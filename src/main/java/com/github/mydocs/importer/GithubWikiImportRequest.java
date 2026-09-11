package com.github.mydocs.importer;

/**
 * GitHub Wiki 导入请求参数（仓库拉取与 zip 上传两个通道共用）。
 *
 * @param targetLibraryName 导入到已有文档库的 metadata.name；与 {@code newLibraryTitle} 二选一
 * @param newLibraryTitle   新建文档库的标题（slug 缺省时由标题生成）
 * @param newLibrarySlug    新建文档库的别名；提供时冲突将直接报错，未提供时自动去重
 * @param publish           导入的文档是否立即发布
 * @author tsdaer
 * @since 1.3.0
 */
public record GithubWikiImportRequest(
    String targetLibraryName,
    String newLibraryTitle,
    String newLibrarySlug,
    boolean publish
) {
}
