package io.hortora.garden.search;

import com.fasterxml.jackson.annotation.JsonInclude;

public record SearchResult(
        String id,
        String title,
        String domain,
        String type,
        int score,
        String body,
        double relevance,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double crossEncoderScore,
        String source,
        String sourcePrefix) {
}
