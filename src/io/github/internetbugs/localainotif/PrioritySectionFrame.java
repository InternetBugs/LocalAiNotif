package io.github.internetbugs.localainotif;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
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
import java.util.Map;
import java.util.Set;

/**
 * Renders the Apple Intelligence-inspired unified merged card + shared bottom Footer +
 * crisp perimeter-only luminous glow for Priority Notifications in
 * {@code NotificationStackScrollLayout}:
 * <ul>
 *   <li><b>Zero-Seam Unified Card Surface</b>: Extends the leading docked priority row's native
 *       HyperOS {@code NotificationBackgroundView} (preserving real-time Liquid Glass / Blur /
 *       Normal adaptation) seamlessly across all docked priority rows and the shared bottom
 *       Footer, while suppressing redundant intermediate row backgrounds.</li>
 *   <li><b>Clean Interior + Internal Hairlines</b>: Draws refined 0.65dp horizontal hairlines
 *       between docked priority rows (and expanded group children) aligned to notification content
 *       insets, keeping the card interior 100% crisp and unobstructed.</li>
 *   <li><b>Crisp Perimeter-Only Dual-Style Glow</b>: Renders one silky Gaussian-feathered outer
 *       halo + thin luminous glass rim around the merged silhouette via AGSL (supporting both
 *       {@code 超级小爱流光} and {@code 星云极光}) with clean Canvas fallback and 1.6s one-shot
 *       reveal/new-VIP sweep (zero idle redraws).</li>
 *   <li><b>Native Interactions</b>: Horizontal swipe detaches individual rows cleanly with full
 *       4-corner rounding (or slides single VIP + Footer together); lockscreen stacked decks
 *       attach the Footer to the front-most priority card while back cards peek natively; pinned
 *       Heads-up banners stay completely untouched.</li>
 * </ul>
 */
final class PrioritySectionFrame implements ViewTreeObserver.OnPreDrawListener {
    private static final String TAG = "LocalAiNotif";
    private static final long SHIMMER_DURATION_MS = 1600L;
    private static final long REVEAL_DEBOUNCE_MS = 450L;
    private static final float REST_ANGLE_DEG = -28f;
    private static final float DEFAULT_CARD_RADIUS_DP = 25f;
    private static final float CONTENT_INSET_DP = 16f;
    private static final float SWIPE_DETACH_THRESHOLD_DP = 0.75f;
    private static final float EXTRA_GAP_BELOW_FOOTER_DP = 4f;

    private static final PathInterpolator SHIMMER_EASE =
            new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);

    // Canvas-fallback dual-style perimeter palettes (used only when AGSL RuntimeShader unavailable)
    private static final int[] XIAOAI_DARK_RIM = {
            0xF0FF9E57, 0xECFF6BB8, 0xECC280FF, 0xEC5CA8FF, 0xF0FF9E57
    };
    private static final int[] XIAOAI_LIGHT_RIM = {
            0xE2F26D1F, 0xDCE5327E, 0xE08F4AF2, 0xDC247AF5, 0xE2F26D1F
    };
    private static final int[] XIAOAI_DARK_AURA = {
            0xFFFF9E57, 0xFFFF6BB8, 0xFFC280FF, 0xFF5CA8FF, 0xFFFF9E57
    };
    private static final int[] XIAOAI_LIGHT_AURA = {
            0xFFF26D1F, 0xFFE5327E, 0xFF8F4AF2, 0xFF247AF5, 0xFFF26D1F
    };
    private static final int[] XIAOAI_COMET = {
            0x00FF9E57, 0x00FF9E57, 0xC8FF8E52, 0xFFFFF6F2, 0xD4FF58A2, 0x00C280FF, 0x00FF9E57
    };

    private static final int[] NEBULA_DARK_RIM = {
            0xE862E3FF, 0xDC4794FF, 0xE4A884FF, 0xECE0F2FF, 0xE862E3FF
    };
    private static final int[] NEBULA_LIGHT_RIM = {
            0xDE1CA3EC, 0xD82B75F0, 0xDC7458F2, 0xC69AC6FF, 0xDE1CA3EC
    };
    private static final int[] NEBULA_DARK_AURA = {
            0xFF62E3FF, 0xFF4794FF, 0xFFA884FF, 0xFFD2ECFF, 0xFF62E3FF
    };
    private static final int[] NEBULA_LIGHT_AURA = {
            0xFF1CA3EC, 0xFF2B75F0, 0xFF7458F2, 0xFF84BEFF, 0xFF1CA3EC
    };
    private static final int[] NEBULA_COMET = {
            0x0062E3FF, 0x0062E3FF, 0xBC62E3FF, 0xFFFFFFFF, 0xCCA884FF, 0x00A884FF, 0x0062E3FF
    };

    private static final float[] SWEEP_POSITIONS = {0.00f, 0.25f, 0.50f, 0.75f, 1.00f};
    private static final float[] COMET_POSITIONS = {0.00f, 0.70f, 0.80f, 0.88f, 0.94f, 0.99f, 1.00f};
    private static final float[] FALLBACK_AURA_WIDTHS_DP = {15.0f, 10.0f, 5.6f, 2.6f};
    private static final float[] FALLBACK_AURA_DARK = {0.045f, 0.095f, 0.165f, 0.230f};
    private static final float[] FALLBACK_AURA_LIGHT = {0.025f, 0.052f, 0.092f, 0.138f};

    // Main-thread shared registry queried by SystemUiPrioritySection hooks
    private static final Map<View, Integer> sMergedBgHeightOverrides = new IdentityHashMap<>();
    private static final Map<View, Float> sMergedBgRadii = new IdentityHashMap<>();
    private static final Map<View, Integer> sMergedRowHeightOverrides = new IdentityHashMap<>();
    private static final Map<View, Float> sDockedSuperTransZOverrides = new IdentityHashMap<>();
    private static final Set<View> sSuppressedBgs = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<View> sForcedVisibleHostBgs = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<View> sNonHostDockedRows = Collections.newSetFromMap(new IdentityHashMap<>());

    // Shared static reflection handles (initialized lazily on main thread by ensureRowMetaLookup)
    private static volatile Class<?> sNbvClass;
    private static volatile Field sBackgroundNormalField;
    private static volatile Method sIsSummaryWithChildrenMethod;
    private static volatile Method sGetAttachedChildrenMethod;
    private static volatile Method sSetSuperTranslationYMethod;
    private static volatile Method sSetSuperTranslationXMethod;
    private static volatile Method sSetSuperScaleXMethod;
    private static volatile Method sSetSuperScaleYMethod;
    private static volatile Method sSetSuperAlphaMethod;
    private static volatile Method sGetSuperTranslationYMethod;
    private static volatile Method sGetSuperScaleXMethod;
    private static volatile Method sGetSuperScaleYMethod;
    private static volatile Method sGetViewSuperTranslationZMethod;
    private static volatile Method sSetViewSuperTranslationZMethod;
    private static volatile Method sGetViewSuperTranslationXMethod;
    private static volatile Method sGetViewSuperScaleXMethod;
    private static volatile Method sGetViewSuperScaleYMethod;
    private static volatile Method sGetExtTranslationZMethod;

    private final View footer;
    private final View footerContents;
    private final SystemUiPrioritySection.PriorityFooterView footerView;
    private final Method getClipHeight;
    private final Method getClipTopAmount;
    private final Method getTopOverlap;
    private final Method getSuperAlpha;
    private final Method getSuperTransitionAlpha;
    private final Method getIsExpanded;

    // Optional row / background reflection handles (resolved lazily, fail-open)
    private boolean rowMetaLookupDone;
    private Method getRadiusTopMethod;
    private Method getRadiusBottomMethod;
    private Method isPinnedMethod;
    private Method isGroupExpandedMethod;
    private Method isGroupExpansionChangingMethod;
    private Method isSummaryWithChildrenMethod;
    private Method getAttachedChildrenMethod;
    private Method updateBackgroundForGroupStateMethod;
    private Field backgroundNormalField;
    private Field nbvInjectorField;
    private Field nbvClipBottomField;
    private Field nbvBottomOverlapField;
    private Field nbviClipBottomField;
    private Field nbviExtClipBottomField;
    private Field nbviBottomOverlapField;
    private Field nbviOutlineModeField;
    private Object nbviOutlineModeNormal;
    private Method nbvUpdateActualHeightMethod;
    private Method nbvUpdateBackgroundOutlineMethod;
    private Method miGlassSetSdfMaxSizeMethod;
    private Method updateClippingMethod;
    private Method getExtClipBottomAmountMethod;
    private Method getChildrenContainerMethod;
    private Method getPrivateLayoutMethod;
    private Method getRowInjectorMethod;
    private Method getParentRowMethod;
    private Field injectorStackingProgressField;

    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint auraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cometPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint agslPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RuntimeShader perimeterShader = GlowStyle.createPriorityCardGlowShader();

    private final Rect hostClipRect = new Rect();
    private final RectF scratchRect = new RectF();
    private final RectF footerRect = new RectF();
    private final RectF outlineDrawRect = new RectF();
    private final RectF haloDrawRect = new RectF();
    private final Matrix shaderMatrix = new Matrix();

    private final ArrayList<RowSlot> visibleSlots = new ArrayList<>(4);
    private final ArrayList<RowSlot> dockedSlots = new ArrayList<>(4);
    private final ArrayList<RowSlot> detachedSlots = new ArrayList<>(2);
    private final ArrayList<RowSlot> stackedSlots = new ArrayList<>(2);
    private final ArrayList<RowSlot> slotPool = new ArrayList<>(6);
    private final ArrayList<DividerLine> internalDividers = new ArrayList<>(6);

    private final Set<View> rowMembership = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> knownRows = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> ownedSuppressedBgs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<View, View> ownedSuppressedBgOwners = new IdentityHashMap<>();
    private final Set<ViewGroup> clipDisabledRows = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> ownedDockedZViews = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> ownedNonHostDockedRows = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> clippedChildrenContainers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> clippedPrivateLayouts = Collections.newSetFromMap(new IdentityHashMap<>());

    // Unified merged card geometry (docked rows + shared bottom Footer)
    private final RectF mergedBounds = new RectF();
    private final RectF mergedHaloBounds = new RectF();
    private final RectF mergedRimBounds = new RectF();
    private final Path mergedPath = new Path();
    private final Path mergedRimPath = new Path();
    private float mergedTopRadius;
    private float mergedBotRadius;
    private float mergedAlpha;
    private boolean mergedVisible;

    // Canvas fallback shaders (only allocated when AGSL shader is null)
    private SweepGradient fallbackRimShader;
    private SweepGradient fallbackAuraShader;
    private SweepGradient fallbackCometShader;

    // Active native backdrop host tracking
    private View activeHostRow;
    private View activeHostBg;
    private int activeHostMergedHeightPx = -1;
    private int activeHostBgWidthPx = -1;
    private int activeHostBgOrigBottom = -1;

    private final float density;
    private ViewGroup stack;
    private boolean stackOrigClipChildren = true;
    private ViewTreeObserver observer;
    private List<View> rows = List.of();

    private int alpha;
    private float footerOffsetX;
    private float footerOffsetY;
    private float footerDrawAlpha = 1f;
    private boolean footerDrawnVisible;
    private boolean boundsFailed;
    private boolean geometryChanged;

    private boolean darkAppearance = true;
    private boolean xiaoAiStyle = GlowStyle.isXiaoAi();
    private final Runnable styleListener = this::onGlowStyleChanged;
    private boolean appearanceDirty = true;
    private boolean appearanceSampledOnce;
    private boolean appearanceRetryPending;
    private int lastUiModeNight;

    private ValueAnimator shimmerAnimator;
    private float shimmerProgress;
    private float shimmerBoost;
    private boolean wasVisiblyShown;
    private long lastHiddenUptimeMs;
    private boolean pendingNewRowShimmer;

    PrioritySectionFrame(View footer, View footerContents,
                         SystemUiPrioritySection.PriorityFooterView footerView,
                         Method getClipHeight, Method getClipTopAmount,
                         Method getTopOverlap, Method getSuperAlpha, Method getSuperTransitionAlpha,
                         Method getIsExpanded) {
        this.footer = footer;
        this.footerContents = footerContents;
        this.footerView = footerView;
        this.getClipHeight = getClipHeight;
        this.getClipTopAmount = getClipTopAmount;
        this.getTopOverlap = getTopOverlap;
        this.getSuperAlpha = getSuperAlpha;
        this.getSuperTransitionAlpha = getSuperTransitionAlpha;
        this.getIsExpanded = getIsExpanded;
        density = footer.getResources().getDisplayMetrics().density;

        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeCap(Paint.Cap.BUTT);
        dividerPaint.setStrokeWidth(Math.max(1f, 0.70f * density));

        auraPaint.setStyle(Paint.Style.STROKE);
        auraPaint.setStrokeJoin(Paint.Join.ROUND);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeJoin(Paint.Join.ROUND);
        rimPaint.setStrokeWidth(1.25f * density);

        cometPaint.setStyle(Paint.Style.STROKE);
        cometPaint.setStrokeJoin(Paint.Join.ROUND);
        cometPaint.setStrokeWidth(1.50f * density);

        agslPaint.setStyle(Paint.Style.FILL);
        if (perimeterShader != null) {
            agslPaint.setShader(perimeterShader);
        }

        GlowStyle.initInSystemUi(footer.getContext());
        xiaoAiStyle = GlowStyle.isXiaoAi();
        GlowStyle.addListener(styleListener);

        lastUiModeNight = footer.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        darkAppearance = isSystemNightMode();
        if (footerView != null) {
            footerView.setDarkAppearance(darkAppearance);
            footerView.setXiaoAiStyle(xiaoAiStyle);
        }
    }

    // ---------------------------------------------------------------- static hook bridges

    static Integer mergedHeightOverrideFor(Object bgView) {
        return bgView instanceof View v ? sMergedBgHeightOverrides.get(v) : null;
    }

    static Integer mergedRowHeightOverrideFor(Object rowView) {
        return rowView instanceof View v ? sMergedRowHeightOverrides.get(v) : null;
    }

    static Float dockedSuperTransZOverrideFor(Object view) {
        return view instanceof View v ? sDockedSuperTransZOverrides.get(v) : null;
    }

    static boolean isNonHostDockedRow(Object rowView) {
        return rowView instanceof View v && sNonHostDockedRows.contains(v);
    }

    static float mergedRadiusFor(Object bgView, float fallback) {
        if (bgView instanceof View v) {
            Float r = sMergedBgRadii.get(v);
            if (r != null && r > 0f) return r;
        }
        return fallback;
    }

    static boolean isSuppressedBackground(Object bgView) {
        return bgView instanceof View v && sSuppressedBgs.contains(v);
    }

    static boolean isForcedVisibleHostBackground(Object bgView) {
        return bgView instanceof View v && sForcedVisibleHostBgs.contains(v);
    }

    static boolean hasManagedBackgrounds() {
        return !sForcedVisibleHostBgs.isEmpty() || !sSuppressedBgs.isEmpty();
    }

    static void enforceManagedBackgroundView(View bg) {
        if (bg == null) return;
        if (sSuppressedBgs.contains(bg)) {
            if (bg.getVisibility() != View.INVISIBLE) {
                bg.setVisibility(View.INVISIBLE);
            }
            if (bg.getAlpha() > 0f) {
                bg.setAlpha(0f);
            }
            invokeFloatSetter(sSetSuperAlphaMethod, bg, 0f);
            return;
        }
        if (sForcedVisibleHostBgs.contains(bg)) {
            if (bg.getVisibility() != View.VISIBLE) {
                bg.setVisibility(View.VISIBLE);
            }
            if (bg.getAlpha() < 0.999f) {
                bg.setAlpha(1f);
            }
            if (bg.getTransitionAlpha() < 0.999f) {
                bg.setTransitionAlpha(1f);
            }
            invokeFloatSetter(sSetSuperAlphaMethod, bg, 1f);
            if (bg.getTranslationY() != 0f) {
                bg.setTranslationY(0f);
            }
            invokeFloatSetter(sSetSuperTranslationYMethod, bg, 0f);
            if (bg.getTranslationX() != 0f) {
                bg.setTranslationX(0f);
            }
            invokeFloatSetter(sSetSuperTranslationXMethod, bg, 0f);
            if (bg.getScaleX() != 1f) {
                bg.setScaleX(1f);
            }
            if (bg.getScaleY() != 1f) {
                bg.setScaleY(1f);
            }
            invokeFloatSetter(sSetSuperScaleXMethod, bg, 1f);
            invokeFloatSetter(sSetSuperScaleYMethod, bg, 1f);
            if (bg.getTop() != 0) {
                bg.setTop(0);
            }
        }
    }

    static void enforceManagedBackgroundsForRow(View row) {
        if (row == null) return;
        Float zOverride = sDockedSuperTransZOverrides.get(row);
        if (zOverride != null) {
            invokeFloatSetter(sSetViewSuperTranslationZMethod, row, zOverride);
        }
        if (!hasManagedBackgrounds()) return;
        enforceRowOwnBackgrounds(row);
        Method isSummary = sIsSummaryWithChildrenMethod;
        Method getChildren = sGetAttachedChildrenMethod;
        if (isSummary != null && getChildren != null) {
            try {
                if (Boolean.TRUE.equals(isSummary.invoke(row))) {
                    Object listObj = getChildren.invoke(row);
                    if (listObj instanceof List<?> children) {
                        for (int i = 0; i < children.size(); i++) {
                            if (children.get(i) instanceof View childRow) {
                                enforceRowOwnBackgrounds(childRow);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void enforceRowOwnBackgrounds(View row) {
        Field bgField = sBackgroundNormalField;
        if (bgField != null) {
            try {
                if (bgField.get(row) instanceof View bg) {
                    enforceManagedBackgroundView(bg);
                }
            } catch (Throwable ignored) {}
        }
        Class<?> nbvCls = sNbvClass;
        if (nbvCls != null && row instanceof ViewGroup vg) {
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = vg.getChildAt(i);
                if (nbvCls.isInstance(child)) {
                    enforceManagedBackgroundView(child);
                }
            }
        }
    }

    private static void invokeFloatSetter(Method method, View target, float value) {
        if (method == null || target == null) return;
        try {
            method.invoke(target, value);
        } catch (Throwable ignored) {}
    }

    private static float invokeFloatGetter(Method method, View target, float fallback) {
        if (method == null || target == null) return fallback;
        try {
            Object res = method.invoke(target);
            return res instanceof Float f ? f : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- membership & lifecycle

    private void onGlowStyleChanged() {
        boolean next = GlowStyle.isXiaoAi();
        if (next == xiaoAiStyle) return;
        xiaoAiStyle = next;
        invalidateFallbackShaders();
        if (footerView != null) {
            footerView.setXiaoAiStyle(next);
        }
        geometryChanged = true;
        if (wasVisiblyShown && alpha > 0) {
            triggerShimmer();
        }
        if (stack != null) {
            stack.invalidate();
        }
    }

    void setRows(List<View> nextRows) {
        boolean hasNewRow = false;
        rowMembership.clear();
        if (nextRows != null && !nextRows.isEmpty()) {
            for (View row : nextRows) {
                if (row == null) continue;
                rowMembership.add(row);
                if (!knownRows.contains(row)) hasNewRow = true;
            }
            knownRows.clear();
            knownRows.addAll(rowMembership);
        } else {
            knownRows.clear();
        }
        this.rows = nextRows == null ? List.of() : nextRows;
        this.appearanceDirty = true;
        if (rows.isEmpty()) {
            restoreAllManagedBackgrounds();
        } else if (hasNewRow && wasVisiblyShown) {
            pendingNewRowShimmer = true;
        }
        if (stack != null) stack.invalidate();
    }

    void attach(ViewGroup parent) {
        stack = parent;
        stackOrigClipChildren = parent.getClipChildren();
        parent.setClipChildren(false);
        observer = parent.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        appearanceDirty = true;
        parent.invalidate();
    }

    ViewGroup detach() {
        cancelShimmer();
        restoreAllManagedBackgrounds();
        ViewGroup previous = stack;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        stack = null;
        if (previous != null) previous.setClipChildren(stackOrigClipChildren);
        releaseSlots();
        internalDividers.clear();
        alpha = 0;
        mergedVisible = false;
        footerDrawnVisible = false;
        wasVisiblyShown = false;
        pendingNewRowShimmer = false;
        appearanceDirty = true;
        appearanceSampledOnce = false;
        appearanceRetryPending = false;
        if (previous != null) previous.invalidate();
        return previous;
    }

    // ---------------------------------------------------------------- gap & stacking hooks

    boolean isFooter(Object child) {
        return child == footer;
    }

    boolean isPriorityRow(Object child) {
        return child instanceof View v && rowMembership.contains(v);
    }

    boolean shouldUnifyGroupStacking(Object candidate) {
        if (!(candidate instanceof View v) || stack == null || v.getParent() != stack
                || !rowMembership.contains(v) || isPinnedRow(v)) {
            return false;
        }
        ensureRowMetaLookup(v);
        return true;
    }

    boolean isChildOfPriorityGroup(Object candidate) {
        if (!(candidate instanceof View v) || stack == null || rowMembership.isEmpty()) {
            return false;
        }
        View cur = v;
        while (cur.getParent() instanceof View parentView && parentView != stack) {
            cur = parentView;
        }
        return cur != v && cur.getParent() == stack && rowMembership.contains(cur) && !isPinnedRow(cur);
    }

    private float currentFooterHeightPx() {
        return footer.getHeight() > 0
                ? footer.getHeight()
                : (footerView != null && footerView.getMeasuredHeight() > 0
                        ? footerView.getMeasuredHeight()
                        : 40f * density);
    }

    private View findTailDockedPriorityRow() {
        if (!dockedSlots.isEmpty()) {
            return dockedSlots.get(dockedSlots.size() - 1).row;
        }
        View tail = null;
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            if (r == null || r.getParent() != stack || r.getVisibility() != View.VISIBLE || isPinnedRow(r)) {
                continue;
            }
            boolean stacked = false;
            for (int s = 0; s < stackedSlots.size(); s++) {
                if (stackedSlots.get(s).row == r) {
                    stacked = true;
                    break;
                }
            }
            if (!stacked) {
                tail = r;
            }
        }
        return tail;
    }

    /**
     * Returns the shared Footer height reserve to add to {@code currentHeight} in
     * {@code NotificationStackingInteractor.calculateStackingInfo} when the tail docked priority
     * row is an expanded group or tall card entering {@code calculateBigNotificationStackingResult},
     * so {@code extClipBottomAmount} leaves exact room for the shared Footer above
     * {@code stackingBottom} (never covering the Lockscreen fingerprint sensor or bottom shortcuts).
     */
    float footerHeightReserveForDockedTail(Object candidate, float currentHeight, int bigNotifHeight) {
        if (!(candidate instanceof View v) || stack == null || rows.isEmpty()
                || footer == null || footer.getParent() != stack
                || footer.getVisibility() != View.VISIBLE
                || v.getParent() != stack || v.getVisibility() != View.VISIBLE
                || !rowMembership.contains(v) || isPinnedRow(v)) {
            return 0f;
        }
        ensureRowMetaLookup(v);
        for (int i = 0; i < stackedSlots.size(); i++) {
            if (stackedSlots.get(i).row == v) return 0f;
        }
        for (int i = 0; i < detachedSlots.size(); i++) {
            if (detachedSlots.get(i).row == v) return 0f;
        }
        View tail = findTailDockedPriorityRow();
        if (tail != v) return 0f;
        float fh = currentFooterHeightPx();
        if (isExpandedGroupRow(v) || (bigNotifHeight > 0 && currentHeight + fh > bigNotifHeight)) {
            return fh;
        }
        return 0f;
    }

    /**
     * Returns the vertical offset (equal to the unclipped shared Priority Footer height) that must
     * be subtracted from {@code yTranslation} when HyperOS computes Lockscreen card stacking for
     * any card positioned after the leading priority row, so peeking stacked cards sit cleanly
     * below the Priority Footer instead of colliding behind/on top of it.
     */
    float lockscreenStackingOffsetFor(Object candidate) {
        if (!(candidate instanceof View v) || stack == null || rows.isEmpty()
                || footer == null || footer.getParent() != stack
                || footer.getVisibility() != View.VISIBLE) {
            return 0f;
        }
        if (v == footer || isChildOfPriorityGroup(v)) return 0f;

        View leading = null;
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            if (r != null && r.getParent() == stack && r.getVisibility() == View.VISIBLE && !isPinnedRow(r)) {
                leading = r;
                break;
            }
        }
        if (leading == null) return 0f;

        // Walk up to the direct child of NSSL (covers both top-level rows and expanded ordinary group children)
        View topLevel = v;
        while (topLevel.getParent() instanceof View parentView && parentView != stack) {
            topLevel = parentView;
        }
        if (topLevel.getParent() != stack || topLevel == leading || topLevel == footer) {
            return 0f;
        }

        int footerIdx = stack.indexOfChild(footer);
        int candIdx = stack.indexOfChild(topLevel);
        if (candIdx < 0) return 0f;

        boolean inStackedSlots = false;
        for (int i = 0; i < stackedSlots.size(); i++) {
            if (stackedSlots.get(i).row == topLevel) {
                inStackedSlots = true;
                break;
            }
        }
        // Only offset views positioned below the shared Footer (ordinary notifications)
        // or priority rows actively stacked behind the front card on Lockscreen.
        // Never shift docked multi-app priority rows above the Footer.
        if (!inStackedSlots && (footerIdx < 0 || candIdx <= footerIdx)) {
            return 0f;
        }

        float fh = currentFooterHeightPx();
        View tailDocked = findTailDockedPriorityRow();
        if (tailDocked != null) {
            int extClip = getRowExtClipBottomAmount(tailDocked);
            if (extClip > 0) {
                float sy = invokeFloatGetter(sGetViewSuperScaleYMethod, tailDocked, tailDocked.getScaleY());
                float scaledClip = extClip * (sy > 0.05f ? sy : 1f);
                return Math.max(0f, fh - scaledClip);
            }
        }
        return fh;
    }

    /**
     * Adjusts vertical gaps in {@code StackScrollAlgorithm.getGapHeightForChild}:
     * collapses gaps to 0px between adjacent priority rows and between the last priority row
     * and the shared Footer, and adds a clean breathing gap between the Footer and the first
     * ordinary notification below. Pinned Heads-up banners stay completely untouched.
     */
    float adjustGapHeight(View child, View prev, float defaultGap, float paddingBetweenElements) {
        if (child == null || prev == null || rowMembership.isEmpty()) return defaultGap;
        boolean prevPinned = isPinnedRow(prev);
        boolean childPinned = isPinnedRow(child);
        if (prevPinned || childPinned) return defaultGap;

        boolean prevPriority = rowMembership.contains(prev);
        boolean childPriority = rowMembership.contains(child);

        if (prevPriority && childPriority) {
            return -paddingBetweenElements;
        }
        if (prevPriority && child == footer) {
            return -paddingBetweenElements;
        }
        if (prev == footer && !childPriority) {
            return defaultGap + EXTRA_GAP_BELOW_FOOTER_DP * density;
        }
        return defaultGap;
    }

    // ---------------------------------------------------------------- per-frame layout sync

    @Override
    public boolean onPreDraw() {
        int previousAlpha = alpha;
        float previousFooterOffsetX = footerOffsetX;
        float previousFooterOffsetY = footerOffsetY;
        float previousFooterDrawAlpha = footerDrawAlpha;
        boolean previousFooterVisible = footerDrawnVisible;
        boolean previousMergedVisible = mergedVisible;
        int previousDetachedCount = detachedSlots.size();
        int previousDividerCount = internalDividers.size();
        geometryChanged = false;

        int nextAlpha = 0;
        if (!boundsFailed && stack != null && !rows.isEmpty()) {
            try {
                nextAlpha = updateLayoutState();
            } catch (ReflectiveOperationException exception) {
                boundsFailed = true;
                restoreAllManagedBackgrounds();
                Log.w(TAG, "Failed to update priority merged card state", exception);
            }
        } else {
            clearFrameVisuals();
        }

        if (nextAlpha == 0) {
            clearFrameVisuals();
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

        boolean changed = previousAlpha != nextAlpha
                || previousFooterOffsetX != footerOffsetX
                || previousFooterOffsetY != footerOffsetY
                || previousFooterDrawAlpha != footerDrawAlpha
                || previousFooterVisible != footerDrawnVisible
                || previousMergedVisible != mergedVisible
                || previousDetachedCount != detachedSlots.size()
                || previousDividerCount != internalDividers.size()
                || geometryChanged;

        if (stack != null && changed) {
            if ((previousAlpha == 0) != (nextAlpha == 0)) {
                footer.setImportantForAccessibility(nextAlpha == 0
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

    private void clearFrameVisuals() {
        restoreAllManagedBackgrounds();
        releaseSlots();
        internalDividers.clear();
        mergedVisible = false;
        footerDrawnVisible = false;
        footerOffsetX = 0f;
        footerOffsetY = 0f;
        footerDrawAlpha = 0f;
    }

    private int updateLayoutState() throws ReflectiveOperationException {
        mergedVisible = false;
        footerDrawnVisible = false;
        dockedSlots.clear();
        detachedSlots.clear();
        stackedSlots.clear();
        internalDividers.clear();

        if (!stack.isShown() || !(boolean) getIsExpanded.invoke(stack)) return 0;

        boolean footerHasBounds = getVisibleBounds(footer, footerRect, false);
        float footerNativeAlpha = footerHasBounds ? getDrawnAlpha(footer) : 0f;

        int visibleCount = 0;
        float maxRowAlpha = 0f;
        float swipeThreshPx = SWIPE_DETACH_THRESHOLD_DP * density;

        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            if (row == null || isPinnedRow(row)) continue;
            float rowAlpha = getDrawnAlpha(row);
            if (rowAlpha < 0.06f) continue;
            if (!getVisibleBounds(row, scratchRect, true)) continue;
            if (scratchRect.width() < row.getWidth() * 0.65f) continue;
            if (scratchRect.height() < 12f * density) continue;

            ensureRowMetaLookup(row);
            if (visibleCount == visibleSlots.size()) visibleSlots.add(acquireSlot());
            RowSlot slot = visibleSlots.get(visibleCount++);

            float prevTopR = slot.topRadius;
            float prevBotR = slot.botRadius;
            boolean prevSwiping = slot.swiping;
            boolean prevStacked = slot.stackedBehind;

            slot.row = row;
            slot.alpha = rowAlpha;
            slot.translationX = invokeFloatGetter(sGetViewSuperTranslationXMethod, row, row.getTranslationX());
            slot.scaleX = invokeFloatGetter(sGetViewSuperScaleXMethod, row, row.getScaleX());
            slot.scaleY = invokeFloatGetter(sGetViewSuperScaleYMethod, row, row.getScaleY());
            resolveCardRadii(row, slot);
            slot.swiping = Math.abs(slot.translationX) > swipeThreshPx;
            slot.expandedGroup = isExpandedGroupRow(row);

            if (!slot.bounds.equals(scratchRect) || prevTopR != slot.topRadius
                    || prevBotR != slot.botRadius || prevSwiping != slot.swiping) {
                slot.bounds.set(scratchRect);
                slot.rebuildGeometry(density);
                geometryChanged = true;
            }

            // Detect HyperOS lockscreen / viewport stacked deck: row tucked behind an earlier priority row.
            // Query ExpandableViewInjector.stackingProgress when available so touch-down bounce (11035,
            // scale ~0.90 with stackingProgress == 0) never falsely detaches a docked row into stackedSlots.
            boolean stacked = false;
            if (visibleCount > 1) {
                RowSlot prev = visibleSlots.get(visibleCount - 2);
                float stackingProg = getRowStackingProgress(row);
                if (stackingProg >= 0f) {
                    stacked = stackingProg > 0.015f || slot.bounds.top < prev.bounds.bottom - 14f * density;
                } else if ((slot.scaleX < 0.985f && slot.bounds.top < prev.bounds.bottom - 4f * density)
                        || slot.bounds.top < prev.bounds.bottom - 14f * density) {
                    stacked = true;
                }
            }
            slot.stackedBehind = stacked;
            if (prevStacked != stacked) geometryChanged = true;

            maxRowAlpha = Math.max(maxRowAlpha, rowAlpha);
        }

        trimSlots(visibleCount);
        if (visibleSlots.isEmpty() || maxRowAlpha <= 0f) return 0;

        // Partition visible priority rows into docked / swiping-detached / lockscreen-stacked
        int nonStackedCount = 0;
        for (int i = 0; i < visibleSlots.size(); i++) {
            if (!visibleSlots.get(i).stackedBehind) nonStackedCount++;
        }
        boolean singlePriorityCard = (nonStackedCount == 1);

        for (int i = 0; i < visibleSlots.size(); i++) {
            RowSlot slot = visibleSlots.get(i);
            if (slot.stackedBehind) {
                stackedSlots.add(slot);
            } else if (slot.swiping && !singlePriorityCard) {
                // Multi-card swipe: individual row detaches immediately with full 4-corner card
                detachedSlots.add(slot);
            } else {
                // Single card (even while swiping — drags Footer together!) or docked multi-card
                dockedSlots.add(slot);
            }
        }

        // Update dark/light surface ink detection from live visible priority rows
        int uiModeNight = footer.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (uiModeNight != lastUiModeNight) {
            lastUiModeNight = uiModeNight;
            appearanceDirty = true;
            appearanceSampledOnce = false;
        }
        if (appearanceDirty || (appearanceRetryPending && geometryChanged)) {
            updateAppearanceFromVisibleSlots();
        }

        if (dockedSlots.isEmpty()) {
            // Edge case: all non-stacked rows are simultaneously swiping
            restoreAllManagedBackgrounds();
            footerDrawnVisible = false;
            mergedVisible = false;
            return Math.round(255f * maxRowAlpha);
        }

        // Compute unified merged card bounding box: [firstDocked.top .. lastDocked.bottom + footerH]
        RowSlot firstDocked = dockedSlots.get(0);
        RowSlot lastDocked = dockedSlots.get(dockedSlots.size() - 1);

        float cardLeft = firstDocked.bounds.left;
        float cardRight = firstDocked.bounds.right;
        float cardTop = firstDocked.bounds.top;
        float dockedRowsBottom = lastDocked.bounds.bottom;
        float minDockedAlpha = firstDocked.alpha;

        for (int i = 1; i < dockedSlots.size(); i++) {
            RowSlot s = dockedSlots.get(i);
            cardLeft = Math.min(cardLeft, s.bounds.left);
            cardRight = Math.max(cardRight, s.bounds.right);
            cardTop = Math.min(cardTop, s.bounds.top);
            dockedRowsBottom = Math.max(dockedRowsBottom, s.bounds.bottom);
            minDockedAlpha = Math.min(minDockedAlpha, s.alpha);
        }

        // Position PriorityFooterView precisely at the bottom of the merged card (if visible)
        float cardBottom;
        if (footerHasBounds && footerNativeAlpha > 0.02f) {
            float footerH = footerRect.height();
            float targetFooterTop = dockedRowsBottom;
            if (stackedSlots.isEmpty() && !detachedSlots.isEmpty()) {
                // Keep merged card reaching footer's natural top when a lower row is swiping out
                for (int i = 0; i < detachedSlots.size(); i++) {
                    float db = detachedSlots.get(i).bounds.bottom;
                    if (db > targetFooterTop) targetFooterTop = db;
                }
            }
            footerOffsetY = targetFooterTop - footerRect.top;
            footerOffsetX = (singlePriorityCard && firstDocked.swiping) ? firstDocked.translationX : 0f;
            footerDrawAlpha = Math.min(1f, minDockedAlpha / Math.max(0.01f, footerNativeAlpha));
            footerDrawnVisible = footerDrawAlpha > 0.02f;
            cardBottom = targetFooterTop + footerH;
            mergedAlpha = Math.min(footerNativeAlpha, minDockedAlpha);
        } else {
            // Footer scrolled off-screen or momentarily clipped: keep docked priority rows unified
            footerOffsetY = 0f;
            footerOffsetX = 0f;
            footerDrawAlpha = 0f;
            footerDrawnVisible = false;
            cardBottom = dockedRowsBottom;
            mergedAlpha = minDockedAlpha;
        }

        if (footerContents != null) {
            footerContents.setAlpha(footerDrawAlpha);
        }
        if (footerView != null) {
            float footerOriginX = footer.getLeft() + footer.getTranslationX() + footerOffsetX;
            footerView.setCardHorizontalSpan(cardLeft - footerOriginX, cardRight - footerOriginX);
            footerView.setDarkAppearance(darkAppearance);
            footerView.setXiaoAiStyle(xiaoAiStyle);
        }

        float topR = firstDocked.topRadius;
        float botR = lastDocked.botRadius;

        scratchRect.set(cardLeft, cardTop, cardRight, cardBottom);
        if (!mergedBounds.equals(scratchRect) || mergedTopRadius != topR || mergedBotRadius != botR) {
            mergedBounds.set(scratchRect);
            mergedTopRadius = topR;
            mergedBotRadius = botR;
            rebuildMergedGeometry();
            geometryChanged = true;
        }
        mergedVisible = mergedBounds.width() > 24f * density && mergedBounds.height() > 24f * density;

        // Synchronize HyperOS native unified glass/blur NotificationBackgroundView
        syncManagedNativeBackgrounds(firstDocked, cardBottom);

        // Compute internal hairline dividers between docked rows (and expanded group children)
        buildInternalDividers(cardLeft, cardRight);

        return Math.round(255f * Math.max(mergedAlpha, maxRowAlpha));
    }

    private void rebuildMergedGeometry() {
        buildSquirclePath(mergedBounds, mergedTopRadius, mergedBotRadius, mergedPath);
        float rimInset = 0.45f * density;
        mergedRimBounds.set(
                mergedBounds.left + rimInset,
                mergedBounds.top + rimInset,
                mergedBounds.right - rimInset,
                mergedBounds.bottom - rimInset);
        buildSquirclePath(
                mergedRimBounds,
                Math.max(0f, mergedTopRadius - rimInset),
                Math.max(0f, mergedBotRadius - rimInset),
                mergedRimPath);
        mergedHaloBounds.set(mergedBounds);
        float haloPad = (darkAppearance ? 12.0f : 8.5f) * density + 1f;
        mergedHaloBounds.inset(-haloPad, -haloPad);
    }

    /**
     * Extends {@code hostSlot}'s native HyperOS {@code NotificationBackgroundView} seamlessly over
     * {@code [hostSlot.top .. mergedCardBottom]} and suppresses redundant backgrounds on subsequent
     * docked rows (and expanded children of docked VIP groups). Restores every released row
     * immediately so swiped, stacked, pinned, or demoted rows behave 100% natively.
     */
    private void syncManagedNativeBackgrounds(RowSlot hostSlot, float mergedCardBottom) {
        if (backgroundNormalField == null || hostSlot == null || hostSlot.row == null) {
            restoreAllManagedBackgrounds();
            return;
        }

        View hostRow = hostSlot.row;
        View hostBg = getRowBackground(hostRow);
        if (hostBg == null) {
            restoreAllManagedBackgrounds();
            return;
        }

        // Compute local unscaled height required for hostBg to reach mergedCardBottom seamlessly
        float scaleY = hostSlot.scaleY > 0.05f ? hostSlot.scaleY : 1f;
        int hostClipTop = 0;
        try {
            hostClipTop = Math.max(0, Math.max((int) getClipTopAmount.invoke(hostRow),
                    (int) getTopOverlap.invoke(hostRow)));
        } catch (Throwable ignored) {}
        int targetHeightPx = Math.max(1, Math.round((mergedCardBottom - hostSlot.bounds.top) / scaleY) + hostClipTop);
        int bgWidth = hostBg.getWidth();

        // Collect all backgrounds that must be suppressed this frame (bg -> owning row):
        // - secondary NotificationBackgroundViews (e.g. backgroundDimmed) on hostRow
        // - ALL NotificationBackgroundViews on subsequent docked priority rows (multi-app unity)
        // - ALL NotificationBackgroundViews of group children for ANY docked summary group
        //   (collapsed, expanding, expanded, or collapsing — prevents stale glass strips on collapse!)
        Map<View, View> nextSuppressedOwners = new IdentityHashMap<>();
        for (int i = 0; i < dockedSlots.size(); i++) {
            RowSlot slot = dockedSlots.get(i);
            collectRowBackgrounds(slot.row, hostBg, nextSuppressedOwners);
            if (isSummaryWithChildrenRow(slot.row)) {
                collectGroupChildBackgrounds(slot.row, hostBg, nextSuppressedOwners);
            }
        }

        // Restore any previously suppressed backgrounds that are no longer suppressed
        if (!ownedSuppressedBgs.isEmpty()) {
            List<View> toRestore = null;
            for (View oldBg : ownedSuppressedBgs) {
                if (!nextSuppressedOwners.containsKey(oldBg)) {
                    if (toRestore == null) toRestore = new ArrayList<>();
                    toRestore.add(oldBg);
                }
            }
            if (toRestore != null) {
                for (View bg : toRestore) {
                    View ownerRow = ownedSuppressedBgOwners.remove(bg);
                    ownedSuppressedBgs.remove(bg);
                    restoreSuppressedBackground(bg, ownerRow);
                }
            }
        }

        // Release old host if host switched (e.g. Row0 started swiping -> Row1 took over!)
        if (activeHostBg != null && activeHostBg != hostBg) {
            releaseActiveHostBackdrop();
        }

        // Ensure hostRow does not clip its extended child mBackgroundNormal
        if (hostRow instanceof ViewGroup vg) {
            clipDisabledRows.add(vg);
            if (vg.getClipChildren()) vg.setClipChildren(false);
            if (vg.getClipToPadding()) vg.setClipToPadding(false);
        }

        // Make sure hostBg is visible, full alpha, unscaled, and pinned to top=0
        // (even when hostRow is an expanding/expanded MIUI group whose native animator
        // tries to hide or shift mBackgroundNormal down via PropertiesFrameLayout!)
        sSuppressedBgs.remove(hostBg);
        ownedSuppressedBgs.remove(hostBg);
        ownedSuppressedBgOwners.remove(hostBg);
        sForcedVisibleHostBgs.add(hostBg);

        boolean hostWasMovedOrHidden = hostBg.getVisibility() != View.VISIBLE
                || hostBg.getAlpha() < 0.999f
                || hostBg.getTransitionAlpha() < 0.999f
                || hostBg.getTop() != 0
                || Math.abs(hostBg.getTranslationY()) > 0.25f
                || Math.abs(hostBg.getTranslationX()) > 0.25f
                || Math.abs(hostBg.getScaleX() - 1f) > 0.002f
                || Math.abs(hostBg.getScaleY() - 1f) > 0.002f
                || Math.abs(invokeFloatGetter(sGetSuperTranslationYMethod, hostBg, 0f)) > 0.25f
                || Math.abs(invokeFloatGetter(sGetSuperScaleXMethod, hostBg, 1f) - 1f) > 0.002f
                || Math.abs(invokeFloatGetter(sGetSuperScaleYMethod, hostBg, 1f) - 1f) > 0.002f;

        enforceManagedBackgroundView(hostBg);
        if (clearHostBottomClipAndOutlineMode(hostBg)) {
            hostWasMovedOrHidden = true;
        }

        // Update height override + bottom layout bound + hostRow clipBounds + HyperOS outline & Liquid Glass SDF
        boolean hostChanged = (activeHostBg != hostBg);
        boolean heightChanged = (activeHostMergedHeightPx != targetHeightPx);
        boolean widthChanged = (activeHostBgWidthPx != bgWidth);
        if (hostChanged) {
            activeHostRow = hostRow;
            activeHostBg = hostBg;
            activeHostBgOrigBottom = hostBg.getBottom();
        }
        sMergedBgHeightOverrides.put(hostBg, targetHeightPx);
        sMergedBgRadii.put(hostBg, Math.max(mergedTopRadius, mergedBotRadius));
        sMergedRowHeightOverrides.put(hostRow, targetHeightPx);

        // Expand hostRow's clipBounds (set by ExpandableView.updateClipping) so the extended
        // native Liquid Glass / Blur background flows seamlessly across docked rows and Footer
        int rowW = hostRow.getWidth();
        if (rowW > 0) {
            Rect existingClip = hostRow.getClipBounds();
            if (existingClip != null && (existingClip.bottom != targetHeightPx || existingClip.right < rowW)) {
                hostClipRect.set(existingClip.left, existingClip.top,
                        Math.max(existingClip.right, rowW), targetHeightPx);
                hostRow.setClipBounds(hostClipRect);
            }
        }

        int desiredBottom = hostBg.getTop() + targetHeightPx;
        boolean bottomAdjusted = false;
        if (hostBg.getBottom() != desiredBottom) {
            hostBg.setBottom(desiredBottom);
            bottomAdjusted = true;
        }

        if (hostChanged || heightChanged || widthChanged || bottomAdjusted || hostWasMovedOrHidden) {
            activeHostMergedHeightPx = targetHeightPx;
            activeHostBgWidthPx = bgWidth;
            refreshNativeBackgroundOutlineAndGlass(hostBg, bgWidth, targetHeightPx);
            hostRow.invalidateOutline();
            geometryChanged = true;
        }

        // Apply suppression to all non-host docked rows and docked group children
        for (Map.Entry<View, View> entry : nextSuppressedOwners.entrySet()) {
            View bg = entry.getKey();
            View ownerRow = entry.getValue();
            boolean newlySuppressed = ownedSuppressedBgs.add(bg);
            ownedSuppressedBgOwners.put(bg, ownerRow);
            sMergedBgHeightOverrides.remove(bg);
            sMergedBgRadii.remove(bg);
            sForcedVisibleHostBgs.remove(bg);
            sSuppressedBgs.add(bg);
            boolean stateChanged = bg.getVisibility() != View.INVISIBLE || bg.getAlpha() > 0f;
            enforceManagedBackgroundView(bg);
            if ((newlySuppressed || stateChanged) && ownerRow != null) {
                ownerRow.invalidate();
            }
        }

        // Equalize Z-order across all non-host docked priority rows + shared Footer so hostRow's
        // stretched Liquid Glass / MiBlur background never occludes lower docked priority rows.
        syncDockedZOrder(hostRow);

        // Clip inner notification content right above the shared Footer hairline whenever any
        // docked row is bottom-clipped by Lockscreen / viewport stackingBottom (extClipBottomAmount > 0).
        syncDockedContentViewportClips();
    }

    private void syncDockedZOrder(View hostRow) {
        if (hostRow == null) {
            releaseDockedZOrder();
            return;
        }
        // Ensure hostRow itself always runs at its genuine native Z-plane (e.g. behind MediaHeader,
        // in front of stacked/ordinary rows below).
        if (ownedDockedZViews.remove(hostRow)) {
            sDockedSuperTransZOverrides.remove(hostRow);
            restoreNativeSuperTransZ(hostRow);
        }

        float hostSuperZ = invokeFloatGetter(
                sGetViewSuperTranslationZMethod, hostRow, hostRow.getTranslationZ());
        float hostTotalZ = hostRow.getElevation() + hostSuperZ;
        int hostChildIdx = stack != null ? stack.indexOfChild(hostRow) : -1;

        Set<View> nextNonHostRows = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<View, Float> nextZTargets = new IdentityHashMap<>();

        for (int i = 1; i < dockedSlots.size(); i++) {
            View r = dockedSlots.get(i).row;
            if (r == null || r == hostRow) continue;
            nextNonHostRows.add(r);
            int idx = stack != null ? stack.indexOfChild(r) : -1;
            float eps = (hostChildIdx >= 0 && idx >= 0 && idx < hostChildIdx) ? (0.01f * i) : 0f;
            nextZTargets.put(r, hostTotalZ - r.getElevation() + eps);
        }

        if (footer != null && footerDrawnVisible && footer.getParent() == stack) {
            int fIdx = stack.indexOfChild(footer);
            float eps = (hostChildIdx >= 0 && fIdx >= 0 && fIdx < hostChildIdx) ? 0.02f : 0f;
            nextZTargets.put(footer, hostTotalZ - footer.getElevation() + eps);
        }

        if (!ownedNonHostDockedRows.isEmpty()) {
            List<View> staleRows = null;
            for (View r : ownedNonHostDockedRows) {
                if (!nextNonHostRows.contains(r)) {
                    if (staleRows == null) staleRows = new ArrayList<>();
                    staleRows.add(r);
                }
            }
            if (staleRows != null) {
                for (View r : staleRows) {
                    ownedNonHostDockedRows.remove(r);
                    sNonHostDockedRows.remove(r);
                    r.invalidateOutline();
                }
            }
        }
        for (View r : nextNonHostRows) {
            if (ownedNonHostDockedRows.add(r)) {
                sNonHostDockedRows.add(r);
                r.invalidateOutline();
            }
        }

        if (!ownedDockedZViews.isEmpty()) {
            List<View> toRelease = null;
            for (View oldView : ownedDockedZViews) {
                if (!nextZTargets.containsKey(oldView)) {
                    if (toRelease == null) toRelease = new ArrayList<>();
                    toRelease.add(oldView);
                }
            }
            if (toRelease != null) {
                for (View oldView : toRelease) {
                    ownedDockedZViews.remove(oldView);
                    sDockedSuperTransZOverrides.remove(oldView);
                    restoreNativeSuperTransZ(oldView);
                }
            }
        }

        for (Map.Entry<View, Float> entry : nextZTargets.entrySet()) {
            View v = entry.getKey();
            float targetSuperZ = entry.getValue();
            ownedDockedZViews.add(v);
            sDockedSuperTransZOverrides.put(v, targetSuperZ);
            float curSuperZ = invokeFloatGetter(sGetViewSuperTranslationZMethod, v, v.getTranslationZ());
            if (Math.abs(curSuperZ - targetSuperZ) > 0.0005f) {
                invokeFloatSetter(sSetViewSuperTranslationZMethod, v, targetSuperZ);
            }
        }
    }

    private void releaseDockedZOrder() {
        if (!ownedDockedZViews.isEmpty()) {
            for (View v : ownedDockedZViews) {
                sDockedSuperTransZOverrides.remove(v);
                restoreNativeSuperTransZ(v);
            }
            ownedDockedZViews.clear();
        }
        if (!ownedNonHostDockedRows.isEmpty()) {
            for (View r : ownedNonHostDockedRows) {
                sNonHostDockedRows.remove(r);
                r.invalidateOutline();
            }
            ownedNonHostDockedRows.clear();
        }
    }

    private static void restoreNativeSuperTransZ(View view) {
        if (view == null || sSetViewSuperTranslationZMethod == null) return;
        float extZ = invokeFloatGetter(sGetExtTranslationZMethod, view, 0f);
        invokeFloatSetter(sSetViewSuperTranslationZMethod, view, view.getTranslationZ() + extZ);
    }

    private void syncDockedContentViewportClips() {
        Set<View> nextContainers = null;
        Set<View> nextPrivateLayouts = null;

        for (int i = 0; i < dockedSlots.size(); i++) {
            View row = dockedSlots.get(i).row;
            if (row == null) continue;
            int extClip = getRowExtClipBottomAmount(row);
            if (extClip <= 0) continue;

            int clipTop = 0;
            int clipH = 0;
            try {
                clipTop = Math.max(0, Math.max((int) getClipTopAmount.invoke(row),
                        (int) getTopOverlap.invoke(row)));
                clipH = (int) getClipHeight.invoke(row);
            } catch (Throwable ignored) {}
            if (clipH <= 0) continue;
            int localContentBottom = clipTop + clipH;

            if (getChildrenContainerMethod != null && isSummaryWithChildrenRow(row)) {
                try {
                    if (getChildrenContainerMethod.invoke(row) instanceof View cc
                            && cc.getVisibility() == View.VISIBLE && cc.getWidth() > 0) {
                        int bottom = Math.max(0, localContentBottom - cc.getTop());
                        Rect existing = cc.getClipBounds();
                        if (existing == null || existing.left != 0 || existing.top != 0
                                || existing.right != cc.getWidth() || existing.bottom != bottom) {
                            cc.setClipBounds(new Rect(0, 0, cc.getWidth(), bottom));
                        }
                        if (nextContainers == null) {
                            nextContainers = Collections.newSetFromMap(new IdentityHashMap<>());
                        }
                        nextContainers.add(cc);
                        clippedChildrenContainers.add(cc);
                    }
                } catch (Throwable ignored) {}
            }

            if (getPrivateLayoutMethod != null) {
                try {
                    if (getPrivateLayoutMethod.invoke(row) instanceof View pl
                            && pl.getVisibility() == View.VISIBLE && pl.getWidth() > 0) {
                        int bottom = Math.max(0, localContentBottom - pl.getTop());
                        Rect existing = pl.getClipBounds();
                        if (existing == null || existing.bottom > bottom) {
                            int left = existing != null ? existing.left : 0;
                            int top = existing != null ? existing.top : 0;
                            int right = existing != null ? existing.right : pl.getWidth();
                            pl.setClipBounds(new Rect(left, top, right, bottom));
                        }
                        if (nextPrivateLayouts == null) {
                            nextPrivateLayouts = Collections.newSetFromMap(new IdentityHashMap<>());
                        }
                        nextPrivateLayouts.add(pl);
                        clippedPrivateLayouts.add(pl);
                    }
                } catch (Throwable ignored) {}
            }
        }

        releaseStaleViewportClips(nextContainers, nextPrivateLayouts);
    }

    private void releaseStaleViewportClips(Set<View> keepContainers, Set<View> keepPrivateLayouts) {
        if (!clippedChildrenContainers.isEmpty()) {
            List<View> toRestore = null;
            for (View cc : clippedChildrenContainers) {
                if (keepContainers == null || !keepContainers.contains(cc)) {
                    if (toRestore == null) toRestore = new ArrayList<>();
                    toRestore.add(cc);
                }
            }
            if (toRestore != null) {
                for (View cc : toRestore) {
                    clippedChildrenContainers.remove(cc);
                    cc.setClipBounds(null);
                }
            }
        }
        if (!clippedPrivateLayouts.isEmpty()) {
            List<View> toRestore = null;
            for (View pl : clippedPrivateLayouts) {
                if (keepPrivateLayouts == null || !keepPrivateLayouts.contains(pl)) {
                    if (toRestore == null) toRestore = new ArrayList<>();
                    toRestore.add(pl);
                }
            }
            if (toRestore != null) {
                for (View pl : toRestore) {
                    clippedPrivateLayouts.remove(pl);
                    Method updateClip = ModuleMain.findMethod(pl.getClass(), "updateClipping");
                    if (updateClip != null) {
                        try {
                            updateClip.invoke(pl);
                            continue;
                        } catch (Throwable ignored) {}
                    }
                    pl.setClipBounds(null);
                }
            }
        }
    }

    private void releaseActiveHostBackdrop() {
        View oldBg = activeHostBg;
        View oldRow = activeHostRow;
        int origBottom = activeHostBgOrigBottom;
        activeHostBg = null;
        activeHostRow = null;
        activeHostMergedHeightPx = -1;
        activeHostBgWidthPx = -1;
        activeHostBgOrigBottom = -1;

        if (oldRow != null) {
            sMergedRowHeightOverrides.remove(oldRow);
        }
        if (oldBg != null) {
            sMergedBgHeightOverrides.remove(oldBg);
            sMergedBgRadii.remove(oldBg);
            sForcedVisibleHostBgs.remove(oldBg);
            if (origBottom > oldBg.getTop()) {
                int restoreH = (oldRow != null && oldRow.getHeight() > 0)
                        ? oldRow.getHeight()
                        : (origBottom - oldBg.getTop());
                oldBg.setBottom(oldBg.getTop() + restoreH);
            }
            refreshNativeBackgroundOutlineAndGlass(oldBg, oldBg.getWidth(), -1);
            oldBg.invalidate();
        }
        if (oldRow != null) {
            if (updateBackgroundForGroupStateMethod != null) {
                try {
                    updateBackgroundForGroupStateMethod.invoke(oldRow);
                } catch (Throwable ignored) {}
            }
            if (updateClippingMethod != null) {
                try {
                    updateClippingMethod.invoke(oldRow);
                } catch (Throwable ignored) {}
            }
            oldRow.invalidateOutline();
        }
        if (oldRow instanceof ViewGroup vg && clipDisabledRows.remove(vg)) {
            vg.setClipChildren(true);
        }
    }

    private void restoreAllManagedBackgrounds() {
        releaseActiveHostBackdrop();
        releaseDockedZOrder();
        releaseStaleViewportClips(null, null);
        if (!ownedSuppressedBgs.isEmpty()) {
            for (View bg : ownedSuppressedBgs) {
                View ownerRow = ownedSuppressedBgOwners.remove(bg);
                restoreSuppressedBackground(bg, ownerRow);
            }
            ownedSuppressedBgs.clear();
            ownedSuppressedBgOwners.clear();
        }
        if (!clipDisabledRows.isEmpty()) {
            for (ViewGroup vg : clipDisabledRows) {
                vg.setClipChildren(true);
            }
            clipDisabledRows.clear();
        }
    }

    private void restoreSuppressedBackground(View bg, View ownerRow) {
        if (bg == null) return;
        sSuppressedBgs.remove(bg);
        bg.setAlpha(1f);
        invokeFloatSetter(sSetSuperAlphaMethod, bg, 1f);
        if (ownerRow != null && updateBackgroundForGroupStateMethod != null) {
            try {
                updateBackgroundForGroupStateMethod.invoke(ownerRow);
            } catch (Throwable ignored) {
                if (bg == getRowBackground(ownerRow) && bg.getVisibility() != View.VISIBLE) {
                    bg.setVisibility(View.VISIBLE);
                }
            }
            ownerRow.invalidate();
        } else if (bg.getVisibility() != View.VISIBLE) {
            bg.setVisibility(View.VISIBLE);
        }
        bg.invalidate();
    }

    private boolean clearHostBottomClipAndOutlineMode(View hostBg) {
        boolean changed = false;
        try {
            if (nbvClipBottomField != null && nbvClipBottomField.getInt(hostBg) != 0) {
                nbvClipBottomField.setInt(hostBg, 0);
                changed = true;
            }
            if (nbvBottomOverlapField != null && nbvBottomOverlapField.getInt(hostBg) != 0) {
                nbvBottomOverlapField.setInt(hostBg, 0);
                changed = true;
            }
            if (nbvInjectorField != null) {
                Object inj = nbvInjectorField.get(hostBg);
                if (inj != null) {
                    if (nbviClipBottomField != null && nbviClipBottomField.getInt(inj) != 0) {
                        nbviClipBottomField.setInt(inj, 0);
                        changed = true;
                    }
                    if (nbviExtClipBottomField != null && nbviExtClipBottomField.getInt(inj) != 0) {
                        nbviExtClipBottomField.setInt(inj, 0);
                        changed = true;
                    }
                    if (nbviBottomOverlapField != null && nbviBottomOverlapField.getInt(inj) != 0) {
                        nbviBottomOverlapField.setInt(inj, 0);
                        changed = true;
                    }
                    if (nbviOutlineModeField != null && nbviOutlineModeNormal != null
                            && nbviOutlineModeField.get(inj) != nbviOutlineModeNormal) {
                        if (nbvUpdateBackgroundOutlineMethod != null) {
                            nbvUpdateBackgroundOutlineMethod.invoke(inj, false, false);
                        } else {
                            nbviOutlineModeField.set(inj, nbviOutlineModeNormal);
                        }
                        changed = true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return changed;
    }

    private void refreshNativeBackgroundOutlineAndGlass(View bg, int width, int overrideHeight) {
        if (bg == null) return;
        try {
            if (nbvInjectorField != null) {
                Object inj = nbvInjectorField.get(bg);
                if (inj != null) {
                    if (overrideHeight > 0 && nbvUpdateBackgroundOutlineMethod != null
                            && nbviOutlineModeField != null && nbviOutlineModeNormal != null
                            && nbviOutlineModeField.get(inj) != nbviOutlineModeNormal) {
                        nbvUpdateBackgroundOutlineMethod.invoke(inj, false, false);
                    }
                    if (nbvUpdateActualHeightMethod != null) {
                        nbvUpdateActualHeightMethod.invoke(inj);
                    }
                }
            }
            bg.invalidateOutline();
            if (miGlassSetSdfMaxSizeMethod != null && width > 0) {
                int h = overrideHeight > 0 ? overrideHeight : bg.getHeight();
                if (h > 0) {
                    miGlassSetSdfMaxSizeMethod.invoke(null, bg, (float) width, (float) h);
                }
            }
            bg.invalidate();
        } catch (Throwable ignored) {}
    }

    private void collectRowBackgrounds(View row, View hostBg, Map<View, View> outOwners) {
        if (row == null) return;
        View normalBg = getRowBackground(row);
        if (normalBg != null && normalBg != hostBg) {
            outOwners.put(normalBg, row);
        }
        Class<?> nbvCls = sNbvClass;
        if (nbvCls != null && row instanceof ViewGroup vg) {
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = vg.getChildAt(i);
                if (child != hostBg && nbvCls.isInstance(child)) {
                    outOwners.put(child, row);
                }
            }
        }
    }

    private void collectGroupChildBackgrounds(View groupRow, View hostBg, Map<View, View> outOwners) {
        if (getAttachedChildrenMethod == null) return;
        try {
            Object listObj = getAttachedChildrenMethod.invoke(groupRow);
            if (!(listObj instanceof List<?> children)) return;
            for (Object item : children) {
                if (item instanceof View childRow) {
                    collectRowBackgrounds(childRow, hostBg, outOwners);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Computes crisp 0.65dp internal hairline dividers between docked priority items. */
    private void buildInternalDividers(float cardLeft, float cardRight) {
        float inset = CONTENT_INSET_DP * density;
        float lineLeft = cardLeft + inset;
        float lineRight = cardRight - inset;
        if (lineRight - lineLeft < 24f * density) return;

        for (int i = 0; i < dockedSlots.size() - 1; i++) {
            RowSlot upper = dockedSlots.get(i);
            RowSlot lower = dockedSlots.get(i + 1);
            // Skip hairline if a detached (swiping) row sits between upper and lower
            float gap = lower.bounds.top - upper.bounds.bottom;
            if (gap > 8f * density) continue;
            float y = (upper.bounds.bottom + lower.bounds.top) * 0.5f;
            float pairAlpha = Math.min(upper.alpha, lower.alpha);
            if (pairAlpha > 0.05f) {
                internalDividers.add(new DividerLine(lineLeft, lineRight, y, pairAlpha));
            }
        }

        // Also add subtle hairlines between visible children inside an expanded VIP group
        for (int i = 0; i < dockedSlots.size(); i++) {
            RowSlot slot = dockedSlots.get(i);
            if (!slot.expandedGroup || getAttachedChildrenMethod == null) continue;
            try {
                Object listObj = getAttachedChildrenMethod.invoke(slot.row);
                if (!(listObj instanceof List<?> children) || children.isEmpty()) continue;
                float prevBottom = Float.NaN;
                float prevAlpha = 1f;
                for (int c = 0; c < children.size(); c++) {
                    if (!(children.get(c) instanceof View child)) continue;
                    if (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.08f) continue;
                    int h = child.getHeight();
                    if (h <= 0) continue;
                    // Accumulate exact rendered offset through NotificationChildrenContainer up to slot.row
                    // (PropertiesFrameLayout.getY() == getTop() + getSuperTranslationY())
                    float relY = 0f;
                    View cur = child;
                    while (cur != null && cur != slot.row) {
                        relY += cur.getY();
                        cur = cur.getParent() instanceof View pv ? pv : null;
                    }
                    float childTop = slot.bounds.top + relY * (slot.scaleY > 0.05f ? slot.scaleY : 1f);
                    float childBottom = childTop + h * (slot.scaleY > 0.05f ? slot.scaleY : 1f);
                    float childAlpha = slot.alpha * child.getAlpha();
                    if (Float.isNaN(prevBottom)) {
                        // Subtle hairline separating the expanded group title bar (e.g. "Shell(2) ^")
                        // from the first expanded child notification inside the unified glass card
                        if (childTop > slot.bounds.top + 24f * density && childTop < slot.bounds.bottom - 12f * density) {
                            internalDividers.add(new DividerLine(lineLeft, lineRight, childTop, childAlpha));
                        }
                    } else if (childTop >= prevBottom - 6f * density
                            && childTop <= prevBottom + 24f * density) {
                        float y = (prevBottom + childTop) * 0.5f;
                        if (y > slot.bounds.top + 12f * density && y < slot.bounds.bottom - 8f * density) {
                            internalDividers.add(new DividerLine(
                                    lineLeft, lineRight, y, Math.min(prevAlpha, childAlpha)));
                        }
                    }
                    prevBottom = childBottom;
                    prevAlpha = childAlpha;
                }
            } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------- appearance & reflection

    private void updateAppearanceFromVisibleSlots() {
        appearanceDirty = false;
        TextView sample = null;
        for (int i = 0; i < visibleSlots.size(); i++) {
            sample = findSampleTextView(visibleSlots.get(i).row);
            if (sample != null) break;
        }
        boolean nextDark;
        if (sample != null) {
            appearanceSampledOnce = true;
            appearanceRetryPending = false;
            nextDark = NebulaSummaryVisuals.isDarkSurfaceFromInk(
                    sample.getCurrentTextColor(), isSystemNightMode());
        } else {
            appearanceRetryPending = true;
            nextDark = appearanceSampledOnce ? darkAppearance : isSystemNightMode();
        }
        if (nextDark != darkAppearance) {
            darkAppearance = nextDark;
            geometryChanged = true;
            invalidateFallbackShaders();
            rebuildMergedGeometry();
        }
        if (footerView != null) {
            footerView.setDarkAppearance(darkAppearance);
        }
    }

    static TextView findSampleTextView(View root) {
        TextView[] fallback = new TextView[1];
        TextView preferred = findSampleTextView(root, 0, fallback);
        return preferred != null ? preferred : fallback[0];
    }

    private static TextView findSampleTextView(View view, int depth, TextView[] fallback) {
        if (view == null || depth > 10 || view.getVisibility() != View.VISIBLE
                || view.getAlpha() < 0.05f || isExcludedSampleView(view)) {
            return null;
        }
        if (view instanceof TextView tv) {
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0 && Color.alpha(tv.getCurrentTextColor()) > 40) {
                if (!isCounterLikeText(text)) return tv;
                if (fallback[0] == null) fallback[0] = tv;
            }
            return null;
        }
        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                TextView found = findSampleTextView(group.getChildAt(i), depth + 1, fallback);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isExcludedSampleView(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        Resources res = view.getResources();
        if (res == null) return false;
        String entryName;
        try {
            entryName = res.getResourceEntryName(id);
        } catch (Throwable ignored) {
            return false;
        }
        if (entryName == null) return false;
        return switch (entryName) {
            case "icon_parent", "icon_slot",
                 "fl_group_number", "group_number_tv",
                 "overflow_number", "expand_button_number",
                 "expand_button_pill", "expand_button_container",
                 "number_of_children",
                 "actions", "actions_container", "smart_reply_container" -> true;
            default -> false;
        };
    }

    private static boolean isCounterLikeText(CharSequence text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFFFC' || c == '+' || c == '·' || c == '•') {
                continue;
            }
            if (c >= '0' && c <= '9') continue;
            return false;
        }
        return true;
    }

    private boolean isSystemNightMode() {
        int uiMode = footer.getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void ensureRowMetaLookup(View row) {
        if (rowMetaLookupDone || row == null) return;
        rowMetaLookupDone = true;
        Class<?> cls = row.getClass();
        getRadiusTopMethod = ModuleMain.findMethod(cls, "getCurrentBackgroundRadiusTop");
        getRadiusBottomMethod = ModuleMain.findMethod(cls, "getCurrentBackgroundRadiusBottom");
        isPinnedMethod = ModuleMain.findMethod(cls, "isPinned");
        isGroupExpandedMethod = ModuleMain.findMethod(cls, "isGroupExpanded");
        isGroupExpansionChangingMethod = ModuleMain.findMethod(cls, "isGroupExpansionChanging");
        isSummaryWithChildrenMethod = ModuleMain.findMethod(cls, "isSummaryWithChildren");
        getAttachedChildrenMethod = ModuleMain.findMethod(cls, "getAttachedChildren");
        updateBackgroundForGroupStateMethod = ModuleMain.findMethod(cls, "updateBackgroundForGroupState");
        updateClippingMethod = ModuleMain.findMethod(cls, "updateClipping");
        getExtClipBottomAmountMethod = ModuleMain.findMethod(cls, "getExtClipBottomAmount");
        getChildrenContainerMethod = ModuleMain.findMethod(cls, "getChildrenContainer");
        getPrivateLayoutMethod = ModuleMain.findMethod(cls, "getPrivateLayout");
        getRowInjectorMethod = ModuleMain.findMethod(cls, "getInjector");
        getParentRowMethod = ModuleMain.findMethod(cls, "getParentRow");
        if (getParentRowMethod == null) {
            getParentRowMethod = ModuleMain.findMethod(cls, "getNotificationParent");
        }
        sGetViewSuperTranslationZMethod = ModuleMain.findMethod(cls, "getSuperTranslationZ");
        sSetViewSuperTranslationZMethod = ModuleMain.findMethod(cls, "setSuperTranslationZ", float.class);
        sGetViewSuperTranslationXMethod = ModuleMain.findMethod(cls, "getSuperTranslationX");
        sGetViewSuperScaleXMethod = ModuleMain.findMethod(cls, "getSuperScaleX");
        sGetViewSuperScaleYMethod = ModuleMain.findMethod(cls, "getSuperScaleY");
        sGetExtTranslationZMethod = ModuleMain.findMethod(cls, "getExtTranslationZ");
        if (getRowInjectorMethod != null) {
            try {
                Object injObj = getRowInjectorMethod.invoke(row);
                if (injObj != null) {
                    injectorStackingProgressField = ModuleMain.findField(injObj.getClass(), "stackingProgress");
                }
            } catch (Throwable ignored) {}
        }
        backgroundNormalField = ModuleMain.findField(cls, "mBackgroundNormal");
        sBackgroundNormalField = backgroundNormalField;
        sIsSummaryWithChildrenMethod = isSummaryWithChildrenMethod;
        sGetAttachedChildrenMethod = getAttachedChildrenMethod;
        if (backgroundNormalField != null) {
            Class<?> bgCls = backgroundNormalField.getType();
            sNbvClass = bgCls;
            sSetSuperTranslationYMethod = ModuleMain.findMethod(bgCls, "setSuperTranslationY", float.class);
            sSetSuperTranslationXMethod = ModuleMain.findMethod(bgCls, "setSuperTranslationX", float.class);
            sSetSuperScaleXMethod = ModuleMain.findMethod(bgCls, "setSuperScaleX", float.class);
            sSetSuperScaleYMethod = ModuleMain.findMethod(bgCls, "setSuperScaleY", float.class);
            sSetSuperAlphaMethod = ModuleMain.findMethod(bgCls, "setSuperAlpha", float.class);
            sGetSuperTranslationYMethod = ModuleMain.findMethod(bgCls, "getSuperTranslationY");
            sGetSuperScaleXMethod = ModuleMain.findMethod(bgCls, "getSuperScaleX");
            sGetSuperScaleYMethod = ModuleMain.findMethod(bgCls, "getSuperScaleY");
            nbvClipBottomField = ModuleMain.findField(bgCls, "mClipBottomAmount");
            nbvBottomOverlapField = ModuleMain.findField(bgCls, "mBottomOverlap");
            nbvInjectorField = ModuleMain.findField(bgCls, "mNotificationBackgroundViewInjector");
            if (nbvInjectorField != null) {
                Class<?> injCls = nbvInjectorField.getType();
                nbvUpdateActualHeightMethod = ModuleMain.findMethod(injCls, "updateActualHeight");
                nbvUpdateBackgroundOutlineMethod = ModuleMain.findMethod(
                        injCls, "updateBackgroundOutline", boolean.class, boolean.class);
                nbviClipBottomField = ModuleMain.findField(injCls, "clipBottomAmount");
                nbviExtClipBottomField = ModuleMain.findField(injCls, "extClipBottomAmount");
                nbviBottomOverlapField = ModuleMain.findField(injCls, "bottomOverlap");
                nbviOutlineModeField = ModuleMain.findField(injCls, "outlineMode");
                if (nbviOutlineModeField != null && nbviOutlineModeField.getType().isEnum()) {
                    Object[] constants = nbviOutlineModeField.getType().getEnumConstants();
                    if (constants != null) {
                        for (Object c : constants) {
                            if ("NORMAL".equals(((Enum<?>) c).name())) {
                                nbviOutlineModeNormal = c;
                                break;
                            }
                        }
                    }
                }
            }
        }
        try {
            Class<?> glassCompat = cls.getClassLoader().loadClass("com.miui.systemui.util.MiGlassCompat");
            miGlassSetSdfMaxSizeMethod = ModuleMain.findMethod(
                    glassCompat, "setMiGlassSdfMaxSizeCompat", View.class, float.class, float.class);
        } catch (Throwable ignored) {}
    }

    private boolean isSummaryWithChildrenRow(View row) {
        if (row == null || isSummaryWithChildrenMethod == null) return false;
        try {
            return Boolean.TRUE.equals(isSummaryWithChildrenMethod.invoke(row));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int getRowExtClipBottomAmount(View row) {
        if (row == null || getExtClipBottomAmountMethod == null) return 0;
        try {
            Object val = getExtClipBottomAmountMethod.invoke(row);
            return val instanceof Integer n ? Math.max(0, n) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private float getRowStackingProgress(View row) {
        if (row == null || getRowInjectorMethod == null) return -1f;
        try {
            Object inj = getRowInjectorMethod.invoke(row);
            if (inj == null) return -1f;
            if (injectorStackingProgressField == null) {
                injectorStackingProgressField = ModuleMain.findField(inj.getClass(), "stackingProgress");
            }
            if (injectorStackingProgressField != null) {
                return injectorStackingProgressField.getFloat(inj);
            }
        } catch (Throwable ignored) {}
        return -1f;
    }

    private View getRowBackground(View row) {
        if (row == null || backgroundNormalField == null) return null;
        try {
            Object obj = backgroundNormalField.get(row);
            return obj instanceof View v ? v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isPinnedRow(View row) {
        if (row == null) return false;
        ensureRowMetaLookup(row);
        if (isPinnedMethod == null) return false;
        try {
            return Boolean.TRUE.equals(isPinnedMethod.invoke(row));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isExpandedGroupRow(View row) {
        if (row == null || isSummaryWithChildrenMethod == null || isGroupExpandedMethod == null) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(isSummaryWithChildrenMethod.invoke(row))) return false;
            if (Boolean.TRUE.equals(isGroupExpandedMethod.invoke(row))) return true;
            return isGroupExpansionChangingMethod != null
                    && Boolean.TRUE.equals(isGroupExpansionChangingMethod.invoke(row));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void resolveCardRadii(View row, RowSlot slot) {
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

    // ---------------------------------------------------------------- one-shot shimmer

    private void triggerShimmer() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shimmerProgress = 0f;
            shimmerBoost = 0f;
            if (footerView != null) footerView.setShimmer(0f, 0f);
            return;
        }
        cancelShimmer();
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
        shimmerAnimator.setDuration(SHIMMER_DURATION_MS);
        shimmerAnimator.setInterpolator(SHIMMER_EASE);
        shimmerAnimator.addUpdateListener(anim -> {
            shimmerProgress = (float) anim.getAnimatedValue();
            shimmerBoost = computeShimmerEnvelope(shimmerProgress);
            if (footerView != null) {
                footerView.setShimmer(shimmerProgress, shimmerBoost);
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
                if (footerView != null) footerView.setShimmer(0f, 0f);
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
        if (footerView != null) {
            footerView.setShimmer(0f, 0f);
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

    // ---------------------------------------------------------------- NSSL draw hooks

    boolean isFooterVisible() {
        return alpha > 0 && footerDrawnVisible && mergedVisible;
    }

    float footerOffsetX() {
        return footerOffsetX;
    }

    float footerOffsetY() {
        return footerOffsetY;
    }

    /**
     * Drawn BEFORE {@code NotificationStackScrollLayout} children:
     * renders the silky Gaussian-feathered multi-color outer perimeter halo purely outside
     * the unified merged card (and any individually swiping priority card).
     */
    void drawBackground(Canvas canvas) {
        if (alpha <= 0) return;

        // 1. Unified merged card outer halo (behind front merged card)
        if (mergedVisible && mergedAlpha > 0.02f) {
            drawPerimeterHalo(canvas, mergedBounds, mergedHaloBounds, mergedPath,
                    mergedTopRadius, mergedBotRadius, mergedAlpha, 0f);
        }

        // 2. Individually swiped priority cards (subtle fading halo while dragging out)
        for (int i = 0; i < detachedSlots.size(); i++) {
            RowSlot slot = detachedSlots.get(i);
            if (slot.alpha <= 0.02f) continue;
            haloDrawRect.set(slot.bounds);
            float haloPad = (darkAppearance ? 12.0f : 8.5f) * density + 1f;
            haloDrawRect.inset(-haloPad, -haloPad);
            drawPerimeterHalo(canvas, slot.bounds, haloDrawRect, slot.path,
                    slot.topRadius, slot.botRadius, slot.alpha, (i + 1) * 20f);
        }
    }

    /**
     * Drawn AFTER {@code NotificationStackScrollLayout} children:
     * 1) Refined 0.65dp internal hairline dividers between docked priority items;
     * 2) Crisp thin glass rim highlight (~1.2dp) + synchronized shimmer flares around the
     *    merged card silhouette (and any individually swiped priority card).
     */
    void drawOutline(Canvas canvas) {
        if (alpha <= 0) return;

        // 1. Crisp internal hairline dividers between docked priority rows
        if (mergedVisible && !internalDividers.isEmpty()) {
            int baseColor = darkAppearance ? 0x36FFFFFF : 0x1E000000;
            int baseAlpha = Color.alpha(baseColor);
            int rgb = baseColor & 0x00FFFFFF;
            for (int i = 0; i < internalDividers.size(); i++) {
                DividerLine d = internalDividers.get(i);
                int a = Math.round(baseAlpha * d.alpha * mergedAlpha);
                if (a <= 1) continue;
                dividerPaint.setColor((a << 24) | rgb);
                canvas.drawLine(d.left, d.y, d.right, d.y, dividerPaint);
            }
        }

        // 2. Unified merged card crisp perimeter glass rim (zero interior haze)
        if (mergedVisible && mergedAlpha > 0.02f) {
            drawPerimeterRim(canvas, mergedBounds, mergedRimPath,
                    mergedTopRadius, mergedBotRadius, mergedAlpha, 0f);
        }

        // 3. Individually swiped priority cards (matching rim while detaching)
        for (int i = 0; i < detachedSlots.size(); i++) {
            RowSlot slot = detachedSlots.get(i);
            if (slot.alpha <= 0.02f) continue;
            drawPerimeterRim(canvas, slot.bounds, slot.rimPath,
                    slot.topRadius, slot.botRadius, slot.alpha, (i + 1) * 20f);
        }
    }

    private void drawPerimeterHalo(Canvas canvas, RectF bounds, RectF haloRect, Path clipOutPath,
                                   float topR, float botR, float cardFade, float angleShiftDeg) {
        int saved = canvas.save();
        canvas.clipOutPath(clipOutPath);

        if (perimeterShader != null) {
            float rotRad = (float) Math.toRadians(shimmerProgress * 360f + angleShiftDeg);
            bindPerimeterShader(bounds, topR, botR, rotRad, cardFade, 0f);
            canvas.drawRect(haloRect, agslPaint);
        } else {
            ensureFallbackShaders();
            float angle = REST_ANGLE_DEG + shimmerProgress * 360f + angleShiftDeg;
            positionSweep(fallbackAuraShader, angle, bounds.centerX(), bounds.centerY());
            auraPaint.setShader(fallbackAuraShader);
            float[] alphas = darkAppearance ? FALLBACK_AURA_DARK : FALLBACK_AURA_LIGHT;
            float boostMul = 1f + 0.38f * shimmerBoost;
            for (int pass = 0; pass < FALLBACK_AURA_WIDTHS_DP.length; pass++) {
                int op = Math.min(255, Math.round(alphas[pass] * boostMul * cardFade * 255f));
                if (op <= 0) continue;
                auraPaint.setStrokeWidth(FALLBACK_AURA_WIDTHS_DP[pass] * density);
                auraPaint.setAlpha(op);
                canvas.drawPath(clipOutPath, auraPaint);
            }
            auraPaint.setShader(null);
        }
        canvas.restoreToCount(saved);
    }

    private void drawPerimeterRim(Canvas canvas, RectF bounds, Path rimPath,
                                  float topR, float botR, float cardFade, float angleShiftDeg) {
        if (perimeterShader != null) {
            float rotRad = (float) Math.toRadians(shimmerProgress * 360f + angleShiftDeg);
            bindPerimeterShader(bounds, topR, botR, rotRad, cardFade, 1f);
            outlineDrawRect.set(bounds);
            float pad = 2.8f * density;
            outlineDrawRect.inset(-pad, -pad);
            canvas.drawRect(outlineDrawRect, agslPaint);
        } else {
            ensureFallbackShaders();
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            float angle = REST_ANGLE_DEG + shimmerProgress * 360f + angleShiftDeg;

            positionSweep(fallbackRimShader, angle, cx, cy);
            rimPaint.setShader(fallbackRimShader);
            float rimOp = (darkAppearance ? 0.92f : 0.84f) + 0.08f * shimmerBoost;
            rimPaint.setAlpha(Math.min(255, Math.round(rimOp * cardFade * 255f)));
            canvas.drawPath(rimPath, rimPaint);

            if (shimmerBoost > 0.01f) {
                positionSweep(fallbackCometShader, -110f + shimmerProgress * 360f + angleShiftDeg, cx, cy);
                cometPaint.setShader(fallbackCometShader);
                cometPaint.setAlpha(Math.min(255, Math.round(shimmerBoost * cardFade * 220f)));
                canvas.drawPath(rimPath, cometPaint);
            }
            rimPaint.setShader(null);
            cometPaint.setShader(null);
        }
    }

    private void bindPerimeterShader(RectF bounds, float topR, float botR,
                                     float rotRad, float cardAlpha, float mode) {
        perimeterShader.setFloatUniform(
                "uBounds", bounds.left, bounds.top, bounds.right, bounds.bottom);
        perimeterShader.setFloatUniform("uRadii", topR, botR);
        perimeterShader.setFloatUniform("uDensity", density);
        perimeterShader.setFloatUniform("uAngle", rotRad);
        perimeterShader.setFloatUniform("uShimmer", shimmerBoost);
        perimeterShader.setFloatUniform("uAlpha", cardAlpha);
        perimeterShader.setFloatUniform("uDark", darkAppearance ? 1f : 0f);
        perimeterShader.setFloatUniform("uStyle", xiaoAiStyle ? 1f : 0f);
        perimeterShader.setFloatUniform("uMode", mode);
        agslPaint.setAlpha(255);
    }

    private void invalidateFallbackShaders() {
        fallbackRimShader = null;
        fallbackAuraShader = null;
        fallbackCometShader = null;
    }

    private void ensureFallbackShaders() {
        if (fallbackRimShader != null) return;
        fallbackRimShader = new SweepGradient(0f, 0f,
                xiaoAiStyle
                        ? (darkAppearance ? XIAOAI_DARK_RIM : XIAOAI_LIGHT_RIM)
                        : (darkAppearance ? NEBULA_DARK_RIM : NEBULA_LIGHT_RIM),
                SWEEP_POSITIONS);
        fallbackAuraShader = new SweepGradient(0f, 0f,
                xiaoAiStyle
                        ? (darkAppearance ? XIAOAI_DARK_AURA : XIAOAI_LIGHT_AURA)
                        : (darkAppearance ? NEBULA_DARK_AURA : NEBULA_LIGHT_AURA),
                SWEEP_POSITIONS);
        fallbackCometShader = new SweepGradient(0f, 0f,
                xiaoAiStyle ? XIAOAI_COMET : NEBULA_COMET,
                COMET_POSITIONS);
    }

    private void positionSweep(SweepGradient shader, float degrees, float centerX, float centerY) {
        shaderMatrix.setRotate(degrees);
        shaderMatrix.postTranslate(centerX, centerY);
        shader.setLocalMatrix(shaderMatrix);
    }

    // ---------------------------------------------------------------- pool & squircle geometry

    private RowSlot acquireSlot() {
        if (!slotPool.isEmpty()) {
            return slotPool.remove(slotPool.size() - 1);
        }
        return new RowSlot();
    }

    private void releaseSlots() {
        trimSlots(0);
        dockedSlots.clear();
        detachedSlots.clear();
        stackedSlots.clear();
    }

    private void trimSlots(int count) {
        for (int i = visibleSlots.size() - 1; i >= count; i--) {
            RowSlot slot = visibleSlots.remove(i);
            slot.row = null;
            if (slotPool.size() < 8) {
                slotPool.add(slot);
            }
        }
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

    private record DividerLine(float left, float right, float y, float alpha) {}

    private static final class RowSlot {
        View row;
        final RectF bounds = new RectF();
        final RectF rimBounds = new RectF();
        final Path path = new Path();
        final Path rimPath = new Path();
        float alpha;
        float topRadius;
        float botRadius;
        float translationX;
        float scaleX = 1f;
        float scaleY = 1f;
        boolean swiping;
        boolean stackedBehind;
        boolean expandedGroup;

        void rebuildGeometry(float density) {
            buildSquirclePath(bounds, topRadius, botRadius, path);
            float inset = 0.45f * density;
            rimBounds.set(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset);
            buildSquirclePath(rimBounds,
                    Math.max(0f, topRadius - inset),
                    Math.max(0f, botRadius - inset),
                    rimPath);
        }
    }
}
