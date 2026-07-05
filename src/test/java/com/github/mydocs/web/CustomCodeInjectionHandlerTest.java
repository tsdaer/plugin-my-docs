package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.github.mydocs.extension.Doc;
import com.github.mydocs.extension.DocLibrary;
import com.github.mydocs.extensionpoint.DocModelContext;
import com.github.mydocs.extensionpoint.DocPageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@ExtendWith(MockitoExtension.class)
class CustomCodeInjectionHandlerTest {

    @Mock
    ReactiveSettingFetcher settingFetcher;

    CustomCodeInjectionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CustomCodeInjectionHandler(new DocIndexSettingsService(settingFetcher));
    }

    private void stubGlobal(String head, String body) {
        var settings = new DocIndexSettings();
        settings.setCustomHeadHtml(head);
        settings.setCustomBodyHtml(body);
        when(settingFetcher.fetch(eq(DocIndexSettingsService.BASIC_GROUP), any()))
            .thenReturn(Mono.just(settings));
    }

    @Test
    void injectsOnlyGlobalOnIndexPage() {
        stubGlobal("<meta name=g>", "<script>g</script>");
        var model = new ConcurrentModel();

        handler.handle(context(model, DocPageType.INDEX, null, null)).block();

        assertThat(head(model)).isEqualTo("<meta name=g>");
        assertThat(body(model)).isEqualTo("<script>g</script>");
    }

    @Test
    void mergesGlobalAndLibraryOnLibraryPage() {
        stubGlobal("<meta name=g>", "");
        var library = library("<meta name=l>", "<script>l</script>");
        var model = new ConcurrentModel();

        handler.handle(context(model, DocPageType.LIBRARY, library, null)).block();

        // 全局在前、库级在后
        assertThat(head(model)).isEqualTo("<meta name=g>\n<meta name=l>");
        // 全局 body 为空，仅库级输出
        assertThat(body(model)).isEqualTo("<script>l</script>");
    }

    @Test
    void mergesAllThreeLevelsInOrderOnDetailPage() {
        stubGlobal("G", "gb");
        var library = library("L", "lb");
        var doc = doc("D", "db");
        var model = new ConcurrentModel();

        handler.handle(context(model, DocPageType.DETAIL, library, doc)).block();

        assertThat(head(model)).isEqualTo("G\nL\nD");
        assertThat(body(model)).isEqualTo("gb\nlb\ndb");
    }

    @Test
    void producesEmptyStringWhenNothingConfigured() {
        stubGlobal("", "");
        var model = new ConcurrentModel();

        handler.handle(context(model, DocPageType.DETAIL, library("", ""), doc("", ""))).block();

        assertThat(head(model)).isEqualTo("");
        assertThat(body(model)).isEqualTo("");
    }

    private static DocModelContext context(Model model, DocPageType type, DocLibrary library,
        Doc doc) {
        return DocModelContext.builder()
            .exchange(null)
            .model(model)
            .pageType(type)
            .library(library)
            .doc(doc)
            .build();
    }

    private static String head(Model model) {
        return (String) model.asMap().get(CustomCodeInjectionHandler.HEAD_MODEL_ATTRIBUTE);
    }

    private static String body(Model model) {
        return (String) model.asMap().get(CustomCodeInjectionHandler.BODY_MODEL_ATTRIBUTE);
    }

    private static DocLibrary library(String head, String body) {
        var library = new DocLibrary();
        var spec = new DocLibrary.Spec();
        spec.setTitle("lib");
        spec.setSlug("lib");
        spec.setCustomHeadHtml(head);
        spec.setCustomBodyHtml(body);
        library.setSpec(spec);
        return library;
    }

    private static Doc doc(String head, String body) {
        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setTitle("doc");
        spec.setSlug("doc");
        spec.setCustomHeadHtml(head);
        spec.setCustomBodyHtml(body);
        doc.setSpec(spec);
        return doc;
    }
}
