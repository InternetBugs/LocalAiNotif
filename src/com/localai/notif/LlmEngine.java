package com.localai.notif;

/**
 * Thin Java face of jni/localai_llm_jni.cpp (MNN-LLM).
 * create() returns null when the model cannot be loaded; callers fall back to heuristics.
 */
public final class LlmEngine implements AutoCloseable {

    private static volatile boolean sNativesLoaded;

    private long mPtr;

    private LlmEngine(long ptr) {
        mPtr = ptr;
    }

    /** Loads the model at configPath (MNN llm config.json). Returns null on failure. */
    public static LlmEngine create(String configPath) {
        synchronized (LlmEngine.class) {
            if (!sNativesLoaded) {
                System.loadLibrary("MNN");
                System.loadLibrary("localai_llm");
                sNativesLoaded = true;
            }
        }
        long ptr = 0;
        try {
            ptr = nativeCreate(configPath);
        } catch (Throwable t) {
            android.util.Log.e("LocalAiNotif", "nativeCreate: " + t);
        }
        return ptr != 0 ? new LlmEngine(ptr) : null;
    }

    public synchronized boolean isOpen() {
        return mPtr != 0;
    }

    /** Blocking generation. Returns "" on any internal failure (caller decides fallback). */
    public synchronized String generate(String systemPrompt, String userPrompt, int maxNewTokens) {
        if (mPtr == 0) return "";
        try {
            return nativeGenerate(mPtr, systemPrompt, userPrompt, maxNewTokens);
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

    private static native long nativeCreate(String configPath);

    private static native String nativeGenerate(long ptr, String systemPrompt, String userPrompt, int maxNewTokens);

    private static native void nativeDestroy(long ptr);
}
