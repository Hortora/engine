package io.hortora.garden.entry;

import java.util.List;

public record GardenEntry(
        String id,
        String title,
        String domain,
        String type,
        int score,
        List<String> tags,
        String submitted,
        String body) {
}
