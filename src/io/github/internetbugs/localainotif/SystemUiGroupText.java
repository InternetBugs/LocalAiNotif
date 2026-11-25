package io.github.internetbugs.localainotif;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import static io.github.internetbugs.localainotif.SystemUiPrioritySection.requireField;
import static io.github.internetbugs.localainotif.SystemUiPrioritySection.requireMethod;

/**
 * Renders the Nebula Aurora AI summary presentation over collapsed notification group slots inside
 * NotificationChildrenContainer, dynamically adapting the collapsed card height to fit the full
 * summary text without truncation while keeping native card surfaces, icons, clipping, and
 * priority frames seamlessly aligned:
 * <ul>
 *   <li><b>Thinking Scanlight</b>: While local LLM inference is actively running ({@code inFlight}),
 *       original single-line notifications remain 100% readable while a softly feathered, 14-degree
 *       tilted Nebula Aurora scanlight wave glides over the text slot (after a 250ms debounce).</li>
 *   <li><b>Wave Wash Reveal (~720ms)</b>: When a new or updated summary arrives, a soft Nebula
 *       light wave sweeps across the text zone—smoothly dissolving the raw lines underneath while
 *       illuminating and uncovering the new AI summary behind the wavefront.</li>
 *   <li><b>Frameless Nebula Prefix &amp; Cool-Tinted Ink</b>: Inline self-drawn dual 4-point
 *       Nebula sparkles + gradient SemiBold "AI 摘要" + 0.8dp fading vertical hairline, followed
 *       by ~16% Nebula-tinted high-contrast body text auto-calibrated for dark/lockscreen vs light
 *       shade cards.</li>
 *   <li><b>Revisit Micro-Glint (~550ms)</b>: When an already-revealed summary re-enters visibility
 *       after screen-off or shade close, a subtle sheen sweeps across the inline emblem only.</li>
 *   <li><b>Expand/Collapse &amp; Drag Sync</b>: Soft scale-recede (1.0 -> 0.96) + fade on expand,
 *       tactile badge glint (~350ms) on collapse return, and 1:1 finger-tracked scale &amp; sparkle
 *       luminescence during drag gestures.</li>
 * </ul>
 *
 * 本机核对(OS 4.0.3.0 / Android 17,2026-11-23):全部反射符号命中;
 * ExpandableNotificationRow.setIsSummaryWithChildren 在本机不存在(hookRow 循环按名过滤,天然跳过);
 * NotificationEntry.mSbn 实际类型为 MIUI ExpandedNotification,继承 StatusBarNotification,cast 安全。
 */
final class SystemUiGroupText {
    private static final String TAG = "LocalAiNotif";
    static final int MAX_ADAPTIVE_LINES = 4;
    private static final Pattern GROUP_PREFIX_PATTERN =
            Pattern.compile("^.+?\\s+\\d+\\s*条(?:新)?通知\\s*[:：]\\s*");
    private static final int MAX_REVEALED_CACHE = 64;
    private static final long REVISIT_HIDDEN_DEBOUNCE_MS = 450L;
    private static final long REVISIT_GLINT_COOLDOWN_MS = 1200L;
    private static final float EXPAND_MIN_SCALE = 0.96f;

    private final ModuleMain module;
    private final Class<?> rowClass;
    private final Method getEntry;
    private final Method getChildren;
    private final Method getParent;
    private final Method getChildrenContainer;
    private final Method getSingleLine;
    private final Method shouldShowPublic;
    private final Method isGroupExpansionChanging;
    private final Method notifyHeightChanged;
    private final Method setMeasuredDimensionMethod;
    private final Method showingAsLowPriority;
    private final Method isBundle;
    private final Method getCollapsedHeaderMargin;
    private final Method getMaxAllowedVisibleChildren;
    private final Method getHybridTextView;
    private final Method getHybridTitleView;
    private final Method showMiuiStyle;
    private final Field sbnField;
    private final Field showingPublic;
    private final Field containingRow;
    private final Field attachedChildrenField;
    private final Field childrenExpandedField;
    private final Field userSwipingField;
    private final Field childPaddingField;
    private final Field headerTranslationField;
    private final Field minSingleLineHeightField;
    private final Field collapsedBottomPaddingField;
    private final Field realHeightField;
    private final Field overflowNumberField;
    private final Field injectorField;
    private final Field overflowMarginEndField;
    private final Field miuiAppIconSizeField;
    private final TimeInterpolator expandInterpolator;
    private final TimeInterpolator collapseInterpolator;

    private final WeakHashMap<ViewGroup, WeakReference<OverlayState>> overlays = new WeakHashMap<>();
    private final Set<View> hiddenHybrids = Collections.newSetFromMap(new WeakHashMap<>());
    private final Runnable glowStyleListener = this::onGlowStyleChanged;
    private final Map<String, String> revealedSummaries = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_REVEALED_CACHE;
        }
    };

    private enum Mode {
        NONE,
        THINKING,
        SUMMARY
    }

    private record GroupState(Mode mode, String groupKey, String versionFp, String summaryText) {
        static final GroupState NONE = new GroupState(Mode.NONE, null, null, null);
    }

    static void install(ModuleMain module, ClassLoader loader) {
        try {
            new SystemUiGroupText(module, loader);
            module.log(Log.INFO, TAG, "Hooked merged group summary view (Nebula Aurora)");
        } catch (Exception e) {
            module.log(Log.ERROR, TAG, "Failed to hook merged group summary view", e);
        }
    }

    private SystemUiGroupText(ModuleMain module, ClassLoader loader) throws Exception {
        this.module = module;
        rowClass = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        Class<?> containerClass = loader.loadClass(
                "com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer");
        Class<?> hybridClass = loader.loadClass(
                "com.android.systemui.statusbar.notification.row.HybridNotificationView");
        Class<?> entryClass = loader.loadClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry");

        getEntry = requireMethod(rowClass, "getEntry");
        getChildren = requireMethod(rowClass, "getAttachedChildren");
        getParent = requireMethod(rowClass, "getNotificationParent");
        getChildrenContainer = requireMethod(rowClass, "getChildrenContainer");
        getSingleLine = requireMethod(rowClass, "getSingleLineView");
        shouldShowPublic = requireMethod(rowClass, "shouldShowPublic");
        showingPublic = ModuleMain.findField(rowClass, "mShowingPublic");
        isGroupExpansionChanging = ModuleMain.findMethod(rowClass, "isGroupExpansionChanging");
        notifyHeightChanged = ModuleMain.findMethod(rowClass, "notifyHeightChanged", String.class, boolean.class);
        setMeasuredDimensionMethod = ModuleMain.findMethod(View.class, "setMeasuredDimension", int.class, int.class);
        sbnField = requireField(entryClass, "mSbn");

        containingRow = requireField(containerClass, "mContainingNotification");
        attachedChildrenField = ModuleMain.findField(containerClass, "mAttachedChildren");
        childrenExpandedField = ModuleMain.findField(containerClass, "mChildrenExpanded");
        userSwipingField = ModuleMain.findField(containerClass, "mIsUserSwipingToExpandRow");
        childPaddingField = requireField(containerClass, "mChildPadding");
        headerTranslationField = requireField(containerClass, "mCurrentHeaderTranslation");
        minSingleLineHeightField = requireField(containerClass, "mMinSingleLineHeight");
        collapsedBottomPaddingField = ModuleMain.findField(containerClass, "mCollapsedBottomPadding");
        realHeightField = ModuleMain.findField(containerClass, "mRealHeight");
        overflowNumberField = requireField(containerClass, "mOverflowNumber");
        injectorField = ModuleMain.findField(containerClass, "mInjector");
        Field marginEnd = null;
        Field appIconSize = null;
        if (injectorField != null) {
            Class<?> injectorType = injectorField.getType();
            marginEnd = ModuleMain.findField(injectorType, "mOverflowNumberMarginEnd");
            appIconSize = ModuleMain.findField(injectorType, "mMiuiAppIconSize");
        }
        overflowMarginEndField = marginEnd;
        miuiAppIconSizeField = appIconSize;

        showingAsLowPriority = requireMethod(containerClass, "showingAsLowPriority");
        isBundle = ModuleMain.findMethod(containerClass, "isBundle");
        getCollapsedHeaderMargin = requireMethod(containerClass, "getCollapsedHeaderMargin");
        getMaxAllowedVisibleChildren = requireMethod(
                containerClass, "getMaxAllowedVisibleChildren", boolean.class);

        getHybridTextView = requireMethod(hybridClass, "getTextView");
        getHybridTitleView = requireMethod(hybridClass, "getTitleView");

        Method miuiStyleMethod = null;
        try {
            Class<?> helper = loader.loadClass("com.miui.systemui.notification.NotificationSettingsHelper");
            miuiStyleMethod = ModuleMain.findMethod(helper, "showMiuiStyle");
        } catch (Throwable ignored) {}
        showMiuiStyle = miuiStyleMethod;

        TimeInterpolator expInterp = new DecelerateInterpolator();
        TimeInterpolator colInterp = new AccelerateInterpolator();
        try {
            Class<?> animExt = loader.loadClass(
                    "com.android.systemui.statusbar.notification.stack.MiuiNotificationAnimationExtensionsKt");
            Field expField = ModuleMain.findField(animExt, "GROUP_ELEMENT_INTERPOLATOR");
            Field colField = ModuleMain.findField(animExt, "GROUP_ELEMENT_COLLAPSE_INTERPOLATOR");
            if (expField != null && expField.get(null) instanceof TimeInterpolator ti) expInterp = ti;
            if (colField != null && colField.get(null) instanceof TimeInterpolator ti) colInterp = ti;
        } catch (Throwable ignored) {}
        expandInterpolator = expInterp;
        collapseInterpolator = colInterp;

        hookHybridView(hybridClass);
        hookContainer(containerClass);
        hookContainerHeight(containerClass);
        hookRow(rowClass);
        GlowStyle.addListener(glowStyleListener);
    }

    private void onGlowStyleChanged() {
        boolean xiaoAi = GlowStyle.isXiaoAi();
        List<ViewGroup> snapshot = new ArrayList<>(overlays.keySet());
        for (ViewGroup container : snapshot) {
            OverlayState state = overlayFor(container);
            if (state == null) continue;
            state.view.setGlowStyle(xiaoAi);
            if (state.mode == Mode.NONE) continue;
            try {
                Object parent = containingRow.get(container);
                if (parent != null) {
                    update(parent);
                    if (state.mode == Mode.SUMMARY && container.isShown()
                            && state.view.getVisibility() == View.VISIBLE && !state.view.isWashAnimating()) {
                        state.view.startBadgeGlint(550L, 0.78f);
                    }
                }
            } catch (Throwable e) {
                module.log(Log.WARN, TAG, "Failed to live-refresh group summary glow style", e);
            }
        }
    }

    private void hookHybridView(Class<?> hybridClass) {
        for (Method method : hybridClass.getDeclaredMethods()) {
            String name = method.getName();
            Class<?>[] params = method.getParameterTypes();
            if (!name.equals("bind") && !name.equals("setVisible")
                    && !name.equals("transformTo") && !name.equals("transformFrom")) continue;
            method.setAccessible(true);
            boolean dragTransform = (name.equals("transformTo") || name.equals("transformFrom"))
                    && params.length == 2 && params[0] == float.class;
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    View hybrid = (View) chain.getThisObject();
                    Object row = findRow(hybrid);
                    if (row == null) return result;
                    Object parent = getParent.invoke(row);
                    if (parent == null) return result;
                    if (name.equals("bind")) {
                        update(parent);
                        return result;
                    }
                    List<?> children = (List<?>) getChildren.invoke(parent);
                    if (children == null || children.isEmpty() || children.get(0) != row) return result;
                    ViewGroup container = (ViewGroup) getChildrenContainer.invoke(parent);
                    OverlayState state = overlayFor(container);
                    if (state == null || state.mode == Mode.NONE) return result;

                    if (name.equals("setVisible")) {
                        boolean visible = (Boolean) chain.getArg(0);
                        cancelExpandAnimation(state);
                        resetRestTransform(state);
                        state.view.setAlpha(visible ? 1f : 0f);
                        state.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                        if (!visible) {
                            state.view.cancelTransientVisuals();
                        }
                    } else if (dragTransform) {
                        syncDrag(state, hybrid);
                    } else if (name.equals("transformTo")) {
                        startExpandFade(state, false);
                    } else if (name.equals("transformFrom")) {
                        startExpandFade(state, true);
                    }
                } catch (Exception e) {
                    module.log(Log.ERROR, TAG, "Failed to sync hybrid transition", e);
                }
                return result;
            });
        }
    }

    private void hookContainer(Class<?> containerClass) {
        for (Method method : containerClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("onMeasure") && !name.equals("onLayout")
                    && !name.equals("updateState") && !name.equals("onNotificationUpdated")
                    && !name.equals("updateChildrenAppearance") && !name.equals("removeNotification")
                    && !name.equals("onConfigurationChanged")) continue;
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                if (name.equals("onMeasure")) {
                    try {
                        ViewGroup container = (ViewGroup) chain.getThisObject();
                        OverlayState preState = overlayFor(container);
                        if (preState != null && preState.mode == Mode.SUMMARY
                                && preState.view.getVisibility() != View.GONE) {
                            int preWidth = View.MeasureSpec.getSize((Integer) chain.getArg(0));
                            if (preWidth > 0 && (preState.adaptiveHeight <= 0
                                    || preState.measuredWidth != preWidth
                                    || preState.measuredTextVersion != preState.textVersion)) {
                                measureOverlay(container, preState, preWidth, false, true);
                            }
                        }
                    } catch (Exception e) {
                        module.log(Log.ERROR, TAG, "Failed pre-measuring adaptive summary height", e);
                    }
                }
                Object result = chain.proceed();
                try {
                    ViewGroup container = (ViewGroup) chain.getThisObject();
                    switch (name) {
                        case "onMeasure" -> {
                            OverlayState state = overlayFor(container);
                            if (state != null && state.view.getVisibility() != View.GONE) {
                                int widthSpec = (Integer) chain.getArg(0);
                                int heightSpec = (Integer) chain.getArg(1);
                                int width = View.MeasureSpec.getSize(widthSpec);
                                measureOverlay(container, state, width, false, true);
                                ensureContainerMeasuredHeight(container, state, heightSpec);
                            }
                        }
                        case "onLayout" -> {
                            OverlayState state = overlayFor(container);
                            if (state != null && state.view.getVisibility() != View.GONE) {
                                int width = (Integer) chain.getArg(3) - (Integer) chain.getArg(1);
                                measureOverlay(container, state, width, true, true);
                            }
                        }
                        case "removeNotification" -> {
                            Object removed = chain.getArg(0);
                            if (removed != null) restoreSingleLine((View) getSingleLine.invoke(removed));
                            update(containingRow.get(container));
                        }
                        default -> update(containingRow.get(container));
                    }
                } catch (Exception e) {
                    module.log(Log.ERROR, TAG, "Failed in container hook: " + name, e);
                }
                return result;
            });
        }
    }

    private void hookContainerHeight(Class<?> containerClass) {
        for (Method method : containerClass.getDeclaredMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 0) continue;
            boolean intrinsic = name.equals("getIntrinsicHeight");
            boolean collapsedNoHeader = name.equals("getCollapsedHeightWithoutHeader");
            if (!intrinsic && !collapsedNoHeader
                    && !name.equals("getCollapsedHeight")
                    && !name.equals("getMinHeight")) continue;
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                ViewGroup container = (ViewGroup) chain.getThisObject();
                OverlayState state = overlayFor(container);
                if (state == null || state.mode != Mode.SUMMARY || state.adaptiveHeight <= 0) {
                    return chain.proceed();
                }
                try {
                    if (intrinsic) {
                        if (childrenExpandedField != null && childrenExpandedField.getBoolean(container)) {
                            return chain.proceed();
                        }
                        if (userSwipingField != null && userSwipingField.getBoolean(container)) {
                            return chain.proceed();
                        }
                    }
                    if (!canUseAdaptiveCollapsedHeight(container)) {
                        return chain.proceed();
                    }
                    if (collapsedNoHeader) {
                        return Math.max(1, state.adaptiveHeight - headerTranslationField.getInt(container));
                    }
                    return state.adaptiveHeight;
                } catch (Exception e) {
                    module.log(Log.ERROR, TAG, "Failed in adaptive height hook: " + name, e);
                    return chain.proceed();
                }
            });
        }
    }

    private boolean canUseAdaptiveCollapsedHeight(ViewGroup container) throws Exception {
        if ((Boolean) showingAsLowPriority.invoke(container)) return false;
        if (isBundle != null && (Boolean) isBundle.invoke(container)) return false;
        if (attachedChildrenField != null
                && attachedChildrenField.get(container) instanceof List<?> list && list.size() < 2) {
            return false;
        }
        Object parent = containingRow.get(container);
        return parent != null && !needsPublicView(parent);
    }

    private void hookRow(Class<?> rowClass) {
        for (Method method : rowClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("addChildNotification") && !name.equals("onNotificationUpdated")
                    && !name.equals("setHideSensitive") && !name.equals("setHideSensitiveForIntrinsicHeight")
                    && !name.equals("setSensitive")
                    && !name.equals("setIsSummaryWithChildren")) continue;
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object row = chain.getThisObject();
                    Object parent = getParent.invoke(row);
                    update(parent == null ? row : parent);
                } catch (Exception e) {
                    module.log(Log.ERROR, TAG, "Failed to refresh group summary row state", e);
                }
                return result;
            });
        }
    }

    private Object findRow(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (rowClass.isInstance(parent)) return parent;
            parent = parent.getParent();
        }
        return null;
    }

    private StatusBarNotification sbn(Object row) throws Exception {
        Object entry = getEntry.invoke(row);
        return entry == null ? null : (StatusBarNotification) sbnField.get(entry);
    }

    private int maxCollapsedSlots(ViewGroup container) throws Exception {
        return Math.max(2, (Integer) getMaxAllowedVisibleChildren.invoke(container, true));
    }

    private boolean needsPublicView(Object row) throws ReflectiveOperationException {
        return (Boolean) shouldShowPublic.invoke(row)
                || (showingPublic != null && showingPublic.getBoolean(row));
    }

    private boolean needsPublicView(Object parent, List<?> children) throws ReflectiveOperationException {
        if (needsPublicView(parent)) return true;
        for (Object child : children) {
            if (needsPublicView(child)) return true;
        }
        return false;
    }

    private GroupState resolveState(ViewGroup container, Object parent, List<?> children) throws Exception {
        if (container == null || parent == null || children == null || children.size() < 2) {
            return GroupState.NONE;
        }
        if ((Boolean) showingAsLowPriority.invoke(container)) return GroupState.NONE;
        if (isBundle != null && (Boolean) isBundle.invoke(container)) return GroupState.NONE;
        StatusBarNotification summary = sbn(parent);
        if (summary == null) return GroupState.NONE;

        Bundle extras = summary.getNotification().extras;
        String summaryText = extras.getString(GroupSummaryData.SUMMARY);
        String summaryFp = extras.getString(GroupSummaryData.FINGERPRINT);
        String thinkingFp = extras.getString(GroupSummaryData.SUMMARIZING_FP);
        boolean hasSummaryCandidate = summaryText != null && !summaryText.isBlank() && summaryFp != null;
        boolean hasThinkingCandidate = thinkingFp != null && !thinkingFp.isBlank();
        if (!hasSummaryCandidate && !hasThinkingCandidate) return GroupState.NONE;
        if (needsPublicView(parent)) return GroupState.NONE;

        boolean priorityGroup = PriorityNotification.isGroupSummary(summary);
        OverlayState overlay = overlayFor(container);
        ArrayList<StatusBarNotification> active = overlay == null
                ? new ArrayList<>(children.size()) : overlay.versionChildren;
        if (active.size() != children.size()) {
            active.clear();
            if (overlay != null) overlay.versionFingerprint = null;
        }
        for (int index = 0; index < children.size(); index++) {
            Object child = children.get(index);
            if (needsPublicView(child)) return GroupState.NONE;
            StatusBarNotification notification = sbn(child);
            if (notification == null || !summary.getGroupKey().equals(notification.getGroupKey())) {
                return GroupState.NONE;
            }
            if (priorityGroup && !PriorityNotification.isMarked(notification)) return GroupState.NONE;
            if (index == active.size()) {
                active.add(notification);
            } else if (active.get(index) != notification) {
                active.set(index, notification);
                overlay.versionFingerprint = null;
            }
        }
        String currentFp = overlay != null ? overlay.versionFingerprint : null;
        if (currentFp == null) {
            currentFp = GroupSummaryData.versionFingerprint(active);
            if (overlay != null) overlay.versionFingerprint = currentFp;
        }
        if (hasSummaryCandidate && currentFp.equals(summaryFp)) {
            return new GroupState(Mode.SUMMARY, summary.getGroupKey(), currentFp, summaryText);
        }
        if (hasThinkingCandidate && currentFp.equals(thinkingFp)) {
            return new GroupState(Mode.THINKING, summary.getGroupKey(), currentFp, null);
        }
        return GroupState.NONE;
    }

    private void update(Object parent) throws Exception {
        if (parent == null) return;
        ViewGroup container = (ViewGroup) getChildrenContainer.invoke(parent);
        List<?> children = (List<?>) getChildren.invoke(parent);
        if (container == null) {
            if (children != null) {
                for (Object child : children) restoreSingleLine((View) getSingleLine.invoke(child));
            }
            return;
        }

        GroupState resolved = resolveState(container, parent, children);
        if (resolved.mode == Mode.NONE) {
            clearOverlay(container, parent, overlayFor(container), children, false);
            return;
        }

        View hybrid0 = (View) getSingleLine.invoke(children.get(0));
        if (hybrid0 == null) return;

        int slots = Math.min(children.size(), maxCollapsedSlots(container));
        OverlayState state = ensureOverlay(container);
        state.mode = resolved.mode;
        state.activeSlots = slots;

        if (resolved.mode == Mode.THINKING) {
            // Keep original single-line notifications completely readable underneath the scan wave.
            for (int i = 0; i < children.size(); i++) {
                restoreSingleLine((View) getSingleLine.invoke(children.get(i)));
            }
            state.rawText = null;
            if (state.view.length() != 0) state.view.setText(null);
            if (state.view.getContentDescription() != null) state.view.setContentDescription(null);
            syncAdaptiveHeight(container, parent, state, 0, false);

            TextView refText = (TextView) getHybridTextView.invoke(hybrid0);
            boolean darkSurface = detectDarkSurface(container, refText);
            boolean visible = hybrid0.getVisibility() == View.VISIBLE;
            state.view.setGlowStyle(GlowStyle.isXiaoAi());
            state.view.enterThinkingMode(darkSurface, container.isShown() && visible);

            if (!state.animating) {
                resetRestTransform(state);
                state.view.setAlpha(visible ? 1f : 0f);
                state.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            }
            layoutOrRequest(container, state);
            return;
        }

        // Mode.SUMMARY
        String revealToken = resolved.versionFp + "#" + resolved.summaryText.hashCode();
        boolean isFreshReveal = !revealToken.equals(revealedSummaries.get(resolved.groupKey));
        revealedSummaries.put(resolved.groupKey, revealToken);

        boolean canAnimateWash = isFreshReveal
                && container.isShown()
                && hybrid0.getVisibility() == View.VISIBLE
                && ValueAnimator.areAnimatorsEnabled();

        syncStyleAndText(container, state, hybrid0, resolved.summaryText, MAX_ADAPTIVE_LINES);

        if (canAnimateWash) {
            state.view.setWashProgressListener(p -> applyHybridWashAlpha(container, p));
            state.view.startWaveWashReveal();
            applyHybridWashAlpha(container, state.view.getWashProgress());
        } else if (state.view.isWashAnimating()) {
            applyHybridWashAlpha(container, state.view.getWashProgress());
        } else {
            if (isFreshReveal && !container.isShown()) {
                state.wasVisibleOnScreen = false;
                state.lastHiddenUptimeMs = SystemClock.uptimeMillis() - REVISIT_HIDDEN_DEBOUNCE_MS;
            }
            state.view.enterSteadySummaryMode();
            for (int i = 0; i < children.size(); i++) {
                View hybrid = (View) getSingleLine.invoke(children.get(i));
                if (i < slots) hideSingleLine(hybrid);
                else restoreSingleLine(hybrid);
            }
        }

        if (!state.animating) {
            boolean show = hybrid0.getVisibility() == View.VISIBLE;
            resetRestTransform(state);
            state.view.setAlpha(show ? 1f : 0f);
            state.view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }

        layoutOrRequest(container, state);
    }

    private void layoutOrRequest(ViewGroup container, OverlayState state) throws Exception {
        if (container.getWidth() > 0) {
            measureOverlay(container, state, container.getWidth(), true, false);
            return;
        }
        if (state.mode == Mode.SUMMARY) {
            int fallbackWidth = resolveFallbackWidth(container, containingRow.get(container));
            if (fallbackWidth > 0) {
                measureOverlay(container, state, fallbackWidth, false, false);
            }
        }
        container.requestLayout();
    }

    private static int resolveFallbackWidth(ViewGroup container, Object parent) {
        int w = container.getMeasuredWidth();
        if (w > 0) return w;
        if (parent instanceof View parentView) {
            w = parentView.getWidth();
            if (w > 0) return w;
            w = parentView.getMeasuredWidth();
            if (w > 0) return w;
            if (parentView.getParent() instanceof View stackView) {
                int stackW = stackView.getWidth()
                        - stackView.getPaddingLeft() - stackView.getPaddingRight();
                if (stackW > 0) return stackW;
            }
        }
        return 0;
    }

    /**
     * Computes {@code child}'s top offset inside {@code ancestor} using unscaled local view
     * hierarchy coordinates (immune to parent row {@code scaleY} / {@code extScaleY} during
     * HyperOS panel pull-down animations).
     */
    private static int localTopInAncestor(View child, ViewGroup ancestor) {
        if (child == null || ancestor == null) return -1;
        float y = 0f;
        View current = child;
        while (current != null && current != ancestor) {
            y += current.getTop() + current.getTranslationY();
            ViewParent vp = current.getParent();
            if (!(vp instanceof View parentView)) return -1;
            current = parentView;
        }
        return current == ancestor ? Math.round(y) : -1;
    }

    private void applyHybridWashAlpha(ViewGroup container, float washProgress) {
        try {
            Object parent = containingRow.get(container);
            if (parent == null) return;
            List<?> children = (List<?>) getChildren.invoke(parent);
            if (children == null || children.isEmpty()) return;
            OverlayState state = overlayFor(container);
            int slots = state != null && state.activeSlots > 0
                    ? Math.min(children.size(), state.activeSlots)
                    : Math.min(children.size(), maxCollapsedSlots(container));

            // Raw lines smoothly dissolve away during the first 44% of the Wave Wash.
            float rawAlpha = washProgress >= 0.44f
                    ? 0f
                    : Math.max(0f, Math.min(1f, 1f - washProgress / 0.44f));
            for (int i = 0; i < children.size(); i++) {
                View hybrid = (View) getSingleLine.invoke(children.get(i));
                if (hybrid == null) continue;
                if (i < slots) {
                    hiddenHybrids.add(hybrid);
                    if (Math.abs(hybrid.getAlpha() - rawAlpha) > 0.005f) {
                        hybrid.setAlpha(rawAlpha);
                    }
                    if (hybrid.getImportantForAccessibility()
                            != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                        hybrid.setImportantForAccessibility(
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    }
                } else {
                    restoreSingleLine(hybrid);
                }
            }
        } catch (Exception e) {
            module.log(Log.ERROR, TAG, "Failed to update hybrid alpha during wave wash", e);
        }
    }

    private void clearOverlay(ViewGroup container, Object parent, OverlayState state,
            List<?> children, boolean deferHeightNotify) throws ReflectiveOperationException {
        if (state != null && (state.mode != Mode.NONE || state.rawText != null || state.adaptiveHeight != 0)) {
            cancelExpandAnimation(state);
            state.mode = Mode.NONE;
            state.rawText = null;
            state.activeSlots = 0;
            state.measuredWidth = 0;
            state.measuredTextHeight = 0;
            state.wasVisibleOnScreen = false;
            state.view.resetAllVisuals();
            resetRestTransform(state);
            state.view.setAlpha(0f);
            state.view.setVisibility(View.GONE);
            state.view.setText(null);
            state.view.setContentDescription(null);
            state.versionChildren.clear();
            state.versionFingerprint = null;
            syncAdaptiveHeight(container, parent, state, 0, deferHeightNotify);
        }
        if (children != null && !hiddenHybrids.isEmpty()) {
            for (Object child : children) restoreSingleLine((View) getSingleLine.invoke(child));
        }
    }

    private OverlayState overlayFor(ViewGroup container) {
        WeakReference<OverlayState> reference = overlays.get(container);
        OverlayState state = reference == null ? null : reference.get();
        return state != null && state.view.getParent() == container ? state : null;
    }

    private OverlayState ensureOverlay(ViewGroup container) {
        GlowStyle.initInSystemUi(container.getContext());
        OverlayState state = overlayFor(container);
        if (state != null) return state;
        NebulaSummaryView view = new NebulaSummaryView(container.getContext());
        view.setClickable(false);
        view.setLongClickable(false);
        view.setFocusable(false);
        view.setSingleLine(false);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        float density = container.getResources().getDisplayMetrics().density;
        view.setLineSpacing(Math.round(2.4f * density), 1f);
        view.setVisibility(View.GONE);
        container.addView(view);
        overlays.put(container, new WeakReference<>(view.state));
        return view.state;
    }

    private void beforeOverlayDraw(ViewGroup container, OverlayState state) {
        if (state.mode == Mode.NONE) return;
        Object parent = null;
        List<?> children = null;
        boolean canDraw = false;
        try {
            parent = containingRow.get(container);
            if (parent != null) {
                children = (List<?>) getChildren.invoke(parent);
                canDraw = children != null && children.size() >= 2 && !needsPublicView(parent, children);
            }
        } catch (Exception e) {
            module.log(Log.ERROR, TAG, "Failed to check group summary privacy on pre-draw", e);
        }
        try {
            if (!canDraw) {
                clearOverlay(container, parent, state, children, true);
            } else if (state.view.getVisibility() != View.GONE && container.getWidth() > 0) {
                measureOverlay(container, state, container.getWidth(), true, true);
                syncRevisitVisibility(container, state);
            }
        } catch (Exception e) {
            module.log(Log.ERROR, TAG, "Failed to update group summary on pre-draw", e);
        }
    }

    private void syncRevisitVisibility(ViewGroup container, OverlayState state) {
        boolean visibleNow = container.isShown()
                && state.view.getVisibility() == View.VISIBLE
                && state.view.getAlpha() >= 0.25f;
        if (state.mode == Mode.THINKING) {
            state.view.setThinkingVisible(visibleNow);
            state.wasVisibleOnScreen = visibleNow;
            return;
        }
        if (state.mode != Mode.SUMMARY) return;

        if (!visibleNow) {
            if (state.wasVisibleOnScreen) {
                state.wasVisibleOnScreen = false;
                state.lastHiddenUptimeMs = SystemClock.uptimeMillis();
            }
            if (!container.isShown()) {
                state.view.cancelTransientVisuals();
            }
            return;
        }

        if (!state.wasVisibleOnScreen) {
            state.wasVisibleOnScreen = true;
            long now = SystemClock.uptimeMillis();
            boolean wasHiddenLongEnough = state.lastHiddenUptimeMs > 0L
                    && (now - state.lastHiddenUptimeMs) >= REVISIT_HIDDEN_DEBOUNCE_MS;
            boolean outsideCooldown = (now - state.lastRevisitGlintMs) >= REVISIT_GLINT_COOLDOWN_MS;
            if (wasHiddenLongEnough && outsideCooldown
                    && !state.animating && !state.view.isWashAnimating()) {
                state.lastRevisitGlintMs = now;
                state.view.startBadgeGlint(550L, 0.72f);
            }
        }
    }

    private boolean detectDarkSurface(ViewGroup container, TextView refText) {
        Configuration cfg = container.getResources().getConfiguration();
        boolean fallbackNight = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (refText == null) return fallbackNight;
        return NebulaSummaryVisuals.isDarkSurfaceFromInk(refText.getCurrentTextColor(), fallbackNight);
    }

    private void syncStyleAndText(ViewGroup container, OverlayState state, View hybrid0,
            String summaryText, int maxLines) throws Exception {
        TextView refText = (TextView) getHybridTextView.invoke(hybrid0);
        boolean darkSurface = detectDarkSurface(container, refText);
        boolean xiaoAi = GlowStyle.isXiaoAi();
        int baseInk = refText != null ? refText.getCurrentTextColor()
                : (darkSurface ? 0xD9FFFFFF : 0xCC000000);
        int tintedInk = NebulaSummaryVisuals.tintSummaryBodyColor(baseInk, darkSurface, xiaoAi);

        boolean layoutSpecChanged = false;
        if (refText != null) {
            if (state.view.getTextSize() != refText.getTextSize()) {
                state.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, refText.getTextSize());
                layoutSpecChanged = true;
            }
            if (state.view.getIncludeFontPadding() != refText.getIncludeFontPadding()) {
                state.view.setIncludeFontPadding(refText.getIncludeFontPadding());
                layoutSpecChanged = true;
            }
            Typeface tf = refText.getTypeface();
            Typeface upright = tf != null && tf.isItalic() ? Typeface.create(tf, Typeface.NORMAL) : tf;
            if (!Objects.equals(state.view.getTypeface(), upright)) {
                state.view.setTypeface(upright);
                layoutSpecChanged = true;
            }
        }
        if (state.view.getCurrentTextColor() != tintedInk) {
            state.view.setTextColor(tintedInk);
        }
        float density = container.getResources().getDisplayMetrics().density;
        state.view.setLineSpacing(Math.round(3.0f * density), 1f);
        if (state.view.getMaxLines() != maxLines) {
            state.view.setMaxLines(maxLines);
            layoutSpecChanged = true;
        }

        Configuration cfg = container.getResources().getConfiguration();
        float fontScale = cfg.fontScale;
        int nightMask = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        float textSizePx = state.view.getTextSize();
        boolean appearanceChanged = state.darkSurface != darkSurface
                || state.xiaoAiStyle != xiaoAi
                || state.fontScale != fontScale
                || state.nightMask != nightMask
                || Math.abs(state.textSizePx - textSizePx) > 0.5f;

        if (!summaryText.equals(state.rawText) || appearanceChanged) {
            boolean metricChanged = state.fontScale != fontScale || Math.abs(state.textSizePx - textSizePx) > 0.5f;
            state.rawText = summaryText;
            state.darkSurface = darkSurface;
            state.xiaoAiStyle = xiaoAi;
            state.fontScale = fontScale;
            state.nightMask = nightMask;
            state.textSizePx = textSizePx;
            if (metricChanged) state.collapsedTop = 0;
            state.view.setDarkSurface(darkSurface);
            state.view.setGlowStyle(xiaoAi);
            String displayText = formatForDisplay(summaryText, isMiuiStyle());
            state.view.setText(state.view.buildSummarySpannable(displayText));
            state.view.setContentDescription(NebulaSummaryVisuals.LABEL + "：" + displayText);
            layoutSpecChanged = true;
        }
        if (layoutSpecChanged) {
            state.textVersion++;
        }
    }

    static String formatForDisplay(String summaryText, boolean miuiStyle) {
        if (summaryText == null) return "";
        String trimmed = summaryText.trim();
        if (trimmed.isEmpty()) return "";
        String stripped = GROUP_PREFIX_PATTERN.matcher(trimmed).replaceFirst("").trim();
        String base = stripped.isEmpty() ? trimmed : stripped;
        if (base.indexOf('\n') < 0 && base.indexOf('\r') < 0) {
            return base;
        }
        String[] rawLines = base.split("\\r?\\n");
        ArrayList<String> parts = new ArrayList<>(rawLines.length);
        for (String raw : rawLines) {
            String line = raw.trim();
            if (!line.isEmpty()) parts.add(line);
        }
        if (parts.isEmpty()) return base;
        boolean cjk = GroupSummaryData.isCjkPrimary(base);
        return String.join(cjk ? "；" : " ", parts);
    }

    static int computeAdaptiveCardHeight(int textHeight, int topPad, int bottomPad, int minCardHeight) {
        if (textHeight <= 0) return 0;
        return Math.max(minCardHeight, topPad + textHeight + bottomPad);
    }

    private void measureOverlay(ViewGroup container, OverlayState state, int containerWidth,
            boolean layout, boolean deferHeightNotify) throws Exception {
        if (state.mode == Mode.SUMMARY) {
            measureAdaptiveSummary(container, state, containerWidth, layout, deferHeightNotify);
            return;
        }
        Rect rect = bounds(container, state, containerWidth);
        if (rect == null) return;
        int w = rect.width();
        int h = rect.height();
        if (!layout || state.view.getMeasuredWidth() != w || state.view.getMeasuredHeight() != h) {
            state.view.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        }
        if (layout && (state.view.getLeft() != rect.left || state.view.getTop() != rect.top
                || state.view.getRight() != rect.right || state.view.getBottom() != rect.bottom)) {
            state.view.layout(rect.left, rect.top, rect.right, rect.bottom);
        }
        applyScalePivot(state.view, w, h);
    }

    private void measureAdaptiveSummary(ViewGroup container, OverlayState state, int containerWidth,
            boolean layout, boolean deferHeightNotify) throws Exception {
        Object parent = containingRow.get(container);
        if (parent == null || containerWidth <= 0) return;
        List<?> children = (List<?>) getChildren.invoke(parent);
        if (children == null || children.size() < 2) return;
        View hybrid0 = (View) getSingleLine.invoke(children.get(0));
        if (hybrid0 == null) return;

        int maxVisible = maxCollapsedSlots(container);
        boolean rtl = container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int startPad = hybrid0.getPaddingStart();
        int endPad = hybrid0.getPaddingEnd();
        int left = rtl ? endPad : startPad;
        int right = containerWidth - (rtl ? startPad : endPad);

        View overflow = (View) overflowNumberField.get(container);
        if (overflow != null && overflow.getVisibility() != View.GONE
                && overflow.getMeasuredWidth() > 0 && children.size() > maxVisible) {
            int margin = 0;
            if (injectorField != null && overflowMarginEndField != null) {
                Object inj = injectorField.get(container);
                if (inj != null) margin = overflowMarginEndField.getInt(inj);
            }
            int indent = overflow.getMeasuredWidth() + margin;
            if (rtl) left += indent;
            else right -= indent;
        }
        int w = right - left;
        if (w <= 0) return;

        float density = container.getResources().getDisplayMetrics().density;
        boolean expanded = childrenExpandedField != null && childrenExpandedField.getBoolean(container);
        boolean changing = isGroupExpansionChanging != null
                && (Boolean) isGroupExpansionChanging.invoke(parent);
        if (!expanded && !changing && container.isShown() && hybrid0.getHeight() > 0) {
            int measuredTop = localTopInAncestor(hybrid0, container);
            if (measuredTop > 0 && measuredTop < Math.round(96f * density)) {
                state.collapsedTop = measuredTop;
            }
        }

        boolean miuiStyle = isMiuiStyle();
        int topPad = state.collapsedTop > 0
                ? state.collapsedTop
                : (miuiStyle
                        ? Math.round(13f * density)
                        : (Integer) getCollapsedHeaderMargin.invoke(container)
                                + headerTranslationField.getInt(container));
        int rawBotPad = collapsedBottomPaddingField != null
                ? Math.round(collapsedBottomPaddingField.getFloat(container)) : 0;
        int bottomPad = miuiStyle
                ? topPad
                : (rawBotPad > 0 ? rawBotPad : Math.round(14f * density));

        if (state.measuredWidth != w || state.measuredTextVersion != state.textVersion
                || state.measuredTextHeight <= 0) {
            state.view.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            state.measuredWidth = w;
            state.measuredTextVersion = state.textVersion;
            state.measuredTextHeight = state.view.getMeasuredHeight();
        }
        int textH = state.measuredTextHeight;
        if (textH <= 0) return;

        int minCardH = minAdaptiveCardHeight(container, density, topPad, bottomPad);
        int adaptiveH = computeAdaptiveCardHeight(textH, topPad, bottomPad, minCardH);
        int boxH = Math.max(textH, adaptiveH - topPad - bottomPad);

        syncAdaptiveHeight(container, parent, state, adaptiveH, deferHeightNotify);

        if (!layout || state.view.getMeasuredWidth() != w || state.view.getMeasuredHeight() != boxH) {
            state.view.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(boxH, View.MeasureSpec.EXACTLY));
        }
        int bottom = topPad + boxH;
        state.bounds.set(left, topPad, right, bottom);
        if (layout && (state.view.getLeft() != left || state.view.getTop() != topPad
                || state.view.getRight() != right || state.view.getBottom() != bottom)) {
            state.view.layout(left, topPad, right, bottom);
        }
        applyScalePivot(state.view, w, boxH);
    }

    private int minAdaptiveCardHeight(ViewGroup container, float density, int topPad, int bottomPad) {
        if (!isMiuiStyle()) {
            int singleH = 0;
            try {
                singleH = minSingleLineHeightField.getInt(container);
            } catch (Throwable ignored) {}
            return topPad + Math.max(singleH, Math.round(20f * density)) + bottomPad;
        }
        int iconSpan = Math.round(40f * density);
        try {
            if (injectorField != null) {
                Object inj = injectorField.get(container);
                if (inj != null && miuiAppIconSizeField != null) {
                    int sz = miuiAppIconSizeField.getInt(inj);
                    if (sz > 0) iconSpan = sz;
                }
            }
        } catch (Throwable ignored) {}
        return Math.max(Math.round(64f * density), iconSpan + Math.round(30f * density));
    }

    private void syncAdaptiveHeight(ViewGroup container, Object parent, OverlayState state,
            int nextHeight, boolean deferNotify) {
        state.adaptiveHeight = nextHeight;
        if (state.reportedAdaptiveHeight == nextHeight) return;
        state.reportedAdaptiveHeight = nextHeight;
        if (container == null) return;
        if (deferNotify || container.isInLayout()) {
            if (!container.isAttachedToWindow()) {
                container.requestLayout();
                return;
            }
            if (!state.heightNotifyScheduled) {
                state.heightNotifyScheduled = true;
                container.post(() -> {
                    state.heightNotifyScheduled = false;
                    try {
                        notifyRowHeightChanged(container, containingRow.get(container));
                    } catch (Throwable e) {
                        module.log(Log.WARN, TAG, "Failed to post adaptive summary height update", e);
                    }
                });
            }
        } else {
            notifyRowHeightChanged(container, parent);
        }
    }

    private void notifyRowHeightChanged(ViewGroup container, Object parent) {
        container.requestLayout();
        if (notifyHeightChanged == null || parent == null) return;
        try {
            boolean animate = container.isShown()
                    && !PriorityShadeRevealAnimator.isPanelOpening()
                    && ValueAnimator.areAnimatorsEnabled();
            notifyHeightChanged.invoke(parent, "LocalAiNotif.summary", animate);
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "Failed to notify row height change", e);
        }
    }

    private void ensureContainerMeasuredHeight(ViewGroup container, OverlayState state, int heightSpec) {
        if (state.mode != Mode.SUMMARY || state.adaptiveHeight <= 0) return;
        int measuredH = container.getMeasuredHeight();
        if (measuredH >= state.adaptiveHeight) return;
        int mode = View.MeasureSpec.getMode(heightSpec);
        int specSize = View.MeasureSpec.getSize(heightSpec);
        int targetH = mode == View.MeasureSpec.EXACTLY
                ? specSize
                : (mode == View.MeasureSpec.AT_MOST ? Math.min(specSize, state.adaptiveHeight) : state.adaptiveHeight);
        if (targetH <= measuredH) return;
        try {
            if (realHeightField != null && realHeightField.getInt(container) < state.adaptiveHeight) {
                realHeightField.setInt(container, state.adaptiveHeight);
            }
            if (setMeasuredDimensionMethod != null) {
                setMeasuredDimensionMethod.invoke(container, container.getMeasuredWidth(), targetH);
            }
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "Failed to expand container measured height", e);
        }
    }

    private static void applyScalePivot(View view, int w, int h) {
        if (w <= 0 || h <= 0) return;
        boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        view.setPivotX(rtl ? w * 0.88f : w * 0.12f);
        view.setPivotY(h * 0.5f);
    }

    private Rect bounds(ViewGroup container, OverlayState state, int containerWidth) throws Exception {
        Object parent = containingRow.get(container);
        if (parent == null || containerWidth <= 0) return null;
        List<?> children = (List<?>) getChildren.invoke(parent);
        if (children == null || children.size() < 2) return null;
        View hybrid0 = (View) getSingleLine.invoke(children.get(0));
        if (hybrid0 == null) return null;

        int maxVisible = maxCollapsedSlots(container);
        int slots = Math.min(children.size(), maxVisible);
        boolean rtl = container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int startPad = hybrid0.getPaddingStart();
        int endPad = hybrid0.getPaddingEnd();
        int left = rtl ? endPad : startPad;
        int right = containerWidth - (rtl ? startPad : endPad);

        View overflow = (View) overflowNumberField.get(container);
        if (overflow != null && overflow.getVisibility() != View.GONE
                && overflow.getMeasuredWidth() > 0 && children.size() > maxVisible) {
            int margin = 0;
            if (injectorField != null && overflowMarginEndField != null) {
                Object inj = injectorField.get(container);
                if (inj != null) margin = overflowMarginEndField.getInt(inj);
            }
            int indent = overflow.getMeasuredWidth() + margin;
            if (rtl) left += indent;
            else right -= indent;
        }
        if (right <= left) return null;

        boolean expanded = childrenExpandedField != null && childrenExpandedField.getBoolean(container);
        boolean changing = isGroupExpansionChanging != null
                && (Boolean) isGroupExpansionChanging.invoke(parent);
        View hybridLast = (View) getSingleLine.invoke(children.get(slots - 1));
        if (!expanded && !changing && container.isShown() && hybrid0.getHeight() > 0
                && hybridLast != null && hybridLast.getHeight() > 0) {
            int measuredTop = localTopInAncestor(hybrid0, container);
            int lastTop = localTopInAncestor(hybridLast, container);
            int measuredBottom = lastTop >= 0 ? lastTop + hybridLast.getHeight() : -1;
            if (measuredTop > 0 && measuredBottom > measuredTop) {
                state.collapsedTop = measuredTop;
                state.collapsedBottom = measuredBottom;
                state.cachedSlots = slots;
            }
        }
        if (state.cachedSlots == slots && state.collapsedBottom > state.collapsedTop) {
            state.bounds.set(left, state.collapsedTop, right, state.collapsedBottom);
            return state.bounds;
        }

        int gap = childPaddingField.getInt(container);
        int fallbackH = minSingleLineHeightField.getInt(container);
        int height = 0;
        for (int i = 0; i < slots; i++) {
            if (i > 0) height += gap;
            View h = (View) getSingleLine.invoke(children.get(i));
            int slotH = h != null && h.getMeasuredHeight() > 0 ? h.getMeasuredHeight()
                    : (h != null && h.getHeight() > 0 ? h.getHeight() : fallbackH);
            height += slotH;
        }
        if (height <= 0) return null;
        int top = isMiuiStyle()
                ? Math.round(13f * container.getResources().getDisplayMetrics().density)
                : (Integer) getCollapsedHeaderMargin.invoke(container)
                        + headerTranslationField.getInt(container);
        state.bounds.set(left, top, right, top + height);
        return state.bounds;
    }

    private TextView activeInnerText(View hybrid) throws Exception {
        TextView text = (TextView) getHybridTextView.invoke(hybrid);
        if (text != null && text.getVisibility() != View.GONE) return text;
        return (TextView) getHybridTitleView.invoke(hybrid);
    }

    private void syncDrag(OverlayState state, View hybrid) throws Exception {
        cancelExpandAnimation(state);
        if (state.view.isWashAnimating()) {
            state.view.cancelTransientVisuals();
        }
        TextView ref = activeInnerText(hybrid);
        float alpha = ref != null ? Math.max(0f, Math.min(1f, ref.getAlpha())) : 0f;
        boolean visible = hybrid.getVisibility() == View.VISIBLE
                && ref != null && ref.getVisibility() == View.VISIBLE && alpha > 0f;
        state.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        state.view.setAlpha(alpha);
        state.view.setTranslationY(ref != null ? ref.getTranslationY() : 0f);
        if (state.mode == Mode.SUMMARY && ValueAnimator.areAnimatorsEnabled()) {
            applyScalePivot(state.view, state.view.getWidth(), state.view.getHeight());
            float scale = EXPAND_MIN_SCALE + (1f - EXPAND_MIN_SCALE) * alpha;
            state.view.setScaleX(scale);
            state.view.setScaleY(scale);
            state.view.setDragLuminescence(0.55f + 0.45f * alpha);
        } else {
            state.view.setScaleX(1f);
            state.view.setScaleY(1f);
            state.view.setDragLuminescence(1f);
        }
    }

    private void startExpandFade(OverlayState state, boolean fadeIn) {
        cancelExpandAnimation(state);
        if (!fadeIn && state.view.isWashAnimating()) {
            state.view.cancelTransientVisuals();
        }
        boolean animEnabled = ValueAnimator.areAnimatorsEnabled();
        float density = state.view.getResources().getDisplayMetrics().density;
        float tuckedTy = animEnabled && state.mode == Mode.SUMMARY ? -2.4f * density : 0f;
        float tuckedScale = animEnabled && state.mode == Mode.SUMMARY ? EXPAND_MIN_SCALE : 1f;
        applyScalePivot(state.view, state.view.getWidth(), state.view.getHeight());

        if (fadeIn) {
            if (state.view.getVisibility() != View.VISIBLE) {
                state.view.setAlpha(0f);
                state.view.setScaleX(tuckedScale);
                state.view.setScaleY(tuckedScale);
                state.view.setTranslationY(tuckedTy);
                state.view.setVisibility(View.VISIBLE);
            }
        } else if (state.view.getVisibility() != View.VISIBLE) {
            state.view.setAlpha(0f);
            return;
        }

        if (!animEnabled) {
            resetRestTransform(state);
            state.view.setAlpha(fadeIn ? 1f : 0f);
            state.view.setVisibility(fadeIn ? View.VISIBLE : View.INVISIBLE);
            return;
        }

        state.animating = true;
        long duration = isMiuiStyle() ? (fadeIn ? 225L : 205L) : 155L;
        TimeInterpolator interpolator = fadeIn ? collapseInterpolator : expandInterpolator;
        state.view.animate()
                .alpha(fadeIn ? 1f : 0f)
                .scaleX(fadeIn ? 1f : tuckedScale)
                .scaleY(fadeIn ? 1f : tuckedScale)
                .translationY(fadeIn ? 0f : tuckedTy)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean canceled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                        state.animating = false;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        state.animating = false;
                        state.view.animate().setListener(null);
                        if (!canceled) {
                            resetRestTransform(state);
                            state.view.setAlpha(fadeIn ? 1f : 0f);
                            state.view.setVisibility(fadeIn ? View.VISIBLE : View.INVISIBLE);
                            if (fadeIn && state.mode == Mode.SUMMARY && !state.view.isWashAnimating()) {
                                state.view.startBadgeGlint(350L, 0.58f);
                            }
                        }
                    }
                })
                .start();
    }

    private void cancelExpandAnimation(OverlayState state) {
        if (state.animating) {
            state.animating = false;
            state.view.animate().setListener(null);
            state.view.animate().cancel();
        }
    }

    private static void resetRestTransform(OverlayState state) {
        state.view.setTranslationY(0f);
        state.view.setScaleX(1f);
        state.view.setScaleY(1f);
        state.view.setDragLuminescence(1f);
    }

    private boolean isMiuiStyle() {
        if (showMiuiStyle != null) {
            try {
                return (Boolean) showMiuiStyle.invoke(null);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    private void hideSingleLine(View hybrid) {
        if (hybrid == null) return;
        hiddenHybrids.add(hybrid);
        if (hybrid.getAlpha() != 0f) hybrid.setAlpha(0f);
        if (hybrid.getImportantForAccessibility()
                != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
            hybrid.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    private void restoreSingleLine(View hybrid) {
        if (hybrid == null || !hiddenHybrids.remove(hybrid)) return;
        hybrid.setAlpha(1f);
        hybrid.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private static final class OverlayState {
        final NebulaSummaryView view;
        final Rect bounds = new Rect();
        final int[] location = new int[2];
        final ArrayList<StatusBarNotification> versionChildren = new ArrayList<>();
        String versionFingerprint;
        Mode mode = Mode.NONE;
        String rawText;
        boolean darkSurface;
        boolean xiaoAiStyle = GlowStyle.isXiaoAi();
        float fontScale;
        float textSizePx;
        int nightMask = -1;
        int collapsedTop;
        int collapsedBottom;
        int cachedSlots;
        int activeSlots;
        int textVersion;
        int measuredTextVersion;
        int measuredWidth;
        int measuredTextHeight;
        int adaptiveHeight;
        int reportedAdaptiveHeight;
        boolean heightNotifyScheduled;
        boolean animating;
        boolean wasVisibleOnScreen;
        long lastHiddenUptimeMs;
        long lastRevisitGlintMs;

        OverlayState(NebulaSummaryView view) {
            this.view = view;
        }
    }

    // ---------------------------------------------------------------- custom Nebula summary view

    interface WashProgressListener {
        void onWashProgress(float progress);
    }

    private final class NebulaSummaryView extends TextView implements ViewTreeObserver.OnPreDrawListener {
        private final OverlayState state = new OverlayState(this);
        private static final long THINKING_DEBOUNCE_MS = 140L;
        private static final long THINKING_FADE_IN_MS = 200L;
        private static final long THINKING_CYCLE_MS = 1450L;
        private static final long WAVE_WASH_DURATION_MS = 880L;

        private static final PathInterpolator THINKING_EASE =
                new PathInterpolator(0.38f, 0.0f, 0.22f, 1.0f);
        private static final PathInterpolator WASH_EASE =
                new PathInterpolator(0.32f, 0.0f, 0.22f, 1.0f);
        private static final PathInterpolator GLINT_EASE =
                new PathInterpolator(0.25f, 0.9f, 0.35f, 1.0f);

        private final float density;
        private final NebulaBadgeSpan badgeSpan;

        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix shaderMatrix = new Matrix();

        private Shader scanWaveShader;

        private LinearGradient revealMaskShader;
        private float cachedMaskFeather;
        private boolean cachedMaskRtl;

        private LinearGradient textTrailShader;
        private float cachedTrailWidth;
        private boolean cachedTrailDark;
        private boolean cachedTrailXiaoAi;

        private boolean thinkingMode;
        private boolean darkSurface = true;
        private boolean xiaoAiStyle = GlowStyle.isXiaoAi();
        private float dragLuminescence = 1f;

        // Thinking scanlight state
        private boolean thinkingDesiredVisible;
        private boolean thinkingScheduled;
        private float thinkingFadeAlpha;
        private float thinkingPhase;
        private ValueAnimator thinkingFadeAnimator;
        private ValueAnimator thinkingScanAnimator;
        private final Runnable startThinkingRunnable = this::beginThinkingVisuals;

        // Wave Wash reveal state
        private boolean washAnimating;
        private float washProgress = 1f;
        private ValueAnimator washAnimator;
        private WashProgressListener washProgressListener;

        // Badge micro-glint state (revisit / collapse return)
        private float badgeGlintProgress = -1f;
        private float badgeGlintIntensity;
        private float sparkleBloom;
        private ValueAnimator glintAnimator;

        NebulaSummaryView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            badgeSpan = new NebulaBadgeSpan(density);
            wavePaint.setStyle(Paint.Style.FILL);
            maskPaint.setStyle(Paint.Style.FILL);
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            trailPaint.setStyle(Paint.Style.FILL);
            trailPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        }

        void setDarkSurface(boolean dark) {
            if (darkSurface != dark) {
                darkSurface = dark;
                badgeSpan.invalidateShaders();
                scanWaveShader = null;
                textTrailShader = null;
                invalidate();
            }
        }

        void setGlowStyle(boolean xiaoAi) {
            if (xiaoAiStyle != xiaoAi) {
                xiaoAiStyle = xiaoAi;
                badgeSpan.setXiaoAiStyle(xiaoAi);
                scanWaveShader = null;
                textTrailShader = null;
                invalidate();
            }
        }

        void setDragLuminescence(float value) {
            float clamped = Math.max(0f, Math.min(1f, value));
            if (Math.abs(dragLuminescence - clamped) > 0.005f) {
                dragLuminescence = clamped;
                invalidate();
            }
        }

        void setWashProgressListener(WashProgressListener listener) {
            washProgressListener = listener;
        }

        boolean isWashAnimating() {
            return washAnimating;
        }

        float getWashProgress() {
            return washProgress;
        }

        CharSequence buildSummarySpannable(String summaryText) {
            badgeSpan.setShowHairline(true);
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append('\uFFFC');
            ssb.append(summaryText);
            ssb.setSpan(badgeSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return ssb;
        }

        void enterThinkingMode(boolean dark, boolean visible) {
            if (thinkingMode && darkSurface == dark && thinkingDesiredVisible == visible) {
                setThinkingVisible(visible);
                return;
            }
            setDarkSurface(dark);
            cancelWashAnimator();
            cancelGlintAnimator();
            thinkingMode = true;
            thinkingDesiredVisible = visible;
            if (!ValueAnimator.areAnimatorsEnabled() || !visible) {
                stopThinkingAnimators();
                invalidate();
                return;
            }
            if (!thinkingScheduled && thinkingScanAnimator == null) {
                thinkingScheduled = true;
                removeCallbacks(startThinkingRunnable);
                postDelayed(startThinkingRunnable, THINKING_DEBOUNCE_MS);
            }
            invalidate();
        }

        void setThinkingVisible(boolean visible) {
            if (!thinkingMode) return;
            thinkingDesiredVisible = visible;
            if (!visible) {
                stopThinkingAnimators();
            } else if (ValueAnimator.areAnimatorsEnabled() && !thinkingScheduled && thinkingScanAnimator == null) {
                thinkingScheduled = true;
                removeCallbacks(startThinkingRunnable);
                postDelayed(startThinkingRunnable, THINKING_DEBOUNCE_MS);
            }
        }

        private void beginThinkingVisuals() {
            thinkingScheduled = false;
            if (!thinkingMode || !thinkingDesiredVisible || !isAttachedToWindow()
                    || !ValueAnimator.areAnimatorsEnabled()) {
                return;
            }
            if (thinkingFadeAnimator != null) thinkingFadeAnimator.cancel();
            thinkingFadeAnimator = ValueAnimator.ofFloat(thinkingFadeAlpha, 1f);
            thinkingFadeAnimator.setDuration(THINKING_FADE_IN_MS);
            thinkingFadeAnimator.setInterpolator(WASH_EASE);
            thinkingFadeAnimator.addUpdateListener(anim -> {
                thinkingFadeAlpha = (float) anim.getAnimatedValue();
                invalidate();
            });
            thinkingFadeAnimator.start();

            if (thinkingScanAnimator != null && thinkingScanAnimator.isRunning()) return;
            thinkingScanAnimator = ValueAnimator.ofFloat(0f, 1f);
            thinkingScanAnimator.setDuration(THINKING_CYCLE_MS);
            thinkingScanAnimator.setRepeatCount(ValueAnimator.INFINITE);
            thinkingScanAnimator.setInterpolator(THINKING_EASE);
            thinkingScanAnimator.addUpdateListener(anim -> {
                thinkingPhase = (float) anim.getAnimatedValue();
                invalidate();
            });
            thinkingScanAnimator.start();
        }

        void enterSteadySummaryMode() {
            if (!thinkingMode && !washAnimating && washProgress == 1f) return;
            thinkingMode = false;
            stopThinkingAnimators();
            cancelWashAnimator();
            washProgress = 1f;
            invalidate();
        }

        void startWaveWashReveal() {
            thinkingMode = false;
            stopThinkingAnimators();
            cancelGlintAnimator();
            cancelWashAnimator();

            if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
                washAnimating = false;
                washProgress = 1f;
                sparkleBloom = 0f;
                badgeGlintProgress = -1f;
                badgeGlintIntensity = 0f;
                if (washProgressListener != null) washProgressListener.onWashProgress(1f);
                invalidate();
                return;
            }

            washAnimating = true;
            washProgress = 0f;
            washAnimator = ValueAnimator.ofFloat(0f, 1f);
            washAnimator.setDuration(WAVE_WASH_DURATION_MS);
            washAnimator.setInterpolator(WASH_EASE);
            washAnimator.addUpdateListener(anim -> {
                washProgress = (float) anim.getAnimatedValue();
                // Sparkle blooms right as the wave sweeps across the inline emblem.
                float badgeWindow = Math.min(1f, washProgress / 0.56f);
                sparkleBloom = NebulaSummaryVisuals.smoothEnvelope(badgeWindow, 0.34f);
                badgeGlintProgress = badgeWindow < 1f ? badgeWindow : -1f;
                badgeGlintIntensity = sparkleBloom;
                if (washProgressListener != null) {
                    washProgressListener.onWashProgress(washProgress);
                }
                invalidate();
            });
            washAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    washAnimating = false;
                    washProgress = 1f;
                    sparkleBloom = 0f;
                    badgeGlintProgress = -1f;
                    badgeGlintIntensity = 0f;
                    washAnimator = null;
                    if (washProgressListener != null) {
                        washProgressListener.onWashProgress(1f);
                    }
                    invalidate();
                }
            });
            washAnimator.start();
        }

        void startBadgeGlint(long durationMs, float peakIntensity) {
            if (thinkingMode || washAnimating || !ValueAnimator.areAnimatorsEnabled()
                    || !isAttachedToWindow()) {
                return;
            }
            cancelGlintAnimator();
            glintAnimator = ValueAnimator.ofFloat(0f, 1f);
            glintAnimator.setDuration(durationMs);
            glintAnimator.setInterpolator(GLINT_EASE);
            glintAnimator.addUpdateListener(anim -> {
                float t = (float) anim.getAnimatedValue();
                badgeGlintProgress = t;
                float env = NebulaSummaryVisuals.smoothEnvelope(t, 0.30f);
                badgeGlintIntensity = env * peakIntensity;
                sparkleBloom = env * peakIntensity * 0.85f;
                invalidate();
            });
            glintAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    badgeGlintProgress = -1f;
                    badgeGlintIntensity = 0f;
                    sparkleBloom = 0f;
                    glintAnimator = null;
                    invalidate();
                }
            });
            glintAnimator.start();
        }

        void cancelTransientVisuals() {
            stopThinkingAnimators();
            cancelGlintAnimator();
            if (washAnimating) {
                cancelWashAnimator();
                washProgress = 1f;
                if (washProgressListener != null) washProgressListener.onWashProgress(1f);
            }
        }

        void resetAllVisuals() {
            thinkingMode = false;
            thinkingDesiredVisible = false;
            stopThinkingAnimators();
            cancelWashAnimator();
            cancelGlintAnimator();
            washProgress = 1f;
            dragLuminescence = 1f;
            washProgressListener = null;
        }

        private void stopThinkingAnimators() {
            thinkingScheduled = false;
            removeCallbacks(startThinkingRunnable);
            if (thinkingFadeAnimator != null) {
                thinkingFadeAnimator.cancel();
                thinkingFadeAnimator = null;
            }
            if (thinkingScanAnimator != null) {
                thinkingScanAnimator.cancel();
                thinkingScanAnimator = null;
            }
            thinkingFadeAlpha = 0f;
            thinkingPhase = 0f;
        }

        private void cancelWashAnimator() {
            if (washAnimator != null) {
                washAnimator.cancel();
                washAnimator = null;
            }
            washAnimating = false;
            sparkleBloom = 0f;
            badgeGlintProgress = -1f;
            badgeGlintIntensity = 0f;
        }

        private void cancelGlintAnimator() {
            if (glintAnimator != null) {
                glintAnimator.cancel();
                glintAnimator = null;
            }
            badgeGlintProgress = -1f;
            badgeGlintIntensity = 0f;
            sparkleBloom = 0f;
        }

        @Override
        public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            if (!isVisible) {
                cancelTransientVisuals();
            } else if (thinkingMode && thinkingDesiredVisible && ValueAnimator.areAnimatorsEnabled()
                    && !thinkingScheduled && thinkingScanAnimator == null) {
                thinkingScheduled = true;
                removeCallbacks(startThinkingRunnable);
                postDelayed(startThinkingRunnable, THINKING_DEBOUNCE_MS);
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override
        public boolean onPreDraw() {
            if (getParent() instanceof ViewGroup container) beforeOverlayDraw(container, state);
            return true;
        }

        @Override
        protected void onDetachedFromWindow() {
            state.heightNotifyScheduled = false;
            getViewTreeObserver().removeOnPreDrawListener(this);
            cancelExpandAnimation(state);
            cancelTransientVisuals();
            super.onDetachedFromWindow();
        }

        private void ensureWaveShader() {
            if (scanWaveShader == null) {
                scanWaveShader = NebulaSummaryVisuals.createFeatheredWaveShader(
                        1, 0, 1, darkSurface, xiaoAiStyle);
            }
        }

        private void ensureMaskShader(float feather, boolean rtl) {
            if (revealMaskShader != null && cachedMaskRtl == rtl
                    && Math.abs(cachedMaskFeather - feather) < 1f) {
                return;
            }
            cachedMaskFeather = feather;
            cachedMaskRtl = rtl;
            if (rtl) {
                revealMaskShader = new LinearGradient(
                        0f, 0f, feather, 0f,
                        0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP);
            } else {
                revealMaskShader = new LinearGradient(
                        0f, 0f, feather, 0f,
                        0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
        }

        private void ensureTextTrailShader(float trailW) {
            if (textTrailShader != null && cachedTrailDark == darkSurface
                    && cachedTrailXiaoAi == xiaoAiStyle
                    && Math.abs(cachedTrailWidth - trailW) < 1f) {
                return;
            }
            cachedTrailWidth = trailW;
            cachedTrailDark = darkSurface;
            cachedTrailXiaoAi = xiaoAiStyle;
            textTrailShader = NebulaSummaryVisuals.createTextTrailGradient(trailW, darkSurface, xiaoAiStyle);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

            if (thinkingMode) {
                if (thinkingFadeAlpha > 0.01f) {
                    float bandW = Math.max(76f * density, w * 0.46f);
                    ensureWaveShader();
                    float travelStart = rtl ? (w + bandW * 0.40f) : (-bandW * 0.40f);
                    float travelEnd = rtl ? (-bandW * 0.40f) : (w + bandW * 0.40f);
                    float centerX = travelStart + (travelEnd - travelStart) * thinkingPhase;
                    float edgeFade = NebulaSummaryVisuals.smoothEnvelope(thinkingPhase, 0.16f);
                    float intensity = thinkingFadeAlpha * (0.48f + 0.52f * edgeFade)
                            * (darkSurface ? 1.0f : 0.92f);
                    NebulaSummaryVisuals.drawTiltedNebulaWave(
                            canvas, 0f, 0f, w, h, centerX, bandW, intensity,
                            darkSurface, scanWaveShader, shaderMatrix, wavePaint);
                }
                return;
            }

            badgeSpan.syncAnimationState(
                    darkSurface, xiaoAiStyle, dragLuminescence, sparkleBloom,
                    badgeGlintProgress, badgeGlintIntensity);

            if (!washAnimating) {
                super.onDraw(canvas);
                return;
            }

            // Hero ~880ms Nebula Wave Wash Reveal scaled to actual text span
            float contentSpan = w;
            android.text.Layout layout = getLayout();
            if (layout != null && layout.getLineCount() > 0) {
                float maxLineW = 0f;
                for (int i = 0; i < layout.getLineCount(); i++) {
                    maxLineW = Math.max(maxLineW, layout.getLineWidth(i));
                }
                if (maxLineW > 0f) {
                    contentSpan = Math.min(w, Math.max(w * 0.65f, maxLineW + 32f * density));
                }
            }
            float feather = Math.max(40f * density, contentSpan * 0.28f);
            float startX = rtl ? (w + feather * 0.35f) : (-feather * 0.35f);
            float endX = rtl ? (w - contentSpan - feather * 1.15f) : (contentSpan + feather * 1.15f);
            float waveX = startX + (endX - startX) * washProgress;
            float waveEnvelope = NebulaSummaryVisuals.smoothEnvelope(washProgress, 0.24f);

            int layer = canvas.saveLayer(0f, 0f, w, h, null);
            super.onDraw(canvas);

            // 1. Text-locked luminous Nebula highlight trailing right behind the wavefront
            if (waveEnvelope > 0.01f) {
                float trailW = Math.max(58f * density, contentSpan * 0.38f);
                ensureTextTrailShader(trailW);
                float trailLeft = rtl
                        ? (waveX - trailW * 0.22f)
                        : (waveX - trailW * 0.78f);
                shaderMatrix.reset();
                shaderMatrix.setTranslate(trailLeft, 0f);
                textTrailShader.setLocalMatrix(shaderMatrix);
                trailPaint.setShader(textTrailShader);
                trailPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * waveEnvelope)));
                canvas.drawRect(0f, 0f, w, h, trailPaint);
                trailPaint.setShader(null);
            }

            // 2. Soft feathered horizontal reveal mask uncovering text smoothly left-to-right
            ensureMaskShader(feather, rtl);
            float maskOriginX = rtl ? waveX : (waveX - feather);
            shaderMatrix.reset();
            shaderMatrix.setTranslate(maskOriginX, 0f);
            revealMaskShader.setLocalMatrix(shaderMatrix);
            maskPaint.setShader(revealMaskShader);
            canvas.drawRect(0f, 0f, w, h, maskPaint);
            maskPaint.setShader(null);

            canvas.restoreToCount(layer);

            // 3. Soft vertically-feathered 14-degree tilted Nebula wavefront ribbon
            if (waveEnvelope > 0.01f) {
                float bandW = Math.max(62f * density, contentSpan * 0.38f);
                ensureWaveShader();
                NebulaSummaryVisuals.drawTiltedNebulaWave(
                        canvas, 0f, 0f, w, h, waveX, bandW,
                        waveEnvelope * (darkSurface ? 1.0f : 0.94f),
                        darkSurface, scanWaveShader, shaderMatrix, wavePaint);
            }
        }
    }

    /**
     * Inline frameless Nebula Aurora prefix placed at index 0 of line 1:
     * [Dual 4-point Nebula sparkles] + [Gradient SemiBold "AI 摘要"] + [0.8dp fading vertical hairline].
     */
    private static final class NebulaBadgeSpan extends ReplacementSpan {
        private final float density;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glintLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hairlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path sparklePath = new Path();
        private final Matrix shaderMatrix = new Matrix();

        private boolean darkSurface = true;
        private boolean xiaoAiStyle = GlowStyle.isXiaoAi();
        private boolean showHairline = true;
        private float alphaScale = 1f;
        private float sparkleBloom;
        private float glintProgress = -1f;
        private float glintIntensity;

        private float cachedTextSize = Float.NaN;
        private boolean cachedDark;
        private boolean cachedXiaoAi;
        private float cachedPrimaryR;
        private float cachedDualW;
        private float cachedLabelW;
        private LinearGradient sparkleShader;
        private LinearGradient labelShader;
        private LinearGradient glintShader;
        private LinearGradient hairlineShader;
        private float cachedHairlineHalfH;

        NebulaBadgeSpan(float density) {
            this.density = density;
            Typeface semiBold = Typeface.create(Typeface.DEFAULT, 600, false);
            fillPaint.setStyle(Paint.Style.FILL);
            labelPaint.setTypeface(semiBold);
            labelPaint.setLetterSpacing(0.015f);
            glintLabelPaint.setTypeface(semiBold);
            glintLabelPaint.setLetterSpacing(0.015f);
            hairlinePaint.setStyle(Paint.Style.STROKE);
            hairlinePaint.setStrokeCap(Paint.Cap.ROUND);
            hairlinePaint.setStrokeWidth(Math.max(1f, 0.80f * density));
        }

        void setXiaoAiStyle(boolean xiaoAi) {
            if (xiaoAiStyle != xiaoAi) {
                xiaoAiStyle = xiaoAi;
                invalidateShaders();
            }
        }

        void syncAnimationState(boolean dark, boolean xiaoAi, float alpha, float bloom,
                float glintPos, float glintInt) {
            if (darkSurface != dark || xiaoAiStyle != xiaoAi) {
                darkSurface = dark;
                xiaoAiStyle = xiaoAi;
                invalidateShaders();
            }
            alphaScale = alpha;
            sparkleBloom = bloom;
            glintProgress = glintPos;
            glintIntensity = glintInt;
        }

        void setShowHairline(boolean show) {
            showHairline = show;
        }

        void invalidateShaders() {
            sparkleShader = null;
            labelShader = null;
            glintShader = null;
            hairlineShader = null;
        }

        private void ensureMetrics(Paint hostPaint) {
            float ts = hostPaint.getTextSize();
            if (Math.abs(ts - cachedTextSize) < 0.25f && cachedDark == darkSurface
                    && cachedXiaoAi == xiaoAiStyle && labelShader != null) {
                return;
            }
            cachedTextSize = ts;
            cachedDark = darkSurface;
            cachedXiaoAi = xiaoAiStyle;
            cachedPrimaryR = Math.max(4.4f * density, ts * 0.355f);
            cachedDualW = NebulaSummaryVisuals.dualSparkleWidth(cachedPrimaryR);

            float badgeTextSize = ts * 0.90f;
            labelPaint.setTextSize(badgeTextSize);
            glintLabelPaint.setTextSize(badgeTextSize);
            cachedLabelW = labelPaint.measureText(NebulaSummaryVisuals.LABEL);

            sparkleShader = NebulaSummaryVisuals.createDiagonalSparkleGradient(
                    0f, -cachedPrimaryR, cachedDualW, cachedPrimaryR, darkSurface, xiaoAiStyle);
            labelShader = NebulaSummaryVisuals.createHorizontalBadgeGradient(
                    0f, cachedLabelW, darkSurface, xiaoAiStyle);
            glintShader = NebulaSummaryVisuals.createGlintSheenGradient(
                    Math.max(24f * density, cachedLabelW * 0.65f), darkSurface, xiaoAiStyle);
            hairlineShader = null;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            ensureMetrics(paint);
            float gapAfterSparkle = 3.0f * density;
            if (!showHairline) {
                return Math.round(cachedDualW + gapAfterSparkle + cachedLabelW);
            }
            float gapBeforeHairline = 4.8f * density;
            float hairlineW = Math.max(1f, 0.80f * density);
            float gapAfterHairline = 5.0f * density;
            return Math.round(cachedDualW + gapAfterSparkle + cachedLabelW
                    + gapBeforeHairline + hairlineW + gapAfterHairline);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            ensureMetrics(paint);
            if (alphaScale <= 0.01f) return;

            float capH = -paint.ascent() * 0.72f;
            float opticalMidY = y - capH * 0.5f;

            // 1. Self-drawn dual 4-point sparkles
            shaderMatrix.reset();
            shaderMatrix.setTranslate(x, opticalMidY);
            sparkleShader.setLocalMatrix(shaderMatrix);
            NebulaSummaryVisuals.drawDualSparkle(
                    canvas, x, opticalMidY, cachedPrimaryR, darkSurface, xiaoAiStyle,
                    alphaScale, sparkleBloom, sparkleShader, fillPaint, sparklePath);

            // 2. Frameless gradient SemiBold "AI 摘要"
            float labelX = x + cachedDualW + 3.0f * density;
            float labelBaseline = opticalMidY - (labelPaint.ascent() + labelPaint.descent()) * 0.5f;

            shaderMatrix.reset();
            shaderMatrix.setTranslate(labelX, 0f);
            labelShader.setLocalMatrix(shaderMatrix);
            labelPaint.setShader(labelShader);
            labelPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * alphaScale)));
            canvas.drawText(NebulaSummaryVisuals.LABEL, labelX, labelBaseline, labelPaint);
            labelPaint.setShader(null);

            // 3. Glyph-locked specular glint sweep across "AI 摘要" during wash / revisit / collapse
            if (glintProgress >= 0f && glintIntensity > 0.01f) {
                float sheenW = Math.max(24f * density, cachedLabelW * 0.65f);
                float totalSpan = cachedDualW + 3.0f * density + cachedLabelW;
                float sheenLeft = (x - sheenW) + (totalSpan + sheenW * 2f) * glintProgress;
                shaderMatrix.reset();
                shaderMatrix.setTranslate(sheenLeft, 0f);
                glintShader.setLocalMatrix(shaderMatrix);
                glintLabelPaint.setShader(glintShader);
                glintLabelPaint.setAlpha(NebulaSummaryVisuals.clamp255(
                        Math.round(255f * glintIntensity * alphaScale)));
                canvas.drawText(NebulaSummaryVisuals.LABEL, labelX, labelBaseline, glintLabelPaint);
                glintLabelPaint.setShader(null);
            }

            // 4. Feather-thin 0.8dp vertical fading hairline separator (single-line inline mode)
            if (!showHairline) return;
            float hairlineW = Math.max(1f, 0.80f * density);
            float hx = labelX + cachedLabelW + 4.8f * density + hairlineW * 0.5f;
            float halfH = capH * 0.50f;
            if (hairlineShader == null || Math.abs(cachedHairlineHalfH - halfH) > 0.5f) {
                cachedHairlineHalfH = halfH;
                hairlineShader = NebulaSummaryVisuals.createHairlineGradient(
                        -halfH, halfH, darkSurface, xiaoAiStyle);
            }
            shaderMatrix.reset();
            shaderMatrix.setTranslate(0f, opticalMidY);
            hairlineShader.setLocalMatrix(shaderMatrix);
            hairlinePaint.setShader(hairlineShader);
            float hairlineAlpha = Math.min(1f, (0.86f + 0.25f * sparkleBloom) * alphaScale);
            hairlinePaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * hairlineAlpha)));
            canvas.drawLine(hx, opticalMidY - halfH, hx, opticalMidY + halfH, hairlinePaint);
            hairlinePaint.setShader(null);
        }
    }
}
