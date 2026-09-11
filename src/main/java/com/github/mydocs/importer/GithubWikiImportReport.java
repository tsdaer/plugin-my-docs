package com.github.mydocs.importer;

import java.util.List;

/**
 * GitHub Wiki 导入结果报告。
 *
 * @param libraryName      目标文档库的 metadata.name
 * @param librarySlug      目标文档库的访问别名
 * @param imported         新建成功的文档数
 * @param updated          匹配到已有文档并原地更新的文档数（从 Wiki 更新）
 * @param skipped          跳过的文件数（侧栏/页眉/页脚等特殊文件、非 Markdown 文件、重名文件）
 * @param slugAdjusted     因库内别名冲突而自动追加序号的文档数
 * @param unresolvedLinks  未解析（找不到对应页面）的 Wiki 链接数，已降级为纯文本
 * @param warnings         需要人工关注的提示信息
 * @author tsdaer
 * @since 1.3.0
 */
public record GithubWikiImportReport(
    String libraryName,
    String librarySlug,
    int imported,
    int updated,
    int skipped,
    int slugAdjusted,
    int unresolvedLinks,
    List<String> warnings
) {
}
