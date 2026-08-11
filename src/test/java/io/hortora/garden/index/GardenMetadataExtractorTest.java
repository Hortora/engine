package io.hortora.garden.index;

import io.casehub.neocortex.rag.ExtractionResult;
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
        assertThat(result.metadata()).containsEntry("tags_joined", "hibernate|lazy-loading|transactions");
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
        assertThat(result.metadata()).containsEntry("tags_joined", "cdi|quarkus|bean-discovery");
    }

    @Test
    void entryWithoutTagsGetsEmptyTagsList() {
        String content = """
                         ---
                         title: "No tags entry"
                         domain: jvm
                         type: gotcha
                         ---
                         Body text here.
                         """;
        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata().get("tags")).isEmpty();
        assertThat(result.metadata()).doesNotContainKey("tags_joined");
    }


    @Test
    void extractsSeeAlsoPlainIds() {
        String content = """
                         ---
                         title: "CDI observer gotcha"
                         domain: jvm
                         type: gotcha
                         ---
                         Body text.
                         
                         **See also:** GE-20260415-884e48 — @Alternative @Priority
                         """;
        ExtractionResult result = extractor.extract("jvm/ge-test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata().get("see_also")).containsExactly("GE-20260415-884e48");
        assertThat(result.metadata().get("see_also_ids")).isEqualTo("GE-20260415-884e48");
    }

    @Test
    void extractsSeeAlsoMarkdownLinks() {
        String content = """
                         ---
                         title: "Test entry"
                         domain: jvm
                         ---
                         Body text.
                         
                         **See also:** [GE-20260428-0482d3](GE-20260428-0482d3.md) — description
                         """;
        ExtractionResult result = extractor.extract("jvm/ge-test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata().get("see_also")).containsExactly("GE-20260428-0482d3");
    }

    @Test
    void extractsSeeAlsoMultipleLines() {
        String content = """
                         ---
                         title: "Multiple see-also"
                         domain: jvm
                         ---
                         Body text.
                         
                         **See also:** GE-20260415-884e48 — first ref
                         
                         **See also:** [GE-20260520-e15ff0](GE-20260520-e15ff0.md) — second ref
                         """;
        ExtractionResult result = extractor.extract("jvm/ge-test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata().get("see_also"))
                .containsExactly("GE-20260415-884e48", "GE-20260520-e15ff0");
    }

    @Test
    void extractsSeeAlsoOldFormatIds() {
        String content = """
                         ---
                         title: "Old format"
                         domain: tools
                         ---
                         Body text.
                         
                         **See also:** GE-0074 (resize-pane silent no-op)
                         """;
        ExtractionResult result = extractor.extract("tools/ge-test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata().get("see_also")).containsExactly("GE-0074");
    }

    @Test
    void noSeeAlsoReturnsAbsentKey() {
        String content = """
                         ---
                         title: "No see-also"
                         domain: jvm
                         ---
                         Body text without any see also links.
                         """;
        ExtractionResult result = extractor.extract("jvm/ge-test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata()).doesNotContainKey("see_also");
        assertThat(result.metadata()).doesNotContainKey("see_also_ids");
    }

    @Test
    void extractsSeeAlsoMultipleIdsOnOneLine() {
        String content = """
                         ---
                         title: "Pipe separated"
                         domain: jvm
                         ---
                         Body text.
                         
                         **See also:** [GE-20260520-e15ff0](GE-20260520-e15ff0.md) — first | [GE-20260521-4de4f1](GE-20260521-4de4f1.md) — second
                         """;
        ExtractionResult result = extractor.extract("jvm/ge-test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata().get("see_also"))
                .containsExactly("GE-20260520-e15ff0", "GE-20260521-4de4f1");
    }

    @Test
    void extractsSeeAlsoDeduplicates() {
        String content = """
                         ---
                         title: "Duplicate refs"
                         domain: jvm
                         ---
                         Body text.
                         
                         **See also:** GE-20260415-884e48 — first mention
                         
                         **See also:** GE-20260415-884e48 — same entry again
                         """;
        ExtractionResult result = extractor.extract("jvm/ge-test.md", content.getBytes(StandardCharsets.UTF_8));

        assertThat(result.listMetadata().get("see_also")).containsExactly("GE-20260415-884e48");
    }

    @Test
    void extractsStalenessAndVersionFields() {
        String content = """
                         ---
                         title: "Test entry"
                         domain: jvm
                         type: gotcha
                         score: 12
                         submitted: 2026-01-15
                         staleness_threshold: 30d
                         verified_on: "quarkus:3.20"
                         author: mdp
                         last_reviewed: 2026-07-01
                         ---
                         Body text here.
                         """;
        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));
        assertThat(result.metadata()).containsEntry("staleness_threshold", "30d");
        assertThat(result.metadata()).containsEntry("staleness_days", "30");
        assertThat(result.metadata()).containsEntry("decay_tier", "0");
        assertThat(result.metadata()).containsEntry("verified_on", "quarkus:3.20");
        assertThat(result.metadata()).containsEntry("author", "mdp");
        assertThat(result.metadata()).containsEntry("last_reviewed", "2026-07-01");
    }

    @Test
    void decayTierDefaultsToStandardWhenAbsent() {
        String content = """
                         ---
                         title: No threshold
                         domain: jvm
                         type: gotcha
                         score: 10
                         ---
                         Body.
                         """;
        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));
        assertThat(result.metadata()).containsEntry("staleness_days", "90");
        assertThat(result.metadata()).containsEntry("decay_tier", "1");
    }

    @Test
    void decayTierEvergreenWhenNever() {
        String content = """
                         ---
                         title: Evergreen
                         domain: jvm
                         type: convention
                         score: 10
                         staleness_threshold: never
                         ---
                         Body.
                         """;
        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));
        assertThat(result.metadata()).containsEntry("staleness_threshold", "never");
        assertThat(result.metadata()).containsEntry("staleness_days", "0");
        assertThat(result.metadata()).containsEntry("decay_tier", "3");
    }

    @Test
    void decayTierSlowFor365d() {
        String content = """
                         ---
                         title: Slow decay
                         domain: jvm
                         staleness_threshold: 365d
                         ---
                         Body.
                         """;
        ExtractionResult result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));
        assertThat(result.metadata()).containsEntry("staleness_days", "365");
        assertThat(result.metadata()).containsEntry("decay_tier", "2");
    }


}
