package com.github.mydocs.web;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.extensionpoint.DocContentHandlerChain;
import java.net.URI;
import java.util.List;
import lombok.Value;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * 为文档详情页补充展示层数据：先经内容后处理扩展链（{@link DocContentHandlerChain}）改写正文，
 * 再统一解析同库短链接、并从最终 HTML 中提取标题大纲。短链改写与大纲提取始终作为链的最后一环，
 * 保证其基于扩展处理后的最终内容。
 */
@Component
public class DocDetailContentBuilder {

    private final DocContentHandlerChain contentHandlerChain;

    public DocDetailContentBuilder(DocContentHandlerChain contentHandlerChain) {
        this.contentHandlerChain = contentHandlerChain;
    }

    public Mono<DetailContent> build(DocLibrary library, Doc doc) {
        var content = spec(doc).getContent();
        if (!StringUtils.hasText(content)) {
            return Mono.just(new DetailContent("", List.of()));
        }

        var librarySlug = library == null || library.getSpec() == null
            ? null : library.getSpec().getSlug();
        return contentHandlerChain.handle(content, doc, library)
            .map(handledContent -> {
                Document document = Jsoup.parseBodyFragment(handledContent);
                rewriteSameLibraryLinks(document.body(), librarySlug);
                return new DetailContent(document.body().html(), extractOutline(document.body()));
            });
    }

    private void rewriteSameLibraryLinks(Element root, String librarySlug) {
        if (!StringUtils.hasText(librarySlug)) {
            return;
        }
        root.select("a[href]").forEach(link -> {
            var resolvedHref = resolveSameLibraryHref(librarySlug, link.attr("href"));
            if (resolvedHref != null) {
                link.attr("href", resolvedHref);
            }
        });
    }

    private List<OutlineHeading> extractOutline(Element root) {
        var headings = root.select("h1[id], h2[id], h3[id], h4[id], h5[id], h6[id]").stream()
            .filter(element -> StringUtils.hasText(element.id()) && StringUtils.hasText(element.text()))
            .toList();
        if (headings.isEmpty()) {
            return List.of();
        }

        int baseLevel = headings.stream()
            .mapToInt(DocDetailContentBuilder::headingLevel)
            .min()
            .orElse(1);

        return headings.stream()
            .map(element -> new OutlineHeading(
                element.id(),
                element.text(),
                headingLevel(element),
                Math.max(0, headingLevel(element) - baseLevel)
            ))
            .toList();
    }

    private static String resolveSameLibraryHref(String librarySlug, String href) {
        if (!StringUtils.hasText(href)) {
            return null;
        }
        if (href.startsWith("#") || href.startsWith("/") || href.startsWith("?")) {
            return null;
        }

        URI uri;
        try {
            uri = URI.create(href);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (uri.isAbsolute() || StringUtils.hasText(uri.getAuthority())
            || StringUtils.hasText(uri.getQuery())) {
            return null;
        }

        String path = uri.getPath();
        if (!StringUtils.hasText(path)) {
            return null;
        }

        String normalizedPath = path.startsWith("./") ? path.substring(2) : path;
        if (!StringUtils.hasText(normalizedPath) || normalizedPath.contains("/")) {
            return null;
        }

        StringBuilder builder = new StringBuilder("/docs/")
            .append(librarySlug)
            .append("/")
            .append(normalizedPath);
        if (StringUtils.hasText(uri.getFragment())) {
            builder.append("#").append(uri.getFragment());
        }
        return builder.toString();
    }

    private static int headingLevel(Element element) {
        return Integer.parseInt(element.tagName().substring(1));
    }

    private static Doc.Spec spec(Doc doc) {
        return doc == null || doc.getSpec() == null ? new Doc.Spec() : doc.getSpec();
    }

    @Value
    public static class DetailContent {
        String html;
        List<OutlineHeading> outline;
    }

    @Value
    public static class OutlineHeading {
        String id;
        String text;
        int level;
        int depth;
    }
}
