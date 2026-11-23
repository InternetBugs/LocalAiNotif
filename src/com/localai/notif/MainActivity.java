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

public final class MainActivity extends Activity implements ModelDownloadManager.Listener {
    private static final Uri PROVIDER = Uri.parse("content://" + Config.AUTHORITY);
    private static final String STATE_PRIORITY_ENABLED = "state_priority_enabled";
    private static final String STATE_GROUP_SUMMARY = "state_group_summary";
    private static final String STATE_REDUCE_ENABLED = "state_reduce_enabled";
    private static final String STATE_REDUCE_LOCK_ONLY = "state_reduce_lock_only";
    private static final String STATE_ALWAYS_LOAD_MODEL = "state_always_load_model";

    private HyperSettingsUi.Palette mPalette;
    private HyperSettingsUi.SummaryPreviewView mSummaryPreview;
    private HyperSettingsUi.PriorityPreviewView mPriorityPreview;
    private HyperSettingsUi.ReducePreviewView mReducePreview;
    private HyperSettingsUi.SwitchRowParts mSummaryRow;
    private HyperSettingsUi.NavRowParts mSummaryAdvancedRow;
    private HyperSettingsUi.SwitchRowParts mPriorityRow;
    private HyperSettingsUi.SwitchRowParts mReduceRow;
    private HyperSettingsUi.SwitchRowParts mReduceLockOnlyRow;
    private HyperSettingsUi.SwitchRowParts mReduceIconsRow;
    private HyperSettingsUi.SwitchRowParts mReduceSoundsRow;
    private HyperSettingsUi.NavRowParts mReduceTileRow;
    private HyperSettingsUi.NavRowParts mModelRow;
    private HyperSettingsUi.SwitchRowParts mAlwaysLoadRow;
    private ModelDownloadManager mDownloadManager;

    private boolean mSummaryBusy;
    private boolean mPriorityBusy;
    private boolean mReduceBusy;
    private boolean mAlwaysLoadBusy;
    private boolean mEngineReceiverRegistered;
    private boolean mReduceReceiverRegistered;
    private Bundle mLastConfig;

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

        setContentView(buildContentView(
                initialPriority, initialSummary, initialReduce, initialReduceLockOnly,
                savedInstanceState != null && savedInstanceState.getBoolean(Config.KEY_REDUCE_FILTER_ICONS),
                savedInstanceState != null && savedInstanceState.getBoolean(Config.KEY_REDUCE_NORMAL_SOUNDS),
                initialAlwaysLoad));
        refreshConfigFromProvider();
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
        if (mPriorityRow != null) {
            outState.putBoolean(STATE_PRIORITY_ENABLED, mPriorityRow.toggle.isChecked());
        }
        if (mSummaryRow != null) {
            outState.putBoolean(STATE_GROUP_SUMMARY, mSummaryRow.toggle.isChecked());
        }
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
            boolean initialReduce,
            boolean initialReduceLockOnly,
            boolean initialReduceFilterIcons,
            boolean initialReduceSounds,
            boolean initialAlwaysLoad) {
        boolean fromSystemSettings = getIntent() != null
                && getIntent().getBooleanExtra(Config.EXTRA_FROM_SYSTEM_SETTINGS, false);
        HyperSettingsUi.PageShell shell = HyperSettingsUi.buildPageShell(
                this, mPalette, resolveAppTitle(), fromSystemSettings);
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
                "自动概括多条折叠通知的要点。",
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
            saveReduceOption(Config.KEY_REDUCE_LOCK_SCREEN_ONLY, target, mReduceLockOnlyRow);
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
            saveReduceOption(Config.KEY_REDUCE_FILTER_ICONS, target, mReduceIconsRow);
        });
        reduceCard.addView(mReduceIconsRow.row);
        reduceCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mReduceSoundsRow = HyperSettingsUi.createSwitchRow(this, mPalette,
                "保留通知音和系统音效", "普通通知也可按原设置响铃，触摸等系统音效跟随手机设置。",
                initialReduceSounds, false, false);
        mReduceSoundsRow.row.setOnClickListener(view -> {
            boolean target = !mReduceSoundsRow.toggle.isChecked();
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            mReduceSoundsRow.toggle.setChecked(target, true);
            saveReduceOption(Config.KEY_REDUCE_NORMAL_SOUNDS, target, mReduceSoundsRow);
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
                "关闭时按需加载，空闲 60 秒无推理后自动释放内存。",
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
        HyperSettingsUi.NavRowParts aboutRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "关于",
                null,
                resolveVersionDisplay(),
                false,
                true,
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
        return shell.root;
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
        boolean modelReady = mLastConfig != null
                ? mLastConfig.getBoolean(Config.KEY_MODEL_READY, false)
                : ModelCatalog.isModelDirValid(new File(Config.DEFAULT_MODEL_PATH));
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

    private void applySummaryDependentAlpha(boolean summaryEnabled) {
        if (mSummaryAdvancedRow != null) {
            float alpha = summaryEnabled ? 1.0f : 0.62f;
            mSummaryAdvancedRow.title.setAlpha(alpha);
            mSummaryAdvancedRow.summary.setAlpha(alpha);
        }
    }

    private void refreshConfigFromProvider() {
        new Thread(() -> {
            Bundle config = null;
            try {
                config = getContentResolver().call(PROVIDER, "get_config", null, null);
            } catch (RuntimeException ignored) {
            }
            Bundle result = config;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result != null) {
                    applyConfigBundle(result, false);
                } else {
                    renderModelEntry();
                }
            });
        }, "LocalAiNotif-load-config").start();
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

    private void saveReduceOption(String key, boolean target, HyperSettingsUi.SwitchRowParts row) {
        row.row.setClickable(false);
        new Thread(() -> {
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
                    row.toggle.setChecked(finalResult.getBoolean(key, target), true);
                } else {
                    row.toggle.setChecked(!target, true);
                    Toast.makeText(this, "无法保存减少干扰设置", Toast.LENGTH_SHORT).show();
                }
            });
        }, "LocalAiNotif-save-reduce-option").start();
    }

    private void applyConfigBundle(Bundle config, boolean animate) {
        mLastConfig = config;
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
        renderSummaryAdvancedSubtitle(config);
        renderModelEntry();
    }

    private void renderAlwaysLoadSubtitle(boolean alwaysLoad) {
        if (mAlwaysLoadRow == null) return;
        boolean modelReady = mLastConfig != null
                ? mLastConfig.getBoolean(Config.KEY_MODEL_READY, false)
                : ModelCatalog.isModelDirValid(new File(Config.DEFAULT_MODEL_PATH));
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
                subtitle = "按需加载中，空闲 60 秒无推理后自动释放内存。";
            } else if (engineLoaded) {
                subtitle = "当前已按需加载，空闲 60 秒无推理后自动释放内存。";
            } else {
                subtitle = "关闭时按需加载，空闲 60 秒无推理后自动释放内存。";
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
        boolean ready = ModelCatalog.isModelDirValid(dir);
        ModelCatalog.ModelSpec matched = ModelCatalog.findByPath(this, modelPath);

        mModelRow.statusDot.setVisibility(View.VISIBLE);
        mModelRow.value.setVisibility(View.VISIBLE);
        mModelRow.summary.setVisibility(View.VISIBLE);

        if (ready) {
            long bytes = ModelCatalog.directorySizeBytes(dir);
            String displayName = matched != null ? matched.displayName : dir.getName();
            mModelRow.statusDot.setColor(mPalette.statusReady);
            mModelRow.value.setTextColor(mPalette.textSecondary);
            mModelRow.value.setText(displayName);
            mModelRow.summary.setText("已就绪，占用 " + ModelCatalog.formatBytes(bytes));
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
        return "v1.0.0";
    }

    private int dp(float value) {
        return HyperSettingsUi.dp(this, value);
    }
}
