package io.hortora.garden.search;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public class TemporalDecayScorer {

    private static final double LN2 = 0.693147;

    public double score(String submittedDate, String decayTier) {
        if (submittedDate == null || submittedDate.isBlank()) return 1.0;

        int tier = parseTier(decayTier);
        if (tier == 3) return 1.0;

        long ageDays;
        try {
            LocalDate submitted = LocalDate.parse(
                    submittedDate.length() > 10 ? submittedDate.substring(0, 10) : submittedDate);
            ageDays = ChronoUnit.DAYS.between(submitted, LocalDate.now());
            if (ageDays <= 0) return 1.0;
        } catch (DateTimeParseException e) {
            return 1.0;
        }

        int halfLifeDays = switch (tier) {
            case 0 -> 30;
            case 2 -> 365;
            default -> 90;
        };
        return Math.exp(-LN2 * ageDays / halfLifeDays);
    }

    private static int parseTier(String tier) {
        if (tier == null) return 1;
        try {
            return Integer.parseInt(tier);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
