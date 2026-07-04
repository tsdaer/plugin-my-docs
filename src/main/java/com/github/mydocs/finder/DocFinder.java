package com.github.mydocs.finder;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.theme.finders.Finder;

@Component
@Finder("myDocs")
public class DocFinder {

    private static final Sort PRIORITY_ASC = Sort.by(Sort.Order.asc("spec.priority"));

    private final ReactiveExtensionClient client;

    public DocFinder(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<List<DocLibrary>> listLibraries() {
        return client.listAll(DocLibrary.class, ListOptions.builder().build(), PRIORITY_ASC)
            .sort(libraryComparator())
            .collectList();
    }

    public Mono<DocLibrary> getLibrary(String name) {
        return client.fetch(DocLibrary.class, name);
    }

    public Mono<DocLibrary> getLibraryBySlug(String slug) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.slug", slug))
            .build();
        return client.listAll(DocLibrary.class, options, PRIORITY_ASC)
            .next();
    }

    public Mono<List<Doc>> listDocs(String libraryName) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.libraryName", libraryName))
            .build();
        return client.listAll(Doc.class, options, PRIORITY_ASC)
            .sort(docComparator())
            .collectList();
    }

    public Mono<List<Doc>> listPublishedDocs(String libraryName) {
        return listDocs(libraryName)
            .map(docs -> docs.stream()
                .filter(DocFinder::isPublished)
                .toList());
    }

    public Mono<List<Doc>> listPublishedDocsByLibrarySlug(String librarySlug) {
        return getLibraryBySlug(librarySlug)
            .flatMap(library -> listPublishedDocs(library.getMetadata().getName()));
    }

    public Mono<Doc> getDoc(String name) {
        return client.fetch(Doc.class, name);
    }

    public Mono<Doc> getPublishedDocBySlugs(String librarySlug, String docSlug) {
        return getLibraryBySlug(librarySlug)
            .flatMap(library -> {
                var options = ListOptions.builder()
                    .fieldQuery(and(
                        equal("spec.libraryName", library.getMetadata().getName()),
                        equal("spec.slug", docSlug)
                    ))
                    .build();
                return client.listAll(Doc.class, options, PRIORITY_ASC)
                    .filter(DocFinder::isPublished)
                    .next();
            });
    }

    public Mono<List<DocTreeNode>> tree(String libraryName) {
        return listPublishedDocs(libraryName)
            .map(DocFinder::buildTree);
    }

    public Mono<List<DocTreeNode>> treeByLibrarySlug(String librarySlug) {
        return getLibraryBySlug(librarySlug)
            .flatMap(library -> tree(library.getMetadata().getName()));
    }

    static List<DocTreeNode> buildTree(List<Doc> docs) {
        Map<String, DocTreeNode> nodes = new LinkedHashMap<>();
        for (var doc : docs.stream().sorted(docComparator()).toList()) {
            nodes.put(metadataName(doc), new DocTreeNode(doc, new ArrayList<>()));
        }

        List<DocTreeNode> roots = new ArrayList<>();
        for (var node : nodes.values()) {
            var parent = spec(node.getDoc()).getParent();
            if (StringUtils.hasText(parent) && nodes.containsKey(parent)) {
                nodes.get(parent).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    private static boolean isPublished(Doc doc) {
        return Boolean.TRUE.equals(spec(doc).getPublished());
    }

    private static Doc.Spec spec(Doc doc) {
        return doc.getSpec() == null ? new Doc.Spec() : doc.getSpec();
    }

    private static DocLibrary.Spec spec(DocLibrary library) {
        return library.getSpec() == null ? new DocLibrary.Spec() : library.getSpec();
    }

    private static String metadataName(Doc doc) {
        return doc.getMetadata() == null ? "" : doc.getMetadata().getName();
    }

    private static String metadataName(DocLibrary library) {
        return library.getMetadata() == null ? "" : library.getMetadata().getName();
    }

    private static Comparator<Doc> docComparator() {
        return Comparator.comparing((Doc doc) -> nullLast(spec(doc).getPriority()))
            .thenComparing(doc -> nullToEmpty(spec(doc).getTitle()))
            .thenComparing(DocFinder::metadataName);
    }

    private static Comparator<DocLibrary> libraryComparator() {
        return Comparator.comparing((DocLibrary library) -> nullLast(spec(library).getPriority()))
            .thenComparing(library -> nullToEmpty(spec(library).getTitle()))
            .thenComparing(DocFinder::metadataName);
    }

    private static int nullLast(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Value
    public static class DocTreeNode {
        Doc doc;
        List<DocTreeNode> children;
    }
}
