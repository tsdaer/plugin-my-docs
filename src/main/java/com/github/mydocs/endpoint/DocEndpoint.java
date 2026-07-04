package com.github.mydocs.endpoint;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.service.DocService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * <p>文档的 Console 自定义 API。</p>
 * <p>创建/更新走本端点而非自动生成的 CRUD API，以便执行 slug 库内唯一等应用层校验。</p>
 * <p>路径会自动加上前缀 {@code /apis/console.api.docs.halo.run/v1alpha1/}。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class DocEndpoint implements CustomEndpoint {

    private final DocService docService;

    public DocEndpoint(DocService docService) {
        this.docService = docService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .POST("/docs", accept(APPLICATION_JSON), this::createDoc)
            .PUT("/docs/{name}", accept(APPLICATION_JSON), this::updateDoc)
            .build();
    }

    private Mono<ServerResponse> createDoc(ServerRequest request) {
        return request.bodyToMono(Doc.class)
            .flatMap(docService::create)
            .flatMap(created -> ServerResponse.ok().bodyValue(created));
    }

    private Mono<ServerResponse> updateDoc(ServerRequest request) {
        return request.bodyToMono(Doc.class)
            .flatMap(docService::update)
            .flatMap(updated -> ServerResponse.ok().bodyValue(updated));
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.docs.halo.run", "v1alpha1");
    }
}
