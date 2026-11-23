package com.localai.notif;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

final class SystemUiPrioritySection {
    private static final String TAG = "LocalAiNotif";
    private static final String LABEL = "LocalAiNotif priority";
    private static final String NOTIFICATION = "com.android.systemui.statusbar.notification.";
    private static final String COLLECTION = NOTIFICATION + "collection.";
    private static final String COORDINATOR = COLLECTION + "coordinator.";
    private static final String RENDER = COLLECTION + "render.";

    private final Set<Object> sectioners = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> promoters = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Object, PriorityPresentation> headers = new WeakHashMap<>();
    private final Map<ViewGroup, PrioritySectionFrame> activeFrames = new WeakHashMap<>();
    private final Set<View> touchedStacks = Collections.newSetFromMap(new WeakHashMap<>());
    private final NotificationPresentation presentation = new NotificationPresentation();
    private Object deferredStabilityManager;
    private final Class<?> sectionerType;
    private final Class<?> entryType;
    private final Class<?> groupType;
    private final Class<?> nodeControllerType;
    private final Constructor<?> newSectioner;
    private final Constructor<?> newNode;
    private final Field sectionerOwner;
    private final Field sectionerName;
    private final Field sectionerBucket;
    private final Field sbn;
    private final Field groupChildren;
    private final Field groupSummary;
    private final Field bundleChildren;
    private final Field attachState;
    private final Field attachedSection;
    private final Field activeSectioner;
    private final Field nodeChildren;
    private final Field nodeController;
    private final Field headerContents;
    private final Method buildNotifNode;
    private final Method getView;
    private final Method setHeaderVisible;
    private final Method setContentVisible;
    private final Method notifyHeaderHeightChanged;
    private final Method getClipHeight;
    private final Method getClipTopAmount;
    private final Method getTopOverlap;
    private final Method getSuperAlpha;
    private final Method getSuperTransitionAlpha;
    private final Method getIsExpanded;

    static void install(ModuleMain module, ClassLoader loader) {
        try {
            new SystemUiPrioritySection(module, loader);
            module.log(Log.INFO, TAG, "Hooked priority section for lockscreen and notification shade");
        } catch (Exception e) {
            module.log(Log.ERROR, TAG, "Failed to hook priority section", e);
        }
    }

    private SystemUiPrioritySection(ModuleMain module, ClassLoader loader) throws Exception {
        Class<?> builder = loader.loadClass(COLLECTION + "ShadeListBuilder");
        Class<?> pipelineEntry = loader.loadClass(COLLECTION + "PipelineEntry");
        Class<?> section = loader.loadClass(COLLECTION + "listbuilder.NotifSection");
        Class<?> ranking = loader.loadClass(COORDINATOR + "RankingCoordinator");
        Class<?> sectioner = loader.loadClass(COLLECTION + "listbuilder.pluggable.NotifSectioner");
        Class<?> specBuilder = loader.loadClass(RENDER + "NodeSpecBuilder");
        Class<?> spec = loader.loadClass(RENDER + "NodeSpecImpl");
        Class<?> header = loader.loadClass(NOTIFICATION + "stack.SectionHeaderView");
        Class<?> stack = loader.loadClass(NOTIFICATION + "stack.NotificationStackScrollLayout");
        sectionerType = loader.loadClass(ranking.getName() + "$1");
        entryType = loader.loadClass(COLLECTION + "NotificationEntry");
        groupType = loader.loadClass(COLLECTION + "GroupEntry");
        nodeControllerType = loader.loadClass(RENDER + "NodeController");
        newSectioner = sectionerType.getDeclaredConstructor(ranking, int.class);
        newSectioner.setAccessible(true);
        newNode = spec.getDeclaredConstructor(spec, nodeControllerType);
        newNode.setAccessible(true);
        sectionerOwner = requireField(sectionerType, "this$0");
        sectionerName = requireField(sectioner, "mName");
        sectionerBucket = requireField(sectioner, "mBucket");
        sbn = requireField(entryType, "mSbn");
        groupChildren = requireField(groupType, "mUnmodifiableChildren");
        groupSummary = requireField(groupType, "mSummary");
        bundleChildren = requireField(loader.loadClass(COLLECTION + "BundleEntry"), "children");
        attachState = requireField(pipelineEntry, "attachState");
        attachedSection = requireField(attachState.getType(), "section");
        activeSectioner = requireField(section, "sectioner");
        nodeChildren = requireField(spec, "children");
        nodeController = requireField(spec, "controller");
        headerContents = requireField(header, "mContents");
        getView = requireMethod(nodeControllerType, "getView");
        setHeaderVisible = requireMethod(header, "setVisible", boolean.class, boolean.class);
        setContentVisible = requireMethod(header, "setContentVisible",
                boolean.class, boolean.class, Consumer.class);
        notifyHeaderHeightChanged = requireMethod(header, "notifyHeightChanged",
                String.class, boolean.class);
        getClipHeight = requireMethod(header, "getClipHeight");
        getClipTopAmount = requireMethod(header, "getClipTopAmount");
        getTopOverlap = requireMethod(header, "getTopOverlap");
        getSuperAlpha = requireMethod(header, "getSuperAlpha");
        getSuperTransitionAlpha = requireMethod(header, "getSuperTransitionAlpha");
        getIsExpanded = requireMethod(stack, "getIsExpanded");
        buildNotifNode = requireMethod(specBuilder, "buildNotifNode", spec, pipelineEntry);
        Class<?> stackController = loader.loadClass(stack.getName() + "Controller");
        Field listContainer = requireField(loader.loadClass(RENDER + "RootNodeController"), "listContainer");
        Field containerOwner = requireField(loader.loadClass(stackController.getName() + "$NotificationListContainerImpl"), "this$0");
        Field stackView = requireField(stackController, "mView");

        Method buildNodeSpec = requireMethod(specBuilder, "buildNodeSpec", nodeControllerType, List.class);
        Method setSectioners = requireMethod(builder, "setSectioners", List.class);
        Method isInSection = requireMethod(sectionerType, "isInSection", pipelineEntry);
        Method getHeaderNodeController = requireMethod(sectionerType, "getHeaderNodeController");
        Method onEntriesUpdated = requireMethod(sectionerType, "onEntriesUpdated", List.class);
        Method dispatchDraw = requireMethod(stack, "dispatchDraw", Canvas.class);
        Method drawChild = requireMethod(stack, "drawChild", Canvas.class, View.class, long.class);

        hookPromotion(module, loader);
        module.hook(drawChild).intercept(chain -> {
            PrioritySectionFrame frame = activeFrames.get(chain.getThisObject());
            if (frame == null || !frame.isHeading(chain.getArg(1))) return chain.proceed();
            if (!frame.isVisible()) return false;
            Canvas canvas = (Canvas) chain.getArg(0);
            int saved = canvas.save();
            try {
                canvas.translate(frame.headingOffsetX(), frame.headingOffset());
                return chain.proceed();
            } finally {
                canvas.restoreToCount(saved);
            }
        });
        module.hook(dispatchDraw).intercept(chain -> {
            PrioritySectionFrame frame = activeFrames.get(chain.getThisObject());
            if (frame == null) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            frame.drawBackground(canvas);
            Object result = chain.proceed();
            frame.drawOutline(canvas);
            return result;
        });
        module.hook(buildNodeSpec).intercept(chain -> {
            Object root = chain.proceed();
            try {
                List<?> entries = (List<?>) chain.getArg(1);
                Map<String, Long> versions = new HashMap<>();
                for (Object entry : entries) collectPresented(entry, versions);
                View stackRoot = (View) stackView.get(containerOwner.get(listContainer.get(nodeController.get(root))));
                presentation.afterRender(stackRoot, versions);
                addHeader(root, chain.getThisObject(), entries);
            } catch (Exception e) {
                module.log(Log.WARN, TAG, "Failed to render priority section heading", e);
            }
            return root;
        });
        module.hook(isInSection).intercept(chain -> {
            if (!sectioners.contains(chain.getThisObject())) return chain.proceed();
            try {
                return isPriorityRow(chain.getArg(0));
            } catch (Exception e) {
                module.log(Log.WARN, TAG, "Failed to read priority section membership", e);
                return false;
            }
        });
        module.hook(getHeaderNodeController).intercept(chain ->
                sectioners.contains(chain.getThisObject()) ? null : chain.proceed());
        module.hook(onEntriesUpdated).intercept(chain ->
                sectioners.contains(chain.getThisObject()) ? null : chain.proceed());
        module.hook(setSectioners).intercept(chain -> {
            List<?> original = (List<?>) chain.getArg(0);
            List<?> updated = original;
            try {
                updated = addSection(original);
            } catch (Exception e) {
                module.log(Log.WARN, TAG, "Failed to register priority section", e);
            }
            return updated == original ? chain.proceed() : chain.proceed(new Object[]{updated});
        });
        try {
            hookGestureStability(module, loader, stack, pipelineEntry);
        } catch (ReflectiveOperationException e) {
            module.log(Log.WARN, TAG, "Failed to hook priority gesture handling", e);
        }
    }

    private void collectPresented(Object entry, Map<String, Long> versions) throws IllegalAccessException {
        if (entryType.isInstance(entry)) {
            NotificationPresentation.add(versions, (StatusBarNotification) sbn.get(entry));
        } else if (groupType.isInstance(entry)) {
            Object summary = groupSummary.get(entry);
            if (summary != null) collectPresented(summary, versions);
            for (Object child : (List<?>) groupChildren.get(entry)) collectPresented(child, versions);
        } else if (bundleChildren.getDeclaringClass().isInstance(entry)) {
            for (Object child : (List<?>) bundleChildren.get(entry)) collectPresented(child, versions);
        }
    }

    private void hookGestureStability(ModuleMain module, ClassLoader loader, Class<?> stack,
            Class<?> pipelineEntry) throws ReflectiveOperationException {
        Class<?> stability = loader.loadClass(COORDINATOR + "VisualStabilityCoordinator$2");
        Field previousState = requireField(pipelineEntry, "previousAttachState");
        Method invalidate = requireMethod(stability, "invalidateList", String.class);
        Method allChanges = requireMethod(stability, "isEveryChangeAllowed");
        Method dispatch = requireMethod(stack, "dispatchTouchEvent", MotionEvent.class);
        Method sectionChange = requireMethod(stability, "isSectionChangeAllowed", entryType);
        Method childMove = requireMethod(stability, "isParentChangeAllowed", entryType);
        Method groupMove = requireMethod(stability, "isParentChangeAllowed", groupType);
        Method prune = requireMethod(stability, "isGroupPruneAllowed", groupType);
        View.OnAttachStateChangeListener touchLifetime = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                endTouch(view, this, invalidate);
            }
        };
        module.hook(dispatch).intercept(chain -> {
            View view = (View) chain.getThisObject();
            int action = ((MotionEvent) chain.getArg(0)).getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && touchedStacks.add(view)) {
                view.addOnAttachStateChangeListener(touchLifetime);
            }
            boolean handled = false;
            try {
                handled = (boolean) chain.proceed();
                return handled;
            } finally {
                if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP
                        || (action == MotionEvent.ACTION_DOWN && !handled)) {
                    endTouch(view, touchLifetime, invalidate);
                }
            }
        });
        module.hook(allChanges).intercept(chain -> touchedStacks.isEmpty() && (boolean) chain.proceed());
        for (Method change : List.of(sectionChange, childMove, groupMove, prune)) {
            module.hook(change).intercept(chain -> {
                Object entry = chain.getArg(0);
                Object oldSection = attachedSection.get(previousState.get(entry));
                boolean wasPriority = oldSection != null && sectioners.contains(activeSectioner.get(oldSection));
                Object representative = groupType.isInstance(entry) ? groupSummary.get(entry) : entry;
                StatusBarNotification item = representative == null ? null : (StatusBarNotification) sbn.get(representative);
                boolean priority = PriorityNotification.isMarked(item)
                        || (item != null && PriorityNotification.isGroupSummary(item));
                if (!wasPriority && !priority) return chain.proceed();
                if (touchedStacks.isEmpty()) return true;
                deferredStabilityManager = chain.getThisObject();
                return false;
            });
        }
    }

    private void endTouch(View view, View.OnAttachStateChangeListener listener, Method invalidate) {
        if (!touchedStacks.remove(view)) return;
        view.removeOnAttachStateChangeListener(listener);
        if (!touchedStacks.isEmpty() || deferredStabilityManager == null) return;
        Object manager = deferredStabilityManager;
        deferredStabilityManager = null;
        try {
            invalidate.invoke(manager, LABEL);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Failed to refresh priority order after touch", e);
        }
    }

    private void hookPromotion(ModuleMain module, ClassLoader loader) throws Exception {
        Class<?> coordinator = loader.loadClass(COORDINATOR + "MiuiNotifCoordinator");
        Class<?> promoterType = loader.loadClass(coordinator.getName() + "$notifPromoter$1");
        Class<?> pipeline = loader.loadClass(COLLECTION + "NotifPipeline");
        Class<?> listEntry = loader.loadClass(COLLECTION + "ListEntry");
        Class<?> bundler = loader.loadClass(COORDINATOR + "BundleCoordinator$bundler$1");
        Constructor<?> newPromoter = promoterType.getDeclaredConstructor(coordinator);
        newPromoter.setAccessible(true);
        Field promoterName = requireField(promoterType, "mName");
        Method attach = requireMethod(coordinator, "attach", pipeline);
        Method addPromoter = requireMethod(pipeline, "addPromoter", promoterType.getSuperclass());
        Method shouldPromote = requireMethod(promoterType, "shouldPromoteToTopLevel", entryType);
        Method getBundleId = requireMethod(bundler, "getBundleIdOrNull", listEntry);

        module.hook(shouldPromote).intercept(chain ->
                promoters.contains(chain.getThisObject())
                        ? shouldPromote(chain.getArg(0)) : chain.proceed());
        module.hook(attach).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object promoter = newPromoter.newInstance(chain.getThisObject());
                promoterName.set(promoter, LABEL);
                promoters.add(promoter);
                addPromoter.invoke(chain.getArg(0), promoter);
            } catch (ReflectiveOperationException e) {
                module.log(Log.WARN, TAG, "Failed to register priority notification promoter", e);
            }
            return result;
        });
        module.hook(getBundleId).intercept(chain -> {
            Object bundleId = chain.proceed();
            if (bundleId == null) return null;
            Object entry = chain.getArg(0);
            if (isPriorityEntry(entry)) return null;
            if (groupType.isInstance(entry)) {
                for (Object child : (List<?>) groupChildren.get(entry)) {
                    if (isPriorityEntry(child)) return null;
                }
            }
            return bundleId;
        });
    }

    private List<?> addSection(List<?> original) throws ReflectiveOperationException {
        for (Object candidate : original) {
            if (!sectionerType.isInstance(candidate)) continue;
            Object newPrioritySection = newSectioner.newInstance(sectionerOwner.get(candidate), 0);
            sectionerName.set(newPrioritySection, LABEL);
            sectionerBucket.setInt(newPrioritySection, sectionerBucket.getInt(original.get(0)));
            sectioners.add(newPrioritySection);
            List<Object> updated = new ArrayList<>(original.size() + 1);
            updated.add(newPrioritySection);
            updated.addAll(original);
            return updated;
        }
        return original;
    }

    private boolean isPriorityEntry(Object entry) throws IllegalAccessException {
        return entryType.isInstance(entry)
                && PriorityNotification.isMarked((StatusBarNotification) sbn.get(entry));
    }

    private boolean shouldPromote(Object entry) throws IllegalAccessException {
        StatusBarNotification notification = (StatusBarNotification) sbn.get(entry);
        return PriorityNotification.isMarked(notification)
                && !PriorityNotification.isGrouped(notification);
    }

    private boolean isPriorityRow(Object entry) throws IllegalAccessException {
        if (!groupType.isInstance(entry)) return isPriorityEntry(entry);
        Object summary = groupSummary.get(entry);
        if (summary == null
                || !PriorityNotification.isGroupSummary((StatusBarNotification) sbn.get(summary))) return false;
        List<?> children = (List<?>) groupChildren.get(entry);
        if (children.isEmpty()) return false;
        for (Object child : children) {
            if (!isPriorityEntry(child)) return false;
        }
        return true;
    }

    private void addHeader(Object root, Object builder, List<?> entries) throws Exception {
        Object rootController = nodeController.get(root);
        List<View> rows = new ArrayList<>();
        Object firstController = null;
        View following = null;
        for (Object entry : entries) {
            Object section = attachedSection.get(attachState.get(entry));
            boolean priority = section != null && sectioners.contains(activeSectioner.get(section));
            if (!priority) {
                if (firstController != null) {
                    Object followingNode = buildNotifNode.invoke(builder, root, entry);
                    following = (View) getView.invoke(nodeController.get(followingNode));
                    break;
                }
                continue;
            }
            Object node = buildNotifNode.invoke(builder, root, entry);
            Object controller = nodeController.get(node);
            if (firstController == null) firstController = controller;
            rows.add((View) getView.invoke(controller));
        }
        if (rows.isEmpty()) {
            PriorityPresentation existing = headers.get(rootController);
            if (existing != null) existing.frame().setRows(rows, null);
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) nodeChildren.get(root);
        for (int index = 0; index < children.size(); index++) {
            if (nodeController.get(children.get(index)) == firstController) {
                PriorityPresentation presentation = headerFor(rootController);
                presentation.frame().setRows(rows, following);
                children.add(index, newNode.newInstance(root, presentation.controller()));
                return;
            }
        }
    }

    private PriorityPresentation headerFor(Object rootController) throws Exception {
        PriorityPresentation existing = headers.get(rootController);
        if (existing != null) return existing;
        Context context = ((View) getView.invoke(rootController)).getContext();
        int layout = context.getResources().getIdentifier(
                "status_bar_notification_section_header", "layout", "com.android.systemui");
        ViewGroup header = (ViewGroup) LayoutInflater.from(context).inflate(layout, null, false);
        ViewGroup contents = (ViewGroup) headerContents.get(header);
        contents.removeAllViews();
        contents.addView(createTitle(context), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setClickable(false);
        header.setFocusable(false);
        setHeaderVisible.invoke(header, true, false);
        PrioritySectionFrame frame = new PrioritySectionFrame(header, contents, getClipHeight,
                getClipTopAmount, getTopOverlap, getSuperAlpha, getSuperTransitionAlpha, getIsExpanded);
        header.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (view.getParent() instanceof ViewGroup stack) {
                    frame.attach(stack);
                    activeFrames.put(stack, frame);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                activeFrames.remove(frame.detach(), frame);
            }
        });
        header.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top == oldBottom - oldTop) return;
            try {
                notifyHeaderHeightChanged.invoke(view, LABEL, false);
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "Failed to update priority section heading height", e);
            }
        });

        Object controller = Proxy.newProxyInstance(nodeControllerType.getClassLoader(),
                new Class<?>[]{nodeControllerType}, (proxy, method, args) -> switch (method.getName()) {
                    case "getView" -> header;
                    case "getNodeLabel", "toString" -> LABEL;
                    case "getChildCount" -> 0;
                    case "offerToKeepInParentForAnimation", "removeFromParentIfKeptForAnimation" -> false;
                    case "onViewAdded" -> setContentVisible.invoke(header, true, false, null);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        PriorityPresentation presentation = new PriorityPresentation(controller, frame);
        headers.put(rootController, presentation);
        return presentation;
    }

    private record PriorityPresentation(Object controller, PrioritySectionFrame frame) {}

    private static View createTitle(Context context) {
        FrameLayout frame = new FrameLayout(context);
        frame.setPaddingRelative(dp(context, 13), dp(context, 5), dp(context, 13), dp(context, 5));
        TextView title = new TextView(context);
        String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        title.setText("zh".equals(language) ? "重要通知" : "Important notifications");
        title.setAccessibilityHeading(true);
        title.setTextSize(14);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(0xff432d0a);
        title.setPaddingRelative(dp(context, 11), dp(context, 4), dp(context, 11), dp(context, 4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xfff4c15c);
        background.setCornerRadius(dp(context, 7));
        title.setBackground(background);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START);
        frame.addView(title, params);
        return frame;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Field requireField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = ModuleMain.findField(owner, name);
        if (field == null) throw new NoSuchFieldException(owner.getName() + '.' + name);
        return field;
    }

    private static Method requireMethod(Class<?> owner, String name, Class<?>... params)
            throws NoSuchMethodException {
        Method method = ModuleMain.findMethod(owner, name, params);
        if (method == null) throw new NoSuchMethodException(owner.getName() + '.' + name);
        return method;
    }
}
