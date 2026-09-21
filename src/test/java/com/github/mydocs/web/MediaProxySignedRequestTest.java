package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 签名请求真的发出去时还是有效的签名吗？
 *
 * <p>自己算对了签名不代表发出去的请求能被上游验证：{@code Authorization} 头里含有逗号与空格，
 * 若中间的 HTTP 客户端对头值做了规范化（换行折行、压缩空格、大小写改写），
 * 签名就会失效，表现为 403 {@code SignatureDoesNotMatch}。这条用例把桩上游实际收到的
 * 请求头抓出来，按上游视角重新验一遍签名。</p>
 */
class MediaProxySignedRequestTest {

    private static final String ACCESS_KEY = "R2KEY";
    private static final String SECRET_KEY = "R2SECRETVALUE";
    private static final String SOURCE =
        "https://bucket.abc.r2.cloudflarestorage.com/SF_VULKAN_SM6_GALLERY_0.webm";

    @Test
    void signsOutgoingRequestWithHeadersTheUpstreamCanVerify() {
        var captured = new AtomicReference<Map<String, List<String>>>();
        var service = new MediaProxyService(stubClient(captured));
        var settings = settingsWithCredentials();

        var proxied = service.fetch(SOURCE, settings, HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(20));
        assertThat(proxied).isNotNull();

        var headers = captured.get();
        assertThat(headers).as("上游没有收到请求").isNotNull();
        String authorization = first(headers, "Authorization");
        assertThat(authorization)
            .as("带凭证的请求必须带 AWS4 签名头")
            .startsWith("AWS4-HMAC-SHA256 Credential=R2KEY/");
        assertThat(first(headers, "x-amz-content-sha256")).isEqualTo("UNSIGNED-PAYLOAD");
        assertThat(first(headers, "x-amz-date")).matches("\\d{8}T\\d{6}Z");

        // 按上游视角复算：用收到的日期与签名头集合重新算一遍，应当与收到的一致。
        String signedHeaders = between(authorization, "SignedHeaders=", ", Signature=");
        String receivedSignature = authorization.substring(authorization.indexOf("Signature=") + 10);
        assertThat(signedHeaders).isEqualTo("host;x-amz-content-sha256;x-amz-date");

        var signer = new AwsSigV4Signer(ACCESS_KEY, SECRET_KEY, "auto", "s3");
        String recomputed = signer.authorizationHeader("GET", java.net.URI.create(SOURCE), Map.of(),
            java.time.Instant.parse(amzDateToInstant(first(headers, "x-amz-date"))));
        assertThat(recomputed)
            .as("收到的时间戳与签名应当与本地重算一致，说明头没有被中间层改写")
            .endsWith("Signature=" + receivedSignature);
    }

    @Test
    void leavesRequestsUnsignedWhenNoCredentialMatchesTheHost() {
        var captured = new AtomicReference<Map<String, List<String>>>();
        var service = new MediaProxyService(stubClient(captured));
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of("bucket.abc.r2.cloudflarestorage.com"));
        settings.setMediaProxyCredentialRules(List.of("other.example.com: access: A",
            "other.example.com: secret: S"));

        service.fetch(SOURCE, settings, HttpMethod.GET, new HttpHeaders()).block(Duration.ofSeconds(20));

        assertThat(first(captured.get(), "Authorization")).isNull();
    }

    /**
     * 地址已带预签名查询参数（X-Amz-Signature 一类）时不得再叠头部签名：
     * 查询串签名与 Authorization 并存，S3 / R2 会直接回 400
     * 「Only one auth mechanism allowed」。
     */
    @Test
    void skipsHeaderSigningWhenTheUrlIsAlreadyPresigned() {
        var captured = new AtomicReference<Map<String, List<String>>>();
        var service = new MediaProxyService(stubClient(captured));

        service.fetch(SOURCE + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123",
            settingsWithCredentials(), HttpMethod.GET, new HttpHeaders())
            .block(Duration.ofSeconds(20));

        assertThat(first(captured.get(), "Authorization")).isNull();
        assertThat(first(captured.get(), "x-amz-date")).isNull();
    }

    @Test
    void refusesToExposeCredentialsToUnlistedHosts() {
        var captured = new AtomicReference<Map<String, List<String>>>();
        var service = new MediaProxyService(stubClient(captured));
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of("media.example.com"));
        settings.setMediaProxyCredentialRules(List.of("bucket.abc.r2.cloudflarestorage.com: access: A",
            "bucket.abc.r2.cloudflarestorage.com: secret: S"));

        // 主机不在允许清单里，请求在校验阶段就被拒，凭证不会被带上。
        try {
            service.fetch(SOURCE, settings, HttpMethod.GET, new HttpHeaders())
                .block(Duration.ofSeconds(20));
        } catch (RuntimeException expected) {
            assertThat(expected).hasMessageContaining("403");
        }
        assertThat(captured.get()).isNull();
    }

    private static DocIndexSettings settingsWithCredentials() {
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of("bucket.abc.r2.cloudflarestorage.com"));
        settings.setMediaProxyCredentialRules(List.of(
            "bucket.abc.r2.cloudflarestorage.com: access: " + ACCESS_KEY,
            "bucket.abc.r2.cloudflarestorage.com: secret: " + SECRET_KEY));
        return settings;
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start) + start.length();
        return value.substring(from, value.indexOf(end));
    }

    private static String amzDateToInstant(String amzDate) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .parse(amzDate, java.time.Instant::from)
            .toString();
    }

    /** 桩上游：抓下收到的请求头，回一段视频内容。 */
    private static WebClient stubClient(AtomicReference<Map<String, List<String>>> captured) {
        captured.set(null);
        return WebClient.builder()
            .exchangeFunction(request -> {
                var received = new java.util.LinkedHashMap<String, List<String>>();
                request.headers().forEach((name, values) -> received.put(name, List.copyOf(values)));
                captured.set(received);
                var body = "video".getBytes(StandardCharsets.UTF_8);
                var response = ClientResponse.create(HttpStatus.OK,
                        ExchangeStrategies.withDefaults())
                    .headers(headers -> {
                        headers.set(HttpHeaders.CONTENT_TYPE, "video/webm");
                        headers.setContentLength(body.length);
                    })
                    .body(Flux.just(new DefaultDataBufferFactory().wrap(body)))
                    .build();
                return Mono.just(response);
            })
            .build();
    }
}

