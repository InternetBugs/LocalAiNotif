package com.localai.notif;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Module-app-side inference endpoint (design §2). Runs in its own process
 *   summarize       -> single-notification summaries (status/outputtext_list/errorcode_list)
 *   summarize_group -> group_summary, plus item results by default
 *   reset_engine    -> drop the MNN engine so it reloads (after a model push)
 *   classify        -> Pass1 batch classification (status/localai_class_list/localai_vip_list)
 *
 * Engine: MNN-LLM (Qwen3-1.7B-int4) via {@link LlmEngine}.
 * Summaries can fall back to extractive text. Classification requires a model result.
 */
public final class InferenceProvider extends ContentProvider {
    private static final String TAG = "LocalAiNotif";
    private static final String METHOD_GET_CONFIG = "get_config";
    private static final String METHOD_SUMMARIZE = "summarize";
    private static final String METHOD_SUMMARIZE_GROUP = "summarize_group";
    private static final String METHOD_RESET_ENGINE = "reset_engine";
    private static final String METHOD_CLASSIFY = "classify";

    private static final String IN_MESSAGES = "messageList";
    private static final String IN_TITLES = "titles";
    private static final String IN_CATEGORY = "category";
    private static final String IN_PACKAGE = "package";
    private static final String OUT_STATUS = "status";
    private static final String OUT_TEXTS = "outputtext_list";
    static final String OUT_ERRORS = "errorcode_list";
    private static final String OUT_GROUP = "group_summary";
    private static final String IN_PACKAGES = "packages";
    private static final String IN_CHANNELS = "channels";
    private static final String STATUS_OK = "0";
    private static final String STATUS_ERROR = "1";

    private static final String SYSTEM_PROMPT =
            "你是安卓手机的通知摘要引擎,只输出摘要文本本身,不要任何前缀、引号或解释。";
    private static final String NO_THINK = "\n/no_think";
    static final long IDLE_UNLOAD_DELAY_MS = 60_000L;

    private static volatile InferenceProvider sInstance;

    private final Object mStateLock = new Object();
    private final Object mLoadLock = new Object();
    private final ExecutorService mLifecycleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LocalAiNotif-engine-lifecycle");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService mIdleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LocalAiNotif-engine-idle");
        t.setDaemon(true);
        return t;
    });

    private LlmEngine mEngine;
    private String mLoadedModelPath;
    private String mFailedModelPath;
    private int mActiveInferences;
    private boolean mEngineLoading;
    private boolean mPendingUnload;
    private long mEngineGeneration;
    private ScheduledFuture<?> mIdleUnloadTask;
    private volatile boolean mAlwaysLoadModel = Config.DEFAULT_ALWAYS_LOAD_MODEL;

    @Override
    public boolean onCreate() {
        sInstance = this;
        Context context = getContext();
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(
                    Config.PREFS_NAME, Context.MODE_PRIVATE);
            mAlwaysLoadModel = prefs.getBoolean(
                    Config.KEY_ALWAYS_LOAD_MODEL, Config.DEFAULT_ALWAYS_LOAD_MODEL);
            if (mAlwaysLoadModel) {
                schedulePreloadAsync();
            }
        }
        return true;
    }

    static void triggerPreloadIfEnabled() {
        InferenceProvider instance = sInstance;
        if (instance != null && instance.isAlwaysLoadEnabled()) {
            instance.schedulePreloadAsync();
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case METHOD_GET_CONFIG:
                return getConfig();
            case Config.METHOD_SET_PRIORITY_ENABLED:
                return setPriorityEnabled(extras);
            case Config.METHOD_SET_CONFIG:
                return setConfig(extras);
            case Config.METHOD_SYNC_REDUCE_STATE:
                return syncReduceState(extras);
            case Config.METHOD_SET_MODEL_PATH:
                return setModelPath(extras);
            case METHOD_SUMMARIZE:
                return summarize(extras, false);
            case METHOD_SUMMARIZE_GROUP:
                return summarize(extras, true);
            case METHOD_CLASSIFY:
                return classify(extras);
            case METHOD_RESET_ENGINE:
                return resetEngine(extras);
            default:
                return null;
        }
    }

    private Bundle getConfig() {
        Context context = getContext();
        SharedPreferences prefs = context.getSharedPreferences(
                Config.PREFS_NAME, Context.MODE_PRIVATE);
        String modelPath = prefs.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH);
        boolean alwaysLoad = prefs.getBoolean(
                Config.KEY_ALWAYS_LOAD_MODEL, Config.DEFAULT_ALWAYS_LOAD_MODEL);
        mAlwaysLoadModel = alwaysLoad;
        Bundle out = new Bundle();
        out.putBoolean(Config.KEY_SUMMARIZE_SCREEN_ON,
                prefs.getBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, Config.DEFAULT_SUMMARIZE_SCREEN_ON));
        out.putInt(Config.KEY_NOTIFICATION_THRESHOLD,
                prefs.getInt(Config.KEY_NOTIFICATION_THRESHOLD, Config.DEFAULT_NOTIFICATION_THRESHOLD));
        out.putInt(Config.KEY_WAIT_SECONDS,
                prefs.getInt(Config.KEY_WAIT_SECONDS, Config.DEFAULT_WAIT_SECONDS));
        out.putBoolean(Config.KEY_ALL_APPS, true);
        out.putInt(Config.KEY_MIN_LENGTH,
                prefs.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH));
        out.putBoolean(Config.KEY_GROUP_SUMMARY,
                prefs.getBoolean(Config.KEY_GROUP_SUMMARY, Config.DEFAULT_GROUP_SUMMARY));
        out.putBoolean(Config.KEY_CLASSIFY_ENABLED, prefs.getBoolean(
                Config.KEY_CLASSIFY_ENABLED, Config.DEFAULT_CLASSIFY_ENABLED));
        out.putBoolean(Config.KEY_PRIORITY_ENABLED, prefs.getBoolean(
                Config.KEY_PRIORITY_ENABLED, Config.DEFAULT_PRIORITY_ENABLED));
        out.putBoolean(Config.KEY_REDUCE_ENABLED, prefs.getBoolean(
                Config.KEY_REDUCE_ENABLED, Config.DEFAULT_REDUCE_ENABLED));
        out.putBoolean(Config.KEY_REDUCE_LOCK_SCREEN_ONLY, prefs.getBoolean(
                Config.KEY_REDUCE_LOCK_SCREEN_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY));
        out.putString(Config.KEY_MODEL_PATH, modelPath);
        out.putString(Config.KEY_DOWNLOAD_SOURCE, sanitizeDownloadSource(
                prefs.getString(Config.KEY_DOWNLOAD_SOURCE, Config.DEFAULT_DOWNLOAD_SOURCE)));
        out.putBoolean(Config.KEY_WIFI_ONLY_DOWNLOAD, prefs.getBoolean(
                Config.KEY_WIFI_ONLY_DOWNLOAD, Config.DEFAULT_WIFI_ONLY_DOWNLOAD));
        out.putBoolean(Config.KEY_ALWAYS_LOAD_MODEL, alwaysLoad);
        out.putBoolean(Config.KEY_MODEL_READY, ModelCatalog.isModelDirValid(new File(modelPath)));
        synchronized (mStateLock) {
            out.putBoolean(Config.KEY_ENGINE_LOADED, mEngine != null);
            out.putBoolean(Config.KEY_ENGINE_LOADING, mEngineLoading);
        }
        return out;
    }

    private Bundle setPriorityEnabled(Bundle input) {
        requireModuleCaller("Only the module can change priority settings");
        if (input == null || !input.containsKey(Config.KEY_PRIORITY_ENABLED)) {
            return error("missing priority setting");
        }
        Context context = getContext();
        SharedPreferences prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.edit().putBoolean(Config.KEY_PRIORITY_ENABLED,
                input.getBoolean(Config.KEY_PRIORITY_ENABLED)).commit()) {
            return error("could not save priority setting");
        }
        context.sendBroadcast(new Intent(Config.ACTION_CONFIG_CHANGED).setPackage("android"));
        Bundle out = getConfig();
        out.putString(OUT_STATUS, STATUS_OK);
        return out;
    }

    private Bundle setConfig(Bundle input) {
        requireModuleCaller("Only the module can change settings");
        if (input == null || input.isEmpty()) {
            return error("missing settings");
        }
        Context context = getContext();
        SharedPreferences prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        boolean notifySystem = false;
        boolean alwaysLoadChanged = false;
        boolean newAlwaysLoad = mAlwaysLoadModel;

        if (input.containsKey(Config.KEY_PRIORITY_ENABLED)) {
            editor.putBoolean(Config.KEY_PRIORITY_ENABLED, input.getBoolean(Config.KEY_PRIORITY_ENABLED));
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_REDUCE_ENABLED)) {
            editor.putBoolean(Config.KEY_REDUCE_ENABLED, input.getBoolean(Config.KEY_REDUCE_ENABLED));
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_REDUCE_LOCK_SCREEN_ONLY)) {
            editor.putBoolean(Config.KEY_REDUCE_LOCK_SCREEN_ONLY,
                    input.getBoolean(Config.KEY_REDUCE_LOCK_SCREEN_ONLY));
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_GROUP_SUMMARY)) {
            editor.putBoolean(Config.KEY_GROUP_SUMMARY, input.getBoolean(Config.KEY_GROUP_SUMMARY));
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_SUMMARIZE_SCREEN_ON)) {
            editor.putBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, input.getBoolean(Config.KEY_SUMMARIZE_SCREEN_ON));
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_CLASSIFY_ENABLED)) {
            editor.putBoolean(Config.KEY_CLASSIFY_ENABLED, input.getBoolean(Config.KEY_CLASSIFY_ENABLED));
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_MIN_LENGTH)) {
            int minLength = Math.max(0, Math.min(200, input.getInt(Config.KEY_MIN_LENGTH)));
            editor.putInt(Config.KEY_MIN_LENGTH, minLength);
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_NOTIFICATION_THRESHOLD)) {
            int threshold = Math.max(1, Math.min(20, input.getInt(Config.KEY_NOTIFICATION_THRESHOLD)));
            editor.putInt(Config.KEY_NOTIFICATION_THRESHOLD, threshold);
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_WAIT_SECONDS)) {
            int waitSeconds = Math.max(0, Math.min(300, input.getInt(Config.KEY_WAIT_SECONDS)));
            editor.putInt(Config.KEY_WAIT_SECONDS, waitSeconds);
            changed = true;
            notifySystem = true;
        }
        if (input.containsKey(Config.KEY_DOWNLOAD_SOURCE)) {
            editor.putString(Config.KEY_DOWNLOAD_SOURCE,
                    sanitizeDownloadSource(input.getString(Config.KEY_DOWNLOAD_SOURCE)));
            changed = true;
        }
        if (input.containsKey(Config.KEY_WIFI_ONLY_DOWNLOAD)) {
            editor.putBoolean(Config.KEY_WIFI_ONLY_DOWNLOAD,
                    input.getBoolean(Config.KEY_WIFI_ONLY_DOWNLOAD));
            changed = true;
        }
        if (input.containsKey(Config.KEY_ALWAYS_LOAD_MODEL)) {
            newAlwaysLoad = input.getBoolean(Config.KEY_ALWAYS_LOAD_MODEL);
            editor.putBoolean(Config.KEY_ALWAYS_LOAD_MODEL, newAlwaysLoad);
            changed = true;
            notifySystem = true;
            alwaysLoadChanged = true;
        }

        if (!changed) {
            return error("no recognized settings");
        }
        if (!editor.commit()) {
            return error("could not save settings");
        }
        if (alwaysLoadChanged) {
            mAlwaysLoadModel = newAlwaysLoad;
            if (newAlwaysLoad) {
                synchronized (mStateLock) {
                    cancelIdleUnloadLocked();
                    mPendingUnload = false;
                    mFailedModelPath = null;
                }
                schedulePreloadAsync();
            } else {
                requestImmediateUnloadAsync();
            }
        }
        if (notifySystem) {
            context.sendBroadcast(new Intent(Config.ACTION_CONFIG_CHANGED).setPackage("android"));
        }
        if (input.containsKey(Config.KEY_REDUCE_ENABLED)
                || input.containsKey(Config.KEY_REDUCE_LOCK_SCREEN_ONLY)) {
            requestTileRefresh(context);
        }
        Bundle out = getConfig();
        out.putString(OUT_STATUS, STATUS_OK);
        return out;
    }

    /**
     * Allows system_server to mirror external ZenMode deactivation or strict-DND switch
     * back into module preferences without bouncing ACTION_CONFIG_CHANGED back to system_server.
     */
    private Bundle syncReduceState(Bundle input) {
        int caller = Binder.getCallingUid();
        if (caller != Process.myUid() && caller != Process.SYSTEM_UID) {
            throw new SecurityException("Only the module or system_server can sync reduce state");
        }
        if (input == null || !input.containsKey(Config.KEY_REDUCE_ENABLED)) {
            return error("missing reduce state");
        }
        Context context = getContext();
        SharedPreferences prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        boolean active = input.getBoolean(Config.KEY_REDUCE_ENABLED);
        if (!prefs.edit().putBoolean(Config.KEY_REDUCE_ENABLED, active).commit()) {
            return error("could not sync reduce state");
        }
        requestTileRefresh(context);
        Bundle out = getConfig();
        out.putString(OUT_STATUS, STATUS_OK);
        return out;
    }

    private static void requestTileRefresh(Context context) {
        try {
            TileService.requestListeningState(
                    context, new ComponentName(context, ReduceInterruptionsTileService.class));
        } catch (Throwable ignored) {
        }
    }

    private Bundle setModelPath(Bundle input) {
        requireModuleCaller("Only the module can change model path");
        if (input == null) {
            return error("missing model path");
        }
        String rawPath = input.getString(Config.KEY_MODEL_PATH);
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return error("empty model path");
        }
        Context context = getContext();
        String validatedPath;
        try {
            File root = ModelCatalog.modelsRoot(context).getCanonicalFile();
            File target = new File(rawPath.trim()).getCanonicalFile();
            String rootPrefix = root.getPath() + File.separator;
            boolean insideModelsRoot = target.getPath().startsWith(rootPrefix);
            boolean isDefaultPath = new File(Config.DEFAULT_MODEL_PATH).getCanonicalFile().equals(target);
            if (!insideModelsRoot && !isDefaultPath) {
                return error("model path outside private models directory");
            }
            validatedPath = target.getPath();
        } catch (Exception e) {
            return error("invalid model path");
        }

        SharedPreferences prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.edit().putString(Config.KEY_MODEL_PATH, validatedPath).commit()) {
            return error("could not save model path");
        }
        resetEngineSync();
        if (isAlwaysLoadEnabled()) {
            schedulePreloadAsync();
        }
        context.sendBroadcast(new Intent(Config.ACTION_CONFIG_CHANGED).setPackage("android"));
        Bundle out = getConfig();
        out.putString(OUT_STATUS, STATUS_OK);
        return out;
    }

    private void requireModuleCaller(String message) {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException(message);
        }
    }

    private static String sanitizeDownloadSource(String source) {
        if (Config.DOWNLOAD_SOURCE_HF_MIRROR.equals(source)
                || Config.DOWNLOAD_SOURCE_HUGGINGFACE.equals(source)
                || Config.DOWNLOAD_SOURCE_MODELSCOPE.equals(source)) {
            return source;
        }
        return Config.DEFAULT_DOWNLOAD_SOURCE;
    }

    private boolean isAlwaysLoadEnabled() {
        return mAlwaysLoadModel;
    }

    private String readConfiguredModelPath() {
        Context context = getContext();
        if (context == null) return Config.DEFAULT_MODEL_PATH;
        SharedPreferences prefs = context.getSharedPreferences(
                Config.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH);
    }

    private Bundle summarize(Bundle input, boolean withGroup) {
        if (input == null) return error("no input");
        List<String> messages = input.getStringArrayList(IN_MESSAGES);
        List<String> titles = input.getStringArrayList(IN_TITLES);
        if (messages == null || messages.isEmpty()) return error("no messages");
        if (titles == null || titles.size() != messages.size()) return error("titles mismatch");

        beginInference();
        try {
            LlmEngine engine = ensureEngineForInference();

            Bundle out = new Bundle();
            out.putString(OUT_STATUS, STATUS_OK);
            if (!withGroup || input.getBoolean(Config.KEY_INCLUDE_ITEM_SUMMARIES, true)) {
                ArrayList<String> outputs = new ArrayList<>(messages.size());
                ArrayList<String> errors = new ArrayList<>(messages.size());
                for (int i = 0; i < messages.size(); i++) {
                    String mnn = null;
                    if (engine != null) {
                        try {
                            mnn = mnnSingle(engine, titles.get(i), messages.get(i));
                        } catch (Throwable t) {
                            Log.e(TAG, "mnnSingle: " + t);
                        }
                    }
                    outputs.add(mnn != null ? mnn : summarize(titles.get(i), messages.get(i)));
                    errors.add(STATUS_OK);
                }
                out.putStringArrayList(OUT_TEXTS, outputs);
                out.putStringArrayList(OUT_ERRORS, errors);
            }
            if (withGroup) {
                String mnnGroup = null;
                if (engine != null) {
                    try {
                        mnnGroup = mnnGroup(engine, input.getString(IN_PACKAGE), messages, titles);
                    } catch (Throwable t) {
                        Log.e(TAG, "mnnGroup: " + t);
                    }
                }
                out.putString(OUT_GROUP, mnnGroup != null ? mnnGroup
                        : groupSummary(input.getString(IN_PACKAGE), messages, titles));
            }
            return out;
        } finally {
            endInference();
        }
    }

    /** Pass1 批量分类。缺失或无效的模型输出通过逐项错误码表示。 */
    private Bundle classify(Bundle input) {
        if (input == null) return error("no input");
        List<String> messages = input.getStringArrayList(IN_MESSAGES);
        List<String> titles = input.getStringArrayList(IN_TITLES);
        List<String> packages = input.getStringArrayList(IN_PACKAGES);
        List<String> channels = input.getStringArrayList(IN_CHANNELS); // 可缺失
        if (messages == null || messages.isEmpty()) return error("no messages");
        if (titles == null || titles.size() != messages.size()) return error("titles mismatch");
        if (packages == null || packages.size() != messages.size()) return error("packages mismatch");

        int n = messages.size();
        String[][] predictions;
        beginInference();
        try {
            LlmEngine engine = ensureEngineForInference();
            predictions = engine == null ? null
                    : Classifier.llm(engine, titles, messages, packages, channels);
        } finally {
            endInference();
        }
        ArrayList<String> classes = new ArrayList<>(n);
        ArrayList<String> vips = new ArrayList<>(n);
        ArrayList<String> errors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String[] prediction = predictions == null ? null : predictions[i];
            boolean classified = prediction != null && prediction[0] != null;
            classes.add(classified ? prediction[0] : Classifier.CLASS_NORMAL);
            vips.add(classified ? prediction[1] : "false");
            errors.add(classified ? STATUS_OK : STATUS_ERROR);
            if (classified) {
                Log.i(TAG, "classify " + i + ": class=" + prediction[0] + " vip=" + prediction[1]
                        + " title=" + clipLog(titles.get(i)));
            }
        }
        Bundle out = new Bundle();
        out.putString(OUT_STATUS, STATUS_OK);
        out.putStringArrayList(PriorityNotification.CLASS_LIST, classes);
        out.putStringArrayList(PriorityNotification.VIP_LIST, vips);
        out.putStringArrayList(OUT_ERRORS, errors);
        return out;
    }

    private static String clipLog(String s) {
        if (s == null) return "null";
        String t = s.replace('\n', ' ');
        return t.length() <= 16 ? t : t.substring(0, 16) + "…";
    }

    private void beginInference() {
        synchronized (mStateLock) {
            mActiveInferences++;
            cancelIdleUnloadLocked();
        }
    }

    private void endInference() {
        LlmEngine toClose = null;
        synchronized (mStateLock) {
            if (mActiveInferences > 0) {
                mActiveInferences--;
            }
            if (mActiveInferences == 0) {
                if (mPendingUnload) {
                    mPendingUnload = false;
                    cancelIdleUnloadLocked();
                    toClose = detachEngineLocked();
                } else if (!isAlwaysLoadEnabled() && mEngine != null) {
                    scheduleIdleUnloadLocked(mEngineGeneration);
                } else if (isAlwaysLoadEnabled()) {
                    cancelIdleUnloadLocked();
                }
            }
        }
        if (toClose != null) {
            final LlmEngine closing = toClose;
            notifyEngineStateChanged();
            mLifecycleExecutor.execute(() -> {
                synchronized (mLoadLock) {
                    closeQuietly(closing);
                }
                Log.i(TAG, "Unloaded MNN engine after active inference finished");
                notifyEngineStateChanged();
            });
        }
    }

    /** Loads the MNN engine on demand for an active inference request. */
    private LlmEngine ensureEngineForInference() {
        String modelDir = readConfiguredModelPath();
        synchronized (mStateLock) {
            if (mEngine != null && modelDir.equals(mLoadedModelPath)) {
                return mEngine;
            }
            if (mEngine == null && modelDir.equals(mFailedModelPath)) {
                return null;
            }
        }

        synchronized (mLoadLock) {
            LlmEngine staleEngine = null;
            long loadGen;
            File config;
            synchronized (mStateLock) {
                modelDir = readConfiguredModelPath();
                if (mEngine != null && modelDir.equals(mLoadedModelPath)) {
                    return mEngine;
                }
                if (mEngine != null) {
                    staleEngine = detachEngineLocked();
                }
                if (modelDir.equals(mFailedModelPath)) {
                    return null;
                }
                config = new File(modelDir, "config.json");
                if (!config.isFile()) {
                    Log.w(TAG, "No model config at " + config);
                    return null;
                }
                loadGen = mEngineGeneration;
                mEngineLoading = true;
            }
            if (staleEngine != null) {
                closeQuietly(staleEngine);
            }
            notifyEngineStateChanged();

            LlmEngine created = createEngineSafely(modelDir, config);
            LlmEngine discard = null;
            LlmEngine result = null;
            synchronized (mStateLock) {
                mEngineLoading = false;
                if (created == null) {
                    if (mEngineGeneration == loadGen) {
                        mFailedModelPath = modelDir;
                    }
                } else if (mEngineGeneration != loadGen) {
                    discard = created;
                } else {
                    mEngine = created;
                    mLoadedModelPath = modelDir;
                    mFailedModelPath = null;
                    result = created;
                }
            }
            if (discard != null) {
                closeQuietly(discard);
            }
            notifyEngineStateChanged();
            return result;
        }
    }

    private void schedulePreloadAsync() {
        mLifecycleExecutor.execute(this::preloadEngineIfEnabled);
    }

    private void preloadEngineIfEnabled() {
        if (!isAlwaysLoadEnabled()) return;
        synchronized (mLoadLock) {
            LlmEngine staleEngine = null;
            String modelDir;
            File config;
            long loadGen;
            synchronized (mStateLock) {
                if (!isAlwaysLoadEnabled()) return;
                cancelIdleUnloadLocked();
                mPendingUnload = false;
                modelDir = readConfiguredModelPath();
                if (mEngine != null && modelDir.equals(mLoadedModelPath)) {
                    return;
                }
                if (modelDir.equals(mFailedModelPath)) {
                    return;
                }
                File dir = new File(modelDir);
                config = new File(dir, "config.json");
                if (!ModelCatalog.isModelDirValid(dir) || !config.isFile()) {
                    Log.i(TAG, "Skip preload: no valid model at " + modelDir);
                    return;
                }
                if (mEngine != null) {
                    staleEngine = detachEngineLocked();
                }
                loadGen = mEngineGeneration;
                mEngineLoading = true;
            }
            if (staleEngine != null) {
                closeQuietly(staleEngine);
            }
            notifyEngineStateChanged();

            Log.i(TAG, "Preloading MNN engine (" + modelDir + ")");
            LlmEngine created = createEngineSafely(modelDir, config);
            LlmEngine discard = null;
            synchronized (mStateLock) {
                mEngineLoading = false;
                boolean keep = (mEngineGeneration == loadGen)
                        && !mPendingUnload
                        && (isAlwaysLoadEnabled() || mActiveInferences > 0);
                if (created == null) {
                    if (mEngineGeneration == loadGen) {
                        mFailedModelPath = modelDir;
                    }
                } else if (!keep) {
                    discard = created;
                } else {
                    mEngine = created;
                    mLoadedModelPath = modelDir;
                    mFailedModelPath = null;
                    if (!isAlwaysLoadEnabled() && mActiveInferences == 0) {
                        scheduleIdleUnloadLocked(mEngineGeneration);
                    }
                }
            }
            if (discard != null) {
                closeQuietly(discard);
                Log.i(TAG, "Discarded preloaded MNN engine after state change");
            }
            notifyEngineStateChanged();
        }
    }

    private void requestImmediateUnloadAsync() {
        LlmEngine toClose = null;
        synchronized (mStateLock) {
            cancelIdleUnloadLocked();
            if (mActiveInferences > 0) {
                mPendingUnload = true;
                Log.i(TAG, "Always-load disabled during inference; will unload on completion");
            } else {
                mPendingUnload = false;
                mEngineGeneration++;
                toClose = detachEngineLocked();
            }
        }
        if (toClose != null) {
            final LlmEngine closing = toClose;
            notifyEngineStateChanged();
            mLifecycleExecutor.execute(() -> {
                synchronized (mLoadLock) {
                    closeQuietly(closing);
                }
                Log.i(TAG, "Unloaded MNN engine after always-load was disabled");
                notifyEngineStateChanged();
            });
        } else {
            notifyEngineStateChanged();
        }
    }

    private void scheduleIdleUnloadLocked(long expectedGen) {
        cancelIdleUnloadLocked();
        mIdleUnloadTask = mIdleScheduler.schedule(
                () -> onIdleUnloadTimeout(expectedGen),
                IDLE_UNLOAD_DELAY_MS,
                TimeUnit.MILLISECONDS);
    }

    private void cancelIdleUnloadLocked() {
        if (mIdleUnloadTask != null) {
            mIdleUnloadTask.cancel(false);
            mIdleUnloadTask = null;
        }
    }

    private void onIdleUnloadTimeout(long expectedGen) {
        LlmEngine toClose;
        synchronized (mStateLock) {
            mIdleUnloadTask = null;
            if (isAlwaysLoadEnabled()
                    || mActiveInferences > 0
                    || mEngine == null
                    || mEngineGeneration != expectedGen) {
                return;
            }
            mEngineGeneration++;
            toClose = detachEngineLocked();
        }
        notifyEngineStateChanged();
        final LlmEngine closing = toClose;
        mLifecycleExecutor.execute(() -> {
            synchronized (mLoadLock) {
                closeQuietly(closing);
            }
            Log.i(TAG, "Idle timeout (60s) reached; unloaded MNN engine");
            notifyEngineStateChanged();
        });
    }

    private LlmEngine detachEngineLocked() {
        LlmEngine old = mEngine;
        mEngine = null;
        mLoadedModelPath = null;
        return old;
    }

    private LlmEngine createEngineSafely(String modelDir, File config) {
        try {
            LlmEngine engine = LlmEngine.create(config.getAbsolutePath());
            Log.i(TAG, "MNN engine " + (engine != null ? "ready" : "create failed")
                    + " (" + modelDir + ")");
            return engine;
        } catch (Throwable t) {
            Log.e(TAG, "engine create: " + t);
            return null;
        }
    }

    private static void closeQuietly(LlmEngine engine) {
        if (engine == null) return;
        try {
            engine.close();
        } catch (Throwable ignored) {
        }
    }

    private void notifyEngineStateChanged() {
        Context context = getContext();
        if (context == null) return;
        try {
            context.sendBroadcast(new Intent(Config.ACTION_ENGINE_STATE_CHANGED)
                    .setPackage(context.getPackageName()));
        } catch (Throwable ignored) {
        }
    }

    private Bundle resetEngine(Bundle extras) {
        int caller = Binder.getCallingUid();
        if (caller != Process.myUid()
                && caller != Process.SHELL_UID
                && caller != Process.ROOT_UID) {
            throw new SecurityException("Only the module or shell can reset engine");
        }
        boolean preload = extras != null && extras.getBoolean(Config.KEY_RESET_PRELOAD, false);
        resetEngineSync();
        boolean modelReady = ModelCatalog.isModelDirValid(new File(readConfiguredModelPath()));
        if (preload && isAlwaysLoadEnabled() && modelReady) {
            schedulePreloadAsync();
        } else if (isAlwaysLoadEnabled() && !modelReady) {
            Context context = getContext();
            if (context != null) {
                context.sendBroadcast(new Intent(Config.ACTION_CONFIG_CHANGED).setPackage("android"));
            }
        }
        Bundle out = new Bundle();
        out.putString(OUT_STATUS, STATUS_OK);
        return out;
    }

    private void resetEngineSync() {
        LlmEngine toClose;
        synchronized (mLoadLock) {
            synchronized (mStateLock) {
                cancelIdleUnloadLocked();
                mPendingUnload = false;
                mEngineGeneration++;
                mFailedModelPath = null;
                toClose = detachEngineLocked();
            }
            closeQuietly(toClose);
        }
        Log.i(TAG, "Engine reset; next call reloads model");
        notifyEngineStateChanged();
    }

    private String mnnSingle(LlmEngine engine, String title, String text) {
        String user = "概括下面这条手机通知的要点,不超过22个字,直接输出概括文本:"
                + "\n标题:" + safe(title)
                + "\n内容:" + safe(text)
                + NO_THINK;
        String out = clean(engine.generate(SYSTEM_PROMPT, user, 64), 25);
        return out.isEmpty() ? null : "✨" + out;
    }

    private String mnnGroup(LlmEngine engine, String packageName,
            List<String> messages, List<String> titles) {
        String app = appName(packageName);
        int count = messages.size();
        StringBuilder sb = new StringBuilder("应用「" + app + "」推送了" + count + "条通知:\n");
        for (int i = 0; i < count; i++) {
            String t = safe(titles.get(i));
            String c = clip(safe(messages.get(i)), 60);
            sb.append(i + 1).append(". ").append(t.isEmpty() ? app : t).append(": ").append(c)
              .append('\n');
        }
        sb.append("用一句话概括以上通知的共同主题,不超过40个字,格式「")
          .append(app).append(' ').append(count)
          .append("条通知:概括」,直接输出一行文本,不要引号。").append(NO_THINK);
        String out = clean(engine.generate(SYSTEM_PROMPT, sb.toString(), 128), 54);
        return out.isEmpty() ? null : out;
    }

    /** Strips think blocks / wrapping quotes, squeezes whitespace, caps length. */
    private static String clean(String s, int max) {
        if (s == null) return "";
        int close = s.lastIndexOf("</think");
        if (close >= 0) {
            int gt = s.indexOf('>', close);
            s = gt >= 0 ? s.substring(gt + 1) : "";
        } else if (s.trim().startsWith("<think")) {
            return ""; // reasoning only, no answer
        }
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() >= 2) {
            char f = s.charAt(0), l = s.charAt(s.length() - 1);
            if ((f == '「' && l == '」') || (f == '《' && l == '》')
                    || (f == '"' && l == '"') || (f == '\'' && l == '\'')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        for (String p : new String[] {"概括:", "概括：", "摘要:", "摘要：", "总结:", "总结："}) {
            if (s.startsWith(p)) {
                s = s.substring(p.length());
                break;
            }
        }
        s = s.trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Heuristic fallback: "✨" + 15–25 char gist. */
    private String summarize(String title, String text) {
        String gist = gist(text);
        if (gist.isEmpty()) gist = gist(title);
        if (gist.isEmpty()) return "";
        return "✨" + gist;
    }

    /** Heuristic fallback: group digest, 24–54 chars, `<App> N 条:最新一条内容`. */
    private String groupSummary(String packageName, List<String> messages, List<String> titles) {
        String app = appName(packageName);
        int count = messages.size();
        String latest = gist(messages.get(count - 1));
        if (latest.isEmpty()) latest = gist(titles.get(count - 1));
        StringBuilder sb = new StringBuilder();
        sb.append(app).append(' ').append(count).append(" 条通知");
        if (!latest.isEmpty()) {
            sb.append(":").append(latest);
        }
        String out = sb.toString();
        return out.length() > 54 ? out.substring(0, 53) + "…" : out;
    }

    /** Placeholder engine: first sentence, whitespace squeezed, capped at 22 chars. */
    private static String gist(String text) {
        if (text == null) return "";
        String squeezed = text.replaceAll("\\s+", " ").trim();
        int end = squeezed.indexOf('。');
        if (end < 0 || end > 30) end = squeezed.indexOf('!');
        if (end < 0 || end > 30) end = squeezed.indexOf('?');
        if (end >= 0 && end <= 30) squeezed = squeezed.substring(0, end + 1);
        if (squeezed.length() > 23) squeezed = squeezed.substring(0, 22) + "…";
        return squeezed;
    }

    private static String appName(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "应用";
        int dot = packageName.lastIndexOf('.');
        String name = dot >= 0 ? packageName.substring(dot + 1) : packageName;
        return name.isEmpty() ? "应用" : name;
    }

    private static Bundle error(String reason) {
        Bundle out = new Bundle();
        out.putString(OUT_STATUS, STATUS_ERROR);
        out.putString("reason", reason);
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { return 0; }
}
