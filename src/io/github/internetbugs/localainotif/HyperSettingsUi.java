package io.github.internetbugs.localainotif;

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
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
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

        // Floating HyperOS 4 top bar: progressive vertical fade scrim (no hard divider),
        // floating circular '<' back button, and horizontally centered collapsed title.
        FrameLayout stickyTopBar = new FrameLayout(activity);

        TopBarScrimDrawable topBarScrim = new TopBarScrimDrawable(
                activity, palette.background, palette.dark, 56f, 24f);
        View topBarScrimView = new View(activity);
        topBarScrimView.setBackground(topBarScrim);
        topBarScrimView.setAlpha(0f);
        topBarScrimView.setVisibility(View.INVISIBLE);
        topBarScrimView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stickyTopBar.addView(topBarScrimView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56 + 24), Gravity.TOP));

        FrameLayout topBarContainer = new FrameLayout(activity);
        FrameLayout topBarSlot = new FrameLayout(activity);

        if (showBackButton) {
            BackArrowView backBtn = new BackArrowView(activity, palette);
            backBtn.setContentDescription("返回");
            backBtn.setOnClickListener(v -> activity.finish());
            FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(
                    dp(activity, 48), dp(activity, 48),
                    Gravity.START | Gravity.CENTER_VERTICAL);
            backLp.setMarginStart(dp(activity, 8));
            topBarSlot.addView(backBtn, backLp);
        }

        TextView collapsedTitle = new TextView(activity);
        collapsedTitle.setText(title);
        collapsedTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f);
        collapsedTitle.setTypeface(typeface(500));
        collapsedTitle.setTextColor(palette.textPrimary);
        collapsedTitle.setSingleLine(true);
        collapsedTitle.setEllipsize(TextUtils.TruncateAt.END);
        collapsedTitle.setGravity(Gravity.CENTER);
        collapsedTitle.setIncludeFontPadding(false);
        collapsedTitle.setAlpha(0f);
        collapsedTitle.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams collapsedLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        int titleSafeMargin = dp(activity, showBackButton ? 64 : 28);
        collapsedLp.setMarginStart(titleSafeMargin);
        collapsedLp.setMarginEnd(titleSafeMargin);
        topBarSlot.addView(collapsedTitle, collapsedLp);

        topBarContainer.addView(topBarSlot, new FrameLayout.LayoutParams(
                contentWidth, dp(activity, 56), Gravity.CENTER_HORIZONTAL));
        stickyTopBar.addView(topBarContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        root.addView(stickyTopBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        int fadeStart = showBackButton ? dp(activity, 12) : dp(activity, largeTitleTopDp);
        int fadeEnd = fadeStart + dp(activity, 36);
        int scrimStart = showBackButton ? 0 : Math.max(0, fadeStart - dp(activity, 12));
        int scrimEnd = showBackButton ? dp(activity, 28) : fadeEnd;
        float titleRisePx = dpF(activity, 4f);
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            float progress = Math.max(0f, Math.min(1f,
                    (scrollY - fadeStart) / (float) Math.max(1, fadeEnd - fadeStart)));
            float scrimProgress = Math.max(0f, Math.min(1f,
                    (scrollY - scrimStart) / (float) Math.max(1, scrimEnd - scrimStart)));
            topBarScrimView.setVisibility(scrimProgress > 0.001f ? View.VISIBLE : View.INVISIBLE);
            topBarScrimView.setAlpha(scrimProgress);
            collapsedTitle.setVisibility(progress > 0.001f ? View.VISIBLE : View.INVISIBLE);
            collapsedTitle.setAlpha(progress);
            collapsedTitle.setTranslationY(titleRisePx * (1f - progress));
            largeTitle.setAlpha(1f - progress * 0.85f);
        });

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scrollView.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            topBarContainer.setPadding(bars.left, bars.top, bars.right, 0);
            topBarScrim.setTopInsetPx(bars.top);
            ViewGroup.LayoutParams scrimLp = topBarScrimView.getLayoutParams();
            int desiredScrimH = bars.top + dp(activity, 56 + 24);
            if (scrimLp != null && scrimLp.height != desiredScrimH) {
                scrimLp.height = desiredScrimH;
                topBarScrimView.setLayoutParams(scrimLp);
            }
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

    /**
     * Progressive translucent vertical fade veil for the HyperOS 4 floating top bar.
     * Avoids a harsh solid slab or hard hairline divider while keeping status-bar icons
     * and the centered collapsed title cleanly legible over scrolled content.
     */
    static final class TopBarScrimDrawable extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mBaseColor;
        private final boolean mDark;
        private final float mBarHeightPx;
        private final float mSkirtHeightPx;
        private int mTopInsetPx;
        private int mDrawableAlpha = 255;

        TopBarScrimDrawable(
                Context context,
                int baseColor,
                boolean dark,
                float barHeightDp,
                float skirtHeightDp) {
            mBaseColor = baseColor;
            mDark = dark;
            mBarHeightPx = dpF(context, barHeightDp);
            mSkirtHeightPx = dpF(context, skirtHeightDp);
        }

        void setTopInsetPx(int topInsetPx) {
            int clamped = Math.max(0, topInsetPx);
            if (mTopInsetPx != clamped) {
                mTopInsetPx = clamped;
                rebuildShader(getBounds());
                invalidateSelf();
            }
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            rebuildShader(bounds);
        }

        private void rebuildShader(Rect bounds) {
            float totalH = bounds.height();
            if (totalH <= 1f) {
                mPaint.setShader(null);
                return;
            }
            float s1 = clampStop(mTopInsetPx / totalH, 0.01f, 0.68f);
            float s2 = clampStop((mTopInsetPx + mBarHeightPx * 0.70f) / totalH, s1 + 0.01f, 0.85f);
            float s3 = clampStop((mTopInsetPx + mBarHeightPx) / totalH, s2 + 0.01f, 0.92f);
            float s4 = clampStop((mTopInsetPx + mBarHeightPx + mSkirtHeightPx * 0.50f) / totalH, s3 + 0.01f, 0.98f);
            int[] colors = {
                    withAlpha(mBaseColor, mDark ? 0.98f : 0.98f),
                    withAlpha(mBaseColor, mDark ? 0.97f : 0.97f),
                    withAlpha(mBaseColor, mDark ? 0.95f : 0.94f),
                    withAlpha(mBaseColor, mDark ? 0.74f : 0.72f),
                    withAlpha(mBaseColor, mDark ? 0.28f : 0.26f),
                    withAlpha(mBaseColor, 0f)
            };
            float[] stops = {0f, s1, s2, s3, s4, 1f};
            mPaint.setShader(new LinearGradient(
                    0f, bounds.top, 0f, bounds.bottom, colors, stops, Shader.TileMode.CLAMP));
        }

        private static float clampStop(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        @Override
        public void draw(Canvas canvas) {
            if (mPaint.getShader() != null) {
                mPaint.setAlpha(mDrawableAlpha);
                canvas.drawRect(getBounds(), mPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mDrawableAlpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * HyperOS 4 floating circular back pill button with soft diffused elevation and thin '<' chevron.
     */
    static final class BackArrowView extends View {
        private static final float CIRCLE_RADIUS_DP = 20f;

        private final Palette mPalette;
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mRimTop = Float.NaN;
        private float mRimBottom = Float.NaN;
        private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private float mPressFraction;
        private ValueAnimator mPressAnimator;

        BackArrowView(Context context, Palette palette) {
            super(context);
            mPalette = palette;
            mFillPaint.setStyle(Paint.Style.FILL);
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(dpF(context, palette.dark ? 0.75f : 0.65f));
            mShadowPaint.setStyle(Paint.Style.FILL);
            mIconPaint.setStyle(Paint.Style.STROKE);
            mIconPaint.setColor(palette.textPrimary);
            mIconPaint.setStrokeWidth(dpF(context, 1.9f));
            mIconPaint.setStrokeCap(Paint.Cap.ROUND);
            mIconPaint.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            float target = (isPressed() || isFocused()) && isEnabled() ? 1f : 0f;
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
            mPressAnimator.setDuration(target > 0f ? 85L : 140L);
            mPressAnimator.setInterpolator(HYPER_EASE);
            mPressAnimator.addUpdateListener(anim -> {
                mPressFraction = (float) anim.getAnimatedValue();
                invalidate();
            });
            mPressAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float radius = Math.min(dpF(getContext(), CIRCLE_RADIUS_DP), Math.min(w, h) * 0.5f - dpF(getContext(), 2f));
            if (radius <= 0f) return;

            float scale = 1f - 0.055f * mPressFraction;
            canvas.save();
            canvas.scale(scale, scale, cx, cy);

            // Pure hardware-accelerated soft diffused elevation halo
            float shadowY = cy + dpF(getContext(), 1.5f);
            if (mPalette.dark) {
                mShadowPaint.setColor(0x18000000);
                canvas.drawCircle(cx, shadowY, radius + dpF(getContext(), 2.2f), mShadowPaint);
                mShadowPaint.setColor(0x28000000);
                canvas.drawCircle(cx, shadowY, radius + dpF(getContext(), 1.0f), mShadowPaint);
            } else {
                mShadowPaint.setColor(0x04000000);
                canvas.drawCircle(cx, shadowY, radius + dpF(getContext(), 3.2f), mShadowPaint);
                mShadowPaint.setColor(0x06000000);
                canvas.drawCircle(cx, shadowY, radius + dpF(getContext(), 2.1f), mShadowPaint);
                mShadowPaint.setColor(0x08000000);
                canvas.drawCircle(cx, shadowY, radius + dpF(getContext(), 1.1f), mShadowPaint);
                mShadowPaint.setColor(0x0A000000);
                canvas.drawCircle(cx, shadowY, radius + dpF(getContext(), 0.3f), mShadowPaint);
            }

            // Frosted circular glass pill surface
            int normalFill = mPalette.dark ? 0xEB2A2A2D : 0xF7FFFFFF;
            int pressedFill = mPalette.dark ? 0xFF3A3A3E : 0xFFE8EAEE;
            mFillPaint.setColor(blendColor(normalFill, pressedFill, mPressFraction));
            canvas.drawCircle(cx, cy, radius, mFillPaint);

            // Delicate hairline specular rim
            if (mPalette.dark) {
                if (mRimTop != cy - radius || mRimBottom != cy + radius) {
                    mRimTop = cy - radius;
                    mRimBottom = cy + radius;
                    mRimPaint.setShader(new LinearGradient(0, mRimTop, 0, mRimBottom,
                            0x30FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
                }
                mRimPaint.setAlpha(255);
            } else {
                mRimPaint.setShader(null);
                mRimPaint.setColor(0x14000000);
            }
            canvas.drawCircle(cx, cy, radius - mRimPaint.getStrokeWidth() * 0.5f, mRimPaint);

            // Optically centered thin left chevron '<'
            float iconCx = cx - dpF(getContext(), 1.0f);
            float dx = dpF(getContext(), 3.3f);
            float dy = dpF(getContext(), 6.5f);
            mPath.reset();
            mPath.moveTo(iconCx + dx, cy - dy);
            mPath.lineTo(iconCx - dx, cy);
            mPath.lineTo(iconCx + dx, cy + dy);
            canvas.drawPath(mPath, mIconPaint);

            canvas.restore();
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelPressAnimator();
            super.onDetachedFromWindow();
        }

        private void cancelPressAnimator() {
            if (mPressAnimator != null) {
                mPressAnimator.cancel();
                mPressAnimator = null;
            }
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
    private abstract static class PreviewView extends View {
        final Matrix transform = new Matrix();
        final float[] coefficients = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        boolean mXiaoAiGlow = Config.isXiaoAiGlow(Config.DEFAULT_GLOW_STYLE);
        LinearGradient sparkle;

        PreviewView(Context context, Palette palette) {
            super(context);
            rebuildSparkle(palette);
        }

        final void rebuildSparkle(Palette palette) {
            if (mXiaoAiGlow) {
                sparkle = linear(
                        new float[]{0f, 0.34f, 0.68f, 1f},
                        palette.dark ? 0xFFFFA552 : 0xFFEA641C,
                        palette.dark ? 0xFFFF5C9E : 0xFFD92B77,
                        palette.dark ? 0xFFC185FF : 0xFF7D45EC,
                        palette.dark ? 0xFF66B8FF : 0xFF1E70EB);
            } else {
                sparkle = linear(null, palette.previewNebulaCyan, palette.previewNebulaViolet);
            }
        }

        final void setGlowStyle(boolean xiaoAi, boolean replayDemo) {
            boolean changed = mXiaoAiGlow != xiaoAi;
            mXiaoAiGlow = xiaoAi;
            if (changed) {
                onGlowStyleChanged(replayDemo);
                invalidate();
            } else if (replayDemo) {
                onGlowStyleChanged(true);
                invalidate();
            }
        }

        protected void onGlowStyleChanged(boolean replayDemo) {}

        static LinearGradient linear(float[] stops, int... colors) {
            return new LinearGradient(0, 0, 1, 0, colors, stops, Shader.TileMode.CLAMP);
        }

        Shader line(Shader shader, float left, float top, float right, float bottom) {
            float dx = right - left;
            float dy = bottom - top;
            coefficients[0] = coefficients[4] = dx;
            coefficients[1] = -dy;
            coefficients[2] = left;
            coefficients[3] = dy;
            coefficients[5] = top;
            transform.setValues(coefficients);
            shader.setLocalMatrix(transform);
            return shader;
        }

        Shader sweep(Shader shader, float cx, float cy, float angle) {
            transform.setRotate(angle);
            transform.postTranslate(cx, cy);
            shader.setLocalMatrix(transform);
            return shader;
        }
    }

    static final class SummaryPreviewView extends PreviewView {
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
        private LinearGradient badge, sparkleBadge;
        private LinearGradient sheen;
        private LinearGradient hairline;
        private Shader wave;
        private float mProgress;
        private float mWashProgress;
        private float mWashBoost;
        private ValueAnimator mAnimator;
        private ValueAnimator mWashAnimator;

        SummaryPreviewView(Context context, Palette palette) {
            super(context, palette);
            mPalette = palette;
            rebuildShaders();
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

        private void rebuildShaders() {
            badge = NebulaSummaryVisuals.createHorizontalBadgeGradient(0, 1, mPalette.dark, mXiaoAiGlow);
            sparkleBadge = NebulaSummaryVisuals.createHorizontalBadgeGradient(0, 1, mPalette.dark, mXiaoAiGlow);
            sheen = NebulaSummaryVisuals.createGlintSheenGradient(1, mPalette.dark, mXiaoAiGlow);
            hairline = NebulaSummaryVisuals.createHairlineGradient(0, 1, mPalette.dark, mXiaoAiGlow);
            wave = NebulaSummaryVisuals.createFeatheredWaveShader(1, 0, 1, mPalette.dark, mXiaoAiGlow);
        }

        @Override
        protected void onGlowStyleChanged(boolean replayDemo) {
            rebuildSparkle(mPalette);
            rebuildShaders();
            if (replayDemo && mSummaryEnabled) {
                startPreviewWash();
            }
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
                float cBase = (headerY + headerLineH * 0.5f) - (mCountPaint.ascent() + mCountPaint.descent()) * 0.5f;
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
                        mXiaoAiGlow,
                        s,
                        mWashBoost,
                        line(sparkleBadge, textLeft, sumLine1MidY - primaryR,
                                textLeft + NebulaSummaryVisuals.dualSparkleWidth(primaryR), sumLine1MidY + primaryR),
                        mFillPaint,
                        mSparklePath);

                float labelX = textLeft + dualW + dpF(getContext(), 2.6f);
                float labelW = mBadgeTextPaint.measureText(AI_SUMMARY_LABEL);
                float labelBase = sumLine1MidY - (mBadgeTextPaint.ascent() + mBadgeTextPaint.descent()) * 0.5f;

                mBadgeTextPaint.setShader(line(badge, labelX, 0, labelX + labelW, 0));
                mBadgeTextPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * s)));
                canvas.drawText(AI_SUMMARY_LABEL, labelX, labelBase, mBadgeTextPaint);
                mBadgeTextPaint.setShader(null);

                if (mWashBoost > 0.01f) {
                    float sheenW = Math.max(dpF(getContext(), 18f), labelW * 0.7f);
                    float sheenLeft = (textLeft - sheenW) + (dualW + labelW + sheenW * 2f) * mWashProgress;
                    mGlintTextPaint.setShader(line(sheen, sheenLeft, 0, sheenLeft + sheenW, 0));
                    mGlintTextPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * mWashBoost * s)));
                    canvas.drawText(AI_SUMMARY_LABEL, labelX, labelBase, mGlintTextPaint);
                    mGlintTextPaint.setShader(null);
                }

                // Feather-thin 0.75dp vertical fading Nebula hairline
                float hx = labelX + labelW + dpF(getContext(), 4.0f);
                float halfH = dpF(getContext(), 4.2f);
                mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 0.75f));
                transform.setScale(1, 2 * halfH);
                transform.postTranslate(0, sumLine1MidY - halfH);
                hairline.setLocalMatrix(transform);
                mStrokePaint.setShader(hairline);
                mStrokePaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * s)));
                canvas.drawLine(hx, sumLine1MidY - halfH, hx, sumLine1MidY + halfH, mStrokePaint);
                mStrokePaint.setShader(null);

                prefixAdvance = (hx - textLeft + dpF(getContext(), 4.4f)) * s;
            }

            int nebulaLine1 = NebulaSummaryVisuals.tintSummaryBodyColor(
                    mPalette.previewSkeletonPrimary, mPalette.dark, mXiaoAiGlow);
            int nebulaLine2 = NebulaSummaryVisuals.tintSummaryBodyColor(
                    mPalette.previewSkeletonSecondary, mPalette.dark, mXiaoAiGlow);

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
                NebulaSummaryVisuals.drawTiltedNebulaWave(
                        canvas, textLeft - dpF(getContext(), 2f), zoneTop, textRight + dpF(getContext(), 2f), zoneBot,
                        waveX, bandW, mWashBoost * s, mPalette.dark, wave, mShaderMatrix, mWavePaint);
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
     * Dedicated HyperOS settings illustration for "AI 概括":
     * OFF shows a tall, dense 5-line single long notification card; ON smoothly condenses
     * the card vertically into a sleek compact card with single-sparkle "✦ AI 概括 |" and
     * 1-2 tinted summary lines, playing a 780ms tilted optical Wave Wash sweep on toggle
     * or glow style switch.
     */
    static final class RecapPreviewView extends PreviewView {
        private static final float STAGE_HEIGHT_DP = 166f;
        private static final float MAX_STAGE_WIDTH_DP = 320f;
        private static final String AI_RECAP_LABEL = NebulaSummaryVisuals.RECAP_LABEL;
        private static final float[] LONG_LINE_RATIOS = {0.96f, 0.92f, 0.88f, 0.94f, 0.62f};

        private final Palette mPalette;
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mStagePath = new Path();
        private final Path mCardPath = new Path();
        private final Path mIconPath = new Path();
        private final Path mSparklePath = new Path();
        private final RectF mRect = new RectF();
        private final Matrix mShaderMatrix = new Matrix();

        private boolean mRecapEnabled;
        private LinearGradient badge, sparkleBadge;
        private LinearGradient sheen;
        private LinearGradient hairline;
        private Shader wave;
        private float mProgress;
        private float mWashProgress;
        private float mWashBoost;
        private ValueAnimator mAnimator;
        private ValueAnimator mWashAnimator;

        RecapPreviewView(Context context, Palette palette) {
            super(context, palette);
            mPalette = palette;
            rebuildShaders();
            mFillPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mWavePaint.setStyle(Paint.Style.FILL);
            mBadgeTextPaint.setTypeface(typeface(600));
            mBadgeTextPaint.setTextSize(dpF(context, 9.2f));
            mBadgeTextPaint.setLetterSpacing(0.015f);
            mGlintTextPaint.setTypeface(typeface(600));
            mGlintTextPaint.setTextSize(dpF(context, 9.2f));
            mGlintTextPaint.setLetterSpacing(0.015f);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private void rebuildShaders() {
            badge = NebulaSummaryVisuals.createHorizontalBadgeGradient(0, 1, mPalette.dark, mXiaoAiGlow);
            sparkleBadge = NebulaSummaryVisuals.createHorizontalBadgeGradient(0, 1, mPalette.dark, mXiaoAiGlow);
            sheen = NebulaSummaryVisuals.createGlintSheenGradient(1, mPalette.dark, mXiaoAiGlow);
            hairline = NebulaSummaryVisuals.createHairlineGradient(0, 1, mPalette.dark, mXiaoAiGlow);
            wave = NebulaSummaryVisuals.createFeatheredWaveShader(1, 0, 1, mPalette.dark, mXiaoAiGlow);
        }

        @Override
        protected void onGlowStyleChanged(boolean replayDemo) {
            rebuildSparkle(mPalette);
            rebuildShaders();
            if (replayDemo && mRecapEnabled) {
                startPreviewWash();
            }
        }

        void setRecapEnabled(boolean enabled, boolean animate) {
            boolean changed = mRecapEnabled != enabled;
            mRecapEnabled = enabled;
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

            // OFF: tall dense 5-line single card; ON: sleek condensed 2-line single card
            float tallCardH = dpF(getContext(), 124f);
            float compactCardH = dpF(getContext(), 76f);
            float cardH = lerp(tallCardH, compactCardH, s);
            float cardTop = (totalH - cardH) * 0.5f;
            float cardBottom = cardTop + cardH;

            mRect.set(left, cardTop, right, cardBottom);
            buildSquirclePath(mRect, cardRadius, mCardPath);

            // Clean neutral single notification surface (no colored border)
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

            int cardClip = canvas.save();
            canvas.clipPath(mCardPath);

            // App icon squircle
            float iconSize = dpF(getContext(), 25f);
            float iconLeft = left + dpF(getContext(), 11f);
            float iconTop = cardTop + dpF(getContext(), 13f);
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 7.2f), mIconPath);
            mFillPaint.setColor(mPalette.previewNormalIcon);
            canvas.drawPath(mIconPath, mFillPaint);

            float textLeft = iconLeft + iconSize + dpF(getContext(), 10f);
            float textRight = right - dpF(getContext(), 12f);
            float textMaxW = Math.max(0f, textRight - textLeft);

            // Header row: title skeleton bar + subtle time pill
            float headerLineH = dpF(getContext(), 5.4f);
            float headerY = cardTop + dpF(getContext(), 15f);
            mFillPaint.setColor(mPalette.previewSkeletonPrimary);
            mRect.set(textLeft, headerY, textLeft + textMaxW * 0.42f, headerY + headerLineH);
            canvas.drawRoundRect(mRect, headerLineH * 0.5f, headerLineH * 0.5f, mFillPaint);

            float timeW = dpF(getContext(), 16f);
            float timeH = dpF(getContext(), 4f);
            float timeTop = headerY + (headerLineH - timeH) * 0.5f;
            mFillPaint.setColor(mPalette.previewSkeletonSecondary);
            mRect.set(textRight - timeW, timeTop, textRight, timeTop + timeH);
            canvas.drawRoundRect(mRect, timeH * 0.5f, timeH * 0.5f, mFillPaint);

            // Line 1: transitions from raw long paragraph start to [✦ AI 概括 | concise tinted bar]
            float rawLineH = dpF(getContext(), 4.4f);
            float recapLineH = dpF(getContext(), 4.8f);
            float line1H = lerp(rawLineH, recapLineH, s);
            float line1Y = lerp(headerY + headerLineH + dpF(getContext(), 9f), cardTop + dpF(getContext(), 36f), s);
            float line1MidY = line1Y + line1H * 0.5f;
            float prefixAdvance = 0f;

            if (s > 0.05f) {
                float sparkleR = dpF(getContext(), 3.8f) * (0.72f + 0.28f * s);
                float sparkleW = NebulaSummaryVisuals.drawSingleSparkle(
                        canvas,
                        textLeft,
                        line1MidY,
                        sparkleR,
                        mPalette.dark,
                        mXiaoAiGlow,
                        s,
                        mWashBoost,
                        line(sparkleBadge, textLeft, line1MidY - sparkleR,
                                textLeft + NebulaSummaryVisuals.singleSparkleWidth(sparkleR), line1MidY + sparkleR),
                        mFillPaint,
                        mSparklePath);

                float labelX = textLeft + sparkleW + dpF(getContext(), 2.4f);
                float labelW = mBadgeTextPaint.measureText(AI_RECAP_LABEL);
                float labelBase = line1MidY - (mBadgeTextPaint.ascent() + mBadgeTextPaint.descent()) * 0.5f;

                mBadgeTextPaint.setShader(line(badge, labelX, 0, labelX + labelW, 0));
                mBadgeTextPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * s)));
                canvas.drawText(AI_RECAP_LABEL, labelX, labelBase, mBadgeTextPaint);
                mBadgeTextPaint.setShader(null);

                if (mWashBoost > 0.01f) {
                    float sheenW = Math.max(dpF(getContext(), 18f), labelW * 0.7f);
                    float sheenLeft = (textLeft - sheenW) + (sparkleW + labelW + sheenW * 2f) * mWashProgress;
                    mGlintTextPaint.setShader(line(sheen, sheenLeft, 0, sheenLeft + sheenW, 0));
                    mGlintTextPaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * mWashBoost * s)));
                    canvas.drawText(AI_RECAP_LABEL, labelX, labelBase, mGlintTextPaint);
                    mGlintTextPaint.setShader(null);
                }

                // Feather-thin 0.75dp vertical fading hairline
                float hx = labelX + labelW + dpF(getContext(), 4.0f);
                float halfH = dpF(getContext(), 4.2f);
                mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 0.75f));
                transform.setScale(1, 2 * halfH);
                transform.postTranslate(0, line1MidY - halfH);
                hairline.setLocalMatrix(transform);
                mStrokePaint.setShader(hairline);
                mStrokePaint.setAlpha(NebulaSummaryVisuals.clamp255(Math.round(255f * s)));
                canvas.drawLine(hx, line1MidY - halfH, hx, line1MidY + halfH, mStrokePaint);
                mStrokePaint.setShader(null);

                prefixAdvance = (hx - textLeft + dpF(getContext(), 4.4f)) * s;
            }

            int tintedLine1 = NebulaSummaryVisuals.tintSummaryBodyColor(
                    mPalette.previewSkeletonPrimary, mPalette.dark, mXiaoAiGlow);
            int tintedLine2 = NebulaSummaryVisuals.tintSummaryBodyColor(
                    mPalette.previewSkeletonSecondary, mPalette.dark, mXiaoAiGlow);

            mFillPaint.setShader(null);
            mFillPaint.setColor(blendColor(mPalette.previewSkeletonSecondary, tintedLine1, s));
            float l1Start = textLeft + prefixAdvance;
            float l1Ratio = lerp(LONG_LINE_RATIOS[0], 0.94f, s);
            float l1End = Math.min(textRight, l1Start + Math.max(0f, textRight - l1Start) * l1Ratio);
            mRect.set(l1Start, line1Y, l1End, line1Y + line1H);
            canvas.drawRoundRect(mRect, line1H * 0.5f, line1H * 0.5f, mFillPaint);

            // Line 2: transitions from raw dense line 2 into concise tinted line 2
            float rawGap = dpF(getContext(), 7.2f);
            float recapGap = dpF(getContext(), 7.5f);
            float line2Y = line1Y + line1H + lerp(rawGap, recapGap, s);
            mFillPaint.setColor(blendColor(mPalette.previewSkeletonSecondary, tintedLine2, s));
            float l2Ratio = lerp(LONG_LINE_RATIOS[1], 0.68f, s);
            mRect.set(textLeft, line2Y, textLeft + textMaxW * l2Ratio, line2Y + line1H);
            canvas.drawRoundRect(mRect, line1H * 0.5f, line1H * 0.5f, mFillPaint);

            // Lines 3..5: dense long-message body lines that smoothly collapse & fade away when ON
            float extraAlpha = Math.max(0f, Math.min(1f, 1f - s * 1.45f));
            if (extraAlpha > 0.02f) {
                float step = rawLineH + rawGap;
                mFillPaint.setColor(withAlpha(mPalette.previewSkeletonSecondary, extraAlpha));
                for (int idx = 2; idx < LONG_LINE_RATIOS.length; idx++) {
                    float ly = line2Y + step * (idx - 1) - dpF(getContext(), 8f) * s;
                    mRect.set(textLeft, ly, textLeft + textMaxW * LONG_LINE_RATIOS[idx], ly + rawLineH);
                    canvas.drawRoundRect(mRect, rawLineH * 0.5f, rawLineH * 0.5f, mFillPaint);
                }
            }

            // One-shot tilted optical Wave Wash gliding across the condensed recap lines
            if (s > 0.10f && mWashBoost > 0.01f) {
                float zoneTop = line1Y - dpF(getContext(), 6f);
                float zoneBot = line2Y + line1H + dpF(getContext(), 6f);
                float bandW = Math.max(dpF(getContext(), 44f), textMaxW * 0.38f);
                float waveX = (textLeft - bandW * 0.35f) + (textMaxW + bandW * 0.7f) * mWashProgress;
                NebulaSummaryVisuals.drawTiltedNebulaWave(
                        canvas, textLeft - dpF(getContext(), 2f), zoneTop, textRight + dpF(getContext(), 2f), zoneBot,
                        waveX, bandW, mWashBoost * s, mPalette.dark, wave, mShaderMatrix, mWavePaint);
            }

            canvas.restoreToCount(cardClip);
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
     * Apple Intelligence-inspired unified merged card with internal hairlines, shared bottom
     * "[Apple Intelligence 7-lobe icon] 重要通知" Footer, and crisp perimeter-only dual-style glow.
     */
    static final class PriorityPreviewView extends PreviewView {
        private static final float STAGE_HEIGHT_DP = 216f;
        private static final float MAX_STAGE_WIDTH_DP = 320f;
        private static final String PRIORITY_LABEL = "重要通知";
        private static final float[] RIM_STOPS = {0f, 0.25f, 0.50f, 0.75f, 1f};
        private static final float[] COMET_STOPS = {0f, 0.68f, 0.80f, 0.88f, 0.95f, 1f};
        private static final float[] AURA_WIDTHS_DP = {9.6f, 6.2f, 3.4f, 1.6f};
        private static final float[] AURA_ALPHAS_DARK = {0.045f, 0.095f, 0.165f, 0.235f};
        private static final float[] AURA_ALPHAS_LIGHT = {0.025f, 0.055f, 0.095f, 0.140f};

        private final Palette mPalette;
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mAuraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mStagePath = new Path();
        private final Path mCardPath = new Path();
        private final Path mIconPath = new Path();
        private final Path mAppleLogoPath = new Path();
        private final RectF mRect = new RectF();
        private boolean mPriorityEnabled;
        private SweepGradient cardRim, aura, comet;
        private float mProgress;
        private float mShimmerProgress;
        private float mShimmerBoost;
        private ValueAnimator mAnimator;
        private ValueAnimator mShimmerAnimator;

        PriorityPreviewView(Context context, Palette palette) {
            super(context, palette);
            mPalette = palette;
            rebuildShaders();
            mFillPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            mAuraPaint.setStyle(Paint.Style.STROKE);
            mAuraPaint.setStrokeJoin(Paint.Join.ROUND);
            mTextPaint.setTypeface(typeface(500));
            mTextPaint.setTextSize(dpF(context, 9.8f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private void rebuildShaders() {
            boolean dark = mPalette.dark;
            if (mXiaoAiGlow) {
                int orange = dark ? 0xFFFF9E57 : GlowStyle.XIAOAI_LIGHT_ORANGE;
                int rose = dark ? 0xFFFF6BB8 : GlowStyle.XIAOAI_LIGHT_ROSE;
                int violet = dark ? 0xFFC280FF : GlowStyle.XIAOAI_LIGHT_VIOLET;
                int blue = dark ? 0xFF5CA8FF : GlowStyle.XIAOAI_LIGHT_BLUE;
                cardRim = new SweepGradient(0, 0, new int[]{
                        withAlpha(orange, dark ? .94f : .86f),
                        withAlpha(rose, dark ? .92f : .84f),
                        withAlpha(violet, dark ? .92f : .85f),
                        withAlpha(blue, dark ? .92f : .84f),
                        withAlpha(orange, dark ? .94f : .86f)}, RIM_STOPS);
                aura = new SweepGradient(0, 0, new int[]{orange, rose, violet, blue, orange}, RIM_STOPS);
                comet = new SweepGradient(0, 0, new int[]{0, 0, withAlpha(orange, .76f),
                        withAlpha(0xFFFFF6F2, .95f), withAlpha(rose, .76f), 0}, COMET_STOPS);
            } else {
                int cyan = mPalette.previewNebulaCyan;
                int azure = mPalette.previewNebulaAzure;
                int violet = mPalette.previewNebulaViolet;
                int moon = mPalette.previewNebulaMoon;
                cardRim = new SweepGradient(0, 0, new int[]{
                        withAlpha(cyan, dark ? .92f : .85f),
                        withAlpha(azure, dark ? .88f : .82f),
                        withAlpha(violet, dark ? .90f : .84f),
                        withAlpha(moon, dark ? .94f : .78f),
                        withAlpha(cyan, dark ? .92f : .85f)}, RIM_STOPS);
                aura = new SweepGradient(0, 0, new int[]{cyan, azure, violet, moon, cyan}, RIM_STOPS);
                comet = new SweepGradient(0, 0, new int[]{0, 0, withAlpha(cyan, .74f),
                        withAlpha(0xFFFFFFFF, .95f), withAlpha(violet, .72f), 0}, COMET_STOPS);
            }
        }

        @Override
        protected void onGlowStyleChanged(boolean replayDemo) {
            rebuildSparkle(mPalette);
            rebuildShaders();
            if (replayDemo && mPriorityEnabled) {
                startPreviewShimmer();
            }
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

            float outerPadH = dpF(getContext(), 16f);
            float cardLeft = stageLeft + outerPadH;
            float cardRight = stageRight - outerPadH;
            float rowH = dpF(getContext(), 41f);
            float normalGap = dpF(getContext(), 9f);
            float footerH = dpF(getContext(), 28f);
            float sectionGap = dpF(getContext(), 14f);
            float cardRadius = dpF(getContext(), 13.5f);

            // OFF layout: 3 ordinary independent cards
            float offTotalH = rowH * 3f + normalGap * 2f;
            float offStartY = (totalH - offTotalH) * 0.5f;

            // ON layout: 1 unified merged priority card (2 rows + shared bottom Footer) + 1 ordinary card below
            float mergedH = rowH * 2f + footerH;
            float onTotalH = mergedH + sectionGap + rowH;
            float onStartY = Math.max(dpF(getContext(), 12f), (totalH - onTotalH) * 0.5f);

            float card1Y = lerp(offStartY, onStartY, p);
            float card2Y = lerp(offStartY + rowH + normalGap, onStartY + rowH, p);
            float card3Y = lerp(offStartY + (rowH + normalGap) * 2f, onStartY + mergedH + sectionGap, p);

            int activeCardFill = mPalette.dark ? 0xFF1F1E24 : 0xFFFFFFFF;
            int mergedFill = blendColor(mPalette.previewNormalCard, activeCardFill, p);
            int pIconBg = blendColor(mPalette.previewNormalIcon,
                    mPalette.dark ? 0xFF33313B : 0xFFE9ECF2, p);
            int pLine1 = blendColor(mPalette.previewSkeletonPrimary,
                    mPalette.dark ? 0xE6FFFFFF : 0xCC181B24, p);
            int pLine2 = blendColor(mPalette.previewSkeletonSecondary,
                    mPalette.dark ? 0x7AFFFFFF : 0x62181B24, p);

            // 1. Unified merged card backdrop + perimeter glow (fades in as cards merge)
            if (p < 0.995f) {
                float sepAlpha = 1f - p;
                drawCardShell(canvas, cardLeft, card1Y, cardRight, card1Y + rowH,
                        cardRadius, withAlpha(mPalette.previewNormalCard, sepAlpha));
                drawCardShell(canvas, cardLeft, card2Y, cardRight, card2Y + rowH,
                        cardRadius, withAlpha(mPalette.previewNormalCard, sepAlpha));
            }
            if (p > 0.02f) {
                float curMergedBot = card2Y + rowH + footerH * p;
                mRect.set(cardLeft, card1Y, cardRight, curMergedBot);
                buildSquirclePath(mRect, cardRadius, mCardPath);
                float cx = mRect.centerX();
                float cy = mRect.centerY();
                float rot = -28f + mShimmerProgress * 360f;

                // 1a. Crisp Figma-calibrated Gaussian-feathered outer halo purely outside card
                int haloSaved = canvas.save();
                canvas.clipOutPath(mCardPath);
                mAuraPaint.setShader(sweep(aura, cx, cy, rot));
                float[] alphas = mPalette.dark ? AURA_ALPHAS_DARK : AURA_ALPHAS_LIGHT;
                for (int i = 0; i < AURA_WIDTHS_DP.length; i++) {
                    float a = alphas[i] * (1f + 0.45f * mShimmerBoost) * p;
                    mAuraPaint.setStrokeWidth(dpF(getContext(), AURA_WIDTHS_DP[i]));
                    mAuraPaint.setAlpha(Math.min(255, Math.round(a * 255f)));
                    canvas.drawPath(mCardPath, mAuraPaint);
                }
                canvas.restoreToCount(haloSaved);
                mAuraPaint.setShader(null);

                // 1b. Clean unobstructed card surface (zero muddy inward color bleed)
                drawCardShell(canvas, cardLeft, card1Y, cardRight, curMergedBot,
                        cardRadius, withAlpha(mergedFill, p));
            }

            // 2. Priority row 1 & row 2 skeleton contents
            drawSkeletonRowContent(canvas, cardLeft, card1Y, cardRight, card1Y + rowH,
                    pIconBg, pLine1, pLine2, 0.48f, 0.78f);
            drawSkeletonRowContent(canvas, cardLeft, card2Y, cardRight, card2Y + rowH,
                    pIconBg, pLine1, pLine2, 0.40f, 0.68f);

            // 3. Internal hairlines + shared bottom "[7-lobe icon] 重要通知" Footer
            if (p > 0.04f) {
                float contentInset = dpF(getContext(), 11f);
                float lineL = cardLeft + contentInset;
                float lineR = cardRight - contentInset;
                int hairlineColor = mPalette.dark ? 0x2EFFFFFF : 0x1A000000;
                mStrokePaint.setShader(null);
                mStrokePaint.setStrokeCap(Paint.Cap.BUTT);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 0.65f));
                mStrokePaint.setColor(withAlpha(hairlineColor, p));

                // Divider between Row 1 and Row 2
                canvas.drawLine(lineL, card2Y, lineR, card2Y, mStrokePaint);
                // Divider above Footer
                float footerTop = card2Y + rowH;
                canvas.drawLine(lineL, footerTop, lineR, footerTop, mStrokePaint);

                // Footer icon + label (left-aligned to content leading edge)
                float footerCenterY = footerTop + (footerH * p) * 0.5f;
                float logoSize = dpF(getContext(), 11.2f);
                float logoGap = dpF(getContext(), 4.8f);
                int inkColor = mPalette.dark ? 0xD8FFFFFF : 0x9E000000;
                mIconPaint.setColor(withAlpha(inkColor, p));
                ReduceInterruptions.drawAppleIntelMono(
                        canvas, lineL, footerCenterY - logoSize * 0.5f, logoSize,
                        mIconPaint, 1.85f, mAppleLogoPath);
                if (mShimmerBoost > 0.01f) {
                    int glint = mXiaoAiGlow
                            ? (mPalette.dark ? 0xFFFFB28A : 0xFFE53882)
                            : (mPalette.dark ? 0xFF8BEAFF : 0xFF3D8BFF);
                    mIconPaint.setColor(withAlpha(glint, 0.55f * mShimmerBoost * p));
                    ReduceInterruptions.drawAppleIntelMono(
                            canvas, lineL, footerCenterY - logoSize * 0.5f, logoSize,
                            mIconPaint, 2.0f, mAppleLogoPath);
                }

                mTextPaint.setColor(withAlpha(inkColor, p));
                float baseline = footerCenterY - (mTextPaint.ascent() + mTextPaint.descent()) * 0.5f;
                canvas.drawText(PRIORITY_LABEL, lineL + logoSize + logoGap, baseline, mTextPaint);

                // 4. Crisp thin glass rim around the unified merged card
                float curMergedBot = card2Y + rowH + footerH * p;
                float rimInset = dpF(getContext(), 0.4f);
                mRect.set(cardLeft + rimInset, card1Y + rimInset,
                        cardRight - rimInset, curMergedBot - rimInset);
                buildSquirclePath(mRect, Math.max(0f, cardRadius - rimInset), mCardPath);
                float cx = mRect.centerX();
                float cy = mRect.centerY();
                float rot = -28f + mShimmerProgress * 360f;

                mStrokePaint.setAlpha(Math.round(255 * p));
                mStrokePaint.setShader(sweep(cardRim, cx, cy, rot));
                mStrokePaint.setStrokeWidth(dpF(getContext(), 1.25f));
                canvas.drawPath(mCardPath, mStrokePaint);

                mStrokePaint.setShader(null);
                mStrokePaint.setColor(withAlpha(mPalette.dark ? 0x52FFFFFF : 0x38FFFFFF, p));
                mStrokePaint.setStrokeWidth(dpF(getContext(), 0.45f));
                canvas.drawPath(mCardPath, mStrokePaint);

                if (mShimmerBoost > 0.01f) {
                    mStrokePaint.setAlpha(Math.round(255 * p * mShimmerBoost));
                    mStrokePaint.setShader(sweep(comet, cx, cy, -110f + mShimmerProgress * 360f));
                    mStrokePaint.setStrokeWidth(dpF(getContext(), 1.5f));
                    canvas.drawPath(mCardPath, mStrokePaint);
                }
                mStrokePaint.setShader(null);
            }

            // 5. Third notification card (ordinary notification separated cleanly below)
            drawNormalCard(
                    canvas,
                    cardLeft, card3Y, cardRight, card3Y + rowH,
                    cardRadius,
                    0.34f, 0.58f,
                    lerp(1.0f, 0.78f, p));
        }

        private void drawCardShell(Canvas canvas, float left, float top, float right, float bottom,
                                   float radius, int fillColor) {
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);
            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                float a = Color.alpha(fillColor) / 255f;
                mFillPaint.setColor(withAlpha(0x0A000000, a));
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.7f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }
            mFillPaint.setColor(fillColor);
            canvas.drawPath(mCardPath, mFillPaint);
        }

        private void drawSkeletonRowContent(
                Canvas canvas,
                float left, float top, float right, float bottom,
                int iconBg,
                int linePrimaryColor,
                int lineSecondaryColor,
                float line1Ratio,
                float line2Ratio) {
            float rowH = bottom - top;
            float iconSize = dpF(getContext(), 21.5f);
            float iconLeft = left + dpF(getContext(), 11f);
            float iconTop = top + (rowH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6.5f), mIconPath);
            mFillPaint.setShader(null);
            mFillPaint.setColor(iconBg);
            canvas.drawPath(mIconPath, mFillPaint);

            float textLeft = iconLeft + iconSize + dpF(getContext(), 9f);
            float textMaxW = Math.max(0f, right - dpF(getContext(), 12f) - textLeft);
            float line1H = dpF(getContext(), 5.2f);
            float line2H = dpF(getContext(), 4.2f);
            float lineGap = dpF(getContext(), 5.2f);
            float linesTop = top + (rowH - (line1H + lineGap + line2H)) * 0.5f;

            mFillPaint.setColor(linePrimaryColor);
            mRect.set(textLeft, linesTop, textLeft + textMaxW * line1Ratio, linesTop + line1H);
            canvas.drawRoundRect(mRect, line1H * 0.5f, line1H * 0.5f, mFillPaint);

            float timeW = dpF(getContext(), 15f);
            float timeH = dpF(getContext(), 3.8f);
            float timeTop = linesTop + (line1H - timeH) * 0.5f;
            mFillPaint.setColor(withAlpha(lineSecondaryColor, 0.85f));
            mRect.set(right - dpF(getContext(), 11f) - timeW, timeTop,
                    right - dpF(getContext(), 11f), timeTop + timeH);
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
            drawCardShell(canvas, left, top, right, bottom, radius,
                    withAlpha(mPalette.previewNormalCard, alphaScale));
            drawSkeletonRowContent(canvas, left, top, right, bottom,
                    withAlpha(mPalette.previewNormalIcon, alphaScale),
                    withAlpha(mPalette.previewSkeletonPrimary, alphaScale),
                    withAlpha(mPalette.previewSkeletonSecondary, alphaScale),
                    line1Ratio, line2Ratio);
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
    static final class ReducePreviewView extends PreviewView {
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

        private SweepGradient mPillBorderSweep;
        private LinearGradient mPillTextFallback;
        private final RuntimeShader mPillTextShader = GlowStyle.createIslandTextGlowShader();

        private boolean mReduceEnabled;
        private SweepGradient aura, cardRim;
        private float mProgress;
        private float mShimmerPhaseDeg;
        private ValueAnimator mAnimator;
        private ValueAnimator mShimmerAnimator;

        ReducePreviewView(Context context, Palette palette) {
            super(context, palette);
            mPalette = palette;
            mAppleIconPainter = new ReduceInterruptions.AppleIntelIridescentPainter(true);
            rebuildShaders();
            mFillPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mPillBorderPaint.setStyle(Paint.Style.STROKE);
            mPillBorderPaint.setStrokeWidth(dpF(context, 1.35f));
            mPillTextPaint.setTypeface(typeface(600));
            mPillTextPaint.setTextSize(dpF(context, 9.6f));
            mBadgeTextPaint.setTypeface(typeface(600));
            mBadgeTextPaint.setTextSize(dpF(context, 8.8f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private void rebuildShaders() {
            float[] stops = {0, .24f, .50f, .74f, 1};
            boolean dark = mPalette.dark;
            if (mXiaoAiGlow) {
                int orange = dark ? GlowStyle.XIAOAI_DARK_ORANGE : GlowStyle.XIAOAI_LIGHT_ORANGE;
                int blue = dark ? GlowStyle.XIAOAI_DARK_BLUE : GlowStyle.XIAOAI_LIGHT_BLUE;
                int rose = dark ? GlowStyle.XIAOAI_DARK_ROSE : GlowStyle.XIAOAI_LIGHT_ROSE;
                int violet = dark ? GlowStyle.XIAOAI_DARK_VIOLET : GlowStyle.XIAOAI_LIGHT_VIOLET;
                aura = new SweepGradient(0, 0, new int[]{orange, blue, rose, violet, orange}, stops);
                cardRim = new SweepGradient(0, 0, new int[]{
                        withAlpha(orange, dark ? .94f : .88f),
                        withAlpha(blue, dark ? .90f : .84f),
                        withAlpha(rose, dark ? .92f : .86f),
                        withAlpha(violet, dark ? .92f : .86f),
                        withAlpha(orange, dark ? .94f : .88f)}, stops);
                mPillBorderSweep = new SweepGradient(0, 0, new int[]{
                        GlowStyle.XIAOAI_DARK_ORANGE, GlowStyle.XIAOAI_DARK_BLUE,
                        GlowStyle.XIAOAI_DARK_ROSE, GlowStyle.XIAOAI_DARK_VIOLET,
                        GlowStyle.XIAOAI_DARK_ORANGE}, stops);
                mPillTextFallback = linear(new float[]{0f, 0.34f, 0.68f, 1f},
                        0xFFFFB876, 0xFFFF86C2, 0xFFCC9EFF, 0xFF7FC8FF);
            } else {
                int cyan = mPalette.previewNebulaCyan;
                int azure = mPalette.previewNebulaAzure;
                int violet = mPalette.previewNebulaViolet;
                int moon = mPalette.previewNebulaMoon;
                aura = new SweepGradient(0, 0, new int[]{cyan, azure, violet, moon, cyan}, stops);
                cardRim = new SweepGradient(0, 0, new int[]{withAlpha(cyan, dark ? .84f : .78f),
                        withAlpha(azure, dark ? .80f : .74f), withAlpha(violet, dark ? .84f : .78f),
                        withAlpha(moon, dark ? .86f : .65f), withAlpha(cyan, dark ? .84f : .78f)}, stops);
                mPillBorderSweep = new SweepGradient(0, 0, new int[]{
                        GlowStyle.NEBULA_DARK_CYAN, GlowStyle.NEBULA_DARK_AZURE,
                        GlowStyle.NEBULA_DARK_VIOLET, GlowStyle.NEBULA_DARK_MOON,
                        GlowStyle.NEBULA_DARK_CYAN}, stops);
                mPillTextFallback = linear(new float[]{0f, 0.38f, 0.72f, 1f},
                        0xFF80EEFF, 0xFF8EC8FF, 0xFFCCB2FF, 0xFFEDF8FF);
            }
        }

        @Override
        protected void onGlowStyleChanged(boolean replayDemo) {
            rebuildSparkle(mPalette);
            rebuildShaders();
            if (replayDemo) {
                syncShimmerState();
            }
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

            // 1. Top "减少干扰" Super Island glow pill (pure-black island + flowing style border & glowing text)
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
                float pillCx = (pillLeft + pillRight) * 0.5f;
                float pillCy = pillTop + pillH * 0.5f;
                float pillR = pillH * 0.5f;

                mRect.set(pillLeft, pillTop, pillRight, pillTop + pillH);
                float pillRot = -32f + mShimmerPhaseDeg;

                // Soft outward island plasma halo
                mStrokePaint.setShader(sweep(mPillBorderSweep, pillCx, pillCy, pillRot));
                mStrokePaint.setStrokeWidth(dpF(getContext(), 4.4f));
                mStrokePaint.setAlpha(Math.round((mPalette.dark ? 0.16f : 0.13f) * r * 255f));
                canvas.drawRoundRect(mRect, pillR, pillR, mStrokePaint);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 2.2f));
                mStrokePaint.setAlpha(Math.round((mPalette.dark ? 0.24f : 0.20f) * r * 255f));
                canvas.drawRoundRect(mRect, pillR, pillR, mStrokePaint);
                mStrokePaint.setShader(null);
                mStrokePaint.setAlpha(255);

                // Authentic pure-black Super Island pill body
                mFillPaint.setShader(null);
                mFillPaint.setColor(withAlpha(0xFF0A0A0E, r));
                canvas.drawRoundRect(mRect, pillR, pillR, mFillPaint);

                // Vivid Super XiaoAi / Nebula glowing pill rim
                mPillBorderPaint.setShader(sweep(mPillBorderSweep, pillCx, pillCy, pillRot));
                mPillBorderPaint.setStrokeWidth(dpF(getContext(), 1.35f));
                mPillBorderPaint.setAlpha(Math.round(255f * r));
                canvas.drawRoundRect(mRect, pillR, pillR, mPillBorderPaint);
                mPillBorderPaint.setShader(null);

                mAppleIconPainter.draw(
                        canvas,
                        pillLeft + pillPadStart,
                        pillTop + (pillH - iconSize) * 0.5f,
                        iconSize,
                        1.72f,
                        0.06f,
                        mShimmerPhaseDeg,
                        r);

                // Flowing gradient + soft white sweep highlight on "减少干扰" text
                float textX = pillLeft + pillPadStart + iconSize + iconTextGap;
                float baseline = pillCy - (mPillTextPaint.ascent() + mPillTextPaint.descent()) * 0.5f;
                if (mPillTextShader != null) {
                    float cycle = (mShimmerPhaseDeg % 360f) / 360f;
                    float sheenT = (cycle * 3f) % 1f;
                    float sheenPos = -0.35f + 1.70f * sheenT;
                    mPillTextShader.setFloatUniform("uOriginX", textX);
                    mPillTextShader.setFloatUniform("uWidth", Math.max(labelW, 1f));
                    mPillTextShader.setFloatUniform("uPhase", cycle * 2f);
                    mPillTextShader.setFloatUniform("uSheenPos", sheenPos);
                    mPillTextShader.setFloatUniform("uStyle", mXiaoAiGlow ? 1f : 0f);
                    mPillTextShader.setFloatUniform("uDark", 1f);
                    mPillTextPaint.setShader(mPillTextShader);
                } else {
                    mPillTextPaint.setShader(line(mPillTextFallback, textX, 0f, textX + labelW, 0f));
                }
                mPillTextPaint.setAlpha(Math.round(255f * r));
                mPillTextPaint.setShadowLayer(
                        dpF(getContext(), 2.8f), 0f, 0f,
                        withAlpha(GlowStyle.islandTextShadowColor(mXiaoAiGlow), r));
                canvas.drawText(MODE_LABEL, textX, baseline, mPillTextPaint);
                mPillTextPaint.clearShadowLayer();
                mPillTextPaint.setShader(null);
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

            // Subtle outward-clipped glow on VIP breakthrough card
            float cardRot = -32f;
            if (r > 0.04f) {
                mStrokePaint.setShader(sweep(aura, cx, cy, cardRot));
                int saved = canvas.save();
                canvas.clipOutPath(mCardPath);
                mStrokePaint.setStrokeWidth(dpF(getContext(), mXiaoAiGlow ? 7.2f : 6.0f));
                mStrokePaint.setAlpha(Math.round((mPalette.dark ? (mXiaoAiGlow ? 0.11f : 0.09f) : 0.07f) * r * 255f));
                canvas.drawPath(mCardPath, mStrokePaint);
                mStrokePaint.setStrokeWidth(dpF(getContext(), mXiaoAiGlow ? 3.4f : 3.0f));
                mStrokePaint.setAlpha(Math.round((mPalette.dark ? (mXiaoAiGlow ? 0.18f : 0.14f) : 0.11f) * r * 255f));
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

            int activeCardFill = mXiaoAiGlow
                    ? (mPalette.dark ? 0xFF191822 : 0xFFFFFFFF)
                    : mPalette.previewNebulaCard;
            int fill = blendColor(mPalette.previewNormalCard, activeCardFill, r);
            mFillPaint.setColor(fill);
            canvas.drawPath(mCardPath, mFillPaint);

            if (mXiaoAiGlow && mPalette.dark && r > 0.04f) {
                int innerSaved = canvas.save();
                canvas.clipPath(mCardPath);
                mStrokePaint.setShader(sweep(aura, cx, cy, cardRot));
                mStrokePaint.setStrokeWidth(dpF(getContext(), 5.8f));
                mStrokePaint.setAlpha(Math.round(0.11f * r * 255f));
                canvas.drawPath(mCardPath, mStrokePaint);
                mStrokePaint.setShader(null);
                canvas.restoreToCount(innerSaved);
            }

            if (r > 0.04f) {
                mStrokePaint.setAlpha(Math.round(255 * r));
                mStrokePaint.setShader(sweep(cardRim, cx, cy, cardRot));
                mStrokePaint.setStrokeWidth(dpF(getContext(), mXiaoAiGlow ? 1.45f : 1.2f));
                canvas.drawPath(mCardPath, mStrokePaint);
                mStrokePaint.setShader(null);
            }

            float iconSize = dpF(getContext(), 21f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = top + (cardH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6.5f), mIconPath);
            int activeIconBg = mXiaoAiGlow
                    ? (mPalette.dark ? 0x2AB86BFF : 0x1A8C46F2)
                    : mPalette.previewNebulaIconBg;
            mFillPaint.setColor(blendColor(mPalette.previewNormalIcon, activeIconBg, r));
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

            int activeLine1 = mXiaoAiGlow
                    ? (mPalette.dark ? 0xC8F0ECFF : 0xB8282438)
                    : mPalette.previewNebulaLinePrimary;
            int activeLine2 = mXiaoAiGlow
                    ? (mPalette.dark ? 0x68D6CEE8 : 0x5C3A324E)
                    : mPalette.previewNebulaLineSecondary;
            mFillPaint.setShader(null);
            mFillPaint.setColor(blendColor(mPalette.previewSkeletonPrimary, activeLine1, r));
            mRect.set(textLeft, linesTop, textLeft + textMaxW * 0.62f, linesTop + l1H);
            canvas.drawRoundRect(mRect, l1H * 0.5f, l1H * 0.5f, mFillPaint);

            mFillPaint.setColor(blendColor(mPalette.previewSkeletonSecondary, activeLine2, r));
            mRect.set(textLeft, linesTop + l1H + gap, textLeft + textMaxW * 0.92f, linesTop + l1H + gap + l2H);
            canvas.drawRoundRect(mRect, l2H * 0.5f, l2H * 0.5f, mFillPaint);

            // Right side: when ON -> styled "放行提醒" pill; when OFF -> ordinary dot
            if (r > 0.06f) {
                float pillH = dpF(getContext(), 15f);
                float pillLeft = rightEdge - badgeW;
                float pillTop = top + (cardH - pillH) * 0.5f;
                mRect.set(pillLeft, pillTop, rightEdge, pillTop + pillH);
                int badgePillBg = mXiaoAiGlow
                        ? (mPalette.dark ? 0x2BFF6EA8 : 0x1AE52B7A)
                        : mPalette.previewNebulaPillBg;
                int badgeTextCol = mXiaoAiGlow
                        ? (mPalette.dark ? 0xFFFFD8EC : 0xFFC42068)
                        : mPalette.previewNebulaBadgeText;
                mFillPaint.setColor(withAlpha(badgePillBg, r));
                canvas.drawRoundRect(mRect, pillH * 0.5f, pillH * 0.5f, mFillPaint);

                mBadgeTextPaint.setColor(withAlpha(badgeTextCol, r));
                float base = (pillTop + pillH * 0.5f) - (mBadgeTextPaint.ascent() + mBadgeTextPaint.descent()) * 0.5f;
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
            mFillPaint.setAlpha(Math.round(255 * alphaScale));
            mFillPaint.setShader(line(sparkle, cx - r, cy - r, cx + r, cy + r));
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

    /**
     * Dedicated HyperOS settings illustration for "噪音过滤" (Promotion / Noise Filtering):
     * 1 ordinary notification stays crisp at the top while 2 promotional notifications smoothly
     * fold and tuck into the native-style "推广通知" entrance bar with a mini app icon strip,
     * Nebula sparkle badge, rounded chevron, and a one-shot Nebula sheen sweep when toggled ON.
     */
    static final class PromotionPreviewView extends PreviewView {
        private static final float STAGE_HEIGHT_DP = 172f;
        private static final float MAX_STAGE_WIDTH_DP = 320f;
        private static final String AD_TAG_LABEL = "推广";

        private final Palette mPalette;
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mStagePath = new Path();
        private final Path mCardPath = new Path();
        private final Path mIconPath = new Path();
        private final Path mSparklePath = new Path();
        private final Path mChevronPath = new Path();
        private final RectF mRect = new RectF();
        private final Matrix mShaderMatrix = new Matrix();

        private boolean mPromotionEnabled;
        private float mProgress;
        private float mSweepProgress;
        private float mSweepBoost;
        private ValueAnimator mAnimator;
        private ValueAnimator mSweepAnimator;

        PromotionPreviewView(Context context, Palette palette) {
            super(context, palette);
            mPalette = palette;
            divider = linear(null, 0, palette.dark ? 0x33FFFFFF : 0x1F000000, 0);
            rebuildShaders();
            mFillPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mWavePaint.setStyle(Paint.Style.FILL);
            mTagPaint.setTypeface(typeface(500));
            mTagPaint.setTextSize(dpF(context, 8.2f));
            mTitlePaint.setTypeface(typeface(500));
            mTitlePaint.setTextSize(dpF(context, 10.0f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private final LinearGradient divider;
        private LinearGradient dividerGlow, rim;
        private Shader wave;

        private void rebuildShaders() {
            boolean dark = mPalette.dark;
            if (mXiaoAiGlow) {
                dividerGlow = linear(null, 0,
                        withAlpha(dark ? 0xFFFF689A : 0xFFE52B7A, dark ? .52f : .38f), 0);
                rim = linear(new float[]{0f, 0.34f, 0.68f, 1f},
                        withAlpha(dark ? GlowStyle.XIAOAI_DARK_ORANGE : GlowStyle.XIAOAI_LIGHT_ORANGE, .68f),
                        withAlpha(dark ? GlowStyle.XIAOAI_DARK_ROSE : GlowStyle.XIAOAI_LIGHT_ROSE, .64f),
                        withAlpha(dark ? GlowStyle.XIAOAI_DARK_VIOLET : GlowStyle.XIAOAI_LIGHT_VIOLET, .64f),
                        withAlpha(dark ? GlowStyle.XIAOAI_DARK_BLUE : GlowStyle.XIAOAI_LIGHT_BLUE, .66f));
            } else {
                dividerGlow = linear(null, 0, withAlpha(mPalette.previewNebulaCyan, dark ? .52f : .40f), 0);
                rim = linear(null, withAlpha(mPalette.previewNebulaCyan, .64f),
                        withAlpha(mPalette.previewNebulaAzure, .56f), withAlpha(mPalette.previewNebulaViolet, .64f));
            }
            wave = NebulaSummaryVisuals.createFeatheredWaveShader(1, 0, 1, dark, mXiaoAiGlow);
        }

        @Override
        protected void onGlowStyleChanged(boolean replayDemo) {
            rebuildSparkle(mPalette);
            rebuildShaders();
            if (replayDemo && mPromotionEnabled) {
                startPreviewSweep();
            }
        }

        void setPromotionEnabled(boolean enabled, boolean animate) {
            boolean changed = mPromotionEnabled != enabled;
            mPromotionEnabled = enabled;
            float target = enabled ? 1f : 0f;
            if (!animate || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
                cancelAnimator();
                cancelSweepAnimator();
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
                cancelSweepAnimator();
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
                startPreviewSweep();
            }
        }

        private void startPreviewSweep() {
            if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) return;
            cancelSweepAnimator();
            mSweepAnimator = ValueAnimator.ofFloat(0f, 1f);
            mSweepAnimator.setDuration(760L);
            mSweepAnimator.setInterpolator(HYPER_EASE);
            mSweepAnimator.addUpdateListener(anim -> {
                mSweepProgress = (float) anim.getAnimatedValue();
                mSweepBoost = NebulaSummaryVisuals.smoothEnvelope(mSweepProgress, 0.24f);
                invalidate();
            });
            mSweepAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mSweepProgress = 0f;
                    mSweepBoost = 0f;
                    mSweepAnimator = null;
                    invalidate();
                }
            });
            mSweepAnimator.start();
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

            float p = mProgress;
            float stageW = Math.min(totalW, dpF(getContext(), MAX_STAGE_WIDTH_DP));
            float stageLeft = (totalW - stageW) * 0.5f;
            float stageRight = stageLeft + stageW;

            // Inner soft HyperOS schematic stage (identical 14dp squircle + 1dp hairline border)
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

            float padH = dpF(getContext(), 14f);
            float left = stageLeft + padH;
            float right = stageRight - padH;
            float cardRadius = dpF(getContext(), 11f);
            float cardH = dpF(getContext(), 38f);
            float cardGap = dpF(getContext(), 8f);

            // OFF vertical layout: 3 notification cards (1 normal + 2 ad cards) centered
            float offTotalH = cardH * 3f + cardGap * 2f;
            float offStartY = (totalH - offTotalH) * 0.5f;

            // ON vertical layout: 1 normal card + subtle divider + folded "推广通知" entrance bar + 2 tucked peeks
            float dividerGap = dpF(getContext(), 16f);
            float entranceH = dpF(getContext(), 42f);
            float stackPeek1 = dpF(getContext(), 5.5f);
            float stackPeek2 = dpF(getContext(), 10.5f);
            float onTotalH = cardH + dividerGap + entranceH + stackPeek2;
            float onStartY = (totalH - onTotalH) * 0.5f;
            float entranceTop = onStartY + cardH + dividerGap;

            // 1. Back-most promotional card (Card 3 -> tucks behind "推广通知" entrance bar)
            float c3Inset = dpF(getContext(), 14.5f) * p;
            float c3Top = lerp(
                    offStartY + (cardH + cardGap) * 2f,
                    entranceTop + entranceH - cardH + stackPeek2,
                    p);
            drawAdCard(
                    canvas,
                    left + c3Inset, c3Top, right - c3Inset, c3Top + cardH,
                    cardRadius - dpF(getContext(), 1.8f) * p,
                    lerp(1f, mPalette.dark ? 0.38f : 0.58f, p),
                    Math.max(0f, 1f - p * 1.35f),
                    0.36f, 0.58f);

            // 2. Middle promotional card (Card 2 -> tucks behind "推广通知" entrance bar)
            float c2Inset = dpF(getContext(), 7.5f) * p;
            float c2Top = lerp(
                    offStartY + cardH + cardGap,
                    entranceTop + entranceH - cardH + stackPeek1,
                    p);
            drawAdCard(
                    canvas,
                    left + c2Inset, c2Top, right - c2Inset, c2Top + cardH,
                    cardRadius - dpF(getContext(), 0.9f) * p,
                    lerp(1f, mPalette.dark ? 0.64f : 0.82f, p),
                    Math.max(0f, 1f - p * 1.35f),
                    0.44f, 0.68f);

            // 3. Normal notification card (Card 1 -> stays crisp and prominent at top)
            float c1Top = lerp(offStartY, onStartY, p);
            drawNormalCard(canvas, left, c1Top, right, c1Top + cardH, cardRadius, 0.46f, 0.76f);

            // 4. Subtle fading hairline divider separating normal notifications from "推广通知"
            if (p > 0.04f) {
                float divY = c1Top + cardH + dividerGap * 0.5f;
                float divInset = dpF(getContext(), 16f);
                float dl = left + divInset;
                float dr = right - divInset;
                mStrokePaint.setAlpha(Math.round(255 * p * (1 - mSweepBoost)));
                mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
                mStrokePaint.setStrokeWidth(dpF(getContext(), 0.75f));
                mStrokePaint.setShader(line(divider, dl, divY, dr, divY));
                canvas.drawLine(dl, divY, dr, divY, mStrokePaint);
                if (mSweepBoost > 0) {
                    mStrokePaint.setShader(line(dividerGlow, dl, divY, dr, divY));
                    mStrokePaint.setAlpha(Math.round(255 * p * mSweepBoost));
                    canvas.drawLine(dl, divY, dr, divY, mStrokePaint);
                }
                mStrokePaint.setShader(null);
            }

            // 5. Native-style "推广通知" folded entrance bar (with Nebula sparkle + mini app icon strip + '>')
            if (p > 0.08f) {
                float entAlpha = Math.max(0f, Math.min(1f, (p - 0.08f) / 0.92f));
                float entTop = lerp(offStartY + cardH + cardGap, entranceTop, p);
                drawPromotionEntranceBar(canvas, left, entTop, right, entTop + entranceH, cardRadius, entAlpha);
            }
        }

        private void drawNormalCard(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float line1Ratio,
                float line2Ratio) {
            float cardH = bottom - top;
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);

            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(0x0B000000);
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.75f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }
            mFillPaint.setColor(mPalette.previewNormalCard);
            canvas.drawPath(mCardPath, mFillPaint);

            float iconSize = dpF(getContext(), 20f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = top + (cardH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6f), mIconPath);
            mFillPaint.setColor(mPalette.previewNormalIcon);
            canvas.drawPath(mIconPath, mFillPaint);

            float textLeft = iconLeft + iconSize + dpF(getContext(), 9f);
            float rightEdge = right - dpF(getContext(), 12f);
            float textMaxW = Math.max(0f, rightEdge - textLeft);
            float l1H = dpF(getContext(), 5.1f);
            float l2H = dpF(getContext(), 4.2f);
            float gap = dpF(getContext(), 5.1f);
            float linesTop = top + (cardH - (l1H + gap + l2H)) * 0.5f;

            mFillPaint.setColor(mPalette.previewSkeletonPrimary);
            mRect.set(textLeft, linesTop, textLeft + textMaxW * line1Ratio, linesTop + l1H);
            canvas.drawRoundRect(mRect, l1H * 0.5f, l1H * 0.5f, mFillPaint);

            float timeW = dpF(getContext(), 15f);
            float timeH = dpF(getContext(), 3.8f);
            float timeTop = linesTop + (l1H - timeH) * 0.5f;
            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonSecondary, 0.9f));
            mRect.set(rightEdge - timeW, timeTop, rightEdge, timeTop + timeH);
            canvas.drawRoundRect(mRect, timeH * 0.5f, timeH * 0.5f, mFillPaint);

            mFillPaint.setColor(mPalette.previewSkeletonSecondary);
            float line2Top = linesTop + l1H + gap;
            mRect.set(textLeft, line2Top, textLeft + textMaxW * line2Ratio, line2Top + l2H);
            canvas.drawRoundRect(mRect, l2H * 0.5f, l2H * 0.5f, mFillPaint);
        }

        private void drawAdCard(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float cardAlpha,
                float contentAlpha,
                float line1Ratio,
                float line2Ratio) {
            float cardH = bottom - top;
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);

            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(withAlpha(0x0A000000, cardAlpha));
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.7f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }
            mFillPaint.setColor(withAlpha(mPalette.previewNormalCard, cardAlpha));
            canvas.drawPath(mCardPath, mFillPaint);

            if (contentAlpha <= 0.03f) return;

            float iconSize = dpF(getContext(), 20f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = top + (cardH - iconSize) * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6f), mIconPath);
            mFillPaint.setColor(withAlpha(mPalette.previewNormalIcon, contentAlpha));
            canvas.drawPath(mIconPath, mFillPaint);

            float textLeft = iconLeft + iconSize + dpF(getContext(), 9f);
            float rightEdge = right - dpF(getContext(), 10f);
            float tagTextW = mTagPaint.measureText(AD_TAG_LABEL);
            float tagW = tagTextW + dpF(getContext(), 10f);
            float tagH = dpF(getContext(), 14.5f);
            float textMaxW = Math.max(0f, rightEdge - tagW - dpF(getContext(), 8f) - textLeft);

            float l1H = dpF(getContext(), 5.0f);
            float l2H = dpF(getContext(), 4.2f);
            float gap = dpF(getContext(), 5.0f);
            float linesTop = top + (cardH - (l1H + gap + l2H)) * 0.5f;

            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonPrimary, contentAlpha * 0.92f));
            mRect.set(textLeft, linesTop, textLeft + textMaxW * line1Ratio, linesTop + l1H);
            canvas.drawRoundRect(mRect, l1H * 0.5f, l1H * 0.5f, mFillPaint);

            mFillPaint.setColor(withAlpha(mPalette.previewSkeletonSecondary, contentAlpha * 0.92f));
            float line2Top = linesTop + l1H + gap;
            mRect.set(textLeft, line2Top, textLeft + textMaxW * line2Ratio, line2Top + l2H);
            canvas.drawRoundRect(mRect, l2H * 0.5f, l2H * 0.5f, mFillPaint);

            // Subtle neutral-muted "推广" tag pill indicating ad notifications before filtering
            float tagLeft = rightEdge - tagW;
            float tagTop = top + (cardH - tagH) * 0.5f;
            int tagBg = mPalette.dark ? 0x16FFFFFF : 0x0E000000;
            mFillPaint.setColor(withAlpha(tagBg, contentAlpha));
            mRect.set(tagLeft, tagTop, rightEdge, tagTop + tagH);
            canvas.drawRoundRect(mRect, tagH * 0.5f, tagH * 0.5f, mFillPaint);

            mTagPaint.setColor(withAlpha(mPalette.textTertiary, contentAlpha));
            float base = (tagTop + tagH * 0.5f) - (mTagPaint.ascent() + mTagPaint.descent()) * 0.5f;
            canvas.drawText(AD_TAG_LABEL, tagLeft + (tagW - tagTextW) * 0.5f, base, mTagPaint);
        }

        private void drawPromotionEntranceBar(
                Canvas canvas,
                float left, float top, float right, float bottom,
                float radius,
                float alpha) {
            float barH = bottom - top;
            float cy = top + barH * 0.5f;
            mRect.set(left, top, right, bottom);
            buildSquirclePath(mRect, radius, mCardPath);

            // Soft contact shadow + crisp card fill
            mFillPaint.setShader(null);
            if (!mPalette.dark) {
                mFillPaint.setColor(withAlpha(0x0C000000, alpha));
                canvas.save();
                canvas.translate(0f, dpF(getContext(), 0.85f));
                canvas.drawPath(mCardPath, mFillPaint);
                canvas.restore();
            }
            mFillPaint.setColor(withAlpha(mPalette.previewNormalCard, alpha));
            canvas.drawPath(mCardPath, mFillPaint);

            // Restrained hairline rim (subtly catching Nebula cyan->violet sheen during toggle ON sweep)
            mStrokePaint.setAlpha(255);
            mStrokePaint.setStrokeWidth(dpF(getContext(), 0.85f));
            if (mSweepBoost > 0.02f) {
                mStrokePaint.setShader(line(rim, left, top, right, bottom));
                mStrokePaint.setAlpha(Math.round(255 * (.21875f + .78125f * mSweepBoost) * alpha));
            } else {
                mStrokePaint.setShader(null);
                mStrokePaint.setColor(withAlpha(mPalette.previewStageBorder, alpha * 1.25f));
            }
            canvas.drawPath(mCardPath, mStrokePaint);
            mStrokePaint.setShader(null);

            // Left: Nebula sparkle squircle badge + "推广通知" label
            float iconSize = dpF(getContext(), 20f);
            float iconLeft = left + dpF(getContext(), 10f);
            float iconTop = cy - iconSize * 0.5f;
            mRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            buildSquirclePath(mRect, dpF(getContext(), 6.2f), mIconPath);
            int promoIconBg = mXiaoAiGlow
                    ? (mPalette.dark ? 0x2AB86BFF : 0x1A8C46F2)
                    : mPalette.previewNebulaIconBg;
            mFillPaint.setColor(withAlpha(promoIconBg, alpha));
            canvas.drawPath(mIconPath, mFillPaint);

            drawGradientSparkle(
                    canvas,
                    iconLeft + iconSize * 0.5f,
                    cy,
                    dpF(getContext(), 4.3f),
                    alpha,
                    mSweepBoost * 0.7f);

            float labelX = iconLeft + iconSize + dpF(getContext(), 8.5f);
            mTitlePaint.setColor(withAlpha(mPalette.textPrimary, alpha * 0.92f));
            float baseline = cy - (mTitlePaint.ascent() + mTitlePaint.descent()) * 0.5f;
            canvas.drawText(PromotionNotification.TITLE, labelX, baseline, mTitlePaint);

            // Right: clean rounded chevron '>' + 2 miniature app icon squircles (mirrors SystemUiPromotions)
            float chevronRight = right - dpF(getContext(), 11f);
            float chevDx = dpF(getContext(), 2.5f);
            float chevDy = dpF(getContext(), 4.2f);
            float chevCx = chevronRight - chevDx;
            mChevronPath.reset();
            mChevronPath.moveTo(chevCx - chevDx, cy - chevDy);
            mChevronPath.lineTo(chevCx + chevDx, cy);
            mChevronPath.lineTo(chevCx - chevDx, cy + chevDy);
            mStrokePaint.setShader(null);
            mStrokePaint.setStrokeWidth(dpF(getContext(), 1.45f));
            mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            mStrokePaint.setColor(withAlpha(mPalette.textTertiary, alpha));
            canvas.drawPath(mChevronPath, mStrokePaint);

            float miniSize = dpF(getContext(), 14f);
            float miniGap = dpF(getContext(), 4f);
            float miniRadius = dpF(getContext(), 4.2f);
            float mini2Right = chevCx - chevDx - dpF(getContext(), 7f);
            float mini2Left = mini2Right - miniSize;
            float mini1Left = mini2Left - miniGap - miniSize;
            float miniTop = cy - miniSize * 0.5f;

            drawMiniAppIcon(canvas, mini1Left, miniTop, miniSize, miniRadius, alpha, true);
            drawMiniAppIcon(canvas, mini2Left, miniTop, miniSize, miniRadius, alpha, false);

            // One-shot tilted Nebula sheen gliding across the entrance bar when toggled ON
            if (mSweepBoost > 0.01f) {
                float barW = right - left;
                float bandW = Math.max(dpF(getContext(), 48f), barW * 0.36f);
                float waveX = (left - bandW * 0.35f) + (barW + bandW * 0.7f) * mSweepProgress;
                int saved = canvas.save();
                canvas.clipPath(mCardPath);
                NebulaSummaryVisuals.drawTiltedNebulaWave(
                        canvas, left, top, right, bottom,
                        waveX, bandW, mSweepBoost * alpha * 0.78f,
                        mPalette.dark, wave, mShaderMatrix, mWavePaint);
                canvas.restoreToCount(saved);
            }
        }

        private void drawMiniAppIcon(
                Canvas canvas,
                float left, float top, float size, float radius,
                float alpha,
                boolean primary) {
            mRect.set(left, top, left + size, top + size);
            buildSquirclePath(mRect, radius, mIconPath);
            mFillPaint.setShader(null);
            mFillPaint.setColor(withAlpha(mPalette.previewNormalIcon, alpha));
            canvas.drawPath(mIconPath, mFillPaint);

            float inset = size * 0.30f;
            mFillPaint.setColor(withAlpha(
                    mPalette.previewSkeletonPrimary,
                    alpha * (primary ? 0.88f : 0.66f)));
            if (primary) {
                float innerR = (size - inset * 2f) * 0.5f;
                canvas.drawCircle(left + size * 0.5f, top + size * 0.5f, innerR, mFillPaint);
            } else {
                mRect.set(left + inset, top + inset, left + size - inset, top + size - inset);
                float r = dpF(getContext(), 1.6f);
                canvas.drawRoundRect(mRect, r, r, mFillPaint);
            }
        }

        private void drawGradientSparkle(
                Canvas canvas, float cx, float cy, float r, float alphaScale, float glowBoost) {
            NebulaSummaryVisuals.buildSparklePath(cx, cy, r, mSparklePath);
            mFillPaint.setAlpha(Math.round(255 * alphaScale));
            mFillPaint.setShader(line(sparkle, cx - r, cy - r, cx + r, cy + r));
            canvas.drawPath(mSparklePath, mFillPaint);
            mFillPaint.setShader(null);
            if (glowBoost > 0.02f) {
                mFillPaint.setColor(withAlpha(0xFFFFFFFF, 0.55f * glowBoost * alphaScale));
                canvas.drawPath(mSparklePath, mFillPaint);
            }
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelAnimator();
            cancelSweepAnimator();
            super.onDetachedFromWindow();
        }

        private void cancelAnimator() {
            if (mAnimator != null) {
                mAnimator.cancel();
                mAnimator = null;
            }
        }

        private void cancelSweepAnimator() {
            if (mSweepAnimator != null) {
                mSweepAnimator.cancel();
                mSweepAnimator = null;
            }
            mSweepProgress = 0f;
            mSweepBoost = 0f;
        }
    }
}
