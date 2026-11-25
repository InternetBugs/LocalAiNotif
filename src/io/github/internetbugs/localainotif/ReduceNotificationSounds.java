package io.github.internetbugs.localainotif;

import android.app.Notification;
import android.net.Uri;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class ReduceNotificationSounds {
    private static final int ALERT_SOUND = 2;
    private final ThreadLocal<Object> mutedRecord = new ThreadLocal<>();
    private final ThreadLocal<Object> replayRecord = new ThreadLocal<>();
    private final Method getService;

    ReduceNotificationSounds(ModuleMain module, ClassLoader loader, Class<?> recordType,
            BooleanSupplier enabled, BiConsumer<Object, StatusBarNotification> defer) throws Exception {
        getService = loader.loadClass("android.os.ServiceManager").getMethod("getService", String.class);
        Class<?> attention = loader.loadClass("com.android.server.notification.NotificationAttentionHelper");
        Method buzz = attention.getDeclaredMethod("buzzBeepBlinkLocked", recordType,
                loader.loadClass(attention.getName() + "$Signals"));
        Method play = attention.getDeclaredMethod("playSound", recordType, Uri.class);
        Method playInCall = attention.getDeclaredMethod("playInCallNotification");
        Method getSbn = recordType.getDeclaredMethod("getSbn");
        Method intercepted = recordType.getDeclaredMethod("isIntercepted");
        Field status = recordType.getDeclaredField("mNRStatusStub");
        Method allowsSound = status.getType().getMethod("isStatusBarAllowSound");
        getSbn.setAccessible(true);
        intercepted.setAccessible(true);
        status.setAccessible(true);
        allowsSound.setAccessible(true);

        Consumer<Object> deferIfAllowed = record -> {
            try {
                Object statusStub = status.get(record);
                if (statusStub != null && (boolean) allowsSound.invoke(statusStub)) {
                    defer.accept(record, (StatusBarNotification) getSbn.invoke(record));
                }
            } catch (ReflectiveOperationException error) {
                Log.w("LocalAiNotif", "Could not read notification sound allowance", error);
            }
        };
        module.hook(play).intercept(chain -> {
            Object record = chain.getArg(0);
            if (record != mutedRecord.get()) return chain.proceed();
            deferIfAllowed.accept(record);
            return false;
        });
        module.hook(playInCall).intercept(chain -> {
            Object record = mutedRecord.get();
            if (record == null) return chain.proceed();
            deferIfAllowed.accept(record);
            return null;
        });
        module.hook(buzz).intercept(chain -> {
            Object record = chain.getArg(0);
            if (!enabled.getAsBoolean() || record == replayRecord.get() || (boolean) intercepted.invoke(record)) {
                return chain.proceed();
            }
            StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
            if (sbn == null || ReduceInterruptions.isBreakthrough(sbn)) return chain.proceed();
            Notification notification = sbn.getNotification();
            if (!ReduceInterruptions.isEligibleForClassification(notification)
                    && (notification.flags & Notification.FLAG_GROUP_SUMMARY) == 0) return chain.proceed();
            Object previous = mutedRecord.get();
            mutedRecord.set(record);
            try {
                return chain.proceed();
            } finally {
                if (previous == null) mutedRecord.remove();
                else mutedRecord.set(previous);
            }
        });
        module.deoptimize(buzz);
    }

    void playApprovedSound(Object record, String key) throws ReflectiveOperationException {
        Object notificationService = getService.invoke(null, "notification");
        Method requestAlert = notificationService.getClass()
                .getMethod("buzzBeepBlinkForNotification", String.class, int.class);
        requestAlert.setAccessible(true);
        replayRecord.set(record);
        try {
            requestAlert.invoke(notificationService, key, ALERT_SOUND);
        } finally {
            replayRecord.remove();
        }
    }
}
