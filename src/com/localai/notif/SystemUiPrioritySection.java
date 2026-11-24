package com.localai.notif;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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
    private static final String LABEL = "LocalAiNotif priority";
    private static final String TAG = "LocalAiNotif";
    private static final String REDUCE_LABEL = "LocalAiNotif reduce";
    private static volatile SystemUiPrioritySection sInstance;

    /** VisualStabilityCoordinator invalidate hook points for on-demand list refresh. */
    private Object stabilityManager;
    private Method stabilityInvalidate;
    /** Reduce lockscreen pill header per rendered stack root. */
    private final Map<Object, ReducePill> reducePills = new WeakHashMap<>();
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
        sInstance = this;
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
            Object nodeBuilder = chain.getThisObject();
            List<?> entries = (List<?>) chain.getArg(1);
            List<?> visible = entries;
            try {
                if (SystemUiReduceInterruptions.lockQuietHiddenActive()) {
                    visible = filterReduceQuiet(entries);
                } else {
                    SystemUiReduceInterruptions.setLockQuietCount(0);
                }
            } catch (Exception e) {
                module.log(Log.WARN, TAG, "Failed to apply lockscreen quiet filter", e);
                visible = entries;
                SystemUiReduceInterruptions.setLockQuietCount(0);
            }
            Object root = (visible == entries)
                    ? chain.proceed() : chain.proceed(new Object[]{chain.getArg(0), visible});
            try {
                Map<String, Long> versions = new HashMap<>();
                for (Object visibleEntry : visible) collectPresented(visibleEntry, versions);
                View stackRoot = (View) stackView.get(containerOwner.get(listContainer.get(nodeController.get(root))));
                presentation.afterRender(stackRoot, versions);
                addHeader(root, nodeBuilder, visible);
                addReducePill(root);
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
        try {
            new LockscreenRevealGesture(module, loader);
        } catch (ReflectiveOperationException e) {
            module.log(Log.WARN, TAG, "Failed to hook lockscreen reveal gesture", e);
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
        stabilityInvalidate = invalidate;
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
            MotionEvent event = (MotionEvent) chain.getArg(0);
            int action = event.getActionMasked();
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
        module.hook(allChanges).intercept(chain -> {
            stabilityManager = chain.getThisObject();
            return touchedStacks.isEmpty() && (boolean) chain.proceed();
        });
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

    // ---------------------------------------------------------------------------------
    // Reduce Interruptions lockscreen experience: hide non-important notifications from the
    // keyguard render list until the user swipes up, and show a "减少干扰 + 星芒LOGO" line
    // below the clock. State lives in SystemUiReduceInterruptions; only rendering happens here.
    // ---------------------------------------------------------------------------------

    /** Requests a list rebuild so freshly hidden/revealed lockscreen notifications apply. */
    static void requestListInvalidation() {
        SystemUiPrioritySection self = sInstance;
        Object manager = self == null ? null : self.stabilityManager;
        Method invalidate = self == null ? null : self.stabilityInvalidate;
        if (manager == null || invalidate == null) return;
        try {
            invalidate.invoke(manager, REDUCE_LABEL);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to refresh notification list after reduce state change", t);
        }
    }

    /**
     * Removes lockscreen-hidden entries from the render list and records how many
     * notifications were hidden (group children counted individually).
     */
    private List<?> filterReduceQuiet(List<?> entries) throws IllegalAccessException {
        List<Object> pruned = null;
        int hidden = 0;
        for (int index = 0; index < entries.size(); index++) {
            Object entry = entries.get(index);
            if (isReduceQuietHidden(entry)) {
                hidden += quietNotificationCount(entry);
                if (pruned == null) {
                    pruned = new ArrayList<>(entries.size());
                    pruned.addAll(entries.subList(0, index));
                }
                continue;
            }
            if (pruned != null) pruned.add(entry);
        }
        SystemUiReduceInterruptions.setLockQuietCount(hidden);
        return pruned != null ? pruned : entries;
    }

    /** True when the entry (and, for groups/bundles, every member) is reduce-muted on keyguard. */
    private boolean isReduceQuietHidden(Object entry) throws IllegalAccessException {
        if (groupType.isInstance(entry)) {
            if (!isReduceQuietHidden(groupSummary.get(entry))) return false;
            for (Object child : (List<?>) groupChildren.get(entry)) {
                if (!isReduceQuietHidden(child)) return false;
            }
            return true;
        }
        if (bundleChildren.getDeclaringClass().isInstance(entry)) {
            for (Object child : (List<?>) bundleChildren.get(entry)) {
                if (!isReduceQuietHidden(child)) return false;
            }
            return true;
        }
        if (entryType.isInstance(entry)) {
            StatusBarNotification item = (StatusBarNotification) sbn.get(entry);
            return !SystemUiReduceInterruptions.isLockScreenImportant(item);
        }
        return false; // Unknown entry types are never hidden (fail-open).
    }

    private int quietNotificationCount(Object entry) throws IllegalAccessException {
        if (groupType.isInstance(entry)) {
            int count = 0;
            for (Object child : (List<?>) groupChildren.get(entry)) count += quietNotificationCount(child);
            return count;
        }
        if (bundleChildren.getDeclaringClass().isInstance(entry)) {
            int count = 0;
            for (Object child : (List<?>) bundleChildren.get(entry)) count += quietNotificationCount(child);
            return count;
        }
        return 1;
    }

    /** Injects the "减少干扰" pill header as the topmost row (directly below the clock). */
    private void addReducePill(Object root) throws Exception {
        if (!SystemUiReduceInterruptions.lockPillShowing()) return;
        Object rootController = nodeController.get(root);
        ReducePill pill = reducePills.get(rootController);
        if (pill == null) {
            pill = createReducePill(rootController);
            reducePills.put(rootController, pill);
        }
        pill.view.setClickable(SystemUiReduceInterruptions.lockQuietHiddenActive()
                && SystemUiReduceInterruptions.lockQuietCount() > 0);
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) nodeChildren.get(root);
        children.add(0, newNode.newInstance(root, pill.controller));
    }

    private ReducePill createReducePill(Object rootController) throws Exception {
        Context context = ((View) getView.invoke(rootController)).getContext();
        int layout = context.getResources().getIdentifier(
                "status_bar_notification_section_header", "layout", "com.android.systemui");
        ViewGroup header = (ViewGroup) LayoutInflater.from(context).inflate(layout, null, false);
        ViewGroup contents = (ViewGroup) headerContents.get(header);
        header.setClipChildren(false);
        header.setClipToPadding(false);
        contents.setClipChildren(false);
        contents.setClipToPadding(false);
        contents.removeAllViews();

        ReducePill pill = new ReducePill();
        pill.view = new ReduceLockPillView(context);
        FrameLayout row = new FrameLayout(context);
        pill.view.setOnClickListener(view -> {
            if (SystemUiReduceInterruptions.lockQuietCount() > 0) {
                SystemUiReduceInterruptions.requestLockReveal();
            }
        });
        row.addView(pill.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        contents.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setClickable(false);
        header.setFocusable(false);
        setHeaderVisible.invoke(header, true, false);
        header.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top == oldBottom - oldTop) return;
            try {
                notifyHeaderHeightChanged.invoke(view, REDUCE_LABEL, false);
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "Failed to update reduce pill height", e);
            }
        });

        pill.controller = Proxy.newProxyInstance(nodeControllerType.getClassLoader(),
                new Class<?>[]{nodeControllerType}, (proxy, method, args) -> switch (method.getName()) {
                    case "getView" -> header;
                    case "getNodeLabel", "toString" -> REDUCE_LABEL;
                    case "getChildCount" -> 0;
                    case "offerToKeepInParentForAnimation", "removeFromParentIfKeptForAnimation" -> false;
                    case "onViewAdded" -> setContentVisible.invoke(header, true, false, null);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        return pill;
    }

    /** Mutable holder for one stack's injected reduce pill header. */
    private static final class ReducePill {
        Object controller;
        ReduceLockPillView view;
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
        header.setClipChildren(false);
        header.setClipToPadding(false);
        contents.setClipChildren(false);
        contents.setClipToPadding(false);
        contents.removeAllViews();
        PriorityCapsuleView capsule = new PriorityCapsuleView(context);
        contents.addView(createTitleContainer(context, capsule), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setClickable(false);
        header.setFocusable(false);
        setHeaderVisible.invoke(header, true, false);
        PrioritySectionFrame frame = new PrioritySectionFrame(header, contents, capsule, getClipHeight,
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

    private static View createTitleContainer(Context context, PriorityCapsuleView capsule) {
        FrameLayout frame = new FrameLayout(context);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        frame.setPaddingRelative(0, dp(context, 2), 0, dp(context, 6));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START);
        frame.addView(capsule, params);
        return frame;
    }

    /**
     * Ultra-translucent HyperOS 4 liquid-glass micro-capsule header ("✦ 重要通知") with adaptive
     * dark smoked-glass / light frosted-glass surfaces, cyan-violet 4-point sparkle, crisp 0.6dp
     * specular rim, and synchronized one-shot Nebula Aurora shimmer.
     */
    static final class PriorityCapsuleView extends View {
        private static final int[] DARK_RIM = {
                0x78EDF6FF, 0x565CE1FF, 0x52A17DFF, 0x38FFFFFF, 0x78EDF6FF
        };
        private static final int[] LIGHT_RIM = {
                0x781CA3EC, 0x6A2B75F0, 0x727458F2, 0x8CFFFFFF, 0x781CA3EC
        };
        private static final int[] SHIMMER_COMET = {
                0x005CE1FF, 0x005CE1FF, 0xC85CE1FF, 0xFFFFFFFF, 0xD4A17DFF, 0x00A17DFF, 0x005CE1FF
        };
        private static final float[] RIM_STOPS = {0f, 0.25f, 0.55f, 0.80f, 1f};
        private static final float[] COMET_STOPS = {0f, 0.66f, 0.78f, 0.88f, 0.94f, 0.99f, 1f};

        private final String label;
        private final float density;
        private final float shadowPad;
        private final float pillHeight;
        private final float padStart;
        private final float padEnd;
        private final float sparkleRadius;
        private final float iconTextGap;

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cometPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sparkleGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF pillRect = new RectF();
        private final Path sparklePath = new Path();
        private final Matrix shaderMatrix = new Matrix();

        private LinearGradient surfaceShader;
        private LinearGradient sheenShader;
        private LinearGradient sparkleShader;
        private SweepGradient rimShader;
        private SweepGradient cometShader;

        private boolean darkAppearance = true;
        private float shimmerProgress;
        private float shimmerBoost;
        private float cachedW = -1f;
        private float cachedH = -1f;
        private boolean cachedDark = true;

        PriorityCapsuleView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
            label = "zh".equals(language) ? "重要通知" : "Important notifications";

            shadowPad = 2f * density;
            pillHeight = 25.5f * density;
            padStart = 9.5f * density;
            padEnd = 11f * density;
            sparkleRadius = 5.3f * density;
            iconTextGap = 5.5f * density;

            fillPaint.setStyle(Paint.Style.FILL);
            sheenPaint.setStyle(Paint.Style.FILL);

            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(0.6f * density);

            cometPaint.setStyle(Paint.Style.STROKE);
            cometPaint.setStrokeWidth(0.95f * density);

            sparklePaint.setStyle(Paint.Style.FILL);
            sparkleGlowPaint.setStyle(Paint.Style.FILL);

            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            textPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 12.2f, context.getResources().getDisplayMetrics()));
            textPaint.setLetterSpacing(0.015f);

            setContentDescription(label);
            setAccessibilityHeading(true);
            setClickable(false);
            setFocusable(false);
            applyInkStyle();
        }

        void setDarkAppearance(boolean dark) {
            if (darkAppearance == dark) return;
            darkAppearance = dark;
            applyInkStyle();
            invalidateShaders();
            invalidate();
        }

        void setShimmer(float progress, float boost) {
            if (shimmerProgress == progress && shimmerBoost == boost) return;
            shimmerProgress = progress;
            shimmerBoost = boost;
            invalidate();
        }

        private void applyInkStyle() {
            if (darkAppearance) {
                textPaint.setColor(0xF5FFFFFF);
                textPaint.setShadowLayer(1.6f * density, 0f, 0.55f * density, 0x5A000000);
            } else {
                textPaint.setColor(0xE0131A2B);
                textPaint.clearShadowLayer();
            }
        }

        private void invalidateShaders() {
            surfaceShader = null;
            sheenShader = null;
            sparkleShader = null;
            rimShader = null;
            cometShader = null;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float textWidth = textPaint.measureText(label);
            float contentW = padStart + sparkleRadius * 2f + iconTextGap + textWidth + padEnd;
            int w = Math.round(contentW + shadowPad * 2f);
            int h = Math.round(pillHeight + shadowPad * 2f);
            setMeasuredDimension(
                    resolveSize(w, widthMeasureSpec),
                    resolveSize(h, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= shadowPad * 2f || h <= shadowPad * 2f) return;

            pillRect.set(shadowPad, shadowPad, w - shadowPad, h - shadowPad);
            float r = pillRect.height() * 0.5f;
            float cx = pillRect.centerX();
            float cy = pillRect.centerY();

            ensureShaders(w, h);

            // 1. Subtle ambient lift shadow for bright lockscreen wallpapers
            fillPaint.setShader(null);
            fillPaint.setColor(darkAppearance ? 0x2B050914 : 0x12142342);
            canvas.save();
            canvas.translate(0f, 0.9f * density);
            canvas.drawRoundRect(pillRect, r, r, fillPaint);
            canvas.restore();

            // 2. Translucent liquid glass surface + top inner specular sheen
            fillPaint.setAlpha(255);
            fillPaint.setShader(surfaceShader);
            canvas.drawRoundRect(pillRect, r, r, fillPaint);

            sheenPaint.setAlpha(255);
            sheenPaint.setShader(sheenShader);
            canvas.drawRoundRect(pillRect, r, r, sheenPaint);

            // 3. Crisp 0.6dp ice-white / Nebula specular rim
            float inset = 0.35f * density;
            pillRect.inset(inset, inset);
            float rimR = pillRect.height() * 0.5f;

            shaderMatrix.reset();
            shaderMatrix.setRotate(-30f + shimmerProgress * 360f, cx, cy);
            rimShader.setLocalMatrix(shaderMatrix);
            rimPaint.setShader(rimShader);
            rimPaint.setAlpha(Math.min(255, Math.round((0.88f + 0.12f * shimmerBoost) * 255f)));
            canvas.drawRoundRect(pillRect, rimR, rimR, rimPaint);

            // 4. Synchronized shimmer glint around the pill rim during the 1.4s sweep
            if (shimmerBoost > 0.01f) {
                shaderMatrix.reset();
                shaderMatrix.setRotate(-115f + shimmerProgress * 360f, cx, cy);
                cometShader.setLocalMatrix(shaderMatrix);
                cometPaint.setShader(cometShader);
                cometPaint.setAlpha(Math.min(255, Math.round(shimmerBoost * 225f)));
                canvas.drawRoundRect(pillRect, rimR, rimR, cometPaint);
            }

            // 5. Crisp 4-point Nebula AI sparkle + subtle shimmer bloom
            float sparkleCx = shadowPad + padStart + sparkleRadius;
            if (shimmerBoost > 0.01f) {
                buildSparklePath(sparkleCx, cy, sparkleRadius * (1.28f + 0.12f * shimmerBoost));
                sparkleGlowPaint.setColor(darkAppearance ? 0xFF9BEAFF : 0xFF5C9EFF);
                sparkleGlowPaint.setAlpha(Math.round(shimmerBoost * 96f));
                canvas.drawPath(sparklePath, sparkleGlowPaint);
            }
            buildSparklePath(sparkleCx, cy, sparkleRadius);
            sparklePaint.setShader(sparkleShader);
            sparklePaint.setAlpha(255);
            canvas.drawPath(sparklePath, sparklePaint);
            if (shimmerBoost > 0.01f) {
                sparkleGlowPaint.setColor(0xFFFFFFFF);
                sparkleGlowPaint.setAlpha(Math.round(shimmerBoost * 165f));
                canvas.drawPath(sparklePath, sparkleGlowPaint);
            }

            // 6. Crisp MiSans Medium title text
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
            float textX = sparkleCx + sparkleRadius + iconTextGap;
            canvas.drawText(label, textX, baseline, textPaint);
        }

        private void buildSparklePath(float cx, float cy, float r) {
            float inner = r * 0.25f;
            sparklePath.reset();
            sparklePath.moveTo(cx, cy - r);
            sparklePath.quadTo(cx + inner * 0.42f, cy - inner * 0.42f, cx + r, cy);
            sparklePath.quadTo(cx + inner * 0.42f, cy + inner * 0.42f, cx, cy + r);
            sparklePath.quadTo(cx - inner * 0.42f, cy + inner * 0.42f, cx - r, cy);
            sparklePath.quadTo(cx - inner * 0.42f, cy - inner * 0.42f, cx, cy - r);
            sparklePath.close();
        }

        private void ensureShaders(float w, float h) {
            if (surfaceShader != null && cachedDark == darkAppearance
                    && Math.abs(cachedW - w) < 0.5f && Math.abs(cachedH - h) < 0.5f) {
                return;
            }
            cachedW = w;
            cachedH = h;
            cachedDark = darkAppearance;

            float top = shadowPad;
            float bot = h - shadowPad;
            float cx = w * 0.5f;
            float cy = h * 0.5f;

            surfaceShader = new LinearGradient(
                    0f, top, 0f, bot,
                    darkAppearance ? 0x7A1C253A : 0xD6F8FBFF,
                    darkAppearance ? 0x5E111726 : 0xBEEBF3FE,
                    Shader.TileMode.CLAMP);
            sheenShader = new LinearGradient(
                    0f, top, 0f, top + (bot - top) * 0.52f,
                    darkAppearance ? 0x26FFFFFF : 0x6AFFFFFF,
                    0x00FFFFFF,
                    Shader.TileMode.CLAMP);

            float sx = shadowPad + padStart;
            sparkleShader = new LinearGradient(
                    sx, cy - sparkleRadius,
                    sx + sparkleRadius * 2f, cy + sparkleRadius,
                    darkAppearance ? 0xFF64E6FF : 0xFF1CA3EC,
                    darkAppearance ? 0xFFA685FF : 0xFF7458F2,
                    Shader.TileMode.CLAMP);

            rimShader = new SweepGradient(cx, cy,
                    darkAppearance ? DARK_RIM : LIGHT_RIM, RIM_STOPS);
            cometShader = new SweepGradient(cx, cy, SHIMMER_COMET, COMET_STOPS);
        }
    }

    /**
     * Lockscreen "减少干扰" line below the clock: Apple Intelligence ribbon LOGO + label,
     * Static drawing (no animation, respects reduced-motion by design); translucent white
     * ink with an anti-halo shadow matches keyguard clock contrast on any wallpaper.
     */
    static final class ReduceLockPillView extends View {
        private final Path logoPath = new Path();
        private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final float logoSize;
        private final float iconTextGap;
        private final float padTop;
        private final float padBottom;

        ReduceLockPillView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
            label = "zh".equals(language) ? "减少干扰" : "Reduce Interruptions";

            logoSize = 15.5f * density;
            iconTextGap = 5.5f * density;
            padTop = 5f * density;
            padBottom = 8f * density;

            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            textPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 14f, context.getResources().getDisplayMetrics()));
            textPaint.setLetterSpacing(0.01f);
            textPaint.setColor(0xF2FFFFFF);
            textPaint.setShadowLayer(1.6f * density, 0f, 0.6f * density, 0x50000000);
            logoPaint.setColor(textPaint.getColor());

            setClickable(false);
            setFocusable(false);
            setContentDescription(label);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textH = fm.descent - fm.ascent;
            float width = logoSize + iconTextGap + textPaint.measureText(label);
            float height = getPaddingTop() + padTop + Math.max(logoSize, textH)
                    + padBottom + getPaddingBottom();
            setMeasuredDimension(
                    resolveSize(Math.round(width) + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                    resolveSize(Math.round(height), heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float contentTop = getPaddingTop() + padTop;
            float contentBottom = getHeight() - getPaddingBottom() - padBottom;
            float centerY = (contentTop + contentBottom) * 0.5f;

            float logoLeft = getPaddingLeft();
            float logoTop = centerY - logoSize * 0.5f;
            ReduceInterruptions.drawAppleIntelMono(canvas, logoLeft, logoTop, logoSize,
                    logoPaint, 1.78f, logoPath);

            float textX = logoLeft + logoSize + iconTextGap;
            float baseline = centerY - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(label, textX, baseline, textPaint);
        }
    }


    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static Field requireField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = ModuleMain.findField(owner, name);
        if (field == null) throw new NoSuchFieldException(owner.getName() + '.' + name);
        return field;
    }

    static Method requireMethod(Class<?> owner, String name, Class<?>... params)
            throws NoSuchMethodException {
        Method method = ModuleMain.findMethod(owner, name, params);
        if (method == null) throw new NoSuchMethodException(owner.getName() + '.' + name);
        return method;
    }
}
