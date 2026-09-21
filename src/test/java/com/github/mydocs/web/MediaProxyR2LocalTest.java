package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * <p>对着真实 R2 私有桶的端到端验证：SigV4 签名、octet-stream 类型改写、流式转发、
 * Range 拖动与 HEAD。凭证放在 {@code build/local-r2-test.properties}（build/ 已被
 * .gitignore 忽略），文件不存在时全部用例自动跳过，因此本测试可以安全留在仓库里。</p>
 */
class MediaProxyR2LocalTest {

    private record R2Config(String host, String access, String secret, String url) {
    }

    private static final Path PROPERTIES = Path.of("build", "local-r2-test.properties");

    private static R2Config loadConfig() {
        if (!Files.isRegularFile(PROPERTIES)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(PROPERTIES)) {
            properties.load(in);
        } catch (IOException exception) {
            throw new IllegalStateException("读取 " + PROPERTIES + " 失败", exception);
        }
        String host = properties.getProperty("host");
        String access = properties.getProperty("access");
        String secret = properties.getProperty("secret");
        String url = properties.getProperty("url");
        if (host == null || access == null || secret == null || url == null) {
            return null;
        }
        return new R2Config(host, access, secret, url);
    }

    private static R2Config configOrNull() {
        R2Config config = loadConfig();
        Assumptions.assumeTrue(config != null,
            "缺少 build/local-r2-test.properties，跳过真实 R2 验证");
        return config;
    }

    private static DocIndexSettings settings(R2Config config, boolean withCredentials) {
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of(config.host()));
        settings.setMediaProxyMaxBytes(1024 * 1024 * 1024);
        if (withCredentials) {
            settings.setMediaProxyCredentialRules(List.of(
                config.host() + ": access: " + config.access(),
                config.host() + ": secret: " + config.secret()));
        }
        return settings;
    }

    /**
     * 真实场景里的第一块绊脚石：R2 对未签名请求回
     * {@code 400 InvalidArgument/Authorization}（XML），代理要把它原样翻译出来，
     * 而不是误报 415，并附上「去配签名凭证」的指引。
     */
    @Test
    void surfacesR2AuthErrorWithoutCredentials() {
        var config = configOrNull();
        try {
            new MediaProxyService().fetch(config.url(), settings(config, false),
                HttpMethod.GET, new HttpHeaders()).block(Duration.ofSeconds(30));
        } catch (ResponseStatusException expected) {
            assertThat(expected.getMessage()).contains("502");
            assertThat(expected.getMessage()).contains("InvalidArgument");
            assertThat(expected.getMessage()).contains("未携带签名");
            return;
        }
        throw new AssertionError("未签名请求应被 R2 拒绝");
    }

    /** 主链路：签名 → 流式取回整个视频 → 类型按扩展名改写为 video/webm。 */
    @Test
    void streamsTheWholeVideoWithSigV4Credentials() {
        var config = configOrNull();
        long fetchStarted = System.nanoTime();
        var proxied = new MediaProxyService()
            .fetch(config.url(), settings(config, true), HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(30));
        long fetchMillis = (System.nanoTime() - fetchStarted) / 1_000_000;

        assertThat(proxied).isNotNull();
        assertThat(proxied.status()).isEqualTo(HttpStatus.OK);
        assertThat(proxied.headers().getFirst(HttpHeaders.CONTENT_TYPE))
            .isEqualTo("video/webm");
        long declared = proxied.headers().getContentLength();
        System.out.println("[r2] fetch " + fetchMillis + "ms, declared length " + declared);

        long bodyStarted = System.nanoTime();
        byte[] body = collect(proxied.body());
        long bodyMillis = (System.nanoTime() - bodyStarted) / 1_000_000;
        System.out.println("[r2] body " + body.length + "B in " + bodyMillis + "ms");

        assertThat(declared).isPositive();
        assertThat(body.length).isEqualTo((int) declared);
    }

    /** 拖动进度条：Range 原样透传并签名，返回 206 与正确的字节切片。 */
    @Test
    void servesRangeRequestsLikeASeekingPlayer() {
        var config = configOrNull();
        byte[] whole = range(config, "bytes=0-2047");
        byte[] head = range(config, "bytes=0-1023");
        byte[] tail = range(config, "bytes=1024-2047");

        assertThat(whole.length).isEqualTo(2048);
        byte[] joined = new byte[2048];
        System.arraycopy(head, 0, joined, 0, head.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        assertThat(joined).isEqualTo(whole);
    }

    /** 播放器预加载元信息：HEAD 只要响应头，签名同样有效。 */
    @Test
    void servesHeadWithSigV4Credentials() {
        var config = configOrNull();
        var proxied = new MediaProxyService()
            .fetch(config.url(), settings(config, true), HttpMethod.HEAD, new HttpHeaders())
            .block(Duration.ofSeconds(30));

        assertThat(proxied).isNotNull();
        assertThat(proxied.status()).isEqualTo(HttpStatus.OK);
        assertThat(proxied.headers().getContentLength()).isPositive();
        // HEAD 只有响应头：响应体流应为空（0 个分块）且正常完结。
        Long chunks = proxied.body().count().block(Duration.ofSeconds(30));
        assertThat(chunks).isZero();
    }

    private static byte[] range(R2Config config, String rangeHeader) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, rangeHeader);
        var proxied = new MediaProxyService()
            .fetch(config.url(), settings(config, true), HttpMethod.GET, headers)
            .block(Duration.ofSeconds(30));

        assertThat(proxied).isNotNull();
        assertThat(proxied.status()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        // Content-Range 形如「bytes <起>-<止>/<总长>」。
        assertThat(proxied.headers().getFirst(HttpHeaders.CONTENT_RANGE))
            .startsWith("bytes " + rangeHeader.substring("bytes=".length()).split("-")[0] + "-");
        return collect(proxied.body());
    }

    private static byte[] collect(Flux<DataBuffer> body) {
        return DataBufferUtils.join(body)
            .map(buffer -> {
                byte[] content = new byte[buffer.readableByteCount()];
                buffer.read(content);
                DataBufferUtils.release(buffer);
                return content;
            })
            .block(Duration.ofSeconds(120));
    }
}
