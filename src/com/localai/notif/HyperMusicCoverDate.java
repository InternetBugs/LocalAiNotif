package com.localai.notif;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.HookHandle;

/** Tracks HyperMusicCover's keyguard island lifecycle and coordinates its date moon. */
final class HyperMusicCoverDate {
    private static final String ZEN_MODE = "zen_mode";
    private static final Uri ZEN = Settings.Global.getUriFor(ZEN_MODE);
    private static volatile BroadcastReceiver islandScreenReceiver;
    private final ThreadLocal<ContentResolver> dateRead = new ThreadLocal<>();
    private volatile ContentObserver dateObserver;
    private HookHandle onChangeHook;
    private HookHandle readHook;

    HyperMusicCoverDate(ModuleMain module) throws ReflectiveOperationException {
        Method watch = ContextWrapper.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class);
        Method unwatch = ContextWrapper.class.getMethod("unregisterReceiver", BroadcastReceiver.class);
        Handler main = new Handler(Looper.getMainLooper());
        module.hook(watch).intercept(chain -> {
            Object result = chain.proceed();
            // LockIslands registers this single-action receiver on the SystemUI Application.
            if (chain.getArg(1) instanceof IntentFilter filter && filter.countActions() == 1
                    && filter.hasAction(Intent.ACTION_SCREEN_OFF) && chain.getArg(0) instanceof BroadcastReceiver receiver
                    && receiver != islandScreenReceiver && isMusicCoverObject(receiver)) {
                islandScreenReceiver = receiver;
                main.post(SystemUiPrioritySection::requestListInvalidation);
            }
            return result;
        });
        module.hook(unwatch).intercept(chain -> {
            Object result = chain.proceed();
            if (islandScreenReceiver != null && chain.getArg(0) == islandScreenReceiver) {
                islandScreenReceiver = null;
                main.post(SystemUiPrioritySection::requestListInvalidation);
            }
            return result;
        });
        Method register = ContentResolver.class.getMethod("registerContentObserver",
                Uri.class, boolean.class, ContentObserver.class);
        Method unregister = ContentResolver.class.getMethod("unregisterContentObserver", ContentObserver.class);
        module.hook(unregister).intercept(chain -> {
            Object result = chain.proceed();
            if (dateObserver != null && chain.getArg(0) == dateObserver) unbind((ContentObserver) chain.getArg(0));
            return result;
        });
        module.hook(register).intercept(chain -> {
            Object result = chain.proceed();
            if (ZEN.equals(chain.getArg(0)) && chain.getArg(2) instanceof ContentObserver observer
                    && isMusicCoverObject(observer)) {
                bind(module, (ContentResolver) chain.getThisObject(), observer);
            }
            return result;
        });
    }

    static boolean isModuleLoaded() {
        return islandScreenReceiver != null;
    }

    private static boolean isMusicCoverObject(Object callback) {
        ClassLoader loader = callback.getClass().getClassLoader();
        if (loader == null) return false;
        try {
            // Upstream keeps its LSPosed entry point across release builds and hot reloads.
            return Class.forName("com.os4.musiccover.Main", false, loader).getClassLoader() == loader;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private synchronized void bind(ModuleMain module, ContentResolver resolver, ContentObserver observer) {
        if (observer == dateObserver) return;
        try {
            Method changed = observer.getClass().getDeclaredMethod("onChange", boolean.class);
            Method getInt = Settings.Global.class.getMethod("getInt", ContentResolver.class, String.class, int.class);
            unbind(dateObserver);
            readHook = module.hook(getInt).intercept(chain -> {
                if (ZEN_MODE.equals(chain.getArg(1)) && chain.getArg(0) == dateRead.get()
                        && chain.getArg(0) != null) {
                    dateRead.remove();
                    return 0;
                }
                return chain.proceed();
            });
            onChangeHook = module.hook(changed).intercept(chain -> {
                if (chain.getThisObject() != dateObserver || !SystemUiReduceInterruptions.lockPillShowing()) {
                    return chain.proceed();
                }
                // The date callback reads zen_mode synchronously, then updates its existing artwork.
                dateRead.set(resolver);
                try {
                    return chain.proceed();
                } finally {
                    dateRead.remove();
                }
            });
            dateObserver = observer;
            refresh();
        } catch (ReflectiveOperationException | RuntimeException e) {
            unbind(dateObserver);
            Log.w("LocalAiNotif", "Could not synchronize HyperMusicCover's keyguard date", e);
        }
    }

    private synchronized void unbind(ContentObserver observer) {
        if (observer != dateObserver) return;
        dateObserver = null;
        if (onChangeHook != null) onChangeHook.unhook();
        if (readHook != null) readHook.unhook();
        onChangeHook = null;
        readHook = null;
    }

    void refresh() {
        ContentObserver observer = dateObserver;
        if (observer != null) observer.dispatchChange(false, ZEN);
    }
}
