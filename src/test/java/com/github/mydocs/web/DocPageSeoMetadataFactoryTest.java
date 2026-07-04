package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import org.junit.jupiter.api.Test;

class DocPageSeoMetadataFactoryTest {

    private final DocPageSeoMetadataFactory factory = new DocPageSeoMetadataFactory();

    @Test
    void buildsLibraryMetadataFromDescriptionAndCover() {
        var library = new DocLibrary();
        var spec = new DocLibrary.Spec();
        spec.setTitle("开发手册");
        spec.setDescription("集中收录接入、部署与排障文档。");
        spec.setCover("https://cdn.example.com/docs-cover.png");
        library.setSpec(spec);

        var metadata = factory.forLibrary(library);

        assertThat(metadata.getTitle()).isEqualTo("开发手册 - 文档");
        assertThat(metadata.getDescription()).isEqualTo("集中收录接入、部署与排障文档。");
        assertThat(metadata.getOgType()).isEqualTo("website");
        assertThat(metadata.getImage()).isEqualTo("https://cdn.example.com/docs-cover.png");
    }

    @Test
    void buildsDetailDescriptionFromRenderedHtml() {
        var library = new DocLibrary();
        var librarySpec = new DocLibrary.Spec();
        librarySpec.setTitle("开发手册");
        library.setSpec(librarySpec);

        var doc = new Doc();
        var docSpec = new Doc.Spec();
        docSpec.setTitle("快速开始");
        docSpec.setContent("<h1>快速开始</h1><p>先创建应用，再配置回调地址。</p>");
        doc.setSpec(docSpec);

        var metadata = factory.forDetail(library, doc);

        assertThat(metadata.getTitle()).isEqualTo("快速开始 - 开发手册");
        assertThat(metadata.getDescription()).isEqualTo("快速开始 先创建应用，再配置回调地址。");
        assertThat(metadata.getOgType()).isEqualTo("article");
    }

    @Test
    void fallsBackToMarkdownWhenHtmlIsBlank() {
        var library = new DocLibrary();
        var librarySpec = new DocLibrary.Spec();
        librarySpec.setTitle("开发手册");
        library.setSpec(librarySpec);

        var doc = new Doc();
        var docSpec = new Doc.Spec();
        docSpec.setTitle("快速开始");
        docSpec.setRaw("# 快速开始\n\n阅读 [接入指南](https://example.com) 了解完整流程。");
        doc.setSpec(docSpec);

        var metadata = factory.forDetail(library, doc);

        assertThat(metadata.getDescription()).isEqualTo("快速开始 阅读 接入指南 了解完整流程。");
    }
}
