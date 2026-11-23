package com.localai.notif;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * system_server controller for M4 "减少干扰" (Reduce Interruptions).
 *
 * Implemented as an intelligent tier of HyperOS ZenMode (Do Not Disturb):
 * - Activating Reduce Interruptions turns on system ZenMode (ZEN_MODE_IMPORTANT_INTERRUPTIONS)
 *   so native calls, alarms, repeat callers, and ongoing events inherit system DND policy.
 * - Incoming non-exempt notifications are silently intercepted at arrival without hiding them
 *   from the lockscreen or notification shade (SUPPRESSED_EFFECT_NOTIFICATION_LIST cleared).
 * - NotificationSummaries immediately classifies pending records on-device (ignoring summary
 *   wait/threshold/screen-on throttles). Only model-confirmed VIP items break through and
 *   receive full re-alert (sound/vibration + heads-up + lockscreen wake).
 * - System scheduled DND rules remain traditional strict DND; Reduce Interruptions is toggled
 *   manually from the Quick Settings tile or LocalAiNotif settings.
 */
final class ReduceInterruptionsController {
    private static final String TAG = "LocalAiNotif";
    private static final int ZEN_MODE_OFF = 0;
    private static final int ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1;
    private static final int ORIGIN_USER_IN_SYSTEMUI = 5;
    private static final int MUTE_REASON_SILENT_UPDATE = 64;
    private static final long REALERT_ALLOW_WINDOW_MS = 6_000L;

    private final Context context;
    private final Object service;
    private final Object lock;
    private final Object listeners;
    private final Field recordsField;
    private final Map<?, ?> recordsByKey;
    private final Method getSbn;
    private final Method notifyPosted;
    private final Method recordIsIntercepted;
    private final Method recordSetIntercepted;
    private final Method recordSetSuppressedVisualEffects;

    private final Object zenModeHelper;
    private final Method zenGetZenMode;
    private final Method zenSetManualZenMode;
    private final Method zenApplyRestrictions;
    private final Field zenUserField;

    private final PowerManager powerManager;
    private final KeyguardManager keyguardManager;
    private final Handler handler;
    private final Executor syncExecutor = Executors.newSingleThreadExecutor();
    private final Runnable onNeedClassify;
    private final Consumer<Boolean> onLocalStateMirrored;

    private final AtomicLong alertSeqGen = new AtomicLong(1L);
    private final AtomicBoolean internalZenMutation = new AtomicBoolean(false);

    /** Active state mirrored across system_server hooks (read concurrently). */
    private volatile boolean reduceActive;
    private volatile boolean lockScreenOnly = Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY;
    private volatile boolean configInitialized;

    /**
     * Snapshot of (key -> fingerprint) already present when Reduce Interruptions turned ON.
     * Pre-existing notifications are never retroactively re-alerted.
     */
    private final Map<String, String> preExistingFingerprints = new ConcurrentHashMap<>();

    /**
     * Newly arrived or content-updated intercepted records awaiting LLM classification.
     * Value is the itemFingerprint observed when intercepted.
     */
    private final Map<String, String> pendingFingerprints = new ConcurrentHashMap<>();

    /** Fingerprints already classified & resolved (either silenced or breakthrough-alerted). */
    private final Map<String, String> resolvedFingerprints = new ConcurrentHashMap<>();

    /**
     * Short-lived allowance so NotificationAttentionHelper.shouldMuteNotificationLocked
     * does not reject our one-time VIP re-alert with MUTE_REASON_SILENT_UPDATE (64)
     * when the original notification carried FLAG_ONLY_ALERT_ONCE.
     */
    private final Map<String, Long> realertDeadlines = new ConcurrentHashMap<>();

    ReduceInterruptionsController(
            ModuleMain module,
            Context context,
            Object service,
            ClassLoader loader,
            Object lock,
            Field recordsField,
            Map<?, ?> recordsByKey,
            Object listeners,
            Method getSbn,
            Method notifyPosted,
            Handler handler,
            Runnable onNeedClassify,
            Consumer<Boolean> onLocalStateMirrored) throws Exception {
        this.context = context;
        this.service = service;
        this.lock = lock;
        this.recordsField = recordsField;
        this.recordsByKey = recordsByKey;
        this.listeners = listeners;
        this.getSbn = getSbn;
        this.notifyPosted = notifyPosted;
        this.handler = handler;
        this.onNeedClassify = onNeedClassify;
        this.onLocalStateMirrored = onLocalStateMirrored;
        this.powerManager = context.getSystemService(PowerManager.class);
        this.keyguardManager = context.getSystemService(KeyguardManager.class);

        Class<?> nmsClass = service.getClass();
        Class<?> recordClass = loader.loadClass("com.android.server.notification.NotificationRecord");
        recordIsIntercepted = ModuleMain.findMethod(recordClass, "isIntercepted");
        recordSetIntercepted = ModuleMain.findMethod(recordClass, "setIntercepted", boolean.class);
        recordSetSuppressedVisualEffects = ModuleMain.findMethod(
                recordClass, "setSuppressedVisualEffects", int.class);

        Field zenHelperField = ModuleMain.findField(nmsClass, "mZenModeHelper");
        zenModeHelper = zenHelperField != null ? zenHelperField.get(service) : null;
        if (zenModeHelper == null) {
            throw new IllegalStateException("mZenModeHelper unavailable on NotificationManagerService");
        }
        Class<?> zenHelperClass = zenModeHelper.getClass();
        zenGetZenMode = ModuleMain.findMethod(zenHelperClass, "getZenMode");
        zenSetManualZenMode = ModuleMain.findMethod(zenHelperClass, "setManualZenMode",
                UserHandle.class, int.class, Uri.class, int.class, String.class,
                String.class, boolean.class, int.class);
        zenApplyRestrictions = ModuleMain.findMethod(zenHelperClass, "applyRestrictions");
        zenUserField = ModuleMain.findField(zenHelperClass, "mUser");

        installHooks(module, loader, recordClass, zenHelperClass);
        registerReceivers();
        Log.i(TAG, "ReduceInterruptionsController ready");
    }

    private void installHooks(ModuleMain module, ClassLoader loader,
            Class<?> recordClass, Class<?> zenHelperClass) {
        // 1. Honor our independent "仅锁屏时生效" switch instead of MIUI's system DND setting.
        try {
            Class<?> zenStubImpl = loader.loadClass("com.android.server.notification.ZenModeStubImpl");
            Method shouldInterceptUnlocked = ModuleMain.findMethod(
                    zenStubImpl, "shouldInterceptWhenUnLocked", Context.class, boolean.class);
            if (shouldInterceptUnlocked != null) {
                module.hook(shouldInterceptUnlocked).intercept(chain -> {
                    if (!reduceActive) return chain.proceed();
                    boolean isForce = (boolean) chain.getArg(1);
                    if (!lockScreenOnly) return Boolean.TRUE;
                    if (isForce) return Boolean.FALSE;
                    return isDeviceLockedOrScreenOff();
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook ZenModeStubImpl.shouldInterceptWhenUnLocked", t);
        }

        // 2. Allow AI VIP breakthroughs to pass ZenModeHelper.shouldIntercept, and mark newly
        //    intercepted eligible notifications for immediate on-device classification.
        try {
            Method shouldIntercept = ModuleMain.findMethod(zenHelperClass, "shouldIntercept", recordClass);
            if (shouldIntercept != null) {
                module.hook(shouldIntercept).intercept(chain -> {
                    if (!reduceActive) return chain.proceed();
                    Object record = chain.getArg(0);
                    StatusBarNotification sbn = safeGetSbn(record);
                    if (sbn != null && ReduceInterruptions.isBreakthrough(sbn)) {
                        return Boolean.FALSE;
                    }
                    boolean intercepted = (boolean) chain.proceed();
                    if (intercepted && sbn != null) {
                        trackInterceptedCandidate(sbn);
                    }
                    return intercepted;
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook ZenModeHelper.shouldIntercept", t);
        }

        // 3. Keep non-VIP notifications visible on lockscreen and in the notification shade.
        try {
            Class<?> extractorClass = loader.loadClass("com.android.server.notification.ZenModeExtractor");
            Method process = ModuleMain.findMethod(extractorClass, "process", recordClass);
            if (process != null) {
                module.hook(process).intercept(chain -> {
                    Object res = chain.proceed();
                    if (!reduceActive) return res;
                    Object record = chain.getArg(0);
                    if (record != null) {
                        boolean intercepted = (boolean) recordIsIntercepted.invoke(record);
                        recordSetSuppressedVisualEffects.invoke(record,
                                intercepted ? ReduceInterruptions.SUPPRESSED_VISUAL_EFFECTS : 0);
                    }
                    return res;
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook ZenModeExtractor.process", t);
        }

        // 4. Ensure system_server ("android") and SystemUI remain exempt in MIUI AppOps
        //    USAGE_NOTIFICATION restrictions so VIP breakthrough sound/vibration can play.
        try {
            Class<?> injectorClass = loader.loadClass("com.android.server.notification.ZenModeHelperInjector");
            Method applyMiuiRestrictions = ModuleMain.findMethod(
                    injectorClass, "applyMiuiRestrictions", zenHelperClass, AppOpsManager.class);
            Method applyRestriction = ModuleMain.findMethod(
                    injectorClass, "applyRestriction", boolean.class, int.class,
                    AppOpsManager.class, String[].class);
            if (applyMiuiRestrictions != null && applyRestriction != null) {
                module.hook(applyMiuiRestrictions).intercept(chain -> {
                    Object res = chain.proceed();
                    if (!reduceActive) return res;
                    try {
                        AppOpsManager appOps = (AppOpsManager) chain.getArg(1);
                        if (appOps != null) {
                            String[] exempt = new String[]{
                                    "com.android.systemui",
                                    "android",
                                    "com.android.cellbroadcastreceiver",
                                    "com.android.server.telecom"
                            };
                            // Usage 5 = AudioAttributes.USAGE_NOTIFICATION
                            applyRestriction.invoke(null, Boolean.FALSE, 5, appOps, exempt);
                        }
                    } catch (Throwable inner) {
                        Log.w(TAG, "Failed to ensure ReduceInterruptions notification AppOps exemption", inner);
                    }
                    return res;
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook ZenModeHelperInjector.applyMiuiRestrictions", t);
        }

        // 5. Bypass MIUI QuietUtils.checkQuiet in NotificationManagerServiceImpl.isSilenceMode
        //    for any record that ZenMode did NOT intercept (VIP breakthroughs, unlocked records
        //    under lock-screen-only mode, and native DND exemptions).
        try {
            Class<?> nmsImplClass = loader.loadClass("com.android.server.notification.NotificationManagerServiceImpl");
            Method isSilenceMode = ModuleMain.findMethod(nmsImplClass, "isSilenceMode", recordClass);
            if (isSilenceMode != null) {
                module.hook(isSilenceMode).intercept(chain -> {
                    if (!reduceActive) return chain.proceed();
                    Object record = chain.getArg(0);
                    if (record != null) {
                        StatusBarNotification sbn = safeGetSbn(record);
                        if (ReduceInterruptions.isBreakthrough(sbn)
                                || !((boolean) recordIsIntercepted.invoke(record))) {
                            return Boolean.FALSE;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook NotificationManagerServiceImpl.isSilenceMode", t);
        }

        // 6. Allow one-time VIP breakthrough re-alert through NotificationAttentionHelper even
        //    if the original notification carried FLAG_ONLY_ALERT_ONCE.
        try {
            Class<?> attentionClass = loader.loadClass("com.android.server.notification.NotificationAttentionHelper");
            Class<?> signalsClass = loader.loadClass("com.android.server.notification.NotificationAttentionHelper$Signals");
            Method shouldMute = ModuleMain.findMethod(
                    attentionClass, "shouldMuteNotificationLocked",
                    recordClass, signalsClass, boolean.class);
            if (shouldMute != null) {
                module.hook(shouldMute).intercept(chain -> {
                    int reason = (int) chain.proceed();
                    if (reason != MUTE_REASON_SILENT_UPDATE || !reduceActive) {
                        return reason;
                    }
                    Object record = chain.getArg(0);
                    StatusBarNotification sbn = safeGetSbn(record);
                    if (sbn != null && consumeRealertAllowance(sbn.getKey())
                            && ReduceInterruptions.isBreakthrough(sbn)) {
                        // Re-evaluate with FLAG_ONLY_ALERT_ONCE temporarily cleared so all
                        // remaining mute checks (silent flag, listener hints, rate limit, etc.)
                        // still apply faithfully.
                        Notification n = sbn.getNotification();
                        int savedFlags = n.flags;
                        n.flags = savedFlags & ~Notification.FLAG_ONLY_ALERT_ONCE;
                        try {
                            return chain.proceed();
                        } finally {
                            n.flags = savedFlags;
                        }
                    }
                    return reason;
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook NotificationAttentionHelper.shouldMuteNotificationLocked", t);
        }

        // 7. Observe external ZenMode changes (e.g., user turns off DND from Settings or switches
        //    to stricter Total Silence / Alarms Only; scheduled rules remain traditional DND).
        try {
            Method dispatchZen = ModuleMain.findMethod(zenHelperClass, "dispatchOnZenModeChanged");
            if (dispatchZen != null) {
                module.hook(dispatchZen).intercept(chain -> {
                    Object res = chain.proceed();
                    if (!internalZenMutation.get()) {
                        int zen = safeGetZenMode();
                        handler.post(() -> handleExternalZenChanged(zen));
                    }
                    return res;
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook ZenModeHelper.dispatchOnZenModeChanged", t);
        }
    }

    private void registerReceivers() {
        // SystemUI (QuietModeTile) requests switching Reduce Interruptions -> Strict DND / Off.
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                int mode = intent.getIntExtra(ReduceInterruptions.EXTRA_MODE, ReduceInterruptions.MODE_OFF);
                handler.post(() -> applyMode(mode, true));
            }
        }, new IntentFilter(ReduceInterruptions.ACTION_REQUEST_MODE),
                "android.permission.STATUS_BAR_SERVICE", handler, Context.RECEIVER_EXPORTED);
    }

    /** Called on the worker handler whenever AsyncConfig loads or refreshes. */
    void onConfigUpdated(Bundle settings) {
        if (settings == null) return;
        boolean targetEnabled = settings.getBoolean(
                Config.KEY_REDUCE_ENABLED, Config.DEFAULT_REDUCE_ENABLED);
        boolean targetLockOnly = settings.getBoolean(
                Config.KEY_REDUCE_LOCK_SCREEN_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY);

        boolean lockOnlyChanged = (targetLockOnly != lockScreenOnly);
        lockScreenOnly = targetLockOnly;

        if (!configInitialized) {
            configInitialized = true;
            int currentZen = safeGetZenMode();
            if (targetEnabled && currentZen == ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
                // Re-attach cleanly after reboot / soft-restart if ZenMode is already active.
                snapshotExistingNotifications();
                reduceActive = true;
                safeApplyRestrictions();
                broadcastState(currentZen);
            } else if (targetEnabled) {
                // ZenMode is not active after boot -> stay consistent with system ZenMode (OFF).
                reduceActive = false;
                syncProviderStateAsync(false);
                broadcastState(currentZen);
            } else {
                reduceActive = false;
                broadcastState(currentZen);
            }
            return;
        }

        if (targetEnabled != reduceActive) {
            applyMode(targetEnabled ? ReduceInterruptions.MODE_REDUCE : ReduceInterruptions.MODE_OFF, false);
        } else if (lockOnlyChanged && reduceActive) {
            safeApplyRestrictions();
            broadcastState(safeGetZenMode());
        }
    }

    private void handleExternalZenChanged(int zenMode) {
        if (reduceActive && zenMode != ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            Log.i(TAG, "System ZenMode changed externally to " + zenMode + "; exiting Reduce Interruptions");
            deactivateStateOnly();
            syncProviderStateAsync(false);
            broadcastState(zenMode);
        } else {
            // Keep SystemUI / TileService aware of latest ZenMode even when Reduce is inactive.
            broadcastState(zenMode);
        }
    }

    private void applyMode(int mode, boolean syncProvider) {
        int targetZen;
        switch (mode) {
            case ReduceInterruptions.MODE_REDUCE:
                if (!reduceActive) {
                    snapshotExistingNotifications();
                    pendingFingerprints.clear();
                    resolvedFingerprints.clear();
                    realertDeadlines.clear();
                    reduceActive = true;
                }
                // Notify SystemUI before ZenMode flips so the native DND tile & moon icon do not flash.
                broadcastState(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
                targetZen = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                if (safeGetZenMode() != targetZen) {
                    setSystemZenMode(targetZen, "localai_reduce_on");
                } else {
                    safeApplyRestrictions();
                }
                if (syncProvider) syncProviderStateAsync(true);
                broadcastState(safeGetZenMode());
                Log.i(TAG, "Reduce Interruptions enabled (lockScreenOnly=" + lockScreenOnly + ")");
                break;

            case ReduceInterruptions.MODE_STRICT_DND:
                deactivateStateOnly();
                targetZen = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                if (safeGetZenMode() != targetZen) {
                    setSystemZenMode(targetZen, "localai_switch_strict_dnd");
                } else {
                    safeApplyRestrictions();
                }
                if (syncProvider) syncProviderStateAsync(false);
                broadcastState(safeGetZenMode());
                Log.i(TAG, "Switched from Reduce Interruptions to traditional strict DND");
                break;

            case ReduceInterruptions.MODE_OFF:
            default:
                deactivateStateOnly();
                if (safeGetZenMode() != ZEN_MODE_OFF) {
                    setSystemZenMode(ZEN_MODE_OFF, "localai_reduce_off");
                }
                if (syncProvider) syncProviderStateAsync(false);
                broadcastState(safeGetZenMode());
                Log.i(TAG, "Reduce Interruptions disabled (no retroactive re-alerts)");
                break;
        }
    }

    private void deactivateStateOnly() {
        reduceActive = false;
        preExistingFingerprints.clear();
        pendingFingerprints.clear();
        resolvedFingerprints.clear();
        realertDeadlines.clear();
    }

    private void snapshotExistingNotifications() {
        preExistingFingerprints.clear();
        synchronized (lock) {
            try {
                List<?> list = (List<?>) recordsField.get(service);
                if (list == null) return;
                for (Object record : list) {
                    StatusBarNotification sbn = safeGetSbn(record);
                    if (sbn != null) {
                        preExistingFingerprints.put(sbn.getKey(), GroupSummaryData.itemFingerprint(sbn));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to snapshot existing notifications", t);
            }
        }
    }

    private void trackInterceptedCandidate(StatusBarNotification sbn) {
        if (!reduceActive || !isEffectiveNow()) return;
        Notification notification = sbn.getNotification();
        if (!ReduceInterruptions.isEligibleForClassification(notification)) return;

        String key = sbn.getKey();
        String fp = GroupSummaryData.itemFingerprint(sbn);
        if (fp.equals(preExistingFingerprints.get(key))) return;
        if (fp.equals(resolvedFingerprints.get(key))) return;
        if (fp.equals(pendingFingerprints.get(key))) return;

        // Content updated or brand-new notification arrived under Reduce Interruptions.
        preExistingFingerprints.remove(key);
        pendingFingerprints.put(key, fp);
        handler.post(onNeedClassify);
    }

    boolean isActive() {
        return reduceActive;
    }

    boolean isEffectiveNow() {
        return reduceActive && (!lockScreenOnly || isDeviceLockedOrScreenOff());
    }

    boolean isPending(String key, String fingerprint) {
        return reduceActive && fingerprint != null && fingerprint.equals(pendingFingerprints.get(key));
    }

    boolean hasPendingNotifications() {
        return reduceActive && !pendingFingerprints.isEmpty();
    }

    void retainActiveKeys(Set<String> activeKeys) {
        if (activeKeys == null) return;
        preExistingFingerprints.keySet().retainAll(activeKeys);
        pendingFingerprints.keySet().retainAll(activeKeys);
        resolvedFingerprints.keySet().retainAll(activeKeys);
        realertDeadlines.keySet().retainAll(activeKeys);
    }

    /**
     * Called on the worker handler under synchronized(lock) when model classification returns
     * (or fails/times out: classified=false -> fail-closed silent).
     */
    void onClassifiedLocked(String key, String fingerprint, boolean classified, boolean vip,
            Object record, StatusBarNotification sbn) {
        String pendingFp = pendingFingerprints.get(key);
        if (pendingFp == null || !pendingFp.equals(fingerprint)) {
            return;
        }
        pendingFingerprints.remove(key, fingerprint);
        resolvedFingerprints.put(key, fingerprint);

        if (!reduceActive || !classified || !vip || record == null || sbn == null) {
            return; // Fail-closed: remain silently in lockscreen & notification shade.
        }
        if (recordsByKey.get(key) != record) {
            return; // Notification was dismissed or replaced before LLM finished.
        }
        if (!fingerprint.equals(GroupSummaryData.itemFingerprint(sbn))) {
            return; // Content changed while LLM was running; wait for newer pass.
        }
        if (ReduceInterruptions.isBreakthrough(sbn)) {
            return; // Already granted breakthrough for this exact fingerprint.
        }

        try {
            long seq = alertSeqGen.incrementAndGet();
            ReduceInterruptions.markBreakthrough(sbn, fingerprint, seq);
            recordSetIntercepted.invoke(record, Boolean.FALSE);
            recordSetSuppressedVisualEffects.invoke(record, 0);
            realertDeadlines.put(key, SystemClock.elapsedRealtime() + REALERT_ALLOW_WINDOW_MS);

            // Notify SystemUI WITHOUT forcing FLAG_ONLY_ALERT_ONCE so HeadsUpCoordinator
            // & AlertCoordinator execute native heads-up + sound/vibrate + lockscreen wake.
            notifyPosted.invoke(listeners, record, record);
            Log.i(TAG, "Reduce Interruptions VIP breakthrough re-alert: " + key + " (seq=" + seq + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to re-alert VIP breakthrough notification: " + key, t);
        }
    }

    private boolean consumeRealertAllowance(String key) {
        Long deadline = realertDeadlines.remove(key);
        return deadline != null && SystemClock.elapsedRealtime() <= deadline;
    }

    private boolean isDeviceLockedOrScreenOff() {
        if (powerManager != null && !powerManager.isInteractive()) {
            return true;
        }
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    private int safeGetZenMode() {
        try {
            return (int) zenGetZenMode.invoke(zenModeHelper);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read ZenModeHelper.getZenMode()", t);
            return ZEN_MODE_OFF;
        }
    }

    private void setSystemZenMode(int zenMode, String reason) {
        long token = Binder.clearCallingIdentity();
        internalZenMutation.set(true);
        try {
            int userId = 0;
            if (zenUserField != null) {
                userId = zenUserField.getInt(zenModeHelper);
            }
            UserHandle user = userHandleOf(userId);
            zenSetManualZenMode.invoke(
                    zenModeHelper,
                    user,
                    zenMode,
                    null,
                    ORIGIN_USER_IN_SYSTEMUI,
                    reason,
                    "com.localai.notif",
                    Boolean.TRUE,
                    Process.SYSTEM_UID);
            safeApplyRestrictions();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to set system ZenMode to " + zenMode, t);
        } finally {
            internalZenMutation.set(false);
            Binder.restoreCallingIdentity(token);
        }
    }

    private static UserHandle userHandleOf(int userId) {
        try {
            Method ofMethod = UserHandle.class.getMethod("of", int.class);
            Object handle = ofMethod.invoke(null, userId);
            if (handle instanceof UserHandle) {
                return (UserHandle) handle;
            }
        } catch (Throwable ignored) {
        }
        return UserHandle.getUserHandleForUid(Math.max(0, userId) * 100_000);
    }

    private static UserHandle userHandleAll() {
        try {
            Field allField = UserHandle.class.getField("ALL");
            Object handle = allField.get(null);
            if (handle instanceof UserHandle) {
                return (UserHandle) handle;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void safeApplyRestrictions() {
        if (zenApplyRestrictions == null) return;
        long token = Binder.clearCallingIdentity();
        try {
            zenApplyRestrictions.invoke(zenModeHelper);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to re-apply ZenMode restrictions", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private StatusBarNotification safeGetSbn(Object record) {
        if (record == null) return null;
        try {
            return (StatusBarNotification) getSbn.invoke(record);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void broadcastState(int zenMode) {
        boolean active = reduceActive;
        boolean lockOnly = lockScreenOnly;
        UserHandle allUsers = userHandleAll();
        for (String pkg : new String[]{"com.android.systemui", "com.localai.notif"}) {
            try {
                Intent intent = new Intent(ReduceInterruptions.ACTION_STATE_CHANGED)
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra(ReduceInterruptions.EXTRA_ACTIVE, active)
                        .putExtra(ReduceInterruptions.EXTRA_LOCK_SCREEN_ONLY, lockOnly)
                        .putExtra(ReduceInterruptions.EXTRA_ZEN_MODE, zenMode);
                if (allUsers != null) {
                    Method sendAsUser = Context.class.getMethod(
                            "sendBroadcastAsUser", Intent.class, UserHandle.class);
                    sendAsUser.invoke(context, intent, allUsers);
                } else {
                    context.sendBroadcast(intent);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to broadcast ReduceInterruptions state to " + pkg, t);
            }
        }
    }

    private void syncProviderStateAsync(boolean active) {
        if (onLocalStateMirrored != null) {
            onLocalStateMirrored.accept(active);
        }
        syncExecutor.execute(() -> {
            Bundle input = new Bundle();
            input.putBoolean(Config.KEY_REDUCE_ENABLED, active);
            Bundle reply = ModuleMain.callProvider(context, Config.METHOD_SYNC_REDUCE_STATE, input);
            if (reply == null) {
                Log.w(TAG, "Failed to sync ReduceInterruptions state back to provider");
            }
        });
    }
}
