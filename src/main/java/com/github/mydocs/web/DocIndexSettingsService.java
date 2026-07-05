package com.github.mydocs.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Component
public class DocIndexSettingsService {

    static final String BASIC_GROUP = "basic";

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

    private static DocIndexSettings defaultSettings() {
        return new DocIndexSettings();
    }
}
