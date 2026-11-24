package com.localai.notif;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SystemUI integration for M4 "减少干扰" (Reduce Interruptions):
 * 1. Strict mutual exclusion with native HyperOS QuietModeTile (dims native DND tile while
 *    Reduce Interruptions is active; clicking native DND switches directly to strict DND).
 * 2. Replaces the status bar "zen" slot drawable with our custom monochrome vector
 *    "星芒叠层" icon while Reduce Interruptions is active, and restores native moon icon otherwise.
 * 3. Allows VIP breakthrough notifications (and unlocked notifications under lock-screen-only)
 *    to bypass SystemUI's ZenMode heads-up and screen-wake gates and triggers native re-alert
 *    (heads-up + sound/vibration + screen wake) once per breakthrough sequence.
 */
final class SystemUiReduceInterruptions {
    private static final String TAG = "LocalAiNotif";
    private static final String NOTIFICATION = "com.android.systemui.statusbar.notification.";
    private static final String COLLECTION = NOTIFICATION + "collection.";
    private static final String COORDINATOR = COLLECTION + "coordinator.";
    private static final String INTERRUPTION = NOTIFICATION + "interruption.";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor ioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean receiverRegistered = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> bypassZenCheck = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Set<Object> quietTiles = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> zenIconViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<ModeIndicator> modeIndicators = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<View, ZenSlot> zenSlots = new WeakHashMap<>();
    private KeyguardZenRow keyguardZenRow;
    private HyperMusicCoverDate hyperMusicCoverDate;
    private final Map<String, Long> alertedBreakthroughSeqs = new ConcurrentHashMap<>();
    private final Map<String, Long> soundWakeSeqByKey = new ConcurrentHashMap<>();
    private static volatile SystemUiReduceInterruptions sInstance;

    /** True after the user swipes up on the lockscreen to reveal reduce-muted notifications
     *  for the current lock session; reset on fresh lock, screen off, or mode toggling. */
    private volatile boolean lockRevealed;
    /** Count of notifications hidden by the lockscreen quiet filter in the latest render pass. */
    private volatile int lockQuietCount;

    private volatile Context appContext;
    private volatile boolean reduceActive;
    private boolean filterIcons;
    private boolean keyguardShowing = true;
    private volatile boolean lockScreenOnly;
    private volatile long reduceDeadline;
    private boolean receivedControllerState;
    private volatile Object alertViewBoundListener;
    private volatile Method alertOnViewBound;

    private Field tileContextField;
    private Method tileRefreshState;
    private Field boolStateValue;
    private Field boolStateState;
    private Field boolStateIcon;
    private Method resourceIconGet;

    private Field iconViewSlot;
    private Method iconViewUpdateDrawable;

    private Field entrySbnField;
    private Field entryInterruptionField;
    private Field expandedBuzzBeepedField;
    private SystemUiNotificationIcons notificationIcons;

    static void install(ModuleMain module, ClassLoader loader) {
        try {
            new SystemUiReduceInterruptions(module, loader);
            module.log(Log.INFO, TAG, "Hooked SystemUI Reduce Interruptions (tile, status icon, breakthrough alerts)");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to install SystemUI Reduce Interruptions hooks", t);
        }
    }

    private SystemUiReduceInterruptions(ModuleMain module, ClassLoader loader) {
        sInstance = this;
        try {
            notificationIcons = new SystemUiNotificationIcons(module, loader, this::ensureInitialized);
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Could not install status bar notification icon filter", error);
        }
        hookQuietModeTile(module, loader);
        hookStatusBarZenIcon(module, loader);
        hookPhoneStatusBar(module, loader);
        hookKeyguardStatusBar(module, loader);
        try {
            keyguardZenRow = new KeyguardZenRow(module, loader);
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Could not hook keyguard notification-count DND row", error);
        }
        try {
            hyperMusicCoverDate = new HyperMusicCoverDate(module);
        } catch (ReflectiveOperationException | RuntimeException error) {
            module.log(Log.WARN, TAG, "Could not observe the optional lockscreen date extension", error);
        }
        hookVisualInterruptionAndAlerts(module, loader);
        SystemUiVolumeReduce.install(module, loader, this::ensureInitialized);
    }

    private void hookQuietModeTile(ModuleMain module, ClassLoader loader) {
        try {
            Class<?> tileClass = loader.loadClass("com.android.systemui.qs.tiles.QuietModeTile");
            Class<?> tileImplClass = loader.loadClass("com.android.systemui.qs.tileimpl.QSTileImpl");
            Class<?> stateClass = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
            Class<?> boolStateClass = loader.loadClass("com.android.systemui.plugins.qs.QSTile$BooleanState");
            Class<?> resourceIconClass = loader.loadClass("com.android.systemui.qs.tileimpl.QSTileImpl$ResourceIcon");
            Class<?> expandableClass = loader.loadClass("com.android.systemui.animation.Expandable");

            tileContextField = ModuleMain.findField(tileImplClass, "mContext");
            tileRefreshState = ModuleMain.findMethod(tileImplClass, "refreshState", Object.class);
            boolStateValue = ModuleMain.findField(boolStateClass, "value");
            boolStateState = ModuleMain.findField(stateClass, "state");
            boolStateIcon = ModuleMain.findField(stateClass, "icon");
            resourceIconGet = ModuleMain.findMethod(resourceIconClass, "get", int.class);

            Method handleUpdateState = ModuleMain.findMethod(
                    tileClass, "handleUpdateState", stateClass, Object.class);
            Method handleClick = ModuleMain.findMethod(tileClass, "handleClick", expandableClass);

            if (handleUpdateState != null) {
                module.hook(handleUpdateState).intercept(chain -> {
                    Object tile = chain.getThisObject();
                    trackQuietTile(tile);
                    Object res = chain.proceed();
                    if (!reduceActive) return res;
                    try {
                        Object state = chain.getArg(0);
                        if (state != null) {
                            if (boolStateValue != null) boolStateValue.setBoolean(state, false);
                            if (boolStateState != null) boolStateState.setInt(state, 1); // Tile.STATE_INACTIVE
                            Context ctx = (Context) tileContextField.get(tile);
                            if (ctx != null && boolStateIcon != null && resourceIconGet != null) {
                                int offId = ctx.getResources().getIdentifier(
                                        "ic_qs_dnd_off", "drawable", "com.android.systemui");
                                if (offId != 0) {
                                    boolStateIcon.set(state, resourceIconGet.invoke(null, offId));
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to update QuietModeTile mutual exclusion state", t);
                    }
                    return res;
                });
            }

            if (handleClick != null) {
                module.hook(handleClick).intercept(chain -> {
                    Object tile = chain.getThisObject();
                    trackQuietTile(tile);
                    if (!reduceActive) {
                        return chain.proceed();
                    }
                    // Clicking native "勿扰模式" while "减少干扰" is active switches straight
                    // to traditional strict DND instead of turning DND completely off.
                    Context ctx = (Context) tileContextField.get(tile);
                    if (ctx != null) {
                        requestSystemMode(ctx, ReduceInterruptions.MODE_STRICT_DND);
                    }
                    return null;
                });
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed to hook QuietModeTile", t);
        }
    }

    private void hookPhoneStatusBar(ModuleMain module, ClassLoader loader) {
        try {
            Class<?> phone = loader.loadClass("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView");
            Class<?> container = loader.loadClass("com.android.systemui.statusbar.views.MiuiStatusIconContainer");
            Field left = ModuleMain.findField(phone, "mStatusBarLeftContainer");
            Field right = ModuleMain.findField(phone, "mStatusBarStatusIcons");
            Field ignored = ModuleMain.findField(container, "ignoredSlots");
            Method inflate = ModuleMain.findMethod(phone, "onFinishInflate");
            Method addIgnored = ModuleMain.findMethod(container, "addIgnoredSlots", List.class);
            if (left == null || right == null || ignored == null || inflate == null || addIgnored == null) return;
            // setIgnoredSlots clears the list, then calls addIgnoredSlots even for an empty list.
            module.hook(addIgnored).intercept(chain -> {
                Object result = chain.proceed();
                View group = (View) chain.getThisObject();
                ZenSlot slot = zenSlots.get(group);
                if (slot != null) {
                    if (((List<?>) chain.getArg(0)).contains("zen")) slot.ownsZen = false;
                    slot.update(group, slot.keyguard ? reduceActive : showModeIconOnLeft());
                }
                return result;
            });
            module.hook(inflate).intercept(chain -> {
                Object result = chain.proceed();
                View bar = (View) chain.getThisObject();
                if (!(left.get(bar) instanceof LinearLayout group) || !(right.get(bar) instanceof View slots)) return result;
                int clockId = bar.getResources().getIdentifier("clock", "id", "com.android.systemui");
                if (!(group.findViewById(clockId) instanceof TextView clock) || clock.getParent() != group) return result;
                @SuppressWarnings("unchecked")
                List<String> ignoredSlots = (List<String>) ignored.get(slots);
                ZenSlot slot = new ZenSlot(ignoredSlots, false);
                zenSlots.put(slots, slot);
                slot.update(slots, showModeIconOnLeft());
                ModeIndicator icon = new ModeIndicator(clock);
                int size = icon.getDrawable().getIntrinsicWidth();
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
                params.gravity = Gravity.CENTER_VERTICAL;
                params.setMarginEnd(Math.round(2 * bar.getResources().getDisplayMetrics().density));
                group.addView(icon, group.indexOfChild(clock) + 1, params);
                modeIndicators.add(icon);
                icon.setVisibility(showModeIconOnLeft() ? View.VISIBLE : View.GONE);
                ensureInitialized(bar.getContext());
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Could not attach Reduce Interruptions status bar indicator", error);
        }
    }

    private void hookKeyguardStatusBar(ModuleMain module, ClassLoader loader) {
        try {
            Class<?> barClass = loader.loadClass("com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView");
            Field icons = ModuleMain.findField(barClass, "mStatusIconContainer");
            if (icons == null) return;
            Field ignored = ModuleMain.findField(icons.getType(), "ignoredSlots");
            Method inflate = ModuleMain.findMethod(barClass, "onFinishInflate");
            if (ignored == null || inflate == null) return;
            module.hook(inflate).intercept(chain -> {
                Object result = chain.proceed();
                if (icons.get(chain.getThisObject()) instanceof View group) {
                    @SuppressWarnings("unchecked")
                    List<String> ignoredSlots = (List<String>) ignored.get(group);
                    ZenSlot slot = new ZenSlot(ignoredSlots, true);
                    zenSlots.put(group, slot);
                    slot.update(group, reduceActive);
                    ensureInitialized(group.getContext());
                }
                return result;
            });
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Could not attach keyguard zen status icon", error);
        }
    }

    private static final class ZenSlot {
        final List<String> ignoredSlots;
        final boolean keyguard;
        boolean ownsZen;

        ZenSlot(List<String> ignoredSlots, boolean keyguard) {
            this.ignoredSlots = ignoredSlots;
            this.keyguard = keyguard;
        }

        void update(View group, boolean hide) {
            if (hide && !ignoredSlots.contains("zen")) {
                ignoredSlots.add("zen");
                ownsZen = true;
                group.requestLayout();
            } else if (!hide && ownsZen) {
                ignoredSlots.remove("zen");
                ownsZen = false;
                group.requestLayout();
            }
        }
    }

    private void updateStatusIndicators() {
        boolean left = showModeIconOnLeft();
        for (ModeIndicator icon : modeIndicators) icon.setVisibility(left ? View.VISIBLE : View.GONE);
        for (Map.Entry<View, ZenSlot> entry : zenSlots.entrySet()) {
            ZenSlot slot = entry.getValue();
            slot.update(entry.getKey(), slot.keyguard ? reduceActive : left);
        }
    }

    private static final class ModeIndicator extends ImageView implements ViewTreeObserver.OnPreDrawListener {
        final TextView clock;
        int tint;

        ModeIndicator(TextView clock) {
            super(clock.getContext());
            this.clock = clock;
            setContentDescription("减少干扰");
            setImageDrawable(ReduceInterruptions.createStatusBarDrawable(getContext()));
            tint = clock.getCurrentTextColor();
            getDrawable().setTint(tint);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            super.onDetachedFromWindow();
        }

        @Override public boolean onPreDraw() {
            if (getVisibility() == VISIBLE && clock.getCurrentTextColor() != tint) {
                tint = clock.getCurrentTextColor();
                getDrawable().setTint(tint);
            }
            return true;
        }
    }

    private void hookStatusBarZenIcon(ModuleMain module, ClassLoader loader) {
        try {
            Class<?> iconViewClass = loader.loadClass("com.android.systemui.statusbar.StatusBarIconView");
            Class<?> statusBarIconClass = loader.loadClass("com.android.internal.statusbar.StatusBarIcon");
            iconViewSlot = ModuleMain.findField(iconViewClass, "mSlot");
            iconViewUpdateDrawable = ModuleMain.findMethod(iconViewClass, "updateDrawable", boolean.class);
            Method getIcon = ModuleMain.findMethod(iconViewClass, "getIcon", statusBarIconClass);

            if (getIcon != null && iconViewSlot != null) {
                module.hook(getIcon).intercept(chain -> {
                    View view = (View) chain.getThisObject();
                    String slot = (String) iconViewSlot.get(view);
                    if ("zen".equals(slot)) {
                        ensureInitialized(view.getContext());
                        synchronized (zenIconViews) {
                            zenIconViews.add(view);
                        }
                        if (reduceActive) {
                            return ReduceInterruptions.createStatusBarDrawable(view.getContext());
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed to hook StatusBarIconView for custom zen icon", t);
        }
    }

    private void hookVisualInterruptionAndAlerts(ModuleMain module, ClassLoader loader) {
        try {
            Class<?> entryClass = loader.loadClass(COLLECTION + "NotificationEntry");
            Class<?> expandedClass = loader.loadClass(NOTIFICATION + "ExpandedNotification");
            entrySbnField = ModuleMain.findField(entryClass, "mSbn");
            entryInterruptionField = ModuleMain.findField(entryClass, "interruption");
            expandedBuzzBeepedField = ModuleMain.findField(expandedClass, "buzzBeeped");

            // 1. Scoped ZenMode bypass used ONLY while evaluating VIP breakthrough or unlocked
            //    lock-screen-only heads-up & screen wakeup.
            Class<?> zenExtClass = loader.loadClass("com.android.systemui.statusbar.policy.ZenModeControllerExtKt");
            Class<?> zenCtrlClass = loader.loadClass("com.android.systemui.statusbar.policy.ZenModeController");
            Method isZenModeOn = ModuleMain.findMethod(zenExtClass, "isZenModeOn", zenCtrlClass);
            if (isZenModeOn != null) {
                module.hook(isZenModeOn).intercept(chain -> {
                    if (Boolean.TRUE.equals(bypassZenCheck.get())) {
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                });
            }

            // 2. Allow breakthrough / unlocked lock-screen-only entries past MIUI's shouldPeek DND check.
            Class<?> injectorClass = loader.loadClass(
                    INTERRUPTION + "VisualInterruptionDecisionProviderImplInjector");
            Method shouldPeek = ModuleMain.findMethod(injectorClass, "shouldPeek", entryClass);
            if (shouldPeek != null) {
                module.hook(shouldPeek).intercept(chain -> {
                    Object entry = chain.getArg(0);
                    if (reduceActive && shouldBypassVisualSuppression(entry)) {
                        return runWithZenBypass(chain::proceed);
                    }
                    return chain.proceed();
                });
            }

            // 3. Allow breakthrough / unlocked lock-screen-only entries past AOSP Peek & Pulse DND filters.
            for (String suppressorName : new String[]{
                    INTERRUPTION + "PeekDndSuppressor",
                    INTERRUPTION + "PulseEffectSuppressor"}) {
                try {
                    Class<?> cls = loader.loadClass(suppressorName);
                    Method shouldSuppress = ModuleMain.findMethod(cls, "shouldSuppress", entryClass);
                    if (shouldSuppress != null) {
                        module.hook(shouldSuppress).intercept(chain -> {
                            Object entry = chain.getArg(0);
                            if (reduceActive && shouldBypassVisualSuppression(entry)) {
                                return Boolean.FALSE;
                            }
                            return chain.proceed();
                        });
                    }
                } catch (Throwable inner) {
                    Log.w(TAG, "Failed to hook " + suppressorName, inner);
                }
            }

            // 4. Hook HyperOS AlertCoordinator view-bound listener to capture the live MiuiAlertManager
            //    trigger AND allow screen wake when a VIP breakthrough fires while screen is off.
            try {
                Class<?> alertLambdaClass = loader.loadClass(
                        COORDINATOR + "AlertCoordinator$$ExternalSyntheticLambda0");
                Method onViewBound = null;
                for (Method m : alertLambdaClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == entryClass) {
                        m.setAccessible(true);
                        onViewBound = m;
                        break;
                    }
                }
                if (onViewBound != null) {
                    alertOnViewBound = onViewBound;
                    module.hook(onViewBound).intercept(chain -> {
                        alertViewBoundListener = chain.getThisObject();
                        Object entry = chain.getArg(0);
                        StatusBarNotification sbn = entrySbn(entry);
                        if (reduceActive && sbn != null) {
                            long seq = ReduceInterruptions.alertSeq(sbn);
                            if (seq > 0L && ReduceInterruptions.isBreakthrough(sbn)) {
                                Long prevSeq = soundWakeSeqByKey.put(sbn.getKey(), seq);
                                if (Long.valueOf(seq).equals(prevSeq)) {
                                    // Already alerted once for this exact breakthrough sequence.
                                    return null;
                                }
                                return runWithZenBypass(chain::proceed);
                            }
                            if (shouldBypassVisualSuppression(entry)) {
                                return runWithZenBypass(chain::proceed);
                            }
                        }
                        return chain.proceed();
                    });
                }
            } catch (Throwable inner) {
                Log.w(TAG, "Failed to hook AlertCoordinator listener", inner);
            }

            // 5. Hook HeadsUpCoordinator update listener: when system_server promotes an initially
            //    silenced notification to VIP breakthrough (new EXTRA_ALERT_SEQ), trigger full
            //    native heads-up + sound/vibration + lockscreen wake ONCE.
            Class<?> listenerClass = loader.loadClass(
                    COORDINATOR + "HeadsUpCoordinator$mNotifCollectionListener$1");
            Class<?> updateSourceClass = loader.loadClass(
                    COLLECTION + "notifcollection.UpdateSource");
            Method onEntryUpdated = ModuleMain.findMethod(
                    listenerClass, "onEntryUpdated", entryClass, updateSourceClass);
            Method onEntryRemoved = ModuleMain.findMethod(
                    listenerClass, "onEntryRemoved", entryClass, int.class);

            if (onEntryUpdated != null) {
                module.hook(onEntryUpdated).intercept(chain -> {
                    Object entry = chain.getArg(0);
                    StatusBarNotification sbn = entrySbn(entry);
                    if (!reduceActive || sbn == null) {
                        return chain.proceed();
                    }
                    long seq = ReduceInterruptions.alertSeq(sbn);
                    if (seq > 0L && ReduceInterruptions.isBreakthrough(sbn)) {
                        String key = sbn.getKey();
                        Long prev = alertedBreakthroughSeqs.get(key);
                        if (prev == null || seq > prev) {
                            alertedBreakthroughSeqs.put(key, seq);
                            // Clear prior interruption latch so HeadsUpCoordinator pops the banner.
                            if (entryInterruptionField != null) {
                                entryInterruptionField.setBoolean(entry, false);
                            }
                            Object res = runWithZenBypass(chain::proceed);
                            triggerBreakthroughSoundAndWake(entry, key, seq);
                            return res;
                        }
                    }
                    return chain.proceed();
                });
            }

            if (onEntryRemoved != null) {
                module.hook(onEntryRemoved).intercept(chain -> {
                    StatusBarNotification sbn = entrySbn(chain.getArg(0));
                    if (sbn != null) {
                        alertedBreakthroughSeqs.remove(sbn.getKey());
                        soundWakeSeqByKey.remove(sbn.getKey());
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed to hook SystemUI interruption/alert pipeline", t);
        }
    }

    private void triggerBreakthroughSoundAndWake(Object entry, String key, long seq) {
        Object listener = alertViewBoundListener;
        Method invokeBound = alertOnViewBound;
        if (listener == null || invokeBound == null || entry == null) return;
        // Wait briefly so if BindEventManager already invokes AlertCoordinator.onViewBound$1
        // on the updated row, we do not double-trigger sound/vibration.
        mainHandler.postDelayed(() -> {
            if (!reduceActive) return;
            if (Long.valueOf(seq).equals(soundWakeSeqByKey.get(key))) return;
            StatusBarNotification currentSbn = entrySbn(entry);
            if (currentSbn == null || ReduceInterruptions.alertSeq(currentSbn) != seq
                    || !ReduceInterruptions.isBreakthrough(currentSbn)) {
                return;
            }
            try {
                if (expandedBuzzBeepedField != null) {
                    expandedBuzzBeepedField.setBoolean(currentSbn, false);
                }
                // onViewBound hook atomically records seq into soundWakeSeqByKey and bypasses ZenMode.
                invokeBound.invoke(listener, entry);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to trigger breakthrough sound/wake for " + key, t);
            }
        }, 120L);
    }

    private boolean shouldBypassVisualSuppression(Object entry) {
        StatusBarNotification sbn = entrySbn(entry);
        if (sbn == null) return false;
        if (ReduceInterruptions.isBreakthrough(sbn)) {
            return true;
        }
        return lockScreenOnly && isUnlockedAndInteractive();
    }

    private boolean isUnlockedAndInteractive() {
        Context ctx = appContext;
        if (ctx == null) return false;
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        boolean interactive = pm == null || pm.isInteractive();
        boolean locked = km != null && km.isKeyguardLocked();
        return interactive && !locked;
    }

    private StatusBarNotification entrySbn(Object entry) {
        if (entry == null || entrySbnField == null) return null;
        try {
            return (StatusBarNotification) entrySbnField.get(entry);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void trackQuietTile(Object tile) {
        if (tile == null) return;
        synchronized (quietTiles) {
            quietTiles.add(tile);
        }
        try {
            if (tileContextField != null) {
                ensureInitialized((Context) tileContextField.get(tile));
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureInitialized(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        appContext = app;
        if (!receiverRegistered.compareAndSet(false, true)) return;

        mainHandler.post(() -> {
            KeyguardManager keyguard = app.getSystemService(KeyguardManager.class);
            if (keyguard == null) return;
            try {
                keyguard.addKeyguardLockedStateListener(mainHandler::post, this::onKeyguardChanged);
            } catch (RuntimeException error) {
                Log.w(TAG, "Using screen broadcasts for status bar icon placement", error);
                IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                screen.addAction(Intent.ACTION_SCREEN_ON);
                screen.addAction(Intent.ACTION_USER_PRESENT);
                app.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        onKeyguardChanged(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || keyguard.isKeyguardLocked());
                    }
                }, screen, null, mainHandler, Context.RECEIVER_NOT_EXPORTED);
            }
            onKeyguardChanged(keyguard.isKeyguardLocked());
        });
        try {
            // Screen off ends the reveal session: notifications hide again on the next lockscreen.
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (reduceActive && lockRevealed) {
                        lockRevealed = false;
                        SystemUiPrioritySection.requestListInvalidation();
                    }
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_OFF), null, mainHandler, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register screen-off reveal reset", t);
        }

        try {
            IntentFilter stateActions = new IntentFilter(ReduceInterruptions.ACTION_STATE_CHANGED);
            stateActions.addAction(ReduceInterruptions.ACTION_ICON_FILTER);
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ReduceInterruptions.ACTION_ICON_FILTER.equals(intent.getAction())) {
                        if (notificationIcons != null) {
                            notificationIcons.setAllowedVersions(intent.getStringArrayListExtra(
                                    ReduceInterruptions.EXTRA_ALLOWED_VERSIONS));
                        }
                        return;
                    }
                    receivedControllerState = true;
                    boolean active = intent.getBooleanExtra(ReduceInterruptions.EXTRA_ACTIVE, false);
                    boolean filter = intent.getBooleanExtra(Config.KEY_REDUCE_FILTER_ICONS, filterIcons);
                    boolean lockOnly = intent.getBooleanExtra(
                            ReduceInterruptions.EXTRA_LOCK_SCREEN_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY);
                    long deadline = intent.getLongExtra(ReduceInterruptions.EXTRA_DEADLINE, 0L);
                    boolean changed = (active != reduceActive) || (lockOnly != lockScreenOnly)
                            || (deadline != reduceDeadline) || (filter != filterIcons);
                    reduceActive = active;
                    filterIcons = filter;
                    lockScreenOnly = lockOnly;
                    reduceDeadline = deadline;
                    if (!active) {
                        alertedBreakthroughSeqs.clear();
                        soundWakeSeqByKey.clear();
                    }
                    boolean suppressDndExit = intent.getBooleanExtra(
                            ReduceInterruptions.EXTRA_SUPPRESS_DND_EXIT, false);
                    SystemUiVolumeReduce.applyReduceState(active, deadline, suppressDndExit);
                    if (intent.getBooleanExtra(ReduceInterruptions.EXTRA_ANNOUNCE, false)) {
                        SystemUiReduceIsland.onReduceChanged(active);
                    }
                    if (changed) {
                        if (keyguardZenRow != null) keyguardZenRow.setReduceActive(reduceActive);
                        lockRevealed = false;
                        if (keyguardShowing) {
                            SystemUiPrioritySection.requestListInvalidation();
                        }
                        refreshUiAfterStateChange();
                    }
                }
            }, stateActions,
                    "android.permission.STATUS_BAR_SERVICE", mainHandler, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register SystemUI ReduceInterruptions receiver", t);
        }

        // Seed initial state in case SystemUI restarted while Reduce Interruptions was active.
        ioExecutor.execute(() -> {
            try {
                Bundle cfg = ModuleMain.callProvider(app, "get_config", null);
                if (cfg != null) {
                    boolean cfgReduce = cfg.getBoolean(Config.KEY_REDUCE_ENABLED, false);
                    boolean cfgLockOnly = cfg.getBoolean(
                            Config.KEY_REDUCE_LOCK_SCREEN_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY);
                    int zen = android.provider.Settings.Global.getInt(
                            app.getContentResolver(), "zen_mode", 0);
                    mainHandler.post(() -> {
                        if (receivedControllerState) return;
                        reduceActive = cfgReduce && zen == 1;
                        filterIcons = cfg.getBoolean(Config.KEY_REDUCE_FILTER_ICONS, Config.DEFAULT_REDUCE_FILTER_ICONS);
                        lockScreenOnly = cfgLockOnly;
                        SystemUiVolumeReduce.applyReduceState(reduceActive, 0L, false);
                        if (reduceActive && keyguardShowing) SystemUiPrioritySection.requestListInvalidation();
                        if (keyguardZenRow != null) keyguardZenRow.setReduceActive(reduceActive);
                        refreshUiAfterStateChange();
                    });
                }
            } catch (Throwable ignored) {
            }
        });

        // Ask the controller for full live state (incl. timed deadline); the provider seed
        // above is only a fallback for the case the broadcast path is unavailable.
        ioExecutor.execute(() -> {
            try {
                Intent query = new Intent(ReduceInterruptions.ACTION_REQUEST_MODE)
                        .setPackage("android")
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra(ReduceInterruptions.EXTRA_MODE, ReduceInterruptions.MODE_QUERY);
                app.sendBroadcast(query);
            } catch (Throwable ignored) {
            }
        });
    }

    private boolean showModeIconOnLeft() {
        return reduceActive && filterIcons && !keyguardShowing;
    }

    private void onKeyguardChanged(boolean showing) {
        boolean was = keyguardShowing;
        if (was == showing) return;
        keyguardShowing = showing;
        updateStatusIndicators();
        if (hyperMusicCoverDate != null) hyperMusicCoverDate.refresh();
        if (reduceActive) {
            // Fresh lock session: reduce-muted notifications hidden again until the next swipe-up.
            lockRevealed = false;
            SystemUiPrioritySection.requestListInvalidation();
        }
    }

    private void refreshUiAfterStateChange() {
        mainHandler.post(() -> {
            updateStatusIndicators();
            if (hyperMusicCoverDate != null) hyperMusicCoverDate.refresh();
            ArrayList<Object> tilesCopy;
            synchronized (quietTiles) {
                tilesCopy = new ArrayList<>(quietTiles);
            }
            for (Object tile : tilesCopy) {
                if (tile != null && tileRefreshState != null) {
                    try {
                        tileRefreshState.invoke(tile, new Object[]{null});
                    } catch (Throwable ignored) {
                    }
                }
            }

            ArrayList<View> iconsCopy;
            synchronized (zenIconViews) {
                iconsCopy = new ArrayList<>(zenIconViews);
            }
            for (View iconView : iconsCopy) {
                if (iconView != null && iconViewUpdateDrawable != null) {
                    try {
                        iconViewUpdateDrawable.invoke(iconView, Boolean.TRUE);
                        iconView.invalidate();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Lockscreen quiet filter (M4 lock experience): while Reduce Interruptions is active on
    // the keyguard, notifications that are not important stay out of the lockscreen render
    // list until the user swipes up; SystemUiPrioritySection performs the actual filtering
    // and pill-header injection, this class owns the state.
    // ---------------------------------------------------------------------------------

    /** True when the lockscreen quiet filter should drop non-important entries right now. */
    static boolean lockQuietHiddenActive() {
        SystemUiReduceInterruptions self = sInstance;
        return self != null && self.reduceActive && self.keyguardShowing && !self.lockRevealed;
    }

    /** True when the "减少干扰" pill line should sit below the clock (mode shown on keyguard). */
    static boolean lockPillShowing() {
        SystemUiReduceInterruptions self = sInstance;
        return self != null && self.reduceActive && self.keyguardShowing;
    }

    static boolean lockPillRevealed() {
        SystemUiReduceInterruptions self = sInstance;
        return self != null && self.lockRevealed;
    }

    /** Notifications hidden from the lockscreen by the latest quiet-filtered render pass. */
    static int lockQuietCount() {
        SystemUiReduceInterruptions self = sInstance;
        return self == null ? 0 : self.lockQuietCount;
    }

    static void setLockQuietCount(int count) {
        SystemUiReduceInterruptions self = sInstance;
        if (self != null) self.lockQuietCount = count;
    }

    /** Swipe-up on keyguard: reveal the muted notifications for this lock session. */
    static void requestLockReveal() {
        SystemUiReduceInterruptions self = sInstance;
        if (self == null || !self.reduceActive || !self.keyguardShowing || self.lockRevealed) return;
        self.lockRevealed = true;
        Log.i(TAG, "Lockscreen reveal: showing reduce-muted notifications");
        SystemUiPrioritySection.requestListInvalidation();
    }

    /**
     * Decides whether a notification must stay visible on the reduced-disturbance lockscreen:
     * important-marked, AI VIP breakthrough, or categories the system DND itself would let
     * through (call/alarm/reminder/event/navigation/media playback). Null-safe fail-open.
     */
    static boolean isLockScreenImportant(StatusBarNotification sbn) {
        if (sbn == null) return true;
        Notification notification = sbn.getNotification();
        if (notification == null) return true;
        if (PriorityNotification.isMarked(sbn) || ReduceInterruptions.isBreakthrough(sbn)) return true;
        String category = notification.category;
        if (Notification.CATEGORY_CALL.equals(category)
                || Notification.CATEGORY_ALARM.equals(category)
                || Notification.CATEGORY_REMINDER.equals(category)
                || Notification.CATEGORY_EVENT.equals(category)
                || Notification.CATEGORY_NAVIGATION.equals(category)
                || Notification.CATEGORY_TRANSPORT.equals(category)) {
            return true;
        }
        if (notification.extras != null) {
            // HyperMusicCover routes session notifications to its media surface, including custom templates.
            if (notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class) != null) {
                return true;
            }
            String template = notification.extras.getString(Notification.EXTRA_TEMPLATE);
            if (template != null && template.contains("MediaStyle")) return true;
        }
        return false;
    }

    private void requestSystemMode(Context ctx, int mode) {
        ioExecutor.execute(() -> {
            try {
                Intent intent = new Intent(ReduceInterruptions.ACTION_REQUEST_MODE)
                        .setPackage("android")
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra(ReduceInterruptions.EXTRA_MODE, mode);
                ctx.sendBroadcast(intent);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to request system ReduceInterruptions mode=" + mode, t);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    private <T> T runWithZenBypass(ThrowingSupplier<T> action) throws Throwable {
        Boolean previous = bypassZenCheck.get();
        bypassZenCheck.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            bypassZenCheck.set(previous);
        }
    }
}
