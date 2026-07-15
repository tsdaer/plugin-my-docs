package com.github.mydocs.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class DocDetailTemplateAssetsTest {

    @Test
    void usesHaloStaticAssetPrefixForFrontendRenderers() throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/docs/detail.html")) {
            assertThat(input).isNotNull();
            String template = new String(input.readAllBytes(), UTF_8);

            assertThat(template)
                .contains("/plugins/my-docs/assets/static/ratex/fonts.css")
                .contains("/plugins/my-docs/assets/static/ratex")
                .contains("/plugins/my-docs/assets/static/vditor/dist/method.min.js")
                .contains("/plugins/my-docs/assets/static/vditor")
                .doesNotContain("/plugins/my-docs/assets/ratex")
                .doesNotContain("/plugins/my-docs/assets/vditor");
        }
    }

    @Test
    void refreshesRatexColorAfterThemeTransitions() throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/docs/detail.html")) {
            assertThat(input).isNotNull();
            String template = new String(input.readAllBytes(), UTF_8);

            assertThat(template)
                .contains("var contentColor = window.getComputedStyle(content).color")
                .contains("colorToHex(contentColor || themeColor, effectiveScheme)")
                .contains("getPropertyValue('--mdocs-text')")
                .contains("formula.setAttribute('background-color', 'transparent')")
                .contains("scheduleMathColorRefresh()")
                .contains("refreshMathColor();")
                .doesNotContain("refreshMathColor(scheme)")
                .contains("setTimeout(function ()")
                .contains("}, 250)");
        }
    }

    @Test
    void loadsOnlyCustomContentThemeStylesheets() throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/docs/detail.html")) {
            assertThat(input).isNotNull();
            String template = new String(input.readAllBytes(), UTF_8);

            assertThat(template)
                .contains("contentThemeLightUrl")
                .contains("contentThemeDarkUrl")
                .contains("addManagedClasses(customClass)")
                .doesNotContain("/dist/css/content-theme/")
                .doesNotContain("contentThemeLight:")
                .doesNotContain("contentThemeDark:");
        }
    }

    @Test
    void exposesBundledStaticResourcesThroughReverseProxy() throws IOException {
        try (var input = getClass().getResourceAsStream("/extensions/reverseProxy.yaml")) {
            assertThat(input).isNotNull();
            String reverseProxy = new String(input.readAllBytes(), UTF_8);

            assertThat(reverseProxy)
                .contains("kind: ReverseProxy")
                .contains("path: /static/**")
                .contains("directory: static");
        }
    }
}
