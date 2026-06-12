package io.hortora.garden.federation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FederationConfigParserTest {

    @Test
    void parsesValidChildConfig() throws IOException {
        var config = parse("valid-child.yaml");

        assertThat(config.gardenId()).isEqualTo("my-garden");
        assertThat(config.idPrefix()).isEqualTo("PE");
        assertThat(config.role()).isEqualTo("child");
        assertThat(config.relevanceThreshold()).isEqualTo(0.7);
        assertThat(config.maxDepth()).isEqualTo(5);

        assertThat(config.upstream()).hasSize(2);
        assertThat(config.upstream().get(0).url()).isEqualTo("https://api.hortora.io/jvm");
        assertThat(config.upstream().get(0).idPrefix()).isEqualTo("GE");
        assertThat(config.upstream().get(0).searchOrder()).isEqualTo("fallback");
        assertThat(config.upstream().get(1).url()).isEqualTo("https://enterprise.internal/garden");
        assertThat(config.upstream().get(1).idPrefix()).isEqualTo("EP");
        assertThat(config.upstream().get(1).searchOrder()).isEqualTo("always");

        assertThat(config.peers()).hasSize(1);
        assertThat(config.peers().get(0).url()).isEqualTo("http://localhost:8091");
        assertThat(config.peers().get(0).idPrefix()).isEqualTo("RE");
    }

    @Test
    void parsesCanonicalWithNoUpstreamOrPeers() throws IOException {
        var config = parse("valid-canonical.yaml");

        assertThat(config.gardenId()).isEqualTo("jvm-garden");
        assertThat(config.idPrefix()).isEqualTo("GE");
        assertThat(config.role()).isEqualTo("canonical");
        assertThat(config.upstream()).isEmpty();
        assertThat(config.peers()).isEmpty();
        assertThat(config.hasFederation()).isFalse();
    }

    @Test
    void noFederationBlockReturnsDefaults() throws IOException {
        var config = parse("no-federation.yaml");

        assertThat(config.gardenId()).isEqualTo("solo-garden");
        assertThat(config.idPrefix()).isEqualTo("SG");
        assertThat(config.role()).isEqualTo("canonical");
        assertThat(config.relevanceThreshold()).isEqualTo(0.7);
        assertThat(config.maxDepth()).isEqualTo(5);
        assertThat(config.upstream()).isEmpty();
        assertThat(config.peers()).isEmpty();
    }

    @Test
    void missingFileReturnsDefaults(@TempDir Path tempDir) throws IOException {
        var config = FederationConfigParser.parse(
                tempDir.resolve("nonexistent.yaml"), "default-garden", "DG");

        assertThat(config.gardenId()).isEqualTo("default-garden");
        assertThat(config.idPrefix()).isEqualTo("DG");
        assertThat(config.role()).isEqualTo("canonical");
        assertThat(config.hasFederation()).isFalse();
    }

    @Test
    void malformedYamlThrows() {
        assertThatThrownBy(() -> parse("malformed.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void invalidRoleThrows() {
        assertThatThrownBy(() -> parse("invalid-role.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    void invalidUrlThrows() {
        assertThatThrownBy(() -> parse("invalid-url.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }

    @Test
    void missingIdThrows() {
        assertThatThrownBy(() -> parse("missing-id.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void defaultRelevanceThreshold() throws IOException {
        var config = parse("valid-canonical.yaml");
        assertThat(config.relevanceThreshold()).isEqualTo(0.7);
    }

    @Test
    void defaultMaxDepth() throws IOException {
        var config = parse("valid-canonical.yaml");
        assertThat(config.maxDepth()).isEqualTo(5);
    }

    private FederationConfig parse(String fixtureFilename) throws IOException {
        Path fixture = Path.of("src/test/resources/fixtures/schema/" + fixtureFilename);
        return FederationConfigParser.parse(fixture, "fallback-id", "FB");
    }
}
