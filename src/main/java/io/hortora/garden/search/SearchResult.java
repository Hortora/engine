package io.hortora.garden.search;

public record SearchResult(
        String id,
        String title,
        String domain,
        String type,
        int score,
        String body,
        double relevance,
        String source,
        String sourcePrefix) {
}
