package io.hortora.garden.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonIncludesPromotedFrontmatterFields() throws Exception {
        var result = new SearchResult("doc/ge-test.md", "Test entry", "jvm", "gotcha",
                8, "body text", 0.85, 3.2, "garden", "own",
                List.of(), Map.of(
                        "staleness_threshold", "30d",
                        "tags_joined", "cdi|quarkus|bean-discovery",
                        "last_reviewed", "2026-07-01",
                        "author", "mdp",
                        "verified_on", "quarkus:3.20"));

        String json = mapper.writeValueAsString(result);
        var tree = mapper.readTree(json);

        assertThat(tree.get("stalenessThreshold").asText()).isEqualTo("30d");
        assertThat(tree.get("tags")).isNotNull();
        assertThat(tree.get("tags").isArray()).isTrue();
        assertThat(tree.get("tags").size()).isEqualTo(3);
        assertThat(tree.get("tags").get(0).asText()).isEqualTo("cdi");
        assertThat(tree.get("tags").get(1).asText()).isEqualTo("quarkus");
        assertThat(tree.get("tags").get(2).asText()).isEqualTo("bean-discovery");
        assertThat(tree.get("lastReviewed").asText()).isEqualTo("2026-07-01");
        assertThat(tree.get("author").asText()).isEqualTo("mdp");
        assertThat(tree.get("verifiedOn").asText()).isEqualTo("quarkus:3.20");
    }

    @Test
    void jsonOmitsNullFrontmatterFields() throws Exception {
        var result = new SearchResult("doc/ge-test.md", "Test entry", "jvm", "gotcha",
                8, "body text", 0.85, null, "garden", "own",
                List.of(), Map.of());

        String json = mapper.writeValueAsString(result);
        var tree = mapper.readTree(json);

        assertThat(tree.has("stalenessThreshold")).isFalse();
        assertThat(tree.has("tags")).isFalse();
        assertThat(tree.has("lastReviewed")).isFalse();
        assertThat(tree.has("author")).isFalse();
        assertThat(tree.has("verifiedOn")).isFalse();
    }

    @Test
    void jsonExcludesInternalMetadataMap() throws Exception {
        var result = new SearchResult("doc/ge-test.md", "Test entry", "jvm", "gotcha",
                8, "body text", 0.85, null, "garden", "own",
                List.of(), Map.of("staleness_threshold", "30d", "decay_tier", "0"));

        String json = mapper.writeValueAsString(result);
        var tree = mapper.readTree(json);

        assertThat(tree.has("metadata")).isFalse();
        assertThat(tree.has("decay_tier")).isFalse();
    }
}
