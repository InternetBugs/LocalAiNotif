package com.localai.notif;

import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import static com.localai.notif.SystemUiPrioritySection.requireField;
import static com.localai.notif.SystemUiPrioritySection.requireMethod;

/** Owns the first upward touch on the keyguard while Reduce Interruptions has hidden rows. */
final class LockscreenRevealGesture {
    private static final String SHADE = "com.android.systemui.shade.";
    private static final int STATUS_BAR_KEYGUARD = 1;

    private final Field eventHandler;
    private final Field eventController;
    private final Field windowController;
    private final Field currentState;
    private final Field statusBarState;
    private final Field keyguardShowing;
    private final Field occlusionTransition;
    private final Field surfaces;
    private final Field keyguardManager;
    private final Method bouncerShowingOrPending;
    private final Field[] excludedStates;
    private final WeakHashMap<View, Swipe> swipes = new WeakHashMap<>();

    LockscreenRevealGesture(ModuleMain module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> window = loader.loadClass(SHADE + "NotificationShadeWindowView");
        eventHandler = requireField(window, "mInteractionEventHandler");
        eventController = requireField(eventHandler.getType(), "this$0");
        Class<?> controller = eventController.getType();
        windowController = requireField(controller, "mNotificationShadeWindowController");
        occlusionTransition = requireField(controller, "mIsOcclusionTransitionRunning");
        surfaces = requireField(controller, "mService");
        Class<?> centralSurfaces = loader.loadClass("com.android.systemui.statusbar.phone.CentralSurfacesImpl");
        keyguardManager = requireField(centralSurfaces, "mStatusBarKeyguardViewManager");
        bouncerShowingOrPending = requireMethod(keyguardManager.getType(), "primaryBouncerIsOrWillBeShowing");
        currentState = requireField(loader.loadClass(SHADE + "NotificationShadeWindowControllerImpl"), "mCurrentState");
        Class<?> state = currentState.getType();
        statusBarState = requireField(state, "statusBarState");
        keyguardShowing = requireField(state, "keyguardShowing");
        String[] exclusions = {"keyguardOccluded", "bouncerShowing", "keyguardGoingAway", "keyguardFadingAway",
                "dozing", "dreaming", "qsExpanded", "controlCenterExpanded", "remoteInputActive"};
        excludedStates = new Field[exclusions.length];
        for (int i = 0; i < exclusions.length; i++) excludedStates[i] = requireField(state, exclusions[i]);

        Method dispatch = requireMethod(window, "dispatchTouchEvent", MotionEvent.class);
        module.hook(dispatch).intercept(chain -> {
            View root = (View) chain.getThisObject();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                swipes.remove(root);
                if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && canReveal(root)) {
                    swipes.put(root, new Swipe(root, event));
                }
                boolean handled = (boolean) chain.proceed();
                if (!handled) swipes.remove(root);
                return handled;
            }
            Swipe swipe = swipes.get(root);
            if (swipe == null) return chain.proceed();
            boolean ended = action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP;
            if (ended) swipes.remove(root);
            if (swipe.claimed) return true;
            if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) {
                swipes.remove(root);
                return chain.proceed();
            }
            float up = swipe.downY - event.getY();
            float across = Math.abs(event.getX() - swipe.downX);
            if (Math.max(Math.abs(up), across) <= swipe.slop) return chain.proceed();
            if (up <= across || !canReveal(root)) {
                swipes.remove(root);
                return chain.proceed();
            }

            // Finish the stream already sent into SystemUI before publishing the revealed list.
            // Deliver CANCEL at the down point so it carries no upward travel into the unlock recognizer.
            swipe.claimed = true;
            MotionEvent cancel = MotionEvent.obtainNoHistory(event);
            try {
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                cancel.setLocation(swipe.downX, swipe.downY);
                chain.proceed(new Object[]{cancel});
            } finally {
                cancel.recycle();
            }
            SystemUiReduceInterruptions.requestLockReveal();
            return true;
        });
    }

    private boolean canReveal(View root) {
        if (!SystemUiReduceInterruptions.lockQuietHiddenActive() || SystemUiReduceInterruptions.lockQuietCount() <= 0) {
            return false;
        }
        try {
            return isKeyguardSurface(root);
        } catch (ReflectiveOperationException e) {
            Log.w("LocalAiNotif", "Could not read lockscreen gesture state", e);
            return false;
        }
    }

    private boolean isKeyguardSurface(View root) throws ReflectiveOperationException {
        Object handler = eventHandler.get(root);
        if (handler == null) return false;
        Object controller = eventController.get(handler);
        if (occlusionTransition.getBoolean(controller)) return false;
        Object window = windowController.get(controller);
        if (!currentState.getDeclaringClass().isInstance(window)) return false;
        Object state = currentState.get(window);
        if (state == null || statusBarState.getInt(state) != STATUS_BAR_KEYGUARD || !keyguardShowing.getBoolean(state)) {
            return false;
        }
        for (Field excluded : excludedStates) if (excluded.getBoolean(state)) return false;
        Object centralSurfaces = surfaces.get(controller);
        if (!keyguardManager.getDeclaringClass().isInstance(centralSurfaces)) return false;
        Object manager = keyguardManager.get(centralSurfaces);
        return manager != null && !(boolean) bouncerShowingOrPending.invoke(manager);
    }

    private static final class Swipe {
        final float downX;
        final float downY;
        final int slop;
        boolean claimed;

        Swipe(View root, MotionEvent down) {
            downX = down.getX();
            downY = down.getY();
            slop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
        }
    }
}
