package com.github.mydocs.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.service.DocService;
import com.github.mydocs.service.DocServiceImpl;
import com.github.mydocs.service.MarkdownRenderer;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.PatchUtils;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.infra.ExternalUrlSupplier;

class PostImporterTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final DocService service = mock(DocService.class);
    private final ExternalUrlSupplier url = mock(ExternalUrlSupplier.class);
    private PostImporter importer;

    @BeforeEach
    void setUp() {
        when(url.get()).thenReturn(URI.create("https://example.com/"));
        importer = new PostImporter(client, service, new PostMarkdownConverter(), url);
    }

    private Metadata metadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return metadata;
    }

    private Post article(String name, String slug, String raw) {
        var post = new Post();
        post.setMetadata(metadata(name));
        var spec = new Post.PostSpec();
        spec.setTitle("文章 " + name);
        spec.setSlug(slug);
        spec.setDeleted(false);
        spec.setBaseSnapshot(name + "-base");
        spec.setHeadSnapshot(name + "-base");
        post.setSpec(spec);
        var status = new Post.PostStatus();
        status.setPermalink("https://example.com/archives/" + slug);
        post.setStatus(status);
        when(client.fetch(Post.class, name)).thenReturn(Mono.just(post));
        snapshot(name + "-base", post, raw, "<p>base</p>");
        return post;
    }

    private Snapshot snapshot(String name, Post post, String raw, String html) {
        var snapshot = new Snapshot();
        snapshot.setMetadata(metadata(name));
        var spec = new Snapshot.SnapShotSpec();
        spec.setSubjectRef(Ref.of(post));
        spec.setRawType("markdown");
        spec.setRawPatch(raw);
        spec.setContentPatch(html);
        snapshot.setSpec(spec);
        when(client.fetch(Snapshot.class, name)).thenReturn(Mono.just(snapshot));
        return snapshot;
    }

    private DocLibrary library() {
        var library = new DocLibrary();
        library.setMetadata(metadata("lib"));
        var spec = new DocLibrary.Spec();
        spec.setTitle("文档库");
        spec.setSlug("docs");
        library.setSpec(spec);
        when(client.fetch(DocLibrary.class, "lib")).thenReturn(Mono.just(library));
        when(client.listAll(eq(Doc.class), any(), any())).thenReturn(Flux.empty());
        when(service.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        return library;
    }

    private PostImportRequest request(String... names) {
        return new PostImportRequest(List.of(names), "lib", null, null, false);
    }

    @Test
    void reconstructsHeadDiffAndCreatesDraftWithoutChangingSource() {
        var post = article("a", "hello", "# Base\n\nold");
        post.getSpec().setHeadSnapshot("a-head");
        snapshot("a-head", post, PatchUtils.diffToJsonPatch("# Base\n\nold", "# Latest\n\nnew"),
            PatchUtils.diffToJsonPatch("<p>base</p>", "<h1>Latest</h1>"));
        library();
        var result = importer.importPosts(request("a")).block();
        assertThat(result.imported()).isEqualTo(1);
        var captured = ArgumentCaptor.forClass(Doc.class);
        verify(service).create(captured.capture());
        var spec = captured.getValue().getSpec();
        assertThat(spec.getRaw()).isEqualTo("# Latest\n\nnew");
        assertThat(spec.getSourcePostName()).isEqualTo("a");
        assertThat(spec.getPublished()).isFalse();
        assertThat(spec.getPublishTime()).isNull();
        assertThat(spec.getParent()).isNull();
        assertThat(post.getSpec().getHeadSnapshot()).isEqualTo("a-head");
        verify(client, never()).update(any());
        verify(client, never()).delete(any());
    }

    @Test
    void rendersImportedMarkdownUsingExistingDocService() {
        article("a", "hello", "# Imported");
        library();
        when(client.create(any(Doc.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        var real = new PostImporter(client, new DocServiceImpl(client, new MarkdownRenderer()),
            new PostMarkdownConverter(), url);
        assertThat(real.importPosts(request("a")).block().imported()).isEqualTo(1);
        var captured = ArgumentCaptor.forClass(Doc.class);
        verify(client).create(captured.capture());
        assertThat(captured.getValue().getSpec().getContent()).contains("<h1", "Imported");
    }

    @Test
    void matchesOnlySourceAndPreservesExistingDocSettings() {
        article("a", "source-slug", "new raw");
        library();
        var existing = new Doc();
        existing.setMetadata(metadata("existing"));
        var spec = new Doc.Spec();
        spec.setSourcePostName("a");
        spec.setTitle("edited");
        spec.setSlug("permanent");
        spec.setLibraryName("lib");
        spec.setParent("parent");
        spec.setPriority(9);
        spec.setPublished(true);
        spec.setPublishTime(Instant.parse("2026-01-01T00:00:00Z"));
        spec.setCustomHeadHtml("<style>p{}</style>");
        spec.setCustomBodyHtml("<footer>custom</footer>");
        existing.setSpec(spec);
        when(client.listAll(eq(Doc.class), any(), any())).thenReturn(Flux.just(existing));
        when(client.fetch(Doc.class, "existing")).thenReturn(Mono.just(existing));
        when(service.update(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        assertThat(importer.importPosts(request("a")).block().updated()).isEqualTo(1);
        assertThat(spec.getTitle()).isEqualTo("文章 a");
        assertThat(spec.getRaw()).isEqualTo("new raw");
        assertThat(spec.getSlug()).isEqualTo("permanent");
        assertThat(spec.getParent()).isEqualTo("parent");
        assertThat(spec.getPriority()).isEqualTo(9);
        assertThat(spec.getPublished()).isTrue();
        assertThat(spec.getPublishTime()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(spec.getCustomHeadHtml()).contains("style");
        assertThat(spec.getCustomBodyHtml()).contains("footer");
        verify(service, never()).create(any());
    }

    @Test
    void appendsInSelectionOrderAndDeduplicatesSlugsWithoutOverwritingUnrelatedDocs() {
        article("b", "same", "B");
        article("a", "same", "A");
        library();
        var unrelated = new Doc();
        unrelated.setMetadata(metadata("unrelated"));
        var spec = new Doc.Spec();
        spec.setTitle("文章 a");
        spec.setSlug("same");
        spec.setPriority(7);
        unrelated.setSpec(spec);
        when(client.listAll(eq(Doc.class), any(), any())).thenReturn(Flux.just(unrelated));
        var result = importer.importPosts(request("b", "a")).block();
        assertThat(result.items()).extracting(PostImportReport.Item::postName).containsExactly("b", "a");
        var captured = ArgumentCaptor.forClass(Doc.class);
        verify(service, times(2)).create(captured.capture());
        assertThat(captured.getAllValues()).extracting(doc -> doc.getSpec().getSlug())
            .containsExactly("same-2", "same-3");
        assertThat(captured.getAllValues()).extracting(doc -> doc.getSpec().getPriority()).containsExactly(8, 9);
        assertThat(result.items().getFirst().warnings()).anyMatch(warning -> warning.contains("别名冲突"));
        verify(service, never()).update(any());
    }

    @Test
    void continuesAfterMissingArticleAndWriteFailure() {
        when(client.fetch(Post.class, "missing")).thenReturn(Mono.empty());
        article("a", "a", "A");
        article("b", "b", "B");
        library();
        var count = new AtomicInteger();
        doAnswer(call -> count.incrementAndGet() == 1
            ? Mono.error(new IllegalStateException("write failed")) : Mono.just(call.getArgument(0)))
            .when(service).create(any());
        var report = importer.importPosts(request("missing", "a", "b")).block();
        assertThat(report.failed()).isEqualTo(2);
        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.items()).extracting(PostImportReport.Item::status)
            .containsExactly("failed", "failed", "created");
    }

    @Test
    void doesNotCreateLibraryWhenAllSnapshotsAreMissingOrInvalid() {
        var post = article("a", "a", "A");
        var snapshot = snapshot("a-base", post, "A", null);
        snapshot.getSpec().getSubjectRef().setName("another-post");
        var report = importer.importPosts(new PostImportRequest(List.of("a"), null, "New", null, false)).block();
        assertThat(report.libraryName()).isNull();
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.items().getFirst().reason()).contains("不属于");
        verify(client, never()).create(any());
        verifyNoInteractions(service);
    }

    @Test
    void ignoresRecycledArticlesAndEmptyContentBeforeCreatingLibrary() {
        var deleted = article("deleted", "deleted", "a");
        deleted.getSpec().setDeleted(true);
        article("empty", "empty", " ");
        var report = importer.importPosts(new PostImportRequest(List.of("deleted", "empty"), null, "New", null, false)).block();
        assertThat(report.failed()).isEqualTo(2);
        verify(client, never()).create(any());
    }

    @Test
    void rejectsInvalidSelectionsAndTargetBeforeReadingArticles() {
        assertThatThrownBy(() -> importer.importPosts(request()).block()).hasMessageContaining("1-100");
        assertThatThrownBy(() -> importer.importPosts(request("a", "a")).block()).hasMessageContaining("1-100");
        assertThatThrownBy(() -> importer.importPosts(new PostImportRequest(
            java.util.stream.IntStream.range(0, 101).mapToObj(Integer::toString).toList(), "lib", null, null, false)).block())
            .hasMessageContaining("1-100");
        assertThatThrownBy(() -> importer.importPosts(new PostImportRequest(List.of("a"), "lib", "New", null, false)).block())
            .hasMessageContaining("二选一");
        verifyNoInteractions(client, service);
    }

    @Test
    void missingTargetNeverCreatesDocuments() {
        article("a", "a", "A");
        when(client.fetch(DocLibrary.class, "lib")).thenReturn(Mono.empty());
        assertThatThrownBy(() -> importer.importPosts(request("a")).block()).hasMessageContaining("目标文档库不存在");
        verifyNoInteractions(service);
    }

    @Test
    void createsNewLibraryAndCanPublishNewDocs() {
        article("a", "a", "A");
        when(client.listAll(eq(DocLibrary.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any(DocLibrary.class))).thenAnswer(call -> {
            DocLibrary library = call.getArgument(0);
            library.getMetadata().setName("new-lib");
            return Mono.just(library);
        });
        when(client.listAll(eq(Doc.class), any(), any())).thenReturn(Flux.empty());
        when(service.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        var report = importer.importPosts(new PostImportRequest(List.of("a"), null, "New", "new", true)).block();
        assertThat(report.libraryName()).isEqualTo("new-lib");
        var captured = ArgumentCaptor.forClass(Doc.class);
        verify(service).create(captured.capture());
        assertThat(captured.getValue().getSpec().getPublished()).isTrue();
        assertThat(captured.getValue().getSpec().getPublishTime()).isNotNull();
    }
}
