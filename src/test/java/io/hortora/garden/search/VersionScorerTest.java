package io.hortora.garden.search;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VersionScorerTest {

    private final VersionScorer scorer = new VersionScorer();
    private final Map<String, String> bom = Map.of("quarkus", "3.36.1", "jdk", "26.0.2", "onnx-runtime", "1.26.0");

    @Test
    void currentVersionScoresOne() {
        assertThat(scorer.score("quarkus:3.36.1", bom, "quarkus CDI", defaults())).isEqualTo(1.0);
    }

    @Test
    void minorVersionGapAppliesDecay() {
        double score = scorer.score("quarkus:3.20", bom, "quarkus CDI", defaults());
        assertThat(score).isCloseTo(0.52, within(0.01));
    }

    @Test
    void majorVersionGapHitsFloor() {
        double score = scorer.score("quarkus:2.0", bom, "quarkus CDI", defaults());
        assertThat(score).isEqualTo(0.5);
    }

    @Test
    void offTopicStackGetsReducedWeight() {
        double score = scorer.score("jdk:21", bom, "quarkus CDI", defaults());
        assertThat(score).isEqualTo(0.5);
    }

    @Test
    void minorGapOffTopicGetsReducedDecay() {
        double score = scorer.score("quarkus:3.30", bom, "jdk sealed interfaces", defaults());
        // distance=6 minor, topic_weight=0.3 → 1.0 - 6*0.03*0.3 = 0.946
        assertThat(score).isCloseTo(0.946, within(0.01));
    }

    @Test
    void noVerifiedOnReturnsOne() {
        assertThat(scorer.score(null, bom, "quarkus CDI", defaults())).isEqualTo(1.0);
        assertThat(scorer.score("", bom, "quarkus CDI", defaults())).isEqualTo(1.0);
    }

    @Test
    void noBomReturnsOne() {
        assertThat(scorer.score("quarkus:3.20", null, "quarkus CDI", defaults())).isEqualTo(1.0);
        assertThat(scorer.score("quarkus:3.20", Map.of(), "quarkus CDI", defaults())).isEqualTo(1.0);
    }

    @Test
    void stackNotInBomReturnsOne() {
        assertThat(scorer.score("python:3.12", bom, "python async", defaults())).isEqualTo(1.0);
    }

    @Test
    void noColonInVerifiedOnReturnsOne() {
        assertThat(scorer.score("quarkus", bom, "quarkus CDI", defaults())).isEqualTo(1.0);
    }

    @Test
    void topicWeightMatchesSubstring() {
        double score = scorer.score("onnx-runtime:1.20.0", bom, "onnx thread pool", defaults());
        // distance=6 minor, topic=1.0 → 1.0 - 6*0.03*1.0 = 0.82
        assertThat(score).isCloseTo(0.82, within(0.01));
    }

    @Test
    void queryContainsStackMatchesTokenSubstring() {
        assertThat(VersionScorer.queryContainsStack("quarkus CDI injection", "quarkus")).isTrue();
        assertThat(VersionScorer.queryContainsStack("onnx thread pool crash", "onnx-runtime")).isTrue();
        assertThat(VersionScorer.queryContainsStack("java sealed interfaces", "quarkus")).isFalse();
        assertThat(VersionScorer.queryContainsStack(null, "quarkus")).isFalse();
        assertThat(VersionScorer.queryContainsStack("", "quarkus")).isFalse();
    }

    @Test
    void shortTokensIgnored() {
        assertThat(VersionScorer.queryContainsStack("is it ok", "quarkus")).isFalse();
    }

    @Test
    void parseVersionHandlesVariousFormats() {
        assertThat(VersionScorer.parseVersion("3.36.1")).isEqualTo(new int[]{3, 36});
        assertThat(VersionScorer.parseVersion("26.0.2")).isEqualTo(new int[]{26, 0});
        assertThat(VersionScorer.parseVersion("3")).isEqualTo(new int[]{3, 0});
        assertThat(VersionScorer.parseVersion("abc")).isEqualTo(new int[]{0, 0});
    }

    private VersionScorer.Config defaults() {
        return new VersionScorer.Config(0.03, 0.5, 0.3);
    }
}
