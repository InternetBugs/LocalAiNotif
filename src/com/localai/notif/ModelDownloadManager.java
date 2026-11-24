package com.localai.notif;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Pure-Java resumable model downloader shared between UI activities and {@link ModelDownloadService}.
 * Supports ModelScope (default), HF-Mirror, and Hugging Face with HTTP Range resume, size/SHA-256
 * verification, atomic installation into app-private {@code files/models/}, and automatic
 * {@link InferenceProvider} engine reload.
 */
final class ModelDownloadManager {
    private static final String TAG = "LocalAiNotif";
    private static final Uri PROVIDER = Uri.parse("content://" + Config.AUTHORITY);
    private static final String USER_AGENT = "LocalAiNotif/1.0 (Android)";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 8;
    private static final long STORAGE_HEADROOM_BYTES = 200L * 1024L * 1024L;

    enum State {
        IDLE,
        FETCHING_META,
        DOWNLOADING,
        PAUSED,
        VERIFYING,
        FAILED
    }

    static final class Snapshot {
        final String modelId;
        final State state;
        final long downloadedBytes;
        final long totalBytes;
        final int completedFiles;
        final int totalFiles;
        final long speedBytesPerSec;
        final String currentFile;
        final String errorMessage;
        final String source;

        Snapshot(
                String modelId,
                State state,
                long downloadedBytes,
                long totalBytes,
                int completedFiles,
                int totalFiles,
                long speedBytesPerSec,
                String currentFile,
                String errorMessage,
                String source) {
            this.modelId = modelId;
            this.state = state;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.completedFiles = completedFiles;
            this.totalFiles = totalFiles;
            this.speedBytesPerSec = speedBytesPerSec;
            this.currentFile = currentFile;
            this.errorMessage = errorMessage;
            this.source = source;
        }

        int progressPercent() {
            if (totalBytes <= 0L) return 0;
            long pct = (downloadedBytes * 100L) / totalBytes;
            return (int) Math.max(0L, Math.min(100L, pct));
        }

        boolean isActive() {
            return state == State.FETCHING_META
                    || state == State.DOWNLOADING
                    || state == State.VERIFYING;
        }
    }

    interface Listener {
        void onDownloadStateChanged();
    }

    private static final class RemoteFile {
        final String path;
        final long size;
        final String sha256;

        RemoteFile(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static volatile ModelDownloadManager sInstance;

    static ModelDownloadManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ModelDownloadManager.class) {
                if (sInstance == null) {
                    sInstance = new ModelDownloadManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private final Context mAppContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final Map<String, Snapshot> mSnapshots = new HashMap<>();

    private String mActiveModelId;
    private Thread mWorkerThread;
    private volatile boolean mPauseRequested;
    private volatile boolean mCancelRequested;
    private volatile HttpURLConnection mCurrentConn;
    private volatile String mLastCompletionNotice;
    private volatile boolean mImporting;

    boolean isImporting() {
        return mImporting;
    }

    synchronized void importLocalModel(Uri tree) {
        if (mImporting) return;
        mImporting = true;
        notifyListeners();
        new Thread(() -> {
            try {
                String name = LocalModels.importTree(mAppContext, tree);
                mLastCompletionNotice = "已导入 " + name;
            } catch (IOException | RuntimeException error) {
                mLastCompletionNotice = "导入失败：" + error.getLocalizedMessage();
            } finally {
                mImporting = false;
                notifyListeners();
            }
        }, "LocalAiNotif-model-import").start();
    }

    private ModelDownloadManager(Context appContext) {
        mAppContext = appContext;
        new Thread(() -> {
            File root = ModelCatalog.modelsRoot(appContext);
            for (String retired : List.of("qwen3-0.6b-mnn", "qwen3-1.7b-mnn", "qwen3-4b-mnn")) {
                ModelCatalog.deleteRecursively(new File(root, "." + retired + ".downloading"));
            }
        }, "LocalAiNotif-retired-downloads").start();
    }

    void addListener(Listener listener) {
        if (listener != null) {
            mListeners.add(listener);
        }
    }

    void removeListener(Listener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    synchronized Snapshot getSnapshot(String modelId) {
        Snapshot snapshot = mSnapshots.get(modelId);
        if (snapshot != null) return snapshot;
        ModelCatalog.ModelSpec spec = ModelCatalog.findById(modelId);
        if (spec != null && !ModelCatalog.isInstalled(mAppContext, spec)
                && ModelCatalog.hasPartialStaging(mAppContext, spec)) {
            long partial = ModelCatalog.directorySizeBytes(ModelCatalog.stagingDir(mAppContext, spec));
            return new Snapshot(modelId, State.PAUSED, partial, spec.estimatedSizeBytes,
                    0, 0, 0L, null, null, null);
        }
        return new Snapshot(modelId, State.IDLE, 0L, 0L, 0, 0, 0L, null, null, null);
    }

    synchronized Snapshot getActiveSnapshot() {
        if (mActiveModelId == null) return null;
        Snapshot snap = mSnapshots.get(mActiveModelId);
        return (snap != null && snap.isActive()) ? snap : null;
    }

    synchronized String getActiveModelId() {
        return mActiveModelId;
    }

    String consumeLastCompletionNotice() {
        String msg = mLastCompletionNotice;
        mLastCompletionNotice = null;
        return msg;
    }

    boolean isOnUnmeteredWifi() {
        try {
            ConnectivityManager cm = mAppContext.getSystemService(ConnectivityManager.class);
            if (cm == null) return true;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null) return false;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable ignored) {
            return true;
        }
    }

    boolean hasActiveNetwork() {
        try {
            ConnectivityManager cm = mAppContext.getSystemService(ConnectivityManager.class);
            if (cm == null) return true;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Starts or resumes downloading {@code modelId} from {@code source}.
     * Returns null on accepted start, or a user-facing reason if blocked by another active task.
     */
    synchronized String startDownload(String modelId, String source) {
        ModelCatalog.ModelSpec spec = ModelCatalog.findById(modelId);
        if (spec == null) {
            return "未找到该模型配置";
        }
        if (mActiveModelId != null && !mActiveModelId.equals(modelId)) {
            Snapshot other = mSnapshots.get(mActiveModelId);
            if (other != null && other.isActive()) {
                ModelCatalog.ModelSpec otherSpec = ModelCatalog.findById(mActiveModelId);
                String otherName = otherSpec != null ? otherSpec.displayName : mActiveModelId;
                return "正在下载 " + otherName + "，请先暂停或取消当前任务";
            }
        }
        if (mActiveModelId != null && mActiveModelId.equals(modelId)
                && mWorkerThread != null && mWorkerThread.isAlive()) {
            return null;
        }

        mPauseRequested = false;
        mCancelRequested = false;
        mActiveModelId = modelId;
        String resolvedSource = source == null ? Config.DEFAULT_DOWNLOAD_SOURCE : source;
        long existing = ModelCatalog.directorySizeBytes(ModelCatalog.stagingDir(mAppContext, spec));
        mSnapshots.put(modelId, new Snapshot(
                modelId,
                State.FETCHING_META,
                existing,
                spec.estimatedSizeBytes,
                0,
                0,
                0L,
                "正在获取文件清单…",
                null,
                resolvedSource));

        ModelDownloadService.startTracking(mAppContext, modelId);
        notifyListeners();

        mWorkerThread = new Thread(() -> runDownload(spec, resolvedSource),
                "LocalAiNotif-dl-" + modelId);
        mWorkerThread.start();
        return null;
    }

    void pauseDownload(String modelId) {
        HttpURLConnection connToAbort = null;
        synchronized (this) {
            if (modelId != null && !modelId.equals(mActiveModelId)) return;
            if (mActiveModelId == null) return;
            mPauseRequested = true;
            connToAbort = mCurrentConn;
        }
        if (connToAbort != null) {
            abortConnectionAsync(connToAbort);
        }
    }

    void cancelDownload(String modelId) {
        if (modelId == null) return;
        HttpURLConnection connToAbort = null;
        boolean wasActive;
        synchronized (this) {
            wasActive = modelId.equals(mActiveModelId) && mWorkerThread != null && mWorkerThread.isAlive();
            if (wasActive) {
                mCancelRequested = true;
                connToAbort = mCurrentConn;
            } else {
                mSnapshots.put(modelId, new Snapshot(modelId, State.IDLE, 0L, 0L, 0, 0, 0L, null, null, null));
            }
        }
        if (connToAbort != null) {
            abortConnectionAsync(connToAbort);
        }
        if (!wasActive) {
            ModelCatalog.ModelSpec spec = ModelCatalog.findById(modelId);
            if (spec != null) {
                new Thread(() -> {
                    ModelCatalog.deleteRecursively(ModelCatalog.stagingDir(mAppContext, spec));
                    notifyListeners();
                }, "LocalAiNotif-clean").start();
            } else {
                notifyListeners();
            }
        }
    }

    private void abortConnectionAsync(HttpURLConnection conn) {
        new Thread(() -> {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }, "LocalAiNotif-abort").start();
    }

    private void runDownload(ModelCatalog.ModelSpec spec, String source) {
        File modelsRoot = ModelCatalog.modelsRoot(mAppContext);
        File staging = ModelCatalog.stagingDir(mAppContext, spec);
        File target = ModelCatalog.modelDir(mAppContext, spec);
        long lastDownloaded = 0L;
        long totalBytes = spec.estimatedSizeBytes;
        int completedFiles = 0;
        int totalFiles = 0;

        try {
            if (!hasActiveNetwork()) {
                throw new DownloadException("当前无网络连接，请检查网络后重试");
            }
            if (!modelsRoot.exists() && !modelsRoot.mkdirs()) {
                throw new DownloadException("无法创建本地模型目录");
            }
            if (!staging.exists() && !staging.mkdirs()) {
                throw new DownloadException("无法创建下载缓存目录");
            }
            ensureIncompleteMarker(staging);

            List<RemoteFile> files = fetchFileTree(spec, source);
            if (files.isEmpty()) {
                throw new DownloadException("未从下载源获取到有效模型文件，可切换下载源重试");
            }

            boolean hasConfig = false;
            boolean hasMnn = false;
            long computedTotal = 0L;
            long alreadyDone = 0L;
            totalFiles = files.size();
            for (RemoteFile rf : files) {
                if ("config.json".equals(rf.path)) hasConfig = true;
                if (rf.path.endsWith(".mnn")) hasMnn = true;
                if (rf.size > 0L) computedTotal += rf.size;
                File doneFile = new File(staging, rf.path);
                File partFile = new File(staging, rf.path + ".part");
                if (doneFile.isFile() && (rf.size <= 0L || doneFile.length() == rf.size)) {
                    alreadyDone += doneFile.length();
                    completedFiles++;
                } else if (partFile.isFile()) {
                    alreadyDone += partFile.length();
                }
            }
            if (!hasConfig || !hasMnn) {
                throw new DownloadException("下载源目录缺少 MNN 模型核心文件，请切换下载源");
            }
            totalBytes = computedTotal > 0L ? computedTotal : spec.estimatedSizeBytes;
            lastDownloaded = alreadyDone;

            long remaining = Math.max(0L, totalBytes - alreadyDone);
            long avail = ModelCatalog.availableStorageBytes(mAppContext);
            if (avail >= 0L && avail < remaining + STORAGE_HEADROOM_BYTES) {
                long need = remaining + STORAGE_HEADROOM_BYTES;
                throw new DownloadException("存储空间不足，至少需要保留 " + ModelCatalog.formatBytes(need));
            }

            updateSnapshot(new Snapshot(
                    spec.id, State.DOWNLOADING, alreadyDone, totalBytes,
                    completedFiles, totalFiles, 0L, null, null, source));

            long downloadedSoFar = 0L;
            completedFiles = 0;
            for (RemoteFile rf : files) {
                File finalFile = new File(staging, rf.path);
                if (finalFile.isFile() && (rf.size <= 0L || finalFile.length() == rf.size)) {
                    downloadedSoFar += finalFile.length();
                    completedFiles++;
                }
            }

            long[] speedAnchorTime = {System.nanoTime()};
            long[] speedAnchorBytes = {downloadedSoFar};
            long[] smoothedSpeed = {0L};
            long[] lastPublishMs = {0L};

            for (RemoteFile rf : files) {
                checkInterrupted();
                File finalFile = new File(staging, rf.path);
                if (finalFile.isFile() && (rf.size <= 0L || finalFile.length() == rf.size)) {
                    continue;
                }
                File parent = finalFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new DownloadException("无法创建子目录：" + rf.path);
                }

                long fileBaseCompleted = downloadedSoFar;
                int filesDoneNow = completedFiles;
                long expectedTotal = totalBytes;
                int totalCount = totalFiles;

                File partFile = new File(staging, rf.path + ".part");
                long fileBytes = downloadSingleFile(
                        spec, source, rf, partFile,
                        currentPartBytes -> {
                            long totalNow = fileBaseCompleted + currentPartBytes;
                            long nowMs = android.os.SystemClock.uptimeMillis();
                            if (nowMs - lastPublishMs[0] >= 350L) {
                                long nowNs = System.nanoTime();
                                long dtNs = nowNs - speedAnchorTime[0];
                                if (dtNs >= 400_000_000L) {
                                    long deltaB = Math.max(0L, totalNow - speedAnchorBytes[0]);
                                    long inst = (deltaB * 1_000_000_000L) / dtNs;
                                    smoothedSpeed[0] = smoothedSpeed[0] == 0L
                                            ? inst
                                            : (smoothedSpeed[0] * 6L + inst * 4L) / 10L;
                                    speedAnchorTime[0] = nowNs;
                                    speedAnchorBytes[0] = totalNow;
                                }
                                lastPublishMs[0] = nowMs;
                                updateSnapshot(new Snapshot(
                                        spec.id, State.DOWNLOADING, totalNow, expectedTotal,
                                        filesDoneNow, totalCount, smoothedSpeed[0],
                                        rf.path, null, source));
                            }
                        });

                if (rf.size > 0L && fileBytes != rf.size) {
                    throw new DownloadException("文件大小校验失败（" + rf.path + "），请重试");
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    throw new DownloadException("无法覆盖缓存文件：" + rf.path);
                }
                if (!partFile.renameTo(finalFile)) {
                    throw new DownloadException("保存文件失败：" + rf.path);
                }

                downloadedSoFar += fileBytes;
                lastDownloaded = downloadedSoFar;
                completedFiles++;
                updateSnapshot(new Snapshot(
                        spec.id, State.DOWNLOADING, downloadedSoFar, totalBytes,
                        completedFiles, totalFiles, smoothedSpeed[0], rf.path, null, source));
            }

            checkInterrupted();
            updateSnapshot(new Snapshot(
                    spec.id, State.VERIFYING, totalBytes, totalBytes,
                    totalFiles, totalFiles, 0L, "正在校验模型文件完整性…", null, source));

            for (RemoteFile rf : files) {
                checkInterrupted();
                File f = new File(staging, rf.path);
                if (!f.isFile() || (rf.size > 0L && f.length() != rf.size)) {
                    throw new DownloadException("模型文件不完整，请重试下载");
                }
                if (!rf.sha256.isEmpty() && rf.sha256.length() == 64) {
                    String actual = sha256Hex(f);
                    if (!rf.sha256.equals(actual)) {
                        f.delete();
                        throw new DownloadException("文件校验未通过（" + rf.path + "），请重新下载");
                    }
                }
            }

            new File(staging, ModelCatalog.MARKER_INCOMPLETE).delete();
            if (!ModelCatalog.isModelDirValid(staging)) {
                throw new DownloadException("下载目录缺少有效的 config.json 或模型图文件");
            }

            // Atomically replace target directory
            if (target.exists()) {
                // Ensure inference process releases handles if replacing active model
                callResetEngineIfNeeded(target);
                ModelCatalog.deleteRecursively(target);
            }
            if (!staging.renameTo(target)) {
                throw new DownloadException("安装模型到私有目录失败，请重试");
            }

            boolean autoActivated = activateIfNoReadyModel(target);
            String notice = autoActivated
                    ? spec.displayName + " 下载完成并已自动启用"
                    : spec.displayName + " 下载完成，可随时切换启用";
            mLastCompletionNotice = notice;

            synchronized (this) {
                mSnapshots.put(spec.id, new Snapshot(
                        spec.id, State.IDLE, 0L, 0L, 0, 0, 0L, null, null, source));
                if (spec.id.equals(mActiveModelId)) {
                    mActiveModelId = null;
                }
            }
            notifyListeners();
            ModelDownloadService.notifyCompleted(mAppContext, spec.displayName, autoActivated);
        } catch (PausedSignal paused) {
            long currentPartial = Math.max(lastDownloaded, ModelCatalog.directorySizeBytes(staging));
            synchronized (this) {
                mSnapshots.put(spec.id, new Snapshot(
                        spec.id, State.PAUSED, currentPartial, totalBytes,
                        completedFiles, totalFiles, 0L, null, null, source));
                if (spec.id.equals(mActiveModelId)) {
                    mActiveModelId = null;
                }
            }
            notifyListeners();
            ModelDownloadService.stopTracking(mAppContext);
        } catch (CancelledSignal cancelled) {
            ModelCatalog.deleteRecursively(staging);
            synchronized (this) {
                mSnapshots.put(spec.id, new Snapshot(
                        spec.id, State.IDLE, 0L, 0L, 0, 0, 0L, null, null, source));
                if (spec.id.equals(mActiveModelId)) {
                    mActiveModelId = null;
                }
            }
            notifyListeners();
            ModelDownloadService.stopTracking(mAppContext);
        } catch (Throwable t) {
            String msg = (t instanceof DownloadException)
                    ? t.getMessage()
                    : "连接下载源失败，请重试或切换下载源";
            Log.e(TAG, "Model download failed (" + spec.id + "): " + t);
            long currentPartial = Math.max(lastDownloaded, ModelCatalog.directorySizeBytes(staging));
            synchronized (this) {
                mSnapshots.put(spec.id, new Snapshot(
                        spec.id, State.FAILED, currentPartial, totalBytes,
                        completedFiles, totalFiles, 0L, null, msg, source));
                if (spec.id.equals(mActiveModelId)) {
                    mActiveModelId = null;
                }
            }
            notifyListeners();
            ModelDownloadService.notifyFailed(mAppContext, spec.displayName, msg);
        } finally {
            mCurrentConn = null;
        }
    }

    private interface ProgressCallback {
        void onBytes(long currentPartBytes);
    }

    private long downloadSingleFile(
            ModelCatalog.ModelSpec spec,
            String source,
            RemoteFile rf,
            File partFile,
            ProgressCallback progressCallback) throws Exception {
        long existing = partFile.isFile() ? partFile.length() : 0L;
        if (rf.size > 0L && existing == rf.size) {
            progressCallback.onBytes(existing);
            return existing;
        }
        if (rf.size > 0L && existing > rf.size) {
            partFile.delete();
            existing = 0L;
        }

        String url = Config.DOWNLOAD_SOURCE_MODELSCOPE.equals(source)
                ? ModelCatalog.modelScopeFileUrl(spec, rf.path)
                : ModelCatalog.huggingFaceFileUrl(source, spec, rf.path);

        boolean useRange = existing > 0L && (rf.size < 0L || rf.size > 64L * 1024L);
        if (!useRange && existing > 0L) {
            partFile.delete();
            existing = 0L;
        }

        HttpURLConnection conn = openWithRedirects(url, useRange ? existing : -1L);
        mCurrentConn = conn;
        try {
            int code = conn.getResponseCode();
            if (code == 416 && rf.size > 0L && existing >= rf.size) {
                progressCallback.onBytes(existing);
                return existing;
            }
            boolean append;
            String contentRange = conn.getHeaderField("Content-Range");
            if (code == HttpURLConnection.HTTP_PARTIAL && useRange) {
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK
                    && useRange
                    && contentRange != null
                    && contentRange.startsWith("bytes " + existing + "-")) {
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK && contentRange == null) {
                append = false;
                existing = 0L;
            } else if (code == HttpURLConnection.HTTP_OK && useRange) {
                // Unexpected partial Content-Range offset; restart cleanly without Range header.
                conn.disconnect();
                partFile.delete();
                existing = 0L;
                conn = openWithRedirects(url, -1L);
                mCurrentConn = conn;
                code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new DownloadException("下载源响应异常（HTTP " + code + "），可稍后重试或切换镜像");
                }
                append = false;
            } else {
                throw new DownloadException("下载源响应异常（HTTP " + code + "），可稍后重试或切换镜像");
            }

            long written = existing;
            progressCallback.onBytes(written);
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                 FileOutputStream out = new FileOutputStream(partFile, append)) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    checkInterrupted();
                    out.write(buf, 0, read);
                    written += read;
                    progressCallback.onBytes(written);
                }
                out.flush();
            }
            checkInterrupted();
            return written;
        } catch (IOException e) {
            checkInterrupted();
            throw e;
        } finally {
            mCurrentConn = null;
            conn.disconnect();
        }
    }

    private List<RemoteFile> fetchFileTree(ModelCatalog.ModelSpec spec, String source) throws Exception {
        if (Config.DOWNLOAD_SOURCE_MODELSCOPE.equals(source)) {
            return fetchModelScopeTree(spec);
        }
        List<RemoteFile> out = new ArrayList<>();
        fetchHuggingFaceTree(source, spec, "", out);
        return out;
    }

    private List<RemoteFile> fetchModelScopeTree(ModelCatalog.ModelSpec spec) throws Exception {
        String body = httpGetString(ModelCatalog.modelScopeTreeUrl(spec));
        JSONObject root = new JSONObject(body);
        if (!root.optBoolean("Success", false)) {
            throw new DownloadException("魔搭社区返回失败，可尝试切换 HF-Mirror");
        }
        JSONObject data = root.optJSONObject("Data");
        JSONArray files = data != null ? data.optJSONArray("Files") : null;
        if (files == null) {
            throw new DownloadException("魔搭社区未返回文件列表");
        }
        List<RemoteFile> result = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) continue;
            if (!"blob".equals(item.optString("Type", ""))) continue;
            String path = item.optString("Path", "");
            if (path.startsWith("./")) path = path.substring(2);
            if (!ModelCatalog.shouldDownloadFile(path)) continue;
            long size = item.optLong("Size", -1L);
            String sha = item.optString("Sha256", "");
            result.add(new RemoteFile(path, size, sha));
        }
        return result;
    }

    private void fetchHuggingFaceTree(
            String source, ModelCatalog.ModelSpec spec, String subDir, List<RemoteFile> out)
            throws Exception {
        checkInterrupted();
        String body = httpGetString(ModelCatalog.huggingFaceTreeUrl(source, spec, subDir));
        JSONArray array = new JSONArray(body);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "");
            String path = item.optString("path", "");
            if ("directory".equals(type) && !path.isEmpty() && !path.startsWith(".")) {
                fetchHuggingFaceTree(source, spec, path, out);
                continue;
            }
            if (!"file".equals(type) || !ModelCatalog.shouldDownloadFile(path)) continue;
            JSONObject lfs = item.optJSONObject("lfs");
            long size = lfs != null ? lfs.optLong("size", item.optLong("size", -1L))
                    : item.optLong("size", -1L);
            String sha = lfs != null ? lfs.optString("oid", "") : "";
            out.add(new RemoteFile(path, size, sha));
        }
    }

    private String httpGetString(String url) throws Exception {
        HttpURLConnection conn = openWithRedirects(url, -1L);
        mCurrentConn = conn;
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new DownloadException("获取模型信息失败（HTTP " + code + "），可切换下载源重试");
            }
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    checkInterrupted();
                    out.write(buf, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (IOException e) {
            checkInterrupted();
            throw e;
        } finally {
            mCurrentConn = null;
            conn.disconnect();
        }
    }

    private HttpURLConnection openWithRedirects(String initialUrl, long rangeStart) throws Exception {
        String currentUrl = initialUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkInterrupted();
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (rangeStart > 0L) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }
            mCurrentConn = conn;
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new DownloadException("下载链接重定向异常");
                }
                currentUrl = new URL(url, location).toString();
                continue;
            }
            return conn;
        }
        throw new DownloadException("下载重定向次数过多");
    }

    private void ensureIncompleteMarker(File staging) {
        File marker = new File(staging, ModelCatalog.MARKER_INCOMPLETE);
        if (!marker.exists()) {
            try {
                marker.createNewFile();
            } catch (IOException ignored) {
            }
        }
    }

    private String sha256Hex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 256 * 1024)) {
            byte[] buf = new byte[256 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                checkInterrupted();
                md.update(buf, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private void callResetEngineIfNeeded(File targetDir) {
        try {
            Bundle cfg = mAppContext.getContentResolver().call(PROVIDER, "get_config", null, null);
            if (cfg == null) return;
            String activePath = cfg.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH);
            if (ModelCatalog.canonicalOrAbsolute(new File(activePath))
                    .equals(ModelCatalog.canonicalOrAbsolute(targetDir))) {
                mAppContext.getContentResolver().call(PROVIDER, Config.METHOD_RESET_ENGINE, null, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean activateIfNoReadyModel(File targetDir) {
        try {
            Bundle cfg = mAppContext.getContentResolver().call(PROVIDER, "get_config", null, null);
            boolean alreadyReady = cfg != null && cfg.getBoolean(Config.KEY_MODEL_READY, false);
            String activePath = cfg != null
                    ? cfg.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH)
                    : Config.DEFAULT_MODEL_PATH;
            boolean sameAsActive = ModelCatalog.canonicalOrAbsolute(new File(activePath))
                    .equals(ModelCatalog.canonicalOrAbsolute(targetDir));
            if (!alreadyReady || sameAsActive) {
                Bundle input = new Bundle();
                input.putString(Config.KEY_MODEL_PATH, targetDir.getAbsolutePath());
                Bundle res = mAppContext.getContentResolver().call(
                        PROVIDER, Config.METHOD_SET_MODEL_PATH, null, input);
                return res != null && "0".equals(res.getString("status"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "activateIfNoReadyModel: " + t);
        }
        return false;
    }

    private void checkInterrupted() {
        if (mCancelRequested) throw new CancelledSignal();
        if (mPauseRequested) throw new PausedSignal();
    }

    private void updateSnapshot(Snapshot snapshot) {
        synchronized (this) {
            mSnapshots.put(snapshot.modelId, snapshot);
        }
        notifyListeners();
    }

    private void notifyListeners() {
        mMainHandler.post(() -> {
            for (Listener listener : mListeners) {
                listener.onDownloadStateChanged();
            }
        });
    }

    private static final class PausedSignal extends RuntimeException {}

    private static final class CancelledSignal extends RuntimeException {}

    private static final class DownloadException extends Exception {
        DownloadException(String message) {
            super(message);
        }
    }
}
