package com.localai.notif;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RenderNode;
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

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
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
    private int islandQuietCount;
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
    private PriorityShadeRevealAnimator shadeRevealAnimator;
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
        hookMergedCardLayout(module, loader);
        module.hook(drawChild).intercept(chain -> {
            PrioritySectionFrame frame = activeFrames.get(chain.getThisObject());
            if (frame == null || !frame.isFooter(chain.getArg(1))) return chain.proceed();
            if (!frame.isFooterVisible()) return false;
            Canvas canvas = (Canvas) chain.getArg(0);
            int saved = canvas.save();
            try {
                canvas.translate(frame.footerOffsetX(), frame.footerOffsetY());
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
                addHeader(root, nodeBuilder, visible, stackRoot instanceof ViewGroup vg ? vg : null);
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
        try {
            hookIslandQuietFilter(module, loader, builder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            module.log(Log.WARN, TAG, "Could not connect to the keyguard island filter", e);
        }
        try {
            shadeRevealAnimator = new PriorityShadeRevealAnimator(module, loader, stack);
        } catch (ReflectiveOperationException | RuntimeException e) {
            module.log(Log.ERROR, TAG, "Failed to hook priority shade reveal animator", e);
        }
    }

    private void hookIslandQuietFilter(ModuleMain module, ClassLoader loader, Class<?> builder)
            throws ReflectiveOperationException {
        Class<?> keyguard = loader.loadClass(COORDINATOR + "KeyguardCoordinator$notifFilter$1");
        Class<?> notifFilter = loader.loadClass(COLLECTION + "listbuilder.pluggable.NotifFilter");
        Field finalizeFilters = requireField(builder, "mNotifFinalizeFilters");
        Field parent = requireField(attachState.getType(), "parent");
        Method filterList = requireMethod(builder, "filterNotifs", Collection.class, List.class, List.class);
        Method keyguardCheck = requireMethod(keyguard, "shouldFilterOut", entryType, long.class);
        Method filterCheck = requireMethod(notifFilter, "shouldFilterOut", entryType, long.class);

        // ShadeListBuilder runs synchronously on the main thread. Retain its filters only for
        // the finalize call, including an empty pass so an earlier reveal count cannot linger.
        class FinalizePass {
            List<?> filters;
            int keyguardIndex;
        }
        FinalizePass pass = new FinalizePass();
        module.hook(filterList).intercept(chain -> {
            List<?> filters = (List<?>) chain.getArg(2);
            if (filters != finalizeFilters.get(chain.getThisObject())) return chain.proceed();
            islandQuietCount = 0;
            if (!HyperMusicCoverDate.isModuleLoaded() || !SystemUiReduceInterruptions.lockQuietHiddenActive()) {
                return chain.proceed();
            }
            for (int i = 0; i < filters.size(); i++) {
                if (keyguard.isInstance(filters.get(i))) {
                    pass.filters = filters;
                    pass.keyguardIndex = i;
                    break;
                }
            }
            try {
                return chain.proceed();
            } finally {
                pass.filters = null;
            }
        });
        // HyperMusicCover's default-priority hook collects the result of proceed(). A quiet
        // veto here therefore reaches both the island collector and the native keyguard list.
        module.hook(keyguardCheck).setPriority(XposedInterface.PRIORITY_LOWEST).intercept(chain -> {
            boolean hidden = (boolean) chain.proceed();
            List<?> filters = pass.filters;
            if (hidden || filters == null || chain.getThisObject() != filters.get(pass.keyguardIndex)) return hidden;
            Object entry = chain.getArg(0);
            StatusBarNotification item = (StatusBarNotification) sbn.get(entry);
            if (SystemUiReduceInterruptions.isLockScreenImportant(item)) return false;

            // Our veto stops the builder's loop. Run the remaining native predicates in order
            // before counting: e.g. a screen-share secret must never enable the reveal gesture.
            try {
                for (int i = pass.keyguardIndex + 1; i < filters.size(); i++) {
                    if ((boolean) filterCheck.invoke(filters.get(i), entry, chain.getArg(1))) return true;
                }
                Object owner = parent.get(attachState.get(entry));
                if (!groupType.isInstance(owner) || groupSummary.get(owner) != entry) islandQuietCount++;
            } catch (ReflectiveOperationException | RuntimeException e) {
                module.log(Log.WARN, TAG, "Could not determine island reveal eligibility", e);
            }
            return true;
        });
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
        int hidden = islandQuietCount;
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
        GlowStyle.initInSystemUi(context);
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
        pill.view = new ReduceLockPillView(context, header);
        FrameLayout row = new FrameLayout(context);
        row.setClipChildren(false);
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

    private void addHeader(Object root, Object builder, List<?> entries, ViewGroup stackRoot)
            throws Exception {
        Object rootController = nodeController.get(root);
        List<View> rows = new ArrayList<>();
        Object firstController = null;
        Object lastPriorityController = null;
        for (Object entry : entries) {
            Object section = attachedSection.get(attachState.get(entry));
            boolean priority = section != null && sectioners.contains(activeSectioner.get(section));
            if (!priority) {
                continue;
            }
            Object node = buildNotifNode.invoke(builder, root, entry);
            Object controller = nodeController.get(node);
            if (firstController == null) firstController = controller;
            lastPriorityController = controller;
            rows.add((View) getView.invoke(controller));
        }
        PriorityPresentation existing = headers.get(rootController);
        if (rows.isEmpty()) {
            if (existing != null) existing.frame().setRows(rows);
            if (shadeRevealAnimator != null) {
                shadeRevealAnimator.updateMembership(
                        stackRoot, existing != null ? existing.header() : null, rows, Collections.emptyList());
            }
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) nodeChildren.get(root);
        int firstIndex = -1;
        int lastIndex = -1;
        for (int index = 0; index < children.size(); index++) {
            Object ctrl = nodeController.get(children.get(index));
            if (firstIndex < 0 && ctrl == firstController) firstIndex = index;
            if (ctrl == lastPriorityController) lastIndex = index;
        }
        List<View> ordinaryRows = new ArrayList<>();
        if (lastIndex >= 0) {
            for (int index = lastIndex + 1; index < children.size(); index++) {
                Object ctrl = nodeController.get(children.get(index));
                if (ctrl == null) continue;
                Object viewObj = getView.invoke(ctrl);
                if (viewObj instanceof View v) {
                    ordinaryRows.add(v);
                }
            }
        }
        if (firstIndex >= 0 && lastIndex >= 0) {
            PriorityPresentation presentation = headerFor(rootController);
            presentation.frame().setRows(rows);
            // Insert shared Priority Footer immediately AFTER the last priority row
            children.add(lastIndex + 1, newNode.newInstance(root, presentation.controller()));
            if (shadeRevealAnimator != null) {
                shadeRevealAnimator.updateMembership(stackRoot, presentation.header(), rows, ordinaryRows);
            }
            return;
        }
        if (shadeRevealAnimator != null) {
            shadeRevealAnimator.updateMembership(
                    stackRoot, existing != null ? existing.header() : null, rows, ordinaryRows);
        }
    }

    /**
     * Hooks HyperOS 4's stack gap calculation, native Liquid Glass / Blur background outline,
     * group background visibility, and lockscreen stacking interactor so docked priority rows +
     * shared Footer merge into one zero-seam native glass card.
     */
    private void hookMergedCardLayout(ModuleMain module, ClassLoader loader) {
        // 1. Zero-gap joining between priority rows & Footer + clean breathing gap below Footer
        try {
            Class<?> algoClass = loader.loadClass(NOTIFICATION + "stack.StackScrollAlgorithm");
            Class<?> expViewClass = loader.loadClass(NOTIFICATION + "row.ExpandableView");
            Method getGap = requireMethod(algoClass, "getGapHeightForChild", expViewClass, expViewClass);
            Field padField = requireField(algoClass, "mPaddingBetweenElements");
            Field hostViewField = requireField(algoClass, "mHostView");
            module.hook(getGap).intercept(chain -> {
                float defaultGap = (float) chain.proceed();
                try {
                    Object algo = chain.getThisObject();
                    Object hostObj = hostViewField.get(algo);
                    PrioritySectionFrame frame = activeFrames.get(hostObj);
                    if (frame == null) return defaultGap;
                    View child = (View) chain.getArg(0);
                    View prev = (View) chain.getArg(1);
                    float pad = padField.getFloat(algo);
                    return frame.adjustGapHeight(child, prev, defaultGap, pad);
                } catch (Throwable ignored) {
                    return defaultGap;
                }
            });
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed to hook StackScrollAlgorithm gap for merged priority card", t);
        }

        // 2. Unified native HyperOS NotificationBackgroundView height, Liquid Glass SDF & outline
        try {
            Class<?> nbvClass = loader.loadClass(NOTIFICATION + "row.NotificationBackgroundView");
            Class<?> nbviClass = loader.loadClass(NOTIFICATION + "row.NotificationBackgroundViewInjectorImpl");
            Class<?> outlineProvClass = loader.loadClass(
                    NOTIFICATION + "row.NotificationBackgroundViewInjectorImpl$outlineProvider$1");
            Method getActualHeight = requireMethod(nbvClass, "getActualHeight");
            Method setClipBottom = requireMethod(nbvClass, "setClipBottomAmount", int.class);
            Method setBottomOverlap = requireMethod(nbvClass, "setBottomOverlap", int.class);
            Method getOutline = requireMethod(outlineProvClass, "getOutline", View.class, Outline.class);
            Method viewGetRenderNode = ModuleMain.findMethod(View.class, "getRenderNode");

            module.hook(getActualHeight).intercept(chain -> {
                Integer overrideH = PrioritySectionFrame.mergedHeightOverrideFor(chain.getThisObject());
                return overrideH != null ? overrideH : chain.proceed();
            });
            module.hook(setClipBottom).intercept(chain -> {
                if (PrioritySectionFrame.mergedHeightOverrideFor(chain.getThisObject()) != null) {
                    return chain.proceed(new Object[]{0});
                }
                return chain.proceed();
            });
            module.hook(setBottomOverlap).intercept(chain -> {
                if (PrioritySectionFrame.mergedHeightOverrideFor(chain.getThisObject()) != null) {
                    return chain.proceed(new Object[]{0});
                }
                return chain.proceed();
            });
            module.hook(getOutline).intercept(chain -> {
                Object result = chain.proceed();
                View view = (View) chain.getArg(0);
                Integer overrideH = PrioritySectionFrame.mergedHeightOverrideFor(view);
                if (overrideH != null && overrideH > 0) {
                    Outline outline = (Outline) chain.getArg(1);
                    int w = view.getWidth();
                    if (w > 0 && outline != null) {
                        int left = view.getLeft();
                        int top = view.getTop();
                        int right = view.getRight();
                        if (viewGetRenderNode != null) {
                            try {
                                Object rn = viewGetRenderNode.invoke(view);
                                if (rn instanceof RenderNode renderNode) {
                                    renderNode.setPosition(left, top, right, top + overrideH);
                                }
                            } catch (Throwable ignored) {}
                        }
                        float fallbackR = 25f * view.getResources().getDisplayMetrics().density;
                        float r = PrioritySectionFrame.mergedRadiusFor(view, fallbackR);
                        outline.setRoundRect(0, 0, w, overrideH, r);
                    }
                }
                return result;
            });

            // Prevent MIUI group expand/collapse animators from switching hostBg to OutlineMode.SUMMARY
            try {
                Field nbviViewField = requireField(nbviClass, "view");
                Method updateBgOutline = requireMethod(nbviClass, "updateBackgroundOutline",
                        boolean.class, boolean.class);
                Method updateSummaryOutline = requireMethod(nbviClass, "updateSummaryBackgroundOutline",
                        int.class, boolean.class);
                module.hook(updateSummaryOutline).intercept(chain -> {
                    Object inj = chain.getThisObject();
                    try {
                        Object bgObj = nbviViewField.get(inj);
                        if (PrioritySectionFrame.isForcedVisibleHostBackground(bgObj)) {
                            updateBgOutline.invoke(inj, false, false);
                            return null;
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                });
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed to hook updateSummaryBackgroundOutline", t);
            }

            // Keep HyperOS Liquid Glass SDF height locked to merged height across relayout/effect updates
            try {
                Class<?> glassCompatClass = loader.loadClass("com.miui.systemui.util.MiGlassCompat");
                Method setSdfMax = requireMethod(glassCompatClass, "setMiGlassSdfMaxSizeCompat",
                        View.class, float.class, float.class);
                module.hook(setSdfMax).intercept(chain -> {
                    Integer overrideH = PrioritySectionFrame.mergedHeightOverrideFor(chain.getArg(0));
                    if (overrideH != null && overrideH > 0) {
                        return chain.proceed(new Object[]{
                                chain.getArg(0),
                                chain.getArg(1),
                                (float) overrideH
                        });
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed to hook MiGlassCompat SDF max size", t);
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed to hook NotificationBackgroundView for unified glass card", t);
        }

        // 3. Airtight host backdrop locking & suppressed background blocking across all HyperOS animators
        try {
            Class<?> expViewClass = loader.loadClass(NOTIFICATION + "row.ExpandableView");
            Method updateClipping = requireMethod(expViewClass, "updateClipping");
            module.hook(updateClipping).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof View v) {
                    Integer mergedH = PrioritySectionFrame.mergedRowHeightOverrideFor(v);
                    if (mergedH != null && mergedH > 0) {
                        int w = v.getWidth();
                        Rect cb = v.getClipBounds();
                        if (cb != null && w > 0 && (cb.bottom != mergedH || cb.right < w)) {
                            cb.bottom = mergedH;
                            cb.right = Math.max(cb.right, w);
                            v.setClipBounds(cb);
                        }
                    }
                }
                return result;
            });

            // Hard draw-time suppression: suppressed NotificationBackgroundViews NEVER enter RenderNode tree
            Class<?> expOutlineClass = loader.loadClass(NOTIFICATION + "row.ExpandableOutlineView");
            Method rowDrawChild = requireMethod(expOutlineClass, "drawChild",
                    Canvas.class, View.class, long.class);
            module.hook(rowDrawChild).intercept(chain -> {
                if (PrioritySectionFrame.isSuppressedBackground(chain.getArg(1))) {
                    return false;
                }
                return chain.proceed();
            });

            Class<?> nbvBaseClass = loader.loadClass(
                    "com.miui.systemui.notification.row.NotificationBackgroundViewBase");
            Method nbvSetAlpha = requireMethod(nbvBaseClass, "setAlpha", float.class);
            module.hook(nbvSetAlpha).intercept(chain -> {
                Object bgObj = chain.getThisObject();
                if (PrioritySectionFrame.isForcedVisibleHostBackground(bgObj)) {
                    return chain.proceed(new Object[]{1f});
                }
                if (PrioritySectionFrame.isSuppressedBackground(bgObj)) {
                    return chain.proceed(new Object[]{0f});
                }
                return chain.proceed();
            });

            Method nbvSetTransX = requireMethod(nbvBaseClass, "setTranslationX", float.class);
            module.hook(nbvSetTransX).intercept(chain -> {
                if (PrioritySectionFrame.isForcedVisibleHostBackground(chain.getThisObject())) {
                    return chain.proceed(new Object[]{0f});
                }
                return chain.proceed();
            });

            // Lock PropertiesFrameLayout deferred & super transforms on active hostBg so group expand
            // animators cannot shift hostBg down below "Shell(2) ^" or leave super.translationY stuck!
            Class<?> propLayoutClass = loader.loadClass("com.miui.systemui.widget.PropertiesFrameLayout");
            Method pflSetTransY = requireMethod(propLayoutClass, "setTranslationY", float.class);
            Method pflSetSuperTransY = requireMethod(propLayoutClass, "setSuperTranslationY", float.class);
            Method pflSetScaleX = requireMethod(propLayoutClass, "setScaleX", float.class);
            Method pflSetScaleY = requireMethod(propLayoutClass, "setScaleY", float.class);
            Method pflSetSuperScaleX = requireMethod(propLayoutClass, "setSuperScaleX", float.class);
            Method pflSetSuperScaleY = requireMethod(propLayoutClass, "setSuperScaleY", float.class);
            Method pflSetSuperTransZ = requireMethod(propLayoutClass, "setSuperTranslationZ", float.class);

            module.hook(pflSetTransY).intercept(chain -> {
                if (PrioritySectionFrame.isForcedVisibleHostBackground(chain.getThisObject())) {
                    return chain.proceed(new Object[]{0f});
                }
                return chain.proceed();
            });
            module.hook(pflSetSuperTransY).intercept(chain -> {
                if (PrioritySectionFrame.isForcedVisibleHostBackground(chain.getThisObject())) {
                    return chain.proceed(new Object[]{0f});
                }
                return chain.proceed();
            });
            module.hook(pflSetScaleX).intercept(chain -> {
                if (PrioritySectionFrame.isForcedVisibleHostBackground(chain.getThisObject())) {
                    return chain.proceed(new Object[]{1f});
                }
                return chain.proceed();
            });
            module.hook(pflSetScaleY).intercept(chain -> {
                if (PrioritySectionFrame.isForcedVisibleHostBackground(chain.getThisObject())) {
                    return chain.proceed(new Object[]{1f});
                }
                return chain.proceed();
            });
            module.hook(pflSetSuperScaleX).intercept(chain -> {
                if (PrioritySectionFrame.isForcedVisibleHostBackground(chain.getThisObject())) {
                    return chain.proceed(new Object[]{1f});
                }
                return chain.proceed();
            });
            module.hook(pflSetSuperScaleY).intercept(chain -> {
                if (PrioritySectionFrame.isForcedVisibleHostBackground(chain.getThisObject())) {
                    return chain.proceed(new Object[]{1f});
                }
                return chain.proceed();
            });
            module.hook(pflSetSuperTransZ).intercept(chain -> {
                Float zOverride = PrioritySectionFrame.dockedSuperTransZOverrideFor(chain.getThisObject());
                if (zOverride != null) {
                    return chain.proceed(new Object[]{zOverride});
                }
                return chain.proceed();
            });

            // Extend hostRow's stackingOutlineProvider to cover the shared Priority Footer when
            // bottom-clipped by stackingBottom, and use a straight horizontal bottom clip on
            // non-host docked rows sitting directly above the Footer hairline.
            try {
                Class<?> stackingOutlineClass = loader.loadClass(
                        NOTIFICATION + "row.ExpandableViewInjector$stackingOutlineProvider$1");
                Method getStackingOutline = requireMethod(
                        stackingOutlineClass, "getOutline", View.class, Outline.class);
                Method getClipHeightMethod = requireMethod(expViewClass, "getClipHeight");
                module.hook(getStackingOutline).intercept(chain -> {
                    Object result = chain.proceed();
                    View view = (View) chain.getArg(0);
                    Outline outline = (Outline) chain.getArg(1);
                    if (view == null || outline == null) return result;
                    int w = view.getWidth();
                    if (w <= 0) return result;
                    Integer mergedH = PrioritySectionFrame.mergedRowHeightOverrideFor(view);
                    if (mergedH != null && mergedH > 0) {
                        float fallbackR = 25f * view.getResources().getDisplayMetrics().density;
                        View bg = null;
                        try {
                            Field f = ModuleMain.findField(view.getClass(), "mBackgroundNormal");
                            if (f != null && f.get(view) instanceof View bv) bg = bv;
                        } catch (Throwable ignored) {}
                        float r = PrioritySectionFrame.mergedRadiusFor(bg != null ? bg : view, fallbackR);
                        outline.setRoundRect(0, 0, w, mergedH, r);
                        return result;
                    }
                    if (PrioritySectionFrame.isNonHostDockedRow(view)) {
                        try {
                            int clipH = (int) getClipHeightMethod.invoke(view);
                            if (clipH > 0) {
                                outline.setRect(0, 0, w, clipH);
                            }
                        } catch (Throwable ignored) {}
                    }
                    return result;
                });
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed to hook stackingOutlineProvider for priority merge", t);
            }

            Class<?> rowClass = loader.loadClass(NOTIFICATION + "row.ExpandableNotificationRow");
            Field bgField = requireField(rowClass, "mBackgroundNormal");
            Method updateGroupBg = requireMethod(rowClass, "updateBackgroundForGroupState");
            module.hook(updateGroupBg).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object bgObj = bgField.get(chain.getThisObject());
                    if (bgObj instanceof View bg) {
                        PrioritySectionFrame.enforceManagedBackgroundView(bg);
                    }
                } catch (Throwable ignored) {}
                return result;
            });

            // Re-assert managed background states after ExpandableNotificationRow$NotificationViewState.applyToView
            try {
                Class<?> rowStateClass = loader.loadClass(
                        NOTIFICATION + "row.ExpandableNotificationRow$NotificationViewState");
                Method applyToView = requireMethod(rowStateClass, "applyToView", View.class);
                module.hook(applyToView).intercept(chain -> {
                    Object result = chain.proceed();
                    if (PrioritySectionFrame.hasManagedBackgrounds()
                            && chain.getArg(0) instanceof View rowView) {
                        PrioritySectionFrame.enforceManagedBackgroundsForRow(rowView);
                    }
                    return result;
                });
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed to hook NotificationViewState.applyToView", t);
            }

            // Re-assert managed background states at the end of MIUI group expand ($3) & collapse ($5) animators
            for (String suffix : new String[]{"$startBackgroundAnimation$3", "$startBackgroundAnimation$5"}) {
                try {
                    Class<?> animListenerClass = loader.loadClass(
                            NOTIFICATION + "stack.NotificationChildrenContainerInjectorImpl" + suffix);
                    Field summaryBgField = requireField(animListenerClass, "$summaryBackground");
                    Field firstChildBgField = requireField(animListenerClass, "$firstChildBackground");
                    Method onAnimEnd = requireMethod(animListenerClass, "onAnimationEnd",
                            android.animation.Animator.class);
                    module.hook(onAnimEnd).intercept(chain -> {
                        Object result = chain.proceed();
                        if (PrioritySectionFrame.hasManagedBackgrounds()) {
                            try {
                                Object listener = chain.getThisObject();
                                if (summaryBgField.get(listener) instanceof View sbg) {
                                    PrioritySectionFrame.enforceManagedBackgroundView(sbg);
                                }
                                if (firstChildBgField.get(listener) instanceof View cbg) {
                                    PrioritySectionFrame.enforceManagedBackgroundView(cbg);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return result;
                    });
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "Failed to hook group background animation listener " + suffix, t);
                }
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed to hook group background / clip state for priority merge", t);
        }

        // 4. Keep HyperOS Lockscreen / viewport stacking harmonious with our unified Priority Card & Footer
        try {
            Class<?> interactorClass = loader.loadClass(
                    "com.miui.systemui.notification.domain.interactor.NotificationStackingInteractor");
            Class<?> expBaseClass = loader.loadClass(
                    "com.miui.systemui.notification.row.ExpandableViewBase");
            Class<?> expViewClass = loader.loadClass(NOTIFICATION + "row.ExpandableView");
            Class<?> rowClass = loader.loadClass(NOTIFICATION + "row.ExpandableNotificationRow");
            Class<?> viewInjectorClass = loader.loadClass(NOTIFICATION + "row.ExpandableViewInjector");
            Class<?> infoClass = loader.loadClass(
                    "com.miui.systemui.notification.view.NotificationRowStackingInfo");
            Field emptyInfoField = requireField(infoClass, "EMPTY");
            Object emptyInfo = emptyInfoField.get(null);
            Field miuiBigHeightField = ModuleMain.findField(interactorClass, "miuiBigNotificationHeight");
            Method calcStacking = requireMethod(interactorClass, "calculateStackingInfo",
                    expBaseClass, float.class, float.class, boolean.class, float.class);
            Method updateChildStacking = ModuleMain.findMethod(interactorClass, "updateChildStackingInfo",
                    expBaseClass, float.class, float.class);
            Method updateParentStacking = ModuleMain.findMethod(interactorClass, "updateParentStackingInfo",
                    expBaseClass, float.class, float.class);
            Method resetChildrenStacking = ModuleMain.findMethod(interactorClass,
                    "resetGroupChildrenStackingInfo", rowClass);
            Method getViewInjector = ModuleMain.findMethod(expViewClass, "getInjector");
            Field injIsLastNotif = ModuleMain.findField(viewInjectorClass, "isLastNotif");
            Field injIsLastFocusNotif = ModuleMain.findField(viewInjectorClass, "isLastFocusNotif");
            Method calcWithTransition = ModuleMain.findMethod(viewInjectorClass, "calculateWithTransition",
                    expViewClass, float.class, float.class, boolean.class, float.class);
            Method setExtTransZ = ModuleMain.findMethod(expBaseClass, "setExtTranslationZ", float.class);
            Method getViewState = ModuleMain.findMethod(expViewClass, "getViewState");
            Field vsInjectorField = getViewState != null
                    ? ModuleMain.findField(loader.loadClass(NOTIFICATION + "stack.ViewState"), "mInjector")
                    : null;
            Method combineFolmeTransZ = vsInjectorField != null
                    ? ModuleMain.findMethod(vsInjectorField.getType(), "combineFolmeTranslationZ")
                    : null;
            Method getChildrenContainer = ModuleMain.findMethod(rowClass, "getChildrenContainer");
            Field nccInjectorField = getChildrenContainer != null
                    ? ModuleMain.findField(
                            loader.loadClass(NOTIFICATION + "stack.NotificationChildrenContainer"), "mInjector")
                    : null;
            Field ncciIconAreaField = nccInjectorField != null
                    ? ModuleMain.findField(nccInjectorField.getType(), "iconArea")
                    : null;
            Field ncciGroupHeaderField = nccInjectorField != null
                    ? ModuleMain.findField(nccInjectorField.getType(), "mMiuiGroupHeader")
                    : null;
            Method headerSetStackingDist = ncciGroupHeaderField != null
                    ? ModuleMain.findMethod(ncciGroupHeaderField.getType(), "setStackingDistance", float.class)
                    : null;
            Method pflSetSuperTransY = ncciGroupHeaderField != null
                    ? ModuleMain.findMethod(ncciGroupHeaderField.getType(), "setSuperTranslationY", float.class)
                    : null;
            Method pflSetSuperScaleX = ncciGroupHeaderField != null
                    ? ModuleMain.findMethod(ncciGroupHeaderField.getType(), "setSuperScaleX", float.class)
                    : null;
            Method pflSetSuperScaleY = ncciGroupHeaderField != null
                    ? ModuleMain.findMethod(ncciGroupHeaderField.getType(), "setSuperScaleY", float.class)
                    : null;
            Method pflSetSuperAlpha = ncciGroupHeaderField != null
                    ? ModuleMain.findMethod(ncciGroupHeaderField.getType(), "setSuperAlpha", float.class)
                    : null;

            // Keep expanded Priority Group children unified at 1.0x scale inside the merged card
            // (never bunching/shrinking individual children into overlapping transparent text soup).
            if (updateChildStacking != null) {
                module.hook(updateChildStacking).intercept(chain -> {
                    Object childView = chain.getArg(0);
                    if (childView != null) {
                        for (PrioritySectionFrame frame : activeFrames.values()) {
                            if (frame != null && frame.isChildOfPriorityGroup(childView)) {
                                if (setExtTransZ != null) {
                                    float folmeZ = 0f;
                                    if (getViewState != null && vsInjectorField != null
                                            && combineFolmeTransZ != null) {
                                        try {
                                            Object vs = getViewState.invoke(childView);
                                            Object vsInj = vs != null ? vsInjectorField.get(vs) : null;
                                            if (vsInj != null
                                                    && combineFolmeTransZ.invoke(vsInj) instanceof Float fz) {
                                                folmeZ = fz;
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                    try {
                                        setExtTransZ.invoke(childView, folmeZ);
                                    } catch (Throwable ignored) {}
                                }
                                return emptyInfo;
                            }
                        }
                    }
                    return chain.proceed();
                });
            }

            // Treat an expanded Priority Group as one unified tall card in NotificationStackingInteractor
            // (identical to MIUI's unified-container big-notification clipping path) so it clips cleanly
            // above Lockscreen's fingerprint sensor / bottom shortcuts without child pile-up.
            if (updateParentStacking != null && getViewInjector != null && calcWithTransition != null) {
                module.hook(updateParentStacking).intercept(chain -> {
                    Object parentObj = chain.getArg(0);
                    if (parentObj instanceof View parentView) {
                        for (PrioritySectionFrame frame : activeFrames.values()) {
                            if (frame != null && frame.shouldUnifyGroupStacking(parentView)) {
                                try {
                                    if (resetChildrenStacking != null) {
                                        resetChildrenStacking.invoke(null, parentView);
                                    }
                                    if (getChildrenContainer != null && nccInjectorField != null) {
                                        Object cc = getChildrenContainer.invoke(parentView);
                                        Object ccInj = cc != null ? nccInjectorField.get(cc) : null;
                                        if (ccInj != null) {
                                            if (ncciIconAreaField != null
                                                    && ncciIconAreaField.get(ccInj) instanceof View iconArea) {
                                                iconArea.setTranslationX(0f);
                                                iconArea.setTranslationY(0f);
                                                iconArea.setScaleX(1f);
                                                iconArea.setScaleY(1f);
                                                iconArea.setClipBounds(null);
                                            }
                                            if (ncciGroupHeaderField != null
                                                    && ncciGroupHeaderField.get(ccInj) instanceof View gh) {
                                                if (headerSetStackingDist != null) {
                                                    headerSetStackingDist.invoke(gh, 0f);
                                                }
                                                if (pflSetSuperTransY != null) {
                                                    pflSetSuperTransY.invoke(gh, gh.getTranslationY());
                                                }
                                                if (pflSetSuperScaleX != null) {
                                                    pflSetSuperScaleX.invoke(gh, gh.getScaleX());
                                                }
                                                if (pflSetSuperScaleY != null) {
                                                    pflSetSuperScaleY.invoke(gh, gh.getScaleY());
                                                }
                                                if (pflSetSuperAlpha != null) {
                                                    pflSetSuperAlpha.invoke(gh, gh.getAlpha());
                                                }
                                            }
                                        }
                                    }
                                    Object inj = getViewInjector.invoke(parentView);
                                    if (inj != null) {
                                        boolean isLast = (injIsLastNotif != null && injIsLastNotif.getBoolean(inj))
                                                || (injIsLastFocusNotif != null && injIsLastFocusNotif.getBoolean(inj));
                                        float extTransY = (float) chain.getArg(1);
                                        float actualH = (float) chain.getArg(2);
                                        return calcWithTransition.invoke(
                                                inj, parentView, extTransY, actualH, isLast, parentView.getPivotY());
                                    }
                                } catch (Throwable ignored) {}
                                break;
                            }
                        }
                    }
                    return chain.proceed();
                });
            }

            module.hook(calcStacking).intercept(chain -> {
                Object view = chain.getArg(0);
                if (view != null) {
                    int bigNotifH = 0;
                    if (miuiBigHeightField != null) {
                        try {
                            bigNotifH = miuiBigHeightField.getInt(chain.getThisObject());
                        } catch (Throwable ignored) {}
                    }
                    for (PrioritySectionFrame frame : activeFrames.values()) {
                        if (frame == null) continue;
                        if (frame.isFooter(view) || frame.isChildOfPriorityGroup(view)) {
                            return emptyInfo;
                        }
                        float origH = (float) chain.getArg(2);
                        float footerReserve = frame.footerHeightReserveForDockedTail(view, origH, bigNotifH);
                        if (footerReserve > 0f) {
                            return chain.proceed(new Object[]{
                                    view,
                                    chain.getArg(1),
                                    origH + footerReserve,
                                    chain.getArg(3),
                                    chain.getArg(4)
                            });
                        }
                        float footerShift = frame.lockscreenStackingOffsetFor(view);
                        if (footerShift > 0f) {
                            float origY = (float) chain.getArg(1);
                            return chain.proceed(new Object[]{
                                     view,
                                     origY - footerShift,
                                     origH,
                                     chain.getArg(3),
                                     chain.getArg(4)
                            });
                        }
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed to hook NotificationStackingInteractor for priority footer", t);
        }
    }

    private PriorityPresentation headerFor(Object rootController) throws Exception {
        PriorityPresentation existing = headers.get(rootController);
        if (existing != null) return existing;
        Context context = ((View) getView.invoke(rootController)).getContext();
        GlowStyle.initInSystemUi(context);
        int layout = context.getResources().getIdentifier(
                "status_bar_notification_section_header", "layout", "com.android.systemui");
        ViewGroup header = (ViewGroup) LayoutInflater.from(context).inflate(layout, null, false);
        ViewGroup contents = (ViewGroup) headerContents.get(header);
        header.setPadding(0, 0, 0, 0);
        header.setMinimumHeight(0);
        header.setClipChildren(false);
        header.setClipToPadding(false);
        contents.setPadding(0, 0, 0, 0);
        contents.setMinimumHeight(0);
        if (contents.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
            mlp.setMargins(0, 0, 0, 0);
            mlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            mlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        contents.setClipChildren(false);
        contents.setClipToPadding(false);
        contents.removeAllViews();
        PriorityFooterView footerView = new PriorityFooterView(context);
        contents.addView(footerView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setClickable(false);
        header.setFocusable(false);
        setHeaderVisible.invoke(header, true, false);
        PrioritySectionFrame frame = new PrioritySectionFrame(header, contents, footerView, getClipHeight,
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
                Log.w(TAG, "Failed to update priority section footer height", e);
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
        PriorityPresentation presentation = new PriorityPresentation(controller, header, frame);
        headers.put(rootController, presentation);
        return presentation;
    }

    private record PriorityPresentation(Object controller, View header, PrioritySectionFrame frame) {}

    /**
     * Apple Intelligence-inspired shared bottom Footer inside the merged Priority Notifications
     * card: thin top hairline aligned to notification content edges + left-aligned monochrome
     * 7-lobe woven ribbon icon + "重要通知" / "Priority Notifications" label with adaptive
     * dark/light translucent ink and synchronized reveal icon glint.
     */
    static final class PriorityFooterView extends View {
        private static final float FOOTER_HEIGHT_DP = 40f;
        private static final float CONTENT_INSET_DP = 16f;
        private static final float ICON_SIZE_DP = 15.8f;
        private static final float ICON_TEXT_GAP_DP = 6.5f;

        private final String label;
        private final float density;
        private final float footerHeightPx;
        private final float contentInsetPx;
        private final float iconSizePx;
        private final float iconTextGapPx;

        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconGlintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path iconPath = new Path();

        private boolean darkAppearance = true;
        private boolean xiaoAiStyle = GlowStyle.isXiaoAi();
        private float cardLocalLeft = Float.NaN;
        private float cardLocalRight = Float.NaN;
        private float shimmerBoost;

        PriorityFooterView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            String language = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
            label = "zh".equals(language) ? "重要通知" : "Priority Notifications";

            footerHeightPx = FOOTER_HEIGHT_DP * density;
            contentInsetPx = CONTENT_INSET_DP * density;
            iconSizePx = ICON_SIZE_DP * density;
            iconTextGapPx = ICON_TEXT_GAP_DP * density;

            dividerPaint.setStyle(Paint.Style.STROKE);
            dividerPaint.setStrokeCap(Paint.Cap.BUTT);
            dividerPaint.setStrokeWidth(Math.max(1f, 0.70f * density));

            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            textPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 13.2f, context.getResources().getDisplayMetrics()));
            textPaint.setLetterSpacing(0.01f);

            setContentDescription(label);
            setAccessibilityHeading(true);
            setClickable(false);
            setFocusable(false);
            applyInkStyle();
        }

        void setCardHorizontalSpan(float localLeft, float localRight) {
            if (Math.abs(cardLocalLeft - localLeft) < 0.5f
                    && Math.abs(cardLocalRight - localRight) < 0.5f) {
                return;
            }
            cardLocalLeft = localLeft;
            cardLocalRight = localRight;
            invalidate();
        }

        void setDarkAppearance(boolean dark) {
            if (darkAppearance == dark) return;
            darkAppearance = dark;
            applyInkStyle();
            invalidate();
        }

        void setXiaoAiStyle(boolean xiaoAi) {
            if (xiaoAiStyle == xiaoAi) return;
            xiaoAiStyle = xiaoAi;
            invalidate();
        }

        void setShimmer(float progress, float boost) {
            if (shimmerBoost == boost) return;
            shimmerBoost = boost;
            invalidate();
        }

        private void applyInkStyle() {
            if (darkAppearance) {
                dividerPaint.setColor(0x36FFFFFF);
                iconPaint.setColor(0xDCFFFFFF);
                textPaint.setColor(0xDCFFFFFF);
            } else {
                dividerPaint.setColor(0x1E000000);
                iconPaint.setColor(0xA6000000);
                textPaint.setColor(0xA6000000);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? Math.round(contentInsetPx * 2f + iconSizePx + iconTextGapPx + textPaint.measureText(label))
                    : MeasureSpec.getSize(widthMeasureSpec);
            int h = Math.round(footerHeightPx);
            setMeasuredDimension(
                    resolveSize(w, widthMeasureSpec),
                    resolveSize(h, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            float spanLeft = Float.isNaN(cardLocalLeft) ? 0f : cardLocalLeft;
            float spanRight = Float.isNaN(cardLocalRight) ? w : cardLocalRight;
            if (spanRight - spanLeft < contentInsetPx * 2f + iconSizePx) {
                spanLeft = 0f;
                spanRight = w;
            }

            // 1. Crisp internal horizontal hairline separating the notification body from Footer
            float lineLeft = spanLeft + contentInsetPx;
            float lineRight = spanRight - contentInsetPx;
            float lineY = Math.max(0.5f, 0.5f * density);
            canvas.drawLine(lineLeft, lineY, lineRight, lineY, dividerPaint);

            // 2. Monochrome Apple Intelligence 7-lobe icon + label, aligned to content leading edge
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float contentCenterY = (lineY + h) * 0.5f - 0.5f * density;
            float textWidth = textPaint.measureText(label);

            float iconLeft;
            float textX;
            if (rtl) {
                iconLeft = lineRight - iconSizePx;
                textX = iconLeft - iconTextGapPx - textWidth;
            } else {
                iconLeft = lineLeft;
                textX = iconLeft + iconSizePx + iconTextGapPx;
            }
            float iconTop = contentCenterY - iconSizePx * 0.5f;

            // Base monochrome 7-lobe icon
            ReduceInterruptions.drawAppleIntelMono(
                    canvas, iconLeft, iconTop, iconSizePx, iconPaint, 1.85f, iconPath);

            // Synchronized subtle icon glint during the 1.6s one-shot reveal sweep
            if (shimmerBoost > 0.01f) {
                int glintColor = xiaoAiStyle
                        ? (darkAppearance ? 0xFFFFB28A : 0xFFE53882)
                        : (darkAppearance ? 0xFF8BEAFF : 0xFF3D8BFF);
                int glintAlpha = Math.min(255, Math.round(shimmerBoost * (darkAppearance ? 140f : 115f)));
                iconGlintPaint.setColor((glintAlpha << 24) | (glintColor & 0x00FFFFFF));
                ReduceInterruptions.drawAppleIntelMono(
                        canvas, iconLeft, iconTop, iconSizePx, iconGlintPaint, 2.05f, iconPath);
                int coreAlpha = Math.min(255, Math.round(shimmerBoost * (darkAppearance ? 110f : 60f)));
                if (coreAlpha > 0) {
                    iconGlintPaint.setColor(Color.argb(coreAlpha, 255, 255, 255));
                    ReduceInterruptions.drawAppleIntelMono(
                            canvas, iconLeft, iconTop, iconSizePx, iconGlintPaint, 1.55f, iconPath);
                }
            }

            // Label
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = contentCenterY - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(label, textX, baseline, textPaint);
        }
    }

    /**
     * Lockscreen "减少干扰" line below the clock: Apple Intelligence ribbon LOGO + label,
     * Static drawing (no animation, respects reduced-motion by design); translucent white
     * ink with an anti-halo shadow matches keyguard clock contrast on any wallpaper.
     */
    static final class ReduceLockPillView extends View implements android.view.ViewTreeObserver.OnPreDrawListener {
        private final View header;
        private final int[] location = new int[2];
        private View mediaHeader;
        private ReduceLockPillView coverLabel;
        private final Path logoPath = new Path();
        private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final float logoSize;
        private final float iconTextGap;
        private final float padTop;
        private final float padBottom;

        ReduceLockPillView(Context context, View header) {
            super(context);
            this.header = header;
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

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (header != null) getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override protected void onDetachedFromWindow() {
            if (header != null) getViewTreeObserver().removeOnPreDrawListener(this);
            removeCoverLabel();
            mediaHeader = null;
            super.onDetachedFromWindow();
        }

        @Override public boolean onPreDraw() {
            if (HyperMusicCoverDate.isModuleLoaded() && SystemUiReduceInterruptions.lockPillShowing()
                    && header.getParent() instanceof ViewGroup stack && stack.getParent() instanceof ViewGroup host) {
                if (mediaHeader == null || mediaHeader.getParent() != stack) {
                    mediaHeader = null;
                    for (int i = 0; i < stack.getChildCount(); i++) {
                        View child = stack.getChildAt(i);
                        if (child.getClass().getName().endsWith(".MiuiMediaHeaderView")) {
                            mediaHeader = child;
                            break;
                        }
                    }
                }
                if (mediaHeader != null && mediaHeader.isShown() && mediaHeader.getAlpha() > 0f
                        && mediaHeader.getHeight() > 0 && stack.getAlpha() > 0f && getWidth() > 0) {
                    // HyperMusicCover can clip the whole section header. Draw the same label in
                    // the stack's parent so its visible and touch bounds both sit above the card.
                    if (coverLabel == null || coverLabel.getParent() != host) {
                        removeCoverLabel();
                        coverLabel = new ReduceLockPillView(getContext(), null);
                        coverLabel.setOnClickListener(v -> SystemUiReduceInterruptions.requestLockReveal());
                        host.addView(coverLabel, getWidth(), getHeight());
                    }
                    mediaHeader.getLocationOnScreen(location);
                    int x = location[0] + (mediaHeader.getWidth() - getWidth()) / 2;
                    int y = location[1] - dp(getContext(), 12) - getHeight();
                    host.getLocationOnScreen(location);
                    coverLabel.setX(x - location[0]);
                    coverLabel.setY(y - location[1]);
                    coverLabel.setAlpha(mediaHeader.getAlpha() * stack.getAlpha());
                    coverLabel.setClickable(isClickable());
                    setVisibility(INVISIBLE);
                    return true;
                }
            }
            removeCoverLabel();
            return true;
        }

        private void removeCoverLabel() {
            if (coverLabel != null && coverLabel.getParent() instanceof ViewGroup parent) parent.removeView(coverLabel);
            coverLabel = null;
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
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
