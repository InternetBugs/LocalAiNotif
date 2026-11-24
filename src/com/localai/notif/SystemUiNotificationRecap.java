package com.localai.notif;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import static com.localai.notif.SystemUiPrioritySection.requireField;
import static com.localai.notif.SystemUiPrioritySection.requireMethod;

/**
 * Renders single-notification "AI 概括" presentation inside {@code ExpandableNotificationRow}
 * while strictly preserving native single-card line limits, card height, background, and outline:
 * <ul>
 *   <li><b>Inline Single-Sparkle Prefix &amp; Tinted Ink</b>: Self-drawn single 4-point sparkle
 *       (distinct from multi-notification dual sparkles) + gradient SemiBold "AI 概括" + 0.8dp
 *       vertical fading hairline, followed by style-adaptive tinted body ink synced live with
 *       {@link GlowStyle} (超级小爱流光 / 星云极光).</li>
 *   <li><b>4-Stage Optical Motion</b>:
 *       (1) 14° tilted scanlight wave while {@code inFlight} (140ms debounce, raw text stays readable);
 *       (2) ~880ms Wave Wash reveal + sparkle bloom on live landing/update;
 *       (3) ~550ms badge micro-glint on revisit or first reveal of a pre-computed recap when expanding
 *           a notification group;
 *       (4) Clean original restore on single-card expand, and ~350ms badge glint on collapse return.</li>
 * </ul>
 */
final class SystemUiNotificationRecap {
    private static final String TAG = "LocalAiNotif";
    private static final int MAX_REVEALED_CACHE = 64;
    private static final int MAX_SNAPSHOT_PIXELS = 2048 * 1024;
    private static final long THINKING_DEBOUNCE_MS = 140L;
    private static final long THINKING_FADE_IN_MS = 200L;
    private static final long THINKING_CYCLE_MS = 1450L;
    private static final long WAVE_WASH_DURATION_MS = 880L;
    private static final long REVISIT_HIDDEN_DEBOUNCE_MS = 450L;
    private static final long REVISIT_GLINT_COOLDOWN_MS = 1200L;

    private static final PathInterpolator THINKING_EASE =
            new PathInterpolator(0.38f, 0.0f, 0.22f, 1.0f);
    private static final PathInterpolator WASH_EASE =
            new PathInterpolator(0.32f, 0.0f, 0.22f, 1.0f);
    private static final PathInterpolator GLINT_EASE =
            new PathInterpolator(0.25f, 0.9f, 0.35f, 1.0f);

    private final Method getEntry;
    private final Method getPrivateLayout;
    private final Method shouldShowPublic;
    private final Method isHeadsUpState;
    private final Method getParent;
    private final Method areChildrenExpanded;
    private final Field showingPublic;
    private final Field userExpanded;
    private final Field sbnField;
    private final SlotAccessor contracted;
    private final SlotAccessor expanded;

    private final WeakHashMap<View, RowState> rowStates = new WeakHashMap<>();
    private final Runnable glowStyleListener = this::onGlowStyleChanged;
    private final Map<String, String> revealedRecaps = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_REVEALED_CACHE;
        }
    };

    private enum Mode {
        NONE,
        THINKING,
        RECAP
    }

    static void install(ModuleMain module, ClassLoader loader) {
        try {
            new SystemUiNotificationRecap(module, loader);
            module.log(Log.INFO, TAG, "Installed notification recap presentation");
        } catch (Exception exception) {
            module.log(Log.WARN, TAG, "Failed to install notification recap presentation", exception);
        }
    }

    private SystemUiNotificationRecap(ModuleMain module, ClassLoader loader) throws Exception {
        String prefix = "com.android.systemui.statusbar.notification.";
        Class<?> row = loader.loadClass(prefix + "row.ExpandableNotificationRow");
        Class<?> content = loader.loadClass(prefix + "row.NotificationContentView");
        Class<?> entry = loader.loadClass(prefix + "collection.NotificationEntry");
        getEntry = requireMethod(row, "getEntry");
        getPrivateLayout = requireMethod(row, "getPrivateLayout");
        contracted = new SlotAccessor(
                requireMethod(content, "getContractedWrapper"),
                requireMethod(content, "getContractedChild"));
        expanded = new SlotAccessor(
                requireMethod(content, "getExpandedWrapper"),
                requireMethod(content, "getExpandedChild"));
        shouldShowPublic = requireMethod(row, "shouldShowPublic");
        isHeadsUpState = requireMethod(row, "isHeadsUpState");
        getParent = ModuleMain.findMethod(row, "getNotificationParent");
        areChildrenExpanded = ModuleMain.findMethod(row, "areChildrenExpanded");
        showingPublic = requireField(row, "mShowingPublic");
        userExpanded = requireField(row, "mUserExpanded");
        sbnField = requireField(entry, "mSbn");

        List<Method> hooks = new ArrayList<>(5);
        hooks.add(requireMethod(row, "onNotificationUpdated"));
        hooks.add(requireMethod(row, "setHideSensitive", boolean.class, boolean.class, long.class, long.class));
        hooks.add(requireMethod(row, "setHeadsUp", boolean.class));
        hooks.add(requireMethod(row, "setUserExpanded", boolean.class, boolean.class));
        Method configChanged = ModuleMain.findMethod(row, "onConfigurationChanged", Configuration.class);
        if (configChanged != null) hooks.add(configChanged);

        for (Method method : hooks) {
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    update((View) chain.getThisObject());
                } catch (Exception exception) {
                    module.log(Log.WARN, TAG, "Failed to render notification recap", exception);
                }
                return result;
            });
        }
        GlowStyle.addListener(glowStyleListener);
    }

    private void onGlowStyleChanged() {
        List<View> snapshot = new ArrayList<>(rowStates.keySet());
        for (View row : snapshot) {
            RowState state = rowStates.get(row);
            if (state == null) continue;
            try {
                update(row);
                if (state.mode == Mode.RECAP && isRowDisplayVisible(row)) {
                    state.startVisibleSlotBadgeGlint(550L, 0.78f);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void update(View row) throws ReflectiveOperationException {
        if (row == null) return;
        GlowStyle.initInSystemUi(row.getContext());

        Object entry = getEntry.invoke(row);
        StatusBarNotification sbn = entry == null ? null : (StatusBarNotification) sbnField.get(entry);
        Notification notification = sbn == null ? null : sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        String recap = extras == null ? null : extras.getString(NotificationRecap.EXTRA);
        String inFlightFp = extras == null ? null : extras.getString(NotificationRecap.EXTRA_IN_FLIGHT);

        boolean hasRecap = recap != null && !recap.isBlank();
        boolean hasInFlight = !hasRecap && inFlightFp != null && !inFlightFp.isBlank();
        boolean isExpandedByUser = userExpanded.getBoolean(row);
        boolean blocked = notification == null
                || !PriorityNotification.isEligible(notification)
                || showingPublic.getBoolean(row)
                || (Boolean) shouldShowPublic.invoke(row)
                || (Boolean) isHeadsUpState.invoke(row)
                || isExpandedByUser;

        RowState state = rowStates.get(row);
        if (!hasRecap && !hasInFlight) {
            if (state != null) {
                state.clearAll();
                state.wasUserExpanded = isExpandedByUser;
                state.sawOriginalWhileVisible = false;
                state.observedKey = null;
                state.observedPostTime = 0L;
            }
            return;
        }

        if (state == null) {
            state = new RowState(this, row);
            rowStates.put(row, state);
        }

        String key = sbn.getKey();
        long postTime = sbn.getPostTime();
        if (!Objects.equals(state.observedKey, key) || state.observedPostTime != postTime) {
            state.observedKey = key;
            state.observedPostTime = postTime;
            state.sawOriginalWhileVisible = false;
            state.wasUserExpanded = false;
            state.pendingFirstRevealGlint = false;
        }

        if (blocked) {
            state.clearAll();
            state.wasUserExpanded = isExpandedByUser;
            return;
        }

        boolean collapsedReturn = state.wasUserExpanded;
        state.wasUserExpanded = false;

        Object layout = getPrivateLayout.invoke(row);
        if (layout == null) {
            state.clearAll();
            return;
        }

        boolean visibleNow = isRowDisplayVisible(row);

        if (hasInFlight) {
            String rawBody = NotificationRecap.body(notification);
            boolean matchesBody = inFlightFp.equals(NotificationRecap.bodyFingerprint(rawBody));
            if (!matchesBody) {
                state.clearAll();
                return;
            }
            state.mode = Mode.THINKING;
            state.activeRecap = null;
            state.activeInFlightFp = inFlightFp;
            state.pendingFirstRevealGlint = false;
            if (visibleNow) state.sawOriginalWhileVisible = true;
            state.ensureObserversAttached();
            updateThinkingSlot(state.contracted, contracted, layout, notification, visibleNow);
            updateThinkingSlot(state.expanded, expanded, layout, notification, visibleNow);
            state.wasVisibleOnScreen = visibleNow;
            return;
        }

        // Mode.RECAP
        String revealToken = postTime + "#" + recap.hashCode();
        boolean isFreshReveal = !revealToken.equals(revealedRecaps.get(key));
        boolean allowLiveWash = isFreshReveal
                && visibleNow
                && !collapsedReturn
                && ValueAnimator.areAnimatorsEnabled()
                && (state.sawOriginalWhileVisible || state.mode == Mode.THINKING || isStandaloneRow(row));

        if (isFreshReveal) {
            revealedRecaps.put(key, revealToken);
            if (!allowLiveWash) {
                state.pendingFirstRevealGlint = true;
            }
        }

        state.mode = Mode.RECAP;
        state.activeRecap = recap;
        state.activeInFlightFp = null;
        state.ensureObserversAttached();

        boolean xiaoAi = GlowStyle.isXiaoAi();
        boolean startedWash = false;
        startedWash |= updateRecapSlot(state.contracted, contracted, layout, notification, recap,
                xiaoAi, allowLiveWash);
        startedWash |= updateRecapSlot(state.expanded, expanded, layout, notification, recap,
                xiaoAi, allowLiveWash && !startedWash);

        if (allowLiveWash && !startedWash) {
            // Live wash couldn't snapshot (e.g. body had zero bounds yet); fall back to crisp badge glint.
            state.pendingFirstRevealGlint = true;
        }

        if (collapsedReturn && visibleNow && !startedWash) {
            state.pendingFirstRevealGlint = false;
            state.startVisibleSlotBadgeGlint(350L, 0.62f);
        } else if (state.pendingFirstRevealGlint && visibleNow && !startedWash) {
            state.pendingFirstRevealGlint = false;
            state.lastRevisitGlintMs = SystemClock.uptimeMillis();
            state.startVisibleSlotBadgeGlint(550L, 0.72f);
        }
        state.wasVisibleOnScreen = visibleNow;
    }

    private void updateThinkingSlot(SlotState slot, SlotAccessor accessor, Object layout,
            Notification notification, boolean rowVisible) throws ReflectiveOperationException {
        TextView body = findBody(accessor, layout, notification, slot);
        if (slot.hasAppliedText() && slot.view() != body) {
            slot.restoreText();
        } else if (slot.hasAppliedText()) {
            slot.restoreText();
        }
        if (body == null) {
            slot.stopTransientVisuals();
            return;
        }
        boolean darkSurface = detectDarkSurface(body);
        boolean xiaoAi = GlowStyle.isXiaoAi();
        boolean slotVisible = rowVisible && body.isShown();
        slot.enterThinkingMode(body, darkSurface, xiaoAi, slotVisible);
    }

    private boolean updateRecapSlot(SlotState slot, SlotAccessor accessor, Object layout,
            Notification notification, String recap, boolean xiaoAi, boolean allowWash)
            throws ReflectiveOperationException {
        TextView body = findBody(accessor, layout, notification, slot);
        if (body == null || (slot.hasAppliedText() && slot.view() != body)) {
            slot.restoreAll();
        }
        if (body == null || TextUtils.isEmpty(body.getText())) return false;

        boolean darkSurface = detectDarkSurface(body);
        int baseInk = body.getCurrentTextColor();
        int tintedInk = NebulaSummaryVisuals.tintSummaryBodyColor(baseInk, darkSurface, xiaoAi);
        String displayString = "\uFFFC" + recap;

        boolean stillApplied = slot.hasAppliedText()
                && slot.view() == body
                && TextUtils.equals(slot.displayString, body.getText());
        boolean appearanceChanged = slot.darkSurface != darkSurface
                || slot.xiaoAiStyle != xiaoAi
                || slot.tintedInk != tintedInk;

        if (stillApplied && displayString.equals(slot.displayString) && !appearanceChanged) {
            slot.stopThinkingOnly();
            return false;
        }

        CharSequence original = stillApplied ? slot.originalText : body.getText();
        CharSequence originalDesc = stillApplied ? slot.originalContentDescription : body.getContentDescription();

        boolean canWashHere = allowWash
                && !stillApplied
                && body.isAttachedToWindow()
                && body.isShown()
                && body.getWidth() > 0
                && body.getHeight() > 0
                && body.getParent() instanceof ViewGroup;

        Bitmap oldSnapshot = null;
        float savedAlpha = 1f;
        if (canWashHere) {
            slot.stopThinkingOnly();
            savedAlpha = body.getAlpha();
            if (savedAlpha > 0.02f) {
                oldSnapshot = captureTextSnapshot(body);
            }
        } else {
            slot.stopTransientVisuals();
        }

        RecapBadgeSpan badgeSpan = slot.ensureBadgeSpan(body.getResources().getDisplayMetrics().density);
        badgeSpan.syncAnimationState(darkSurface, xiaoAi, 1f, 0f, -1f, 0f);

        SpannableStringBuilder styled = new SpannableStringBuilder(displayString);
        styled.setSpan(badgeSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (styled.length() > 1) {
            styled.setSpan(new ForegroundColorSpan(tintedInk), 1, styled.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        body.setText(styled);
        body.setContentDescription(NotificationRecap.LABEL + "：" + recap);
        slot.bindApplied(body, original, originalDesc, displayString, darkSurface, xiaoAi, tintedInk);

        if (oldSnapshot != null) {
            return slot.startWaveWash(body, oldSnapshot, savedAlpha);
        }
        return false;
    }

    private void onRowPreDraw(RowState state) {
        View row = state.rowRef.get();
        if (row == null || state.mode == Mode.NONE) return;
        boolean visibleNow = isRowDisplayVisible(row);

        if (state.mode == Mode.THINKING) {
            state.contracted.syncThinkingVisibility(visibleNow);
            state.expanded.syncThinkingVisibility(visibleNow);
            if (visibleNow) state.sawOriginalWhileVisible = true;
            state.wasVisibleOnScreen = visibleNow;
            return;
        }

        if (!visibleNow) {
            if (state.wasVisibleOnScreen) {
                state.wasVisibleOnScreen = false;
                state.lastHiddenUptimeMs = SystemClock.uptimeMillis();
            }
            if (!row.isShown()) {
                state.stopTransientVisuals();
            }
            return;
        }

        if (!state.wasVisibleOnScreen) {
            state.wasVisibleOnScreen = true;
            long now = SystemClock.uptimeMillis();
            if (state.pendingFirstRevealGlint) {
                state.pendingFirstRevealGlint = false;
                state.lastRevisitGlintMs = now;
                state.startVisibleSlotBadgeGlint(550L, 0.72f);
                return;
            }
            boolean wasHiddenLongEnough = state.lastHiddenUptimeMs > 0L
                    && (now - state.lastHiddenUptimeMs) >= REVISIT_HIDDEN_DEBOUNCE_MS;
            boolean outsideCooldown = (now - state.lastRevisitGlintMs) >= REVISIT_GLINT_COOLDOWN_MS;
            if (wasHiddenLongEnough && outsideCooldown) {
                state.lastRevisitGlintMs = now;
                state.startVisibleSlotBadgeGlint(550L, 0.72f);
            }
        }
    }

    private boolean isStandaloneRow(View row) {
        if (getParent == null) return true;
        try {
            return getParent.invoke(row) == null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isRowDisplayVisible(View row) {
        if (row == null || !row.isAttachedToWindow() || !row.isShown() || row.getAlpha() < 0.25f) {
            return false;
        }
        if (getParent != null && areChildrenExpanded != null) {
            try {
                Object parent = getParent.invoke(row);
                if (parent != null && !(Boolean) areChildrenExpanded.invoke(parent)) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static boolean detectDarkSurface(TextView body) {
        Configuration cfg = body.getResources().getConfiguration();
        boolean fallbackNight = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return NebulaSummaryVisuals.isDarkSurfaceFromInk(body.getCurrentTextColor(), fallbackNight);
    }

    private static Bitmap captureTextSnapshot(TextView body) {
        int w = body.getWidth();
        int h = body.getHeight();
        if (w <= 0 || h <= 0 || (long) w * h > MAX_SNAPSHOT_PIXELS) return null;
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            body.draw(c);
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TextView findBody(SlotAccessor slot, Object layout, Notification notification, SlotState previous)
            throws ReflectiveOperationException {
        Object wrapper = slot.getWrapper.invoke(layout);
        if (wrapper == null) return null;
        Class<?> wrapperClass = wrapper.getClass();
        for (String name : new String[]{"mBigText", "mBigtext", "mText"}) {
            Field field = ModuleMain.findField(wrapperClass, name);
            if (field != null && field.get(wrapper) instanceof TextView text
                    && text.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(text.getText())) {
                return text;
            }
        }
        String name = wrapperClass.getSimpleName();
        if (name.equals("NotificationMessagingTemplateViewWrapper")
                || name.equals("NotificationConversationTemplateViewWrapper")) {
            return findMessage((View) slot.getChild.invoke(layout),
                    NotificationRecap.body(notification), previous);
        }
        return null;
    }

    private static TextView findMessage(View view, String source, SlotState previous) {
        if (view instanceof TextView text && text.getClass().getName().equals(
                "com.android.internal.widget.MessagingTextMessage")) {
            CharSequence current = text.getText();
            if (previous != null && previous.hasAppliedText() && previous.view() == text
                    && TextUtils.equals(previous.displayString, current)) {
                current = previous.originalText;
            }
            if (current != null && source.equals(current.toString().strip())) return text;
        }
        if (view instanceof ViewGroup group) {
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                View child = group.getChildAt(index);
                if (child.getVisibility() != View.VISIBLE) continue;
                TextView match = findMessage(child, source, previous);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static final class SlotAccessor {
        final Method getWrapper;
        final Method getChild;

        SlotAccessor(Method getWrapper, Method getChild) {
            this.getWrapper = getWrapper;
            this.getChild = getChild;
        }
    }

    // ---------------------------------------------------------------- per-row & per-slot state

    private static final class RowState
            implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final SystemUiNotificationRecap owner;
        final WeakReference<View> rowRef;
        final SlotState contracted = new SlotState();
        final SlotState expanded = new SlotState();

        Mode mode = Mode.NONE;
        String observedKey;
        long observedPostTime;
        String activeRecap;
        String activeInFlightFp;
        boolean sawOriginalWhileVisible;
        boolean wasUserExpanded;
        boolean wasVisibleOnScreen;
        boolean pendingFirstRevealGlint;
        long lastHiddenUptimeMs;
        long lastRevisitGlintMs;
        private boolean observersAttached;

        RowState(SystemUiNotificationRecap owner, View row) {
            this.owner = owner;
            this.rowRef = new WeakReference<>(row);
        }

        void ensureObserversAttached() {
            if (observersAttached) return;
            View row = rowRef.get();
            if (row == null) return;
            observersAttached = true;
            row.addOnAttachStateChangeListener(this);
            if (row.isAttachedToWindow()) {
                row.getViewTreeObserver().addOnPreDrawListener(this);
            }
        }

        void detachObservers() {
            if (!observersAttached) return;
            observersAttached = false;
            View row = rowRef.get();
            if (row != null) {
                row.removeOnAttachStateChangeListener(this);
                ViewTreeObserver vto = row.getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnPreDrawListener(this);
            }
        }

        void startVisibleSlotBadgeGlint(long durationMs, float peakIntensity) {
            if (!contracted.startBadgeGlintIfVisible(durationMs, peakIntensity)) {
                expanded.startBadgeGlintIfVisible(durationMs, peakIntensity);
            }
        }

        void stopTransientVisuals() {
            contracted.stopTransientVisuals();
            expanded.stopTransientVisuals();
        }

        void clearAll() {
            mode = Mode.NONE;
            activeRecap = null;
            activeInFlightFp = null;
            wasVisibleOnScreen = false;
            pendingFirstRevealGlint = false;
            contracted.restoreAll();
            expanded.restoreAll();
            detachObservers();
        }

        @Override
        public boolean onPreDraw() {
            owner.onRowPreDraw(this);
            return true;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            if (observersAttached) {
                v.getViewTreeObserver().addOnPreDrawListener(this);
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            ViewTreeObserver vto = v.getViewTreeObserver();
            if (vto.isAlive()) vto.removeOnPreDrawListener(this);
            wasVisibleOnScreen = false;
            lastHiddenUptimeMs = SystemClock.uptimeMillis();
            stopTransientVisuals();
        }
    }

    private static final class SlotState {
        private WeakReference<TextView> viewRef;
        CharSequence originalText;
        CharSequence originalContentDescription;
        String displayString;
        boolean darkSurface = true;
        boolean xiaoAiStyle = GlowStyle.isXiaoAi();
        int tintedInk;

        private RecapBadgeSpan badgeSpan;

        // Stage 1: Thinking Scanlight (drawn in body.getOverlay())
        private WeakReference<TextView> thinkingViewRef;
        private ScanOverlayDrawable scanOverlay;
        private boolean thinkingMode;
        private boolean thinkingDesiredVisible;
        private boolean thinkingScheduled;
        private float thinkingFadeAlpha;
        private float thinkingPhase;
        private ValueAnimator thinkingFadeAnimator;
        private ValueAnimator thinkingScanAnimator;
        private final Runnable startThinkingRunnable = this::beginThinkingVisuals;

        // Stage 2: Wave Wash Reveal (drawn in parent.getOverlay())
        private WeakReference<ViewGroup> washParentRef;
        private WeakReference<TextView> washBodyRef;
        private WashOverlayDrawable washOverlay;
        private Bitmap washOldBitmap;
        private float washSavedAlpha = 1f;
        private boolean washAnimating;
        private float washProgress = 1f;
        private ValueAnimator washAnimator;

        // Stage 3 & 4: Badge micro-glint (revisit / expanded-group reveal / collapse return)
        private float sparkleBloom;
        private float badgeGlintProgress = -1f;
        private float badgeGlintIntensity;
        private ValueAnimator glintAnimator;

        TextView view() {
            return viewRef == null ? null : viewRef.get();
        }

        boolean hasAppliedText() {
            return displayString != null && view() != null;
        }

        RecapBadgeSpan ensureBadgeSpan(float density) {
            if (badgeSpan == null) {
                badgeSpan = new RecapBadgeSpan(density);
            }
            return badgeSpan;
        }

        void bindApplied(TextView body, CharSequence original, CharSequence originalDesc,
                String display, boolean dark, boolean xiaoAi, int ink) {
            this.viewRef = new WeakReference<>(body);
            this.originalText = original;
            this.originalContentDescription = originalDesc;
            this.displayString = display;
            this.darkSurface = dark;
            this.xiaoAiStyle = xiaoAi;
            this.tintedInk = ink;
        }

        void restoreText() {
            TextView body = view();
            if (body != null && displayString != null && TextUtils.equals(displayString, body.getText())) {
                body.setText(originalText);
                body.setContentDescription(originalContentDescription);
            }
            viewRef = null;
            originalText = null;
            originalContentDescription = null;
            displayString = null;
        }

        void restoreAll() {
            stopTransientVisuals();
            restoreText();
        }

        void enterThinkingMode(TextView body, boolean dark, boolean xiaoAi, boolean visible) {
            TextView prev = thinkingViewRef == null ? null : thinkingViewRef.get();
            if (prev != null && prev != body) {
                stopThinkingOnly();
            }
            cancelWaveWash();
            cancelBadgeGlint();

            thinkingViewRef = new WeakReference<>(body);
            darkSurface = dark;
            xiaoAiStyle = xiaoAi;
            thinkingMode = true;
            thinkingDesiredVisible = visible;

            if (scanOverlay != null) {
                scanOverlay.updateStyle(darkSurface, xiaoAiStyle);
            }

            if (!ValueAnimator.areAnimatorsEnabled() || !visible) {
                stopThinkingAnimators();
                detachScanOverlay();
                return;
            }
            if (!thinkingScheduled && thinkingScanAnimator == null) {
                thinkingScheduled = true;
                body.removeCallbacks(startThinkingRunnable);
                body.postDelayed(startThinkingRunnable, THINKING_DEBOUNCE_MS);
            }
        }

        void syncThinkingVisibility(boolean rowVisible) {
            if (!thinkingMode) return;
            TextView body = thinkingViewRef == null ? null : thinkingViewRef.get();
            boolean visible = rowVisible && body != null && body.isAttachedToWindow() && body.isShown();
            thinkingDesiredVisible = visible;
            if (!visible) {
                stopThinkingAnimators();
                detachScanOverlay();
            } else if (ValueAnimator.areAnimatorsEnabled() && !thinkingScheduled && thinkingScanAnimator == null) {
                thinkingScheduled = true;
                body.removeCallbacks(startThinkingRunnable);
                body.postDelayed(startThinkingRunnable, THINKING_DEBOUNCE_MS);
            }
        }

        private void beginThinkingVisuals() {
            thinkingScheduled = false;
            TextView body = thinkingViewRef == null ? null : thinkingViewRef.get();
            if (!thinkingMode || !thinkingDesiredVisible || body == null
                    || !body.isAttachedToWindow() || !body.isShown()
                    || !ValueAnimator.areAnimatorsEnabled()) {
                return;
            }
            attachScanOverlay(body);

            if (thinkingFadeAnimator != null) thinkingFadeAnimator.cancel();
            thinkingFadeAnimator = ValueAnimator.ofFloat(thinkingFadeAlpha, 1f);
            thinkingFadeAnimator.setDuration(THINKING_FADE_IN_MS);
            thinkingFadeAnimator.setInterpolator(WASH_EASE);
            thinkingFadeAnimator.addUpdateListener(anim -> {
                thinkingFadeAlpha = (float) anim.getAnimatedValue();
                updateScanOverlayFrame();
            });
            thinkingFadeAnimator.start();

            if (thinkingScanAnimator != null && thinkingScanAnimator.isRunning()) return;
            thinkingScanAnimator = ValueAnimator.ofFloat(0f, 1f);
            thinkingScanAnimator.setDuration(THINKING_CYCLE_MS);
            thinkingScanAnimator.setRepeatCount(ValueAnimator.INFINITE);
            thinkingScanAnimator.setInterpolator(THINKING_EASE);
            thinkingScanAnimator.addUpdateListener(anim -> {
                thinkingPhase = (float) anim.getAnimatedValue();
                updateScanOverlayFrame();
            });
            thinkingScanAnimator.start();
        }

        private void attachScanOverlay(TextView body) {
            if (scanOverlay == null) {
                scanOverlay = new ScanOverlayDrawable(body.getResources().getDisplayMetrics().density);
            }
            scanOverlay.updateStyle(darkSurface, xiaoAiStyle);
            scanOverlay.setBounds(0, 0, body.getWidth(), body.getHeight());
            body.getOverlay().remove(scanOverlay);
            body.getOverlay().add(scanOverlay);
        }

        private void updateScanOverlayFrame() {
            TextView body = thinkingViewRef == null ? null : thinkingViewRef.get();
            if (body == null || scanOverlay == null) return;
            if (!body.isAttachedToWindow() || !body.isShown()) {
                stopThinkingAnimators();
                detachScanOverlay();
                return;
            }
            scanOverlay.setBounds(0, 0, body.getWidth(), body.getHeight());
            scanOverlay.updateFrame(thinkingFadeAlpha, thinkingPhase,
                    body.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            scanOverlay.invalidateSelf();
            body.invalidate();
        }

        private void detachScanOverlay() {
            TextView body = thinkingViewRef == null ? null : thinkingViewRef.get();
            if (body != null && scanOverlay != null) {
                body.getOverlay().remove(scanOverlay);
                body.invalidate();
            }
        }

        void stopThinkingOnly() {
            thinkingMode = false;
            thinkingDesiredVisible = false;
            stopThinkingAnimators();
            detachScanOverlay();
            thinkingViewRef = null;
        }

        private void stopThinkingAnimators() {
            thinkingScheduled = false;
            TextView body = thinkingViewRef == null ? null : thinkingViewRef.get();
            if (body != null) body.removeCallbacks(startThinkingRunnable);
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

        boolean startWaveWash(TextView body, Bitmap oldSnapshot, float savedAlpha) {
            cancelWaveWash();
            cancelBadgeGlint();
            stopThinkingOnly();

            ViewParent vp = body.getParent();
            if (!(vp instanceof ViewGroup parent) || !ValueAnimator.areAnimatorsEnabled()
                    || !body.isAttachedToWindow()) {
                oldSnapshot.recycle();
                return false;
            }

            washBodyRef = new WeakReference<>(body);
            washParentRef = new WeakReference<>(parent);
            washOldBitmap = oldSnapshot;
            washSavedAlpha = savedAlpha;
            washAnimating = true;
            washProgress = 0f;

            float density = body.getResources().getDisplayMetrics().density;
            washOverlay = new WashOverlayDrawable(body, badgeSpan, oldSnapshot, savedAlpha, density,
                    darkSurface, xiaoAiStyle);
            washOverlay.setBounds(0, 0, parent.getWidth(), parent.getHeight());

            body.setAlpha(0f);
            parent.getOverlay().add(washOverlay);

            washAnimator = ValueAnimator.ofFloat(0f, 1f);
            washAnimator.setDuration(WAVE_WASH_DURATION_MS);
            washAnimator.setInterpolator(WASH_EASE);
            washAnimator.addUpdateListener(anim -> {
                TextView activeBody = washBodyRef == null ? null : washBodyRef.get();
                ViewGroup activeParent = washParentRef == null ? null : washParentRef.get();
                if (activeBody == null || activeParent == null || !activeBody.isAttachedToWindow()) {
                    cancelWaveWash();
                    return;
                }
                washProgress = (float) anim.getAnimatedValue();
                float badgeWindow = Math.min(1f, washProgress / 0.56f);
                sparkleBloom = NebulaSummaryVisuals.smoothEnvelope(badgeWindow, 0.34f);
                badgeGlintProgress = badgeWindow < 1f ? badgeWindow : -1f;
                badgeGlintIntensity = sparkleBloom;
                if (badgeSpan != null) {
                    badgeSpan.syncAnimationState(darkSurface, xiaoAiStyle, 1f,
                            sparkleBloom, badgeGlintProgress, badgeGlintIntensity);
                }
                if (washOverlay != null) {
                    washOverlay.setBounds(0, 0, activeParent.getWidth(), activeParent.getHeight());
                    washOverlay.setProgress(washProgress);
                    washOverlay.invalidateSelf();
                }
                activeParent.invalidate();
            });
            washAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    finishWaveWash();
                }
            });
            washAnimator.start();
            return true;
        }

        private void finishWaveWash() {
            TextView body = washBodyRef == null ? null : washBodyRef.get();
            ViewGroup parent = washParentRef == null ? null : washParentRef.get();
            if (parent != null && washOverlay != null) {
                parent.getOverlay().remove(washOverlay);
                parent.invalidate();
            }
            if (body != null) {
                body.setAlpha(washSavedAlpha);
                if (badgeSpan != null) {
                    badgeSpan.syncAnimationState(darkSurface, xiaoAiStyle, 1f, 0f, -1f, 0f);
                }
                body.invalidate();
            }
            if (washOldBitmap != null) {
                if (!washOldBitmap.isRecycled()) washOldBitmap.recycle();
                washOldBitmap = null;
            }
            washOverlay = null;
            washBodyRef = null;
            washParentRef = null;
            washAnimator = null;
            washAnimating = false;
            washProgress = 1f;
            sparkleBloom = 0f;
            badgeGlintProgress = -1f;
            badgeGlintIntensity = 0f;
        }

        private void cancelWaveWash() {
            if (washAnimator != null) {
                ValueAnimator anim = washAnimator;
                washAnimator = null;
                anim.cancel();
            }
            if (washAnimating || washOverlay != null || washOldBitmap != null) {
                finishWaveWash();
            }
        }

        boolean startBadgeGlintIfVisible(long durationMs, float peakIntensity) {
            TextView body = view();
            if (body == null || badgeSpan == null || !hasAppliedText()
                    || thinkingMode || washAnimating
                    || !body.isAttachedToWindow() || !body.isShown()
                    || !ValueAnimator.areAnimatorsEnabled()) {
                return false;
            }
            cancelBadgeGlint();
            glintAnimator = ValueAnimator.ofFloat(0f, 1f);
            glintAnimator.setDuration(durationMs);
            glintAnimator.setInterpolator(GLINT_EASE);
            glintAnimator.addUpdateListener(anim -> {
                TextView active = view();
                if (active == null || !active.isAttachedToWindow() || !active.isShown()) {
                    cancelBadgeGlint();
                    return;
                }
                float t = (float) anim.getAnimatedValue();
                badgeGlintProgress = t;
                float env = NebulaSummaryVisuals.smoothEnvelope(t, 0.30f);
                badgeGlintIntensity = env * peakIntensity;
                sparkleBloom = env * peakIntensity * 0.85f;
                badgeSpan.syncAnimationState(darkSurface, xiaoAiStyle, 1f,
                        sparkleBloom, badgeGlintProgress, badgeGlintIntensity);
                active.invalidate();
            });
            glintAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    badgeGlintProgress = -1f;
                    badgeGlintIntensity = 0f;
                    sparkleBloom = 0f;
                    glintAnimator = null;
                    TextView active = view();
                    if (active != null && badgeSpan != null) {
                        badgeSpan.syncAnimationState(darkSurface, xiaoAiStyle, 1f, 0f, -1f, 0f);
                        active.invalidate();
                    }
                }
            });
            glintAnimator.start();
            return true;
        }

        private void cancelBadgeGlint() {
            if (glintAnimator != null) {
                ValueAnimator anim = glintAnimator;
                glintAnimator = null;
                anim.cancel();
            }
            badgeGlintProgress = -1f;
            badgeGlintIntensity = 0f;
            sparkleBloom = 0f;
            TextView active = view();
            if (active != null && badgeSpan != null) {
                badgeSpan.syncAnimationState(darkSurface, xiaoAiStyle, 1f, 0f, -1f, 0f);
                active.invalidate();
            }
        }

        void stopTransientVisuals() {
            stopThinkingOnly();
            cancelWaveWash();
            cancelBadgeGlint();
        }
    }

    // ---------------------------------------------------------------- optical overlays

    private static final class ScanOverlayDrawable extends Drawable {
        private final float density;
        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix shaderMatrix = new Matrix();
        private Shader scanWaveShader;
        private boolean darkSurface = true;
        private boolean xiaoAiStyle = GlowStyle.isXiaoAi();
        private float fadeAlpha;
        private float phase;
        private boolean rtl;

        ScanOverlayDrawable(float density) {
            this.density = density;
            wavePaint.setStyle(Paint.Style.FILL);
        }

        void updateStyle(boolean dark, boolean xiaoAi) {
            if (darkSurface != dark || xiaoAiStyle != xiaoAi) {
                darkSurface = dark;
                xiaoAiStyle = xiaoAi;
                scanWaveShader = null;
            }
        }

        void updateFrame(float fadeAlpha, float phase, boolean rtl) {
            this.fadeAlpha = fadeAlpha;
            this.phase = phase;
            this.rtl = rtl;
        }

        @Override
        public void draw(Canvas canvas) {
            if (fadeAlpha <= 0.01f) return;
            android.graphics.Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            if (w <= 0f || h <= 0f) return;

            if (scanWaveShader == null) {
                scanWaveShader = NebulaSummaryVisuals.createFeatheredWaveShader(
                        1f, 0f, 1f, darkSurface, xiaoAiStyle);
            }
            float bandW = Math.max(76f * density, w * 0.46f);
            float travelStart = rtl ? (w + bandW * 0.40f) : (-bandW * 0.40f);
            float travelEnd = rtl ? (-bandW * 0.40f) : (w + bandW * 0.40f);
            float centerX = travelStart + (travelEnd - travelStart) * phase;
            float edgeFade = NebulaSummaryVisuals.smoothEnvelope(phase, 0.16f);
            float intensity = fadeAlpha * (0.48f + 0.52f * edgeFade) * (darkSurface ? 1.0f : 0.92f);
            NebulaSummaryVisuals.drawTiltedNebulaWave(
                    canvas, b.left, b.top, b.right, b.bottom, b.left + centerX, bandW, intensity,
                    darkSurface, scanWaveShader, shaderMatrix, wavePaint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class WashOverlayDrawable extends Drawable {
        private final WeakReference<TextView> bodyRef;
        private final RecapBadgeSpan badgeSpan;
        private final Bitmap oldBitmap;
        private final float savedAlpha;
        private final float density;
        private final boolean darkSurface;
        private final boolean xiaoAiStyle;

        private final Paint oldPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
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
        private float progress;

        WashOverlayDrawable(TextView body, RecapBadgeSpan badgeSpan, Bitmap oldBitmap, float savedAlpha,
                float density, boolean darkSurface, boolean xiaoAiStyle) {
            this.bodyRef = new WeakReference<>(body);
            this.badgeSpan = badgeSpan;
            this.oldBitmap = oldBitmap;
            this.savedAlpha = savedAlpha;
            this.density = density;
            this.darkSurface = darkSurface;
            this.xiaoAiStyle = xiaoAiStyle;
            wavePaint.setStyle(Paint.Style.FILL);
            maskPaint.setStyle(Paint.Style.FILL);
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            trailPaint.setStyle(Paint.Style.FILL);
            trailPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        }

        void setProgress(float progress) {
            this.progress = progress;
        }

        private void ensureWaveShader() {
            if (scanWaveShader == null) {
                scanWaveShader = NebulaSummaryVisuals.createFeatheredWaveShader(
                        1f, 0f, 1f, darkSurface, xiaoAiStyle);
            }
        }

        private void ensureMaskShader(float feather, boolean rtl) {
            if (revealMaskShader != null && cachedMaskRtl == rtl
                    && Math.abs(cachedMaskFeather - feather) < 1f) {
                return;
            }
            cachedMaskFeather = feather;
            cachedMaskRtl = rtl;
            revealMaskShader = rtl
                    ? new LinearGradient(0f, 0f, feather, 0f, 0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP)
                    : new LinearGradient(0f, 0f, feather, 0f, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        }

        private void ensureTextTrailShader(float trailW) {
            if (textTrailShader != null && Math.abs(cachedTrailWidth - trailW) < 1f) {
                return;
            }
            cachedTrailWidth = trailW;
            textTrailShader = NebulaSummaryVisuals.createTextTrailGradient(trailW, darkSurface, xiaoAiStyle);
        }

        @Override
        public void draw(Canvas canvas) {
            TextView body = bodyRef.get();
            if (body == null || !body.isAttachedToWindow()) return;
            float w = body.getWidth();
            float h = body.getHeight();
            if (w <= 0f || h <= 0f) return;

            boolean rtl = body.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float offsetX = body.getLeft() + body.getTranslationX();
            float offsetY = body.getTop() + body.getTranslationY();

            int outerSave = canvas.save();
            canvas.translate(offsetX, offsetY);

            // 1. Dissolve original text snapshot during the first 44% of the wash
            float rawAlpha = progress >= 0.44f
                    ? 0f
                    : Math.max(0f, Math.min(1f, 1f - progress / 0.44f));
            if (rawAlpha > 0.005f && oldBitmap != null && !oldBitmap.isRecycled()) {
                int clipSave = canvas.save();
                canvas.clipRect(0f, 0f, w, h);
                oldPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * rawAlpha * savedAlpha)));
                canvas.drawBitmap(oldBitmap, 0f, 0f, oldPaint);
                canvas.restoreToCount(clipSave);
            }

            // 2. Compute optical wavefront span from actual layout line width
            float contentSpan = w;
            Layout layout = body.getLayout();
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
            float waveX = startX + (endX - startX) * progress;
            float waveEnvelope = NebulaSummaryVisuals.smoothEnvelope(progress, 0.24f);

            // 3. Draw new recap text uncovered by soft feathered mask + luminous highlight trail
            int layer = canvas.saveLayerAlpha(0f, 0f, w, h,
                    NebulaSummaryVisuals.clamp255(Math.round(255f * savedAlpha)));
            body.draw(canvas);

            if (waveEnvelope > 0.01f) {
                float trailW = Math.max(58f * density, contentSpan * 0.38f);
                ensureTextTrailShader(trailW);
                float trailLeft = rtl ? (waveX - trailW * 0.22f) : (waveX - trailW * 0.78f);
                shaderMatrix.reset();
                shaderMatrix.setTranslate(trailLeft, 0f);
                textTrailShader.setLocalMatrix(shaderMatrix);
                trailPaint.setShader(textTrailShader);
                trailPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * waveEnvelope)));
                canvas.drawRect(0f, 0f, w, h, trailPaint);
                trailPaint.setShader(null);
            }

            ensureMaskShader(feather, rtl);
            float maskOriginX = rtl ? waveX : (waveX - feather);
            shaderMatrix.reset();
            shaderMatrix.setTranslate(maskOriginX, 0f);
            revealMaskShader.setLocalMatrix(shaderMatrix);
            maskPaint.setShader(revealMaskShader);
            canvas.drawRect(0f, 0f, w, h, maskPaint);
            maskPaint.setShader(null);

            canvas.restoreToCount(layer);

            // 4. Soft vertically-feathered 14-degree tilted optical wavefront ribbon
            if (waveEnvelope > 0.01f) {
                float bandW = Math.max(62f * density, contentSpan * 0.38f);
                ensureWaveShader();
                NebulaSummaryVisuals.drawTiltedNebulaWave(
                        canvas, 0f, 0f, w, h, waveX, bandW,
                        waveEnvelope * (darkSurface ? 1.0f : 0.94f) * savedAlpha,
                        darkSurface, scanWaveShader, shaderMatrix, wavePaint);
            }

            canvas.restoreToCount(outerSave);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- inline single-sparkle badge span

    /**
     * Inline frameless prefix placed at index 0 of single-notification recap text:
     * [Single 4-point sparkle] + [Gradient SemiBold "AI 概括"] + [0.8dp fading vertical hairline].
     */
    private static final class RecapBadgeSpan extends ReplacementSpan {
        private final float density;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glintLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hairlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path sparklePath = new Path();
        private final Matrix shaderMatrix = new Matrix();

        private boolean darkSurface = true;
        private boolean xiaoAiStyle = GlowStyle.isXiaoAi();
        private float alphaScale = 1f;
        private float sparkleBloom;
        private float glintProgress = -1f;
        private float glintIntensity;

        private float cachedTextSize = Float.NaN;
        private boolean cachedDark;
        private boolean cachedXiaoAi;
        private float cachedSparkleR;
        private float cachedSparkleW;
        private float cachedLabelW;
        private LinearGradient sparkleShader;
        private LinearGradient labelShader;
        private LinearGradient glintShader;
        private LinearGradient hairlineShader;
        private float cachedHairlineHalfH;

        RecapBadgeSpan(float density) {
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
            cachedSparkleR = Math.max(4.7f * density, ts * 0.38f);
            cachedSparkleW = NebulaSummaryVisuals.singleSparkleWidth(cachedSparkleR);

            float badgeTextSize = ts * 0.90f;
            labelPaint.setTextSize(badgeTextSize);
            glintLabelPaint.setTextSize(badgeTextSize);
            cachedLabelW = labelPaint.measureText(NotificationRecap.LABEL);

            sparkleShader = NebulaSummaryVisuals.createDiagonalSparkleGradient(
                    0f, -cachedSparkleR, cachedSparkleW, cachedSparkleR, darkSurface, xiaoAiStyle);
            labelShader = NebulaSummaryVisuals.createHorizontalBadgeGradient(
                    0f, cachedLabelW, darkSurface, xiaoAiStyle);
            glintShader = NebulaSummaryVisuals.createGlintSheenGradient(
                    Math.max(24f * density, cachedLabelW * 0.65f), darkSurface, xiaoAiStyle);
            hairlineShader = null;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            ensureMetrics(paint);
            float gapAfterSparkle = 2.8f * density;
            float gapBeforeHairline = 4.8f * density;
            float hairlineW = Math.max(1f, 0.80f * density);
            float gapAfterHairline = 5.0f * density;
            return Math.round(cachedSparkleW + gapAfterSparkle + cachedLabelW
                    + gapBeforeHairline + hairlineW + gapAfterHairline);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            ensureMetrics(paint);
            if (alphaScale <= 0.01f) return;

            float capH = -paint.ascent() * 0.72f;
            float opticalMidY = y - capH * 0.5f;

            // 1. Self-drawn single 4-point sparkle
            shaderMatrix.reset();
            shaderMatrix.setTranslate(x, opticalMidY);
            sparkleShader.setLocalMatrix(shaderMatrix);
            NebulaSummaryVisuals.drawSingleSparkle(
                    canvas, x, opticalMidY, cachedSparkleR, darkSurface, xiaoAiStyle,
                    alphaScale, sparkleBloom, sparkleShader, fillPaint, sparklePath);

            // 2. Frameless gradient SemiBold "AI 概括"
            float labelX = x + cachedSparkleW + 2.8f * density;
            float labelBaseline = opticalMidY - (labelPaint.ascent() + labelPaint.descent()) * 0.5f;

            shaderMatrix.reset();
            shaderMatrix.setTranslate(labelX, 0f);
            labelShader.setLocalMatrix(shaderMatrix);
            labelPaint.setShader(labelShader);
            labelPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * alphaScale)));
            canvas.drawText(NotificationRecap.LABEL, labelX, labelBaseline, labelPaint);
            labelPaint.setShader(null);

            // 3. Glyph-locked specular glint sweep across "AI 概括" during wash / revisit / collapse
            if (glintProgress >= 0f && glintIntensity > 0.01f) {
                float sheenW = Math.max(24f * density, cachedLabelW * 0.65f);
                float totalSpan = cachedSparkleW + 2.8f * density + cachedLabelW;
                float sheenLeft = (x - sheenW) + (totalSpan + sheenW * 2f) * glintProgress;
                shaderMatrix.reset();
                shaderMatrix.setTranslate(sheenLeft, 0f);
                glintShader.setLocalMatrix(shaderMatrix);
                glintLabelPaint.setShader(glintShader);
                glintLabelPaint.setAlpha(NebulaSummaryVisuals.clamp255(
                        Math.round(255f * glintIntensity * alphaScale)));
                canvas.drawText(NotificationRecap.LABEL, labelX, labelBaseline, glintLabelPaint);
                glintLabelPaint.setShader(null);
            }

            // 4. Feather-thin 0.8dp vertical fading hairline separator
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
