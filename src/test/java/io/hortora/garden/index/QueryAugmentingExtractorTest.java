package io.hortora.garden.index;

import io.casehub.neocortex.rag.ExtractionResult;
import io.casehub.neocortex.rag.MetadataExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QueryAugmentingExtractorTest {

    static final String SEPARATOR = "\n\n---HYPOTHETICAL-QUERIES---\n";

    @Test
    void augmentsContentWithQueries() {
        var delegate = stubExtractor("CDI producer pattern\n\nBody text about CDI.", Map.of("title", "CDI producer"));
        var generator = stubGenerator(List.of("Q1?", "Q2?", "Q3?"));
        var extractor = new QueryAugmentingExtractor(delegate, generator, Path.of("/garden"));

        ExtractionResult result = extractor.extract("jvm/entry.md", new byte[0]);

        assertThat(result.body()).contains("Body text about CDI.");
        assertThat(result.body()).contains(SEPARATOR);
        assertThat(result.body()).contains("Q1?");
        assertThat(result.body()).contains("Q2?");
        assertThat(result.body()).contains("Q3?");
    }

    @Test
    void preservesMetadata() {
        var metadata = Map.of("title", "Test", "domain", "jvm");
        var delegate = stubExtractor("Body", metadata);
        var generator = stubGenerator(List.of("Q1?", "Q2?"));
        var extractor = new QueryAugmentingExtractor(delegate, generator, Path.of("/garden"));

        ExtractionResult result = extractor.extract("entry.md", new byte[0]);

        assertThat(result.metadata()).isEqualTo(metadata);
    }

    @Test
    void emptyBodySkipsAugmentation() {
        var delegate = stubExtractor("", Map.of());
        var generator = stubGenerator(List.of("Q1?", "Q2?"));
        var extractor = new QueryAugmentingExtractor(delegate, generator, Path.of("/garden"));

        ExtractionResult result = extractor.extract("entry.md", new byte[0]);

        assertThat(result.body()).isEmpty();
    }

    @Test
    void generatorReturnsEmptySkipsAugmentation() {
        var delegate = stubExtractor("Body text", Map.of("title", "Test"));
        var generator = stubGenerator(List.of());
        var extractor = new QueryAugmentingExtractor(delegate, generator, Path.of("/garden"));

        ExtractionResult result = extractor.extract("entry.md", new byte[0]);

        assertThat(result.body()).isEqualTo("Body text");
        assertThat(result.body()).doesNotContain(SEPARATOR);
    }

    @Test
    void stripsQueriesFromContent() {
        String augmented = "Original body text" + SEPARATOR + "Q1?\nQ2?\nQ3?";
        String stripped = QueryAugmentingExtractor.stripQueries(augmented);
        assertThat(stripped).isEqualTo("Original body text");
    }

    @Test
    void stripQueriesNoopWhenNoSeparator() {
        String plain = "Plain body text without queries";
        assertThat(QueryAugmentingExtractor.stripQueries(plain)).isEqualTo(plain);
    }

    @Test
    void stripQueriesHandlesNull() {
        assertThat(QueryAugmentingExtractor.stripQueries(null)).isNull();
    }

    private MetadataExtractor stubExtractor(String body, Map<String, String> metadata) {
        return (path, content) -> new ExtractionResult(body, metadata);
    }

    private QueryGenerator stubGenerator(List<String> queries) {
        return (title, body, entryPath) -> queries.isEmpty() ? Optional.empty() : Optional.of(queries);
    }
}
