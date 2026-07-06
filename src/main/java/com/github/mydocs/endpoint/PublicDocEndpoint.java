package com.github.mydocs.endpoint;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.github.mydocs.finder.DocFinder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class PublicDocEndpoint implements CustomEndpoint {

    private final DocFinder docFinder;

    public PublicDocEndpoint(DocFinder docFinder) {
        this.docFinder = docFinder;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/libraries", accept(APPLICATION_JSON), this::listLibraries)
            .GET("/libraries/{librarySlug}", accept(APPLICATION_JSON), this::getLibrary)
            .GET("/docs", accept(APPLICATION_JSON), this::listDocs)
            .GET("/docs/{librarySlug}/{docSlug}", accept(APPLICATION_JSON), this::getDoc)
            .GET("/trees/{librarySlug}", accept(APPLICATION_JSON), this::getTree)
            .build();
    }

    private Mono<ServerResponse> listLibraries(ServerRequest request) {
        return docFinder.listLibraries()
            .flatMap(libraries -> ServerResponse.ok().bodyValue(libraries));
    }

    private Mono<ServerResponse> getLibrary(ServerRequest request) {
        return docFinder.getLibraryBySlug(request.pathVariable("librarySlug"))
            .switchIfEmpty(notFound())
            .flatMap(library -> ServerResponse.ok().bodyValue(library));
    }

    private Mono<ServerResponse> listDocs(ServerRequest request) {
        var librarySlug = request.queryParam("librarySlug")
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Query parameter librarySlug is required."));
        return docFinder.listPublishedDocsByLibrarySlug(librarySlug)
            .switchIfEmpty(notFound())
            .flatMap(docs -> ServerResponse.ok().bodyValue(docs));
    }

    private Mono<ServerResponse> getDoc(ServerRequest request) {
        return docFinder.getPublishedDocBySlugs(
                request.pathVariable("librarySlug"),
                request.pathVariable("docSlug")
            )
            .switchIfEmpty(notFound())
            .flatMap(doc -> ServerResponse.ok().bodyValue(doc));
    }

    private Mono<ServerResponse> getTree(ServerRequest request) {
        return docFinder.treeByLibrarySlug(request.pathVariable("librarySlug"))
            .switchIfEmpty(notFound())
            .flatMap(tree -> ServerResponse.ok().bodyValue(tree));
    }

    private static <T> Mono<T> notFound() {
        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.my-docs.tsdaer.run", "v1alpha1");
    }
}
