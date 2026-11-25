package io.github.internetbugs.localainotif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared visual glow theme state, palettes, and AGSL {@link RuntimeShader} factories
 * bridging the two supported styles across SystemUI and the Settings previews:
 * <ul>
 *   <li><b>Super XiaoAi Glow (超级小爱流光, default)</b>: Warm Tangerine-Orange ↔ Luminous
 *       Coral-Rose ↔ Super XiaoAi Lavender-Violet ↔ Electric Cobalt-Blue, calibrated against
 *       Xiaomi HyperOS 4's native Super XiaoAi Super Island glow shaders.</li>
 *   <li><b>Nebula Aurora (星云极光)</b>: Cool Sky Cyan → Azure → Ice Violet → Moon White
 *       translucent liquid-glass aesthetic.</li>
 * </ul>
 */
final class GlowStyle {
    private static final String TAG = "LocalAi.GlowStyle";

    // Super XiaoAi Dark / Lockscreen / Super Island signature anchors (aligned with HyperOS 7K0.agsl)
    static final int XIAOAI_DARK_ORANGE = 0xFFFF9238;
    static final int XIAOAI_DARK_ROSE = 0xFFFF408A;
    static final int XIAOAI_DARK_VIOLET = 0xFFB86BFF;
    static final int XIAOAI_DARK_BLUE = 0xFF3694FF;
    static final int XIAOAI_DARK_PEACH_CORE = 0xFFFFF4EA;

    // Super XiaoAi Light Notification Shade anchors (deeper chroma to stay crisp on white glass)
    static final int XIAOAI_LIGHT_ORANGE = 0xFFF26D1F;
    static final int XIAOAI_LIGHT_ROSE = 0xFFE52B7A;
    static final int XIAOAI_LIGHT_VIOLET = 0xFF8C46F2;
    static final int XIAOAI_LIGHT_BLUE = 0xFF1E78F5;
    static final int XIAOAI_LIGHT_CORE = 0xFFFFFFFF;

    // Nebula Aurora Dark / Light anchors (original cool liquid-glass palette)
    static final int NEBULA_DARK_CYAN = 0xFF5CE1FF;
    static final int NEBULA_DARK_AZURE = 0xFF3D8BFF;
    static final int NEBULA_DARK_VIOLET = 0xFFA17DFF;
    static final int NEBULA_DARK_MOON = 0xFFEDF6FF;

    static final int NEBULA_LIGHT_CYAN = 0xFF1CA3EC;
    static final int NEBULA_LIGHT_AZURE = 0xFF2B75F0;
    static final int NEBULA_LIGHT_VIOLET = 0xFF7458F2;
    static final int NEBULA_LIGHT_MOON = 0xFFD6E9FF;

    /**
     * 11-light RGB triplets (33 floats) for Xiaomi's native {@code LightBgShader}
     * ({@code uLightColors[11]} in {@code 7K0.agsl}) when rendering the Super Island border
     * under the cool Nebula Aurora style.
     */
    static final float[] NEBULA_ISLAND_LIGHT_COLORS_33 = {
            0.361f, 0.882f, 1.000f, // 0: Sky Cyan
            0.631f, 0.490f, 1.000f, // 1: Ice Violet
            0.239f, 0.545f, 1.000f, // 2: Azure Core
            0.840f, 0.945f, 1.000f, // 3: Moon White-Cyan
            0.239f, 0.545f, 1.000f, // 4: Azure Core
            0.361f, 0.882f, 1.000f, // 5: Sky Cyan
            0.631f, 0.490f, 1.000f, // 6: Ice Violet
            0.239f, 0.545f, 1.000f, // 7: Azure Core
            0.631f, 0.490f, 1.000f, // 8: Ice Violet
            0.361f, 0.882f, 1.000f, // 9: Sky Cyan
            0.840f, 0.945f, 1.000f  // 10: Moon White-Cyan
    };

    /**
     * Unified AGSL perimeter-glow shader for the merged Priority Notifications card,
     * supporting both {@code 超级小爱流光} ({@code uStyle = 1}) and {@code 星云极光}
     * ({@code uStyle = 0}):
     * <ul>
     *   <li>{@code uMode < 0.5}: Silky Gaussian-feathered multi-color outer halo blooming
     *       purely outside the unified card silhouette (drawn behind children).</li>
     *   <li>{@code uMode >= 0.5}: Crisp, refined thin glass rim (~1.2dp) hugging the card
     *       boundary with whisper-tight edge catchlight (zero wide inward haze so text,
     *       internal dividers, and Footer stay 100% crisp) + twin traveling specular
     *       flares during the 1.6s one-shot reveal sweep.</li>
     * </ul>
     */
    private static final String PRIORITY_PERIMETER_AGSL =
            "uniform float4 uBounds;\n"
            + "uniform float2 uRadii;\n"
            + "uniform float uDensity;\n"
            + "uniform float uAngle;\n"
            + "uniform float uShimmer;\n"
            + "uniform float uAlpha;\n"
            + "uniform float uDark;\n"
            + "uniform float uStyle;\n"
            + "uniform float uMode;\n"
            + "\n"
            + "float sdRoundedRect(float2 p, float2 halfSize, float topR, float botR) {\n"
            + "    float r = (p.y < 0.0) ? topR : botR;\n"
            + "    r = min(r, min(halfSize.x, halfSize.y));\n"
            + "    float2 q = abs(p) - halfSize + r;\n"
            + "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n"
            + "}\n"
            + "\n"
            + "float3 sampleXiaoAiSpectrum(float2 d, float dark) {\n"
            + "    // Calibrated to Figma dark-glass perimeter reference & Super XiaoAi palette:\n"
            + "    // top-left = lavender-violet, top-right = luminous rose-peach,\n"
            + "    // bottom-right = warm coral-orange, bottom-left = electric periwinkle-violet\n"
            + "    float3 cOrange = mix(float3(0.95, 0.43, 0.14), float3(1.00, 0.62, 0.34), dark);\n"
            + "    float3 cRose   = mix(float3(0.90, 0.20, 0.50), float3(1.00, 0.42, 0.72), dark);\n"
            + "    float3 cViolet = mix(float3(0.56, 0.29, 0.95), float3(0.76, 0.50, 1.00), dark);\n"
            + "    float3 cBlue   = mix(float3(0.14, 0.48, 0.96), float3(0.36, 0.66, 1.00), dark);\n"
            + "\n"
            + "    float wOrange = pow(max(1e-4, dot(d, float2( 0.78,  0.62)) * 0.52 + 0.48), 2.25);\n"
            + "    float wRose   = pow(max(1e-4, dot(d, float2( 0.72, -0.69)) * 0.52 + 0.48), 2.15);\n"
            + "    float wViolet = pow(max(1e-4, dot(d, float2(-0.74, -0.67)) * 0.52 + 0.48), 2.10);\n"
            + "    float wBlue   = pow(max(1e-4, dot(d, float2(-0.74,  0.67)) * 0.52 + 0.48), 2.20);\n"
            + "    float sum = wOrange + wRose + wViolet + wBlue + 1e-4;\n"
            + "    return (cOrange * wOrange + cRose * wRose + cViolet * wViolet + cBlue * wBlue) / sum;\n"
            + "}\n"
            + "\n"
            + "float3 sampleNebulaSpectrum(float2 d, float dark) {\n"
            + "    float3 cCyan   = mix(float3(0.11, 0.64, 0.93), float3(0.38, 0.89, 1.00), dark);\n"
            + "    float3 cAzure  = mix(float3(0.17, 0.46, 0.94), float3(0.28, 0.58, 1.00), dark);\n"
            + "    float3 cViolet = mix(float3(0.45, 0.34, 0.95), float3(0.66, 0.52, 1.00), dark);\n"
            + "    float3 cMoon   = mix(float3(0.56, 0.78, 0.98), float3(0.88, 0.95, 1.00), dark);\n"
            + "\n"
            + "    float wCyan   = pow(max(1e-4, dot(d, float2( 0.76, -0.65)) * 0.52 + 0.48), 2.15);\n"
            + "    float wAzure  = pow(max(1e-4, dot(d, float2( 0.74,  0.67)) * 0.52 + 0.48), 2.15);\n"
            + "    float wViolet = pow(max(1e-4, dot(d, float2(-0.74,  0.67)) * 0.52 + 0.48), 2.15);\n"
            + "    float wMoon   = pow(max(1e-4, dot(d, float2(-0.76, -0.65)) * 0.52 + 0.48), 2.25);\n"
            + "    float sum = wCyan + wAzure + wViolet + wMoon + 1e-4;\n"
            + "    return (cCyan * wCyan + cAzure * wAzure + cViolet * wViolet + cMoon * wMoon) / sum;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    if (uAlpha <= 0.004) return half4(0.0);\n"
            + "    float2 center = 0.5 * (uBounds.xy + uBounds.zw);\n"
            + "    float2 halfSize = max(0.5 * (uBounds.zw - uBounds.xy), float2(1.0));\n"
            + "    float2 p = fragCoord - center;\n"
            + "    float d = sdRoundedRect(p, halfSize, uRadii.x, uRadii.y);\n"
            + "    float dp = max(uDensity, 1.0);\n"
            + "\n"
            + "    float2 pNorm = float2(p.x / max(halfSize.x, 1.0), p.y / max(halfSize.y, 1.0));\n"
            + "    float2 dir = normalize(pNorm + float2(1e-5, 0.0));\n"
            + "    float cs = cos(uAngle);\n"
            + "    float sn = sin(uAngle);\n"
            + "    float2 rotDir = float2(dir.x * cs - dir.y * sn, dir.x * sn + dir.y * cs);\n"
            + "    float3 col = mix(sampleNebulaSpectrum(rotDir, uDark), sampleXiaoAiSpectrum(rotDir, uDark), uStyle);\n"
            + "\n"
            + "    if (uMode < 0.5) {\n"
            + "        // Mode 0: Silky Gaussian-feathered multi-color outer halo purely outside card\n"
            + "        if (d <= -1.0 * dp) return half4(0.0);\n"
            + "        float outerDp = max(d, 0.0) / dp;\n"
            + "        float maxReach = mix(7.2, 10.8, uDark);\n"
            + "        if (outerDp >= maxReach) return half4(0.0);\n"
            + "        float gTight = exp(-outerDp * outerDp / mix(4.5, 7.0, uDark));\n"
            + "        float gSoft  = exp(-outerDp * outerDp / mix(16.0, 29.0, uDark));\n"
            + "        float edgeFade = 1.0 - smoothstep(maxReach * 0.56, maxReach, outerDp);\n"
            + "        float baseStr = mix(0.24, 0.45, uDark) * (1.0 + 0.38 * uShimmer);\n"
            + "        float3 haloTint = mix(col, mix(float3(0.94, 0.98, 1.0), float3(1.0, 0.94, 0.96), uStyle),\n"
            + "                              gTight * mix(0.10, 0.22, uDark));\n"
            + "        float a = clamp((gTight * 0.64 + gSoft * 0.42) * baseStr * edgeFade, 0.0, 1.0) * uAlpha;\n"
            + "        return half4(haloTint * a, a);\n"
            + "    }\n"
            + "\n"
            + "    // Mode 1: Crisp thin luminous glass rim + whisper-minimal edge catchlight (clean interior)\n"
            + "    float dDp = d / dp;\n"
            + "    if (dDp > 2.6 || dDp < -3.4) return half4(0.0);\n"
            + "\n"
            + "    float rimDist = abs(dDp + 0.35);\n"
            + "    float rimOuter = 1.0 - smoothstep(0.42, 1.18, rimDist);\n"
            + "    float rimCore  = 1.0 - smoothstep(0.06, 0.48, rimDist);\n"
            + "\n"
            + "    // Whisper-tight 0..2dp glass catchlight ONLY at the perimeter edge (zero interior haze)\n"
            + "    float innerMask = 1.0 - smoothstep(-0.25, 0.25, dDp);\n"
            + "    float innerDepth = max(-dDp, 0.0);\n"
            + "    float innerCatch = exp(-innerDepth * innerDepth / 1.9) * innerMask\n"
            + "            * mix(0.035, 0.135, uDark) * (1.0 + 0.32 * uShimmer);\n"
            + "\n"
            + "    // Twin traveling specular highlight flares during the 1.6s one-shot reveal sweep\n"
            + "    float flare = 0.0;\n"
            + "    if (uShimmer > 0.005) {\n"
            + "        float fAng = uAngle - 1.05;\n"
            + "        float2 fDir = float2(cos(fAng), sin(fAng));\n"
            + "        float align1 = max(1e-4, dot(dir, fDir));\n"
            + "        float align2 = max(1e-4, dot(dir, -fDir));\n"
            + "        float arc = pow(align1, 7.5) + pow(align2, 9.5) * 0.65;\n"
            + "        flare = arc * (1.0 - smoothstep(0.18, 1.65, rimDist)) * uShimmer;\n"
            + "    }\n"
            + "\n"
            + "    float3 hotWhite = mix(float3(0.96, 0.99, 1.0), float3(1.0, 0.96, 0.95), uStyle);\n"
            + "    float coreMix = clamp(rimCore * mix(0.28, 0.48, uDark) + flare * 0.74, 0.0, 0.90);\n"
            + "    float3 rimRgb = mix(col, hotWhite, coreMix);\n"
            + "\n"
            + "    float rimAlpha = rimOuter * mix(0.84, 0.94, uDark);\n"
            + "    float totalAlpha = clamp(max(rimAlpha, innerCatch) + flare * 0.32, 0.0, 1.0) * uAlpha;\n"
            + "    float rimWeight = clamp(rimAlpha / max(rimAlpha + innerCatch, 1e-4), 0.0, 1.0);\n"
            + "    float3 finalRgb = mix(col, rimRgb, rimWeight);\n"
            + "    return half4(finalRgb * totalAlpha, totalAlpha);\n"
            + "}\n";

    /**
     * AGSL text shader for the Reduce Interruptions Super Island title ("减少干扰已开启") and
     * settings preview pill: vibrant flowing multi-stop style gradient + tilted soft-white
     * highlight wave gliding continuously across the glyphs.
     */
    private static final String ISLAND_TEXT_GLOW_AGSL =
            "uniform float uOriginX;\n"
            + "uniform float uWidth;\n"
            + "uniform float uPhase;\n"
            + "uniform float uSheenPos;\n"
            + "uniform float uStyle;\n"
            + "uniform float uDark;\n"
            + "\n"
            + "float3 xiaoAiGrad(float t, float dark) {\n"
            + "    // High-luminance Super XiaoAi text palette calibrated for pure-black Super Island\n"
            + "    float3 c0 = mix(float3(0.92, 0.42, 0.14), float3(1.00, 0.72, 0.46), dark); // Warm peach-orange\n"
            + "    float3 c1 = mix(float3(0.88, 0.20, 0.50), float3(1.00, 0.52, 0.76), dark); // Luminous coral-rose\n"
            + "    float3 c2 = mix(float3(0.56, 0.30, 0.94), float3(0.80, 0.62, 1.00), dark); // Soft lavender-violet\n"
            + "    float3 c3 = mix(float3(0.14, 0.48, 0.94), float3(0.50, 0.79, 1.00), dark); // Luminous sky-blue\n"
            + "    float p = fract(t) * 4.0;\n"
            + "    if (p < 1.0) return mix(c0, c1, smoothstep(0.0, 1.0, p));\n"
            + "    if (p < 2.0) return mix(c1, c2, smoothstep(0.0, 1.0, p - 1.0));\n"
            + "    if (p < 3.0) return mix(c2, c3, smoothstep(0.0, 1.0, p - 2.0));\n"
            + "    return mix(c3, c0, smoothstep(0.0, 1.0, p - 3.0));\n"
            + "}\n"
            + "\n"
            + "float3 nebulaGrad(float t, float dark) {\n"
            + "    float3 c0 = mix(float3(0.06, 0.62, 0.92), float3(0.50, 0.93, 1.00), dark); // Sky cyan\n"
            + "    float3 c1 = mix(float3(0.16, 0.45, 0.94), float3(0.56, 0.78, 1.00), dark); // Azure\n"
            + "    float3 c2 = mix(float3(0.45, 0.33, 0.94), float3(0.80, 0.70, 1.00), dark); // Ice violet\n"
            + "    float3 c3 = mix(float3(0.12, 0.56, 0.95), float3(0.92, 0.97, 1.00), dark); // Moon highlight\n"
            + "    float p = fract(t) * 4.0;\n"
            + "    if (p < 1.0) return mix(c0, c1, smoothstep(0.0, 1.0, p));\n"
            + "    if (p < 2.0) return mix(c1, c2, smoothstep(0.0, 1.0, p - 1.0));\n"
            + "    if (p < 3.0) return mix(c2, c3, smoothstep(0.0, 1.0, p - 2.0));\n"
            + "    return mix(c3, c0, smoothstep(0.0, 1.0, p - 3.0));\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float w = max(uWidth, 1.0);\n"
            + "    float x = (fragCoord.x - uOriginX) / w;\n"
            + "    float gradT = x * 0.72 - uPhase;\n"
            + "    float3 base = mix(nebulaGrad(gradT, uDark), xiaoAiGrad(gradT, uDark), uStyle);\n"
            + "\n"
            + "    // Slight forward slant for optical light-sweep motion across characters\n"
            + "    float tiltedX = x - fragCoord.y * 0.0022;\n"
            + "    float d = (tiltedX - uSheenPos) / 0.21;\n"
            + "    float sheen = exp(-d * d * 2.4) * mix(0.60, 0.84, uDark);\n"
            + "    float3 highlight = mix(float3(0.96, 0.99, 1.00), float3(1.00, 0.985, 0.965), uStyle);\n"
            + "    float3 rgb = mix(base, highlight, sheen);\n"
            + "    return half4(rgb, 1.0);\n"
            + "}\n";

    private static volatile boolean sXiaoAi = Config.isXiaoAiGlow(Config.DEFAULT_GLOW_STYLE);
    private static final AtomicBoolean sSystemUiInitialized = new AtomicBoolean(false);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final Executor sIoExecutor = Executors.newSingleThreadExecutor();
    private static final List<WeakReference<Runnable>> sListeners = new ArrayList<>();

    private GlowStyle() {}

    static boolean isXiaoAi() {
        return sXiaoAi;
    }

    static String currentKey() {
        return sXiaoAi ? Config.GLOW_STYLE_XIAOAI : Config.GLOW_STYLE_NEBULA;
    }

    static String displayLabel(String styleKey) {
        return Config.isXiaoAiGlow(styleKey) ? "超级小爱流光" : "星云极光";
    }

    static void setCurrent(String styleKey) {
        setCurrent(Config.isXiaoAiGlow(styleKey));
    }

    static void setCurrent(boolean xiaoAi) {
        if (sXiaoAi == xiaoAi) return;
        sXiaoAi = xiaoAi;
        notifyListenersOnMain();
    }

    static void addListener(Runnable listener) {
        if (listener == null) return;
        synchronized (sListeners) {
            Iterator<WeakReference<Runnable>> it = sListeners.iterator();
            while (it.hasNext()) {
                Runnable existing = it.next().get();
                if (existing == null) {
                    it.remove();
                } else if (existing == listener) {
                    return;
                }
            }
            sListeners.add(new WeakReference<>(listener));
        }
    }

    private static void notifyListenersOnMain() {
        Runnable dispatch = () -> {
            List<Runnable> copy = new ArrayList<>();
            synchronized (sListeners) {
                Iterator<WeakReference<Runnable>> it = sListeners.iterator();
                while (it.hasNext()) {
                    Runnable r = it.next().get();
                    if (r == null) it.remove();
                    else copy.add(r);
                }
            }
            for (Runnable r : copy) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.w(TAG, "GlowStyle listener failed", t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch.run();
        } else {
            sMainHandler.post(dispatch);
        }
    }

    /**
     * Initializes live glow style synchronization inside {@code com.android.systemui}:
     * registers a broadcast receiver for instant switching from Settings without reboot,
     * and seeds the initial persisted preference asynchronously from {@link InferenceProvider}.
     */
    static void initInSystemUi(Context context) {
        if (context == null) return;
        if (!sSystemUiInitialized.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        final Context receiverCtx = app;
        try {
            IntentFilter filter = new IntentFilter(Config.ACTION_GLOW_STYLE_CHANGED);
            receiverCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null) return;
                    String raw = intent.getStringExtra(Config.KEY_GLOW_STYLE);
                    if (raw != null) {
                        Log.i(TAG, "Live glow style switched to: " + raw);
                        setCurrent(raw);
                    }
                }
            }, filter, null, sMainHandler, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register glow style receiver", t);
        }

        sIoExecutor.execute(() -> {
            try {
                Bundle cfg = ModuleMain.callProvider(receiverCtx, "get_config", null);
                if (cfg != null && cfg.containsKey(Config.KEY_GLOW_STYLE)) {
                    String raw = cfg.getString(Config.KEY_GLOW_STYLE, Config.DEFAULT_GLOW_STYLE);
                    Log.i(TAG, "Loaded initial glow style: " + raw);
                    setCurrent(raw);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to load initial glow style", t);
            }
        });
    }

    static RuntimeShader createPriorityCardGlowShader() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        try {
            return new RuntimeShader(PRIORITY_PERIMETER_AGSL);
        } catch (Throwable t) {
            Log.w(TAG, "Priority AGSL shader unavailable, falling back to Canvas", t);
            return null;
        }
    }

    static RuntimeShader createIslandTextGlowShader() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        try {
            return new RuntimeShader(ISLAND_TEXT_GLOW_AGSL);
        } catch (Throwable t) {
            Log.w(TAG, "Island text AGSL shader unavailable, falling back to LinearGradient", t);
            return null;
        }
    }

    static int islandTextShadowColor(boolean xiaoAi) {
        return xiaoAi ? 0x66FF78A8 : 0x6664C8FF;
    }

    static int islandFallbackHighlightColor(boolean xiaoAi) {
        return xiaoAi ? 0xFFFFAE84 : 0xFFB4A4FF;
    }

    static String islandFallbackHighlightHex(boolean xiaoAi) {
        return xiaoAi ? "#FFAE84" : "#B4A4FF";
    }

    static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
