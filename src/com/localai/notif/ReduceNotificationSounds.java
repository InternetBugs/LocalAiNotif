package com.localai.notif;

import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.VibrationEffect;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

final class ReduceNotificationSounds {
    static boolean isNotificationUsage(int usage) {
        return usage == AudioAttributes.USAGE_NOTIFICATION
                || usage == AudioAttributes.USAGE_NOTIFICATION_EVENT
                || usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT
                || usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED;
    }

    private static final class Attempt {
        final Object record;
        final boolean alreadySounded;
        boolean checkingMute;
        boolean playingSound;

        Attempt(Object record, boolean alreadySounded) {
            this.record = record;
            this.alreadySounded = alreadySounded;
        }
    }

    static void install(ModuleMain module, ClassLoader loader, Class<?> recordType, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        Class<?> attention = loader.loadClass("com.android.server.notification.NotificationAttentionHelper");
        Class<?> signals = loader.loadClass(attention.getName() + "$Signals");
        Class<?> miui = loader.loadClass("com.android.server.notification.NotificationManagerServiceImpl");
        Method buzz = attention.getDeclaredMethod("buzzBeepBlinkLocked", recordType, signals);
        Method mute = attention.getDeclaredMethod("shouldMuteNotificationLocked", recordType, signals, boolean.class);
        Method vibrate = attention.getDeclaredMethod("playVibration", recordType, VibrationEffect.class, boolean.class);
        Method play = attention.getDeclaredMethod("playSound", recordType, Uri.class);
        Method silence = miui.getDeclaredMethod("isSilenceMode", recordType);
        Method flash = loader.loadClass("android.view.accessibility.AccessibilityManager")
                .getDeclaredMethod("startFlashNotificationEvent", Context.class, int.class, String.class);
        Method intercepted = recordType.getDeclaredMethod("isIntercepted");
        Method sound = recordType.getDeclaredMethod("getSound");
        Method attributes = recordType.getDeclaredMethod("getAudioAttributes");
        Method getSbn = recordType.getDeclaredMethod("getSbn");
        intercepted.setAccessible(true);
        sound.setAccessible(true);
        attributes.setAccessible(true);
        getSbn.setAccessible(true);

        ThreadLocal<Attempt> current = new ThreadLocal<>();
        Map<Object, Boolean> sounded = Collections.synchronizedMap(new WeakHashMap<>());
        module.hook(mute).intercept(chain -> {
            Attempt attempt = current.get();
            if (attempt == null || attempt.alreadySounded || attempt.record != chain.getArg(0)) return chain.proceed();
            attempt.checkingMute = true;
            try {
                return chain.proceed();
            } finally {
                attempt.checkingMute = false;
            }
        });
        module.hook(intercepted).intercept(chain -> {
            Attempt attempt = current.get();
            if (attempt != null && attempt.checkingMute && attempt.record == chain.getThisObject()) return false;
            return chain.proceed();
        });
        module.hook(play).intercept(chain -> {
            Attempt attempt = current.get();
            if (attempt == null || attempt.alreadySounded || attempt.record != chain.getArg(0)) return chain.proceed();
            attempt.playingSound = true;
            try {
                return chain.proceed();
            } finally {
                attempt.playingSound = false;
            }
        });
        module.hook(silence).intercept(chain -> {
            Attempt attempt = current.get();
            if (attempt != null && attempt.playingSound && attempt.record == chain.getArg(0)) return false;
            return chain.proceed();
        });
        module.hook(vibrate).intercept(chain -> {
            Attempt attempt = current.get();
            if (attempt != null && !attempt.alreadySounded && attempt.record == chain.getArg(0)) return false;
            return chain.proceed();
        });
        module.hook(flash).intercept(chain -> {
            Attempt attempt = current.get();
            if (attempt != null && !attempt.alreadySounded && (int) chain.getArg(1) == 3) return false;
            return chain.proceed();
        });
        module.hook(sound).intercept(chain -> {
            Attempt attempt = current.get();
            if (attempt != null && attempt.alreadySounded && attempt.record == chain.getThisObject()) return null;
            return chain.proceed();
        });
        module.deoptimize(mute);
        module.deoptimize(buzz);
        module.deoptimize(miui.getDeclaredMethod("isDeniedPlaySound", android.media.AudioManager.class, recordType));
        module.hook(buzz).intercept(chain -> {
            Object record = chain.getArg(0);
            boolean previousSound = sounded.containsKey(record);
            if (!enabled.getAsBoolean() && !previousSound) return chain.proceed();
            boolean replay = previousSound && ReduceInterruptions.isBreakthrough((StatusBarNotification) getSbn.invoke(record));
            if (!replay) {
                if (!enabled.getAsBoolean() || !(boolean) intercepted.invoke(record)) return chain.proceed();
                AudioAttributes audio = (AudioAttributes) attributes.invoke(record);
                Uri uri = (Uri) sound.invoke(record);
                if (audio == null || !isNotificationUsage(audio.getUsage()) || uri == null || Uri.EMPTY.equals(uri)) {
                    return chain.proceed();
                }
            }
            Attempt previous = current.get();
            current.set(new Attempt(record, replay));
            try {
                int effects = (int) chain.proceed();
                if (!replay && (effects & 2) != 0) sounded.put(record, true);
                return effects;
            } finally {
                if (previous == null) current.remove();
                else current.set(previous);
            }
        });
    }
}
