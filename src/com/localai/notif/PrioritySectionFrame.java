package com.localai.notif;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Renders the "Nebula Aurora Ambient Glass Field x Per-Card Liquid Prism" presentation for
 * priority notifications in NotificationStackScrollLayout:
 * <ul>
 *   <li><b>Outer Ambient Glass Field (有场无硬框)</b>: A sheer, concentric-squircle translucent
 *       Nebula mist chamber with a whisper-thin 0.7dp specular glass hairline softly binding the
 *       "✦ 重要通知" micro-capsule and all visible priority cards into one cohesive luminous unit,
 *       with continuous subtle Nebula flow across inter-card gaps.</li>
 *   <li><b>Inner Per-Card Dual-Layer Prism</b>: Each priority card keeps its own crisp 1.25dp
 *       Nebula Aurora squircle prism rim, top liquid-glass catchlight, and tight outward-clipped
 *       soft rim glow (with back-card occlusion clipping in lockscreen stacked decks).</li>
 *   <li><b>Synchronized 1.4s One-Shot Shimmer</b>: Glides smoothly across the field hairline,
 *       micro-capsule, and every priority card upon reveal or new VIP arrival, then rests quietly.</li>
 *   <li><b>Auto Dark/Light Adaptation</b>: Samples live card ink luminance to match HyperOS smoked
 *       dark glass or light frosted white glass seamlessly.</li>
 * </ul>
 */
final class PrioritySectionFrame implements ViewTreeObserver.OnPreDrawListener {
    private static final String TAG = "LocalAiNotif";
    private static final long SHIMMER_DURATION_MS = 1400L;
    private static final long REVEAL_DEBOUNCE_MS = 450L;
    private static final float REST_ANGLE_DEG = -32f;
    private static final float DEFAULT_CARD_RADIUS_DP = 25f;
    private static final float HEADING_GAP_DP = 6.5f;
    private static final float HEADING_OPTICAL_INSET_DP = 2f;
    private static final float FIELD_PAD_H_DP = 7.5f;
    private static final float FIELD_PAD_TOP_DP = 6.0f;
    private static final float FIELD_PAD_BOTTOM_EXPANDED_DP = 7.5f;
    private static final float FIELD_PAD_BOTTOM_STACKED_DP = 3.5f;

    private static final PathInterpolator SHIMMER_EASE =
            new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);

    // Dark / lockscreen smoked-glass Nebula Aurora stops (Sky Cyan -> Azure -> Ice Violet -> Moon White)
    private static final int[] DARK_RIM_COLORS = {
            0xDC5CE1FF, 0xD03D8BFF, 0xDCA17DFF, 0xEAEDF6FF, 0xD264C9FF, 0xDC5CE1FF
    };
    private static final int[] DARK_AURA_COLORS = {
            0xFF5CE1FF, 0xFF3D8BFF, 0xFFA17DFF, 0xFFB8DCFF, 0xFF5CE1FF
    };
    private static final int[] DARK_FIELD_RIM_COLORS = {
            0x6EEDF6FF, 0x525CE1FF, 0x423D8BFF, 0x4EA17DFF, 0x2CFFFFFF, 0x6EEDF6FF
    };
    private static final int[] DARK_FIELD_FILL_COLORS = {
            0x285CE1FF, 0x1D3D8BFF, 0x2AA17DFF
    };
    private static final int[] DARK_COMET_COLORS = {
            0x005CE1FF, 0x005CE1FF, 0xB85CE1FF, 0xFFFFFFFF, 0xD4A17DFF, 0x00A17DFF, 0x005CE1FF
    };
    private static final int[] DARK_DIVIDER_COLORS = {
            0x005CE1FF, 0x4280CEFF, 0x5CEDF6FF, 0x40A17DFF, 0x00A17DFF
    };

    // Light notification shade frosted-glass Nebula Aurora stops (deeper chroma to stay crisp on white)
    private static final int[] LIGHT_RIM_COLORS = {
            0xE01CA3EC, 0xDC2B75F0, 0xE07458F2, 0xC8B2D8FF, 0xDA2F96F4, 0xE01CA3EC
    };
    private static final int[] LIGHT_AURA_COLORS = {
            0xFF1CA3EC, 0xFF2B75F0, 0xFF7458F2, 0xFF72B8FF, 0xFF1CA3EC
    };
    private static final int[] LIGHT_FIELD_RIM_COLORS = {
            0x661CA3EC, 0x582B75F0, 0x627458F2, 0x80FFFFFF, 0x523399F5, 0x661CA3EC
    };
    private static final int[] LIGHT_FIELD_FILL_COLORS = {
            0x1F1CA3EC, 0x182B75F0, 0x227458F2
    };
    private static final int[] LIGHT_COMET_COLORS = {
            0x001CA3EC, 0x001CA3EC, 0xB01CA3EC, 0xF2FFFFFF, 0xC87458F2, 0x007458F2, 0x001CA3EC
    };
    private static final int[] LIGHT_DIVIDER_COLORS = {
            0x001CA3EC, 0x362B75F0, 0x4C7458F2, 0x361CA3EC, 0x001CA3EC
    };

    private static final float[] RIM_POSITIONS = {0.00f, 0.22f, 0.46f, 0.68f, 0.85f, 1.00f};
    private static final float[] AURA_POSITIONS = {0.00f, 0.25f, 0.52f, 0.78f, 1.00f};
    private static final float[] FIELD_FILL_POSITIONS = {0.00f, 0.48f, 1.00f};
    private static final float[] COMET_POSITIONS = {0.00f, 0.70f, 0.80f, 0.88f, 0.94f, 0.99f, 1.00f};
    private static final float[] DIVIDER_POSITIONS = {0.00f, 0.22f, 0.50f, 0.78f, 1.00f};

    // Tighter focused per-card outer glow (widths in dp -> outward reach up to ~3.8dp inside field)
    private static final float[] CARD_AURA_WIDTHS_DP = {7.6f, 4.8f, 2.4f};
    private static final float[] CARD_AURA_REST_DARK = {0.042f, 0.068f, 0.095f};
    private static final float[] CARD_AURA_BOOST_DARK = {0.046f, 0.070f, 0.090f};
    private static final float[] CARD_AURA_REST_LIGHT = {0.032f, 0.054f, 0.078f};
    private static final float[] CARD_AURA_BOOST_LIGHT = {0.038f, 0.058f, 0.076f};

    // Whisper-soft outer ambient field halo (2 passes, outward reach ~4.5dp)
    private static final float[] FIELD_HALO_WIDTHS_DP = {9.0f, 4.4f};
    private static final float[] FIELD_HALO_REST_DARK = {0.026f, 0.042f};
    private static final float[] FIELD_HALO_BOOST_DARK = {0.032f, 0.048f};
    private static final float[] FIELD_HALO_REST_LIGHT = {0.018f, 0.032f};
    private static final float[] FIELD_HALO_BOOST_LIGHT = {0.024f, 0.038f};

    private final View heading;
    private final View headingContents;
    private final SystemUiPrioritySection.PriorityCapsuleView capsuleView;
    private final Method getClipHeight;
    private final Method getClipTopAmount;
    private final Method getTopOverlap;
    private final Method getSuperAlpha;
    private final Method getSuperTransitionAlpha;
    private final Method getIsExpanded;

    private final Paint fieldFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fieldSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fieldRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fieldCometPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gapBridgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint auraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cometPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF scratchRect = new RectF();
    private final RectF headingRect = new RectF();
    private final RectF followingRect = new RectF();
    private final RectF fieldRect = new RectF();
    private final RectF fieldRimRect = new RectF();
    private final RectF bridgeRect = new RectF();
    private final Path fieldPath = new Path();
    private final Path fieldRimPath = new Path();
    private final Matrix shaderMatrix = new Matrix();

    private final ArrayList<CardSlot> activeSlots = new ArrayList<>(4);
    private final ArrayList<CardSlot> slotPool = new ArrayList<>(6);
    private final Set<View> knownRows = Collections.newSetFromMap(new IdentityHashMap<>());

    private final float density;
    private ViewGroup stack;
    private ViewTreeObserver observer;
    private List<View> rows = List.of();
    private View following;

    private int alpha;
    private float headingOffset;
    private float headingOffsetX;
    private boolean boundsFailed;

    private boolean fieldVisible;
    private float fieldTopRadius;
    private float fieldBotRadius;
    private LinearGradient fieldBaseShader;
    private LinearGradient fieldMistShader;
    private LinearGradient fieldSheenShader;
    private SweepGradient fieldRimShader;
    private SweepGradient fieldHaloShader;
    private SweepGradient fieldCometShader;
    private float cachedFieldL = Float.NaN;
    private float cachedFieldT = Float.NaN;
    private float cachedFieldR = Float.NaN;
    private float cachedFieldB = Float.NaN;
    private boolean cachedFieldDark;

    private boolean dividerVisible;
    private float dividerLeft;
    private float dividerRight;
    private float dividerY;
    private int dividerAlpha;
    private LinearGradient dividerShader;
    private float cachedDivLeft;
    private float cachedDivRight;
    private boolean cachedDivDark;

    private boolean darkAppearance = true;
    private boolean appearanceDirty = true;
    private boolean radiusLookupDone;
    private Method getRadiusTopMethod;
    private Method getRadiusBottomMethod;
    private Field backgroundNormalField;

    private ValueAnimator shimmerAnimator;
    private float shimmerProgress;
    private float shimmerBoost;
    private boolean wasVisiblyShown;
    private long lastHiddenUptimeMs;
    private boolean pendingNewRowShimmer;

    PrioritySectionFrame(View heading, View headingContents,
                         SystemUiPrioritySection.PriorityCapsuleView capsuleView,
                         Method getClipHeight, Method getClipTopAmount,
                         Method getTopOverlap, Method getSuperAlpha, Method getSuperTransitionAlpha,
                         Method getIsExpanded) {
        this.heading = heading;
        this.headingContents = headingContents;
        this.capsuleView = capsuleView;
        this.getClipHeight = getClipHeight;
        this.getClipTopAmount = getClipTopAmount;
        this.getTopOverlap = getTopOverlap;
        this.getSuperAlpha = getSuperAlpha;
        this.getSuperTransitionAlpha = getSuperTransitionAlpha;
        this.getIsExpanded = getIsExpanded;
        density = heading.getResources().getDisplayMetrics().density;

        fieldFillPaint.setStyle(Paint.Style.FILL);
        fieldSheenPaint.setStyle(Paint.Style.FILL);
        gapBridgePaint.setStyle(Paint.Style.FILL);

        fieldRimPaint.setStyle(Paint.Style.STROKE);
        fieldRimPaint.setStrokeJoin(Paint.Join.ROUND);
        fieldRimPaint.setStrokeWidth(0.70f * density);

        fieldCometPaint.setStyle(Paint.Style.STROKE);
        fieldCometPaint.setStrokeJoin(Paint.Join.ROUND);
        fieldCometPaint.setStrokeWidth(1.05f * density);

        auraPaint.setStyle(Paint.Style.STROKE);
        auraPaint.setStrokeJoin(Paint.Join.ROUND);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeJoin(Paint.Join.ROUND);
        rimPaint.setStrokeWidth(1.25f * density);

        sheenPaint.setStyle(Paint.Style.STROKE);
        sheenPaint.setStrokeJoin(Paint.Join.ROUND);
        sheenPaint.setStrokeWidth(0.60f * density);

        cometPaint.setStyle(Paint.Style.STROKE);
        cometPaint.setStrokeJoin(Paint.Join.ROUND);
        cometPaint.setStrokeWidth(1.55f * density);

        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeCap(Paint.Cap.ROUND);
        dividerPaint.setStrokeWidth(0.65f * density);

        darkAppearance = isSystemNightMode();
        if (capsuleView != null) {
            capsuleView.setDarkAppearance(darkAppearance);
        }
    }

    void setRows(List<View> nextRows, View nextFollowing) {
        boolean hasNewRow = false;
        if (nextRows != null && !nextRows.isEmpty()) {
            for (View row : nextRows) {
                if (row != null && !knownRows.contains(row)) {
                    hasNewRow = true;
                    break;
                }
            }
            knownRows.clear();
            for (View row : nextRows) {
                if (row != null) knownRows.add(row);
            }
        } else {
            knownRows.clear();
        }
        this.rows = nextRows == null ? List.of() : nextRows;
        this.following = nextFollowing;
        this.appearanceDirty = true;
        if (hasNewRow && wasVisiblyShown) {
            pendingNewRowShimmer = true;
        }
        if (stack != null) stack.invalidate();
    }

    void attach(ViewGroup parent) {
        stack = parent;
        observer = parent.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        appearanceDirty = true;
        parent.invalidate();
    }

    ViewGroup detach() {
        cancelShimmer();
        ViewGroup previous = stack;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        stack = null;
        releaseSlots();
        alpha = 0;
        fieldVisible = false;
        dividerVisible = false;
        wasVisiblyShown = false;
        pendingNewRowShimmer = false;
        if (previous != null) previous.invalidate();
        return previous;
    }

    @Override
    public boolean onPreDraw() {
        int previousAlpha = alpha;
        float previousHeadingOffset = headingOffset;
        float previousHeadingOffsetX = headingOffsetX;
        int previousSlotCount = activeSlots.size();
        boolean previousFieldVisible = fieldVisible;
        boolean previousDividerVisible = dividerVisible;
        float previousDividerY = dividerY;
        int previousDividerAlpha = dividerAlpha;
        float prevSig = computeGeometrySignature();

        int nextAlpha = 0;
        if (!boundsFailed && stack != null && !rows.isEmpty()) {
            try {
                nextAlpha = updateLayoutState();
            } catch (ReflectiveOperationException exception) {
                boundsFailed = true;
                Log.w(TAG, "Failed to position priority section visuals", exception);
            }
        } else {
            releaseSlots();
            fieldVisible = false;
            dividerVisible = false;
        }

        if (nextAlpha == 0) {
            releaseSlots();
            fieldVisible = false;
            dividerVisible = false;
            if (wasVisiblyShown) {
                wasVisiblyShown = false;
                lastHiddenUptimeMs = SystemClock.uptimeMillis();
                cancelShimmer();
            }
        } else {
            boolean becameVisible = !wasVisiblyShown && nextAlpha >= 38;
            if (becameVisible) {
                wasVisiblyShown = true;
                long hiddenFor = lastHiddenUptimeMs == 0L
                        ? REVEAL_DEBOUNCE_MS + 1L
                        : SystemClock.uptimeMillis() - lastHiddenUptimeMs;
                if (hiddenFor >= REVEAL_DEBOUNCE_MS) {
                    triggerShimmer();
                }
            } else if (pendingNewRowShimmer && wasVisiblyShown) {
                pendingNewRowShimmer = false;
                triggerShimmer();
            }
        }

        float nextSig = computeGeometrySignature();
        boolean changed = previousAlpha != nextAlpha
                || previousHeadingOffset != headingOffset
                || previousHeadingOffsetX != headingOffsetX
                || previousSlotCount != activeSlots.size()
                || previousFieldVisible != fieldVisible
                || previousDividerVisible != dividerVisible
                || previousDividerY != dividerY
                || previousDividerAlpha != dividerAlpha
                || Math.abs(prevSig - nextSig) > 0.25f;

        if (stack != null && changed) {
            if ((previousAlpha == 0) != (nextAlpha == 0)) {
                heading.setImportantForAccessibility(nextAlpha == 0
                        ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
            alpha = nextAlpha;
            stack.invalidate();
        } else {
            alpha = nextAlpha;
        }
        return true;
    }

    private float computeGeometrySignature() {
        float sig = fieldVisible
                ? (fieldRect.left + fieldRect.top * 1.1f + fieldRect.right * 1.3f + fieldRect.bottom * 1.7f)
                : 0f;
        for (int i = 0; i < activeSlots.size(); i++) {
            CardSlot s = activeSlots.get(i);
            sig += (s.bounds.left + s.bounds.top * 1.3f + s.bounds.right * 1.7f
                    + s.bounds.bottom * 2.1f + s.alpha * 100f + s.topRadius) * (i + 1);
        }
        return sig;
    }

    private int updateLayoutState() throws ReflectiveOperationException {
        releaseSlots();
        fieldVisible = false;
        dividerVisible = false;

        if (!(boolean) getIsExpanded.invoke(stack) || !stack.isShown()) return 0;
        if (!getVisibleBounds(heading, headingRect, false)) return 0;

        float headingAlpha = getDrawnAlpha(heading);
        if (headingAlpha <= 0f) return 0;

        float firstCardTop = Float.POSITIVE_INFINITY;
        float firstCardLeft = Float.POSITIVE_INFINITY;
        float firstCardRight = Float.NEGATIVE_INFINITY;
        float firstCardTopRadius = DEFAULT_CARD_RADIUS_DP * density;

        float minCardLeft = Float.POSITIVE_INFINITY;
        float maxCardRight = Float.NEGATIVE_INFINITY;
        float lastCardBottom = Float.NEGATIVE_INFINITY;
        float lastCardLeft = Float.POSITIVE_INFINITY;
        float lastCardRight = Float.NEGATIVE_INFINITY;
        float lastCardBotRadius = DEFAULT_CARD_RADIUS_DP * density;
        float lastCardAlpha = 0f;
        float maxCardAlpha = 0f;
        View firstVisibleRow = null;

        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            float rowAlpha = getDrawnAlpha(row);
            if (rowAlpha < 0.08f) continue;
            if (!getVisibleBounds(row, scratchRect, true)) continue;
            if (scratchRect.width() < row.getWidth() * 0.72f) continue;
            if (scratchRect.height() < 12f * density) continue;

            CardSlot slot = acquireSlot();
            slot.row = row;
            slot.bounds.set(scratchRect);
            slot.alpha = rowAlpha;
            slot.stackIndex = activeSlots.size();
            slot.stackedBehind = false;
            resolveCardRadii(row, slot);
            slot.rebuildGeometry(density);

            if (firstVisibleRow == null) firstVisibleRow = row;
            if (scratchRect.top < firstCardTop) {
                firstCardTop = scratchRect.top;
                firstCardLeft = scratchRect.left;
                firstCardRight = scratchRect.right;
                firstCardTopRadius = slot.topRadius;
            }
            if (scratchRect.bottom >= lastCardBottom) {
                lastCardBottom = scratchRect.bottom;
                lastCardLeft = scratchRect.left;
                lastCardRight = scratchRect.right;
                lastCardBotRadius = slot.botRadius;
                lastCardAlpha = rowAlpha;
            }
            minCardLeft = Math.min(minCardLeft, scratchRect.left);
            maxCardRight = Math.max(maxCardRight, scratchRect.right);
            maxCardAlpha = Math.max(maxCardAlpha, rowAlpha);
            activeSlots.add(slot);
        }

        if (activeSlots.isEmpty() || maxCardAlpha <= 0f) return 0;

        // Detect stacked overlap among priority cards (lockscreen card deck)
        for (int i = 1; i < activeSlots.size(); i++) {
            CardSlot prev = activeSlots.get(i - 1);
            CardSlot cur = activeSlots.get(i);
            if (cur.bounds.top < prev.bounds.bottom - 2f * density) {
                cur.stackedBehind = true;
            }
        }

        if (appearanceDirty && firstVisibleRow != null) {
            updateAppearanceFromRow(firstVisibleRow);
        }

        // Align micro-capsule and obtain its final screen-space top inside stack
        float finalCapsuleTop = alignHeading(
                firstCardLeft, firstCardRight, firstCardTop, headingAlpha, maxCardAlpha);

        // Check following ordinary card position (expanded gap vs lockscreen tucked-behind deck)
        boolean hasFollowing = following != null && getVisibleBounds(following, followingRect, true);
        float followingAlpha = hasFollowing ? getDrawnAlpha(following) : 0f;
        boolean followingVisible = hasFollowing && followingAlpha >= 0.08f;
        boolean followingStackedBehind = followingVisible && followingRect.top < lastCardBottom;

        // Compute concentric Ambient Nebula Glass Field enveloping header capsule + all priority cards
        float padH = FIELD_PAD_H_DP * density;
        float edgeSafety = 2f * density;
        float fLeft = Math.max(edgeSafety, minCardLeft - padH);
        float fRight = Math.min(stack.getWidth() - edgeSafety, maxCardRight + padH);
        float fTop = finalCapsuleTop - FIELD_PAD_TOP_DP * density;
        float actualPadH = Math.max(0f, Math.min(minCardLeft - fLeft, fRight - maxCardRight));

        float padBot = followingStackedBehind
                ? FIELD_PAD_BOTTOM_STACKED_DP * density
                : FIELD_PAD_BOTTOM_EXPANDED_DP * density;
        float fBottom = lastCardBottom + padBot;
        if (followingVisible && !followingStackedBehind) {
            float maxBottom = followingRect.top - 3f * density;
            if (maxBottom > lastCardBottom + 2f * density) {
                fBottom = Math.min(fBottom, maxBottom);
            } else {
                fBottom = lastCardBottom + 2f * density;
            }
        }

        if (fRight - fLeft > 24f * density && fBottom > firstCardTop) {
            fieldRect.set(fLeft, fTop, fRight, fBottom);
            float actualPadBot = Math.max(0f, fBottom - lastCardBottom);
            fieldTopRadius = clamp(firstCardTopRadius + actualPadH, 24f * density, 35f * density);
            fieldBotRadius = clamp(lastCardBotRadius + Math.min(actualPadH, actualPadBot + 3f * density),
                    24f * density, 35f * density);
            buildSquirclePath(fieldRect, fieldTopRadius, fieldBotRadius, fieldPath);

            float rimInset = 0.35f * density;
            fieldRimRect.set(fieldRect.left + rimInset, fieldRect.top + rimInset,
                    fieldRect.right - rimInset, fieldRect.bottom - rimInset);
            buildSquirclePath(fieldRimRect,
                    Math.max(0f, fieldTopRadius - rimInset),
                    Math.max(0f, fieldBotRadius - rimInset),
                    fieldRimPath);
            fieldVisible = true;
        }

        // Only draw the subtle fading Nebula hairline below the glass field when expanded with clean slack
        if (followingVisible && !followingStackedBehind && fieldVisible) {
            float remainingGap = followingRect.top - fieldRect.bottom;
            if (remainingGap >= 6f * density) {
                float inset = 28f * density;
                float left = Math.max(lastCardLeft, followingRect.left) + inset;
                float right = Math.min(lastCardRight, followingRect.right) - inset;
                if (right - left >= 40f * density) {
                    dividerVisible = true;
                    dividerLeft = left;
                    dividerRight = right;
                    dividerY = (fieldRect.bottom + followingRect.top) * 0.5f;
                    dividerAlpha = Math.round(255f * Math.min(lastCardAlpha, followingAlpha));
                }
            }
        }

        return Math.round(255f * Math.min(headingAlpha, maxCardAlpha));
    }

    /** Aligns micro-capsule to front card leading edge & 6.5dp above card top; returns final pill top Y. */
    private float alignHeading(float cardLeft, float cardRight, float firstCardTop,
                               float headingAlpha, float cardAlpha) {
        boolean rtl = heading.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float opticalInset = HEADING_OPTICAL_INSET_DP * density;

        float capsuleLeftRel = 0f;
        float capsuleTopRel = 0f;
        float capsuleW = headingRect.width();
        float capsuleH = headingRect.height();
        float pillShadowPad = 0f;
        if (capsuleView != null && capsuleView.getWidth() > 0 && capsuleView.getHeight() > 0) {
            float[] rel = relativePositionInAncestor(capsuleView, heading);
            capsuleLeftRel = rel[0];
            capsuleTopRel = rel[1];
            capsuleW = capsuleView.getWidth();
            capsuleH = capsuleView.getHeight();
            pillShadowPad = 2f * density;
        }

        float actualCapsuleLeft = headingRect.left + capsuleLeftRel + pillShadowPad;
        float actualCapsuleRight = headingRect.left + capsuleLeftRel + capsuleW - pillShadowPad;
        float actualCapsuleTop = headingRect.top + capsuleTopRel + pillShadowPad;
        float actualCapsuleBottom = headingRect.top + capsuleTopRel + capsuleH - pillShadowPad;

        if (rtl) {
            headingOffsetX = (cardRight - opticalInset) - actualCapsuleRight;
        } else {
            headingOffsetX = (cardLeft + opticalInset) - actualCapsuleLeft;
        }

        float desiredCapsuleBottom = firstCardTop - HEADING_GAP_DP * density;
        float deltaY = desiredCapsuleBottom - actualCapsuleBottom;
        headingOffset = Math.min(14f * density, deltaY);

        headingContents.setAlpha(Math.min(1f, cardAlpha / headingAlpha));
        return actualCapsuleTop + headingOffset;
    }

    private static float[] relativePositionInAncestor(View child, View ancestor) {
        float x = 0f;
        float y = 0f;
        View current = child;
        while (current != null && current != ancestor) {
            x += current.getLeft() + current.getTranslationX();
            y += current.getTop() + current.getTranslationY();
            if (!(current.getParent() instanceof View parent)) break;
            current = parent;
        }
        return new float[]{x, y};
    }

    private void updateAppearanceFromRow(View row) {
        appearanceDirty = false;
        boolean nextDark = detectDarkCardSurface(row);
        if (nextDark != darkAppearance) {
            darkAppearance = nextDark;
            invalidateFieldShaders();
            dividerShader = null;
            for (CardSlot s : activeSlots) s.invalidateShaders();
            for (CardSlot s : slotPool) s.invalidateShaders();
        }
        if (capsuleView != null) {
            capsuleView.setDarkAppearance(darkAppearance);
        }
    }

    private boolean detectDarkCardSurface(View row) {
        TextView sample = findSampleTextView(row, 0);
        if (sample != null) {
            int ink = sample.getCurrentTextColor();
            if (Color.alpha(ink) > 40) {
                float lum = (0.299f * Color.red(ink) + 0.587f * Color.green(ink)
                        + 0.114f * Color.blue(ink)) / 255f;
                return lum > 0.52f;
            }
        }
        return isSystemNightMode();
    }

    private static TextView findSampleTextView(View view, int depth) {
        if (view == null || depth > 7 || view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView tv) {
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0) return tv;
        }
        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                TextView found = findSampleTextView(group.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isSystemNightMode() {
        int uiMode = heading.getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void resolveCardRadii(View row, CardSlot slot) {
        if (!radiusLookupDone) {
            radiusLookupDone = true;
            Class<?> cls = row.getClass();
            getRadiusTopMethod = ModuleMain.findMethod(cls, "getCurrentBackgroundRadiusTop");
            getRadiusBottomMethod = ModuleMain.findMethod(cls, "getCurrentBackgroundRadiusBottom");
            backgroundNormalField = ModuleMain.findField(cls, "mBackgroundNormal");
        }
        float fallback = DEFAULT_CARD_RADIUS_DP * density;
        float topR = fallback;
        float botR = fallback;
        try {
            if (getRadiusTopMethod != null) {
                float v = (float) getRadiusTopMethod.invoke(row);
                if (v >= 10f * density && v <= 42f * density) topR = v;
            }
            if (getRadiusBottomMethod != null) {
                float v = (float) getRadiusBottomMethod.invoke(row);
                if (v >= 10f * density && v <= 42f * density) botR = v;
            }
        } catch (Throwable ignored) {
            topR = fallback;
            botR = fallback;
        }
        slot.topRadius = topR;
        slot.botRadius = botR;
    }

    private float getDrawnAlpha(View view) throws ReflectiveOperationException {
        return (float) getSuperAlpha.invoke(view) * (float) getSuperTransitionAlpha.invoke(view);
    }

    private boolean getVisibleBounds(View child, RectF result, boolean useCardInset)
            throws ReflectiveOperationException {
        if (child == null || child.getParent() != stack || child.getVisibility() != View.VISIBLE
                || child.getAlpha() <= 0f || child.getWidth() <= 0) return false;
        int height = (int) getClipHeight.invoke(child);
        if (height <= 0) return false;
        int top = Math.max(0, Math.max((int) getClipTopAmount.invoke(child),
                (int) getTopOverlap.invoke(child)));
        float left = 0f;
        float right = child.getWidth();
        if (useCardInset && backgroundNormalField != null) {
            try {
                Object bgObj = backgroundNormalField.get(child);
                if (bgObj instanceof View bg && bg.getParent() == child
                        && bg.getWidth() > child.getWidth() * 0.85f
                        && bg.getWidth() < child.getWidth()) {
                    left = bg.getLeft();
                    right = bg.getRight();
                }
            } catch (Throwable ignored) {}
        }
        result.set(left, top, right, top + height);
        child.getMatrix().mapRect(result);
        result.offset(child.getLeft(), child.getTop());
        return !result.isEmpty();
    }

    // ---------------------------------------------------------------- shimmer

    private void triggerShimmer() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shimmerProgress = 0f;
            shimmerBoost = 0f;
            if (capsuleView != null) capsuleView.setShimmer(0f, 0f);
            return;
        }
        cancelShimmer();
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
        shimmerAnimator.setDuration(SHIMMER_DURATION_MS);
        shimmerAnimator.setInterpolator(SHIMMER_EASE);
        shimmerAnimator.addUpdateListener(anim -> {
            shimmerProgress = (float) anim.getAnimatedValue();
            shimmerBoost = computeShimmerEnvelope(shimmerProgress);
            if (capsuleView != null) {
                capsuleView.setShimmer(shimmerProgress, shimmerBoost);
            }
            if (stack != null) {
                stack.invalidate();
            }
        });
        shimmerAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                shimmerProgress = 0f;
                shimmerBoost = 0f;
                shimmerAnimator = null;
                if (capsuleView != null) capsuleView.setShimmer(0f, 0f);
                if (stack != null) stack.invalidate();
            }
        });
        shimmerAnimator.start();
    }

    private void cancelShimmer() {
        if (shimmerAnimator != null) {
            shimmerAnimator.cancel();
            shimmerAnimator = null;
        }
        shimmerProgress = 0f;
        shimmerBoost = 0f;
        if (capsuleView != null) {
            capsuleView.setShimmer(0f, 0f);
        }
    }

    private static float computeShimmerEnvelope(float t) {
        if (t <= 0f || t >= 1f) return 0f;
        if (t < 0.22f) {
            float u = t / 0.22f;
            return u * u * (3f - 2f * u);
        }
        float u = (1f - t) / 0.78f;
        return u * u * (3f - 2f * u);
    }

    // ---------------------------------------------------------------- public hooks

    boolean isHeading(Object child) {
        return child == heading;
    }

    boolean isVisible() {
        return alpha > 0 && !activeSlots.isEmpty();
    }

    float headingOffset() {
        return headingOffset;
    }

    float headingOffsetX() {
        return headingOffsetX;
    }

    /**
     * Drawn BEFORE NotificationStackScrollLayout children:
     * 1) Outer sheer Nebula Ambient Glass Field uniting the header capsule and all priority cards;
     * 2) Subtle luminous Nebula mist bridges across inter-card gaps when multiple priority cards exist;
     * 3) Focused outward-clipped per-card Nebula soft rim glow.
     */
    void drawBackground(Canvas canvas) {
        if (alpha <= 0 || activeSlots.isEmpty()) return;

        float groupFade = alpha / 255f;
        float angle = REST_ANGLE_DEG + shimmerProgress * 360f;

        // 1. Ambient Nebula Glass Field (sheer mist + top glass sheen + 0.7dp specular hairline)
        if (fieldVisible) {
            ensureFieldShaders();
            float fcx = fieldRect.centerX();
            float fcy = fieldRect.centerY();

            // 1a. Whisper-soft outer ambient field halo (outward-clipped)
            int haloSaved = canvas.save();
            canvas.clipOutPath(fieldPath);
            shaderMatrix.reset();
            shaderMatrix.setRotate(angle, fcx, fcy);
            fieldHaloShader.setLocalMatrix(shaderMatrix);
            auraPaint.setShader(fieldHaloShader);
            float[] fRest = darkAppearance ? FIELD_HALO_REST_DARK : FIELD_HALO_REST_LIGHT;
            float[] fBoost = darkAppearance ? FIELD_HALO_BOOST_DARK : FIELD_HALO_BOOST_LIGHT;
            for (int pass = 0; pass < FIELD_HALO_WIDTHS_DP.length; pass++) {
                float a = (fRest[pass] + fBoost[pass] * shimmerBoost) * groupFade;
                int op = Math.min(255, Math.round(a * 255f));
                if (op <= 0) continue;
                auraPaint.setStrokeWidth(FIELD_HALO_WIDTHS_DP[pass] * density);
                auraPaint.setAlpha(op);
                canvas.drawPath(fieldPath, auraPaint);
            }
            canvas.restoreToCount(haloSaved);

            // 1b. Translucent smoked/frosted base veil + diagonal Nebula mist wash
            fieldFillPaint.setShader(fieldBaseShader);
            fieldFillPaint.setAlpha(Math.round(groupFade * 255f));
            canvas.drawPath(fieldPath, fieldFillPaint);

            fieldFillPaint.setShader(fieldMistShader);
            float mistScale = 1f + 0.25f * shimmerBoost;
            fieldFillPaint.setAlpha(Math.min(255, Math.round(groupFade * mistScale * 255f)));
            canvas.drawPath(fieldPath, fieldFillPaint);

            // 1c. Top inner liquid-glass specular sheen
            fieldSheenPaint.setShader(fieldSheenShader);
            fieldSheenPaint.setAlpha(Math.round(groupFade * 255f));
            canvas.drawPath(fieldPath, fieldSheenPaint);

            // 1d. Feather-thin 0.70dp specular glass hairline outline (binds whole unit cleanly)
            shaderMatrix.reset();
            shaderMatrix.setRotate(angle, fcx, fcy);
            fieldRimShader.setLocalMatrix(shaderMatrix);
            fieldRimPaint.setShader(fieldRimShader);
            float fieldRimOpacity = (darkAppearance ? 0.78f : 0.76f) + 0.22f * shimmerBoost;
            fieldRimPaint.setAlpha(Math.min(255, Math.round(fieldRimOpacity * groupFade * 255f)));
            canvas.drawPath(fieldRimPath, fieldRimPaint);

            // 1e. Synchronized shimmer wave along the ambient field hairline during the 1.4s sweep
            if (shimmerBoost > 0.01f) {
                shaderMatrix.reset();
                shaderMatrix.setRotate(-115f + shimmerProgress * 360f, fcx, fcy);
                fieldCometShader.setLocalMatrix(shaderMatrix);
                fieldCometPaint.setShader(fieldCometShader);
                fieldCometPaint.setAlpha(Math.min(255, Math.round(shimmerBoost * groupFade * 165f)));
                canvas.drawPath(fieldRimPath, fieldCometPaint);
            }

            // 1f. Subtle inter-card Nebula mist bridge connecting sibling priority cards in gaps
            if (activeSlots.size() >= 2) {
                drawInterCardMistBridges(canvas, groupFade);
            }
        }

        // 2. Per-card tight outward-clipped Nebula soft rim glow
        float[] restAlphas = darkAppearance ? CARD_AURA_REST_DARK : CARD_AURA_REST_LIGHT;
        float[] boostAlphas = darkAppearance ? CARD_AURA_BOOST_DARK : CARD_AURA_BOOST_LIGHT;

        for (int i = activeSlots.size() - 1; i >= 0; i--) {
            CardSlot slot = activeSlots.get(i);
            float cardFade = slot.alpha * (slot.stackedBehind ? 0.50f : 1.0f);
            if (cardFade <= 0.02f) continue;

            slot.ensureShaders(darkAppearance, density);

            int saved = canvas.save();
            canvas.clipOutPath(slot.path);
            for (int f = 0; f < i; f++) {
                canvas.clipOutPath(activeSlots.get(f).path);
            }

            shaderMatrix.reset();
            shaderMatrix.setRotate(angle + i * 24f, slot.bounds.centerX(), slot.bounds.centerY());
            slot.auraShader.setLocalMatrix(shaderMatrix);
            auraPaint.setShader(slot.auraShader);

            for (int pass = 0; pass < CARD_AURA_WIDTHS_DP.length; pass++) {
                float a = (restAlphas[pass] + boostAlphas[pass] * shimmerBoost) * cardFade;
                int opacity = Math.min(255, Math.round(a * 255f));
                if (opacity <= 0) continue;
                auraPaint.setStrokeWidth(CARD_AURA_WIDTHS_DP[pass] * density);
                auraPaint.setAlpha(opacity);
                canvas.drawPath(slot.path, auraPaint);
            }
            canvas.restoreToCount(saved);
        }
    }

    /** Soft luminous Nebula glow flowing across the gap between consecutive priority cards. */
    private void drawInterCardMistBridges(Canvas canvas, float groupFade) {
        int bridgeColor = darkAppearance ? 0xFF7AD4FF : 0xFF3E8BF5;
        int baseAlpha = Math.round((darkAppearance ? 22f : 16f) * (1f + 0.55f * shimmerBoost) * groupFade);
        if (baseAlpha <= 1) return;

        for (int i = 0; i < activeSlots.size() - 1; i++) {
            CardSlot upper = activeSlots.get(i);
            CardSlot lower = activeSlots.get(i + 1);
            if (lower.stackedBehind) continue;
            float gap = lower.bounds.top - upper.bounds.bottom;
            if (gap < 1.5f * density || gap > 28f * density) continue;

            float pairFade = Math.min(upper.alpha, lower.alpha);
            if (pairFade < 0.08f) continue;

            float inset = 20f * density;
            float left = Math.max(upper.bounds.left, lower.bounds.left) + inset;
            float right = Math.min(upper.bounds.right, lower.bounds.right) - inset;
            if (right - left <= 24f * density) continue;

            float top = upper.bounds.bottom - 1.5f * density;
            float bot = lower.bounds.top + 1.5f * density;
            bridgeRect.set(left, top, right, bot);

            gapBridgePaint.setShader(new LinearGradient(
                    left, 0f, right, 0f,
                    new int[]{0x00000000, bridgeColor, bridgeColor, 0x00000000},
                    new float[]{0f, 0.25f, 0.75f, 1f},
                    Shader.TileMode.CLAMP));
            gapBridgePaint.setAlpha(Math.min(255, Math.round(baseAlpha * pairFade)));
            canvas.drawRect(bridgeRect, gapBridgePaint);
        }
        gapBridgePaint.setShader(null);
    }

    /**
     * Drawn AFTER NotificationStackScrollLayout children: renders each priority card's
     * continuous-curvature Nebula Aurora prism rim, top glass catchlight, traveling shimmer comet,
     * and the subtle fading hairline below the glass field when expanded.
     */
    void drawOutline(Canvas canvas) {
        if (alpha <= 0 || activeSlots.isEmpty()) return;

        float angle = REST_ANGLE_DEG + shimmerProgress * 360f;

        for (int i = activeSlots.size() - 1; i >= 0; i--) {
            CardSlot slot = activeSlots.get(i);
            float cardFade = slot.alpha * (slot.stackedBehind ? 0.58f : 1.0f);
            if (cardFade <= 0.02f) continue;

            slot.ensureShaders(darkAppearance, density);

            int saved = canvas.save();
            for (int f = 0; f < i; f++) {
                canvas.clipOutPath(activeSlots.get(f).path);
            }

            float cx = slot.bounds.centerX();
            float cy = slot.bounds.centerY();

            // 1. Primary 1.25dp Nebula Aurora prism rim (harmonic angle shift across linked cards)
            shaderMatrix.reset();
            shaderMatrix.setRotate(angle + i * 24f, cx, cy);
            slot.rimShader.setLocalMatrix(shaderMatrix);
            rimPaint.setShader(slot.rimShader);
            float rimOpacity = (darkAppearance ? 0.88f : 0.90f) + 0.10f * shimmerBoost;
            rimPaint.setAlpha(Math.min(255, Math.round(rimOpacity * cardFade * 255f)));
            canvas.drawPath(slot.rimPath, rimPaint);

            // 2. Whisper-thin 0.60dp Liquid Glass top specular catchlight
            sheenPaint.setShader(slot.sheenShader);
            float sheenOpacity = (darkAppearance ? 0.80f : 0.65f) * cardFade;
            sheenPaint.setAlpha(Math.min(255, Math.round(sheenOpacity * 255f)));
            canvas.drawPath(slot.rimPath, sheenPaint);

            // 3. Traveling luminous shimmer comet arc (active only during 1.4s sweep)
            if (shimmerBoost > 0.01f) {
                shaderMatrix.reset();
                shaderMatrix.setRotate(-115f + shimmerProgress * 360f + i * 20f, cx, cy);
                slot.cometShader.setLocalMatrix(shaderMatrix);
                cometPaint.setShader(slot.cometShader);
                cometPaint.setAlpha(Math.min(255, Math.round(shimmerBoost * cardFade * 230f)));
                canvas.drawPath(slot.rimPath, cometPaint);
            }

            canvas.restoreToCount(saved);
        }

        // 4. Optional ultra-subtle fading Nebula hairline below the ambient field when expanded
        if (dividerVisible && dividerAlpha > 2) {
            ensureDividerShader();
            dividerPaint.setShader(dividerShader);
            dividerPaint.setAlpha(dividerAlpha);
            canvas.drawLine(dividerLeft, dividerY, dividerRight, dividerY, dividerPaint);
        }
    }

    private void invalidateFieldShaders() {
        fieldBaseShader = null;
        fieldMistShader = null;
        fieldSheenShader = null;
        fieldRimShader = null;
        fieldHaloShader = null;
        fieldCometShader = null;
    }

    private void ensureFieldShaders() {
        if (fieldMistShader != null && cachedFieldDark == darkAppearance
                && Math.abs(cachedFieldL - fieldRect.left) < 0.75f
                && Math.abs(cachedFieldT - fieldRect.top) < 0.75f
                && Math.abs(cachedFieldR - fieldRect.right) < 0.75f
                && Math.abs(cachedFieldB - fieldRect.bottom) < 0.75f) {
            return;
        }
        cachedFieldL = fieldRect.left;
        cachedFieldT = fieldRect.top;
        cachedFieldR = fieldRect.right;
        cachedFieldB = fieldRect.bottom;
        cachedFieldDark = darkAppearance;

        float cx = fieldRect.centerX();
        float cy = fieldRect.centerY();

        fieldBaseShader = new LinearGradient(
                0f, fieldRect.top, 0f, fieldRect.bottom,
                darkAppearance ? 0x26101828 : 0x52F5F9FF,
                darkAppearance ? 0x1A0B101D : 0x38ECF3FE,
                Shader.TileMode.CLAMP);

        fieldMistShader = new LinearGradient(
                fieldRect.left, fieldRect.top, fieldRect.right, fieldRect.bottom,
                darkAppearance ? DARK_FIELD_FILL_COLORS : LIGHT_FIELD_FILL_COLORS,
                FIELD_FILL_POSITIONS,
                Shader.TileMode.CLAMP);

        fieldSheenShader = new LinearGradient(
                0f, fieldRect.top, 0f, fieldRect.top + Math.min(fieldRect.height() * 0.34f, 64f * density),
                darkAppearance ? 0x1AFFFFFF : 0x45FFFFFF,
                0x00FFFFFF,
                Shader.TileMode.CLAMP);

        fieldRimShader = new SweepGradient(cx, cy,
                darkAppearance ? DARK_FIELD_RIM_COLORS : LIGHT_FIELD_RIM_COLORS, RIM_POSITIONS);
        fieldHaloShader = new SweepGradient(cx, cy,
                darkAppearance ? DARK_AURA_COLORS : LIGHT_AURA_COLORS, AURA_POSITIONS);
        fieldCometShader = new SweepGradient(cx, cy,
                darkAppearance ? DARK_COMET_COLORS : LIGHT_COMET_COLORS, COMET_POSITIONS);
    }

    private void ensureDividerShader() {
        if (dividerShader != null && cachedDivDark == darkAppearance
                && Math.abs(cachedDivLeft - dividerLeft) < 1f
                && Math.abs(cachedDivRight - dividerRight) < 1f) {
            return;
        }
        cachedDivLeft = dividerLeft;
        cachedDivRight = dividerRight;
        cachedDivDark = darkAppearance;
        dividerShader = new LinearGradient(
                dividerLeft, 0f, dividerRight, 0f,
                darkAppearance ? DARK_DIVIDER_COLORS : LIGHT_DIVIDER_COLORS,
                DIVIDER_POSITIONS,
                Shader.TileMode.CLAMP);
    }

    // ---------------------------------------------------------------- pool & squircle geometry

    private CardSlot acquireSlot() {
        if (!slotPool.isEmpty()) {
            return slotPool.remove(slotPool.size() - 1);
        }
        return new CardSlot();
    }

    private void releaseSlots() {
        for (int i = activeSlots.size() - 1; i >= 0; i--) {
            CardSlot slot = activeSlots.remove(i);
            slot.row = null;
            if (slotPool.size() < 8) {
                slotPool.add(slot);
            }
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Continuous-curvature squircle matching HyperOS 4 card geometry and HyperSettingsUi. */
    static void buildSquirclePath(RectF rect, float topRadius, float bottomRadius, Path out) {
        out.reset();
        float width = rect.width();
        float height = rect.height();
        if (width <= 0f || height <= 0f) return;

        float halfMin = Math.min(width, height) * 0.5f;
        float dt = Math.max(0f, Math.min(topRadius * 1.20f, halfMin));
        float db = Math.max(0f, Math.min(bottomRadius * 1.20f, halfMin));
        float k = 0.30f;
        float ct = dt * k;
        float cb = db * k;
        float l = rect.left;
        float t = rect.top;
        float r = rect.right;
        float b = rect.bottom;

        out.moveTo(l + dt, t);
        out.lineTo(r - dt, t);
        if (dt > 0f) out.cubicTo(r - ct, t, r, t + ct, r, t + dt);
        out.lineTo(r, b - db);
        if (db > 0f) out.cubicTo(r, b - cb, r - cb, b, r - db, b);
        out.lineTo(l + db, b);
        if (db > 0f) out.cubicTo(l + cb, b, l, b - cb, l, b - db);
        out.lineTo(l, t + dt);
        if (dt > 0f) out.cubicTo(l, t + ct, l + ct, t, l + dt, t);
        out.close();
    }

    private static final class CardSlot {
        View row;
        final RectF bounds = new RectF();
        final RectF rimBounds = new RectF();
        final Path path = new Path();
        final Path rimPath = new Path();
        float alpha;
        float topRadius;
        float botRadius;
        int stackIndex;
        boolean stackedBehind;

        SweepGradient rimShader;
        SweepGradient auraShader;
        SweepGradient cometShader;
        LinearGradient sheenShader;
        float shaderCx = Float.NaN;
        float shaderCy = Float.NaN;
        float shaderTop = Float.NaN;
        float shaderBottom = Float.NaN;
        boolean shaderDark;

        void rebuildGeometry(float density) {
            buildSquirclePath(bounds, topRadius, botRadius, path);
            float inset = 0.55f * density;
            rimBounds.set(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset);
            buildSquirclePath(rimBounds,
                    Math.max(0f, topRadius - inset),
                    Math.max(0f, botRadius - inset),
                    rimPath);
        }

        void invalidateShaders() {
            rimShader = null;
            auraShader = null;
            cometShader = null;
            sheenShader = null;
        }

        void ensureShaders(boolean dark, float density) {
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            if (rimShader != null && shaderDark == dark
                    && Math.abs(cx - shaderCx) < 0.75f
                    && Math.abs(cy - shaderCy) < 0.75f
                    && Math.abs(bounds.top - shaderTop) < 0.75f
                    && Math.abs(bounds.bottom - shaderBottom) < 0.75f) {
                return;
            }
            shaderCx = cx;
            shaderCy = cy;
            shaderTop = bounds.top;
            shaderBottom = bounds.bottom;
            shaderDark = dark;

            rimShader = new SweepGradient(cx, cy,
                    dark ? DARK_RIM_COLORS : LIGHT_RIM_COLORS, RIM_POSITIONS);
            auraShader = new SweepGradient(cx, cy,
                    dark ? DARK_AURA_COLORS : LIGHT_AURA_COLORS, AURA_POSITIONS);
            cometShader = new SweepGradient(cx, cy,
                    dark ? DARK_COMET_COLORS : LIGHT_COMET_COLORS, COMET_POSITIONS);
            sheenShader = new LinearGradient(
                    0f, bounds.top, 0f, bounds.bottom,
                    dark ? new int[]{0x7AFFFFFF, 0x00FFFFFF, 0x24A17DFF}
                         : new int[]{0x99FFFFFF, 0x00FFFFFF, 0x1E7458F2},
                    new float[]{0f, 0.36f, 1f},
                    Shader.TileMode.CLAMP);
        }
    }
}
