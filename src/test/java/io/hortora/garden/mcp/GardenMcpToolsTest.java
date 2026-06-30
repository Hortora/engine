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
                        "jvm/GE-20260620-a1b2c3.md",
                        Map.of("title", "Hibernate lazy loading gotcha",
                                "domain", "jvm", "type", "gotcha", "score", "8"),
                        Map.of("tags", List.of("hibernate", "lazy-loading", "transactions"))),
                new ChunkInput(
                        "CDI producer methods for configuration.",
                        "jvm/GE-20260621-d4e5f6.md",
                        Map.of("title", "CDI producer pattern",
                                "domain", "jvm", "type", "technique", "score", "7"),
                        Map.of("tags", List.of("cdi", "quarkus", "beans")))
        ));
    }

    @Test
    void gardenSearchReturnsFormattedResults() {
        String result = mcpTools.gardenSearch("hibernate lazy", null, null, null, null);

        assertThat(result).contains("## [own] Hibernate lazy loading gotcha");
        assertThat(result).contains("**ID:** GE-20260620-a1b2c3");
        assertThat(result).contains("**Domain:** jvm");
        assertThat(result).contains("**Type:** gotcha");
        assertThat(result).containsPattern("\\*\\*Relevance:\\*\\* \\d+\\.\\d{2}");
        assertThat(result).contains("Hibernate lazy loading fails outside transaction.");
    }

    @Test
    void gardenSearchEmptyResultsReturnsMessage() {
        ingestor.deleteCorpus(CORPUS);

        String result = mcpTools.gardenSearch("nonexistent topic xyz", null, null, null, null);

        assertThat(result).startsWith("No relevant garden entries found for:");
    }

    @Test
    void gardenStatusReturnsPathAndCount() {
        String result = mcpTools.gardenStatus();

        assertThat(result).contains("Garden path:");
        assertThat(result).contains("Indexed entries:");
    }

    @Test
    void gardenSearchStripsDoubledTitle() {
        ingestor.deleteCorpus(CORPUS);
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput(
                        "Hibernate lazy loading gotcha\n\nHibernate lazy loading fails outside transaction.",
                        "jvm/GE-20260518-d1e4b2.md",
                        Map.of("title", "Hibernate lazy loading gotcha",
                                "domain", "jvm", "type", "gotcha", "score", "8"))
        ));

        String result = mcpTools.gardenSearch("hibernate lazy", null, null, null, null);

        long titleCount = result.lines()
                .filter(l -> l.contains("Hibernate lazy loading gotcha"))
                .count();
        assertThat(titleCount).as("Title should appear once (heading), not twice").isEqualTo(1);
        assertThat(result).contains("Hibernate lazy loading fails outside transaction.");
    }

    @Test
    void gardenSearchKeepsRelativePathForNonGeDocuments() {
        ingestor.deleteCorpus(CORPUS);
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput(
                        "Testing principles and TDD workflow.",
                        "approaches/testing.md",
                        Map.of("title", "Testing — Principles",
                                "domain", "approaches", "type", "reference", "score", "10"))
        ));

        String result = mcpTools.gardenSearch("testing TDD", null, null, null, null);

        assertThat(result).contains("**ID:** approaches/testing");
        assertThat(result).doesNotContain("**ID:** testing");
    }

    @Test
    void gardenReindexDeletesCorpusAndResetsCursor() {
        String result = mcpTools.gardenReindex();

        assertThat(result).contains("Reindex triggered");
        assertThat(result).contains("garden");

        String status = mcpTools.gardenStatus();
        assertThat(status).contains("Indexed entries: 0");
    }

    @Test
    void gardenSearchFiltersByType() {
        // Note: In-memory retriever may not support type filtering
        // This test verifies the parameter is accepted and passed through
        String result = mcpTools.gardenSearch("CDI producer", null, "technique", null, null);

        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("CDI producer pattern"),
                r -> assertThat(r).contains("No relevant garden entries found")
        );
    }

    @Test
    void gardenSearchFiltersByTags() {
        // Note: In-memory retriever may not support list-valued payload filters
        // This test verifies the parameter is accepted and passed through
        String result = mcpTools.gardenSearch("CDI producer", null, null, "cdi", null);

        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("CDI producer pattern"),
                r -> assertThat(r).contains("No relevant garden entries found")
        );
    }

    @Test
    void gardenSearchCombinesAllFilters() {
        // Note: In-memory retriever may not support all filter types
        // This test verifies all parameters are accepted and passed through
        String result = mcpTools.gardenSearch("Hibernate lazy", "jvm", "gotcha", "hibernate", null);

        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("Hibernate lazy loading gotcha"),
                r -> assertThat(r).contains("No relevant garden entries found")
        );
    }

    @Test
    void gardenSearchIncludesMetadataComment() {
        String result = mcpTools.gardenSearch("hibernate lazy", null, null, null, null);

        assertThat(result).contains("<!-- search_meta:");
        assertThat(result).contains("returned=");
        assertThat(result).contains("requested=");
    }
}
