package com.github.mydocs.search;

import com.github.mydocs.extension.Doc;
import java.time.Instant;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.search.HaloDocument;

@Component
public class DocSearchDocumentConverter {

    public static final String TYPE = "doc.docs.halo.run";

    private static final int DESCRIPTION_MAX_LENGTH = 160;

    public boolean isIndexable(Doc doc) {
        return metadata(doc).getDeletionTimestamp() == null
            && Boolean.TRUE.equals(spec(doc).getPublished())
            && StringUtils.hasText(metadataName(doc))
            && StringUtils.hasText(spec(doc).getSlug())
            && StringUtils.hasText(spec(doc).getLibraryName());
    }

    public String documentId(Doc doc) {
        return documentId(metadataName(doc));
    }

    public String documentId(String metadataName) {
        return TYPE + "/" + metadataName;
    }

    public String metadataName(Doc doc) {
        return metadata(doc).getName();
    }

    public HaloDocument toHaloDocument(Doc doc, String librarySlug) {
        var metadata = metadata(doc);
        var spec = spec(doc);
        var content = plainText(firstNonBlank(spec.getContent(), spec.getRaw()));
        var haloDocument = new HaloDocument();
        haloDocument.setId(documentId(metadata.getName()));
        haloDocument.setMetadataName(metadata.getName());
        haloDocument.setAnnotations(metadata.getAnnotations());
        haloDocument.setTitle(nullToEmpty(spec.getTitle()));
        haloDocument.setDescription(description(content));
        haloDocument.setContent(content);
        haloDocument.setOwnerName(metadata.getName());
        haloDocument.setCreationTimestamp(timestampOrEpoch(metadata.getCreationTimestamp()));
        haloDocument.setUpdateTimestamp(timestampOrEpoch(metadata.getCreationTimestamp()));
        haloDocument.setPermalink("/docs/" + librarySlug + "/" + spec.getSlug());
        haloDocument.setType(TYPE);
        haloDocument.setPublished(true);
        haloDocument.setRecycled(false);
        haloDocument.setExposed(true);
        return haloDocument;
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private static String plainText(String content) {
        return StringUtils.hasText(content) ? Jsoup.parse(content).text() : "";
    }

    private static String description(String content) {
        if (content.length() <= DESCRIPTION_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, DESCRIPTION_MAX_LENGTH);
    }

    private static Instant timestampOrEpoch(Instant timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static MetadataOperator metadata(Doc doc) {
        return doc.getMetadata() == null ? new Metadata() : doc.getMetadata();
    }

    private static Doc.Spec spec(Doc doc) {
        return doc.getSpec() == null ? new Doc.Spec() : doc.getSpec();
    }
}
