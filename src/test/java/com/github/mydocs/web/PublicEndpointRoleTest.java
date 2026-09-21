package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mydocs.endpoint.MediaProxyEndpoint;
import com.github.mydocs.endpoint.PublicDocEndpoint;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.endpoint.CustomEndpoint;

/**
 * 公开端点的匿名权限守卫。
 *
 * <p>Halo 把 {@code /apis/{group}/{version}/{资源}} 这类路径判成「资源请求」
 * （见 {@code RequestInfoFactory}：{@code resource=media-proxy}、{@code verb=get}），
 * 所以 {@code nonResourceURLs} 规则匹配不到它们，匿名角色必须把资源名逐个列进
 * {@code resources}。media-proxy 上线时就栽在这里：端点通了、开关也开了，
 * 匿名访客却被重定向到登录页。</p>
 *
 * <p>新增公开端点时，把资源名同时加进 {@code extensions/roleTemplates.yaml} 与这里的清单；
 * 声明在代码里、断言读自模板，漏配一条就会失败。</p>
 */
class PublicEndpointRoleTest {

    private static final String PUBLIC_GROUP = "api.my-docs.tsdaer.run";

    /**
     * PublicDocEndpoint 与 MediaProxyEndpoint 暴露给访客的资源名。
     * 路径形如 {@code /apis/api.my-docs.tsdaer.run/v1alpha1/{资源名}}。
     */
    private static final Set<String> PUBLIC_RESOURCES =
        Set.of("libraries", "docs", "trees", "media-proxy");

    /** console 前缀的端点要求登录，绝不能出现在匿名角色里。 */
    private static final Set<String> CONSOLE_RESOURCES = Set.of("github-wiki-imports");

    @Test
    void anonymousRoleGrantsEveryPublicResource() {
        assertThat(anonymousResources())
            .as("匿名角色缺少公开端点的资源名，访客会被重定向到登录页")
            .containsAll(PUBLIC_RESOURCES);
    }

    @Test
    void anonymousRoleDoesNotGrantConsoleResources() {
        assertThat(anonymousResources())
            .as("console 端点的资源不应出现在匿名角色里")
            .doesNotContainAnyElementsOf(CONSOLE_RESOURCES);
    }

    /**
     * nonResourceURLs 规则对这类端点不生效，别指望它兜住——
     * 这条断言把「为什么必须逐个列 resources」钉在测试里。
     */
    @Test
    void nonResourceUrlRuleAloneIsNotEnough() {
        String yaml = readRoleTemplates();
        assertThat(yaml).contains("nonResourceURLs");
        assertThat(anonymousResources())
            .as("只有 nonResourceURLs 而没有资源清单时，公开端点会 302 到登录页")
            .isNotEmpty();
    }

    /** 公开端点的 groupVersion 必须与角色模板里授予的组一致。 */
    @Test
    void publicEndpointsUseTheGrantedApiGroup() {
        for (CustomEndpoint endpoint : List.of(
            new PublicDocEndpoint(null),
            new MediaProxyEndpoint(null, null))) {
            assertThat(endpoint.groupVersion().group()).isEqualTo(PUBLIC_GROUP);
        }
    }

    /** 读 roleTemplates.yaml 里匿名角色的资源清单。 */
    private static Set<String> anonymousResources() {
        String yaml = readRoleTemplates();
        int anonymousAt = yaml.indexOf("aggregate-to-anonymous");
        assertThat(anonymousAt).as("roleTemplates.yaml 里没有匿名角色").isGreaterThan(-1);

        Set<String> resources = new LinkedHashSet<>();
        boolean inPublicGroup = false;
        for (String line : yaml.substring(anonymousAt).split("\\R")) {
            // 规则行可能带 YAML 列表前缀 "- "，先剥掉再判断。
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                trimmed = trimmed.substring(2).trim();
            }
            if (trimmed.startsWith("apiGroups:")) {
                inPublicGroup = trimmed.contains(PUBLIC_GROUP);
                continue;
            }
            if (trimmed.startsWith("nonResourceURLs:")) {
                inPublicGroup = false;
                continue;
            }
            if (trimmed.startsWith("resources:") && inPublicGroup) {
                Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(trimmed);
                while (matcher.find()) {
                    resources.add(matcher.group(1));
                }
                inPublicGroup = false;
            }
        }
        return resources;
    }

    private static String readRoleTemplates() {
        try (InputStream stream = PublicEndpointRoleTest.class.getClassLoader()
            .getResourceAsStream("extensions/roleTemplates.yaml")) {
            assertThat(stream).as("classpath 上找不到 extensions/roleTemplates.yaml").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 roleTemplates.yaml 失败", exception);
        }
    }
}
