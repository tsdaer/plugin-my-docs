package com.github.mydocs.web;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * <p>同域媒体反代：由 Halo 服务端代取后台允许清单里的外部媒体，再以站点自身的域名
 * 回给浏览器，前台 {@code <video>} / 图片因此不再直连第三方对象存储。</p>
 *
 * <p>它解决的场景是「对象存储桶不公开、附件 permalink 又只是裸对象地址」：
 * 浏览器直连会拿到 400 / 403，而同域代理可以带上网关凭证（允许清单里可配请求头）。</p>
 *
 * <p>安全边界全部落在允许清单上：只有清单命中的主机才会被代取，重定向后的目标必须重新命中
 * 同一份清单，解析到环回 / 私有网段的地址直接拒绝，响应流超过上限即中断，
 * 内容类型只放行音视频与（非 SVG 的）图片。允许清单为空或开关关闭时，端点一律拒绝请求。</p>
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

    /** 不超过这个大小就整个放内存，超过则落临时文件流式回放。 */
    private static final long IN_MEMORY_LIMIT = 8L * 1024 * 1024;

    private static final int BUFFER_SIZE = 64 * 1024;

    private final WebClient webClient;

    public MediaProxyService() {
        this(WebClient.builder()
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create()
                    .followRedirect(false)
                    .compress(true)
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
     * @return 可直接写回浏览器的状态码、响应头与响应体
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
                .map(response -> {
                    try {
                        return toProxiedMedia(response);
                    } catch (RuntimeException exception) {
                        // 校验没过就别把临时文件留在磁盘上。
                        response.body().release();
                        throw exception;
                    }
                });
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

        return webClient.method(method)
            .uri(target)
            .headers(headers -> {
                requestHeaders.forEach((name, values) -> {
                    if (FORWARD_REQUEST_HEADERS.stream().anyMatch(name::equalsIgnoreCase)) {
                        headers.put(name, values);
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
            })
            .exchangeToMono(response -> {
                HttpStatus status = HttpStatus.resolve(response.statusCode().value());
                boolean redirect = status != null && status.is3xxRedirection();
                if (redirect) {
                    String location = response.headers().header(HttpHeaders.LOCATION).stream()
                        .findFirst().orElse(null);
                    if (redirectCount >= MAX_REDIRECTS) {
                        response.releaseBody().subscribe();
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "上游重定向次数过多"));
                    }
                    if (!StringUtils.hasText(location)) {
                        response.releaseBody().subscribe();
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "上游返回了没有 Location 的重定向"));
                    }
                    response.releaseBody().subscribe();
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

                // 类型与声明长度先判，避免为一个注定要拒的上游先下载整个文件。
                // 上游类型不可信（对象存储常见 octet-stream），所以结合 URL 扩展名一起判断。
                String upstreamType = response.headers().asHttpHeaders()
                    .getFirst(HttpHeaders.CONTENT_TYPE);
                String resolvedType = MediaTypePolicy.resolve(upstreamType, target.toString());
                if (resolvedType == null) {
                    response.releaseBody().subscribe();
                    log.warn("媒体代理拒绝上游内容类型：type={} source={}", upstreamType, target);
                    return Mono.error(new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "上游内容类型不允许通过媒体代理：" + upstreamType));
                }
                boolean attachment = MediaTypePolicy.needsAttachmentDisposition(target.toString());
                long declaredLength = response.headers().asHttpHeaders().getContentLength();
                if (declaredLength > maxBytes) {
                    response.releaseBody().subscribe();
                    return Mono.error(tooLarge());
                }

                // 204 之类的 2xx 不带响应体，不必为它落一份空文件（落了也没人持有）。
                if (!hasBody(status)) {
                    response.releaseBody().subscribe();
                    return Mono.just(new UpstreamResponse(status, response.headers().asHttpHeaders(),
                        ProxiedBody.of(new byte[0]), resolvedType, attachment));
                }

                // HEAD 只要响应头，不必为它落一份 0 字节的临时文件。
                if (method == HttpMethod.HEAD) {
                    response.releaseBody().subscribe();
                    return Mono.just(new UpstreamResponse(status,
                        response.headers().asHttpHeaders(), ProxiedBody.of(new byte[0]),
                        resolvedType, attachment));
                }

                // 响应体必须在 exchangeToMono 的回调内读完：回调返回后 WebClient 会释放它。
                return store(response.bodyToFlux(DataBuffer.class), declaredLength, maxBytes)
                    .map(stored -> new UpstreamResponse(status, response.headers().asHttpHeaders(),
                        stored, resolvedType, attachment));
            });
    }

    /**
     * 把上游响应体落成可重复读取的形态：小文件放内存，大文件落临时文件再流式回放，
     * 避免整个视频进堆。两种形态都在流上就按上限截断，不看上游声明的长度是否可信。
     *
     * <p>这里刻意不做「用完即删」：临时文件的生命周期归调用方
     * （{@code MediaProxyEndpoint} 在响应终止时调用 {@link ProxiedBody#release()}）。
     * 早先在这里用 {@code usingWhen} 释放过一次，结果是资源 Mono 一发出对象就删文件，
     * 端点还没读到就已经没了——大文件必然播不了。</p>
     */
    private static Mono<ProxiedBody> store(Flux<DataBuffer> body, long declaredLength,
        long maxBytes) {
        return Mono.defer(() -> {
            Flux<DataBuffer> bounded = enforceLimit(body, maxBytes);
            boolean toFile = declaredLength > IN_MEMORY_LIMIT || declaredLength < 0;
            if (!toFile) {
                return DataBufferUtils.join(bounded)
                    .map(joined -> {
                        byte[] bytes = new byte[joined.readableByteCount()];
                        joined.read(bytes);
                        DataBufferUtils.release(joined);
                        return ProxiedBody.of(bytes);
                    })
                    .switchIfEmpty(Mono.just(ProxiedBody.of(new byte[0])));
            }

            return Mono.fromCallable(() -> Files.createTempFile("mdocs-media-", ".bin"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(path -> DataBufferUtils.write(bounded, path)
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.fromCallable(() -> ProxiedBody.of(path, Files.size(path)))
                        .subscribeOn(Schedulers.boundedElastic()))
                    // 写盘失败也要把半个临时文件清掉；成功路径交给调用方释放。
                    .onErrorResume(error -> Mono.fromRunnable(() -> deleteQuietly(path))
                        .subscribeOn(Schedulers.boundedElastic())
                        .then(Mono.error(error))));
        });
    }

    /**
     * 按实际字节数截断上游响应：累计超过上限就中断并转成 502。
     * 上游的 Content-Length 可能缺失或撒谎，所以上限只能落在流上。
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

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("清理媒体代理临时文件失败：{}", path, exception);
        }
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "媒体文件超过代理大小上限");
    }

    private ProxiedMedia toProxiedMedia(UpstreamResponse upstream) {
        HttpHeaders headers = upstream.headers();
        HttpStatus status = upstream.status();

        if (status == null || status.isError()) {
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

        var body = status == HttpStatus.OK || status == HttpStatus.PARTIAL_CONTENT
            ? upstream.body()
            : ProxiedBody.of(new byte[0]);
        return new ProxiedMedia(status, responseHeaders, body);
    }

    /** 只有这两个状态才有需要写回浏览器的响应体。 */
    private static boolean hasBody(HttpStatus status) {
        return status == HttpStatus.OK || status == HttpStatus.PARTIAL_CONTENT;
    }

    private static long maxBytes(DocIndexSettings settings) {
        Integer configured = settings.getMediaProxyMaxBytes();
        long value = configured == null ? 536870912L : configured.longValue();
        return Math.max(1048576L, Math.min(value, 2147483647L));
    }

    /** 上游响应：状态码、响应头、已落地的响应体，以及判定后的类型与下载策略。 */
    private record UpstreamResponse(HttpStatus status, HttpHeaders headers, ProxiedBody body,
        String contentType, boolean attachment) {
    }

    /**
     * 已经读下来的响应体。小文件在内存里，大文件在临时文件里；
     * 临时文件由端点在响应终止后删除。
     */
    public static final class ProxiedBody {

        private final byte[] bytes;
        private final Path file;
        private final long length;

        private ProxiedBody(byte[] bytes, Path file, long length) {
            this.bytes = bytes;
            this.file = file;
            this.length = length;
        }

        static ProxiedBody of(byte[] bytes) {
            return new ProxiedBody(bytes, null, bytes.length);
        }

        static ProxiedBody of(Path file, long length) {
            return new ProxiedBody(null, file, length);
        }

        /** 供测试构造文件支撑的响应体。 */
        public static ProxiedBody forTesting(Path file, long length) {
            return new ProxiedBody(null, file, length);
        }

        public long length() {
            return length;
        }

        /** 是否落在临时文件里（大文件分支），用于排查与测试。 */
        public boolean isFileBacked() {
            return file != null;
        }

        /** 临时文件路径；内存形态返回 null。 */
        public Path backingFile() {
            return file;
        }

        /** 每次订阅都从头读一遍，保证重试或写回失败后还能重放。 */
        public Flux<DataBuffer> asFlux() {
            if (file == null) {
                return Flux.just(new DefaultDataBufferFactory().wrap(bytes));
            }
            return DataBufferUtils.read(file, new DefaultDataBufferFactory(), BUFFER_SIZE);
        }

        /** 释放临时文件；内存形态无需处理。 */
        public void release() {
            if (file != null) {
                deleteQuietly(file);
            }
        }
    }

    /** 已经决定要写回浏览器的响应。 */
    public record ProxiedMedia(HttpStatus status, HttpHeaders headers, ProxiedBody body) {
    }
}
