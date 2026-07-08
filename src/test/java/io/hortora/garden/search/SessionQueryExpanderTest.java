package io.hortora.garden.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SessionQueryExpanderTest {

    @Test
    void skipSentinel() {
        assertThat(SessionQueryExpander.shouldSkip("SKIP")).isTrue();
        assertThat(SessionQueryExpander.shouldSkip("skip")).isTrue();
        assertThat(SessionQueryExpander.shouldSkip("Skip")).isTrue();
    }

    @Test
    void tooShortResponseIsSkipped() {
        assertThat(SessionQueryExpander.shouldSkip("Not enough info")).isTrue();
        assertThat(SessionQueryExpander.shouldSkip("a".repeat(29))).isTrue();
    }

    @Test
    void adequateLengthResponseIsNotSkipped() {
        assertThat(SessionQueryExpander.shouldSkip("a".repeat(30))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "I'm not sure what this refers to",
        "I don't know enough about this topic",
        "It's unclear what the query is asking",
        "This is a broad topic that covers many areas"
    })
    void hedgingResponseIsSkipped(String response) {
        assertThat(SessionQueryExpander.shouldSkip(response)).isTrue();
    }

    @Test
    void validHypotheticalIsNotSkipped() {
        String good = "@Alternative @Priority(100) bean wins over @DefaultBean — "
            + "AmbiguousResolutionException when both are on classpath";
        assertThat(SessionQueryExpander.shouldSkip(good)).isFalse();
    }
}
