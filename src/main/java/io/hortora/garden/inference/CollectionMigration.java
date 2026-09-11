package io.hortora.garden.inference;

import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.CursorStore;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.rag.runtime.CollectionCompatibility;
import io.casehub.neocortex.rag.runtime.CollectionExpectedConfig;
import io.casehub.neocortex.rag.runtime.MigrationAction;
import io.casehub.neocortex.rag.runtime.RagConfig;
import io.hortora.garden.config.GardenConfig;
import io.qdrant.client.QdrantClient;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.concurrent.ExecutionException;

@ApplicationScoped
public class CollectionMigration {

    private final Instance<MultiModalEmbedder> multiModalEmbedderInstance;
    private final QdrantClient qdrantClient;
    private final EmbeddingIngestor embeddingIngestor;
    private final CursorStore cursorStore;
    private final GardenConfig gardenConfig;
    private final RagConfig ragConfig;

    @Inject
    public CollectionMigration(
            Instance<MultiModalEmbedder> multiModalEmbedderInstance,
            QdrantClient qdrantClient,
            EmbeddingIngestor embeddingIngestor,
            CursorStore cursorStore,
            GardenConfig gardenConfig,
            RagConfig ragConfig) {
        this.multiModalEmbedderInstance = multiModalEmbedderInstance;
        this.qdrantClient = qdrantClient;
        this.embeddingIngestor = embeddingIngestor;
        this.cursorStore = cursorStore;
        this.gardenConfig = gardenConfig;
        this.ragConfig = ragConfig;
    }

    public void resetCorpus(CorpusRef corpusRef, String gardenId) {
        embeddingIngestor.deleteCorpus(corpusRef);
        cursorStore.save(gardenId, "");
    }

    void onStartup(@Observes @Priority(10) StartupEvent event) {
        onStartup(event, 5, 2000);
    }

    void onStartup(StartupEvent event, int maxRetries, long retryDelayMs) {
        if (!multiModalEmbedderInstance.isResolvable()) {
            return;
        }

        MultiModalEmbedder embedder = multiModalEmbedderInstance.get();
        validateColbertLimit(embedder);

        if (!waitForQdrant(maxRetries, retryDelayMs)) {
            return;
        }

        CorpusRef corpusRef      = new CorpusRef("hortora", gardenConfig.id());
        String    collectionName = ragConfig.tenancyStrategy().collectionName(corpusRef);

        try {
            if (!qdrantClient.collectionExistsAsync(collectionName).get()) {
                if (cursorStore.load(gardenConfig.id()).isPresent()) {
                    Log.infof("Collection '%s' does not exist but cursor found — clearing cursor to force re-indexing",
                              collectionName);
                    cursorStore.save(gardenConfig.id(), "");
                }
                return;
            }

            var expected = new CollectionExpectedConfig(
                    embedder.denseDimension(),
                    true,
                    embedder.colbertDimension().isPresent());
            MigrationAction action = CollectionCompatibility.check(qdrantClient, collectionName, expected);

            switch (action) {
                case MigrationAction.Compatible _ ->
                    Log.infof("Collection '%s' is up-to-date — no migration needed", collectionName);
                case MigrationAction.DimensionMismatch m -> {
                    Log.infof("Collection '%s' dense dimension %d != expected %d — re-indexing",
                              collectionName, m.actual(), m.expected());
                    resetCorpus(corpusRef, gardenConfig.id());
                }
                case MigrationAction.MissingSparseVectors _ -> {
                    Log.infof("Collection '%s' lacks sparse vectors — re-indexing", collectionName);
                    resetCorpus(corpusRef, gardenConfig.id());
                }
                case MigrationAction.MissingColBert _ -> {
                    Log.infof("Collection '%s' lacks ColBERT multi-vector config — re-indexing", collectionName);
                    resetCorpus(corpusRef, gardenConfig.id());
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.warn("Interrupted during collection migration check", e);
        } catch (ExecutionException e) {
            Log.warn("Failed to check collection for migration", e.getCause());
        }
    }


    private boolean waitForQdrant(int maxRetries, long delayMs) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                qdrantClient.listCollectionsAsync().get();
                Log.infof("Qdrant ready (attempt %d/%d)", attempt, maxRetries);
                return true;
            } catch (ExecutionException e) {
                Log.warnf("Qdrant not ready (attempt %d/%d): %s",
                          attempt, maxRetries, e.getCause().getMessage());
                if (attempt < maxRetries && delayMs > 0) {
                    try {Thread.sleep(delayMs);} catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.warn("Qdrant readiness check exhausted — proceeding without migration");
        return false;
    }

    private void validateColbertLimit(MultiModalEmbedder embedder) {
        if (embedder.colbertDimension().isEmpty()) {
            return;
        }
        int colbertDim = embedder.colbertDimension().getAsInt();
        long totalFloats = (long) embedder.maxSequenceLength() * colbertDim;
        int limit = ragConfig.maxMultivectorFloats();
        if (totalFloats > limit) {
            throw new IllegalStateException(
                    "ColBERT multi-vector size exceeds limit: maxSequenceLength=%d × colbertDimension=%d = %d floats > maxMultivectorFloats=%d. Reduce max-sequence-length or raise casehub.rag.max-multivector-floats."
                            .formatted(embedder.maxSequenceLength(), colbertDim, totalFloats, limit));
        }
    }

}
