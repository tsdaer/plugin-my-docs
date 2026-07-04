package com.github.mydocs.web;

import com.github.mydocs.finder.DocFinder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;

@Controller
public class DocPageController {

    private final DocFinder docFinder;
    private final TemplateNameResolver templateNameResolver;
    private final DocPageSeoMetadataFactory seoMetadataFactory;

    public DocPageController(DocFinder docFinder, TemplateNameResolver templateNameResolver,
        DocPageSeoMetadataFactory seoMetadataFactory) {
        this.docFinder = docFinder;
        this.templateNameResolver = templateNameResolver;
        this.seoMetadataFactory = seoMetadataFactory;
    }

    @GetMapping("/docs")
    public Mono<String> index(ServerWebExchange exchange, Model model) {
        return docFinder.listLibraries()
            .zipWith(templateNameResolver.resolveTemplateNameOrDefault(exchange, "docs/index"))
            .map(tuple -> {
                model.addAttribute("libraries", tuple.getT1());
                model.addAttribute("seo", seoMetadataFactory.forIndex());
                return tuple.getT2();
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
            ).map(tuple -> {
                model.addAttribute("library", library);
                model.addAttribute("tree", tuple.getT1());
                model.addAttribute("docs", tuple.getT2());
                model.addAttribute("seo", seoMetadataFactory.forLibrary(library));
                return tuple.getT3();
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
                    templateNameResolver.resolveTemplateNameOrDefault(exchange, "docs/detail")
                ).map(tuple -> {
                    model.addAttribute("library", library);
                    model.addAttribute("doc", doc);
                    model.addAttribute("tree", tuple.getT1());
                    model.addAttribute("seo", seoMetadataFactory.forDetail(library, doc));
                    return tuple.getT2();
                })));
    }

    private static <T> Mono<T> notFound() {
        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
