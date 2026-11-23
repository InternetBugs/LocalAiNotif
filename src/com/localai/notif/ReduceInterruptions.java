package com.localai.notif;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * Shared protocol constants, eligibility helpers, and the custom monochrome
 * "星芒叠层" (Sparkle Stack) vector icon used in both the status bar and
 * Control Center Quick Settings tile.
 */
final class ReduceInterruptions {
    static final String ACTION_STATE_CHANGED = "com.localai.notif.REDUCE_STATE_CHANGED";
    static final String ACTION_REQUEST_MODE = "com.localai.notif.REDUCE_REQUEST_MODE";

    static final String EXTRA_ACTIVE = "active";
    static final String EXTRA_LOCK_SCREEN_ONLY = "lock_screen_only";
    static final String EXTRA_ZEN_MODE = "zen_mode";
    static final String EXTRA_MODE = "mode";

    static final int MODE_OFF = 0;
    static final int MODE_REDUCE = 1;
    static final int MODE_STRICT_DND = 2;

    /** Stores the approved notification's itemFingerprint when AI grants VIP breakthrough. */
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
        String approvedFp = extras.getString(EXTRA_BREAKTHROUGH_FP);
        if (approvedFp == null || approvedFp.isEmpty()) return false;
        return approvedFp.equals(GroupSummaryData.itemFingerprint(sbn));
    }

    static long alertSeq(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null) {
            return 0L;
        }
        return sbn.getNotification().extras.getLong(EXTRA_ALERT_SEQ, 0L);
    }

    static void markBreakthrough(StatusBarNotification sbn, String fingerprint, long seq) {
        Notification notification = sbn.getNotification();
        if (notification.extras == null) {
            notification.extras = new Bundle();
        }
        notification.extras.putString(EXTRA_BREAKTHROUGH_FP, fingerprint);
        notification.extras.putLong(EXTRA_ALERT_SEQ, seq);
    }

    static boolean isEligibleForClassification(Notification notification) {
        if (notification == null) return false;
        if (!PriorityNotification.isEligible(notification)) return false;
        return !Notification.CATEGORY_CALL.equals(notification.category)
                && !Notification.CATEGORY_ALARM.equals(notification.category);
    }

    /**
     * Draws the monochrome "星芒叠层" (Sparkle Stack) vector icon normalized to a 24x24 viewBox
     * into the given square bounds. Matches res/drawable/ic_reduce_interruptions.xml exactly.
     */
    static void drawSparkleStack(Canvas canvas, float left, float top, float size,
            Paint strokePaint, Paint fillPaint) {
        if (size <= 0f) return;
        float scale = size / 24f;
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        // 1. Subtle back stacked card edge
        Path backEdge = new Path();
        backEdge.moveTo(5.6f, 19.4f);
        backEdge.lineTo(15.4f, 19.4f);
        backEdge.cubicTo(16.6f, 19.4f, 17.5f, 18.7f, 17.8f, 17.8f);
        strokePaint.setStrokeWidth(1.65f);
        canvas.drawPath(backEdge, strokePaint);

        // 2. Primary rounded notification card contour (open top-right for sparkle)
        Path card = new Path();
        card.moveTo(11.6f, 5.8f);
        card.lineTo(6.2f, 5.8f);
        card.cubicTo(4.6f, 5.8f, 3.4f, 7.0f, 3.4f, 8.6f);
        card.lineTo(3.4f, 13.6f);
        card.cubicTo(3.4f, 15.2f, 4.6f, 16.4f, 6.2f, 16.4f);
        card.lineTo(16.2f, 16.4f);
        card.cubicTo(17.8f, 16.4f, 19.0f, 15.2f, 19.0f, 13.6f);
        card.lineTo(19.0f, 11.9f);
        strokePaint.setStrokeWidth(1.8f);
        canvas.drawPath(card, strokePaint);

        // 3. Minimal interior message lines
        strokePaint.setStrokeWidth(1.65f);
        canvas.drawLine(6.8f, 10.0f, 11.4f, 10.0f, strokePaint);
        canvas.drawLine(6.8f, 13.0f, 14.4f, 13.0f, strokePaint);

        // 4. Crisp 4-point AI sparkle star at top-right
        fillPaint.setStyle(Paint.Style.FILL);
        Path star = new Path();
        star.moveTo(17.2f, 2.2f);
        star.quadTo(17.6f, 5.5f, 21.4f, 6.4f);
        star.quadTo(17.6f, 7.3f, 17.2f, 10.6f);
        star.quadTo(16.8f, 7.3f, 13.0f, 6.4f);
        star.quadTo(16.8f, 5.5f, 17.2f, 2.2f);
        star.close();
        canvas.drawPath(star, fillPaint);

        canvas.restore();
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
        return new SparkleStackDrawable(intrinsicPx);
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
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        drawSparkleStack(canvas, 0f, 0f, size, stroke, fill);
        return Icon.createWithBitmap(bitmap);
    }

    private static final class SparkleStackDrawable extends Drawable {
        private final int intrinsicSize;
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int tintColor = Color.WHITE;
        private int drawableAlpha = 255;

        SparkleStackDrawable(int intrinsicSize) {
            this.intrinsicSize = intrinsicSize;
            applyPaintColor();
        }

        private void applyPaintColor() {
            int combinedAlpha = (Color.alpha(tintColor) * drawableAlpha) / 255;
            int color = Color.argb(combinedAlpha, Color.red(tintColor),
                    Color.green(tintColor), Color.blue(tintColor));
            strokePaint.setColor(color);
            fillPaint.setColor(color);
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
            drawSparkleStack(canvas, left, top, size, strokePaint, fillPaint);
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
            fillPaint.setColorFilter(colorFilter);
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
