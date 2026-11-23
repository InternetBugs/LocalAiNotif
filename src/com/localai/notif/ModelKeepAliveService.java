package com.localai.notif;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * Silent keep-alive service running in the {@code :inference} process.
 * Bound by {@code system_server} when "always load AI model" is enabled so the inference
 * process remains resident without displaying a persistent notification.
 */
public final class ModelKeepAliveService extends Service {
    private static final String TAG = "LocalAiNotif";
    private final Binder mBinder = new Binder();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ModelKeepAliveService created");
        InferenceProvider.triggerPreloadIfEnabled();
    }

    @Override
    public IBinder onBind(Intent intent) {
        InferenceProvider.triggerPreloadIfEnabled();
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        InferenceProvider.triggerPreloadIfEnabled();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ModelKeepAliveService destroyed");
        super.onDestroy();
    }
}
