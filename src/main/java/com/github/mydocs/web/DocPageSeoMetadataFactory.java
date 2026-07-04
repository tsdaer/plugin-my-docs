package com.github.mydocs.web;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

@Component
public class DocPageSeoMetadataFactory {

    private static final int DESCRIPTION_MAX_LENGTH = 160;
    private static final String INDEX_TITLE = "文档";
    private static final String INDEX_DESCRIPTION = "浏览站点中的文档库、产品手册与帮助中心内容。";

    public SeoMetadata forIndex() {
        return new SeoMetadata(INDEX_TITLE, INDEX_DESCRIPTION, "website", null);
    }

    public SeoMetadata forLibrary(DocLibrary library) {
        var spec = library.getSpec();
        return new SeoMetadata(
            spec.getTitle() + " - 文档",
            summarize(firstNonBlank(
                spec.getDescription(),
                "浏览「" + spec.getTitle() + "」文档库中的全部已发布文档。"
            )),
            "website",
            normalizeImage(spec.getCover())
        );
    }

    public SeoMetadata forDetail(DocLibrary library, Doc doc) {
        var librarySpec = library.getSpec();
        var docSpec = doc.getSpec();
        return new SeoMetadata(
            docSpec.getTitle() + " - " + librarySpec.getTitle(),
            summarize(firstNonBlank(
                docSpec.getContent(),
                docSpec.getRaw(),
                librarySpec.getDescription(),
                "阅读「" + docSpec.getTitle() + "」文档。"
            )),
            "article",
            normalizeImage(librarySpec.getCover())
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeImage(String image) {
        return StringUtils.hasText(image) ? image.trim() : null;
    }

    String summarize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        var summary = normalizeWhitespace(HtmlUtils.htmlUnescape(stripMarkup(text)));
        if (!StringUtils.hasText(summary)) {
            return "";
        }

        if (summary.length() <= DESCRIPTION_MAX_LENGTH) {
            return summary;
        }

        return summary.substring(0, DESCRIPTION_MAX_LENGTH - 3).stripTrailing() + "...";
    }

    private static String stripMarkup(String text) {
        return text
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?s)<[^>]+>", " ")
            .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ")
            .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
            .replaceAll("[#>*_`~|]", " ");
    }

    private static String normalizeWhitespace(String text) {
        return text
            .replace('\u00A0', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }
}
