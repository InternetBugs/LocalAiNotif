package com.localai.notif;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Fixes HyperOS 4's first-attach {@code MiuiPanelAnimValueObjectImpl.folme == null} panel animation
 * defect and orchestrates a deliberate layered reveal for ordinary notifications below the Priority
 * Section on unlocked Notification Center pull-downs.
 *
 * <p>Part A (root-cause repair): {@code ExpandableViewInjector.onAttachedToWindow()} constructs
 * {@code MiuiPanelAnimValueObjectImpl} while {@code index == Integer.MIN_VALUE}; its constructor
 * invokes {@code onViewAttachedToWindow(view)} which skips creating {@code folme} when
 * {@code index < 0}, and the immediately following {@code setIndex$1(idx)} updates {@code index}
 * without creating {@code folme}. Repairing {@code folme} after {@code setIndex$1(idx)} (plus a
 * defensive sweep before panel visibility/expand animations) restores uniform panel-animation
 * participation for freshly attached rows.
 *
 * <p>Part B (deliberate layered reveal): Whenever the unlocked Notification Center is pulled down
 * and at least one priority notification exists, priority cards enter natively while ordinary rows
 * below stay cleanly hidden during initial drag, then fade in ({@code alpha 0 -> 1}) with a subtle
 * downward settle ({@code -10dp -> 0}) and light ripple stagger after an {@code 80ms} lead (or
 * immediately upon crossing the slow-drag commit threshold / finger release). Zero intervention on
 * lockscreen, when zero priority notifications exist, when animators are disabled, or during
 * in-shade interactions once settled.
 */
final class PriorityShadeRevealAnimator {
    private static final String TAG = "LocalAiNotif";
    private static final int KEY_PANEL_ANIM = 11018;
    private static final int KEY_LAYERED_REVEAL = 11901;

    static final long LEAD_DELAY_MS = 80L;
    static final long ROW_STAGGER_MS = 25L;
    static final long MAX_STAGGER_MS = 75L;
    static final long ROW_DURATION_MS = 210L;
    static final float START_OFFSET_DP = -10f;
    static final float COMMIT_THRESH_MULTIPLIER = 1.9f;

    private static final String SHADE_PKG = "com.android.systemui.shade.";
    private static final String NOTIF_PKG = "com.android.systemui.statusbar.notification.";

    private static volatile PriorityShadeRevealAnimator sInstance;

    private final Class<?> nsslClass;
    private final Class<?> expandableViewClass;

    // ExpandableView / ViewState / MiuiAnimUpdateData reflection
    private final Method getViewState;
    private final Method getViewInjector;
    private final Field viewInjectorAnimator;
    private final Method scheduleUpdateProperties;
    private final Field viewStateInjector;
    private final Field viewStateYTranslation;
    private final Field folmeValueMap;
    private final Method ensureValueEntry;
    private final Field animDataAlpha;
    private final Field animDataScaleX;
    private final Field animDataScaleY;
    private final Field animDataTranslationX;
    private final Field animDataTranslationY;
    private final Field animDataIsAnimating;
    private final Field animDataActiveOwnerMask;
    private final Field animDataCompleteOwnerMask;

    // MiuiPanelAnimValueObjectImpl reflection
    private final Field panelAnimView;
    private final Field panelAnimIndex;
    private final Field panelAnimFolme;
    private final Field panelAnimAmbientState;
    private final Field panelAnimBigTransY;
    private final Field panelAnimSlideTransX;
    private final Field panelAnimIsVisibleRunning;
    private final Method panelAnimOnAttached;

    // Panel controller / AmbientState reflection
    private final Field animCtrlStack;
    private final Field animCtrlAmbientState;
    private final Field expandCtrlAmbientState;
    private final Field expandCtrlVisible;
    private final Field expandCtrlStatusBarStateExt;
    private final Field statusBarStateFlow;
    private final Field readonlyFlowDelegate;
    private final Method stateFlowGetValue;
    private final Method ambientIsOnKeyguard;

    // Live runtime state (main thread only)
    private final RevealStateMachine stateMachine = new RevealStateMachine();
    private final Set<View> latestPriorityViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final List<View> latestOrdinaryViews = new ArrayList<>();
    private final List<View> sessionOrdinaryRows = new ArrayList<>();
    private final Map<View, Integer> sessionRowIndices = new IdentityHashMap<>();
    private final Map<View, Float> activeRevealOffsets = new IdentityHashMap<>();
    private final Set<View> layeredEntryAttachedViews = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> detachListenerViews = Collections.newSetFromMap(new WeakHashMap<>());

    private ViewGroup currentStack;
    private View currentPriorityHeading;
    private Object currentExpandController;
    private Object currentAmbientState;
    private boolean hasPriorityRows;
    private boolean panelVisibleNow;
    private boolean panelAppearedNow;
    private long lastPanelAppearMs;
    private boolean repairingFolme;
    private boolean frameCallbackScheduled;
    private boolean warnedOnce;

    private final View.OnAttachStateChangeListener rowDetachCleaner = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {}

        @Override
        public void onViewDetachedFromWindow(View v) {
            detachListenerViews.remove(v);
            v.removeOnAttachStateChangeListener(this);
            if (layeredEntryAttachedViews.remove(v)) {
                removeLayeredEntry(v, false);
            }
            activeRevealOffsets.remove(v);
            sessionRowIndices.remove(v);
        }
    };

    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        frameCallbackScheduled = false;
        onChoreographerFrame(SystemClock.uptimeMillis());
    };

    PriorityShadeRevealAnimator(ModuleMain module, ClassLoader loader, Class<?> stackClass)
            throws ReflectiveOperationException {
        nsslClass = stackClass;
        expandableViewClass = loader.loadClass(NOTIF_PKG + "row.ExpandableView");
        Class<?> viewInjectorClass = loader.loadClass(NOTIF_PKG + "row.ExpandableViewInjector");
        Class<?> viewInjectorBaseClass = loader.loadClass(
                "com.miui.systemui.notification.row.ExpandableViewInjectorBase");
        Class<?> viewStateClass = loader.loadClass(NOTIF_PKG + "stack.ViewState");
        Class<?> viewStateInjectorClass = loader.loadClass(NOTIF_PKG + "stack.ViewStateInjectorImpl");
        Class<?> animDataClass = loader.loadClass(NOTIF_PKG + "stack.MiuiAnimUpdateData");
        Class<?> panelAnimClass = loader.loadClass(SHADE_PKG + "MiuiPanelAnimValueObjectImpl");
        Class<?> animCtrlClass = loader.loadClass(SHADE_PKG + "MiuiNotificationPanelAnimControllerImpl");
        Class<?> expandCtrlClass = loader.loadClass(SHADE_PKG + "NotificationPanelExpandController");
        Class<?> ambientClass = loader.loadClass(NOTIF_PKG + "stack.AmbientState");

        getViewState = SystemUiPrioritySection.requireMethod(expandableViewClass, "getViewState");
        getViewInjector = SystemUiPrioritySection.requireMethod(expandableViewClass, "getInjector");
        viewInjectorAnimator = SystemUiPrioritySection.requireField(viewInjectorClass, "animator");
        scheduleUpdateProperties = SystemUiPrioritySection.requireMethod(
                viewInjectorBaseClass, "scheduleUpdateProperties");
        viewStateInjector = SystemUiPrioritySection.requireField(viewStateClass, "mInjector");
        viewStateYTranslation = SystemUiPrioritySection.requireField(viewStateClass, "mYTranslation");
        folmeValueMap = SystemUiPrioritySection.requireField(viewStateInjectorClass, "folmeValueMap");
        ensureValueEntry = SystemUiPrioritySection.requireMethod(
                viewStateInjectorClass, "ensureValueEntry", int.class);
        animDataAlpha = SystemUiPrioritySection.requireField(animDataClass, "alpha");
        animDataScaleX = SystemUiPrioritySection.requireField(animDataClass, "scaleX");
        animDataScaleY = SystemUiPrioritySection.requireField(animDataClass, "scaleY");
        animDataTranslationX = SystemUiPrioritySection.requireField(animDataClass, "translationX");
        animDataTranslationY = SystemUiPrioritySection.requireField(animDataClass, "translationY");
        animDataIsAnimating = SystemUiPrioritySection.requireField(animDataClass, "isAnimating");
        animDataActiveOwnerMask = SystemUiPrioritySection.requireField(animDataClass, "activeOwnerMask");
        animDataCompleteOwnerMask = SystemUiPrioritySection.requireField(animDataClass, "completeOwnerMask");

        panelAnimView = SystemUiPrioritySection.requireField(panelAnimClass, "view");
        panelAnimIndex = SystemUiPrioritySection.requireField(panelAnimClass, "index");
        panelAnimFolme = SystemUiPrioritySection.requireField(panelAnimClass, "folme");
        panelAnimAmbientState = SystemUiPrioritySection.requireField(panelAnimClass, "ambientState");
        panelAnimBigTransY = SystemUiPrioritySection.requireField(panelAnimClass, "bigTransY");
        panelAnimSlideTransX = SystemUiPrioritySection.requireField(panelAnimClass, "slideTransX");
        panelAnimIsVisibleRunning = SystemUiPrioritySection.requireField(panelAnimClass, "isVisibleAnimRunning");
        panelAnimOnAttached = SystemUiPrioritySection.requireMethod(
                panelAnimClass, "onViewAttachedToWindow", View.class);

        animCtrlStack = SystemUiPrioritySection.requireField(animCtrlClass, "notificationStackScrollLayout");
        animCtrlAmbientState = SystemUiPrioritySection.requireField(animCtrlClass, "ambientState");
        expandCtrlAmbientState = SystemUiPrioritySection.requireField(expandCtrlClass, "ambientState");
        expandCtrlVisible = SystemUiPrioritySection.requireField(expandCtrlClass, "visible");
        ambientIsOnKeyguard = SystemUiPrioritySection.requireMethod(ambientClass, "isOnKeyguard");

        // Optional StatusBarStateControllerExt lookup to also exclude SHADE_LOCKED (state == 2)
        Field extField = ModuleMain.findField(expandCtrlClass, "statusBarStateControllerExt");
        Field sbFlow = null;
        Field delegate = null;
        Method getVal = null;
        if (extField != null) {
            sbFlow = ModuleMain.findField(extField.getType(), "statusBarState");
            if (sbFlow != null) {
                delegate = ModuleMain.findField(sbFlow.getType(), "$$delegate_0");
                if (delegate != null) {
                    getVal = ModuleMain.findMethod(delegate.getType(), "getValue");
                }
            }
        }
        expandCtrlStatusBarStateExt = extField;
        statusBarStateFlow = sbFlow;
        readonlyFlowDelegate = delegate;
        stateFlowGetValue = getVal;

        // Hook methods
        Method setIndex = SystemUiPrioritySection.requireMethod(panelAnimClass, "setIndex$1", int.class);
        Method scheduleUpdate = SystemUiPrioritySection.requireMethod(panelAnimClass, "scheduleUpdate");
        Method startVisibleAnim = SystemUiPrioritySection.requireMethod(
                animCtrlClass, "startPanelVisibleAnimation", boolean.class, boolean.class, boolean.class);
        Method setVisible = SystemUiPrioritySection.requireMethod(expandCtrlClass, "setVisible$2", boolean.class);
        Method notifyAppearance = SystemUiPrioritySection.requireMethod(
                expandCtrlClass, "notifyAppearanceChanged", boolean.class, boolean.class);
        Method notifyExpandHeight = SystemUiPrioritySection.requireMethod(
                expandCtrlClass, "notifyExpandHeightChanged",
                float.class, float.class, boolean.class, boolean.class);
        Method onExpandFinish = SystemUiPrioritySection.requireMethod(expandCtrlClass, "onExpandFinish");
        Method hideMethod = SystemUiPrioritySection.requireMethod(expandCtrlClass, "hide", boolean.class);

        // Part A: repair first-attach folme == null immediately once setIndex$1 assigns a valid index.
        module.hook(setIndex).intercept(chain -> {
            Object result = chain.proceed();
            try {
                repairFolmeIfNeeded(chain.getThisObject());
            } catch (Throwable t) {
                logOnce("Failed to repair panel animator folme after setIndex", t);
            }
            return result;
        });

        // Part A defensive sweep + Part B pre-animation shielding before native STATE_PANEL_APPEARED.
        module.hook(startVisibleAnim).intercept(chain -> {
            try {
                Object ctrl = chain.getThisObject();
                Object stackObj = animCtrlStack.get(ctrl);
                if (stackObj instanceof ViewGroup vg) {
                    currentStack = vg;
                    repairAllStackChildrenFolme(vg);
                }
                Object amb = animCtrlAmbientState.get(ctrl);
                if (amb != null) currentAmbientState = amb;
                boolean appear = (boolean) chain.getArg(0);
                boolean animate = (boolean) chain.getArg(1);
                if (appear && animate) {
                    syncEnvironment();
                    if (stateMachine.phase() == RevealStateMachine.Phase.DRAGGING_HIDDEN) {
                        captureSessionOrdinaryRows();
                    }
                    if (stateMachine.isShieldingOrRevealing()) {
                        applyCurrentFrame(SystemClock.uptimeMillis());
                    }
                }
            } catch (Throwable t) {
                logOnce("Error preparing panel visible animation", t);
            }
            return chain.proceed();
        });

        // Part B: neutralize 11018's fly-from-top/zoom/early-alpha on active layered-reveal rows.
        module.hook(scheduleUpdate).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object animObj = chain.getThisObject();
                if (currentAmbientState == null) {
                    Object amb = panelAnimAmbientState.get(animObj);
                    if (amb != null) currentAmbientState = amb;
                }
                Object viewObj = panelAnimView.get(animObj);
                if (viewObj instanceof View view && sessionRowIndices.containsKey(view)) {
                    boolean visibleRunning = panelAnimIsVisibleRunning.getBoolean(animObj);
                    if (stateMachine.shouldNeutralizePanelAnim(visibleRunning)) {
                        neutralizePanelAnimEntry(view, animObj);
                    }
                }
            } catch (Throwable t) {
                logOnce("Error neutralizing 11018 panel anim entry", t);
            }
            return result;
        });

        // Panel visibility lifecycle (closed <-> visible)
        module.hook(setVisible).intercept(chain -> {
            Object result = chain.proceed();
            try {
                captureExpandController(chain.getThisObject());
                boolean visible = (boolean) chain.getArg(0);
                handleVisibleChanged(visible);
            } catch (Throwable t) {
                logOnce("Error handling panel visible change", t);
            }
            return result;
        });

        // Panel appearance lifecycle (hidden <-> appeared)
        module.hook(notifyAppearance).intercept(chain -> {
            try {
                captureExpandController(chain.getThisObject());
                boolean appear = (boolean) chain.getArg(0);
                boolean animate = (boolean) chain.getArg(1);
                handleAppearanceChanged(appear, animate);
            } catch (Throwable t) {
                logOnce("Error handling panel appearance change", t);
            }
            return chain.proceed();
        });

        // Continuous drag height & tracking updates (also covers show/hide/onExpandFinish)
        module.hook(notifyExpandHeight).intercept(chain -> {
            try {
                captureExpandController(chain.getThisObject());
                float expandHeight = (float) chain.getArg(0);
                float expandThresh = (float) chain.getArg(1);
                boolean tracking = (boolean) chain.getArg(2);
                boolean animate = (boolean) chain.getArg(3);
                handleExpandHeightChanged(expandHeight, expandThresh, tracking, animate);
            } catch (Throwable t) {
                logOnce("Error handling expand height change", t);
            }
            return chain.proceed();
        });

        // Finger lift on shade drag
        module.hook(onExpandFinish).intercept(chain -> {
            Object result = chain.proceed();
            try {
                captureExpandController(chain.getThisObject());
                handleExpandFinish();
            } catch (Throwable t) {
                logOnce("Error handling expand finish", t);
            }
            return result;
        });

        // Ensure instant hide(false) always drops layered entries cleanly
        module.hook(hideMethod).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object ctrl = chain.getThisObject();
                captureExpandController(ctrl);
                boolean animate = (boolean) chain.getArg(0);
                if (!animate && !expandCtrlVisible.getBoolean(ctrl)) {
                    handleVisibleChanged(false);
                }
            } catch (Throwable t) {
                logOnce("Error handling panel hide", t);
            }
            return result;
        });

        sInstance = this;
        module.log(Log.INFO, TAG, "Hooked HyperOS panel folme repair and priority layered reveal");
    }

    /** True while the notification shade is actively opening/pulling down (not yet settled). */
    static boolean isPanelOpening() {
        PriorityShadeRevealAnimator self = sInstance;
        if (self == null) return false;
        if (self.stateMachine.isShieldingOrRevealing()) return true;
        if (!self.panelVisibleNow) return false;
        return !self.panelAppearedNow
                || (SystemClock.uptimeMillis() - self.lastPanelAppearMs) < 380L;
    }

    /** True while {@code view} is being hidden or animated by the active unlocked layered reveal. */
    static boolean isActiveRevealRow(View view) {
        PriorityShadeRevealAnimator self = sInstance;
        return self != null && view != null
                && self.stateMachine.isShieldingOrRevealing()
                && self.sessionRowIndices.containsKey(view);
    }

    /**
     * Returns the vertical offset (px, {@code <= 0}) currently applied by the layered reveal entry
     * on {@code view}, so {@link PrioritySectionFrame} can compute settled layout bounds without
     * jitter as {@code following} settles downward into place.
     */
    static float getActiveRevealOffsetY(View view) {
        PriorityShadeRevealAnimator self = sInstance;
        if (self == null || view == null || !self.stateMachine.isShieldingOrRevealing()) return 0f;
        Float offset = self.activeRevealOffsets.get(view);
        return offset != null ? offset : 0f;
    }

    /** Updates priority / ordinary row membership after every {@code NodeSpecBuilder.buildNodeSpec}. */
    void updateMembership(ViewGroup stack, View priorityHeading,
                          List<View> priorityRows, List<View> ordinaryRows) {
        if (stack != null) currentStack = stack;
        currentPriorityHeading = priorityHeading;

        latestPriorityViews.clear();
        if (priorityHeading != null) latestPriorityViews.add(priorityHeading);
        if (priorityRows != null) {
            for (View v : priorityRows) {
                if (v != null) latestPriorityViews.add(v);
            }
        }
        hasPriorityRows = priorityRows != null && !priorityRows.isEmpty();

        latestOrdinaryViews.clear();
        if (ordinaryRows != null) {
            for (View v : ordinaryRows) {
                if (v != null && !latestPriorityViews.contains(v)) {
                    latestOrdinaryViews.add(v);
                }
            }
        }

        syncEnvironment();

        // If any previously shielded row was promoted to priority, release it immediately.
        if (!layeredEntryAttachedViews.isEmpty()) {
            List<View> toRelease = null;
            for (View v : layeredEntryAttachedViews) {
                if (latestPriorityViews.contains(v)) {
                    if (toRelease == null) toRelease = new ArrayList<>();
                    toRelease.add(v);
                }
            }
            if (toRelease != null) {
                for (View v : toRelease) {
                    releaseRowLayeredEntry(v, true);
                    sessionOrdinaryRows.remove(v);
                }
                rebuildSessionIndices();
            }
        }

        // If priority count dropped to 0, immediately release all layered entries (0% intervention).
        if (!hasPriorityRows) {
            clearAllLayeredEntries(true);
            return;
        }

        // While dragging hidden (before reveal starts), keep session membership fresh so newly
        // rendered ordinary rows don't flash before reveal begins. Never touch newly arriving rows
        // once SETTLED (they use native add animation).
        if (stateMachine.phase() == RevealStateMachine.Phase.DRAGGING_HIDDEN) {
            captureSessionOrdinaryRows();
            applyCurrentFrame(SystemClock.uptimeMillis());
        }
    }

    // ---------------------------------------------------------------- Part A: folme == null fix

    private void repairFolmeIfNeeded(Object animatorObj) throws ReflectiveOperationException {
        if (repairingFolme || animatorObj == null) return;
        int idx = panelAnimIndex.getInt(animatorObj);
        if (idx < 0) return;
        if (panelAnimFolme.get(animatorObj) != null) return;
        Object viewObj = panelAnimView.get(animatorObj);
        if (!(viewObj instanceof View view)) return;
        if (!view.isAttachedToWindow() || !nsslClass.isInstance(view.getParent())) return;

        repairingFolme = true;
        try {
            panelAnimOnAttached.invoke(animatorObj, view);
        } finally {
            repairingFolme = false;
        }
    }

    private void repairAllStackChildrenFolme(ViewGroup stack) {
        if (stack == null) return;
        int count = stack.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = stack.getChildAt(i);
            if (!expandableViewClass.isInstance(child)) continue;
            try {
                Object injector = getViewInjector.invoke(child);
                if (injector == null) continue;
                Object animator = viewInjectorAnimator.get(injector);
                if (animator != null) {
                    repairFolmeIfNeeded(animator);
                }
            } catch (Throwable ignored) {
                // Keep sweeping remaining children safely
            }
        }
    }

    // ---------------------------------------------------------------- Part B: lifecycle hooks

    private void captureExpandController(Object controller) throws IllegalAccessException {
        currentExpandController = controller;
        Object amb = expandCtrlAmbientState.get(controller);
        if (amb != null) currentAmbientState = amb;
    }

    private void handleVisibleChanged(boolean visible) {
        panelVisibleNow = visible;
        if (!visible) {
            panelAppearedNow = false;
        }
        syncEnvironment();
        long nowMs = SystemClock.uptimeMillis();
        if (!visible) {
            stateMachine.onVisibleChanged(false, nowMs);
            clearAllLayeredEntries(true);
            stopChoreographer();
            return;
        }
        if (stateMachine.phase() == RevealStateMachine.Phase.IDLE_CLOSED) {
            captureSessionOrdinaryRows();
            stateMachine.onVisibleChanged(true, nowMs);
            if (stateMachine.shouldApplyLayeredEntry()) {
                applyCurrentFrame(nowMs);
            } else {
                clearAllLayeredEntries(true);
            }
        }
    }

    private void handleAppearanceChanged(boolean appear, boolean animate) {
        panelAppearedNow = appear;
        long nowMs = SystemClock.uptimeMillis();
        if (appear) {
            panelVisibleNow = true;
            if (animate) lastPanelAppearMs = nowMs;
        }
        syncEnvironment();
        if (appear && animate
                && (stateMachine.phase() == RevealStateMachine.Phase.IDLE_CLOSED
                || stateMachine.phase() == RevealStateMachine.Phase.DRAGGING_HIDDEN)) {
            captureSessionOrdinaryRows();
        }
        stateMachine.onAppearanceChanged(appear, animate, nowMs);
        if (stateMachine.shouldApplyLayeredEntry()) {
            applyCurrentFrame(nowMs);
            if (stateMachine.needsChoreographerFrame()) {
                scheduleNextFrame();
            } else {
                stopChoreographer();
            }
        } else {
            clearAllLayeredEntries(true);
            stopChoreographer();
        }
    }

    private void handleExpandHeightChanged(float expandHeight, float expandThresh,
                                           boolean tracking, boolean animate) {
        syncEnvironment();
        long nowMs = SystemClock.uptimeMillis();
        if (stateMachine.phase() == RevealStateMachine.Phase.DRAGGING_HIDDEN
                && sessionOrdinaryRows.isEmpty()) {
            captureSessionOrdinaryRows();
        }
        stateMachine.onExpandHeightChanged(expandHeight, expandThresh, tracking, animate, nowMs);
        if (stateMachine.shouldApplyLayeredEntry()) {
            if (stateMachine.needsChoreographerFrame()) {
                applyCurrentFrame(nowMs);
                scheduleNextFrame();
            } else if (stateMachine.phase() == RevealStateMachine.Phase.DRAGGING_HIDDEN
                    && !sessionOrdinaryRows.isEmpty()
                    && !layeredEntryAttachedViews.containsAll(sessionOrdinaryRows)) {
                applyCurrentFrame(nowMs);
            }
        } else if (stateMachine.phase() == RevealStateMachine.Phase.SETTLED
                || stateMachine.phase() == RevealStateMachine.Phase.IDLE_CLOSED) {
            clearAllLayeredEntries(true);
            stopChoreographer();
        }
    }

    private void handleExpandFinish() {
        syncEnvironment();
        long nowMs = SystemClock.uptimeMillis();
        stateMachine.onExpandFinish(nowMs);
        if (stateMachine.shouldApplyLayeredEntry()) {
            applyCurrentFrame(nowMs);
            if (stateMachine.needsChoreographerFrame()) {
                scheduleNextFrame();
            }
        } else if (stateMachine.phase() == RevealStateMachine.Phase.SETTLED
                || stateMachine.phase() == RevealStateMachine.Phase.IDLE_CLOSED) {
            clearAllLayeredEntries(true);
            stopChoreographer();
        }
    }

    private void onChoreographerFrame(long nowMs) {
        syncEnvironment();
        if (!stateMachine.shouldApplyLayeredEntry()) {
            clearAllLayeredEntries(true);
            return;
        }
        boolean more = stateMachine.tick(nowMs);
        if (more && stateMachine.shouldApplyLayeredEntry()) {
            applyCurrentFrame(nowMs);
            scheduleNextFrame();
        } else {
            // Reached SETTLED (or bypassed): remove 11901 cleanly so rows are 100% native.
            clearAllLayeredEntries(true);
        }
    }

    private void scheduleNextFrame() {
        if (frameCallbackScheduled) return;
        frameCallbackScheduled = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopChoreographer() {
        if (!frameCallbackScheduled) return;
        frameCallbackScheduled = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    // ---------------------------------------------------------------- Environment & membership

    private void syncEnvironment() {
        boolean unlocked = isUnlockedShade();
        boolean animEnabled = ValueAnimator.areAnimatorsEnabled();
        RevealStateMachine.Phase before = stateMachine.phase();
        stateMachine.setEnvironment(unlocked, hasPriorityRows, animEnabled);
        RevealStateMachine.Phase after = stateMachine.phase();
        if (before != after && !stateMachine.shouldApplyLayeredEntry()) {
            clearAllLayeredEntries(true);
            stopChoreographer();
        }
    }

    private boolean isUnlockedShade() {
        try {
            if (currentAmbientState != null && (boolean) ambientIsOnKeyguard.invoke(currentAmbientState)) {
                return false;
            }
            if (currentExpandController != null && expandCtrlStatusBarStateExt != null
                    && statusBarStateFlow != null && readonlyFlowDelegate != null
                    && stateFlowGetValue != null) {
                Object ext = expandCtrlStatusBarStateExt.get(currentExpandController);
                if (ext != null) {
                    Object ro = statusBarStateFlow.get(ext);
                    if (ro != null) {
                        Object del = readonlyFlowDelegate.get(ro);
                        if (del != null) {
                            Object val = stateFlowGetValue.invoke(del);
                            if (val instanceof Number n && n.intValue() != 0) {
                                // 0 = SHADE (unlocked), 1 = KEYGUARD, 2 = SHADE_LOCKED
                                return false;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logOnce("Failed to read lockscreen/bar state; failing safe to bypass", t);
            return false;
        }
        return true;
    }

    /**
     * Collects all top-level ordinary notification / aggregate rows below the Priority Section
     * in visual top-to-bottom order so every ordinary card (including promotions / fold entrances)
     * participates cleanly in the layered reveal.
     */
    private void captureSessionOrdinaryRows() {
        sessionOrdinaryRows.clear();
        sessionRowIndices.clear();
        if (!hasPriorityRows) {
            stateMachine.setRowCount(0);
            return;
        }

        Set<View> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<View> collected = new ArrayList<>();

        for (View v : latestOrdinaryViews) {
            if (isEligibleOrdinaryView(v) && seen.add(v)) {
                collected.add(v);
            }
        }

        // Also scan current NSSL children below the priority section to capture any aggregate /
        // promotions / section header rows not present in ShadeListBuilder's entries list.
        ViewGroup stack = currentStack;
        if (stack != null) {
            float maxPriorityY = computeMaxPriorityY();
            int count = stack.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = stack.getChildAt(i);
                if (!isEligibleExtraStackChild(child, maxPriorityY)) continue;
                if (seen.add(child)) {
                    collected.add(child);
                }
            }
        }

        // Sort strictly top-to-bottom by ViewState.mYTranslation (fallback to view.getTop()).
        if (collected.size() > 1) {
            Map<View, Float> sortY = new IdentityHashMap<>(collected.size());
            for (View v : collected) {
                sortY.put(v, readSortY(v));
            }
            collected.sort(Comparator.comparingDouble(v -> {
                Float y = sortY.get(v);
                return y != null ? y : 0f;
            }));
        }

        sessionOrdinaryRows.addAll(collected);
        rebuildSessionIndices();
    }

    private void rebuildSessionIndices() {
        sessionRowIndices.clear();
        for (int i = 0; i < sessionOrdinaryRows.size(); i++) {
            sessionRowIndices.put(sessionOrdinaryRows.get(i), i);
        }
        stateMachine.setRowCount(sessionOrdinaryRows.size());
    }

    private boolean isEligibleOrdinaryView(View view) {
        if (view == null || latestPriorityViews.contains(view)) return false;
        if (!expandableViewClass.isInstance(view)) return false;
        if (view.getVisibility() == View.GONE) return false;
        if (currentStack != null && view.getParent() != null && view.getParent() != currentStack) {
            return false;
        }
        return !isExcludedChromeView(view);
    }

    private boolean isEligibleExtraStackChild(View child, float maxPriorityY) {
        if (child == null || latestPriorityViews.contains(child)) return false;
        if (!expandableViewClass.isInstance(child)) return false;
        if (child.getVisibility() == View.GONE) return false;
        if (isExcludedChromeView(child)) return false;
        String name = child.getClass().getName();
        boolean isRowOrHeader = name.endsWith(".ExpandableNotificationRow")
                || name.endsWith(".SectionHeaderView")
                || name.contains("Aggregate")
                || name.contains("Fold")
                || name.contains("Promotion");
        if (!isRowOrHeader) return false;
        if (maxPriorityY > Float.NEGATIVE_INFINITY) {
            float y = readSortY(child);
            if (y < maxPriorityY - 1f) return false;
        }
        return true;
    }

    private boolean isExcludedChromeView(View view) {
        if (view == currentPriorityHeading) return true;
        String name = view.getClass().getName();
        return name.endsWith(".MiuiMediaHeaderView")
                || name.endsWith(".NotificationShelf")
                || name.endsWith(".EmptyShadeView")
                || name.endsWith(".FooterView")
                || name.contains("DismissView");
    }

    private float computeMaxPriorityY() {
        float maxY = Float.NEGATIVE_INFINITY;
        for (View v : latestPriorityViews) {
            if (v == null || v == currentPriorityHeading) continue;
            if (v.getVisibility() == View.GONE) continue;
            float y = readSortY(v);
            if (y > maxY) maxY = y;
        }
        return maxY;
    }

    private float readSortY(View view) {
        try {
            Object vs = getViewState.invoke(view);
            if (vs != null) {
                return viewStateYTranslation.getFloat(vs);
            }
        } catch (Throwable ignored) {}
        return view.getTop() + view.getTranslationY();
    }

    // ---------------------------------------------------------------- Property compositing

    private void neutralizePanelAnimEntry(View view, Object animObj) throws ReflectiveOperationException {
        Object vs = getViewState.invoke(view);
        if (vs == null) return;
        Object vsInjector = viewStateInjector.get(vs);
        if (vsInjector == null) return;
        @SuppressWarnings("unchecked")
        SparseArray<Object> map = (SparseArray<Object>) folmeValueMap.get(vsInjector);
        if (map == null) return;
        Object data11018 = map.get(KEY_PANEL_ANIM);
        if (data11018 == null) return;

        float bigTransY = panelAnimBigTransY.getFloat(animObj);
        float slideTransX = panelAnimSlideTransX.getFloat(animObj);

        animDataScaleX.setFloat(data11018, 1f);
        animDataScaleY.setFloat(data11018, 1f);
        animDataAlpha.setFloat(data11018, 1f);
        animDataTranslationX.setFloat(data11018, slideTransX);
        animDataTranslationY.setFloat(data11018, Math.max(0f, bigTransY));
    }

    private void applyCurrentFrame(long nowMs) {
        if (sessionOrdinaryRows.isEmpty()) return;
        float startOffsetPx = startOffsetPx();
        for (int i = 0; i < sessionOrdinaryRows.size(); i++) {
            View row = sessionOrdinaryRows.get(i);
            if (row == null || !row.isAttachedToWindow()) continue;
            float alpha = stateMachine.rowAlpha(i, nowMs);
            float offsetY = stateMachine.rowOffsetPx(i, nowMs, startOffsetPx);
            writeLayeredEntry(row, alpha, offsetY);
        }
        if (currentStack != null) {
            currentStack.invalidate();
        }
    }

    private float startOffsetPx() {
        View ref = currentStack;
        if (ref == null && !sessionOrdinaryRows.isEmpty()) {
            ref = sessionOrdinaryRows.get(0);
        }
        float density = ref != null ? ref.getResources().getDisplayMetrics().density : 3f;
        return START_OFFSET_DP * density;
    }

    private void writeLayeredEntry(View view, float alpha, float offsetY) {
        try {
            Object vs = getViewState.invoke(view);
            if (vs == null) return;
            Object vsInjector = viewStateInjector.get(vs);
            if (vsInjector == null) return;
            Object data = ensureValueEntry.invoke(vsInjector, KEY_LAYERED_REVEAL);
            if (data == null) return;

            animDataAlpha.setFloat(data, alpha);
            animDataScaleX.setFloat(data, 1f);
            animDataScaleY.setFloat(data, 1f);
            animDataTranslationX.setFloat(data, 0f);
            animDataTranslationY.setFloat(data, offsetY);
            animDataIsAnimating.setBoolean(data, true);
            animDataActiveOwnerMask.setInt(data, 1);
            animDataCompleteOwnerMask.setInt(data, 0);

            activeRevealOffsets.put(view, offsetY);
            layeredEntryAttachedViews.add(view);
            if (detachListenerViews.add(view)) {
                view.addOnAttachStateChangeListener(rowDetachCleaner);
            }

            Object viewInj = getViewInjector.invoke(view);
            if (stateMachine.isShieldingOrRevealing() && viewInj != null) {
                Object animObj = viewInjectorAnimator.get(viewInj);
                if (animObj != null) {
                    neutralizePanelAnimEntry(view, animObj);
                }
            }
            if (viewInj != null) {
                scheduleUpdateProperties.invoke(viewInj);
            }
        } catch (Throwable t) {
            logOnce("Failed writing layered reveal entry", t);
        }
    }

    private void clearAllLayeredEntries(boolean scheduleUpdate) {
        if (layeredEntryAttachedViews.isEmpty()) {
            activeRevealOffsets.clear();
            return;
        }
        List<View> views = new ArrayList<>(layeredEntryAttachedViews);
        layeredEntryAttachedViews.clear();
        activeRevealOffsets.clear();
        for (View v : views) {
            removeLayeredEntry(v, scheduleUpdate);
        }
        if (scheduleUpdate && currentStack != null) {
            currentStack.invalidate();
        }
    }

    private void releaseRowLayeredEntry(View view, boolean scheduleUpdate) {
        layeredEntryAttachedViews.remove(view);
        activeRevealOffsets.remove(view);
        removeLayeredEntry(view, scheduleUpdate);
    }

    private void removeLayeredEntry(View view, boolean scheduleUpdate) {
        if (view == null) return;
        try {
            Object vs = getViewState.invoke(view);
            if (vs == null) return;
            Object vsInjector = viewStateInjector.get(vs);
            if (vsInjector == null) return;
            @SuppressWarnings("unchecked")
            SparseArray<Object> map = (SparseArray<Object>) folmeValueMap.get(vsInjector);
            if (map != null && map.get(KEY_LAYERED_REVEAL) != null) {
                map.remove(KEY_LAYERED_REVEAL);
                if (scheduleUpdate) {
                    Object viewInj = getViewInjector.invoke(view);
                    if (viewInj != null) {
                        scheduleUpdateProperties.invoke(viewInj);
                    }
                }
            }
        } catch (Throwable t) {
            logOnce("Failed removing layered reveal entry", t);
        }
    }

    private void logOnce(String msg, Throwable t) {
        if (warnedOnce) return;
        warnedOnce = true;
        Log.w(TAG, msg, t);
    }

    // ---------------------------------------------------------------- Pure-Java State Machine

    /**
     * Pure-Java timing & lifecycle state machine for unlocked Notification Center layered reveal.
     * Kept strictly free of Android framework calls so host JVM unit tests can verify every
     * transition, slow-drag commit/abort path, and HyperOS easing curve deterministically.
     */
    static final class RevealStateMachine {
        private static final float[] EMPTY_FLOATS = new float[0];

        enum Phase {
            IDLE_CLOSED,
            DRAGGING_HIDDEN,
            WAITING_LEAD,
            REVEALING,
            SETTLED,
            CLOSING
        }

        private Phase phase = Phase.IDLE_CLOSED;
        private boolean panelVisible;
        private boolean panelAppeared;
        private boolean tracking;
        private boolean unlockedShade = true;
        private boolean hasPriority;
        private boolean animatorsEnabled = true;
        private boolean settledFromLayeredReveal;
        private boolean closedFromMidReveal;
        private long appearStartMs;
        private long revealStartMs;
        private int rowCount;
        private float[] frozenAlphas = EMPTY_FLOATS;
        private float[] frozenOffsets = EMPTY_FLOATS;

        Phase phase() {
            return phase;
        }

        boolean isShieldingOrRevealing() {
            return phase == Phase.DRAGGING_HIDDEN
                    || phase == Phase.WAITING_LEAD
                    || phase == Phase.REVEALING;
        }

        boolean needsChoreographerFrame() {
            return phase == Phase.WAITING_LEAD || phase == Phase.REVEALING;
        }

        boolean shouldApplyLayeredEntry() {
            return isShieldingOrRevealing() || (phase == Phase.CLOSING && closedFromMidReveal);
        }

        boolean shouldNeutralizePanelAnim(boolean nativeVisibleAnimRunning) {
            if (isShieldingOrRevealing()) return true;
            return phase == Phase.SETTLED && settledFromLayeredReveal && nativeVisibleAnimRunning;
        }

        private boolean isEligible() {
            return unlockedShade && hasPriority && animatorsEnabled && rowCount > 0;
        }

        void setRowCount(int count) {
            rowCount = Math.max(0, count);
            if (rowCount == 0 && (phase == Phase.WAITING_LEAD || phase == Phase.REVEALING)) {
                phase = Phase.SETTLED;
                settledFromLayeredReveal = false;
            }
        }

        void setEnvironment(boolean unlocked, boolean priorityPresent, boolean animEnabled) {
            unlockedShade = unlocked;
            hasPriority = priorityPresent;
            animatorsEnabled = animEnabled;

            if (!unlockedShade || !hasPriority) {
                settledFromLayeredReveal = false;
                closedFromMidReveal = false;
                frozenAlphas = EMPTY_FLOATS;
                frozenOffsets = EMPTY_FLOATS;
                phase = panelAppeared ? Phase.SETTLED : Phase.IDLE_CLOSED;
                return;
            }
            if (!animatorsEnabled && isShieldingOrRevealing()) {
                settledFromLayeredReveal = false;
                closedFromMidReveal = false;
                phase = panelAppeared ? Phase.SETTLED : Phase.IDLE_CLOSED;
            }
        }

        void onVisibleChanged(boolean visible, long nowMs) {
            panelVisible = visible;
            if (!visible) {
                panelAppeared = false;
                tracking = false;
                settledFromLayeredReveal = false;
                closedFromMidReveal = false;
                appearStartMs = 0L;
                revealStartMs = 0L;
                frozenAlphas = EMPTY_FLOATS;
                frozenOffsets = EMPTY_FLOATS;
                phase = Phase.IDLE_CLOSED;
                return;
            }
            if (phase == Phase.IDLE_CLOSED && isEligible()) {
                phase = Phase.DRAGGING_HIDDEN;
                appearStartMs = 0L;
                revealStartMs = 0L;
            }
        }

        void onAppearanceChanged(boolean appear, boolean animate, long nowMs) {
            panelAppeared = appear;
            if (appear) {
                panelVisible = true;
                if (!isEligible() || !animate) {
                    settledFromLayeredReveal = false;
                    closedFromMidReveal = false;
                    phase = Phase.SETTLED;
                    return;
                }
                if (phase == Phase.CLOSING) {
                    // User caught the shade mid-close while rows were already visible: settle
                    // smoothly without blinking rows back down to alpha 0.
                    settledFromLayeredReveal = false;
                    closedFromMidReveal = false;
                    frozenAlphas = EMPTY_FLOATS;
                    frozenOffsets = EMPTY_FLOATS;
                    phase = Phase.SETTLED;
                    return;
                }
                if (phase == Phase.IDLE_CLOSED || phase == Phase.DRAGGING_HIDDEN) {
                    appearStartMs = nowMs;
                    revealStartMs = 0L;
                    phase = Phase.DRAGGING_HIDDEN;
                }
            } else {
                if (!animate) {
                    settledFromLayeredReveal = false;
                    closedFromMidReveal = false;
                    frozenAlphas = EMPTY_FLOATS;
                    frozenOffsets = EMPTY_FLOATS;
                    phase = Phase.IDLE_CLOSED;
                    return;
                }
                if (phase == Phase.DRAGGING_HIDDEN || phase == Phase.WAITING_LEAD) {
                    // Drag aborted back up before ordinary rows ever started fading in: keep
                    // hidden (alpha == 0) until panelVisible turns false so ordinary rows never flash.
                    appearStartMs = 0L;
                    revealStartMs = 0L;
                    phase = Phase.DRAGGING_HIDDEN;
                } else if (phase == Phase.REVEALING) {
                    // Freeze each row's current reveal alpha so native close animation multiplies
                    // downward monotonically without any upward alpha flash.
                    frozenAlphas = new float[rowCount];
                    frozenOffsets = new float[rowCount];
                    for (int i = 0; i < rowCount; i++) {
                        frozenAlphas[i] = rowAlpha(i, nowMs);
                        frozenOffsets[i] = rowOffsetFraction(i, nowMs);
                    }
                    settledFromLayeredReveal = false;
                    closedFromMidReveal = true;
                    phase = Phase.CLOSING;
                } else if (phase == Phase.SETTLED) {
                    settledFromLayeredReveal = false;
                    closedFromMidReveal = false;
                    phase = Phase.CLOSING;
                }
            }
        }

        void onExpandHeightChanged(float expandHeight, float expandThresh,
                                   boolean isTracking, boolean animate, long nowMs) {
            tracking = isTracking;
            if (!isEligible()) return;
            if (!animate) {
                if (panelAppeared) {
                    settledFromLayeredReveal = false;
                    closedFromMidReveal = false;
                    phase = Phase.SETTLED;
                }
                return;
            }
            if (phase == Phase.DRAGGING_HIDDEN && panelAppeared) {
                float thresh = expandThresh > 1f ? expandThresh : 200f;
                float commitThresh = thresh * COMMIT_THRESH_MULTIPLIER;
                if (!isTracking || expandHeight >= commitThresh) {
                    commitReveal(nowMs);
                }
            }
        }

        void onExpandFinish(long nowMs) {
            tracking = false;
            if (phase == Phase.DRAGGING_HIDDEN && panelAppeared && isEligible()) {
                commitReveal(nowMs);
            }
        }

        private void commitReveal(long nowMs) {
            long baseAppear = appearStartMs > 0L ? appearStartMs : nowMs;
            revealStartMs = Math.max(nowMs, baseAppear + LEAD_DELAY_MS);
            phase = (nowMs >= revealStartMs) ? Phase.REVEALING : Phase.WAITING_LEAD;
        }

        boolean tick(long nowMs) {
            if (phase == Phase.WAITING_LEAD) {
                if (nowMs >= revealStartMs) {
                    phase = Phase.REVEALING;
                } else {
                    return true;
                }
            }
            if (phase == Phase.REVEALING) {
                long lastEnd = revealStartMs + rowDelayMs(Math.max(0, rowCount - 1)) + ROW_DURATION_MS;
                if (nowMs >= lastEnd) {
                    phase = Phase.SETTLED;
                    settledFromLayeredReveal = true;
                    return false;
                }
                return true;
            }
            return false;
        }

        static long rowDelayMs(int rowIndex) {
            return Math.min(MAX_STAGGER_MS, Math.max(0, rowIndex) * ROW_STAGGER_MS);
        }

        float rowProgress(int rowIndex, long nowMs) {
            if (phase != Phase.REVEALING) return 0f;
            long rowStart = revealStartMs + rowDelayMs(rowIndex);
            if (nowMs <= rowStart) return 0f;
            long elapsed = nowMs - rowStart;
            if (elapsed >= ROW_DURATION_MS) return 1f;
            return (float) elapsed / (float) ROW_DURATION_MS;
        }

        float rowAlpha(int rowIndex, long nowMs) {
            return switch (phase) {
                case DRAGGING_HIDDEN, WAITING_LEAD -> 0f;
                case REVEALING -> evalHyperEase(rowProgress(rowIndex, nowMs));
                case CLOSING -> {
                    if (!closedFromMidReveal) yield 1f;
                    yield (rowIndex >= 0 && rowIndex < frozenAlphas.length)
                            ? frozenAlphas[rowIndex] : 0f;
                }
                case IDLE_CLOSED, SETTLED -> 1f;
            };
        }

        private float rowOffsetFraction(int rowIndex, long nowMs) {
            return switch (phase) {
                case DRAGGING_HIDDEN, WAITING_LEAD -> 1f;
                case REVEALING -> 1f - evalHyperEase(rowProgress(rowIndex, nowMs));
                case CLOSING -> {
                    if (!closedFromMidReveal) yield 0f;
                    yield (rowIndex >= 0 && rowIndex < frozenOffsets.length)
                            ? frozenOffsets[rowIndex] : 0f;
                }
                case IDLE_CLOSED, SETTLED -> 0f;
            };
        }

        float rowOffsetPx(int rowIndex, long nowMs, float startOffsetPx) {
            return startOffsetPx * rowOffsetFraction(rowIndex, nowMs);
        }

        /**
         * Exact cubic-bezier(0.22, 1.0, 0.36, 1.0) matching HyperOS {@code PathInterpolator}.
         * Since {@code P1y = 1.0} and {@code P2y = 1.0}, {@code B_y(u) = 1 - (1 - u)^3}.
         */
        static float evalHyperEase(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            // Solve B_x(u) = 3*(1-u)^2*u*0.22 + 3*(1-u)*u^2*0.36 + u^3 = t on [0, 1]
            // Equivalent polynomial: ((3*x1 - 3*x2 + 1)*u + (3*x2 - 6*x1))*u^2 + 3*x1*u = t
            final float ax = 0.58f;   // 3*0.22 - 3*0.36 + 1
            final float bx = -0.24f;  // 3*0.36 - 6*0.22
            final float cx = 0.66f;   // 3*0.22
            float lo = 0f;
            float hi = 1f;
            float u = t;
            for (int i = 0; i < 8; i++) {
                float x = ((ax * u + bx) * u + cx) * u;
                float err = x - t;
                if (Math.abs(err) < 1e-6f) break;
                if (err < 0f) lo = u;
                else hi = u;
                float dx = (3f * ax * u + 2f * bx) * u + cx;
                float next = (dx > 1e-5f) ? (u - err / dx) : 0.5f * (lo + hi);
                u = (next > lo && next < hi) ? next : 0.5f * (lo + hi);
            }
            float inv = 1f - u;
            return 1f - inv * inv * inv;
        }
    }
}
