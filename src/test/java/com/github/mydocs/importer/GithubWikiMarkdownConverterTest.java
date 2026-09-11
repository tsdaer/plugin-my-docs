package com.github.mydocs.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mydocs.service.MarkdownRenderer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GithubWikiMarkdownConverterTest {

    final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void detectsSpecialFilesCaseInsensitively() {
        assertThat(GithubWikiMarkdownConverter.isSpecialFile("_Sidebar")).isTrue();
        assertThat(GithubWikiMarkdownConverter.isSpecialFile("_FOOTER")).isTrue();
        assertThat(GithubWikiMarkdownConverter.isSpecialFile("_header")).isTrue();
        assertThat(GithubWikiMarkdownConverter.isSpecialFile("Home")).isFalse();
        assertThat(GithubWikiMarkdownConverter.isSpecialFile(null)).isFalse();
    }

    @Test
    void slugifiesPageNames() {
        assertThat(GithubWikiMarkdownConverter.slugify("Getting-Started")).isEqualTo("getting-started");
        assertThat(GithubWikiMarkdownConverter.slugify("API Guide")).isEqualTo("api-guide");
        assertThat(GithubWikiMarkdownConverter.slugify("安装指南")).isEqualTo("安装指南");
        assertThat(GithubWikiMarkdownConverter.slugify("C++ 注意事项!")).isEqualTo("c-注意事项");
        assertThat(GithubWikiMarkdownConverter.slugify("  _what_.__is__  ")).isEqualTo("what-is");
        assertThat(GithubWikiMarkdownConverter.slugify("!!!")).isEqualTo("wiki-page");
        assertThat(GithubWikiMarkdownConverter.slugify(null)).isEqualTo("wiki-page");
    }

    @Test
    void slugIsTruncatedToHundredChars() {
        String longName = "a".repeat(150);
        assertThat(GithubWikiMarkdownConverter.slugify(longName)).hasSize(100);
    }

    @Test
    void anchorSlugMatchesCommonmarkHeadingAnchorOutput() {
        // 交叉验证：转换器生成的锚点必须与 MarkdownRenderer（commonmark heading-anchor 扩展）
        // 实际渲染出的标题 id 一致，否则 [[Page#Section]] 跳转会失效。
        var headings = Map.of(
            "Some Section", "some-section",
            "C++ 与 Java", "c-与-java",
            "What_is_this?", "what_is_this",
            "配置 详解", "配置-详解"
        );
        headings.forEach((headingText, expectedAnchor) -> {
            assertThat(GithubWikiMarkdownConverter.anchorSlug(headingText))
                .isEqualTo(expectedAnchor);
            String html = renderer.render("# " + headingText);
            assertThat(html).contains("id=\"" + expectedAnchor + "\"");
        });
    }

    @Test
    void convertsBasicWikiLink() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "See [[Getting-Started]] for details.",
            key -> "getting-started".equals(key) ? "getting-started" : null);
        assertThat(conversion.markdown())
            .isEqualTo("See [Getting-Started](./getting-started) for details.");
        assertThat(conversion.unresolvedLinks()).isZero();
    }

    @Test
    void convertsLinkWithDisplayTextAndAnchor() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "See [[Getting Started#Quick Start|快速开始]] here.",
            key -> "getting-started".equals(key) ? "getting-started" : null);
        assertThat(conversion.markdown())
            .isEqualTo("See [快速开始](./getting-started#quick-start) here.");
        assertThat(conversion.unresolvedLinks()).isZero();
    }

    @Test
    void treatsSpacesAndHyphensAsEquivalentPageKeys() {
        // Gollum 页面文件名以连字符替代空格，[[Page Name]] 与 Page-Name.md 应互相匹配。
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "[[Getting Started]] 与 [[getting-started]] 指向同一页。",
            key -> "getting-started".equals(key) ? "getting-started" : null);
        assertThat(conversion.markdown())
            .isEqualTo("[Getting Started](./getting-started) 与 [getting-started](./getting-started) 指向同一页。");
    }

    @Test
    void convertsExternalAutolink() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "[[https://example.com/docs]] and [[mailto:me@example.com|发邮件]]",
            key -> null);
        assertThat(conversion.markdown())
            .isEqualTo("[https://example.com/docs](https://example.com/docs) and [发邮件](mailto:me@example.com)");
        assertThat(conversion.unresolvedLinks()).isZero();
    }

    @Test
    void degradesUnresolvedLinkToPlainText() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "[[Missing Page]] and [[Missing|不存在的页面]]",
            key -> null);
        assertThat(conversion.markdown()).isEqualTo("Missing Page and 不存在的页面");
        assertThat(conversion.unresolvedLinks()).isEqualTo(2);
    }

    @Test
    void keepsLinksInsideFencedCodeBlocksUntouched() {
        String markdown = """
            前置 [[Home]]

            ```java
            String s = "[[Not A Link]]";
            ```

            后置 [[Home]]
            """;
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            markdown, key -> "home".equals(key) ? "home" : null);
        assertThat(conversion.markdown())
            .contains("前置 [Home](./home)")
            .contains("String s = \"[[Not A Link]]\";")
            .contains("后置 [Home](./home)");
    }

    @Test
    void keepsTildeFencedCodeBlocksUntouched() {
        String markdown = """
            ~~~text
            [[Raw]]
            ~~~
            [[Home]]
            """;
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            markdown, key -> "home".equals(key) ? "home" : null);
        assertThat(conversion.markdown()).contains("[[Raw]]");
        assertThat(conversion.markdown()).contains("[Home](./home)");
    }

    @Test
    void doesNotCloseFenceOnInlineTripleBackticks() {
        // ```code``` 是行内代码样式；按行内代码段切分后其中的 [[Home]] 不应被转换。
        String markdown = """
            使用 ```[[Home]]``` 标记

            之后 [[Home]] 仍应转换
            """;
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            markdown, key -> "home".equals(key) ? "home" : null);
        assertThat(conversion.markdown()).contains("之后 [Home](./home)");
        assertThat(conversion.markdown()).contains("使用 ```[[Home]]``` 标记");
    }

    @Test
    void keepsLinksInsideInlineCodeUntouched() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "输入 `[[Home]]` 命令",
            key -> "home".equals(key) ? "home" : null);
        assertThat(conversion.markdown()).isEqualTo("输入 `[[Home]]` 命令");
        assertThat(conversion.unresolvedLinks()).isZero();
    }

    @Test
    void escapesSquareBracketsInLabel() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "[[Home|[注意] 括号]]",
            key -> "home".equals(key) ? "home" : null);
        assertThat(conversion.markdown()).isEqualTo("[\\[注意\\] 括号](./home)");
    }

    @Test
    void linkWithEmptyAnchorFragmentOmitsHash() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "[[Home#]]",
            key -> "home".equals(key) ? "home" : null);
        assertThat(conversion.markdown()).isEqualTo("[Home](./home)");
    }

    @Test
    void preservesTrailingNewlineAndBlankLines() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "a\n\nb\n", key -> null);
        assertThat(conversion.markdown()).isEqualTo("a\n\nb\n");
    }

    @Test
    void convertsRelativeMdLinksCaseInsensitively() {
        // GitHub 编辑器生成的普通相对链接：目标大小写与文件名不一致时也能解析。
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "See [介绍](getting-started.md) and [指南](./API-Guide.md#tips).",
            key -> {
                if ("getting-started".equals(key)) {
                    return "getting-started";
                }
                return "api-guide".equals(key) ? "api-guide" : null;
            });
        assertThat(conversion.markdown())
            .isEqualTo("See [介绍](./getting-started) and [指南](./api-guide#tips).");
        assertThat(conversion.unresolvedLinks()).isZero();
    }

    @Test
    void keepsUnknownMdLinksAndExternalLinksUntouched() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "[缺失](Missing-Page.md)、[外链](https://example.com/a.md)、"
                + "[子目录](docs/sub/page.md)、[无扩展名](plain)",
            key -> null);
        assertThat(conversion.markdown())
            .isEqualTo("[缺失](Missing-Page.md)、[外链](https://example.com/a.md)、"
                + "[子目录](docs/sub/page.md)、[无扩展名](plain)");
    }

    @Test
    void keepsMdImagesUntouched() {
        var conversion = GithubWikiMarkdownConverter.convertLinks(
            "![截图](screenshot.md)",
            key -> "screenshot".equals(key) ? "screenshot" : null);
        assertThat(conversion.markdown()).isEqualTo("![截图](screenshot.md)");
    }
}
