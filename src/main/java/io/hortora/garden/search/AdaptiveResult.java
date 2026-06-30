package io.hortora.garden.search;

import java.util.List;

public record AdaptiveResult(
        List<SearchResult> results,
        int requestedLimit,
        int availableAboveFloor,
        boolean extended) {
}
