package io.hortora.garden.search;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.testing.InMemoryEmbeddingIngestor;
import io.casehub.neocortex.rag.testing.InMemoryQueryExpander;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(QueryExpansionTest.ExpansionEnabledProfile.class)
class QueryExpansionTest {

    public static class ExpansionEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "casehub.rag.expansion.enabled", "true",
                "casehub.rag.expansion.mode", "llm",
                "casehub.rag.expansion.hypothetical-count", "1"
            );
        }
    }

    @Inject SearchResource searchResource;
    @Inject InMemoryEmbeddingIngestor ingestor;
    @Inject InMemoryQueryExpander queryExpander;

    private static final CorpusRef CORPUS = new CorpusRef("hortora", "garden");

    @BeforeEach
    void setup() {
        queryExpander.clear();
        ingestor.deleteCorpus(CORPUS);
        ingestor.ingest(CORPUS, List.of(
            new ChunkInput(
                "CDI producer methods for configuration.",
                "jvm/ge-test-cdi-producer.md",
                Map.of("title", "CDI producer pattern",
                       "domain", "jvm", "type", "technique", "score", "7"),
                Map.of("tags", List.of("cdi", "quarkus")))
        ));
    }

    @Test
    void queryExpansionDecoratorIsActive() {
        searchResource.searchFor("CDI configuration", null, null, null, null);

        assertThat(queryExpander.expandedQueries())
            .as("QueryExpandingCaseRetriever should have intercepted the retrieval")
            .isNotEmpty();
    }

    @Test
    void expandedQueryContainsOriginalText() {
        searchResource.searchFor("CDI configuration", null, null, null, null);

        assertThat(queryExpander.expandedQueries().getFirst().text())
            .isEqualTo("CDI configuration");
        assertThat(queryExpander.expandedQueries().getFirst().expandedText())
            .isNotNull()
            .contains("hypothetical:");
    }
}
