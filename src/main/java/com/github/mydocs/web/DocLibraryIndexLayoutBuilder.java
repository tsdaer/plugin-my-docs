package com.github.mydocs.web;

import com.github.mydocs.extension.DocLibrary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DocLibraryIndexLayoutBuilder {

    public DocLibraryIndexLayout build(List<DocLibrary> libraries, DocIndexSettings settings,
        int requestedPage) {
        if (libraries == null || libraries.isEmpty()) {
            return DocLibraryIndexLayout.empty();
        }

        Map<String, Coordinate> placementByLibrary = new LinkedHashMap<>();
        Map<Coordinate, FolderMeta> folderMetaByCoordinate = new LinkedHashMap<>();
        Map<Integer, Integer> maxRowsByPage = new LinkedHashMap<>();
        Map<Integer, Integer> columnsByRow = new LinkedHashMap<>();

        for (var pageLayout : settings.getLibraryIndexPageLayouts()) {
            maxRowsByPage.putIfAbsent(pageLayout.getPage(), pageLayout.getMaxRows());
        }
        for (var rowLayout : settings.getLibraryIndexRowLayouts()) {
            columnsByRow.putIfAbsent(rowLayout.getRow(), rowLayout.getColumns());
        }
        for (var placement : settings.getLibraryIndexPlacements()) {
            placementByLibrary.putIfAbsent(placement.getLibraryName(),
                new Coordinate(placement.getRow(), placement.getColumn()));
        }
        for (var folderTitle : settings.getLibraryIndexFolderTitles()) {
            folderMetaByCoordinate.putIfAbsent(
                new Coordinate(folderTitle.getRow(), folderTitle.getColumn()),
                new FolderMeta(folderTitle.getTitle(), folderTitle.getDescription())
            );
        }

        Map<Coordinate, List<DocLibrary>> slotLibraries = new LinkedHashMap<>();
        Set<String> assignedLibraries = new LinkedHashSet<>();
        int maxReferencedRow = 0;
        for (var rowLayout : settings.getLibraryIndexRowLayouts()) {
            maxReferencedRow = Math.max(maxReferencedRow, rowLayout.getRow());
        }
        for (var coordinate : placementByLibrary.values()) {
            maxReferencedRow = Math.max(maxReferencedRow, coordinate.row());
        }
        for (var coordinate : folderMetaByCoordinate.keySet()) {
            maxReferencedRow = Math.max(maxReferencedRow, coordinate.row());
        }

        for (var library : libraries) {
            String name = metadataName(library);
            Coordinate coordinate = placementByLibrary.get(name);
            if (coordinate == null) {
                continue;
            }
            slotLibraries.computeIfAbsent(coordinate, ignored -> new ArrayList<>()).add(library);
            assignedLibraries.add(name);
        }

        List<DocLibrary> remainingLibraries = libraries.stream()
            .filter(library -> !assignedLibraries.contains(metadataName(library)))
            .toList();

        int totalRows = Math.max(
            placeRemainingLibraries(
                remainingLibraries,
                settings.getLibraryIndexDefaultColumns(),
                columnsByRow,
                slotLibraries,
                maxReferencedRow
            ),
            maxReferencedRow
        );
        if (totalRows < 1) {
            totalRows = 1;
        }

        List<Row> rows = new ArrayList<>();
        for (int rowNumber = 1; rowNumber <= totalRows; rowNumber++) {
            int columns = columnsForRow(rowNumber, settings.getLibraryIndexDefaultColumns(),
                columnsByRow);
            List<Slot> slots = new ArrayList<>();
            for (int columnNumber = 1; columnNumber <= columns; columnNumber++) {
                var coordinate = new Coordinate(rowNumber, columnNumber);
                var librariesInSlot = slotLibraries.getOrDefault(coordinate, List.of());
                if (librariesInSlot.isEmpty()) {
                    slots.add(Slot.empty());
                    continue;
                }
                if (librariesInSlot.size() == 1) {
                    slots.add(Slot.library(librariesInSlot.getFirst()));
                    continue;
                }
                FolderMeta folderMeta = folderMetaByCoordinate.get(coordinate);
                String title = folderMeta == null ? null : folderMeta.title();
                if (!StringUtils.hasText(title)) {
                    title = spec(librariesInSlot.getFirst()).getTitle();
                }
                String description = folderMeta == null ? null : folderMeta.description();
                if (!StringUtils.hasText(description)) {
                    description = spec(librariesInSlot.getFirst()).getDescription();
                }
                slots.add(Slot.folder(title, description, librariesInSlot));
            }
            rows.add(new Row(rowNumber, columns, List.copyOf(slots)));
        }

        List<Page> pages = paginateRows(rows, settings.getLibraryIndexDefaultMaxRows(), maxRowsByPage);
        int totalPages = Math.max(1, pages.size());
        int currentPageNumber = Math.min(Math.max(requestedPage, 1), totalPages);
        Page currentPage = pages.isEmpty()
            ? new Page(1, settings.getLibraryIndexDefaultMaxRows(), List.of())
            : pages.get(currentPageNumber - 1);

        return new DocLibraryIndexLayout(
            List.copyOf(pages),
            currentPage,
            currentPageNumber,
            totalPages
        );
    }

    private static int placeRemainingLibraries(
        List<DocLibrary> remainingLibraries,
        int defaultColumns,
        Map<Integer, Integer> columnsByRow,
        Map<Coordinate, List<DocLibrary>> slotLibraries,
        int minTotalRows) {
        int row = 1;
        int index = 0;
        while (index < remainingLibraries.size()) {
            int columns = columnsForRow(row, defaultColumns, columnsByRow);
            for (int column = 1; column <= columns && index < remainingLibraries.size(); column++) {
                var coordinate = new Coordinate(row, column);
                if (slotLibraries.containsKey(coordinate)) {
                    continue;
                }
                slotLibraries.put(coordinate, List.of(remainingLibraries.get(index++)));
            }
            row++;
        }
        return Math.max(row - 1, minTotalRows);
    }

    private static int columnsForRow(
        int row,
        int defaultColumns,
        Map<Integer, Integer> columnsByRow) {
        return Math.max(1, columnsByRow.getOrDefault(row, defaultColumns));
    }

    private static List<Page> paginateRows(List<Row> rows, int defaultMaxRows,
        Map<Integer, Integer> maxRowsByPage) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Page> pages = new ArrayList<>();
        int pageNumber = 1;
        int index = 0;
        while (index < rows.size()) {
            int maxRows = Math.max(1, maxRowsByPage.getOrDefault(pageNumber, defaultMaxRows));
            List<Row> pageRows = new ArrayList<>();
            for (int count = 0; count < maxRows && index < rows.size(); count++) {
                pageRows.add(rows.get(index++));
            }
            pages.add(new Page(pageNumber, maxRows, List.copyOf(pageRows)));
            pageNumber++;
        }
        return List.copyOf(pages);
    }

    private static String metadataName(DocLibrary library) {
        return library.getMetadata() == null ? "" : library.getMetadata().getName();
    }

    private static DocLibrary.Spec spec(DocLibrary library) {
        return library.getSpec() == null ? new DocLibrary.Spec() : library.getSpec();
    }

    @Value
    public static class DocLibraryIndexLayout {
        List<Page> pages;
        Page currentPage;
        int currentPageNumber;
        int totalPages;

        static DocLibraryIndexLayout empty() {
            return new DocLibraryIndexLayout(List.of(), new Page(1, 1, List.of()), 1, 1);
        }

        public boolean isEmpty() {
            return currentPage.getRows().isEmpty();
        }

        public boolean hasPreviousPage() {
            return currentPageNumber > 1;
        }

        public boolean hasNextPage() {
            return currentPageNumber < totalPages;
        }

        public int previousPageNumber() {
            return Math.max(1, currentPageNumber - 1);
        }

        public int nextPageNumber() {
            return Math.min(totalPages, currentPageNumber + 1);
        }
    }

    @Value
    public static class Page {
        int index;
        int maxRows;
        List<Row> rows;
    }

    @Value
    public static class Row {
        int index;
        int columns;
        List<Slot> slots;
    }

    @Value
    public static class Slot {
        boolean empty;
        boolean folder;
        String title;
        String description;
        DocLibrary library;
        List<DocLibrary> libraries;

        static Slot empty() {
            return new Slot(true, false, "", "", null, List.of());
        }

        static Slot library(DocLibrary library) {
            return new Slot(false, false, spec(library).getTitle(),
                spec(library).getDescription(), library, List.of(library));
        }

        static Slot folder(String title, String description, List<DocLibrary> libraries) {
            return new Slot(false, true, title, description, null, List.copyOf(libraries));
        }
    }

    private record FolderMeta(String title, String description) {
    }

    private record Coordinate(int row, int column) {
    }
}
