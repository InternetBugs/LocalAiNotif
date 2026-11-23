package com.localai.notif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Control Center Quick Settings tile for "减少干扰" (Reduce Interruptions).
 * Uses the custom monochrome "星芒叠层" vector icon and stays strictly synced
 * with system_server ZenMode and MainActivity via InferenceProvider IPC.
 */
public final class ReduceInterruptionsTileService extends TileService {
    private static final Uri PROVIDER = Uri.parse("content://" + Config.AUTHORITY);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean active;
    private volatile boolean lockScreenOnly;
    private volatile boolean modelReady;
    private volatile boolean busy;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ReduceInterruptions.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            active = intent.getBooleanExtra(ReduceInterruptions.EXTRA_ACTIVE, false);
            lockScreenOnly = intent.getBooleanExtra(
                    ReduceInterruptions.EXTRA_LOCK_SCREEN_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY);
            renderTile();
        }
    };

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        refreshFromProvider();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (!receiverRegistered) {
            registerReceiver(stateReceiver,
                    new IntentFilter(ReduceInterruptions.ACTION_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        }
        renderTile();
        refreshFromProvider();
    }

    @Override
    public void onStopListening() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (busy) return;
        boolean target = !active;
        busy = true;
        active = target;
        renderTile();
        executor.execute(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putBoolean(Config.KEY_REDUCE_ENABLED, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            mainHandler.post(() -> {
                if (finalResult != null) {
                    applyConfig(finalResult);
                } else {
                    active = !target;
                    renderTile();
                }
                busy = false;
            });
        });
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void refreshFromProvider() {
        executor.execute(() -> {
            Bundle cfg = null;
            try {
                cfg = getContentResolver().call(PROVIDER, "get_config", null, null);
            } catch (RuntimeException ignored) {
            }
            Bundle finalCfg = cfg;
            mainHandler.post(() -> {
                if (finalCfg != null) {
                    applyConfig(finalCfg);
                } else {
                    renderTile();
                }
            });
        });
    }

    private void applyConfig(Bundle cfg) {
        active = cfg.getBoolean(Config.KEY_REDUCE_ENABLED, Config.DEFAULT_REDUCE_ENABLED);
        lockScreenOnly = cfg.getBoolean(
                Config.KEY_REDUCE_LOCK_SCREEN_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY);
        String modelPath = cfg.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH);
        modelReady = cfg.getBoolean(Config.KEY_MODEL_READY,
                ModelCatalog.isModelDirValid(new File(modelPath)));
        renderTile();
    }

    private void renderTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel("减少干扰");
        tile.setIcon(ReduceInterruptions.createTileIcon(this));
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (!modelReady) {
            tile.setSubtitle(active ? "未下载模型，当前等同勿扰" : "未下载模型");
        } else if (active) {
            tile.setSubtitle(lockScreenOnly ? "仅锁屏生效" : "放行重要通知");
        } else {
            tile.setSubtitle("已关闭");
        }
        tile.updateTile();
    }
}
