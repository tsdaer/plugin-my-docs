package com.github.mydocs.importer;

import static run.halo.app.extension.index.query.Queries.equal;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.service.DocService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;

/** Reads full head content from Halo snapshots; never mutates source articles or snapshots. */
@Slf4j
@Component
public class PostImporter {
    public static final int MAX_POSTS = 100;
    private final ReactiveExtensionClient client;
    private final DocService docService;
    private final PostMarkdownConverter converter;
    private final ExternalUrlSupplier externalUrl;

    public PostImporter(ReactiveExtensionClient client, DocService docService,
        PostMarkdownConverter converter, ExternalUrlSupplier externalUrl) {
        this.client = client;
        this.docService = docService;
        this.converter = converter;
        this.externalUrl = externalUrl;
    }

    public Mono<PostImportReport> importPosts(PostImportRequest request) {
        return Mono.defer(() -> {
            validate(request);
            return Flux.fromIterable(request.postNames()).concatMap(this::prepare).collectList()
                .flatMap(prepared -> {
                    if (prepared.stream().noneMatch(item -> item.conversion() != null)) {
                        return Mono.just(report(null, prepared.stream().map(Prepared::failure).toList()));
                    }
                    return new ImportLibraryResolver(client).resolve(request.targetLibraryName(),
                            request.newLibraryTitle(), request.newLibrarySlug(), "从本站文章导入")
                        .flatMap(library -> importIntoLibrary(prepared, request, library));
                });
        });
    }

    private void validate(PostImportRequest request) {
        if (request == null || request.postNames() == null || request.postNames().isEmpty()
            || request.postNames().size() > MAX_POSTS
            || request.postNames().stream().anyMatch(name -> !StringUtils.hasText(name))
            || new HashSet<>(request.postNames()).size() != request.postNames().size()) {
            throw new ServerWebInputException("请选择 1-100 篇不重复的文章。");
        }
        if (StringUtils.hasText(request.targetLibraryName())
            == StringUtils.hasText(request.newLibraryTitle())) {
            throw new ServerWebInputException("请从已有文档库与新建文档库中二选一。");
        }
    }

    private Mono<Prepared> prepare(String name) {
        String[] title = {name};
        return client.fetch(Post.class, name)
            .switchIfEmpty(Mono.error(new ServerWebInputException("文章不存在或已删除。")))
            .flatMap(post -> {
                var spec = post.getSpec();
                if (spec != null && StringUtils.hasText(spec.getTitle())) {
                    title[0] = spec.getTitle();
                }
                if (spec == null || post.isDeleted()) {
                    return Mono.error(new ServerWebInputException("文章不存在或已进入回收站。"));
                }
                if (!StringUtils.hasText(spec.getTitle()) || spec.getTitle().length() > 200) {
                    return Mono.error(new ServerWebInputException("文章标题需为 1-200 个字符。"));
                }
                String base = spec.getBaseSnapshot();
                String head = StringUtils.hasText(spec.getHeadSnapshot()) ? spec.getHeadSnapshot() : base;
                if (!StringUtils.hasText(base) || !StringUtils.hasText(head)) {
                    return Mono.error(new ServerWebInputException("文章尚无已保存的正文快照。"));
                }
                Mono<Snapshot> baseSnapshot = snapshot(base, name);
                Mono<ContentWrapper> content = head.equals(base)
                    ? baseSnapshot.map(value -> ContentWrapper.patchSnapshot(value, value))
                    : Mono.zip(snapshot(head, name), baseSnapshot)
                        .map(pair -> ContentWrapper.patchSnapshot(pair.getT1(), pair.getT2()));
                return content.map(value -> new Prepared(name, spec.getTitle(), spec.getSlug(),
                    converter.convert(value, permalink(post)), null));
            })
            .onErrorResume(error -> Mono.just(new Prepared(name, title[0], null, null,
                failure(name, title[0], error))));
    }

    private Mono<Snapshot> snapshot(String name, String postName) {
        return client.fetch(Snapshot.class, name)
            .switchIfEmpty(Mono.error(new ServerWebInputException("正文快照不存在，请重新保存文章。")))
            .map(snapshot -> {
                var ref = snapshot.getSpec() == null ? null : snapshot.getSpec().getSubjectRef();
                if (ref == null || !postName.equals(ref.getName()) || !"Post".equals(ref.getKind())
                    || !"content.halo.run".equals(ref.getGroup())) {
                    throw new ServerWebInputException("正文快照不属于所选文章。");
                }
                return snapshot;
            });
    }

    private String permalink(Post post) {
        String link = post.getStatus() == null ? null : post.getStatus().getPermalink();
        String siteUrl = externalUrl.get().toString();
        return StringUtils.hasText(link) ? java.net.URI.create(siteUrl).resolve(link).toString() : siteUrl;
    }

    private Mono<PostImportReport> importIntoLibrary(List<Prepared> prepared,
        PostImportRequest request, DocLibrary library) {
        String libraryName = library.getMetadata().getName();
        return client.listAll(Doc.class, ListOptions.builder()
                .fieldQuery(equal("spec.libraryName", libraryName)).build(), Sort.unsorted())
            .collectList().flatMap(existing -> {
                List<Doc> docs = new ArrayList<>(existing);
                Set<String> usedSlugs = new HashSet<>();
                docs.stream().filter(doc -> doc.getSpec() != null)
                    .map(doc -> doc.getSpec().getSlug()).filter(Objects::nonNull)
                    .map(slug -> slug.toLowerCase(Locale.ROOT)).forEach(usedSlugs::add);
                int priority = docs.stream().filter(doc -> doc.getSpec() != null)
                    .map(doc -> doc.getSpec().getPriority()).filter(Objects::nonNull)
                    .mapToInt(Integer::intValue).max().orElse(-1) + 1;
                int[] nextPriority = {priority};
                return Flux.fromIterable(prepared).concatMap(item -> {
                    if (item.failure() != null) {
                        return Mono.just(item.failure());
                    }
                    return Mono.defer(() -> save(item, docs, usedSlugs, libraryName,
                            nextPriority, Boolean.TRUE.equals(request.publish())))
                        .onErrorResume(error -> Mono.just(failure(item.name(), item.title(), error)));
                }).collectList().map(items -> report(library, items));
            });
    }

    private Mono<PostImportReport.Item> save(Prepared item, List<Doc> docs, Set<String> usedSlugs,
        String libraryName, int[] nextPriority, boolean publish) {
        List<Doc> matches = docs.stream().filter(doc -> doc.getSpec() != null
            && item.name().equals(doc.getSpec().getSourcePostName())
            && doc.getMetadata().getDeletionTimestamp() == null).toList();
        if (matches.size() > 1) {
            return Mono.error(new ServerWebInputException("库内有多篇文档关联该文章，请先处理重复关联。"));
        }
        if (!matches.isEmpty()) {
            return client.fetch(Doc.class, matches.getFirst().getMetadata().getName())
                .switchIfEmpty(Mono.error(new ServerWebInputException("目标文档已删除，请重试。")))
                .flatMap(doc -> {
                    if (doc.getMetadata().getDeletionTimestamp() != null
                        || !libraryName.equals(doc.getSpec().getLibraryName())
                        || !item.name().equals(doc.getSpec().getSourcePostName())) {
                        return Mono.error(new ServerWebInputException("文档所属库或来源已变化，请重试。"));
                    }
                    doc.getSpec().setTitle(item.title());
                    doc.getSpec().setRaw(item.conversion().markdown());
                    doc.getSpec().setRawType("markdown");
                    return docService.update(doc);
                }).map(doc -> result(item, doc, "updated", false));
        }
        String desired = StringUtils.hasText(item.slug()) ? item.slug().trim()
            : GithubWikiMarkdownConverter.slugify(item.title());
        desired = desired.substring(0, Math.min(desired.length(), 200));
        String slug = desired;
        for (int i = 2; usedSlugs.contains(slug.toLowerCase(Locale.ROOT)); i++) {
            String suffix = "-" + i;
            slug = desired.substring(0, Math.min(desired.length(), 200 - suffix.length())) + suffix;
        }
        var doc = new Doc();
        var metadata = new Metadata();
        // A deterministic name makes simultaneous/retried creates conflict instead of duplicating a source.
        metadata.setName(sourceDocName(libraryName, item.name()));
        doc.setMetadata(metadata);
        var spec = new Doc.Spec();
        spec.setTitle(item.title());
        spec.setSlug(slug);
        spec.setLibraryName(libraryName);
        spec.setSourcePostName(item.name());
        spec.setPriority(nextPriority[0]++);
        spec.setRaw(item.conversion().markdown());
        spec.setRawType("markdown");
        spec.setPublished(publish);
        spec.setPublishTime(publish ? Instant.now() : null);
        doc.setSpec(spec);
        boolean adjusted = !slug.equals(desired);
        return docService.create(doc).doOnNext(saved -> {
            docs.add(saved);
            usedSlugs.add(saved.getSpec().getSlug().toLowerCase(Locale.ROOT));
        }).map(saved -> result(item, saved, "created", adjusted));
    }

    private String sourceDocName(String library, String post) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((library + "\n" + post).getBytes(StandardCharsets.UTF_8));
            return "doc-post-" + HexFormat.of().formatHex(hash).substring(0, 40);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private PostImportReport.Item result(Prepared item, Doc doc, String status, boolean adjusted) {
        var warnings = new ArrayList<>(item.conversion().warnings());
        if (adjusted) {
            warnings.add("别名冲突，已自动调整为 " + doc.getSpec().getSlug());
        }
        return new PostImportReport.Item(item.name(), item.title(), status,
            doc.getMetadata().getName(), null, warnings);
    }

    private PostImportReport.Item failure(String name, String title, Throwable error) {
        log.warn("Failed importing Halo post {}", name, error);
        String reason = error instanceof ServerWebInputException input ? input.getReason()
            : error instanceof org.springframework.dao.OptimisticLockingFailureException
                ? "文档被同时修改，请重试。" : "读取或保存失败，请重试并检查服务器日志。";
        return new PostImportReport.Item(name, title, "failed", null, reason, List.of());
    }

    private PostImportReport report(DocLibrary library, List<PostImportReport.Item> items) {
        return new PostImportReport(library == null ? null : library.getMetadata().getName(),
            library == null ? null : library.getSpec().getSlug(),
            (int) items.stream().filter(item -> "created".equals(item.status())).count(),
            (int) items.stream().filter(item -> "updated".equals(item.status())).count(),
            (int) items.stream().filter(item -> "failed".equals(item.status())).count(), items);
    }

    private record Prepared(String name, String title, String slug,
        PostMarkdownConverter.Conversion conversion, PostImportReport.Item failure) { }
}
