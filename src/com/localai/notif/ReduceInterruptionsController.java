package com.localai.notif;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.Condition;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import io.github.libxposed.api.XposedInterface.Invoker;

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
    private static final int KEEP_DURATION = -1;
    private static final int OP_PLAY_AUDIO = 28;
    private static final int STREAM_ASSISTANT = 11;

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
    private final Invoker<?, Method> nativeShouldIntercept;
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
    private volatile boolean filterIcons;
    private volatile boolean normalSounds;
    private ReduceNotificationSounds notificationSounds;
    private final Map<String, Object> deferredSoundRecords = new ConcurrentHashMap<>();
    private Method refreshAudioStreams;
    private Object audioService;
    private final AtomicBoolean iconRefreshPending = new AtomicBoolean();
    private Set<String> lastIconVersions;
    private final Map<String, String> grantedIconVersions = new HashMap<>();
    private volatile boolean configInitialized;
    /** RTC epoch millis when timed Reduce Interruptions expires; 0 = untimed. */
    private volatile long reduceDeadline;

    // Native Zen normally expires first. This also handles an unavailable native condition.
    private final Runnable deadlineExpiryRunnable = () -> {
        long deadline = reduceDeadline;
        if (reduceActive && deadline > 0L && System.currentTimeMillis() >= deadline) {
            Log.i(TAG, "Timed Reduce Interruptions expired; turning off");
            applyMode(ReduceInterruptions.MODE_OFF, true, KEEP_DURATION);
        }
    };

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
        Method shouldIntercept = ModuleMain.findMethod(zenHelperClass, "shouldIntercept", recordClass);
        nativeShouldIntercept = shouldIntercept == null ? null
                : module.getInvoker(shouldIntercept).setType(Invoker.Type.ORIGIN);
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
        installSoundPolicy(module, loader, zenHelperClass);
        try {
            notificationSounds = new ReduceNotificationSounds(module, loader, recordClass,
                    () -> normalSounds && isEffectiveNow(), (record, sbn) -> {
                        trackInterceptedCandidate(sbn);
                        if (pendingFingerprints.containsKey(sbn.getKey())) {
                            deferredSoundRecords.put(sbn.getKey(), record);
                        }
                    });
        } catch (Throwable error) {
            Log.w(TAG, "Could not install Reduce Interruptions notification sound handling", error);
        }
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

    private void installSoundPolicy(ModuleMain module, ClassLoader loader, Class<?> zenClass) {
        try {
            Method apply = ModuleMain.findMethod(zenClass, "applyRestrictions", boolean.class, IntPredicate.class, int.class);
            if (apply != null) {
                module.hook(apply).intercept(chain -> {
                    if (!(boolean) chain.getArg(0) || (int) chain.getArg(2) != OP_PLAY_AUDIO || !reduceActive || !normalSounds) {
                        return chain.proceed();
                    }
                    IntPredicate original = (IntPredicate) chain.getArg(1);
                    IntPredicate mute = usage -> !isAppOrSystemPlayback(usage) && original.test(usage);
                    return chain.proceed(new Object[]{true, mute, OP_PLAY_AUDIO});
                });
            }
            Class<?> audio = loader.loadClass("com.android.server.audio.AudioService");
            Method affected = ModuleMain.findMethod(audio, "isStreamAffectedByCurrentZen", int.class);
            if (affected == null) return;
            module.hook(affected).intercept(chain -> {
                int stream = (int) chain.getArg(0);
                if (reduceActive && normalSounds && (stream == AudioManager.STREAM_MUSIC
                        || stream == AudioManager.STREAM_SYSTEM || stream == STREAM_ASSISTANT
                        || stream == AudioManager.STREAM_NOTIFICATION)) return false;
                return chain.proceed();
            });
            Method updateMute = ModuleMain.findMethod(audio, "updateStreamMuteFromRingerMode");
            if (updateMute != null) module.deoptimize(updateMute);
            refreshAudioStreams = ModuleMain.findMethod(audio, "updateRingerModeMutedStreams");
        } catch (Throwable error) {
            Log.w(TAG, "Could not install Reduce Interruptions audio policy", error);
        }
    }

    static boolean isAppOrSystemPlayback(int usage) {
        return switch (usage) {
            case AudioAttributes.USAGE_UNKNOWN, AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME,
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.USAGE_ASSISTANT,
                    AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> true;
            default -> false;
        };
    }

    private void registerReceivers() {
        // SystemUI (QuietModeTile / volume panel button) requests mode changes with optional countdown.
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                int mode = intent.getIntExtra(ReduceInterruptions.EXTRA_MODE, ReduceInterruptions.MODE_OFF);
                if (mode == ReduceInterruptions.MODE_QUERY) {
                    broadcastState(safeGetZenMode(), false, false, true);
                    return;
                }
                int duration = intent.getIntExtra(ReduceInterruptions.EXTRA_DURATION_MINUTES, KEEP_DURATION);
                applyMode(mode, true, duration);
            }
        }, new IntentFilter(ReduceInterruptions.ACTION_REQUEST_MODE),
                "android.permission.STATUS_BAR_SERVICE", handler, Context.RECEIVER_EXPORTED);
        IntentFilter screen = new IntentFilter(Intent.ACTION_USER_PRESENT);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (filterIcons && reduceActive && lockScreenOnly) publishIconFilter(false);
            }
        }, screen, null, handler, Context.RECEIVER_NOT_EXPORTED);
        if (keyguardManager != null) {
            try {
                keyguardManager.addKeyguardLockedStateListener(handler::post, locked -> {
                    if (lockScreenOnly) onNotificationListChanged();
                });
            } catch (RuntimeException error) {
                Log.w(TAG, "Keyguard listener unavailable, using screen broadcasts", error);
            }
        }
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
        boolean nextFilter = settings.getBoolean(Config.KEY_REDUCE_FILTER_ICONS, Config.DEFAULT_REDUCE_FILTER_ICONS);
        boolean iconsChanged = filterIcons != nextFilter;
        filterIcons = nextFilter;
        boolean nextSounds = settings.getBoolean(Config.KEY_REDUCE_NORMAL_SOUNDS, false);
        boolean soundsChanged = normalSounds != nextSounds;
        normalSounds = nextSounds;

        if (!configInitialized) {
            configInitialized = true;
            int currentZen = safeGetZenMode();
            if (targetEnabled && currentZen == ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
                // Re-attach cleanly after reboot / soft-restart if ZenMode is already active.
                snapshotExistingNotifications();
                reduceActive = true;
                scheduleReduceDeadline(adoptNativeRemainDeadline());
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
            applyMode(targetEnabled ? ReduceInterruptions.MODE_REDUCE : ReduceInterruptions.MODE_OFF,
                    false, KEEP_DURATION);
        } else if ((lockOnlyChanged || soundsChanged) && reduceActive) {
            safeApplyRestrictions();
            broadcastState(safeGetZenMode());
        } else if (iconsChanged) {
            broadcastState(safeGetZenMode());
        }
    }

    private void handleExternalZenChanged(int zenMode) {
        if (reduceActive && zenMode != ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            Log.i(TAG, "System ZenMode changed externally to " + zenMode + "; exiting Reduce Interruptions");
            deactivateStateOnly();
            if (normalSounds) safeApplyRestrictions();
            syncProviderStateAsync(false);
            broadcastState(zenMode, true, zenMode == ZEN_MODE_OFF);
        } else {
            // Keep SystemUI / TileService aware of latest ZenMode even when Reduce is inactive.
            broadcastState(zenMode);
        }
    }

    private void applyMode(int mode, boolean syncProvider, int durationMinutes) {
        switch (mode) {
            case ReduceInterruptions.MODE_REDUCE: {
                if (durationMinutes == 0 && !reduceActive) return;
                boolean starting = !reduceActive;
                if (starting) {
                    snapshotExistingNotifications();
                    pendingFingerprints.clear();
                    resolvedFingerprints.clear();
                    realertDeadlines.clear();
                    reduceActive = true;
                }
                long deadline = durationMinutes < 0 ? adoptNativeRemainDeadline()
                        : durationMinutes == 0 ? 0L
                        : System.currentTimeMillis() + durationMinutes * 60_000L;
                scheduleReduceDeadline(deadline);
                // Publish ownership before changing the native Zen state.
                broadcastState(ZEN_MODE_IMPORTANT_INTERRUPTIONS, starting, false);
                if (durationMinutes >= 0) {
                    Uri condition = durationMinutes > 0 ? nativeCountdownCondition(durationMinutes) : null;
                    setSystemZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, condition, "localai_reduce_timer");
                } else if (safeGetZenMode() != ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
                    if (!setZenViaSoundMode(true)) {
                        setSystemZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, "localai_reduce_on");
                    }
                } else {
                    safeApplyRestrictions();
                }
                if (syncProvider) syncProviderStateAsync(true);
                broadcastState(safeGetZenMode());
                Log.i(TAG, "Reduce Interruptions enabled (lockScreenOnly=" + lockScreenOnly
                        + ", deadline=" + reduceDeadline + ")");
                break;
            }

            case ReduceInterruptions.MODE_STRICT_DND:
            case ReduceInterruptions.MODE_OFF:
            default:
                boolean ending = reduceActive;
                deactivateStateOnly();
                if (mode == ReduceInterruptions.MODE_OFF) {
                    boolean zenWasOn = safeGetZenMode() != ZEN_MODE_OFF;
                    if (zenWasOn) {
                        // SoundMode 关闭会同时清掉 MIUI 的原生倒计时存储。
                        if (!setZenViaSoundMode(false)) {
                            setSystemZenMode(ZEN_MODE_OFF, null, "localai_reduce_off");
                        }
                    }
                    if (syncProvider) syncProviderStateAsync(false);
                    broadcastState(safeGetZenMode(), ending, ending && zenWasOn);
                    Log.i(TAG, "Reduce Interruptions disabled (no retroactive re-alerts)");
                } else {
                    // Always re-assert manual ZenMode WITHOUT a countdown condition so the
                    // previous Reduce timer cannot silently end the new strict DND session.
                    if (!setZenViaSoundMode(true)) {
                        setSystemZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, "localai_switch_strict_dnd");
                    }
                    if (syncProvider) syncProviderStateAsync(false);
                    broadcastState(safeGetZenMode(), ending, false);
                    Log.i(TAG, "Switched from Reduce Interruptions to traditional strict DND");
                }
                break;

        }
    }

    /**
     * Stores the expiry (0 = untimed) and arms the fallback handler timer. The real
     * countdown is owned by MIUI's native storage (ExtraNotificationManager /
     * SoundMode.setZenModeForDuration), which ends ZenMode itself; the handler timer
     * only guarantees Reduce exits even if that native path never fires.
     */
    private void scheduleReduceDeadline(long deadlineRtc) {
        cancelFallbackDeadline();
        reduceDeadline = deadlineRtc;
        if (deadlineRtc > 0L) {
            handler.postDelayed(deadlineExpiryRunnable,
                    Math.max(1_000L, deadlineRtc - System.currentTimeMillis()));
        }
    }

    private void cancelFallbackDeadline() {
        handler.removeCallbacks(deadlineExpiryRunnable);
    }


    private void deactivateStateOnly() {
        reduceActive = false;
        reduceDeadline = 0L;
        cancelFallbackDeadline();
        preExistingFingerprints.clear();
        pendingFingerprints.clear();
        resolvedFingerprints.clear();
        realertDeadlines.clear();
        deferredSoundRecords.clear();
        grantedIconVersions.clear();
    }

    private void snapshotExistingNotifications() {
        grantedIconVersions.clear();
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

    void onNotificationListChanged() {
        if (!filterIcons || !reduceActive || !iconRefreshPending.compareAndSet(false, true)) return;
        handler.post(() -> {
            iconRefreshPending.set(false);
            publishIconFilter(false);
        });
    }

    private void publishIconFilter(boolean force) {
        Set<String> allowed = null;
        if (filterIcons && isEffectiveNow()) {
            allowed = new HashSet<>();
            if (safeGetZenMode() == ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
                synchronized (lock) {
                    try {
                        for (Object record : recordsByKey.values()) {
                            StatusBarNotification sbn = safeGetSbn(record);
                            if (sbn == null) continue;
                            String version = GroupSummaryData.versionPart(sbn);
                            boolean vip = version.equals(grantedIconVersions.get(sbn.getKey()));
                            if (vip || (nativeShouldIntercept != null
                                    && Boolean.FALSE.equals(nativeShouldIntercept.invoke(zenModeHelper, record)))) {
                                allowed.add(version);
                            }
                        }
                    } catch (ReflectiveOperationException error) {
                        Log.w(TAG, "Could not read native status icon exemptions", error);
                    }
                }
            }
        }
        if (!force && java.util.Objects.equals(allowed, lastIconVersions)) return;
        sendIconFilter(allowed);
    }

    private void sendIconFilter(Set<String> allowed) {
        Intent update = new Intent(ReduceInterruptions.ACTION_ICON_FILTER)
                .setPackage("com.android.systemui")
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
        if (allowed != null) {
            update.putStringArrayListExtra(ReduceInterruptions.EXTRA_ALLOWED_VERSIONS, new ArrayList<>(allowed));
        }
        try {
            context.sendBroadcast(update, "android.permission.STATUS_BAR_SERVICE");
            lastIconVersions = allowed;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not publish status icon filter", error);
        }
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
        deferredSoundRecords.keySet().retainAll(activeKeys);
        grantedIconVersions.keySet().retainAll(activeKeys);
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
        Object deferredSound = deferredSoundRecords.remove(key);

        if (!isEffectiveNow() || !classified || !vip || record == null || sbn == null) {
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
            if (deferredSound != null && !(boolean) recordIsIntercepted.invoke(record)) {
                if (normalSounds && deferredSound == record) notificationSounds.playApprovedSound(record, key);
                return;
            }
            long seq = alertSeqGen.incrementAndGet();
            ReduceInterruptions.markBreakthrough(sbn, seq);
            recordSetIntercepted.invoke(record, Boolean.FALSE);
            recordSetSuppressedVisualEffects.invoke(record, 0);
            realertDeadlines.put(key, SystemClock.elapsedRealtime() + REALERT_ALLOW_WINDOW_MS);

            // Notify SystemUI WITHOUT forcing FLAG_ONLY_ALERT_ONCE so HeadsUpCoordinator
            // & AlertCoordinator execute native heads-up + sound/vibrate + lockscreen wake.
            notifyPosted.invoke(listeners, record, record);
            grantedIconVersions.put(key, GroupSummaryData.versionPart(sbn));
            onNotificationListChanged();
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

    private void setSystemZenMode(int zenMode, Uri condition, String reason) {
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
                    condition,
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

    /**
     * MIUI 原生勿扰开关。优先走 Settings 的
     * {@code android.provider.MiuiSettings$SoundMode.setZenModeOn(Context, boolean, String)},
     * 与音量面板月亮/计时 pill 和设置页勿扰态完全同一存储;失败返回 false 由调用方退化。
     */
    private boolean setZenViaSoundMode(boolean on) {
        long token = Binder.clearCallingIdentity();
        try {
            Class<?> soundMode = Class.forName("android.provider.MiuiSettings$SoundMode");
            Method setZenModeOn = soundMode.getMethod(
                    "setZenModeOn", Context.class, boolean.class, String.class);
            setZenModeOn.invoke(null, context, on, "miui_manual");
            safeApplyRestrictions();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "SoundMode.setZenModeOn(" + on + ") unavailable, falling back", t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private Uri nativeCountdownCondition(int minutes) {
        try {
            Class<?> config = Class.forName("android.service.notification.ZenModeConfig");
            Method toTimeCondition = config.getMethod(
                    "toTimeCondition", Context.class, int.class, int.class, boolean.class);
            int user = zenUserField != null ? zenUserField.getInt(zenModeHelper) : 0;
            Condition condition = (Condition) toTimeCondition.invoke(null, context, minutes, user, true);
            return condition.id;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Native Zen countdown unavailable", e);
            return null;
        }
    }

    /** Mirrors an existing native timer when upgrading strict DND or reattaching after startup. */
    private long adoptNativeRemainDeadline() {
        try {
            Class<?> extra = Class.forName("android.app.ExtraNotificationManager");
            Method getRemainTime = extra.getMethod("getRemainTime", Context.class);
            long remain = ((Number) getRemainTime.invoke(null, context)).longValue();
            if (remain > 0L) {
                return System.currentTimeMillis() + remain;
            }
        } catch (Throwable t) {
            Log.w(TAG, "ExtraNotificationManager.getRemainTime unavailable", t);
        }
        return 0L;
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
            if (refreshAudioStreams != null) {
                if (audioService == null) {
                    audioService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                            .invoke(null, Context.AUDIO_SERVICE);
                }
                if (refreshAudioStreams.getDeclaringClass().isInstance(audioService)) refreshAudioStreams.invoke(audioService);
            }
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
        broadcastState(zenMode, false, false);
    }

    private void broadcastState(int zenMode, boolean announce, boolean suppressDndExit) {
        broadcastState(zenMode, announce, suppressDndExit, false);
    }

    private void broadcastState(int zenMode, boolean announce, boolean suppressDndExit, boolean forceIconUpdate) {
        publishIconFilter(forceIconUpdate);
        boolean active = reduceActive;
        boolean lockOnly = lockScreenOnly;
        UserHandle allUsers = userHandleAll();
        for (String pkg : new String[]{"com.android.systemui", "com.localai.notif"}) {
            try {
                Intent intent = new Intent(ReduceInterruptions.ACTION_STATE_CHANGED)
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(ReduceInterruptions.EXTRA_ACTIVE, active)
                        .putExtra(Config.KEY_REDUCE_FILTER_ICONS, filterIcons)
                        .putExtra(ReduceInterruptions.EXTRA_LOCK_SCREEN_ONLY, lockOnly)
                        .putExtra(ReduceInterruptions.EXTRA_ZEN_MODE, zenMode)
                        .putExtra(ReduceInterruptions.EXTRA_DEADLINE, reduceDeadline)
                        .putExtra(ReduceInterruptions.EXTRA_ANNOUNCE, announce)
                        .putExtra(ReduceInterruptions.EXTRA_SUPPRESS_DND_EXIT, suppressDndExit);
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
