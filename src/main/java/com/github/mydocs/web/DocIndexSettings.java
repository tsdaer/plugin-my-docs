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
