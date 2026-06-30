package io.hortora.garden.index;

import io.casehub.rag.ExtractionResult;
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

        assertThat(result.body())
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

        assertThat(result.body()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void mdFileWithoutFrontmatterReturnsEmptyContent() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-no-frontmatter.md"));

        ExtractionResult result = extractor.extract("notes/readme.md", content);

        assertThat(result.body()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void extractsTagsAsListMetadata() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-hibernate-lazy.md"));

        ExtractionResult result = extractor.extract("jvm/ge-test.md", content);

        assertThat(result.listMetadata().get("tags")).containsExactly("hibernate", "lazy-loading", "transactions");
        assertThat(result.metadata().get("tags")).isNull();
    }

    @Test
    void extractsSubmittedDate() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-hibernate-lazy.md"));

        ExtractionResult result = extractor.extract("jvm/ge-test.md", content);

        assertThat(result.metadata()).containsEntry("submitted", "2026-04-15");
    }

    @Test
    void crlfLineEndingsParseCorrectly() {
        byte[] content = "---\r\ntitle: \"CRLF test\"\r\ndomain: jvm\r\n---\r\nBody with CRLF."
                .getBytes(StandardCharsets.UTF_8);

        ExtractionResult result = extractor.extract("test/crlf.md", content);

        assertThat(result.body()).isEqualTo("CRLF test\n\nBody with CRLF.");
        assertThat(result.metadata()).containsEntry("title", "CRLF test");
        assertThat(result.metadata()).containsEntry("domain", "jvm");
    }

    @Test
    void malformedYamlReturnsEmptyExtractionResult() {
        byte[] content = "---\n: invalid yaml [[[broken\n---\nBody text."
                .getBytes(StandardCharsets.UTF_8);

        ExtractionResult result = extractor.extract("test/bad-yaml.md", content);

        assertThat(result.body()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void nonMappingYamlReturnsEmptyExtractionResult() {
        byte[] content = "---\nhello world\n---\nBody text."
                .getBytes(StandardCharsets.UTF_8);

        ExtractionResult result = extractor.extract("test/scalar-yaml.md", content);

        assertThat(result.body()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void missingTitleExtractsBodyWithoutTitlePrefix() {
        byte[] content = "---\ndomain: tools\ntype: technique\n---\nBody without title."
                .getBytes(StandardCharsets.UTF_8);

        ExtractionResult result = extractor.extract("test/no-title.md", content);

        assertThat(result.body()).isEqualTo("Body without title.");
        assertThat(result.metadata()).containsEntry("domain", "tools");
        assertThat(result.metadata()).doesNotContainKey("title");
    }

    @Test
    void unclosedFrontmatterReturnsEmptyExtractionResult() {
        byte[] content = "---\ntitle: unclosed\ndomain: jvm\nBody without closing delimiter."
                .getBytes(StandardCharsets.UTF_8);

        ExtractionResult result = extractor.extract("test/unclosed.md", content);

        assertThat(result.body()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void tagsExtractedAsListMetadata() {
        String content = """
            ---
            title: "Test entry"
            domain: jvm
            tags: [cdi, quarkus, bean-discovery]
            ---
            Body text here.
            """;
        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata().get("tags")).containsExactly("cdi", "quarkus", "bean-discovery");
        assertThat(result.metadata().get("tags")).isNull();
    }
}
