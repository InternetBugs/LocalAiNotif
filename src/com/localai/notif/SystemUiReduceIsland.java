package com.localai.notif;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 超级岛专属效果:减少干扰开/关切换瞬间,在超级岛弹出 Apple Intelligence 风格虹彩交织丝带+文案卡。
 * 复刻充电岛链路(DeviceNotificationListenerImpl):DynamicIslandData.tickerData
 * 携带 IslandTemplate JSON(IslandTemplateFactory 由此构建卡片视图),图标走
 * extras["miui.focus.pics"] 的 Parcelable Icon,priority/properties 与充电岛一致。
 * 动画、布局、停留时长全部跟随原生模板,不额外自绘窗口。
 */
final class SystemUiReduceIsland {
    private static final String TAG = "LocalAi.ReduceIsland";

    private static final String CNAME_PLUGIN_CONTROLLER =
            "com.android.systemui.statusbar.notification.DynamicIslandPluginController";
    private static final String CNAME_ISLAND_DATA =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData";
    /**
     * MIUI 插件 gradle 生成的动态 getter,
     * 见 jadx_main/sources/com/android/systemui/statusbar/notification/DynamicIslandPluginController.java:70
     */
    private static final String METHOD_GET_CONTENT =
            "getContent$packages__apps__MiuiSystemUI__packages__SystemUI__miuiModules__"
                    + "Notification__android_common__MiuiNotification";

    private static final String CARD_KEY = "localai_reduce_island";
    private static final String PIC_KEY = "localai_reduce_sparkle";
    private static final long SHOW_MS = 3500L;
    private static final long SAFETY_REMOVE_DELAY_MS = SHOW_MS + 2500L;

    private static ModuleMain module;
    private static Context systemUiContext;
    private static Object islandContent; // DynamicIslandContent 运行时实例
    private static Class<?> islandDataClass;

    private static Boolean lastAnnounced;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable safetyRemove = () -> removeCard();

    static void install(ModuleMain m, ClassLoader loader) {
        module = m;
        try {
            Class<?> ctrlClass = loader.loadClass(CNAME_PLUGIN_CONTROLLER);
            islandDataClass = loader.loadClass(CNAME_ISLAND_DATA);
            Method getContent = ModuleMain.findMethod(ctrlClass, METHOD_GET_CONTENT);
            if (getContent == null) {
                Log.w(TAG, "getContent$...MiuiNotification not found; island effect disabled");
                return;
            }
            m.hook(getContent).intercept(chain -> {
                Object res = chain.proceed();
                if (res != null) {
                    islandContent = res;
                    if (systemUiContext == null) {
                        systemUiContext = (Context) ModuleMain.findField(
                                ctrlClass, "context").get(chain.getThisObject());
                    }
                }
                return res;
            });
            Log.i(TAG, "DynamicIsland hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }

    /**
     * 仅在状态翻转时被 SystemUiReduceInterruptions.refreshUiAfterStateChange(主线程)调用。
     */
    static void onReduceChanged(boolean active) {
        Boolean last = lastAnnounced;
        if (last != null && last == active) {
            return; // 无翻转,不重复弹卡
        }
        lastAnnounced = active;
        mainHandler.removeCallbacks(safetyRemove);
        showCard(active);
        mainHandler.postDelayed(safetyRemove, SAFETY_REMOVE_DELAY_MS);
    }

    private static void showCard(boolean active) {
        if (islandContent == null || islandDataClass == null || systemUiContext == null) {
            Log.w(TAG, "island content not ready, skip card");
            return;
        }
        try {
            // Super Island background is always pure black: luminous Apple Intelligence
            // periwinkle-violet when active, calm muted silver when turned off.
            String accent = active ? "#B4A4FF" : "#B4BAC6";
            String title = active ? "减少干扰已开启" : "减少干扰已关闭";

            Bundle pics = new Bundle();
            pics.putParcelable(PIC_KEY, islandIcon(active));
            Bundle extras = new Bundle();
            extras.putBundle("miui.focus.pics", pics);
            extras.putInt("miui.user.id", currentUserId());

            String json = "{\"islandTimeout\":" + SHOW_MS
                    + ",\"highlightColor\":\"" + accent + "\""
                    + ",\"bigIslandArea\":{\"imageTextInfoLeft\":{\"type\":1,"
                    + "\"picInfo\":{\"pic\":\"" + PIC_KEY + "\",\"type\":4}},"
                    + "\"textInfo\":{\"title\":\"" + title + "\","
                    + "\"showHighlightColor\":true,\"turnAnim\":false}}}";

            Object data = islandDataClass.getDeclaredConstructor().newInstance();
            ModuleMain.findMethod(islandDataClass, "setKey", String.class).invoke(data, CARD_KEY);
            ModuleMain.findMethod(islandDataClass, "setTickerData", String.class).invoke(data, json);
            ModuleMain.findMethod(islandDataClass, "setProperties", Integer.class)
                    .invoke(data, Integer.valueOf(0));
            ModuleMain.findMethod(islandDataClass, "setPriority", Integer.class)
                    .invoke(data, Integer.valueOf(1));
            ModuleMain.findMethod(islandDataClass, "setExtras", Bundle.class).invoke(data, extras);

            // 同 key 重入前先移除旧卡,避免 islands 队列里堆积。
            removeCard();
            Method add = islandContent.getClass().getMethod(
                    "addDynamicIslandView", islandDataClass, boolean.class);
            add.invoke(islandContent, data, Boolean.FALSE);
            Log.i(TAG, "island card shown: " + title);
        } catch (Throwable t) {
            Log.e(TAG, "showCard failed", t);
        }
    }

    private static void removeCard() {
        Object content = islandContent;
        if (content == null) return;
        try {
            Method remove = content.getClass().getMethod("removeDynamicIslandView", String.class);
            remove.invoke(content, CARD_KEY);
        } catch (Throwable t) {
            Log.w(TAG, "removeCard failed", t);
        }
    }

    private static Icon islandIcon(boolean active) {
        return ReduceInterruptions.createIslandIcon(systemUiContext, active);
    }

    private static int currentUserId() {
        try {
            Method m = ActivityManager.class.getDeclaredMethod("getCurrentUser");
            m.setAccessible(true);
            return (Integer) m.invoke(null);
        } catch (Throwable t) {
            return 0;
        }
    }
}
