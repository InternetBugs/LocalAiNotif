package com.localai.notif;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

final class GroupSummaryData {
    static final String ORIGINAL_TITLE = "localai.original_title";
    static final String ORIGINAL_TEXT = "localai.original_text";
    static final String SUMMARY = "localai.group_summary";
    static final String FINGERPRINT = "localai.group_fingerprint";
    static final String SUMMARIZING_FP = "localai.group_summarizing_fp";
    static final int AI_FLAG = 0x10000000;
    static final int MAX_ITEMS = 10;
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    });

    private GroupSummaryData() {}

    static String title(Notification notification) {
        Bundle extras = notification.extras;
        return string(extras.getCharSequence(extras.containsKey(ORIGINAL_TITLE)
                ? ORIGINAL_TITLE : Notification.EXTRA_TITLE));
    }

    static String text(Notification notification) {
        Bundle extras = notification.extras;
        if (extras.containsKey(ORIGINAL_TEXT)) return string(extras.getCharSequence(ORIGINAL_TEXT));
        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        return string(bigText != null && bigText.length() > 0
                ? bigText : extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    static void rememberOriginal(Notification notification) {
        if ((notification.flags & AI_FLAG) != 0) return;
        Bundle extras = notification.extras;
        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        extras.putCharSequence(ORIGINAL_TITLE, extras.getCharSequence(Notification.EXTRA_TITLE));
        extras.putCharSequence(ORIGINAL_TEXT, bigText != null && bigText.length() > 0
                ? bigText : extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    static String itemFingerprint(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        return itemFingerprint(sbn.getKey(), title(notification), text(notification));
    }

    static String itemFingerprint(String key, String title, String text) {
        return digest(List.of(key, title, text));
    }

    static String fingerprint(List<StatusBarNotification> children) {
        ArrayList<String> items = new ArrayList<>(children.size());
        for (StatusBarNotification child : children) {
            items.add(child.getKey() + ":" + itemFingerprint(child));
        }
        return fingerprintParts(items);
    }

    static String versionPart(StatusBarNotification child) {
        return child.getKey() + ":" + child.getPostTime();
    }

    static String versionFingerprint(List<StatusBarNotification> children) {
        ArrayList<String> items = new ArrayList<>(children.size());
        for (StatusBarNotification child : children) {
            items.add(versionPart(child));
        }
        return fingerprintParts(items);
    }

    static String fingerprintParts(List<String> items) {
        Collections.sort(items);
        return digest(items);
    }

    private static String digest(List<String> values) {
        MessageDigest hash = SHA256.get();
        hash.reset();
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            hash.update((byte) ':');
            hash.update(bytes);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    static String clean(String content) {
        String result = content.replaceAll("(?is)<think.*?</think\\s*>", "").trim();
        if (result.startsWith("```")) {
            int newline = result.indexOf('\n');
            if (newline >= 0 && result.endsWith("```")) {
                result = result.substring(newline + 1, result.length() - 3).trim();
            }
        }
        return result;
    }

    static Bundle parse(String content, int itemCount) throws Exception {
        JSONObject object = new JSONObject(clean(content));
        Object rawGroup = object.opt("group");
        String summary = rawGroup instanceof String
                ? ((String) rawGroup).replaceAll("\\s+", " ").trim() : "";
        ArrayList<String> items = new ArrayList<>();
        JSONObject summaries = object.optJSONObject("items");
        for (int i = 0; i < itemCount; i++) {
            Object value = summaries == null ? null : summaries.opt(Integer.toString(i + 1));
            items.add(value instanceof String ? ((String) value).trim() : "");
        }
        Bundle result = new Bundle();
        result.putString("group_summary", summary);
        result.putStringArrayList("outputtext_list", items);
        result.putString("status", "0");
        return result;
    }

    private static String string(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
