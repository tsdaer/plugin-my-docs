package com.github.mydocs.web;

import com.github.mydocs.extensionpoint.DocDetailModelHandlerAggregator;
import com.github.mydocs.extensionpoint.DocPageType;
import com.github.mydocs.finder.DocFinder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;

@Controller
public class DocPageController {

    private final DocFinder docFinder;
    private final TemplateNameResolver templateNameResolver;
    private final DocPageSeoMetadataFactory seoMetadataFactory;
    private final DocDetailContentBuilder detailContentBuilder;
    private final DocIndexSettingsService docIndexSettingsService;
    private final DocLibraryIndexLayoutBuilder docLibraryIndexLayoutBuilder;
    private final DocDetailModelHandlerAggregator modelHandlerAggregator;

    public DocPageController(DocFinder docFinder, TemplateNameResolver templateNameResolver,
        DocPageSeoMetadataFactory seoMetadataFactory,
        DocDetailContentBuilder detailContentBuilder,
        DocIndexSettingsService docIndexSettingsService,
        DocLibraryIndexLayoutBuilder docLibraryIndexLayoutBuilder,
        DocDetailModelHandlerAggregator modelHandlerAggregator) {
        this.docFinder = docFinder;
        this.templateNameResolver = templateNameResolver;
        this.seoMetadataFactory = seoMetadataFactory;
        this.detailContentBuilder = detailContentBuilder;
        this.docIndexSettingsService = docIndexSettingsService;
        this.docLibraryIndexLayoutBuilder = docLibraryIndexLayoutBuilder;
        this.modelHandlerAggregator = modelHandlerAggregator;
    }

    @GetMapping("/docs")
    public Mono<String> index(@RequestParam(name = "page", defaultValue = "1") int page,
        ServerWebExchange exchange, Model model) {
        return Mono.zip(
                docFinder.listLibraries(),
                docIndexSettingsService.fetch(),
                templateNameResolver.resolveTemplateNameOrDefault(exchange, "docs/index")
            )
            .flatMap(tuple -> {
                model.addAttribute("libraries", tuple.getT1());
                model.addAttribute("libraryLayout",
                    docLibraryIndexLayoutBuilder.build(tuple.getT1(), tuple.getT2(), page));
                model.addAttribute("seo", seoMetadataFactory.forIndex());
                return modelHandlerAggregator
                    .apply(exchange, model, DocPageType.INDEX, null, null)
                    .thenReturn(tuple.getT3());
            });
    }

    @GetMapping("/docs/{librarySlug}")
    public Mono<String> library(@PathVariable("librarySlug") String librarySlug,
        ServerWebExchange exchange,
        Model model) {
        return docFinder.getLibraryBySlug(librarySlug)
            .switchIfEmpty(notFound())
            .flatMap(library -> Mono.zip(
                docFinder.tree(library.getMetadata().getName()),
                docFinder.listPublishedDocs(library.getMetadata().getName()),
                templateNameResolver.resolveTemplateNameOrDefault(exchange, "docs/library")
            ).flatMap(tuple -> {
                model.addAttribute("library", library);
                model.addAttribute("tree", tuple.getT1());
                model.addAttribute("docs", tuple.getT2());
                model.addAttribute("seo", seoMetadataFactory.forLibrary(library));
                return modelHandlerAggregator
                    .apply(exchange, model, DocPageType.LIBRARY, library, null)
                    .thenReturn(tuple.getT3());
            }));
    }

    @GetMapping("/docs/{librarySlug}/{docSlug}")
    public Mono<String> detail(@PathVariable("librarySlug") String librarySlug,
        @PathVariable("docSlug") String docSlug,
        ServerWebExchange exchange, Model model) {
        return docFinder.getLibraryBySlug(librarySlug)
            .switchIfEmpty(notFound())
            .flatMap(library -> docFinder.getPublishedDocBySlugs(librarySlug, docSlug)
                .switchIfEmpty(notFound())
                .flatMap(doc -> Mono.zip(
                    docFinder.tree(library.getMetadata().getName()),
                    templateNameResolver.resolveTemplateNameOrDefault(exchange, "docs/detail"),
                    detailContentBuilder.build(library, doc)
                ).flatMap(tuple -> {
                    model.addAttribute("library", library);
                    model.addAttribute("doc", doc);
                    model.addAttribute("tree", tuple.getT1());
                    model.addAttribute("detailContent", tuple.getT3());
                    model.addAttribute("seo", seoMetadataFactory.forDetail(library, doc));
                    return modelHandlerAggregator
                        .apply(exchange, model, DocPageType.DETAIL, library, doc)
                        .thenReturn(tuple.getT2());
                })));
    }

    private static <T> Mono<T> notFound() {
        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
