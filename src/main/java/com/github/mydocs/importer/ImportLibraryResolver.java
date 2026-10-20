package com.github.mydocs.importer;

import static com.github.mydocs.importer.GithubWikiMarkdownConverter.slugify;
import static run.halo.app.extension.index.query.Queries.startsWith;

import com.github.mydocs.extension.DocLibrary;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/** Shared target-library validation and creation for article and Wiki imports. */
public class ImportLibraryResolver {
    private final ReactiveExtensionClient client;

    public ImportLibraryResolver(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<DocLibrary> resolve(String targetName, String newTitle, String newSlug,
        String description) {
        boolean hasExisting = StringUtils.hasText(targetName);
        boolean hasNew = StringUtils.hasText(newTitle);
        if (hasExisting == hasNew) {
            return Mono.error(new ServerWebInputException(
                "请从「导入到已有文档库」与「新建文档库」中二选一。"));
        }
        if (hasExisting) {
            return client.fetch(DocLibrary.class, targetName)
                .filter(library -> library.getMetadata().getDeletionTimestamp() == null)
                .switchIfEmpty(Mono.error(new ServerWebInputException("目标文档库不存在：" + targetName)));
        }
        String title = newTitle.trim();
        if (title.length() > 100) {
            return Mono.error(new ServerWebInputException("新建文档库标题长度不能超过 100。"));
        }
        boolean provided = StringUtils.hasText(newSlug);
        String desiredSlug = provided ? newSlug.trim() : slugify(title);
        if (desiredSlug.isEmpty() || desiredSlug.length() > 100) {
            return Mono.error(new ServerWebInputException("新建文档库别名需为 1-100 个字符。"));
        }
        return client.listAll(DocLibrary.class, ListOptions.builder()
                .fieldQuery(startsWith("spec.slug",
                    desiredSlug.substring(0, Math.min(desiredSlug.length(), 89))))
                .build(), Sort.unsorted())
            .map(library -> library.getSpec().getSlug()).collectList()
            .flatMap(taken -> {
                String slug = desiredSlug;
                if (taken.contains(slug) && provided) {
                    return Mono.error(new ServerWebInputException(
                        "文档库别名 [" + slug + "] 已存在，请更换。"));
                }
                for (int i = 2; taken.contains(slug); i++) {
                    String suffix = "-" + i;
                    slug = desiredSlug.substring(0, Math.min(desiredSlug.length(), 100 - suffix.length()))
                        + suffix;
                }
                var library = new DocLibrary();
                var metadata = new Metadata();
                metadata.setGenerateName("doc-library-");
                library.setMetadata(metadata);
                var spec = new DocLibrary.Spec();
                spec.setTitle(title);
                spec.setSlug(slug);
                spec.setDescription(description);
                library.setSpec(spec);
                return client.create(library);
            });
    }
}
