package com.github.mydocs.endpoint;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.github.mydocs.web.DocIndexSettingsService;
import com.github.mydocs.web.MediaProxyRules;
import com.github.mydocs.web.MediaProxyService;
import com.github.mydocs.web.MediaProxyService.ProxiedMedia;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 同域媒体反代端点：{@code /apis/api.my-docs.tsdaer.run/v1alpha1/media-proxy?src=…}。
 *
 * <p>前台文档正文里命中允许清单的媒体地址会被改写成这个路径，浏览器因此只与站点同域通信，
 * 不需要第三方存储开放匿名读，也不涉及 CORS。匿名可访问，实际能做多少事完全由
 * 「文档设置 → 媒体同域代理」的允许清单决定。</p>
 *
 * <p>响应体直通上游（流式转发）：WebFlux 订阅后随到随写，浏览器断开时取消信号
 * 传回上游、连接随之释放，服务端不落任何中间副本。视频的首帧延迟因此约等于上游
 * 首字节延迟，Range 拖动只取所需片段。</p>
 */
@Component
public class MediaProxyEndpoint implements CustomEndpoint {

    private static final String GROUP = "api.my-docs.tsdaer.run";
    private static final String VERSION = "v1alpha1";

    /** 路由以 {@code groupVersion()} 为前缀，路径常量从公开地址里截出来，避免两处写法漂移。 */
    private static final String PROXY_RESOURCE_PATH =
        MediaProxyRules.PROXY_PATH.substring(("/apis/" + GROUP + "/" + VERSION).length());

    private final MediaProxyService mediaProxyService;
    private final DocIndexSettingsService settingsService;

    public MediaProxyEndpoint(MediaProxyService mediaProxyService,
        DocIndexSettingsService settingsService) {
        this.mediaProxyService = mediaProxyService;
        this.settingsService = settingsService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET(PROXY_RESOURCE_PATH, this::proxy)
            .HEAD(PROXY_RESOURCE_PATH, this::proxy)
            .build();
    }

    private Mono<ServerResponse> proxy(ServerRequest request) {
        return settingsService.fetch().flatMap(settings -> {
            var source = request.queryParam(MediaProxyRules.SOURCE_PARAM).orElse(null);
            var method = request.method();
            return mediaProxyService.fetch(source, settings, method, request.headers().asHttpHeaders())
                .flatMap(proxied -> respond(proxied, method)
                    // 兜住「取回成功但响应没写出去就出错 / 被取消」的窗口：
                    // 主动取消一次尚未订阅的上游体，把连接收掉（已订阅时为空操作）。
                    .doOnError(error -> proxied.dispose())
                    .doOnCancel(proxied::dispose));
        });
    }

    private static Mono<ServerResponse> respond(ProxiedMedia proxied, HttpMethod method) {
        var body = method == HttpMethod.HEAD
            ? Flux.<DataBuffer>empty()
            : proxied.body().doOnDiscard(DataBuffer.class, DataBufferUtils::release);

        return ServerResponse.status(proxied.status())
            .headers(headers -> {
                copyHeader(proxied.headers(), headers, HttpHeaders.CONTENT_TYPE);
                copyHeader(proxied.headers(), headers, HttpHeaders.CONTENT_LENGTH);
                copyHeader(proxied.headers(), headers, HttpHeaders.CONTENT_RANGE);
                copyHeader(proxied.headers(), headers, HttpHeaders.ACCEPT_RANGES);
                copyHeader(proxied.headers(), headers, HttpHeaders.ETAG);
                copyHeader(proxied.headers(), headers, HttpHeaders.LAST_MODIFIED);
                copyHeader(proxied.headers(), headers, HttpHeaders.CACHE_CONTROL);
                copyHeader(proxied.headers(), headers, "X-Content-Type-Options");
            })
            .body(body, DataBuffer.class);
    }

    /** 同名头只写一次，避免 Content-Type 之类的头重复导致上游报错。 */
    private static void copyHeader(HttpHeaders source, HttpHeaders target, String name) {
        List<String> values = source.get(name);
        if (values != null && !values.isEmpty() && !target.containsHeader(name)) {
            target.put(name, values);
        }
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.my-docs.tsdaer.run", "v1alpha1");
    }
}
