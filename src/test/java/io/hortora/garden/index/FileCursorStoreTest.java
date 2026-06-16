package io.hortora.garden.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileCursorStoreTest {

    @TempDir Path tempDir;

    @Test
    void loadReturnsEmptyWhenNoFileExists() {
        var store = new FileCursorStore(tempDir);
        assertThat(store.load()).isEmpty();
    }

    @Test
    void saveAndLoadRoundTrip() {
        var store = new FileCursorStore(tempDir);
        String cursor = "{\"jvm/GE-0144.md\":1718500000000}";
        store.save(cursor);
        assertThat(store.load()).hasValue(cursor);
    }

    @Test
    void saveCreatesStateDirectory() {
        var store = new FileCursorStore(tempDir);
        store.save("{}");
        assertThat(Files.isDirectory(tempDir.resolve("_state"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("_state/garden.cursor"))).isTrue();
    }

    @Test
    void saveOverwritesPreviousCursor() {
        var store = new FileCursorStore(tempDir);
        store.save("{\"a.md\":1}");
        store.save("{\"a.md\":2}");
        assertThat(store.load()).hasValue("{\"a.md\":2}");
    }
}
