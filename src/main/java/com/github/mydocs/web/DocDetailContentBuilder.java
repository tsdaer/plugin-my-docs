package com.github.mydocs.web;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.extensionpoint.DocContentHandlerChain;
import com.github.mydocs.service.MarkdownRenderer;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.Value;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;

/**
 * 为文档详情页补充展示层数据：先经内容后处理扩展链（{@link DocContentHandlerChain}）改写正文，
 * 再统一解析同库短链接、并从最终 HTML 中提取标题大纲。短链改写与大纲提取始终作为链的最后一环，
 * 保证其基于扩展处理后的最终内容。
 */
@Component
public class DocDetailContentBuilder {

    private final DocContentHandlerChain contentHandlerChain;
    private final MarkdownRenderer markdownRenderer;
    private final DocIndexSettingsService settingsService;
    private final MediaProxyService mediaProxyService;
    private final ObjectProvider<ExternalUrlSupplier> externalUrlSupplier;

    public DocDetailContentBuilder(DocContentHandlerChain contentHandlerChain,
        MarkdownRenderer markdownRenderer, DocIndexSettingsService settingsService,
        MediaProxyService mediaProxyService,
        ObjectProvider<ExternalUrlSupplier> externalUrlSupplier) {
        this.contentHandlerChain = contentHandlerChain;
        this.markdownRenderer = markdownRenderer;
        this.settingsService = settingsService;
        this.mediaProxyService = mediaProxyService;
        this.externalUrlSupplier = externalUrlSupplier;
    }

    public Mono<DetailContent> build(DocLibrary library, Doc doc) {
        return settingsService.fetch().flatMap(settings -> {
            var docSpec = spec(doc);
            String content;
            if (StringUtils.hasText(docSpec.getRaw())
                && !"html".equalsIgnoreCase(docSpec.getRawType())) {
                content = markdownRenderer.render(docSpec.getRaw(),
                    MarkdownRenderer.RenderOptions.from(settings));
            } else {
                content = markdownRenderer.enhanceHtml(docSpec.getContent(),
                    MarkdownRenderer.RenderOptions.from(settings));
            }
            if (!StringUtils.hasText(content)) {
                return Mono.just(new DetailContent("", List.of(), settings));
            }

            var librarySlug = library == null || library.getSpec() == null
                ? null : library.getSpec().getSlug();
            return contentHandlerChain.handle(content, doc, library)
                .map(handledContent -> {
                    Document document = Jsoup.parseBodyFragment(handledContent);
                    rewriteSameLibraryLinks(document.body(), librarySlug);
                    rewriteProxiedMediaSources(document.body(), settings);
                    return new DetailContent(document.body().html(),
                        extractOutline(document.body()), settings);
                });
        });
    }

    /**
     * 把命中媒体代理允许清单的图片 / 音视频地址换成站点自身的代理路径。
     * 允许清单为空或开关关闭时 {@link MediaProxyRules#rewrite} 原样返回，DOM 不受影响。
     */
    private void rewriteProxiedMediaSources(Element root, DocIndexSettings settings) {
        if (!Boolean.TRUE.equals(settings.getMediaProxyEnabled())) {
            return;
        }
        var allowedHosts = settings.getMediaProxyAllowedHosts();
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return;
        }

        String siteHost = siteHost();
        for (String selector : List.of("img[src]", "video[src]", "audio[src]", "source[src]")) {
            root.select(selector).forEach(element -> {
                String src = element.attr("src");
                String rewritten = MediaProxyRules.rewrite(src, allowedHosts, siteHost);
                if (!Objects.equals(rewritten, src)) {
                    element.attr("src", rewritten);
                    element.attr("data-mdocs-proxied", "true");
                }
            });
        }
    }

    /** 站点外部地址的主机名，用于跳过本来就同域的地址；没配 external-url 时跳过该优化。 */
    private String siteHost() {
        return Optional.ofNullable(externalUrlSupplier.getIfAvailable())
            .map(ExternalUrlSupplier::getRaw)
            .map(url -> url.getPort() > 0
                ? url.getHost() + ":" + url.getPort() : url.getHost())
            .orElse(null);
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
        normalizedPath = stripPageExtension(normalizedPath);

        StringBuilder builder = new StringBuilder("/docs/")
            .append(librarySlug)
            .append("/")
            .append(normalizedPath);
        if (StringUtils.hasText(uri.getFragment())) {
            builder.append("#").append(uri.getFragment());
        }
        return builder.toString();
    }

    /**
     * Wiki 正文里未经导入转换的 {@code .md} / {@code .markdown} 链接（历史导入内容、
     * HTML 链接、导入器未覆盖的写法）指向的是页面而非静态文件，剥掉扩展名后再按页面
     * 别名解析为库内短链接。
     */
    private static String stripPageExtension(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex <= 0) {
            return path;
        }
        var extension = path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("md") || extension.equals("markdown")) {
            return path.substring(0, dotIndex);
        }
        return path;
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
        DocIndexSettings renderSettings;
    }

    @Value
    public static class OutlineHeading {
        String id;
        String text;
        int level;
        int depth;
    }
}
