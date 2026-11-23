package com.localai.notif;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private static final String TAG = "LocalAiNotif";
    private static volatile Object sHyperNotifService;
    private static volatile Object sNotificationService;
    private static volatile NotificationSummaries sSummaries;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in process: " + param.getProcessName()
                + " (isSystemServer=" + param.isSystemServer() + ")");
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        hookSystemServer(param.getClassLoader());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        if ("com.miui.notification".equals(pkg)) {
            hookMiuiNotification(param.getDefaultClassLoader());
        } else if ("com.android.systemui".equals(pkg)) {
            SystemUiGroupText.install(this, param.getDefaultClassLoader());
            SystemUiPrioritySection.install(this, param.getDefaultClassLoader());
            SystemUiReduceInterruptions.install(this, param.getDefaultClassLoader());
        } else if ("com.android.settings".equals(pkg)) {
            hookSettings(param.getDefaultClassLoader());
        }
    }

    private void hookMiuiNotification(ClassLoader cl) {
        try {
            Class<?> sysUtilsClass = cl.loadClass("miui.notification.common.util.SystemUtils");

            // getModelStatus("llmtask", context) -> TRUE: 设置页开关点亮,不被重置;
            // isMiAccountLogin(context) -> true: 免账号门槛;
            // checkAndDownloadModel(...) -> null: 禁走小米云端下载。
            Method getModelStatus = sysUtilsClass.getDeclaredMethod("getModelStatus", String.class, Context.class);
            hook(getModelStatus).intercept(chain -> Boolean.TRUE);

            Method isMiAccountLogin = sysUtilsClass.getDeclaredMethod("isMiAccountLogin", Context.class);
            hook(isMiAccountLogin).intercept(chain -> Boolean.TRUE);

            Method checkAndDownloadModel = sysUtilsClass.getDeclaredMethod(
                    "checkAndDownloadModel",
                    String.class, String.class, String.class, int.class, boolean.class, Context.class);
            hook(checkAndDownloadModel).intercept(chain -> null);

            log(Log.INFO, TAG, "Hooked com.miui.notification SystemUtils");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook com.miui.notification", t);
        }
    }
    private void hookSettings(ClassLoader cl) {
        try {
            Class<?> notifSettingsClass = cl.loadClass("com.android.settings.NotificationStatusBarSettings");
            Method getAiPreSettings = notifSettingsClass.getDeclaredMethod("getAIPreSettings");
            hook(getAiPreSettings).intercept(chain -> Boolean.TRUE);
            log(Log.INFO, TAG, "Hooked com.android.settings NotificationStatusBarSettings.getAIPreSettings");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook com.android.settings", t);
        }
    }

    private void hookSystemServer(ClassLoader cl) {
        try {
            Class<?> hyper = cl.loadClass("com.android.server.notification.HyperNotificationManagerService");
            Class<?> nms = cl.loadClass("com.android.server.notification.NotificationManagerService");
            Class<?> record = cl.loadClass("com.android.server.notification.NotificationRecord");
            Class<?> impl = cl.loadClass("com.android.server.notification.NotificationManagerServiceImpl");
            Class<?> listeners = cl.loadClass("com.android.server.notification.NotificationManagerService$NotificationListeners");

            hook(findMethod(hyper, "isHighPowerDevices")).intercept(chain -> Boolean.TRUE);
            hook(findMethod(hyper, "init", Context.class)).intercept(chain -> {
                Context context = (Context) chain.getArg(0);
                android.provider.Settings.System.putInt(context.getContentResolver(), "ai_notification_setting", 1);
                Object result = chain.proceed();
                sHyperNotifService = chain.getThisObject();
                Field enabled = findField(hyper, "mNotificationEnabled");
                if (enabled != null) enabled.setBoolean(sHyperNotifService, true);
                startSummaries(cl);
                return result;
            });
            // Capture the NMS instance once it starts; the controller reads its committed list.
            hook(findMethod(nms, "onStart")).intercept(chain -> {
                Object result = chain.proceed();
                sNotificationService = chain.getThisObject();
                startSummaries(cl);
                return result;
            });
            // The controller reads the committed NMS list, including already summarized children.
            // Keep the native AI queue idle; native writeback is reused by the controller.
            hook(findMethod(hyper, "updateNotificationInternal", record, boolean.class)).intercept(chain -> null);
            hook(findMethod(impl, "replaceNotification", record, boolean.class)).intercept(chain -> null);
            for (Method method : listeners.getDeclaredMethods()) {
                if (!method.getName().equals("notifyPostedLocked")
                        && !method.getName().equals("prepareNotifyPostedLocked")
                        && !method.getName().equals("notifyRemovedLocked")
                        && !method.getName().equals("notifyRankingUpdateLocked")) continue;
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    refreshSummaries();
                    return result;
                });
            }

            Class<?> receiver = cl.loadClass("com.android.server.notification.HyperNotificationManagerService$1");
            hook(findMethod(receiver, "onReceive", Context.class, Intent.class)).intercept(chain -> {
                String action = ((Intent) chain.getArg(1)).getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)
                        || "ai.HyperNotification.alarm.TIMER".equals(action)) {
                    refreshSummaries();
                    return null;
                }
                return chain.proceed();
            });
            Class<?> worker = cl.loadClass("com.android.server.notification.HyperNotificationManagerService$WorkerHandler");
            hook(findMethod(worker, "handleMessage", Message.class)).intercept(chain -> {
                if (((Message) chain.getArg(0)).what == 1) {
                    refreshSummaries();
                    return null;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "Hooked live notification grouping and native summary writeback");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook system_server", t);
        }
    }

    private synchronized void startSummaries(ClassLoader cl) {
        if (sSummaries != null || sNotificationService == null || sHyperNotifService == null) return;
        try {
            // NMS.mContext field: "getContext" method is absent from the on-device NMS (dump verified).
            Context context = (Context) findField(sNotificationService.getClass(), "mContext")
                    .get(sNotificationService);
            sSummaries = new NotificationSummaries(this, context, sNotificationService, sHyperNotifService,
                    cl, () -> queryModuleConfig(context));
            log(Log.INFO, TAG, "Notification summary controller ready");
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to initialize notification summaries", e);
        }
    }

    private static void refreshSummaries() {
        NotificationSummaries summaries = sSummaries;
        if (summaries != null) summaries.refresh();
    }

    private static final long[] PROVIDER_RETRY_DELAYS_MS = {150L, 300L};

    private Bundle queryModuleConfig(Context ctx) {
        return callProvider(ctx, "get_config", null);
    }

    /**
     * Calls InferenceProvider with bounded retry to survive MIUI GreezeManager's
     * frozen-binder race (first synchronous call can get BR_FROZEN_REPLY ~2ms before
     * GreezeManager thaws the :inference process, causing ContentResolver.call to return null).
     */
    static Bundle callProvider(Context ctx, String method, Bundle input) {
        if (ctx == null) return null;
        Uri uri = Uri.parse("content://" + Config.AUTHORITY);
        for (int attempt = 0; attempt <= PROVIDER_RETRY_DELAYS_MS.length; attempt++) {
            try {
                Bundle reply = ctx.getContentResolver().call(uri, method, null, input);
                if (reply != null) return reply;
                if (attempt < PROVIDER_RETRY_DELAYS_MS.length) {
                    Log.w(TAG, "Provider call '" + method + "' returned null (attempt "
                            + (attempt + 1) + ", process may be thawing); retrying");
                }
            } catch (Throwable t) {
                if (attempt >= PROVIDER_RETRY_DELAYS_MS.length) {
                    Log.e(TAG, "Provider call '" + method + "' failed after retries", t);
                    return null;
                }
                Log.w(TAG, "Provider call '" + method + "' threw on attempt "
                        + (attempt + 1) + "; retrying: " + t.getMessage());
            }
            if (attempt < PROVIDER_RETRY_DELAYS_MS.length) {
                try {
                    Thread.sleep(PROVIDER_RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        Log.w(TAG, "Provider call '" + method + "' returned null after all retries");
        return null;
    }

    static Field findField(Class<?> cls, String name) {
        Class<?> curr = cls;
        while (curr != null) {
            try {
                Field f = curr.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                curr = curr.getSuperclass();
            }
        }
        return null;
    }

    static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        Class<?> curr = cls;
        while (curr != null) {
            try {
                Method m = curr.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                curr = curr.getSuperclass();
            }
        }
        return null;
    }
}
