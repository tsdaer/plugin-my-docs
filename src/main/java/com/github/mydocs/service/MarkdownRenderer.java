package com.github.mydocs.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.CoreHtmlNodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.github.mydocs.web.DocIndexSettings;

/**
 * <p>将文档正文的 Markdown 原文（{@code spec.raw}）渲染为 HTML（{@code spec.content}）。</p>
 * <p>基于 commonmark-java，启用 GFM 风格的扩展：表格、任务列表、删除线、自动链接，
 * 并为标题生成锚点 id 以便前台目录跳转。围栏代码块会输出 {@code language-xxx} class，
 * 以便前台接入客户端代码高亮（如 highlight.js / Prism）。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class MarkdownRenderer {

    private static final String IMAGE_WIDTH_PARAM = "md-width";
    private static final String IMAGE_ALIGN_PARAM = "md-align";
    private static final String IMAGE_PADDING_PARAM = "md-pad";
    private static final Pattern CSS_LENGTH_PATTERN = Pattern.compile(
        "^(?:0|\\d+(?:\\.\\d+)?(?:px|rem|em|%)?)$"
    );
    private static final Pattern MARK_PATTERN = Pattern.compile("==([^=\\r\\n]+)==");
    private static final Pattern INLINE_MATH_PATTERN =
        Pattern.compile("(?<!\\$)\\$([^$\\r\\n]+)\\$(?!\\$)");
    private static final Pattern CJK_LATIN_PATTERN = Pattern.compile(
        "([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}])([A-Za-z0-9@%])"
    );
    private static final Pattern LATIN_CJK_PATTERN = Pattern.compile(
        "([A-Za-z0-9@%])([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}])"
    );
    private static final Pattern TERM_PATTERN = Pattern.compile("(?i)\\b(" + String.join("|",
        "javascript", "typescript", "github", "gitlab", "markdown", "springboot",
        "springcloud", "springmvc", "mysql", "postgresql", "mongodb", "redis",
        "docker", "kubernetes", "html", "css", "json", "yaml", "xml", "api",
        "graphql", "java", "openjdk", "jdk", "android", "ios", "halo") + ")\\b");
    private static final Map<String, String> TERM_CASES = Map.ofEntries(
        Map.entry("javascript", "JavaScript"), Map.entry("typescript", "TypeScript"),
        Map.entry("github", "GitHub"), Map.entry("gitlab", "GitLab"),
        Map.entry("markdown", "Markdown"), Map.entry("springboot", "SpringBoot"),
        Map.entry("springcloud", "SpringCloud"), Map.entry("springmvc", "SpringMVC"),
        Map.entry("mysql", "MySQL"), Map.entry("postgresql", "PostgreSQL"),
        Map.entry("mongodb", "MongoDB"), Map.entry("redis", "Redis"),
        Map.entry("docker", "Docker"), Map.entry("kubernetes", "Kubernetes"),
        Map.entry("html", "HTML"), Map.entry("css", "CSS"), Map.entry("json", "JSON"),
        Map.entry("yaml", "YAML"), Map.entry("xml", "XML"), Map.entry("api", "API"),
        Map.entry("graphql", "GraphQL"), Map.entry("java", "Java"),
        Map.entry("openjdk", "OpenJDK"), Map.entry("jdk", "JDK"),
        Map.entry("android", "Android"), Map.entry("ios", "iOS"),
        Map.entry("halo", "Halo")
    );

    /**
     * 渲染 Markdown 为 HTML。{@code null} 或空白输入返回空字符串，保证结果非 null。
     *
     * @param markdown Markdown 原文
     * @return 渲染后的 HTML
     */
    public String render(String markdown) {
        return render(markdown, RenderOptions.defaults());
    }

    public String render(String markdown, RenderOptions options) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        List<Extension> extensions = new ArrayList<>(List.of(
            TablesExtension.create(),
            TaskListItemsExtension.create(),
            StrikethroughExtension.create(),
            HeadingAnchorExtension.create()
        ));
        if (options.gfmAutoLink()) {
            extensions.add(AutolinkExtension.create());
        }
        if (options.footnotes()) {
            extensions.add(FootnotesExtension.create());
        }
        Parser parser = Parser.builder().extensions(extensions).build();
        HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .extensions(extensions)
            .nodeRendererFactory(MarkdownHtmlNodeRenderer::new)
            .build();
        markdown = normalizeMathBlocks(markdown);
        Node document = parser.parse(markdown);
        return enhanceHtml(htmlRenderer.render(document), options);
    }

    public String enhanceHtml(String html, RenderOptions options) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(html);
        Element body = document.body();
        if (options.mark()) {
            replaceTextPattern(body, MARK_PATTERN, "mark");
        }
        replaceTextPattern(body, INLINE_MATH_PATTERN, "span class=\"language-math\"");
        if (options.fixTermTypo()) {
            transformText(body, MarkdownRenderer::fixTermCase);
        }
        if (options.autoSpace()) {
            transformText(body, MarkdownRenderer::addCjkSpacing);
        }
        if (options.paragraphBeginningSpace()) {
            body.children().stream()
                .filter(element -> "p".equals(element.tagName()))
                .forEach(element -> element.addClass("mdocs-indent-2"));
        }
        return body.html();
    }

    private static String normalizeMathBlocks(String markdown) {
        List<String> lines = Arrays.asList(markdown.split("\\R", -1));
        StringBuilder result = new StringBuilder(markdown.length() + 32);
        boolean inFence = false;
        String fence = null;
        boolean inMath = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inMath && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                String marker = trimmed.substring(0, 3);
                if (!inFence) {
                    inFence = true;
                    fence = marker;
                } else if (marker.equals(fence)) {
                    inFence = false;
                    fence = null;
                }
                result.append(line).append('\n');
                continue;
            }
            if (!inFence && !inMath && trimmed.startsWith("$$") && trimmed.endsWith("$$")
                && trimmed.length() > 4) {
                result.append("```math\n")
                    .append(trimmed, 2, trimmed.length() - 2).append("\n```\n");
                continue;
            }
            if (!inFence && "$$".equals(trimmed)) {
                result.append(inMath ? "```\n" : "```math\n");
                inMath = !inMath;
                continue;
            }
            result.append(line).append('\n');
        }
        if (inMath) {
            result.append("```\n");
        }
        return result.toString();
    }

    private static void replaceTextPattern(Element root, Pattern pattern, String tag) {
        for (TextNode textNode : List.copyOf(root.textNodes())) {
            replaceTextNode(textNode, pattern, tag);
        }
        for (Element child : root.children()) {
            if (!Set.of("code", "pre", "script", "style", "kbd").contains(child.tagName())) {
                replaceTextPattern(child, pattern, tag);
            }
        }
    }

    private static void replaceTextNode(TextNode node, Pattern pattern, String tag) {
        var matcher = pattern.matcher(node.getWholeText());
        if (!matcher.find()) {
            return;
        }
        matcher.reset();
        StringBuilder html = new StringBuilder();
        int offset = 0;
        while (matcher.find()) {
            html.append(Entities.escape(node.getWholeText().substring(offset, matcher.start())));
            html.append('<').append(tag).append('>')
                .append(Entities.escape(matcher.group(1)))
                .append("</").append(tag.substring(0, tag.indexOf(' ') > 0 ? tag.indexOf(' ') : tag.length()))
                .append('>');
            offset = matcher.end();
        }
        html.append(Entities.escape(node.getWholeText().substring(offset)));
        node.before(html.toString());
        node.remove();
    }

    private static void transformText(Element root, java.util.function.UnaryOperator<String> fn) {
        for (TextNode textNode : root.textNodes()) {
            textNode.text(fn.apply(textNode.getWholeText()));
        }
        for (Element child : root.children()) {
            if (!Set.of("code", "pre", "script", "style", "kbd").contains(child.tagName())) {
                transformText(child, fn);
            }
        }
    }

    private static String addCjkSpacing(String value) {
        String spaced = CJK_LATIN_PATTERN.matcher(value).replaceAll("$1 $2");
        return LATIN_CJK_PATTERN.matcher(spaced).replaceAll("$1 $2");
    }

    private static String fixTermCase(String value) {
        return TERM_PATTERN.matcher(value).replaceAll(match ->
            TERM_CASES.getOrDefault(match.group().toLowerCase(), match.group()));
    }

    public record RenderOptions(
        boolean autoSpace,
        boolean gfmAutoLink,
        boolean footnotes,
        boolean mark,
        boolean fixTermTypo,
        boolean paragraphBeginningSpace
    ) {
        public static RenderOptions defaults() {
            return new RenderOptions(false, true, true, false, false, false);
        }

        public static RenderOptions from(DocIndexSettings settings) {
            return new RenderOptions(
                Boolean.TRUE.equals(settings.getRenderAutoSpace()),
                !Boolean.FALSE.equals(settings.getRenderGfmAutoLink()),
                !Boolean.FALSE.equals(settings.getRenderFootnotes()),
                Boolean.TRUE.equals(settings.getRenderMark()),
                Boolean.TRUE.equals(settings.getRenderFixTermTypo()),
                Boolean.TRUE.equals(settings.getRenderParagraphBeginningSpace())
            );
        }
    }

    /**
     * 覆盖围栏代码块的默认渲染：把 info 串首个单词作为语言，输出
     * {@code <pre><code class="language-xxx">}，供前台客户端高亮识别。
     */
    private static class MarkdownHtmlNodeRenderer extends CoreHtmlNodeRenderer {

        private final HtmlWriter html;

        MarkdownHtmlNodeRenderer(HtmlNodeRendererContext context) {
            super(context);
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class, Image.class);
        }

        @Override
        public void visit(FencedCodeBlock block) {
            String info = block.getInfo();
            html.line();
            if (StringUtils.hasText(info) && "math".equalsIgnoreCase(info.trim().split("\\s+", 2)[0])) {
                html.tag("div", Map.of("class", "language-math"));
                html.text(block.getLiteral());
                html.tag("/div");
                html.line();
                return;
            }
            html.tag("pre");
            if (StringUtils.hasText(info)) {
                String language = info.trim().split("\\s+", 2)[0];
                html.tag("code", Map.of("class", "language-" + language));
            } else {
                html.tag("code");
            }
            html.text(block.getLiteral());
            html.tag("/code");
            html.tag("/pre");
            html.line();
        }

        @Override
        public void visit(Image image) {
            ImageRenderData renderData = parseImageRenderData(image.getDestination());
            String url = renderData.src();
            Map<String, String> attrs = new LinkedHashMap<>();
            if (context.shouldSanitizeUrls()) {
                url = context.urlSanitizer().sanitizeImageUrl(url);
            }

            attrs.put("src", context.encodeUrl(url));
            attrs.put("alt", getAltText(image));
            if (image.getTitle() != null) {
                attrs.put("title", image.getTitle());
            }

            String style = renderData.toInlineStyle();
            if (StringUtils.hasText(style)) {
                attrs.put("style", style);
            }

            html.tag("img", context.extendAttributes(image, "img", attrs), true);
        }
    }

    private record ImageRenderData(
        String src,
        Integer widthPercent,
        String align,
        String padding
    ) {

        String toInlineStyle() {
            List<String> styles = new ArrayList<>();
            if (widthPercent != null) {
                styles.add("width:" + widthPercent + "%");
            }
            if (StringUtils.hasText(padding)) {
                styles.add("padding:" + padding);
                styles.add("box-sizing:border-box");
            }
            if (StringUtils.hasText(align)) {
                styles.add("display:block");
                switch (align) {
                    case "center" -> {
                        styles.add("margin-left:auto");
                        styles.add("margin-right:auto");
                    }
                    case "right" -> {
                        styles.add("margin-left:auto");
                        styles.add("margin-right:0");
                    }
                    default -> {
                        styles.add("margin-left:0");
                        styles.add("margin-right:auto");
                    }
                }
            }
            return String.join(";", styles);
        }
    }

    private static ImageRenderData parseImageRenderData(String destination) {
        if (!StringUtils.hasText(destination)) {
            return new ImageRenderData("", null, null, null);
        }

        int hashIndex = destination.indexOf('#');
        if (hashIndex < 0) {
            return new ImageRenderData(destination, null, null, null);
        }

        String base = destination.substring(0, hashIndex);
        String fragment = destination.substring(hashIndex + 1);
        if (!StringUtils.hasText(fragment)) {
            return new ImageRenderData(base, null, null, null);
        }

        Integer width = null;
        String align = null;
        String padding = null;
        List<String> remaining = new ArrayList<>();
        for (String token : fragment.split("&")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }

            int equalsIndex = token.indexOf('=');
            String rawKey = equalsIndex >= 0 ? token.substring(0, equalsIndex) : token;
            String rawValue = equalsIndex >= 0 ? token.substring(equalsIndex + 1) : "";
            String key = decodeFragmentValue(rawKey);
            String value = decodeFragmentValue(rawValue);
            switch (key) {
                case IMAGE_WIDTH_PARAM -> width = parseWidthPercent(value);
                case IMAGE_ALIGN_PARAM -> align = parseAlign(value);
                case IMAGE_PADDING_PARAM -> padding = parsePadding(value);
                default -> remaining.add(token);
            }
        }

        String src = base;
        if (!remaining.isEmpty()) {
            src = src + "#" + String.join("&", remaining);
        }
        return new ImageRenderData(src, width, align, padding);
    }

    private static String decodeFragmentValue(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Integer parseWidthPercent(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            int width = Integer.parseInt(value.trim());
            return width >= 1 && width <= 100 ? width : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseAlign(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "left", "center", "right" -> normalized;
            default -> null;
        };
    }

    private static String parsePadding(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (!CSS_LENGTH_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        return normalized.matches("^\\d+(?:\\.\\d+)?$") ? normalized + "px" : normalized;
    }

    private static String getAltText(Image image) {
        AltTextVisitor visitor = new AltTextVisitor();
        image.accept(visitor);
        return visitor.getAltText();
    }

    private static class AltTextVisitor extends org.commonmark.node.AbstractVisitor {

        private final StringBuilder sb = new StringBuilder();

        String getAltText() {
            return sb.toString();
        }

        @Override
        public void visit(Text text) {
            sb.append(text.getLiteral());
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            sb.append('\n');
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            sb.append('\n');
        }
    }
}
