package io.hortora.garden.index;

import io.casehub.neocortex.rag.ExtractionResult;
import io.casehub.neocortex.rag.MetadataExtractor;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.config.InvertedHydeConfig;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Decorator
@Priority(100)
public class QueryAugmentingExtractor implements MetadataExtractor {

    static final String SEPARATOR = "\n\n---HYPOTHETICAL-QUERIES---\n";

    private final MetadataExtractor delegate;
    private final QueryGenerator generator;
    private final Path gardenPath;
    private final boolean enabled;

    @Inject
    public QueryAugmentingExtractor(@Delegate @Any MetadataExtractor delegate,
                                     QueryGenerator generator,
                                     GardenConfig gardenConfig,
                                     InvertedHydeConfig hydeConfig) {
        this.delegate = delegate;
        this.generator = generator;
        this.gardenPath = gardenConfig.path();
        this.enabled = hydeConfig.enabled();
    }

    QueryAugmentingExtractor(MetadataExtractor delegate, QueryGenerator generator, Path gardenPath) {
        this.delegate = delegate;
        this.generator = generator;
        this.gardenPath = gardenPath;
        this.enabled = true;
    }

    @Override
    public ExtractionResult extract(String path, byte[] content) {
        ExtractionResult result = delegate.extract(path, content);

        if (!enabled || result.body() == null || result.body().isBlank()) {
            return result;
        }

        Path entryPath = gardenPath.resolve(path);
        String title = result.metadata().get("title");

        Optional<List<String>> queries = generator.generate(title, result.body(), entryPath);
        if (queries.isEmpty()) {
            return result;
        }

        String augmented = result.body() + SEPARATOR + String.join("\n", queries.get());
        return new ExtractionResult(augmented, result.metadata(), result.listMetadata());
    }

    public static String stripQueries(String content) {
        if (content == null) return null;
        int idx = content.indexOf(SEPARATOR);
        return idx >= 0 ? content.substring(0, idx) : content;
    }
}
