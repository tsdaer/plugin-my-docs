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
    void migratesLegacyLightAndGithubThemes() {
        var legacy = new DocIndexSettings();
        legacy.setRenderContentTheme("light");
        legacy.setRenderCodeTheme("github");

        var settings = service(legacy).fetch().block();

        assertThat(settings.getRenderContentThemeLight()).isEqualTo("light");
        assertThat(settings.getRenderContentThemeDark()).isEqualTo("dark");
        assertThat(settings.getRenderCodeThemeLight()).isEqualTo("github");
        assertThat(settings.getRenderCodeThemeDark()).isEqualTo("github-dark");
    }

    @Test
    void keepsNonDefaultLegacyThemesOnBothSchemes() {
        var legacy = new DocIndexSettings();
        legacy.setRenderContentTheme("wechat");
        legacy.setRenderCodeTheme("monokai");

        var settings = service(legacy).fetch().block();

        assertThat(settings.getRenderContentThemeLight()).isEqualTo("wechat");
        assertThat(settings.getRenderContentThemeDark()).isEqualTo("wechat");
        assertThat(settings.getRenderCodeThemeLight()).isEqualTo("monokai");
        assertThat(settings.getRenderCodeThemeDark()).isEqualTo("monokai");
    }

    @Test
    void validatesCustomThemeUrlsAndClasses() {
        var source = new DocIndexSettings();
        source.setRenderContentThemeLight("custom");
        source.setRenderContentThemeLightUrl("https://cdn.example.com/light.css");
        source.setRenderContentThemeLightClass("markdown-body custom_light markdown-body");
        source.setRenderContentThemeDark("custom");
        source.setRenderContentThemeDarkUrl("javascript:alert(1)");
        source.setRenderContentThemeDarkClass("bad.class");

        var settings = service(source).fetch().block();

        assertThat(settings.getRenderContentThemeLight()).isEqualTo("custom");
        assertThat(settings.getRenderContentThemeLightUrl())
            .isEqualTo("https://cdn.example.com/light.css");
        assertThat(settings.getRenderContentThemeLightClass())
            .isEqualTo("markdown-body custom_light");
        assertThat(settings.getRenderContentThemeDark()).isEqualTo("dark");
        assertThat(settings.getRenderContentThemeDarkUrl()).isEmpty();
        assertThat(settings.getRenderContentThemeDarkClass()).isEqualTo("markdown-body");
    }

    private static DocIndexSettingsService service(DocIndexSettings settings) {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq(DocIndexSettingsService.BASIC_GROUP), any()))
            .thenReturn(Mono.just(settings));
        return new DocIndexSettingsService(fetcher);
    }
}
