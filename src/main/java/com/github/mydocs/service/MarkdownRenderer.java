package com.github.mydocs.service;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.CoreHtmlNodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

    private final Parser parser;
    private final HtmlRenderer htmlRenderer;

    public MarkdownRenderer() {
        List<Extension> extensions = List.of(
            TablesExtension.create(),
            TaskListItemsExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create(),
            HeadingAnchorExtension.create()
        );
        this.parser = Parser.builder()
            .extensions(extensions)
            .build();
        this.htmlRenderer = HtmlRenderer.builder()
            .extensions(extensions)
            .nodeRendererFactory(LanguageClassCodeBlockRenderer::new)
            .build();
    }

    /**
     * 渲染 Markdown 为 HTML。{@code null} 或空白输入返回空字符串，保证结果非 null。
     *
     * @param markdown Markdown 原文
     * @return 渲染后的 HTML
     */
    public String render(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return "";
        }
        Node document = parser.parse(markdown);
        return htmlRenderer.render(document);
    }

    /**
     * 覆盖围栏代码块的默认渲染：把 info 串首个单词作为语言，输出
     * {@code <pre><code class="language-xxx">}，供前台客户端高亮识别。
     */
    private static class LanguageClassCodeBlockRenderer extends CoreHtmlNodeRenderer {

        private final HtmlWriter html;

        LanguageClassCodeBlockRenderer(HtmlNodeRendererContext context) {
            super(context);
            this.html = context.getWriter();
        }

        @Override
        public java.util.Set<Class<? extends Node>> getNodeTypes() {
            return java.util.Set.of(FencedCodeBlock.class);
        }

        @Override
        public void visit(FencedCodeBlock block) {
            String info = block.getInfo();
            html.line();
            html.tag("pre");
            if (StringUtils.hasText(info)) {
                String language = info.trim().split("\\s+", 2)[0];
                html.tag("code",
                    java.util.Map.of("class", "language-" + language));
            } else {
                html.tag("code");
            }
            html.text(block.getLiteral());
            html.tag("/code");
            html.tag("/pre");
            html.line();
        }
    }
}
