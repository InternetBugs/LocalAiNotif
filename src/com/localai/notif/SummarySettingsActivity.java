package com.localai.notif;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

/**
 * HyperOS secondary settings page for AI summary trigger timing, length threshold,
 * and app coverage.
 */
public final class SummarySettingsActivity extends Activity {
    private static final Uri PROVIDER = Uri.parse("content://" + Config.AUTHORITY);

    private static final int[] WAIT_OPTIONS = {0, 5, 15, 30, 60};
    private static final int[] THRESHOLD_OPTIONS = {1, 2, 3, 5};
    private static final int[] MIN_LENGTH_OPTIONS = {0, 10, 15, 30, 50};

    private HyperSettingsUi.Palette mPalette;
    private HyperSettingsUi.SwitchRowParts mScreenOnRow;
    private HyperSettingsUi.NavRowParts mWaitRow;
    private HyperSettingsUi.NavRowParts mThresholdRow;
    private HyperSettingsUi.NavRowParts mMinLengthRow;

    private boolean mScreenOn = Config.DEFAULT_SUMMARIZE_SCREEN_ON;
    private int mWaitSeconds = Config.DEFAULT_WAIT_SECONDS;
    private int mThreshold = Config.DEFAULT_NOTIFICATION_THRESHOLD;
    private int mMinLength = Config.DEFAULT_MIN_LENGTH;

    private boolean mScreenOnBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean isDark = HyperSettingsUi.applyTheme(this);
        super.onCreate(savedInstanceState);

        mPalette = HyperSettingsUi.Palette.of(isDark);
        HyperSettingsUi.configureEdgeToEdge(this, mPalette);

        setContentView(buildContentView());
        loadConfig();
    }

    private View buildContentView() {
        HyperSettingsUi.PageShell shell = HyperSettingsUi.buildPageShell(
                this, mPalette, "摘要设置", true);
        LinearLayout content = shell.content;

        // Group 1: 触发时机
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "触发时机", 4));
        LinearLayout timingCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        mScreenOnRow = HyperSettingsUi.createSwitchRow(
                this,
                mPalette,
                "亮屏时生成摘要",
                "关闭后仅在息屏时生成摘要，亮屏期间保持原文。",
                mScreenOn,
                true,
                false);
        mScreenOnRow.row.setOnClickListener(v -> {
            if (mScreenOnBusy) return;
            boolean target = !mScreenOnRow.toggle.isChecked();
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            mScreenOnRow.toggle.setChecked(target, true);
            mScreenOnBusy = true;
            mScreenOnRow.row.setClickable(false);
            Bundle delta = new Bundle();
            delta.putBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, target);
            saveConfig(delta, () -> {
                mScreenOnBusy = false;
                mScreenOnRow.row.setClickable(true);
            }, () -> {
                mScreenOnRow.toggle.setChecked(!target, true);
                mScreenOnBusy = false;
                mScreenOnRow.row.setClickable(true);
            });
        });
        timingCard.addView(mScreenOnRow.row);
        timingCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mWaitRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "合并等待时间",
                "收到通知后延迟汇总，减少短时间内重复推理。",
                formatWait(mWaitSeconds),
                false,
                false,
                false);
        mWaitRow.row.setOnClickListener(v -> showWaitDialog());
        timingCard.addView(mWaitRow.row);
        timingCard.addView(HyperSettingsUi.createInsetDivider(this, mPalette));

        mThresholdRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "最小触发数量",
                "待处理通知达到设定数量后再启动调度。",
                formatThreshold(mThreshold),
                false,
                false,
                true);
        mThresholdRow.row.setOnClickListener(v -> showThresholdDialog());
        timingCard.addView(mThresholdRow.row);
        content.addView(timingCard);

        // Group 2: 内容门槛
        content.addView(HyperSettingsUi.createSmallTitle(this, mPalette, "内容门槛", 18));
        LinearLayout scopeCard = HyperSettingsUi.createGroupCard(this, mPalette, 0);

        mMinLengthRow = HyperSettingsUi.createNavRow(
                this,
                mPalette,
                "最小字数门槛",
                "普通折叠通知总正文超过此字数才生成摘要，重要通知不受限制。",
                formatMinLength(mMinLength),
                false,
                true,
                true);
        mMinLengthRow.row.setOnClickListener(v -> showMinLengthDialog());
        scopeCard.addView(mMinLengthRow.row);
        content.addView(scopeCard);

        content.addView(HyperSettingsUi.createFooter(
                this,
                mPalette,
                "修改后会立即同步至系统通知服务，无需重启设备。"));

        return shell.root;
    }

    private void showWaitDialog() {
        List<HyperSettingsUi.ChoiceItem> items = Arrays.asList(
                new HyperSettingsUi.ChoiceItem("立即触发（0 秒）", "收到通知后立刻调度，响应最快"),
                new HyperSettingsUi.ChoiceItem("5 秒", "合并连续到达的多条短消息"),
                new HyperSettingsUi.ChoiceItem("15 秒", "适合高频群聊通知"),
                new HyperSettingsUi.ChoiceItem("30 秒", "明显降低短时间内推理频次"),
                new HyperSettingsUi.ChoiceItem("60 秒", "更省电，汇总周期更长")
        );
        int selected = indexOf(WAIT_OPTIONS, mWaitSeconds);
        HyperSettingsUi.showChoiceDialog(this, mPalette, "合并等待时间", items, selected, idx -> {
            int value = WAIT_OPTIONS[idx];
            if (value == mWaitSeconds) return;
            Bundle delta = new Bundle();
            delta.putInt(Config.KEY_WAIT_SECONDS, value);
            saveConfig(delta, null, null);
        });
    }

    private void showThresholdDialog() {
        List<HyperSettingsUi.ChoiceItem> items = Arrays.asList(
                new HyperSettingsUi.ChoiceItem("1 条（默认）", "满足条件立即调度"),
                new HyperSettingsUi.ChoiceItem("2 条", "至少累计 2 条待处理通知"),
                new HyperSettingsUi.ChoiceItem("3 条", "至少累计 3 条待处理通知"),
                new HyperSettingsUi.ChoiceItem("5 条", "仅在消息较多时统一处理")
        );
        int selected = indexOf(THRESHOLD_OPTIONS, mThreshold);
        HyperSettingsUi.showChoiceDialog(this, mPalette, "最小触发数量", items, selected, idx -> {
            int value = THRESHOLD_OPTIONS[idx];
            if (value == mThreshold) return;
            Bundle delta = new Bundle();
            delta.putInt(Config.KEY_NOTIFICATION_THRESHOLD, value);
            saveConfig(delta, null, null);
        });
    }

    private void showMinLengthDialog() {
        List<HyperSettingsUi.ChoiceItem> items = Arrays.asList(
                new HyperSettingsUi.ChoiceItem("不限制（0 字）", "所有符合条件的折叠组均生成摘要"),
                new HyperSettingsUi.ChoiceItem("10 字", "跳过极短提醒"),
                new HyperSettingsUi.ChoiceItem("15 字（默认）", "兼顾简短消息与长通知可读性"),
                new HyperSettingsUi.ChoiceItem("30 字", "仅对较长内容生成摘要"),
                new HyperSettingsUi.ChoiceItem("50 字", "仅对明显偏长的通知组概括")
        );
        int selected = indexOf(MIN_LENGTH_OPTIONS, mMinLength);
        HyperSettingsUi.showChoiceDialog(this, mPalette, "最小字数门槛", items, selected, idx -> {
            int value = MIN_LENGTH_OPTIONS[idx];
            if (value == mMinLength) return;
            Bundle delta = new Bundle();
            delta.putInt(Config.KEY_MIN_LENGTH, value);
            saveConfig(delta, null, null);
        });
    }

    private void loadConfig() {
        new Thread(() -> {
            Bundle result = null;
            try {
                result = getContentResolver().call(PROVIDER, "get_config", null, null);
            } catch (RuntimeException ignored) {
            }
            Bundle finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || finalResult == null) return;
                applyConfig(finalResult, false);
            });
        }, "LocalAiNotif-summary-load").start();
    }

    private void saveConfig(Bundle delta, Runnable onSuccess, Runnable onFailure) {
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
                    Toast.makeText(this, "无法保存摘要设置", Toast.LENGTH_SHORT).show();
                }
            });
        }, "LocalAiNotif-summary-save").start();
    }

    private void applyConfig(Bundle config, boolean animate) {
        mScreenOn = config.getBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, Config.DEFAULT_SUMMARIZE_SCREEN_ON);
        mWaitSeconds = config.getInt(Config.KEY_WAIT_SECONDS, Config.DEFAULT_WAIT_SECONDS);
        mThreshold = config.getInt(Config.KEY_NOTIFICATION_THRESHOLD, Config.DEFAULT_NOTIFICATION_THRESHOLD);
        mMinLength = config.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH);

        if (mScreenOnRow != null) mScreenOnRow.toggle.setChecked(mScreenOn, animate);
        if (mWaitRow != null) mWaitRow.value.setText(formatWait(mWaitSeconds));
        if (mThresholdRow != null) mThresholdRow.value.setText(formatThreshold(mThreshold));
        if (mMinLengthRow != null) mMinLengthRow.value.setText(formatMinLength(mMinLength));
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return -1;
    }

    private static String formatWait(int seconds) {
        return seconds <= 0 ? "立即触发" : seconds + " 秒";
    }

    private static String formatThreshold(int threshold) {
        return threshold + " 条";
    }

    private static String formatMinLength(int minLength) {
        return minLength <= 0 ? "不限制" : minLength + " 字";
    }
}
