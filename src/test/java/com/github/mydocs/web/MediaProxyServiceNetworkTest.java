package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * <p>{@link MediaProxyService} 的真实链路测试：后两个用例不发网络请求，覆盖开关关闭、
 * 目标不在允许清单、环回地址这几条拒绝路径。</p>
 *
 * <p>第一个用例会真的去公网取一段视频，验证状态码透传、Content-Type、分段请求
 * （Range / Content-Range）与响应体落地。断网时该用例自动跳过，不影响常规回归。</p>
 */
class MediaProxyServiceNetworkTest {

    private static final String PUBLIC_MP4 =
        "https://www.w3schools.com/html/mov_bbb.mp4";

    private static final long PUBLIC_MP4_BYTES = 788493L;

    /** 10.5 MB 的公开样片：声明长度超过 8 MiB 阈值，会走临时文件分支。 */
    private static final String LARGE_MP4 =
        "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_10MB.mp4";
    private static final String LARGE_MP4_HOST = "test-videos.co.uk";
    private static final long LARGE_MP4_BYTES = 10484984L;

    @Test
    void fetchesPublicMediaStreamingWithRangeSupport() {
        Assumptions.assumeTrue(reachable("www.w3schools.com", 443),
            "公网不可达，跳过真实取流验证");

        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of("www.w3schools.com"));
        settings.setMediaProxyMaxBytes(64 * 1024 * 1024);

        var service = new MediaProxyService();
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=0-2047");

        var proxied = service.fetch(PUBLIC_MP4, settings, HttpMethod.GET, headers).block(
            Duration.ofSeconds(60));

        assertThat(proxied).isNotNull();
        assertThat(proxied.status()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(proxied.headers().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith("video/");
        assertThat(proxied.headers().getFirst(HttpHeaders.CONTENT_RANGE)).startsWith("bytes 0-2047/");
        assertThat(proxied.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        byte[] bytes = readAll(proxied);
        assertThat(bytes).isNotNull();
        assertThat(bytes.length).isEqualTo(2048);
        // 分段请求应该原样转发长度，否则视频拖动进度会失效。
        assertThat(proxied.body().length()).isEqualTo(2048);
        proxied.body().release();
    }

    /**
     * 关键回归：响应体跨过临时文件阈值时，文件必须在端点读完之前一直存在。
     * 早先 store() 里用 usingWhen 释放，资源一发出对象就删文件，
     * 这条用例是当时唯一能发现「大文件必然播不了」的覆盖。
     */
    @Test
    void servesLargeMediaThroughTheTempFilePath() {
        Assumptions.assumeTrue(reachable(LARGE_MP4_HOST, 443),
            "公网不可达，跳过临时文件分支验证");

        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of(LARGE_MP4_HOST));
        settings.setMediaProxyMaxBytes(64 * 1024 * 1024);

        var proxied = new MediaProxyService()
            .fetch(LARGE_MP4, settings, HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(30));

        assertThat(proxied).isNotNull();
        assertThat(proxied.status()).isEqualTo(HttpStatus.OK);
        // 走的是临时文件分支，而不是把整个视频读进堆。
        assertThat(proxied.body().isFileBacked()).isTrue();
        assertThat(proxied.body().length()).isEqualTo(LARGE_MP4_BYTES);

        // 读得到内容，说明文件没有在 store() 返回时就消失。
        byte[] bytes = readAll(proxied);
        assertThat(bytes).isNotNull();
        assertThat(bytes.length).isEqualTo((int) LARGE_MP4_BYTES);

        Path backingFile = proxied.body().backingFile();
        assertThat(backingFile).exists();
        proxied.body().release();
        assertThat(backingFile).doesNotExist();
        assertThat(proxied.body().length()).isEqualTo(LARGE_MP4_BYTES);
    }

    @Test
    void abortsWhenTheUpstreamExceedsTheConfiguredLimit() {
        Assumptions.assumeTrue(reachable(LARGE_MP4_HOST, 443),
            "公网不可达，跳过上限验证");

        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of(LARGE_MP4_HOST));
        // 10.5 MB 的上游 + 1 MiB 上限：声明长度这一关就该拦住，不去读整个响应体。
        settings.setMediaProxyMaxBytes(1024 * 1024);

        assertThatThrownBy(() -> new MediaProxyService()
            .fetch(LARGE_MP4, settings, HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(30)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("502");
    }

    private static byte[] readAll(MediaProxyService.ProxiedMedia proxied) {
        return DataBufferUtils.join(proxied.body().asFlux())
            .map(buffer -> {
                byte[] content = new byte[buffer.readableByteCount()];
                buffer.read(content);
                DataBufferUtils.release(buffer);
                return content;
            })
            .block(Duration.ofSeconds(120));
    }

    /**
     * 线上 415 的回归：对象存储没存 Content-Type，回 {@code application/octet-stream}，
     * 以前会被「不是 video/* 就拒」挡掉。现在结合扩展名放行，并把类型改成推断结果再回给浏览器。
     */
    @Test
    void servesOctetStreamMediaAndRewritesTheContentType() {
        var body = "webm-bytes".getBytes(StandardCharsets.UTF_8);
        var service = new MediaProxyService(stubClient(HttpStatus.OK, "application/octet-stream",
            body));
        var settings = allowAll("bucket.abc.r2.cloudflarestorage.com");

        var proxied = service.fetch(
            "https://bucket.abc.r2.cloudflarestorage.com/SF_VULKAN_SM6_GALLERY_0.webm",
            settings, HttpMethod.GET, new HttpHeaders()).block(Duration.ofSeconds(20));

        assertThat(proxied).isNotNull();
        assertThat(proxied.status()).isEqualTo(HttpStatus.OK);
        assertThat(proxied.headers().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("video/webm");
        assertThat(proxied.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(readAll(proxied)).isEqualTo(body);
        proxied.body().release();
    }

    /** 上游把 HTML 标成图片扩展名时仍要拒绝，不能被扩展名兜底洗白。 */
    @Test
    void rejectsHtmlEvenWhenTheUrlLooksLikeAnImage() {
        var service = new MediaProxyService(stubClient(HttpStatus.OK, "text/html",
            "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)));
        var settings = allowAll("media.example.com");

        assertThatThrownBy(() -> service
            .fetch("https://media.example.com/logo.png", settings, HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(20)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("415");
    }

    /** 没有 Content-Type 也没有可用扩展名时，宁可拒绝也不猜。 */
    @Test
    void rejectsUnknownBinaryWithoutAMediaExtension() {
        var service = new MediaProxyService(stubClient(HttpStatus.OK, "application/octet-stream",
            new byte[] {1, 2, 3}));
        var settings = allowAll("media.example.com");

        assertThatThrownBy(() -> service
            .fetch("https://media.example.com/download", settings, HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(20)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("415");
    }

    private static DocIndexSettings allowAll(String... hosts) {
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of(hosts));
        return settings;
    }

    /** 不联网的桩客户端：返回指定状态、类型与响应体。 */
    private static WebClient stubClient(HttpStatus status, String contentType, byte[] body) {
        return WebClient.builder()
            .exchangeFunction(request -> {
                var headers = new HttpHeaders();
                if (contentType != null) {
                    headers.set(HttpHeaders.CONTENT_TYPE, contentType);
                }
                var buffer = new DefaultDataBufferFactory().wrap(body);
                var response = ClientResponse.create(status,
                        org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults())
                    .headers(target -> target.addAll(headers))
                    .body(Flux.just(buffer))
                    .build();
                return Mono.just(response);
            })
            .build();
    }

    @Test
    void refusesPublicHostsThatAreNotOnTheAllowlist() {
        // 目标可达但不在清单里：必须在发起请求前就拒绝，避免把站点变成开放代理。
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of("media.example.com"));

        assertThatThrownBy(() -> new MediaProxyService()
            .fetch("https://www.w3schools.com/html/mov_bbb.mp4", settings, HttpMethod.GET,
                new HttpHeaders())
            .block(Duration.ofSeconds(20)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    @Test
    void rejectsDisabledProxyAndPrivateTargets() {
        var service = new MediaProxyService();

        var disabled = new DocIndexSettings();
        disabled.setMediaProxyEnabled(false);
        disabled.setMediaProxyAllowedHosts(List.of("www.w3schools.com"));
        assertThatThrownBy(() -> service
            .fetch(PUBLIC_MP4, disabled, HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(20)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");

        var loopback = new DocIndexSettings();
        loopback.setMediaProxyEnabled(true);
        loopback.setMediaProxyAllowedHosts(List.of("127.0.0.1"));
        assertThatThrownBy(() -> service
            .fetch("http://127.0.0.1:8080/a.mp4", loopback, HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(20)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    private static boolean reachable(String host, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}


