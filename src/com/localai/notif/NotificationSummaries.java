package com.localai.notif;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class NotificationSummaries {
    private static final String TAG = "LocalAiNotif";
    // 分类开关:key 与默认值统一放 Config,与 provider get_config 透出的一致。
    private static final boolean DEFAULT_CLASSIFY_ENABLED = Config.DEFAULT_CLASSIFY_ENABLED;
    // 噪音静默幂等标记:extras 里存指纹,重复分类(相同内容)不再处理。
    private static final String EXTRA_NOISE_MUTED = "localai.noise_muted";
    private static final Comparator<Entry> REQUEST_ORDER =
            Comparator.comparingLong((Entry entry) -> entry.sbn.getNotification().when)
                    .thenComparing(entry -> entry.sbn.getKey());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Context context;
    private final Handler handler;
    private final Object service;
    private final Object hyperService;
    private final Object lock;
    private final Object listeners;
    private final Field records;
    private final Map<?, ?> recordsByKey;
    private final Method getSbn;
    private final Method notifyPosted;
    private final Field recordImportance;

    private final Map<?, ?> allowedPackages;
    private final AsyncConfig config;
    private final PowerManager power;
    private final Map<String, Long> presented = new HashMap<>();
    private final Map<String, String> attempted = new HashMap<>();
    private final Map<String, String> submittedItems = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, CachedGroup> cachedGroups = new HashMap<>();
    private final Map<String, PriorityDecision> priorityDecisions = new HashMap<>();
    private final Set<String> reduceInFlight = new HashSet<>();
    private final SummaryScheduler scheduler;
    private final PriorityGroups priorityGroups;
    private final ReduceInterruptionsController reduceController;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private volatile boolean priorityEnabled = Config.DEFAULT_PRIORITY_ENABLED;
    private boolean configReady;
    private boolean keepAliveBound;
    private final Runnable retryKeepAlive = () -> {
        if (configReady) updateModelKeepAlive(shouldKeepModelAlive(settings()));
    };
    private final ServiceConnection keepAliveConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Model keep-alive service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Model keep-alive service disconnected; waiting for reconnect");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            handler.post(() -> {
                if (keepAliveBound) {
                    keepAliveBound = false;
                    try {
                        context.unbindService(this);
                    } catch (Throwable ignored) {
                    }
                }
                Log.w(TAG, "Model keep-alive binding died; scheduling rebind");
                handler.removeCallbacks(retryKeepAlive);
                if (configReady && shouldKeepModelAlive(settings())) {
                    handler.postDelayed(retryKeepAlive, 2000L);
                }
            });
        }

        @Override
        public void onNullBinding(ComponentName name) {
            handler.post(() -> {
                if (keepAliveBound) {
                    keepAliveBound = false;
                    try {
                        context.unbindService(this);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    };

    NotificationSummaries(ModuleMain module, Context context, Object service, Object hyperService,
            ClassLoader loader, Supplier<Bundle> config) throws Exception {
        this.context = context;
        this.service = service;
        this.hyperService = hyperService;
        power = context.getSystemService(PowerManager.class);
        Class<?> nms = service.getClass();
        Class<?> record = loader.loadClass("com.android.server.notification.NotificationRecord");
        Class<?> hyper = hyperService.getClass();
        lock = ModuleMain.findField(nms, "mNotificationLock").get(service);
        records = ModuleMain.findField(nms, "mNotificationList");
        recordsByKey = (Map<?, ?>) ModuleMain.findField(nms, "mNotificationsByKey").get(service);
        listeners = ModuleMain.findField(nms, "mListeners").get(service);
        recordImportance = ModuleMain.findField(record, "mImportance");

        getSbn = ModuleMain.findMethod(record, "getSbn");
        notifyPosted = ModuleMain.findMethod(listeners.getClass(), "notifyPostedLocked", record, record);
        PriorityGroups grouping = null;
        try {
            grouping = new PriorityGroups(module, service, lock, record, getSbn, listeners, notifyPosted);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set up native priority groups", e);
        }
        priorityGroups = grouping;
        allowedPackages = (Map<?, ?>) ModuleMain.findField(hyper,
                "mAllowAINotificaitonPackagers").get(null);
        HandlerThread worker = new HandlerThread("LocalAiNotif-notifications");
        worker.start();
        handler = new Handler(worker.getLooper());
        ReduceInterruptionsController reduce = null;
        try {
            reduce = new ReduceInterruptionsController(module, context, service, loader,
                    lock, records, recordsByKey, listeners, getSbn, notifyPosted,
                    handler, this::dispatchReducePending, this::onReduceStateMirrored);
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize Reduce Interruptions controller", e);
        }
        reduceController = reduce;
        this.config = new AsyncConfig(handler, Executors.newSingleThreadExecutor(), config,
                this::onAsyncConfigUpdated);
        scheduler = new SummaryScheduler(context, handler, this.config, this::prepareDispatch);
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_SCREEN_ON);
        filter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        filter.addAction(android.content.Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Config.ACTION_CONFIG_CHANGED);
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if (Config.ACTION_CONFIG_CHANGED.equals(action)
                        || Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    NotificationSummaries.this.config.refresh();
                } else {
                    refresh();
                }
            }
        }, filter, null, handler, Context.RECEIVER_EXPORTED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                ArrayList<String> keys = intent.getStringArrayListExtra(NotificationPresentation.KEYS);
                long[] times = intent.getLongArrayExtra(NotificationPresentation.TIMES);
                if (keys == null || times == null || keys.size() != times.length) return;
                synchronized (lock) {
                    try {
                        for (int index = 0; index < keys.size(); index++) {
                            String key = keys.get(index);
                            Object record = recordsByKey.get(key);
                            if (record != null && ((StatusBarNotification) getSbn.invoke(record)).getPostTime() == times[index]) {
                                presented.put(key, times[index]);
                            }
                        }
                    } catch (ReflectiveOperationException e) {
                        Log.w(TAG, "Failed to match rendered notifications", e);
                    }
                }
                refresh();
            }
        }, new IntentFilter(NotificationPresentation.ACTION), "android.permission.STATUS_BAR_SERVICE",
                handler, Context.RECEIVER_EXPORTED);
        handler.post(this.config::refresh);
    }

    void refresh() {
        if (refreshPending.compareAndSet(false, true)) {
            handler.postDelayed(() -> {
                refreshPending.set(false);
                schedulerRefresh();
            }, 150);
        }
    }

    private Bundle settings() {
        return config.get();
    }

    private void onReduceStateMirrored(boolean active) {
        if (config != null) {
            config.putBooleanLocal(Config.KEY_REDUCE_ENABLED, active);
        }
    }

    private void onAsyncConfigUpdated() {
        configReady = true;
        if (reduceController != null) {
            reduceController.onConfigUpdated(settings());
        }
        schedulerRefresh();
    }

    private void schedulerRefresh() {
        if (!configReady) return;
        Bundle currentSettings = settings();
        updateModelKeepAlive(shouldKeepModelAlive(currentSettings));
        boolean enabled = currentSettings.getBoolean(Config.KEY_PRIORITY_ENABLED, Config.DEFAULT_PRIORITY_ENABLED);
        if (enabled && !priorityEnabled) {
            attempted.clear();
            submittedItems.clear();
        }
        priorityEnabled = enabled;
        if (priorityGroups != null) {
            synchronized (lock) {
                try {
                    priorityGroups.reconcile((List<?>) records.get(service), priorityEnabled);
                } catch (ReflectiveOperationException e) {
                    Log.w(TAG, "Failed to reconcile priority groups", e);
                }
            }
        }
        dispatchReducePending();
        scheduler.refresh();
    }

    private static boolean shouldKeepModelAlive(Bundle settings) {
        return settings != null
                && settings.getBoolean(Config.KEY_ALWAYS_LOAD_MODEL, Config.DEFAULT_ALWAYS_LOAD_MODEL)
                && settings.getBoolean(Config.KEY_MODEL_READY, false);
    }

    private void updateModelKeepAlive(boolean enabled) {
        handler.removeCallbacks(retryKeepAlive);
        if (enabled == keepAliveBound) return;
        if (enabled) {
            Intent intent = new Intent();
            intent.setClassName("com.localai.notif", "com.localai.notif.ModelKeepAliveService");
            try {
                boolean ok = context.bindService(
                        intent,
                        keepAliveConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND);
                keepAliveBound = ok;
                Log.i(TAG, "Model keep-alive bind " + (ok ? "active" : "failed"));
                if (!ok) {
                    try {
                        context.unbindService(keepAliveConnection);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                keepAliveBound = false;
                Log.w(TAG, "Failed to bind model keep-alive service", t);
            }
        } else {
            keepAliveBound = false;
            try {
                context.unbindService(keepAliveConnection);
                Log.i(TAG, "Model keep-alive unbound");
            } catch (Throwable t) {
                Log.w(TAG, "Failed to unbind model keep-alive service", t);
            }
        }
    }

    private boolean isPriorityEligible(StatusBarNotification sbn, Bundle currentSettings) {
        return PriorityNotification.isEligible(sbn.getNotification());
    }

    private boolean hasPriorityDecision(Entry entry) {
        PriorityDecision decision = priorityDecisions.get(entry.sbn.getKey());
        return decision != null
                && decision.matches(entry.fingerprint, entry.sbn.getNotification().getChannelId());
    }

    private List<Entry> unclassifiedItems(List<Entry> items, boolean classifyEnabled, boolean priorityGroup) {
        if (!classifyEnabled || priorityGroup) return List.of();
        ArrayList<Entry> pending = new ArrayList<>();
        for (Entry entry : items) {
            if (entry.eligible
                    && (!entry.title.isBlank() || !entry.text.isBlank())
                    && !hasPriorityDecision(entry)) {
                pending.add(entry);
            }
        }
        return pending;
    }

    private void updatePriority(Entry entry) throws ReflectiveOperationException {
        Notification notification = entry.sbn.getNotification();
        PriorityDecision decision = priorityDecisions.get(entry.sbn.getKey());
        boolean important = priorityEnabled && entry.eligible && decision != null
                && decision.important()
                && decision.matches(entry.fingerprint, notification.getChannelId());
        if (PriorityNotification.setMarked(entry.sbn, important)) {
            PriorityNotification.notifyUpdated(listeners, notifyPosted, entry.record, entry.sbn);
        }
    }

    private List<Batch> collect(Bundle settings) throws Exception {
        boolean interactive = power.isInteractive();
        synchronized (lock) {
            Map<String, List<Entry>> groups = new LinkedHashMap<>();
            Map<String, Entry> summaries = new HashMap<>();
            Map<String, List<Entry>> priorityMembers = new HashMap<>();
            Set<String> liveItems = new HashSet<>();
            Set<String> activeKeys = new HashSet<>();
            Set<String> awaitingGroups = new HashSet<>();
            int minimum = settings.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH);
            boolean enabled = settings.getBoolean(Config.KEY_GROUP_SUMMARY, true);
            boolean classifyEnabled = isClassifyEnabled(settings);
            for (Object record : (List<?>) records.get(service)) {
                StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
                Notification notification = sbn.getNotification();
                String notificationKey = sbn.getKey();
                activeKeys.add(notificationKey);
                if (!interactive) presented.put(notificationKey, sbn.getPostTime());
                Long visibleVersion = presented.get(notificationKey);
                if (visibleVersion == null || visibleVersion != sbn.getPostTime()) {
                    awaitingGroups.add(sbn.getGroupKey());
                    if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) == 0) liveItems.add(notificationKey);
                    continue;
                }
                if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                    summaries.put(sbn.getGroupKey(), new Entry(record, sbn, false));
                    continue;
                }
                boolean eligible = isPriorityEligible(sbn, settings);
                if (eligible) GroupSummaryData.rememberOriginal(notification);
                Entry entry = new Entry(record, sbn, eligible);
                updatePriority(entry);
                boolean promotedSingleVip = priorityEnabled && PriorityNotification.isMarked(sbn)
                        && !PriorityNotification.isGrouped(sbn);
                if (!promotedSingleVip) {
                    groups.computeIfAbsent(sbn.getGroupKey(), key -> new ArrayList<>()).add(entry);
                }
                if (enabled && priorityEnabled && PriorityNotification.isMarked(sbn)) {
                    priorityMembers.computeIfAbsent(priorityBatchId(sbn), key -> new ArrayList<>()).add(entry);
                }
                liveItems.add(sbn.getKey());
            }
            submittedItems.keySet().retainAll(liveItems);
            priorityDecisions.keySet().retainAll(liveItems);
            presented.keySet().retainAll(activeKeys);
            if (reduceController != null) {
                reduceController.retainActiveKeys(activeKeys);
            }
            ArrayList<Batch> batches = new ArrayList<>();
            Set<String> liveGroups = new HashSet<>();
            for (Map.Entry<String, List<Entry>> group : groups.entrySet()) {
                List<Entry> children = group.getValue();
                Entry summary = summaries.get(group.getKey());
                boolean priorityGroup = summary != null && PriorityNotification.isGroupSummary(summary.sbn);
                boolean eligible = enabled && summary != null && children.size() >= 2
                        && !awaitingGroups.contains(group.getKey());
                int total = 0;
                for (Entry child : children) {
                    eligible &= priorityGroup ? PriorityNotification.isMarked(child.sbn)
                            : child.eligible && !child.text.isEmpty();
                    total += child.text.trim().length();
                }
                eligible &= priorityGroup || total > minimum;
                if (eligible) {
                    String id = priorityGroup ? priorityBatchId(summary.sbn) : "g:" + group.getKey();
                    Batch batch = new Batch(id, summary, children,
                            priorityGroup ? 0 : minimum, null);
                    List<Entry> pendingClassify = unclassifiedItems(batch.requested, classifyEnabled, priorityGroup);
                    batch = new Batch(id, summary, children,
                            priorityGroup ? 0 : minimum, pendingClassify);
                    batches.add(batch);
                    liveGroups.add(batch.id);
                    if (priorityGroup) {
                        priorityMembers.remove(id);
                        if (!batch.fingerprint.equals(attempted.get(id))) attempted.remove(id);
                    }
                    if (classifyEnabled && !priorityGroup) {
                        Set<Entry> requested = new HashSet<>(batch.requested);
                        for (Entry child : children) {
                            if (!requested.contains(child) && child.eligible
                                    && (!child.title.isBlank() || !child.text.isBlank())
                                    && !hasPriorityDecision(child)) {
                                batches.add(new Batch("s:" + child.sbn.getKey(), null,
                                        List.of(child), minimum, List.of(child)));
                            }
                        }
                    }
                    CachedGroup cached = cachedGroups.get(batch.id);
                    if (cached != null && !cached.fingerprint.equals(batch.fingerprint)) {
                        cachedGroups.remove(batch.id);
                        cached = null;
                    }
                    if (cached != null) setGroup(summary, batch.fingerprint, cached.summary);
                    else if (!batch.fingerprint.equals(summary.sbn.getNotification().extras
                            .getString(GroupSummaryData.FINGERPRINT))) clearGroup(summary);
                } else {
                    if (summary != null) clearGroup(summary);
                    for (Entry child : children) {
                        if (classifyEnabled && child.eligible
                                && (!child.title.isBlank() || !child.text.isBlank())
                                && (child.sbn.getNotification().flags & GroupSummaryData.AI_FLAG) == 0
                                && !hasPriorityDecision(child)) {
                            batches.add(new Batch("s:" + child.sbn.getKey(), null,
                                    List.of(child), minimum, List.of(child)));
                        }
                    }
                }
            }
            for (Map.Entry<String, Entry> summary : summaries.entrySet()) {
                if (!groups.containsKey(summary.getKey())) clearGroup(summary.getValue());
            }
            Set<String> liveBatches = new HashSet<>();
            for (Batch batch : batches) liveBatches.add(batch.id);
            for (Map.Entry<String, List<Entry>> priority : priorityMembers.entrySet()) {
                String id = priority.getKey();
                String previousAttempt = attempted.get(id);
                CachedGroup cached = cachedGroups.get(id);
                if (priority.getValue().size() < 2 || (previousAttempt == null && cached == null)) continue;
                String fingerprint = groupFingerprint(priority.getValue());
                if (fingerprint.equals(previousAttempt)) liveBatches.add(id);
                if (cached != null && fingerprint.equals(cached.fingerprint)) liveGroups.add(id);
            }
            cachedGroups.keySet().retainAll(liveGroups);
            attempted.keySet().retainAll(liveBatches);
            return batches;
        }
    }

    private static String priorityBatchId(StatusBarNotification sbn) {
        return "vip:" + sbn.getUserId() + ":" + sbn.getPackageName();
    }

    private static String groupFingerprint(List<Entry> children) {
        ArrayList<String> items = new ArrayList<>(children.size());
        for (Entry child : children) items.add(child.sbn.getKey() + ":" + child.fingerprint);
        return GroupSummaryData.fingerprintParts(items);
    }

    private int unsent(Batch batch) {
        if (inFlight.contains(batch.id) || batch.fingerprint.equals(attempted.get(batch.id))) return 0;
        if (batch.summary != null) {
            return batch.children.size();
        }
        int count = 0;
        for (Entry child : batch.children) {
            if (!child.fingerprint.equals(submittedItems.get(child.sbn.getKey()))) count++;
        }
        return count;
    }

    private Runnable prepareDispatch(int threshold) {
        try {
            Bundle settings = settings();
            List<Batch> batches = collect(settings);
            int count = 0;
            for (Batch batch : batches) {
                count += unsent(batch);
                if (count >= threshold) {
                    boolean classifyEnabled = isClassifyEnabled(settings);
                    return () -> dispatch(batches, classifyEnabled);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to collect active notifications", e);
        }
        return null;
    }

    private void dispatch(List<Batch> batches, boolean classifyEnabled) {
        try {
            for (Batch batch : batches) {
                if (unsent(batch) == 0) continue;
                attempted.put(batch.id, batch.fingerprint);
                inFlight.add(batch.id);
                for (Entry entry : batch.children) {
                    submittedItems.put(entry.sbn.getKey(), entry.fingerprint);
                }
                executor.execute(() -> request(batch, classifyEnabled));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch summaries", e);
        }
    }

    private boolean isClassifyEnabled(Bundle settings) {
        // 噪音静默功能已全局关闭；在开启「重要通知」或「减少干扰」时执行模型分类用于识别 VIP。
        boolean reduceActive = reduceController != null && reduceController.isActive();
        return (priorityEnabled || reduceActive)
                && (settings == null || settings.getBoolean(Config.KEY_CLASSIFY_ENABLED, DEFAULT_CLASSIFY_ENABLED));
    }

    /**
     * Fast-path classification for Reduce Interruptions: immediately classifies newly intercepted
     * notifications without waiting for summary wait_seconds, threshold, screen-on gate, or
     * SystemUI render receipt, and independent of whether the "重要通知" section toggle is ON.
     */
    private void dispatchReducePending() {
        if (reduceController == null || !reduceController.hasPendingNotifications()) return;
        ArrayList<Entry> pendingEntries = new ArrayList<>();
        synchronized (lock) {
            try {
                List<?> list = (List<?>) records.get(service);
                if (list == null) return;
                for (Object record : list) {
                    StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
                    Notification notification = sbn.getNotification();
                    if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    Entry entry = new Entry(record, sbn, ReduceInterruptions.isEligibleForClassification(notification));
                    if (!reduceController.isPending(sbn.getKey(), entry.fingerprint)) continue;

                    // If title & text are both blank, fail-closed silent immediately.
                    if (entry.title.isBlank() && entry.text.isBlank()) {
                        reduceController.onClassifiedLocked(
                                sbn.getKey(), entry.fingerprint, false, false, record, sbn);
                        continue;
                    }

                    // Reuse already-known model decision for the same fingerprint + channel.
                    PriorityDecision cached = priorityDecisions.get(sbn.getKey());
                    if (cached != null && cached.matches(entry.fingerprint, notification.getChannelId())) {
                        if (priorityEnabled && cached.important()
                                && isPriorityEligible(sbn, settings())) {
                            PriorityNotification.setMarked(sbn, true);
                        }
                        reduceController.onClassifiedLocked(
                                sbn.getKey(), entry.fingerprint, true, cached.important(), record, sbn);
                        continue;
                    }

                    String token = sbn.getKey() + ":" + entry.fingerprint;
                    if (!reduceInFlight.add(token)) continue;
                    pendingEntries.add(entry);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to scan pending Reduce Interruptions notifications", e);
                return;
            }
        }
        if (pendingEntries.isEmpty()) return;

        Batch batch = new Batch("r:" + System.nanoTime(), null, pendingEntries, 0, pendingEntries);
        executor.execute(() -> {
            Bundle classification = classify(batch.toClassify);
            handler.post(() -> finishReduceClassification(batch, classification));
        });
    }

    private void finishReduceClassification(Batch batch, Bundle classification) {
        boolean ok = classification != null && "0".equals(classification.getString("status"));
        ArrayList<String> classes = ok ? classification.getStringArrayList(PriorityNotification.CLASS_LIST) : null;
        ArrayList<String> vips = ok ? classification.getStringArrayList(PriorityNotification.VIP_LIST) : null;
        ArrayList<String> errors = ok ? classification.getStringArrayList(InferenceProvider.OUT_ERRORS) : null;
        int count = batch.requested.size();
        boolean validShape = ok && classes != null && vips != null && errors != null
                && classes.size() == count && vips.size() == count && errors.size() == count;

        synchronized (lock) {
            try {
                for (int index = 0; index < count; index++) {
                    Entry requested = batch.requested.get(index);
                    String key = requested.sbn.getKey();
                    reduceInFlight.remove(key + ":" + requested.fingerprint);

                    boolean itemClassified = validShape && "0".equals(errors.get(index));
                    boolean vip = itemClassified && PriorityNotification.isImportant(
                            classes.get(index), vips.get(index));

                    Object record = recordsByKey.get(key);
                    StatusBarNotification sbn = record != null
                            ? (StatusBarNotification) getSbn.invoke(record)
                            : null;
                    boolean sameContent = sbn != null
                            && requested.fingerprint.equals(GroupSummaryData.itemFingerprint(sbn))
                            && java.util.Objects.equals(
                                    requested.sbn.getNotification().getChannelId(),
                                    sbn.getNotification().getChannelId());

                    if (itemClassified && sameContent) {
                        priorityDecisions.put(key, new PriorityDecision(
                                requested.fingerprint,
                                requested.sbn.getNotification().getChannelId(),
                                vip));
                        submittedItems.put(key, requested.fingerprint);
                        attempted.put("s:" + key, GroupSummaryData.fingerprintParts(
                                new ArrayList<>(List.of(key + ":" + requested.fingerprint))));
                        if (priorityEnabled && vip
                                && isPriorityEligible(sbn, settings())) {
                            PriorityNotification.setMarked(sbn, true);
                        }
                    }

                    if (reduceController != null) {
                        reduceController.onClassifiedLocked(
                                key,
                                requested.fingerprint,
                                itemClassified && sameContent,
                                vip && sameContent,
                                sameContent ? record : null,
                                sameContent ? sbn : null);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply Reduce Interruptions classification", e);
            }
        }
        refresh();
    }

    private void request(Batch batch, boolean classifyEnabled) {
        // Pass1:仅对尚未分类的条目做一次分类;已分类条目与重要通知组不再重复分类,避免耗时与模型抖动导致退出重要通知。
        Bundle classes = classifyEnabled && !batch.toClassify.isEmpty()
                ? classify(batch.toClassify) : null;
        if (classes != null) handler.post(() -> applyPriorityClassification(batch.toClassify, classes));
        boolean skipGroupSummaryForNewVip = batch.summary != null && !batch.priorityGroup
                && priorityEnabled && containsImportantVip(classes);
        Bundle reply = null;
        // 对齐 iOS 语义:单条通知不摘要、原文完整显示;只有成组批次才请求组摘要。
        // 分类照常执行(单条噪音治理仍然生效),单条时 reply 为 null,finish() 只走静默路径。
        if (batch.summary != null && !skipGroupSummaryForNewVip) {
            try {
                Bundle input = new Bundle();
                ArrayList<String> messages = new ArrayList<>();
                ArrayList<String> titles = new ArrayList<>();
                ArrayList<String> categories = new ArrayList<>();
                for (Entry entry : batch.requested) {
                    messages.add(entry.text);
                    titles.add(entry.title);
                    categories.add(category(entry));
                }
                input.putStringArrayList("messageList", messages);
                input.putStringArrayList("titles", titles);
                input.putStringArrayList("category", categories);
                input.putString("package", batch.children.get(0).sbn.getPackageName());
                input.putInt("line_count", Math.min(batch.children.size(), 3));
                input.putInt(Config.KEY_MIN_LENGTH, batch.minimum);
                input.putBoolean(Config.KEY_INCLUDE_ITEM_SUMMARIES, false);
                Log.i(TAG, "Requesting group summary: " + batch.requested.size() + " notification(s)");
                reply = ModuleMain.callProvider(context, "summarize_group", input);
            } catch (Exception e) {
                Log.e(TAG, "Summary request failed", e);
            }
        }
        Bundle result = reply;
        Bundle classification = classes;
        boolean skippedSummary = skipGroupSummaryForNewVip;
        handler.post(() -> finish(batch, result, classification, skippedSummary));
    }

    private static boolean containsImportantVip(Bundle classification) {
        if (classification == null || !"0".equals(classification.getString("status"))) return false;
        ArrayList<String> classes = classification.getStringArrayList(PriorityNotification.CLASS_LIST);
        ArrayList<String> vips = classification.getStringArrayList(PriorityNotification.VIP_LIST);
        ArrayList<String> errors = classification.getStringArrayList(InferenceProvider.OUT_ERRORS);
        if (classes == null || vips == null || errors == null
                || classes.size() != vips.size() || classes.size() != errors.size()) return false;
        for (int i = 0; i < classes.size(); i++) {
            if ("0".equals(errors.get(i)) && PriorityNotification.isImportant(classes.get(i), vips.get(i))) {
                return true;
            }
        }
        return false;
    }

    private void applyPriorityClassification(List<Entry> items, Bundle classification) {
        if (!"0".equals(classification.getString("status"))) return;
        ArrayList<String> classes = classification.getStringArrayList(PriorityNotification.CLASS_LIST);
        ArrayList<String> vips = classification.getStringArrayList(PriorityNotification.VIP_LIST);
        ArrayList<String> errors = classification.getStringArrayList(InferenceProvider.OUT_ERRORS);
        int count = items.size();
        if (classes == null || vips == null || errors == null
                || classes.size() != count || vips.size() != count || errors.size() != count) return;
        Bundle currentSettings = settings();
        synchronized (lock) {
            try {
                for (int index = 0; index < count; index++) {
                    if (!"0".equals(errors.get(index))) continue;
                    Entry requested = items.get(index);
                    Object record = recordsByKey.get(requested.sbn.getKey());
                    if (record == null) continue;
                    StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
                    PriorityDecision decision = new PriorityDecision(requested.fingerprint,
                            requested.sbn.getNotification().getChannelId(),
                            PriorityNotification.isImportant(classes.get(index), vips.get(index)));
                    if (!decision.matches(GroupSummaryData.itemFingerprint(sbn), sbn.getNotification().getChannelId())) continue;
                    PriorityDecision existing = priorityDecisions.get(sbn.getKey());
                    if (existing != null && existing.matches(requested.fingerprint,
                            requested.sbn.getNotification().getChannelId())) {
                        continue;
                    }
                    priorityDecisions.put(sbn.getKey(), decision);
                    if (record != requested.record) continue;
                    if (PriorityNotification.setMarked(sbn, priorityEnabled
                            && isPriorityEligible(sbn, currentSettings) && decision.important())) {
                        PriorityNotification.notifyUpdated(listeners, notifyPosted, record, sbn);
                    }
                    if (reduceController != null) {
                        reduceController.onClassifiedLocked(
                                sbn.getKey(), requested.fingerprint, true, decision.important(), record, sbn);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply priority classification", e);
            }
        }
    }

    /** Pass1 分类调用:对未分类条目一次;任何异常都返回 null,让调用方全批按 normal 继续,不抛错。 */
    private Bundle classify(List<Entry> items) {
        if (items == null || items.isEmpty()) return null;
        try {
            Bundle input = new Bundle();
            ArrayList<String> messages = new ArrayList<>(items.size());
            ArrayList<String> titles = new ArrayList<>(items.size());
            ArrayList<String> packages = new ArrayList<>(items.size());
            ArrayList<String> channels = new ArrayList<>(items.size());
            for (Entry entry : items) {
                messages.add(entry.text);
                titles.add(entry.title);
                packages.add(entry.sbn.getPackageName());
                channels.add(entry.sbn.getNotification().getChannelId());
            }
            input.putStringArrayList("messageList", messages);
            input.putStringArrayList("titles", titles);
            input.putStringArrayList("packages", packages);
            input.putStringArrayList("channels", channels);
            Log.i(TAG, "Requesting classification for " + items.size() + " notification(s)");
            return ModuleMain.callProvider(context, "classify", input);
        } catch (Exception e) {
            Log.e(TAG, "Classification request failed; batch treated as normal", e);
            return null;
        }
    }

    private void finish(Batch requested, Bundle reply, Bundle classification, boolean skippedSummary) {
        try {
            Bundle settings = settings();
            boolean screenOn = settings.getBoolean(Config.KEY_SUMMARIZE_SCREEN_ON,
                    Config.DEFAULT_SUMMARIZE_SCREEN_ON);
            if (!screenOn && context.getSystemService(android.os.PowerManager.class).isInteractive()) {
                attempted.remove(requested.id);
                return;
            }
            synchronized (lock) {
                Batch current = null;
                for (Batch batch : collect(settings)) {
                    if (batch.id.equals(requested.id) && batch.fingerprint.equals(requested.fingerprint)) {
                        current = batch;
                        break;
                    }
                }
                if (skippedSummary && current != null) {
                    attempted.remove(requested.id);
                    return;
                }
                if (current == null && !(requested.priorityGroup
                        && requested.fingerprint.equals(attempted.get(requested.id)))) {
                    Log.i(TAG, "Discarded summary for changed notification membership/content");
                    return;
                }
                // 噪音治理先于摘要应用:不依赖摘要成败,只要分类结果有效就静默 noise 项(设计 §3.2)。
                if (current != null) {
                    if (current.priorityGroup) attempted.put(current.id, current.fingerprint);
                    applyClassification(current, requested, classification);
                }
                if (reply == null || !"0".equals(reply.getString("status"))) return;
                // AI 呈现只保留组摘要卡片(用户要求:子通知点击详情不显示 AI 图标/摘要)。
                // 逐条 HyperAINotification 写回已删除——那是详情页 AI 标记的数据源。
                String groupSummary = reply.getString("group_summary", "");
                if (requested.summary == null || groupSummary.isBlank()) return;
                cachedGroups.put(requested.id, new CachedGroup(requested.fingerprint, groupSummary));
                if (current != null) {
                    setGroup(current.summary, current.fingerprint, groupSummary);
                    Log.i(TAG, "Applied group summary");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply summary", e);
        } finally {
            inFlight.remove(requested.id);
            refresh();
        }
    }

    private static final boolean NOISE_MUTE_ENABLED = false;

    /**
     * 噪音静默功能暂时全局关闭，待后续逻辑完善后再启用。
     */
    private void applyClassification(Batch current, Batch requested, Bundle classification)
            throws Exception {
        if (!NOISE_MUTE_ENABLED) return;
        if (classification == null || !"0".equals(classification.getString("status"))) return;
        ArrayList<String> classes = classification.getStringArrayList(PriorityNotification.CLASS_LIST);
        ArrayList<String> errors = classification.getStringArrayList(InferenceProvider.OUT_ERRORS);
        int count = requested.toClassify.size();
        if (classes == null || errors == null || classes.size() != count || errors.size() != count) return;
        Map<String, Entry> active = new HashMap<>();
        for (Entry child : current.children) active.put(child.sbn.getKey(), child);
        for (int i = 0; i < count; i++) {
            if (!"0".equals(errors.get(i))) continue;
            Entry entry = active.get(requested.toClassify.get(i).sbn.getKey());
            if (entry != null && Classifier.CLASS_NOISE.equals(classes.get(i))) muteNoise(entry);
        }
    }

    /**
     * 噪音治理(幂等,设计 §3.2):清声震;打上 HyperOS 原生折叠标志 extras["miui.unimportant"]=true
     * 并把 record importance 降到 IMPORTANCE_LOW。MiuiSystemUI FoldNotifControllerImpl.sendFoldNotification
     * 恰好以 extras.getBoolean("miui.unimportant") 为"更多通知"聚合入口判据(反编译实证),
     * 重投后 SystemUI 自动把该通知收进"更多通知"折叠区。仅内存态,不动 app channel、不动持久设置;
     * 进行中事件(媒体/导航)不折叠不降级。
     */
    private void muteNoise(Entry entry) throws Exception {
        Notification notification = entry.sbn.getNotification();
        String muted = notification.extras.getString(EXTRA_NOISE_MUTED);
        if (entry.fingerprint.equals(muted)) return; // 已治理过本版本,重复分类不再处理
        notification.extras.putString(EXTRA_NOISE_MUTED, entry.fingerprint);
        notification.sound = null;
        notification.vibrate = null;
        notification.defaults &= ~(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        boolean folded = false;
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) == 0) {
            notification.extras.putBoolean("miui.unimportant", true);
            if (recordImportance.getInt(entry.record) > android.app.NotificationManager.IMPORTANCE_LOW) {
                recordImportance.setInt(entry.record, android.app.NotificationManager.IMPORTANCE_LOW);
            }
            folded = true;
        }
        Log.i(TAG, "Muted noise notification: " + entry.sbn.getKey()
                + " (sound/vibration cleared; " + (folded ? "importance -> LOW, fold flag set" : "importance kept") + ")");
        PriorityNotification.notifyUpdated(listeners, notifyPosted, entry.record, entry.sbn);
    }

    private String category(Entry entry) {
        Object category = allowedPackages.get(entry.sbn.getPackageName());
        return category instanceof String ? (String) category : "1_IM";
    }

    private void setGroup(Entry entry, String fingerprint, String summary) throws Exception {
        Bundle extras = entry.sbn.getNotification().extras;
        if (fingerprint.equals(extras.getString(GroupSummaryData.FINGERPRINT))
                && summary.equals(extras.getString(GroupSummaryData.SUMMARY))) return;
        extras.putString(GroupSummaryData.FINGERPRINT, fingerprint);
        extras.putString(GroupSummaryData.SUMMARY, summary);
        PriorityNotification.notifyUpdated(listeners, notifyPosted, entry.record, entry.sbn);
    }

    private void clearGroup(Entry entry) throws Exception {
        Bundle extras = entry.sbn.getNotification().extras;
        if (!extras.containsKey(GroupSummaryData.SUMMARY)
                && !extras.containsKey(GroupSummaryData.FINGERPRINT)) return;
        extras.remove(GroupSummaryData.FINGERPRINT);
        extras.remove(GroupSummaryData.SUMMARY);
        PriorityNotification.notifyUpdated(listeners, notifyPosted, entry.record, entry.sbn);
    }

    private static final class Entry {
        final Object record;
        final StatusBarNotification sbn;
        final boolean eligible;
        final String title;
        final String text;
        final String fingerprint;

        Entry(Object record, StatusBarNotification sbn, boolean eligible) {
            this.record = record;
            this.sbn = sbn;
            this.eligible = eligible;
            title = GroupSummaryData.title(sbn.getNotification());
            text = GroupSummaryData.text(sbn.getNotification());
            fingerprint = GroupSummaryData.itemFingerprint(sbn.getKey(), title, text);
        }
    }

    private static final class Batch {
        final String id;
        final Entry summary;
        final List<Entry> children;
        final List<Entry> requested;
        final List<Entry> toClassify;
        final String fingerprint;
        final int minimum;
        final boolean priorityGroup;

        Batch(String id, Entry summary, List<Entry> children, int minimum, List<Entry> toClassify) {
            this.id = id;
            this.summary = summary;
            this.children = new ArrayList<>(children);
            this.minimum = minimum;
            priorityGroup = summary != null && PriorityNotification.isGroupSummary(summary.sbn);
            fingerprint = groupFingerprint(children);
            if (children.size() == 1) {
                requested = this.children;
            } else {
                ArrayList<Entry> sorted = new ArrayList<>(children);
                sorted.sort(REQUEST_ORDER);
                requested = new ArrayList<>(sorted.subList(
                        Math.max(0, sorted.size() - GroupSummaryData.MAX_ITEMS), sorted.size()));
            }
            this.toClassify = toClassify == null ? List.of() : new ArrayList<>(toClassify);
        }
    }

    private static final class CachedGroup {
        final String fingerprint;
        final String summary;

        CachedGroup(String fingerprint, String summary) {
            this.fingerprint = fingerprint;
            this.summary = summary;
        }
    }

    private record PriorityDecision(String fingerprint, String channelId, boolean important) {
        boolean matches(String currentFingerprint, String currentChannel) {
            return java.util.Objects.equals(channelId, currentChannel) && fingerprint.equals(currentFingerprint);
        }
    }
}
