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
    void emptyInputReturnsEmptyString() {
        assertThat(renderer.render(null)).isEmpty();
        assertThat(renderer.render("   ")).isEmpty();
    }
}
