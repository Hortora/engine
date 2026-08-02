package io.hortora.garden.provenance;

import java.util.List;

public record ProvenanceRecordRequest(
        String issueRepo,
        int issueNumber,
        String specName,
        List<String> geIds,
        String recordedBy) {}
