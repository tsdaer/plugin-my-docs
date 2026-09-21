package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 上游回错误文档时的可读性回归。
 *
 * <p>对象存储的鉴权失败 / 对象不存在都回 {@code application/xml}。此前一律报
 * 「415 上游内容类型不允许」，把排查方向引到内容类型上，而真正的原因
 * （{@code <Code>AccessDenied</Code>} 之类）躺在响应体里没人看。</p>
 */
class MediaProxyUpstreamErrorTest {

    private static final String SOURCE =
        "https://bucket.abc.r2.cloudflarestorage.com/SF_VULKAN_SM6_GALLERY_0.webm";

    @Test
    void reportsTheS3ErrorCodeFromTheUpstreamXml() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>";

        var thrown = fetch(xml);

        // 报错里要能直接看出是对象存储拒绝，以及它的错误码。
        assertThat(thrown).hasMessageContaining("502");
        assertThat(thrown.getMessage()).contains("AccessDenied").contains("Access Denied");
        assertThat(thrown.getMessage()).doesNotContain("415");
    }

    @Test
    void reportsTheAuthorizationErrorFromR2() {
        String xml = "<Error><Code>InvalidArgument</Code><Message>Authorization</Message></Error>";

        var thrown = fetch(xml);

        assertThat(thrown.getMessage())
            .contains("InvalidArgument")
            .contains("Authorization");
    }

    @Test
    void describesXmlWithoutCodeTagsGracefully() {
        var thrown = fetch("<html>not an s3 error</html>");

        assertThat(thrown.getMessage()).contains("502");
    }

    @Test
    void stillRejectsNonXmlSuccessTypesWithTheTypeMessage() {
        var thrown = fetch("<html><body>hi</body></html>", "text/html", HttpStatus.OK);

        assertThat(thrown.getMessage()).contains("415");
        assertThat(thrown.getMessage()).contains("text/html");
    }

    /** 错误状态（非 XML 响应体）如实报上游状态，不再误报成「类型不允许」。 */
    @Test
    void reportsTheUpstreamStatusForNonXmlErrorBodies() {
        var thrown = fetch("<html><body>hi</body></html>", "text/html", HttpStatus.BAD_REQUEST);

        assertThat(thrown.getMessage()).contains("502");
        assertThat(thrown.getMessage()).contains("400");
        assertThat(thrown.getMessage()).doesNotContain("415");
    }

    private static ResponseStatusException fetch(String body) {
        return fetch(body, "application/xml", HttpStatus.BAD_REQUEST);
    }

    private static ResponseStatusException fetch(String body, String contentType,
        HttpStatus status) {
        var service = new MediaProxyService(stubClient(contentType,
            body.getBytes(StandardCharsets.UTF_8), status));
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of("bucket.abc.r2.cloudflarestorage.com"));

        try {
            service.fetch(SOURCE, settings, HttpMethod.GET, new HttpHeaders())
                .block(Duration.ofSeconds(20));
        } catch (ResponseStatusException expected) {
            return expected;
        }
        throw new AssertionError("预期抛出 ResponseStatusException");
    }

    private static WebClient stubClient(String contentType, byte[] body, HttpStatus status) {
        return WebClient.builder()
            .exchangeFunction(request -> Mono.just(ClientResponse
                .create(status, ExchangeStrategies.withDefaults())
                .headers(headers -> {
                    headers.set(HttpHeaders.CONTENT_TYPE, contentType);
                    headers.setContentLength(body.length);
                })
                .body(Flux.just(new DefaultDataBufferFactory().wrap(body)))
                .build()))
            .build();
    }
}

