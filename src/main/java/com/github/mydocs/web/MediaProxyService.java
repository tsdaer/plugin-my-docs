package com.github.mydocs.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * <p>同域媒体反代：由 Halo 服务端代取后台允许清单里的外部媒体，再以站点自身的域名
 * 回给浏览器，前台 {@code <video>} / 图片因此不再直连第三方对象存储。</p>
 *
 * <p>它解决的场景是「对象存储桶不公开、附件 permalink 又只是裸对象地址」：
 * 浏览器直连会拿到 400 / 403，而同域代理可以带上网关凭证（允许清单里可配请求头）。</p>
 *
 * <p><b>流式转发</b>：上游响应头一到就把响应交回给浏览器，响应体边收边转，
 * 不在服务端整段缓冲。这是视频能正常播放的前提——播放器要的是首帧快、
 * Range 拖动只取所需片段、切进度条时立刻放弃旧请求；任何「先下完再回」的实现
 * 都会让首帧等完整下载、每次拖动都重新全量拉取。浏览器断开时取消信号会顺着
 * 响应体流传导回上游，不会白白下完一个没人要的文件。</p>
 *
 * <p>安全边界全部落在允许清单上：只有清单命中的主机才会被代取，重定向后的目标必须重新命中
 * 同一份清单，解析到环回 / 私有网段的地址直接拒绝，响应按大小上限把关（声明的
 * Content-Length 超限直接拒；上游谎报长度时按实际字节中断），内容类型只放行音视频与
 * （非 SVG 的）图片。允许清单为空或开关关闭时，端点一律拒绝请求。</p>
 *
 * <p><b>授权提示</b>：端点是匿名可达的，而请求头规则只按主机匹配，
 * 因此清单里的主机一旦配上凭证，任何访客都能取该主机下<em>任意</em>对象，
 * 不只是文档引用过的那些。请只把确实要对公网开放的媒体主机写进允许清单。</p>
 */
@Component
public class MediaProxyService {

    private static final Logger log = LoggerFactory.getLogger(MediaProxyService.class);

    /** 允许转发的请求头（含视频拖动进度依赖的 Range）。 */
    private static final Set<String> FORWARD_REQUEST_HEADERS =
        Set.of(HttpHeaders.RANGE, HttpHeaders.IF_NONE_MATCH, HttpHeaders.IF_MODIFIED_SINCE);

    /** 允许回给浏览器的响应头。刻意不放 Allow-Origin：同域代理不需要 CORS。 */
    private static final Set<String> FORWARD_RESPONSE_HEADERS = Set.of(
        HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_RANGE,
        HttpHeaders.ACCEPT_RANGES, HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED,
        HttpHeaders.CONTENT_ENCODING, HttpHeaders.CACHE_CONTROL, HttpHeaders.EXPIRES
    );

    private static final int MAX_REDIRECTS = 3;

    /** 连接与首个响应头的等待上限，防上游挂死不释放连接。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    /** 上游错误文档最多读这么长，够提出 Code/Message 即可。 */
    private static final long MAX_ERROR_SNIPPET = 8 * 1024;

    private final WebClient webClient;

    public MediaProxyService() {
        this(WebClient.builder()
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create()
                    .followRedirect(false)
                    // 刻意不开启压缩协商：我们要把上游字节原样转给浏览器，
                    // 若中途解压了却仍透传 Content-Encoding/Content-Length，视频会被破坏。
                    .responseTimeout(RESPONSE_TIMEOUT)
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) CONNECT_TIMEOUT.toMillis())))
            .build());
    }

    /** 供测试注入桩客户端，或替换连接器。 */
    public MediaProxyService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 代取一次媒体请求。
     *
     * @param source 原始媒体地址，协议与主机都会重新校验
     * @param settings 生效中的插件设置（允许清单、请求头规则、大小上限）
     * @param method 浏览器发来的方法，仅支持 GET 与 HEAD
     * @param requestHeaders 浏览器请求头，只挑 Range 一类转发
     * @return 可直接写回浏览器的状态码、响应头与流式响应体；响应体被订阅后开始转发，
     *     终止（完成 / 出错 / 取消）时上游连接自动释放
     */
    public Mono<ProxiedMedia> fetch(String source, DocIndexSettings settings, HttpMethod method,
        HttpHeaders requestHeaders) {
        return Mono.defer(() -> {
            if (settings == null || !Boolean.TRUE.equals(settings.getMediaProxyEnabled())) {
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
            }
            List<String> allowedHosts = settings.getMediaProxyAllowedHosts();
            if (allowedHosts == null || allowedHosts.isEmpty()) {
                return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
            }
            if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
                return Mono.error(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));
            }

            URI target = validate(source, allowedHosts);
            long maxBytes = maxBytes(settings);
            return request(target, settings, allowedHosts, method, requestHeaders, 0, maxBytes)
                .map(MediaProxyService::toProxiedMedia);
        });
    }

    /** 逐个校验协议、主机与解析结果；重定向后会用同一份规则再校验一遍。 */
    private URI validate(String source, List<String> allowedHosts) {
        if (!StringUtils.hasText(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 src 参数");
        }

        URI uri;
        try {
            uri = URI.create(source.trim());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "src 不是合法地址");
        }
        if (!uri.isAbsolute() || uri.getScheme() == null
            || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 http(s) 地址");
        }
        String host = MediaProxyRules.hostOf(uri);
        if (host == null || MediaProxyRules.firstMatch(allowedHosts, host).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该主机不在媒体代理允许清单里");
        }
        if (MediaProxyRules.isPrivateAddress(uri)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "拒绝代理内网地址");
        }
        return uri;
    }

    private Mono<UpstreamResponse> request(URI target, DocIndexSettings settings,
        List<String> allowedHosts, HttpMethod method, HttpHeaders requestHeaders,
        int redirectCount, long maxBytes) {
        String host = MediaProxyRules.hostOf(target);

        // toEntityFlux：响应头到达即完成，响应体保持惰性流，端点订阅后才真正开始转发，
        // 连接的释放挂在响应体流的终止信号上（完成 / 出错 / 取消），随浏览器断开联动。
        return webClient.method(method)
            .uri(target)
            .headers(headers -> {
                var forwarded = new java.util.LinkedHashMap<String, String>();
                requestHeaders.forEach((name, values) -> {
                    if (FORWARD_REQUEST_HEADERS.stream().anyMatch(name::equalsIgnoreCase)
                        && !values.isEmpty()) {
                        String trimmed = values.get(0).trim();
                        headers.set(name, trimmed);
                        forwarded.put(name.toLowerCase(Locale.ROOT), trimmed);
                    }
                });
                for (String[] header : MediaProxyRules.resolveRequestHeaders(
                    settings.getMediaProxyRequestHeaders(), host)) {
                    try {
                        headers.set(header[0], header[1]);
                    } catch (IllegalArgumentException exception) {
                        // Reactor Netty 禁止的受限请求头：跳过，不影响其余转发。
                        log.warn("媒体代理忽略不可写的请求头 {}: {}", header[0],
                            exception.getMessage());
                    }
                }

                // R2 / S3 不认静态 Bearer，必须按请求现算 SigV4 签名；
                // 参与签名的头要与真正发出去的值完全一致。
                // 地址本身已带 X-Amz-Signature 一类查询鉴权（预签名 URL）时跳过：
                // 查询串签名与 Authorization 头签名并存，S3 会直接回 400。
                if (!isPresigned(target)) {
                    var signingRule = MediaProxyRules.resolveSigningRule(
                        MediaProxyRules.parseSigningRules(settings.getMediaProxyCredentialRules()),
                        host);
                    if (signingRule != null) {
                        var signer = new AwsSigV4Signer(signingRule.accessKey(),
                            signingRule.secretKey(), signingRule.region(), "s3");
                        signer.headers(method.name(), target, forwarded, Instant.now())
                            .forEach(headers::set);
                    }
                }
            })
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> upstreamError(response, target))
            .toEntityFlux(DataBuffer.class)
            .flatMap(entity -> {
                HttpStatus status = HttpStatus.resolve(entity.getStatusCode().value());

                if (status != null && status.is3xxRedirection()) {
                    String location = entity.getHeaders().getFirst(HttpHeaders.LOCATION);
                    return drain(entity.getBody()).then(Mono.defer(() -> followRedirect(
                        target, location, settings, allowedHosts, method, requestHeaders,
                        redirectCount, maxBytes)));
                }

                // 类型与声明长度先判，避免为一个注定要拒的上游先下载整个文件。
                // 上游类型不可信（对象存储常见 octet-stream），所以结合 URL 扩展名一起判断。
                String upstreamType = entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                String resolvedType = MediaTypePolicy.resolve(upstreamType, target.toString());
                if (resolvedType == null) {
                    if (MediaTypePolicy.isXml(upstreamType)) {
                        // 对象存储的鉴权/找不到对象错误都是 XML 文档，报「415 类型不允许」
                        // 会把人引到类型上，实际原因在响应体里，读出来带上。
                        return readUpstreamError(status, entity.getHeaders(), entity.getBody(),
                            target);
                    }
                    log.warn("媒体代理拒绝上游内容类型：type={} source={}", upstreamType, target);
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "上游内容类型不允许通过媒体代理：" + upstreamType));
                }
                boolean attachment = MediaTypePolicy.needsAttachmentDisposition(target.toString());
                long declaredLength = entity.getHeaders().getContentLength();
                if (declaredLength > maxBytes) {
                    return Mono.error(tooLarge());
                }

                // HEAD 只要响应头；把（通常为空的）上游体消费掉以释放连接。
                if (method == HttpMethod.HEAD) {
                    return drain(entity.getBody()).then(Mono.fromSupplier(() ->
                        new UpstreamResponse(status, entity.getHeaders(),
                            Flux.empty(), resolvedType, attachment)));
                }

                return Mono.just(new UpstreamResponse(status, entity.getHeaders(),
                    enforceLimit(entity.getBody(), maxBytes), resolvedType, attachment));
            });
    }

    /** URL 查询串里已经带 SigV4 预签名参数（或旧式 Signature）时不再叠加头部签名。 */
    private static boolean isPresigned(URI target) {
        String query = target.getRawQuery();
        if (query == null) {
            return false;
        }
        String lowered = query.toLowerCase(Locale.ROOT);
        return lowered.contains("x-amz-signature=") || lowered.contains("x-amz-algorithm=")
            || lowered.contains("signature=");
    }

    private Mono<UpstreamResponse> followRedirect(URI target, String location,
        DocIndexSettings settings, List<String> allowedHosts, HttpMethod method,
        HttpHeaders requestHeaders, int redirectCount, long maxBytes) {
        if (redirectCount >= MAX_REDIRECTS) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "上游重定向次数过多"));
        }
        if (!StringUtils.hasText(location)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "上游返回了没有 Location 的重定向"));
        }
        URI next;
        try {
            next = target.resolve(location.trim());
        } catch (IllegalArgumentException exception) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "上游重定向地址不合法"));
        }
        // 重定向目标必须重新过一遍允许清单与内网检查。
        URI validated = validate(next.toString(), allowedHosts);
        return request(validated, settings, allowedHosts, method, requestHeaders,
            redirectCount + 1, maxBytes);
    }

    /** 读完一个不再需要的响应体（重定向、HEAD），让 WebClient 释放上游连接。 */
    private static Mono<Void> drain(Flux<DataBuffer> body) {
        return body.doOnNext(DataBufferUtils::release).then();
    }

    /**
     * 错误状态统一在这里变成可读的 502：读一小段响应体，
     * 是 S3 的 {@code <Code>} / {@code <Message>} XML 就提出来，否则带上状态码。
     * 限制读取长度，避免为了报错把一个大响应拉下来。
     */
    private static Mono<? extends Throwable> upstreamError(ClientResponse response, URI target) {
        HttpStatus status = HttpStatus.resolve(response.statusCode().value());
        long limit = MAX_ERROR_SNIPPET;
        return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class)
                .takeWhile(buffer -> buffer.readableByteCount() <= limit)
                .take(4))
            .<Throwable>map(joined -> {
                byte[] bytes = new byte[joined.readableByteCount()];
                joined.read(bytes);
                DataBufferUtils.release(joined);
                String snippet = new String(bytes, StandardCharsets.UTF_8);
                log.warn("媒体代理上游返回错误文档：status={} source={} body={}", status, target,
                    snippet.replaceAll("\\s+", " ").trim());
                return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    describeUpstreamError(status, snippet));
            })
            .defaultIfEmpty(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "上游返回 " + (status == null ? "未知状态" : status.value()) + "，响应体为空"));
    }

    /** 2xx / 3xx 但内容是 XML 文档：多半是对象存储把错误标成了别的状态。 */
    private Mono<UpstreamResponse> readUpstreamError(HttpStatus status, HttpHeaders headers,
        Flux<DataBuffer> body, URI target) {
        long limit = MAX_ERROR_SNIPPET;
        return DataBufferUtils.join(body
                .takeWhile(buffer -> buffer.readableByteCount() <= limit)
                .take(4))
            .<UpstreamResponse>map(joined -> {
                byte[] bytes = new byte[joined.readableByteCount()];
                joined.read(bytes);
                DataBufferUtils.release(joined);
                String snippet = new String(bytes, StandardCharsets.UTF_8);
                log.warn("媒体代理上游返回错误文档：status={} source={} body={}", status, target,
                    snippet.replaceAll("\\s+", " ").trim());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    describeUpstreamError(status, snippet));
            })
            .switchIfEmpty(Mono.<UpstreamResponse>fromSupplier(() -> {
                log.warn("媒体代理上游返回错误文档（空响应）：status={} source={}", status, target);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "上游返回 " + (status == null ? "未知状态" : status.value()) + "，响应体为空");
            }));
    }

    /** 从 S3 风格的 XML 里取 Code/Message，取不到就退回状态码。 */
    static String describeUpstreamError(HttpStatus status, String body) {
        String code = extractXmlTag(body, "Code");
        String message = extractXmlTag(body, "Message");
        if (StringUtils.hasText(code) && StringUtils.hasText(message)) {
            return "上游拒绝：" + status.value() + " " + code + " - " + message;
        }
        if (StringUtils.hasText(code)) {
            return "上游拒绝：" + status.value() + " " + code;
        }
        String compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(compact)) {
            return "上游返回 " + status.value() + "，响应体为空";
        }
        return "上游返回 " + status.value() + "：" + compact.substring(0,
            Math.min(160, compact.length()));
    }

    private static String extractXmlTag(String body, String tag) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        var matcher = Pattern.compile("<" + tag + ">([^<]{0,200})</" + tag + ">").matcher(body);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "媒体文件超过代理大小上限");
    }

    private static ProxiedMedia toProxiedMedia(UpstreamResponse upstream) {
        HttpStatus status = upstream.status();
        HttpHeaders headers = upstream.headers();

        if (status == null || status.isError()) {
            // 理论走不到（错误状态在 onStatus 已拦截），保守兜底。
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "上游返回 " + (status == null ? "未知状态" : status.value()));
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        FORWARD_RESPONSE_HEADERS.forEach(name -> {
            List<String> values = headers.get(name);
            if (values != null && !values.isEmpty()) {
                responseHeaders.put(name, values);
            }
        });
        // 类型用判定结果覆盖上游值：上游可能给的是 octet-stream，浏览器靠这个决定怎么播。
        responseHeaders.setContentType(MediaType.parseMediaType(upstream.contentType()));
        if (upstream.attachment()) {
            // 扩展名兜底放行的图片不给内联，避免上游把可执行内容塞进图片扩展名。
            responseHeaders.setContentDisposition(
                org.springframework.http.ContentDisposition.attachment().build());
        }
        if (!responseHeaders.containsHeader(HttpHeaders.ACCEPT_RANGES)) {
            responseHeaders.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        }
        // 同源内容不应被当成其它类型嗅探，尤其是来源不可控的第三方存储。
        responseHeaders.set("X-Content-Type-Options", "nosniff");
        if (!responseHeaders.containsHeader(HttpHeaders.CACHE_CONTROL)) {
            responseHeaders.setCacheControl("public, max-age=3600");
        }

        return new ProxiedMedia(status, responseHeaders, upstream.body());
    }

    /**
     * 按实际字节数截断上游响应：累计超过上限就中断。上游的 Content-Length
     * 可能缺失或撒谎，声明长度这一关在 {@link #request} 里已判过，这里兜流上的。
     * 响应头此时可能已写回浏览器，超限表现为连接中断，浏览器按网络错误处理。
     */
    private static Flux<DataBuffer> enforceLimit(Flux<DataBuffer> body, long maxBytes) {
        return Flux.defer(() -> {
            AtomicLong counter = new AtomicLong();
            return body
                .takeWhile(buffer -> counter.addAndGet(buffer.readableByteCount()) <= maxBytes)
                .concatWith(Flux.defer(() -> counter.get() > maxBytes
                    ? Flux.error(tooLarge())
                    : Flux.empty()));
        });
    }

    private static long maxBytes(DocIndexSettings settings) {
        Integer configured = settings.getMediaProxyMaxBytes();
        long value = configured == null ? 536870912L : configured.longValue();
        return Math.max(1048576L, Math.min(value, 2147483647L));
    }

    /** 上游响应：状态码、响应头、流式响应体，以及判定后的类型与下载策略。 */
    private record UpstreamResponse(HttpStatus status, HttpHeaders headers,
        Flux<DataBuffer> body, String contentType, boolean attachment) {
    }

    /**
     * 已经决定要写回浏览器的响应。响应体是直连上游的流：端点把它交给 WebFlux 后，
     * 随写随转发；流终止（完成 / 出错 / 取消）时 WebClient 自动释放上游连接。
     *
     * <p>{@link #dispose()} 兜「响应造好了但 WebFlux 没来得及订阅响应体」的窗口
     * （构建响应头出错、返回前被取消）：主动取消一次上游体，把连接收掉。</p>
     */
    public static final class ProxiedMedia {

        private final HttpStatus status;
        private final HttpHeaders headers;
        private final Flux<DataBuffer> body;
        private final AtomicBoolean subscribed = new AtomicBoolean();

        public ProxiedMedia(HttpStatus status, HttpHeaders headers, Flux<DataBuffer> body) {
            this.status = status;
            this.headers = headers;
            this.body = body.doOnSubscribe(this::onSubscribe);
        }

        private void onSubscribe(Subscription subscription) {
            subscribed.set(true);
        }

        public HttpStatus status() {
            return status;
        }

        public HttpHeaders headers() {
            return headers;
        }

        public Flux<DataBuffer> body() {
            return body;
        }

        /** 响应体从未被订阅时主动取消，释放上游连接；已被订阅则交给流的终止信号。 */
        public void dispose() {
            if (!subscribed.get()) {
                body.subscribe().dispose();
            }
        }
    }
}
