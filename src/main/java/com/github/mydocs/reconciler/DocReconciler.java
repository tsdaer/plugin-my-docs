package com.github.mydocs.reconciler;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.service.MarkdownRenderer;
import java.util.Objects;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/**
 * <p>监听 {@link Doc} 变更，将其 Markdown 原文（{@code spec.raw}）渲染为 HTML
 * 写入 {@code spec.content}。</p>
 * <p>渲染是确定性的：仅当重新渲染的结果与已存储的 {@code content} 不同才调用
 * {@code update}，因此重复 reconcile 会自然收敛，不会形成更新死循环。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class DocReconciler implements Reconciler<Reconciler.Request> {

    private final ExtensionClient client;
    private final MarkdownRenderer markdownRenderer;

    public DocReconciler(ExtensionClient client, MarkdownRenderer markdownRenderer) {
        this.client = client;
        this.markdownRenderer = markdownRenderer;
    }

    @Override
    public Result reconcile(Request request) {
        client.fetch(Doc.class, request.name()).ifPresent(doc -> {
            var spec = doc.getSpec();
            if (spec == null) {
                return;
            }
            String rendered = markdownRenderer.render(spec.getRaw());
            if (!Objects.equals(rendered, spec.getContent())) {
                spec.setContent(rendered);
                client.update(doc);
            }
        });
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Doc())
            .build();
    }
}
