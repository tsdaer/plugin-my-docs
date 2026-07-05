package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.extensionpoint.DocContentHandler;
import com.github.mydocs.extensionpoint.DocContentHandlerChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

class DocDetailContentBuilderTest {

    // 无内容处理扩展时，链为恒等变换，等同旧行为。
    private final DocDetailContentBuilder builder =
        new DocDetailContentBuilder(new DocContentHandlerChain(emptyGetter()));

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
            new DocContentHandlerChain(getterWith(handler)));

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
