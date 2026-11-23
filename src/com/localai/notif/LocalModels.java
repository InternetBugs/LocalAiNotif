package com.localai.notif;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LocalModels {
    static final String SOURCE = ".import-source";
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};

    private LocalModels() {}

    static List<File> list(Context context) {
        File root = ModelCatalog.modelsRoot(context);
        List<File> models = new ArrayList<>();
        for (File parent : List.of(root, new File(root, ".local"))) {
            File[] children = parent.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isHidden() || !child.isDirectory() || Files.isSymbolicLink(child.toPath())) continue;
                if (ModelCatalog.isModelDirValid(child)) models.add(child);
            }
        }
        models.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(File::getPath));
        return models;
    }

    static synchronized String importTree(Context context, Uri tree) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String id = DocumentsContract.getTreeDocumentId(tree);
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        File parent = new File(ModelCatalog.modelsRoot(context), ".local");
        Files.createDirectories(parent.toPath());
        File target;
        try (Cursor cursor = resolver.query(document, COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("无法读取所选目录");
            target = childFile(parent, cursor.getString(1));
        }
        if (target.exists()) throw new IOException("已导入同名模型");
        Uri rootConfig = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        boolean hasConfig = false;
        try (Cursor cursor = resolver.query(rootConfig, COLUMNS, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if ("config.json".equals(cursor.getString(1))) hasConfig = true;
                if (ModelCatalog.MARKER_INCOMPLETE.equals(cursor.getString(1)))
                    throw new IOException("模型目录尚未下载完整");
            }
        }
        if (!hasConfig) throw new IOException("请选择包含 config.json 的模型目录");
        File staging = Files.createTempDirectory(context.getCacheDir().toPath(), "model-import-").toFile();
        try {
            copyChildren(resolver, tree, id, staging, new HashSet<>());
            File configFile = new File(staging, "config.json");
            if (!ModelCatalog.isModelDirValid(staging) || configFile.length() > 2 * 1024 * 1024)
                throw new IOException("缺少有效的 MNN 模型图或配置");
            JSONObject config = new JSONObject(Files.readString(configFile.toPath()));
            for (String[] asset : List.of(new String[]{"llm_model", "llm.mnn"},
                    new String[]{"llm_weight", "llm.mnn.weight"},
                    new String[]{"tokenizer_file", "tokenizer.txt"},
                    new String[]{"llm_config", "llm_config.json"})) {
                File file = new File(staging, config.optString(asset[0], asset[1])).getCanonicalFile();
                if (!file.toPath().startsWith(staging.getCanonicalFile().toPath())
                        || !file.isFile() || file.length() == 0)
                    throw new IOException("模型缺少有效文件：" + asset[0]);
            }
            boolean alreadyGranted = resolver.getPersistedUriPermissions().stream()
                    .anyMatch(grant -> grant.isReadPermission() && tree.equals(grant.getUri()));
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                Files.writeString(new File(staging, SOURCE).toPath(), tree.toString());
                Files.move(staging.toPath(), target.toPath());
            } catch (IOException | RuntimeException error) {
                if (!alreadyGranted) releaseGrant(context, tree);
                throw error;
            }
            return target.getName();
        } catch (JSONException error) {
            throw new IOException("无法解析模型的 config.json", error);
        } finally {
            ModelCatalog.deleteRecursively(staging);
        }
    }

    static Uri source(File dir) {
        try {
            File marker = new File(dir, SOURCE);
            if (!marker.isFile() || marker.length() > 32768) return null;
            Uri source = Uri.parse(Files.readString(marker.toPath()).trim());
            return "file".equals(source.getScheme()) || DocumentsContract.isTreeUri(source) ? source : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    static String sourceLabel(Uri source) {
        if ("file".equals(source.getScheme())) return source.getPath();
        String id = DocumentsContract.getTreeDocumentId(source);
        return "com.android.externalstorage.documents".equals(source.getAuthority())
                ? id.replaceFirst("^primary:", "内部存储/") : id;
    }

    static synchronized boolean deleteCopy(Context context, File dir) throws IOException {
        File root = ModelCatalog.modelsRoot(context).getCanonicalFile();
        File parent = dir.getCanonicalFile().getParentFile();
        if (Files.isSymbolicLink(dir.toPath())
                || !(root.equals(parent) || new File(root, ".local").equals(parent))
                || dir.getName().startsWith(".")) throw new IOException("无效的模块模型目录");
        Uri source = source(dir);
        File removed = new File(context.getCacheDir(), "removed-model-" + java.util.UUID.randomUUID());
        Files.move(dir.toPath(), removed.toPath());
        if (source != null && "content".equals(source.getScheme())
                && list(context).stream().noneMatch(other -> source.equals(source(other)))) {
            releaseGrant(context, source);
        }
        return ModelCatalog.deleteRecursively(removed);
    }

    private static void releaseGrant(Context context, Uri tree) {
        try {
            context.getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private static File childFile(File parent, String name) throws IOException {
        if (name == null || name.isBlank() || name.startsWith(".") || name.contains("/")
                || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("模型目录包含无效文件名");
        return new File(parent, name);
    }

    private static void copyChildren(ContentResolver resolver, Uri tree, String id, File destination,
            Set<String> visited) throws IOException {
        if (!visited.add(id)) throw new IOException("模型目录包含循环引用");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("无法列出模型文件");
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                String name = cursor.getString(1);
                if (name != null && name.startsWith(".")) continue;
                File output = childFile(destination, name);
                if (Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    Files.createDirectory(output.toPath());
                    copyChildren(resolver, tree, childId, output, visited);
                } else {
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, childId);
                    try (InputStream stream = resolver.openInputStream(child)) {
                        if (stream == null) throw new IOException("无法读取 " + name);
                        long copied = Files.copy(stream, output.toPath());
                        if (!cursor.isNull(3) && cursor.getLong(3) >= 0 && copied != cursor.getLong(3))
                            throw new IOException("文件复制不完整：" + name);
                    }
                }
            }
        }
    }
}
