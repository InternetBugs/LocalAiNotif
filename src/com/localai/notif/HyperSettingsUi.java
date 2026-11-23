package com.localai.notif;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Checkable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Pure-View Xiaomi HyperOS design system helpers shared by LocalAiNotif settings pages.
 */
final class HyperSettingsUi {
    private static final PathInterpolator HYPER_EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);

    private HyperSettingsUi() {}

    static final class Palette {
        final boolean dark;
        final int background;
        final int cardBackground;
        final int cardPressed;
        final int dialogBackground;
        final int textPrimary;
        final int textSecondary;
        final int textTertiary;
        final int accent;
        final int accentPressed;
        final int accentSoftBg;
        final int accentSoftPressed;
        final int secondaryBtnBg;
        final int secondaryBtnPressed;
        final int statusReady;
        final int statusReadyBg;
        final int statusWarn;
        final int statusWarnBg;
        final int danger;
        final int dangerSoftBg;
        final int dangerSoftPressed;
        final int switchOffTrack;
        final int switchThumb;
        final int divider;

        final int previewStageBg;
        final int previewStageBorder;
        final int previewNormalCard;
        final int previewNormalIcon;
        final int previewSkeletonPrimary;
        final int previewSkeletonSecondary;
        final int previewNebulaCyan;
        final int previewNebulaAzure;
        final int previewNebulaViolet;
        final int previewNebulaMoon;
        final int previewNebulaCapsuleFill;
        final int previewNebulaCapsuleStroke;
        final int previewNebulaCard;
        final int previewNebulaBadgeText;
        final int previewNebulaPillBg;
        final int previewNebulaIconBg;
        final int previewNebulaLinePrimary;
        final int previewNebulaLineSecondary;

        private Palette(
                boolean dark,
                int background,
                int cardBackground,
                int cardPressed,
                int dialogBackground,
                int textPrimary,
                int textSecondary,
                int textTertiary,
                int accent,
                int accentPressed,
                int accentSoftBg,
                int accentSoftPressed,
                int secondaryBtnBg,
                int secondaryBtnPressed,
                int statusReady,
                int statusReadyBg,
                int statusWarn,
                int statusWarnBg,
                int danger,
                int dangerSoftBg,
                int dangerSoftPressed,
                int switchOffTrack,
                int switchThumb,
                int divider,
                int previewStageBg,
                int previewStageBorder,
                int previewNormalCard,
                int previewNormalIcon,
                int previewSkeletonPrimary,
                int previewSkeletonSecondary,
                int previewNebulaCyan,
                int previewNebulaAzure,
                int previewNebulaViolet,
                int previewNebulaMoon,
                int previewNebulaCapsuleFill,
                int previewNebulaCapsuleStroke,
                int previewNebulaCard,
                int previewNebulaBadgeText,
                int previewNebulaPillBg,
                int previewNebulaIconBg,
                int previewNebulaLinePrimary,
                int previewNebulaLineSecondary) {
            this.dark = dark;
            this.background = background;
            this.cardBackground = cardBackground;
            this.cardPressed = cardPressed;
            this.dialogBackground = dialogBackground;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textTertiary = textTertiary;
            this.accent = accent;
            this.accentPressed = accentPressed;
            this.accentSoftBg = accentSoftBg;
            this.accentSoftPressed = accentSoftPressed;
            this.secondaryBtnBg = secondaryBtnBg;
            this.secondaryBtnPressed = secondaryBtnPressed;
            this.statusReady = statusReady;
            this.statusReadyBg = statusReadyBg;
            this.statusWarn = statusWarn;
            this.statusWarnBg = statusWarnBg;
            this.danger = danger;
            this.dangerSoftBg = dangerSoftBg;
            this.dangerSoftPressed = dangerSoftPressed;
            this.switchOffTrack = switchOffTrack;
            this.switchThumb = switchThumb;
            this.divider = divider;
            this.previewStageBg = previewStageBg;
            this.previewStageBorder = previewStageBorder;
            this.previewNormalCard = previewNormalCard;
            this.previewNormalIcon = previewNormalIcon;
            this.previewSkeletonPrimary = previewSkeletonPrimary;
            this.previewSkeletonSecondary = previewSkeletonSecondary;
            this.previewNebulaCyan = previewNebulaCyan;
            this.previewNebulaAzure = previewNebulaAzure;
            this.previewNebulaViolet = previewNebulaViolet;
            this.previewNebulaMoon = previewNebulaMoon;
            this.previewNebulaCapsuleFill = previewNebulaCapsuleFill;
            this.previewNebulaCapsuleStroke = previewNebulaCapsuleStroke;
            this.previewNebulaCard = previewNebulaCard;
            this.previewNebulaBadgeText = previewNebulaBadgeText;
            this.previewNebulaPillBg = previewNebulaPillBg;
            this.previewNebulaIconBg = previewNebulaIconBg;
            this.previewNebulaLinePrimary = previewNebulaLinePrimary;
            this.previewNebulaLineSecondary = previewNebulaLineSecondary;
        }

        static Palette of(boolean dark) {
            if (dark) {
                return new Palette(
                        true,
                        0xFF000000, // HyperOS OLED black canvas
                        0xFF1F1F1F, // HyperOS elevated dark card
                        0xFF2B2B2B, // HyperOS dark pressed surface
                        0xFF242424, // HyperOS floating dark dialog
                        0xE6FFFFFF, // Primary ink (prevents OLED halation)
                        0x8CFFFFFF, // Secondary summary / footer ink
                        0x66FFFFFF, // Tertiary subtle ink
                        0xFF277AF7, // HyperOS dark calibrated accent blue
                        0xFF1E67D6, // Accent pressed
                        0x26277AF7, // Soft blue pill fill
                        0x3D277AF7, // Soft blue pill pressed
                        0xFF2E2E30, // Secondary pill button fill
                        0xFF3C3C3E, // Secondary pill button pressed
                        0xFF34C77B, // Status ready green
                        0x2134C77B, // Status ready pill bg
                        0xFFFFD484, // Status warning amber
                        0x26F4C15C, // Status warning pill bg
                        0xFFFF5C58, // Destructive red
                        0x24FF5C58, // Destructive soft bg
                        0x38FF5C58, // Destructive soft pressed
                        0xFF3E3E3E, // Switch OFF track
                        0xFFFFFFFF, // Switch thumb
                        0x1AFFFFFF, // Subtle hairline divider
                        0xFF121212, // Schematic stage fill
                        0x14FFFFFF, // Schematic stage hairline border
                        0xFF232323, // Ordinary notification card
                        0xFF343434, // Ordinary icon squircle
                        0xFF474747, // Ordinary skeleton title
                        0xFF343434, // Ordinary skeleton body
                        0xFF5CE1FF, // Nebula Sky Cyan
                        0xFF3D8BFF, // Nebula Azure Core
                        0xFFA17DFF, // Nebula Ice Violet
                        0xFFEDF6FF, // Nebula Moon White
                        0xC4192132, // Nebula smoked-glass micro-capsule fill
                        0x66C6E4FF, // Nebula micro-capsule hairline rim
                        0xFF1C2230, // Priority card cool tinted glass
                        0xFFDCEEFF, // Nebula label ink
                        0x2B5CE1FF, // Nebula soft pill background
                        0x2B5CE1FF, // Nebula icon soft badge
                        0xB0D8EEFF, // Priority skeleton primary
                        0x57BBD9FF  // Priority skeleton secondary
                );
            }
            return new Palette(
                    false,
                    0xFFF7F7F7, // HyperOS signature light gray canvas
                    0xFFFFFFFF, // Crisp flat white card
                    0xFFEDEDED, // HyperOS soft pressed surface
                    0xFFFFFFFF, // HyperOS floating light dialog
                    0xE6000000, // Primary ink
                    0x8C000000, // Secondary summary / footer ink
                    0x66000000, // Tertiary subtle ink
                    0xFF3482FF, // HyperOS official system blue
                    0xFF2370EB, // Accent pressed
                    0x1F3482FF, // Soft blue pill fill (crisp HyperOS light tint)
                    0x333482FF, // Soft blue pill pressed
                    0xFFF0F2F5, // Secondary pill button fill
                    0xFFE2E6EC, // Secondary pill button pressed
                    0xFF19A866, // Status ready green
                    0x1719A866, // Status ready pill bg
                    0xFFC9860D, // Status warning amber
                    0x1FF4C15C, // Status warning pill bg
                    0xFFE53935, // Destructive red
                    0x14E53935, // Destructive soft bg
                    0x24E53935, // Destructive soft pressed
                    0xFFE0E0E0, // Switch OFF track
                    0xFFFFFFFF, // Switch thumb
                    0x14000000, // Subtle hairline divider
                    0xFFF2F3F5, // Schematic stage fill
                    0x0D000000, // Schematic stage hairline border
                    0xFFFFFFFF, // Ordinary notification card
                    0xFFE5E7EB, // Ordinary icon squircle
                    0xFFD0D4DC, // Ordinary skeleton title
                    0xFFE7E9EE, // Ordinary skeleton body
                    0xFF1CA3EC, // Nebula Deep Sky Cyan
                    0xFF2B75F0, // Nebula HyperOS Azure
                    0xFF7458F2, // Nebula Crisp Ice Violet
                    0xFFD6E9FF, // Nebula Frosted Moon Highlight
                    0xEEFFFFFF, // Nebula frosted-glass micro-capsule fill
                    0x5C2B75F0, // Nebula micro-capsule hairline rim
                    0xFFFFFFFF, // Priority card crisp white surface
                    0xFF1F4ED8, // Nebula label ink
                    0x1A2B75F0, // Nebula soft pill background
                    0x1C2B75F0, // Nebula icon soft badge
                    0x80203A6E, // Priority skeleton primary
                    0x42203A6E  // Priority skeleton secondary
            );
        }
    }

    enum CapsuleStyle {
        PRIMARY_BLUE,
        SOFT_BLUE,
        SECONDARY_GRAY,
        SOFT_GREEN_TAG,
        SOFT_AMBER_TAG,
        DANGER_SOFT
    }

    static final class PageShell {
        final FrameLayout root;
        final ScrollView scrollView;
        final LinearLayout content;
        final TextView largeTitle;
        final TextView collapsedTitle;

        PageShell(
                FrameLayout root,
                ScrollView scrollView,
                LinearLayout content,
                TextView largeTitle,
                TextView collapsedTitle) {
            this.root = root;
            this.scrollView = scrollView;
            this.content = content;
            this.largeTitle = largeTitle;
            this.collapsedTitle = collapsedTitle;
        }
    }

    static boolean isNightMode(Context context) {
        int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    static boolean applyTheme(Activity activity) {
        boolean isDark = isNightMode(activity);
        activity.setTheme(isDark
                ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        return isDark;
    }

    @SuppressWarnings("deprecation")
    static void configureEdgeToEdge(Activity activity, Palette palette) {
        Window window = activity.getWindow();
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);

        View decor = window.getDecorView();
        decor.post(() -> {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) {
                return;
            }
            int lightFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(palette.dark ? 0 : lightFlags, lightFlags);
        });
    }

    /**
     * Builds the canonical HyperOS settings page chrome:
     * collapsing 32sp large title, 56dp sticky bar (with optional back button for subpages),
     * 560dp responsive max-width centering, and full Edge-to-Edge WindowInsets padding.
     */
    static PageShell buildPageShell(
            Activity activity,
            Palette palette,
            String title,
            boolean showBackButton) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(palette.background);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);

        FrameLayout maxWidthWrapper = new FrameLayout(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);

        boolean landscape = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int largeTitleTopDp;
        if (showBackButton) {
            largeTitleTopDp = landscape ? 60 : 66;
        } else {
            largeTitleTopDp = landscape ? 16 : 44;
        }

        TextView largeTitle = new TextView(activity);
        largeTitle.setText(title);
        largeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
        largeTitle.setTypeface(typeface(500));
        largeTitle.setTextColor(palette.textPrimary);
        largeTitle.setLetterSpacing(-0.01f);
        largeTitle.setIncludeFontPadding(false);
        LinearLayout.LayoutParams largeTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        largeTitleLp.setMargins(dp(activity, 28), dp(activity, largeTitleTopDp),
                dp(activity, 28), dp(activity, 20));
        content.addView(largeTitle, largeTitleLp);

        int screenWidthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int maxContentWidthPx = dp(activity, 560);
        int contentWidth = screenWidthPx > maxContentWidthPx
                ? maxContentWidthPx
                : ViewGroup.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        maxWidthWrapper.addView(content, contentLp);
        scrollView.addView(maxWidthWrapper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Sticky HyperOS top bar
        LinearLayout stickyTopBar = new LinearLayout(activity);
        stickyTopBar.setOrientation(LinearLayout.VERTICAL);
        stickyTopBar.setBackgroundColor(palette.background);

        FrameLayout topBarContainer = new FrameLayout(activity);
        FrameLayout topBarSlot = new FrameLayout(activity);

        if (showBackButton) {
            BackArrowView backBtn = new BackArrowView(activity, palette);
            backBtn.setContentDescription("返回");
            backBtn.setOnClickListener(v -> activity.finish());
            FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(
                    dp(activity, 44), dp(activity, 44),
                    Gravity.START | Gravity.CENTER_VERTICAL);
            backLp.setMarginStart(dp(activity, 12));
            topBarSlot.addView(backBtn, backLp);
        }

        TextView collapsedTitle = new TextView(activity);
        collapsedTitle.setText(title);
        collapsedTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        collapsedTitle.setTypeface(typeface(500));
        collapsedTitle.setTextColor(palette.textPrimary);
        collapsedTitle.setAlpha(0f);
        collapsedTitle.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams collapsedLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        collapsedLp.setMarginStart(dp(activity, showBackButton ? 60 : 28));
        topBarSlot.addView(collapsedTitle, collapsedLp);

        topBarContainer.addView(topBarSlot, new FrameLayout.LayoutParams(
                contentWidth, dp(activity, 56), Gravity.CENTER_HORIZONTAL));
        stickyTopBar.addView(topBarContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56)));

        View topBarDivider = new View(activity);
        topBarDivider.setBackgroundColor(palette.divider);
        topBarDivider.setAlpha(0f);
        stickyTopBar.addView(topBarDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(activity, 0.5f))));

        if (!showBackButton) {
            stickyTopBar.setAlpha(0f);
            stickyTopBar.setVisibility(View.INVISIBLE);
        }

        root.addView(stickyTopBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        int fadeStart = showBackButton ? dp(activity, 12) : dp(activity, largeTitleTopDp);
        int fadeEnd = fadeStart + dp(activity, 36);
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            float progress = Math.max(0f, Math.min(1f,
                    (scrollY - fadeStart) / (float) Math.max(1, fadeEnd - fadeStart)));
            if (!showBackButton) {
                stickyTopBar.setVisibility(progress > 0.001f ? View.VISIBLE : View.INVISIBLE);
                stickyTopBar.setAlpha(progress);
            }
            collapsedTitle.setVisibility(progress > 0.001f ? View.VISIBLE : View.INVISIBLE);
            collapsedTitle.setAlpha(progress);
            topBarDivider.setAlpha(progress);
            largeTitle.setAlpha(1f - progress * 0.85f);
        });

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scrollView.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            stickyTopBar.setPadding(bars.left, bars.top, bars.right, 0);
            return insets;
        });

        return new PageShell(root, scrollView, content, largeTitle, collapsedTitle);
    }

    static TextView createSmallTitle(Context context, Palette palette, String text, int topMarginDp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setTypeface(typeface(500));
        view.setTextColor(palette.textSecondary);
        view.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(context, 28), dp(context, topMarginDp), dp(context, 28), dp(context, 8));
        view.setLayoutParams(lp);
        return view;
    }

    static TextView createFooter(Context context, Palette palette, String text) {
        TextView footer = new TextView(context);
        footer.setText(text);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        footer.setTypeface(typeface(400));
        footer.setTextColor(palette.textSecondary);
        footer.setLineSpacing(dp(context, 3), 1.0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(context, 28), dp(context, 14), dp(context, 28), dp(context, 28));
        footer.setLayoutParams(lp);
        return footer;
    }

    static LinearLayout createGroupCard(Context context, Palette palette, int bottomMarginDp) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createCardBackground(context, palette, 18f, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(context, 12), 0, dp(context, 12), dp(context, bottomMarginDp));
        card.setLayoutParams(lp);
        return card;
    }

    static View createInsetDivider(Context context, Palette palette) {
        View divider = new View(context);
        divider.setBackgroundColor(palette.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 0.5f)));
        lp.setMargins(dp(context, 16), 0, dp(context, 16), 0);
        divider.setLayoutParams(lp);
        return divider;
    }

    static Typeface typeface(int weight) {
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    static float dpF(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    static int dp(Context context, float value) {
        return Math.round(dpF(context, value));
    }

    static Drawable createCardBackground(Context context, Palette palette, float radiusDp, boolean interactive) {
        return createRowBackground(context, palette, radiusDp, true, true, interactive);
    }

    static Drawable createRowBackground(
            Context context,
            Palette palette,
            float radiusDp,
            boolean roundTop,
            boolean roundBottom,
            boolean interactive) {
        float topR = roundTop ? dpF(context, radiusDp) : 0f;
        float bottomR = roundBottom ? dpF(context, radiusDp) : 0f;
        SquircleDrawable normal = new SquircleDrawable(palette.cardBackground, topR, bottomR, 0, 0f);
        if (!interactive) {
            return normal;
        }
        SquircleDrawable pressed = new SquircleDrawable(palette.cardPressed, topR, bottomR, 0, 0f);
        return makePressStates(normal, pressed);
    }

    static Drawable createDialogRowBackground(
            Context context,
            Palette palette,
            float radiusDp,
            boolean roundTop,
            boolean roundBottom) {
        float topR = roundTop ? dpF(context, radiusDp) : 0f;
        float bottomR = roundBottom ? dpF(context, radiusDp) : 0f;
        SquircleDrawable normal = new SquircleDrawable(Color.TRANSPARENT, topR, bottomR, 0, 0f);
        SquircleDrawable pressed = new SquircleDrawable(palette.cardPressed, topR, bottomR, 0, 0f);
        return makePressStates(normal, pressed);
    }

    static Drawable createPillDrawable(Context context, int fillColor, int pressedColor, float radiusDp, boolean interactive) {
        float r = dpF(context, radiusDp);
        SquircleDrawable normal = new SquircleDrawable(fillColor, r, r, 0, 0f);
        if (!interactive) {
            return normal;
        }
        SquircleDrawable pressed = new SquircleDrawable(pressedColor, r, r, 0, 0f);
        return makePressStates(normal, pressed);
    }

    private static StateListDrawable makePressStates(Drawable normal, Drawable pressed) {
        StateListDrawable states = new StateListDrawable();
        states.setExitFadeDuration(120);
        states.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, pressed);
        states.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_enabled}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    static void buildSquirclePath(RectF rect, float radius, Path out) {
        buildSquirclePath(rect, radius, radius, out);
    }

    static void buildSquirclePath(RectF rect, float topRadius, float bottomRadius, Path out) {
        out.reset();
        float width = rect.width();
        float height = rect.height();
        if (width <= 0f || height <= 0f) {
            return;
        }
        float halfMin = Math.min(width, height) * 0.5f;
        float dt = Math.max(0f, Math.min(topRadius * 1.20f, halfMin));
        float db = Math.max(0f, Math.min(bottomRadius * 1.20f, halfMin));
        float k = 0.30f;
        float ct = dt * k;
        float cb = db * k;
        float l = rect.left;
        float t = rect.top;
        float r = rect.right;
        float b = rect.bottom;

        out.moveTo(l + dt, t);
        out.lineTo(r - dt, t);
        if (dt > 0f) {
            out.cubicTo(r - ct, t, r, t + ct, r, t + dt);
        }
        out.lineTo(r, b - db);
        if (db > 0f) {
            out.cubicTo(r, b - cb, r - cb, b, r - db, b);
        }
        out.lineTo(l + db, b);
        if (db > 0f) {
            out.cubicTo(l + cb, b, l, b - cb, l, b - db);
        }
        out.lineTo(l, t + dt);
        if (dt > 0f) {
            out.cubicTo(l, t + ct, l + ct, t, l + dt, t);
        }
        out.close();
    }

    static int blendColor(int from, int to, float fraction) {
        float t = Math.max(0f, Math.min(1f, fraction));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    static int withAlpha(int color, float alphaScale) {
        int alpha = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alphaScale)));
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static final class SwitchRowParts {
        final LinearLayout row;
        final TextView title;
        final TextView summary;
        final HyperSwitchView toggle;

        SwitchRowParts(LinearLayout row, TextView title, TextView summary, HyperSwitchView toggle) {
            this.row = row;
            this.title = title;
            this.summary = summary;
            this.toggle = toggle;
        }
    }

    static SwitchRowParts createSwitchRow(
            Context context,
            Palette palette,
            String titleText,
            String summaryText,
            boolean initialChecked,
            boolean roundTop,
            boolean roundBottom) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(createRowBackground(context, palette, 18f, roundTop, roundBottom, true));
        row.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        row.setMinimumHeight(dp(context, 72));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(typeface(500));
        title.setTextColor(palette.textPrimary);
        title.setIncludeFontPadding(false);
        textCol.addView(title);

        TextView summary = new TextView(context);
        summary.setText(summaryText);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        summary.setTypeface(typeface(400));
        summary.setTextColor(palette.textSecondary);
        summary.setLineSpacing(dp(context, 3), 1.0f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(context, 5);
        textCol.addView(summary, summaryLp);

        LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textColLp.setMarginEnd(dp(context, 16));
        row.addView(textCol, textColLp);

        HyperSwitchView toggle = new HyperSwitchView(context, palette);
        toggle.setChecked(initialChecked, false);
        toggle.setDuplicateParentStateEnabled(true);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            @SuppressWarnings("deprecation")
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(toggle.isChecked());
            }
        });

        return new SwitchRowParts(row, title, summary, toggle);
    }

    static final class NavRowParts {
        final LinearLayout row;
        final TextView title;
        final TextView summary;
        final StatusDotView statusDot;
        final TextView value;
        final ChevronView chevron;

        NavRowParts(
                LinearLayout row,
                TextView title,
                TextView summary,
                StatusDotView statusDot,
                TextView value,
                ChevronView chevron) {
            this.row = row;
            this.title = title;
            this.summary = summary;
            this.statusDot = statusDot;
            this.value = value;
            this.chevron = chevron;
        }
    }

    static NavRowParts createNavRow(
            Context context,
            Palette palette,
            String titleText,
            String summaryText,
            String valueText,
            boolean withStatusDot,
            boolean roundTop,
            boolean roundBottom) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(createRowBackground(context, palette, 18f, roundTop, roundBottom, true));
        row.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        row.setMinimumHeight(dp(context, summaryText != null && !summaryText.isEmpty() ? 72 : 58));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(typeface(500));
        title.setTextColor(palette.textPrimary);
        title.setIncludeFontPadding(false);
        textCol.addView(title);

        TextView summary = new TextView(context);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        summary.setTypeface(typeface(400));
        summary.setTextColor(palette.textSecondary);
        summary.setLineSpacing(dp(context, 3), 1.0f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(context, 5);
        if (summaryText != null && !summaryText.isEmpty()) {
            summary.setText(summaryText);
        } else {
            summary.setVisibility(View.GONE);
        }
        textCol.addView(summary, summaryLp);

        LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textColLp.setMarginEnd(dp(context, 12));
        row.addView(textCol, textColLp);

        StatusDotView dot = null;
        if (withStatusDot) {
            dot = new StatusDotView(context, palette.statusWarn);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    dp(context, 7), dp(context, 7));
            dotLp.setMarginEnd(dp(context, 6));
            row.addView(dot, dotLp);
        }

        TextView value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        value.setTypeface(typeface(400));
        value.setTextColor(palette.textSecondary);
        value.setIncludeFontPadding(false);
        if (valueText != null && !valueText.isEmpty()) {
            value.setText(valueText);
        } else {
            value.setVisibility(View.GONE);
        }
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.setMarginEnd(dp(context, 6));
        row.addView(value, valueLp);

        ChevronView chevron = new ChevronView(context, palette.textTertiary);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(context, 16), dp(context, 16)));

        return new NavRowParts(row, title, summary, dot, value, chevron);
    }

    static final class ChoiceItem {
        final String label;
        final String subtitle;

        ChoiceItem(String label, String subtitle) {
            this.label = label;
            this.subtitle = subtitle;
        }
    }

    interface IntSelectionCallback {
        void onSelected(int index);
    }

    /**
     * HyperOS floating bottom-anchored single-choice sheet dialog.
     */
    static Dialog showChoiceDialog(
            Activity activity,
            Palette palette,
            String titleText,
            List<ChoiceItem> items,
            int selectedIndex,
            IntSelectionCallback callback) {
        Dialog dialog = createBottomSheetDialog(activity, palette);
        LinearLayout sheet = createDialogCard(activity, palette);

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(typeface(500));
        title.setTextColor(palette.textPrimary);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 14));
        sheet.addView(title, titleLp);

        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sheet.addView(createInsetDivider(activity, palette));
            }
            final int index = i;
            ChoiceItem item = items.get(i);
            boolean selected = i == selectedIndex;

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(createDialogRowBackground(activity, palette, 12f, false, false));
            row.setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 14));
            row.setMinimumHeight(dp(activity, item.subtitle != null ? 62 : 52));
            row.setClickable(true);
            row.setFocusable(true);

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView label = new TextView(activity);
            label.setText(item.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            label.setTypeface(typeface(selected ? 500 : 400));
            label.setTextColor(selected ? palette.accent : palette.textPrimary);
            label.setIncludeFontPadding(false);
            col.addView(label);

            if (item.subtitle != null && !item.subtitle.isEmpty()) {
                TextView sub = new TextView(activity);
                sub.setText(item.subtitle);
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                sub.setTypeface(typeface(400));
                sub.setTextColor(palette.textSecondary);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                subLp.topMargin = dp(activity, 4);
                col.addView(sub, subLp);
            }

            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.setMarginEnd(dp(activity, 12));
            row.addView(col, colLp);

            CheckMarkView check = new CheckMarkView(activity, palette.accent);
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            row.addView(check, new LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18)));

            row.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                dialog.dismiss();
                if (callback != null) callback.onSelected(index);
            });
            sheet.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView cancelBtn = createSheetButton(activity, palette, "取消", CapsuleStyle.SECONDARY_GRAY);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46));
        cancelLp.setMargins(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 20));
        sheet.addView(cancelBtn, cancelLp);

        attachBottomSheetContent(activity, dialog, sheet);
        dialog.show();
        return dialog;
    }

    /**
     * HyperOS floating bottom-anchored confirmation dialog.
     */
    static Dialog showConfirmDialog(
            Activity activity,
            Palette palette,
            String titleText,
            String messageText,
            String confirmText,
            boolean destructive,
            Runnable onConfirm) {
        Dialog dialog = createBottomSheetDialog(activity, palette);
        LinearLayout sheet = createDialogCard(activity, palette);

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f);
        title.setTypeface(typeface(500));
        title.setTextColor(palette.textPrimary);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(dp(activity, 22), dp(activity, 22), dp(activity, 22), dp(activity, 10));
        sheet.addView(title, titleLp);

        TextView msg = new TextView(activity);
        msg.setText(messageText);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        msg.setTypeface(typeface(400));
        msg.setTextColor(palette.textSecondary);
        msg.setLineSpacing(dp(activity, 3), 1.0f);
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(dp(activity, 24), 0, dp(activity, 24), dp(activity, 20));
        sheet.addView(msg, msgLp);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancelBtn = createSheetButton(activity, palette, "取消", CapsuleStyle.SECONDARY_GRAY);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, dp(activity, 46), 1f);
        leftLp.setMarginEnd(dp(activity, 10));
        btnRow.addView(cancelBtn, leftLp);

        TextView confirmBtn = createSheetButton(
                activity, palette, confirmText,
                destructive ? CapsuleStyle.DANGER_SOFT : CapsuleStyle.PRIMARY_BLUE);
        confirmBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, dp(activity, 46), 1f);
        btnRow.addView(confirmBtn, rightLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(dp(activity, 20), 0, dp(activity, 20), dp(activity, 20));
        sheet.addView(btnRow, rowLp);

        attachBottomSheetContent(activity, dialog, sheet);
        dialog.show();
        return dialog;
    }

    static void setPathText(TextView view, String path) {
        view.setSingleLine(false);
        view.setHorizontallyScrolling(false);
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        view.setText(path.replace("/", "/\u200B").replace("\\", "\\\u200B"));
        view.setContentDescription(path);
    }

    static Dialog createBottomSheetDialog(Activity activity, Palette palette) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    static LinearLayout createDialogCard(Context context, Palette palette) {
        LinearLayout sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(new SquircleDrawable(
                palette.dialogBackground, dpF(context, 22f), dpF(context, 22f), 0, 0f));
        return sheet;
    }

    static void attachBottomSheetContent(Activity activity, Dialog dialog, View sheetCard) {
        FrameLayout wrapper = new FrameLayout(activity);
        int padH = dp(activity, 12);
        int padB = dp(activity, 12);
        wrapper.setPadding(padH, 0, padH, padB);
        wrapper.addView(sheetCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(wrapper);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int maxW = dp(activity, 520);
            window.setLayout(Math.min(screenW, maxW), ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(isNightMode(activity) ? 0.65f : 0.36f);
        }
    }

    static TextView createSheetButton(Context context, Palette palette, String text, CapsuleStyle style) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btn.setTypeface(typeface(500));
        btn.setIncludeFontPadding(false);
        btn.setClickable(true);
        btn.setFocusable(true);
        applyCapsuleStyle(context, palette, btn, style, 23f);
        return btn;
    }

    static TextView createCapsuleButton(Context context, Palette palette, String text, CapsuleStyle style) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btn.setTypeface(typeface(500));
        btn.setIncludeFontPadding(false);
        btn.setMinWidth(dp(context, 68));
        btn.setMinimumHeight(dp(context, 32));
        btn.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        applyCapsuleStyle(context, palette, btn, style, 16f);
        return btn;
    }

    static void applyCapsuleStyle(
            Context context,
            Palette palette,
            TextView view,
            CapsuleStyle style,
            float radiusDp) {
        int bg;
        int pressed;
        int fg;
        boolean interactive;
        switch (style) {
            case PRIMARY_BLUE:
                bg = palette.accent;
                pressed = palette.accentPressed;
                fg = Color.WHITE;
                interactive = true;
                break;
            case SOFT_BLUE:
                bg = palette.accentSoftBg;
                pressed = palette.accentSoftPressed;
                fg = palette.accent;
                interactive = true;
                break;
            case SOFT_GREEN_TAG:
                bg = palette.statusReadyBg;
                pressed = palette.statusReadyBg;
                fg = palette.statusReady;
                interactive = false;
                break;
            case SOFT_AMBER_TAG:
                bg = palette.statusWarnBg;
                pressed = palette.statusWarnBg;
                fg = palette.statusWarn;
                interactive = false;
                break;
            case DANGER_SOFT:
                bg = palette.dangerSoftBg;
                pressed = palette.dangerSoftPressed;
                fg = palette.danger;
                interactive = true;
                break;
            case SECONDARY_GRAY:
            default:
                bg = palette.secondaryBtnBg;
                pressed = palette.secondaryBtnPressed;
                fg = palette.textPrimary;
                interactive = true;
                break;
        }
        view.setTextColor(fg);
        String styleKey = style.name() + ":" + radiusDp;
        if (!styleKey.equals(view.getTag())) {
            view.setTag(styleKey);
            view.setBackground(createPillDrawable(context, bg, pressed, radiusDp, interactive));
        }
    }

    static TextView createInlineBadge(Context context, Palette palette, String text, boolean accent) {
        TextView badge = new TextView(context);
        badge.setText(text);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        badge.setTypeface(typeface(500));
        badge.setIncludeFontPadding(false);
        badge.setTextColor(accent ? palette.accent : palette.statusReady);
        badge.setPadding(dp(context, 7), dp(context, 2.5f), dp(context, 7), dp(context, 2.5f));
        badge.setBackground(createPillDrawable(
                context,
                accent ? palette.accentSoftBg : palette.statusReadyBg,
                accent ? palette.accentSoftBg : palette.statusReadyBg,
                7f,
                false));
        return badge;
    }

    static final class SquircleDrawable extends Drawable {
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mBoundsF = new RectF();
        private final int mBaseFillColor;
        private final int mBaseStrokeColor;
        private final float mTopRadius;
        private final float mBottomRadius;
        private final float mStrokeWidth;
        private int mDrawableAlpha = 255;

        SquircleDrawable(int fillColor, float radiusPx, int strokeColor, float strokeWidthPx) {
            this(fillColor, radiusPx, radiusPx, strokeColor, strokeWidthPx);
        }

        SquircleDrawable(
                int fillColor,
                float topRadiusPx,
                float bottomRadiusPx,
                int strokeColor,
                float strokeWidthPx) {
            mBaseFillColor = fillColor;
            mBaseStrokeColor = strokeColor;
            mTopRadius = topRadiusPx;
            mBottomRadius = bottomRadiusPx;
            mStrokeWidth = strokeWidthPx;
            mFillPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeWidth(strokeWidthPx);
            applyEffectiveAlpha();
        }

        private void applyEffectiveAlpha() {
            int fillA = (Color.alpha(mBaseFillColor) * mDrawableAlpha) / 255;
            mFillPaint.setColor(Color.argb(
                    fillA,
                    Color.red(mBaseFillColor),
                    Color.green(mBaseFillColor),
                    Color.blue(mBaseFillColor)));
            int strokeA = (Color.alpha(mBaseStrokeColor) * mDrawableAlpha) / 255;
            mStrokePaint.setColor(Color.argb(
                    strokeA,
                    Color.red(mBaseStrokeColor),
                    Color.green(mBaseStrokeColor),
                    Color.blue(mBaseStrokeColor)));
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            float inset = mStrokeWidth * 0.5f;
            mBoundsF.set(
                    bounds.left + inset,
                    bounds.top + inset,
                    bounds.right - inset,
                    bounds.bottom - inset);
            buildSquirclePath(mBoundsF, mTopRadius, mBottomRadius, mPath);
        }

        @Override
        public void draw(Canvas canvas) {
            if (!mPath.isEmpty()) {
                if (Color.alpha(mFillPaint.getColor()) > 0) {
                    canvas.drawPath(mPath, mFillPaint);
                }
                if (mStrokeWidth > 0f && Color.alpha(mStrokePaint.getColor()) > 0) {
                    canvas.drawPath(mPath, mStrokePaint);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mDrawableAlpha = Math.max(0, Math.min(255, alpha));
            applyEffectiveAlpha();
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mFillPaint.setColorFilter(colorFilter);
            mStrokePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static final class ChevronView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        ChevronView(Context context, int color) {
            super(context);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(color);
            mPaint.setStrokeWidth(dpF(context, 1.6f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float cx = w * 0.52f;
            float cy = h * 0.5f;
            float dx = dpF(getContext(), 2.8f);
            float dy = dpF(getContext(), 4.6f);
            mPath.reset();
            mPath.moveTo(cx - dx, cy - dy);
            mPath.lineTo(cx + dx, cy);
            mPath.lineTo(cx - dx, cy + dy);
            canvas.drawPath(mPath, mPaint);
        }
    }

    static final class BackArrowView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        BackArrowView(Context context, Palette palette) {
            super(context);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(palette.textPrimary);
            mPaint.setStrokeWidth(dpF(context, 2.0f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
            setBackground(createPillDrawable(context, Color.TRANSPARENT, palette.cardPressed, 22f, true));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float cy = h * 0.5f;
            float left = w * 0.34f;
            float right = w * 0.68f;
            float head = dpF(getContext(), 6.2f);
            mPath.reset();
            mPath.moveTo(left + head, cy - head);
            mPath.lineTo(left, cy);
            mPath.lineTo(left + head, cy + head);
            mPath.moveTo(left + dpF(getContext(), 0.8f), cy);
            mPath.lineTo(right, cy);
            canvas.drawPath(mPath, mPaint);
        }
    }

    static final class CheckMarkView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        CheckMarkView(Context context, int color) {
            super(context);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(color);
            mPaint.setStrokeWidth(dpF(context, 2.0f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            mPath.reset();
            mPath.moveTo(w * 0.16f, h * 0.52f);
            mPath.lineTo(w * 0.42f, h * 0.76f);
            mPath.lineTo(w * 0.86f, h * 0.26f);
            canvas.drawPath(mPath, mPaint);
        }
    }

    static final class StatusDotView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StatusDotView(Context context, int color) {
            super(context);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setColor(int color) {
            if (mPaint.getColor() != color) {
                mPaint.setColor(color);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) * 0.5f;
            if (r > 0f) {
                canvas.drawCircle(getWidth() * 0.5f, getHeight() * 0.5f, r, mPaint);
            }
        }
    }

    /**
     * Clean 4dp HyperOS capsule progress bar supporting determinate progress and indeterminate sweep.
     */
    static final class HyperProgressBar extends View {
        private final Palette mPalette;
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        private float mProgress;
        private boolean mIndeterminate;
        private float mSweepPhase;
        private ValueAnimator mIndeterminateAnimator;

        HyperProgressBar(Context context, Palette palette) {
            super(context);
            mPalette = palette;
            mTrackPaint.setStyle(Paint.Style.FILL);
            mTrackPaint.setColor(palette.switchOffTrack);
            mFillPaint.setStyle(Paint.Style.FILL);
            mFillPaint.setColor(palette.accent);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setState(int percent, boolean indeterminate, boolean muted) {
            mFillPaint.setColor(muted ? mPalette.textTertiary : mPalette.accent);
            mProgress = Math.max(0f, Math.min(1f, percent / 100f));
            if (mIndeterminate != indeterminate) {
                mIndeterminate = indeterminate;
                if (indeterminate) {
                    startIndeterminate();
                } else {
                    stopIndeterminate();
                }
            }
            invalidate();
        }

        private void startIndeterminate() {
            stopIndeterminate();
            if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
                mSweepPhase = 0.35f;
                return;
            }
            mIndeterminateAnimator = ValueAnimator.ofFloat(0f, 1f);
            mIndeterminateAnimator.setDuration(1150L);
            mIndeterminateAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mIndeterminateAnimator.setInterpolator(new LinearInterpolator());
            mIndeterminateAnimator.addUpdateListener(anim -> {
                mSweepPhase = (float) anim.getAnimatedValue();
                invalidate();
            });
            mIndeterminateAnimator.start();
        }

        private void stopIndeterminate() {
            if (mIndeterminateAnimator != null) {
                mIndeterminateAnimator.cancel();
                mIndeterminateAnimator = null;
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (mIndeterminate) {
                startIndeterminate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stopIndeterminate();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredH = dp(getContext(), 4.5f);
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, resolveSize(desiredH, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float r = h * 0.5f;

            mRect.set(0f, 0f, w, h);
            canvas.drawRoundRect(mRect, r, r, mTrackPaint);

            if (mIndeterminate) {
                float barW = w * 0.32f;
                float start = (w + barW) * mSweepPhase - barW;
                float clampedL = Math.max(0f, start);
                float clampedR = Math.min(w, start + barW);
                if (clampedR > clampedL) {
                    mRect.set(clampedL, 0f, clampedR, h);
                    canvas.drawRoundRect(mRect, r, r, mFillPaint);
                }
            } else if (mProgress > 0.002f) {
                float fillW = Math.max(h, w * mProgress);
                mRect.set(0f, 0f, Math.min(w, fillW), h);
                canvas.drawRoundRect(mRect, r, r, mFillPaint);
            }
        }
    }

    /**
     * Authentic HyperOS 50dp x 28dp capsule switch with spring-eased glide and tactile thumb stretch.
     */
    static final class HyperSwitchView extends View implements Checkable {
        private static final float WIDTH_DP = 50f;
        private static final float HEIGHT_DP = 28f;
        private static final float THUMB_DIAMETER_DP = 22f;
        private static final float THUMB_MARGIN_DP = 3f;
        private static final float PRESS_STRETCH_DP = 2.5f;

        private final Palette mPalette;
        private final ArgbEvaluator mArgbEvaluator = new ArgbEvaluator();
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mTrackRect = new RectF();
        private final RectF mThumbRect = new RectF();

        private boolean mChecked;
        private float mProgress;
        private float mPressFraction;
        private ValueAnimator mToggleAnimator;
        private ValueAnimator mPressAnimator;

        HyperSwitchView(Context context, Palette palette) {
            super(context);
            mPalette = palette;
            mTrackPaint.setStyle(Paint.Style.FILL);
            mThumbPaint.setStyle(Paint.Style.FILL);
            mThumbPaint.setColor(palette.switchThumb);
            mShadowPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = dp(getContext(), WIDTH_DP);
            int h = dp(getContext(), HEIGHT_DP);
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
        }

        @Override
        public void setChecked(boolean checked) {
            setChecked(checked, false);
        }

        void setChecked(boolean checked, boolean animate) {
            boolean changed = mChecked != checked;
            mChecked = checked;
            float target = checked ? 1f : 0f;
            if (!animate || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
                cancelToggleAnimator();
                mProgress = target;
                invalidate();
                return;
            }
            if (!changed && (mProgress == target
                    || (mToggleAnimator != null && mToggleAnimator.isRunning()))) {
                return;
            }
            cancelToggleAnimator();
            mToggleAnimator = ValueAnimator.ofFloat(mProgress, target);
            mToggleAnimator.setDuration(220L);
            mToggleAnimator.setInterpolator(HYPER_EASE);
            mToggleAnimator.addUpdateListener(anim -> {
                mProgress = (float) anim.getAnimatedValue();
                invalidate();
            });
            mToggleAnimator.start();
        }

        @Override
        public boolean isChecked() {
            return mChecked;
        }

        @Override
        public void toggle() {
            setChecked(!mChecked, true);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1.0f : 0.45f);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            float target = (isPressed() && isEnabled()) ? 1f : 0f;
            if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
                cancelPressAnimator();
                if (mPressFraction != target) {
                    mPressFraction = target;
                    invalidate();
                }
                return;
            }
            if (Math.abs(mPressFraction - target) < 0.01f) {
                return;
            }
            cancelPressAnimator();
            mPressAnimator = ValueAnimator.ofFloat(mPressFraction, target);
            mPressAnimator.setDuration(target > 0f ? 90L : 140L);
            mPressAnimator.setInterpolator(HYPER_EASE);
            mPressAnimator.addUpdateListener(anim -> {
                mPressFraction = (float) anim.getAnimatedValue();
                invalidate();
            });
            mPressAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            if (width <= 0f || height <= 0f) {
                return;
            }

            int trackColor = (int) mArgbEvaluator.evaluate(
                    mProgress, mPalette.switchOffTrack, mPalette.accent);
            mTrackPaint.setColor(trackColor);
            mTrackRect.set(0f, 0f, width, height);
            float trackRadius = height * 0.5f;
            canvas.drawRoundRect(mTrackRect, trackRadius, trackRadius, mTrackPaint);

            float margin = dpF(getContext(), THUMB_MARGIN_DP);
            float baseDiameter = Math.min(dpF(getContext(), THUMB_DIAMETER_DP), height - margin * 2f);
            float stretch = dpF(getContext(), PRESS_STRETCH_DP) * mPressFraction;
            float thumbWidth = baseDiameter + stretch;
            float travel = Math.max(0f, width - margin * 2f - thumbWidth);
            float thumbLeft = margin + travel * mProgress;
            float thumbTop = (height - baseDiameter) * 0.5f;
            float thumbRadius = baseDiameter * 0.5f;

            float shadowOffsetY = dpF(getContext(), 0.8f);
            float shadowSpread = dpF(getContext(), 0.8f);
            mShadowPaint.setColor(mPalette.dark ? 0x33000000 : 0x1A000000);
            mThumbRect.set(
                    thumbLeft - shadowSpread * 0.5f,
                    thumbTop + shadowOffsetY,
                    thumbLeft + thumbWidth + shadowSpread * 0.5f,
                    thumbTop + baseDiameter + shadowOffsetY + shadowSpread * 0.5f);
            canvas.drawRoundRect(mThumbRect, thumbRadius, thumbRadius, mShadowPaint);

            mThumbRect.set(thumbLeft, thumbTop, thumbLeft + thumbWidth, thumbTop + baseDiameter);
            canvas.drawRoundRect(mThumbRect, thumbRadius, thumbRadius, mThumbPaint);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(mChecked);
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelToggleAnimator();
            cancelPressAnimator();
            super.onDetachedFromWindow();
        }

        private void cancelToggleAnimator() {
            if (mToggleAnimator != null) {
                mToggleAnimator.cancel();
                mToggleAnimator = null;
            }
        }

        private void cancelPressAnimator() {
            if (mPressAnimator != null) {
                mPressAnimator.cancel();
                mPressAnimator = null;
            }
        }
    }

    /**
     * Dedicated HyperOS illustration for "AI 摘要":
     * 3 separate notifications smoothly collapse into a clean Apple-Intelligence-minimal stack card
     * featuring the frameless Nebula Aurora dual-sparkle + "AI 摘要" inline prefix, subtle
     * Nebula-tinted summary lines, and a one-shot Wave Wash sweep when toggled ON.
     */
    static final class SummaryPreviewView extends View {
        private static final float STAGE_HEIGHT_DP = 166f;
        private static final float MAX_STAGE_WIDTH_DP = 320f;
        private static final String AI_SUMMARY_LABEL = NebulaSummaryVisuals.LABEL;
        private static final String COUNT_LABEL = "3 条";

        private final Palette mPalette;
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCountPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mStagePath = new Path();
        private final Path mCardPath = new Path();
        private final Path mIconPath = new Path();
        private final Path mSparklePath = new Path();
        private final RectF mRect = new RectF();
        private final Matrix mShaderMatrix = new Matrix();

        private boolean mSummaryEnabled;
        private float mProgress;
        private float mWashProgress;
        private float mWashBoost;
        private ValueAnimator mAnimator;
        private ValueAnimator mWashAnimator;

        SummaryPreviewView(Context context, Palette palette) {
            super(context);
            mPalette = palette;
            mFillPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mWavePaint.setStyle(Paint.Style.FILL);
            mBadgeTextPaint.setTypeface(typeface(600));
            mBadgeTextPaint.setTextSize(dpF(context, 9.2f));
            mBadgeTextPaint.setLetterSpacing(0.015f);
            mGlintTextPaint.setTypeface(typeface(600));
            mGlintTextPaint.setTextSize(dpF(context, 9.2f));
            mGlintTextPaint.setLetterSpacing(0.015f);
            mCountPaint.setTypeface(typeface(500));
            mCountPaint.setTextSize(dpF(context, 9.4f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setSummaryEnabled(boolean enabled, boolean animate) {
            boolean changed = mSummaryEnabled != enabled;
            mSummaryEnabled = enabled;
            float target = enabled ? 1f : 0f;
            if (!animate || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
                cancelAnimator();
                cancelWashAnimator();
                mProgress = target;
                invalidate();
                return;
            }
            if (!changed && (mProgress == target
                    || (mAnimator != null && mAnimator.isRunning()))) {
                return;
            }
            cancelAnimator();
            if (!enabled) {
                cancelWashAnimator();
            }
            mAnimator = ValueAnimator.ofFloat(mProgress, target);
            mAnimator.setDuration(280L);
            mAnimator.setInterpolator(HYPER_EASE);
            mAnimator.addUpdateListener(anim -> {
                mProgress = (float) anim.getAnimatedValue();
                invalidate();
            });
            mAnimator.start();
            if (enabled && changed) {
                startPreviewWash();
            }
        }

        private void startPreviewWash() {
            if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) return;
            cancelWashAnimator();
            mWashAnimator = ValueAnimator.ofFloat(0f, 1f);
            mWashAnimator.setDuration(780L);
            mWashAnimator.setInterpolator(HYPER_EASE);
            mWashAnimator.addUpdateListener(anim -> {
                mWashProgress = (float) anim.getAnimatedValue();
                mWashBoost = NebulaSummaryVisuals.smoothEnvelope(mWashProgress, 0.24f);
                invalidate();
            });
            mWashAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mWashProgress = 0f;
                    mWashBoost = 0f;
                    mWashAnimator = null;
                    invalidate();
                }
            });
            mWashAnimator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredH = dp(getContext(), STAGE_HEIGHT_DP);
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, resolveSize(desiredH, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float totalW = getWidth();
            float totalH = getHeight();
            if (totalW <= 0f || totalH <= 0f) return;

            float s = mProgress;
            float stageW = Math.min(totalW, dpF(getContext(), MAX_STAGE_WIDTH_DP));
            float stageLeft = (totalW - stageW) * 0.5f;
            float stageRight = stageLeft + stageW;

            // Soft HyperOS stage
            float stageRadius = dpF(getContext(), 14f);
            float strokeHalf = dpF(getContext(), 0.5f);
            mRect.set(stageLeft + strokeHalf, strokeHalf, stageRight - strokeHalf, totalH - strokeHalf);
            buildSquirclePath(mRect, stageRadius, mStagePath);
            mFillPaint.setShader(null);
            mFillPaint.setColor(mPalette.previewStageBg);
            canvas.drawPath(mStagePath, mFillPaint);
            mStrokePaint.setShader(null);
            mStrokePaint.setColor(mPalette.previewStageBorder);
            mStrokePaint.setStrokeWidth(dpF(getContext(), 1f));
            canvas.drawPath(mStagePath, mStrokePaint);

            float padH = dpF(getContext(), 16f);
            float left = stageLeft + padH;
            float right = stageRight - padH;
            float cardRadius = dpF(getContext(), 12f);

            // OFF state: 3 separate raw cards from same app
            float rawCardH = dpF(getContext(), 38f);
            float rawGap = dpF(getContext(), 8f);
            float rawTotalH = rawCardH * 3f + rawGap * 2f;
            float rawStartY = (totalH - rawTotalH) * 0.5f;

            // ON state: 1 clean Apple-minimal AI-summarized card with 2 folded depth cards underneath
            float mergedH = dpF(getContext(), 76f);
            float stackPeek1 = dpF(getContext(), 7f);
            float stackPeek2 = dpF(getContext(), 13f);
            float mergedTop = (totalH - (mergedH + stackPeek2)) * 0.5f;

            // Draw Card 3 (back-most folded layer when ON)
            float c3Inset = dpF(getContext(), 16f) * s;
            float c3Top = lerp(rawStartY + (rawCardH + rawGap) * 2f, mergedTop + mergedH - rawCardH + stackPeek2, s);
            float c3Bottom = c3Top + rawCardH;
            drawLayerCard(canvas, left + c3Inset, c3Top, right - c3Inset, c3Bottom,
                    cardRadius - dpF(getContext(), 2f) * s,
                    lerp(1f, mPalette.dark ? 0.42f : 0.60f, s),
                    1f - s, 0.36f, 0.62f);

            // Draw Card 2 (middle folded layer when ON)
            float c2Inset = dpF(getContext(), 8f) * s;
            float c2Top = lerp(rawStartY + rawCardH + rawGap, mergedTop + mergedH - rawCardH + stackPeek1, s);
            float c2Bottom = c2Top + rawCardH;
            drawLayerCard(canvas, left + c2Inset, c2Top, right - c2Inset, c2Bottom,
                    cardRadius - dpF(getContext(), 1f) * s,
                    lerp(1f, mPalette.dark ? 0.68f : 0.84f, s),
                    1f - s, 0.44f, 0.70f);

            // Draw Card 1 (primary card -> transitions cleanly into frameless Nebula AI Summary card)
            float c1Top = lerp(rawStartY, mergedTop, s);
            float c1H = lerp(rawCardH, mergedH, s);
            float c1Bottom = c1Top + c1H;
            drawPrimarySummaryCard(canvas, left, c1Top, right, c1Bottom, cardRadius, s);
        }

        private void drawLayerCard(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float cardAlpha,
                float contentAlpha,
                float line1Ratio,
                float line2Ratio) {
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);
            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(withAlpha(0x0A000000, cardAlpha));
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.75f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }
            mFillPaint.setColor(withAlpha(mPalette.previewNormalCard, cardAlpha));
            canvas.drawPath(mCardPath, mFillPaint);

            if (contentAlpha <= 0.04f) return;
            float cardH = bottom - top;
            float iconSize = dpF(getContext(), 20f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = top + (cardH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6f), mIconPath);
            mFillPaint.setColor(withAlpha(mPalette.previewNormalIcon, contentAlpha));
            canvas.drawPath(mIconPath, mFillPaint);

            float textLeft = iconLeft + iconSize + dpF(getContext(), 9f);
            float textMaxW = Math.max(0f, right - dpF(getContext(), 12f) - textLeft);
            float l1H = dpF(getContext(), 5f);
            float l2H = dpF(getContext(), 4.2f);
            float gap = dpF(getContext(), 5f);
            float linesTop = top + (cardH - (l1H + gap + l2H)) * 0.5f;

            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonPrimary, contentAlpha));
            mRect.set(textLeft, linesTop, textLeft + textMaxW * line1Ratio, linesTop + l1H);
            canvas.drawRoundRect(mRect, l1H * 0.5f, l1H * 0.5f, mFillPaint);

            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonSecondary, contentAlpha));
            mRect.set(textLeft, linesTop + l1H + gap, textLeft + textMaxW * line2Ratio, linesTop + l1H + gap + l2H);
            canvas.drawRoundRect(mRect, l2H * 0.5f, l2H * 0.5f, mFillPaint);
        }

        private void drawPrimarySummaryCard(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float s) {
            float cardH = bottom - top;
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);

            // Clean neutral card surface (Apple Intelligence minimal restraint: no colored card border)
            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(0x0D000000);
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 1f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }
            mFillPaint.setColor(mPalette.previewNormalCard);
            canvas.drawPath(mCardPath, mFillPaint);

            // Neutral app icon squircle (card chrome stays native and calm)
            float iconSize = lerp(dpF(getContext(), 20f), dpF(getContext(), 25f), s);
            float iconLeft = left + dpF(getContext(), 11f);
            float iconTop = lerp(top + (cardH - iconSize) * 0.5f, top + dpF(getContext(), 13f), s);
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 7.2f), mIconPath);
            mFillPaint.setColor(mPalette.previewNormalIcon);
            canvas.drawPath(mIconPath, mFillPaint);

            float textLeft = iconLeft + iconSize + dpF(getContext(), 10f);
            float textRight = right - dpF(getContext(), 12f);
            float textMaxW = Math.max(0f, textRight - textLeft);

            // Header row: native app title skeleton bar + subtle "3 条" count badge when ON
            float headerLineH = dpF(getContext(), 5.4f);
            float headerY = lerp(top + (cardH - dpF(getContext(), 14.2f)) * 0.5f, top + dpF(getContext(), 15f), s);
            mFillPaint.setColor(mPalette.previewSkeletonPrimary);
            mRect.set(textLeft, headerY, textLeft + textMaxW * lerp(0.42f, 0.38f, s), headerY + headerLineH);
            canvas.drawRoundRect(mRect, headerLineH * 0.5f, headerLineH * 0.5f, mFillPaint);

            if (s > 0.05f) {
                mCountPaint.setColor(withAlpha(mPalette.textTertiary, s));
                float cw = mCountPaint.measureText(COUNT_LABEL);
                Paint.FontMetrics cfm = mCountPaint.getFontMetrics();
                float cBase = (headerY + headerLineH * 0.5f) - (cfm.ascent + cfm.descent) * 0.5f;
                canvas.drawText(COUNT_LABEL, textRight - cw, cBase, mCountPaint);
            } else {
                float timeW = dpF(getContext(), 16f);
                float timeH = dpF(getContext(), 4f);
                float timeTop = headerY + (headerLineH - timeH) * 0.5f;
                mFillPaint.setColor(withAlpha(mPalette.previewSkeletonSecondary, 1f - s));
                mRect.set(textRight - timeW, timeTop, textRight, timeTop + timeH);
                canvas.drawRoundRect(mRect, timeH * 0.5f, timeH * 0.5f, mFillPaint);
            }

            // Summary line 1: inline frameless [✦✦ AI 摘要 | ] + subtle Nebula-tinted summary bar
            float sumLineH = dpF(getContext(), 4.8f);
            float sumLine1Y = lerp(headerY + headerLineH + dpF(getContext(), 5f), top + dpF(getContext(), 36f), s);
            float sumLine1MidY = sumLine1Y + sumLineH * 0.5f;
            float prefixAdvance = 0f;

            if (s > 0.05f) {
                float primaryR = dpF(getContext(), 3.6f) * (0.72f + 0.28f * s);
                float dualW = NebulaSummaryVisuals.drawDualSparkle(
                        canvas,
                        textLeft,
                        sumLine1MidY,
                        primaryR,
                        mPalette.dark,
                        s,
                        mWashBoost,
                        null,
                        mFillPaint,
                        mSparklePath);

                float labelX = textLeft + dualW + dpF(getContext(), 2.6f);
                float labelW = mBadgeTextPaint.measureText(AI_SUMMARY_LABEL);
                Paint.FontMetrics fm = mBadgeTextPaint.getFontMetrics();
                float labelBase = sumLine1MidY - (fm.ascent + fm.descent) * 0.5f;

                LinearGradient labelShader = NebulaSummaryVisuals.createHorizontalBadgeGradient(
                        labelX, labelX + labelW, mPalette.dark);
                mBadgeTextPaint.setShader(labelShader);
                mBadgeTextPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * s)));
                canvas.drawText(AI_SUMMARY_LABEL, labelX, labelBase, mBadgeTextPaint);
                mBadgeTextPaint.setShader(null);

                if (mWashBoost > 0.01f) {
                    float sheenW = Math.max(dpF(getContext(), 18f), labelW * 0.7f);
                    float sheenLeft = (textLeft - sheenW) + (dualW + labelW + sheenW * 2f) * mWashProgress;
                    LinearGradient sheen = NebulaSummaryVisuals.createGlintSheenGradient(sheenW, mPalette.dark);
                    mShaderMatrix.reset();
                    mShaderMatrix.setTranslate(sheenLeft, 0f);
                    sheen.setLocalMatrix(mShaderMatrix);
                    mGlintTextPaint.setShader(sheen);
                    mGlintTextPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * mWashBoost * s)));
                    canvas.drawText(AI_SUMMARY_LABEL, labelX, labelBase, mGlintTextPaint);
                    mGlintTextPaint.setShader(null);
                }

                // Feather-thin 0.75dp vertical fading Nebula hairline
                float hx = labelX + labelW + dpF(getContext(), 4.0f);
                float halfH = dpF(getContext(), 4.2f);
                mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 0.75f));
                mStrokePaint.setShader(NebulaSummaryVisuals.createHairlineGradient(
                        sumLine1MidY - halfH, sumLine1MidY + halfH, mPalette.dark));
                mStrokePaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * s)));
                canvas.drawLine(hx, sumLine1MidY - halfH, hx, sumLine1MidY + halfH, mStrokePaint);
                mStrokePaint.setShader(null);

                prefixAdvance = (hx - textLeft + dpF(getContext(), 4.4f)) * s;
            }

            int nebulaLine1 = NebulaSummaryVisuals.tintSummaryBodyColor(
                    mPalette.previewSkeletonPrimary, mPalette.dark);
            int nebulaLine2 = NebulaSummaryVisuals.tintSummaryBodyColor(
                    mPalette.previewSkeletonSecondary, mPalette.dark);

            mFillPaint.setShader(null);
            mFillPaint.setColor(blendColor(mPalette.previewSkeletonSecondary, nebulaLine1, s));
            float s1Start = textLeft + prefixAdvance;
            float s1End = Math.min(textRight, s1Start + Math.max(0f, textRight - s1Start) * lerp(0.68f, 0.94f, s));
            mRect.set(s1Start, sumLine1Y, s1End, sumLine1Y + sumLineH);
            canvas.drawRoundRect(mRect, sumLineH * 0.5f, sumLineH * 0.5f, mFillPaint);

            // Summary line 2 (fades in smoothly aligned to textLeft just like wrapped real TextView)
            float sumLine2Y = sumLine1Y + sumLineH + dpF(getContext(), 7.5f);
            if (s > 0.05f) {
                mFillPaint.setColor(withAlpha(nebulaLine2, s));
                mRect.set(textLeft, sumLine2Y, textLeft + textMaxW * 0.72f, sumLine2Y + sumLineH);
                canvas.drawRoundRect(mRect, sumLineH * 0.5f, sumLineH * 0.5f, mFillPaint);
            }

            // One-shot Nebula Wave Wash gliding across the summary lines when toggled ON
            if (s > 0.10f && mWashBoost > 0.01f) {
                float zoneTop = sumLine1Y - dpF(getContext(), 6f);
                float zoneBot = sumLine2Y + sumLineH + dpF(getContext(), 6f);
                float bandW = Math.max(dpF(getContext(), 44f), textMaxW * 0.38f);
                float waveX = (textLeft - bandW * 0.35f) + (textMaxW + bandW * 0.7f) * mWashProgress;
                Shader waveShader = NebulaSummaryVisuals.createFeatheredWaveShader(
                        bandW, zoneTop, zoneBot, mPalette.dark);
                NebulaSummaryVisuals.drawTiltedNebulaWave(
                        canvas, textLeft - dpF(getContext(), 2f), zoneTop, textRight + dpF(getContext(), 2f), zoneBot,
                        waveX, bandW, mWashBoost * s, mPalette.dark, waveShader, mShaderMatrix, mWavePaint);
            }
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelAnimator();
            cancelWashAnimator();
            super.onDetachedFromWindow();
        }

        private void cancelAnimator() {
            if (mAnimator != null) {
                mAnimator.cancel();
                mAnimator = null;
            }
        }

        private void cancelWashAnimator() {
            if (mWashAnimator != null) {
                mWashAnimator.cancel();
                mWashAnimator = null;
            }
            mWashProgress = 0f;
            mWashBoost = 0f;
        }
    }

    /**
     * Dedicated HyperOS settings illustration for "重要通知":
     * amber priority frame pinning important notifications above ordinary cards.
     */
    static final class PriorityPreviewView extends View {
        private static final float STAGE_HEIGHT_DP = 216f;
        private static final float MAX_STAGE_WIDTH_DP = 320f;
        private static final String PRIORITY_LABEL = "重要通知";
        private static final float[] RIM_STOPS = {0f, 0.24f, 0.48f, 0.72f, 1f};
        private static final float[] COMET_STOPS = {0f, 0.68f, 0.80f, 0.88f, 0.95f, 1f};
        private static final float[] AURA_WIDTHS_DP = {5.8f, 3.0f};
        private static final float[] AURA_ALPHAS_DARK = {0.060f, 0.115f};
        private static final float[] AURA_ALPHAS_LIGHT = {0.048f, 0.092f};

        private final Palette mPalette;
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mAuraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mStagePath = new Path();
        private final Path mFieldPath = new Path();
        private final Path mCardPath = new Path();
        private final Path mIconPath = new Path();
        private final Path mSparklePath = new Path();
        private final RectF mRect = new RectF();
        private final Matrix mShaderMatrix = new Matrix();

        private boolean mPriorityEnabled;
        private float mProgress;
        private float mShimmerProgress;
        private float mShimmerBoost;
        private ValueAnimator mAnimator;
        private ValueAnimator mShimmerAnimator;

        PriorityPreviewView(Context context, Palette palette) {
            super(context);
            mPalette = palette;
            mFillPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            mAuraPaint.setStyle(Paint.Style.STROKE);
            mAuraPaint.setStrokeJoin(Paint.Join.ROUND);
            mTextPaint.setTypeface(typeface(500));
            mTextPaint.setTextSize(dpF(context, 9.4f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setPriorityEnabled(boolean enabled, boolean animate) {
            boolean changed = mPriorityEnabled != enabled;
            mPriorityEnabled = enabled;
            float target = enabled ? 1f : 0f;
            if (!animate || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
                cancelAnimator();
                cancelShimmerAnimator();
                mProgress = target;
                invalidate();
                return;
            }
            if (!changed && (mProgress == target
                    || (mAnimator != null && mAnimator.isRunning()))) {
                return;
            }
            cancelAnimator();
            if (!enabled) {
                cancelShimmerAnimator();
            }
            mAnimator = ValueAnimator.ofFloat(mProgress, target);
            mAnimator.setDuration(280L);
            mAnimator.setInterpolator(HYPER_EASE);
            mAnimator.addUpdateListener(anim -> {
                mProgress = (float) anim.getAnimatedValue();
                invalidate();
            });
            mAnimator.start();
            if (enabled && changed) {
                startPreviewShimmer();
            }
        }

        private void startPreviewShimmer() {
            if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) return;
            cancelShimmerAnimator();
            mShimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
            mShimmerAnimator.setDuration(1250L);
            mShimmerAnimator.setInterpolator(HYPER_EASE);
            mShimmerAnimator.addUpdateListener(anim -> {
                mShimmerProgress = (float) anim.getAnimatedValue();
                mShimmerBoost = shimmerEnvelope(mShimmerProgress);
                invalidate();
            });
            mShimmerAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mShimmerProgress = 0f;
                    mShimmerBoost = 0f;
                    mShimmerAnimator = null;
                    invalidate();
                }
            });
            mShimmerAnimator.start();
        }

        private static float shimmerEnvelope(float t) {
            if (t <= 0f || t >= 1f) return 0f;
            float u = t < 0.24f ? (t / 0.24f) : ((1f - t) / 0.76f);
            return u * u * (3f - 2f * u);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredH = dp(getContext(), STAGE_HEIGHT_DP);
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, resolveSize(desiredH, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float totalW = getWidth();
            float totalH = getHeight();
            if (totalW <= 0f || totalH <= 0f) {
                return;
            }

            float p = mProgress;
            float stageW = Math.min(totalW, dpF(getContext(), MAX_STAGE_WIDTH_DP));
            float stageLeft = (totalW - stageW) * 0.5f;
            float stageRight = stageLeft + stageW;

            // Inner soft HyperOS schematic stage
            float stageRadius = dpF(getContext(), 14f);
            float strokeHalf = dpF(getContext(), 0.5f);
            mRect.set(stageLeft + strokeHalf, strokeHalf, stageRight - strokeHalf, totalH - strokeHalf);
            buildSquirclePath(mRect, stageRadius, mStagePath);
            mFillPaint.setShader(null);
            mFillPaint.setColor(mPalette.previewStageBg);
            canvas.drawPath(mStagePath, mFillPaint);
            mStrokePaint.setShader(null);
            mStrokePaint.setColor(mPalette.previewStageBorder);
            mStrokePaint.setStrokeWidth(dpF(getContext(), 1f));
            canvas.drawPath(mStagePath, mStrokePaint);

            float outerPadH = dpF(getContext(), 14f);
            float normalLeft = stageLeft + outerPadH;
            float normalRight = stageRight - outerPadH;
            float cardH = dpF(getContext(), 37f);
            float normalGap = dpF(getContext(), 9f);
            float priorityGap = dpF(getContext(), 6.5f);
            float cardRadius = dpF(getContext(), 11f);

            float offTotalH = cardH * 3f + normalGap * 2f;
            float offStartY = (totalH - offTotalH) * 0.5f;

            // ON layout: Ambient Nebula Glass Field holding micro-capsule + 2 linked priority cards,
            // plus 1 ordinary notification clearly outside the glass field below.
            float fieldPadH = dpF(getContext(), 7f);
            float fieldPadTop = dpF(getContext(), 6.5f);
            float capsuleH = dpF(getContext(), 17.5f);
            float capsuleGap = dpF(getContext(), 6f);
            float fieldPadBot = dpF(getContext(), 7f);
            float fieldH = fieldPadTop + capsuleH + capsuleGap + cardH * 2f + priorityGap + fieldPadBot;
            float sectionGap = dpF(getContext(), 13f);
            float onTotalH = fieldH + sectionGap + cardH;
            float onStartY = Math.max(dpF(getContext(), 9f), (totalH - onTotalH) * 0.5f);

            float cardInset = fieldPadH * p;
            float pCardLeft = normalLeft + cardInset;
            float pCardRight = normalRight - cardInset;

            // 1. Sheer Nebula Ambient Glass Field (concentric squircle mist + 0.7dp specular hairline)
            if (p > 0.02f) {
                float fieldTop = lerp(offStartY - dpF(getContext(), 6f), onStartY, p);
                float fieldBot = fieldTop + fieldH;
                drawAmbientGlassField(canvas, normalLeft, fieldTop, normalRight, fieldBot,
                        cardRadius + fieldPadH, p);

                // Top-left Liquid Glass micro-capsule inside the chamber
                drawMicroCapsule(canvas, pCardLeft + dpF(getContext(), 1f),
                        fieldTop + fieldPadTop, capsuleH, p);
            }

            // 2. Priority Card 1 (linked inside Nebula Ambient Glass Field)
            float card1Y = lerp(offStartY, onStartY + fieldPadTop + capsuleH + capsuleGap, p);
            float card2Y = lerp(
                    offStartY + cardH + normalGap,
                    onStartY + fieldPadTop + capsuleH + capsuleGap + cardH + priorityGap,
                    p);

            // Soft Nebula mist bridge flowing across the gap between Card 1 and Card 2
            if (p > 0.05f) {
                float bridgeL = pCardLeft + dpF(getContext(), 14f);
                float bridgeR = pCardRight - dpF(getContext(), 14f);
                float bridgeTop = card1Y + cardH - dpF(getContext(), 1f);
                float bridgeBot = card2Y + dpF(getContext(), 1f);
                mFillPaint.setAlpha(255);
                mFillPaint.setShader(new LinearGradient(
                        bridgeL, 0f, bridgeR, 0f,
                        new int[]{
                                0x00000000,
                                withAlpha(mPalette.previewNebulaCyan, (mPalette.dark ? 0.16f : 0.12f) * p),
                                withAlpha(mPalette.previewNebulaViolet, (mPalette.dark ? 0.16f : 0.12f) * p),
                                0x00000000
                        },
                        new float[]{0f, 0.28f, 0.72f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(bridgeL, bridgeTop, bridgeR, bridgeBot, mFillPaint);
                mFillPaint.setShader(null);
            }

            int pCardFill = blendColor(mPalette.previewNormalCard, mPalette.previewNebulaCard, p);
            int pIconBg = blendColor(mPalette.previewNormalIcon, mPalette.previewNebulaIconBg, p);
            int pLine1 = blendColor(mPalette.previewSkeletonPrimary, mPalette.previewNebulaLinePrimary, p);
            int pLine2 = blendColor(mPalette.previewSkeletonSecondary, mPalette.previewNebulaLineSecondary, p);

            drawPriorityCard(
                    canvas,
                    pCardLeft, card1Y, pCardRight, card1Y + cardH,
                    cardRadius,
                    pCardFill, pIconBg, pLine1, pLine2,
                    0.48f, 0.78f,
                    p, 0f);

            // 3. Priority Card 2 (second linked priority card inside the same glass chamber)
            drawPriorityCard(
                    canvas,
                    pCardLeft, card2Y, pCardRight, card2Y + cardH,
                    cardRadius,
                    pCardFill, pIconBg, pLine1, pLine2,
                    0.40f, 0.68f,
                    p, 26f);

            // 4. Subtle fading Nebula hairline below the ambient glass field
            float card3Y = lerp(
                    offStartY + (cardH + normalGap) * 2f,
                    onStartY + fieldH + sectionGap,
                    p);
            if (p > 0.04f) {
                float divY = (onStartY + fieldH + card3Y) * 0.5f;
                float divInset = dpF(getContext(), 18f);
                float dl = normalLeft + divInset;
                float dr = normalRight - divInset;
                mStrokePaint.setAlpha(255);
                mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 0.75f));
                mStrokePaint.setShader(new LinearGradient(
                        dl, divY, dr, divY,
                        new int[]{
                                0x00000000,
                                withAlpha(mPalette.previewNebulaCyan, (mPalette.dark ? 0.36f : 0.30f) * p),
                                withAlpha(mPalette.dark ? mPalette.previewNebulaMoon : mPalette.previewNebulaViolet,
                                        (mPalette.dark ? 0.48f : 0.40f) * p),
                                withAlpha(mPalette.previewNebulaViolet, (mPalette.dark ? 0.34f : 0.28f) * p),
                                0x00000000
                        },
                        new float[]{0f, 0.22f, 0.50f, 0.78f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawLine(dl, divY, dr, divY, mStrokePaint);
                mStrokePaint.setShader(null);
            }

            // 5. Third notification card (ordinary notification clearly outside the glass field)
            drawNormalCard(
                    canvas,
                    normalLeft, card3Y, normalRight, card3Y + cardH,
                    cardRadius,
                    0.34f, 0.58f,
                    lerp(1.0f, 0.76f, p));
        }

        private void drawAmbientGlassField(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float p) {
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mFieldPath);
            float cx = mRect.centerX();
            float cy = mRect.centerY();

            // Sheer diagonal Nebula glass mist fill
            mFillPaint.setAlpha(255);
            mFillPaint.setShader(new LinearGradient(
                    left, top, right, bottom,
                    new int[]{
                            withAlpha(mPalette.previewNebulaCyan, (mPalette.dark ? 0.14f : 0.11f) * p),
                            withAlpha(mPalette.previewNebulaAzure, (mPalette.dark ? 0.10f : 0.08f) * p),
                            withAlpha(mPalette.previewNebulaViolet, (mPalette.dark ? 0.15f : 0.12f) * p)
                    },
                    new float[]{0f, 0.48f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawPath(mFieldPath, mFillPaint);

            // Soft top liquid-glass sheen
            mFillPaint.setAlpha(255);
            mFillPaint.setShader(new LinearGradient(
                    0f, top, 0f, top + (bottom - top) * 0.32f,
                    withAlpha(0xFFFFFFFF, (mPalette.dark ? 0.08f : 0.38f) * p),
                    0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            canvas.drawPath(mFieldPath, mFillPaint);
            mFillPaint.setShader(null);

            // Feather-thin 0.75dp specular glass hairline outline
            SweepGradient rim = new SweepGradient(cx, cy,
                    new int[]{
                            withAlpha(mPalette.previewNebulaMoon, (mPalette.dark ? 0.48f : 0.52f) * p),
                            withAlpha(mPalette.previewNebulaCyan, (mPalette.dark ? 0.44f : 0.44f) * p),
                            withAlpha(mPalette.previewNebulaAzure, (mPalette.dark ? 0.34f : 0.38f) * p),
                            withAlpha(mPalette.previewNebulaViolet, (mPalette.dark ? 0.42f : 0.44f) * p),
                            withAlpha(mPalette.previewNebulaMoon, (mPalette.dark ? 0.48f : 0.52f) * p)
                    }, RIM_STOPS);
            mShaderMatrix.reset();
            mShaderMatrix.setRotate(-32f + mShimmerProgress * 360f, cx, cy);
            rim.setLocalMatrix(mShaderMatrix);
            mStrokePaint.setAlpha(255);
            mStrokePaint.setShader(rim);
            mStrokePaint.setStrokeWidth(dpF(getContext(), 0.75f + 0.25f * mShimmerBoost));
            canvas.drawPath(mFieldPath, mStrokePaint);
            mStrokePaint.setShader(null);
        }

        private void drawMicroCapsule(Canvas canvas, float left, float top, float height, float p) {
            float sparkleR = dpF(getContext(), 3.4f);
            float padStart = dpF(getContext(), 6.5f);
            float padEnd = dpF(getContext(), 8f);
            float gap = dpF(getContext(), 3.8f);
            float textW = mTextPaint.measureText(PRIORITY_LABEL);
            float pillW = padStart + sparkleR * 2f + gap + textW + padEnd;
            float r = height * 0.5f;

            mRect.set(left, top, left + pillW, top + height);
            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(withAlpha(0x10142342, p));
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.55f));
                canvas.drawRoundRect(mRect, r, r, mFillPaint);
                canvas.restore();
            }
            mFillPaint.setColor(withAlpha(mPalette.previewNebulaCapsuleFill, p));
            canvas.drawRoundRect(mRect, r, r, mFillPaint);

            float cx = mRect.centerX();
            float cy = mRect.centerY();
            SweepGradient rim = new SweepGradient(cx, cy,
                    new int[]{
                            withAlpha(mPalette.previewNebulaCyan, (0.58f + 0.35f * mShimmerBoost) * p),
                            withAlpha(mPalette.previewNebulaAzure, (0.52f + 0.35f * mShimmerBoost) * p),
                            withAlpha(mPalette.previewNebulaViolet, (0.58f + 0.35f * mShimmerBoost) * p),
                            withAlpha(mPalette.previewNebulaMoon, (0.72f + 0.25f * mShimmerBoost) * p),
                            withAlpha(mPalette.previewNebulaCyan, (0.58f + 0.35f * mShimmerBoost) * p)
                    }, RIM_STOPS);
            mShaderMatrix.reset();
            mShaderMatrix.setRotate(-30f + mShimmerProgress * 360f, cx, cy);
            rim.setLocalMatrix(mShaderMatrix);
            mStrokePaint.setAlpha(255);
            mStrokePaint.setShader(rim);
            mStrokePaint.setStrokeWidth(dpF(getContext(), 0.7f + 0.22f * mShimmerBoost));
            canvas.drawRoundRect(mRect, r, r, mStrokePaint);
            mStrokePaint.setShader(null);

            float sparkleCx = left + padStart + sparkleR;
            drawGradientSparkle(canvas, sparkleCx, cy, sparkleR, p, mShimmerBoost);

            mTextPaint.setColor(withAlpha(mPalette.previewNebulaBadgeText, p));
            Paint.FontMetrics fm = mTextPaint.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(PRIORITY_LABEL, sparkleCx + sparkleR + gap, baseline, mTextPaint);
        }

        private void drawPriorityCard(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                int cardBg,
                int iconBg,
                int linePrimaryColor,
                int lineSecondaryColor,
                float line1Ratio,
                float line2Ratio,
                float p,
                float angleOffset) {
            float cardH = bottom - top;
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);
            float cx = mRect.centerX();
            float cy = mRect.centerY();

            // 1. Tight outward-clipped feathered Nebula Aurora glow
            if (p > 0.03f) {
                SweepGradient aura = new SweepGradient(cx, cy,
                        new int[]{
                                mPalette.previewNebulaCyan,
                                mPalette.previewNebulaAzure,
                                mPalette.previewNebulaViolet,
                                mPalette.previewNebulaMoon,
                                mPalette.previewNebulaCyan
                        }, RIM_STOPS);
                mShaderMatrix.reset();
                mShaderMatrix.setRotate(-32f + angleOffset + mShimmerProgress * 360f, cx, cy);
                aura.setLocalMatrix(mShaderMatrix);
                mAuraPaint.setShader(aura);

                float[] alphas = mPalette.dark ? AURA_ALPHAS_DARK : AURA_ALPHAS_LIGHT;
                int saved = canvas.save();
                canvas.clipOutPath(mCardPath);
                for (int i = 0; i < AURA_WIDTHS_DP.length; i++) {
                    float a = alphas[i] * (1f + 0.75f * mShimmerBoost) * p;
                    mAuraPaint.setStrokeWidth(dpF(getContext(), AURA_WIDTHS_DP[i]));
                    mAuraPaint.setAlpha(Math.min(255, Math.round(a * 255f)));
                    canvas.drawPath(mCardPath, mAuraPaint);
                }
                canvas.restoreToCount(saved);
                mAuraPaint.setShader(null);
            }

            // 2. Crisp card surface
            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(0x0B000000);
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.7f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }
            mFillPaint.setColor(cardBg);
            canvas.drawPath(mCardPath, mFillPaint);

            // 3. Per-card squircle Nebula Aurora prism rim + optional shimmer comet
            if (p > 0.03f) {
                SweepGradient rim = new SweepGradient(cx, cy,
                        new int[]{
                                withAlpha(mPalette.previewNebulaCyan, 0.90f * p),
                                withAlpha(mPalette.previewNebulaAzure, 0.86f * p),
                                withAlpha(mPalette.previewNebulaViolet, 0.90f * p),
                                withAlpha(mPalette.previewNebulaMoon, (mPalette.dark ? 0.92f : 0.75f) * p),
                                withAlpha(mPalette.previewNebulaCyan, 0.90f * p)
                        }, RIM_STOPS);
                mShaderMatrix.reset();
                mShaderMatrix.setRotate(-32f + angleOffset + mShimmerProgress * 360f, cx, cy);
                rim.setLocalMatrix(mShaderMatrix);
                mStrokePaint.setAlpha(255);
                mStrokePaint.setShader(rim);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 1.2f));
                canvas.drawPath(mCardPath, mStrokePaint);

                if (mShimmerBoost > 0.01f) {
                    SweepGradient comet = new SweepGradient(cx, cy,
                            new int[]{
                                    0x00000000,
                                    0x00000000,
                                    withAlpha(mPalette.previewNebulaCyan, 0.75f * mShimmerBoost * p),
                                    withAlpha(0xFFFFFFFF, 0.95f * mShimmerBoost * p),
                                    withAlpha(mPalette.previewNebulaViolet, 0.70f * mShimmerBoost * p),
                                    0x00000000
                            }, COMET_STOPS);
                    mShaderMatrix.reset();
                    mShaderMatrix.setRotate(-115f + angleOffset + mShimmerProgress * 360f, cx, cy);
                    comet.setLocalMatrix(mShaderMatrix);
                    mStrokePaint.setAlpha(255);
                    mStrokePaint.setShader(comet);
                    mStrokePaint.setStrokeWidth(dpF(getContext(), 1.5f));
                    canvas.drawPath(mCardPath, mStrokePaint);
                }
                mStrokePaint.setShader(null);
            }

            // 4. Card contents
            float iconSize = dpF(getContext(), 21f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = top + (cardH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6.5f), mIconPath);
            mFillPaint.setColor(iconBg);
            canvas.drawPath(mIconPath, mFillPaint);

            if (p > 0.05f) {
                drawGradientSparkle(
                        canvas,
                        iconLeft + iconSize * 0.5f,
                        iconTop + iconSize * 0.5f,
                        dpF(getContext(), 4.5f),
                        p,
                        mShimmerBoost * 0.6f);
            }

            float textLeft = iconLeft + iconSize + dpF(getContext(), 9f);
            float textMaxW = Math.max(0f, right - dpF(getContext(), 12f) - textLeft);
            float line1H = dpF(getContext(), 5.2f);
            float line2H = dpF(getContext(), 4.2f);
            float lineGap = dpF(getContext(), 5.2f);
            float linesTop = top + (cardH - (line1H + lineGap + line2H)) * 0.5f;

            mFillPaint.setShader(null);
            mFillPaint.setColor(linePrimaryColor);
            mRect.set(textLeft, linesTop, textLeft + textMaxW * line1Ratio, linesTop + line1H);
            canvas.drawRoundRect(mRect, line1H * 0.5f, line1H * 0.5f, mFillPaint);

            float timeW = dpF(getContext(), 15f);
            float timeH = dpF(getContext(), 3.8f);
            float timeTop = linesTop + (line1H - timeH) * 0.5f;
            mFillPaint.setColor(withAlpha(lineSecondaryColor, 0.85f));
            mRect.set(right - dpF(getContext(), 12f) - timeW, timeTop,
                    right - dpF(getContext(), 12f), timeTop + timeH);
            canvas.drawRoundRect(mRect, timeH * 0.5f, timeH * 0.5f, mFillPaint);

            mFillPaint.setColor(lineSecondaryColor);
            float line2Top = linesTop + line1H + lineGap;
            mRect.set(textLeft, line2Top, textLeft + textMaxW * line2Ratio, line2Top + line2H);
            canvas.drawRoundRect(mRect, line2H * 0.5f, line2H * 0.5f, mFillPaint);
        }

        private void drawNormalCard(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float line1Ratio,
                float line2Ratio,
                float alphaScale) {
            float cardH = bottom - top;
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);

            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(withAlpha(0x0A000000, alphaScale));
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.7f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }

            mFillPaint.setColor(withAlpha(mPalette.previewNormalCard, alphaScale));
            canvas.drawPath(mCardPath, mFillPaint);

            float iconSize = dpF(getContext(), 21f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = top + (cardH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6.5f), mIconPath);
            mFillPaint.setColor(withAlpha(mPalette.previewNormalIcon, alphaScale));
            canvas.drawPath(mIconPath, mFillPaint);

            float textLeft = iconLeft + iconSize + dpF(getContext(), 9f);
            float textMaxW = Math.max(0f, right - dpF(getContext(), 12f) - textLeft);
            float line1H = dpF(getContext(), 5.2f);
            float line2H = dpF(getContext(), 4.2f);
            float lineGap = dpF(getContext(), 5.2f);
            float linesTop = top + (cardH - (line1H + lineGap + line2H)) * 0.5f;

            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonPrimary, alphaScale));
            mRect.set(textLeft, linesTop, textLeft + textMaxW * line1Ratio, linesTop + line1H);
            canvas.drawRoundRect(mRect, line1H * 0.5f, line1H * 0.5f, mFillPaint);

            float timeW = dpF(getContext(), 15f);
            float timeH = dpF(getContext(), 3.8f);
            float timeTop = linesTop + (line1H - timeH) * 0.5f;
            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonSecondary, alphaScale * 0.85f));
            mRect.set(right - dpF(getContext(), 12f) - timeW, timeTop,
                    right - dpF(getContext(), 12f), timeTop + timeH);
            canvas.drawRoundRect(mRect, timeH * 0.5f, timeH * 0.5f, mFillPaint);

            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonSecondary, alphaScale));
            float line2Top = linesTop + line1H + lineGap;
            mRect.set(textLeft, line2Top, textLeft + textMaxW * line2Ratio, line2Top + line2H);
            canvas.drawRoundRect(mRect, line2H * 0.5f, line2H * 0.5f, mFillPaint);
        }

        private void drawGradientSparkle(Canvas canvas, float cx, float cy, float r,
                                         float alphaScale, float glowBoost) {
            float inner = r * 0.25f;
            mSparklePath.reset();
            mSparklePath.moveTo(cx, cy - r);
            mSparklePath.quadTo(cx + inner * 0.42f, cy - inner * 0.42f, cx + r, cy);
            mSparklePath.quadTo(cx + inner * 0.42f, cy + inner * 0.42f, cx, cy + r);
            mSparklePath.quadTo(cx - inner * 0.42f, cy + inner * 0.42f, cx - r, cy);
            mSparklePath.quadTo(cx - inner * 0.42f, cy - inner * 0.42f, cx, cy - r);
            mSparklePath.close();

            mFillPaint.setAlpha(255);
            mFillPaint.setShader(new LinearGradient(
                    cx - r, cy - r, cx + r, cy + r,
                    withAlpha(mPalette.previewNebulaCyan, alphaScale),
                    withAlpha(mPalette.previewNebulaViolet, alphaScale),
                    Shader.TileMode.CLAMP));
            canvas.drawPath(mSparklePath, mFillPaint);
            mFillPaint.setShader(null);
            if (glowBoost > 0.02f) {
                mFillPaint.setColor(withAlpha(0xFFFFFFFF, 0.58f * glowBoost * alphaScale));
                canvas.drawPath(mSparklePath, mFillPaint);
            }
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelAnimator();
            cancelShimmerAnimator();
            super.onDetachedFromWindow();
        }

        private void cancelAnimator() {
            if (mAnimator != null) {
                mAnimator.cancel();
                mAnimator = null;
            }
        }

        private void cancelShimmerAnimator() {
            if (mShimmerAnimator != null) {
                mShimmerAnimator.cancel();
                mShimmerAnimator = null;
            }
            mShimmerProgress = 0f;
            mShimmerBoost = 0f;
        }
    }

    /**
     * Dedicated HyperOS settings illustration for "减少干扰" (Reduce Interruptions):
     * when enabled, displays the Apple Intelligence style iridescent 7-lobe focus pill,
     * highlights the VIP notification breaking through with alerts, and softly dims ordinary
     * notifications while keeping them visible in the notification list.
     */
    static final class ReducePreviewView extends View {
        private static final float STAGE_HEIGHT_DP = 178f;
        private static final float MAX_STAGE_WIDTH_DP = 320f;
        private static final String MODE_LABEL = "减少干扰";
        private static final String ALLOW_LABEL = "放行提醒";

        private final Palette mPalette;
        private final ReduceInterruptions.AppleIntelIridescentPainter mAppleIconPainter;
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPillBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mStagePath = new Path();
        private final Path mCardPath = new Path();
        private final Path mIconPath = new Path();
        private final Path mSparklePath = new Path();
        private final RectF mRect = new RectF();

        private LinearGradient mPillBorderShader;
        private float mPillGradLeft = Float.NaN;
        private float mPillGradRight = Float.NaN;

        private boolean mReduceEnabled;
        private float mProgress;
        private float mShimmerPhaseDeg;
        private ValueAnimator mAnimator;
        private ValueAnimator mShimmerAnimator;

        ReducePreviewView(Context context, Palette palette) {
            super(context);
            mPalette = palette;
            mAppleIconPainter = new ReduceInterruptions.AppleIntelIridescentPainter(palette.dark);
            mFillPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mPillBorderPaint.setStyle(Paint.Style.STROKE);
            mPillBorderPaint.setStrokeWidth(dpF(context, 0.9f));
            mPillTextPaint.setTypeface(typeface(600));
            mPillTextPaint.setTextSize(dpF(context, 9.6f));
            mBadgeTextPaint.setTypeface(typeface(600));
            mBadgeTextPaint.setTextSize(dpF(context, 8.8f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setReduceEnabled(boolean enabled, boolean animate) {
            boolean changed = mReduceEnabled != enabled;
            mReduceEnabled = enabled;
            syncShimmerState();
            float target = enabled ? 1f : 0f;
            if (!animate || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
                cancelAnimator();
                mProgress = target;
                invalidate();
                return;
            }
            if (!changed && (mProgress == target
                    || (mAnimator != null && mAnimator.isRunning()))) {
                return;
            }
            cancelAnimator();
            mAnimator = ValueAnimator.ofFloat(mProgress, target);
            mAnimator.setDuration(280L);
            mAnimator.setInterpolator(HYPER_EASE);
            mAnimator.addUpdateListener(anim -> {
                mProgress = (float) anim.getAnimatedValue();
                invalidate();
            });
            mAnimator.start();
        }

        private void syncShimmerState() {
            if (!mReduceEnabled || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
                cancelShimmerAnimator();
                return;
            }
            if (mShimmerAnimator != null && mShimmerAnimator.isRunning()) {
                return;
            }
            mShimmerAnimator = ValueAnimator.ofFloat(0f, 360f);
            mShimmerAnimator.setDuration(7200L);
            mShimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mShimmerAnimator.setInterpolator(new LinearInterpolator());
            mShimmerAnimator.addUpdateListener(anim -> {
                mShimmerPhaseDeg = (float) anim.getAnimatedValue();
                invalidate();
            });
            mShimmerAnimator.start();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            syncShimmerState();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredH = dp(getContext(), STAGE_HEIGHT_DP);
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, resolveSize(desiredH, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float totalW = getWidth();
            float totalH = getHeight();
            if (totalW <= 0f || totalH <= 0f) return;

            float r = mProgress;
            float stageW = Math.min(totalW, dpF(getContext(), MAX_STAGE_WIDTH_DP));
            float stageLeft = (totalW - stageW) * 0.5f;
            float stageRight = stageLeft + stageW;

            // Soft HyperOS inner stage
            float stageRadius = dpF(getContext(), 14f);
            float strokeHalf = dpF(getContext(), 0.5f);
            mRect.set(stageLeft + strokeHalf, strokeHalf, stageRight - strokeHalf, totalH - strokeHalf);
            buildSquirclePath(mRect, stageRadius, mStagePath);
            mFillPaint.setColor(mPalette.previewStageBg);
            canvas.drawPath(mStagePath, mFillPaint);
            mStrokePaint.setColor(mPalette.previewStageBorder);
            mStrokePaint.setStrokeWidth(dpF(getContext(), 1f));
            canvas.drawPath(mStagePath, mStrokePaint);

            float padH = dpF(getContext(), 14f);
            float left = stageLeft + padH;
            float right = stageRight - padH;
            float cardRadius = dpF(getContext(), 11f);
            float cardH = dpF(getContext(), 38f);
            float cardGap = dpF(getContext(), 8f);

            // OFF vertical layout: 3 ordinary interrupting cards centered
            float offTotalH = cardH * 3f + cardGap * 2f;
            float offStartY = (totalH - offTotalH) * 0.5f;

            // ON vertical layout: top "减少干扰" Apple Intelligence pill + 1 breakthrough card + 2 quiet cards
            float pillH = dpF(getContext(), 22f);
            float pillGap = dpF(getContext(), 9f);
            float onTotalH = pillH + pillGap + cardH * 3f + cardGap * 2f;
            float onTop = Math.max(dpF(getContext(), 9f), (totalH - onTotalH) * 0.5f);

            // 1. Top "减少干扰" focus pill with Apple Intelligence iridescent 7-lobe ribbon
            if (r > 0.02f) {
                float iconSize = dpF(getContext(), 15.5f);
                float labelW = mPillTextPaint.measureText(MODE_LABEL);
                float pillPadStart = dpF(getContext(), 7.5f);
                float pillPadEnd = dpF(getContext(), 9.5f);
                float iconTextGap = dpF(getContext(), 4.0f);
                float pillW = pillPadStart + iconSize + iconTextGap + labelW + pillPadEnd;
                float pillLeft = stageLeft + (stageW - pillW) * 0.5f;
                float pillTop = lerp(onTop - dpF(getContext(), 6f), onTop, r);
                float pillRight = pillLeft + pillW;

                mRect.set(pillLeft, pillTop, pillRight, pillTop + pillH);
                int pillFill = mPalette.dark ? 0xFF211E2E : 0xFFFFFFFF;
                mFillPaint.setColor(withAlpha(pillFill, r));
                canvas.drawRoundRect(mRect, pillH * 0.5f, pillH * 0.5f, mFillPaint);

                if (mPillBorderShader == null || mPillGradLeft != pillLeft || mPillGradRight != pillRight) {
                    mPillGradLeft = pillLeft;
                    mPillGradRight = pillRight;
                    int[] borderColors = mPalette.dark
                            ? new int[]{0x704EC8FF, 0x85BA7AF8, 0x70FF667C, 0x70FFB868}
                            : new int[]{0x5820A6F6, 0x6C9E52EE, 0x58F2486A, 0x58F69434};
                    mPillBorderShader = new LinearGradient(
                            pillLeft, 0f, pillRight, 0f, borderColors, null, Shader.TileMode.CLAMP);
                    mPillBorderPaint.setShader(mPillBorderShader);
                }
                mPillBorderPaint.setAlpha(Math.round(255f * r));
                canvas.drawRoundRect(mRect, pillH * 0.5f, pillH * 0.5f, mPillBorderPaint);

                mAppleIconPainter.draw(
                        canvas,
                        pillLeft + pillPadStart,
                        pillTop + (pillH - iconSize) * 0.5f,
                        iconSize,
                        1.72f,
                        0.06f,
                        mShimmerPhaseDeg,
                        r);

                int textColor = mPalette.dark ? 0xFFD8CEFF : 0xFF6346D8;
                mPillTextPaint.setColor(withAlpha(textColor, r));
                Paint.FontMetrics fm = mPillTextPaint.getFontMetrics();
                float baseline = (pillTop + pillH * 0.5f) - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(MODE_LABEL, pillLeft + pillPadStart + iconSize + iconTextGap,
                        baseline, mPillTextPaint);
            }

            float cardsStartY = lerp(offStartY, onTop + pillH + pillGap, r);

            // 2. Card 1: VIP breakthrough notification (stays crisp, highlighted & alerts)
            float c1Top = cardsStartY;
            drawBreakthroughCard(canvas, left, c1Top, right, c1Top + cardH, cardRadius, r);

            // 3. Card 2: Ordinary notification (remains in list, softly silenced)
            float c2Top = c1Top + cardH + cardGap;
            drawQuietCard(canvas, left, c2Top, right, c2Top + cardH, cardRadius,
                    0.40f, 0.68f, lerp(1.0f, 0.56f, r), r);

            // 4. Card 3: Ordinary notification (remains in list, softly silenced)
            float c3Top = c2Top + cardH + cardGap;
            drawQuietCard(canvas, left, c3Top, right, c3Top + cardH, cardRadius,
                    0.34f, 0.54f, lerp(1.0f, 0.40f, r), r);
        }

        private void drawBreakthroughCard(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float r) {
            float cardH = bottom - top;
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);
            float cx = mRect.centerX();
            float cy = mRect.centerY();

            // Subtle outward-clipped Nebula Aurora glow on VIP breakthrough card
            if (r > 0.04f) {
                SweepGradient aura = new SweepGradient(cx, cy,
                        new int[]{
                                mPalette.previewNebulaCyan,
                                mPalette.previewNebulaAzure,
                                mPalette.previewNebulaViolet,
                                mPalette.previewNebulaMoon,
                                mPalette.previewNebulaCyan
                        },
                        new float[]{0f, 0.24f, 0.50f, 0.74f, 1f});
                mStrokePaint.setShader(aura);
                int saved = canvas.save();
                canvas.clipOutPath(mCardPath);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 6.0f));
                mStrokePaint.setAlpha(Math.round((mPalette.dark ? 0.09f : 0.07f) * r * 255f));
                canvas.drawPath(mCardPath, mStrokePaint);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 3.0f));
                mStrokePaint.setAlpha(Math.round((mPalette.dark ? 0.14f : 0.11f) * r * 255f));
                canvas.drawPath(mCardPath, mStrokePaint);
                canvas.restoreToCount(saved);
                mStrokePaint.setShader(null);
                mStrokePaint.setAlpha(255);
            }

            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(0x0D000000);
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.9f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }

            int fill = blendColor(mPalette.previewNormalCard, mPalette.previewNebulaCard, r);
            mFillPaint.setColor(fill);
            canvas.drawPath(mCardPath, mFillPaint);

            if (r > 0.04f) {
                SweepGradient rim = new SweepGradient(cx, cy,
                        new int[]{
                                withAlpha(mPalette.previewNebulaCyan, (mPalette.dark ? 0.84f : 0.78f) * r),
                                withAlpha(mPalette.previewNebulaAzure, (mPalette.dark ? 0.80f : 0.74f) * r),
                                withAlpha(mPalette.previewNebulaViolet, (mPalette.dark ? 0.84f : 0.78f) * r),
                                withAlpha(mPalette.previewNebulaMoon, (mPalette.dark ? 0.86f : 0.65f) * r),
                                withAlpha(mPalette.previewNebulaCyan, (mPalette.dark ? 0.84f : 0.78f) * r)
                        },
                        new float[]{0f, 0.24f, 0.50f, 0.74f, 1f});
                mStrokePaint.setAlpha(255);
                mStrokePaint.setShader(rim);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 1.2f));
                canvas.drawPath(mCardPath, mStrokePaint);
                mStrokePaint.setShader(null);
            }

            float iconSize = dpF(getContext(), 21f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = top + (cardH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6.5f), mIconPath);
            mFillPaint.setColor(blendColor(mPalette.previewNormalIcon, mPalette.previewNebulaIconBg, r));
            canvas.drawPath(mIconPath, mFillPaint);

            if (r > 0.05f) {
                float sx = iconLeft + iconSize * 0.5f;
                float sy = iconTop + iconSize * 0.5f;
                float sr = dpF(getContext(), 4.5f);
                drawGradientSparkle(canvas, sx, sy, sr, r);
            }

            float textLeft = iconLeft + iconSize + dpF(getContext(), 9f);
            float rightEdge = right - dpF(getContext(), 10f);
            float badgeW = mBadgeTextPaint.measureText(ALLOW_LABEL) + dpF(getContext(), 12f);
            float textMaxW = Math.max(0f, rightEdge - textLeft - badgeW * r - dpF(getContext(), 6f));

            float l1H = dpF(getContext(), 5.2f);
            float l2H = dpF(getContext(), 4.3f);
            float gap = dpF(getContext(), 5.2f);
            float linesTop = top + (cardH - (l1H + gap + l2H)) * 0.5f;

            mFillPaint.setShader(null);
            mFillPaint.setColor(blendColor(mPalette.previewSkeletonPrimary, mPalette.previewNebulaLinePrimary, r));
            mRect.set(textLeft, linesTop, textLeft + textMaxW * 0.62f, linesTop + l1H);
            canvas.drawRoundRect(mRect, l1H * 0.5f, l1H * 0.5f, mFillPaint);

            mFillPaint.setColor(blendColor(mPalette.previewSkeletonSecondary, mPalette.previewNebulaLineSecondary, r));
            mRect.set(textLeft, linesTop + l1H + gap, textLeft + textMaxW * 0.92f, linesTop + l1H + gap + l2H);
            canvas.drawRoundRect(mRect, l2H * 0.5f, l2H * 0.5f, mFillPaint);

            // Right side: when ON -> cool Nebula "放行提醒" pill; when OFF -> ordinary dot
            if (r > 0.06f) {
                float pillH = dpF(getContext(), 15f);
                float pillLeft = rightEdge - badgeW;
                float pillTop = top + (cardH - pillH) * 0.5f;
                mRect.set(pillLeft, pillTop, rightEdge, pillTop + pillH);
                mFillPaint.setColor(withAlpha(mPalette.previewNebulaPillBg, r));
                canvas.drawRoundRect(mRect, pillH * 0.5f, pillH * 0.5f, mFillPaint);

                mBadgeTextPaint.setColor(withAlpha(mPalette.previewNebulaBadgeText, r));
                Paint.FontMetrics fm = mBadgeTextPaint.getFontMetrics();
                float base = (pillTop + pillH * 0.5f) - (fm.ascent + fm.descent) * 0.5f;
                float tw = mBadgeTextPaint.measureText(ALLOW_LABEL);
                canvas.drawText(ALLOW_LABEL, pillLeft + (badgeW - tw) * 0.5f, base, mBadgeTextPaint);
            } else {
                float dotR = dpF(getContext(), 3f);
                mFillPaint.setColor(withAlpha(mPalette.accent, 1f - r));
                canvas.drawCircle(rightEdge - dotR, top + cardH * 0.5f, dotR, mFillPaint);
            }
        }

        private void drawQuietCard(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float line1Ratio,
                float line2Ratio,
                float alphaScale,
                float r) {
            float cardH = bottom - top;
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);

            if (!mPalette.dark) {
                mFillPaint.setColor(withAlpha(0x09000000, alphaScale));
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.7f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }

            mFillPaint.setColor(withAlpha(mPalette.previewNormalCard, alphaScale));
            canvas.drawPath(mCardPath, mFillPaint);

            float iconSize = dpF(getContext(), 20f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = top + (cardH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6f), mIconPath);
            mFillPaint.setColor(withAlpha(mPalette.previewNormalIcon, alphaScale));
            canvas.drawPath(mIconPath, mFillPaint);

            float textLeft = iconLeft + iconSize + dpF(getContext(), 9f);
            float rightEdge = right - dpF(getContext(), 12f);
            float textMaxW = Math.max(0f, rightEdge - textLeft - dpF(getContext(), 20f));
            float l1H = dpF(getContext(), 5f);
            float l2H = dpF(getContext(), 4.2f);
            float gap = dpF(getContext(), 5f);
            float linesTop = top + (cardH - (l1H + gap + l2H)) * 0.5f;

            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonPrimary, alphaScale));
            mRect.set(textLeft, linesTop, textLeft + textMaxW * line1Ratio, linesTop + l1H);
            canvas.drawRoundRect(mRect, l1H * 0.5f, l1H * 0.5f, mFillPaint);

            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonSecondary, alphaScale));
            mRect.set(textLeft, linesTop + l1H + gap, textLeft + textMaxW * line2Ratio, linesTop + l1H + gap + l2H);
            canvas.drawRoundRect(mRect, l2H * 0.5f, l2H * 0.5f, mFillPaint);

            // Right indicator: OFF -> active blue alert dot; ON -> minimal muted slash-bar
            float cy = top + cardH * 0.5f;
            if (r < 0.95f) {
                float dotR = dpF(getContext(), 3f);
                mFillPaint.setColor(withAlpha(mPalette.accent, (1f - r) * 0.85f));
                canvas.drawCircle(rightEdge - dotR, cy, dotR, mFillPaint);
            }
            if (r > 0.05f) {
                float cx = rightEdge - dpF(getContext(), 4.5f);
                float sr = dpF(getContext(), 4.2f);
                mStrokePaint.setColor(withAlpha(mPalette.textTertiary, r * alphaScale));
                mStrokePaint.setStrokeWidth(dpF(getContext(), 1.3f));
                mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawCircle(cx, cy, sr, mStrokePaint);
                canvas.drawLine(cx - sr * 0.65f, cy - sr * 0.65f, cx + sr * 0.65f, cy + sr * 0.65f, mStrokePaint);
            }
        }

        private void drawGradientSparkle(Canvas canvas, float cx, float cy, float r, float alphaScale) {
            float inner = r * 0.25f;
            mSparklePath.reset();
            mSparklePath.moveTo(cx, cy - r);
            mSparklePath.quadTo(cx + inner * 0.42f, cy - inner * 0.42f, cx + r, cy);
            mSparklePath.quadTo(cx + inner * 0.42f, cy + inner * 0.42f, cx, cy + r);
            mSparklePath.quadTo(cx - inner * 0.42f, cy + inner * 0.42f, cx - r, cy);
            mSparklePath.quadTo(cx - inner * 0.42f, cy - inner * 0.42f, cx, cy - r);
            mSparklePath.close();
            mFillPaint.setAlpha(255);
            mFillPaint.setShader(new LinearGradient(
                    cx - r, cy - r, cx + r, cy + r,
                    withAlpha(mPalette.previewNebulaCyan, alphaScale),
                    withAlpha(mPalette.previewNebulaViolet, alphaScale),
                    Shader.TileMode.CLAMP));
            canvas.drawPath(mSparklePath, mFillPaint);
            mFillPaint.setShader(null);
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelAnimator();
            cancelShimmerAnimator();
            super.onDetachedFromWindow();
        }

        private void cancelAnimator() {
            if (mAnimator != null) {
                mAnimator.cancel();
                mAnimator = null;
            }
        }

        private void cancelShimmerAnimator() {
            if (mShimmerAnimator != null) {
                mShimmerAnimator.cancel();
                mShimmerAnimator = null;
            }
            mShimmerPhaseDeg = 0f;
        }
    }
}
