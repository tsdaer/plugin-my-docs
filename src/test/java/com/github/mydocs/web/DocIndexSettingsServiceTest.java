package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

class DocIndexSettingsServiceTest {

    @Test
    void migratesLegacyGithubCodeTheme() {
        var legacy = new DocIndexSettings();
        legacy.setRenderContentTheme("light");
        legacy.setRenderCodeTheme("github");

        var settings = service(legacy).fetch().block();

        assertThat(settings.getRenderCodeThemeLight()).isEqualTo("github");
        assertThat(settings.getRenderCodeThemeDark()).isEqualTo("github-dark");
    }

    @Test
    void keepsNonDefaultLegacyCodeThemeOnBothSchemes() {
        var legacy = new DocIndexSettings();
        legacy.setRenderContentTheme("wechat");
        legacy.setRenderCodeTheme("monokai");

        var settings = service(legacy).fetch().block();

        assertThat(settings.getRenderCodeThemeLight()).isEqualTo("monokai");
        assertThat(settings.getRenderCodeThemeDark()).isEqualTo("monokai");
    }

    @Test
    void validatesCustomThemeUrlsAndClasses() {
        var source = new DocIndexSettings();
        source.setRenderContentThemeLightUrl("https://cdn.example.com/light.css");
        source.setRenderContentThemeLightClass("markdown-body custom_light markdown-body");
        source.setRenderContentThemeDarkUrl("javascript:alert(1)");
        source.setRenderContentThemeDarkClass("bad.class");

        var settings = service(source).fetch().block();

        assertThat(settings.getRenderContentThemeLightUrl())
            .isEqualTo("https://cdn.example.com/light.css");
        assertThat(settings.getRenderContentThemeLightClass())
            .isEqualTo("markdown-body custom_light");
        assertThat(settings.getRenderContentThemeDarkUrl()).isEmpty();
        assertThat(settings.getRenderContentThemeDarkClass()).isEqualTo("markdown-body");
    }

    @Test
    void normalizesMediaProxyHostsFromMultilineTextarea() {
        var source = new DocIndexSettings();
        source.setMediaProxyAllowedHosts(java.util.List.of(
            "  Media.Example.com \n*.r2.cloudflarestorage.com",
            "",
            "# 注释行",
            "https://not-a-host.example.com/path",
            "127.0.0.1",
            "media.example.com"
        ));

        var settings = service(source).fetch().block();

        assertThat(settings.getMediaProxyAllowedHosts())
            .containsExactly("media.example.com", "*.r2.cloudflarestorage.com");
    }

    @Test
    void keepsHeaderRulesOnlyForAllowlistedHostsAndPreservesValueCase() {
        var source = new DocIndexSettings();
        source.setMediaProxyAllowedHosts(java.util.List.of("media.example.com"));
        source.setMediaProxyRequestHeaders(java.util.List.of(
            "media.example.com: Authorization: Bearer AbCdEf",
            "evil.example.net: Authorization: Bearer nope",
            "media.example.com: Authorization",
            "media.example.com: X-Bad Name: value",
            "media.example.com: X-Ok: v1"
        ));

        var settings = service(source).fetch().block();

        assertThat(settings.getMediaProxyRequestHeaders())
            .containsExactly(
                "media.example.com: Authorization: Bearer AbCdEf",
                "media.example.com: X-Ok: v1"
            );
    }

    @Test
    void mediaProxyIsOffUnlessExplicitlyEnabled() {
        var settings = service(new DocIndexSettings()).fetch().block();

        assertThat(settings.getMediaProxyEnabled()).isFalse();
        assertThat(settings.getMediaProxyAllowedHosts()).isEmpty();
        assertThat(settings.getMediaProxyMaxBytes()).isEqualTo(536870912);
    }

    private static DocIndexSettingsService service(DocIndexSettings settings) {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq(DocIndexSettingsService.BASIC_GROUP), any()))
            .thenReturn(Mono.just(settings));
        return new DocIndexSettingsService(fetcher);
    }
}
