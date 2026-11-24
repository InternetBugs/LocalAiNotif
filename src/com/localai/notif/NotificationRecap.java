package com.localai.notif;

import android.app.Notification;
import android.os.Bundle;

import java.util.ArrayList;

final class NotificationRecap {
    static final String METHOD = "summarize_recap";
    static final String TITLE = "title";
    static final String BODY = "body";
    static final String MIN_LENGTH = "min_length";
    static final String EXTRA = "localai.notification_recap";
    static final String EXTRA_IN_FLIGHT = "localai.notification_recap_in_flight";
    static final String LABEL = "AI 概括";
    static final int MAX_INPUT_LENGTH = 2400;

    private NotificationRecap() {}

    static String body(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return "";
        Bundle[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle.class);
        if (messages != null && messages.length > 0) {
            Bundle latest = messages[messages.length - 1];
            return latest == null ? "" : asText(latest.getCharSequence("text"));
        }
        String bigText = asText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        return bigText.isEmpty() ? asText(extras.getCharSequence(Notification.EXTRA_TEXT)) : bigText;
    }

    static boolean isLong(String body) {
        return isLong(body, Config.DEFAULT_RECAP_MIN_LENGTH);
    }

    static boolean isLong(String body, int minChineseChars) {
        if (body == null || body.isEmpty()) return false;
        int minUnits = Math.max(1, minChineseChars) * 2;
        int units = 0;
        for (int offset = 0; offset < body.length() && units < minUnits; ) {
            int character = body.codePointAt(offset);
            if (Character.isLetterOrDigit(character)) {
                units += Character.isIdeographic(character) || character >= 0x3000 ? 2 : 1;
            }
            offset += Character.charCount(character);
        }
        return units >= minUnits;
    }

    static String bodyFingerprint(String body) {
        if (body == null || body.isEmpty()) return "";
        ArrayList<String> parts = new ArrayList<>(1);
        parts.add(body);
        return GroupSummaryData.fingerprintParts(parts);
    }

    private static String asText(CharSequence text) {
        return text == null ? "" : text.toString().strip();
    }
}
