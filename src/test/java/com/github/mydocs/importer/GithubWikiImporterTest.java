package com.github.mydocs.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class GithubWikiImporterTest {

    @Mock
    ReactiveExtensionClient client;

    private GithubWikiImporter importer() {
        return new GithubWikiImporter(client);
    }

    @Test
    void createsNewLibraryAndDocsInWikiOrder() {
        when(client.listAll(eq(DocLibrary.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(DocLibrary.class))).thenAnswer(invocation -> {
            DocLibrary library = invocation.getArgument(0);
            library.getMetadata().setName("doc-library-abc");
            return Mono.just(library);
        });
        when(client.listAll(eq(Doc.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(Doc.class))).thenAnswer(invocation -> {
            Doc doc = invocation.getArgument(0);
            doc.getMetadata().setName(doc.getMetadata().getGenerateName() + "x");
            return Mono.just(doc);
        });

        Map<String, String> files = new LinkedHashMap<>();
        files.put("A-Page", "# A\n\nsee [[Home]] and [[Missing]].");
        files.put("_Sidebar", "* nav");
        files.put("Home", "Start at [[A-Page]].");
        files.put("Z-Last", "The end.");

        var report = importer().importDocs(files, 0,
            new GithubWikiImportRequest(null, "My Wiki", null, true)).block();

        assertThat(report).isNotNull();
        assertThat(report.libraryName()).isEqualTo("doc-library-abc");
        assertThat(report.librarySlug()).isEqualTo("my-wiki");
        assertThat(report.imported()).isEqualTo(3);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.slugAdjusted()).isZero();
        assertThat(report.unresolvedLinks()).isEqualTo(1);

        var libraryCaptor = ArgumentCaptor.forClass(DocLibrary.class);
        verify(client).create(libraryCaptor.capture());
        assertThat(libraryCaptor.getValue().getSpec().getTitle()).isEqualTo("My Wiki");
        assertThat(libraryCaptor.getValue().getSpec().getSlug()).isEqualTo("my-wiki");

        var docs = capturedDocs();
        assertThat(docs).extracting(doc -> doc.getSpec().getTitle())
            .containsExactly("Home", "A-Page", "Z-Last");
        assertThat(docs).extracting(doc -> doc.getSpec().getSlug())
            .containsExactly("home", "a-page", "z-last");
        assertThat(docs).extracting(doc -> doc.getSpec().getPriority())
            .containsExactly(0, 1, 2);
        assertThat(docs).allSatisfy(doc -> {
            assertThat(doc.getSpec().getLibraryName()).isEqualTo("doc-library-abc");
            assertThat(doc.getSpec().getRawType()).isEqualTo("markdown");
            assertThat(doc.getSpec().getPublished()).isTrue();
            assertThat(doc.getSpec().getPublishTime()).isNotNull();
        });
        assertThat(docs.get(0).getSpec().getRaw()).isEqualTo("Start at [A-Page](./a-page).");
        assertThat(docs.get(1).getSpec().getRaw())
            .isEqualTo("# A\n\nsee [Home](./home) and Missing.");
    }

    @Test
    void importIntoExistingLibraryDeduplicatesSlugsAndContinuesPriority() {
        var library = new DocLibrary();
        var metadata = new Metadata();
        metadata.setName("lib-1");
        library.setMetadata(metadata);
        var spec = new DocLibrary.Spec();
        spec.setTitle("已有库");
        spec.setSlug("lib-slug");
        library.setSpec(spec);
        when(client.fetch(DocLibrary.class, "lib-1")).thenReturn(Mono.just(library));

        var existingDoc = new Doc();
        var existingMetadata = new Metadata();
        existingMetadata.setName("doc-existing");
        existingDoc.setMetadata(existingMetadata);
        var existingSpec = new Doc.Spec();
        existingSpec.setSlug("a-page");
        existingSpec.setPriority(7);
        existingSpec.setLibraryName("lib-1");
        existingDoc.setSpec(existingSpec);
        when(client.listAll(eq(Doc.class), any(), any())).thenReturn(Flux.just(existingDoc));
        when(client.create(any(Doc.class))).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("A-Page", "content");

        var report = importer().importDocs(files, 2,
            new GithubWikiImportRequest("lib-1", null, null, false)).block();

        assertThat(report).isNotNull();
        assertThat(report.libraryName()).isEqualTo("lib-1");
        assertThat(report.librarySlug()).isEqualTo("lib-slug");
        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(2);
        assertThat(report.slugAdjusted()).isEqualTo(1);

        var docs = capturedDocs();
        assertThat(docs.get(0).getSpec().getSlug()).isEqualTo("a-page-2");
        assertThat(docs.get(0).getSpec().getPriority()).isEqualTo(8);
        assertThat(docs.get(0).getSpec().getPublished()).isFalse();
        assertThat(docs.get(0).getSpec().getPublishTime()).isNull();
    }

    @Test
    void warnsOnCaseInsensitivePageNameCollision() {
        when(client.listAll(eq(DocLibrary.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(DocLibrary.class))).thenAnswer(invocation -> {
            DocLibrary library = invocation.getArgument(0);
            library.getMetadata().setName("doc-library-abc");
            return Mono.just(library);
        });
        when(client.listAll(eq(Doc.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(Doc.class))).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("Guide", "链接到 [[guide]]。");
        files.put("guide", "另一个同名页面。");

        var report = importer().importDocs(files, 0,
            new GithubWikiImportRequest(null, "库", null, true)).block();

        assertThat(report).isNotNull();
        assertThat(report.imported()).isEqualTo(2);
        assertThat(report.slugAdjusted()).isEqualTo(1);
        assertThat(report.warnings()).anyMatch(w -> w.contains("页面名冲突"));
        // “guide”大小写不敏感冲突后追加序号，链接仍指向先前的“Guide”页面。
        var docs = capturedDocs();
        assertThat(docs).extracting(doc -> doc.getSpec().getSlug())
            .containsExactly("guide", "guide-2");
        assertThat(docs.get(0).getSpec().getRaw()).isEqualTo("链接到 [guide](./guide)。");
    }

    /**
     * 捕获全部 create 调用中的 Doc 实例（create 也用于创建 DocLibrary，需按类型过滤）。
     */
    private List<Doc> capturedDocs() {
        var captor = ArgumentCaptor.forClass(Doc.class);
        verify(client, atLeastOnce()).create(captor.capture());
        return captor.getAllValues().stream()
            .filter(Doc.class::isInstance)
            .map(Doc.class::cast)
            .toList();
    }
}
