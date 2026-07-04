package com.github.mydocs.reconciler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.search.DocSearchDocumentConverter;
import com.github.mydocs.service.MarkdownRenderer;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Reconciler;

@ExtendWith(MockitoExtension.class)
class DocReconcilerTest {

    @Mock
    ExtensionClient client;

    @Mock
    ApplicationEventPublisher eventPublisher;

    // 用真实渲染器，验证渲染结果确实写入 content
    final MarkdownRenderer markdownRenderer = new MarkdownRenderer();
    final DocSearchDocumentConverter searchDocumentConverter = new DocSearchDocumentConverter();

    DocReconciler newReconciler() {
        return new DocReconciler(client, markdownRenderer, searchDocumentConverter, eventPublisher);
    }

    private static Doc docWith(String raw, String content) {
        var doc = new Doc();
        var metadata = new Metadata();
        metadata.setName("doc-1");
        doc.setMetadata(metadata);
        var spec = new Doc.Spec();
        spec.setTitle("t");
        spec.setSlug("s");
        spec.setLibraryName("lib");
        spec.setRaw(raw);
        spec.setContent(content);
        doc.setSpec(spec);
        return doc;
    }

    @Test
    void rendersRawIntoContentWhenChanged() {
        var doc = docWith("# Hello", null);
        when(client.fetch(Doc.class, "doc-1")).thenReturn(Optional.of(doc));

        newReconciler().reconcile(new Reconciler.Request("doc-1"));

        var captor = ArgumentCaptor.forClass(Doc.class);
        verify(client).update(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getSpec().getContent())
            .contains("<h1").contains("Hello");
    }

    @Test
    void skipsUpdateWhenContentAlreadyMatches() {
        String rendered = markdownRenderer.render("# Hello");
        var doc = docWith("# Hello", rendered);
        when(client.fetch(Doc.class, "doc-1")).thenReturn(Optional.of(doc));

        newReconciler().reconcile(new Reconciler.Request("doc-1"));

        verify(client, never()).update(any());
    }

    @Test
    void publishesSearchAddEventForPublishedDoc() {
        var doc = docWith("# Hello", markdownRenderer.render("# Hello"));
        doc.getSpec().setPublished(true);
        when(client.fetch(Doc.class, "doc-1")).thenReturn(Optional.of(doc));
        when(client.fetch(DocLibrary.class, "lib")).thenReturn(Optional.of(library("lib", "guide")));

        newReconciler().reconcile(new Reconciler.Request("doc-1"));

        verify(eventPublisher).publishEvent(
            any(run.halo.app.search.event.HaloDocumentAddRequestEvent.class));
    }

    @Test
    void doesNothingWhenDocNotFound() {
        when(client.fetch(Doc.class, "missing")).thenReturn(Optional.empty());

        newReconciler().reconcile(new Reconciler.Request("missing"));

        verify(client, never()).update(any());
    }

    private static DocLibrary library(String name, String slug) {
        var library = new DocLibrary();
        var metadata = new Metadata();
        metadata.setName(name);
        library.setMetadata(metadata);
        var spec = new DocLibrary.Spec();
        spec.setTitle("Guide");
        spec.setSlug(slug);
        library.setSpec(spec);
        return library;
    }
}
