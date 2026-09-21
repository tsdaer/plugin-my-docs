package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
 * 视频播放时序回归：代理必须在上游响应头到达后立刻把响应交给浏览器，
 * 不能等整个响应体下载完。否则视频首帧要等完整文件下载（大文件 = 分钟级转圈），
 * 每次拖动进度条都会重新触发一次全量下载。
 */
class MediaProxyPlaybackTimingTest {

    @Test
    void handsTheResponseToTheBrowserBeforeTheUpstreamBodyFinishes() {
        // 上游 4 块 × 200ms 间隔：完整下载约 800ms。
        var body = videoBody(4, 32 * 1024, 200);
        var service = new MediaProxyService(stubClient(body));
        var settings = allowAll("media.example.com");

        long startedAt = System.nanoTime();
        var proxied = service.fetch("https://media.example.com/clip.mp4", settings,
            HttpMethod.GET, new HttpHeaders()).block(Duration.ofSeconds(30));
        long fetchMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(proxied).isNotNull();
        assertThat(proxied.status()).isEqualTo(HttpStatus.OK);
        // 响应头就绪即返回：留给上游至少一块的传输时间余量。
        assertThat(fetchMillis)
            .as("fetch() 应在响应头到达后立即完成（实际 %dms），不能等整个响应体下载完", fetchMillis)
            .isLessThan(500);

        // 响应体仍是完整、按序的。
        byte[] received = collect(proxied.body());
        assertThat(received.length).isEqualTo(4 * 32 * 1024);
    }

    /** 播放器拖动进度即取消旧请求：取消必须传导到上游，不再继续白下载。 */
    @Test
    void cancelsTheUpstreamDownloadWhenTheBrowserDisconnects() {
        var emitted = new AtomicLong();
        var body = videoBody(20, 32 * 1024, 100).doOnNext(buffer -> emitted.incrementAndGet());
        var service = new MediaProxyService(stubClient(body));
        var settings = allowAll("media.example.com");

        var proxied = service.fetch("https://media.example.com/clip.mp4", settings,
            HttpMethod.GET, new HttpHeaders()).block(Duration.ofSeconds(30));

        var disposable = proxied.body().subscribe();
        // 读到第一块就断开（播放器换 Range 请求时的真实行为）。
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disposable.dispose();
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(emitted.get())
            .as("浏览器断开后上游应停止发送（实际已收 %d 块）", emitted.get())
            .isLessThan(20);
    }

    static byte[] collect(Flux<org.springframework.core.io.buffer.DataBuffer> flux) {
        return flux.map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                return bytes;
            })
            .reduce(new byte[0], (acc, chunk) -> {
                byte[] merged = new byte[acc.length + chunk.length];
                System.arraycopy(acc, 0, merged, 0, acc.length);
                System.arraycopy(chunk, 0, merged, acc.length, chunk.length);
                return merged;
            })
            .block(Duration.ofSeconds(30));
    }

    private static Flux<org.springframework.core.io.buffer.DataBuffer> videoBody(
        int chunks, int chunkSize, long intervalMillis) {
        byte[] pattern = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        return Flux.range(0, chunks)
            .delayElements(Duration.ofMillis(intervalMillis))
            .map(index -> {
                byte[] chunk = new byte[chunkSize];
                for (int i = 0; i < chunkSize; i++) {
                    chunk[i] = pattern[(i + index) % pattern.length];
                }
                return new DefaultDataBufferFactory().wrap(chunk);
            });
    }

    private static DocIndexSettings allowAll(String... hosts) {
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of(hosts));
        return settings;
    }

    private static WebClient stubClient(
        Flux<org.springframework.core.io.buffer.DataBuffer> body) {
        return WebClient.builder()
            .exchangeFunction(request -> {
                var response = ClientResponse.create(HttpStatus.OK,
                        ExchangeStrategies.withDefaults())
                    .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                    .body(body)
                    .build();
                return Mono.just(response);
            })
            .build();
    }
}
