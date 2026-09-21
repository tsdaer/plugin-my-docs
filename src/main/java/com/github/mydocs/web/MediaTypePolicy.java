package com.github.mydocs.web;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

/**
 * <p>媒体代理的内容类型闸门。</p>
 *
 * <p>上游是对象存储时，Content-Type 常常不可靠：R2 / S3 里上传时没带类型的对象会返回
 * {@code application/octet-stream}，明明是个 webm 视频，直接按「不是 video/* 就拒」处理
 * 会把正常媒体挡在门外。所以判定分两步：白名单类型直接放行，明确危险的类型直接拒绝，
 * 其余（含 {@code application/octet-stream}、缺类型）再看 URL 扩展名是否是我们认识的媒体格式。</p>
 *
 * <p>「明确的类型 + 扩展名」两条至少要中一条，加上响应侧的 {@code X-Content-Type-Options: nosniff}
 * 与必要的 {@code Content-Disposition: attachment}，即使上游把类型标错也不会被浏览器当成脚本执行。</p>
 */
final class MediaTypePolicy {

    /** 允许直接回给浏览器的类型前缀。 */
    private static final Set<String> ALLOWED_PREFIXES = Set.of("video/", "audio/", "image/");

    /**
     * 明确拒绝的类型：SVG 虽然是 image/*，但直接导航到它会在同源下执行脚本；
     * text/html 与 XML 同理。
     */
    private static final Set<String> BLOCKED_TYPES = Set.of(
        "image/svg+xml", "image/svg", "text/html", "application/xhtml+xml",
        "application/xml", "text/xml", "application/javascript", "text/javascript"
    );

    /** 扩展名到类型的兜底映射：上游没给类型或给成二进制流时用它。 */
    private static final Map<String, String> EXTENSION_TYPES = Map.ofEntries(
        Map.entry("mp4", "video/mp4"),
        Map.entry("m4v", "video/mp4"),
        Map.entry("mov", "video/quicktime"),
        Map.entry("webm", "video/webm"),
        Map.entry("ogv", "video/ogg"),
        Map.entry("ogg", "audio/ogg"),
        Map.entry("m3u8", "application/vnd.apple.mpegurl"),
        Map.entry("mp3", "audio/mpeg"),
        Map.entry("m4a", "audio/mp4"),
        Map.entry("wav", "audio/wav"),
        Map.entry("flac", "audio/flac"),
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("avif", "image/avif"),
        Map.entry("bmp", "image/bmp"),
        Map.entry("ico", "image/x-icon")
    );

    private MediaTypePolicy() {
    }

    /**
     * 决定上游响应能不能通过代理。
     *
     * @param contentType 上游给的 Content-Type，可能为空或不可信
     * @param sourceUrl 原始媒体地址，扩展名从这里取
     * @return 放行则返回要回给浏览器的类型；拒绝返回 null
     */
    static String resolve(String contentType, String sourceUrl) {
        String normalizedType = normalizeType(contentType);
        if (normalizedType != null) {
            return normalizedType;
        }

        String extensionType = EXTENSION_TYPES.get(extensionOf(sourceUrl));
        if (extensionType == null) {
            return null;
        }
        // 上游把 HTML / SVG 之类塞进 .png 时不能靠扩展名洗白。
        String upstream = normalizeUnsafeCheck(contentType);
        if (upstream != null && BLOCKED_TYPES.contains(upstream)) {
            return null;
        }
        return extensionType;
    }

    /** 白名单类型返回归一化后的值；不是白名单返回 null（注意与「拒绝」区分）。 */
    private static String normalizeType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        try {
            var mediaType = MediaType.parseMediaType(contentType);
            String type = mediaType.getType().toLowerCase(Locale.ROOT) + "/"
                + mediaType.getSubtype().toLowerCase(Locale.ROOT);
            if (BLOCKED_TYPES.contains(type)) {
                return null;
            }
            String prefix = mediaType.getType().toLowerCase(Locale.ROOT) + "/";
            return ALLOWED_PREFIXES.stream().anyMatch(prefix::startsWith) ? type : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 只看上游类型是否命中黑名单，用于扩展名兜底前的把关。 */
    private static String normalizeUnsafeCheck(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        try {
            var mediaType = MediaType.parseMediaType(contentType);
            return mediaType.getType().toLowerCase(Locale.ROOT) + "/"
                + mediaType.getSubtype().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 扩展名兜底放行时，浏览器是否必须按附件下载而不能内联展示。 */
    static boolean needsAttachmentDisposition(String sourceUrl) {
        String extension = extensionOf(sourceUrl);
        // HLS 清单与视频/音频内联播放没有风险；扩展名兜底命中图片时保守一点。
        return EXTENSION_TYPES.containsKey(extension) && extensionType(extension).startsWith("image/");
    }

    private static String extensionType(String extension) {
        return EXTENSION_TYPES.getOrDefault(extension, "");
    }

    /** 取 URL 路径部分的扩展名（不含点号，已转小写）；取不到返回空串。 */
    static String extensionOf(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String path = url;
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            path = path.substring(slash + 1);
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
