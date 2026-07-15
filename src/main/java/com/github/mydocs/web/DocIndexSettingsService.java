package com.github.mydocs.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Component
public class DocIndexSettingsService {

    static final String BASIC_GROUP = "basic";
    private static final Set<String> CONTENT_THEMES =
        Set.of("light", "dark", "wechat", "ant-design", "custom");
    private static final Set<String> LEGACY_CONTENT_THEMES =
        Set.of("light", "dark", "wechat", "ant-design");
    private static final Pattern CSS_CLASS_PATTERN =
        Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]*$");
    private static final Pattern CODE_THEME_PATTERN = Pattern.compile("^[a-z0-9-]{1,100}$");

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
        String legacyContentTheme = StringUtils.hasText(settings.getRenderContentTheme())
            && LEGACY_CONTENT_THEMES.contains(settings.getRenderContentTheme())
            ? settings.getRenderContentTheme() : null;
        String legacyCodeTheme = normalizeCodeTheme(settings.getRenderCodeTheme(), "");
        String lightTheme = normalizeContentTheme(settings.getRenderContentThemeLight(),
            legacyContentTheme == null ? "light" : legacyContentTheme);
        String darkTheme = normalizeContentTheme(settings.getRenderContentThemeDark(),
            "light".equals(legacyContentTheme) ? "dark"
                : legacyContentTheme == null ? "dark" : legacyContentTheme);
        String lightUrl = normalizeThemeUrl(settings.getRenderContentThemeLightUrl());
        String darkUrl = normalizeThemeUrl(settings.getRenderContentThemeDarkUrl());
        normalized.setRenderContentThemeLight(
            "custom".equals(lightTheme) && !StringUtils.hasText(lightUrl) ? "light" : lightTheme);
        normalized.setRenderContentThemeDark(
            "custom".equals(darkTheme) && !StringUtils.hasText(darkUrl) ? "dark" : darkTheme);
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
        normalized.setCustomHeadHtml(nullToEmpty(settings.getCustomHeadHtml()));
        normalized.setCustomBodyHtml(nullToEmpty(settings.getCustomBodyHtml()));
        return normalized;
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

    private static String normalizeContentTheme(String value, String fallback) {
        String normalized = StringUtils.trimWhitespace(value);
        return StringUtils.hasText(normalized) && CONTENT_THEMES.contains(normalized)
            ? normalized : fallback;
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
        settings.setRenderContentThemeLight("light");
        settings.setRenderContentThemeDark("dark");
        settings.setRenderContentThemeLightUrl("");
        settings.setRenderContentThemeDarkUrl("");
        settings.setRenderContentThemeLightClass("markdown-body");
        settings.setRenderContentThemeDarkClass("markdown-body");
        settings.setRenderCodeThemeLight("github");
        settings.setRenderCodeThemeDark("github-dark");
        return settings;
    }
}
