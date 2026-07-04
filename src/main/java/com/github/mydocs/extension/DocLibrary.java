package com.github.mydocs.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * <p>文档库（DocLibrary）：一套独立文档的容器。</p>
 * <p>每个文档库拥有自己的名称、别名与描述，其下的 {@link Doc} 通过 {@code spec.libraryName}
 * 归属到某个文档库。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "docs.halo.run",
    version = "v1alpha1",
    kind = "DocLibrary",
    plural = "doclibraries",
    singular = "doclibrary"
)
public class DocLibrary extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "DocLibrarySpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100,
            description = "文档库标题")
        private String title;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100,
            description = "访问别名，全局唯一")
        private String slug;

        @Schema(maxLength = 500, description = "文档库描述")
        private String description;

        @Schema(description = "封面图地址")
        private String cover;

        @Schema(description = "排序权重，值越小越靠前")
        private Integer priority;
    }
}
