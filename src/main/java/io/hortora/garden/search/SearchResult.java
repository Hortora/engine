package io.hortora.garden.search;

public record SearchResult(
        String title,
        String domain,
        String type,
        int score,
        String body,
        double relevance) {
}
