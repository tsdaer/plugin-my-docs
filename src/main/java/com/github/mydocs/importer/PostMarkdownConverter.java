package com.github.mydocs.importer;

import com.vladsch.flexmark.ast.LinkNodeBase;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.parser.Parser;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebInputException;
import run.halo.app.content.ContentWrapper;

/** Converts saved article content to editable Markdown without reformatting Markdown sources. */
@Component
public class PostMarkdownConverter {
    private static final Pattern INLINE_URL_ATTRIBUTE = Pattern.compile(
        "(?i)(\\s(?:src|href|poster)\\s*=\\s*)(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private final Parser parser = Parser.builder().build();
    private final FlexmarkHtmlConverter converter = FlexmarkHtmlConverter.builder(
        new com.vladsch.flexmark.util.data.MutableDataSet()
        .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
        // Halo heading/editor IDs must not become visible {#id} attribute extensions:
        // the document renderer/editor use CommonMark and do not interpret that syntax.
        .set(FlexmarkHtmlConverter.SKIP_ATTRIBUTES, true)
        .set(FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, false)
        .set(FlexmarkHtmlConverter.OUTPUT_UNKNOWN_TAGS, true)).build();

    public Conversion convert(ContentWrapper content, String permalink) {
        List<String> warnings = new ArrayList<>();
        String markdown;
        if ("markdown".equalsIgnoreCase(content.getRawType())) {
            markdown = content.getRaw();
        } else {
            String html = "html".equalsIgnoreCase(content.getRawType())
                && StringUtils.hasText(content.getRaw()) ? content.getRaw() : content.getContent();
            if (!StringUtils.hasText(html)) {
                throw new ServerWebInputException("文章没有可转换的正文。请先在文章编辑器中保存内容。");
            }
            var body = Jsoup.parseBodyFragment(html).body();
            normalizeHtmlUrls(body, permalink);
            // Preserve custom editor components and media that cannot be represented by Markdown.
            // Placeholder text survives the converter; restoration happens after conversion.
            List<String> fragments = new ArrayList<>();
            String marker = "MDOCS" + java.util.UUID.randomUUID().toString().replace("-", "") + "HTML";
            for (Element element : new ArrayList<>(body.select("*"))) {
                if (element.parent() == null) {
                    continue;
                }
                boolean complex = element.tagName().contains("-") || switch (element.tagName()) {
                    case "iframe", "video", "audio", "svg", "math", "canvas", "details" -> true;
                    case "table" -> !element.select("[rowspan],[colspan]").isEmpty();
                    default -> false;
                };
                if (complex && element.parents().stream().noneMatch(parent ->
                    parent.tagName().contains("-") || List.of("video", "audio", "svg", "math",
                        "canvas", "iframe", "details").contains(parent.tagName()))) {
                    String token = marker + fragments.size() + "END";
                    fragments.add(element.outerHtml());
                    element.replaceWith(new Element("p").text(token));
                }
            }
            markdown = converter.convert(body.html());
            for (int i = 0; i < fragments.size(); i++) {
                markdown = markdown.replace(marker + i + "END", "\n\n" + fragments.get(i) + "\n\n");
            }
            if (!fragments.isEmpty()) {
                warnings.add("复杂组件已保留为 HTML，显示效果可能依赖原编辑器或主题，请检查转换结果。");
            }
            warnings.add("富文本已转换为 Markdown，请检查排版。");
        }
        if (!StringUtils.hasText(markdown)) {
            throw new ServerWebInputException("文章没有可转换的正文。请先在文章编辑器中保存内容。");
        }
        return new Conversion(normalizeMarkdownUrls(markdown, permalink), List.copyOf(warnings));
    }

    private String normalizeMarkdownUrls(String markdown, String permalink) {
        var document = parser.parse(markdown);
        List<Replacement> replacements = new ArrayList<>();
        for (var node : document.getDescendants()) {
            if (node instanceof LinkNodeBase link) {
                var url = link.getUrl();
                String resolved = resolve(url.toString(), permalink);
                if (!resolved.equals(url.toString())) {
                    replacements.add(new Replacement(url.getStartOffset(), url.getEndOffset(), resolved));
                }
            }
            // Raw HTML in Markdown can also contain article-relative media and links.
            if (node instanceof com.vladsch.flexmark.ast.HtmlInline) {
                String html = node.getChars().toString();
                String normalized = normalizeInlineHtml(html, permalink);
                if (!normalized.equals(html)) {
                    replacements.add(new Replacement(node.getStartOffset(), node.getEndOffset(), normalized));
                }
            } else if (node instanceof com.vladsch.flexmark.ast.HtmlBlock) {
                String html = node.getChars().toString();
                var body = Jsoup.parseBodyFragment(html).body();
                if (normalizeHtmlUrls(body, permalink)) {
                    replacements.add(new Replacement(node.getStartOffset(), node.getEndOffset(), body.html()));
                }
            }
        }
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        var result = new StringBuilder(markdown);
        int boundary = markdown.length();
        for (var replacement : replacements) {
            if (replacement.end() <= boundary) {
                result.replace(replacement.start(), replacement.end(), replacement.value());
                boundary = replacement.start();
            }
        }
        return result.toString();
    }

    // Jsoup would close an isolated opening <a> / <video> tag. Only replace its URL attributes.
    private String normalizeInlineHtml(String html, String permalink) {
        var matcher = INLINE_URL_ATTRIBUTE.matcher(html);
        var result = new StringBuilder();
        while (matcher.find()) {
            String raw = matcher.group(2) != null ? matcher.group(2)
                : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            String value = org.jsoup.parser.Parser.unescapeEntities(raw, true);
            String resolved = resolve(value, permalink);
            String replacement = resolved.equals(value) ? matcher.group()
                : matcher.group(1) + "\"" + org.jsoup.nodes.Entities.escape(resolved)
                    .replace("\"", "&quot;") + "\"";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean normalizeHtmlUrls(Element body, String permalink) {
        boolean changed = false;
        for (var element : body.select("[src],[href],[poster]")) {
            for (String attr : List.of("src", "href", "poster")) {
                if (element.hasAttr(attr)) {
                    String old = element.attr(attr);
                    String resolved = resolve(old, permalink);
                    if (!old.equals(resolved)) {
                        element.attr(attr, resolved);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private String resolve(String value, String permalink) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(permalink)) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() || value.startsWith("//")) {
                return value;
            }
            return URI.create(permalink).resolve(uri).toString();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    public record Conversion(String markdown, List<String> warnings) { }
    private record Replacement(int start, int end, String value) { }
}
