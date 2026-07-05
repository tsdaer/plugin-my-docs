package com.github.mydocs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.mydocs.extension.Doc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class DocServiceImplTest {

    @Mock
    ReactiveExtensionClient client;

    final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    @Test
    void createPersistsRenderedContentTogetherWithRaw() {
        var doc = doc("doc-1", "# Hello");
        when(client.listAll(any(), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(Doc.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var service = new DocServiceImpl(client, markdownRenderer);
        Doc created = service.create(doc).block();

        var captor = ArgumentCaptor.forClass(Doc.class);
        verify(client).create(captor.capture());
        assertThat(captor.getValue().getSpec().getContent()).contains("<h1").contains("Hello");
        assertThat(created.getSpec().getContent()).contains("<h1").contains("Hello");
    }

    @Test
    void updatePersistsRenderedContentTogetherWithRaw() {
        var doc = doc("doc-1", "Hello **world**");
        when(client.listAll(any(), any(), any())).thenReturn(Flux.empty());
        when(client.update(any(Doc.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var service = new DocServiceImpl(client, markdownRenderer);
        Doc updated = service.update(doc).block();

        var captor = ArgumentCaptor.forClass(Doc.class);
        verify(client).update(captor.capture());
        assertThat(captor.getValue().getSpec().getContent()).contains("<strong>world</strong>");
        assertThat(updated.getSpec().getContent()).contains("<strong>world</strong>");
    }

    private static Doc doc(String name, String raw) {
        var doc = new Doc();
        var metadata = new Metadata();
        metadata.setName(name);
        doc.setMetadata(metadata);

        var spec = new Doc.Spec();
        spec.setTitle("Title");
        spec.setSlug("title");
        spec.setLibraryName("lib");
        spec.setRaw(raw);
        spec.setRawType("markdown");
        doc.setSpec(spec);
        return doc;
    }
}
