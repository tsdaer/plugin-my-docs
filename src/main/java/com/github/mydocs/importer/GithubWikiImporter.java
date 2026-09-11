package com.github.mydocs.importer;

import static com.github.mydocs.importer.GithubWikiMarkdownConverter.convertLinks;
import static com.github.mydocs.importer.GithubWikiMarkdownConverter.isSpecialFile;
import static com.github.mydocs.importer.GithubWikiMarkdownConverter.normalizePageKey;
import static com.github.mydocs.importer.GithubWikiMarkdownConverter.slugify;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.startsWith;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * <p>GitHub Wiki 导入执行器：把一组 Wiki Markdown 文件（来自仓库拉取或 zip 上传）转换并
 * 写入目标文档库。</p>
 * <p>执行流程：解析目标库（新建或已有）→ 预拉库内已有 slug 一次性去重 → 逐篇转换 Wiki 链接 →
 * 顺序创建 {@link Doc}（渲染与搜索索引由 {@code DocReconciler} 兜底）→ 汇总报告。</p>
 *
 * @author tsdaer
 * @since 1.3.0
 */
@Component
public class GithubWikiImporter {

    public static final String LIBRARY_DESCRIPTION = "从 GitHub Wiki 导入";

    private final ReactiveExtensionClient client;

    public GithubWikiImporter(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * 导入一组 Wiki 文件。
     *
     * @param wikiFiles         以文件基本名（不含扩展名）为 key 的 Markdown 内容
     * @param skippedOtherFiles 来源阶段已跳过的文件数（如 zip 内非 Markdown 文件），计入报告
     * @param request           导入请求（目标库与发布选项）
     * @return 导入报告
     */
    public Mono<GithubWikiImportReport> importDocs(Map<String, String> wikiFiles,
        int skippedOtherFiles, GithubWikiImportRequest request) {
        // 先于建库校验：避免空来源先创建出空文档库再报错。
        boolean hasPages = wikiFiles.keySet().stream().anyMatch(name -> !isSpecialFile(name));
        if (!hasPages) {
            return Mono.error(new ServerWebInputException(
                "未在导入来源中找到任何可导入的 Wiki 页面。"));
        }
        return resolveTargetLibrary(request)
            .flatMap(library -> importIntoLibrary(wikiFiles, skippedOtherFiles, request, library));
    }

    private Mono<GithubWikiImportReport> importIntoLibrary(Map<String, String> wikiFiles,
        int skippedOtherFiles, GithubWikiImportRequest request, DocLibrary library) {
        String libraryName = library.getMetadata().getName();
        return listLibraryDocs(libraryName)
            .flatMap(existingDocs -> {
                var preparation = prepare(wikiFiles, existingDocs);
                if (preparation.getDocs().isEmpty()) {
                    return Mono.error(new ServerWebInputException(
                        "未在导入来源中找到任何可导入的 Wiki 页面。"));
                }
                var created = new AtomicInteger();
                return Flux.fromIterable(preparation.getDocs())
                    .concatMap(prepared -> client.create(
                        buildDoc(prepared, libraryName, request.publish())))
                    .doOnNext(doc -> created.incrementAndGet())
                    .then(Mono.defer(() -> Mono.just(buildReport(
                        library, preparation, skippedOtherFiles, created.get()))));
            });
    }

    private Mono<DocLibrary> resolveTargetLibrary(GithubWikiImportRequest request) {
        boolean hasExisting = StringUtils.hasText(request.targetLibraryName());
        boolean hasNewTitle = StringUtils.hasText(request.newLibraryTitle());
        if (hasExisting == hasNewTitle) {
            return Mono.error(new ServerWebInputException(
                "请从「导入到已有文档库」与「新建文档库」中二选一。"));
        }
        if (hasExisting) {
            return client.fetch(DocLibrary.class, request.targetLibraryName())
                .switchIfEmpty(Mono.error(new ServerWebInputException(
                    "目标文档库不存在：" + request.targetLibraryName())));
        }
        var title = request.newLibraryTitle().trim();
        if (title.length() > 100) {
            return Mono.error(new ServerWebInputException("新建文档库标题长度不能超过 100。"));
        }
        boolean slugProvided = StringUtils.hasText(request.newLibrarySlug());
        var desiredSlug = slugProvided
            ? request.newLibrarySlug().trim() : slugify(title);
        if (desiredSlug.isEmpty() || desiredSlug.length() > 100) {
            return Mono.error(new ServerWebInputException(
                "新建文档库别名需为 1-100 个字符。"));
        }
        return reserveLibrarySlug(desiredSlug, slugProvided)
            .flatMap(slug -> client.create(buildLibrary(title, slug)));
    }

    /**
     * 确保新建库的 slug 全局唯一：用户显式提供的别名冲突时报错，自动生成的别名追加序号去重。
     * 以前缀查询一次取回所有同前缀别名，覆盖 {@code -2}、{@code -3} 等候选。
     */
    private Mono<String> reserveLibrarySlug(String desiredSlug, boolean userProvided) {
        return client.listAll(DocLibrary.class, ListOptions.builder()
                .fieldQuery(startsWith("spec.slug", desiredSlug))
                .build(), Sort.unsorted())
            .map(library -> library.getSpec().getSlug())
            .collectList()
            .flatMap(taken -> {
                if (!taken.contains(desiredSlug)) {
                    return Mono.just(desiredSlug);
                }
                if (userProvided) {
                    return Mono.error(new ServerWebInputException(
                        "文档库别名 [" + desiredSlug + "] 已存在，请更换。"));
                }
                for (int i = 2; ; i++) {
                    String candidate = desiredSlug + "-" + i;
                    if (!taken.contains(candidate)) {
                        return Mono.just(candidate);
                    }
                }
            });
    }

    private DocLibrary buildLibrary(String title, String slug) {
        var library = new DocLibrary();
        var metadata = new Metadata();
        metadata.setGenerateName("doc-library-");
        library.setMetadata(metadata);
        var spec = new DocLibrary.Spec();
        spec.setTitle(title);
        spec.setSlug(slug);
        spec.setDescription(LIBRARY_DESCRIPTION);
        library.setSpec(spec);
        return library;
    }

    private Mono<List<Doc>> listLibraryDocs(String libraryName) {
        return client.listAll(Doc.class, ListOptions.builder()
                .fieldQuery(equal("spec.libraryName", libraryName))
                .build(), Sort.unsorted())
            .collectList();
    }

    /**
     * 纯转换阶段：过滤特殊文件、排序、slug 一次性去重（含库内已有 slug）、转换 Wiki 链接。
     */
    private Preparation prepare(Map<String, String> wikiFiles, List<Doc> existingDocs) {
        List<String> warnings = new ArrayList<>();
        Set<String> usedSlugs = new HashSet<>();
        for (Doc existing : existingDocs) {
            if (existing.getSpec() != null && StringUtils.hasText(existing.getSpec().getSlug())) {
                usedSlugs.add(existing.getSpec().getSlug());
            }
        }
        int priorityStart = existingDocs.stream()
            .map(doc -> doc.getSpec() == null ? null : doc.getSpec().getPriority())
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(-1) + 1;

        List<String> pageNames = new ArrayList<>();
        int specialFiles = 0;
        for (String baseName : wikiFiles.keySet()) {
            if (isSpecialFile(baseName)) {
                specialFiles++;
            } else {
                pageNames.add(baseName);
            }
        }
        // Home 为 Wiki 首页，排在最前；其余按文件名不区分大小写排序，保持稳定的目录顺序。
        pageNames.sort((a, b) -> {
            boolean homeA = "home".equalsIgnoreCase(a);
            boolean homeB = "home".equalsIgnoreCase(b);
            if (homeA != homeB) {
                return homeA ? -1 : 1;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });

        Map<String, String> slugByBase = new LinkedHashMap<>();
        Map<String, String> slugByPageKey = new LinkedHashMap<>();
        int slugAdjusted = 0;
        for (String baseName : pageNames) {
            String candidate = slugify(baseName);
            String finalSlug = candidate;
            for (int i = 2; usedSlugs.contains(finalSlug); i++) {
                finalSlug = candidate + "-" + i;
            }
            if (!finalSlug.equals(candidate)) {
                slugAdjusted++;
            }
            usedSlugs.add(finalSlug);
            slugByBase.put(baseName, finalSlug);
            String pageKey = normalizePageKey(baseName);
            if (slugByPageKey.containsKey(pageKey)) {
                warnings.add("页面名冲突（忽略大小写与空格/连字符差异）：“"
                    + baseName + "”的链接将指向先前的同名页面。");
            } else {
                slugByPageKey.put(pageKey, finalSlug);
            }
        }

        List<PreparedDoc> docs = new ArrayList<>(pageNames.size());
        int unresolvedLinks = 0;
        int priority = priorityStart;
        for (String baseName : pageNames) {
            var conversion = convertLinks(wikiFiles.get(baseName), slugByPageKey::get);
            unresolvedLinks += conversion.unresolvedLinks();
            docs.add(new PreparedDoc(baseName, slugByBase.get(baseName),
                conversion.markdown(), priority++));
        }
        return new Preparation(docs, specialFiles, slugAdjusted, unresolvedLinks, warnings);
    }

    private Doc buildDoc(PreparedDoc prepared, String libraryName, boolean publish) {
        var doc = new Doc();
        var metadata = new Metadata();
        metadata.setGenerateName("doc-");
        doc.setMetadata(metadata);
        var spec = new Doc.Spec();
        spec.setTitle(prepared.getBaseName());
        spec.setSlug(prepared.getSlug());
        spec.setLibraryName(libraryName);
        spec.setPriority(prepared.getPriority());
        spec.setRaw(prepared.getMarkdown());
        spec.setRawType("markdown");
        spec.setPublished(publish);
        spec.setPublishTime(publish ? Instant.now() : null);
        doc.setSpec(spec);
        return doc;
    }

    private GithubWikiImportReport buildReport(DocLibrary library, Preparation preparation,
        int skippedOtherFiles, int imported) {
        var warnings = new ArrayList<String>(preparation.getWarnings());
        if (preparation.getUnresolvedLinks() > 0) {
            warnings.add("有 " + preparation.getUnresolvedLinks()
                + " 个 Wiki 链接未找到对应页面，已降级为纯文本。");
        }
        return new GithubWikiImportReport(
            library.getMetadata().getName(),
            library.getSpec().getSlug(),
            imported,
            preparation.getSpecialFiles() + skippedOtherFiles,
            preparation.getSlugAdjusted(),
            preparation.getUnresolvedLinks(),
            warnings);
    }

    /**
     * 转换阶段产物。
     */
    @Value
    private static class Preparation {
        List<PreparedDoc> docs;
        int specialFiles;
        int slugAdjusted;
        int unresolvedLinks;
        List<String> warnings;
    }

    /**
     * 单篇待导入文档。
     */
    @Value
    private static class PreparedDoc {
        String baseName;
        String slug;
        String markdown;
        int priority;
    }
}
