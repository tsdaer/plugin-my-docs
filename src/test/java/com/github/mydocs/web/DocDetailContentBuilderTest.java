package com.github.mydocs.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mydocs.extension.Doc;
import org.junit.jupiter.api.Test;

class DocDetailContentBuilderTest {

    private final DocDetailContentBuilder builder = new DocDetailContentBuilder();

    @Test
    void rewritesSameLibraryShortLinksAndExtractsOutline() {
        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setContent("""
            <p>
              <a href="./intro#install">Intro</a>
              <a href="getting-started">Getting started</a>
              <a href="#current-heading">Current</a>
              <a href="/docs/other/faq#q1">Other library</a>
              <a href="https://example.com/docs">External</a>
              <a href="../shared#x">Parent path</a>
            </p>
            <h2 id="install">Install</h2>
            <p>body</p>
            <h4 id="next-step">Next step</h4>
            """);
        doc.setSpec(spec);

        var content = builder.build("guide", doc);

        assertThat(content.getHtml())
            .contains("href=\"/docs/guide/intro#install\"")
            .contains("href=\"/docs/guide/getting-started\"")
            .contains("href=\"#current-heading\"")
            .contains("href=\"/docs/other/faq#q1\"")
            .contains("href=\"https://example.com/docs\"")
            .contains("href=\"../shared#x\"");

        assertThat(content.getOutline())
            .extracting(
                DocDetailContentBuilder.OutlineHeading::getId,
                DocDetailContentBuilder.OutlineHeading::getText,
                DocDetailContentBuilder.OutlineHeading::getDepth
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("install", "Install", 0),
                org.assertj.core.groups.Tuple.tuple("next-step", "Next step", 2)
            );
    }

    @Test
    void returnsEmptyDetailContentForBlankHtml() {
        var doc = new Doc();
        var spec = new Doc.Spec();
        spec.setContent("   ");
        doc.setSpec(spec);

        var content = builder.build("guide", doc);

        assertThat(content.getHtml()).isEmpty();
        assertThat(content.getOutline()).isEmpty();
    }
}
