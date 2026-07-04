package com.github.mydocs.reconciler;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.search.DocSearchDocumentConverter;
import com.github.mydocs.service.MarkdownRenderer;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.search.event.HaloDocumentAddRequestEvent;
import run.halo.app.search.event.HaloDocumentDeleteRequestEvent;

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
    private final DocSearchDocumentConverter searchDocumentConverter;
    private final ApplicationEventPublisher eventPublisher;

    public DocReconciler(ExtensionClient client, MarkdownRenderer markdownRenderer,
        DocSearchDocumentConverter searchDocumentConverter,
        ApplicationEventPublisher eventPublisher) {
        this.client = client;
        this.markdownRenderer = markdownRenderer;
        this.searchDocumentConverter = searchDocumentConverter;
        this.eventPublisher = eventPublisher;
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
            syncSearchIndex(doc);
        });
        return Result.doNotRetry();
    }

    private void syncSearchIndex(Doc doc) {
        var metadataName = searchDocumentConverter.metadataName(doc);
        if (!StringUtils.hasText(metadataName)) {
            return;
        }
        if (!searchDocumentConverter.isIndexable(doc)) {
            publishDelete(metadataName);
            return;
        }
        client.fetch(DocLibrary.class, doc.getSpec().getLibraryName())
            .filter(library -> library.getMetadata().getDeletionTimestamp() == null)
            .filter(library -> library.getSpec() != null)
            .map(library -> library.getSpec().getSlug())
            .filter(StringUtils::hasText)
            .ifPresentOrElse(
                librarySlug -> eventPublisher.publishEvent(new HaloDocumentAddRequestEvent(
                    this,
                    List.of(searchDocumentConverter.toHaloDocument(doc, librarySlug))
                )),
                () -> publishDelete(metadataName)
            );
    }

    private void publishDelete(String metadataName) {
        eventPublisher.publishEvent(new HaloDocumentDeleteRequestEvent(
            this,
            List.of(searchDocumentConverter.documentId(metadataName))
        ));
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Doc())
            .build();
    }
}
