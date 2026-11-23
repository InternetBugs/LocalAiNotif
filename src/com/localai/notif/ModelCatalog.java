package com.localai.notif;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
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

    static final List<ModelSpec> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new ModelSpec(
                    "qwen3-1.7b",
                    "Qwen3-1.7B",
                    "推荐",
                    "速度与准确度均衡，适合日常通知分类与多条组摘要。",
                    "约 1.1 GB，推理峰值内存约 1.6 GB，日常综合表现最稳",
                    "默认推荐的端侧模型（MNN INT4 量化）。在中文理解、重要通知判定与折叠组摘要的自然度之间取得良好平衡，单次推理耗时与内存占用可控，适合小米 15 等设备长期日常开启。",
                    "MNN INT4",
                    "约 1.6 GB",
                    "均衡（单次约 3–10 秒）",
                    "qwen3-1.7b-mnn",
                    "MNN/Qwen3-1.7B-MNN",
                    "taobao-mnn/Qwen3-1.7B-MNN",
                    1_135_000_000L
            ),
            new ModelSpec(
                    "qwen3-0.6b",
                    "Qwen3-0.6B",
                    null,
                    "体积小、启动快、内存占用低，适合偏重续航与响应速度。",
                    "约 420 MB，推理峰值内存约 0.7 GB，冷启动与出字最快",
                    "极致轻量档位（MNN INT4 量化）。下载体积约 420 MB，加载迅速、几乎不影响大型游戏或多任务后台驻留；面对复杂长对话群聊时，摘要细节与分类精细度略弱于 1.7B 与 4B。",
                    "MNN INT4",
                    "约 0.7 GB",
                    "最快（低内存轻量）",
                    "qwen3-0.6b-mnn",
                    "MNN/Qwen3-0.6B-MNN",
                    "taobao-mnn/Qwen3-0.6B-MNN",
                    442_000_000L
            ),
            new ModelSpec(
                    "qwen3-4b",
                    "Qwen3-4B",
                    null,
                    "语义理解与摘要提炼更准确，适合 12GB 及以上大内存设备。",
                    "约 2.3 GB，推理峰值内存约 3.8 GB，分类与概括质量最高",
                    "高质量进阶档位（MNN INT4 量化）。对长文本通知、多条群聊上下文的要点归纳以及重要通知边界的判定更稳定，但下载体积与推理内存开销较大，单次推理耗时也相对更长。",
                    "MNN INT4",
                    "约 3.8 GB",
                    "较慢（高准确度优先）",
                    "qwen3-4b-mnn",
                    "MNN/Qwen3-4B-MNN",
                    "taobao-mnn/Qwen3-4B-MNN",
                    2_470_000_000L
            )
    ));

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
