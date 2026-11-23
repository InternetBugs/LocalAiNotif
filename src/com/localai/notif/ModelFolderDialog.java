package com.localai.notif;

import android.app.Activity;
import android.app.Dialog;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ModelFolderDialog {
    private record Entry(Uri uri, String name, boolean folder, long bytes) {}
    private final Activity activity;
    private final HyperSettingsUi.Palette palette;
    private final Uri tree;
    private final Dialog dialog;
    private final LinearLayout content;
    private final TextView path;
    private final ArrayDeque<Entry> parents = new ArrayDeque<>();
    private int generation;

    ModelFolderDialog(Activity activity, HyperSettingsUi.Palette palette, Uri source) {
        this.activity = activity;
        this.palette = palette;
        tree = source;
        dialog = HyperSettingsUi.createBottomSheetDialog(activity, palette);
        LinearLayout sheet = HyperSettingsUi.createDialogCard(activity, palette);
        sheet.setPadding(dp(16), dp(16), dp(16), dp(12));
        path = new TextView(activity);
        path.setTextSize(16);
        path.setTextColor(palette.textPrimary);
        path.setPadding(0, 0, 0, dp(8));
        sheet.addView(path, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1,
                Math.min(dp(320), activity.getResources().getDisplayMetrics().heightPixels / 2)));
        TextView close = HyperSettingsUi.createSheetButton(activity, palette, "关闭",
                HyperSettingsUi.CapsuleStyle.SECONDARY_GRAY);
        close.setOnClickListener(view -> dialog.dismiss());
        sheet.addView(close, new LinearLayout.LayoutParams(-1, dp(46)));
        HyperSettingsUi.attachBottomSheetContent(activity, dialog, sheet);
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK || parents.size() < 2) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) back();
            return true;
        });
        Uri root = "file".equals(source.getScheme()) ? source :
                DocumentsContract.buildDocumentUriUsingTree(source, DocumentsContract.getTreeDocumentId(source));
        parents.push(new Entry(root, LocalModels.sourceLabel(source), true, 0));
    }

    void show() {
        dialog.show();
        load();
    }

    private void back() {
        parents.pop();
        load();
    }

    private void load() {
        Entry current = parents.element();
        int request = ++generation;
        StringBuilder fullPath = new StringBuilder();
        for (var folders = parents.descendingIterator(); folders.hasNext();) {
            if (fullPath.length() > 0 && fullPath.charAt(fullPath.length() - 1) != '/') fullPath.append('/');
            fullPath.append(folders.next().name());
        }
        HyperSettingsUi.setPathText(path, fullPath.toString());
        content.removeAllViews();
        if (parents.size() > 1) addRow("上一级", null, view -> back());
        new Thread(() -> {
            List<Entry> entries = new ArrayList<>();
            String error = null;
            try {
                if ("file".equals(current.uri().getScheme())) {
                    File[] files = new File(current.uri().getPath()).listFiles();
                    if (files == null) throw new IOException();
                    for (File file : files) {
                        if (LocalModels.SOURCE.equals(file.getName())) continue;
                        entries.add(new Entry(Uri.fromFile(file), file.getName(), file.isDirectory(), file.length()));
                    }
                } else {
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                            DocumentsContract.getDocumentId(current.uri()));
                    String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};
                    try (Cursor cursor = activity.getContentResolver().query(children, columns, null, null, null)) {
                        if (cursor == null) throw new IOException();
                        while (cursor.moveToNext()) {
                            entries.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                                    cursor.getString(1), Document.MIME_TYPE_DIR.equals(cursor.getString(2)),
                                    cursor.isNull(3) ? -1 : cursor.getLong(3)));
                        }
                    }
                }
                entries.sort(Comparator.comparing(Entry::folder).reversed()
                        .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
            } catch (RuntimeException | IOException failure) {
                error = "无法读取此文件夹，请检查文件夹位置和访问权限";
            }
            String message = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing() || request != generation) return;
                if (message != null || entries.isEmpty()) {
                    addRow(message != null ? message : "文件夹为空", null, null);
                    return;
                }
                for (Entry entry : entries) {
                    addRow(entry.name(), entry.folder() ? "文件夹" : ModelCatalog.formatBytes(entry.bytes()),
                            entry.folder() ? view -> { parents.push(entry); load(); } : null);
                }
            });
        }, "LocalAiNotif-view-folder").start();
    }

    private void addRow(String label, String value, View.OnClickListener click) {
        HyperSettingsUi.NavRowParts row = HyperSettingsUi.createNavRow(activity, palette, label,
                null, value, false, false, false);
        if (click == null) {
            row.chevron.setVisibility(View.GONE);
            row.row.setClickable(false);
            row.row.setFocusable(false);
        } else {
            row.row.setOnClickListener(click);
        }
        content.addView(row.row);
    }

    private int dp(int value) {
        return HyperSettingsUi.dp(activity, value);
    }
}
