package com.localai.notif;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;

/**
 * Preset MNN-LLM model catalog, source URL helpers, and local model directory inspection.
 */
final class ModelCatalog {
    static final String MARKER_INCOMPLETE = ".incomplete";

    static final class ModelSpec {
        final String id;
        final String displayName;
        final String badgeText;
        final String shortSummary;
        final String specFootnote;
        final String detailDescription;
        final String quantizationLabel;
        final String peakRamLabel;
        final String speedLabel;
        final String dirName;
        final String modelScopeRepo;
        final String huggingFaceRepo;
        final long estimatedSizeBytes;

        ModelSpec(
                String id,
                String displayName,
                String badgeText,
                String shortSummary,
                String specFootnote,
                String detailDescription,
                String quantizationLabel,
                String peakRamLabel,
                String speedLabel,
                String dirName,
                String modelScopeRepo,
                String huggingFaceRepo,
                long estimatedSizeBytes) {
            this.id = id;
            this.displayName = displayName;
            this.badgeText = badgeText;
            this.shortSummary = shortSummary;
            this.specFootnote = specFootnote;
            this.detailDescription = detailDescription;
            this.quantizationLabel = quantizationLabel;
            this.peakRamLabel = peakRamLabel;
            this.speedLabel = speedLabel;
            this.dirName = dirName;
            this.modelScopeRepo = modelScopeRepo;
            this.huggingFaceRepo = huggingFaceRepo;
            this.estimatedSizeBytes = estimatedSizeBytes;
        }
    }

    static final List<ModelSpec> PRESETS = List.of(
            new ModelSpec(
                    "qwen3.5-0.8b",
                    "Qwen3.5-0.8B",
                    "推荐",
                    "用于中文通知分类和摘要。兼容的高通骁龙处理器可使用 Hexagon NPU。",
                    "约 550 MB，MNN 4 位量化",
                    "当前支持骁龙 8 至尊版（SM8750）的 Hexagon NPU，可与 CPU 协同处理通知。NPU 不可用时使用 CPU 推理。",
                    "MNN INT4",
                    "CPU 924 MiB\nNPU 混合 299 MiB",
                    "CPU 0.36 秒\nNPU 混合 0.99 秒",
                    "qwen3.5-0.8b-mnn",
                    "MNN/Qwen3.5-0.8B-MNN",
                    "taobao-mnn/Qwen3.5-0.8B-MNN",
                    547_600_000L
            ),
            new ModelSpec(
                    "qwen3.5-2b",
                    "Qwen3.5-2B",
                    null,
                    "用于中文通知分类和摘要。兼容的高通骁龙处理器可使用 Hexagon NPU。",
                    "约 1.27 GB，MNN 4 位量化",
                    "在骁龙 8 至尊版（SM8750）上，Hexagon NPU 处理投影和门控归一化，CPU 执行其余运算。NPU 不可用时自动使用 CPU。",
                    "MNN INT4",
                    "CPU 1,725 MiB\nNPU 混合 1,343 MiB",
                    "CPU 0.73 秒\nNPU 混合 4.99 秒",
                    "qwen3.5-2b-mnn",
                    "MNN/Qwen3.5-2B-MNN",
                    "taobao-mnn/Qwen3.5-2B-MNN",
                    1_271_728_585L
            )
    );

    private ModelCatalog() {}

    static ModelSpec findById(String id) {
        if (id == null) return null;
        for (ModelSpec spec : PRESETS) {
            if (spec.id.equals(id)) return spec;
        }
        return null;
    }

    static ModelSpec findByPath(Context context, String path) {
        if (path == null || path.trim().isEmpty()) return null;
        File target = new File(path.trim());
        String targetCanonical = canonicalOrAbsolute(target);
        for (ModelSpec spec : PRESETS) {
            File presetDir = modelDir(context, spec);
            if (canonicalOrAbsolute(presetDir).equals(targetCanonical)) {
                return spec;
            }
        }
        return null;
    }

    static File modelsRoot(Context context) {
        return new File(context.getFilesDir(), "models");
    }

    static File modelDir(Context context, ModelSpec spec) {
        return new File(modelsRoot(context), spec.dirName);
    }

    static File stagingDir(Context context, ModelSpec spec) {
        return new File(modelsRoot(context), "." + spec.dirName + ".downloading");
    }

    static boolean isInstalled(Context context, ModelSpec spec) {
        return isModelDirValid(modelDir(context, spec));
    }

    static boolean hasPartialStaging(Context context, ModelSpec spec) {
        File dir = stagingDir(context, spec);
        if (!dir.isDirectory()) return false;
        File[] children = dir.listFiles();
        return children != null && children.length > 0;
    }

    /**
     * Checks whether a model directory contains an intact MNN-LLM layout
     * (config.json + at least one non-empty .mnn graph, without an incomplete marker).
     */
    static boolean isModelDirValid(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        if (new File(dir, MARKER_INCOMPLETE).exists()) return false;
        File config = new File(dir, "config.json");
        if (!config.isFile() || config.length() <= 16L) return false;
        return hasNonEmptyFileWithSuffix(dir, ".mnn", 1024L);
    }

    private static boolean hasNonEmptyFileWithSuffix(File dir, String suffix, long minBytes) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(suffix) && f.length() >= minBytes) {
                return true;
            }
            if (f.isDirectory() && hasNonEmptyFileWithSuffix(f, suffix, minBytes)) {
                return true;
            }
        }
        return false;
    }

    static long directorySizeBytes(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        long sum = 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            sum += directorySizeBytes(child);
        }
        return sum;
    }

    record ActiveSize(String path, long bytes) {
        static ActiveSize read(android.os.Bundle config) {
            if (config == null || !config.getBoolean(Config.KEY_MODEL_READY)) return null;
            String path = config.getString(Config.KEY_MODEL_PATH, Config.DEFAULT_MODEL_PATH);
            return new ActiveSize(path, directorySizeBytes(new File(path)));
        }
    }

    static boolean deleteRecursively(File file) {
        if (file != null && java.nio.file.Files.isSymbolicLink(file.toPath())) return file.delete();
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    static long availableStorageBytes(Context context) {
        try {
            File base = context.getFilesDir();
            if (!base.exists()) base.mkdirs();
            StatFs stat = new StatFs(base.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Throwable t) {
            return -1L;
        }
    }

    static boolean shouldDownloadFile(String relPath) {
        if (relPath == null || relPath.isEmpty()) return false;
        if (relPath.startsWith("/") || relPath.contains("\\") || relPath.contains("..")) return false;
        String lower = relPath.toLowerCase(Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        String fileName = slash >= 0 ? lower.substring(slash + 1) : lower;
        if (fileName.isEmpty() || fileName.startsWith(".")) return false;
        if (fileName.equals("readme.md") || fileName.equals("license") || fileName.equals("license.txt")
                || fileName.equals("notice") || fileName.equals("configuration.json")) {
            return false;
        }
        if (fileName.endsWith(".md") || fileName.endsWith(".png") || fileName.endsWith(".jpg")
                || fileName.endsWith(".jpeg") || fileName.endsWith(".gif")
                || fileName.endsWith(".webp") || fileName.endsWith(".svg")) {
            return false;
        }
        return true;
    }

    static String sourceDisplayName(String source) {
        if (Config.DOWNLOAD_SOURCE_HF_MIRROR.equals(source)) {
            return "HF-Mirror";
        }
        if (Config.DOWNLOAD_SOURCE_HUGGINGFACE.equals(source)) {
            return "Hugging Face";
        }
        return "魔搭社区";
    }

    static String sourceDisplaySubtitle(String source) {
        if (Config.DOWNLOAD_SOURCE_HF_MIRROR.equals(source)) {
            return "Hugging Face 国内镜像节点";
        }
        if (Config.DOWNLOAD_SOURCE_HUGGINGFACE.equals(source)) {
            return "Hugging Face 官方直连（需良好国际网络）";
        }
        return "ModelScope 国内高速直连（推荐）";
    }

    static String modelScopeTreeUrl(ModelSpec spec) {
        return "https://www.modelscope.cn/api/v1/models/" + spec.modelScopeRepo
                + "/repo/files?Revision=master&Recursive=true";
    }

    static String modelScopeFileUrl(ModelSpec spec, String relPath) {
        return "https://www.modelscope.cn/api/v1/models/" + spec.modelScopeRepo
                + "/repo?FilePath=" + urlEncodeQuery(relPath) + "&Revision=master";
    }

    static String huggingFaceTreeUrl(String source, ModelSpec spec, String subDir) {
        String host = Config.DOWNLOAD_SOURCE_HF_MIRROR.equals(source)
                ? "hf-mirror.com"
                : "huggingface.co";
        String base = "https://" + host + "/api/models/" + spec.huggingFaceRepo + "/tree/main";
        if (subDir == null || subDir.isEmpty()) {
            return base;
        }
        return base + "/" + encodePathSegments(subDir);
    }

    static String huggingFaceFileUrl(String source, ModelSpec spec, String relPath) {
        String host = Config.DOWNLOAD_SOURCE_HF_MIRROR.equals(source)
                ? "hf-mirror.com"
                : "huggingface.co";
        return "https://" + host + "/" + spec.huggingFaceRepo
                + "/resolve/main/" + encodePathSegments(relPath);
    }

    static String formatBytes(long bytes) {
        if (bytes < 0L) return "未知";
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return mb >= 100.0
                    ? String.format(Locale.US, "%.0f MB", mb)
                    : String.format(Locale.US, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0L) return "";
        if (bytesPerSec < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    static String canonicalOrAbsolute(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }

    private static String urlEncodeQuery(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String encodePathSegments(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(urlEncodeQuery(parts[i]));
        }
        return sb.toString();
    }
}
