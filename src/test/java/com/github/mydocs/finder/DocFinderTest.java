package com.github.mydocs.finder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
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
class DocFinderTest {

    @Mock
    ReactiveExtensionClient client;

    @Test
    void listsLibrariesByPriority() {
        when(client.listAll(eq(DocLibrary.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(
                library("lib-b", "b", "B", 20),
                library("lib-a", "a", "A", 10)
            ));

        var libraries = new DocFinder(client).listLibraries().block();

        assertThat(libraries).extracting(library -> library.getMetadata().getName())
            .containsExactly("lib-a", "lib-b");
    }

    @Test
    void buildsTreeWithPublishedDocsOnly() {
        when(client.listAll(eq(Doc.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(
                doc("child", "child", "Child", "lib", "root", 20, true),
                doc("draft", "draft", "Draft", "lib", "root", 30, false),
                doc("root", "root", "Root", "lib", null, 10, true)
            ));

        var tree = new DocFinder(client).tree("lib").block();

        assertThat(tree).hasSize(1);
        assertThat(tree.getFirst().getDoc().getMetadata().getName()).isEqualTo("root");
        assertThat(tree.getFirst().getChildren()).hasSize(1);
        assertThat(tree.getFirst().getChildren().getFirst().getDoc().getMetadata().getName())
            .isEqualTo("child");
    }

    @Test
    void resolvesPublishedDocByLibraryAndDocSlug() {
        when(client.listAll(eq(DocLibrary.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(library("lib-1", "guide", "Guide", 10)));
        when(client.listAll(eq(Doc.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.just(doc("doc-1", "intro", "Intro", "lib-1", null, 10, true)));

        var doc = new DocFinder(client).getPublishedDocBySlugs("guide", "intro").block();

        assertThat(doc).isNotNull();
        assertThat(doc.getMetadata().getName()).isEqualTo("doc-1");
    }

    private static DocLibrary library(String name, String slug, String title, Integer priority) {
        var library = new DocLibrary();
        var metadata = new Metadata();
        metadata.setName(name);
        library.setMetadata(metadata);
        var spec = new DocLibrary.Spec();
        spec.setSlug(slug);
        spec.setTitle(title);
        spec.setPriority(priority);
        library.setSpec(spec);
        return library;
    }

    private static Doc doc(String name, String slug, String title, String libraryName,
        String parent, Integer priority, boolean published) {
        var doc = new Doc();
        var metadata = new Metadata();
        metadata.setName(name);
        doc.setMetadata(metadata);
        var spec = new Doc.Spec();
        spec.setSlug(slug);
        spec.setTitle(title);
        spec.setLibraryName(libraryName);
        spec.setParent(parent);
        spec.setPriority(priority);
        spec.setPublished(published);
        doc.setSpec(spec);
        return doc;
    }
}
