package io.github.internetbugs.localainotif;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedInterface.CtorInvoker;

final class SystemUiNotificationIcons {
    private static final String NOTIFICATION = "com.android.systemui.statusbar.notification.";
    private static final int STATUS_BAR_DEFAULT_ARGS = 48;
    private final Class<?> notificationEntry;
    private final Class<?> groupEntry;
    private final Class<?> bundleEntry;
    private final Field sbnField;
    private final Field groupChildren;
    private final Field groupSummary;
    private final Field bundleChildren;
    private final Field individuals;
    private final Field individualKey;
    private final Class<?> keyType;
    private final CtorInvoker<Object> newKey;
    private final Constructor<?> newStore;
    private final Method newFlow;
    private final Method getValue;
    private final Method setValue;
    private Object iconFlow;
    private Object nativeStore;
    private List<?> rendered = List.of();
    private Set<String> allowedVersions;

    SystemUiNotificationIcons(ModuleMain module, ClassLoader loader, Consumer<Context> initialize)
            throws ReflectiveOperationException {
        String collection = NOTIFICATION + "collection.";
        notificationEntry = loader.loadClass(collection + "NotificationEntry");
        groupEntry = loader.loadClass(collection + "GroupEntry");
        bundleEntry = loader.loadClass(collection + "BundleEntry");
        sbnField = notificationEntry.getField("mSbn");
        groupChildren = groupEntry.getField("mUnmodifiableChildren");
        groupSummary = groupEntry.getField("mSummary");
        bundleChildren = bundleEntry.getField("children");

        String repository = NOTIFICATION + "data.repository.";
        Class<?> store = loader.loadClass(repository + "ActiveNotificationsStore");
        keyType = loader.loadClass(store.getName() + "$Key$Individual");
        newKey = module.getInvoker(Object.class.getConstructor());
        individualKey = keyType.getField("key");
        individuals = store.getField("individuals");
        newStore = store.getConstructor(Map.class, Map.class, Map.class, List.class, Map.class);
        Class<?> flowType = loader.loadClass("kotlinx.coroutines.flow.StateFlowImpl");
        getValue = flowType.getMethod("getValue");
        setValue = flowType.getMethod("setValue", Object.class);
        newFlow = loader.loadClass("kotlinx.coroutines.flow.StateFlowKt").getMethod("MutableStateFlow", Object.class);

        Class<?> renderer = loader.loadClass(NOTIFICATION + "domain.interactor.RenderNotificationListInteractor");
        Field context = renderer.getField("context");
        Field repo = renderer.getField("repository");
        Field active = repo.getType().getField("activeNotifications");
        Class<?> icons = loader.loadClass(NOTIFICATION + "icon.domain.interactor.NotificationIconsInteractor");
        Method iconSet = icons.getDeclaredMethod("filteredNotifSet$default", icons,
                boolean.class, boolean.class, int.class);
        Field sources = iconSet.getReturnType().getField("$flows$inlined");
        Method render = renderer.getDeclaredMethod("setRenderedList", List.class);

        module.hook(render).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object owner = chain.getThisObject();
                initialize.accept((Context) context.get(owner));
                Object original = getValue.invoke(active.get(repo.get(owner)));
                synchronized (this) {
                    nativeStore = original;
                    rendered = new ArrayList<>((List<?>) chain.getArg(0));
                    publish();
                }
            } catch (ReflectiveOperationException error) {
                Log.w("LocalAiNotif", "Could not update status bar notification icons", error);
            }
            return result;
        });
        module.hook(iconSet).intercept(chain -> {
            Object result = chain.proceed();
            if ((int) chain.getArg(3) == STATUS_BAR_DEFAULT_ARGS) {
                try {
                    synchronized (this) {
                        Object[] inputs = (Object[]) sources.get(result);
                        if (iconFlow == null) {
                            iconFlow = newFlow.invoke(null, getValue.invoke(inputs[0]));
                            publish();
                        }
                        inputs[0] = iconFlow;
                    }
                } catch (ReflectiveOperationException error) {
                    Log.w("LocalAiNotif", "Could not attach status bar icon filter", error);
                }
            }
            return result;
        });
    }

    synchronized void setAllowedVersions(List<String> versions) {
        allowedVersions = versions == null ? null : new HashSet<>(versions);
        try {
            publish();
        } catch (ReflectiveOperationException error) {
            Log.w("LocalAiNotif", "Could not apply status bar icon filter", error);
        }
    }

    private void publish() throws ReflectiveOperationException {
        if (iconFlow == null || nativeStore == null) return;
        Object next = nativeStore;
        if (allowedVersions != null) {
            Map<?, ?> models = (Map<?, ?>) individuals.get(nativeStore);
            Map<String, Object> visible = new LinkedHashMap<>();
            for (Object entry : rendered) collectAllowed(entry, models, visible);
            List<Object> keys = new ArrayList<>(visible.size());
            for (String key : visible.keySet()) {
                Object item = newKey.newInstanceSpecial(keyType);
                individualKey.set(item, key);
                keys.add(item);
            }
            next = newStore.newInstance(Map.of(), Map.of(), visible, keys, Map.of());
        }
        setValue.invoke(iconFlow, next);
    }

    private void collectAllowed(Object entry, Map<?, ?> models, Map<String, Object> visible)
            throws IllegalAccessException {
        if (notificationEntry.isInstance(entry)) {
            StatusBarNotification sbn = (StatusBarNotification) sbnField.get(entry);
            if (sbn != null && allowedVersions.contains(GroupSummaryData.versionPart(sbn))) {
                Object model = models.get(sbn.getKey());
                if (model != null) visible.put(sbn.getKey(), model);
            }
        } else if (groupEntry.isInstance(entry)) {
            List<?> children = (List<?>) groupChildren.get(entry);
            if (children.isEmpty()) collectAllowed(groupSummary.get(entry), models, visible);
            for (Object child : children) collectAllowed(child, models, visible);
        } else if (bundleEntry.isInstance(entry)) {
            for (Object child : (List<?>) bundleChildren.get(entry)) collectAllowed(child, models, visible);
        }
    }
}
