package io.hortora.garden.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.entry.GardenEntry;
import io.hortora.garden.entry.GardenEntryParser;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class GardenIndexer {

    @Inject
    GardenConfig config;

    @Inject
    GardenEntryParser parser;

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    EmbeddingStore<TextSegment> embeddingStore;

    private volatile int indexedCount = 0;

    void onStart(@Observes StartupEvent event) {
        try {
            indexGarden();
        } catch (IOException e) {
            Log.errorf("Failed to index garden at %s: %s", config.path(), e.getMessage());
        }
    }

    void indexGarden() throws IOException {
        Path gardenPath = config.path();
        if (!Files.isDirectory(gardenPath)) {
            Log.warnf("Garden path does not exist or is not a directory: %s — starting with empty index", gardenPath);
            return;
        }

        List<GardenEntry> entries = new ArrayList<>();
        try (var walk = Files.walk(gardenPath)) {
            walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> {
                        try {
                            entries.add(parser.parse(p));
                        } catch (IllegalArgumentException e) {
                            // No frontmatter — not a garden entry (e.g. README.md), skip silently
                        } catch (IOException e) {
                            Log.warnf("Could not read %s: %s", p, e.getMessage());
                        }
                    });
        }

        if (entries.isEmpty()) {
            Log.infof("No garden entries found at: %s", gardenPath);
            return;
        }

        List<TextSegment> segments = entries.stream()
                .map(this::toSegment)
                .toList();

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

        indexedCount = entries.size();
        Log.infof("Garden indexed: %d entries from %s", indexedCount, gardenPath);
    }

    public int indexedCount() {
        return indexedCount;
    }

    private TextSegment toSegment(GardenEntry entry) {
        var metadata = dev.langchain4j.data.document.Metadata.from("id", entry.id())
                .put("title", entry.title() != null ? entry.title() : "")
                .put("domain", entry.domain() != null ? entry.domain() : "")
                .put("type", entry.type() != null ? entry.type() : "")
                .put("score", String.valueOf(entry.score()));
        return TextSegment.from(entry.title() + "\n\n" + entry.body(), metadata);
    }
}
