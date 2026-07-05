package com.github.mydocs.service;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;

import com.github.mydocs.extension.Doc;
import java.util.Objects;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * {@link DocService} 的默认实现。
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Service
public class DocServiceImpl implements DocService {

    private final ReactiveExtensionClient client;
    private final MarkdownRenderer markdownRenderer;

    public DocServiceImpl(ReactiveExtensionClient client, MarkdownRenderer markdownRenderer) {
        this.client = client;
        this.markdownRenderer = markdownRenderer;
    }

    @Override
    public Mono<Doc> create(Doc doc) {
        renderContent(doc);
        return ensureSlugUniqueInLibrary(doc)
            .then(client.create(doc));
    }

    @Override
    public Mono<Doc> update(Doc doc) {
        renderContent(doc);
        return ensureSlugUniqueInLibrary(doc)
            .then(client.update(doc));
    }

    private void renderContent(Doc doc) {
        if (doc == null || doc.getSpec() == null) {
            return;
        }
        doc.getSpec().setContent(markdownRenderer.render(doc.getSpec().getRaw()));
    }

    /**
     * 校验 slug 在所属文档库内唯一。若库内已存在相同 slug 且不是当前文档本身，
     * 则以 {@link ServerWebInputException} 中断（HTTP 400）。
     */
    private Mono<Void> ensureSlugUniqueInLibrary(Doc doc) {
        var spec = doc.getSpec();
        var libraryName = spec == null ? null : spec.getLibraryName();
        var slug = spec == null ? null : spec.getSlug();
        if (!StringUtils.hasText(libraryName) || !StringUtils.hasText(slug)) {
            return Mono.empty();
        }
        var selfName = doc.getMetadata() == null ? null : doc.getMetadata().getName();
        var options = ListOptions.builder()
            .fieldQuery(and(
                equal("spec.libraryName", libraryName),
                equal("spec.slug", slug)
            ))
            .build();
        return client.listAll(Doc.class, options, Sort.unsorted())
            .filter(existing -> !Objects.equals(existing.getMetadata().getName(), selfName))
            .hasElements()
            .flatMap(duplicated -> {
                if (Boolean.TRUE.equals(duplicated)) {
                    return Mono.error(new ServerWebInputException(
                        "文档别名 [" + slug + "] 在当前文档库内已存在。"));
                }
                return Mono.empty();
            });
    }
}
