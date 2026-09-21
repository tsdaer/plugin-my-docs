package com.github.mydocs.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * <p>AWS Signature Version 4 签名器，用来访问要求签名的 S3 兼容对象存储。</p>
 *
 * <p>为什么需要它：Cloudflare R2 / S3 的 API 不接受静态的 Bearer 令牌，GetObject 必须带
 * {@code Authorization: AWS4-HMAC-SHA256 Credential=…} 这类按请求计算出来的签名头。
 * 光靠一个固定请求头填不出来，所以这里按 JDK 的 HMAC-SHA256 自己算。</p>
 *
 * <p>签名结果与 AWS 官方实现一致，见 {@code AwsSigV4SignerTest} 里对照文档示例算出的向量。</p>
 */
public final class AwsSigV4Signer {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    static final String CONTENT_SHA256_HEADER = "x-amz-content-sha256";

    private static final DateTimeFormatter AMZ_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    /** 不做 payload 校验的占位值，S3 兼容存储普遍接受。 */
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final String service;
    private final String payloadHash;
    private final boolean signContentSha256Header;

    public AwsSigV4Signer(String accessKey, String secretKey) {
        this(accessKey, secretKey, "auto", "s3", UNSIGNED_PAYLOAD);
    }

    AwsSigV4Signer(String accessKey, String secretKey, String region, String service) {
        this(accessKey, secretKey, region, service, UNSIGNED_PAYLOAD);
    }

    /**
     * @param payloadHash 放进 {@code x-amz-content-sha256} 的值。对象存储用
     *     {@code UNSIGNED-PAYLOAD} 即可；对照 AWS 文档向量时传真实的空体哈希。
     */
    AwsSigV4Signer(String accessKey, String secretKey, String region, String service,
        String payloadHash) {
        this(accessKey, secretKey, region, service, payloadHash, true);
    }

    /**
     * @param signContentSha256Header 是否把 {@code x-amz-content-sha256} 列进 SignedHeaders。
     *     S3 请求带上它更稳；AWS 文档里的 IAM 示例没有这个头，对照向量时传 false。
     */
    AwsSigV4Signer(String accessKey, String secretKey, String region, String service,
        String payloadHash, boolean signContentSha256Header) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.service = service;
        this.payloadHash = payloadHash;
        this.signContentSha256Header = signContentSha256Header;
    }

    /**
     * 生成 S3 请求需要的三个头：{@code Authorization}、{@code x-amz-date}
     * 与 {@code x-amz-content-sha256}。三者必须来自同一次计算，
     * 否则日期与签名对不上。
     */
    public Map<String, String> headers(String method, URI uri,
        Map<String, String> extraSignedHeaders, Instant at) {
        return Map.of(
            "Authorization", authorizationHeader(method, uri, extraSignedHeaders, at),
            "x-amz-date", AMZ_DATE.format(at),
            CONTENT_SHA256_HEADER, payloadHash
        );
    }

    /**
     * 为一次请求生成 {@code Authorization} 头的值。
     *
     * @param method HTTP 方法，如 GET
     * @param uri 请求地址，查询串会按 S3 规则重新规范编码
     * @param extraSignedHeaders 需要一并签名的头（如 {@code range}），键必须是小写
     * @param at 签名时间
     * @return 可直接放进 {@code Authorization} 头的字符串
     */
    public String authorizationHeader(String method, URI uri, Map<String, String> extraSignedHeaders,
        Instant at) {
        String amzDate = AMZ_DATE.format(at);
        String dateStamp = DATE_STAMP.format(at);

        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("host", hostHeader(uri));
        if (signContentSha256Header) {
            canonicalHeaders.put(CONTENT_SHA256_HEADER, payloadHash);
        }
        canonicalHeaders.put("x-amz-date", amzDate);
        if (extraSignedHeaders != null) {
            extraSignedHeaders.forEach((name, value) -> {
                if (value != null) {
                    canonicalHeaders.put(name.toLowerCase(Locale.ROOT), value.trim());
                }
            });
        }

        String signedHeaders = String.join(";", canonicalHeaders.keySet());
        StringBuilder headerBlock = new StringBuilder();
        canonicalHeaders.forEach((name, value) ->
            headerBlock.append(name).append(':').append(value).append('\n'));

        String canonicalRequest = method.toUpperCase(Locale.ROOT) + "\n"
            + canonicalUri(uri) + "\n"
            + canonicalQuery(uri) + "\n"
            + headerBlock + "\n"
            + signedHeaders + "\n"
            + payloadHash;

        String scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
            + amzDate + "\n"
            + scope + "\n"
            + sha256Hex(canonicalRequest);

        byte[] signingKey = signingKey(dateStamp);
        String signature = HEX.formatHex(hmac(signingKey, stringToSign));

        return ALGORITHM + " Credential=" + accessKey + "/" + scope
            + ", SignedHeaders=" + signedHeaders
            + ", Signature=" + signature;
    }

    private byte[] signingKey(String dateStamp) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    /**
     * 路径按 AWS 规则规范化：先解码成原始字符再统一编码。
     * 直接把 {@code getRawPath()} 再编码一次会把已有的 {@code %20} 变成 {@code %2520}，
     * 存储端算出来的签名就对不上（表现为 403 SignatureDoesNotMatch）。
     */
    static String canonicalUri(URI uri) {
        String path = normalizeDotSegments(uri.getPath());
        if (path == null || path.isEmpty()) {
            return "/";
        }
        StringBuilder encoded = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!segment.isEmpty()) {
                encoded.append('/').append(encode(segment));
            }
        }
        return encoded.length() == 0 ? "/" : encoded.toString();
    }

    /** 按 RFC 3986 去掉 {@code .} 与 {@code ..} 段，路径参数不参与规范化。 */
    private static String normalizeDotSegments(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        java.util.Deque<String> segments = new java.util.ArrayDeque<>();
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(segment);
        }
        return String.join("/", segments);
    }

    /** 查询参数按名字排序、各自编码，空值写成 {@code name=}。 */
    static String canonicalQuery(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        TreeMap<String, List<String>> params = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            params.computeIfAbsent(decode(name), key -> new ArrayList<>()).add(decode(value));
        }

        StringBuilder canonical = new StringBuilder();
        params.forEach((name, values) -> values.stream().sorted().forEach(value -> {
            if (canonical.length() > 0) {
                canonical.append('&');
            }
            canonical.append(encode(name)).append('=').append(encode(value));
        }));
        return canonical.toString();
    }

    /** 带端口的地址要把端口拼进去，否则签名与存储端算出来的不一致。 */
    private static String hostHeader(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("地址缺少主机名：" + uri);
        }
        if (uri.getPort() > 0 && uri.getPort() != defaultPort(uri.getScheme())) {
            return host + ":" + uri.getPort();
        }
        return host;
    }

    private static int defaultPort(String scheme) {
        return "http".equalsIgnoreCase(scheme) ? 80 : 443;
    }

    /** AWS 的编码规则：RFC 3986 非保留字符之外全部百分号编码，空格是 %20 而不是 +。 */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", exception);
        }
    }
}


