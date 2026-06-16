package io.hortora.garden.index;

import java.util.Map;

public record ExtractionResult(String content, Map<String, String> metadata) {
    public ExtractionResult {
        if (content == null)
            throw new IllegalArgumentException("content must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
