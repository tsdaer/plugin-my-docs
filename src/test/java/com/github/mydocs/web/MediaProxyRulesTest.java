package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaProxyRulesTest {

    private static final List<String> ALLOWED =
        List.of("media.example.com", "*.r2.cloudflarestorage.com", "cdn.example.org:8443");

    @Test
    void acceptsPlainWildcardAndPortedHostPatterns() {
        assertThat(MediaProxyRules.isValidHostPattern("media.example.com")).isTrue();
        assertThat(MediaProxyRules.isValidHostPattern("*.example.com")).isTrue();
        assertThat(MediaProxyRules.isValidHostPattern("cdn.example.org:8443")).isTrue();
        assertThat(MediaProxyRules.isValidHostPattern("localhost")).isFalse();
        assertThat(MediaProxyRules.isValidHostPattern("*.localhost:8080")).isFalse();
        assertThat(MediaProxyRules.isValidHostPattern("127.0.0.1")).isFalse();
        assertThat(MediaProxyRules.isValidHostPattern("10.0.0.1")).isFalse();
        assertThat(MediaProxyRules.isValidHostPattern("foo..bar")).isFalse();
        assertThat(MediaProxyRules.isValidHostPattern("media.example.com/path")).isFalse();
        assertThat(MediaProxyRules.isValidHostPattern("*.example.com:99999")).isFalse();
        assertThat(MediaProxyRules.isValidHostPattern("")).isFalse();
        assertThat(MediaProxyRules.isValidHostPattern(null)).isFalse();
    }

    @Test
    void matchesWildcardForSubdomainsAndTheBareDomain() {
        assertThat(MediaProxyRules.matches("*.example.com", "a.example.com")).isTrue();
        assertThat(MediaProxyRules.matches("*.example.com", "a.b.example.com")).isTrue();
        // 通配同时覆盖裸域名，否则 *.example.com 配了却在 example.com 上失效。
        assertThat(MediaProxyRules.matches("*.example.com", "example.com")).isTrue();
        assertThat(MediaProxyRules.matches("*.example.com", "notexample.com")).isFalse();
        assertThat(MediaProxyRules.matches("MEDIA.Example.COM", "media.example.com")).isTrue();
    }

    @Test
    void matchesIgnorePortWhenThePatternHasNone() {
        assertThat(MediaProxyRules.matches("media.example.com", "media.example.com:8443")).isTrue();
        assertThat(MediaProxyRules.matches("media.example.com", "media.example.com:443")).isTrue();
        assertThat(MediaProxyRules.matches("media.example.com", "media.example.com")).isTrue();
        assertThat(MediaProxyRules.matches("media.example.com:8080", "media.example.com:8080"))
            .isTrue();
        assertThat(MediaProxyRules.matches("media.example.com:8080", "media.example.com:9090"))
            .isFalse();
        assertThat(MediaProxyRules.matches("media.example.com:8080", "media.example.com")).isFalse();
        // 配置里显式写默认端口，等价于省略端口。
        assertThat(MediaProxyRules.matches("media.example.com:443", "media.example.com")).isTrue();
        assertThat(MediaProxyRules.matches("cdn.example.org:8443", "cdn.example.org:8443")).isTrue();
        assertThat(MediaProxyRules.hostOf(URI.create("https://cdn.example.org:8443/a.webm")))
            .isEqualTo("cdn.example.org:8443");
        assertThat(MediaProxyRules.hostOf(URI.create("https://CDN.Example.org/a.webm")))
            .isEqualTo("cdn.example.org");
    }

    @Test
    void ignoresTrailingDotsInHosts() {
        assertThat(MediaProxyRules.hostOf(URI.create("https://media.example.com./a.webm")))
            .isEqualTo("media.example.com");
        assertThat(MediaProxyRules.matches("media.example.com", "media.example.com.")).isTrue();
        assertThat(MediaProxyRules.matches("media.example.com.", "media.example.com")).isTrue();
    }

    @Test
    void rewritesOnlyAllowlistedAbsoluteMediaUrls() {
        assertThat(MediaProxyRules.rewrite("https://media.example.com/a/b.webm", ALLOWED, null))
            .isEqualTo(MediaProxyRules.PROXY_PATH
                + "?src=https%3A%2F%2Fmedia.example.com%2Fa%2Fb.webm");
        assertThat(MediaProxyRules.rewrite(
            "https://bucket.abc123.r2.cloudflarestorage.com/SF_VULKAN_SM6_GALLERY_0.webm",
            ALLOWED, null))
            .startsWith(MediaProxyRules.PROXY_PATH + "?src=");
        // 显式端口与尾点写法都要能命中清单，否则私有桶场景下既没代理、直连也打不开。
        assertThat(MediaProxyRules.rewrite("https://media.example.com:443/a.webm", ALLOWED, null))
            .startsWith(MediaProxyRules.PROXY_PATH + "?src=");
        assertThat(MediaProxyRules.rewrite("https://media.example.com./a.webm", ALLOWED, null))
            .startsWith(MediaProxyRules.PROXY_PATH + "?src=");

        assertThat(MediaProxyRules.rewrite("https://other.example.net/a.webm", ALLOWED, null))
            .isEqualTo("https://other.example.net/a.webm");
        assertThat(MediaProxyRules.rewrite("/upload/local.webm", ALLOWED, null))
            .isEqualTo("/upload/local.webm");
        assertThat(MediaProxyRules.rewrite("https://media.example.com/a.webm", List.of(), null))
            .isEqualTo("https://media.example.com/a.webm");
        assertThat(MediaProxyRules.rewrite("ftp://media.example.com/a.webm", ALLOWED, null))
            .isEqualTo("ftp://media.example.com/a.webm");
        assertThat(MediaProxyRules.rewrite("not a url", ALLOWED, null)).isEqualTo("not a url");
    }

    @Test
    void skipsSourcesThatAreAlreadyOnTheSiteHost() {
        assertThat(MediaProxyRules.rewrite("https://site.example.com/a.webm", ALLOWED,
            "site.example.com")).isEqualTo("https://site.example.com/a.webm");
        assertThat(MediaProxyRules.isSameSite("site.example.com:443", "site.example.com")).isTrue();
        assertThat(MediaProxyRules.isSameSite("site.example.com:8443", "site.example.com")).isFalse();
    }

    @Test
    void resolvesRequestHeadersByHost() {
        var rules = List.of(
            "*.cloudflarestorage.com: Authorization: Bearer aaa",
            "media.example.com: Authorization: Bearer bbb",
            "media.example.com: X-Custom: 1"
        );

        assertThat(MediaProxyRules.resolveRequestHeaders(rules, "bucket.abc.r2.cloudflarestorage.com"))
            .extracting(header -> header[0] + "=" + header[1])
            .containsExactly("Authorization=Bearer aaa");
        // 通配规则落在裸域名上也要生效，与允许清单语义一致。
        assertThat(MediaProxyRules.resolveRequestHeaders(rules, "cloudflarestorage.com"))
            .extracting(header -> header[0] + "=" + header[1])
            .containsExactly("Authorization=Bearer aaa");
        assertThat(MediaProxyRules.resolveRequestHeaders(rules, "media.example.com"))
            .extracting(header -> header[0] + "=" + header[1])
            .containsExactly("Authorization=Bearer bbb", "X-Custom=1");
        assertThat(MediaProxyRules.resolveRequestHeaders(rules, "media.example.com:443"))
            .extracting(header -> header[0] + "=" + header[1])
            .containsExactly("Authorization=Bearer bbb", "X-Custom=1");
        assertThat(MediaProxyRules.resolveRequestHeaders(rules, "other.example.com")).isEmpty();
    }

    @Test
    void parsesHeaderRuleLines() {
        assertThat(MediaProxyRules.parseHeaderRule("media.example.com: Authorization: Bearer a:b:c"))
            .containsExactly("media.example.com", "Authorization", "Bearer a:b:c");
        assertThat(MediaProxyRules.parseHeaderRule("media.example.com: Authorization")).isNull();
        assertThat(MediaProxyRules.parseHeaderRule("127.0.0.1: Authorization: x")).isNull();
        assertThat(MediaProxyRules.parseHeaderRule("media.example.com: : value")).isNull();
        // 头名位置是纯数字说明主机顺手带了端口，这种规则要整条丢弃，
        // 否则真正的头不生效，还会向上游发一个名为端口的垃圾头。
        assertThat(MediaProxyRules.parseHeaderRule("media.example.com:8443: X-Token: t")).isNull();
        assertThat(MediaProxyRules.parseHeaderRule("media.example.com:8443: Authorization: Bearer t"))
            .isNull();
    }

    @Test
    void parsesSigningCredentialRules() {
        var rules = MediaProxyRules.parseSigningRules(List.of(
            "media.example.com: access: AKIDEXAMPLE",
            "media.example.com: secret: wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "bucket.abc.r2.cloudflarestorage.com: access: R2KEY",
            "bucket.abc.r2.cloudflarestorage.com: secret: R2SECRET",
            "bucket.abc.r2.cloudflarestorage.com: region: auto",
            // 只有一半凭证、非法主机、未知键都要丢掉
            "half.example.com: access: ONLYACCESS",
            "127.0.0.1: access: x",
            "media.example.org: token: nope"
        ));

        assertThat(rules).hasSize(2);
        assertThat(MediaProxyRules.resolveSigningRule(rules, "media.example.com"))
            .isNotNull()
            .satisfies(rule -> {
                assertThat(rule.accessKey()).isEqualTo("AKIDEXAMPLE");
                assertThat(rule.secretKey()).isEqualTo("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
                assertThat(rule.region()).isEqualTo("auto");
            });
        assertThat(MediaProxyRules.resolveSigningRule(rules, "bucket.abc.r2.cloudflarestorage.com"))
            .isNotNull();
        assertThat(MediaProxyRules.resolveSigningRule(rules, "other.example.com")).isNull();
        assertThat(MediaProxyRules.resolveSigningRule(rules, "half.example.com")).isNull();
    }

    @Test
    void signingRulesFollowTheSameHostMatchingAsTheAllowlist() {
        var rules = MediaProxyRules.parseSigningRules(List.of(
            "*.r2.cloudflarestorage.com: access: K",
            "*.r2.cloudflarestorage.com: secret: S"
        ));

        // 通配覆盖子域与裸域名，且尾点写法同样命中，与允许清单语义一致。
        assertThat(MediaProxyRules.resolveSigningRule(rules, "bucket.abc.r2.cloudflarestorage.com"))
            .isNotNull();
        assertThat(MediaProxyRules.resolveSigningRule(rules, "r2.cloudflarestorage.com")).isNotNull();
        assertThat(MediaProxyRules.resolveSigningRule(rules, "evil.com")).isNull();
    }

    @Test
    void extractsSourceFromProxyUrl() {
        assertThat(MediaProxyRules.sourceOf(
            MediaProxyRules.PROXY_PATH + "?src=https%3A%2F%2Fmedia.example.com%2Fa.webm"))
            .isEqualTo("https://media.example.com/a.webm");
        assertThat(MediaProxyRules.sourceOf(MediaProxyRules.PROXY_PATH + "?src=%2Fupload%2Fa.webm"))
            .isEqualTo("/upload/a.webm");
        assertThat(MediaProxyRules.sourceOf("https://media.example.com/a.webm")).isNull();
        assertThat(MediaProxyRules.isProxyUrl("https://media.example.com/a.webm")).isFalse();
    }

    @Test
    void rejectsLoopbackAndPrivateNetworks() {
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://127.0.0.1/a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://10.1.2.3/a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://172.16.5.5/a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://172.32.5.5/a.webm"))).isFalse();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://192.168.1.5/a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://169.254.169.254/a.webm")))
            .isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://[::1]/a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://[fd00::1]/a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://nas.local/a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://nas.local./a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("http://localhost./a.webm"))).isTrue();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("https://media.example.com/a.webm")))
            .isFalse();
        // 全是十六进制字符的普通域名不能被当成 IPv6 字面量。
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("https://cdn.example.com/a.webm")))
            .isFalse();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("https://abc.def.com/a.webm")))
            .isFalse();
        assertThat(MediaProxyRules.isPrivateAddress(URI.create("https://beef.cafe/a.webm")))
            .isFalse();
    }

    @Test
    void overlapsDetectsRulesSharingAHost() {
        assertThat(MediaProxyRules.overlaps("*.example.com", "a.example.com")).isTrue();
        assertThat(MediaProxyRules.overlaps("a.example.com", "*.example.com")).isTrue();
        // 通配与裸域名重叠：允许清单写 *.example.com 时，example.com 的凭证规则也应该被接受。
        assertThat(MediaProxyRules.overlaps("*.example.com", "example.com")).isTrue();
        assertThat(MediaProxyRules.overlaps("example.com", "*.example.com")).isTrue();
        assertThat(MediaProxyRules.overlaps("a.example.com", "a.example.com")).isTrue();
        assertThat(MediaProxyRules.overlaps("a.example.com", "b.example.com")).isFalse();
        assertThat(MediaProxyRules.overlaps("*.example.com", "example.org")).isFalse();
        // 端口不参与重叠判断：清单写域名、凭证也写域名，匹配阶段再按端口细化。
        assertThat(MediaProxyRules.overlaps("media.example.com", "media.example.com:8443")).isTrue();
    }
}
