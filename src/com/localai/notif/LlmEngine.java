package com.localai.notif;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Thin Java face of jni/localai_llm_jni.cpp (MNN-LLM).
 * create() returns null when the model cannot be loaded; callers fall back to heuristics.
 */
public final class LlmEngine implements AutoCloseable {

    private static volatile boolean sNativesLoaded;

    private volatile long mPtr;
    private final byte[] mConfigPath;
    private final String[] mBackends;
    private final File mCacheDir;
    private int mNextBackend;

    private LlmEngine(byte[] configPath, String[] backends, File cacheDir) {
        mConfigPath = configPath;
        mBackends = backends;
        mCacheDir = cacheDir;
    }

    /** Loads the model at configPath (MNN llm config.json). Returns null on failure. */
    public static LlmEngine create(String configPath) {
        return create(null, configPath);
    }

    static LlmEngine create(Context context, String configPath) {
        File config = new File(configPath);
        synchronized (LlmEngine.class) {
            if (!sNativesLoaded) {
                System.loadLibrary("MNN");
                System.loadLibrary("localai_llm");
                sNativesLoaded = true;
            }
        }
        String configured = "";
        try {
            configured = new JSONObject(new String(Files.readAllBytes(config.toPath()),
                    StandardCharsets.UTF_8)).optString("backend_type", "auto");
        } catch (Exception e) {
            Log.w("LocalAiNotif", "Cannot read model backend", e);
        }
        boolean preset = context != null && ModelCatalog.findByPath(context, config.getParent()) != null;
        boolean automatic = "auto".equals(configured) || (preset && "cpu".equals(configured));
        String[] backends;
        if (automatic || "hexagon".equals(configured)) {
            backends = context != null && prepareHexagon(context)
                    ? new String[]{"hexagon", "vulkan", "cpu"}
                    : new String[]{"vulkan", "cpu"};
        } else if ("vulkan".equals(configured) || "opencl".equals(configured)) {
            backends = new String[]{"vulkan", "cpu"};
        } else if ("cpu".equals(configured)) {
            backends = new String[]{"cpu"};
        } else {
            backends = new String[]{"configured", "cpu"};
        }
        File cacheDir = context == null ? null : new File(context.getCacheDir(),
                "mnn/" + Integer.toHexString(config.getAbsolutePath().hashCode()));
        LlmEngine engine = new LlmEngine(utf8(configPath), backends, cacheDir);
        return engine.loadNextBackend() ? engine : null;
    }

    private boolean loadNextBackend() {
        while (mNextBackend < mBackends.length) {
            String backend = mBackends[mNextBackend++];
            try {
                JSONObject options = new JSONObject();
                if (!"configured".equals(backend)) options.put("backend_type", backend);
                if ("hexagon".equals(backend) || "vulkan".equals(backend)) {
                    options.put("precision", "low").put("thread_num", 1);
                    if (mCacheDir != null) {
                        File dir = new File(mCacheDir, backend);
                        if (dir.isDirectory() || dir.mkdirs()) options.put("tmp_path", dir.getAbsolutePath());
                    }
                }
                mPtr = nativeCreate(mConfigPath, utf8(backend), utf8(options.toString()));
                if (mPtr != 0) {
                    Log.i("LocalAiNotif", "MNN backend=" + backend + ", model loaded");
                    return true;
                }
                Log.w("LocalAiNotif", "Cannot load MNN backend=" + backend);
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                Log.w("LocalAiNotif", "Cannot load MNN backend=" + backend, e);
            }
        }
        return false;
    }

    private static synchronized boolean prepareHexagon(Context context) {
        File dir = new File(context.getApplicationInfo().nativeLibraryDir);
        try {
            System.loadLibrary("MNN_htpops");
            int arch = nativeHexagonArch();
            String skel = String.format(Locale.ROOT, "libMNN_htpops_skelV%02X.so", arch);
            if (arch == 0 || !new File(dir, skel).isFile()) return false;
            for (String variable : new String[]{"DSP_LIBRARY_PATH", "ADSP_LIBRARY_PATH"}) {
                String previous = Os.getenv(variable);
                String path = dir.getAbsolutePath();
                if (previous == null || previous.isEmpty()) {
                    previous = "/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
                }
                if (!previous.startsWith(path + ";")) Os.setenv(variable, path + ";" + previous, true);
            }
            return true;
        } catch (android.system.ErrnoException | UnsatisfiedLinkError e) {
            Log.i("LocalAiNotif", "Hexagon unavailable: " + e.getMessage());
            return false;
        }
    }

    public boolean isOpen() {
        return mPtr != 0;
    }

    /** Blocking generation. Returns "" on any internal failure (caller decides fallback). */
    public synchronized String generate(String systemPrompt, String userPrompt, int maxNewTokens) {
        if (mPtr == 0) return "";
        byte[] system = utf8(systemPrompt);
        byte[] user = utf8(userPrompt);
        do {
            try {
                byte[] output = nativeGenerate(mPtr, system, user, maxNewTokens);
                if (output != null && output.length > 0) return new String(output, StandardCharsets.UTF_8);
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                Log.w("LocalAiNotif", "MNN generation failed", e);
            }
            if (mNextBackend == mBackends.length) break;
            Log.w("LocalAiNotif", "Generation failed on " + mBackends[mNextBackend - 1] + ", retrying");
            close();
        } while (loadNextBackend());
        return "";
    }

    @Override
    public synchronized void close() {
        if (mPtr != 0) {
            try {
                nativeDestroy(mPtr);
            } catch (Throwable ignored) {
            }
            mPtr = 0;
        }
    }

    private static byte[] utf8(String text) {
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
    }

    static String clip(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Math.max(0, limit);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }

    private static native int nativeHexagonArch();

    private static native long nativeCreate(byte[] configPath, byte[] backend, byte[] options);

    private static native byte[] nativeGenerate(long ptr, byte[] systemPrompt, byte[] userPrompt, int maxNewTokens);

    private static native void nativeDestroy(long ptr);
}
