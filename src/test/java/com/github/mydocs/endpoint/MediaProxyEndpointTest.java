package com.github.mydocs.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.mydocs.web.DocIndexSettings;
import com.github.mydocs.web.DocIndexSettingsService;
import com.github.mydocs.web.MediaProxyService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * <p>端点层回归：用真实 WebFlux 服务器 + 真实 {@link MediaProxyEndpoint}，
 * 让「取回 → 写回浏览器」整条链路真的跑一遍。</p>
 *
 * <p>响应体是直连上游的流：这里既验证字节完整写到网络上，也验证浏览器断开时
 * 取消信号传导回上游体（否则播放器每次拖动进度条都会留下一个下载完才罢休的请求）。</p>
 */
class MediaProxyEndpointTest {

    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
            server = null;
        }
    }

    @Test
    void streamsTheUpstreamBodyToTheWire() throws Exception {
        byte[] payload = new byte[512 * 1024];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index % 251);
        }
        var proxied = media(HttpStatus.OK, Flux.just(wrap(payload)));

        var response = requestThroughRealServer(proxied, "GET");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().length).isEqualTo(payload.length);
        assertThat(response.body()).isEqualTo(payload);
    }

    @Test
    void propagatesClientDisconnectToTheUpstreamBody() throws Exception {
        // 上游体慢慢吐：第一块后就断开，断言取消传导（doOnCancel 触发）。
        var cancelled = new AtomicBoolean(false);
        var emitted = new AtomicInteger(0);
        var chunks = new CopyOnWriteArrayList<DataBuffer>();
        for (int index = 0; index < 32; index++) {
            chunks.add(wrap(new byte[64 * 1024]));
        }
        Flux<DataBuffer> slowBody = Flux.fromIterable(chunks)
            .delayElements(Duration.ofMillis(50))
            .doOnNext(buffer -> emitted.incrementAndGet())
            .doOnCancel(() -> cancelled.set(true));
        var proxied = media(HttpStatus.OK, slowBody);
        startServer(proxied);

        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + "?src=x"))
                .timeout(Duration.ofSeconds(10))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // 先确认响应本身是成功的，再读一小段断开。
            assertThat(response.statusCode()).isEqualTo(200);
            try (var stream = response.body()) {
                assertThat(stream.readNBytes(1024)).hasSize(1024);
            }
        }

        for (int attempt = 0; attempt < 50 && !cancelled.get(); attempt++) {
            Thread.sleep(100);
        }
        assertThat(cancelled.get())
            .as("浏览器断开后，上游响应体流应收到取消信号")
            .isTrue();
    }

    @Test
    void servesHeadWithoutBody() throws Exception {
        var headers = new HttpHeaders();
        headers.setContentLength(4096);
        var proxied = media(HttpStatus.OK, Flux.empty(), headers);

        var response = requestThroughRealServer(proxied, "HEAD");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().length).isZero();
    }

    private static MediaProxyService.ProxiedMedia media(HttpStatus status, Flux<DataBuffer> body) {
        return media(status, body, new HttpHeaders());
    }

    private static MediaProxyService.ProxiedMedia media(HttpStatus status, Flux<DataBuffer> body,
        HttpHeaders headers) {
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("video/mp4"));
        return new MediaProxyService.ProxiedMedia(status, headers, body);
    }

    private static DataBuffer wrap(byte[] bytes) {
        return new DefaultDataBufferFactory().wrap(bytes);
    }

    private HttpResponse<byte[]> requestThroughRealServer(MediaProxyService.ProxiedMedia proxied,
        String method) throws Exception {
        startServer(proxied);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + "?src=x"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30))
                .build();
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private void startServer(MediaProxyService.ProxiedMedia proxied) {
        var stubService = new StubMediaProxyService(proxied);
        var endpoint = new MediaProxyEndpoint(stubService, settingsService());
        RouterFunction<ServerResponse> route = endpoint.endpoint();
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(new org.springframework.http.server.reactive.ReactorHttpHandlerAdapter(
                RouterFunctions.toHttpHandler(route)))
            .bindNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.port() + "/media-proxy";
    }

    private static DocIndexSettingsService settingsService() {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq(DocIndexSettingsService.BASIC_GROUP), any()))
            .thenReturn(Mono.just(new DocIndexSettings()));
        return new DocIndexSettingsService(fetcher);
    }

    /** 直接返回准备好的响应，不联网，专测端点这一层。 */
    private static final class StubMediaProxyService extends MediaProxyService {

        private final ProxiedMedia proxied;

        StubMediaProxyService(ProxiedMedia proxied) {
            this.proxied = proxied;
        }

        @Override
        public Mono<ProxiedMedia> fetch(String source, DocIndexSettings settings, HttpMethod method,
            HttpHeaders requestHeaders) {
            return Mono.just(proxied);
        }
    }
}
