package io.github.internetbugs.localainotif;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** State is owned by the handler; only the provider query runs on the executor. */
final class AsyncConfig implements Supplier<Bundle> {
    private static final String TAG = "LocalAiNotif";
    private final Handler handler;
    private final Executor executor;
    private final Supplier<Bundle> query;
    private final Runnable updated;
    private Bundle cached = new Bundle();
    private boolean loading;
    private boolean dirty;
    private static final long[] RETRY_DELAYS_MS = {2_000L, 5_000L, 15_000L};
    private int retryIndex;
    private final Runnable retry = this::refresh;

    AsyncConfig(Handler handler, Executor executor, Supplier<Bundle> query, Runnable updated) {
        this.handler = handler;
        this.executor = executor;
        this.query = query;
        this.updated = updated;
    }

    @Override
    public Bundle get() {
        return cached;
    }

    /** Updates an entry in the handler-owned snapshot when system_server mirrors state locally. */
    void putBooleanLocal(String key, boolean value) {
        cached.putBoolean(key, value);
    }

    void refresh() {
        handler.removeCallbacks(retry);
        if (loading) {
            dirty = true;
            return;
        }
        loading = true;
        executor.execute(() -> {
            Bundle result = null;
            try {
                result = query.get();
            } catch (Exception e) {
                Log.w(TAG, "Failed to refresh configuration", e);
            }
            Bundle value = result;
            handler.post(() -> {
                loading = false;
                if (dirty) {
                    dirty = false;
                    refresh();
                    return;
                }
                if (value == null) {
                    handler.postDelayed(retry, RETRY_DELAYS_MS[retryIndex]);
                    retryIndex = Math.min(retryIndex + 1, RETRY_DELAYS_MS.length - 1);
                    return;
                }
                retryIndex = 0;
                cached = new Bundle(value);
                updated.run();
            });
        });
    }
}
