package com.github.mydocs.importer;

import java.util.List;

public record PostImportRequest(List<String> postNames, String targetLibraryName,
    String newLibraryTitle, String newLibrarySlug, Boolean publish) { }
