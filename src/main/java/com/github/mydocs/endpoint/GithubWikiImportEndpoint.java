package com.github.mydocs.endpoint;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.github.mydocs.importer.GithubWikiImportReport;
import com.github.mydocs.importer.GithubWikiImportRequest;
import com.github.mydocs.importer.GithubWikiImporter;
import com.github.mydocs.importer.GithubWikiRepositoryFetcher;
import com.github.mydocs.importer.GithubWikiZipReader;
import java.io.IOException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * <p>GitHub Wiki 导入的 Console 自定义 API，提供两个通道：</p>
 * <ul>
 *   <li>{@code POST /github-wiki-imports/repository}（JSON）：由服务端浅克隆
 *       {@code {repo}.wiki.git} 拉取页面；</li>
 *   <li>{@code POST /github-wiki-imports/zip}（multipart）：上传 clone 后打包的
 *       Wiki 压缩包。</li>
 * </ul>
 * <p>路径自动加上前缀 {@code /apis/console.api.my-docs.tsdaer.run/v1alpha1/}。</p>
 *
 * @author tsdaer
 * @since 1.3.0
 */
@Component
public class GithubWikiImportEndpoint implements CustomEndpoint {

    private static final int MAX_ZIP_BYTES = 20 * 1024 * 1024;

    private final GithubWikiRepositoryFetcher repositoryFetcher;
    private final GithubWikiImporter importer;

    public GithubWikiImportEndpoint(GithubWikiRepositoryFetcher repositoryFetcher,
        GithubWikiImporter importer) {
        this.repositoryFetcher = repositoryFetcher;
        this.importer = importer;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .POST("/github-wiki-imports/repository", accept(APPLICATION_JSON), this::importFromRepository)
            .POST("/github-wiki-imports/zip", accept(MULTIPART_FORM_DATA), this::importFromZip)
            .build();
    }

    private Mono<ServerResponse> importFromRepository(ServerRequest request) {
        return request.bodyToMono(RepositoryImportRequest.class)
            .flatMap(body -> {
                if (!StringUtils.hasText(body.repoUrl())) {
                    return Mono.error(new ServerWebInputException("请填写 GitHub 仓库地址。"));
                }
                var importRequest = new GithubWikiImportRequest(body.targetLibraryName(),
                    body.newLibraryTitle(), body.newLibrarySlug(),
                    body.publish() == null || body.publish());
                return repositoryFetcher.fetch(body.repoUrl(), body.token())
                    .flatMap(files -> importer.importDocs(files, 0, importRequest));
            })
            .flatMap(report -> ServerResponse.ok().bodyValue(report));
    }

    private Mono<ServerResponse> importFromZip(ServerRequest request) {
        return request.multipartData().flatMap(multipart -> {
            Part part = multipart.getFirst("file");
            if (!(part instanceof FilePart filePart)) {
                return Mono.error(new ServerWebInputException("请选择要上传的 Wiki 压缩包。"));
            }
            var importRequest = new GithubWikiImportRequest(
                fieldValue(multipart, "targetLibraryName"),
                fieldValue(multipart, "newLibraryTitle"),
                fieldValue(multipart, "newLibrarySlug"),
                !"false".equalsIgnoreCase(fieldValue(multipart, "publish")));
            return DataBufferUtils.join(filePart.content())
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .flatMap(bytes -> {
                    if (bytes.length > MAX_ZIP_BYTES) {
                        return Mono.error(new ServerWebInputException(
                            "压缩包超过 20MB 上限，请仅打包 Markdown 文件。"));
                    }
                    GithubWikiZipReader.Result result;
                    try {
                        result = GithubWikiZipReader.read(bytes);
                    } catch (IOException e) {
                        return Mono.<GithubWikiZipReader.Result>error(
                            new ServerWebInputException("压缩包解析失败：" + e.getMessage()));
                    }
                    return Mono.just(result);
                })
                .flatMap(result -> importer.importDocs(
                    result.files(), result.skippedOtherFiles(), importRequest));
        }).flatMap(report -> ServerResponse.ok().bodyValue(report));
    }

    private String fieldValue(MultiValueMap<String, Part> multipart, String name) {
        Part part = multipart.getFirst(name);
        return part instanceof FormFieldPart fieldPart ? fieldPart.value() : null;
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.my-docs.tsdaer.run", "v1alpha1");
    }

    /**
     * 仓库通道的 JSON 请求体。
     */
    record RepositoryImportRequest(
        String repoUrl,
        String token,
        String targetLibraryName,
        String newLibraryTitle,
        String newLibrarySlug,
        Boolean publish
    ) {
    }
}
