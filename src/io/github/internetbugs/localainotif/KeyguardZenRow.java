package io.github.internetbugs.localainotif;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface.Invoker;

/** Controls only the DND part of HyperOS's keyguard notification-count row. */
final class KeyguardZenRow {
    private final Invoker<?, Method> render;
    private final Set<Object> nativeZenRows = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean reduceActive;

    KeyguardZenRow(ModuleMain module, ClassLoader loader) throws Exception {
        // This optimized collector is shared by several flows. Branch zero is isZenModeEnabled.
        Class<?> collector = loader.loadClass("com.miui.systemui.notification.view.viewbinder."
                + "NotificationNumStateViewBinder$bind$1$1$1$1");
        Field branch = collector.getDeclaredField("$r8$classId");
        branch.setAccessible(true);
        Method emit = collector.getDeclaredMethod("emit", Object.class,
                loader.loadClass("kotlin.coroutines.Continuation"));
        render = module.getInvoker(emit).setType(Invoker.Type.ORIGIN);
        module.hook(emit).intercept(chain -> {
            Object row = chain.getThisObject();
            if (branch.getInt(row) == 0 && chain.getArg(0) instanceof Boolean enabled) {
                if (enabled) {
                    nativeZenRows.add(row);
                    if (reduceActive) return chain.proceed(new Object[]{false, chain.getArg(1)});
                } else {
                    nativeZenRows.remove(row);
                }
            }
            return chain.proceed();
        });
    }

    void setReduceActive(boolean active) {
        if (active == reduceActive) return;
        reduceActive = active;
        for (Object row : nativeZenRows) {
            try {
                // Branch zero updates the view, posts its native animation and returns Unit synchronously.
                render.invoke(row, !active, null);
            } catch (Throwable error) {
                Log.w("LocalAiNotif", "Could not refresh keyguard DND row", error);
            }
        }
    }
}
