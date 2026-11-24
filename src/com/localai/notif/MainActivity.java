package com.localai.notif;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements ModelDownloadManager.Listener {
    private static final Uri PROVIDER = Uri.parse("content://" + Config.AUTHORITY);
    private static final Executor OPTION_IO = Executors.newSingleThreadExecutor();
    private static final String STATE_PRIORITY_ENABLED = "state_priority_enabled";
    private static final String STATE_GROUP_SUMMARY = "state_group_summary";
    private static final String STATE_RECAP_SCREEN_ON = "state_recap_screen_on";
    private static final String STATE_RECAP_MIN_LENGTH = "state_recap_min_length";
    private static final String STATE_REDUCE_ENABLED = "state_reduce_enabled";
    private static final String STATE_REDUCE_LOCK_ONLY = "state_reduce_lock_only";
    private static final String STATE_ALWAYS_LOAD_MODEL = "state_always_load_model";
    private static final String STATE_GLOW_STYLE = "state_glow_style";

    private HyperSettingsUi.Palette mPalette;
    private HyperSettingsUi.SummaryPreviewView mSummaryPreview;
    private HyperSettingsUi.RecapPreviewView mRecapPreview;
    private HyperSettingsUi.PriorityPreviewView mPriorityPreview;
    private HyperSettingsUi.ReducePreviewView mReducePreview;
    private HyperSettingsUi.SwitchRowParts mSummaryRow;
    private HyperSettingsUi.NavRowParts mSummaryAdvancedRow;
    private HyperSettingsUi.SwitchRowParts mRecapRow;
    private HyperSettingsUi.SwitchRowParts mRecapScreenOnRow;
    private HyperSettingsUi.NavRowParts mRecapMinLengthRow;
    private HyperSettingsUi.SwitchRowParts mPriorityRow;
    private HyperSettingsUi.SwitchRowParts mPromotionRow;
    private HyperSettingsUi.PromotionPreviewView mPromotionPreview;
    private HyperSettingsUi.PageShell mPage;
    private View mPromotionHeading;
    private HyperSettingsUi.SwitchRowParts mReduceRow;
    private HyperSettingsUi.SwitchRowParts mReduceLockOnlyRow;
    private HyperSettingsUi.SwitchRowParts mReduceIconsRow;
    private HyperSettingsUi.SwitchRowParts mReduceSoundsRow;
    private HyperSettingsUi.NavRowParts mReduceTileRow;
    private HyperSettingsUi.NavRowParts mModelRow;
    private HyperSettingsUi.SwitchRowParts mAlwaysLoadRow;
    private HyperSettingsUi.NavRowParts mGlowStyleRow;
    private ModelDownloadManager mDownloadManager;

    private boolean mSummaryBusy;
    private boolean mRecapBusy;
    private boolean mRecapMinLengthBusy;
    private boolean mPriorityBusy;
    private boolean mReduceBusy;
    private boolean mAlwaysLoadBusy;
    private boolean mGlowStyleBusy;
    private int mRecapMinLength = Config.DEFAULT_RECAP_MIN_LENGTH;
    private String mGlowStyle = Config.DEFAULT_GLOW_STYLE;
    private boolean mEngineReceiverRegistered;
    private boolean mReduceReceiverRegistered;
    private Bundle mLastConfig;
    private ModelCatalog.ActiveSize mActiveSize;

    private final BroadcastReceiver mEngineStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isFinishing() && !isDestroyed()) {
                refreshConfigFromProvider();
            }
        }
    };

    private final BroadcastReceiver mReduceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isFinishing() || isDestroyed()) return;
            boolean active = intent.getBooleanExtra(ReduceInterruptions.EXTRA_ACTIVE, false);
            boolean lockOnly = intent.getBooleanExtra(
                    ReduceInterruptions.EXTRA_LOCK_SCREEN_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY);
            if (!mReduceBusy) {
                setReduceVisual(active, true);
            }
            if (mReduceLockOnlyRow != null && mReduceLockOnlyRow.row.isClickable()) {
                mReduceLockOnlyRow.toggle.setChecked(lockOnly, true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean isDark = HyperSettingsUi.applyTheme(this);
        super.onCreate(savedInstanceState);

        mPalette = HyperSettingsUi.Palette.of(isDark);
        HyperSettingsUi.configureEdgeToEdge(this, mPalette);
        boolean initialPromotion = savedInstanceState != null
                ? savedInstanceState.getBoolean(Config.KEY_PROMOTION_FILTER, Config.DEFAULT_PROMOTION_FILTER)
                : Config.DEFAULT_PROMOTION_FILTER;
        mDownloadManager = ModelDownloadManager.getInstance(this);

        boolean initialPriority = savedInstanceState != null
                ? savedInstanceState.getBoolean(STATE_PRIORITY_ENABLED, Config.DEFAULT_PRIORITY_ENABLED)
                : Config.DEFAULT_PRIORITY_ENABLED;
        boolean initialSummary = savedInstanceState != null
                ? savedInstanceState.getBoolean(STATE_GROUP_SUMMARY, Config.DEFAULT_GROUP_SUMMARY)
                : Config.DEFAULT_GROUP_SUMMARY;
        boolean initialReduce = savedInstanceState != null
                ? savedInstanceState.getBoolean(STATE_REDUCE_ENABLED, Config.DEFAULT_REDUCE_ENABLED)
                : Config.DEFAULT_REDUCE_ENABLED;
        boolean initialReduceLockOnly = savedInstanceState != null
                ? savedInstanceState.getBoolean(STATE_REDUCE_LOCK_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY)
                : Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY;
        boolean initialAlwaysLoad = savedInstanceState != null
                ? savedInstanceState.getBoolean(STATE_ALWAYS_LOAD_MODEL, Config.DEFAULT_ALWAYS_LOAD_MODEL)
                : Config.DEFAULT_ALWAYS_LOAD_MODEL;
        boolean initialRecap = savedInstanceState != null
                ? savedInstanceState.getBoolean(Config.KEY_NOTIFICATION_RECAP, Config.DEFAULT_NOTIFICATION_RECAP)
                : Config.DEFAULT_NOTIFICATION_RECAP;
        boolean initialRecapScreenOn = savedInstanceState != null
                ? savedInstanceState.getBoolean(STATE_RECAP_SCREEN_ON, Config.DEFAULT_RECAP_SCREEN_ON)
                : Config.DEFAULT_RECAP_SCREEN_ON;
        mRecapMinLength = savedInstanceState != null
                ? Config.sanitizeRecapMinLength(
                        savedInstanceState.getInt(STATE_RECAP_MIN_LENGTH, Config.DEFAULT_RECAP_MIN_LENGTH))
                : Config.DEFAULT_RECAP_MIN_LENGTH;
        mGlowStyle = savedInstanceState != null
                ? Config.sanitizeGlowStyle(savedInstanceState.getString(STATE_GLOW_STYLE, Config.DEFAULT_GLOW_STYLE))
                : Config.DEFAULT_GLOW_STYLE;
        GlowStyle.setCurrent(mGlowStyle);

        setContentView(buildContentView(
                initialPriority, initialSummary,
                initialRecap, initialRecapScreenOn,
                initialReduce, initialReduceLockOnly,
                savedInstanceState != null && savedInstanceState.getBoolean(Config.KEY_REDUCE_FILTER_ICONS),
                savedInstanceState != null && savedInstanceState.getBoolean(Config.KEY_REDUCE_NORMAL_SOUNDS),
                initialAlwaysLoad, initialPromotion));
        showRequestedSection(getIntent());
        refreshConfigFromProvider();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showRequestedSection(intent);
    }

    private void showRequestedSection(Intent intent) {
        if (intent == null || !Config.ACTION_PROMOTION_SETTINGS.equals(intent.getAction())) {
            return;
        }
        if (mPromotionHeading.getTop() > 0) {
            mPage.scrollView.post(() -> mPage.scrollView.scrollTo(
                    0, Math.max(0, mPromotionHeading.getTop() - dp(72))));
            return;
        }
        mPromotionHeading.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (top <= 0) return;
                v.removeOnLayoutChangeListener(this);
                mPage.scrollView.post(() -> mPage.scrollView.scrollTo(
                        0, Math.max(0, top - dp(72))));
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mDownloadManager.addListener(this);
        if (!mEngineReceiverRegistered) {
            registerReceiver(
                    mEngineStateReceiver,
                    new IntentFilter(Config.ACTION_ENGINE_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
            mEngineReceiverRegistered = true;
        }
        if (!mReduceReceiverRegistered) {
            registerReceiver(
                    mReduceStateReceiver,
                    new IntentFilter(ReduceInterruptions.ACTION_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED);
            mReduceReceiverRegistered = true;
        }
        renderModelEntry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConfigFromProvider();
    }

    @Override
    protected void onStop() {
        if (mEngineReceiverRegistered) {
            unregisterReceiver(mEngineStateReceiver);
            mEngineReceiverRegistered = false;
        }
        if (mReduceReceiverRegistered) {
            unregisterReceiver(mReduceStateReceiver);
            mReduceReceiverRegistered = false;
        }
        mDownloadManager.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(Config.KEY_PROMOTION_FILTER, mPromotionRow.toggle.isChecked());
        if (mPriorityRow != null) {
            outState.putBoolean(STATE_PRIORITY_ENABLED, mPriorityRow.toggle.isChecked());
        }
        if (mSummaryRow != null) {
            outState.putBoolean(STATE_GROUP_SUMMARY, mSummaryRow.toggle.isChecked());
        }
        if (mRecapRow != null) {
            outState.putBoolean(Config.KEY_NOTIFICATION_RECAP, mRecapRow.toggle.isChecked());
        }
        if (mRecapScreenOnRow != null) {
            outState.putBoolean(STATE_RECAP_SCREEN_ON, mRecapScreenOnRow.toggle.isChecked());
        }
        outState.putInt(STATE_RECAP_MIN_LENGTH, mRecapMinLength);
        if (mReduceRow != null) {
            outState.putBoolean(STATE_REDUCE_ENABLED, mReduceRow.toggle.isChecked());
        }
        if (mReduceLockOnlyRow != null) {
            outState.putBoolean(STATE_REDUCE_LOCK_ONLY, mReduceLockOnlyRow.toggle.isChecked());
        }
        if (mAlwaysLoadRow != null) {
            outState.putBoolean(STATE_ALWAYS_LOAD_MODEL, mAlwaysLoadRow.toggle.isChecked());
        }
        outState.putBoolean(Config.KEY_REDUCE_FILTER_ICONS, mReduceIconsRow.toggle.isChecked());
        outState.putBoolean(Config.KEY_REDUCE_NORMAL_SOUNDS, mReduceSoundsRow.toggle.isChecked());
        outState.putString(STATE_GLOW_STYLE, mGlowStyle);
    }

    @Override
    public void onDownloadStateChanged() {
        if (isFinishing() || isDestroyed()) return;
        renderModelEntry();
        if (mDownloadManager.getActiveSnapshot() == null) {
            refreshConfigFromProvider();
        }
    }

    private View buildContentView(
            boolean initialPriority,
            boolean initialSummary,
            boolean initialRecap,
            boolean initialRecapScreenOn,
            boolean initialReduce,
            boolean initialReduceLockOnly,
            boolean initialReduceFilterIcons,
            boolean initialReduceSounds,
            boolean initialAlwaysLoad, boolean initialPromotionFilter) {
        boolean showBackButton = getIntent().getBooleanExtra(Config.EXTRA_FROM_SYSTEM_SETTINGS, false)
                || Config.ACTION_PROMOTION_SETTINGS.equals(getIntent().getAction());
        HyperSettingsUi.PageShell shell = HyperSettingsUi.buildPageShell(
                this, mPalette, resolveAppTitle(), showBackButton);
        mPage = shell;
        LinearLayout content = shell.content;

        // 1. Section: AI 摘要 (preview + toggle + subpage unified inside one 18dp squircle card)
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "AI 摘要", 2));

        LinearLayout summaryCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        FrameLayout summaryPreviewWrap = new FrameLayout(this);
        summaryPreviewWrap.setPadding(dp(16), dp(16), dp(16), dp(4));
        mSummaryPreview = new HyperSettingsUi.SummaryPreviewView(this, mPalette);
        mSummaryPreview.setSummaryEnabled(initialSummary, false);
        summaryPreviewWrap.addView(mSummaryPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        summaryCard.addView(summaryPreviewWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mSummaryRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "AI 摘要",
                "将同一应用的多条折叠通知汇总为简明摘要。",
                initialSummary,
                false,
                false);
        mSummaryRow.row.setOnClickListener(view -> {
            if (mSummaryBusy) return;
            boolean target = !mSummaryRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setSummaryVisual(target, true);
            saveSummaryEnabled(target);
        });
        summaryCard.addView(mSummaryRow.row);
        summaryCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mSummaryAdvancedRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "摘要触发设置",
                "亮屏时生效，超过 15 字",
                null,
                false,
                false,
                true);
        mSummaryAdvancedRow.row.setOnClickListener(v ->
                startActivity(new Intent(this, SummarySettingsActivity.class)));
        summaryCard.addView(mSummaryAdvancedRow.row);
        content.addView(summaryCard);

        // 2. Section: AI 概括 (dedicated preview + toggle + screen-on + min-length threshold)
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, NotificationRecap.LABEL, 20));
        content.addView(buildRecapCard(initialRecap, initialRecapScreenOn));

        // 2. Section: 重要通知 (preview + toggle unified inside one 18dp squircle card)
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "重要通知", 20));

        LinearLayout priorityCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        FrameLayout priorityPreviewWrap = new FrameLayout(this);
        priorityPreviewWrap.setPadding(dp(16), dp(16), dp(16), dp(4));
        mPriorityPreview = new HyperSettingsUi.PriorityPreviewView(this, mPalette);
        mPriorityPreview.setPriorityEnabled(initialPriority, false);
        priorityPreviewWrap.addView(mPriorityPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        priorityCard.addView(priorityPreviewWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mPriorityRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "重要通知",
                "在锁屏和通知中心置顶显示重要通知。",
                initialPriority,
                false,
                true);
        mPriorityRow.row.setOnClickListener(view -> {
            if (mPriorityBusy) return;
            boolean target = !mPriorityRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setPriorityVisual(target, true);
            savePriorityEnabled(target);
        });
        priorityCard.addView(mPriorityRow.row);
        content.addView(priorityCard);

        mPromotionHeading = HyperSettingsUi.createSmallTitle(this, mPalette, "噪音过滤", 20);
        content.addView(mPromotionHeading);
        content.addView(buildPromotionCard(initialPromotionFilter));

        // 3. Section: 减少干扰 (preview + master toggle + lock-screen-only + QS tile shortcut)
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "减少干扰", 20));

        LinearLayout reduceCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        FrameLayout reducePreviewWrap = new FrameLayout(this);
        reducePreviewWrap.setPadding(dp(16), dp(16), dp(16), dp(4));
        mReducePreview = new HyperSettingsUi.ReducePreviewView(this, mPalette);
        mReducePreview.setReduceEnabled(initialReduce, false);
        reducePreviewWrap.addView(mReducePreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        reduceCard.addView(reducePreviewWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mReduceRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "减少干扰",
                "开启系统勿扰，由 AI 放行重要通知。",
                initialReduce,
                false,
                false);
        mReduceRow.row.setOnClickListener(view -> {
            if (mReduceBusy) return;
            boolean target = !mReduceRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setReduceVisual(target, true);
            saveReduceEnabled(target);
        });
        reduceCard.addView(mReduceRow.row);
        reduceCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mReduceLockOnlyRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "仅锁屏时生效",
                "亮屏解锁使用手机时保持正常提醒，息屏或锁屏时拦截打扰。",
                initialReduceLockOnly,
                false,
                false);
        mReduceLockOnlyRow.row.setOnClickListener(view -> {
            boolean target = !mReduceLockOnlyRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            mReduceLockOnlyRow.toggle.setChecked(target, true);
            saveSwitchOption(Config.KEY_REDUCE_LOCK_SCREEN_ONLY, target, mReduceLockOnlyRow);
        });
        reduceCard.addView(mReduceLockOnlyRow.row);
        reduceCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mReduceIconsRow = HyperSettingsUi.createSwitchRow(this, mPalette,
                "仅显示已放行通知的图标", "隐藏未放行的通知图标。模式图标在解锁时移到左侧首位，锁屏时显示在右侧。",
                initialReduceFilterIcons, false, false);
        mReduceIconsRow.row.setOnClickListener(view -> {
            boolean target = !mReduceIconsRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            mReduceIconsRow.toggle.setChecked(target, true);
            saveSwitchOption(Config.KEY_REDUCE_FILTER_ICONS, target, mReduceIconsRow);
        });
        reduceCard.addView(mReduceIconsRow.row);
        reduceCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mReduceSoundsRow = HyperSettingsUi.createSwitchRow(this, mPalette,
                "保留应用内声音和系统音效", "音乐、游戏和触摸音效按原设置播放。普通通知静音，重要通知放行后可响铃。",
                initialReduceSounds, false, false);
        mReduceSoundsRow.row.setOnClickListener(view -> {
            boolean target = !mReduceSoundsRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            mReduceSoundsRow.toggle.setChecked(target, true);
            saveSwitchOption(Config.KEY_REDUCE_NORMAL_SOUNDS, target, mReduceSoundsRow);
        });
        reduceCard.addView(mReduceSoundsRow.row);
        reduceCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mReduceTileRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "添加到控制中心",
                "在下拉控制中心快速切换，与系统勿扰互斥。",
                "添加",
                false,
                false,
                true);
        mReduceTileRow.row.setOnClickListener(v -> requestAddReduceTile());
        reduceCard.addView(mReduceTileRow.row);
        content.addView(reduceCard);

        // 4. Section: 本地模型
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "本地模型", 20));
        LinearLayout modelCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);
        mModelRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "端侧模型",
                "正在检测本地模型状态…",
                "检查中",
                true,
                true,
                false);
        mModelRow.row.setOnClickListener(v ->
                startActivity(new Intent(this, ModelDownloadActivity.class)));
        modelCard.addView(mModelRow.row);
        modelCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mAlwaysLoadRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "始终加载 AI 模型",
                "关闭时按需加载，空闲 3 分钟无推理后自动释放内存。",
                initialAlwaysLoad,
                false,
                true);
        mAlwaysLoadRow.row.setOnClickListener(view -> {
            if (mAlwaysLoadBusy) return;
            boolean target = !mAlwaysLoadRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setAlwaysLoadVisual(target, true);
            saveAlwaysLoadModel(target);
        });
        modelCard.addView(mAlwaysLoadRow.row);
        content.addView(modelCard);

        // 5. Section: 其他
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "其他", 20));
        LinearLayout otherCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);
        mGlowStyleRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "光效风格",
                null,
                GlowStyle.displayLabel(mGlowStyle),
                false,
                true,
                false);
        mGlowStyleRow.row.setOnClickListener(v -> showGlowStyleDialog());
        otherCard.addView(mGlowStyleRow.row);
        otherCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        HyperSettingsUi.NavRowParts aboutRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "关于",
                null,
                resolveVersionDisplay(),
                false,
                false,
                true);
        aboutRow.row.setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        otherCard.addView(aboutRow.row);
        content.addView(otherCard);

        // 6. Footer guidance note
        content.addView(HyperSettingsUi.createFooter(
                this,
                mPalette,
                "请在 LSPosed 中启用本模块并重启设备。所有通知识别与摘要均在本地完成，不上传任何数据。"));

        applySummaryDependentAlpha(initialSummary);
        setGlowStyleVisual(mGlowStyle, false);
        return shell.root;
    }

    private LinearLayout buildRecapCard(boolean enabled, boolean screenOn) {
        LinearLayout card = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        FrameLayout recapPreviewWrap = new FrameLayout(this);
        recapPreviewWrap.setPadding(dp(16), dp(16), dp(16), dp(4));
        mRecapPreview = new HyperSettingsUi.RecapPreviewView(this, mPalette);
        mRecapPreview.setRecapEnabled(enabled, false);
        recapPreviewWrap.addView(mRecapPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(recapPreviewWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mRecapRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                NotificationRecap.LABEL,
                "自动提炼单条长通知要点，展开后可查看完整原文。",
                enabled,
                false,
                false);
        mRecapRow.row.setOnClickListener(view -> {
            if (mRecapBusy) return;
            boolean target = !mRecapRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setRecapVisual(target, true);
            saveRecapEnabled(target);
        });
        card.addView(mRecapRow.row);
        card.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mRecapScreenOnRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "亮屏时概括",
                "关闭后仅在息屏时生成新概括，亮屏期间保持原文。",
                screenOn,
                false,
                false);
        mRecapScreenOnRow.row.setOnClickListener(view -> {
            if (!mRecapScreenOnRow.row.isClickable()) return;
            boolean target = !mRecapScreenOnRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            mRecapScreenOnRow.toggle.setChecked(target, true);
            saveSwitchOption(Config.KEY_RECAP_SCREEN_ON, target, mRecapScreenOnRow);
        });
        card.addView(mRecapScreenOnRow.row);
        card.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mRecapMinLengthRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "触发字数门槛",
                "正文超过此字数时自动概括，英文按约 2 字母折 1 字。",
                formatRecapMinLength(mRecapMinLength),
                false,
                false,
                true);
        mRecapMinLengthRow.row.setOnClickListener(v -> showRecapMinLengthDialog());
        card.addView(mRecapMinLengthRow.row);
        return card;
    }

    private LinearLayout buildPromotionCard(boolean enabled) {
        LinearLayout card = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        FrameLayout promotionPreviewWrap = new FrameLayout(this);
        promotionPreviewWrap.setPadding(dp(16), dp(16), dp(16), dp(4));
        mPromotionPreview = new HyperSettingsUi.PromotionPreviewView(this, mPalette);
        mPromotionPreview.setPromotionEnabled(enabled, false);
        promotionPreviewWrap.addView(mPromotionPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(promotionPreviewWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mPromotionRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "噪音过滤",
                "将识别出的广告收进「推广通知」，锁屏时隐藏。",
                enabled,
                false,
                true);
        mPromotionRow.row.setOnClickListener(view -> {
            if (!mPromotionRow.row.isClickable()) return;
            boolean target = !mPromotionRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setPromotionVisual(target, true);
            saveSwitchOption(Config.KEY_PROMOTION_FILTER, target, mPromotionRow);
        });
        card.addView(mPromotionRow.row);
        return card;
    }

    private void setPromotionVisual(boolean enabled, boolean animate) {
        if (mPromotionRow != null) {
            mPromotionRow.toggle.setChecked(enabled, animate);
        }
        if (mPromotionPreview != null) {
            mPromotionPreview.setPromotionEnabled(enabled, animate);
        }
    }

    private void setSummaryVisual(boolean enabled, boolean animate) {
        if (mSummaryRow != null) {
            mSummaryRow.toggle.setChecked(enabled, animate);
        }
        if (mSummaryPreview != null) {
            mSummaryPreview.setSummaryEnabled(enabled, animate);
        }
        applySummaryDependentAlpha(enabled);
    }

    private void setRecapVisual(boolean enabled, boolean animate) {
        if (mRecapRow != null) {
            mRecapRow.toggle.setChecked(enabled, animate);
        }
        if (mRecapPreview != null) {
            mRecapPreview.setRecapEnabled(enabled, animate);
        }
    }

    private void showRecapMinLengthDialog() {
        if (mRecapMinLengthBusy) return;
        int selected = 1;
        for (int i = 0; i < Config.RECAP_MIN_LENGTH_OPTIONS.length; i++) {
            if (Config.RECAP_MIN_LENGTH_OPTIONS[i] == mRecapMinLength) {
                selected = i;
                break;
            }
        }
        List<HyperSettingsUi.ChoiceItem> items = Arrays.asList(
                new HyperSettingsUi.ChoiceItem("50 字", "约 50 个汉字或 100 个英文字母，覆盖较短段落"),
                new HyperSettingsUi.ChoiceItem("80 字（默认）", "约 80 个汉字或 160 个英文字母，兼顾阅读与精简"),
                new HyperSettingsUi.ChoiceItem("120 字", "约 120 个汉字或 240 个英文字母，仅概括明显长消息"),
                new HyperSettingsUi.ChoiceItem("200 字", "约 200 个汉字或 400 个英文字母，仅概括超长通知")
        );
        HyperSettingsUi.showChoiceDialog(this, mPalette, "触发字数门槛", items, selected, idx -> {
            if (idx < 0 || idx >= Config.RECAP_MIN_LENGTH_OPTIONS.length) return;
            int target = Config.RECAP_MIN_LENGTH_OPTIONS[idx];
            if (target == mRecapMinLength) return;
            int previous = mRecapMinLength;
            setRecapMinLengthVisual(target);
            saveRecapMinLength(target, previous);
        });
    }

    private void setRecapMinLengthVisual(int minLength) {
        mRecapMinLength = Config.sanitizeRecapMinLength(minLength);
        if (mRecapMinLengthRow != null) {
            mRecapMinLengthRow.value.setText(formatRecapMinLength(mRecapMinLength));
            mRecapMinLengthRow.value.setVisibility(View.VISIBLE);
        }
    }

    private static String formatRecapMinLength(int minLength) {
        return Config.sanitizeRecapMinLength(minLength) + " 字";
    }

    private void setPriorityVisual(boolean enabled, boolean animate) {
        if (mPriorityRow != null) {
            mPriorityRow.toggle.setChecked(enabled, animate);
        }
        if (mPriorityPreview != null) {
            mPriorityPreview.setPriorityEnabled(enabled, animate);
        }
    }

    private void setReduceVisual(boolean enabled, boolean animate) {
        if (mReduceRow != null) {
            mReduceRow.toggle.setChecked(enabled, animate);
        }
        if (mReducePreview != null) {
            mReducePreview.setReduceEnabled(enabled, animate);
        }
        renderReduceSubtitle();
    }

    private void renderReduceSubtitle() {
        if (mReduceRow == null) return;
        boolean modelReady = mLastConfig != null && mLastConfig.getBoolean(Config.KEY_MODEL_READY);
        if (!modelReady) {
            mReduceRow.summary.setText("使用系统勿扰规则。下载模型后可智能放行重要通知。");
        } else {
            mReduceRow.summary.setText("开启系统勿扰，由 AI 放行重要通知。");
        }
    }

    private void requestAddReduceTile() {
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        if (sbm == null) {
            Toast.makeText(this, "请在控制中心编辑页面手动添加「减少干扰」", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            sbm.requestAddTileService(
                    new ComponentName(this, ReduceInterruptionsTileService.class),
                    "减少干扰",
                    ReduceInterruptions.createTileIcon(this),
                    getMainExecutor(),
                    resultCode -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                            Toast.makeText(this, "已添加到控制中心", Toast.LENGTH_SHORT).show();
                        } else if (resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                            Toast.makeText(this, "控制中心已包含「减少干扰」开关", Toast.LENGTH_SHORT).show();
                        } else if (resultCode != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
                            Toast.makeText(this, "请在控制中心编辑页面手动添加「减少干扰」", Toast.LENGTH_SHORT).show();
                        }
                    });
        } catch (RuntimeException e) {
            Toast.makeText(this, "请在控制中心编辑页面手动添加「减少干扰」", Toast.LENGTH_SHORT).show();
        }
    }

    private void setAlwaysLoadVisual(boolean enabled, boolean animate) {
        if (mAlwaysLoadRow != null) {
            mAlwaysLoadRow.toggle.setChecked(enabled, animate);
        }
        renderAlwaysLoadSubtitle(enabled);
    }

    private void showGlowStyleDialog() {
        if (mGlowStyleBusy) return;
        int selected = Config.isXiaoAiGlow(mGlowStyle) ? 0 : 1;
        List<HyperSettingsUi.ChoiceItem> items = Arrays.asList(
                new HyperSettingsUi.ChoiceItem(
                        "超级小爱流光",
                        "暖橙、粉红与电光紫蓝霓虹光效，贴合澎湃超级岛质感"),
                new HyperSettingsUi.ChoiceItem(
                        "星云极光",
                        "经典天青、晴空蓝与冰紫液态玻璃光效")
        );
        HyperSettingsUi.showChoiceDialog(this, mPalette, "光效风格", items, selected, index -> {
            String target = index == 0 ? Config.GLOW_STYLE_XIAOAI : Config.GLOW_STYLE_NEBULA;
            if (target.equals(mGlowStyle)) return;
            String previous = mGlowStyle;
            setGlowStyleVisual(target, true);
            saveGlowStyle(target, previous);
        });
    }

    private void setGlowStyleVisual(String style, boolean replayDemo) {
        String normalized = Config.sanitizeGlowStyle(style);
        mGlowStyle = normalized;
        GlowStyle.setCurrent(normalized);
        if (mGlowStyleRow != null) {
            mGlowStyleRow.value.setText(GlowStyle.displayLabel(normalized));
            mGlowStyleRow.value.setVisibility(View.VISIBLE);
        }
        boolean xiaoAi = Config.isXiaoAiGlow(normalized);
        if (mSummaryPreview != null) mSummaryPreview.setGlowStyle(xiaoAi, replayDemo);
        if (mRecapPreview != null) mRecapPreview.setGlowStyle(xiaoAi, replayDemo);
        if (mPriorityPreview != null) mPriorityPreview.setGlowStyle(xiaoAi, replayDemo);
        if (mReducePreview != null) mReducePreview.setGlowStyle(xiaoAi, replayDemo);
        if (mPromotionPreview != null) mPromotionPreview.setGlowStyle(xiaoAi, replayDemo);
    }

    private void saveGlowStyle(String target, String previous) {
        mGlowStyleBusy = true;
        if (mGlowStyleRow != null) mGlowStyleRow.row.setClickable(false);
        OPTION_IO.execute(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putString(Config.KEY_GLOW_STYLE, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mGlowStyleBusy = false;
                if (mGlowStyleRow != null) mGlowStyleRow.row.setClickable(true);
                if (finalResult != null) {
                    mLastConfig = finalResult;
                    String actual = Config.sanitizeGlowStyle(
                            finalResult.getString(Config.KEY_GLOW_STYLE, target));
                    if (!actual.equals(mGlowStyle)) {
                        setGlowStyleVisual(actual, false);
                    }
                } else {
                    setGlowStyleVisual(previous, false);
                    Toast.makeText(this, "无法保存光效风格设置", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void applySummaryDependentAlpha(boolean summaryEnabled) {
        if (mSummaryAdvancedRow != null) {
            float alpha = summaryEnabled ? 1.0f : 0.62f;
            mSummaryAdvancedRow.title.setAlpha(alpha);
            mSummaryAdvancedRow.summary.setAlpha(alpha);
        }
    }

    private void refreshConfigFromProvider() {
        OPTION_IO.execute(() -> {
            Bundle config = null;
            try {
                config = getContentResolver().call(PROVIDER, "get_config", null, null);
            } catch (RuntimeException ignored) {
            }
            Bundle result = config;
            ModelCatalog.ActiveSize size = ModelCatalog.ActiveSize.read(result);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result != null) {
                    mActiveSize = size;
                    applyConfigBundle(result, false);
                } else {
                    renderModelEntry();
                }
            });
        });
    }

    private void saveRecapEnabled(boolean target) {
        mRecapBusy = true;
        if (mRecapRow != null) mRecapRow.row.setClickable(false);
        OPTION_IO.execute(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putBoolean(Config.KEY_NOTIFICATION_RECAP, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mRecapBusy = false;
                if (mRecapRow != null) mRecapRow.row.setClickable(true);
                if (finalResult != null) {
                    applyConfigBundle(finalResult, true);
                } else {
                    setRecapVisual(!target, true);
                    Toast.makeText(this, "无法保存 AI 概括设置", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void saveRecapMinLength(int target, int previous) {
        mRecapMinLengthBusy = true;
        if (mRecapMinLengthRow != null) mRecapMinLengthRow.row.setClickable(false);
        OPTION_IO.execute(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putInt(Config.KEY_RECAP_MIN_LENGTH, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mRecapMinLengthBusy = false;
                if (mRecapMinLengthRow != null) mRecapMinLengthRow.row.setClickable(true);
                if (finalResult != null) {
                    applyConfigBundle(finalResult, false);
                } else {
                    setRecapMinLengthVisual(previous);
                    Toast.makeText(this, "无法保存触发字数门槛设置", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void saveSummaryEnabled(boolean target) {
        mSummaryBusy = true;
        if (mSummaryRow != null) mSummaryRow.row.setClickable(false);
        new Thread(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putBoolean(Config.KEY_GROUP_SUMMARY, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (finalResult != null) {
                    applyConfigBundle(finalResult, true);
                } else {
                    setSummaryVisual(!target, true);
                    Toast.makeText(this, "无法保存 AI 摘要设置", Toast.LENGTH_SHORT).show();
                }
                mSummaryBusy = false;
                if (mSummaryRow != null) mSummaryRow.row.setClickable(true);
            });
        }, "LocalAiNotif-save-summary").start();
    }

    private void savePriorityEnabled(boolean target) {
        mPriorityBusy = true;
        if (mPriorityRow != null) mPriorityRow.row.setClickable(false);
        new Thread(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putBoolean(Config.KEY_PRIORITY_ENABLED, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_PRIORITY_ENABLED, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (finalResult != null) {
                    applyConfigBundle(finalResult, true);
                } else {
                    setPriorityVisual(!target, true);
                    Toast.makeText(this, "无法保存重要通知设置", Toast.LENGTH_SHORT).show();
                }
                mPriorityBusy = false;
                if (mPriorityRow != null) mPriorityRow.row.setClickable(true);
            });
        }, "LocalAiNotif-save-priority").start();
    }

    private void saveAlwaysLoadModel(boolean target) {
        mAlwaysLoadBusy = true;
        if (mAlwaysLoadRow != null) mAlwaysLoadRow.row.setClickable(false);
        new Thread(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putBoolean(Config.KEY_ALWAYS_LOAD_MODEL, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mAlwaysLoadBusy = false;
                if (mAlwaysLoadRow != null) mAlwaysLoadRow.row.setClickable(true);
                if (finalResult != null) {
                    applyConfigBundle(finalResult, true);
                } else {
                    setAlwaysLoadVisual(!target, true);
                    Toast.makeText(this, "无法保存模型常驻设置", Toast.LENGTH_SHORT).show();
                }
            });
        }, "LocalAiNotif-save-always-load").start();
    }

    private void saveReduceEnabled(boolean target) {
        mReduceBusy = true;
        if (mReduceRow != null) mReduceRow.row.setClickable(false);
        new Thread(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putBoolean(Config.KEY_REDUCE_ENABLED, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mReduceBusy = false;
                if (mReduceRow != null) mReduceRow.row.setClickable(true);
                if (finalResult != null) {
                    applyConfigBundle(finalResult, true);
                } else {
                    setReduceVisual(!target, true);
                    Toast.makeText(this, "无法保存减少干扰设置", Toast.LENGTH_SHORT).show();
                }
            });
        }, "LocalAiNotif-save-reduce").start();
    }

    private void saveSwitchOption(String key, boolean target, HyperSettingsUi.SwitchRowParts row) {
        row.row.setClickable(false);
        OPTION_IO.execute(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putBoolean(key, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                row.row.setClickable(true);
                if (finalResult != null) {
                    mLastConfig = finalResult;
                    boolean actual = finalResult.getBoolean(key, target);
                    if (row == mPromotionRow) {
                        setPromotionVisual(actual, true);
                    } else {
                        row.toggle.setChecked(actual, true);
                    }
                } else {
                    if (row == mPromotionRow) {
                        setPromotionVisual(!target, true);
                        Toast.makeText(this, "无法保存噪音过滤设置", Toast.LENGTH_SHORT).show();
                    } else {
                        row.toggle.setChecked(!target, true);
                        Toast.makeText(this, "无法保存设置", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
    }

    private void applyConfigBundle(Bundle config, boolean animate) {
        mLastConfig = config;
        if (mPromotionRow != null && mPromotionRow.row.isClickable()) {
            boolean enabled = config.getBoolean(Config.KEY_PROMOTION_FILTER, Config.DEFAULT_PROMOTION_FILTER);
            setPromotionVisual(enabled, animate);
        }
        if (!mRecapBusy) {
            boolean recap = config.getBoolean(Config.KEY_NOTIFICATION_RECAP, Config.DEFAULT_NOTIFICATION_RECAP);
            setRecapVisual(recap, animate);
        }
        if (mRecapScreenOnRow != null && mRecapScreenOnRow.row.isClickable()) {
            mRecapScreenOnRow.toggle.setChecked(config.getBoolean(
                    Config.KEY_RECAP_SCREEN_ON, Config.DEFAULT_RECAP_SCREEN_ON), animate);
        }
        if (!mRecapMinLengthBusy) {
            setRecapMinLengthVisual(config.getInt(
                    Config.KEY_RECAP_MIN_LENGTH, Config.DEFAULT_RECAP_MIN_LENGTH));
        }
        boolean priority = config.getBoolean(Config.KEY_PRIORITY_ENABLED, Config.DEFAULT_PRIORITY_ENABLED);
        boolean summary = config.getBoolean(Config.KEY_GROUP_SUMMARY, Config.DEFAULT_GROUP_SUMMARY);
        boolean reduce = config.getBoolean(Config.KEY_REDUCE_ENABLED, Config.DEFAULT_REDUCE_ENABLED);
        boolean reduceLockOnly = config.getBoolean(
                Config.KEY_REDUCE_LOCK_SCREEN_ONLY, Config.DEFAULT_REDUCE_LOCK_SCREEN_ONLY);
        boolean alwaysLoad = config.getBoolean(Config.KEY_ALWAYS_LOAD_MODEL, Config.DEFAULT_ALWAYS_LOAD_MODEL);
        if (!mPriorityBusy) {
            setPriorityVisual(priority, animate);
        }
        if (!mSummaryBusy) {
            setSummaryVisual(summary, animate);
        }
        if (!mReduceBusy) {
            setReduceVisual(reduce, animate);
        } else {
            renderReduceSubtitle();
        }
        if (mReduceLockOnlyRow != null && mReduceLockOnlyRow.row.isClickable()) {
            mReduceLockOnlyRow.toggle.setChecked(reduceLockOnly, animate);
        }
        if (mReduceIconsRow.row.isClickable()) {
            mReduceIconsRow.toggle.setChecked(config.getBoolean(
                    Config.KEY_REDUCE_FILTER_ICONS, Config.DEFAULT_REDUCE_FILTER_ICONS), animate);
        }
        if (mReduceSoundsRow.row.isClickable()) {
            mReduceSoundsRow.toggle.setChecked(config.getBoolean(Config.KEY_REDUCE_NORMAL_SOUNDS, false), animate);
        }
        if (!mAlwaysLoadBusy) {
            setAlwaysLoadVisual(alwaysLoad, animate);
        }
        if (!mGlowStyleBusy) {
            String glowStyle = Config.sanitizeGlowStyle(
                    config.getString(Config.KEY_GLOW_STYLE, Config.DEFAULT_GLOW_STYLE));
            setGlowStyleVisual(glowStyle, false);
        }
        renderSummaryAdvancedSubtitle(config);
        renderModelEntry();
    }

    private void renderAlwaysLoadSubtitle(boolean alwaysLoad) {
        if (mAlwaysLoadRow == null) return;
        boolean modelReady = mLastConfig != null && mLastConfig.getBoolean(Config.KEY_MODEL_READY);
        boolean engineLoaded = mLastConfig != null
                && mLastConfig.getBoolean(Config.KEY_ENGINE_LOADED, false);
        boolean engineLoading = mLastConfig != null
                && mLastConfig.getBoolean(Config.KEY_ENGINE_LOADING, false);

        String subtitle;
        if (alwaysLoad) {
            if (!modelReady) {
                subtitle = "已开启，下载或启用模型后将自动常驻内存。";
            } else if (engineLoading) {
                subtitle = "正在将模型加载至内存…";
            } else if (engineLoaded) {
                subtitle = "模型已常驻内存，通知到达时免冷启动。";
            } else {
                subtitle = "保持模型常驻内存，通知到达时免冷启动。";
            }
        } else {
            if (engineLoading) {
                subtitle = "按需加载中，空闲 3 分钟无推理后自动释放内存。";
            } else if (engineLoaded) {
                subtitle = "当前已按需加载，空闲 3 分钟无推理后自动释放内存。";
            } else {
                subtitle = "关闭时按需加载，空闲 3 分钟无推理后自动释放内存。";
            }
        }
        mAlwaysLoadRow.summary.setText(subtitle);
    }

    private void renderSummaryAdvancedSubtitle(Bundle config) {
        if (mSummaryAdvancedRow == null) return;
        boolean screenOn = config.getBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, Config.DEFAULT_SUMMARIZE_SCREEN_ON);
        int minLen = config.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH);
        int waitSec = config.getInt(Config.KEY_WAIT_SECONDS, Config.DEFAULT_WAIT_SECONDS);

        StringBuilder sb = new StringBuilder();
        sb.append(screenOn ? "亮屏时生效" : "仅息屏生效");
        if (minLen > 0) {
            sb.append("，超过 ").append(minLen).append(" 字");
        } else {
            sb.append("，不限字数");
        }
        if (waitSec > 0) {
            sb.append("，等待 ").append(waitSec).append(" 秒");
        }
        mSummaryAdvancedRow.summary.setText(sb.toString());
        mSummaryAdvancedRow.summary.setVisibility(View.VISIBLE);
    }

    private void renderModelEntry() {
        if (mModelRow == null) return;
        ModelDownloadManager.Snapshot activeDl = mDownloadManager != null
                ? mDownloadManager.getActiveSnapshot()
                : null;
        if (activeDl != null) {
            ModelCatalog.ModelSpec dlSpec = ModelCatalog.findById(activeDl.modelId);
            String name = dlSpec != null ? dlSpec.displayName : "端侧模型";
            mModelRow.statusDot.setVisibility(View.VISIBLE);
            mModelRow.statusDot.setColor(mPalette.accent);
            mModelRow.value.setVisibility(View.VISIBLE);
            mModelRow.value.setTextColor(mPalette.accent);
            if (activeDl.state == ModelDownloadManager.State.VERIFYING) {
                mModelRow.value.setText("校验中");
                mModelRow.summary.setText("正在校验 " + name);
            } else if (activeDl.state == ModelDownloadManager.State.FETCHING_META) {
                mModelRow.value.setText("准备中");
                mModelRow.summary.setText("正在准备下载 " + name);
            } else {
                mModelRow.value.setText(activeDl.progressPercent() + "%");
                String speed = ModelCatalog.formatSpeed(activeDl.speedBytesPerSec);
                mModelRow.summary.setText("正在下载 " + name + (speed.isEmpty() ? "" : "（" + speed + "）"));
            }
            mModelRow.summary.setVisibility(View.VISIBLE);
            return;
        }

        String modelPath = mLastConfig != null
                ? mLastConfig.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH)
                : Config.DEFAULT_MODEL_PATH;
        File dir = new File(modelPath);
        boolean ready = mLastConfig != null && mLastConfig.getBoolean(Config.KEY_MODEL_READY);
        ModelCatalog.ModelSpec matched = ModelCatalog.findByPath(this, modelPath);

        mModelRow.statusDot.setVisibility(View.VISIBLE);
        mModelRow.value.setVisibility(View.VISIBLE);
        mModelRow.summary.setVisibility(View.VISIBLE);

        if (ready) {
            String displayName = matched != null ? matched.displayName : dir.getName();
            mModelRow.statusDot.setColor(mPalette.statusReady);
            mModelRow.value.setTextColor(mPalette.textSecondary);
            mModelRow.value.setText(displayName);
            mModelRow.summary.setText(mActiveSize != null && modelPath.equals(mActiveSize.path())
                    ? "已就绪，占用 " + ModelCatalog.formatBytes(mActiveSize.bytes()) : "已就绪");
        } else {
            mModelRow.statusDot.setColor(mPalette.statusWarn);
            mModelRow.value.setTextColor(mPalette.accent);
            mModelRow.value.setText("去下载");
            mModelRow.summary.setText("未下载模型，下载后启用端侧 AI 识别与摘要。");
        }
    }

    private String resolveAppTitle() {
        int labelRes = getApplicationInfo().labelRes;
        if (labelRes != 0) {
            try {
                return getString(labelRes);
            } catch (RuntimeException ignored) {
            }
        }
        CharSequence label = getApplicationInfo().nonLocalizedLabel;
        return label != null && label.length() > 0 ? label.toString() : "端侧AI通知总结";
    }

    private String resolveVersionDisplay() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null && !info.versionName.isEmpty()) {
                return "v" + info.versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return "v" + Config.MODULE_VERSION_NAME;
    }

    private int dp(float value) {
        return HyperSettingsUi.dp(this, value);
    }
}
