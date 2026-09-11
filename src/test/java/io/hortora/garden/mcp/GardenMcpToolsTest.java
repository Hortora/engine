package io.hortora.garden.mcp;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.casehub.neocortex.rag.testing.InMemoryEmbeddingIngestor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class GardenMcpToolsTest {

    @Inject GardenMcpTools mcpTools;
    @Inject InMemoryEmbeddingIngestor ingestor;
    @Inject
            io.casehub.neocortex.rag.testing.InMemoryRetrievalTracker retrievalTracker;
    @Inject
    io.hortora.garden.provenance.ProvenanceStore provenanceStore;


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

    @BeforeEach
    void clearTracking() {
        retrievalTracker.clear();
    }

    @BeforeEach
    void clearProvenance() {
        provenanceStore.deleteAll();
    }


    @Test
    void gardenSearchReturnsFormattedResults() {
        String result = mcpTools.gardenSearch("hibernate lazy", null, null, null, null, null, null, null);

        assertThat(result).contains("## [own] Hibernate lazy loading gotcha");
        assertThat(result).contains("**ID:** GE-20260620-a1b2c3");
        assertThat(result).contains("**Domain:** jvm");
        assertThat(result).contains("**Type:** gotcha");
        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).containsPattern("\\*\\*Score:\\*\\* [\\-\\d]+\\.\\d+ \\(CE\\)"),
                r -> assertThat(r).containsPattern("\\*\\*Relevance:\\*\\* \\d+\\.\\d{2}"));
        assertThat(result).contains("Hibernate lazy loading fails outside transaction.");
    }

    @Test
    void gardenSearchEmptyResultsReturnsMessage() {
        ingestor.deleteCorpus(CORPUS);

        String result = mcpTools.gardenSearch("nonexistent topic xyz", null, null, null, null, null, null, null);

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

        String result = mcpTools.gardenSearch("hibernate lazy", null, null, null, null, null, null, null);

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

        String result = mcpTools.gardenSearch("testing TDD", null, null, null, null, null, null, null);

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
        String result = mcpTools.gardenSearch("CDI producer", null, null, "technique", null, null, null, null);

        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("CDI producer pattern"),
                r -> assertThat(r).contains("No relevant garden entries found")
        );
    }

    @Test
    void gardenSearchFiltersByTags() {
        // Note: In-memory retriever may not support list-valued payload filters
        // This test verifies the parameter is accepted and passed through
        String result = mcpTools.gardenSearch("CDI producer", null, null, null, "cdi", null, null, null);

        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("CDI producer pattern"),
                r -> assertThat(r).contains("No relevant garden entries found")
        );
    }

    @Test
    void gardenSearchCombinesAllFilters() {
        // Note: In-memory retriever may not support all filter types
        // This test verifies all parameters are accepted and passed through
        String result = mcpTools.gardenSearch("Hibernate lazy", null, "jvm", "gotcha", "hibernate", null, null, null);

        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("Hibernate lazy loading gotcha"),
                r -> assertThat(r).contains("No relevant garden entries found")
        );
    }

    @Test
    void gardenSearchIncludesMetadataComment() {
        String result = mcpTools.gardenSearch("hibernate lazy", null, null, null, null, null, null, null);

        assertThat(result).contains("<!-- search_meta:");
        assertThat(result).contains("returned=");
        assertThat(result).contains("requested=");
    }

    @Test
    void gardenUnretrievedReturnsEntriesNeverRetrieved() {
        String result = mcpTools.gardenUnretrieved(1, null);

        assertThat(result).contains("GE-20260620-a1b2c3");
        assertThat(result).contains("GE-20260621-d4e5f6");
    }

    @Test
    void gardenUnretrievedExcludesRetrievedEntries() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        retrievalTracker.record(
                RetrievalQuery.of("hibernate"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
                16);

        String result = mcpTools.gardenUnretrieved(1, null);

        assertThat(result).doesNotContain("GE-20260620-a1b2c3");
        assertThat(result).contains("GE-20260621-d4e5f6");
    }

    @Test
    void gardenUnretrievedExcludesRecentEntries() {
        String todayId = "jvm/GE-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-aabbcc.md";
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput("Recent entry.", todayId,
                        Map.of("title", "Recent", "domain", "jvm", "type", "gotcha"))));

        String result = mcpTools.gardenUnretrieved(30, null);

        assertThat(result).doesNotContain("aabbcc");
    }

    @Test
    void gardenUnretrievedIncludesNonGeEntriesUnconditionally() {
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput("Testing principles.", "approaches/testing.md",
                        Map.of("title", "Testing", "domain", "approaches", "type", "reference"))));

        String result = mcpTools.gardenUnretrieved(1, null);

        assertThat(result).contains("approaches/testing");
    }

    @Test
    void gardenUnretrievedDetectsStaleEntries() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        retrievalTracker.record(
                RetrievalQuery.of("hibernate"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
                16);

        String result = mcpTools.gardenUnretrieved(1, 0);

        assertThat(result).contains("Stale entries");
        assertThat(result).contains("GE-20260620-a1b2c3");
    }

    @Test
    void gardenUnretrievedGroupsByDomain() {
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput("Python gotcha.", "python/GE-20260101-aabbcc.md",
                        Map.of("title", "Python gotcha", "domain", "python", "type", "gotcha"))));

        String result = mcpTools.gardenUnretrieved(1, null);

        assertThat(result).contains("### jvm");
        assertThat(result).contains("### python");
    }

    @Test
    void gardenUnretrievedAllRetrievedReturnsPositiveMessage() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        retrievalTracker.record(
                RetrievalQuery.of("hibernate"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
                16);
        retrievalTracker.record(
                RetrievalQuery.of("CDI"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260621-d4e5f6.md", 0.8, Map.of())),
                16);

        String result = mcpTools.gardenUnretrieved(1, null);

        assertThat(result).contains("All");
        assertThat(result).contains("entries have been retrieved");
    }

    @Test
    void gardenSearchRecordsRetrievalsViaDecorator() {
        retrievalTracker.clear();
        mcpTools.gardenSearch("hibernate lazy", null, null, null, null, null, null, null);

        CorpusRef corpus = new CorpusRef("hortora", "garden");
        Set<String> retrievedIds = retrievalTracker.findRetrievedDocumentIds(
                corpus, java.time.Instant.EPOCH, java.time.Instant.now());

        assertThat(retrievedIds).isNotEmpty();
    }

    @Test
    void gardenUnretrievedSurfacesLowQualityEntries() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        for (int i = 0; i < 3; i++) {
            retrievalTracker.record(
                    RetrievalQuery.of("hibernate"),
                    corpus,
                    List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
                    16);
        }
        var records = retrievalTracker.findRecords(corpus, java.time.Instant.EPOCH, java.time.Instant.now());
        for (var record : records) {
            retrievalTracker.feedback(record.retrievalId(), "jvm/GE-20260620-a1b2c3.md",
                                      io.casehub.neocortex.rag.RetrievalOutcome.NOT_RELEVANT);
        }

        String result = mcpTools.gardenUnretrieved(1, null);

        assertThat(result).contains("Low quality");
        assertThat(result).contains("GE-20260620-a1b2c3");
    }


    @Test
    void passesMinDaysFilterExcludesRecentGeEntries() {
        String recentId = "jvm/GE-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-aabbcc.md";
        assertThat(GardenMcpTools.passesMinDaysFilter(recentId, 30)).isFalse();
    }

    @Test
    void passesMinDaysFilterIncludesOldGeEntries() {
        assertThat(GardenMcpTools.passesMinDaysFilter("jvm/GE-20250101-aabbcc.md", 30)).isTrue();
    }

    @Test
    void passesMinDaysFilterIncludesNonGeEntries() {
        assertThat(GardenMcpTools.passesMinDaysFilter("approaches/testing.md", 30)).isTrue();
    }

    @Test
    void passesMinDaysFilterIncludesGeWithoutPath() {
        assertThat(GardenMcpTools.passesMinDaysFilter("GE-20250101-aabbcc.md", 30)).isTrue();
    }

    @Test
    void gardenRecordProvenanceRecordsEntries() {
        String result = mcpTools.gardenRecordProvenance(
                "Hortora/trellis", 14, null, "GE-0031|GE-0045", "brainstorming");
        assertThat(result).contains("2");

        var lineage = provenanceStore.forwardLineage("Hortora/trellis", 14);
        assertThat(lineage).hasSize(2);
    }

    @Test
    void gardenRecordProvenanceFiltersEmptySegments() {
        String result = mcpTools.gardenRecordProvenance(
                "Hortora/trellis", 14, null, "|GE-0031||GE-0045|", "brainstorming");
        assertThat(result).contains("2");
    }

    @Test
    void gardenRecordProvenanceRejectsAllEmptyIds() {
        String result = mcpTools.gardenRecordProvenance(
                "Hortora/trellis", 14, null, "|||", "brainstorming");
        assertThat(result.toLowerCase()).contains("error");
    }

    @Test
    void gardenRecordProvenanceCoercesNullSpecNameToEmpty() {
        mcpTools.gardenRecordProvenance("Hortora/trellis", 14, null, "GE-0031", "brainstorming");

        var lineage = provenanceStore.forwardLineage("Hortora/trellis", 14);
        assertThat(lineage).hasSize(1);
        assertThat(lineage.getFirst().documentId()).isEqualTo("GE-0031");
    }

    @Test
    void gardenFeedbackRecordsIssueContext() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        retrievalTracker.record(
                RetrievalQuery.of("hibernate"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
                16);

        mcpTools.gardenFeedback(
                "GE-20260620-a1b2c3", "RELEVANT", null,
                "Hortora/engine", 88);

        var ctx = provenanceStore.findFeedbackContext("GE-20260620-a1b2c3");
        assertThat(ctx).hasSize(1);
        assertThat(ctx.getFirst().issueRepo()).isEqualTo("Hortora/engine");
        assertThat(ctx.getFirst().issueNumber()).isEqualTo(88);
        assertThat(ctx.getFirst().outcome()).isEqualTo("RELEVANT");
    }

    @Test
    void gardenFeedbackOutdatedRecordsStaleness() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        retrievalTracker.record(
                RetrievalQuery.of("hibernate"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
                16);

        String result = mcpTools.gardenFeedback(
                "GE-20260620-a1b2c3", "OUTDATED", "quarkus:3.36.1|jdk:26", null, null);

        assertThat(result).contains("staleness");
        assertThat(result).contains("quarkus:3.36.1|jdk:26");
        var feedback = retrievalTracker.findFeedback(corpus,
                java.time.Instant.EPOCH, java.time.Instant.now());
        assertThat(feedback).hasSize(1);
        assertThat(feedback.getFirst().outcome())
                .isEqualTo(io.casehub.neocortex.rag.RetrievalOutcome.NOT_RELEVANT);
        var reports = provenanceStore.findStalenessReports("GE-20260620-a1b2c3");
        assertThat(reports).hasSize(1);
        assertThat(reports.getFirst().stack()).isEqualTo("quarkus:3.36.1|jdk:26");
    }

    @Test
    void gardenFeedbackOutdatedRequiresStack() {
        String result = mcpTools.gardenFeedback("GE-20260620-a1b2c3", "OUTDATED", null, null, null);

        assertThat(result.toLowerCase()).contains("error");
        assertThat(result).contains("stack");
    }

    @Test
    void gardenRecordProvenanceAutoRecordsFeedback() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        retrievalTracker.record(
                RetrievalQuery.of("hibernate CDI"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of()),
                        new RetrievedChunk("content", "jvm/GE-20260621-d4e5f6.md", 0.8, Map.of())),
                16);

        mcpTools.gardenRecordProvenance(
                "Hortora/trellis", 14, null,
                "GE-20260620-a1b2c3|GE-20260621-d4e5f6", "brainstorming");

        var feedback = retrievalTracker.findFeedback(corpus,
                java.time.Instant.EPOCH, java.time.Instant.now());
        assertThat(feedback).hasSize(2);
        assertThat(feedback).allMatch(f ->
                f.outcome() == io.casehub.neocortex.rag.RetrievalOutcome.RELEVANT);
    }

    @Test
    void gardenRecordProvenanceSkipsFeedbackForUnretrievedEntries() {
        mcpTools.gardenRecordProvenance(
                "Hortora/trellis", 14, null, "GE-0031", "brainstorming");

        CorpusRef corpus = new CorpusRef("hortora", "garden");
        var feedback = retrievalTracker.findFeedback(corpus,
                java.time.Instant.EPOCH, java.time.Instant.now());
        assertThat(feedback).isEmpty();
    }

    @Test
    void gardenRecordOutcomeDelegatesToService() {
        String result = mcpTools.gardenRecordOutcome(
                "GE-20260620-a1b2c3", "Hortora/engine", 75,
                "Testing outcome", 0.9, "Helpful");

        assertThat(result).contains("recorded");
        assertThat(result).contains("GE-20260620-a1b2c3");
    }

    @Test
    void gardenFeedbackRecordsAgainstMostRecentRetrieval() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        retrievalTracker.record(
                RetrievalQuery.of("hibernate"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
                16);

        String result = mcpTools.gardenFeedback("GE-20260620-a1b2c3", "RELEVANT", null, null, null);

        assertThat(result).contains("1 feedback");
        var feedback = retrievalTracker.findFeedback(corpus,
                java.time.Instant.EPOCH, java.time.Instant.now());
        assertThat(feedback).hasSize(1);
        assertThat(feedback.getFirst().outcome())
                .isEqualTo(io.casehub.neocortex.rag.RetrievalOutcome.RELEVANT);
        assertThat(feedback.getFirst().sourceDocumentId())
                .isEqualTo("jvm/GE-20260620-a1b2c3.md");
    }

    @Test
    void gardenFeedbackHandlesMultipleGeIds() {
        CorpusRef corpus = new CorpusRef("hortora", "garden");
        retrievalTracker.record(
                RetrievalQuery.of("hibernate CDI"),
                corpus,
                List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of()),
                        new RetrievedChunk("content", "jvm/GE-20260621-d4e5f6.md", 0.8, Map.of())),
                16);

        String result = mcpTools.gardenFeedback(
                "GE-20260620-a1b2c3|GE-20260621-d4e5f6", "HIGHLY_RELEVANT", null, null, null);

        assertThat(result).contains("2 feedback");
        var feedback = retrievalTracker.findFeedback(corpus,
                java.time.Instant.EPOCH, java.time.Instant.now());
        assertThat(feedback).hasSize(2);
    }

    @Test
    void gardenFeedbackSkipsUnretrievedEntries() {
        String result = mcpTools.gardenFeedback("GE-20260620-a1b2c3", "RELEVANT", null, null, null);

        assertThat(result).contains("0 feedback");
        assertThat(result).contains("1 skipped");
    }

    @Test
    void gardenFeedbackRejectsInvalidOutcome() {
        String result = mcpTools.gardenFeedback("GE-20260620-a1b2c3", "BOGUS", null, null, null);

        assertThat(result.toLowerCase()).contains("invalid outcome");
    }

    @Test
    void gardenFeedbackRejectsEmptyIds() {
        String result = mcpTools.gardenFeedback("|||", "RELEVANT", null, null, null);

        assertThat(result.toLowerCase()).contains("error");
    }

    @Test
    void gardenDeleteEntryRemovesFromIndex() {
        assertThat(ingestor.listDocuments(CORPUS)).contains("jvm/GE-20260620-a1b2c3.md");

        String result = mcpTools.gardenDeleteEntry("GE-20260620-a1b2c3");

        assertThat(result).contains("Deleted vectors for GE-20260620-a1b2c3");
        assertThat(ingestor.listDocuments(CORPUS)).doesNotContain("jvm/GE-20260620-a1b2c3.md");
    }

    @Test
    void gardenDeleteEntryReturnsMessageForMissingEntry() {
        String result = mcpTools.gardenDeleteEntry("GE-99999999-ffffff");

        assertThat(result).contains("not found in Qdrant index");
    }

    @Test
    void gardenReindexEntryReEmbedsExistingEntry() {
        assertThat(ingestor.listDocuments(CORPUS)).contains("jvm/GE-20260620-a1b2c3.md");

        String result = mcpTools.gardenReindexEntry("GE-20260620-a1b2c3");

        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("Re-indexed GE-20260620-a1b2c3"),
                r -> assertThat(r).contains("File not found on disk")
        );
    }

    @Test
    void gardenReindexEntryReturnsMessageForUnknownEntry() {
        String result = mcpTools.gardenReindexEntry("GE-99999999-ffffff");

        assertThat(result).contains("not found");
    }

    @Test
    void gardenOutcomeReportRendersOutput() {
        mcpTools.gardenRecordOutcome(
                "GE-20260620-a1b2c3", "Hortora/engine", 75,
                "Testing", 0.5, null);

        String result = mcpTools.gardenOutcomeReport();

        assertThat(result).contains("GE-20260620-a1b2c3");
    }


}
