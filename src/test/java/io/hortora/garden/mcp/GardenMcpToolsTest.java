package io.hortora.garden.mcp;

import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.testing.InMemoryEmbeddingIngestor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class GardenMcpToolsTest {

    @Inject GardenMcpTools mcpTools;
    @Inject InMemoryEmbeddingIngestor ingestor;

    private static final CorpusRef CORPUS = new CorpusRef("hortora", "garden");

    @BeforeEach
    void seedFixtures() {
        ingestor.deleteCorpus(CORPUS);
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput(
                        "Hibernate lazy loading fails outside transaction.",
                        "jvm/ge-test-hibernate-lazy.md",
                        Map.of("title", "Hibernate lazy loading gotcha",
                                "domain", "jvm", "type", "gotcha", "score", "8"))
        ));
    }

    @Test
    void gardenSearchReturnsFormattedResults() {
        String result = mcpTools.gardenSearch("hibernate lazy", null, null);

        assertThat(result).contains("## [own] Hibernate lazy loading gotcha");
        assertThat(result).contains("Hibernate lazy loading fails outside transaction.");
    }

    @Test
    void gardenSearchEmptyResultsReturnsMessage() {
        ingestor.deleteCorpus(CORPUS);

        String result = mcpTools.gardenSearch("nonexistent topic xyz", null, null);

        assertThat(result).startsWith("No relevant garden entries found for:");
    }

    @Test
    void gardenStatusReturnsPathAndCount() {
        String result = mcpTools.gardenStatus();

        assertThat(result).contains("Garden path:");
        assertThat(result).contains("Indexed entries:");
    }
}
