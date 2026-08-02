package io.hortora.garden.provenance;

import java.util.List;

public record ProvenanceStats(
        int totalRecords,
        int uniqueEntries,
        int uniqueIssues,
        List<EntryRefCount> topReferenced,
        int unreferencedCount) {}
