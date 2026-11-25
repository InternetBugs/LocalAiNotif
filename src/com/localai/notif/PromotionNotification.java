package com.localai.notif;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

final class PromotionNotification {
    static final String TITLE = "推广通知";
    private static final String VERSION = "localai.promotion_version";

    private PromotionNotification() {}

    /** 可参与推广收纳的通知：与分类资格保持一致，完全交由端侧模型判定。 */
    static boolean isCandidate(Notification notification) {
        return ReduceInterruptions.isEligibleForClassification(notification);
    }

    static boolean isMarked(StatusBarNotification item) {
        Bundle extras = item.getNotification().extras;
        return extras != null && extras.getLong(VERSION, Long.MIN_VALUE) == item.getPostTime();
    }

    static boolean setMarked(StatusBarNotification item, boolean promotion) {
        Bundle extras = item.getNotification().extras;
        if (promotion) {
            if (isMarked(item)) return false;
            if (extras == null) item.getNotification().extras = extras = new Bundle();
            extras.putLong(VERSION, item.getPostTime());
        } else {
            if (extras == null || !extras.containsKey(VERSION)) return false;
            extras.remove(VERSION);
        }
        return true;
    }
}
