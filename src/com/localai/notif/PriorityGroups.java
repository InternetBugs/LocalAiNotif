package com.localai.notif;

import android.app.Notification;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PriorityGroups {
    private static final int SUMMARY_ID = 0x4c414e03;
    private static final int FLAG_AUTOGROUP_SUMMARY = 0x400;
    private static final int SUMMARY_BASE_FLAGS = Notification.FLAG_LOCAL_ONLY
            | Notification.FLAG_GROUP_SUMMARY | FLAG_AUTOGROUP_SUMMARY;
    private static final long POST_TIMEOUT_MS = 2_000L;

    private final Object service;
    private final Object lock;
    private final Method getSbn;
    private final Method notifyPosted;
    private final Object listeners;
    private final Map<?, ?> recordsByKey;
    private final Field groupHelper;
    private final Field groupCallback;
    private final Field enqueuedNotifications;
    private final Constructor<?> newAttributes;
    private final Method addSummary;
    private final Method clearSummary;
    private final Map<App, Group> groups = new HashMap<>();
    private final Map<String, NativeGroup> nativeGroups = new HashMap<>();

    PriorityGroups(ModuleMain module, Object service, Object lock, Class<?> record,
            Method getSbn, Object listeners, Method notifyPosted) throws Exception {
        this.service = service;
        this.lock = lock;
        this.getSbn = getSbn;
        this.listeners = listeners;
        this.notifyPosted = notifyPosted;
        Class<?> nms = service.getClass();
        recordsByKey = (Map<?, ?>) ModuleMain.findField(nms, "mNotificationsByKey").get(service);
        groupHelper = ModuleMain.findField(nms, "mGroupHelper");
        Class<?> helper = groupHelper.getType();
        groupCallback = ModuleMain.findField(helper, "mCallback");
        enqueuedNotifications = ModuleMain.findField(nms, "mEnqueuedNotifications");
        Class<?> attributes = helper.getClassLoader().loadClass(helper.getName()
                + "$NotificationAttributes");
        newAttributes = attributes.getDeclaredConstructor(int.class, Icon.class, int.class,
                int.class, int.class, String.class);
        newAttributes.setAccessible(true);
        addSummary = ModuleMain.findMethod(groupCallback.getType(), "addAutoGroupSummary",
                int.class, String.class, String.class, String.class, int.class, attributes);
        clearSummary = ModuleMain.findMethod(nms, "clearAutogroupSummaryLocked",
                int.class, String.class, String.class);
        Method createSummary = ModuleMain.findMethod(nms, "createAutoGroupSummary",
                int.class, String.class, String.class, String.class, int.class, attributes);
        Method setPostSilently = ModuleMain.findMethod(record, "setPostSilently", boolean.class);
        Method setRecordOverride = ModuleMain.findMethod(record, "setOverrideGroupKey", String.class);
        module.hook(setRecordOverride).intercept(chain -> {
            synchronized (lock) {
                Object rec = chain.getThisObject();
                StatusBarNotification sbn = sbn(rec);
                if (isPrioritySummaryRecord(rec)) {
                    String override = (String) chain.getArg(0);
                    if (!PriorityNotification.groupKey(sbn.getUserId(), sbn.getPackageName())
                            .equals(override)) return null;
                }
                NativeGroup group = nativeGroups.get(sbn.getKey());
                if (group == null || group.owner != sbn) return chain.proceed();
                String override = (String) chain.getArg(0);
                if (!PriorityNotification.groupKey(sbn.getUserId(), sbn.getPackageName())
                        .equals(override)) group.override = override;
                return null;
            }
        });
        module.hook(createSummary).intercept(chain -> {
            Object result = chain.proceed();
            if (result == null || !Integer.valueOf(SUMMARY_ID).equals(chain.getArg(4))
                    || !PriorityNotification.groupKey((int) chain.getArg(0), (String) chain.getArg(1))
                            .equals(chain.getArg(3))) return result;
            StatusBarNotification sbn = sbn(result);
            sbn.getNotification().extras.putBoolean(PriorityNotification.EXTRA_GROUP_SUMMARY, true);
            setPostSilently.invoke(result, true);
            synchronized (lock) {
                Group group = groups.get(App.of(sbn));
                if (group != null) group.pending = sbn;
            }
            return result;
        });
        installGroupHelperProtection(module, nms, helper, record);
    }

    /**
     * Prevents Android 17 GroupHelper's delayed sparse-group / empty-summary cleanup
     * (3000ms after post) from stripping FLAG_GROUP_SUMMARY or canceling our synthetic
     * priority group summary or re-aggregating its VIP children back into Aggregate_*.
     */
    private void installGroupHelperProtection(ModuleMain module, Class<?> nms,
            Class<?> helper, Class<?> record) {
        Method convertSummary = ModuleMain.findMethod(
                nms, "convertSummaryToNotificationLocked", String.class);
        if (convertSummary != null) {
            module.hook(convertSummary).intercept(chain -> {
                String key = (String) chain.getArg(0);
                if (isPrioritySummaryKey(key)) return Boolean.FALSE;
                return chain.proceed();
            });
        }
        Method removeAppSummary = ModuleMain.findMethod(
                nms, "removeAppSummaryLocked", String.class);
        if (removeAppSummary != null) {
            module.hook(removeAppSummary).intercept(chain -> {
                String key = (String) chain.getArg(0);
                if (isPrioritySummaryKey(key)) return null;
                return chain.proceed();
            });
        }
        Method onPosted = ModuleMain.findMethod(helper, "onNotificationPosted", record, boolean.class);
        if (onPosted != null) {
            module.hook(onPosted).intercept(chain -> {
                if (isPrioritySummaryRecord(chain.getArg(0))) return Boolean.FALSE;
                return chain.proceed();
            });
        }
        Method onPostedWithDelay = ModuleMain.findMethod(
                helper, "onNotificationPostedWithDelay", record, List.class, Map.class);
        if (onPostedWithDelay != null) {
            module.hook(onPostedWithDelay).intercept(chain -> {
                Object rec = chain.getArg(0);
                if (isPrioritySummaryRecord(rec) || isPriorityManagedChild(rec)) return null;
                return chain.proceed();
            });
        }
        Method onRemovedWithDelay = ModuleMain.findMethod(
                helper, "onGroupedNotificationRemovedWithDelay", record, List.class, Map.class);
        if (onRemovedWithDelay != null) {
            module.hook(onRemovedWithDelay).intercept(chain -> {
                Object rec = chain.getArg(0);
                if (isPrioritySummaryRecord(rec) || isPriorityManagedChild(rec)) return null;
                return chain.proceed();
            });
        }
        for (Method m : helper.getDeclaredMethods()) {
            if (!"getSparseGroups".equals(m.getName())) continue;
            m.setAccessible(true);
            module.hook(m).intercept(chain -> {
                Object res = chain.proceed();
                if (res instanceof Map<?, ?> sparse && !sparse.isEmpty()) {
                    sparse.entrySet().removeIf(e -> isPrioritySummaryRecord(e.getValue()));
                }
                return res;
            });
            break;
        }
    }

    private boolean isPrioritySummaryKey(String key) {
        if (key == null || recordsByKey == null) return false;
        return isPrioritySummaryRecord(recordsByKey.get(key));
    }

    private boolean isPrioritySummaryRecord(Object record) {
        if (record == null) return false;
        try {
            StatusBarNotification sbn = sbn(record);
            if (sbn == null || sbn.getNotification() == null) return false;
            if (PriorityNotification.isGroupSummary(sbn)) return true;
            return sbn.getId() == SUMMARY_ID
                    && sbn.getNotification().extras != null
                    && sbn.getNotification().extras.getBoolean(PriorityNotification.EXTRA_GROUP_SUMMARY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isPriorityManagedChild(Object record) {
        if (record == null) return false;
        try {
            StatusBarNotification sbn = sbn(record);
            return sbn != null && PriorityNotification.isGrouped(sbn);
        } catch (Throwable ignored) {
            return false;
        }
    }

    void reconcile(List<?> records, boolean enabled) throws ReflectiveOperationException {
        Map<App, List<Object>> members = new LinkedHashMap<>();
        Map<App, Object> summaries = new HashMap<>();
        for (Object record : records) {
            StatusBarNotification sbn = sbn(record);
            if (PriorityNotification.isGroupSummary(sbn)) {
                summaries.put(App.of(sbn), record);
            } else if (enabled && PriorityNotification.isMarked(sbn)) {
                members.computeIfAbsent(App.of(sbn), app -> new ArrayList<>()).add(record);
            }
        }

        groups.entrySet().removeIf(entry -> entry.getValue().retiring
                && !summaries.containsKey(entry.getKey()));
        Set<String> grouped = new HashSet<>();
        for (Map.Entry<App, List<Object>> entry : members.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            App app = entry.getKey();
            Group group = groups.computeIfAbsent(app, key -> new Group());
            if (group.retiring) continue;
            Object summaryRecord = summaries.get(app);
            if (summaryRecord == null) {
                requestSummary(app, group, entry.getValue());
                continue;
            }
            group.pending = null;
            Notification summary = sbn(summaryRecord).getNotification();
            int visibility = groupVisibility(entry.getValue());
            if (summary.visibility != visibility) {
                summary.visibility = visibility;
                PriorityNotification.notifyUpdated(listeners, notifyPosted, summaryRecord, sbn(summaryRecord));
            }
            for (Object record : entry.getValue()) {
                StatusBarNotification child = sbn(record);
                NativeGroup original = nativeGroups.get(child.getKey());
                if (original == null || original.owner != child) {
                    nativeGroups.put(child.getKey(), new NativeGroup(child, child.getOverrideGroupKey()));
                }
                updateOverride(record, child, app.groupKey());
                grouped.add(child.getKey());
            }
        }
        for (Object record : (List<?>) enqueuedNotifications.get(service)) {
            StatusBarNotification child = sbn(record);
            NativeGroup original = nativeGroups.get(child.getKey());
            if (original != null && !grouped.contains(child.getKey())
                    && PriorityNotification.isGrouped(child)) {
                child.setOverrideGroupKey(original.override);
            }
        }
        for (String key : new ArrayList<>(nativeGroups.keySet())) {
            if (grouped.contains(key)) continue;
            NativeGroup original = nativeGroups.remove(key);
            Object record = recordsByKey.get(key);
            if (record != null && sbn(record) == original.owner) updateOverride(record, original.owner, original.override);
        }
        for (App app : new ArrayList<>(groups.keySet())) {
            List<Object> remaining = members.get(app);
            if (remaining != null && remaining.size() >= 2) continue;
            Group group = groups.get(app);
            if (group.retiring) continue;
            Object summaryRecord = summaries.get(app);
            StatusBarNotification summary = summaryRecord == null ? null : sbn(summaryRecord);
            if (summary == null && group.pending != null && !postTimedOut(group)) continue;
            if (summary == null) summary = group.pending;
            if (summary != null && summary.getId() == SUMMARY_ID
                    && app.groupKey().equals(summary.getGroupKey())) {
                clearSummary.invoke(service, app.user(), app.pkg(), app.groupKey());
                group.retiring = summaryRecord != null;
            }
            if (!group.retiring) groups.remove(app);
        }
    }

    private int groupVisibility(List<Object> records) throws ReflectiveOperationException {
        int visibility = Notification.VISIBILITY_PUBLIC;
        for (Object record : records) {
            visibility = Math.min(visibility, sbn(record).getNotification().visibility);
        }
        return visibility;
    }

    private void requestSummary(App app, Group group, List<Object> records)
            throws ReflectiveOperationException {
        if (group.pending != null && !postTimedOut(group)) return;
        StatusBarNotification anchor = sbn(records.get(0));
        List<String> keys = new ArrayList<>();
        for (Object record : records) {
            StatusBarNotification child = sbn(record);
            keys.add(child.getKey());
            if (child.getPostTime() > anchor.getPostTime()) anchor = child;
        }
        Collections.sort(keys);
        if (keys.equals(group.requested) && !postTimedOut(group)) return;
        Notification notification = anchor.getNotification();
        if (notification.getSmallIcon() == null) return;
        Object callback = groupCallback.get(groupHelper.get(service));
        Object attributes = newAttributes.newInstance(SUMMARY_BASE_FLAGS,
                notification.getSmallIcon(), notification.color, groupVisibility(records),
                Notification.GROUP_ALERT_CHILDREN, notification.getChannelId());
        group.requested = keys;
        group.requestedAt = SystemClock.uptimeMillis();
        addSummary.invoke(callback, app.user(), app.pkg(), anchor.getKey(),
                app.groupKey(), SUMMARY_ID, attributes);
    }

    private void updateOverride(Object record, StatusBarNotification sbn, String override)
            throws ReflectiveOperationException {
        if (Objects.equals(override, sbn.getOverrideGroupKey())) return;
        sbn.setOverrideGroupKey(override);
        PriorityNotification.notifyUpdated(listeners, notifyPosted, record, sbn);
    }

    private StatusBarNotification sbn(Object record) throws ReflectiveOperationException {
        return (StatusBarNotification) getSbn.invoke(record);
    }

    private static boolean postTimedOut(Group group) {
        return SystemClock.uptimeMillis() - group.requestedAt >= POST_TIMEOUT_MS;
    }

    private record App(int user, String pkg) {
        static App of(StatusBarNotification sbn) {
            return new App(sbn.getUserId(), sbn.getPackageName());
        }

        String groupKey() {
            return PriorityNotification.groupKey(user, pkg);
        }
    }

    private static final class Group {
        StatusBarNotification pending;
        List<String> requested;
        long requestedAt;
        boolean retiring;
    }

    private static final class NativeGroup {
        final StatusBarNotification owner;
        String override;

        NativeGroup(StatusBarNotification owner, String override) {
            this.owner = owner;
            this.override = override;
        }
    }
}
