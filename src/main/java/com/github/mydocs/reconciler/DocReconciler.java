package com.github.mydocs.reconciler;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.search.DocSearchDocumentConverter;
import com.github.mydocs.service.MarkdownRenderer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;
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
 * 写入 {@code spec.content}，并同步 Halo 搜索索引。</p>
 * <p>渲染是确定性的：仅当重新渲染的结果与已存储的 {@code content} 不同才调用
 * {@code update}，因此重复 reconcile 会自然收敛，不会形成更新死循环。</p>
 * <p>搜索索引同样只在状态真正变化时同步：以注解 {@value #SEARCH_SYNC_ANNOTATION}
 * 记录上次索引时的状态指纹（发布状态 + 所属库 slug + 正文哈希），一致则跳过，
 * 避免插件重启后的全量 resync 反复重建索引。文档被删除（fetch 不到）时发布删除事件，
 * 清理索引残留。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class DocReconciler implements Reconciler<Reconciler.Request> {

    static final String SEARCH_SYNC_ANNOTATION = "my-docs.tsdaer.run/search-sync";

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
        client.fetch(Doc.class, request.name()).ifPresentOrElse(
            doc -> reconcileDoc(doc),
            () -> publishDelete(request.name())
        );
        return Result.doNotRetry();
    }

    private void reconcileDoc(Doc doc) {
        var spec = doc.getSpec();
        if (spec == null) {
            return;
        }
        String rendered = markdownRenderer.render(spec.getRaw());
        var decision = decideSearchSync(doc, rendered);

        // 内容与同步注解合并为一次 update，避免同一 reconcile 内二次写引发版本冲突。
        boolean contentChanged = !Objects.equals(rendered, spec.getContent());
        if (contentChanged) {
            spec.setContent(rendered);
        }
        boolean annotationChanged = false;
        if (decision.action() == SearchSyncAction.ADD) {
            annotate(doc, decision.signature());
            annotationChanged = true;
        } else if (decision.action() == SearchSyncAction.DELETE) {
            clearAnnotation(doc);
            annotationChanged = true;
        }
        if (contentChanged || annotationChanged) {
            client.update(doc);
        }

        // 事件在持久化之后发布，保证索引侧读取到的是已落库状态。
        if (decision.action() == SearchSyncAction.ADD) {
            eventPublisher.publishEvent(new HaloDocumentAddRequestEvent(this,
                List.of(searchDocumentConverter.toHaloDocument(doc, decision.librarySlug()))));
        } else if (decision.action() == SearchSyncAction.DELETE) {
            publishDelete(searchDocumentConverter.metadataName(doc));
        }
    }

    private SearchSyncDecision decideSearchSync(Doc doc, String effectiveContent) {
        if (!searchDocumentConverter.isIndexable(doc)) {
            return hasSyncAnnotation(doc)
                ? SearchSyncDecision.delete() : SearchSyncDecision.none();
        }
        String librarySlug = resolveLibrarySlug(doc.getSpec().getLibraryName());
        if (librarySlug == null) {
            return hasSyncAnnotation(doc)
                ? SearchSyncDecision.delete() : SearchSyncDecision.none();
        }
        String signature = searchSyncSignature(doc, librarySlug, effectiveContent);
        if (signature.equals(syncAnnotation(doc))) {
            return SearchSyncDecision.none();
        }
        return SearchSyncDecision.add(signature, librarySlug);
    }

    private String resolveLibrarySlug(String libraryName) {
        return client.fetch(DocLibrary.class, libraryName)
            .filter(library -> library.getMetadata().getDeletionTimestamp() == null)
            .filter(library -> library.getSpec() != null)
            .map(library -> library.getSpec().getSlug())
            .filter(StringUtils::hasText)
            .orElse(null);
    }

    private String searchSyncSignature(Doc doc, String librarySlug, String content) {
        String basis = Boolean.TRUE.equals(doc.getSpec().getPublished())
            + "|" + librarySlug + "|" + content;
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 算法", e);
        }
    }

    private boolean hasSyncAnnotation(Doc doc) {
        return syncAnnotation(doc) != null;
    }

    private String syncAnnotation(Doc doc) {
        Map<String, String> annotations =
            doc.getMetadata() == null ? null : doc.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(SEARCH_SYNC_ANNOTATION);
    }

    private void annotate(Doc doc, String signature) {
        var metadata = doc.getMetadata();
        var annotations = metadata.getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
            metadata.setAnnotations(annotations);
        }
        annotations.put(SEARCH_SYNC_ANNOTATION, signature);
    }

    private void clearAnnotation(Doc doc) {
        var annotations = doc.getMetadata().getAnnotations();
        if (annotations != null) {
            annotations.remove(SEARCH_SYNC_ANNOTATION);
        }
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

    private enum SearchSyncAction {
        NONE, ADD, DELETE
    }

    private record SearchSyncDecision(SearchSyncAction action, String signature,
        String librarySlug) {

        static SearchSyncDecision none() {
            return new SearchSyncDecision(SearchSyncAction.NONE, null, null);
        }

        static SearchSyncDecision delete() {
            return new SearchSyncDecision(SearchSyncAction.DELETE, null, null);
        }

        static SearchSyncDecision add(String signature, String librarySlug) {
            return new SearchSyncDecision(SearchSyncAction.ADD, signature, librarySlug);
        }
    }
}
