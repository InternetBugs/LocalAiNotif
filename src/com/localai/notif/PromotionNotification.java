package com.localai.notif;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

final class PromotionNotification {
    static final String TITLE = "推广通知";
    private static final String VERSION = "localai.promotion_version";

    private PromotionNotification() {}

    /** 可参与推广收纳的通知：沿用分类资格，并排除聊天类会话。 */
    static boolean isCandidate(Notification notification) {
        if (!ReduceInterruptions.isEligibleForClassification(notification)) return false;
        if (Notification.CATEGORY_MESSAGE.equals(notification.category)) return false;
        Bundle extras = notification.extras;
        String template = extras == null ? null : extras.getString(Notification.EXTRA_TEMPLATE);
        return template == null || !template.endsWith("$MessagingStyle");
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
