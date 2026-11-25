package io.github.internetbugs.localainotif;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * HyperOS-styled About page:
 * centered brand hero header, entry visibility controls (System Settings entry &
 * Hide Launcher Icon with anti-lockout bottom-sheet confirmations), project metadata
 * (author, GPL-3.0 sheet, GitHub repo, Releases check), and dual-track LSPosed
 * runtime environment / diagnostics card.
 */
public final class AboutActivity extends Activity {
    private static final Uri PROVIDER = Uri.parse("content://" + Config.AUTHORITY);
    private static final String REPO_URL = ReleaseUpdate.REPO_URL;
    private static final String RELEASES_URL = REPO_URL + "/releases";
    private static final String LICENSE_URL = REPO_URL + "/blob/main/LICENSE";
    private static final String DEVELOPER_NAME = "酥鱼 (InternetBugs)";
    private static final String SCOPE_LIST_DISPLAY =
            "system、com.android.systemui、com.miui.notification、com.android.settings、io.github.internetbugs.localainotif";
    private static final long HOOK_PROBE_TIMEOUT_MS = 750L;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private HyperSettingsUi.Palette mPalette;

    private HyperSettingsUi.SwitchRowParts mSystemEntryRow;
    private HyperSettingsUi.SwitchRowParts mHideIconRow;
    private HyperSettingsUi.NavRowParts mUpdateRow;
    private HyperSettingsUi.NavRowParts mModuleStatusRow;
    private HyperSettingsUi.NavRowParts mDeviceRow;
    private HyperSettingsUi.NavRowParts mModelStatusRow;

    private boolean mShowInSystemSettings = Config.DEFAULT_SHOW_IN_SYSTEM_SETTINGS;
    private boolean mSystemEntryBusy;
    private boolean mSystemHookActive;
    private int mSystemHookVersionCode;
    private String mSystemHookFramework = "";
    private boolean mHookProbePending;
    private Bundle mLastConfig;
    private ModelCatalog.ActiveSize mActiveSize;

    private boolean mReceiversRegistered;
    private final Runnable mHookProbeTimeout = () -> {
        if (isFinishing() || isDestroyed()) return;
        mHookProbePending = false;
        refreshConfigAndProbe(false);
    };

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isFinishing() || isDestroyed()) return;
            String action = intent != null ? intent.getAction() : null;
            if (Config.ACTION_HOOK_STATE_CHANGED.equals(action)) {
                mMainHandler.removeCallbacks(mHookProbeTimeout);
                mHookProbePending = false;
                refreshConfigAndProbe(false);
            } else if (Config.ACTION_ENGINE_STATE_CHANGED.equals(action)) {
                refreshConfigAndProbe(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean isDark = HyperSettingsUi.applyTheme(this);
        super.onCreate(savedInstanceState);

        mPalette = HyperSettingsUi.Palette.of(isDark);
        HyperSettingsUi.configureEdgeToEdge(this, mPalette);

        setContentView(buildContentView());
        renderLauncherIconState(false);
        renderModuleStatus();
        renderDeviceInfo();
        renderModelStatus();
        refreshConfigAndProbe(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mReceiversRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Config.ACTION_HOOK_STATE_CHANGED);
            filter.addAction(Config.ACTION_ENGINE_STATE_CHANGED);
            registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            mReceiversRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderLauncherIconState(false);
        refreshConfigAndProbe(true);
    }

    @Override
    protected void onStop() {
        mMainHandler.removeCallbacks(mHookProbeTimeout);
        if (mReceiversRegistered) {
            unregisterReceiver(mStateReceiver);
            mReceiversRegistered = false;
        }
        super.onStop();
    }

    private View buildContentView() {
        HyperSettingsUi.PageShell shell = HyperSettingsUi.buildPageShell(
                this, mPalette, "关于", true);
        // Use HyperOS classic centered hero header at top; collapsed sticky "关于" fades in on scroll.
        shell.largeTitle.setVisibility(View.GONE);
        LinearLayout content = shell.content;

        content.addView(buildHeroHeader());

        // Group 1: 入口设置
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "入口设置", 8));
        LinearLayout entryCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        mSystemEntryRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "在系统设置中显示入口",
                "在系统「通知与状态栏」的「小米超级岛」下方显示入口。",
                mShowInSystemSettings,
                true,
                false);
        mSystemEntryRow.row.setOnClickListener(this::onSystemEntryClicked);
        entryCard.addView(mSystemEntryRow.row);
        entryCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        boolean iconHidden = isLauncherIconHidden();
        mHideIconRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "隐藏桌面图标",
                buildHideIconSubtitle(mShowInSystemSettings),
                iconHidden,
                false,
                true);
        mHideIconRow.row.setOnClickListener(this::onHideIconClicked);
        entryCard.addView(mHideIconRow.row);
        content.addView(entryCard);

        // Group 2: 项目信息
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "项目信息", 20));
        LinearLayout projectCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        HyperSettingsUi.NavRowParts devRow = HyperSettingsUi.createNavRow(
                this, mPalette, "开发者", null, DEVELOPER_NAME, false, true, false);
        makeStaticInfoRow(devRow);
        projectCard.addView(devRow.row);
        projectCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        HyperSettingsUi.NavRowParts licenseRow = HyperSettingsUi.createNavRow(
                this, mPalette, "开源协议", null, "GPL-3.0", false, false, false);
        licenseRow.row.setOnClickListener(v -> showLicenseBottomSheet());
        projectCard.addView(licenseRow.row);
        projectCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        HyperSettingsUi.NavRowParts repoRow = HyperSettingsUi.createNavRow(
                this, mPalette, "GitHub 仓库", null, "InternetBugs/LocalAiNotif-For-HyperOS", false, false, false);
        repoRow.row.setOnClickListener(v -> openExternalUrl(REPO_URL));
        projectCard.addView(repoRow.row);
        projectCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mUpdateRow = HyperSettingsUi.createNavRow(
                this, mPalette, "检查更新", "检查是否有新的正式版。", null, false, false, true);
        mUpdateRow.summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        mUpdateRow.row.setOnClickListener(view -> checkForUpdate());
        projectCard.addView(mUpdateRow.row);
        content.addView(projectCard);

        // Group 3: 运行环境
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "运行环境", 20));
        LinearLayout envCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        mModuleStatusRow = HyperSettingsUi.createNavRow(
                this, mPalette, "LSPosed 模块状态", "正在检测模块激活状态…", "检测中", true, true, false);
        mModuleStatusRow.chevron.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusValLp =
                (LinearLayout.LayoutParams) mModuleStatusRow.value.getLayoutParams();
        if (statusValLp != null) {
            statusValLp.setMarginEnd(0);
            mModuleStatusRow.value.setLayoutParams(statusValLp);
        }
        mModuleStatusRow.row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            refreshConfigAndProbe(true);
            Toast.makeText(this, "正在重新检测模块状态…", Toast.LENGTH_SHORT).show();
        });
        envCard.addView(mModuleStatusRow.row);
        envCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mDeviceRow = HyperSettingsUi.createNavRow(
                this, mPalette, "设备与系统", "", null, false, false, false);
        makeStaticInfoRow(mDeviceRow);
        envCard.addView(mDeviceRow.row);
        envCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mModelStatusRow = HyperSettingsUi.createNavRow(
                this, mPalette, "当前模型与引擎", "正在检测模型状态…", "检测中", true, false, false);
        makeStaticInfoRow(mModelStatusRow);
        envCard.addView(mModelStatusRow.row);
        envCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        HyperSettingsUi.NavRowParts scopeRow = HyperSettingsUi.createNavRow(
                this, mPalette, "推荐作用域", SCOPE_LIST_DISPLAY, null, false, false, false);
        makeStaticInfoRow(scopeRow);
        envCard.addView(scopeRow.row);
        envCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        HyperSettingsUi.NavRowParts copyDiagRow = HyperSettingsUi.createNavRow(
                this, mPalette, "复制诊断信息", "汇总版本、模块状态、系统与模型信息，便于反馈问题。",
                "复制", false, false, true);
        copyDiagRow.value.setTextColor(mPalette.accent);
        copyDiagRow.row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            copyToClipboard("LocalAiNotif 诊断信息", buildDiagnosticsText(), "已复制诊断信息");
        });
        envCard.addView(copyDiagRow.row);
        content.addView(envCard);

        // Footer: Privacy & offline commitment
        content.addView(HyperSettingsUi.createFooter(
                this,
                mPalette,
                "所有通知分类与摘要均在设备本地通过端侧模型完成，不上传任何隐私数据，无需云端 API。"));

        return shell.root;
    }

    private View buildHeroHeader() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.setMargins(dp(28), dp(68), dp(28), dp(20));
        hero.setLayoutParams(heroLp);

        Drawable appIcon = null;
        try {
            appIcon = getApplicationInfo().loadIcon(getPackageManager());
        } catch (RuntimeException ignored) {
        }
        SquircleIconView iconView = new SquircleIconView(this, appIcon, dpF(16f));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        hero.addView(iconView, iconLp);

        TextView appTitle = new TextView(this);
        appTitle.setText(resolveAppTitle());
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        appTitle.setTypeface(HyperSettingsUi.typeface(500));
        appTitle.setTextColor(mPalette.textPrimary);
        appTitle.setIncludeFontPadding(false);
        appTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(14);
        hero.addView(appTitle, titleLp);

        String versionPillText = resolveFullVersionLabel();
        TextView versionPill = new TextView(this);
        versionPill.setText(versionPillText);
        versionPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        versionPill.setTypeface(HyperSettingsUi.typeface(500));
        versionPill.setIncludeFontPadding(false);
        versionPill.setPadding(dp(11), dp(4.5f), dp(11), dp(4.5f));
        versionPill.setClickable(true);
        versionPill.setFocusable(true);
        HyperSettingsUi.applyCapsuleStyle(
                this, mPalette, versionPill, HyperSettingsUi.CapsuleStyle.SOFT_BLUE, 12f);
        versionPill.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            copyToClipboard("应用版本", resolveAppTitle() + " " + versionPillText, "已复制版本信息");
        });
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.topMargin = dp(8);
        hero.addView(versionPill, pillLp);

        TextView tagline = new TextView(this);
        tagline.setText("本地端侧大模型驱动的 HyperOS 智能通知模块");
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tagline.setTypeface(HyperSettingsUi.typeface(400));
        tagline.setTextColor(mPalette.textSecondary);
        tagline.setGravity(Gravity.CENTER);
        tagline.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = dp(10);
        hero.addView(tagline, tagLp);

        return hero;
    }

    private void onSystemEntryClicked(View view) {
        if (mSystemEntryBusy) return;
        boolean target = !mSystemEntryRow.toggle.isChecked();
        if (!target && isLauncherIconHidden()) {
            HyperSettingsUi.showConfirmDialog(
                    this,
                    mPalette,
                    "关闭系统设置入口",
                    "当前已隐藏桌面图标。关闭系统设置入口后，后续仅能从「LSPosed 管理器」重新打开本应用。",
                    "仍要关闭",
                    true,
                    () -> commitSystemEntryToggle(false));
            return;
        }
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        commitSystemEntryToggle(target);
    }

    private void commitSystemEntryToggle(boolean target) {
        mSystemEntryBusy = true;
        if (mSystemEntryRow != null) {
            mSystemEntryRow.row.setClickable(false);
            mSystemEntryRow.toggle.setChecked(target, true);
        }
        new Thread(() -> {
            Bundle result = null;
            try {
                Bundle input = new Bundle();
                input.putBoolean(Config.KEY_SHOW_IN_SYSTEM_SETTINGS, target);
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, input);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mSystemEntryBusy = false;
                if (mSystemEntryRow != null) mSystemEntryRow.row.setClickable(true);
                if (finalResult != null) {
                    applyConfigBundle(finalResult, true);
                } else {
                    if (mSystemEntryRow != null) {
                        mSystemEntryRow.toggle.setChecked(!target, true);
                    }
                    Toast.makeText(this, "无法保存入口设置", Toast.LENGTH_SHORT).show();
                }
            });
        }, "LocalAiNotif-save-system-entry").start();
    }

    private void onHideIconClicked(View view) {
        boolean currentlyHidden = isLauncherIconHidden();
        if (!currentlyHidden) {
            String message = mShowInSystemSettings
                    ? "隐藏后桌面将不再显示应用图标，你仍可从「系统设置 › 通知与状态栏 › 端侧AI通知总结」或「LSPosed 管理器」重新打开。"
                    : "当前未开启系统设置入口。隐藏桌面图标后，你仅能从「LSPosed 管理器」重新打开本应用。";
            HyperSettingsUi.showConfirmDialog(
                    this,
                    mPalette,
                    "隐藏桌面图标",
                    message,
                    "确认隐藏",
                    !mShowInSystemSettings,
                    () -> applyLauncherIconHidden(true));
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            applyLauncherIconHidden(false);
        }
    }

    private void applyLauncherIconHidden(boolean hidden) {
        try {
            PackageManager pm = getPackageManager();
            ComponentName alias = new ComponentName(this, Config.LAUNCHER_ALIAS_CLASS);
            pm.setComponentEnabledSetting(
                    alias,
                    hidden
                            ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            renderLauncherIconState(true);
            Toast.makeText(this, hidden ? "已隐藏桌面图标" : "已恢复桌面图标", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            renderLauncherIconState(true);
            Toast.makeText(this, "切换桌面图标失败", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isLauncherIconHidden() {
        try {
            int state = getPackageManager().getComponentEnabledSetting(
                    new ComponentName(this, Config.LAUNCHER_ALIAS_CLASS));
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void renderLauncherIconState(boolean animate) {
        if (mHideIconRow == null) return;
        mHideIconRow.toggle.setChecked(isLauncherIconHidden(), animate);
        mHideIconRow.summary.setText(buildHideIconSubtitle(mShowInSystemSettings));
    }

    private static String buildHideIconSubtitle(boolean systemEntryEnabled) {
        return systemEntryEnabled
                ? "隐藏桌面启动图标，可从「系统设置 › 通知与状态栏」或 LSPosed 管理器打开。"
                : "隐藏桌面启动图标，隐藏后可从「LSPosed 管理器」重新打开。";
    }

    private void refreshConfigAndProbe(boolean allowPing) {
        new Thread(() -> {
            Bundle config = null;
            try {
                config = getContentResolver().call(PROVIDER, "get_config", null, null);
            } catch (RuntimeException ignored) {
            }
            Bundle finalConfig = config;
            ModelCatalog.ActiveSize size = ModelCatalog.ActiveSize.read(config);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (finalConfig != null) {
                    mActiveSize = size;
                    applyConfigBundle(finalConfig, false);
                } else {
                    renderModuleStatus();
                    renderModelStatus();
                }
                if (allowPing && !isCurrentSystemHookVerified() && !mHookProbePending) {
                    triggerSystemHookProbe();
                }
            });
        }, "LocalAiNotif-about-load").start();
    }

    private void triggerSystemHookProbe() {
        mHookProbePending = true;
        renderModuleStatus();
        try {
            sendBroadcast(new Intent(Config.ACTION_PING_SYSTEM_HOOK).setPackage("android"));
        } catch (RuntimeException ignored) {
        }
        mMainHandler.removeCallbacks(mHookProbeTimeout);
        mMainHandler.postDelayed(mHookProbeTimeout, HOOK_PROBE_TIMEOUT_MS);
    }

    private void applyConfigBundle(Bundle config, boolean animate) {
        mLastConfig = config;
        mShowInSystemSettings = config.getBoolean(
                Config.KEY_SHOW_IN_SYSTEM_SETTINGS, Config.DEFAULT_SHOW_IN_SYSTEM_SETTINGS);
        mSystemHookActive = config.getBoolean(Config.KEY_SYSTEM_HOOK_ACTIVE, false);
        mSystemHookVersionCode = config.getInt(Config.KEY_SYSTEM_HOOK_VERSION_CODE, 0);
        String fw = config.getString(Config.KEY_SYSTEM_HOOK_FRAMEWORK);
        mSystemHookFramework = fw != null ? fw : "";
        if (isCurrentSystemHookVerified()) {
            mMainHandler.removeCallbacks(mHookProbeTimeout);
            mHookProbePending = false;
        }
        if (!mSystemEntryBusy && mSystemEntryRow != null) {
            mSystemEntryRow.toggle.setChecked(mShowInSystemSettings, animate);
        }
        if (mHideIconRow != null) {
            mHideIconRow.summary.setText(buildHideIconSubtitle(mShowInSystemSettings));
        }
        renderModuleStatus();
        renderModelStatus();
    }

    private boolean isCurrentSystemHookVerified() {
        return mSystemHookActive && mSystemHookVersionCode == resolveCurrentVersionCode();
    }

    private String resolveFrameworkDisplay() {
        if (ModuleStatus.isSelfHooked()) {
            return ModuleStatus.formatFrameworkSummary();
        }
        if (mSystemHookFramework != null && !mSystemHookFramework.isEmpty()) {
            return mSystemHookFramework;
        }
        return "LSPosed";
    }

    private void renderModuleStatus() {
        if (mModuleStatusRow == null) return;
        boolean selfHooked = ModuleStatus.isSelfHooked();
        boolean currentSystemVerified = isCurrentSystemHookVerified();
        mModuleStatusRow.statusDot.setVisibility(View.VISIBLE);
        mModuleStatusRow.value.setVisibility(View.VISIBLE);
        mModuleStatusRow.summary.setVisibility(View.VISIBLE);

        if (currentSystemVerified) {
            mModuleStatusRow.statusDot.setColor(mPalette.statusReady);
            mModuleStatusRow.value.setTextColor(mPalette.statusReady);
            mModuleStatusRow.value.setText("已激活");
            mModuleStatusRow.summary.setText(resolveFrameworkDisplay() + " · 系统 Hook 运行中");
        } else if (mHookProbePending) {
            mModuleStatusRow.statusDot.setColor(mPalette.accent);
            mModuleStatusRow.value.setTextColor(mPalette.accent);
            mModuleStatusRow.value.setText("检测中");
            mModuleStatusRow.summary.setText("正在探测 LSPosed 与系统服务 Hook 状态…");
        } else if (mSystemHookActive) {
            mModuleStatusRow.statusDot.setColor(mPalette.statusWarn);
            mModuleStatusRow.value.setTextColor(mPalette.statusWarn);
            mModuleStatusRow.value.setText("需软重启");
            mModuleStatusRow.summary.setText("旧版系统 Hook 运行中，更新模块后请软重启使新版生效。");
        } else if (selfHooked) {
            mModuleStatusRow.statusDot.setColor(mPalette.statusWarn);
            mModuleStatusRow.value.setTextColor(mPalette.statusWarn);
            mModuleStatusRow.value.setText("需软重启");
            mModuleStatusRow.summary.setText(
                    ModuleStatus.formatFrameworkSummary() + " 已注入本应用，但系统服务尚未加载，请软重启设备。");
        } else {
            mModuleStatusRow.statusDot.setColor(mPalette.danger);
            mModuleStatusRow.value.setTextColor(mPalette.danger);
            mModuleStatusRow.value.setText("未激活");
            mModuleStatusRow.summary.setText("未检测到模块生效，请在 LSPosed 管理器中启用并软重启设备。");
        }
    }

    private void renderDeviceInfo() {
        if (mDeviceRow == null) return;
        mDeviceRow.summary.setText(buildDeviceSummary());
        mDeviceRow.summary.setVisibility(View.VISIBLE);
    }

    private void renderModelStatus() {
        if (mModelStatusRow == null) return;
        String modelPath = mLastConfig != null
                ? mLastConfig.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH)
                : Config.DEFAULT_MODEL_PATH;
        File dir = new File(modelPath);
        boolean ready = mLastConfig != null && mLastConfig.getBoolean(Config.KEY_MODEL_READY);
        boolean loaded = mLastConfig != null && mLastConfig.getBoolean(Config.KEY_ENGINE_LOADED, false);
        boolean loading = mLastConfig != null && mLastConfig.getBoolean(Config.KEY_ENGINE_LOADING, false);
        ModelCatalog.ModelSpec matched = ModelCatalog.findByPath(this, modelPath);

        mModelStatusRow.statusDot.setVisibility(View.VISIBLE);
        mModelStatusRow.value.setVisibility(View.VISIBLE);
        mModelStatusRow.summary.setVisibility(View.VISIBLE);

        if (ready) {
            String displayName = matched != null ? matched.displayName : dir.getName();
            String engineState = loading ? "正在加载" : (loaded ? "引擎已加载" : "引擎待机");
            mModelStatusRow.statusDot.setColor(mPalette.statusReady);
            mModelStatusRow.value.setTextColor(mPalette.textSecondary);
            mModelStatusRow.value.setText(displayName);
            String size = mActiveSize != null && modelPath.equals(mActiveSize.path())
                    ? "（" + ModelCatalog.formatBytes(mActiveSize.bytes()) + "）" : "";
            mModelStatusRow.summary.setText("已就绪" + size + " · " + engineState);
        } else {
            mModelStatusRow.statusDot.setColor(mPalette.statusWarn);
            mModelStatusRow.value.setTextColor(mPalette.statusWarn);
            mModelStatusRow.value.setText("未下载");
            mModelStatusRow.summary.setText("尚未下载或配置本地端侧模型。");
        }
    }

    private void showLicenseBottomSheet() {
        Dialog dialog = HyperSettingsUi.createBottomSheetDialog(this, mPalette);
        LinearLayout sheet = HyperSettingsUi.createDialogCard(this, mPalette);

        TextView title = new TextView(this);
        title.setText("GNU GPL v3.0 开源协议");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f);
        title.setTypeface(HyperSettingsUi.typeface(500));
        title.setTextColor(mPalette.textPrimary);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(dp(22), dp(22), dp(22), dp(12));
        sheet.addView(title, titleLp);

        TextView body = new TextView(this);
        body.setText(
                "LocalAiNotif 依据 GNU General Public License v3.0 发布。\n\n"
                        + "• 你可以自由使用、学习、修改与再分发本项目源代码。\n"
                        + "• 基于本项目的修改或衍生作品须同样以 GPL-3.0 协议开放源码，并保留原作者版权与协议声明。\n"
                        + "• 本软件按「现状」提供，不附带任何明示或暗示的担保。");
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        body.setTypeface(HyperSettingsUi.typeface(400));
        body.setTextColor(mPalette.textSecondary);
        body.setLineSpacing(dp(4), 1.0f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.setMargins(dp(24), 0, dp(24), dp(20));
        sheet.addView(body, bodyLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView closeBtn = HyperSettingsUi.createSheetButton(
                this, mPalette, "关闭", HyperSettingsUi.CapsuleStyle.SECONDARY_GRAY);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        leftLp.setMarginEnd(dp(10));
        btnRow.addView(closeBtn, leftLp);

        TextView viewBtn = HyperSettingsUi.createSheetButton(
                this, mPalette, "在 GitHub 查看全文", HyperSettingsUi.CapsuleStyle.PRIMARY_BLUE);
        viewBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            dialog.dismiss();
            openExternalUrl(LICENSE_URL);
        });
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        btnRow.addView(viewBtn, rightLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(dp(20), 0, dp(20), dp(20));
        sheet.addView(btnRow, rowLp);

        HyperSettingsUi.attachBottomSheetContent(this, dialog, sheet);
        dialog.show();
    }

    private void checkForUpdate() {
        mUpdateRow.row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        setUpdateStatus("正在检查更新…", true);
        new Thread(() -> {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                String installed = info.versionName != null ? info.versionName : Config.MODULE_VERSION_NAME;
                ReleaseUpdate update = ReleaseUpdate.check(installed);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (update == null) {
                        setUpdateStatus("当前版本 v" + installed + " 无需更新。", false);
                        return;
                    }
                    setUpdateStatus("发现新版本 " + update.tag, false);
                    if (!hasWindowFocus()) return;
                    if (update.downloadUrl == null) {
                        showUpdateFallback("此版本暂未提供可下载的安装包。",
                                REPO_URL + "/releases/tag/" + update.tag);
                        return;
                    }
                    HyperSettingsUi.showConfirmDialog(this, mPalette, "发现新版本 " + update.tag,
                            "当前版本 v" + installed + "。安装包大小为 " + ModelCatalog.formatBytes(update.size)
                                    + "，将保存到系统「下载」文件夹。",
                            "下载更新", false, () -> downloadUpdate(update));
                });
            } catch (IOException | JSONException | PackageManager.NameNotFoundException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setUpdateStatus("检查失败，点按重试。", false);
                    if (hasWindowFocus()) showUpdateFallback("暂时无法获取版本信息，可前往发布页查看。", RELEASES_URL);
                });
            }
        }, "LocalAiNotif-check-update").start();
    }

    private void downloadUpdate(ReleaseUpdate update) {
        setUpdateStatus("正在准备下载…", true);
        new Thread(() -> {
            try {
                ReleaseUpdate.DownloadState state = ReleaseUpdate.download(getApplicationContext(), update);
                String status = switch (state) {
                    case QUEUED -> "已开始下载 " + update.tag + "，可在系统「下载」中查看。";
                    case IN_PROGRESS -> "正在下载 " + update.tag + "，可在系统「下载」中查看。";
                    case ALREADY_DOWNLOADED -> "已下载 " + update.tag + "，可在系统「下载」中打开。";
                };
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setUpdateStatus(status, false);
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setUpdateStatus("无法开始下载，点按重试。", false);
                    if (hasWindowFocus()) showUpdateFallback("系统下载服务不可用，可前往发布页下载。",
                            REPO_URL + "/releases/tag/" + update.tag);
                });
            }
        }, "LocalAiNotif-download-update").start();
    }

    private void setUpdateStatus(String text, boolean busy) {
        mUpdateRow.row.setEnabled(!busy);
        mUpdateRow.summary.setText(text);
    }

    private void showUpdateFallback(String message, String url) {
        HyperSettingsUi.showConfirmDialog(this, mPalette, "检查更新", message, "查看发布页", false,
                () -> openExternalUrl(url));
    }

    private void openExternalUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            copyToClipboard("链接", url, "未找到浏览器，已复制链接");
        }
    }

    private void copyToClipboard(String label, String text, String toastMessage) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private String buildDiagnosticsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("LocalAiNotif 诊断信息\n");
        sb.append("版本: ").append(resolveFullVersionLabel()).append('\n');
        sb.append("LSPosed 框架: ").append(resolveFrameworkDisplay()).append('\n');
        sb.append("LSPosed 自注入: ").append(ModuleStatus.isSelfHooked() ? "已注入" : "未注入").append('\n');
        sb.append("system_server Hook: ")
                .append(isCurrentSystemHookVerified()
                        ? "运行中 (code=" + mSystemHookVersionCode + ")"
                        : (mSystemHookActive ? "旧版运行中（需软重启）" : "未检测到"))
                .append('\n');
        sb.append("设备与系统: ").append(buildDeviceSummary()).append('\n');
        if (mModelStatusRow != null) {
            sb.append("模型与引擎: ")
                    .append(mModelStatusRow.value.getText())
                    .append(" / ")
                    .append(mModelStatusRow.summary.getText())
                    .append('\n');
        }
        sb.append("系统设置入口: ").append(mShowInSystemSettings ? "开启" : "关闭").append('\n');
        sb.append("隐藏桌面图标: ").append(isLauncherIconHidden() ? "开启" : "关闭").append('\n');
        sb.append("推荐作用域: ").append(SCOPE_LIST_DISPLAY);
        return sb.toString();
    }

    private String buildDeviceSummary() {
        String market = getSystemProperty("ro.product.marketname");
        String device = !market.isEmpty() ? market : Build.MODEL;
        String osInc = getSystemProperty("ro.mi.os.version.incremental");
        String osName = getSystemProperty("ro.mi.os.version.name");
        String hyperOs = !osInc.isEmpty() ? osInc : osName;

        StringBuilder sb = new StringBuilder();
        if (device != null && !device.isEmpty()) {
            sb.append(device).append(" · ");
        }
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(')');
        if (!hyperOs.isEmpty()) {
            sb.append(" · HyperOS ").append(hyperOs.startsWith("OS") ? hyperOs.substring(2) : hyperOs);
        }
        return sb.toString();
    }

    private static String getSystemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            Object val = get.invoke(null, key, "");
            return val != null ? val.toString().trim() : "";
        } catch (Throwable ignored) {
            return "";
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
        return label != null && label.length() > 0 ? label.toString() : "端侧AI通知总结 For HyperOS";
    }

    private int resolveCurrentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return (int) info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
            return Config.MODULE_VERSION_CODE;
        }
    }

    private String resolveFullVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            String name = info.versionName != null ? info.versionName : Config.MODULE_VERSION_NAME;
            return "v" + name + " (" + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "v" + Config.MODULE_VERSION_NAME;
        }
    }

    private void makeStaticInfoRow(HyperSettingsUi.NavRowParts parts) {
        parts.chevron.setVisibility(View.GONE);
        parts.row.setClickable(false);
        parts.row.setFocusable(false);
        parts.row.setBackground(null);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) parts.value.getLayoutParams();
        if (lp != null) {
            lp.setMarginEnd(0);
            parts.value.setLayoutParams(lp);
        }
    }

    private int dp(float value) {
        return HyperSettingsUi.dp(this, value);
    }

    private float dpF(float value) {
        return HyperSettingsUi.dpF(this, value);
    }

    private static final class SquircleIconView extends View {
        private final Drawable mDrawable;
        private final float mRadiusPx;
        private final Path mClipPath = new Path();
        private final RectF mBoundsF = new RectF();

        SquircleIconView(Context context, Drawable drawable, float radiusPx) {
            super(context);
            mDrawable = drawable != null ? drawable.mutate() : null;
            mRadiusPx = radiusPx;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mBoundsF.set(0f, 0f, w, h);
            HyperSettingsUi.buildSquirclePath(mBoundsF, mRadiusPx, mClipPath);
            if (mDrawable != null) {
                mDrawable.setBounds(0, 0, w, h);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mDrawable == null || mClipPath.isEmpty()) return;
            int save = canvas.save();
            canvas.clipPath(mClipPath);
            mDrawable.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
