package com.github.mydocs.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class GithubWikiZipReaderTest {

    @Test
    void readsMarkdownEntriesAndSkipsOthers() throws IOException {
        byte[] zip = zip(bytes -> {
            put(bytes, "Home.md", "# 首页");
            put(bytes, "docs/Nested Page.md", "嵌套页面");
            put(bytes, "assets/logo.png", "binary".getBytes(StandardCharsets.ISO_8859_1));
            put(bytes, "__MACOSX/._Home.md", "junk".getBytes(StandardCharsets.ISO_8859_1));
            put(bytes, "_Footer.md", "页脚");
        });

        var result = GithubWikiZipReader.read(zip);

        assertThat(result.files()).isEqualTo(Map.of(
            "Home", "# 首页",
            "Nested Page", "嵌套页面",
            "_Footer", "页脚"
        ));
        assertThat(result.skippedOtherFiles()).isEqualTo(2);
    }

    @Test
    void keepsFirstEntryOnDuplicateBaseNames() throws IOException {
        byte[] zip = zip(bytes -> {
            put(bytes, "a/Page.md", "first");
            put(bytes, "b/Page.md", "second");
        });

        var result = GithubWikiZipReader.read(zip);

        assertThat(result.files()).containsEntry("Page", "first");
        assertThat(result.skippedOtherFiles()).isEqualTo(1);
    }

    @Test
    void skipsPathTraversalEntries() throws IOException {
        byte[] zip = zip(bytes -> {
            put(bytes, "../evil.md", "evil");
            put(bytes, "ok.md", "fine");
        });

        var result = GithubWikiZipReader.read(zip);

        assertThat(result.files()).containsEntry("ok", "fine");
        assertThat(result.skippedOtherFiles()).isEqualTo(1);
    }

    interface ZipWriter {
        void write(ZipOutputStream zip) throws IOException;
    }

    private static byte[] zip(ZipWriter writer) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            writer.write(zip);
        }
        return buffer.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content)
        throws IOException {
        put(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] content)
        throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
