package io.github.internetbugs.localainotif;

import android.app.Notification;
import android.app.Person;
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
    private static final int MAX_MESSAGING_TURNS = 4;
    private static final int MAX_INBOX_LINES = 4;
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    });

    private GroupSummaryData() {}

    static String title(Notification notification) {
        if (notification == null || notification.extras == null) return "";
        Bundle extras = notification.extras;
        if (extras.containsKey(ORIGINAL_TITLE)) {
            return string(extras.getCharSequence(ORIGINAL_TITLE));
        }
        return rawTitle(extras);
    }

    static String text(Notification notification) {
        if (notification == null || notification.extras == null) return "";
        Bundle extras = notification.extras;
        if (extras.containsKey(ORIGINAL_TEXT)) return string(extras.getCharSequence(ORIGINAL_TEXT));
        return extractText(extras, rawTitle(extras));
    }

    static void rememberOriginal(Notification notification) {
        if (notification == null || (notification.flags & AI_FLAG) != 0 || notification.extras == null) return;
        Bundle extras = notification.extras;
        if (extras.containsKey(ORIGINAL_TITLE) && extras.containsKey(ORIGINAL_TEXT)) return;
        String resolvedTitle = rawTitle(extras);
        if (!extras.containsKey(ORIGINAL_TITLE)) {
            extras.putCharSequence(ORIGINAL_TITLE, resolvedTitle);
        }
        if (!extras.containsKey(ORIGINAL_TEXT)) {
            extras.putCharSequence(ORIGINAL_TEXT, extractText(extras, resolvedTitle));
        }
    }

    private static String rawTitle(Bundle extras) {
        CharSequence conv = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        boolean hasMessages = extras.containsKey(Notification.EXTRA_MESSAGES);
        if (hasMessages && conv != null && conv.toString().trim().length() > 0) {
            return conv.toString().trim();
        }
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title != null && title.toString().trim().length() > 0) return title.toString();
        return string(conv);
    }

    private static String extractText(Bundle extras, String resolvedTitle) {
        String bigText = string(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).trim();
        String plainText = string(extras.getCharSequence(Notification.EXTRA_TEXT)).trim();

        String messaging = extractMessagingText(extras, resolvedTitle);
        if (!messaging.isEmpty()) {
            // If only a single short turn existed and EXTRA_BIG_TEXT carries fuller text, keep the fuller text.
            if (messaging.indexOf('；') < 0 && bigText.length() > messaging.length()) {
                return bigText;
            }
            return messaging;
        }

        String inbox = extractInboxText(extras);
        if (!inbox.isEmpty()) {
            if (inbox.indexOf('；') < 0 && bigText.length() > inbox.length()) {
                return bigText;
            }
            return inbox;
        }

        return !bigText.isEmpty() ? bigText : plainText;
    }

    private static String extractMessagingText(Bundle extras, String resolvedTitle) {
        Bundle[] items = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle.class);
        if (items == null || items.length == 0) return "";
        ArrayList<String> senders = new ArrayList<>(Math.min(items.length, MAX_MESSAGING_TURNS));
        ArrayList<String> texts = new ArrayList<>(Math.min(items.length, MAX_MESSAGING_TURNS));
        for (Bundle msg : items) {
            if (msg == null) continue;
            String body = string(msg.getCharSequence("text")).replaceAll("\\s+", " ").trim();
            if (body.isEmpty()) continue;
            String sender = "";
            Person person = msg.getParcelable("sender_person", Person.class);
            if (person != null && person.getName() != null) {
                sender = person.getName().toString().trim();
            }
            if (sender.isEmpty()) {
                sender = string(msg.getCharSequence("sender")).trim();
            }
            senders.add(sender);
            texts.add(body);
        }
        return formatMessagingTurns(resolvedTitle, senders, texts);
    }

    private static String extractInboxText(Bundle extras) {
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines == null || lines.length == 0) return "";
        ArrayList<String> collected = new ArrayList<>(Math.min(lines.length, MAX_INBOX_LINES));
        for (CharSequence line : lines) {
            if (line != null) collected.add(line.toString());
        }
        return formatInboxLines(collected);
    }

    /** Formats up to the latest {@link #MAX_MESSAGING_TURNS} MessagingStyle turns cleanly for summarization. */
    static String formatMessagingTurns(String resolvedTitle, List<String> senders, List<String> texts) {
        if (texts == null || texts.isEmpty()) return "";
        String normTitle = resolvedTitle == null ? "" : resolvedTitle.trim();
        int count = texts.size();
        int start = Math.max(0, count - MAX_MESSAGING_TURNS);
        ArrayList<String> parts = new ArrayList<>(count - start);
        String previousPart = null;
        for (int i = start; i < count; i++) {
            String body = texts.get(i) == null ? "" : texts.get(i).replaceAll("\\s+", " ").trim();
            if (body.isEmpty()) continue;
            String sender = (senders != null && i < senders.size() && senders.get(i) != null)
                    ? senders.get(i).trim() : "";
            boolean includeSender = !sender.isEmpty()
                    && !sender.equalsIgnoreCase(normTitle)
                    && !startsWithSenderPrefix(body, sender);
            String part = includeSender ? (sender + "：" + body) : body;
            if (!part.equals(previousPart)) {
                parts.add(part);
                previousPart = part;
            }
        }
        return String.join("；", parts);
    }

    /** Formats up to {@link #MAX_INBOX_LINES} InboxStyle lines cleanly for summarization. */
    static String formatInboxLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        ArrayList<String> parts = new ArrayList<>(MAX_INBOX_LINES);
        for (String raw : lines) {
            if (raw == null) continue;
            String cleaned = raw.replaceAll("\\s+", " ").trim();
            if (!cleaned.isEmpty() && !parts.contains(cleaned)) {
                parts.add(cleaned);
                if (parts.size() >= MAX_INBOX_LINES) break;
            }
        }
        return String.join("；", parts);
    }

    private static boolean startsWithSenderPrefix(String body, String sender) {
        if (body.length() <= sender.length() || !body.startsWith(sender)) return false;
        char next = body.charAt(sender.length());
        return next == ':' || next == '：';
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

    static boolean isCjkPrimary(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static String string(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
