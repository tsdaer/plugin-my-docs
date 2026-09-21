package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.extensionpoint.DocContentHandler;
import com.github.mydocs.extensionpoint.DocContentHandlerChain;
import com.github.mydocs.service.MarkdownRenderer;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

class DocDetailContentBuilderTest {

    // 无内容处理扩展时，链为恒等变换，等同旧行为。
    private final DocDetailContentBuilder builder =
        builderWith(emptyGetter(), new DocIndexSettings());

    @Test
    void rewritesSameLibraryShortLinksAndExtractsOutline() {
        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setContent("""
            <p>
              <a href="./intro#install">Intro</a>
              <a href="getting-started">Getting started</a>
              <a href="#current-heading">Current</a>
              <a href="/docs/other/faq#q1">Other library</a>
              <a href="https://example.com/docs">External</a>
              <a href="../shared#x">Parent path</a>
            </p>
            <h2 id="install">Install</h2>
            <p>body</p>
            <h4 id="next-step">Next step</h4>
            """);
        doc.setSpec(spec);

        var content = builder.build(library("guide"), doc).block();

        assertThat(content.getHtml())
            .contains("href=\"/docs/guide/intro#install\"")
            .contains("href=\"/docs/guide/getting-started\"")
            .contains("href=\"#current-heading\"")
            .contains("href=\"/docs/other/faq#q1\"")
            .contains("href=\"https://example.com/docs\"")
            .contains("href=\"../shared#x\"");

        assertThat(content.getOutline())
            .extracting(
                DocDetailContentBuilder.OutlineHeading::getId,
                DocDetailContentBuilder.OutlineHeading::getText,
                DocDetailContentBuilder.OutlineHeading::getDepth
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("install", "Install", 0),
                org.assertj.core.groups.Tuple.tuple("next-step", "Next step", 2)
            );
    }

    @Test
    void stripsMdExtensionWhenRewritingSameLibraryLinks() {
        // 未经导入转换的 .md / .markdown 链接（历史导入内容、HTML 链接等）指向的是
        // 库内页面，重写为短链接时剥掉扩展名；其它扩展名与点开头文件保持原样。
        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setContent("""
            <p>
              <a href="Page-Name.md">Page</a>
              <a href="user-guide.markdown">Guide</a>
              <a href="./FAQ.md#usage">FAQ</a>
              <a href="logo.png">Logo</a>
              <a href="folder/notes.md">Folder</a>
            </p>
            """);
        doc.setSpec(spec);

        var content = builder.build(library("guide"), doc).block();

        assertThat(content.getHtml())
            .contains("href=\"/docs/guide/Page-Name\"")
            .contains("href=\"/docs/guide/user-guide\"")
            .contains("href=\"/docs/guide/FAQ#usage\"")
            .contains("href=\"/docs/guide/logo.png\"")
            .contains("href=\"folder/notes.md\"");
    }

    @Test
    void returnsEmptyDetailContentForBlankHtml() {
        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setContent("   ");
        doc.setSpec(spec);

        var content = builder.build(library("guide"), doc).block();

        assertThat(content.getHtml()).isEmpty();
        assertThat(content.getOutline()).isEmpty();
    }

    @Test
    void runsContentHandlersBeforeLinkRewriteAndOutline() {
        // handler 追加一个带同库短链与标题的片段，验证内置后处理基于扩展后的最终 HTML。
        DocContentHandler handler = context -> {
            context.setContent(context.getContent()
                + "<a href=\"./appendix\">Appendix</a><h2 id=\"added\">Added</h2>");
            return reactor.core.publisher.Mono.just(context);
        };
        var chainBuilder = new DocDetailContentBuilder(
            new DocContentHandlerChain(getterWith(handler)), new MarkdownRenderer(),
            settingsService(new DocIndexSettings()), new MediaProxyService(),
            providerWithExternalUrl(null));

        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setContent("<h2 id=\"intro\">Intro</h2>");
        doc.setSpec(spec);

        var content = chainBuilder.build(library("guide"), doc).block();

        assertThat(content.getHtml())
            .contains("href=\"/docs/guide/appendix\"");
        assertThat(content.getOutline())
            .extracting(DocDetailContentBuilder.OutlineHeading::getId)
            .containsExactly("intro", "added");
    }

    @Test
    void rendersRawMarkdownWithCurrentSettingsBeforeContentHandlers() {
        var settings = new DocIndexSettings();
        settings.setRenderMark(true);
        settings.setRenderParagraphBeginningSpace(true);
        var rawBuilder = builderWith(emptyGetter(), settings);
        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setRaw("## Intro\n\n==marked==");
        spec.setRawType("markdown");
        spec.setContent("<p>stale</p>");
        doc.setSpec(spec);

        var content = rawBuilder.build(library("guide"), doc).block();

        assertThat(content.getHtml())
            .contains("<h2 id=\"intro\">Intro</h2>")
            .contains("<p class=\"mdocs-indent-2\"><mark>marked</mark></p>")
            .doesNotContain("stale");
    }

    @Test
    void rewritesAllowlistedMediaSourcesThroughTheSameOriginProxy() {
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of("*.r2.cloudflarestorage.com", "media.example.com"));
        var proxyBuilder = builderWith(emptyGetter(), settings);

        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setContent("""
            <p><img src="https://bucket.abc.r2.cloudflarestorage.com/a.webm" alt="demo"></p>
            <p><img src="https://media.example.com/b.png" alt="pic"></p>
            <p><img src="https://other.example.net/c.png" alt="external"></p>
            <p><img src="/upload/local.png" alt="local"></p>
            <video src="https://media.example.com/d.mp4"></video>
            <audio><source src="https://media.example.com/e.mp3"></audio>
            """);
        doc.setSpec(spec);

        var content = proxyBuilder.build(library("guide"), doc).block();

        assertThat(content.getHtml())
            .contains("src=\"/apis/api.my-docs.tsdaer.run/v1alpha1/media-proxy?src=")
            .contains("data-mdocs-proxied=\"true\"")
            .contains("src=\"https://other.example.net/c.png\"")
            .contains("src=\"/upload/local.png\"");
        assertThat(content.getHtml().split("data-mdocs-proxied", -1).length - 1).isEqualTo(4);
    }

    @Test
    void leavesMediaSourcesAloneWhenTheProxyIsOffOrNotConfigured() {
        for (var settings : List.of(offProxy(), enabledWithoutHosts())) {
            var doc = new Doc();
            var spec = new Doc.Spec();
            spec.setContent("<p><img src=\"https://media.example.com/b.png\" alt=\"pic\"></p>");
            doc.setSpec(spec);

            var content = builderWith(emptyGetter(), settings).build(library("guide"), doc).block();

            assertThat(content.getHtml())
                .contains("src=\"https://media.example.com/b.png\"")
                .doesNotContain("media-proxy");
        }
    }

    @Test
    void skipsProxyingSourcesAlreadyOnTheSiteHost() {
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of("site.example.com"));
        var proxyBuilder = new DocDetailContentBuilder(new DocContentHandlerChain(emptyGetter()),
            new MarkdownRenderer(), settingsService(settings), new MediaProxyService(),
            providerWithExternalUrl("https://site.example.com"));

        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setContent("<p><img src=\"https://site.example.com/a.png\" alt=\"pic\"></p>");
        doc.setSpec(spec);

        var content = proxyBuilder.build(library("guide"), doc).block();

        assertThat(content.getHtml())
            .contains("src=\"https://site.example.com/a.png\"")
            .doesNotContain("media-proxy");
    }

    private static DocIndexSettings offProxy() {
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(false);
        settings.setMediaProxyAllowedHosts(List.of("media.example.com"));
        return settings;
    }

    private static DocIndexSettings enabledWithoutHosts() {
        var settings = new DocIndexSettings();
        settings.setMediaProxyEnabled(true);
        settings.setMediaProxyAllowedHosts(List.of());
        return settings;
    }

    private static DocDetailContentBuilder builderWith(ExtensionGetter getter,
        DocIndexSettings settings) {
        return new DocDetailContentBuilder(new DocContentHandlerChain(getter),
            new MarkdownRenderer(), settingsService(settings), new MediaProxyService(),
            providerWithExternalUrl(null));
    }

    private static org.springframework.beans.factory.ObjectProvider<run.halo.app.infra.ExternalUrlSupplier>
        providerWithExternalUrl(String externalUrl) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public run.halo.app.infra.ExternalUrlSupplier getObject() {
                return supplier();
            }

            @Override
            public run.halo.app.infra.ExternalUrlSupplier getObject(Object... args) {
                return supplier();
            }

            @Override
            public run.halo.app.infra.ExternalUrlSupplier getIfAvailable() {
                return externalUrl == null ? null : supplier();
            }

            @Override
            public run.halo.app.infra.ExternalUrlSupplier getIfUnique() {
                return getIfAvailable();
            }

            private run.halo.app.infra.ExternalUrlSupplier supplier() {
                return new run.halo.app.infra.ExternalUrlSupplier() {
                    @Override
                    public java.net.URI get() {
                        return java.net.URI.create(externalUrl);
                    }

                    @Override
                    public java.net.URL getURL(org.springframework.http.HttpRequest request) {
                        return null;
                    }

                    @Override
                    public java.net.URL getRaw() {
                        try {
                            return java.net.URI.create(externalUrl).toURL();
                        } catch (java.net.MalformedURLException exception) {
                            return null;
                        }
                    }
                };
            }
        };
    }

    private static DocIndexSettingsService settingsService(DocIndexSettings settings) {
        ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq(DocIndexSettingsService.BASIC_GROUP), any()))
            .thenReturn(Mono.just(settings));
        return new DocIndexSettingsService(fetcher);
    }

    private static DocLibrary library(String slug) {
        var library = new DocLibrary();
        var spec = new DocLibrary.Spec();
        spec.setTitle("Guide");
        spec.setSlug(slug);
        library.setSpec(spec);
        return library;
    }

    private static ExtensionGetter emptyGetter() {
        return getterWith();
    }

    private static ExtensionGetter getterWith(DocContentHandler... handlers) {
        return new ExtensionGetter() {
            @Override
            public <T extends org.pf4j.ExtensionPoint> reactor.core.publisher.Mono<T>
                getEnabledExtension(Class<T> extensionPoint) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T extends org.pf4j.ExtensionPoint> Flux<T>
                getEnabledExtensions(Class<T> extensionPoint) {
                return (Flux<T>) Flux.fromArray(handlers);
            }

            @Override
            public <T extends org.pf4j.ExtensionPoint> Flux<T>
                getExtensions(Class<T> extensionPointClass) {
                return Flux.empty();
            }

            @Override
            public <T extends org.pf4j.ExtensionPoint> List<T>
                getExtensionList(Class<T> extensionPointClass) {
                return List.of();
            }
        };
    }
}
