package com.github.mydocs.web;

public final class SeoMetadata {

    private final String title;
    private final String description;
    private final String ogType;
    private final String image;

    public SeoMetadata(String title, String description, String ogType, String image) {
        this.title = title;
        this.description = description;
        this.ogType = ogType;
        this.image = image;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getOgType() {
        return ogType;
    }

    public String getImage() {
        return image;
    }
}
