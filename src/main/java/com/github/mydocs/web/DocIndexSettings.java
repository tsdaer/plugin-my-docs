package com.github.mydocs.web;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class DocIndexSettings {

    private Integer libraryIndexDefaultColumns = 2;

    private Integer libraryIndexDefaultMaxRows = 2;

    private List<LibraryPageLayout> libraryIndexPageLayouts = new ArrayList<>();

    private List<LibraryRowLayout> libraryIndexRowLayouts = new ArrayList<>();

    private List<LibraryPlacement> libraryIndexPlacements = new ArrayList<>();

    private List<LibraryFolderTitle> libraryIndexFolderTitles = new ArrayList<>();

    private String renderContentThemeLight;

    private String renderContentThemeDark;

    private String renderContentThemeLightUrl;

    private String renderContentThemeDarkUrl;

    private String renderContentThemeLightClass;

    private String renderContentThemeDarkClass;

    private String renderCodeThemeLight;

    private String renderCodeThemeDark;

    private Boolean renderLineNumber = false;

    private Boolean renderAutoSpace = false;

    private Boolean renderGfmAutoLink = true;

    private Boolean renderFootnotes = true;

    private Boolean renderMark = false;

    private Boolean renderFixTermTypo = false;

    private Boolean renderParagraphBeginningSpace = false;

    private Boolean renderCodeBlockPreview = true;

    private Boolean renderMathBlockPreview = true;

    // 仅用于读取旧版 ConfigMap，规范化后不再写入模板模型。
    private String renderContentTheme;

    private String renderCodeTheme;

    private String customHeadHtml = "";

    private String customBodyHtml = "";

    @Data
    public static class LibraryPageLayout {
        private Integer page;
        private Integer maxRows;
    }

    @Data
    public static class LibraryRowLayout {
        private Integer row;
        private Integer columns;
    }

    @Data
    public static class LibraryPlacement {
        private String libraryName;
        private Integer row;
        private Integer column;
    }

    @Data
    public static class LibraryFolderTitle {
        private Integer row;
        private Integer column;
        private String title;
        private String description;
    }
}
