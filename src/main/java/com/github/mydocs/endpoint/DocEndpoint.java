package com.github.mydocs.endpoint;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.service.DocService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * <p>文档的 Console 自定义 API。</p>
 * <p>创建/更新走本端点而非自动生成的 CRUD API，以便执行 slug 库内唯一等应用层校验，
 * 以及请求完整性校验（PUT 路径名与 body 名一致、所属文档库存在）。</p>
 * <p>路径会自动加上前缀 {@code /apis/console.api.my-docs.tsdaer.run/v1alpha1/}。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Component
public class DocEndpoint implements CustomEndpoint {

    private final DocService docService;
    private final ReactiveExtensionClient client;

    public DocEndpoint(DocService docService, ReactiveExtensionClient client) {
        this.docService = docService;
        this.client = client;
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
            .flatMap(this::validateLibraryExists)
            .flatMap(docService::create)
            .flatMap(created -> ServerResponse.ok().bodyValue(created));
    }

    private Mono<ServerResponse> updateDoc(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(Doc.class)
            .flatMap(doc -> {
                if (doc.getMetadata() == null
                    || !name.equals(doc.getMetadata().getName())) {
                    return Mono.error(new ServerWebInputException(
                        "路径参数 {name} 与请求体 metadata.name 不一致。"));
                }
                return validateLibraryExists(doc);
            })
            .flatMap(docService::update)
            .flatMap(updated -> ServerResponse.ok().bodyValue(updated));
    }

    /**
     * 校验 spec.libraryName 指向的文档库存在，避免写出游离文档。
     */
    private Mono<Doc> validateLibraryExists(Doc doc) {
        var spec = doc.getSpec();
        var libraryName = spec == null ? null : spec.getLibraryName();
        if (!StringUtils.hasText(libraryName)) {
            return Mono.error(new ServerWebInputException("缺少所属文档库（spec.libraryName）。"));
        }
        return client.fetch(DocLibrary.class, libraryName)
            .switchIfEmpty(Mono.error(new ServerWebInputException(
                "所属文档库不存在：" + libraryName)))
            .thenReturn(doc);
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.my-docs.tsdaer.run", "v1alpha1");
    }
}
