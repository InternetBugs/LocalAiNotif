package com.localai.notif;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import static com.localai.notif.SystemUiPrioritySection.requireField;
import static com.localai.notif.SystemUiPrioritySection.requireMethod;

final class SystemUiPromotions {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String NOTIFICATION = SYSTEM_UI + ".statusbar.notification.";
    private static final String TAG = "LocalAiNotif.promotions";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<IconKey, StatusBarNotification> icons = new LinkedHashMap<>();
    private final Method notifyAsUser;
    private final Method cancelAsUser;
    private final Method getLazy;
    private final Method userHandle;
    private final Method glassMaterial;
    private final Method backToShade;
    private final Method rebuild;
    private final Method profiles;
    private final Method profileHandle;
    private final Field dismissal;
    private final Object activeState;
    private final Field notificationManager;
    private final Field material;
    private final Field iconSize;
    private final Field iconMargin;
    private final Field showingFold;
    private Object controller;
    private Object manager;
    private Object tracker;
    private Object builder;
    private Context context;
    private Handler background;
    private KeyguardManager keyguard;
    private boolean locked;
    private boolean nativeFold;
    private boolean hasActivePromotions;
    private boolean detail;
    private boolean openingEntrance;
    private boolean entranceReached;
    private boolean entranceVisible;
    private boolean entrancePosted;
    private boolean relevant;
    private UserHandle user;
    private Appearance requested;
    private record IconKey(UserHandle user, String pkg) {}
    private record Appearance(UserHandle user, List<IconKey> icons, int configuration, boolean glass) {}

    static void install(ModuleMain module, ClassLoader loader) {
        try {
            new SystemUiPromotions(module, loader);
        } catch (ReflectiveOperationException | RuntimeException error) {
            module.log(Log.WARN, TAG, "Could not attach promotion notifications", error);
        }
    }

    private SystemUiPromotions(ModuleMain module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> collection = loader.loadClass(NOTIFICATION + "collection.ShadeListBuilder");
        Class<?> entry = loader.loadClass(NOTIFICATION + "collection.NotificationEntry");
        Class<?> fold = loader.loadClass(NOTIFICATION + "history.FoldNotifControllerImpl");
        Class<?> navigation = loader.loadClass(NOTIFICATION + "history.FoldNotifManagerImpl");
        Class<?> coordinator = loader.loadClass(NOTIFICATION + "collection.coordinator.FoldCoordinator");
        Class<?> filter = loader.loadClass(coordinator.getName() + "$foldFilter$1");
        Class<?> row = loader.loadClass(NOTIFICATION + "row.ExpandableNotificationRow");
        Class<?> header = loader.loadClass(SYSTEM_UI + ".controlcenter.shade.FoldNotificationHeaderController");
        Field headerTitle = requireField(header, "title");
        Field sbn = requireField(entry, "mSbn");
        dismissal = requireField(entry, "mDismissState");
        activeState = requireField(dismissal.getType(), "NOT_DISMISSED").get(null);
        Field preGroup = requireField(collection, "mNotifPreGroupFilters");
        Field finalizeFilters = requireField(collection, "mNotifFinalizeFilters");
        Field foldContext = requireField(fold, "context");
        Field foldManager = requireField(fold, "foldNotifManagerImpl");
        Field backgroundHandler = requireField(fold, "bgHandler");
        Field userTracker = requireField(fold, "userTracker");
        notificationManager = requireField(fold, "mNm$delegate");
        iconSize = requireField(fold, "iconSize");
        iconMargin = requireField(fold, "iconMargin");
        material = requireField(fold, "materialStateInteractor");
        glassMaterial = requireMethod(material.getType(), "isGlassMaterialType");
        Class<?> userTrackerType = loader.loadClass(SYSTEM_UI + ".settings.UserTrackerImpl");
        userHandle = requireMethod(userTrackerType, "getUserHandle");
        profiles = requireMethod(userTrackerType, "getUserProfiles");
        profileHandle = requireMethod(loader.loadClass("android.content.pm.UserInfo"), "getUserHandle");
        rebuild = requireMethod(collection, "rebuildListIfBefore", int.class);
        showingFold = requireField(navigation, "isShowingFold");
        backToShade = requireMethod(navigation, "back2Shade", boolean.class);
        getLazy = requireMethod(loader.loadClass("kotlin.Lazy"), "getValue");
        notifyAsUser = requireMethod(NotificationManager.class, "notifyAsUser",
                String.class, int.class, Notification.class, UserHandle.class);
        cancelAsUser = requireMethod(NotificationManager.class, "cancelAsUser", String.class, int.class, UserHandle.class);
        Method getEntry = requireMethod(row, "getEntry");
        Method check = requireMethod(filter, "shouldFilterOut", entry, long.class);
        Method filterList = requireMethod(collection, "filterNotifs", Collection.class, List.class, List.class);
        Method click = requireMethod(loader.loadClass(NOTIFICATION + "NotificationClicker"), "onClick", View.class);
        Method navigate = requireMethod(navigation, "setShowingFold", boolean.class);
        Method appearance = requireMethod(header, "updateAppearance", boolean.class, boolean.class);
        Class<?> keyguardFilter = loader.loadClass(NOTIFICATION + "collection.coordinator.KeyguardCoordinator$notifFilter$1");
        Method checkLockscreen = requireMethod(keyguardFilter, "shouldFilterOut", entry, long.class);
        Constructor<?> constructor = filter.getDeclaredConstructor(coordinator);
        constructor.setAccessible(true);
        Object promotionFilter = constructor.newInstance(new Object[]{null});

        for (Constructor<?> init : fold.getDeclaredConstructors()) {
            module.hook(init).intercept(chain -> {
                Object result = chain.proceed();
                controller = chain.getThisObject();
                context = (Context) foldContext.get(controller);
                manager = foldManager.get(controller);
                tracker = userTracker.get(controller);
                background = (Handler) backgroundHandler.get(controller);
                keyguard = context.getSystemService(KeyguardManager.class);
                return result;
            });
        }
        module.hook(click).intercept(chain -> {
            Object view = chain.getArg(0);
            Object tapped = row.isInstance(view) ? getEntry.invoke(view) : null;
            openingEntrance = tapped != null && isEntrance((StatusBarNotification) sbn.get(tapped));
            try {
                return chain.proceed();
            } finally {
                openingEntrance = false;
            }
        });
        module.hook(navigate).intercept(chain -> {
            boolean showing = (boolean) chain.getArg(0);
            if (manager == chain.getThisObject() && showing != showingFold.getBoolean(manager)) {
                detail = showing && openingEntrance;
                requestRebuild();
            }
            return chain.proceed();
        });
        module.hook(appearance).intercept(chain -> {
            TextView title = (TextView) headerTitle.get(chain.getThisObject());
            if (detail) {
                title.setText(PromotionNotification.TITLE);
            } else if (PromotionNotification.TITLE.contentEquals(title.getText())) {
                title.setText(id("string", "miui_unimportant_notifications"));
            }
            return chain.proceed();
        });
        module.hook(check).intercept(chain -> {
            StatusBarNotification item = (StatusBarNotification) sbn.get(chain.getArg(0));
            if (chain.getThisObject() == promotionFilter) return filterItem(item);
            if (relevant && PromotionNotification.isMarked(item)) return nativeFold && !detail;
            return chain.proceed();
        });
        module.hook(checkLockscreen).setPriority(XposedInterface.PRIORITY_LOWEST).intercept(chain -> {
            if (relevant && locked) {
                StatusBarNotification item = (StatusBarNotification) sbn.get(chain.getArg(0));
                if (PromotionNotification.isMarked(item) || isEntrance(item)) return true;
            }
            return chain.proceed();
        });
        module.hook(filterList).setPriority(XposedInterface.PRIORITY_LOWEST).intercept(chain -> {
            if (controller == null) return chain.proceed();
            List<?> filters = (List<?>) chain.getArg(2);
            if (filters == preGroup.get(chain.getThisObject())) {
                try {
                    builder = chain.getThisObject();
                    beginPass((Collection<?>) chain.getArg(0), sbn);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    relevant = false;
                    Log.w(TAG, "Could not inspect shade notifications", error);
                }
            }
            if (!relevant || filters != finalizeFilters.get(chain.getThisObject())) return chain.proceed();
            List<Object> extended = new ArrayList<>(filters);
            extended.add(promotionFilter);
            Object result = chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1), extended});
            try {
                finishPass();
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "Could not update promotion entrance", error);
            }
            return result;
        });
        installSettingsButton(module, loader, header);
    }

    private void installSettingsButton(ModuleMain module, ClassLoader loader, Class<?> header)
            throws ReflectiveOperationException {
        Class<?> listener = loader.loadClass(header.getName() + "$start$1");
        Field owner = requireField(listener, "this$0");
        Field button = requireField(header, "settings");
        Field starter = requireField(header, "activityStarter");
        Method start = requireMethod(starter.getType(), "startActivity", Intent.class, boolean.class);
        module.hook(requireMethod(listener, "onClick", View.class)).intercept(chain -> {
            if (!detail) return chain.proceed();
            Object current = owner.get(chain.getThisObject());
            if (button.get(current) != chain.getArg(0)) return chain.proceed();
            Intent intent = new Intent(Config.ACTION_PROMOTION_SETTINGS)
                    .setClassName("com.localai.notif", "com.localai.notif.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                start.invoke(starter.get(current), intent, true);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "Could not open promotion settings", error);
            }
            return null;
        });
    }

    private void requestRebuild() {
        main.post(() -> {
            try {
                if (builder != null) rebuild.invoke(builder, 3);
            } catch (ReflectiveOperationException error) {
                Log.w(TAG, "Could not refresh promotion notifications", error);
            }
        });
    }

    private void beginPass(Collection<?> entries, Field sbn) throws ReflectiveOperationException {
        UserHandle current = (UserHandle) userHandle.invoke(tracker);
        if (!current.equals(user)) {
            if (detail) backToShade.invoke(manager, true);
            entranceVisible = false;
            entrancePosted = false;
            requested = null;
            user = current;
        }
        locked = keyguard == null || keyguard.isKeyguardLocked();
        nativeFold = showingFold.getBoolean(manager);
        hasActivePromotions = false;
        icons.clear();
        entranceReached = false;
        boolean previouslyPosted = entrancePosted;
        entrancePosted = false;
        for (Object entry : entries) {
            StatusBarNotification item = (StatusBarNotification) sbn.get(entry);
            if (user.equals(item.getUser()) && isEntrance(item)) entrancePosted = true;
            if (!hasActivePromotions && PromotionNotification.isMarked(item) && dismissal.get(entry) == activeState) {
                hasActivePromotions = user.equals(item.getUser()) || item.getUserId() == -1;
                if (!hasActivePromotions) {
                    for (Object profile : (List<?>) profiles.invoke(tracker)) {
                        if (item.getUser().equals(profileHandle.invoke(profile))) {
                            hasActivePromotions = true;
                            break;
                        }
                    }
                }
            }
        }
        if (previouslyPosted && !entrancePosted) requested = null;
        relevant = detail || entrancePosted || hasActivePromotions || requested != null;
    }

    private boolean filterItem(StatusBarNotification item) {
        if (isEntrance(item)) {
            if (!user.equals(item.getUser())) return true;
            entranceReached = true;
            return locked || nativeFold || !hasActivePromotions;
        }
        if (!PromotionNotification.isMarked(item)) return detail && !locked;
        IconKey key = new IconKey(item.getUser(), item.getPackageName());
        if (hasActivePromotions && icons.size() < 4) icons.putIfAbsent(key, item);
        return locked || (!detail && (nativeFold || (entranceVisible && entrancePosted)));
    }

    private void finishPass() throws ReflectiveOperationException {
        if (detail && (locked || !hasActivePromotions)) main.post(this::closeDetail);
        if (!locked && !nativeFold && entranceVisible != entranceReached) {
            entranceVisible = entranceReached;
            requestRebuild();
        }
        if ((locked || (nativeFold && !detail)) && hasActivePromotions) return;
        boolean glass = !icons.isEmpty() && (boolean) glassMaterial.invoke(material.get(controller));
        Configuration config = context.getResources().getConfiguration();
        Appearance target = new Appearance(user, List.copyOf(icons.keySet()), config.hashCode(), glass);
        if (target.equals(requested)) return;
        if (icons.isEmpty() && !entrancePosted && requested == null) return;
        requested = target;
        List<StatusBarNotification> examples = List.copyOf(icons.values());
        background.post(() -> publish(target, examples));
    }

    private void closeDetail() {
        if (!detail || (!locked && hasActivePromotions)) return;
        try {
            backToShade.invoke(manager, true);
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "Could not return from promotions", error);
        }
    }

    private void publish(Appearance target, List<StatusBarNotification> examples) {
        try {
            Object notifications = getLazy.invoke(notificationManager.get(controller));
            if (examples.isEmpty()) {
                cancelAsUser.invoke(notifications, TAG, TAG.hashCode(), target.user());
                main.post(() -> {
                    if (requested == target && !entrancePosted) requested = null;
                });
            } else {
                Notification entrance = createEntrance(examples, target.glass());
                notifyAsUser.invoke(notifications, TAG, TAG.hashCode(), entrance, target.user());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            main.post(() -> {
                if (requested == target) requested = null;
            });
            Log.w(TAG, "Could not publish promotion entrance", error);
        }
    }

    private Notification createEntrance(List<StatusBarNotification> examples, boolean glass)
            throws ReflectiveOperationException {
        RemoteViews content = new RemoteViews(SYSTEM_UI, id("layout", "unimportant_notification"));
        int title = id("id", "aggregate_title");
        content.setTextViewText(title, PromotionNotification.TITLE);
        content.setTextColor(title, context.getColor(id("color", glass
                ? "fold_notif_entrance_text_color_glass" : "fold_notif_entrance_text_color")));
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        String arrow = glass ? "white_glass" : dark ? "white" : "black";
        int more = id("id", "aggregate_title_icon_more");
        content.setImageViewResource(more, id("drawable", "custom_notification_more_icon_" + arrow));
        content.setViewVisibility(more, View.VISIBLE);
        Bitmap strip = appIcons(examples);
        int iconView = id("id", "aggregate_title_icons");
        content.setImageViewBitmap(iconView, strip);
        content.setViewVisibility(iconView, strip == null ? View.INVISIBLE : View.VISIBLE);
        Bundle extras = new Bundle();
        extras.putBoolean("miui.showAtTail", true);
        extras.putBoolean("miui.isPersistent", true);
        extras.putBoolean("miui.customHeight", true);
        extras.putBoolean("miui.showDivider", true);
        extras.putBoolean("miui.enableKeyguard", false);
        extras.putBoolean("miui_unimportant", true);
        PendingIntent click = PendingIntent.getBroadcast(context, 0, new Intent(TAG).setPackage(SYSTEM_UI),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, "id_aggregate").setSmallIcon(id("drawable", "icon"))
                .setContentTitle(PromotionNotification.TITLE).setCustomContentView(content).setContentIntent(click)
                .addExtras(extras).setVisibility(Notification.VISIBILITY_SECRET).setOnlyAlertOnce(true)
                .setLocalOnly(true).setOngoing(true).build();
    }

    private Bitmap appIcons(List<StatusBarNotification> examples) throws IllegalAccessException {
        int size = iconSize.getInt(controller);
        int gap = iconMargin.getInt(controller);
        List<Drawable> drawables = new ArrayList<>();
        for (StatusBarNotification item : examples) {
            try {
                Drawable icon = context.getPackageManager().getApplicationIcon(item.getPackageName());
                drawables.add(context.getPackageManager().getUserBadgedIcon(icon, item.getUser()));
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            }
        }
        if (drawables.isEmpty() || size <= 0) return null;
        Bitmap strip = Bitmap.createBitmap(drawables.size() * (size + gap) - gap, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(strip);
        for (Drawable icon : drawables) {
            icon.setBounds(0, 0, size, size);
            icon.draw(canvas);
            canvas.translate(size + gap, 0);
        }
        return strip;
    }

    private int id(String type, String name) {
        return context.getResources().getIdentifier(name, type, SYSTEM_UI);
    }

    private static boolean isEntrance(StatusBarNotification item) {
        return SYSTEM_UI.equals(item.getPackageName()) && TAG.equals(item.getTag()) && item.getId() == TAG.hashCode();
    }
}
