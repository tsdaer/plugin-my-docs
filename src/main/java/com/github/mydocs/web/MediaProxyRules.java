package com.github.mydocs.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * <p>同域媒体反代的主机匹配与地址改写规则。</p>
 * <p>纯函数、无状态，便于单独测试：允许清单决定哪些主机可以被代取，
 * 改写只把「命中清单的绝对 http(s) 媒体地址」换成站点自身的代理地址。</p>
 */
public final class MediaProxyRules {

    /** 代理端点的公开路径（{@code GroupVersion} 为 api.my-docs.tsdaer.run/v1alpha1）。 */
    public static final String PROXY_PATH = "/apis/api.my-docs.tsdaer.run/v1alpha1/media-proxy";

    public static final String SOURCE_PARAM = "src";

    static final Pattern HEADER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private static final Pattern HOST_PATTERN =
        Pattern.compile("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:\\d{1,5})?$");

    /** 形如 {@code ::1} / {@code fe80::1} / {@code fc00::1} 的 IPv6 字面量。 */
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9a-f:]+$");

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 明确挡掉的地址：内部主机名与云元数据服务不该出现在媒体地址里。 */
    private static final Set<String> BLOCKED_HOSTS =
        Set.of("localhost", "localhost.localdomain", "ip6-localhost", "metadata.google.internal");

    private MediaProxyRules() {
    }

    /**
     * 主机规则只接受域名（可带 {@code *.} 前缀与端口）。
     * 纯数字标签虽然能骗过域名正则，但那是 IP 字面量，一律拒绝。
     */
    static boolean isValidHostPattern(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String candidate = trimTrailingDots(value.toLowerCase(Locale.ROOT));
        if (!isValidHostSyntax(candidate)) {
            return false;
        }
        String host = hostWithoutPort(candidate);
        String base = host.startsWith("*.") ? host.substring(2) : host;
        return !base.isEmpty() && !BLOCKED_HOSTS.contains(base) && !isIpv4Literal(base);
    }

    /** 取主机部分（去掉端口）；{@code [::1]} 这类 IPv6 字面量保留方括号。 */
    static String hostOf(URI uri) {
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return null;
        }
        // 去掉尾点：media.example.com. 与 media.example.com 是同一台主机。
        String normalized = trimTrailingDots(host.toLowerCase(Locale.ROOT));
        return uri.getPort() > 0 ? normalized + ":" + uri.getPort() : normalized;
    }

    /** DNS 允许域名以点结尾，配置里写不写这个点应该都能用。 */
    static String trimTrailingDots(String host) {
        if (!StringUtils.hasText(host)) {
            return host;
        }
        String trimmed = host;
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String hostWithoutPort(String host) {
        if (host.startsWith("[")) {
            int closing = host.indexOf(']');
            return closing >= 0 ? host.substring(0, closing + 1) : host;
        }
        int colon = host.lastIndexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    static int portOf(String host) {
        if (host.startsWith("[")) {
            int closing = host.indexOf(']');
            return closing >= 0 && closing + 1 < host.length() && host.charAt(closing + 1) == ':'
                ? parsePort(host.substring(closing + 2)) : -1;
        }
        int colon = host.lastIndexOf(':');
        return colon >= 0 ? parsePort(host.substring(colon + 1)) : -1;
    }

    private static int parsePort(String value) {
        if (value.isEmpty() || value.length() > 5) {
            return -1;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return -1;
            }
        }
        return Integer.parseInt(value);
    }

    /** 允许清单用 {@code *.} 前缀表示「该域名及其子域」，省略端口表示任意端口，含默认端口。 */
    static boolean matches(String pattern, String host) {
        if (!StringUtils.hasText(pattern) || !StringUtils.hasText(host)) {
            return false;
        }
        String normalizedPattern = trimTrailingDots(pattern.toLowerCase(Locale.ROOT));
        String normalizedHost = trimTrailingDots(host.toLowerCase(Locale.ROOT));

        String patternHost = hostWithoutPort(normalizedPattern);
        String hostOnly = hostWithoutPort(normalizedHost);
        if (!hostNameMatches(patternHost, hostOnly)) {
            return false;
        }

        int patternPort = portOf(normalizedPattern);
        return patternPort <= 0
            || patternPort == portOf(normalizedHost)
            || isDefaultPortPair(patternPort, portOf(normalizedHost));
    }

    private static boolean hostNameMatches(String patternHost, String hostOnly) {
        if (patternHost.startsWith("*.")) {
            String suffix = patternHost.substring(1);
            // 通配同时覆盖裸域名本身，否则 *.example.com 配了却在 example.com 上不生效。
            return hostOnly.equals(patternHost.substring(2)) || hostOnly.endsWith(suffix);
        }
        return patternHost.equals(hostOnly);
    }

    /** {@code :443} 与省略端口是同一件事（{@code http://host:80} 同理）。 */
    private static boolean isDefaultPortPair(int left, int right) {
        return (left == 443 && right == -1) || (right == 443 && left == -1)
            || (left == 80 && right == -1) || (right == 80 && left == -1);
    }

    static Optional<String> firstMatch(List<String> patterns, String host) {
        return patterns.stream().filter(pattern -> matches(pattern, host)).findFirst();
    }

    /** 两条主机规则是否可能命中同一个主机，用于校验请求头规则有没有对应的允许项。 */
    static boolean overlaps(String left, String right) {
        String leftHost = hostWithoutPort(trimTrailingDots(left.toLowerCase(Locale.ROOT)));
        String rightHost = hostWithoutPort(trimTrailingDots(right.toLowerCase(Locale.ROOT)));
        if (leftHost.equals(rightHost)) {
            return true;
        }
        if (leftHost.startsWith("*.")) {
            // 通配覆盖子域与裸域名本身，与 matches 的语义保持一致。
            return hostNameMatches(leftHost, rightHost);
        }
        if (rightHost.startsWith("*.")) {
            return hostNameMatches(rightHost, leftHost);
        }
        return false;
    }

    /**
     * 是否值得代理：只处理 http(s) 绝对地址，且主机命中允许清单。
     * 相对地址、站内地址、不在清单里的地址都原样返回。
     */
    static String rewrite(String rawUrl, List<String> allowedHosts, String siteHost) {
        if (!StringUtils.hasText(rawUrl) || allowedHosts == null || allowedHosts.isEmpty()) {
            return rawUrl;
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ignored) {
            return rawUrl;
        }
        if (!uri.isAbsolute() || uri.getScheme() == null
            || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            return rawUrl;
        }

        String host = hostOf(uri);
        if (host == null || isSameSite(host, siteHost) || firstMatch(allowedHosts, host).isEmpty()) {
            return rawUrl;
        }
        return buildProxyUrl(rawUrl.trim());
    }

    static String buildProxyUrl(String source) {
        return PROXY_PATH + "?" + SOURCE_PARAM + "="
            + java.net.URLEncoder.encode(source, StandardCharsets.UTF_8);
    }

    /** 站点自身域名下的地址本来就同域，代理只会白跑一趟。 */
    static boolean isSameSite(String host, String siteHost) {
        if (!StringUtils.hasText(host) || !StringUtils.hasText(siteHost)) {
            return false;
        }
        String left = host.toLowerCase(Locale.ROOT);
        String right = siteHost.toLowerCase(Locale.ROOT);
        if (left.equals(right)) {
            return true;
        }
        if (!hostWithoutPort(left).equals(hostWithoutPort(right))) {
            return false;
        }
        // 站点外部地址与附件 URL 的空端口表达方式可能不同（有无显式 80/443），
        // 比较时把空端口视作同一类，避免把 80/443 之一误判成不同站点。
        int leftPort = portOf(left);
        int rightPort = portOf(right);
        if (leftPort == rightPort) {
            return true;
        }
        boolean bothUnset = (leftPort == -1 || leftPort == 80 || leftPort == 443)
            && (rightPort == -1 || rightPort == 80 || rightPort == 443);
        return bothUnset;
    }

    /** 解析 {@code 主机: 头名: 头值}；头值里允许出现冒号，所以最多切成三段。 */    static String[] parseHeaderRule(String line) {
        if (!StringUtils.hasText(line)) {
            return null;
        }
        String[] parts = line.split(":", 3);
        if (parts.length < 3) {
            return null;
        }
        String hostPattern = parts[0].trim().toLowerCase(Locale.ROOT);
        String name = parts[1].trim();
        String value = parts[2].trim();
        // 「主机: 头名: 头值」里主机不能带端口：否则第二段会被当成头名，
        // 结果是真正的头没生效、还往上游发一个名为端口的垃圾头。
        if (isPositiveNumber(name)) {
            return null;
        }
        // 这里只校验写法；是否对得上允许清单统一交给 overlaps 判断。
        if (!isValidHostSyntax(hostPattern) || !StringUtils.hasText(name)
            || !StringUtils.hasText(value)) {
            return null;
        }
        return new String[] {hostPattern, name, value};
    }

    private static boolean isPositiveNumber(String value) {
        if (!StringUtils.hasText(value) || value.length() > 5) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** 主机规则写法（含 {@code *.} 通配与端口），不检查是否指向字面 IP。 */
    private static boolean isValidHostSyntax(String pattern) {
        if (!HOST_PATTERN.matcher(pattern).matches()) {
            return false;
        }
        int port = portOf(pattern);
        if (port > 65535) {
            return false;
        }
        // 裸 IP / 本机名永远不该出现在凭证规则里，顺手挡掉；
        // 通配前缀（*.example.com）在 HOST_PATTERN 里已经允许。
        String host = hostWithoutPort(pattern);
        String base = host.startsWith("*.") ? host.substring(2) : host;
        return !base.isEmpty() && !BLOCKED_HOSTS.contains(base) && !isIpv4Literal(base);
    }

    /**
     * 命中主机规则时按顺序补上的请求头，后者覆盖前者。
     * 匹配用 {@link #matches}，因此 {@code *.example.com} 的凭证在裸域名
     * {@code example.com} 上同样生效——允许清单与凭证规则保持同一套语义。
     */
    static List<String[]> resolveRequestHeaders(List<String> rules, String host) {
        List<String[]> headers = new ArrayList<>();
        if (rules == null || rules.isEmpty() || !StringUtils.hasText(host)) {
            return headers;
        }
        for (String rule : rules) {
            String[] parts = rule.split(":", 3);
            if (parts.length < 3) {
                continue;
            }
            String pattern = parts[0].trim();
            if (!matches(pattern, host)) {
                continue;
            }
            String name = parts[1].trim();
            String value = parts[2].trim();
            headers.removeIf(existing -> existing[0].equalsIgnoreCase(name));
            headers.add(new String[] {name, value});
        }
        return headers;
    }

    /**
     * <p>{@code Authorization} 填不出来的场景：Cloudflare R2 / S3 的 API 不认静态 Bearer，
     * GetObject 必须带按请求算出来的 SigV4 签名。所以凭证规则单独走一套键，
     * 由 {@link #resolveSigningRule} 取出后在发请求时现算签名头。</p>
     *
     * <p>写法：每行 {@code 主机: 键: 值}，键取 {@code access}、{@code secret}
     * 与可选的 {@code region}（默认 {@code auto}）。access 与 secret 必须成对出现。</p>
     */
    record SigningRule(String hostPattern, String accessKey, String secretKey, String region) {
    }

    /** 解析签名凭证规则；缺 access 或 secret 的整条丢弃。 */
    static List<SigningRule> parseSigningRules(List<String> lines) {
        Map<String, String[]> grouped = new java.util.LinkedHashMap<>();
        for (String line : lines == null ? List.<String>of() : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String[] parts = line.split(":", 3);
            if (parts.length < 3) {
                continue;
            }
            String pattern = parts[0].trim().toLowerCase(Locale.ROOT);
            String key = parts[1].trim().toLowerCase(Locale.ROOT);
            String value = parts[2].trim();
            if (!isValidHostSyntax(pattern) || value.isEmpty() || value.length() > 512
                || value.contains("\r") || value.contains("\n")) {
                continue;
            }
            if (!key.equals("access") && !key.equals("secret") && !key.equals("region")) {
                continue;
            }
            String[] entry = grouped.computeIfAbsent(pattern, ignored -> new String[3]);
            switch (key) {
                case "access" -> entry[0] = value;
                case "secret" -> entry[1] = value;
                default -> entry[2] = value;
            }
        }

        List<SigningRule> rules = new ArrayList<>();
        grouped.forEach((pattern, entry) -> {
            if (entry[0] == null || entry[1] == null) {
                return;
            }
            String region = entry[2] == null || entry[2].isBlank() ? "auto" : entry[2].trim();
            rules.add(new SigningRule(pattern, entry[0], entry[1], region));
        });
        return List.copyOf(rules);
    }

    /** 取第一个命中该主机的签名规则；没有则返回 null（表示按普通请求头发）。 */
    static SigningRule resolveSigningRule(List<SigningRule> rules, String host) {
        if (rules == null || rules.isEmpty() || !StringUtils.hasText(host)) {
            return null;
        }
        return rules.stream().filter(rule -> matches(rule.hostPattern(), host)).findFirst()
            .orElse(null);
    }

    static boolean isPrivateAddress(URI uri) {
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return true;
        }
        // 与 matches/hostOf 保持一致：nas.local. 也是 nas.local。
        String normalized = trimTrailingDots(host.toLowerCase(Locale.ROOT));
        if (BLOCKED_HOSTS.contains(normalized) || normalized.endsWith(".local")
            || normalized.endsWith(".internal")) {
            return true;
        }
        if (normalized.startsWith("[")) {
            return isPrivateIpv6(normalized.substring(1).split("]")[0]);
        }
        if (isIpv4Literal(normalized)) {
            long value = ipv4ToLong(normalized);
            if (value < 0) {
                return true;
            }
            int first = (int) (value >>> 24);
            int second = (int) ((value >>> 16) & 0xFF);
            return first == 0 || first == 10 || first == 127
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 169 && second == 254);
        }
        return isPrivateIpv6(normalized);
    }

    /** 环回、唯一本地（fc00::/7）与链路本地（fe80::/10）地址。 */
    private static boolean isPrivateIpv6(String address) {
        // 没有冒号就不是 IPv6 字面量。否则 cdn.example.com 这种全是十六进制字符的域名
        // 会被 "只有十六进制与冒号" 的正则误判。
        if (!address.contains(":") || !IPV6_LITERAL.matcher(address).matches()) {
            return false;
        }
        return address.equals("::1") || address.startsWith("fc") || address.startsWith("fd")
            || address.startsWith("fe80");
    }

    private static boolean isIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int index = 0; index < part.length(); index++) {
                if (!Character.isDigit(part.charAt(index))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long ipv4ToLong(String host) {
        String[] parts = host.split("\\.", -1);
        long value = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return -1;
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    static boolean isProxyUrl(String url) {
        return StringUtils.hasText(url) && url.startsWith(PROXY_PATH + "?");
    }

    /** 从代理地址里取回原始媒体地址，供前端判断 HLS 之类的源类型。 */
    static String sourceOf(String proxyUrl) {
        if (!isProxyUrl(proxyUrl)) {
            return null;
        }
        String query = proxyUrl.substring((PROXY_PATH + "?").length());
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (SOURCE_PARAM.equals(pair.substring(0, equals))) {
                return java.net.URLDecoder.decode(pair.substring(equals + 1),
                    StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}

