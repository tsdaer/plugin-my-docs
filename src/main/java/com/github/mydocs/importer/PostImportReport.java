package com.github.mydocs.importer;

import java.util.List;

public record PostImportReport(String libraryName, String librarySlug, int imported, int updated,
    int failed, List<Item> items) {
    public record Item(String postName, String title, String status, String docName,
        String reason, List<String> warnings) { }
}
