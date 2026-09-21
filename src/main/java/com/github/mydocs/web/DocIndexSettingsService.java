package com.github.mydocs.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Component
public class DocIndexSettingsService {

    /** 设置分组名，同时被端点测试复用。 */
    public static final String BASIC_GROUP = "basic";
    private static final Pattern CSS_CLASS_PATTERN =
        Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]*$");
    private static final Pattern CODE_THEME_PATTERN = Pattern.compile("^[a-z0-9-]{1,100}$");

    private static final Logger log = LoggerFactory.getLogger(DocIndexSettingsService.class);

    private final ReactiveSettingFetcher settingFetcher;

    public DocIndexSettingsService(ReactiveSettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    public Mono<DocIndexSettings> fetch() {
        return settingFetcher.fetch(BASIC_GROUP, DocIndexSettings.class)
            .defaultIfEmpty(new DocIndexSettings())
            .map(this::normalize)
            .onErrorReturn(defaultSettings());
    }

    private DocIndexSettings normalize(DocIndexSettings settings) {
        var normalized = defaultSettings();
        normalized.setLibraryIndexDefaultColumns(
            positive(settings.getLibraryIndexDefaultColumns(), 2, 12));
        normalized.setLibraryIndexDefaultMaxRows(
            positive(settings.getLibraryIndexDefaultMaxRows(), 2, 24));
        normalized.setLibraryIndexPageLayouts(
            normalizePageLayouts(settings.getLibraryIndexPageLayouts()));
        normalized.setLibraryIndexRowLayouts(
            normalizeRowLayouts(settings.getLibraryIndexRowLayouts()));
        normalized.setLibraryIndexPlacements(normalizePlacements(settings.getLibraryIndexPlacements()));
        normalized.setLibraryIndexFolderTitles(
            normalizeFolderTitles(settings.getLibraryIndexFolderTitles()));
        String legacyCodeTheme = normalizeCodeTheme(settings.getRenderCodeTheme(), "");
        String lightUrl = normalizeThemeUrl(settings.getRenderContentThemeLightUrl());
        String darkUrl = normalizeThemeUrl(settings.getRenderContentThemeDarkUrl());
        normalized.setRenderContentThemeLightUrl(lightUrl);
        normalized.setRenderContentThemeDarkUrl(darkUrl);
        normalized.setRenderContentThemeLightClass(
            normalizeThemeClasses(settings.getRenderContentThemeLightClass()));
        normalized.setRenderContentThemeDarkClass(
            normalizeThemeClasses(settings.getRenderContentThemeDarkClass()));
        normalized.setRenderCodeThemeLight(normalizeCodeTheme(settings.getRenderCodeThemeLight(),
            StringUtils.hasText(legacyCodeTheme) ? legacyCodeTheme : "github"));
        normalized.setRenderCodeThemeDark(normalizeCodeTheme(settings.getRenderCodeThemeDark(),
            "github".equals(legacyCodeTheme) ? "github-dark"
                : StringUtils.hasText(legacyCodeTheme) ? legacyCodeTheme : "github-dark"));
        normalized.setRenderLineNumber(Boolean.TRUE.equals(settings.getRenderLineNumber()));
        normalized.setRenderAutoSpace(Boolean.TRUE.equals(settings.getRenderAutoSpace()));
        normalized.setRenderGfmAutoLink(!Boolean.FALSE.equals(settings.getRenderGfmAutoLink()));
        normalized.setRenderFootnotes(!Boolean.FALSE.equals(settings.getRenderFootnotes()));
        normalized.setRenderMark(Boolean.TRUE.equals(settings.getRenderMark()));
        normalized.setRenderFixTermTypo(Boolean.TRUE.equals(settings.getRenderFixTermTypo()));
        normalized.setRenderParagraphBeginningSpace(
            Boolean.TRUE.equals(settings.getRenderParagraphBeginningSpace()));
        normalized.setRenderCodeBlockPreview(!Boolean.FALSE.equals(settings.getRenderCodeBlockPreview()));
        normalized.setRenderMathBlockPreview(!Boolean.FALSE.equals(settings.getRenderMathBlockPreview()));
        normalized.setMediaProxyEnabled(Boolean.TRUE.equals(settings.getMediaProxyEnabled()));
        normalized.setMediaProxyAllowedHosts(
            normalizeMediaProxyAllowedHosts(settings.getMediaProxyAllowedHosts()));
        normalized.setMediaProxyRequestHeaders(
            normalizeMediaProxyRequestHeaders(settings.getMediaProxyRequestHeaders(),
                normalized.getMediaProxyAllowedHosts()));
        normalized.setMediaProxyCredentialRules(
            normalizeMediaProxyCredentialRules(settings.getMediaProxyCredentialRules(),
                normalized.getMediaProxyAllowedHosts()));
        normalized.setMediaProxyMaxBytes(
            positive(settings.getMediaProxyMaxBytes(), 536870912, 2147483647));
        normalized.setCustomHeadHtml(nullToEmpty(settings.getCustomHeadHtml()));
        normalized.setCustomBodyHtml(nullToEmpty(settings.getCustomBodyHtml()));
        return normalized;
    }

    /**
     * 签名凭证规则：解析后只保留主机能被允许清单命中的规则，格式与 {@code MediaProxyRules}
     * 保持一致。解析失败或未命中允许清单的行会在服务端日志里告警（值一律脱敏）——
     * 否则一条写错的主机名会让私有桶的请求静默变成「未签名」，排查起来只剩 400。
     */
    static List<String> normalizeMediaProxyCredentialRules(List<String> source,
        List<String> allowedHosts) {
        var lines = rawMediaProxyLines(source);
        var rules = MediaProxyRules.parseSigningRules(lines);
        List<String> normalized = rules.stream()
            .filter(rule -> allowedHosts.stream()
                .anyMatch(allowed -> MediaProxyRules.overlaps(allowed, rule.hostPattern())))
            .map(rule -> rule.hostPattern() + ": access: " + rule.accessKey()
                + "\n" + rule.hostPattern() + ": secret: " + rule.secretKey()
                + ("auto".equals(rule.region()) ? ""
                    : "\n" + rule.hostPattern() + ": region: " + rule.region()))
            .toList();

        for (String line : lines) {
            if (line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split(":", 3);
            String hostPattern = parts.length == 3
                ? parts[0].trim().toLowerCase(Locale.ROOT) : null;
            if (hostPattern == null || !MediaProxyRules.isValidHostPattern(hostPattern)) {
                log.warn("忽略无法解析的媒体代理签名凭证规则：{}（每行应为「主机: 键: 值」，"
                    + "键取 access / secret / region）", maskCredentialLine(line));
                continue;
            }
            boolean kept = normalized.stream().anyMatch(rule ->
                rule.startsWith(hostPattern + ":"));
            if (!kept) {
                log.warn("媒体代理签名凭证规则未生效（主机 {}）：主机需与允许主机清单一致，"
                    + "且 access 与 secret 必须成对出现", hostPattern);
            }
        }
        return normalized;
    }

    /** 日志脱敏：只保留主机与键名，值换成 ***；连格式都不对时截断原文。 */
    private static String maskCredentialLine(String line) {
        String[] parts = line.split(":", 3);
        if (parts.length == 3) {
            return parts[0].trim() + ": " + parts[1].trim() + ": ***";
        }
        return line.length() <= 40 ? line + ": ***" : line.substring(0, 40) + "…: ***";
    }

    /**
     * 允许代理的主机：每行一条，支持 {@code *.example.com} 这类前缀通配，
     * 允许附带 {@code :端口}。写坏的行直接丢弃——配置项宁可少代理，也不能放宽成任意主机。
     */
    static List<String> normalizeMediaProxyAllowedHosts(List<String> source) {
        return rawMediaProxyLines(source).stream()
            .filter(line -> !line.startsWith("#"))
            .map(line -> line.toLowerCase(java.util.Locale.ROOT))
            .filter(MediaProxyRules::isValidHostPattern)
            .distinct()
            .limit(50)
            .toList();
    }

    /**
     * 请求头规则：每行 {@code 主机: 头名: 头值}，主机部分必须能被某条允许主机规则匹配，
     * 否则丢弃。头名限制为 token 字符，并拒掉带换行的头值，避免请求头注入。
     * 头值区分大小写（Bearer 令牌之类），只把主机部分转小写。
     */
    static List<String> normalizeMediaProxyRequestHeaders(List<String> source,
        List<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return List.of();
        }
        return rawMediaProxyLines(source).stream()
            .filter(line -> !line.startsWith("#"))
            .map(MediaProxyRules::parseHeaderRule)
            .filter(java.util.Objects::nonNull)
            .filter(rule -> MediaProxyRules.HEADER_NAME_PATTERN.matcher(rule[1]).matches())
            .filter(rule -> !rule[2].isEmpty() && rule[2].length() <= 2048)
            .filter(rule -> !rule[2].contains("\r") && !rule[2].contains("\n"))
            .filter(rule -> allowedHosts.stream()
                .anyMatch(allowed -> MediaProxyRules.overlaps(allowed, rule[0])))
            .map(rule -> rule[0] + ": " + rule[1] + ": " + rule[2])
            .distinct()
            .limit(20)
            .toList();
    }

    /** 多行文本设置可能是「一行一个元素」，也可能是「一个元素里带换行」，两种都拆平，保留原始大小写。 */
    private static List<String> rawMediaProxyLines(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String raw : source) {
            if (raw == null) {
                continue;
            }
            for (String line : raw.split("\\R")) {
                String trimmed = StringUtils.trimWhitespace(line);
                if (StringUtils.hasText(trimmed)) {
                    lines.add(trimmed);
                }
            }
        }
        return List.copyOf(lines);
    }

    private static List<DocIndexSettings.LibraryPageLayout> normalizePageLayouts(
        List<DocIndexSettings.LibraryPageLayout> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<DocIndexSettings.LibraryPageLayout> items = new ArrayList<>();
        Set<Integer> seenPages = new LinkedHashSet<>();
        for (var item : source) {
            if (item == null) {
                continue;
            }
            int page = positive(item.getPage(), 0, 999);
            int maxRows = positive(item.getMaxRows(), 0, 24);
            if (page < 1 || maxRows < 1 || !seenPages.add(page)) {
                continue;
            }
            var normalized = new DocIndexSettings.LibraryPageLayout();
            normalized.setPage(page);
            normalized.setMaxRows(maxRows);
            items.add(normalized);
        }
        items.sort(java.util.Comparator.comparing(DocIndexSettings.LibraryPageLayout::getPage));
        return List.copyOf(items);
    }

    private static List<DocIndexSettings.LibraryRowLayout> normalizeRowLayouts(
        List<DocIndexSettings.LibraryRowLayout> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<DocIndexSettings.LibraryRowLayout> items = new ArrayList<>();
        Set<Integer> seenRows = new LinkedHashSet<>();
        for (var item : source) {
            if (item == null) {
                continue;
            }
            int row = positive(item.getRow(), 0, 999);
            int columns = positive(item.getColumns(), 0, 24);
            if (row < 1 || columns < 1 || !seenRows.add(row)) {
                continue;
            }
            var normalized = new DocIndexSettings.LibraryRowLayout();
            normalized.setRow(row);
            normalized.setColumns(columns);
            items.add(normalized);
        }
        items.sort(java.util.Comparator.comparing(DocIndexSettings.LibraryRowLayout::getRow));
        return List.copyOf(items);
    }

    private static List<DocIndexSettings.LibraryPlacement> normalizePlacements(
        List<DocIndexSettings.LibraryPlacement> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<DocIndexSettings.LibraryPlacement> items = new ArrayList<>();
        Set<String> seenLibraries = new LinkedHashSet<>();
        for (var item : source) {
            if (item == null) {
                continue;
            }
            String libraryName = StringUtils.trimWhitespace(item.getLibraryName());
            int row = positive(item.getRow(), 0, 999);
            int column = positive(item.getColumn(), 0, 24);
            if (!StringUtils.hasText(libraryName) || row < 1 || column < 1
                || !seenLibraries.add(libraryName)) {
                continue;
            }
            var normalized = new DocIndexSettings.LibraryPlacement();
            normalized.setLibraryName(libraryName);
            normalized.setRow(row);
            normalized.setColumn(column);
            items.add(normalized);
        }
        items.sort(java.util.Comparator
            .comparing(DocIndexSettings.LibraryPlacement::getRow)
            .thenComparing(DocIndexSettings.LibraryPlacement::getColumn)
            .thenComparing(DocIndexSettings.LibraryPlacement::getLibraryName));
        return List.copyOf(items);
    }

    private static List<DocIndexSettings.LibraryFolderTitle> normalizeFolderTitles(
        List<DocIndexSettings.LibraryFolderTitle> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<DocIndexSettings.LibraryFolderTitle> items = new ArrayList<>();
        Set<String> seenCoordinates = new LinkedHashSet<>();
        for (var item : source) {
            if (item == null) {
                continue;
            }
            int row = positive(item.getRow(), 0, 999);
            int column = positive(item.getColumn(), 0, 24);
            String title = StringUtils.trimWhitespace(item.getTitle());
            String key = row + ":" + column;
            if (row < 1 || column < 1 || !StringUtils.hasText(title)
                || !seenCoordinates.add(key)) {
                continue;
            }
            var normalized = new DocIndexSettings.LibraryFolderTitle();
            normalized.setRow(row);
            normalized.setColumn(column);
            normalized.setTitle(title);
            normalized.setDescription(StringUtils.trimWhitespace(item.getDescription()));
            items.add(normalized);
        }
        items.sort(java.util.Comparator
            .comparing(DocIndexSettings.LibraryFolderTitle::getRow)
            .thenComparing(DocIndexSettings.LibraryFolderTitle::getColumn));
        return List.copyOf(items);
    }

    private static int positive(Integer value, int fallback, int max) {
        if (value == null || value < 1) {
            return fallback;
        }
        return Math.min(value, max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeThemeUrl(String value) {
        String normalized = StringUtils.trimWhitespace(value);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        if (normalized.startsWith("/") && !normalized.startsWith("//")) {
            return normalized;
        }
        try {
            var uri = java.net.URI.create(normalized);
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost())
                ? normalized : "";
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String normalizeThemeClasses(String value) {
        String normalized = StringUtils.trimWhitespace(value);
        if (!StringUtils.hasText(normalized)) {
            return "markdown-body";
        }
        var classes = java.util.Arrays.stream(normalized.split("\\s+"))
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (classes.isEmpty() || classes.size() > 10
            || classes.stream().anyMatch(item -> !CSS_CLASS_PATTERN.matcher(item).matches())) {
            return "markdown-body";
        }
        return String.join(" ", classes);
    }

    private static String normalizeCodeTheme(String value, String fallback) {
        String normalized = StringUtils.trimWhitespace(value);
        return StringUtils.hasText(normalized) && CODE_THEME_PATTERN.matcher(normalized).matches()
            ? normalized : fallback;
    }

    private static DocIndexSettings defaultSettings() {
        var settings = new DocIndexSettings();
        settings.setRenderContentThemeLightUrl("");
        settings.setRenderContentThemeDarkUrl("");
        settings.setRenderContentThemeLightClass("markdown-body");
        settings.setRenderContentThemeDarkClass("markdown-body");
        settings.setRenderCodeThemeLight("github");
        settings.setRenderCodeThemeDark("github-dark");
        return settings;
    }
}
