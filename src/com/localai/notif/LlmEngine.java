package com.localai.notif;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Thin Java face of jni/localai_llm_jni.cpp (MNN-LLM).
 * create() returns null when the model cannot be loaded; callers fall back to heuristics.
 */
public final class LlmEngine implements AutoCloseable {

    private static volatile boolean sNativesLoaded;

    private volatile long mPtr;
    private final byte[] mConfigPath;
    private boolean mHexagon;

    private LlmEngine(long ptr, byte[] configPath, boolean hexagon) {
        mPtr = ptr;
        mConfigPath = configPath;
        mHexagon = hexagon;
    }

    /** Loads the model at configPath (MNN llm config.json). Returns null on failure. */
    public static LlmEngine create(String configPath) {
        return create(null, configPath);
    }

    static LlmEngine create(Context context, String configPath) {
        File config = new File(configPath);
        boolean requested = false;
        boolean explicitHexagon = false;
        if (context != null) {
            try {
                String configured = new JSONObject(new String(Files.readAllBytes(config.toPath()),
                        StandardCharsets.UTF_8)).optString("backend_type", "cpu");
                explicitHexagon = "hexagon".equals(configured);
                requested = explicitHexagon || ("cpu".equals(configured)
                        && ModelCatalog.findByPath(context, config.getParent()) != null);
            } catch (Exception e) {
                Log.w("LocalAiNotif", "Cannot read model backend", e);
            }
        }
        boolean hexagon = requested && prepareHexagon(context);
        synchronized (LlmEngine.class) {
            if (!sNativesLoaded) {
                System.loadLibrary("MNN");
                System.loadLibrary("localai_llm");
                sNativesLoaded = true;
            }
        }
        byte[] path = utf8(configPath);
        try {
            if (hexagon) {
                long ptr = nativeCreate(path, true, false);
                if (ptr != 0) {
                    Log.i("LocalAiNotif", "MNN backend=hexagon, v79 runtime ready");
                    return new LlmEngine(ptr, path, true);
                }
                Log.w("LocalAiNotif", "Hexagon runtime or model load failed, using CPU");
            }
            long ptr = nativeCreate(path, false, explicitHexagon || hexagon);
            return ptr != 0 ? new LlmEngine(ptr, path, false) : null;
        } catch (Throwable t) {
            android.util.Log.e("LocalAiNotif", "nativeCreate: " + t);
            return null;
        }
    }

    private static boolean prepareHexagon(Context context) {
        if (!"SM8750".equalsIgnoreCase(Build.SOC_MODEL)) return false;
        File dir = new File(context.getApplicationInfo().nativeLibraryDir);
        if (!new File(dir, "libMNN_htpops.so").isFile()
                || !new File(dir, "libMNN_htpops_skelV79.so").isFile()) return false;
        try {
            for (String variable : new String[]{"DSP_LIBRARY_PATH", "ADSP_LIBRARY_PATH"}) {
                String previous = Os.getenv(variable);
                String path = dir.getAbsolutePath();
                if (previous == null || previous.isEmpty()) {
                    previous = "/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
                }
                if (!previous.startsWith(path + ";")) Os.setenv(variable, path + ";" + previous, true);
            }
            return true;
        } catch (android.system.ErrnoException e) {
            Log.w("LocalAiNotif", "Cannot configure Hexagon library path", e);
            return false;
        }
    }

    public boolean isOpen() {
        return mPtr != 0;
    }

    /** Blocking generation. Returns "" on any internal failure (caller decides fallback). */
    public synchronized String generate(String systemPrompt, String userPrompt, int maxNewTokens) {
        if (mPtr == 0) return "";
        try {
            byte[] system = utf8(systemPrompt);
            byte[] user = utf8(userPrompt);
            byte[] output = nativeGenerate(mPtr, system, user, maxNewTokens);
            if (mHexagon && (output == null || output.length == 0)) {
                Log.w("LocalAiNotif", "Hexagon generation failed, retrying on CPU");
                close();
                mHexagon = false;
                mPtr = nativeCreate(mConfigPath, false, true);
                if (mPtr != 0) output = nativeGenerate(mPtr, system, user, maxNewTokens);
            }
            return output == null ? "" : new String(output, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            android.util.Log.e("LocalAiNotif", "nativeGenerate: " + t);
            return "";
        }
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

    private static native long nativeCreate(byte[] configPath, boolean hexagon, boolean forceCpu);

    private static native byte[] nativeGenerate(long ptr, byte[] systemPrompt, byte[] userPrompt, int maxNewTokens);

    private static native void nativeDestroy(long ptr);
}
