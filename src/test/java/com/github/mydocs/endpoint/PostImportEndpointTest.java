package com.github.mydocs.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.github.mydocs.importer.PostImporter;
import com.github.mydocs.importer.PostImportReport;
import com.github.mydocs.importer.PostImportRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Runs the actual JSON route over HTTP, independently of Halo's host-level RBAC. */
class PostImportEndpointTest {
    @Test
    void decodesSelectionAndSerializesPartialSuccessReport() throws Exception {
        var importer = mock(PostImporter.class);
        when(importer.importPosts(any())).thenReturn(Mono.just(new PostImportReport("lib", "docs", 1, 0, 1,
            List.of(new PostImportReport.Item("a", "Article", "created", "doc-a", null, List.of()),
                new PostImportReport.Item("b", "Missing", "failed", null, "文章不存在", List.of())))));
        var endpoint = new PostImportEndpoint(importer);
        var server = HttpServer.create().host("127.0.0.1").port(0)
            .handle(new org.springframework.http.server.reactive.ReactorHttpHandlerAdapter(
                RouterFunctions.toHttpHandler(endpoint.endpoint())))
            .bindNow();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/post-imports"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"postNames\":[\"a\",\"b\"],\"targetLibraryName\":\"lib\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"imported\":1", "\"failed\":1", "文章不存在");
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
            var captured = ArgumentCaptor.forClass(PostImportRequest.class);
            verify(importer).importPosts(captured.capture());
            assertThat(captured.getValue().postNames()).containsExactly("a", "b");
            assertThat(captured.getValue().targetLibraryName()).isEqualTo("lib");
            assertThat(captured.getValue().publish()).isNull();
            assertThat(endpoint.groupVersion().group()).isEqualTo("console.api.my-docs.tsdaer.run");
        } finally {
            server.disposeNow();
        }
    }
}
