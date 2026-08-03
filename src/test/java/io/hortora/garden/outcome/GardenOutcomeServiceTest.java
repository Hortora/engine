package io.hortora.garden.outcome;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class GardenOutcomeServiceTest {

    @Inject GardenOutcomeService service;

    @BeforeEach
    void clearOutcomes() {
        service.clearAll();
    }

    @Test
    void recordOutcomeCreatesCase() {
        String result = service.recordOutcome(
                "GE-20260620-a1b2c3", "Hortora/engine", 75,
                "Refactoring gardenUnretrieved", 0.8, "Relevant but slightly outdated");

        assertThat(result).contains("GE-20260620-a1b2c3");
        assertThat(result).contains("recorded");
    }

    @Test
    void secondOutcomeAdjustsConfidence() {
        service.recordOutcome("GE-20260620-a1b2c3", "Hortora/engine", 75,
                "First task", 1.0, "Very helpful");
        service.recordOutcome("GE-20260620-a1b2c3", "Hortora/engine", 76,
                "Second task", 0.0, "Not relevant");

        String report = service.outcomeReport();
        assertThat(report).contains("GE-20260620-a1b2c3");
        assertThat(report).contains("confidence");
    }

    @Test
    void outcomeReportEmptyWhenNoOutcomes() {
        String report = service.outcomeReport();
        assertThat(report).contains("No outcome data");
    }

    @Test
    void recordOutcomeDoesNotCreateDuplicates() {
        service.recordOutcome("GE-20260620-a1b2c3", "Hortora/engine", 75,
                "First", 1.0, null);
        service.recordOutcome("GE-20260620-a1b2c3", "Hortora/engine", 76,
                "Second", 0.5, null);
        service.recordOutcome("GE-20260620-a1b2c3", "Hortora/engine", 77,
                "Third", 0.0, null);

        String report = service.outcomeReport();
        long count = report.lines()
                .filter(l -> l.contains("GE-20260620-a1b2c3"))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
