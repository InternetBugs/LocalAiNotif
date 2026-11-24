package com.localai.notif;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Method;

final class PriorityNotification {
    static final String EXTRA_PRIORITY = "localai.priority";
    static final String EXTRA_GROUP_SUMMARY = "localai.priority_group_summary";
    private static final String GROUP_NAME = "com.localai.notif.priority";
    static final String CLASS_LIST = "localai_class_list";
    static final String VIP_LIST = "localai_vip_list";
    private static final ThreadLocal<Object> publishingRecord = new ThreadLocal<>();

    private PriorityNotification() {}

    static boolean isEligible(Notification notification) {
        return (notification.flags & (0x08000000 | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) == 0;
    }

    static boolean isMarked(StatusBarNotification sbn) {
        return sbn != null && sbn.getNotification().extras != null
                && sbn.getNotification().extras.getBoolean(EXTRA_PRIORITY);
    }

    static boolean isGroupSummary(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                && isGrouped(sbn)
                && notification.extras != null && notification.extras.getBoolean(EXTRA_GROUP_SUMMARY);
    }

    static boolean isGrouped(StatusBarNotification sbn) {
        return groupKey(sbn.getUserId(), sbn.getPackageName()).equals(sbn.getOverrideGroupKey());
    }

    static String groupKey(int userId, String packageName) {
        return userId + "|" + packageName + "|g:" + GROUP_NAME;
    }

    static boolean setMarked(StatusBarNotification sbn, boolean important) {
        if (isMarked(sbn) == important) return false;
        Notification notification = sbn.getNotification();
        if (important) {
            if (notification.extras == null) notification.extras = new Bundle();
            notification.extras.putBoolean(EXTRA_PRIORITY, true);
        } else {
            notification.extras.remove(EXTRA_PRIORITY);
        }
        return true;
    }

    static boolean isImportant(String classification, String vip) {
        return Classifier.CLASS_IMPORTANT.equals(classification) && "true".equals(vip);
    }

    static void notifyUpdated(Object listeners, Method notifyPosted, Object record, StatusBarNotification sbn)
            throws ReflectiveOperationException {
        sbn.getNotification().flags |= Notification.FLAG_ONLY_ALERT_ONCE;
        Object previous = publishingRecord.get();
        publishingRecord.set(record);
        try {
            notifyPosted.invoke(listeners, record, record);
        } finally {
            if (previous == null) publishingRecord.remove();
            else publishingRecord.set(previous);
        }
    }

    static boolean isPublishing(Object record) {
        return record != null && record == publishingRecord.get();
    }
}
