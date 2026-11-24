package com.localai.notif;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.TextPaint;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 减少干扰模式超级岛专属效果：
 * <ul>
 *   <li>开启态（{@code "减少干扰已开启"}）：通过小米原生 {@code extras["miui.bigIsland.effect.src"]}
 *       直接点亮 HyperOS 4 超级小爱原生 SDF 内外流光边框（{@code LightBgView / LightBgShader}），
 *       同时精准 Hook 插件侧 {@code LightBgShader}（在「星云极光」风格下将 {@code uLightColors}
 *       无缝切为冷蓝紫星云色阶，绝不影响原生超级小爱卡片）及 {@code IslandRightTextViewHolder /
 *       IslandTextViewHolder} 给「减少干扰已开启」文字施加 AGSL 渐彩流光 + 柔白扫光动效。</li>
 *   <li>关闭态（{@code "减少干扰已关闭"}）：不附加发光边框与文字光效，维持素雅银灰。</li>
 * </ul>
 */
final class SystemUiReduceIsland {
    private static final String TAG = "LocalAi.ReduceIsland";
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";

    private static final String CNAME_PLUGIN_CONTROLLER =
            "com.android.systemui.statusbar.notification.DynamicIslandPluginController";
    private static final String CNAME_ISLAND_DATA =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData";
    private static final String METHOD_GET_CONTENT =
            "getContent$packages__apps__MiuiSystemUI__packages__SystemUI__miuiModules__"
                    + "Notification__android_common__MiuiNotification";

    private static final String CARD_KEY = "localai_reduce_island";
    private static final String PIC_KEY = "localai_reduce_sparkle";
    private static final String EXTRA_BIG_ISLAND_EFFECT = "miui.bigIsland.effect.src";
    private static final String EXTRA_LOCAL_ACTIVE = "localai.reduce_island_active";
    private static final long SHOW_MS = 3500L;
    private static final long SAFETY_REMOVE_DELAY_MS = SHOW_MS + 2500L;
    private static final long TEXT_SWEEP_PERIOD_MS = 1750L;

    private static ModuleMain module;
    private static Context systemUiContext;
    private static Object islandContent;
    private static Class<?> islandDataClass;

    private static Boolean lastAnnounced;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable safetyRemove = SystemUiReduceIsland::removeCard;

    // Deduplicate miui.systemui.plugin hooks per ClassLoader
    private static final Set<ClassLoader> hookedPluginLoaders =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> shaderNebulaApplied =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<TextView, IslandTextGlowController> activeTextControllers =
            new WeakHashMap<>();

    private static volatile boolean ourBigIslandGlowActive;
    private static volatile boolean ourBigIslandGlowXiaoAi = true;

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
                        if (systemUiContext != null) {
                            GlowStyle.initInSystemUi(systemUiContext);
                        }
                    }
                    installPluginHooks(m, res.getClass().getClassLoader());
                }
                return res;
            });
            Log.i(TAG, "DynamicIsland hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }

        // Also hook PluginFactory.createPluginContext so plugin hooks attach immediately on load/reload.
        try {
            Class<?> factory = loader.loadClass(
                    "com.android.systemui.shared.plugins.PluginInstance$PluginFactory");
            Method createContext = factory.getDeclaredMethod("createPluginContext");
            m.hook(createContext).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof Context ctx && PLUGIN_PACKAGE.equals(ctx.getPackageName())) {
                    GlowStyle.initInSystemUi(ctx);
                    installPluginHooks(m, ctx.getClassLoader());
                }
                return result;
            });
        } catch (Throwable t) {
            Log.w(TAG, "PluginFactory hook unavailable; will attach via DynamicIslandContent", t);
        }
    }

    private static synchronized void installPluginHooks(ModuleMain m, ClassLoader pluginLoader) {
        if (m == null || pluginLoader == null || hookedPluginLoaders.contains(pluginLoader)) return;
        hookedPluginLoaders.add(pluginLoader);
        hookBigIslandGlowPalette(m, pluginLoader);
        hookTextViewHolder(m, pluginLoader,
                "miui.systemui.dynamicisland.module.IslandRightTextViewHolder");
        hookTextViewHolder(m, pluginLoader,
                "miui.systemui.dynamicisland.module.IslandTextViewHolder");
    }

    /**
     * Tracks when our card activates Xiaomi's native Big Island glow ({@code LightBgShader})
     * and adapts {@code uLightColors} on the fly when the user chooses Nebula Aurora style,
     * while leaving Xiaomi's native Super XiaoAi colors untouched for Super XiaoAi style and
     * for all third-party/system islands.
     */
    private static void hookBigIslandGlowPalette(ModuleMain m, ClassLoader pluginLoader) {
        try {
            Class<?> utilsCls = pluginLoader.loadClass(
                    "miui.systemui.dynamicisland.DynamicIslandUtils");
            Method isGlowBig = ModuleMain.findMethod(
                    utilsCls, "isGlowEffectEnabledForBigState$miui_dynamicisland_release",
                    islandDataClass != null
                            ? islandDataClass
                            : pluginLoader.loadClass(CNAME_ISLAND_DATA));
            if (isGlowBig != null) {
                Method getKeyMethod = ModuleMain.findMethod(isGlowBig.getParameterTypes()[0], "getKey");
                m.hook(isGlowBig).intercept(chain -> {
                    Object res = chain.proceed();
                    boolean enabled = Boolean.TRUE.equals(res);
                    if (enabled && getKeyMethod != null) {
                        Object data = chain.getArg(0);
                        String key = data != null ? (String) getKeyMethod.invoke(data) : null;
                        boolean isOurs = CARD_KEY.equals(key);
                        ourBigIslandGlowActive = isOurs;
                        if (isOurs) {
                            ourBigIslandGlowXiaoAi = GlowStyle.isXiaoAi();
                        }
                    }
                    return res;
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "DynamicIslandUtils glow hook unavailable", t);
        }

        try {
            Class<?> shaderCls = pluginLoader.loadClass("com.mi.widget.shader.LightBgShader");
            Method updateShader = ModuleMain.findMethod(shaderCls, "updateShader");
            Method getTexShader = ModuleMain.findMethod(shaderCls, "getMTextureShader");
            Field origColorsField = ModuleMain.findField(shaderCls, "U_LIGHT_COLORS");
            if (updateShader != null && getTexShader != null && origColorsField != null) {
                m.hook(updateShader).intercept(chain -> {
                    try {
                        Object shaderObj = chain.getThisObject();
                        boolean wantNebula = ourBigIslandGlowActive && !ourBigIslandGlowXiaoAi;
                        Boolean lastApplied = shaderNebulaApplied.get(shaderObj);
                        if (lastApplied == null || lastApplied.booleanValue() != wantNebula) {
                            Object texObj = getTexShader.invoke(shaderObj);
                            if (texObj instanceof RuntimeShader texShader) {
                                float[] colors = wantNebula
                                        ? GlowStyle.NEBULA_ISLAND_LIGHT_COLORS_33
                                        : (float[]) origColorsField.get(null);
                                if (colors != null && colors.length == 33) {
                                    texShader.setFloatUniform("uLightColors", colors);
                                    shaderNebulaApplied.put(shaderObj, wantNebula);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked native LightBgShader for dual-style Super Island glow");
            }
        } catch (Throwable t) {
            Log.w(TAG, "LightBgShader hook unavailable", t);
        }
    }

    private static void hookTextViewHolder(ModuleMain m, ClassLoader pluginLoader, String className) {
        try {
            Class<?> holderCls = pluginLoader.loadClass(className);
            Class<?> templateCls = pluginLoader.loadClass(
                    "miui.systemui.dynamicisland.model.IslandTemplate");
            Class<?> dataCls = islandDataClass != null
                    ? islandDataClass
                    : pluginLoader.loadClass(CNAME_ISLAND_DATA);
            Method bindMethod = ModuleMain.findMethod(holderCls, "bind", templateCls, dataCls);
            Method updatePartialMethod = ModuleMain.findMethod(holderCls, "updatePartial", templateCls, dataCls);
            Field titleField = ModuleMain.findField(holderCls, "title");
            Method getKeyMethod = ModuleMain.findMethod(dataCls, "getKey");
            Method getExtrasMethod = ModuleMain.findMethod(dataCls, "getExtras");
            if (bindMethod == null || titleField == null || getKeyMethod == null) return;

            for (Method targetMethod : updatePartialMethod != null
                    ? new Method[]{bindMethod, updatePartialMethod}
                    : new Method[]{bindMethod}) {
                m.hook(targetMethod).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object holder = chain.getThisObject();
                        Object titleObj = titleField.get(holder);
                        if (!(titleObj instanceof TextView tv)) return result;

                        Object data = chain.getArg(1);
                        String key = data != null ? (String) getKeyMethod.invoke(data) : null;
                        boolean activeLocalAi = false;
                        if (CARD_KEY.equals(key) && getExtrasMethod != null) {
                            Bundle extras = (Bundle) getExtrasMethod.invoke(data);
                            activeLocalAi = extras != null && extras.getBoolean(EXTRA_LOCAL_ACTIVE, false);
                        }
                        if (activeLocalAi) {
                            attachTextGlow(tv, GlowStyle.isXiaoAi());
                        } else {
                            detachTextGlow(tv);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to apply Super Island text glow", t);
                    }
                    return result;
                });
            }
            Log.i(TAG, "Hooked " + className + " for Super Island text glow");
        } catch (Throwable t) {
            Log.w(TAG, "TextViewHolder hook unavailable for " + className, t);
        }
    }

    private static void attachTextGlow(TextView tv, boolean xiaoAi) {
        if (tv == null) return;
        IslandTextGlowController ctrl = activeTextControllers.get(tv);
        if (ctrl == null) {
            ctrl = new IslandTextGlowController(tv);
            activeTextControllers.put(tv, ctrl);
        }
        ctrl.start(xiaoAi);
    }

    private static void detachTextGlow(TextView tv) {
        if (tv == null) return;
        IslandTextGlowController ctrl = activeTextControllers.remove(tv);
        if (ctrl != null) {
            ctrl.release();
        }
    }

    /**
     * 仅在状态翻转时被 SystemUiReduceInterruptions.refreshUiAfterStateChange(主线程)调用。
     */
    static void onReduceChanged(boolean active) {
        Boolean last = lastAnnounced;
        if (last != null && last == active) {
            return;
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
            boolean xiaoAi = GlowStyle.isXiaoAi();
            String accent = active ? GlowStyle.islandFallbackHighlightHex(xiaoAi) : "#B4BAC6";
            String title = active ? "减少干扰已开启" : "减少干扰已关闭";

            Bundle pics = new Bundle();
            pics.putParcelable(PIC_KEY, islandIcon(active));
            Bundle extras = new Bundle();
            extras.putBundle("miui.focus.pics", pics);
            extras.putInt("miui.user.id", currentUserId());
            if (active) {
                // Triggers Xiaomi's native HyperOS 4 Super XiaoAi Big Island plasma border glow
                extras.putString(EXTRA_BIG_ISLAND_EFFECT, "localai_reduce_glow");
                extras.putBoolean(EXTRA_LOCAL_ACTIVE, true);
                ourBigIslandGlowActive = true;
                ourBigIslandGlowXiaoAi = xiaoAi;
            } else {
                ourBigIslandGlowActive = false;
            }

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

            removeCard();
            Method add = islandContent.getClass().getMethod(
                    "addDynamicIslandView", islandDataClass, boolean.class);
            add.invoke(islandContent, data, Boolean.FALSE);
            Log.i(TAG, "island card shown: " + title + " (glow=" + (active ? GlowStyle.currentKey() : "off") + ")");
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

    /**
     * Drives the flowing multi-stop gradient + soft-white optical light sweep directly on the
     * Super Island title {@link TextView} while attached and visible, with zero per-frame allocations
     * and full cleanup upon detach or view recycling.
     */
    private static final class IslandTextGlowController
            implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        private final TextView target;
        private final float density;
        private final RuntimeShader agslShader;
        private final Matrix fallbackMatrix = new Matrix();
        private LinearGradient fallbackGradient;
        private ValueAnimator animator;
        private boolean xiaoAi = true;
        private boolean active;
        private float progress;

        IslandTextGlowController(TextView target) {
            this.target = target;
            this.density = target.getResources().getDisplayMetrics().density;
            this.agslShader = GlowStyle.createIslandTextGlowShader();
            target.addOnAttachStateChangeListener(this);
            target.addOnLayoutChangeListener(this);
        }

        void start(boolean useXiaoAi) {
            xiaoAi = useXiaoAi;
            active = true;
            fallbackGradient = null;
            target.setShadowLayer(3.2f * density, 0f, 0f, GlowStyle.islandTextShadowColor(xiaoAi));
            applyShaderFrame(0.18f);
            if (!ValueAnimator.areAnimatorsEnabled()) {
                stopAnimator();
                target.invalidate();
                return;
            }
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(TEXT_SWEEP_PERIOD_MS);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(anim -> {
                if (!active || !target.isAttachedToWindow()) {
                    stopAnimator();
                    return;
                }
                progress = (float) anim.getAnimatedValue();
                applyShaderFrame(progress);
                target.invalidate();
            });
            animator.start();
        }

        void release() {
            active = false;
            stopAnimator();
            target.removeOnAttachStateChangeListener(this);
            target.removeOnLayoutChangeListener(this);
            TextPaint paint = target.getPaint();
            if (paint != null && paint.getShader() != null) {
                paint.setShader(null);
            }
            target.setShadowLayer(0f, 0f, 0f, 0);
            target.invalidate();
        }

        private void stopAnimator() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        private void applyShaderFrame(float t) {
            if (!active) return;
            TextPaint paint = target.getPaint();
            if (paint == null) return;
            Layout layout = target.getLayout();
            float originX = (layout != null && layout.getLineCount() > 0) ? layout.getLineLeft(0) : 0f;
            float textWidth = measureSpanWidth(paint, layout);
            if (agslShader != null) {
                // Sweep white highlight softly from left (-0.32) to right (+1.32) while drifting gradient
                float sheenPos = -0.32f + 1.64f * t;
                agslShader.setFloatUniform("uOriginX", originX);
                agslShader.setFloatUniform("uWidth", textWidth);
                agslShader.setFloatUniform("uPhase", t * 0.45f);
                agslShader.setFloatUniform("uSheenPos", sheenPos);
                agslShader.setFloatUniform("uStyle", xiaoAi ? 1.0f : 0.0f);
                agslShader.setFloatUniform("uDark", 1.0f);
                paint.setShader(agslShader);
                return;
            }
            if (fallbackGradient == null) {
                int[] colors = xiaoAi
                        ? new int[]{0xFFFFB06E, 0xFFFF6CA8, 0xFFFFF8F2, 0xFFC89BFF, 0xFF72C2FF, 0xFFFFB06E}
                        : new int[]{0xFF7CEBFF, 0xFF8CC4FF, 0xFFF4FAFF, 0xFFCDB4FF, 0xFF7CEBFF};
                fallbackGradient = new LinearGradient(
                        0f, 0f, Math.max(1f, textWidth), 0f,
                        colors, null, Shader.TileMode.REPEAT);
            }
            fallbackMatrix.setTranslate(originX + textWidth * t, 0f);
            fallbackGradient.setLocalMatrix(fallbackMatrix);
            paint.setShader(fallbackGradient);
        }

        private float measureSpanWidth(TextPaint paint, Layout layout) {
            if (layout != null && layout.getLineCount() > 0) {
                float lw = layout.getLineWidth(0);
                if (lw > 12f * density) return lw;
            }
            CharSequence text = target.getText();
            if (text != null && text.length() > 0) {
                float measured = paint.measureText(text, 0, text.length());
                if (measured > 12f * density) return measured;
            }
            float w = target.getWidth() - target.getPaddingLeft() - target.getPaddingRight();
            return Math.max(w > 12f * density ? w : 80f * density, 1f);
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            if (active) {
                applyShaderFrame(progress);
                if (animator != null && !animator.isRunning() && ValueAnimator.areAnimatorsEnabled()) {
                    animator.start();
                }
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            activeTextControllers.remove(target);
            release();
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (active && (right - left) != (oldRight - oldLeft)) {
                fallbackGradient = null;
                applyShaderFrame(progress);
            }
        }
    }
}
