package com.github.mydocs.search;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.search.HaloDocument;
import run.halo.app.search.HaloDocumentsProvider;

@Component
public class DocSearchDocumentsProvider implements HaloDocumentsProvider {

    public static final String TYPE = DocSearchDocumentConverter.TYPE;

    private final ReactiveExtensionClient client;
    private final DocSearchDocumentConverter converter;

    public DocSearchDocumentsProvider(ReactiveExtensionClient client,
        DocSearchDocumentConverter converter) {
        this.client = client;
        this.converter = converter;
    }

    @Override
    public Flux<HaloDocument> fetchAll() {
        return librarySlugsByName()
            .flatMapMany(librarySlugs -> client.listAll(Doc.class, ListOptions.builder().build(),
                    Sort.unsorted())
                .filter(this::isIndexable)
                .filter(doc -> StringUtils.hasText(librarySlugs.get(spec(doc).getLibraryName())))
                .map(doc -> converter.toHaloDocument(doc,
                    librarySlugs.get(spec(doc).getLibraryName()))));
    }

    @Override
    public String getType() {
        return TYPE;
    }

    private Mono<Map<String, String>> librarySlugsByName() {
        return client.listAll(DocLibrary.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(library -> metadata(library).getDeletionTimestamp() == null)
            .filter(library -> StringUtils.hasText(metadata(library).getName()))
            .filter(library -> StringUtils.hasText(spec(library).getSlug()))
            .collectMap(library -> metadata(library).getName(), library -> spec(library).getSlug());
    }

    private boolean isIndexable(Doc doc) {
        return converter.isIndexable(doc);
    }

    private static MetadataOperator metadata(Doc doc) {
        return doc.getMetadata() == null ? new Metadata() : doc.getMetadata();
    }

    private static MetadataOperator metadata(DocLibrary library) {
        return library.getMetadata() == null ? new Metadata() : library.getMetadata();
    }

    private static Doc.Spec spec(Doc doc) {
        return doc.getSpec() == null ? new Doc.Spec() : doc.getSpec();
    }

    private static DocLibrary.Spec spec(DocLibrary library) {
        return library.getSpec() == null ? new DocLibrary.Spec() : library.getSpec();
    }
}
