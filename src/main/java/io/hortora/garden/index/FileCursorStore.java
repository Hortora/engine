package io.hortora.garden.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class FileCursorStore {

    private final Path cursorFile;

    public FileCursorStore(Path gardenPath) {
        this.cursorFile = gardenPath.resolve("_state/garden.cursor");
    }

    public Optional<String> load() {
        if (!Files.exists(cursorFile)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(cursorFile).trim();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read cursor: " + cursorFile, e);
        }
    }

    public void save(String cursor) {
        try {
            Files.createDirectories(cursorFile.getParent());
            Files.writeString(cursorFile, cursor);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write cursor: " + cursorFile, e);
        }
    }
}
