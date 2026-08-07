package io.hortora.garden.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TemporalDecayScorerTest {

    private final TemporalDecayScorer scorer = new TemporalDecayScorer();

    @Test
    void freshEntryScoresHigh() {
        String today = LocalDate.now().toString();
        double score = scorer.score(today, "1");
        assertThat(score).isGreaterThan(0.95);
    }

    @Test
    void oldFastDecayEntryScoresLow() {
        String sixMonthsAgo = LocalDate.now().minusDays(180).toString();
        double score = scorer.score(sixMonthsAgo, "0");
        assertThat(score).isLessThan(0.05);
    }

    @Test
    void oldSlowDecayEntryScoresModerate() {
        String sixMonthsAgo = LocalDate.now().minusDays(180).toString();
        double score = scorer.score(sixMonthsAgo, "2");
        assertThat(score).isGreaterThan(0.5);
    }

    @Test
    void evergreenAlwaysReturnsOne() {
        String yearAgo = LocalDate.now().minusDays(365).toString();
        assertThat(scorer.score(yearAgo, "3")).isEqualTo(1.0);
    }

    @Test
    void halfLifeIsAccurate() {
        String ninetyDaysAgo = LocalDate.now().minusDays(90).toString();
        double score = scorer.score(ninetyDaysAgo, "1");
        assertThat(score).isCloseTo(0.5, within(0.05));
    }

    @Test
    void nullSubmittedReturnsOne() {
        assertThat(scorer.score(null, "1")).isEqualTo(1.0);
    }

    @Test
    void nullTierDefaultsToStandard() {
        String today = LocalDate.now().toString();
        assertThat(scorer.score(today, null)).isGreaterThan(0.95);
    }

    @Test
    void unparsableDateReturnsOne() {
        assertThat(scorer.score("not-a-date", "1")).isEqualTo(1.0);
    }

    @Test
    void futureDateReturnsOne() {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        assertThat(scorer.score(tomorrow, "1")).isEqualTo(1.0);
    }
}
