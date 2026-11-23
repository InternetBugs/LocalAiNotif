package com.localai.notif;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground data-sync service keeping 1–2.4 GB MNN model downloads alive in the background
 * with a clean HyperOS-friendly progress notification.
 */
public final class ModelDownloadService extends Service implements ModelDownloadManager.Listener {
    private static final String TAG = "LocalAiNotif";
    private static final String CHANNEL_ID = "localai_model_download";
    private static final int PROGRESS_NOTIF_ID = 0x4c4101;
    private static final int RESULT_NOTIF_ID = 0x4c4102;

    private static final String ACTION_TRACK = "com.localai.notif.action.TRACK_DOWNLOAD";
    private static final String ACTION_PAUSE = "com.localai.notif.action.PAUSE_DOWNLOAD";
    private static final String ACTION_CANCEL = "com.localai.notif.action.CANCEL_DOWNLOAD";
    private static final String ACTION_STOP = "com.localai.notif.action.STOP_TRACKING";
    private static final String EXTRA_MODEL_ID = "model_id";

    private ModelDownloadManager mManager;
    private NotificationManager mNotifManager;
    private String mTrackingModelId;
    private long mLastNotifyMs;

    static void startTracking(Context context, String modelId) {
        Intent intent = new Intent(context, ModelDownloadService.class);
        intent.setAction(ACTION_TRACK);
        intent.putExtra(EXTRA_MODEL_ID, modelId);
        try {
            context.startForegroundService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start foreground download service: " + t);
        }
    }

    static void stopTracking(Context context) {
        Intent intent = new Intent(context, ModelDownloadService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    static void notifyCompleted(Context context, String modelName, boolean autoActivated) {
        stopTracking(context);
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;
            ensureChannel(nm);
            String text = autoActivated
                    ? modelName + " 已下载并自动启用，推理引擎已就绪。"
                    : modelName + " 已下载完成，点击前往启用。";
            Notification notif = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("端侧模型下载完成")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openDownloadPageIntent(context))
                    .build();
            nm.notify(RESULT_NOTIF_ID, notif);
        } catch (Throwable ignored) {
        }
    }

    static void notifyFailed(Context context, String modelName, String reason) {
        stopTracking(context);
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;
            ensureChannel(nm);
            Notification notif = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(modelName + " 下载中断")
                    .setContentText(reason != null ? reason : "可返回应用断点续传。")
                    .setAutoCancel(true)
                    .setContentIntent(openDownloadPageIntent(context))
                    .build();
            nm.notify(RESULT_NOTIF_ID, notif);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mManager = ModelDownloadManager.getInstance(this);
        mNotifManager = getSystemService(NotificationManager.class);
        if (mNotifManager != null) {
            ensureChannel(mNotifManager);
        }
        mManager.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PAUSE.equals(action)) {
            mManager.pauseDownload(intent.getStringExtra(EXTRA_MODEL_ID));
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            mManager.cancelDownload(intent.getStringExtra(EXTRA_MODEL_ID));
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.hasExtra(EXTRA_MODEL_ID)) {
            mTrackingModelId = intent.getStringExtra(EXTRA_MODEL_ID);
        }
        ModelDownloadManager.Snapshot snap = mTrackingModelId != null
                ? mManager.getSnapshot(mTrackingModelId)
                : mManager.getActiveSnapshot();
        Notification notif = buildProgressNotification(snap);
        try {
            startForeground(PROGRESS_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (snap == null || !snap.isActive()) {
            stopForegroundAndSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDownloadStateChanged() {
        ModelDownloadManager.Snapshot snap = mTrackingModelId != null
                ? mManager.getSnapshot(mTrackingModelId)
                : mManager.getActiveSnapshot();
        if (snap == null || !snap.isActive()) {
            stopForegroundAndSelf();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastNotifyMs < 500L && snap.state == ModelDownloadManager.State.DOWNLOADING) {
            return;
        }
        mLastNotifyMs = now;
        if (mNotifManager != null) {
            mNotifManager.notify(PROGRESS_NOTIF_ID, buildProgressNotification(snap));
        }
    }

    private void stopForegroundAndSelf() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mManager != null) {
            mManager.removeListener(this);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildProgressNotification(ModelDownloadManager.Snapshot snap) {
        String modelId = snap != null ? snap.modelId : mTrackingModelId;
        ModelCatalog.ModelSpec spec = ModelCatalog.findById(modelId);
        String name = spec != null ? spec.displayName : "端侧模型";

        String title;
        String detail;
        boolean indeterminate;
        int percent = 0;

        if (snap == null || snap.state == ModelDownloadManager.State.FETCHING_META) {
            title = "正在准备下载 " + name;
            detail = "正在获取模型文件清单…";
            indeterminate = true;
        } else if (snap.state == ModelDownloadManager.State.VERIFYING) {
            title = "正在校验 " + name;
            detail = "正在检查模型文件完整性…";
            indeterminate = true;
        } else {
            percent = snap.progressPercent();
            title = "正在下载 " + name + "（" + percent + "%）";
            String speed = ModelCatalog.formatSpeed(snap.speedBytesPerSec);
            detail = ModelCatalog.formatBytes(snap.downloadedBytes)
                    + " / " + ModelCatalog.formatBytes(snap.totalBytes)
                    + (speed.isEmpty() ? "" : "  " + speed);
            indeterminate = snap.totalBytes <= 0L;
        }

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(detail)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, indeterminate)
                .setContentIntent(openDownloadPageIntent(this));

        if (modelId != null && (snap == null || snap.state != ModelDownloadManager.State.VERIFYING)) {
            Intent pauseIntent = new Intent(this, ModelDownloadService.class);
            pauseIntent.setAction(ACTION_PAUSE);
            pauseIntent.putExtra(EXTRA_MODEL_ID, modelId);
            PendingIntent pausePi = PendingIntent.getService(
                    this, 1, pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent cancelIntent = new Intent(this, ModelDownloadService.class);
            cancelIntent.setAction(ACTION_CANCEL);
            cancelIntent.putExtra(EXTRA_MODEL_ID, modelId);
            PendingIntent cancelPi = PendingIntent.getService(
                    this, 2, cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            builder.addAction(new Notification.Action.Builder(null, "暂停", pausePi).build());
            builder.addAction(new Notification.Action.Builder(null, "取消", cancelPi).build());
        }
        return builder.build();
    }

    private static PendingIntent openDownloadPageIntent(Context context) {
        Intent open = new Intent(context, ModelDownloadActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureChannel(NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "端侧模型下载", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示端侧语言模型下载进度与完成提醒");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
