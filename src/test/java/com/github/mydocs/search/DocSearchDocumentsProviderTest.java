package com.github.mydocs.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class DocSearchDocumentsProviderTest {

    @Mock
    ReactiveExtensionClient client;

    final DocSearchDocumentConverter converter = new DocSearchDocumentConverter();

    @Test
    void fetchAllReturnsPublishedDocsWithPublicPermalinks() {
        when(client.listAll(eq(DocLibrary.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(library("lib-1", "guide", false)));
        when(client.listAll(eq(Doc.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(
                doc("doc-1", "intro", "Intro", "lib-1", true, false),
                doc("draft", "draft", "Draft", "lib-1", false, false),
                doc("deleted", "deleted", "Deleted", "lib-1", true, true),
                doc("orphan", "orphan", "Orphan", "missing", true, false)
            ));

        var documents = new DocSearchDocumentsProvider(client, converter).fetchAll()
            .collectList()
            .block();

        assertThat(documents).hasSize(1);
        var document = documents.getFirst();
        assertThat(document.getId()).isEqualTo("doc.docs.halo.run/doc-1");
        assertThat(document.getMetadataName()).isEqualTo("doc-1");
        assertThat(document.getTitle()).isEqualTo("Intro");
        assertThat(document.getContent()).isEqualTo("Hello search");
        assertThat(document.getDescription()).isEqualTo("Hello search");
        assertThat(document.getPermalink()).isEqualTo("/docs/guide/intro");
        assertThat(document.getType()).isEqualTo(DocSearchDocumentsProvider.TYPE);
        assertThat(document.isPublished()).isTrue();
        assertThat(document.isExposed()).isTrue();
        assertThat(document.isRecycled()).isFalse();
    }

    @Test
    void toHaloDocumentFallsBackToRawContent() {
        var doc = doc("doc-1", "intro", "Intro", "lib-1", true, false);
        doc.getSpec().setContent(null);
        doc.getSpec().setRaw("# Hello raw");

        var document = converter.toHaloDocument(doc, "guide");

        assertThat(document.getContent()).isEqualTo("# Hello raw");
    }

    private static DocLibrary library(String name, String slug, boolean deleted) {
        var library = new DocLibrary();
        var metadata = new Metadata();
        metadata.setName(name);
        if (deleted) {
            metadata.setDeletionTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        }
        library.setMetadata(metadata);
        var spec = new DocLibrary.Spec();
        spec.setSlug(slug);
        spec.setTitle("Guide");
        library.setSpec(spec);
        return library;
    }

    private static Doc doc(String name, String slug, String title, String libraryName,
        boolean published, boolean deleted) {
        var doc = new Doc();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        if (deleted) {
            metadata.setDeletionTimestamp(Instant.parse("2026-01-02T00:00:00Z"));
        }
        doc.setMetadata(metadata);
        var spec = new Doc.Spec();
        spec.setSlug(slug);
        spec.setTitle(title);
        spec.setLibraryName(libraryName);
        spec.setPublished(published);
        spec.setContent("<h1>Hello</h1><p>search</p>");
        doc.setSpec(spec);
        return doc;
    }
}
