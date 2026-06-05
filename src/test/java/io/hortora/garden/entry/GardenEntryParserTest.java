package io.hortora.garden.entry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GardenEntryParserTest {

    private final GardenEntryParser parser = new GardenEntryParser();

    @Test
    void parsesTitle() throws Exception {
        var entry = parse("ge-test-hibernate-lazy.md");
        assertThat(entry.title()).isEqualTo("Hibernate lazy loading fails outside transaction");
    }

    @Test
    void parsesDomain() throws Exception {
        var entry = parse("ge-test-hibernate-lazy.md");
        assertThat(entry.domain()).isEqualTo("jvm");
    }

    @Test
    void parsesType() throws Exception {
        var entry = parse("ge-test-hibernate-lazy.md");
        assertThat(entry.type()).isEqualTo("gotcha");
    }

    @Test
    void parsesScore() throws Exception {
        var entry = parse("ge-test-hibernate-lazy.md");
        assertThat(entry.score()).isEqualTo(8);
    }

    @Test
    void parsesTags() throws Exception {
        var entry = parse("ge-test-hibernate-lazy.md");
        assertThat(entry.tags()).containsExactlyInAnyOrder("hibernate", "lazy-loading", "transactions");
    }

    @Test
    void parsesBody() throws Exception {
        var entry = parse("ge-test-hibernate-lazy.md");
        assertThat(entry.body()).contains("LazyInitializationException");
    }

    @Test
    void derivesIdFromFilePath() throws Exception {
        var entry = parse("ge-test-hibernate-lazy.md");
        assertThat(entry.id()).contains("ge-test-hibernate-lazy.md");
    }

    @Test
    void throwsWhenNoFrontmatter() {
        assertThatThrownBy(() -> parse("ge-test-no-frontmatter.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GardenEntry parse(String filename) throws Exception {
        return parser.parse(Path.of("src/test/resources/fixtures/" + filename));
    }
}
