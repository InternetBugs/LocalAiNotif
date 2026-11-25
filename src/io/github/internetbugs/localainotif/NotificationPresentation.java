package io.github.internetbugs.localainotif;

import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class NotificationPresentation {
    static final String ACTION = "io.github.internetbugs.localainotif.NOTIFICATIONS_PRESENTED";
    static final String KEYS = "keys";
    static final String TIMES = "post_times";
    private final Map<String, Long> sent = new HashMap<>();
    private Map<String, Long> latest = Map.of();
    private final Executor sender = Executors.newSingleThreadExecutor();

    void afterRender(View root, Map<String, Long> versions) {
        latest = versions;
        sent.keySet().retainAll(versions.keySet());
        if (sent.equals(versions)) return;
        Context context = root.getContext();
        Runnable report = () -> send(context, versions);
        if (!root.isShown() || root.getWindowVisibility() != View.VISIBLE) {
            report.run();
            return;
        }
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (root.isHardwareAccelerated()) {
            observer.registerFrameCommitCallback(report);
        } else {
            observer.addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
                private boolean drawn;

                @Override public void onDraw() {
                    if (drawn) return;
                    drawn = true;
                    root.post(() -> {
                        if (observer.isAlive()) observer.removeOnDrawListener(this);
                        report.run();
                    });
                }
            });
        }
        root.invalidate();
    }

    private void send(Context context, Map<String, Long> versions) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Long> entry : versions.entrySet()) {
            if (entry.getValue().equals(latest.get(entry.getKey()))
                    && !entry.getValue().equals(sent.put(entry.getKey(), entry.getValue()))) {
                changed.add(entry.getKey());
            }
        }
        for (int start = 0; start < changed.size(); start += 64) {
            ArrayList<String> keys = new ArrayList<>(changed.subList(start, Math.min(start + 64, changed.size())));
            long[] times = new long[keys.size()];
            for (int index = 0; index < keys.size(); index++) times[index] = versions.get(keys.get(index));
            Intent receipt = new Intent(ACTION).setPackage("android").addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    .putStringArrayListExtra(KEYS, keys).putExtra(TIMES, times);
            sender.execute(() -> context.sendBroadcast(receipt));
        }
    }

    static void add(Map<String, Long> versions, StatusBarNotification notification) {
        if (notification != null) versions.put(notification.getKey(), notification.getPostTime());
    }
}
