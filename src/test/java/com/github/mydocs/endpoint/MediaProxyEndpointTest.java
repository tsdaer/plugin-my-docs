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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * <p>端点层回归：用真实 WebFlux 服务器 + 真实 {@link MediaProxyEndpoint}，
 * 让「取回 → 写回浏览器」整条链路真的跑一遍。</p>
 *
 * <p>这一层的价值在于覆盖 body 的生命周期。此前两次修复都栽在这里：
 * 清理挂在资源 Mono（{@code usingWhen}）或返回的 {@code Mono<ServerResponse>} 上时，
 * 清理会在 WebFlux 开始写响应体之前执行，临时文件被提前删除，
 * 大于内存阈值的媒体全部返回 500、客户端收到 0 字节；
 * 直接调用 {@code MediaProxyService#fetch} 并自己读 body 的测试完全看不到这个问题。</p>
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
    void writesFileBackedBodyToTheWireAndReleasesTheTempFile() throws Exception {
        Path tempFile = Files.createTempFile("mdocs-endpoint-", ".bin");
        byte[] payload = new byte[512 * 1024];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index % 251);
        }
        Files.write(tempFile, payload);

        var proxied = new MediaProxyService.ProxiedMedia(HttpStatus.OK, new HttpHeaders(),
            fileBackedBody(tempFile, payload.length));
        var response = requestThroughRealServer(proxied, "GET");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().length).isEqualTo(payload.length);
        assertThat(response.body()).isEqualTo(payload);
        // body 流读完后清理才该发生。
        assertThat(Files.exists(tempFile)).isFalse();
    }

    @Test
    void releasesTheTempFileWhenTheClientDisconnectsEarly() throws Exception {
        Path tempFile = Files.createTempFile("mdocs-endpoint-", ".bin");
        byte[] payload = new byte[8 * 1024 * 1024];
        Files.write(tempFile, payload);

        var proxied = new MediaProxyService.ProxiedMedia(HttpStatus.OK, new HttpHeaders(),
            fileBackedBody(tempFile, payload.length));
        startServer(proxied);

        // 只读第一小段就断开：取消路径必须把临时文件收掉。
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + "?src=x"))
                .timeout(Duration.ofSeconds(10))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // 先确认响应本身是成功的：坏实现（清理早于写 body）会在这里就 500，
            // 只断言「文件最终不存在」的话，那种失败响应也会让用例变绿。
            assertThat(response.statusCode()).isEqualTo(200);
            try (var stream = response.body()) {
                assertThat(stream.readNBytes(1024)).hasSize(1024);
            }
        }

        for (int attempt = 0; attempt < 50 && Files.exists(tempFile); attempt++) {
            Thread.sleep(100);
        }
        assertThat(Files.exists(tempFile)).isFalse();
    }

    @Test
    void servesHeadWithoutBody() throws Exception {
        Path tempFile = Files.createTempFile("mdocs-endpoint-", ".bin");
        Files.write(tempFile, new byte[4096]);
        var headers = new HttpHeaders();
        headers.setContentLength(4096);
        var proxied = new MediaProxyService.ProxiedMedia(HttpStatus.OK, headers,
            fileBackedBody(tempFile, 4096));

        var response = requestThroughRealServer(proxied, "HEAD");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().length).isZero();
        assertThat(Files.exists(tempFile)).isFalse();
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

    private static MediaProxyService.ProxiedBody fileBackedBody(Path path, long length) {
        return MediaProxyService.ProxiedBody.forTesting(path, length);
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
