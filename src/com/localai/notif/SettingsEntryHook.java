package com.localai.notif;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Injects an optional native-styled entry ("端侧AI通知总结") directly below
 * "小米超级岛" (key="dynamic_island") inside HyperOS Settings'
 * {@code NotificationStatusBarSettings}, synced live via {@link InferenceProvider}.
 */
final class SettingsEntryHook {
    private static final String TAG = "LocalAiNotif";
    private static final Uri PROVIDER = Uri.parse("content://" + Config.AUTHORITY);
    private static final String TARGET_FRAGMENT = "com.android.settings.NotificationStatusBarSettings";
    private static final String ANCHOR_KEY = "dynamic_island";
    private static final String REF_CATEGORY_CHILD_KEY = "notification_managing";
    private static final String FALLBACK_ANCHOR_KEY = "notification_settings";
    private static final String TOP_GROUP_KEY = "localai_notif_top_group";
    private static final String ENTRY_KEY = "localai_notif_settings_entry";
    private static final String ENTRY_TITLE = "端侧AI通知总结";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LocalAiNotif-settings-entry");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean sHasCachedShow;
    private static volatile boolean sCachedShow = Config.DEFAULT_SHOW_IN_SYSTEM_SETTINGS;

    private SettingsEntryHook() {}

    static void install(ModuleMain module, ClassLoader cl) {
        try {
            Class<?> fragmentClass = cl.loadClass(TARGET_FRAGMENT);
            Method onCreate = fragmentClass.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                syncEntry(chain.getThisObject(), cl);
                return result;
            });

            Method onResume = fragmentClass.getDeclaredMethod("onResume");
            module.hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                syncEntry(chain.getThisObject(), cl);
                return result;
            });

            module.log(Log.INFO, TAG, "Hooked NotificationStatusBarSettings for system settings entry");
            Log.i(TAG, "Hooked NotificationStatusBarSettings for system settings entry");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to install SettingsEntryHook", t);
        }
    }

    private static void syncEntry(Object fragment, ClassLoader cl) {
        if (fragment == null) return;
        if (sHasCachedShow) {
            applyEntryState(fragment, cl, sCachedShow);
        }
        Context rawCtx = getFragmentContext(fragment);
        if (rawCtx == null) return;
        Context appCtx = rawCtx.getApplicationContext();
        if (appCtx == null) appCtx = rawCtx;

        final Context queryCtx = appCtx;
        final WeakReference<Object> fragmentRef = new WeakReference<>(fragment);
        EXECUTOR.execute(() -> {
            boolean show = Config.DEFAULT_SHOW_IN_SYSTEM_SETTINGS;
            boolean ok = false;
            try {
                Bundle cfg = queryCtx.getContentResolver().call(PROVIDER, "get_config", null, null);
                if (cfg != null) {
                    show = cfg.getBoolean(
                            Config.KEY_SHOW_IN_SYSTEM_SETTINGS,
                            Config.DEFAULT_SHOW_IN_SYSTEM_SETTINGS);
                    ok = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to query system settings entry config: " + t.getMessage());
            }
            if (!ok) {
                if (sHasCachedShow) {
                    show = sCachedShow;
                } else {
                    return;
                }
            } else {
                sCachedShow = show;
                sHasCachedShow = true;
            }
            final boolean finalShow = show;
            MAIN_HANDLER.post(() -> {
                Object frag = fragmentRef.get();
                if (frag == null || !isFragmentActive(frag)) return;
                applyEntryState(frag, cl, finalShow);
            });
        });
    }

    private static void applyEntryState(Object fragment, ClassLoader cl, boolean show) {
        try {
            Object screen = invokeNoArg(fragment, "getPreferenceScreen");
            if (screen == null) return;

            Class<?> prefBase = cl.loadClass("androidx.preference.Preference");
            Class<?> catBase = cl.loadClass("androidx.preference.PreferenceCategory");

            Object existing = findPreference(screen, ENTRY_KEY);
            Object topGroup = findPreference(screen, TOP_GROUP_KEY);
            Object anchor = findPreference(screen, ANCHOR_KEY);

            if (!show) {
                if (existing != null) {
                    Object existingParent = invokeNoArg(existing, "getParent");
                    if (existingParent == null) existingParent = screen;
                    invokeOneArg(existingParent, "removePreference", prefBase, existing);
                }
                if (topGroup != null) {
                    int groupOrder = getPreferenceOrder(topGroup, 0);
                    if (anchor != null && invokeNoArg(anchor, "getParent") == topGroup) {
                        invokeOneArg(topGroup, "removePreference", prefBase, anchor);
                        invokeOneArg(screen, "removePreference", prefBase, topGroup);
                        invokeOneArg(anchor, "setOrder", int.class, groupOrder);
                        invokeOneArg(screen, "addPreference", prefBase, anchor);
                    } else {
                        invokeOneArg(screen, "removePreference", prefBase, topGroup);
                    }
                }
                return;
            }

            Context prefCtx = resolvePreferenceContext(anchor, screen, fragment);
            if (prefCtx == null) return;

            // Wrap "dynamic_island" and our entry inside a zero-height empty PreferenceCategory
            // so Miuix PreferenceGroupAdapter merges both rows into one unified white card.
            Object targetContainer;
            int targetOrder;
            if (anchor != null) {
                Object anchorParent = invokeNoArg(anchor, "getParent");
                if (anchorParent != null && catBase.isInstance(anchorParent)) {
                    targetContainer = anchorParent;
                    invokeOneArg(targetContainer, "setOrderingAsAdded", boolean.class, Boolean.FALSE);
                    targetOrder = normalizeOrdersAndComputeTarget(targetContainer, anchor);
                } else {
                    Object group = (topGroup != null && catBase.isInstance(topGroup))
                            ? topGroup
                            : createMergedTopCategory(screen, prefCtx, cl);
                    if (group != null) {
                        invokeOneArg(screen, "setOrderingAsAdded", boolean.class, Boolean.FALSE);
                        int anchorOrder = normalizeTopLevelOrders(screen, anchor);
                        if (anchorParent != null) {
                            invokeOneArg(anchorParent, "removePreference", prefBase, anchor);
                        } else {
                            invokeOneArg(screen, "removePreference", prefBase, anchor);
                        }
                        if (invokeNoArg(group, "getParent") == null) {
                            invokeOneArg(group, "setOrder", int.class, anchorOrder);
                            invokeOneArg(screen, "addPreference", prefBase, group);
                        }
                        invokeOneArg(group, "setOrderingAsAdded", boolean.class, Boolean.FALSE);
                        invokeOneArg(anchor, "setOrder", int.class, 0);
                        invokeOneArg(group, "addPreference", prefBase, anchor);
                        targetContainer = group;
                        targetOrder = 10;
                    } else {
                        targetContainer = screen;
                        invokeOneArg(screen, "setOrderingAsAdded", boolean.class, Boolean.FALSE);
                        targetOrder = normalizeOrdersAndComputeTarget(screen, anchor);
                    }
                }
            } else {
                targetContainer = screen;
                invokeOneArg(screen, "setOrderingAsAdded", boolean.class, Boolean.FALSE);
                targetOrder = normalizeOrdersAndComputeTarget(screen, null);
            }

            if (existing != null) {
                Object existingParent = invokeNoArg(existing, "getParent");
                if (existingParent == targetContainer) {
                    invokeOneArg(existing, "setOrder", int.class, targetOrder);
                    invokeOneArg(existing, "setVisible", boolean.class, Boolean.TRUE);
                    return;
                }
                if (existingParent != null) {
                    invokeOneArg(existingParent, "removePreference", prefBase, existing);
                }
                invokeOneArg(existing, "setOrder", int.class, targetOrder);
                invokeOneArg(targetContainer, "addPreference", prefBase, existing);
                return;
            }

            Object entry = instantiateNativePreference(anchor, prefCtx, cl);
            if (entry == null) return;

            if (anchor != null) {
                Object layoutRes = invokeNoArg(anchor, "getLayoutResource");
                if (layoutRes instanceof Integer && (Integer) layoutRes != 0) {
                    invokeOneArg(entry, "setLayoutResource", int.class, layoutRes);
                }
                Object widgetRes = invokeNoArg(anchor, "getWidgetLayoutResource");
                if (widgetRes instanceof Integer && (Integer) widgetRes != 0) {
                    invokeOneArg(entry, "setWidgetLayoutResource", int.class, widgetRes);
                }
            }

            invokeOneArg(entry, "setKey", String.class, ENTRY_KEY);
            invokeOneArg(entry, "setTitle", CharSequence.class, ENTRY_TITLE);
            invokeOneArg(entry, "setPersistent", boolean.class, Boolean.FALSE);
            invokeOneArg(entry, "setShouldDisableView", boolean.class, Boolean.TRUE);
            invokeOneArg(entry, "setIntent", Intent.class, buildLaunchIntent());
            try {
                invokeOneArg(entry, "setShowRightArrow", boolean.class, Boolean.TRUE);
            } catch (Throwable ignored) {
            }
            invokeOneArg(entry, "setOrder", int.class, targetOrder);

            attachClickListener(entry, fragment, prefCtx, cl);
            invokeOneArg(targetContainer, "addPreference", prefBase, entry);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to update system settings entry", t);
        }
    }

    private static Context resolvePreferenceContext(Object anchor, Object screen, Object fragment) {
        try {
            if (anchor != null) {
                Object ctx = invokeNoArg(anchor, "getContext");
                if (ctx instanceof Context) return (Context) ctx;
            }
            Object ctx = invokeNoArg(screen, "getContext");
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {
        }
        return getFragmentContext(fragment);
    }

    private static Object createMergedTopCategory(Object screen, Context prefCtx, ClassLoader cl) {
        Object category = null;
        try {
            Object refChild = findPreference(screen, REF_CATEGORY_CHILD_KEY);
            if (refChild != null) {
                Object refParent = invokeNoArg(refChild, "getParent");
                if (refParent != null) {
                    category = tryNewPreference(refParent.getClass(), prefCtx);
                }
            }
        } catch (Throwable ignored) {
        }
        if (category == null) {
            String[] candidates = {
                    "com.android.settingslib.miuisettings.preference.PreferenceCategory",
                    "androidx.preference.PreferenceCategory"
            };
            for (String name : candidates) {
                try {
                    category = tryNewPreference(cl.loadClass(name), prefCtx);
                    if (category != null) break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (category == null) return null;
        try {
            invokeOneArg(category, "setKey", String.class, TOP_GROUP_KEY);
            invokeOneArg(category, "setPersistent", boolean.class, Boolean.FALSE);
            invokeOneArg(category, "setTitle", CharSequence.class, null);
            int emptyLayout = prefCtx.getResources().getIdentifier(
                    "miuix_preference_category_layout_empty", "layout", prefCtx.getPackageName());
            if (emptyLayout != 0) {
                invokeOneArg(category, "setLayoutResource", int.class, emptyLayout);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to configure merged top PreferenceCategory: " + t.getMessage());
        }
        return category;
    }

    private static int normalizeTopLevelOrders(Object screen, Object anchor) throws Exception {
        Object countObj = invokeNoArg(screen, "getPreferenceCount");
        int count = countObj instanceof Integer ? (Integer) countObj : 0;
        List<Object> children = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object child = invokeOneArg(screen, "getPreference", int.class, i);
            if (child != null) {
                Object key = invokeNoArg(child, "getKey");
                if (!ENTRY_KEY.equals(key) && !TOP_GROUP_KEY.equals(key)) {
                    children.add(child);
                }
            }
        }
        int anchorOrder = 0;
        for (int i = 0; i < children.size(); i++) {
            Object child = children.get(i);
            int order = i * 10;
            invokeOneArg(child, "setOrder", int.class, order);
            if (child == anchor) {
                anchorOrder = order;
            }
        }
        return anchorOrder;
    }

    private static int getPreferenceOrder(Object pref, int fallback) {
        try {
            Object order = invokeNoArg(pref, "getOrder");
            if (order instanceof Integer) return (Integer) order;
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int normalizeOrdersAndComputeTarget(Object parent, Object anchor) throws Exception {
        Object countObj = invokeNoArg(parent, "getPreferenceCount");
        int count = countObj instanceof Integer ? (Integer) countObj : 0;
        List<Object> children = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object child = invokeOneArg(parent, "getPreference", int.class, i);
            if (child != null) {
                Object key = invokeNoArg(child, "getKey");
                if (!ENTRY_KEY.equals(key)) {
                    children.add(child);
                }
            }
        }
        int anchorOrder = -1;
        int fallbackOrder = -1;
        for (int i = 0; i < children.size(); i++) {
            Object child = children.get(i);
            int order = i * 10;
            invokeOneArg(child, "setOrder", int.class, order);
            if (anchor != null && child == anchor) {
                anchorOrder = order;
            } else if (fallbackOrder < 0) {
                Object key = invokeNoArg(child, "getKey");
                if (FALLBACK_ANCHOR_KEY.equals(key)) {
                    fallbackOrder = order;
                }
            }
        }
        if (anchorOrder >= 0) {
            return anchorOrder + 5;
        }
        if (fallbackOrder >= 0) {
            return fallbackOrder - 5;
        }
        return -5;
    }

    private static Object instantiateNativePreference(Object anchor, Context prefCtx, ClassLoader cl) {
        if (anchor != null) {
            Object created = tryNewPreference(anchor.getClass(), prefCtx);
            if (created != null) return created;
        }
        String[] candidates = {
                "com.android.settingslib.miuisettings.preference.Preference",
                "androidx.preference.Preference"
        };
        for (String name : candidates) {
            try {
                Object created = tryNewPreference(cl.loadClass(name), prefCtx);
                if (created != null) return created;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object tryNewPreference(Class<?> cls, Context ctx) {
        try {
            Constructor<?> ctor = cls.getConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ctx);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void attachClickListener(
            Object entry,
            Object fragment,
            Context fallbackCtx,
            ClassLoader cl) throws Exception {
        Class<?> listenerIface = cl.loadClass("androidx.preference.Preference$OnPreferenceClickListener");
        WeakReference<Object> fragmentRef = new WeakReference<>(fragment);
        Context appCtx = fallbackCtx.getApplicationContext();
        Context launchFallback = appCtx != null ? appCtx : fallbackCtx;

        Object listener = Proxy.newProxyInstance(
                cl,
                new Class<?>[]{listenerIface},
                (proxy, method, args) -> {
                    if ("onPreferenceClick".equals(method.getName())) {
                        launchModuleSettings(fragmentRef.get(), launchFallback);
                        return Boolean.TRUE;
                    }
                    if ("toString".equals(method.getName())) {
                        return "LocalAiNotifSettingsEntryClickListener";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                    return null;
                });

        invokeOneArg(entry, "setOnPreferenceClickListener", listenerIface, listener);
    }

    private static Intent buildLaunchIntent() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.localai.notif", "com.localai.notif.MainActivity"));
        intent.putExtra(Config.EXTRA_FROM_SYSTEM_SETTINGS, true);
        return intent;
    }

    private static void launchModuleSettings(Object fragment, Context fallbackCtx) {
        Intent intent = buildLaunchIntent();

        Activity hostActivity = null;
        if (fragment != null) {
            try {
                Object act = invokeNoArg(fragment, "getActivity");
                if (act instanceof Activity) {
                    hostActivity = (Activity) act;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            if (hostActivity != null && !hostActivity.isFinishing() && !hostActivity.isDestroyed()) {
                // Launch inside System Settings task stack so back returns to Notification & Status Bar.
                hostActivity.startActivity(intent);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Activity launch failed, falling back to NEW_TASK: " + t.getMessage());
        }

        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            fallbackCtx.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch LocalAiNotif MainActivity", t);
        }
    }

    private static boolean isFragmentActive(Object fragment) {
        try {
            Object added = invokeNoArg(fragment, "isAdded");
            Object detached = invokeNoArg(fragment, "isDetached");
            if (Boolean.FALSE.equals(added) || Boolean.TRUE.equals(detached)) {
                return false;
            }
            return getFragmentContext(fragment) != null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static Context getFragmentContext(Object fragment) {
        try {
            Object ctx = invokeNoArg(fragment, "getContext");
            if (ctx instanceof Context) return (Context) ctx;
            Object act = invokeNoArg(fragment, "getActivity");
            if (act instanceof Context) return (Context) act;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findPreference(Object prefGroup, String key) {
        try {
            return invokeOneArg(prefGroup, "findPreference", CharSequence.class, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method m = findMethodHierarchy(target.getClass(), name);
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name + "()");
        }
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object invokeOneArg(Object target, String name, Class<?> paramType, Object arg)
            throws Exception {
        Method m = findMethodHierarchy(target.getClass(), name, paramType);
        if (m == null) {
            throw new NoSuchMethodException(
                    target.getClass().getName() + "." + name + "(" + paramType.getName() + ")");
        }
        m.setAccessible(true);
        return m.invoke(target, arg);
    }

    private static Method findMethodHierarchy(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            try {
                return cur.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
