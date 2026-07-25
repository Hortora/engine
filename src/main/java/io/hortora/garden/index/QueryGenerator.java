package io.hortora.garden.index;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface QueryGenerator {
    Optional<List<String>> generate(String title, String body, Path entryPath);
}
