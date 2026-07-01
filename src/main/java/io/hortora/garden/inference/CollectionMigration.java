package io.hortora.garden.inference;

import io.casehub.inference.MultiModalEmbedder;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.CursorStore;
import io.casehub.rag.EmbeddingIngestor;
import io.casehub.rag.runtime.RagConfig;
import io.hortora.garden.config.GardenConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.quarkus.logging.Log;

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
        if (!multiModalEmbedderInstance.isResolvable()) {
            return;
        }

        MultiModalEmbedder embedder = multiModalEmbedderInstance.get();
        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        String collectionName = ragConfig.tenancyStrategy().collectionName(corpusRef);

        try {
            if (!qdrantClient.collectionExistsAsync(collectionName).get()) {
                return;
            }

            CollectionInfo info = qdrantClient.getCollectionInfoAsync(collectionName).get();
            CollectionParams params = info.getConfig().getParams();

            // Check dense dimension mismatch
            int existingDim = extractDenseDimension(params);
            int expectedDim = embedder.denseDimension();
            if (existingDim > 0 && existingDim != expectedDim) {
                Log.infof("Collection '%s' dense dimension %d != expected %d — re-indexing",
                        collectionName, existingDim, expectedDim);
                resetCorpus(corpusRef, gardenConfig.id());
                return;
            }

            // Check missing sparse vectors
            if (!params.hasSparseVectorsConfig()) {
                Log.infof("Collection '%s' lacks sparse vectors — re-indexing", collectionName);
                resetCorpus(corpusRef, gardenConfig.id());
                return;
            }

            // Check missing ColBERT config (multi-vector)
            if (!hasColbertConfig(params)) {
                Log.infof("Collection '%s' lacks ColBERT multi-vector config — re-indexing",
                        collectionName);
                resetCorpus(corpusRef, gardenConfig.id());
                return;
            }

            Log.infof("Collection '%s' is up-to-date — no migration needed", collectionName);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.warn("Interrupted during collection migration check", e);
        } catch (ExecutionException e) {
            Log.warn("Failed to check collection for migration", e.getCause());
        }
    }

    /**
     * Extracts the dense vector dimension from collection params.
     * Returns -1 if dimension cannot be determined.
     */
    static int extractDenseDimension(CollectionParams params) {
        VectorsConfig vectorsConfig = params.getVectorsConfig();
        if (vectorsConfig.hasParams()) {
            return (int) vectorsConfig.getParams().getSize();
        }
        if (vectorsConfig.hasParamsMap() &&
                vectorsConfig.getParamsMap().containsMap("dense")) {
            VectorParams denseParams = vectorsConfig.getParamsMap().getMapOrDefault("dense", null);
            if (denseParams != null) {
                return (int) denseParams.getSize();
            }
        }
        return -1;
    }

    /**
     * Checks whether the collection has ColBERT multi-vector configuration.
     * ColBERT vectors are stored in a named vector called "colbert".
     */
    static boolean hasColbertConfig(CollectionParams params) {
        VectorsConfig vectorsConfig = params.getVectorsConfig();
        return vectorsConfig.hasParamsMap() &&
               vectorsConfig.getParamsMap().containsMap("colbert");
    }
}
