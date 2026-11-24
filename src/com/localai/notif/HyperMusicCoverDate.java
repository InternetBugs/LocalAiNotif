package com.localai.notif;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** HyperMusicCover 0.9.55 adds its own DND moon and battery status to the keyguard date. */
final class HyperMusicCoverDate {
    private static final Uri ZEN = Settings.Global.getUriFor("zen_mode");
    private final Handler main = new Handler(Looper.getMainLooper());
    private ContentObserver dateObserver;

    HyperMusicCoverDate(ModuleMain module) throws ReflectiveOperationException {
        Method register = ContentResolver.class.getMethod("registerContentObserver",
                Uri.class, boolean.class, ContentObserver.class);
        module.hook(register).intercept(chain -> {
            Object result = chain.proceed();
            if (ZEN.equals(chain.getArg(0)) && chain.getArg(2) instanceof ContentObserver observer
                    && observer.getClass().getName().equals("d20")) {
                main.post(() -> bind(module, observer));
            }
            return result;
        });
    }

    private void bind(ModuleMain module, ContentObserver observer) {
        if (dateObserver != null) return;
        try {
            ClassLoader loader = observer.getClass().getClassLoader();
            Class<?> extension = Class.forName("com.os4.musiccover.Main", false, loader);
            if (extension.getClassLoader() != loader) return;
            Class<?> date = Class.forName("e20", false, loader);
            Method readZen = date.getDeclaredMethod("r", ContentResolver.class);
            Field moon = date.getDeclaredField("h");
            if (!Modifier.isStatic(readZen.getModifiers()) || readZen.getReturnType() != void.class
                    || !Modifier.isStatic(moon.getModifiers()) || moon.getType() != boolean.class) return;
            moon.setAccessible(true);
            module.hook(readZen).intercept(chain -> {
                Object result = chain.proceed();
                if (SystemUiReduceInterruptions.lockPillShowing()) moon.setBoolean(null, false);
                return result;
            });
            dateObserver = observer;
            refresh();
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("LocalAiNotif", "Could not synchronize HyperMusicCover's keyguard date", e);
        }
    }

    void refresh() {
        if (dateObserver != null) dateObserver.dispatchChange(false, ZEN);
    }
}
