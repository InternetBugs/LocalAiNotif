package com.localai.notif;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Shader;

/**
 * Shared AI notification summary visual primitives supporting both:
 * <ul>
 *   <li><b>Super XiaoAi Glow (超级小爱流光)</b>: Warm Tangerine-Orange → Luminous Coral-Rose →
 *       Lavender-Violet → Electric Blue neon accents with clean high-contrast body text.</li>
 *   <li><b>Nebula Aurora (星云极光)</b>: Cool Sky Cyan → Azure → Ice Violet liquid-glass styling.</li>
 * </ul>
 * Used identically by SystemUI ({@link SystemUiGroupText}) and Settings previews ({@link HyperSettingsUi}).
 */
final class NebulaSummaryVisuals {
    static final String LABEL = "AI 摘要";

    // --- Nebula Aurora (cool) stops ---
    static final int DARK_CYAN = 0xFF7CEBFF;
    static final int DARK_AZURE = 0xFF8CC4FF;
    static final int DARK_VIOLET = 0xFFCDB4FF;
    static final int DARK_MOON = 0xFFF2F9FF;
    static final int DARK_BODY_TINT_TARGET = 0xFFAEDBFF;

    static final int LIGHT_CYAN = 0xFF0E99E6;
    static final int LIGHT_AZURE = 0xFF256CEB;
    static final int LIGHT_VIOLET = 0xFF6F4FF0;
    static final int LIGHT_MOON = 0xFFFFFFFF;
    static final int LIGHT_BODY_TINT_TARGET = 0xFF1F4EC4;

    private static final int[] NEBULA_DARK_BADGE_COLORS = {DARK_CYAN, DARK_AZURE, DARK_VIOLET};
    private static final int[] NEBULA_LIGHT_BADGE_COLORS = {LIGHT_CYAN, LIGHT_AZURE, LIGHT_VIOLET};
    private static final float[] NEBULA_BADGE_STOPS = {0f, 0.48f, 1f};

    private static final int[] NEBULA_DARK_WAVE_COLORS = {
            0x007CEBFF, 0x4C7CEBFF, 0x6270B8FF, 0x7CEFF9FF, 0x54CDB4FF, 0x00CDB4FF
    };
    private static final int[] NEBULA_LIGHT_WAVE_COLORS = {
            0x000E99E6, 0x360E99E6, 0x48256CEB, 0x5678B8FF, 0x3C6F4FF0, 0x006F4FF0
    };
    private static final float[] NEBULA_WAVE_STOPS = {0f, 0.20f, 0.42f, 0.56f, 0.78f, 1f};

    // --- Super XiaoAi Glow (warm-cool neon) stops ---
    static final int XIAOAI_DARK_BODY_TINT_TARGET = 0xFFEAE4FF;
    static final int XIAOAI_LIGHT_BODY_TINT_TARGET = 0xFF2C2658;

    private static final int[] XIAOAI_DARK_BADGE_COLORS = {
            0xFFFFA552, 0xFFFF5C9E, 0xFFC185FF, 0xFF66B8FF
    };
    private static final int[] XIAOAI_LIGHT_BADGE_COLORS = {
            0xFFEA641C, 0xFFD92B77, 0xFF7D45EC, 0xFF1E70EB
    };
    private static final float[] XIAOAI_BADGE_STOPS = {0f, 0.34f, 0.68f, 1f};

    private static final int[] XIAOAI_DARK_WAVE_COLORS = {
            0x00FF9238, 0x4EFF8E44, 0x64FF4B94, 0x84FFF4F8, 0x5EC078FF, 0x4A4C9CFF, 0x004C9CFF
    };
    private static final int[] XIAOAI_LIGHT_WAVE_COLORS = {
            0x00F26D1F, 0x38F26D1F, 0x48E52B7A, 0x58B47BFF, 0x428C46F2, 0x341E78F5, 0x001E78F5
    };
    private static final float[] XIAOAI_WAVE_STOPS = {0f, 0.16f, 0.36f, 0.52f, 0.70f, 0.86f, 1f};

    private static final int[] XIAOAI_DARK_BULLETS = {0xE6FF9E54, 0xE6FF66A6, 0xE67CB4FF};
    private static final int[] XIAOAI_LIGHT_BULLETS = {0xE0E5621E, 0xE0D42E7B, 0xE05E54EC};

    private static final int[] VERT_FEATHER_COLORS = {
            0x00FFFFFF, 0xEEFFFFFF, 0xFFFFFFFF, 0xEEFFFFFF, 0x00FFFFFF
    };
    private static final float[] VERT_FEATHER_STOPS = {0f, 0.24f, 0.50f, 0.76f, 1f};

    private NebulaSummaryVisuals() {}

    /**
     * Infers whether the host notification card surface is dark by inspecting the native ink color
     * luminance (light ink => dark/lockscreen card surface; dark ink => light shade card surface).
     */
    static boolean isDarkSurfaceFromInk(int inkColor, boolean fallbackNightMode) {
        if (Color.alpha(inkColor) < 24) return fallbackNightMode;
        float lum = 0.299f * Color.red(inkColor)
                + 0.587f * Color.green(inkColor)
                + 0.114f * Color.blue(inkColor);
        return lum >= 136f;
    }

    static int tintSummaryBodyColor(int baseInkColor, boolean darkSurface) {
        return tintSummaryBodyColor(baseInkColor, darkSurface, GlowStyle.isXiaoAi());
    }

    /**
     * Under Super XiaoAi Glow, keeps body ink crisp & close to native contrast (subtle 9% cool
     * lavender/indigo blend) so multi-line Chinese summaries stay effortless to read.
     * Under Nebula Aurora, preserves the original 17–20% ice-cyan/indigo blend.
     */
    static int tintSummaryBodyColor(int baseInkColor, boolean darkSurface, boolean xiaoAi) {
        int a = Color.alpha(baseInkColor);
        int target;
        float amount;
        if (xiaoAi) {
            target = darkSurface ? XIAOAI_DARK_BODY_TINT_TARGET : XIAOAI_LIGHT_BODY_TINT_TARGET;
            amount = 0.09f;
        } else {
            target = darkSurface ? DARK_BODY_TINT_TARGET : LIGHT_BODY_TINT_TARGET;
            amount = darkSurface ? 0.20f : 0.17f;
        }
        int r = Math.round(Color.red(baseInkColor) + (Color.red(target) - Color.red(baseInkColor)) * amount);
        int g = Math.round(Color.green(baseInkColor) + (Color.green(target) - Color.green(baseInkColor)) * amount);
        int b = Math.round(Color.blue(baseInkColor) + (Color.blue(target) - Color.blue(baseInkColor)) * amount);
        return Color.argb(a, clamp255(r), clamp255(g), clamp255(b));
    }

    static int bulletDotColor(boolean darkSurface, boolean xiaoAi, int bulletIndex) {
        if (!xiaoAi) {
            return darkSurface ? 0xD95CE1FF : 0xD92B75F0;
        }
        int[] palette = darkSurface ? XIAOAI_DARK_BULLETS : XIAOAI_LIGHT_BULLETS;
        return palette[Math.max(0, bulletIndex) % palette.length];
    }

    static int[] badgeColors(boolean darkSurface, boolean xiaoAi) {
        if (xiaoAi) {
            return darkSurface ? XIAOAI_DARK_BADGE_COLORS : XIAOAI_LIGHT_BADGE_COLORS;
        }
        return darkSurface ? NEBULA_DARK_BADGE_COLORS : NEBULA_LIGHT_BADGE_COLORS;
    }

    static float[] badgeStops(boolean xiaoAi) {
        return xiaoAi ? XIAOAI_BADGE_STOPS : NEBULA_BADGE_STOPS;
    }

    static LinearGradient createHorizontalBadgeGradient(float left, float right, boolean darkSurface) {
        return createHorizontalBadgeGradient(left, right, darkSurface, GlowStyle.isXiaoAi());
    }

    static LinearGradient createHorizontalBadgeGradient(
            float left, float right, boolean darkSurface, boolean xiaoAi) {
        return new LinearGradient(
                left, 0f, Math.max(left + 1f, right), 0f,
                badgeColors(darkSurface, xiaoAi), badgeStops(xiaoAi), Shader.TileMode.CLAMP);
    }

    static LinearGradient createDiagonalSparkleGradient(
            float x0, float y0, float x1, float y1, boolean darkSurface) {
        return createDiagonalSparkleGradient(x0, y0, x1, y1, darkSurface, GlowStyle.isXiaoAi());
    }

    static LinearGradient createDiagonalSparkleGradient(
            float x0, float y0, float x1, float y1, boolean darkSurface, boolean xiaoAi) {
        return new LinearGradient(
                x0, y0, x1, y1,
                badgeColors(darkSurface, xiaoAi), badgeStops(xiaoAi), Shader.TileMode.CLAMP);
    }

    static LinearGradient createHairlineGradient(float top, float bottom, boolean darkSurface) {
        return createHairlineGradient(top, bottom, darkSurface, GlowStyle.isXiaoAi());
    }

    static LinearGradient createHairlineGradient(
            float top, float bottom, boolean darkSurface, boolean xiaoAi) {
        int mid = xiaoAi
                ? (darkSurface ? 0x90E29BFF : 0x648E54E8)
                : (darkSurface ? 0x8A9EE2FF : 0x5A2E72EC);
        return new LinearGradient(
                0f, top, 0f, Math.max(top + 1f, bottom),
                new int[]{0x00000000, mid, 0x00000000},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
    }

    static LinearGradient createGlintSheenGradient(float bandWidth, boolean darkSurface) {
        return createGlintSheenGradient(bandWidth, darkSurface, GlowStyle.isXiaoAi());
    }

    static LinearGradient createGlintSheenGradient(
            float bandWidth, boolean darkSurface, boolean xiaoAi) {
        int edge = xiaoAi
                ? (darkSurface ? 0x58FF8AB8 : 0x48D84E9A)
                : (darkSurface ? 0x555CE1FF : 0x441CA3EC);
        int core = xiaoAi
                ? (darkSurface ? 0xF5FFF8F4 : 0xE8FFFFFF)
                : (darkSurface ? 0xF0FFFFFF : 0xE6FFFFFF);
        return new LinearGradient(
                0f, 0f, Math.max(1f, bandWidth), 0f,
                new int[]{0x00FFFFFF, edge, core, edge, 0x00FFFFFF},
                new float[]{0f, 0.24f, 0.50f, 0.76f, 1f},
                Shader.TileMode.CLAMP);
    }

    static LinearGradient createScanWaveGradient(float bandWidth, boolean darkSurface, boolean xiaoAi) {
        if (xiaoAi) {
            return new LinearGradient(
                    0f, 0f, Math.max(1f, bandWidth), 0f,
                    darkSurface ? XIAOAI_DARK_WAVE_COLORS : XIAOAI_LIGHT_WAVE_COLORS,
                    XIAOAI_WAVE_STOPS,
                    Shader.TileMode.CLAMP);
        }
        return new LinearGradient(
                0f, 0f, Math.max(1f, bandWidth), 0f,
                darkSurface ? NEBULA_DARK_WAVE_COLORS : NEBULA_LIGHT_WAVE_COLORS,
                NEBULA_WAVE_STOPS,
                Shader.TileMode.CLAMP);
    }

    static LinearGradient createTextTrailGradient(float trailWidth, boolean darkSurface, boolean xiaoAi) {
        if (xiaoAi) {
            int[] colors = darkSurface
                    ? new int[]{0x00FF9238, 0x7CFF8A42, 0xDCFFF4F0, 0x84C07AFF, 0x584E9EFF, 0x004E9EFF}
                    : new int[]{0x00EA641C, 0x66EA641C, 0xC8D92B77, 0x747D45EC, 0x501E70EB, 0x001E70EB};
            return new LinearGradient(
                    0f, 0f, Math.max(1f, trailWidth), 0f,
                    colors, new float[]{0f, 0.20f, 0.48f, 0.72f, 0.88f, 1f}, Shader.TileMode.CLAMP);
        }
        int[] colors = darkSurface
                ? new int[]{0x005CE1FF, 0x785CE1FF, 0xD8EDF6FF, 0x80A17DFF, 0x00A17DFF}
                : new int[]{0x000E99E6, 0x660E99E6, 0xC8256CEB, 0x706F4FF0, 0x006F4FF0};
        return new LinearGradient(
                0f, 0f, Math.max(1f, trailWidth), 0f,
                colors, new float[]{0f, 0.24f, 0.52f, 0.78f, 1f}, Shader.TileMode.CLAMP);
    }

    static Shader createFeatheredWaveShader(float bandWidth, float top, float bottom, boolean darkSurface) {
        return createFeatheredWaveShader(bandWidth, top, bottom, darkSurface, GlowStyle.isXiaoAi());
    }

    /**
     * Creates a vertically soft-feathered horizontal wave shader so the 14-degree tilted
     * optical sweep floats weightlessly inside the text zone with zero hard top/bottom clip edges.
     */
    static Shader createFeatheredWaveShader(
            float bandWidth, float top, float bottom, boolean darkSurface, boolean xiaoAi) {
        LinearGradient horizontal = createScanWaveGradient(bandWidth, darkSurface, xiaoAi);
        LinearGradient verticalFeather = new LinearGradient(
                0f, top, 0f, Math.max(top + 1f, bottom),
                VERT_FEATHER_COLORS,
                VERT_FEATHER_STOPS,
                Shader.TileMode.CLAMP);
        return new ComposeShader(horizontal, verticalFeather, PorterDuff.Mode.MULTIPLY);
    }

    /**
     * Builds a sharp, concave 4-point AI sparkle centered at (cx, cy).
     */
    static void buildSparklePath(float cx, float cy, float radius, Path out) {
        out.reset();
        if (radius <= 0f) return;
        float inner = radius * 0.24f;
        out.moveTo(cx, cy - radius);
        out.quadTo(cx + inner * 0.42f, cy - inner * 0.42f, cx + radius, cy);
        out.quadTo(cx + inner * 0.42f, cy + inner * 0.42f, cx, cy + radius);
        out.quadTo(cx - inner * 0.42f, cy + inner * 0.42f, cx - radius, cy);
        out.quadTo(cx - inner * 0.42f, cy - inner * 0.42f, cx, cy - radius);
        out.close();
    }

    static float drawDualSparkle(
            Canvas canvas,
            float left,
            float centerY,
            float primaryR,
            boolean darkSurface,
            float alphaScale,
            float bloom,
            Shader shader,
            Paint fillPaint,
            Path path) {
        return drawDualSparkle(canvas, left, centerY, primaryR, darkSurface,
                GlowStyle.isXiaoAi(), alphaScale, bloom, shader, fillPaint, path);
    }

    static float drawDualSparkle(
            Canvas canvas,
            float left,
            float centerY,
            float primaryR,
            boolean darkSurface,
            boolean xiaoAi,
            float alphaScale,
            float bloom,
            Shader shader,
            Paint fillPaint,
            Path path) {
        float totalW = primaryR * 2.52f;
        if (alphaScale <= 0.01f || primaryR <= 0.5f) return totalW;

        float scale = 1f + 0.14f * bloom;
        float pCx = left + primaryR;
        float pCy = centerY + primaryR * 0.10f;
        float pR = primaryR * scale;

        float sR = primaryR * 0.47f * (1f + 0.18f * bloom);
        float sCx = left + primaryR * 2.02f;
        float sCy = centerY - primaryR * 0.54f;

        if (bloom > 0.02f) {
            fillPaint.setShader(null);
            int haloColor = xiaoAi
                    ? (darkSurface ? 0xFFFF865C : 0xFFD93B88)
                    : (darkSurface ? DARK_CYAN : LIGHT_AZURE);
            int haloAlpha = clamp255(Math.round((darkSurface ? 92f : 64f) * bloom * alphaScale));
            if (haloAlpha > 0) {
                fillPaint.setColor(withAlpha(haloColor, haloAlpha));
                canvas.drawCircle(pCx, pCy, pR * 1.18f, fillPaint);
            }
        }

        Shader useShader = shader != null
                ? shader
                : createDiagonalSparkleGradient(
                        left, centerY - primaryR,
                        left + totalW, centerY + primaryR,
                        darkSurface, xiaoAi);
        fillPaint.setShader(useShader);
        fillPaint.setAlpha(clamp255(Math.round(255f * alphaScale)));

        buildSparklePath(pCx, pCy, pR, path);
        canvas.drawPath(path, fillPaint);

        buildSparklePath(sCx, sCy, sR, path);
        canvas.drawPath(path, fillPaint);

        fillPaint.setShader(null);
        float coreAlpha = ((darkSurface ? 0.58f : 0.44f) + 0.38f * bloom) * alphaScale;
        int coreOp = clamp255(Math.round(255f * coreAlpha));
        if (coreOp > 0) {
            fillPaint.setColor(xiaoAi
                    ? Color.argb(coreOp, 255, 248, 244)
                    : Color.argb(coreOp, 245, 252, 255));
            buildSparklePath(pCx, pCy, pR * 0.46f, path);
            canvas.drawPath(path, fillPaint);
        }
        return totalW;
    }

    /**
     * Draws a softly feathered 14-degree tilted optical light wave across the given bounds.
     * Used for both the subtle thinking scanlight and the hero Wave Wash reveal wavefront.
     */
    static void drawTiltedNebulaWave(
            Canvas canvas,
            float left, float top, float right, float bottom,
            float centerX,
            float bandWidth,
            float intensity,
            boolean darkSurface,
            Shader waveShader,
            Matrix shaderMatrix,
            Paint paint) {
        if (intensity <= 0.01f || right <= left || bottom <= top || bandWidth <= 1f) return;

        float h = bottom - top;
        float skewX = -0.24f;
        int saved = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        float midY = (top + bottom) * 0.5f;
        canvas.translate(0f, midY);
        canvas.skew(skewX, 0f);
        canvas.translate(0f, -midY);

        float drawL = centerX - bandWidth * 0.5f;
        float drawR = centerX + bandWidth * 0.5f;
        shaderMatrix.reset();
        shaderMatrix.setScale(bandWidth, h);
        shaderMatrix.postTranslate(drawL, top);
        waveShader.setLocalMatrix(shaderMatrix);
        paint.setShader(waveShader);
        paint.setAlpha(clamp255(Math.round(255f * intensity)));
        canvas.drawRect(drawL, top - h * 0.15f, drawR, bottom + h * 0.15f, paint);
        paint.setShader(null);
        canvas.restoreToCount(saved);
    }

    static float dualSparkleWidth(float primaryR) {
        return primaryR * 2.52f;
    }

    static float smoothEnvelope(float t, float attack) {
        if (t <= 0f || t >= 1f) return 0f;
        float u = t < attack ? (t / attack) : ((1f - t) / (1f - attack));
        return u * u * (3f - 2f * u);
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(clamp255(alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
