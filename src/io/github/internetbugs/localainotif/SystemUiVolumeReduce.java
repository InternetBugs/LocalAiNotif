package io.github.internetbugs.localainotif;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.xmlpull.v1.XmlPullParser;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Adds a Reduce Interruptions row to the HyperOS 4.0.3 volume plugin. */
final class SystemUiVolumeReduce {
    private static final String TAG = "LocalAi.VolumeReduce";
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final String VOLUME = "com.android.systemui.miui.volume.";
    private static final String RINGER_LAYOUT = VOLUME + "MiuiRingerModeLayout";
    private static final long DND_EXIT_NOTICE_WINDOW_MS = 1_500L;
    private static final float MOON_DIM_ALPHA = 0.4f;

    // MIUISystemUIPlugin.apk resources on HyperOS 4.0.3.
    private static final int RID_RINGER_LAYOUT_ROOT = 0x7f0a0330;
    private static final int RID_BUTTON_ROW = 0x7f0a0329;
    private static final int RID_HOST_FRAME = 0x7f0a032a;
    private static final int RID_DIVIDER = 0x7f0a032f;
    private static final int RID_DND_COLUMN = 0x7f0a0188;
    private static final int RID_RINGER_COLUMN = 0x7f0a045c;
    private static final int RID_TIMER_LAYOUT = 0x7f0a052a;
    private static final int RID_TIMER_SEEKBAR = 0x7f0a052b;
    private static final int RID_COLUMN_LAYOUT = 0x7f0d0126;

    private static final WeakHashMap<Object, WeakReference<Binding>> bindings = new WeakHashMap<>();
    private static final Set<Object> reduceTimers = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Object> moonHelpers = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Binding> measuringPanel = new ThreadLocal<>();

    private static volatile ClassLoader hookedLoader;
    private static volatile boolean reduceActive;
    private static volatile long reduceDeadline;
    private static volatile long suppressDndExitUntil;

    private static final class Binding {
        View layout;
        View divider;
        LinearLayout column;
        View ringerColumn;
        LinearLayout moonColumn;
        View[] moonButtonParts;
        View[] buttonParts;
        View moonTimerLayout;
        View timerLayout;
        View moonSeekBar;
        Object helper;
        Object moonHelper;
        Object timer;
        ImageView icon;
        VolumeReduceIconDrawable sparkle;
        boolean expanded;
    }

    static void install(ModuleMain module, ClassLoader loader, Consumer<Context> initializeState) {
        try {
            Class<?> factory = loader.loadClass("com.android.systemui.shared.plugins.PluginInstance$PluginFactory");
            Field host = ModuleMain.findField(factory, "hostContext");
            Method createContext = factory.getDeclaredMethod("createPluginContext");
            module.hook(createContext).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof Context ctx && PLUGIN_PACKAGE.equals(ctx.getPackageName())) {
                    installPluginHooks(module, ctx.getClassLoader());
                    if (host != null) initializeState.accept((Context) host.get(chain.getThisObject()));
                }
                return result;
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Volume plugin loader hook unavailable", e);
        }

        try {
            Method inflate = LayoutInflater.class.getMethod(
                    "inflate", XmlPullParser.class, ViewGroup.class, boolean.class);
            module.hook(inflate).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof View view && view.getClass().getName().startsWith(VOLUME)) {
                    View layout = view.findViewById(RID_RINGER_LAYOUT_ROOT);
                    if (layout != null) {
                        ClassLoader pluginLoader = layout.getClass().getClassLoader();
                        installPluginHooks(module, pluginLoader);
                        if (hookedLoader == pluginLoader) tryAttach(layout);
                    }
                }
                return result;
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "Volume layout hook unavailable", e);
        }
    }

    static void applyReduceState(boolean active, long deadline, boolean suppressDndExit) {
        boolean changed = active != reduceActive || deadline != reduceDeadline;
        if (suppressDndExit) {
            // Subsequent state broadcasts may still carry the previous native Zen value.
            suppressDndExitUntil = SystemClock.elapsedRealtime() + DND_EXIT_NOTICE_WINDOW_MS;
        } else if (active) {
            suppressDndExitUntil = 0L;
        }
        reduceDeadline = deadline;
        reduceActive = active;
        if (changed) {
            for (WeakReference<Binding> reference : new ArrayList<>(bindings.values())) {
                Binding binding = reference.get();
                if (binding != null) updateButtonsAndTimer(binding);
            }
        }
    }

    private static synchronized void installPluginHooks(ModuleMain module, ClassLoader loader) {
        if (hookedLoader == loader) return;
        try {
            Class<?> layout = loader.loadClass(RINGER_LAYOUT);
            Class<?> timer = loader.loadClass(VOLUME + "TimerItem");
            Class<?> helper = loader.loadClass(RINGER_LAYOUT + "$RingerButtonHelper");
            Class<?> util = loader.loadClass(VOLUME + "Util");
            Method setTotal = ModuleMain.findMethod(util, "setLastTotalCountDownTime", Context.class, int.class, boolean.class);
            Method scheduleTick = ModuleMain.findMethod(timer, "scheduleTimerUpdateH", boolean.class);
            Field timerContext = ModuleMain.findField(timer, "mContext");
            Field helperState = ModuleMain.findField(helper, "mState");

            module.hook(ModuleMain.findMethod(layout, "updateExpandedH", boolean.class, boolean.class))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Binding binding = bindingFor(chain.getThisObject());
                        if (binding != null) applyExpanded(binding, (boolean) chain.getArg(0), false);
                        return result;
                    });
            module.hook(ModuleMain.findMethod(layout, "setNeedShowDialog", boolean.class)).intercept(chain -> {
                Object result = chain.proceed();
                Binding binding = bindingFor(chain.getThisObject());
                if (binding != null) {
                    invoke(binding.timer, "setNeedShowDialog", (boolean) chain.getArg(0));
                    applyExpanded(binding, binding.expanded, true);
                }
                return result;
            });
            for (String name : new String[]{"onMaterialModeChanged", "updateResources"}) {
                module.hook(ModuleMain.findMethod(layout, name)).intercept(chain -> {
                    Object result = chain.proceed();
                    Binding binding = bindingFor(chain.getThisObject());
                    if (binding != null) {
                        invoke(binding.timer, "updateTimerSeekbar");
                        applyExpanded(binding, binding.expanded, true);
                    }
                    return result;
                });
            }
            module.hook(ModuleMain.findMethod(layout, "setZenModeByUser", boolean.class)).intercept(chain -> {
                if (!reduceActive || bindingFor(chain.getThisObject()) == null) return chain.proceed();
                requestMode(((View) chain.getThisObject()).getContext(), ReduceInterruptions.MODE_STRICT_DND, -1);
                return null;
            });

            module.hook(ModuleMain.findMethod(helper, "updateState")).intercept(chain -> {
                Object button = chain.getThisObject();
                if (!reduceActive || !moonHelpers.contains(button)) return chain.proceed();
                boolean nativeState = helperState.getBoolean(button);
                helperState.setBoolean(button, false);
                try {
                    return chain.proceed();
                } finally {
                    helperState.setBoolean(button, nativeState);
                }
            });

            module.hook(timer.getMethod("onTimeSet", int.class)).intercept(chain -> {
                Object item = chain.getThisObject();
                if (!reduceTimers.contains(item)) return chain.proceed();
                int seconds = (int) chain.getArg(0);
                if (seconds > 0 || reduceActive) {
                    Context context = (Context) timerContext.get(item);
                    setTotal.invoke(null, context, seconds, true);
                    requestMode(context, ReduceInterruptions.MODE_REDUCE, seconds / 60);
                }
                return null;
            });
            module.hook(timer.getMethod("updateRemainTimeH", boolean.class, int.class)).intercept(chain -> {
                Object item = chain.getThisObject();
                if (!reduceTimers.contains(item)) return chain.proceed();
                long remaining = reduceActive ? reduceDeadline - System.currentTimeMillis() : 0L;
                int seconds = (int) ((Math.max(0L, remaining) + 999L) / 1_000L);
                Object result = chain.proceed(new Object[]{true, seconds});
                if (seconds == 0) scheduleTick.invoke(item, false);
                return result;
            });

            hookPanelHeight(module, loader);
            hookDndNotice(module, loader);
            hookPanelMotion(module, loader);
            hookedLoader = loader;
            Log.i(TAG, "Volume panel hooks ready");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.e(TAG, "Volume plugin hooks failed", e);
        }
    }

    private static void hookPanelMotion(ModuleMain module, ClassLoader loader) {
        try {
            Class<?> expand = loader.loadClass(VOLUME + "VolumeExpandCollapsedAnimator");
            Field layout = expand.getDeclaredField("mRingerModeLayout");
            layout.setAccessible(true);
            module.hook(expand.getDeclaredMethod("frameCallback$lambda$9", expand, long.class)).intercept(chain -> {
                Object result = chain.proceed();
                Binding binding = bindingFor(layout.get(chain.getArg(0)));
                if (binding != null) syncExpandedFrame(binding);
                return result;
            });
            module.hook(expand.getDeclaredMethod("setVolumeContainerTranslationZ", boolean.class)).intercept(chain -> {
                Object result = chain.proceed();
                Binding binding = bindingFor(layout.get(chain.getThisObject()));
                if (binding != null) binding.buttonParts[0].setTranslationZ(binding.moonButtonParts[0].getTranslationZ());
                return result;
            });
            hookCollapsedRenderThreadMotion(module, loader);
            hookCollapsedUiMotion(module, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.w(TAG, "Volume panel animation hook unavailable", e);
        }
    }

    private static void syncExpandedFrame(Binding binding) {
        View moon = binding.moonColumn;
        int rowSpacing = moon.getTop() - binding.ringerColumn.getTop();
        copyBounds(moon, binding.column, rowSpacing);
        for (int index = 0; index < binding.buttonParts.length; index++) {
            copyBounds(binding.moonButtonParts[index], binding.buttonParts[index], 0);
        }
        View timer = binding.timerLayout;
        View moonTimer = binding.moonTimerLayout;
        copyBounds(moonTimer, timer, 0);
        timer.setPivotX(moonTimer.getPivotX());
        timer.setPivotY(moonTimer.getPivotY());
        timer.setScaleX(moonTimer.getScaleX());
        timer.setScaleY(moonTimer.getScaleY());
        timer.setAlpha(moonTimer.getAlpha());
    }

    private static void copyBounds(View source, View target, int verticalOffset) {
        target.setLeftTopRightBottom(source.getLeft(), source.getTop() + verticalOffset,
                source.getRight(), source.getBottom() + verticalOffset);
    }

    private static void hookCollapsedRenderThreadMotion(ModuleMain module, ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> animator = loader.loadClass(VOLUME + "VolumeShowHideAnimatorRT");
        Field rows = animator.getDeclaredField("ringerBtnLayouts");
        Field bases = animator.getDeclaredField("ringerTranslateBases");
        Field motionArgs = animator.getDeclaredField("ringerArgs");
        Field layout = animator.getDeclaredField("ringerLayout");
        rows.setAccessible(true);
        bases.setAccessible(true);
        motionArgs.setAccessible(true);
        layout.setAccessible(true);
        module.hook(animator.getDeclaredMethod("computeGeometry")).intercept(chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            View[] buttons = (View[]) rows.get(owner);
            if (buttons.length == 3 && bindingFor(layout.get(owner)) != null) {
                float[] offsets = (float[]) bases.get(owner);
                float rowSpacing = buttons[2].getTop() - buttons[1].getTop();
                bases.set(owner, new float[]{offsets[0], offsets[1], offsets[1] + rowSpacing});
            }
            return result;
        });
        module.hook(animator.getDeclaredMethod("computeRingerArgs", boolean.class)).intercept(chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            Binding binding = bindingFor(layout.get(owner));
            View[] buttons = (View[]) rows.get(owner);
            if (binding != null && buttons.length == 3 && buttons[2] == binding.column) {
                Object[] args = (Object[]) motionArgs.get(owner);
                args[2] = args[1];
            }
            return result;
        });
        module.hook(animator.getDeclaredMethod("resetState", String.class, boolean.class)).intercept(chain -> {
            Object owner = chain.getThisObject();
            View[] buttons = (View[]) rows.get(owner);
            Binding binding = bindingFor(layout.get(owner));
            if (binding != null && buttons.length == 2 && buttons[1] == binding.moonColumn) {
                rows.set(owner, new View[]{buttons[0], buttons[1], binding.column});
            }
            return chain.proceed();
        });
    }

    private static void hookCollapsedUiMotion(ModuleMain module, ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> listener = loader.loadClass(VOLUME + "VolumeShowHideAnimator$listener$1");
        Field owner = listener.getDeclaredField("this$0");
        Field layout = owner.getType().getDeclaredField("mRingerLayout");
        owner.setAccessible(true);
        layout.setAccessible(true);
        module.hook(listener.getDeclaredMethod("onUpdate", Object.class, Collection.class)).intercept(chain -> {
            Object result = chain.proceed();
            Binding binding = bindingFor(layout.get(owner.get(chain.getThisObject())));
            if (binding != null) {
                View moon = binding.moonColumn;
                float rowStep = moon.getTranslationY() - binding.ringerColumn.getTranslationY();
                binding.column.setTranslationX(moon.getTranslationX());
                binding.column.setTranslationY(moon.getTranslationY() + rowStep);
                binding.column.setScaleX(moon.getScaleX());
                binding.column.setScaleY(moon.getScaleY());
            }
            return result;
        });
    }

    private static void hookPanelHeight(ModuleMain module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> motion = loader.loadClass(VOLUME + "MiuiVolumeDialogMotion");
        Class<?> dimensions = loader.loadClass(VOLUME + "MiuiVolumeDialogRes");
        Class<?> buttonRes = loader.loadClass(VOLUME + "RingerButtonRes");
        Field ringer = ModuleMain.findField(motion, "mRingerModeLayout");
        Method rowHeight = ModuleMain.findMethod(buttonRes, "getHeight", Context.class, boolean.class, boolean.class);
        Method dividerHeight = ModuleMain.findMethod(dimensions, "getRingerModeLayoutDividerHeight", Context.class, boolean.class);

        // Both the content position and expanded blur/shadow use this native height.
        for (String name : new String[]{"getExpandedStates", "updateExpandBgSize"}) {
            module.hook(ModuleMain.findMethod(motion, name)).intercept(chain -> {
                Binding binding = bindingFor(ringer.get(chain.getThisObject()));
                if (binding == null) return chain.proceed();
                measuringPanel.set(binding);
                try {
                    return chain.proceed();
                } finally {
                    measuringPanel.remove();
                }
            });
        }
        module.hook(dimensions.getMethod("getHeight", Context.class, boolean.class, boolean.class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (measuringPanel.get() == null || !(boolean) chain.getArg(2)) return result;
                    Context context = (Context) chain.getArg(0);
                    boolean floating = (boolean) chain.getArg(1);
                    int gap = (int) dividerHeight.invoke(null, context, floating);
                    measuringPanel.get().divider.getLayoutParams().height = gap;
                    return (int) result + gap + (int) rowHeight.invoke(null, context, true, floating);
                });
    }

    private static void hookDndNotice(ModuleMain module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> observer = loader.loadClass(VOLUME + "VolumePanelViewController$SilenceModeObserver");
        Method show = ModuleMain.findMethod(observer, "showToastOrStatusBar", boolean.class, boolean.class);
        Field silent = ModuleMain.findField(observer, "mIsSilenceMode");
        Field zen = ModuleMain.findField(observer, "mIsZenMode");
        module.hook(show).intercept(chain -> {
            Object state = chain.getThisObject();
            boolean zenOn = zen.getBoolean(state);
            boolean zenChanged = zenOn != (boolean) chain.getArg(1);
            if (zenChanged && silent.getBoolean(state) == (boolean) chain.getArg(0)) {
                if (shouldSuppressDndNotice(zenOn)) return null;
                if (zenOn) suppressDndExitUntil = 0L;
            }
            return chain.proceed();
        });

        Class<?> panel = loader.loadClass(VOLUME + "VolumePanelViewController");
        Method postNotice = ModuleMain.findMethod(panel, "showToastInStatusBar",
                String.class, String.class, String.class, int.class, String.class);
        if (postNotice == null) return;
        // The observer can queue an island post before the controller state reaches SystemUI.
        module.hook(postNotice).intercept(chain -> {
            Object notice = chain.getArg(2);
            if (("disturb_mode_on".equals(notice) && shouldSuppressDndNotice(true))
                    || ("disturb_mode_off".equals(notice) && shouldSuppressDndNotice(false))) {
                return null;
            }
            return chain.proceed();
        });
    }

    private static boolean shouldSuppressDndNotice(boolean zenOn) {
        return reduceActive || (!zenOn && SystemClock.elapsedRealtime() < suppressDndExitUntil);
    }

    private static Binding bindingFor(Object layout) {
        WeakReference<Binding> reference = bindings.get(layout);
        return reference == null ? null : reference.get();
    }

    private static void tryAttach(View layout) {
        if (bindingFor(layout) != null) return;
        try {
            Context context = layout.getContext();
            LinearLayout row = layout.findViewById(RID_BUTTON_ROW);
            View nativeDivider = row.findViewById(RID_DIVIDER);
            LinearLayout moonColumn = row.findViewById(RID_DND_COLUMN);
            LinearLayout column = (LinearLayout) LayoutInflater.from(context).inflate(RID_COLUMN_LAYOUT, row, false);
            column.setId(View.generateViewId());
            Class<?> layoutClass = layout.getClass();
            ClassLoader loader = layoutClass.getClassLoader();
            Class<?> helperClass = loader.loadClass(RINGER_LAYOUT + "$RingerButtonHelper");
            Constructor<?> helperCtor = helperClass.getDeclaredConstructor(layoutClass, View.class, boolean.class, boolean.class);
            helperCtor.setAccessible(true);
            Object helper = helperCtor.newInstance(layout, column, true, reduceActive);
            Class<?> timerClass = loader.loadClass(VOLUME + "TimerItem");
            Constructor<?> timerCtor = timerClass.getDeclaredConstructor(Context.class, View.class, boolean.class, View.class);
            timerCtor.setAccessible(true);
            Object timer = timerCtor.newInstance(context, column, true, layout);

            Binding binding = new Binding();
            binding.layout = layout;
            binding.divider = new View(context);
            binding.column = column;
            binding.ringerColumn = row.findViewById(RID_RINGER_COLUMN);
            binding.moonColumn = moonColumn;
            binding.moonTimerLayout = moonColumn.findViewById(RID_TIMER_LAYOUT);
            binding.timerLayout = column.findViewById(RID_TIMER_LAYOUT);
            binding.moonSeekBar = moonColumn.findViewById(RID_TIMER_SEEKBAR);
            binding.helper = helper;
            binding.moonHelper = ModuleMain.findField(layoutClass, "mDndHelper").get(layout);
            binding.timer = timer;
            binding.icon = (ImageView) ModuleMain.findField(helperClass, "mIcon").get(helper);
            binding.sparkle = new VolumeReduceIconDrawable(context, reduceActive);

            View button = (View) ModuleMain.findField(helperClass, "mBlurView").get(helper);
            View label = (View) ModuleMain.findField(helperClass, "mStandardView").get(helper);
            binding.buttonParts = new View[]{button, label, binding.icon};
            binding.moonButtonParts = new View[]{moonColumn.findViewById(button.getId()),
                    moonColumn.findViewById(label.getId()), moonColumn.findViewById(binding.icon.getId())};
            label.setContentDescription("减少干扰");
            label.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                    info.setCheckable(true);
                    info.setChecked(reduceActive);
                }
            });
            button.setOnClickListener(view -> {
                invoke(binding.layout, "performHapticFeedback");
                requestMode(view.getContext(), reduceActive ? ReduceInterruptions.MODE_OFF
                        : ReduceInterruptions.MODE_REDUCE, -1);
            });

            View host = layout.findViewById(RID_HOST_FRAME);
            ViewGroup.LayoutParams hostParams = host.getLayoutParams();
            hostParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            host.setLayoutParams(hostParams);
            bindings.put(layout, new WeakReference<>(binding));
            moonHelpers.add(binding.moonHelper);
            reduceTimers.add(timer);
            row.addView(binding.divider, new LinearLayout.LayoutParams(
                    (LinearLayout.LayoutParams) nativeDivider.getLayoutParams()));
            row.addView(column);
            column.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    invoke(timer, "updateShowingState", true);
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    invoke(timer, "updateShowingState", false);
                }
            });
            if (column.isAttachedToWindow()) invoke(timer, "updateShowingState", true);
            updateButtonsAndTimer(binding);
            Log.i(TAG, "Reduce Interruptions row attached");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "Volume panel attach failed", e);
        }
    }

    private static void applyExpanded(Binding binding, boolean expanded, boolean force) {
        binding.expanded = expanded;
        binding.column.setOrientation(binding.moonColumn.getOrientation());
        invoke(binding.timer, "updateExpanded", expanded);
        invoke(binding.helper, "onExpanded", expanded, force);
        updateButtonsAndTimer(binding);
    }

    private static void updateButtonsAndTimer(Binding binding) {
        invoke(binding.helper, "setRingerMode", reduceActive);
        invoke(binding.helper, "updateState");
        if (binding.icon.getDrawable() != binding.sparkle) binding.icon.setImageDrawable(binding.sparkle);
        binding.sparkle.setActive(reduceActive, binding.icon.isAttachedToWindow() && binding.icon.isShown());
        binding.moonColumn.setAlpha(reduceActive ? MOON_DIM_ALPHA : 1f);
        binding.moonSeekBar.setEnabled(!reduceActive);
        invoke(binding.moonHelper, "updateState");
        invoke(binding.timer, "updateRemainTimeH", false, 0);
        invoke(binding.timer, "setupCountDownProgress");
        invoke(binding.timer, "updateCountProgressH", reduceActive);
    }

    private static void invoke(Object target, String name, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) types[i] = args[i] instanceof Boolean ? boolean.class : int.class;
            ModuleMain.findMethod(target.getClass(), name, types).invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Volume panel " + name + " failed", e);
        }
    }

    private static void requestMode(Context context, int mode, int minutes) {
        Intent intent = new Intent(ReduceInterruptions.ACTION_REQUEST_MODE)
                .setPackage("android")
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(ReduceInterruptions.EXTRA_MODE, mode);
        if (minutes >= 0) intent.putExtra(ReduceInterruptions.EXTRA_DURATION_MINUTES, minutes);
        context.sendBroadcast(intent);
    }
}
