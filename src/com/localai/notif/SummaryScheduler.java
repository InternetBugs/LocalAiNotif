package com.localai.notif;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.function.IntFunction;
import java.util.function.Supplier;

final class SummaryScheduler extends BroadcastReceiver implements AlarmManager.OnAlarmListener {
    private final Handler handler;
    private final AlarmManager alarms;
    private final PowerManager power;
    private final Supplier<Bundle> config;
    private final IntFunction<Runnable> prepareDispatch;
    private long readySince = -1;

    SummaryScheduler(Context context, Handler handler, Supplier<Bundle> config,
            IntFunction<Runnable> prepareDispatch) {
        this.handler = handler;
        this.alarms = context.getSystemService(AlarmManager.class);
        this.power = context.getSystemService(PowerManager.class);
        this.config = config;
        this.prepareDispatch = prepareDispatch;
    }

    void refresh() {
        handler.post(this::schedule);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        refresh();
    }

    @Override
    public void onAlarm() {
        schedule();
    }

    private void schedule() {
        Bundle settings = config.get();
        if (settings == null) settings = new Bundle();
        boolean screenOn = settings.getBoolean(Config.KEY_SUMMARIZE_SCREEN_ON,
                Config.DEFAULT_SUMMARIZE_SCREEN_ON);
        int threshold = Math.max(1, settings.getInt(Config.KEY_NOTIFICATION_THRESHOLD,
                Config.DEFAULT_NOTIFICATION_THRESHOLD));
        long waitMillis = Math.max(0, settings.getInt(Config.KEY_WAIT_SECONDS,
                Config.DEFAULT_WAIT_SECONDS)) * 1000L;

        alarms.cancel(this);
        Runnable dispatch = prepareDispatch.apply(threshold);
        if (dispatch == null || (!screenOn && power.isInteractive())) {
            readySince = -1;
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (readySince < 0) readySince = now;
        long due = readySince + waitMillis;
        if (due <= now) {
            readySince = -1;
            dispatch.run();
        } else {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, due,
                    "LocalAiNotif", this, handler);
        }
    }
}
