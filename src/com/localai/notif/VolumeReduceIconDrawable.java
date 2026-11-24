package com.localai.notif;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Animated sci-fi neon drawable for the HyperOS volume panel Reduce Interruptions button.
 */
final class VolumeReduceIconDrawable extends Drawable {
    private static final long ACTIVATE_MS = 560L;
    private static final long DEACTIVATE_MS = 360L;
    private static final long FLOW_PERIOD_MS = 6_000L;
    private static final float STROKE_24 = 1.72f;
    private static final TimeInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    // {x, y, radius, startT} in 24x24 coordinates
    private static final float[][] STARS = {
            {19.8f, 4.4f, 2.1f, 0.30f},
            {4.0f, 19.4f, 1.6f, 0.42f},
            {20.0f, 19.8f, 1.1f, 0.52f},
    };

    private final int intrinsicSize;
    private final Path ribbon = new Path();
    private final Path segment = new Path();
    private final Path star = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float length;
    private final float[] pos = new float[2];
    private final Matrix shaderMatrix = new Matrix();

    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint colorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cometPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private SweepGradient gradient;
    private boolean xiaoAi;
    private boolean active;
    private int mode; // 0 steady, 1 activating, 2 deactivating
    private float t;
    private float flowDegrees;
    private int alpha = 255;
    private ValueAnimator transition;
    private ValueAnimator flow;

    VolumeReduceIconDrawable(Context context, boolean active) {
        float density = context != null ? context.getResources().getDisplayMetrics().density : 3f;
        this.intrinsicSize = Math.max(24, Math.round(22f * density));

        initStrokePaint(whitePaint);
        whitePaint.setColor(Color.WHITE);
        initStrokePaint(colorPaint);
        initStrokePaint(glowPaint);
        initStrokePaint(sheenPaint);
        sheenPaint.setColor(Color.WHITE);

        starPaint.setStyle(Paint.Style.FILL);
        cometPaint.setStyle(Paint.Style.FILL);
        cometPaint.setColor(Color.WHITE);

        ReduceInterruptions.buildAppleIntelPath(ribbon);
        measure.setPath(ribbon, false);
        length = measure.getLength();
        this.active = active;
        applyPalette(GlowStyle.isXiaoAi());
    }

    private static void initStrokePaint(Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void applyPalette(boolean x) {
        if (gradient != null && x == xiaoAi) return;
        xiaoAi = x;
        int[] colors;
        float[] stops;
        if (x) {
            colors = new int[] {
                    GlowStyle.XIAOAI_LIGHT_BLUE,
                    GlowStyle.XIAOAI_LIGHT_VIOLET,
                    GlowStyle.XIAOAI_LIGHT_ROSE,
                    GlowStyle.XIAOAI_LIGHT_ORANGE,
                    GlowStyle.XIAOAI_LIGHT_VIOLET,
                    GlowStyle.XIAOAI_LIGHT_BLUE,
            };
            stops = new float[] {0f, 0.2f, 0.42f, 0.6f, 0.8f, 1f};
        } else {
            colors = new int[] {
                    GlowStyle.NEBULA_LIGHT_CYAN,
                    GlowStyle.NEBULA_LIGHT_AZURE,
                    GlowStyle.NEBULA_LIGHT_VIOLET,
                    GlowStyle.NEBULA_LIGHT_CYAN,
            };
            stops = new float[] {0f, 0.34f, 0.67f, 1f};
        }
        gradient = new SweepGradient(12f, 12f, colors, stops);
        colorPaint.setShader(gradient);
        glowPaint.setShader(gradient);
        starPaint.setShader(gradient);
    }

    void setActive(boolean next, boolean animate) {
        applyPalette(GlowStyle.isXiaoAi());
        if (next == active) {
            invalidateSelf();
            return;
        }
        active = next;
        cancelTransition();
        if (animate && isVisible() && ValueAnimator.areAnimatorsEnabled()
                && Looper.myLooper() == Looper.getMainLooper()) {
            mode = next ? 1 : 2;
            t = 0f;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(next ? ACTIVATE_MS : DEACTIVATE_MS);
            a.setInterpolator(new LinearInterpolator());
            a.addUpdateListener(v -> {
                t = (float) v.getAnimatedValue();
                invalidateSelf();
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator anim) {
                    if (transition == anim) {
                        transition = null;
                        mode = 0;
                        invalidateSelf();
                    }
                }
            });
            transition = a;
            a.start();
        } else {
            mode = 0;
        }
        updateFlow();
        invalidateSelf();
    }

    private void cancelTransition() {
        if (transition != null) {
            ValueAnimator a = transition;
            transition = null;
            a.cancel();
        }
        mode = 0;
    }

    private void updateFlow() {
        boolean want = active && isVisible() && ValueAnimator.areAnimatorsEnabled()
                && Looper.myLooper() == Looper.getMainLooper();
        if (want && flow == null) {
            float base = flowDegrees;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 360f);
            a.setDuration(FLOW_PERIOD_MS);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new LinearInterpolator());
            a.addUpdateListener(v -> {
                flowDegrees = (base + (float) v.getAnimatedValue()) % 360f;
                invalidateSelf();
            });
            flow = a;
            a.start();
        } else if (!want && flow != null) {
            ValueAnimator a = flow;
            flow = null;
            a.cancel();
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible && transition != null) {
            cancelTransition();
        }
        updateFlow();
        return changed;
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

        float trace = 1f;
        float scale = 1f;
        float rotation = 0f;
        float comet = 0f;
        float spin = 0f;
        float whiteAmt;
        float colorAmt;

        if (mode == 1) {
            float e = EASE.getInterpolation(t);
            trace = EASE.getInterpolation(clamp(t / 0.72f));
            whiteAmt = 1f - clamp((t - 0.30f) / 0.45f);
            colorAmt = 1f;
            scale = 1f + 0.12f * (float) Math.sin(Math.PI * clamp(t / 0.62f));
            rotation = -36f * (1f - e);
            comet = trace < 1f ? 1f : 1f - clamp((t - 0.72f) / 0.14f);
            spin = 200f * (1f - e);
        } else if (mode == 2) {
            float e = EASE.getInterpolation(t);
            colorAmt = 1f - e;
            whiteAmt = e;
            scale = 1f - 0.10f * (float) Math.sin(Math.PI * t);
            rotation = 18f * (float) Math.sin(Math.PI * t);
            spin = -120f * e;
        } else {
            colorAmt = active ? 1f : 0f;
            whiteAmt = active ? 0f : 1f;
        }

        canvas.save();
        canvas.translate(left, top);
        float viewScale = size / 24f;
        canvas.scale(viewScale, viewScale);
        if (rotation != 0f) {
            canvas.rotate(rotation, 12f, 12f);
        }
        if (scale != 1f) {
            canvas.scale(scale, scale, 12f, 12f);
        }

        if (whiteAmt > 0.004f) {
            whitePaint.setStrokeWidth(STROKE_24);
            whitePaint.setAlpha(a(whiteAmt));
            canvas.drawPath(ribbon, whitePaint);
        }

        if (colorAmt > 0.004f) {
            shaderMatrix.setRotate(flowDegrees + spin, 12f, 12f);
            gradient.setLocalMatrix(shaderMatrix);
            Path p = ribbon;
            if (trace < 0.999f) {
                segment.reset();
                if (trace > 0f && length > 0f) {
                    measure.getSegment(0f, length * trace, segment, true);
                }
                p = segment;
            }

            int glowAlpha1 = Math.round(26f * clamp(colorAmt) * alpha / 255f);
            if (glowAlpha1 > 0) {
                glowPaint.setStrokeWidth(STROKE_24 * 2.6f);
                glowPaint.setAlpha(glowAlpha1);
                canvas.drawPath(p, glowPaint);
            }
            int glowAlpha2 = Math.round(48f * clamp(colorAmt) * alpha / 255f);
            if (glowAlpha2 > 0) {
                glowPaint.setStrokeWidth(STROKE_24 * 1.8f);
                glowPaint.setAlpha(glowAlpha2);
                canvas.drawPath(p, glowPaint);
            }

            colorPaint.setStrokeWidth(STROKE_24);
            colorPaint.setAlpha(a(colorAmt));
            canvas.drawPath(p, colorPaint);

            int sheenAlpha = a(colorAmt * 0.42f);
            if (sheenAlpha > 0) {
                sheenPaint.setStrokeWidth(STROKE_24 * 0.34f);
                sheenPaint.setAlpha(sheenAlpha);
                canvas.drawPath(p, sheenPaint);
            }

            if (comet > 0.004f && trace > 0f && length > 0f) {
                measure.getPosTan(length * trace, pos, null);
                int haloAlpha = a(comet * 0.35f);
                if (haloAlpha > 0) {
                    starPaint.setAlpha(haloAlpha);
                    canvas.drawCircle(pos[0], pos[1], STROKE_24 * 1.9f, starPaint);
                }
                int coreAlpha = a(comet * 0.95f);
                if (coreAlpha > 0) {
                    cometPaint.setAlpha(coreAlpha);
                    canvas.drawCircle(pos[0], pos[1], STROKE_24 * 0.95f, cometPaint);
                }
            }

            if (mode == 1) {
                for (float[] s : STARS) {
                    float local = (t - s[3]) / 0.38f;
                    if (local <= 0f || local >= 1f) continue;
                    float pop = (float) Math.sin(Math.PI * local);
                    float x = s[0];
                    float y = s[1];
                    float r = s[2] * 1.15f * pop;
                    float k = r * 0.28f;
                    star.reset();
                    star.moveTo(x, y - r);
                    star.quadTo(x + k, y - k, x + r, y);
                    star.quadTo(x + k, y + k, x, y + r);
                    star.quadTo(x - k, y + k, x - r, y);
                    star.quadTo(x - k, y - k, x, y - r);
                    star.close();
                    canvas.save();
                    canvas.rotate(90f * local, x, y);
                    starPaint.setAlpha(a(pop));
                    canvas.drawPath(star, starPaint);
                    canvas.restore();
                }
            }
        }

        canvas.restore();
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private int a(float f) {
        return Math.round(255f * clamp(f) * alpha / 255f);
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
        this.alpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        whitePaint.setColorFilter(colorFilter);
        colorPaint.setColorFilter(colorFilter);
        glowPaint.setColorFilter(colorFilter);
        sheenPaint.setColorFilter(colorFilter);
        starPaint.setColorFilter(colorFilter);
        cometPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        // Ignore external ImageView tint so it cannot flatten the gradient.
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
