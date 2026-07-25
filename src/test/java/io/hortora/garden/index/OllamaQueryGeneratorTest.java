package io.hortora.garden.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaQueryGeneratorTest {

    @Test
    void validThreeLineOutput() {
        String raw = "How does @Produces work in Quarkus CDI?\nWhy does @DefaultBean require method-level annotation?\nWhat pattern enables replaceable CDI defaults?";
        List<String> result = OllamaQueryGenerator.validateOutput(raw, 3);
        assertThat(result).hasSize(3);
    }

    @Test
    void trailingEmptyLinesAreIgnored() {
        String raw = "First question here?\nSecond question here?\nThird question here?\n\n\n";
        List<String> result = OllamaQueryGenerator.validateOutput(raw, 3);
        assertThat(result).hasSize(3);
    }

    @Test
    void extraLinesAreTrimmed() {
        String raw = "Q1 about CDI producers?\nQ2 about bean scoping?\nQ3 about interceptors?\nQ4 extra line here?";
        List<String> result = OllamaQueryGenerator.validateOutput(raw, 3);
        assertThat(result).hasSize(3);
    }

    @Test
    void tooShortLinesAreFiltered() {
        String raw = "Good question about CDI?\nBad\nAnother good question here?";
        List<String> result = OllamaQueryGenerator.validateOutput(raw, 3);
        assertThat(result).hasSize(2);
        assertThat(result).noneMatch(q -> q.equals("Bad"));
    }

    @Test
    void tooLongLinesAreFiltered() {
        String longLine = "x".repeat(151);
        String raw = "Good question about CDI producers?\n" + longLine + "\nAnother good question?";
        List<String> result = OllamaQueryGenerator.validateOutput(raw, 3);
        assertThat(result).hasSize(2);
    }

    @Test
    void fewerThanTwoValidLinesReturnsEmpty() {
        String raw = "OK\nNah\nX";
        List<String> result = OllamaQueryGenerator.validateOutput(raw, 3);
        assertThat(result).isEmpty();
    }

    @Test
    void emptyOutputReturnsEmpty() {
        List<String> result = OllamaQueryGenerator.validateOutput("", 3);
        assertThat(result).isEmpty();
    }

    @Test
    void nullOutputReturnsEmpty() {
        List<String> result = OllamaQueryGenerator.validateOutput(null, 3);
        assertThat(result).isEmpty();
    }

    @Test
    void numberedLinesHaveNumbersStripped() {
        String raw = "1. How does @Produces work?\n2. Why use @DefaultBean?\n3. What is CDI priority?";
        List<String> result = OllamaQueryGenerator.validateOutput(raw, 3);
        assertThat(result).allMatch(q -> !q.startsWith("1.") && !q.startsWith("2.") && !q.startsWith("3."));
        assertThat(result.get(0)).isEqualTo("How does @Produces work?");
    }

    // --- Sidecar cache ---

    @Test
    void versionHashChangesWithModel(@TempDir Path tempDir) {
        String v1 = OllamaQueryGenerator.computeVersionHash("prompt-text", "gemma3:4b", 3);
        String v2 = OllamaQueryGenerator.computeVersionHash("prompt-text", "llama3.2", 3);
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void versionHashChangesWithPrompt(@TempDir Path tempDir) {
        String v1 = OllamaQueryGenerator.computeVersionHash("prompt-v1", "gemma3:4b", 3);
        String v2 = OllamaQueryGenerator.computeVersionHash("prompt-v2", "gemma3:4b", 3);
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void versionHashChangesWithQueryCount(@TempDir Path tempDir) {
        String v1 = OllamaQueryGenerator.computeVersionHash("prompt", "gemma3:4b", 3);
        String v2 = OllamaQueryGenerator.computeVersionHash("prompt", "gemma3:4b", 5);
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void readCacheReturnsQueriesWhenValid(@TempDir Path tempDir) throws IOException {
        Path queriesFile = tempDir.resolve("entry.queries");
        String versionHash = OllamaQueryGenerator.computeVersionHash("prompt", "model", 3);
        Files.writeString(queriesFile, "# v:" + versionHash + " prompt=abc123 model=model count=3\nQ1?\nQ2?\nQ3?\n");

        Path entryFile = tempDir.resolve("entry.md");
        Files.writeString(entryFile, "content");
        // Make queries file newer than entry
        queriesFile.toFile().setLastModified(entryFile.toFile().lastModified() + 1000);

        List<String> cached = OllamaQueryGenerator.readCache(queriesFile, entryFile, versionHash);
        assertThat(cached).containsExactly("Q1?", "Q2?", "Q3?");
    }

    @Test
    void readCacheReturnsEmptyWhenVersionMismatch(@TempDir Path tempDir) throws IOException {
        Path queriesFile = tempDir.resolve("entry.queries");
        Files.writeString(queriesFile, "# v:oldversion prompt=abc model=model count=3\nQ1?\nQ2?\nQ3?\n");

        Path entryFile = tempDir.resolve("entry.md");
        Files.writeString(entryFile, "content");
        queriesFile.toFile().setLastModified(entryFile.toFile().lastModified() + 1000);

        List<String> cached = OllamaQueryGenerator.readCache(queriesFile, entryFile, "newversion");
        assertThat(cached).isEmpty();
    }

    @Test
    void readCacheReturnsEmptyWhenEntryIsNewer(@TempDir Path tempDir) throws IOException {
        Path queriesFile = tempDir.resolve("entry.queries");
        String versionHash = OllamaQueryGenerator.computeVersionHash("prompt", "model", 3);
        Files.writeString(queriesFile, "# v:" + versionHash + " prompt=abc123 model=model count=3\nQ1?\nQ2?\nQ3?\n");

        Path entryFile = tempDir.resolve("entry.md");
        Files.writeString(entryFile, "updated content");
        // Make entry newer than queries
        entryFile.toFile().setLastModified(queriesFile.toFile().lastModified() + 1000);

        List<String> cached = OllamaQueryGenerator.readCache(queriesFile, entryFile, versionHash);
        assertThat(cached).isEmpty();
    }

    @Test
    void readCacheReturnsEmptyWhenFileMissing(@TempDir Path tempDir) {
        Path queriesFile = tempDir.resolve("nonexistent.queries");
        Path entryFile = tempDir.resolve("entry.md");
        List<String> cached = OllamaQueryGenerator.readCache(queriesFile, entryFile, "hash");
        assertThat(cached).isEmpty();
    }

    @Test
    void writeCacheCreatesValidFile(@TempDir Path tempDir) throws IOException {
        Path queriesFile = tempDir.resolve("entry.queries");
        String versionHash = "abc123";
        List<String> queries = List.of("Q1?", "Q2?", "Q3?");

        OllamaQueryGenerator.writeCache(queriesFile, versionHash, "gemma3:4b", 3, queries);

        List<String> lines = Files.readAllLines(queriesFile);
        assertThat(lines.get(0)).startsWith("# v:abc123 ");
        assertThat(lines.get(0)).contains("model=gemma3:4b");
        assertThat(lines.get(0)).contains("count=3");
        assertThat(lines.subList(1, lines.size())).containsExactly("Q1?", "Q2?", "Q3?");
    }
}
