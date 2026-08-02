package io.hortora.garden.provenance;

public record ProvenanceRecord(
        String issueRepo,
        int issueNumber,
        String specName,
        String geId,
        String recordedAt,
        String recordedBy) {}
