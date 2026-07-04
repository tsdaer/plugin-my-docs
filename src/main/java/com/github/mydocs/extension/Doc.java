package com.github.mydocs.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * <p>文档（Doc）：单篇文档。</p>
 * <p>通过 {@code spec.libraryName} 归属到某个 {@link DocLibrary}，通过 {@code spec.parent}
 * 自引用父文档，从而在库内组织成层级目录树；同级顺序由 {@code spec.priority} 决定。</p>
 * <p>正文以 Markdown 为原始格式：{@code spec.raw} 存 Markdown 原文，{@code spec.content}
 * 存渲染后的 HTML，{@code spec.rawType} 标记原始格式。</p>
 *
 * @author tsdaer
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "docs.halo.run",
    version = "v1alpha1",
    kind = "Doc",
    plural = "docs",
    singular = "doc"
)
public class Doc extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "DocSpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200,
            description = "文档标题")
        private String title;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200,
            description = "访问别名，库内唯一")
        private String slug;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
            description = "所属文档库的 metadata.name")
        private String libraryName;

        @Schema(description = "父文档的 metadata.name，为空表示顶层节点")
        private String parent;

        @Schema(description = "同级排序权重，值越小越靠前")
        private Integer priority;

        @Schema(description = "正文渲染后的 HTML，由后端根据 raw 渲染得到")
        private String content;

        @Schema(description = "正文原始内容（Markdown）")
        private String raw;

        @Schema(description = "正文原始格式，如 markdown、html")
        private String rawType;

        @Schema(description = "是否已发布")
        private Boolean published;

        @Schema(description = "发布时间")
        private Instant publishTime;
    }
}
