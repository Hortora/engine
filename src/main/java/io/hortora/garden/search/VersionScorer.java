package io.hortora.garden.search;

import java.util.Map;

public class VersionScorer {

    public record Config(double decayFactor, double floor, double defaultTopicWeight) {}

    public double score(String verifiedOn, Map<String, String> bom, String queryText, Config config) {
        if (verifiedOn == null || verifiedOn.isBlank()) return 1.0;
        if (bom == null || bom.isEmpty()) return 1.0;

        String[] parts = verifiedOn.split(":", 2);
        if (parts.length < 2) return 1.0;

        String stack = parts[0];
        String entryVersion = parts[1];
        String bomVersion = bom.get(stack);
        if (bomVersion == null) return 1.0;

        int[] entryParts = parseVersion(entryVersion);
        int[] bomParts = parseVersion(bomVersion);

        if (entryParts[0] != bomParts[0]) return config.floor;

        int minorDistance = Math.abs(bomParts[1] - entryParts[1]);
        if (minorDistance == 0) return 1.0;

        double topicWeight = queryContainsStack(queryText, stack) ? 1.0 : config.defaultTopicWeight;
        return Math.max(config.floor, 1.0 - minorDistance * config.decayFactor * topicWeight);
    }

    static boolean queryContainsStack(String query, String stack) {
        if (query == null || query.isBlank()) return false;
        String lowerStack = stack.toLowerCase();
        for (String token : query.toLowerCase().split("[\\s\\-]+")) {
            if (token.length() >= 3 && lowerStack.contains(token)) return true;
        }
        return false;
    }

    static int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int major = 0, minor = 0;
        try { major = Integer.parseInt(parts[0]); } catch (NumberFormatException ignored) {}
        if (parts.length > 1) {
            try { minor = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return new int[]{major, minor};
    }
}
