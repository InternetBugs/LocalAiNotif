package com.localai.notif;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.List;

final class PrioritySectionFrame implements ViewTreeObserver.OnPreDrawListener {
    private static final int OUTLINE_COLOR = 0xffffcd73;
    private static final int BACKGROUND_COLOR = 0xff241c15;

    private final View heading;
    private final View headingContents;
    private final Method getClipHeight;
    private final Method getClipTopAmount;
    private final Method getTopOverlap;
    private final Method getSuperAlpha;
    private final Method getSuperTransitionAlpha;
    private final Method getIsExpanded;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF nextBounds = new RectF();
    private final RectF childBounds = new RectF();
    private final float density;
    private ViewGroup stack;
    private ViewTreeObserver observer;
    private List<View> rows = List.of();
    private View following;
    private int alpha;
    private float headingOffset;
    private boolean boundsFailed;

    PrioritySectionFrame(View heading, View headingContents, Method getClipHeight, Method getClipTopAmount,
                         Method getTopOverlap, Method getSuperAlpha, Method getSuperTransitionAlpha,
                         Method getIsExpanded) {
        this.heading = heading;
        this.headingContents = headingContents;
        this.getClipHeight = getClipHeight;
        this.getClipTopAmount = getClipTopAmount;
        this.getTopOverlap = getTopOverlap;
        this.getSuperAlpha = getSuperAlpha;
        this.getSuperTransitionAlpha = getSuperTransitionAlpha;
        this.getIsExpanded = getIsExpanded;
        density = heading.getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(1.5f * density);
    }

    void setRows(List<View> rows, View following) {
        this.rows = rows;
        this.following = following;
        if (stack != null) stack.invalidate();
    }

    void attach(ViewGroup parent) {
        stack = parent;
        observer = parent.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        parent.invalidate();
    }

    ViewGroup detach() {
        ViewGroup previous = stack;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        stack = null;
        bounds.setEmpty();
        if (previous != null) previous.invalidate();
        return previous;
    }

    @Override
    public boolean onPreDraw() {
        nextBounds.setEmpty();
        int nextAlpha = 0;
        float previousHeadingOffset = headingOffset;
        if (!boundsFailed && stack != null && !rows.isEmpty()) {
            try {
                nextAlpha = updateBounds();
            } catch (ReflectiveOperationException exception) {
                boundsFailed = true;
                Log.w("LocalAiNotif", "Failed to position importance frame", exception);
            }
        }
        if (nextAlpha == 0) nextBounds.setEmpty();
        if (stack != null && (!bounds.equals(nextBounds) || alpha != nextAlpha
                || previousHeadingOffset != headingOffset)) {
            if ((alpha == 0) != (nextAlpha == 0)) {
                heading.setImportantForAccessibility(nextAlpha == 0
                        ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
            bounds.set(nextBounds);
            alpha = nextAlpha;
            stack.invalidate();
        }
        return true;
    }

    private int updateBounds() throws ReflectiveOperationException {
        if (!(boolean) getIsExpanded.invoke(stack) || !stack.isShown()) return 0;
        if (!getVisibleBounds(heading, childBounds)) return 0;
        nextBounds.set(childBounds);
        float firstCardTop = Float.POSITIVE_INFINITY;
        float lastCardBottom = Float.NEGATIVE_INFINITY;
        float cardAlpha = 0f;
        float headingAlpha = getDrawnAlpha(heading);
        float cardLeft = Float.POSITIVE_INFINITY;
        float cardRight = Float.NEGATIVE_INFINITY;
        for (View row : rows) {
            float rowAlpha = getDrawnAlpha(row);
            if (rowAlpha < 0.08f) continue;
            if (!getVisibleBounds(row, childBounds)) continue;
            if (childBounds.width() < row.getWidth() * 0.75f) continue;
            firstCardTop = Math.min(firstCardTop, childBounds.top);
            lastCardBottom = Math.max(lastCardBottom, childBounds.bottom);
            cardLeft = Math.min(cardLeft, childBounds.left);
            cardRight = Math.max(cardRight, childBounds.right);
            cardAlpha = Math.max(cardAlpha, rowAlpha);
        }
        if (cardAlpha == 0f || headingAlpha <= 0f) return 0;
        headingOffset = Math.min(0f, firstCardTop - nextBounds.bottom - 2f * density);
        nextBounds.offset(0f, headingOffset);
        nextBounds.left = Math.min(nextBounds.left, cardLeft);
        nextBounds.right = Math.max(nextBounds.right, cardRight);
        headingContents.setAlpha(Math.min(1f, cardAlpha / headingAlpha));

        float frameBottom = lastCardBottom + 4f * density;
        if (getVisibleBounds(following, childBounds)) {
            frameBottom = Math.min(frameBottom,
                    Math.min(childBounds.top, (lastCardBottom + childBounds.top) / 2f));
        }
        float horizontalPadding = 4f * density;
        float halfStroke = paint.getStrokeWidth() / 2f;
        nextBounds.left = Math.max(halfStroke, nextBounds.left - horizontalPadding);
        nextBounds.right = Math.min(stack.getWidth() - halfStroke, nextBounds.right + horizontalPadding);
        nextBounds.top -= 2f * density;
        nextBounds.bottom = frameBottom - halfStroke;
        if (nextBounds.bottom <= firstCardTop) return 0;
        return Math.round(255f * Math.min(headingAlpha, cardAlpha));
    }

    private float getDrawnAlpha(View view) throws ReflectiveOperationException {
        return (float) getSuperAlpha.invoke(view) * (float) getSuperTransitionAlpha.invoke(view);
    }

    private boolean getVisibleBounds(View child, RectF result) throws ReflectiveOperationException {
        if (child == null || child.getParent() != stack || child.getVisibility() != View.VISIBLE
                || child.getAlpha() <= 0f || child.getWidth() <= 0) return false;
        int height = (int) getClipHeight.invoke(child);
        if (height <= 0) return false;
        int top = Math.max(0, Math.max((int) getClipTopAmount.invoke(child), (int) getTopOverlap.invoke(child)));
        result.set(0, top, child.getWidth(), top + height);
        child.getMatrix().mapRect(result);
        result.offset(child.getLeft(), child.getTop());
        return !result.isEmpty();
    }

    boolean isHeading(Object child) {
        return child == heading;
    }

    boolean isVisible() {
        return alpha > 0 && !bounds.isEmpty();
    }

    float headingOffset() {
        return headingOffset;
    }

    void drawBackground(Canvas canvas) {
        draw(canvas, Paint.Style.FILL, BACKGROUND_COLOR, alpha * 68 / 255);
    }

    void drawOutline(Canvas canvas) {
        draw(canvas, Paint.Style.STROKE, OUTLINE_COLOR, alpha * 224 / 255);
    }

    private void draw(Canvas canvas, Paint.Style style, int color, int opacity) {
        if (bounds.isEmpty() || opacity == 0) return;
        paint.setStyle(style);
        paint.setColor(color);
        paint.setAlpha(opacity);
        float radius = 24f * density;
        canvas.drawRoundRect(bounds, radius, radius, paint);
    }
}
