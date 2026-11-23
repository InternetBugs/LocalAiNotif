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
 * Shared Nebula Aurora visual primitives for AI notification summaries, used identically by
 * SystemUI ({@link SystemUiGroupText}) and the settings preview ({@link HyperSettingsUi}).
 *
 * Pure Android Canvas/Shader implementation (no R.*, no AndroidX) calibrated for both
 * dark/lockscreen smoked surfaces and light frosted notification shades.
 */
final class NebulaSummaryVisuals {
    static final String LABEL = "AI 摘要";

    // Dark / lockscreen / HyperOS frosted-glass Nebula Aurora stops (high luminance for mid-gray glass)
    static final int DARK_CYAN = 0xFF7CEBFF;
    static final int DARK_AZURE = 0xFF8CC4FF;
    static final int DARK_VIOLET = 0xFFCDB4FF;
    static final int DARK_MOON = 0xFFF2F9FF;
    static final int DARK_BODY_TINT_TARGET = 0xFFAEDBFF;

    // Light notification shade calibrated Nebula stops (deeper chroma to stay sharp on white)
    static final int LIGHT_CYAN = 0xFF0E99E6;
    static final int LIGHT_AZURE = 0xFF256CEB;
    static final int LIGHT_VIOLET = 0xFF6F4FF0;
    static final int LIGHT_MOON = 0xFFFFFFFF;
    static final int LIGHT_BODY_TINT_TARGET = 0xFF1F4EC4;

    private static final int[] DARK_BADGE_COLORS = {DARK_CYAN, DARK_AZURE, DARK_VIOLET};
    private static final int[] LIGHT_BADGE_COLORS = {LIGHT_CYAN, LIGHT_AZURE, LIGHT_VIOLET};
    private static final float[] BADGE_STOPS = {0f, 0.48f, 1f};

    private static final int[] DARK_WAVE_COLORS = {
            0x007CEBFF, 0x4C7CEBFF, 0x6270B8FF, 0x7CEFF9FF, 0x54CDB4FF, 0x00CDB4FF
    };
    private static final int[] LIGHT_WAVE_COLORS = {
            0x000E99E6, 0x360E99E6, 0x48256CEB, 0x5678B8FF, 0x3C6F4FF0, 0x006F4FF0
    };
    private static final float[] WAVE_STOPS = {0f, 0.20f, 0.42f, 0.56f, 0.78f, 1f};
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

    /**
     * Blends the native notification text color with ~15-17% Nebula ice-cyan/indigo tint while
     * preserving the original alpha channel and keeping high contrast on both dark and light cards.
     */
    static int tintSummaryBodyColor(int baseInkColor, boolean darkSurface) {
        int a = Color.alpha(baseInkColor);
        int target = darkSurface ? DARK_BODY_TINT_TARGET : LIGHT_BODY_TINT_TARGET;
        float amount = darkSurface ? 0.20f : 0.17f;
        int r = Math.round(Color.red(baseInkColor) + (Color.red(target) - Color.red(baseInkColor)) * amount);
        int g = Math.round(Color.green(baseInkColor) + (Color.green(target) - Color.green(baseInkColor)) * amount);
        int b = Math.round(Color.blue(baseInkColor) + (Color.blue(target) - Color.blue(baseInkColor)) * amount);
        return Color.argb(a, clamp255(r), clamp255(g), clamp255(b));
    }

    static int[] badgeColors(boolean darkSurface) {
        return darkSurface ? DARK_BADGE_COLORS : LIGHT_BADGE_COLORS;
    }

    static float[] badgeStops() {
        return BADGE_STOPS;
    }

    static LinearGradient createHorizontalBadgeGradient(float left, float right, boolean darkSurface) {
        return new LinearGradient(
                left, 0f, Math.max(left + 1f, right), 0f,
                badgeColors(darkSurface), BADGE_STOPS, Shader.TileMode.CLAMP);
    }

    static LinearGradient createDiagonalSparkleGradient(
            float x0, float y0, float x1, float y1, boolean darkSurface) {
        return new LinearGradient(
                x0, y0, x1, y1,
                badgeColors(darkSurface), BADGE_STOPS, Shader.TileMode.CLAMP);
    }

    static LinearGradient createHairlineGradient(float top, float bottom, boolean darkSurface) {
        int mid = darkSurface ? 0x8A9EE2FF : 0x5A2E72EC;
        return new LinearGradient(
                0f, top, 0f, Math.max(top + 1f, bottom),
                new int[]{0x00000000, mid, 0x00000000},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
    }

    static LinearGradient createGlintSheenGradient(float bandWidth, boolean darkSurface) {
        int edge = darkSurface ? 0x555CE1FF : 0x441CA3EC;
        int core = darkSurface ? 0xF0FFFFFF : 0xE6FFFFFF;
        return new LinearGradient(
                0f, 0f, Math.max(1f, bandWidth), 0f,
                new int[]{0x00FFFFFF, edge, core, edge, 0x00FFFFFF},
                new float[]{0f, 0.24f, 0.50f, 0.76f, 1f},
                Shader.TileMode.CLAMP);
    }

    static LinearGradient createScanWaveGradient(float bandWidth, boolean darkSurface) {
        return new LinearGradient(
                0f, 0f, Math.max(1f, bandWidth), 0f,
                darkSurface ? DARK_WAVE_COLORS : LIGHT_WAVE_COLORS,
                WAVE_STOPS,
                Shader.TileMode.CLAMP);
    }

    /**
     * Creates a vertically soft-feathered horizontal Nebula wave shader so the 14-degree tilted
     * optical sweep floats weightlessly inside the text zone with zero hard top/bottom clip edges.
     */
    static Shader createFeatheredWaveShader(float bandWidth, float top, float bottom, boolean darkSurface) {
        LinearGradient horizontal = createScanWaveGradient(bandWidth, darkSurface);
        LinearGradient verticalFeather = new LinearGradient(
                0f, top, 0f, Math.max(top + 1f, bottom),
                VERT_FEATHER_COLORS,
                VERT_FEATHER_STOPS,
                Shader.TileMode.CLAMP);
        return new ComposeShader(horizontal, verticalFeather, PorterDuff.Mode.MULTIPLY);
    }

    /**
     * Builds a sharp, concave Apple-Intelligence-like 4-point sparkle centered at (cx, cy).
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

    /**
     * Draws the signature dual-sparkle Nebula Aurora emblem (one primary 4-point star + one
     * nestled upper-right satellite star, with a pinpoint ice-white specular core and optional
     * bloom during reveal/glint animations).
     *
     * @param left        Left edge of the dual-sparkle bounding box
     * @param centerY     Optical vertical center aligned to line-1 cap midline
     * @param primaryR    Radius of the primary 4-point sparkle
     * @param darkSurface True on dark/lockscreen cards, false on light shade cards
     * @param alphaScale  Overall opacity multiplier [0..1]
     * @param bloom       Transient highlight boost [0..1]
     * @param shader      Prebuilt diagonal gradient (or null to construct on demand)
     * @param fillPaint   Reusable FILL paint
     * @param path        Reusable scratch path
     * @return Total horizontal advance occupied by the dual sparkles
     */
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
        float totalW = primaryR * 2.52f;
        if (alphaScale <= 0.01f || primaryR <= 0.5f) return totalW;

        float scale = 1f + 0.14f * bloom;
        float pCx = left + primaryR;
        float pCy = centerY + primaryR * 0.10f;
        float pR = primaryR * scale;

        float sR = primaryR * 0.47f * (1f + 0.18f * bloom);
        float sCx = left + primaryR * 2.02f;
        float sCy = centerY - primaryR * 0.54f;

        // Soft starlight micro-bloom behind primary sparkle during reveal/glint
        if (bloom > 0.02f) {
            fillPaint.setShader(null);
            int haloColor = darkSurface ? DARK_CYAN : LIGHT_AZURE;
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
                        darkSurface);
        fillPaint.setShader(useShader);
        fillPaint.setAlpha(clamp255(Math.round(255f * alphaScale)));

        buildSparklePath(pCx, pCy, pR, path);
        canvas.drawPath(path, fillPaint);

        buildSparklePath(sCx, sCy, sR, path);
        canvas.drawPath(path, fillPaint);

        // Pinpoint ice-white specular highlight core inside the primary sparkle
        fillPaint.setShader(null);
        float coreAlpha = ((darkSurface ? 0.58f : 0.44f) + 0.38f * bloom) * alphaScale;
        int coreOp = clamp255(Math.round(255f * coreAlpha));
        if (coreOp > 0) {
            fillPaint.setColor(Color.argb(coreOp, 245, 252, 255));
            buildSparklePath(pCx, pCy, pR * 0.46f, path);
            canvas.drawPath(path, fillPaint);
        }
        return totalW;
    }

    /**
     * Draws a softly feathered 14-degree tilted Nebula Aurora light wave across the given bounds.
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
        float skewX = -0.24f; // ~13.5 deg forward tilt for optical scanner feel
        int saved = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        // Pivot skew around vertical midpoint so top/bottom travel symmetrically
        float midY = (top + bottom) * 0.5f;
        canvas.translate(0f, midY);
        canvas.skew(skewX, 0f);
        canvas.translate(0f, -midY);

        float drawL = centerX - bandWidth * 0.5f;
        float drawR = centerX + bandWidth * 0.5f;
        shaderMatrix.reset();
        shaderMatrix.setTranslate(drawL, 0f);
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
