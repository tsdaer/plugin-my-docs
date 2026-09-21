package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 媒体代理内容类型闸门。
 *
 * 这层判定最早只放行 {@code video/*} 等前缀，结果对象存储里没带 Content-Type 的对象
 * （R2 / S3 会回 {@code application/octet-stream}）明明能播，却被代理挡成 415。
 * 现在改成「白名单类型直接放行、明确危险的拒绝、其余看 URL 扩展名」。
 */
class MediaTypePolicyTest {

    @Test
    void keepsAllowlistedTypesAsIs() {
        assertThat(MediaTypePolicy.resolve("video/mp4", "https://m.example.com/a.mp4"))
            .isEqualTo("video/mp4");
        assertThat(MediaTypePolicy.resolve("video/webm; charset=binary", "https://m.example.com/a.webm"))
            .isEqualTo("video/webm");
        assertThat(MediaTypePolicy.resolve("audio/mpeg", "https://m.example.com/a.mp3"))
            .isEqualTo("audio/mpeg");
        assertThat(MediaTypePolicy.resolve("image/png", "https://m.example.com/a.png"))
            .isEqualTo("image/png");
    }

    @Test
    void fallsBackToExtensionWhenUpstreamSaysOctetStream() {
        // 这就是线上 415 的场景：对象存储没存 Content-Type，回 application/octet-stream。
        assertThat(MediaTypePolicy.resolve("application/octet-stream",
            "https://bucket.abc.r2.cloudflarestorage.com/SF_VULKAN_SM6_GALLERY_0.webm"))
            .isEqualTo("video/webm");
        assertThat(MediaTypePolicy.resolve("binary/octet-stream", "https://m.example.com/a.mp4"))
            .isEqualTo("video/mp4");
        assertThat(MediaTypePolicy.resolve("", "https://m.example.com/a.m3u8"))
            .isEqualTo("application/vnd.apple.mpegurl");
        assertThat(MediaTypePolicy.resolve(null, "https://m.example.com/a.PNG"))
            .isEqualTo("image/png");
        // 查询串与锚点不影响扩展名判断。
        assertThat(MediaTypePolicy.resolve("application/octet-stream",
            "https://m.example.com/a.webm?token=1#t=10")).isEqualTo("video/webm");
    }

    @Test
    void rejectsDangerousTypesEvenWhenTheExtensionLooksLikeMedia() {
        // 上游把 HTML / SVG 塞进图片扩展名时不能靠扩展名洗白。
        assertThat(MediaTypePolicy.resolve("text/html", "https://m.example.com/a.png")).isNull();
        assertThat(MediaTypePolicy.resolve("image/svg+xml", "https://m.example.com/a.svg")).isNull();
        assertThat(MediaTypePolicy.resolve("image/svg+xml", "https://m.example.com/a.png")).isNull();
        assertThat(MediaTypePolicy.resolve("application/javascript", "https://m.example.com/a.mp4"))
            .isNull();
        assertThat(MediaTypePolicy.resolve("application/xhtml+xml", "https://m.example.com/a.webm"))
            .isNull();
        assertThat(MediaTypePolicy.resolve("text/xml", "https://m.example.com/a.mp4")).isNull();
    }

    @Test
    void rejectsUnknownTypesWithoutAKnownMediaExtension() {
        assertThat(MediaTypePolicy.resolve("application/octet-stream",
            "https://m.example.com/download")).isNull();
        assertThat(MediaTypePolicy.resolve("application/octet-stream",
            "https://m.example.com/a.zip")).isNull();
        assertThat(MediaTypePolicy.resolve("", "https://m.example.com/a.json")).isNull();
        assertThat(MediaTypePolicy.resolve("application/pdf", "https://m.example.com/a.pdf")).isNull();
    }

    @Test
    void marksFallbackImagesAsAttachmentsButLetsMediaStreamInline() {
        assertThat(MediaTypePolicy.needsAttachmentDisposition("https://m.example.com/a.png")).isTrue();
        assertThat(MediaTypePolicy.needsAttachmentDisposition("https://m.example.com/a.webm"))
            .isFalse();
        assertThat(MediaTypePolicy.needsAttachmentDisposition("https://m.example.com/a.m3u8"))
            .isFalse();
        // 扩展名不在表里时无需加下载头（那种请求本来就被拒了）。
        assertThat(MediaTypePolicy.needsAttachmentDisposition("https://m.example.com/a.bin"))
            .isFalse();
    }

    @Test
    void extractsExtensionFromPathOnly() {
        assertThat(MediaTypePolicy.extensionOf("https://m.example.com/dir/a.webm")).isEqualTo("webm");
        assertThat(MediaTypePolicy.extensionOf("https://m.example.com/a.webm?v=1")).isEqualTo("webm");
        assertThat(MediaTypePolicy.extensionOf("https://m.example.com/a.webm#t=1")).isEqualTo("webm");
        assertThat(MediaTypePolicy.extensionOf("https://m.example.com/a")).isEmpty();
        assertThat(MediaTypePolicy.extensionOf("https://m.example.com/dir.v2/a")).isEmpty();
        assertThat(MediaTypePolicy.extensionOf("https://m.example.com/a.")).isEmpty();
        assertThat(MediaTypePolicy.extensionOf(null)).isEmpty();
    }
}
