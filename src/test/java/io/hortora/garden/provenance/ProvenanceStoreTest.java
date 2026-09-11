package io.hortora.garden.provenance;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ProvenanceStoreTest {

    @Inject ProvenanceStore store;

    @BeforeEach
    void clear() {
        store.deleteAll();
    }

    @Test
    void recordAndForwardLineage() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031", "GE-0045"), "brainstorming");

        var lineage = store.forwardLineage("Hortora/trellis", 14);
        assertEquals(2, lineage.size());
        assertTrue(lineage.stream().anyMatch(r -> r.documentId().equals("GE-0031")));
        assertTrue(lineage.stream().anyMatch(r -> r.documentId().equals("GE-0045")));
    }

    @Test
    void recordIsIdempotent() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");

        var lineage2 = store.forwardLineage("Hortora/trellis", 14);
        assertEquals(1, lineage2.size());
    }

    @Test
    void reverseLineage() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");
        store.record("Hortora/engine", 42, "", List.of("GE-0031"), "work-start");

        var reverse = store.reverseLineage("GE-0031");
        assertEquals(2, reverse.size());
    }

    @Test
    void stats() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031", "GE-0045"), "brainstorming");
        store.record("Hortora/engine", 42, "", List.of("GE-0031"), "work-start");

        var stats = store.stats();
        assertEquals(3, stats.totalRecords());
        assertEquals(2, stats.uniqueDocuments());
        assertEquals(2, stats.uniqueActions());
        assertFalse(stats.topReferenced().isEmpty());
        assertEquals("GE-0031", stats.topReferenced().getFirst().documentId());
        assertEquals(2, stats.topReferenced().getFirst().count());
    }

    @Test
    void forwardLineageEmptyForUnknownIssue() {
        var lineage = store.forwardLineage("Hortora/unknown", 999);
        assertTrue(lineage.isEmpty());
    }

    @Test
    void reverseLineageEmptyForUnknownEntry() {
        var reverse = store.reverseLineage("GE-NONEXISTENT");
        assertTrue(reverse.isEmpty());
    }

    @Test
    void recordReturnsCount() {
        int count = store.record("Hortora/trellis", 14, "", List.of("GE-0031", "GE-0045"), "brainstorming");
        assertEquals(2, count);
    }
}
