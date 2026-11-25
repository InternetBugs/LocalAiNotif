package io.github.internetbugs.localainotif;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HyperOS secondary page for downloading, inspecting, activating, and deleting
 * on-device MNN-LLM models.
 */
public final class ModelDownloadActivity extends Activity implements ModelDownloadManager.Listener {
    private static final Uri PROVIDER = Uri.parse("content://" + Config.AUTHORITY);
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    private static final int REQ_MODEL_FOLDER = 1002;

    private static final String[] DOWNLOAD_SOURCES = {
            Config.DOWNLOAD_SOURCE_MODELSCOPE,
            Config.DOWNLOAD_SOURCE_HF_MIRROR,
            Config.DOWNLOAD_SOURCE_HUGGINGFACE
    };

    private static final class ModelRowHolder {
        final ModelCatalog.ModelSpec spec;
        final LinearLayout row;
        final TextView summary;
        final TextView meta;
        final TextView actionBtn;
        final LinearLayout progressTray;
        final HyperSettingsUi.HyperProgressBar progressBar;
        final TextView progressText;
        final TextView speedText;
        final TextView cancelLink;

        ModelRowHolder(
                ModelCatalog.ModelSpec spec,
                LinearLayout row,
                TextView summary,
                TextView meta,
                TextView actionBtn,
                LinearLayout progressTray,
                HyperSettingsUi.HyperProgressBar progressBar,
                TextView progressText,
                TextView speedText,
                TextView cancelLink) {
            this.spec = spec;
            this.row = row;
            this.summary = summary;
            this.meta = meta;
            this.actionBtn = actionBtn;
            this.progressTray = progressTray;
            this.progressBar = progressBar;
            this.progressText = progressText;
            this.speedText = speedText;
            this.cancelLink = cancelLink;
        }
    }

    private HyperSettingsUi.Palette mPalette;
    private ModelDownloadManager mManager;

    private HyperSettingsUi.StatusDotView mStatusDot;
    private TextView mStatusTitle;
    private TextView mStatusBadge;
    private TextView mStatusDesc;
    private TextView mStorageText;

    private final Map<String, ModelRowHolder> mRowHolders = new HashMap<>();
    private HyperSettingsUi.NavRowParts mSourceRow;
    private HyperSettingsUi.NavRowParts mImportRow;
    private LinearLayout mLocalCard;
    private View mCatalogTitle;
    private LinearLayout mCatalogCard;
    private List<File> mLocalModels = List.of();
    private Map<File, Long> mModelSizes = Map.of();
    private long mAvailableBytes = -1;
    private int mInventoryGeneration;
    private HyperSettingsUi.SwitchRowParts mWifiOnlyRow;

    private String mActiveModelPath = Config.DEFAULT_MODEL_PATH;
    private boolean mEngineLoaded;
    private boolean mEngineLoading;
    private boolean mAlwaysLoadModel = Config.DEFAULT_ALWAYS_LOAD_MODEL;
    private String mDownloadSource = Config.DEFAULT_DOWNLOAD_SOURCE;
    private boolean mWifiOnly = Config.DEFAULT_WIFI_ONLY_DOWNLOAD;
    private boolean mWifiBusy;
    private boolean mSwitchBusy;
    private boolean mRequestedNotifPerm;
    private boolean mEngineReceiverRegistered;

    private final BroadcastReceiver mEngineStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isFinishing() && !isDestroyed()) {
                refreshConfigAndUi();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean isDark = HyperSettingsUi.applyTheme(this);
        super.onCreate(savedInstanceState);

        mPalette = HyperSettingsUi.Palette.of(isDark);
        HyperSettingsUi.configureEdgeToEdge(this, mPalette);
        mManager = ModelDownloadManager.getInstance(this);

        setContentView(buildContentView());
        refreshConfigAndUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mManager.addListener(this);
        if (!mEngineReceiverRegistered) {
            registerReceiver(
                    mEngineStateReceiver,
                    new IntentFilter(Config.ACTION_ENGINE_STATE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
            mEngineReceiverRegistered = true;
        }
        renderAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConfigAndUi();
    }

    @Override
    protected void onStop() {
        if (mEngineReceiverRegistered) {
            unregisterReceiver(mEngineStateReceiver);
            mEngineReceiverRegistered = false;
        }
        mManager.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDownloadStateChanged() {
        if (isFinishing() || isDestroyed()) return;
        String notice = mManager.consumeLastCompletionNotice();
        if (notice != null) {
            Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
            refreshConfigAndUi();
        } else {
            renderAll();
        }
    }

    private View buildContentView() {
        HyperSettingsUi.PageShell shell = HyperSettingsUi.buildPageShell(
                this, mPalette, "本地模型", true);
        LinearLayout content = shell.content;

        // 1. Current on-device status card
        LinearLayout statusCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);
        statusCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);

        mStatusDot = new HyperSettingsUi.StatusDotView(this, mPalette.statusWarn);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMarginEnd(dp(8));
        statusHeader.addView(mStatusDot, dotLp);

        mStatusTitle = new TextView(this);
        mStatusTitle.setText("检测模型中…");
        mStatusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        mStatusTitle.setTypeface(HyperSettingsUi.typeface(500));
        mStatusTitle.setTextColor(mPalette.textPrimary);
        mStatusTitle.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginEnd(dp(8));
        statusHeader.addView(mStatusTitle, titleLp);

        mStatusBadge = HyperSettingsUi.createCapsuleButton(
                this, mPalette, "未下载", HyperSettingsUi.CapsuleStyle.SOFT_AMBER_TAG);
        mStatusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mStatusBadge.setMinWidth(0);
        mStatusBadge.setMinimumHeight(dp(24));
        mStatusBadge.setPadding(dp(10), 0, dp(10), 0);
        statusHeader.addView(mStatusBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));

        statusCard.addView(statusHeader);

        mStatusDesc = new TextView(this);
        mStatusDesc.setText("正在检查应用私有目录中的 MNN 模型…");
        mStatusDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mStatusDesc.setTypeface(HyperSettingsUi.typeface(400));
        mStatusDesc.setTextColor(mPalette.textSecondary);
        mStatusDesc.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(8);
        statusCard.addView(mStatusDesc, descLp);

        mStorageText = new TextView(this);
        mStorageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        mStorageText.setTypeface(HyperSettingsUi.typeface(400));
        mStorageText.setTextColor(mPalette.textTertiary);
        mStorageText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams storageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        storageLp.topMargin = dp(6);
        statusCard.addView(mStorageText, storageLp);

        content.addView(statusCard);

        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "设备上的模型", 18));
        mLocalCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);
        content.addView(mLocalCard);

        // 2. Preset models grouped card
        mCatalogTitle = HyperSettingsUi.createSmallTitle(this, mPalette, "可下载模型", 18);
        content.addView(mCatalogTitle);
        mCatalogCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);
        content.addView(mCatalogCard);
        for (ModelCatalog.ModelSpec spec : ModelCatalog.PRESETS) {
            mRowHolders.put(spec.id, buildModelRow(spec, false, false));
        }
        buildLocalRows();

        // 3. Download preferences grouped card
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "下载设置", 18));
        LinearLayout prefCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        mSourceRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "下载源",
                ModelCatalog.sourceDisplaySubtitle(mDownloadSource),
                ModelCatalog.sourceDisplayName(mDownloadSource),
                false,
                true,
                false);
        mSourceRow.row.setOnClickListener(v -> showSourceDialog());
        prefCard.addView(mSourceRow.row);
        prefCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mWifiOnlyRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "仅在 Wi-Fi 下下载",
                "使用移动网络开始下载前弹出确认，避免消耗大额流量。",
                mWifiOnly,
                false,
                true);
        mWifiOnlyRow.row.setOnClickListener(v -> {
            if (mWifiBusy) return;
            boolean target = !mWifiOnlyRow.toggle.isChecked();
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            mWifiOnlyRow.toggle.setChecked(target, true);
            mWifiBusy = true;
            mWifiOnlyRow.row.setClickable(false);
            Bundle delta = new Bundle();
            delta.putBoolean(Config.KEY_WIFI_ONLY_DOWNLOAD, target);
            saveDownloadPrefs(delta, () -> {
                mWifiOnly = target;
                mWifiBusy = false;
                mWifiOnlyRow.row.setClickable(true);
            }, () -> {
                mWifiOnlyRow.toggle.setChecked(!target, true);
                mWifiBusy = false;
                mWifiOnlyRow.row.setClickable(true);
            });
        });
        prefCard.addView(mWifiOnlyRow.row);
        content.addView(prefCard);

        // 4. Footer guidance
        content.addView(HyperSettingsUi.createFooter(
                this,
                mPalette,
                "模型仅保存在本机。下载可在后台继续，切换模型后会自动重载推理引擎。"));

        return shell.root;
    }

    private void buildLocalRows() {
        mLocalCard.removeAllViews();
        mCatalogCard.removeAllViews();
        for (File dir : mLocalModels) {
            ModelCatalog.ModelSpec preset = ModelCatalog.findByPath(this, dir.getPath());
            if (preset != null) {
                addPresetRow(mLocalCard, preset, false);
            } else {
                boolean selected = ModelCatalog.canonicalOrAbsolute(dir)
                        .equals(ModelCatalog.canonicalOrAbsolute(new File(mActiveModelPath)));
                HyperSettingsUi.NavRowParts row = HyperSettingsUi.createNavRow(this, mPalette,
                        dir.getName(), null, selected ? "使用中" : "已导入", false,
                        mLocalCard.getChildCount() == 0, false);
                row.row.setOnClickListener(view -> showModelDetailSheet(null, dir));
                mLocalCard.addView(row.row);
            }
            mLocalCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));
        }
        List<ModelCatalog.ModelSpec> available = new java.util.ArrayList<>();
        for (ModelCatalog.ModelSpec spec : ModelCatalog.PRESETS) {
            if (!mLocalModels.contains(ModelCatalog.modelDir(this, spec))) available.add(spec);
        }
        for (int index = 0; index < available.size(); index++) {
            if (index > 0) mCatalogCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));
            addPresetRow(mCatalogCard, available.get(index), index + 1 == available.size());
        }
        mCatalogTitle.setVisibility(available.isEmpty() ? View.GONE : View.VISIBLE);
        mCatalogCard.setVisibility(available.isEmpty() ? View.GONE : View.VISIBLE);
        mImportRow = HyperSettingsUi.createNavRow(this, mPalette, "导入本地模型",
                "选择包含 config.json 的 MNN 模型文件夹", null, false, mLocalModels.isEmpty(), true);
        mImportRow.row.setOnClickListener(view -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            try {
                startActivityForResult(pick, REQ_MODEL_FOLDER);
            } catch (android.content.ActivityNotFoundException error) {
                Toast.makeText(this, "无法打开系统文件选择器", Toast.LENGTH_SHORT).show();
            }
        });
        mLocalCard.addView(mImportRow.row);
        renderImportStatus();
    }

    private void addPresetRow(LinearLayout card, ModelCatalog.ModelSpec spec, boolean last) {
        View row = mRowHolders.get(spec.id).row;
        row.setBackground(HyperSettingsUi.createRowBackground(this, mPalette, 18f,
                card.getChildCount() == 0, last, true));
        card.addView(row);
    }

    private void renderImportStatus() {
        boolean importing = mManager.isImporting();
        mImportRow.row.setEnabled(!importing);
        mImportRow.summary.setText(importing ? "正在复制模型文件…" : "选择包含 config.json 的 MNN 模型文件夹");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MODEL_FOLDER && resultCode == RESULT_OK && data != null
                && data.getData() != null) mManager.importLocalModel(data.getData());
    }

    private ModelRowHolder buildModelRow(ModelCatalog.ModelSpec spec, boolean roundTop, boolean roundBottom) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(HyperSettingsUi.createRowBackground(
                this, mPalette, 18f, roundTop, roundBottom, true));
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> showModelDetailSheet(spec));

        // Top horizontal block: left info + right capsule action
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nameLine = new LinearLayout(this);
        nameLine.setOrientation(LinearLayout.HORIZONTAL);
        nameLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameView = new TextView(this);
        nameView.setText(spec.displayName);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        nameView.setTypeface(HyperSettingsUi.typeface(500));
        nameView.setTextColor(mPalette.textPrimary);
        nameView.setIncludeFontPadding(false);
        nameLine.addView(nameView);

        if (spec.badgeText != null && !spec.badgeText.isEmpty()) {
            TextView tag = HyperSettingsUi.createInlineBadge(this, mPalette, spec.badgeText, true);
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tagLp.setMarginStart(dp(7));
            nameLine.addView(tag, tagLp);
        }
        infoCol.addView(nameLine);

        TextView summaryView = new TextView(this);
        summaryView.setText(spec.shortSummary);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        summaryView.setTypeface(HyperSettingsUi.typeface(400));
        summaryView.setTextColor(mPalette.textSecondary);
        summaryView.setLineSpacing(dp(2.5f), 1.0f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(5);
        infoCol.addView(summaryView, summaryLp);

        TextView metaView = new TextView(this);
        metaView.setText(spec.specFootnote);
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        metaView.setTypeface(HyperSettingsUi.typeface(400));
        metaView.setTextColor(mPalette.textTertiary);
        metaView.setLineSpacing(dp(2f), 1.0f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(4);
        infoCol.addView(metaView, metaLp);

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.setMarginEnd(dp(12));
        topRow.addView(infoCol, infoLp);

        TextView actionBtn = HyperSettingsUi.createCapsuleButton(
                this, mPalette, "下载", HyperSettingsUi.CapsuleStyle.SOFT_BLUE);
        actionBtn.setClickable(true);
        actionBtn.setFocusable(true);
        actionBtn.setOnClickListener(v -> onPrimaryActionClick(spec, v));
        topRow.addView(actionBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));

        row.addView(topRow);

        // Expandable download progress tray (shown only during active/paused/failed states)
        LinearLayout tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.VERTICAL);
        tray.setVisibility(View.GONE);
        LinearLayout.LayoutParams trayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trayLp.topMargin = dp(12);

        HyperSettingsUi.HyperProgressBar progressBar =
                new HyperSettingsUi.HyperProgressBar(this, mPalette);
        tray.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4.5f)));

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsLp.topMargin = dp(7);

        TextView progressText = new TextView(this);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        progressText.setTypeface(HyperSettingsUi.typeface(400));
        progressText.setTextColor(mPalette.textSecondary);
        progressText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams progTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        progTextLp.setMarginEnd(dp(8));
        statsRow.addView(progressText, progTextLp);

        TextView speedText = new TextView(this);
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        speedText.setTypeface(HyperSettingsUi.typeface(400));
        speedText.setTextColor(mPalette.textTertiary);
        speedText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams speedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        speedLp.setMarginEnd(dp(10));
        statsRow.addView(speedText, speedLp);

        TextView cancelLink = new TextView(this);
        cancelLink.setText("取消");
        cancelLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        cancelLink.setTypeface(HyperSettingsUi.typeface(500));
        cancelLink.setTextColor(mPalette.textSecondary);
        cancelLink.setIncludeFontPadding(false);
        cancelLink.setPadding(dp(6), dp(3), dp(2), dp(3));
        cancelLink.setClickable(true);
        cancelLink.setFocusable(true);
        cancelLink.setOnClickListener(v -> confirmCancelDownload(spec));
        statsRow.addView(cancelLink);

        tray.addView(statsRow, statsLp);
        row.addView(tray, trayLp);

        return new ModelRowHolder(
                spec, row, summaryView, metaView, actionBtn,
                tray, progressBar, progressText, speedText, cancelLink);
    }

    private void onPrimaryActionClick(ModelCatalog.ModelSpec spec, View anchor) {
        anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        ModelDownloadManager.Snapshot snap = mManager.getSnapshot(spec.id);
        boolean installed = ModelCatalog.isInstalled(this, spec);
        boolean active = isSpecActive(spec);

        if (snap.state == ModelDownloadManager.State.FETCHING_META
                || snap.state == ModelDownloadManager.State.DOWNLOADING) {
            mManager.pauseDownload(spec.id);
            return;
        }
        if (snap.state == ModelDownloadManager.State.VERIFYING) {
            return;
        }
        if (snap.state == ModelDownloadManager.State.PAUSED
                || snap.state == ModelDownloadManager.State.FAILED) {
            initiateDownloadWithNetworkCheck(spec);
            return;
        }
        if (installed) {
            if (active) {
                showModelDetailSheet(spec);
            } else {
                activateModel(spec);
            }
            return;
        }
        initiateDownloadWithNetworkCheck(spec);
    }

    private void initiateDownloadWithNetworkCheck(ModelCatalog.ModelSpec spec) {
        if (!mManager.hasActiveNetwork()) {
            Toast.makeText(this, "当前无网络连接，请检查网络后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mWifiOnly && !mManager.isOnUnmeteredWifi()) {
            String approx = ModelCatalog.formatBytes(spec.estimatedSizeBytes);
            HyperSettingsUi.showConfirmDialog(
                    this,
                    mPalette,
                    "当前使用移动网络",
                    "已开启「仅在 Wi-Fi 下下载」。继续下载 " + spec.displayName
                            + " 预计消耗约 " + approx + " 移动流量。",
                    "继续下载",
                    false,
                    () -> startDownloadNow(spec));
            return;
        }
        startDownloadNow(spec);
    }

    private void startDownloadNow(ModelCatalog.ModelSpec spec) {
        maybeRequestNotificationPermission();
        String err = mManager.startDownload(spec.id, mDownloadSource);
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
        }
    }

    private void maybeRequestNotificationPermission() {
        if (mRequestedNotifPerm) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            mRequestedNotifPerm = true;
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
    }

    private void confirmCancelDownload(ModelCatalog.ModelSpec spec) {
        HyperSettingsUi.showConfirmDialog(
                this,
                mPalette,
                "取消下载 " + spec.displayName + "？",
                "取消后会删除已下载的临时缓存文件，后续需重新下载。",
                "取消下载",
                true,
                () -> mManager.cancelDownload(spec.id));
    }

    private void showModelDetailSheet(ModelCatalog.ModelSpec spec) {
        showModelDetailSheet(spec, ModelCatalog.modelDir(this, spec));
    }

    private void showModelDetailSheet(ModelCatalog.ModelSpec spec, File dir) {
        ModelDownloadManager.State state = spec == null ? ModelDownloadManager.State.IDLE
                : mManager.getSnapshot(spec.id).state;
        String name = spec == null ? dir.getName() : spec.displayName;
        Uri source = LocalModels.source(dir);
        boolean installed = mModelSizes.containsKey(dir);
        boolean active = installed && ModelCatalog.canonicalOrAbsolute(dir)
                .equals(ModelCatalog.canonicalOrAbsolute(new File(mActiveModelPath)));

        Dialog dialog = HyperSettingsUi.createBottomSheetDialog(this, mPalette);
        LinearLayout sheet = HyperSettingsUi.createDialogCard(this, mPalette);
        sheet.setPadding(dp(20), dp(22), dp(20), dp(20));

        // Title + badge
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        title.setTypeface(HyperSettingsUi.typeface(500));
        title.setTextColor(mPalette.textPrimary);
        title.setIncludeFontPadding(false);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (active) {
            TextView tag = HyperSettingsUi.createInlineBadge(this, mPalette, "使用中", false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            titleRow.addView(tag, lp);
        } else if (spec != null && spec.badgeText != null) {
            TextView tag = HyperSettingsUi.createInlineBadge(this, mPalette, spec.badgeText, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            titleRow.addView(tag, lp);
        }
        sheet.addView(titleRow);

        // Full model explanation
        TextView detail = new TextView(this);
        detail.setText(spec == null ? "本机 MNN 模型" : spec.detailDescription);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        detail.setTypeface(HyperSettingsUi.typeface(400));
        detail.setTextColor(mPalette.textSecondary);
        detail.setLineSpacing(dp(3.5f), 1.0f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(10);
        sheet.addView(detail, detailLp);

        // Spec info block
        LinearLayout specBox = new LinearLayout(this);
        specBox.setOrientation(LinearLayout.VERTICAL);
        specBox.setBackground(new HyperSettingsUi.SquircleDrawable(
                mPalette.previewStageBg, dpF(14f), dpF(14f), mPalette.previewStageBorder, dpF(0.8f)));
        specBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams specBoxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        specBoxLp.topMargin = dp(14);

        if (spec != null) addSpecLine(specBox, "量化格式", spec.quantizationLabel, false);
        addSpecLine(specBox, "磁盘占用", installed
                ? ModelCatalog.formatBytes(mModelSizes.get(dir))
                : spec == null ? "文件缺失" : "约 " + ModelCatalog.formatBytes(spec.estimatedSizeBytes), spec != null);
        if (spec != null) {
            addSpecLine(specBox, "推理峰值内存", spec.peakRamLabel, true);
            addSpecLine(specBox, "响应速度", spec.speedLabel, true);
        }
        if (source != null) addPathLine(specBox, "原文件夹", LocalModels.sourceLabel(source));
        addPathLine(specBox, "模块保存位置", "files/" + getFilesDir().toPath().relativize(dir.toPath()));
        sheet.addView(specBox, specBoxLp);
        if (spec != null) {
            TextView note = HyperSettingsUi.createFooter(this, mPalette,
                    "骁龙 8 至尊版实测，取三次中位数。内存为进程峰值 RSS，GPU 和 NPU 专用内存未单独计量。"
                            + "响应用时为生成同一条短通知摘要的耗时，不含模型加载。");
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteLp.topMargin = dp(8);
            sheet.addView(note, noteLp);
        }

        // Contextual actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(16);

        if (state == ModelDownloadManager.State.FETCHING_META
                || state == ModelDownloadManager.State.DOWNLOADING) {
            TextView pauseBtn = HyperSettingsUi.createSheetButton(
                    this, mPalette, "暂停下载", HyperSettingsUi.CapsuleStyle.SECONDARY_GRAY);
            pauseBtn.setOnClickListener(v -> {
                dialog.dismiss();
                mManager.pauseDownload(spec.id);
            });
            addActionButton(actions, pauseBtn, false);

            TextView cancelBtn = HyperSettingsUi.createSheetButton(
                    this, mPalette, "取消并清理缓存", HyperSettingsUi.CapsuleStyle.DANGER_SOFT);
            cancelBtn.setOnClickListener(v -> {
                dialog.dismiss();
                confirmCancelDownload(spec);
            });
            addActionButton(actions, cancelBtn, true);
        } else if (state == ModelDownloadManager.State.PAUSED
                || state == ModelDownloadManager.State.FAILED) {
            TextView resumeBtn = HyperSettingsUi.createSheetButton(
                    this, mPalette, "继续下载", HyperSettingsUi.CapsuleStyle.PRIMARY_BLUE);
            resumeBtn.setOnClickListener(v -> {
                dialog.dismiss();
                initiateDownloadWithNetworkCheck(spec);
            });
            addActionButton(actions, resumeBtn, false);

            TextView cancelBtn = HyperSettingsUi.createSheetButton(
                    this, mPalette, "取消并清理缓存", HyperSettingsUi.CapsuleStyle.DANGER_SOFT);
            cancelBtn.setOnClickListener(v -> {
                dialog.dismiss();
                confirmCancelDownload(spec);
            });
            addActionButton(actions, cancelBtn, true);
        } else if (installed) {
            long sizeBytes = mModelSizes.get(dir);
            if (!active) {
                TextView activateBtn = HyperSettingsUi.createSheetButton(
                        this, mPalette, "设为当前模型", HyperSettingsUi.CapsuleStyle.PRIMARY_BLUE);
                activateBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    activateModel(dir, name);
                });
                addActionButton(actions, activateBtn, false);
            } else {
                TextView reloadBtn = HyperSettingsUi.createSheetButton(
                        this, mPalette, "重载推理引擎", HyperSettingsUi.CapsuleStyle.SOFT_BLUE);
                reloadBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    reloadEngine();
                });
                addActionButton(actions, reloadBtn, false);
            }

            TextView folderBtn = HyperSettingsUi.createSheetButton(this, mPalette,
                    source == null ? "查看模型文件夹" : "查看原文件夹", HyperSettingsUi.CapsuleStyle.SECONDARY_GRAY);
            folderBtn.setOnClickListener(view -> {
                dialog.dismiss();
                new ModelFolderDialog(this, mPalette, source == null ? Uri.fromFile(dir) : source).show();
            });
            addActionButton(actions, folderBtn, true);
            TextView deleteBtn = HyperSettingsUi.createSheetButton(
                    this, mPalette,
                    (source != null || spec == null ? "删除导入的模型" : "删除模型文件")
                            + "（释放 " + ModelCatalog.formatBytes(sizeBytes) + "）",
                    HyperSettingsUi.CapsuleStyle.DANGER_SOFT);
            deleteBtn.setOnClickListener(v -> {
                dialog.dismiss();
                confirmDeleteModel(dir, name, active);
            });
            addActionButton(actions, deleteBtn, true);
        } else if (spec != null) {
            TextView dlBtn = HyperSettingsUi.createSheetButton(
                    this, mPalette, "下载此模型", HyperSettingsUi.CapsuleStyle.PRIMARY_BLUE);
            dlBtn.setOnClickListener(v -> {
                dialog.dismiss();
                initiateDownloadWithNetworkCheck(spec);
            });
            addActionButton(actions, dlBtn, false);
        }

        TextView closeBtn = HyperSettingsUi.createSheetButton(
                this, mPalette, "关闭", HyperSettingsUi.CapsuleStyle.SECONDARY_GRAY);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        addActionButton(actions, closeBtn, true);

        sheet.addView(actions, actionsLp);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(sheet);
        HyperSettingsUi.attachBottomSheetContent(this, dialog, scroll);
        dialog.show();
    }

    private void addPathLine(LinearLayout parent, String label, String path) {
        TextView caption = new TextView(this);
        caption.setPadding(0, dp(8), 0, dp(3));
        caption.setText(label);
        caption.setTextSize(12.5f);
        caption.setTextColor(mPalette.textSecondary);
        parent.addView(caption);
        TextView value = new TextView(this);
        value.setTextSize(13.5f);
        value.setTextColor(mPalette.textPrimary);
        value.setLineSpacing(dp(2), 1f);
        HyperSettingsUi.setPathText(value, path);
        parent.addView(value, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSpecLine(LinearLayout parent, String label, String value, boolean topGap) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topGap) lp.topMargin = dp(7);

        TextView lView = new TextView(this);
        lView.setText(label);
        lView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lView.setTypeface(HyperSettingsUi.typeface(400));
        lView.setTextColor(mPalette.textSecondary);
        lView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        line.addView(lView, lLp);

        TextView vView = new TextView(this);
        vView.setText(value);
        vView.setGravity(Gravity.END);
        vView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        vView.setTypeface(HyperSettingsUi.typeface(500));
        vView.setTextColor(mPalette.textPrimary);
        vView.setIncludeFontPadding(false);
        line.addView(vView);

        parent.addView(line, lp);
    }

    private void addActionButton(LinearLayout container, TextView button, boolean topGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        if (topGap) lp.topMargin = dp(10);
        container.addView(button, lp);
    }

    private void confirmDeleteModel(File dir, String name, boolean wasActive) {
        String note = "仅删除模块保存的副本，手机上的原文件夹和文件会保留。";
        if (wasActive) {
            File fallback = new File(Config.DEFAULT_MODEL_PATH);
            note += !ModelCatalog.canonicalOrAbsolute(dir).equals(ModelCatalog.canonicalOrAbsolute(fallback))
                    && mModelSizes.containsKey(fallback) ? "删除后将切回 Qwen3.5-0.8B。" : "删除后当前模型将卸载。";
        }
        HyperSettingsUi.showConfirmDialog(
                this,
                mPalette,
                "删除 " + name + "？",
                note,
                "删除",
                true,
                () -> deleteModelAsync(dir, name));
    }

    private void deleteModelAsync(File dir, String name) {
        if (mSwitchBusy) return;
        mSwitchBusy = true;
        new Thread(() -> {
            String message;
            try {
                Bundle config = getContentResolver().call(PROVIDER, "get_config", null, null);
                if (config == null) throw new java.io.IOException("无法读取当前模型");
                File current = new File(config.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH));
                boolean active = ModelCatalog.canonicalOrAbsolute(current).equals(ModelCatalog.canonicalOrAbsolute(dir));
                if (active) {
                    try {
                        getContentResolver().call(PROVIDER, Config.METHOD_RESET_ENGINE, null, null);
                    } catch (RuntimeException ignored) {
                    }
                }
                boolean cleared = LocalModels.deleteCopy(this, dir);
                if (active) {
                    Bundle input = new Bundle();
                    input.putString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH);
                    Bundle result = getContentResolver().call(PROVIDER, Config.METHOD_SET_MODEL_PATH, null, input);
                    if (result == null || !"0".equals(result.getString("status")))
                        throw new java.io.IOException("副本已移除，重置引擎失败");
                }
                message = cleared ? "已删除 " + name : "已移除模型，部分缓存待清理";
            } catch (java.io.IOException | RuntimeException error) {
                message = "删除操作未完成：" + error.getLocalizedMessage();
            }
            String resultMessage = message;
            runOnUiThread(() -> {
                mSwitchBusy = false;
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, resultMessage, Toast.LENGTH_SHORT).show();
                refreshConfigAndUi();
            });
        }, "LocalAiNotif-delete-model").start();
    }

    private void activateModel(ModelCatalog.ModelSpec spec) {
        activateModel(ModelCatalog.modelDir(this, spec), spec.displayName);
    }

    private void activateModel(File dir, String displayName) {
        if (mSwitchBusy) return;
        if (!mModelSizes.containsKey(dir)) {
            Toast.makeText(this, "模型文件不完整，请重新下载", Toast.LENGTH_SHORT).show();
            return;
        }
        mSwitchBusy = true;
        new Thread(() -> {
            Bundle res = null;
            try {
                Bundle input = new Bundle();
                input.putString(Config.KEY_MODEL_PATH, dir.getAbsolutePath());
                if (ModelCatalog.isModelDirValid(dir)) {
                    res = getContentResolver().call(PROVIDER, Config.METHOD_SET_MODEL_PATH, null, input);
                }
                if (res != null && !"0".equals(res.getString("status"))) {
                    res = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalRes = res;
            runOnUiThread(() -> {
                mSwitchBusy = false;
                if (isFinishing() || isDestroyed()) return;
                if (finalRes != null) {
                    applyConfig(finalRes, false);
                    Toast.makeText(this, "已切换至 " + displayName,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "切换模型失败", Toast.LENGTH_SHORT).show();
                }
            });
        }, "LocalAiNotif-activate-model").start();
    }

    private void reloadEngine() {
        boolean shouldPreload = mAlwaysLoadModel;
        new Thread(() -> {
            boolean ok = false;
            try {
                Bundle input = new Bundle();
                input.putBoolean(Config.KEY_RESET_PRELOAD, shouldPreload);
                Bundle res = getContentResolver().call(PROVIDER, Config.METHOD_RESET_ENGINE, null, input);
                ok = res != null && "0".equals(res.getString("status"));
            } catch (RuntimeException ignored) {
            }
            boolean finalOk = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                String okMsg = shouldPreload
                        ? "已重置引擎，正在重新加载模型"
                        : "已释放旧引擎，下次推理时自动重新加载";
                Toast.makeText(
                        this,
                        finalOk ? okMsg : "重载引擎失败",
                        Toast.LENGTH_SHORT).show();
                refreshConfigAndUi();
            });
        }, "LocalAiNotif-reset-engine").start();
    }

    private void showSourceDialog() {
        List<HyperSettingsUi.ChoiceItem> items = Arrays.asList(
                new HyperSettingsUi.ChoiceItem("魔搭社区（ModelScope）", "国内高速直连，推荐默认使用"),
                new HyperSettingsUi.ChoiceItem("HF-Mirror", "Hugging Face 国内镜像站"),
                new HyperSettingsUi.ChoiceItem("Hugging Face", "官方源，需稳定国际网络")
        );
        int selected = 0;
        for (int i = 0; i < DOWNLOAD_SOURCES.length; i++) {
            if (DOWNLOAD_SOURCES[i].equals(mDownloadSource)) {
                selected = i;
                break;
            }
        }
        HyperSettingsUi.showChoiceDialog(this, mPalette, "选择下载源", items, selected, idx -> {
            String target = DOWNLOAD_SOURCES[idx];
            if (target.equals(mDownloadSource)) return;
            Bundle delta = new Bundle();
            delta.putString(Config.KEY_DOWNLOAD_SOURCE, target);
            saveDownloadPrefs(delta, () -> {
                mDownloadSource = target;
                renderSourceRow();
            }, null);
        });
    }

    private void refreshConfigAndUi() {
        int generation = ++mInventoryGeneration;
        new Thread(() -> {
            Bundle result = null;
            List<File> localModels = LocalModels.list(this);
            try {
                result = getContentResolver().call(PROVIDER, "get_config", null, null);
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            Map<File, Long> sizes = new HashMap<>();
            for (File model : localModels) sizes.put(model, ModelCatalog.directorySizeBytes(model));
            File active = new File(result == null ? Config.DEFAULT_MODEL_PATH
                    : result.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH));
            if (!sizes.containsKey(active) && ModelCatalog.isModelDirValid(active)) {
                sizes.put(active, ModelCatalog.directorySizeBytes(active));
            }
            long available = ModelCatalog.availableStorageBytes(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != mInventoryGeneration) return;
                mLocalModels = localModels;
                mModelSizes = sizes;
                mAvailableBytes = available;
                if (finalResult != null) {
                    applyConfig(finalResult, false);
                } else {
                    buildLocalRows();
                    renderAll();
                }
            });
        }, "LocalAiNotif-model-load-cfg").start();
    }

    private void saveDownloadPrefs(Bundle delta, Runnable onSuccess, Runnable onFailure) {
        new Thread(() -> {
            Bundle result = null;
            try {
                result = getContentResolver().call(PROVIDER, Config.METHOD_SET_CONFIG, null, delta);
                if (result != null && !"0".equals(result.getString("status"))) {
                    result = null;
                }
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (finalResult != null) {
                    applyConfig(finalResult, true);
                    if (onSuccess != null) onSuccess.run();
                } else {
                    if (onFailure != null) onFailure.run();
                    Toast.makeText(this, "无法保存下载设置", Toast.LENGTH_SHORT).show();
                }
            });
        }, "LocalAiNotif-model-save-cfg").start();
    }

    private void applyConfig(Bundle config, boolean animate) {
        mActiveModelPath = config.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH);
        mEngineLoaded = config.getBoolean(Config.KEY_ENGINE_LOADED, false);
        mEngineLoading = config.getBoolean(Config.KEY_ENGINE_LOADING, false);
        mAlwaysLoadModel = config.getBoolean(Config.KEY_ALWAYS_LOAD_MODEL, Config.DEFAULT_ALWAYS_LOAD_MODEL);
        mDownloadSource = config.getString(Config.KEY_DOWNLOAD_SOURCE, Config.DEFAULT_DOWNLOAD_SOURCE);
        mWifiOnly = config.getBoolean(Config.KEY_WIFI_ONLY_DOWNLOAD, Config.DEFAULT_WIFI_ONLY_DOWNLOAD);
        if (mWifiOnlyRow != null && !mWifiBusy) {
            mWifiOnlyRow.toggle.setChecked(mWifiOnly, animate);
        }
        buildLocalRows();
        renderAll();
    }

    private void renderAll() {
        renderImportStatus();
        renderStatusCard();
        renderSourceRow();
        for (ModelCatalog.ModelSpec spec : ModelCatalog.PRESETS) {
            ModelRowHolder holder = mRowHolders.get(spec.id);
            if (holder != null) {
                renderModelRow(holder);
            }
        }
    }

    private void renderStatusCard() {
        File activeDir = new File(mActiveModelPath);
        boolean ready = mModelSizes.containsKey(activeDir);
        ModelCatalog.ModelSpec matched = ModelCatalog.findByPath(this, mActiveModelPath);

        if (ready) {
            long sizeBytes = mModelSizes.get(activeDir);
            String title = matched != null ? matched.displayName : activeDir.getName();
            mStatusDot.setColor(mPalette.statusReady);
            mStatusTitle.setText(title);
            mStatusBadge.setText("使用中");
            HyperSettingsUi.applyCapsuleStyle(
                    this, mPalette, mStatusBadge, HyperSettingsUi.CapsuleStyle.SOFT_GREEN_TAG, 12f);
            String engineNote;
            if (mEngineLoading) {
                engineNote = "正在加载推理引擎至内存";
            } else if (mEngineLoaded) {
                engineNote = mAlwaysLoadModel
                        ? "推理引擎已常驻内存就绪"
                        : "推理引擎已按需加载（空闲 3 分钟自动释放）";
            } else {
                engineNote = mAlwaysLoadModel
                        ? "已开启始终加载，等待引擎常驻就绪"
                        : "文件已就绪，收到通知时按需加载并在空闲 3 分钟后释放";
            }
            String prefix = matched != null ? "" : "检测到自定义本地模型，";
            mStatusDesc.setText(prefix + engineNote + "，占用 " + ModelCatalog.formatBytes(sizeBytes) + "。");
        } else {
            mStatusDot.setColor(mPalette.statusWarn);
            mStatusTitle.setText("未部署端侧模型");
            mStatusBadge.setText("未下载");
            HyperSettingsUi.applyCapsuleStyle(
                    this, mPalette, mStatusBadge, HyperSettingsUi.CapsuleStyle.SOFT_AMBER_TAG, 12f);
            mStatusDesc.setText(mLocalModels.isEmpty() ? "导入本地模型或下载预设模型，即可启用通知分类与 AI 摘要。"
                    : "请选择设备上的模型，用于通知分类与 AI 摘要。");
        }

        long avail = mAvailableBytes;
        if (avail >= 0L) {
            mStorageText.setText("设备可用存储 " + ModelCatalog.formatBytes(avail));
            mStorageText.setVisibility(View.VISIBLE);
        } else {
            mStorageText.setVisibility(View.GONE);
        }
    }

    private void renderSourceRow() {
        if (mSourceRow == null) return;
        mSourceRow.value.setText(ModelCatalog.sourceDisplayName(mDownloadSource));
        mSourceRow.value.setVisibility(View.VISIBLE);
        mSourceRow.summary.setText(ModelCatalog.sourceDisplaySubtitle(mDownloadSource));
        mSourceRow.summary.setVisibility(View.VISIBLE);
    }

    private void renderModelRow(ModelRowHolder h) {
        ModelCatalog.ModelSpec spec = h.spec;
        ModelDownloadManager.Snapshot snap = mManager.getSnapshot(spec.id);
        File dir = ModelCatalog.modelDir(this, spec);
        boolean installed = mModelSizes.containsKey(dir);
        boolean active = isSpecActive(spec);

        if (snap.state == ModelDownloadManager.State.FETCHING_META) {
            h.actionBtn.setText("暂停");
            HyperSettingsUi.applyCapsuleStyle(
                    this, mPalette, h.actionBtn, HyperSettingsUi.CapsuleStyle.SECONDARY_GRAY, 16f);
            h.actionBtn.setEnabled(true);
            h.meta.setText(spec.specFootnote);
            h.progressTray.setVisibility(View.VISIBLE);
            h.progressBar.setState(0, true, false);
            h.progressText.setTextColor(mPalette.textSecondary);
            h.progressText.setText("正在从 " + ModelCatalog.sourceDisplayName(snap.source) + " 获取文件清单…");
            h.speedText.setText("");
            h.cancelLink.setVisibility(View.VISIBLE);
            return;
        }

        if (snap.state == ModelDownloadManager.State.DOWNLOADING) {
            h.actionBtn.setText("暂停");
            HyperSettingsUi.applyCapsuleStyle(
                    this, mPalette, h.actionBtn, HyperSettingsUi.CapsuleStyle.SECONDARY_GRAY, 16f);
            h.actionBtn.setEnabled(true);
            h.meta.setText(spec.specFootnote);
            h.progressTray.setVisibility(View.VISIBLE);
            int pct = snap.progressPercent();
            h.progressBar.setState(pct, snap.totalBytes <= 0L, false);
            h.progressText.setTextColor(mPalette.textSecondary);
            String filePart = snap.totalFiles > 0
                    ? "（" + snap.completedFiles + "/" + snap.totalFiles + " 文件）"
                    : "";
            h.progressText.setText(ModelCatalog.formatBytes(snap.downloadedBytes)
                    + " / " + ModelCatalog.formatBytes(snap.totalBytes) + filePart);
            h.speedText.setText(ModelCatalog.formatSpeed(snap.speedBytesPerSec));
            h.cancelLink.setVisibility(View.VISIBLE);
            return;
        }

        if (snap.state == ModelDownloadManager.State.VERIFYING) {
            h.actionBtn.setText("校验中");
            HyperSettingsUi.applyCapsuleStyle(
                    this, mPalette, h.actionBtn, HyperSettingsUi.CapsuleStyle.SECONDARY_GRAY, 16f);
            h.actionBtn.setEnabled(false);
            h.meta.setText(spec.specFootnote);
            h.progressTray.setVisibility(View.VISIBLE);
            h.progressBar.setState(100, true, false);
            h.progressText.setTextColor(mPalette.accent);
            h.progressText.setText("正在校验模型文件完整性…");
            h.speedText.setText("");
            h.cancelLink.setVisibility(View.GONE);
            return;
        }

        if (snap.state == ModelDownloadManager.State.PAUSED) {
            h.actionBtn.setText("继续");
            HyperSettingsUi.applyCapsuleStyle(
                    this, mPalette, h.actionBtn, HyperSettingsUi.CapsuleStyle.PRIMARY_BLUE, 16f);
            h.actionBtn.setEnabled(true);
            h.meta.setText(spec.specFootnote);
            h.progressTray.setVisibility(View.VISIBLE);
            h.progressBar.setState(snap.progressPercent(), false, true);
            h.progressText.setTextColor(mPalette.textSecondary);
            h.progressText.setText("已暂停，" + ModelCatalog.formatBytes(snap.downloadedBytes)
                    + " / " + ModelCatalog.formatBytes(snap.totalBytes));
            h.speedText.setText("");
            h.cancelLink.setVisibility(View.VISIBLE);
            return;
        }

        if (snap.state == ModelDownloadManager.State.FAILED) {
            h.actionBtn.setText("重试");
            HyperSettingsUi.applyCapsuleStyle(
                    this, mPalette, h.actionBtn, HyperSettingsUi.CapsuleStyle.PRIMARY_BLUE, 16f);
            h.actionBtn.setEnabled(true);
            h.meta.setText(spec.specFootnote);
            h.progressTray.setVisibility(View.VISIBLE);
            h.progressBar.setState(snap.progressPercent(), false, true);
            h.progressText.setTextColor(mPalette.danger);
            h.progressText.setText(snap.errorMessage != null ? snap.errorMessage : "下载失败，可点击重试");
            h.speedText.setText("");
            h.cancelLink.setVisibility(View.VISIBLE);
            return;
        }

        // IDLE state: installed vs not downloaded
        h.progressTray.setVisibility(View.GONE);
        h.actionBtn.setEnabled(true);
        if (installed) {
            long sizeBytes = mModelSizes.get(dir);
            h.meta.setText("已下载 " + ModelCatalog.formatBytes(sizeBytes) + "，点击查看说明或管理");
            if (active) {
                h.actionBtn.setText("使用中");
                HyperSettingsUi.applyCapsuleStyle(
                        this, mPalette, h.actionBtn, HyperSettingsUi.CapsuleStyle.SOFT_GREEN_TAG, 16f);
            } else {
                h.actionBtn.setText("启用");
                HyperSettingsUi.applyCapsuleStyle(
                        this, mPalette, h.actionBtn, HyperSettingsUi.CapsuleStyle.SOFT_BLUE, 16f);
            }
        } else {
            h.meta.setText(spec.specFootnote);
            h.actionBtn.setText("下载");
            HyperSettingsUi.applyCapsuleStyle(
                    this, mPalette, h.actionBtn, HyperSettingsUi.CapsuleStyle.SOFT_BLUE, 16f);
        }
    }

    private boolean isSpecActive(ModelCatalog.ModelSpec spec) {
        File target = ModelCatalog.modelDir(this, spec);
        File active = new File(mActiveModelPath);
        if (!mModelSizes.containsKey(target)) return false;
        return ModelCatalog.canonicalOrAbsolute(target).equals(ModelCatalog.canonicalOrAbsolute(active));
    }

    private int dp(float value) {
        return HyperSettingsUi.dp(this, value);
    }

    private float dpF(float value) {
        return HyperSettingsUi.dpF(this, value);
    }
}
