package com.localai.notif;

import android.app.Notification;
import android.graphics.Typeface;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import static com.localai.notif.SystemUiPrioritySection.requireField;
import static com.localai.notif.SystemUiPrioritySection.requireMethod;

final class SystemUiNotificationRecap {
    private static final String TAG = "LocalAiNotif";
    private final Method getEntry;
    private final Method getPrivateLayout;
    private final Method shouldShowPublic;
    private final Method isHeadsUpState;
    private final Field showingPublic;
    private final Field userExpanded;
    private final Field sbnField;
    private final Slot contracted;
    private final Slot expanded;

    static void install(ModuleMain module, ClassLoader loader) {
        try {
            new SystemUiNotificationRecap(module, loader);
        } catch (Exception exception) {
            module.log(Log.WARN, TAG, "Failed to install notification recap presentation", exception);
        }
    }

    private SystemUiNotificationRecap(ModuleMain module, ClassLoader loader) throws Exception {
        String prefix = "com.android.systemui.statusbar.notification.";
        Class<?> row = loader.loadClass(prefix + "row.ExpandableNotificationRow");
        Class<?> content = loader.loadClass(prefix + "row.NotificationContentView");
        Class<?> entry = loader.loadClass(prefix + "collection.NotificationEntry");
        getEntry = requireMethod(row, "getEntry");
        getPrivateLayout = requireMethod(row, "getPrivateLayout");
        contracted = new Slot(requireMethod(content, "getContractedWrapper"),
                requireMethod(content, "getContractedChild"));
        expanded = new Slot(requireMethod(content, "getExpandedWrapper"),
                requireMethod(content, "getExpandedChild"));
        shouldShowPublic = requireMethod(row, "shouldShowPublic");
        isHeadsUpState = requireMethod(row, "isHeadsUpState");
        showingPublic = requireField(row, "mShowingPublic");
        userExpanded = requireField(row, "mUserExpanded");
        sbnField = requireField(entry, "mSbn");
        Method updated = requireMethod(row, "onNotificationUpdated");
        Method hideSensitive = requireMethod(row, "setHideSensitive", boolean.class, boolean.class,
                long.class, long.class);
        Method headsUp = requireMethod(row, "setHeadsUp", boolean.class);
        Method expand = requireMethod(row, "setUserExpanded", boolean.class, boolean.class);
        for (Method method : new Method[]{updated, hideSensitive, headsUp, expand}) {
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    update((View) chain.getThisObject());
                } catch (Exception exception) {
                    module.log(Log.WARN, TAG, "Failed to render notification recap", exception);
                }
                return result;
            });
        }
    }

    private void update(View row) throws ReflectiveOperationException {
        Object entry = getEntry.invoke(row);
        StatusBarNotification sbn = entry == null ? null : (StatusBarNotification) sbnField.get(entry);
        Notification notification = sbn == null ? null : sbn.getNotification();
        String recap = notification == null || notification.extras == null
                ? null : notification.extras.getString(NotificationRecap.EXTRA);
        if (recap == null || recap.isBlank() || !PriorityNotification.isEligible(notification)
                || showingPublic.getBoolean(row) || (Boolean) shouldShowPublic.invoke(row)
                || (Boolean) isHeadsUpState.invoke(row) || userExpanded.getBoolean(row)) {
            restore(contracted, row);
            restore(expanded, row);
            return;
        }

        Object layout = getPrivateLayout.invoke(row);
        String display = NotificationRecap.LABEL + "  " + recap;
        updateSlot(contracted, row, layout, notification, display);
        updateSlot(expanded, row, layout, notification, display);
    }

    private static void updateSlot(Slot slot, View row, Object layout, Notification notification,
            String display) throws ReflectiveOperationException {
        AppliedText previous = slot.applied.get(row);
        TextView body = layout == null ? null : findBody(slot, layout, notification, previous);
        if (body == null || (previous != null && previous.view().get() != body)) {
            restore(slot, row);
            previous = null;
        }
        if (body == null || TextUtils.isEmpty(body.getText())) return;
        boolean stillApplied = previous != null && TextUtils.equals(previous.display(), body.getText());
        if (stillApplied && display.equals(previous.display())) return;
        CharSequence original = stillApplied ? previous.original() : body.getText();
        SpannableString styled = new SpannableString(display);
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, NotificationRecap.LABEL.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.setText(styled);
        slot.applied.put(row, new AppliedText(new WeakReference<>(body), original, display));
    }

    private static TextView findBody(Slot slot, Object layout, Notification notification, AppliedText previous)
            throws ReflectiveOperationException {
        Object wrapper = slot.getWrapper.invoke(layout);
        if (wrapper == null) return null;
        Class<?> wrapperClass = wrapper.getClass();
        for (String name : new String[]{"mBigText", "mBigtext", "mText"}) {
            Field field = ModuleMain.findField(wrapperClass, name);
            if (field != null && field.get(wrapper) instanceof TextView text
                    && text.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(text.getText())) return text;
        }
        String name = wrapperClass.getSimpleName();
        if (name.equals("NotificationMessagingTemplateViewWrapper")
                || name.equals("NotificationConversationTemplateViewWrapper")) {
            return findMessage((View) slot.getChild.invoke(layout),
                    NotificationRecap.body(notification), previous);
        }
        return null;
    }

    private static TextView findMessage(View view, String source, AppliedText previous) {
        if (view instanceof TextView text && text.getClass().getName().equals(
                "com.android.internal.widget.MessagingTextMessage")) {
            CharSequence current = text.getText();
            if (previous != null && previous.view().get() == text
                    && TextUtils.equals(previous.display(), current)) current = previous.original();
            if (current != null && source.equals(current.toString().strip())) return text;
        }
        if (view instanceof ViewGroup group) {
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                View child = group.getChildAt(index);
                if (child.getVisibility() != View.VISIBLE) continue;
                TextView match = findMessage(child, source, previous);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void restore(Slot slot, View row) {
        AppliedText previous = slot.applied.remove(row);
        if (previous == null) return;
        TextView view = previous.view().get();
        if (view != null && TextUtils.equals(previous.display(), view.getText())) {
            view.setText(previous.original());
        }
    }

    private static final class Slot {
        final Method getWrapper;
        final Method getChild;
        final WeakHashMap<View, AppliedText> applied = new WeakHashMap<>();

        Slot(Method getWrapper, Method getChild) {
            this.getWrapper = getWrapper;
            this.getChild = getChild;
        }
    }

    private record AppliedText(WeakReference<TextView> view, CharSequence original, String display) {}
}
