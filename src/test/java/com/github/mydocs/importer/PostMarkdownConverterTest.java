package com.github.mydocs.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import run.halo.app.content.ContentWrapper;

class PostMarkdownConverterTest {
    private final PostMarkdownConverter converter = new PostMarkdownConverter();
    private static final String URL = "https://example.com/archives/article";

    private ContentWrapper content(String type, String raw, String html) {
        return ContentWrapper.builder().rawType(type).raw(raw).content(html).build();
    }

    @Test
    void preservesMarkdownFormattingAndAbsoluteLinks() {
        String raw = "# 标题\n\n* *强调*\n\n![图](https://cdn.example.com/a.png)\n\n```md\n[x](../keep)\n```\n";
        assertThat(converter.convert(content("markdown", raw, "unused"), URL).markdown()).isEqualTo(raw);
    }

    @Test
    void resolvesInlineReferenceHtmlAndAnchorUrlsWithoutTouchingCode() {
        String raw = "[下一篇](../other?x=1#part) ![图](./a.png) [章节](#intro)\n\n"
            + "[参考][ref]\n\n[ref]: ../guide \"guide\"\n\n"
            + "<video src=\"./movie.mp4\" poster=\"./poster.jpg\"></video>\n\n"
            + "`[示例](../code)`";
        String result = converter.convert(content("markdown", raw, null), URL).markdown();
        assertThat(result).contains("https://example.com/other?x=1#part", "https://example.com/archives/a.png",
            "https://example.com/archives/article#intro", "https://example.com/guide",
            "https://example.com/archives/movie.mp4", "https://example.com/archives/poster.jpg",
            "`[示例](../code)`");
    }

    @Test
    void convertsCommonRichTextStructures() {
        String html = "<h2>标题</h2><p><strong>重点</strong> <em>强调</em></p>"
            + "<blockquote>引用</blockquote><ul><li>项目</li></ul>"
            + "<pre><code class=\"language-java\">int x = 1;</code></pre>"
            + "<table><thead><tr><th>A</th><th>B</th></tr></thead>"
            + "<tbody><tr><td>1</td><td>2</td></tr></tbody></table>"
            + "<p><img src=\"../image.png\"><a href=\"../file.zip\">附件</a></p>";
        var result = converter.convert(content("html", html, null), URL);
        assertThat(result.markdown()).contains("## 标题", "**重点**", "*强调*", "> 引用",
            "项目", "int x = 1;", "|", "https://example.com/image.png", "https://example.com/file.zip");
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void doesNotExposeHtmlIdsAsMarkdownAttributeSuffixes() {
        String html = "<h1 id=\"%E9%9A%8F%E8%AE%B0\">随记</h1>"
            + "<h2 id=\"section-2\">第二节</h2>"
            + "<p id=\"paragraph-3\"><span id=\"inline-4\">正文</span></p>"
            + "<p><a href=\"https://example.com/guide#section-2\">链接</a></p>";
        String markdown = converter.convert(content("html", html, null), URL).markdown();
        assertThat(markdown).contains("# 随记", "## 第二节", "正文",
                "[链接](https://example.com/guide#section-2)")
            .doesNotContain("{#", "%E9%9A%8F%E8%AE%B0", "paragraph-3", "inline-4");
    }

    @Test
    void doesNotRemoveLiteralAttributeSyntaxFromOriginalMarkdownOrCode() {
        String raw = "# 示例 {#my-anchor}\n\n正文中的 `{#%E9%9A%8F%E8%AE%B0}`\n\n"
            + "```text\n{#keep-this}\n```\n";
        assertThat(converter.convert(content("markdown", raw, null), URL).markdown()).isEqualTo(raw);
        String html = "<pre><code>{#keep-this}</code></pre><p>正文中的 {#literal}</p>";
        assertThat(converter.convert(content("html", html, null), URL).markdown())
            .contains("{#keep-this}", "{#literal}");
    }

    @Test
    void omitsEditorIdsWhenConvertingRenderedHtmlFromOtherFormats() {
        String html = "<h2 id=\"%E9%9A%8F%E8%AE%B0\">随记</h2>";
        assertThat(converter.convert(content("json", "{editor state}", html), URL).markdown())
            .contains("## 随记").doesNotContain("{#", "%E9%9A%8F%E8%AE%B0");
    }

    @Test
    void preservesInlineHtmlTagBoundariesWhenResolvingUrls() {
        String raw = "Before <a class=\"link\" href='../guide?x=1&amp;y=2'>link</a> after.\n\n"
            + "Inline <img data-src=\"../lazy.png\" src=./image.png> image.";
        String result = converter.convert(content("markdown", raw, null), URL).markdown();
        assertThat(result).contains("href=\"https://example.com/guide?x=1&amp;y=2\">link</a> after.",
            "data-src=\"../lazy.png\"", "src=\"https://example.com/archives/image.png\"");
        assertThat(result).doesNotContain("></a>link");
    }

    @Test
    void retainsComplexComponentsAndMediaAsHtml() {
        String html = "<halo-custom data-id=\"1\"><p>组件</p></halo-custom>"
            + "<video src=\"./a.mp4\" controls></video>"
            + "<table><tr><td colspan=\"2\">合并</td></tr></table>";
        var result = converter.convert(content("html", html, null), URL);
        assertThat(result.markdown()).contains("<halo-custom", "组件", "<video", "<table", "colspan=\"2\"");
        assertThat(result.warnings()).anyMatch(message -> message.contains("复杂组件"));
    }

    @Test
    void keepsAllComplexFragmentsWhenThereAreMoreThanTen() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 15; i++) html.append("<halo-test>component-").append(i).append("</halo-test>");
        String markdown = converter.convert(content("html", html.toString(), null), URL).markdown();
        for (int i = 0; i < 15; i++) assertThat(markdown).contains("component-" + i + "</halo-test>");
        assertThat(markdown).doesNotContain("MDOCS");
    }

    @Test
    void convertsRenderedHtmlForOtherEditorFormats() {
        var result = converter.convert(content("json", "{editor state}", "<h1>标题</h1>"), URL);
        assertThat(result.markdown()).contains("# 标题").doesNotContain("editor state");
    }

    @Test
    void rejectsMissingAndEmptyContent() {
        assertThatThrownBy(() -> converter.convert(content("json", "{}", null), URL))
            .hasMessageContaining("没有可转换");
        assertThatThrownBy(() -> converter.convert(content("markdown", " ", null), URL))
            .hasMessageContaining("没有可转换");
    }
}
