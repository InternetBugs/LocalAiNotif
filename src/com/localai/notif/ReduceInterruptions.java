package com.localai.notif;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * Shared protocol constants, eligibility helpers, and the Apple Intelligence style
 * 7-lobe continuous woven ribbon icon used across the status bar, Control Center
 * Quick Settings tile, volume panel, Super Island, and settings preview.
 */
final class ReduceInterruptions {
    static final String ACTION_STATE_CHANGED = "com.localai.notif.REDUCE_STATE_CHANGED";
    static final String ACTION_REQUEST_MODE = "com.localai.notif.REDUCE_REQUEST_MODE";
    static final String ACTION_ICON_FILTER = "com.localai.notif.REDUCE_ICON_FILTER";
    static final String EXTRA_ALLOWED_VERSIONS = "allowed_versions";

    static final String EXTRA_ACTIVE = "active";
    static final String EXTRA_LOCK_SCREEN_ONLY = "lock_screen_only";
    static final String EXTRA_ZEN_MODE = "zen_mode";
    static final String EXTRA_MODE = "mode";
    /** RTC epoch millis when timed Reduce Interruptions expires; 0 = untimed. */
    static final String EXTRA_DEADLINE = "localai.reduce_deadline";
    static final String EXTRA_DURATION_MINUTES = "localai.reduce_duration_minutes";
    static final String EXTRA_ANNOUNCE = "localai.reduce_announce";
    static final String EXTRA_SUPPRESS_DND_EXIT = "localai.reduce_suppress_dnd_exit";

    static final int MODE_OFF = 0;
    static final int MODE_REDUCE = 1;
    static final int MODE_STRICT_DND = 2;
    /** Asks the controller to re-broadcast current state (SystemUI cold start). */
    static final int MODE_QUERY = 3;
    /** Stores the approved notification's immutable key:postTime token when AI grants VIP breakthrough. */
    static final String EXTRA_BREAKTHROUGH_FP = "localai.reduce_breakthrough_fp";
    /** Monotonic sequence bumped once when an initially silenced record is upgraded to VIP. */
    static final String EXTRA_ALERT_SEQ = "localai.reduce_alert_seq";

    /**
     * Visual effects suppressed for intercepted non-VIP notifications while Reduce
     * Interruptions is active: suppresses heads-up peek (16), full-screen intent (4),
     * lights (8), and ambient/AOD pulse (128), while intentionally keeping notifications
     * visible in the lockscreen and notification shade list (does NOT set bit 256).
     */
    static final int SUPPRESSED_VISUAL_EFFECTS =
            android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                    | android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS
                    | android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK
                    | android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;

    private ReduceInterruptions() {}

    static boolean isBreakthrough(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return false;
        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return false;
        String approvedVersion = extras.getString(EXTRA_BREAKTHROUGH_FP);
        if (approvedVersion == null || approvedVersion.isEmpty()) return false;
        return approvedVersion.equals(GroupSummaryData.versionPart(sbn));
    }

    static long alertSeq(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null) {
            return 0L;
        }
        return sbn.getNotification().extras.getLong(EXTRA_ALERT_SEQ, 0L);
    }

    static void markBreakthrough(StatusBarNotification sbn, long seq) {
        Notification notification = sbn.getNotification();
        if (notification.extras == null) {
            notification.extras = new Bundle();
        }
        notification.extras.putString(EXTRA_BREAKTHROUGH_FP, GroupSummaryData.versionPart(sbn));
        notification.extras.putLong(EXTRA_ALERT_SEQ, seq);
    }

    static boolean isEligibleForClassification(Notification notification) {
        if (notification == null) return false;
        if (!PriorityNotification.isEligible(notification)) return false;
        return !Notification.CATEGORY_CALL.equals(notification.category)
                && !Notification.CATEGORY_ALARM.equals(notification.category);
    }

    // Optically centered 7-lobe {7/2} Apple Intelligence continuous woven ribbon in 24x24 space.
    // Mirrors res/drawable/ic_reduce_interruptions.xml identically.
    private static final float APPLE_INTEL_START_X = 11.01f;
    private static final float APPLE_INTEL_START_Y = 4.08f;
    private static final float[] APPLE_INTEL_CUBICS = {
            11.63f, 2.99f, 12.37f, 2.99f, 12.99f, 4.08f,
            14.96f, 7.52f, 17.40f, 10.58f, 20.31f, 13.26f,
            21.24f, 14.11f, 21.08f, 14.83f, 19.87f, 15.19f,
            16.08f, 16.34f, 12.55f, 18.04f, 9.29f, 20.29f,
            8.25f, 21.00f, 7.59f, 20.68f, 7.50f, 19.43f,
            7.23f, 15.48f, 6.36f, 11.66f, 4.89f, 7.98f,
            4.43f, 6.81f, 4.88f, 6.24f, 6.13f, 6.43f,
            10.04f, 7.04f, 13.96f, 7.04f, 17.87f, 6.43f,
            19.12f, 6.24f, 19.57f, 6.81f, 19.11f, 7.98f,
            17.64f, 11.66f, 16.77f, 15.48f, 16.50f, 19.43f,
            16.41f, 20.68f, 15.75f, 21.00f, 14.71f, 20.29f,
            11.45f, 18.04f, 7.92f, 16.34f, 4.13f, 15.19f,
            2.92f, 14.83f, 2.76f, 14.11f, 3.69f, 13.26f,
            6.60f, 10.58f, 9.04f, 7.52f, 11.01f, 4.08f,
    };

    // Vivid 360-degree Apple Intelligence sweep palettes (calibrated to avoid muddy RGB chords).
    private static final int[] APPLE_SWEEP_COLORS_DARK = {
            0xFFFF667C, // 3:00 Coral Rose
            0xFFFFB868, // 5:00 Warm Amber
            0xFFFF7AAE, // 6:45 Luminous Rose Pink
            0xFF8C92FF, // 8:25 Periwinkle
            0xFF4EC8FF, // 9:50 Luminous Cyan
            0xFF8290FF, // 11:30 Electric Indigo
            0xFFBA7AF8, // 1:00 Apple Violet
            0xFFF586D6, // 2:05 Orchid Pink
            0xFFFF667C, // 3:00 Wrap
    };

    private static final int[] APPLE_SWEEP_COLORS_LIGHT = {
            0xFFF2486A, // 3:00 Rich Coral Rose
            0xFFF69434, // 5:00 Golden Amber
            0xFFEC5698, // 6:45 Vibrant Rose Pink
            0xFF6C72F8, // 8:25 Crisp Periwinkle
            0xFF20A6F6, // 9:50 Crisp Sky Cyan
            0xFF566EF8, // 11:30 Royal Indigo
            0xFF9E52EE, // 1:00 Deep Apple Violet
            0xFFE258BE, // 2:05 Vibrant Orchid
            0xFFF2486A, // 3:00 Wrap
    };

    private static final float[] APPLE_SWEEP_POSITIONS = {
            0.00f, 0.16f, 0.31f, 0.45f, 0.57f, 0.71f, 0.83f, 0.92f, 1.00f
    };

    /**
     * Populates {@code out} with the optically centered 24x24 Apple Intelligence 7-lobe path.
     */
    static void buildAppleIntelPath(Path out) {
        out.reset();
        out.moveTo(APPLE_INTEL_START_X, APPLE_INTEL_START_Y);
        for (int i = 0; i < APPLE_INTEL_CUBICS.length; i += 6) {
            out.cubicTo(
                    APPLE_INTEL_CUBICS[i], APPLE_INTEL_CUBICS[i + 1],
                    APPLE_INTEL_CUBICS[i + 2], APPLE_INTEL_CUBICS[i + 3],
                    APPLE_INTEL_CUBICS[i + 4], APPLE_INTEL_CUBICS[i + 5]);
        }
        out.close();
    }

    /**
     * Draws the monochrome Apple Intelligence 7-lobe continuous woven ribbon normalized to
     * a 24x24 viewBox into the given square bounds.
     */
    static void drawAppleIntelMono(Canvas canvas, float left, float top, float size,
            Paint strokePaint, float strokeWidth24, Path reusablePath) {
        if (size <= 0f) return;
        Path path = reusablePath != null ? reusablePath : new Path();
        if (path.isEmpty()) {
            buildAppleIntelPath(path);
        }
        float scale = size / 24f;
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(strokeWidth24);
        canvas.drawPath(path, strokePaint);

        canvas.restore();
    }

    /**
     * Reusable zero-allocation painter for the full-color luminous Apple Intelligence
     * iridescent ribbon effect (hardware-acceleration safe; avoids BlurMaskFilter).
     */
    static final class AppleIntelIridescentPainter {
        private final boolean darkBg;
        private final Path path = new Path();
        private final SweepGradient sweepGradient;
        private final Matrix shaderMatrix = new Matrix();
        private final Paint ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        AppleIntelIridescentPainter(boolean darkBg) {
            this.darkBg = darkBg;
            buildAppleIntelPath(path);
            sweepGradient = new SweepGradient(
                    12f, 12f,
                    darkBg ? APPLE_SWEEP_COLORS_DARK : APPLE_SWEEP_COLORS_LIGHT,
                    APPLE_SWEEP_POSITIONS);
            ribbonPaint.setStyle(Paint.Style.STROKE);
            ribbonPaint.setStrokeCap(Paint.Cap.ROUND);
            ribbonPaint.setStrokeJoin(Paint.Join.ROUND);
            ribbonPaint.setShader(sweepGradient);

            sheenPaint.setStyle(Paint.Style.STROKE);
            sheenPaint.setStrokeCap(Paint.Cap.ROUND);
            sheenPaint.setStrokeJoin(Paint.Join.ROUND);
            sheenPaint.setColor(Color.WHITE);
        }

        void draw(Canvas canvas, float left, float top, float size,
                float strokeWidth24, float insetFraction, float phaseDegrees, float alphaScale) {
            if (size <= 0f || alphaScale <= 0.004f) return;
            float clampedAlpha = Math.max(0f, Math.min(1f, alphaScale));
            float inset = size * insetFraction;
            float drawSize = size - inset * 2f;
            if (drawSize <= 0f) return;
            float scale = drawSize / 24f;

            shaderMatrix.setRotate(phaseDegrees, 12f, 12f);
            sweepGradient.setLocalMatrix(shaderMatrix);

            canvas.save();
            canvas.translate(left + inset, top + inset);
            canvas.scale(scale, scale);

            // 1. Multi-stage ambient iridescent bloom (HW-canvas safe)
            if (darkBg) {
                drawGlowPass(canvas, strokeWidth24 * 2.75f, Math.round(28 * clampedAlpha));
                drawGlowPass(canvas, strokeWidth24 * 2.05f, Math.round(50 * clampedAlpha));
                drawGlowPass(canvas, strokeWidth24 * 1.50f, Math.round(76 * clampedAlpha));
            } else {
                drawGlowPass(canvas, strokeWidth24 * 2.15f, Math.round(22 * clampedAlpha));
                drawGlowPass(canvas, strokeWidth24 * 1.55f, Math.round(40 * clampedAlpha));
            }

            // 2. Crisp vibrant iridescent ribbon
            ribbonPaint.setStrokeWidth(strokeWidth24);
            ribbonPaint.setAlpha(Math.round(255 * clampedAlpha));
            canvas.drawPath(path, ribbonPaint);

            // 3. Liquid-glass luminous specular highlight core
            int sheenAlpha = Math.round((darkBg ? 128 : 80) * clampedAlpha);
            if (sheenAlpha > 0) {
                sheenPaint.setStrokeWidth(strokeWidth24 * 0.34f);
                sheenPaint.setAlpha(sheenAlpha);
                canvas.drawPath(path, sheenPaint);
            }

            canvas.restore();
        }

        private void drawGlowPass(Canvas canvas, float strokeWidth, int alpha) {
            if (alpha <= 0) return;
            ribbonPaint.setStrokeWidth(strokeWidth);
            ribbonPaint.setAlpha(alpha);
            canvas.drawPath(path, ribbonPaint);
        }
    }

    /**
     * Crisp monochrome vector drawable for the SystemUI status bar "zen" slot.
     * Drawn in pure white so StatusBarIconView / DarkIconDispatcher automatically
     * tints it for both light and dark status bars.
     */
    static Drawable createStatusBarDrawable(Context context) {
        float density = context != null
                ? context.getResources().getDisplayMetrics().density
                : 3f;
        int intrinsicPx = Math.max(18, Math.round(17f * density));
        return new AppleIntelMonoDrawable(intrinsicPx, 1.78f);
    }

    /**
     * Crisp monochrome vector drawable for the HyperOS volume panel Reduce Interruptions button.
     */
    static Drawable createVolumeDrawable(Context context) {
        float density = context != null
                ? context.getResources().getDisplayMetrics().density
                : 3f;
        int intrinsicPx = Math.max(24, Math.round(22f * density));
        return new AppleIntelMonoDrawable(intrinsicPx, 1.72f);
    }

    /**
     * High-DPI monochrome bitmap Icon for Quick Settings TileService & requestAddTileService.
     */
    static Icon createTileIcon(Context context) {
        float density = context != null
                ? context.getResources().getDisplayMetrics().density
                : 3f;
        int size = Math.max(48, Math.round(24f * density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        drawAppleIntelMono(canvas, 0f, 0f, size, stroke, 1.72f, null);
        return Icon.createWithBitmap(bitmap);
    }

    /**
     * High-DPI Icon for HyperOS Super Island: luminous Apple Intelligence iridescent glow when
     * enabled, and calm muted silver monochrome when disabled.
     */
    static Icon createIslandIcon(Context context, boolean active) {
        float density = context != null
                ? context.getResources().getDisplayMetrics().density
                : 3f;
        int size = Math.max(72, Math.round(32f * density));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (active) {
            AppleIntelIridescentPainter painter = new AppleIntelIridescentPainter(true);
            painter.draw(canvas, 0f, 0f, size, 1.68f, 0.06f, 0f, 1f);
        } else {
            float inset = size * 0.08f;
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(0xD8C2C8D4);
            drawAppleIntelMono(canvas, inset, inset, size - inset * 2f, stroke, 1.72f, null);
        }
        return Icon.createWithBitmap(bitmap);
    }

    private static final class AppleIntelMonoDrawable extends Drawable {
        private final int intrinsicSize;
        private final float strokeWidth24;
        private final Path cachedPath = new Path();
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tintColor = Color.WHITE;
        private int drawableAlpha = 255;

        AppleIntelMonoDrawable(int intrinsicSize, float strokeWidth24) {
            this.intrinsicSize = intrinsicSize;
            this.strokeWidth24 = strokeWidth24;
            buildAppleIntelPath(cachedPath);
            applyPaintColor();
        }

        private void applyPaintColor() {
            int combinedAlpha = (Color.alpha(tintColor) * drawableAlpha) / 255;
            int color = Color.argb(combinedAlpha, Color.red(tintColor),
                    Color.green(tintColor), Color.blue(tintColor));
            strokePaint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float w = bounds.width();
            float h = bounds.height();
            float size = Math.min(w, h);
            if (size <= 0f) return;
            float left = bounds.left + (w - size) * 0.5f;
            float top = bounds.top + (h - size) * 0.5f;
            drawAppleIntelMono(canvas, left, top, size, strokePaint, strokeWidth24, cachedPath);
        }

        @Override
        public int getIntrinsicWidth() {
            return intrinsicSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return intrinsicSize;
        }

        @Override
        public void setAlpha(int alpha) {
            drawableAlpha = Math.max(0, Math.min(255, alpha));
            applyPaintColor();
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            strokePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public void setTint(int tint) {
            tintColor = tint;
            applyPaintColor();
            invalidateSelf();
        }

        @Override
        public void setTintList(android.content.res.ColorStateList tint) {
            tintColor = tint != null ? tint.getDefaultColor() : Color.WHITE;
            applyPaintColor();
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
