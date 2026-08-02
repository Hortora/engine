package io.hortora.garden.search;

import java.util.List;

public record EntryDetail(
        String id,
        String title,
        String domain,
        String type,
        int score,
        String body,
        String source,
        String sourcePrefix,
        List<String> seeAlsoIds) {}
