package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mydocs.extension.DocLibrary;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

class DocLibraryIndexLayoutBuilderTest {

    private final DocLibraryIndexLayoutBuilder builder = new DocLibraryIndexLayoutBuilder();

    @Test
    void keepsFixedColumnsForRowsThatAreNotFull() {
        var settings = new DocIndexSettings();
        settings.setLibraryIndexDefaultColumns(2);

        var layout = builder.build(List.of(library("guide", "开发文档", 0)), settings, 1);

        assertThat(layout.getCurrentPage().getRows()).hasSize(1);
        assertThat(layout.getCurrentPage().getRows().getFirst().getColumns()).isEqualTo(2);
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots()).hasSize(2);
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots().getFirst().isEmpty()).isFalse();
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots().get(1).isEmpty()).isTrue();
    }

    @Test
    void foldsLibrariesSharingOneCoordinateIntoFolder() {
        var settings = new DocIndexSettings();
        settings.setLibraryIndexDefaultColumns(2);

        var placementA = new DocIndexSettings.LibraryPlacement();
        placementA.setLibraryName("guide");
        placementA.setRow(1);
        placementA.setColumn(2);

        var placementB = new DocIndexSettings.LibraryPlacement();
        placementB.setLibraryName("api");
        placementB.setRow(1);
        placementB.setColumn(2);
        settings.setLibraryIndexPlacements(List.of(placementA, placementB));

        var folderTitle = new DocIndexSettings.LibraryFolderTitle();
        folderTitle.setRow(1);
        folderTitle.setColumn(2);
        folderTitle.setTitle("入门合集");
        folderTitle.setDescription("面向新成员的文档库集合");
        settings.setLibraryIndexFolderTitles(List.of(folderTitle));

        var layout = builder.build(List.of(
            library("guide", "开发文档", 0),
            library("api", "接口文档", 1),
            library("faq", "常见问题", 2)
        ), settings, 1);

        assertThat(layout.getCurrentPage().getRows()).hasSize(1);
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots().getFirst().getLibrary().getSpec().getSlug())
            .isEqualTo("faq");
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots().get(1).isFolder()).isTrue();
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots().get(1).getTitle()).isEqualTo("入门合集");
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots().get(1).getDescription())
            .isEqualTo("面向新成员的文档库集合");
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots().get(1).getLibraries())
            .extracting(library -> library.getSpec().getSlug())
            .containsExactly("guide", "api");
    }

    @Test
    void appliesSpecificRowColumnSettings() {
        var settings = new DocIndexSettings();
        settings.setLibraryIndexDefaultColumns(2);

        var rowLayout = new DocIndexSettings.LibraryRowLayout();
        rowLayout.setRow(2);
        rowLayout.setColumns(3);
        settings.setLibraryIndexRowLayouts(List.of(rowLayout));

        var layout = builder.build(List.of(
            library("guide", "开发文档", 0),
            library("api", "接口文档", 1),
            library("faq", "常见问题", 2)
        ), settings, 1);

        assertThat(layout.getCurrentPage().getRows()).hasSize(2);
        assertThat(layout.getCurrentPage().getRows().get(1).getColumns()).isEqualTo(3);
        assertThat(layout.getCurrentPage().getRows().get(1).getSlots().getFirst().getLibrary().getSpec().getSlug())
            .isEqualTo("faq");
    }

    @Test
    void hidesOverflowPlacementsButKeepsBlankRows() {
        var settings = new DocIndexSettings();
        settings.setLibraryIndexDefaultColumns(2);
        settings.setLibraryIndexDefaultMaxRows(3);

        var rowLayout = new DocIndexSettings.LibraryRowLayout();
        rowLayout.setRow(3);
        rowLayout.setColumns(1);
        settings.setLibraryIndexRowLayouts(List.of(rowLayout));

        var placement = new DocIndexSettings.LibraryPlacement();
        placement.setLibraryName("faq");
        placement.setRow(3);
        placement.setColumn(2);
        settings.setLibraryIndexPlacements(List.of(placement));

        var layout = builder.build(List.of(
            library("guide", "开发文档", 0),
            library("api", "接口文档", 1),
            library("faq", "常见问题", 2)
        ), settings, 1);

        assertThat(layout.getCurrentPage().getRows()).hasSize(3);
        assertThat(layout.getCurrentPage().getRows().get(2).getColumns()).isEqualTo(1);
        assertThat(layout.getCurrentPage().getRows().get(2).getSlots().getFirst().isEmpty()).isTrue();
    }

    @Test
    void paginatesRowsBySpecificPageMaxRows() {
        var settings = new DocIndexSettings();
        settings.setLibraryIndexDefaultColumns(2);
        settings.setLibraryIndexDefaultMaxRows(2);

        var pageLayout = new DocIndexSettings.LibraryPageLayout();
        pageLayout.setPage(2);
        pageLayout.setMaxRows(1);
        settings.setLibraryIndexPageLayouts(List.of(pageLayout));

        var layout = builder.build(List.of(
            library("guide", "开发文档", 0),
            library("api", "接口文档", 1),
            library("faq", "常见问题", 2),
            library("sdk", "SDK 文档", 3),
            library("ops", "运维文档", 4)
        ), settings, 2);

        assertThat(layout.getTotalPages()).isEqualTo(2);
        assertThat(layout.getCurrentPageNumber()).isEqualTo(2);
        assertThat(layout.getCurrentPage().getRows()).hasSize(1);
        assertThat(layout.getCurrentPage().getRows().getFirst().getSlots().getFirst().getLibrary()
            .getSpec().getSlug()).isEqualTo("ops");
    }

    private static DocLibrary library(String slug, String title, int priority) {
        var library = new DocLibrary();
        var metadata = new Metadata();
        metadata.setName(slug);
        library.setMetadata(metadata);

        var spec = new DocLibrary.Spec();
        spec.setSlug(slug);
        spec.setTitle(title);
        spec.setPriority(priority);
        spec.setDescription(title + " description");
        library.setSpec(spec);
        return library;
    }
}
