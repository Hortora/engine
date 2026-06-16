package io.hortora.garden.index;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GardenMetadataExtractorTest {

    private final GardenMetadataExtractor extractor = new GardenMetadataExtractor();

    @Test
    void parsesYamlFrontmatterAndConcatenatesTitleWithBody() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-hibernate-lazy.md"));

        ExtractionResult result = extractor.extract("jvm/ge-test.md", content);

        assertThat(result.content())
                .startsWith("Hibernate lazy loading fails outside transaction\n\n")
                .contains("LazyInitializationException");
        assertThat(result.metadata())
                .containsEntry("title", "Hibernate lazy loading fails outside transaction")
                .containsEntry("domain", "jvm")
                .containsEntry("type", "gotcha")
                .containsEntry("score", "8");
    }

    @Test
    void nonMdFileReturnsEmptyContent() {
        byte[] content = "some image data".getBytes(StandardCharsets.UTF_8);

        ExtractionResult result = extractor.extract("images/diagram.png", content);

        assertThat(result.content()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void mdFileWithoutFrontmatterReturnsEmptyContent() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-no-frontmatter.md"));

        ExtractionResult result = extractor.extract("notes/readme.md", content);

        assertThat(result.content()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void extractsTagsAsCommaSeparatedString() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-hibernate-lazy.md"));

        ExtractionResult result = extractor.extract("jvm/ge-test.md", content);

        assertThat(result.metadata()).containsEntry("tags", "hibernate, lazy-loading, transactions");
    }

    @Test
    void extractsSubmittedDate() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-hibernate-lazy.md"));

        ExtractionResult result = extractor.extract("jvm/ge-test.md", content);

        assertThat(result.metadata()).containsEntry("submitted", "2026-04-15");
    }
}
