package io.github.internetbugs.localainotif;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

final class NotificationSummaries {
    private static final String TAG = "LocalAiNotif";
    // 分类开关:key 与默认值统一放 Config,与 provider get_config 透出的一致。
    private static final boolean DEFAULT_CLASSIFY_ENABLED = Config.DEFAULT_CLASSIFY_ENABLED;
    private static final Comparator<Entry> REQUEST_ORDER =
            Comparator.comparingLong((Entry entry) -> entry.when)
                    .thenComparing(entry -> entry.sbn.getKey());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Context context;
    private final Handler handler;
    private final Object service;
    private final Object lock;
    private final Object listeners;
    private final Field records;
    private final Map<?, ?> recordsByKey;
    private final Method getSbn;
    private final Method notifyPosted;

    private final AsyncConfig config;
    private final PowerManager power;
    private final Map<String, Long> presented = new HashMap<>();
    private final Map<String, Entry> entriesByKey = new HashMap<>();
    private final Map<String, String> attempted = new HashMap<>();
    private final Map<String, String> submittedItems = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, CachedGroup> cachedGroups = new HashMap<>();
    private final Map<String, PriorityDecision> priorityDecisions = new HashMap<>();
    private final Set<String> classificationInFlight = new HashSet<>();
    private final Map<String, Long> promotionAttempts = new HashMap<>();
    private final Map<String, Object> recapAttempts = new HashMap<>();
    private String recapModelPath;
    private long recapGeneration;
    private boolean recapInFlight;
    private String recapInFlightKey;
    private Object recapInFlightRecord;
    private boolean promotionEnabled;
    private boolean modelReady;
    private final SummaryScheduler scheduler;
    private final PriorityGroups priorityGroups;
    private final ReduceInterruptionsController reduceController;
    private final String frameworkSummary;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final AtomicLong listRevision = new AtomicLong();
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

    NotificationSummaries(ModuleMain module, Context context, Object service,
            ClassLoader loader, Supplier<Bundle> config) throws Exception {
        this.context = context;
        this.service = service;
        this.frameworkSummary = formatFrameworkSummary(module);
        power = context.getSystemService(PowerManager.class);
        Class<?> nms = service.getClass();
        Class<?> record = loader.loadClass("com.android.server.notification.NotificationRecord");
        lock = ModuleMain.findField(nms, "mNotificationLock").get(service);
        records = ModuleMain.findField(nms, "mNotificationList");
        recordsByKey = (Map<?, ?>) ModuleMain.findField(nms, "mNotificationsByKey").get(service);
        listeners = ModuleMain.findField(nms, "mListeners").get(service);

        getSbn = ModuleMain.findMethod(record, "getSbn");
        notifyPosted = ModuleMain.findMethod(listeners.getClass(), "notifyPostedLocked", record, record);
        PriorityGroups grouping = null;
        try {
            grouping = new PriorityGroups(module, service, lock, record, getSbn, listeners, notifyPosted);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set up native priority groups", e);
        }
        priorityGroups = grouping;
        HandlerThread worker = new HandlerThread("LocalAiNotif-notifications");
        worker.start();
        handler = new Handler(worker.getLooper());
        ReduceInterruptionsController reduce = null;
        try {
            reduce = new ReduceInterruptionsController(module, context, service, loader,
                    lock, records, recordsByKey, listeners, getSbn, notifyPosted,
                    handler, this::dispatchPendingClassifications, this::onReduceStateMirrored);
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
        filter.addAction(Config.ACTION_PING_SYSTEM_HOOK);
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if (Config.ACTION_PING_SYSTEM_HOOK.equals(action)) {
                    reportHookActiveAsync();
                } else if (Config.ACTION_CONFIG_CHANGED.equals(action)
                        || Intent.ACTION_USER_UNLOCKED.equals(action)) {
                    NotificationSummaries.this.config.refresh();
                    if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                        reportHookActiveAsync();
                    }
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
        reportHookActiveAsync();
    }

    private void reportHookActiveAsync() {
        executor.execute(() -> {
            try {
                Bundle info = new Bundle();
                info.putInt(Config.KEY_SYSTEM_HOOK_VERSION_CODE, Config.MODULE_VERSION_CODE);
                info.putString(Config.KEY_SYSTEM_HOOK_FRAMEWORK, frameworkSummary);
                context.getContentResolver().call(
                        Uri.parse("content://" + Config.AUTHORITY),
                        Config.METHOD_REPORT_HOOK_ACTIVE,
                        "system",
                        info);
            } catch (Throwable ignored) {
            }
        });
    }

    private static String formatFrameworkSummary(ModuleMain module) {
        try {
            String name = module.getFrameworkName();
            String ver = module.getFrameworkVersion();
            int api = module.getApiVersion();
            StringBuilder sb = new StringBuilder();
            sb.append(name != null && !name.isEmpty() ? name.trim() : "LSPosed");
            if (ver != null && !ver.isEmpty()) {
                sb.append(' ').append(ver.trim());
            }
            if (api > 0) {
                sb.append(" (API ").append(api).append(')');
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "LSPosed";
        }
    }

    void refresh() {
        listRevision.incrementAndGet();
        if (reduceController != null) reduceController.onNotificationListChanged();
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
        boolean ready = currentSettings.getBoolean(Config.KEY_MODEL_READY);
        if ((enabled && !priorityEnabled) || (ready && !modelReady)) {
            attempted.clear();
            submittedItems.clear();
        }
        modelReady = ready;
        priorityEnabled = enabled;
        boolean filter = currentSettings.getBoolean(Config.KEY_PROMOTION_FILTER);
        if (promotionEnabled != filter) {
            promotionAttempts.clear();
            promotionEnabled = filter;
            if (!filter) clearPromotions();
        }
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
            intent.setClassName("io.github.internetbugs.localainotif", "io.github.internetbugs.localainotif.ModelKeepAliveService");
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
                && decision.matches(entry.fingerprint, entry.channelId);
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
            refresh();
        }
    }

    private List<Batch> collect(Bundle settings) throws Exception {
        boolean interactive = power.isInteractive();
        List<Entry> snapshot = new ArrayList<>();
        List<Object> priorityRecords = new ArrayList<>();
        long revision;
        synchronized (lock) {
            revision = listRevision.get();
            for (Object record : (List<?>) records.get(service)) {
                StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
                boolean eligible = isPriorityEligible(sbn, settings);
                if (eligible) GroupSummaryData.rememberOriginal(sbn.getNotification());
                snapshot.add(entryFor(record, sbn, eligible));
                if (PriorityNotification.isMarked(sbn) || PriorityNotification.isGroupSummary(sbn)) {
                    priorityRecords.add(record);
                }
            }
            if (priorityGroups != null) priorityGroups.reconcile(priorityRecords, priorityEnabled);
        }
        for (Entry entry : snapshot) entry.prepareFingerprint();
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        Map<String, Entry> summaries = new HashMap<>();
        Map<String, List<Entry>> priorityMembers = new HashMap<>();
        Set<String> liveItems = new HashSet<>();
        Set<String> activeKeys = new HashSet<>();
        Set<String> awaitingGroups = new HashSet<>();
        int minimum = settings.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH);
        boolean enabled = settings.getBoolean(Config.KEY_GROUP_SUMMARY, true);
        boolean classifyEnabled = isClassifyEnabled(settings);
        List<Entry> pending = new ArrayList<>();
        try {
            synchronized (lock) {
                if (listRevision.get() != revision) return List.of();
                boolean reducePending = reduceController != null && reduceController.hasPendingNotifications();
                for (Entry entry : snapshot) {
                    StatusBarNotification sbn = entry.sbn;
                    String key = sbn.getKey();
                    if (recordsByKey.get(key) != entry.record) return List.of();
                    if (reducePending || (promotionEnabled && modelReady)) collectPending(entry, reducePending, pending);
                    if (!interactive) presented.put(key, sbn.getPostTime());
                    boolean visible = java.util.Objects.equals(presented.get(key), sbn.getPostTime());
                    if (visible && (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0) {
                        updatePriority(entry);
                    }
                    entry.captureGrouping(visible);
                }
                if (listRevision.get() != revision) return List.of();
            }
        } finally {
            submitPending(pending);
        }
        reconcileRecaps(snapshot, settings, revision, interactive);
        for (Entry entry : snapshot) {
            String key = entry.sbn.getKey();
            activeKeys.add(key);
            if (!entry.visible) awaitingGroups.add(entry.groupKey);
            if ((entry.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                if (entry.visible) summaries.put(entry.groupKey, entry);
                continue;
            }
            liveItems.add(key);
            if (!entry.visible || (promotionEnabled && entry.promotion)) continue;
            if (!(priorityEnabled && entry.priority && !entry.priorityGrouped)) {
                groups.computeIfAbsent(entry.groupKey, unused -> new ArrayList<>()).add(entry);
            }
            if (enabled && priorityEnabled && entry.priority) {
                priorityMembers.computeIfAbsent(priorityBatchId(entry.sbn), unused -> new ArrayList<>()).add(entry);
            }
        }
        if (listRevision.get() != revision) return List.of();
            promotionAttempts.keySet().retainAll(activeKeys);
            submittedItems.keySet().retainAll(liveItems);
            priorityDecisions.keySet().retainAll(liveItems);
            presented.keySet().retainAll(activeKeys);
            entriesByKey.keySet().retainAll(activeKeys);
            synchronized (lock) {
                if (reduceController != null && listRevision.get() == revision) reduceController.retainActiveKeys(activeKeys);
            }
            ArrayList<Batch> batches = new ArrayList<>();
            Set<String> liveGroups = new HashSet<>();
            for (Map.Entry<String, List<Entry>> group : groups.entrySet()) {
                List<Entry> children = group.getValue();
                Entry summary = summaries.get(group.getKey());
                boolean priorityGroup = summary != null && summary.prioritySummary;
                boolean eligible = enabled && modelReady && summary != null && children.size() >= 2
                        && !awaitingGroups.contains(group.getKey());
                int total = 0;
                for (Entry child : children) {
                    eligible &= priorityGroup ? child.priority
                            : child.eligible && !child.text.isEmpty();
                    total += child.text.trim().length();
                }
                eligible &= priorityGroup || total > minimum;
                if (eligible) {
                    String id = priorityGroup ? priorityBatchId(summary.sbn) : "g:" + group.getKey();
                    Batch batch = new Batch(id, summary, children, classifyEnabled);
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
                                        List.of(child), true));
                            }
                        }
                    }
                    CachedGroup cached = cachedGroups.get(batch.id);
                    if (cached != null && !cached.fingerprint.equals(batch.fingerprint)) {
                        cachedGroups.remove(batch.id);
                        cached = null;
                    }
                    if (cached != null) {
                        if (!cached.pendingPresentation || !interactive || settings.getBoolean(
                                Config.KEY_SUMMARIZE_SCREEN_ON, Config.DEFAULT_SUMMARIZE_SCREEN_ON)) {
                            synchronized (lock) {
                                if (!isCurrentSummary(summary, revision)) continue;
                                setGroup(summary, batch.versionFingerprint, cached.summary);
                                if (cached.pendingPresentation) Log.i(TAG, "Applied group summary");
                                cached.pendingPresentation = false;
                            }
                        } else {
                            clearSummaryState(summary, revision, false, true);
                        }
                    } else {
                        clearSummaryState(summary, revision, true, !inFlight.contains(batch.id));
                    }
                } else {
                    if (summary != null) {
                        clearSummaryState(summary, revision, true, true);
                    }
                    for (Entry child : children) {
                        if (classifyEnabled && child.eligible
                                && (!child.title.isBlank() || !child.text.isBlank())
                                && (child.flags & GroupSummaryData.AI_FLAG) == 0
                                && !hasPriorityDecision(child)) {
                            batches.add(new Batch("s:" + child.sbn.getKey(), null,
                                    List.of(child), true));
                        }
                    }
                }
            }
            for (Map.Entry<String, Entry> summary : summaries.entrySet()) {
                if (!groups.containsKey(summary.getKey())) {
                    clearSummaryState(summary.getValue(), revision, true, true);
                }
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
            if (listRevision.get() != revision) return List.of();
            for (Entry entry : snapshot) {
                if (awaitingGroups.contains(entry.groupKey) && (entry.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                    String id = entry.prioritySummary ? priorityBatchId(entry.sbn) : "g:" + entry.groupKey;
                    liveGroups.add(id);
                    liveBatches.add(id);
                }
            }
            cachedGroups.keySet().retainAll(liveGroups);
            attempted.keySet().retainAll(liveBatches);
            return listRevision.get() == revision ? batches : List.of();
    }

    private void reconcileRecaps(List<Entry> snapshot, Bundle settings, long revision, boolean interactive)
            throws ReflectiveOperationException {
        boolean recapEnabled = settings.getBoolean(Config.KEY_NOTIFICATION_RECAP, Config.DEFAULT_NOTIFICATION_RECAP);
        boolean recapScreenOn = settings.getBoolean(Config.KEY_RECAP_SCREEN_ON, Config.DEFAULT_RECAP_SCREEN_ON);
        int recapMinLength = Config.sanitizeRecapMinLength(
                settings.getInt(Config.KEY_RECAP_MIN_LENGTH, Config.DEFAULT_RECAP_MIN_LENGTH));
        String modelPath = recapEnabled && modelReady
                ? settings.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH) : null;
        boolean allowNewRecap = !interactive || recapScreenOn;
        Entry requested = null;
        String body = "";
        long generation;
        synchronized (lock) {
            if (listRevision.get() != revision) return;
            boolean resetRecaps = !java.util.Objects.equals(modelPath, recapModelPath);
            if (resetRecaps) {
                recapModelPath = modelPath;
                recapGeneration++;
                recapAttempts.clear();
            }
            recapAttempts.keySet().retainAll(recordsByKey.keySet());
            Entry next = null;
            String nextBody = "";
            for (Entry entry : snapshot) {
                String key = entry.sbn.getKey();
                if (recordsByKey.get(key) != entry.record) continue;
                Notification notification = entry.sbn.getNotification();
                boolean baseEligible = modelPath != null && entry.eligible && !(promotionEnabled && entry.promotion);
                String entryBody = NotificationRecap.body(notification);
                boolean qualifies = baseEligible && NotificationRecap.isLong(entryBody, recapMinLength);
                if (!qualifies) recapAttempts.remove(key);

                boolean isActiveInFlight = recapInFlight && key.equals(recapInFlightKey)
                        && entry.record == recapInFlightRecord;
                if (notification.extras != null) {
                    boolean changed = false;
                    if ((!qualifies || resetRecaps) && notification.extras.containsKey(NotificationRecap.EXTRA)) {
                        notification.extras.remove(NotificationRecap.EXTRA);
                        changed = true;
                    }
                    if ((!qualifies || resetRecaps || !isActiveInFlight)
                            && notification.extras.containsKey(NotificationRecap.EXTRA_IN_FLIGHT)) {
                        notification.extras.remove(NotificationRecap.EXTRA_IN_FLIGHT);
                        changed = true;
                    }
                    if (changed) {
                        PriorityNotification.notifyUpdated(listeners, notifyPosted, entry.record, entry.sbn);
                    }
                }
                if (!qualifies || !allowNewRecap || recapInFlight || !entry.visible
                        || recapAttempts.get(key) == entry.record
                        || (next != null && next.sbn.getPostTime() > entry.sbn.getPostTime())) continue;
                next = entry;
                nextBody = entryBody;
            }
            if (next == null) return;
            requested = next;
            body = nextBody;
            generation = recapGeneration;
            String reqKey = requested.sbn.getKey();
            recapInFlight = true;
            recapInFlightKey = reqKey;
            recapInFlightRecord = requested.record;
            recapAttempts.put(reqKey, requested.record);
            Notification reqNotif = requested.sbn.getNotification();
            if (reqNotif.extras != null) {
                reqNotif.extras.putString(NotificationRecap.EXTRA_IN_FLIGHT, NotificationRecap.bodyFingerprint(body));
                PriorityNotification.notifyUpdated(listeners, notifyPosted, requested.record, requested.sbn);
            }
        }
        Entry target = requested;
        String targetBody = body;
        int targetMinLength = recapMinLength;
        long targetGeneration = generation;
        executor.execute(() -> {
            Bundle input = new Bundle();
            input.putString(NotificationRecap.TITLE, LlmEngine.clip(target.title, 120));
            input.putString(NotificationRecap.BODY, LlmEngine.clip(targetBody, NotificationRecap.MAX_INPUT_LENGTH));
            input.putInt(NotificationRecap.MIN_LENGTH, targetMinLength);
            Bundle reply = ModuleMain.callProvider(context, NotificationRecap.METHOD, input);
            String recap = reply != null && "0".equals(reply.getString("status"))
                    ? reply.getString(NotificationRecap.EXTRA, "") : "";
            handler.post(() -> finishRecap(target, targetBody, targetGeneration, recap));
        });
    }

    private void finishRecap(Entry requested, String body, long generation, String recap) {
        recapInFlight = false;
        recapInFlightKey = null;
        recapInFlightRecord = null;
        try {
            Bundle currentSettings = settings();
            boolean canApply = !recap.isBlank()
                    && generation == recapGeneration
                    && currentSettings.getBoolean(Config.KEY_NOTIFICATION_RECAP, Config.DEFAULT_NOTIFICATION_RECAP)
                    && currentSettings.getBoolean(Config.KEY_MODEL_READY)
                    && java.util.Objects.equals(recapModelPath, currentSettings.getString(
                            Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH));
            int currentMinLength = Config.sanitizeRecapMinLength(
                    currentSettings.getInt(Config.KEY_RECAP_MIN_LENGTH, Config.DEFAULT_RECAP_MIN_LENGTH));
            synchronized (lock) {
                String key = requested.sbn.getKey();
                if (recordsByKey.get(key) != requested.record) return;
                StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(requested.record);
                Notification notification = sbn.getNotification();
                boolean changed = false;
                if (notification.extras != null && notification.extras.containsKey(NotificationRecap.EXTRA_IN_FLIGHT)) {
                    notification.extras.remove(NotificationRecap.EXTRA_IN_FLIGHT);
                    changed = true;
                }
                String latestBody = NotificationRecap.body(notification);
                if (!body.equals(latestBody)
                        || !requested.title.equals(GroupSummaryData.title(notification))) {
                    recapAttempts.remove(key, requested.record);
                } else if (!NotificationRecap.isLong(latestBody, currentMinLength)) {
                    recapAttempts.remove(key, requested.record);
                } else if (canApply && PriorityNotification.isEligible(notification)
                        && !(promotionEnabled && PromotionNotification.isMarked(sbn))
                        && notification.extras != null) {
                    notification.extras.putString(NotificationRecap.EXTRA, recap);
                    changed = true;
                }
                if (changed) {
                    PriorityNotification.notifyUpdated(listeners, notifyPosted, requested.record, sbn);
                }
            }
        } catch (Exception exception) {
            Log.w(TAG, "Failed to apply notification recap", exception);
        } finally {
            scheduler.refresh();
        }
    }

    private boolean isCurrentSummary(Entry summary, long revision) {
        return listRevision.get() == revision && recordsByKey.get(summary.sbn.getKey()) == summary.record;
    }

    private void clearSummaryState(Entry summary, long revision, boolean clearText, boolean clearIndicator)
            throws Exception {
        synchronized (lock) {
            if (!isCurrentSummary(summary, revision)) return;
            if (clearText) clearGroup(summary);
            if (clearIndicator) clearSummarizing(summary);
        }
    }

    private static String priorityBatchId(StatusBarNotification sbn) {
        return "vip:" + sbn.getUserId() + ":" + sbn.getPackageName();
    }

    private static String groupFingerprint(List<Entry> children) {
        ArrayList<String> items = new ArrayList<>(children.size());
        for (Entry child : children) items.add(child.classificationToken);
        return GroupSummaryData.fingerprintParts(items);
    }

    private static String groupVersionFingerprint(List<Entry> children) {
        ArrayList<String> items = new ArrayList<>(children.size());
        for (Entry child : children) items.add(GroupSummaryData.versionPart(child.sbn));
        return GroupSummaryData.fingerprintParts(items);
    }

    private int unsent(Batch batch) {
        if (inFlight.contains(batch.id) || batch.fingerprint.equals(attempted.get(batch.id))) return 0;
        if (batch.summary != null && batch.toClassify.isEmpty() && cachedGroups.containsKey(batch.id)) return 0;
        if (!classificationInFlight.isEmpty()) {
            for (Entry entry : batch.toClassify) {
                if (classificationInFlight.contains(entry.classificationToken)) return 0;
            }
        }
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
                    return () -> dispatch(batches);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to collect active notifications", e);
        }
        return null;
    }

    private void dispatch(List<Batch> batches) {
        try {
            for (Batch batch : batches) {
                if (unsent(batch) == 0) continue;
                attempted.put(batch.id, batch.fingerprint);
                inFlight.add(batch.id);
                for (Entry entry : batch.children) {
                    submittedItems.put(entry.sbn.getKey(), entry.fingerprint);
                }
                for (Entry entry : batch.toClassify) classificationInFlight.add(entry.classificationToken);
                boolean needsSummary = batch.summary != null && !cachedGroups.containsKey(batch.id);
                executor.execute(() -> request(batch, needsSummary));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch summaries", e);
        }
    }

    private boolean isClassifyEnabled(Bundle settings) {
        boolean reduceActive = reduceController != null && reduceController.isActive();
        return (priorityEnabled || reduceActive) && settings != null && settings.getBoolean(Config.KEY_MODEL_READY)
                && settings.getBoolean(Config.KEY_CLASSIFY_ENABLED, DEFAULT_CLASSIFY_ENABLED);
    }

    private void clearPromotions() {
        synchronized (lock) {
            try {
                for (Object record : (List<?>) records.get(service)) {
                    try {
                        StatusBarNotification item = (StatusBarNotification) getSbn.invoke(record);
                        updatePromotion(record, item, false);
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        Log.w(TAG, "Could not restore promotion notification", error);
                    }
                }
            } catch (ReflectiveOperationException error) {
                Log.w(TAG, "Could not restore promotion notifications", error);
            }
        }
    }

    private void updatePromotion(Object record, StatusBarNotification item, boolean promotion)
            throws ReflectiveOperationException {
        if (PromotionNotification.setMarked(item, promotion)) {
            PriorityNotification.notifyUpdated(listeners, notifyPosted, record, item);
        }
    }

    private void dispatchPendingClassifications() {
        if (!configReady || reduceController == null || !reduceController.hasPendingNotifications()) return;
        ArrayList<Entry> pendingEntries = new ArrayList<>();
        ArrayList<Entry> snapshot = new ArrayList<>();
        synchronized (lock) {
            try {
                for (String key : reduceController.pendingKeys()) {
                    Object record = recordsByKey.get(key);
                    if (record == null) continue;
                    StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
                    snapshot.add(entryFor(record, sbn, isPriorityEligible(sbn, settings())));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to scan pending Reduce Interruptions notifications", e);
            }
        }
        try {
            for (Entry entry : snapshot) {
                entry.prepareFingerprint();
                synchronized (lock) {
                    if (recordsByKey.get(entry.sbn.getKey()) == entry.record) collectPending(entry, true, pendingEntries);
                }
            }
        } finally {
            submitPending(pendingEntries);
        }
    }

    private void collectPending(Entry entry, boolean reducePending, List<Entry> pendingEntries) {
        StatusBarNotification sbn = entry.sbn;
        Notification notification = sbn.getNotification();
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        String key = sbn.getKey();
        boolean reduce = reducePending && reduceController.isPending(key, entry.fingerprint);
        boolean checkPromotion = promotionEnabled && modelReady
                && PromotionNotification.isCandidate(notification)
                && !java.util.Objects.equals(promotionAttempts.get(key), sbn.getPostTime());
        if (!reduce && !checkPromotion) return;
        if (!modelReady || (entry.title.isBlank() && entry.text.isBlank())) {
            if (reduce) reduceController.onClassifiedLocked(key, entry.fingerprint, false, false, entry.record, sbn);
            return;
        }
        PriorityDecision cached = priorityDecisions.get(key);
        if (cached != null && cached.matches(entry.fingerprint, notification.getChannelId())) {
            if (checkPromotion) {
                try {
                    updatePromotion(entry.record, sbn, cached.promotion());
                    promotionAttempts.put(key, sbn.getPostTime());
                } catch (ReflectiveOperationException error) {
                    Log.w(TAG, "Failed to update promotion", error);
                }
            }
            if (reduce) reduceController.onClassifiedLocked(key, entry.fingerprint, true, cached.important(), entry.record, sbn);
            return;
        }
        if (!classificationInFlight.add(entry.classificationToken)) return;
        if (checkPromotion) promotionAttempts.put(key, sbn.getPostTime());
        pendingEntries.add(entry);
    }

    private void submitPending(List<Entry> pendingEntries) {
        if (pendingEntries.isEmpty()) return;

        executor.execute(() -> {
            for (int start = 0; start < pendingEntries.size(); start += GroupSummaryData.MAX_ITEMS) {
                int end = Math.min(start + GroupSummaryData.MAX_ITEMS, pendingEntries.size());
                List<Entry> requested = pendingEntries.subList(start, end);
                Bundle classification = classify(requested);
                handler.post(() -> finishPendingClassification(requested, classification));
            }
        });
    }

    private void finishPendingClassification(List<Entry> items, Bundle classification) {
        boolean ok = classification != null && "0".equals(classification.getString("status"));
        ArrayList<String> classes = ok ? classification.getStringArrayList(PriorityNotification.CLASS_LIST) : null;
        ArrayList<String> vips = ok ? classification.getStringArrayList(PriorityNotification.VIP_LIST) : null;
        ArrayList<String> errors = ok ? classification.getStringArrayList(InferenceProvider.OUT_ERRORS) : null;
        int count = items.size();
        boolean validShape = ok && classes != null && vips != null && errors != null
                && classes.size() == count && vips.size() == count && errors.size() == count;

        synchronized (lock) {
            for (int index = 0; index < count; index++) {
                Entry requested = items.get(index);
                String key = requested.sbn.getKey();
                classificationInFlight.remove(requested.classificationToken);
                try {
                    boolean itemClassified = validShape && "0".equals(errors.get(index));
                    boolean vip = itemClassified && PriorityNotification.isImportant(
                            classes.get(index), vips.get(index));

                    Object record = recordsByKey.get(key);
                    StatusBarNotification sbn = record != null
                            ? (StatusBarNotification) getSbn.invoke(record)
                            : null;
                    boolean sameContent = record == requested.record && sbn != null
                            && requested.fingerprint.equals(GroupSummaryData.itemFingerprint(sbn))
                            && java.util.Objects.equals(
                                    requested.channelId,
                                    sbn.getNotification().getChannelId());

                    if (itemClassified && sameContent) {
                        boolean promotion = Classifier.CLASS_NOISE.equals(classes.get(index));
                        priorityDecisions.put(key, new PriorityDecision(
                                requested.fingerprint,
                                requested.channelId,
                                vip, promotion));
                        submittedItems.put(key, requested.fingerprint);
                        attempted.put("s:" + key, GroupSummaryData.fingerprintParts(
                                new ArrayList<>(List.of(key + ":" + requested.fingerprint))));
                        boolean important = priorityEnabled && vip && isPriorityEligible(sbn, settings());
                        boolean changed = PriorityNotification.setMarked(sbn, important);
                        changed |= PromotionNotification.setMarked(sbn, promotionEnabled && promotion
                                && PromotionNotification.isCandidate(sbn.getNotification()));
                        boolean breakthroughPending = vip && reduceController != null
                                && reduceController.isPending(key, requested.fingerprint);
                        if (changed && !breakthroughPending) {
                            PriorityNotification.notifyUpdated(listeners, notifyPosted, record, sbn);
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
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply Reduce Interruptions classification", e);
                }
            }
        }
        refresh();
    }

    private void request(Batch batch, boolean needsSummary) {
        if (needsSummary) {
            markSummarizing(batch);
        }
        // Pass1:仅对尚未分类的条目做一次分类;已分类条目与重要通知组不再重复分类,避免耗时与模型抖动导致退出重要通知。
        Bundle classes = classify(batch.toClassify);
        if (!batch.toClassify.isEmpty()) handler.post(() -> {
            try {
                if (classes != null) applyPriorityClassification(batch.toClassify, classes);
            } finally {
                for (Entry entry : batch.toClassify) classificationInFlight.remove(entry.classificationToken);
                if (reduceController != null && reduceController.hasPendingNotifications()) {
                    dispatchPendingClassifications();
                }
            }
        });
        boolean skipGroupSummaryForNewVip = batch.summary != null && !batch.priorityGroup
                && priorityEnabled && containsImportantVip(classes);
        Bundle reply = null;
        // 对齐 iOS 语义:单条通知不摘要、原文完整显示;只有成组批次才请求组摘要。
        // 分类照常执行(单条噪音治理仍然生效),单条时 reply 为 null,finish() 只走静默路径。
        if (needsSummary && !skipGroupSummaryForNewVip) {
            try {
                Bundle input = new Bundle();
                ArrayList<String> messages = new ArrayList<>();
                ArrayList<String> titles = new ArrayList<>();
                for (Entry entry : batch.requested) {
                    messages.add(entry.text);
                    titles.add(entry.title);
                }
                input.putStringArrayList("messageList", messages);
                input.putStringArrayList("titles", titles);
                String packageName = batch.children.get(0).sbn.getPackageName();
                input.putString("package", packageName);
                input.putString("appLabel", appLabel(packageName));
                input.putInt("totalCount", batch.children.size());
                input.putInt("distinctTitles", countDistinctTitles(batch.children));
                input.putBoolean(Config.KEY_INCLUDE_ITEM_SUMMARIES, false);
                Log.i(TAG, "Requesting group summary: " + batch.requested.size() + " notification(s)");
                reply = ModuleMain.callProvider(context, "summarize_group", input);
            } catch (Exception e) {
                Log.e(TAG, "Summary request failed", e);
            }
        }
        Bundle result = reply;
        boolean skippedSummary = skipGroupSummaryForNewVip;
        handler.post(() -> finish(batch, result, skippedSummary));
    }

    private static int countDistinctTitles(List<Entry> children) {
        if (children == null || children.isEmpty()) return 0;
        Set<String> seen = new HashSet<>();
        for (Entry child : children) {
            String title = child.title == null ? "" : child.title.trim().toLowerCase(java.util.Locale.ROOT);
            if (!title.isEmpty()) seen.add(title);
        }
        return seen.size();
    }

    /** User-visible app name for summary prompts; empty on failure so the provider falls back. */
    private String appLabel(String packageName) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
            return label == null ? "" : label.toString().trim();
        } catch (Exception e) {
            return "";
        }
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
                            requested.channelId,
                            PriorityNotification.isImportant(classes.get(index), vips.get(index)),
                            Classifier.CLASS_NOISE.equals(classes.get(index)));
                    if (!decision.matches(GroupSummaryData.itemFingerprint(sbn), sbn.getNotification().getChannelId())) continue;
                    PriorityDecision existing = priorityDecisions.get(sbn.getKey());
                    if (existing != null && existing.matches(requested.fingerprint,
                            requested.channelId)) {
                        continue;
                    }
                    priorityDecisions.put(sbn.getKey(), decision);
                    if (record != requested.record) continue;
                    boolean changed = PriorityNotification.setMarked(sbn, priorityEnabled
                            && isPriorityEligible(sbn, currentSettings) && decision.important());
                    changed |= PromotionNotification.setMarked(sbn, promotionEnabled && decision.promotion()
                            && PromotionNotification.isCandidate(sbn.getNotification()));
                    if (changed) {
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
                channels.add(entry.channelId);
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

    private void finish(Batch requested, Bundle reply, boolean skippedSummary) {
        String summary = reply != null && "0".equals(reply.getString("status"))
                ? reply.getString("group_summary", "") : "";
        if (requested.summary != null && !summary.isBlank()) {
            cachedGroups.put(requested.id, new CachedGroup(requested.fingerprint, summary));
        } else {
            boolean screenDeferred = requested.summary != null && !settings().getBoolean(
                    Config.KEY_SUMMARIZE_SCREEN_ON, Config.DEFAULT_SUMMARIZE_SCREEN_ON) && power.isInteractive();
            if (skippedSummary || screenDeferred) attempted.remove(requested.id, requested.fingerprint);
            clearSummarizing(requested);
        }
        inFlight.remove(requested.id);
        refresh();
    }

    private Entry entryFor(Object record, StatusBarNotification sbn, boolean eligible) {
        String key = sbn.getKey();
        String title = GroupSummaryData.title(sbn.getNotification());
        String text = GroupSummaryData.text(sbn.getNotification());
        Entry cached = entriesByKey.get(key);
        boolean sameContent = cached != null && cached.title.equals(title) && cached.text.equals(text);
        if (sameContent && cached.record == record && cached.sbn == sbn && cached.eligible == eligible
                && cached.when == sbn.getNotification().when
                && java.util.Objects.equals(cached.channelId, sbn.getNotification().getChannelId())) return cached;
        String fingerprint = sameContent ? cached.fingerprint : null;
        Entry entry = new Entry(record, sbn, eligible, title, text, fingerprint);
        entriesByKey.put(key, entry);
        return entry;
    }

    private void setGroup(Entry entry, String versionFingerprint, String summary) throws Exception {
        Bundle extras = entry.sbn.getNotification().extras;
        boolean hadSummarizing = extras.containsKey(GroupSummaryData.SUMMARIZING_FP);
        if (!hadSummarizing && versionFingerprint.equals(extras.getString(GroupSummaryData.FINGERPRINT))
                && summary.equals(extras.getString(GroupSummaryData.SUMMARY))) return;
        extras.remove(GroupSummaryData.SUMMARIZING_FP);
        extras.putString(GroupSummaryData.FINGERPRINT, versionFingerprint);
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

    private void markSummarizing(Batch batch) {
        if (batch == null || batch.summary == null) return;
        String summaryKey = batch.summary.sbn.getKey();
        synchronized (lock) {
            try {
                Object record = recordsByKey.get(summaryKey);
                if (record == null) return;
                StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
                if (sbn == null) return;
                Bundle extras = sbn.getNotification().extras;
                // Do not show thinking indicator if this exact version already has a finished summary.
                String existingSummary = extras.getString(GroupSummaryData.SUMMARY);
                if (existingSummary != null && !existingSummary.isBlank()
                        && batch.versionFingerprint.equals(extras.getString(GroupSummaryData.FINGERPRINT))) {
                    return;
                }
                if (batch.versionFingerprint.equals(extras.getString(GroupSummaryData.SUMMARIZING_FP))) return;
                extras.putString(GroupSummaryData.SUMMARIZING_FP, batch.versionFingerprint);
                PriorityNotification.notifyUpdated(listeners, notifyPosted, record, sbn);
            } catch (Exception e) {
                Log.w(TAG, "Failed to mark summarizing state", e);
            }
        }
    }

    private void clearSummarizing(Entry entry) throws Exception {
        if (entry == null) return;
        Bundle extras = entry.sbn.getNotification().extras;
        if (!extras.containsKey(GroupSummaryData.SUMMARIZING_FP)) return;
        extras.remove(GroupSummaryData.SUMMARIZING_FP);
        PriorityNotification.notifyUpdated(listeners, notifyPosted, entry.record, entry.sbn);
    }

    private void clearSummarizing(Batch batch) {
        if (batch == null || batch.summary == null) return;
        String summaryKey = batch.summary.sbn.getKey();
        synchronized (lock) {
            try {
                Object record = recordsByKey.get(summaryKey);
                if (record == null) return;
                StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
                if (sbn == null) return;
                Bundle extras = sbn.getNotification().extras;
                String currentFp = extras.getString(GroupSummaryData.SUMMARIZING_FP);
                if (currentFp == null) return;
                if (!batch.versionFingerprint.equals(currentFp) && inFlight.contains(batch.id)) return;
                extras.remove(GroupSummaryData.SUMMARIZING_FP);
                PriorityNotification.notifyUpdated(listeners, notifyPosted, record, sbn);
            } catch (Exception e) {
                Log.w(TAG, "Failed to clear summarizing state", e);
            }
        }
    }

    private static final class Entry {
        final Object record;
        final StatusBarNotification sbn;
        final boolean eligible;
        final String title;
        final String text;
        final String channelId;
        final long when;
        String groupKey;
        int flags;
        boolean visible, priority, prioritySummary, priorityGrouped, promotion;
        String fingerprint;
        String classificationToken;

        Entry(Object record, StatusBarNotification sbn, boolean eligible, String title, String text, String fingerprint) {
            this.record = record;
            this.sbn = sbn;
            this.eligible = eligible;
            this.title = title;
            this.text = text;
            this.fingerprint = fingerprint;
            this.channelId = sbn.getNotification().getChannelId();
            this.when = sbn.getNotification().when;
        }

        void captureGrouping(boolean visible) {
            this.visible = visible;
            groupKey = sbn.getGroupKey();
            flags = sbn.getNotification().flags;
            priority = PriorityNotification.isMarked(sbn);
            prioritySummary = PriorityNotification.isGroupSummary(sbn);
            priorityGrouped = PriorityNotification.isGrouped(sbn);
            promotion = PromotionNotification.isMarked(sbn);
        }

        void prepareFingerprint() {
            if (classificationToken != null) return;
            if (fingerprint == null) fingerprint = GroupSummaryData.itemFingerprint(sbn.getKey(), title, text);
            classificationToken = sbn.getKey() + ":" + fingerprint;
        }
    }

    private final class Batch {
        final String id;
        final Entry summary;
        final List<Entry> children;
        final List<Entry> requested;
        final List<Entry> toClassify;
        final String fingerprint;
        final String versionFingerprint;
        final boolean priorityGroup;

        Batch(String id, Entry summary, List<Entry> children, boolean classifyEnabled) {
            this.id = id;
            this.summary = summary;
            this.children = new ArrayList<>(children);
            priorityGroup = summary != null && summary.prioritySummary;
            fingerprint = groupFingerprint(children);
            versionFingerprint = groupVersionFingerprint(children);
            if (children.size() == 1) {
                requested = this.children;
            } else {
                ArrayList<Entry> sorted = new ArrayList<>(children);
                sorted.sort(REQUEST_ORDER);
                requested = new ArrayList<>(sorted.subList(
                        Math.max(0, sorted.size() - GroupSummaryData.MAX_ITEMS), sorted.size()));
            }
            toClassify = unclassifiedItems(requested, classifyEnabled, priorityGroup);
        }
    }

    private static final class CachedGroup {
        final String fingerprint;
        final String summary;
        boolean pendingPresentation = true;

        CachedGroup(String fingerprint, String summary) {
            this.fingerprint = fingerprint;
            this.summary = summary;
        }
    }

    private record PriorityDecision(String fingerprint, String channelId, boolean important, boolean promotion) {
        boolean matches(String currentFingerprint, String currentChannel) {
            return java.util.Objects.equals(channelId, currentChannel) && fingerprint.equals(currentFingerprint);
        }
    }
}
