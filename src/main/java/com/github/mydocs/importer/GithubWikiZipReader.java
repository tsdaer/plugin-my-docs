package com.github.mydocs.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * <p>读取用户上传的 GitHub Wiki 压缩包（将 wiki 仓库 clone 后打包），提取其中全部
 * Markdown 文件。</p>
 * <p>仅读取 {@code .md} / {@code .markdown} 条目，忽略目录、二进制与其他扩展名；
 * 条目名取文件基本名（压缩包可能带有外层目录、{@code __MACOSX} 等杂项）。
 * 带 ZipSlip 路径穿越防护与解压容量上限，避免恶意压缩包攻击。</p>
 *
 * @author tsdaer
 * @since 1.3.0
 */
public final class GithubWikiZipReader {

    private static final int MAX_ENTRY_COUNT = 2000;
    private static final int MAX_ENTRY_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;

    private GithubWikiZipReader() {
    }

    /**
     * 解析 zip 字节流。
     *
     * @param bytes zip 文件内容
     * @return 读取结果：以文件基本名（不含扩展名）为 key 的 Markdown 内容表与跳过的非
     *         Markdown 文件数
     * @throws IOException zip 损坏、条目数 / 单条目 / 总量超限时抛出
     */
    public static Result read(byte[] bytes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        int skippedOtherFiles = 0;
        int totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes),
            StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (files.size() + skippedOtherFiles >= MAX_ENTRY_COUNT) {
                    throw new IOException("压缩包内文件数量超过上限 " + MAX_ENTRY_COUNT);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!isMarkdownName(name) || isJunkPath(name)) {
                    skippedOtherFiles++;
                    continue;
                }
                String baseName = baseName(name);
                if (files.containsKey(baseName)) {
                    // 重名页面以先出现者为准，后者计入跳过。
                    skippedOtherFiles++;
                    continue;
                }
                byte[] content = zip.readAllBytes();
                if (content.length > MAX_ENTRY_BYTES) {
                    throw new IOException("压缩包内单文件超过上限 "
                        + (MAX_ENTRY_BYTES / 1024 / 1024) + "MB：" + name);
                }
                totalBytes += content.length;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw new IOException("压缩包解压总量超过上限 "
                        + (MAX_TOTAL_BYTES / 1024 / 1024) + "MB");
                }
                files.put(baseName, new String(content, StandardCharsets.UTF_8));
            }
        }
        return new Result(files, skippedOtherFiles);
    }

    private static boolean isMarkdownName(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private static boolean isJunkPath(String name) {
        if (name.contains("..") || name.startsWith("/") || name.contains(":")) {
            // ZipSlip：包含上跳段或绝对路径 / 盘符的条目一律拒绝。
            return true;
        }
        String lower = name.toLowerCase();
        return lower.startsWith("__macosx/") || lower.contains("/__macosx/")
            || lower.startsWith(".git/") || lower.contains("/.git/");
    }

    private static String baseName(String name) {
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * zip 解析结果。
     *
     * @param files             以文件基本名（不含扩展名）为 key 的 Markdown 内容
     * @param skippedOtherFiles 跳过的非 Markdown / 重名 / 杂项文件数
     */
    public record Result(Map<String, String> files, int skippedOtherFiles) {
    }
}
