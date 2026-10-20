package com.github.mydocs.endpoint;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.github.mydocs.importer.PostImporter;
import com.github.mydocs.importer.PostImportRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/** Console only; access granted exclusively by the article-import role template. */
@Component
public class PostImportEndpoint implements CustomEndpoint {
    private final PostImporter importer;

    public PostImportEndpoint(PostImporter importer) {
        this.importer = importer;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route().POST("/post-imports", request -> request.bodyToMono(PostImportRequest.class)
            .switchIfEmpty(Mono.error(new ServerWebInputException("请提交文章导入参数。")))
            .flatMap(importer::importPosts)
            .flatMap(report -> ServerResponse.ok().contentType(APPLICATION_JSON).bodyValue(report)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.my-docs.tsdaer.run", "v1alpha1");
    }
}
