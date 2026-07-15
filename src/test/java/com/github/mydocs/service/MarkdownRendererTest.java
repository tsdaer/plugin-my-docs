package com.github.mydocs.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownRendererTest {

    final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersBasicMarkdown() {
        String html = renderer.render("# Title\n\nHello **world**");
        assertThat(html).contains("<h1").contains("Title");
        assertThat(html).contains("<strong>world</strong>");
    }

    @Test
    void rendersTable() {
        String markdown = """
            | a | b |
            | - | - |
            | 1 | 2 |
            """;
        String html = renderer.render(markdown);
        assertThat(html).contains("<table>").contains("<td>1</td>");
    }

    @Test
    void rendersTaskList() {
        String html = renderer.render("- [x] done\n- [ ] todo");
        assertThat(html).contains("type=\"checkbox\"");
        assertThat(html).contains("checked");
    }

    @Test
    void fencedCodeBlockGetsLanguageClass() {
        String html = renderer.render("```java\nint x = 1;\n```");
        assertThat(html).contains("<code class=\"language-java\">");
    }

    @Test
    void imageFragmentParamsBecomeSafeInlineStyles() {
        String html = renderer.render("""
            ![Hero](https://cdn.example.com/hero.png#md-width=50&md-align=center&md-pad=16)
            """);
        assertThat(html).contains("src=\"https://cdn.example.com/hero.png\"");
        assertThat(html).contains("alt=\"Hero\"");
        assertThat(html).contains("style=\"width:50%;padding:16px;box-sizing:border-box;display:block;margin-left:auto;margin-right:auto\"");
    }

    @Test
    void nonMydocsFragmentPartsArePreserved() {
        String html = renderer.render("""
            ![Hero](https://cdn.example.com/hero.png#preview&md-align=right)
            """);
        assertThat(html).contains("src=\"https://cdn.example.com/hero.png#preview\"");
        assertThat(html).contains("margin-left:auto");
        assertThat(html).contains("margin-right:0");
    }

    @Test
    void emptyInputReturnsEmptyString() {
        assertThat(renderer.render(null)).isEmpty();
        assertThat(renderer.render("   ")).isEmpty();
    }

    @Test
    void appliesOptionalMarkdownSyntaxSettings() {
        var enabled = new MarkdownRenderer.RenderOptions(false, true, true, true, false, false);
        String html = renderer.render("Visit https://example.com and ==mark==.\n\n[^1]: note\n\nref[^1]",
            enabled);

        assertThat(html)
            .contains("href=\"https://example.com\"")
            .contains("<mark>mark</mark>")
            .contains("footnote");

        var disabled = new MarkdownRenderer.RenderOptions(false, false, false, false, false, false);
        String plain = renderer.render("Visit https://example.com and ==mark==.", disabled);
        assertThat(plain)
            .doesNotContain("href=\"https://example.com\"")
            .contains("==mark==");
    }

    @Test
    void appliesTypographyWithoutChangingCode() {
        var options = new MarkdownRenderer.RenderOptions(true, true, true, false, true, true);
        String html = renderer.render("中文github测试\n\n`github中文`", options);

        assertThat(html)
            .contains("<p class=\"mdocs-indent-2\">中文 GitHub 测试</p>")
            .contains("<code>github中文</code>");
    }

    @Test
    void emitsMathElementsForFrontendEnhancement() {
        String html = renderer.render("Inline $a^2$ formula.\n\n$$\nb^2\n$$");

        assertThat(html)
            .contains("<span class=\"language-math\">a^2</span>")
            .contains("<div class=\"language-math\">b^2");
    }

    @Test
    void preservesComplexBlockAndColoredInlineMath() {
        String html = renderer.render("""
            $$
            \\frac{1}{
              \\Bigl(\\sqrt{\\phi \\sqrt{5}}-\\phi\\Bigr) e^{
              \\frac25 \\pi}} = 1+\\frac{e^{-2\\pi}} {1+\\frac{e^{-4\\pi}} {
                1+\\frac{e^{-6\\pi}}
                {1+\\frac{e^{-8\\pi}}{1+\\cdots}}
              }
            }
            $$

            或者$a^2 + b^2 = \\color{red}c^2$
            """);

        assertThat(html)
            .contains("<div class=\"language-math\">")
            .contains("\\Bigl(\\sqrt{\\phi \\sqrt{5}}-\\phi\\Bigr)")
            .contains("<span class=\"language-math\">a^2 + b^2 = \\color{red}c^2</span>");
    }
}
