package io.hortora.garden.inference;

import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.CursorStore;
import io.casehub.rag.EmbeddingIngestor;
import io.casehub.rag.runtime.RagConfig;
import io.hortora.garden.config.GardenConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
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

    private final Instance<SparseEmbedder> sparseEmbedderInstance;
    private final QdrantClient qdrantClient;
    private final EmbeddingIngestor embeddingIngestor;
    private final CursorStore cursorStore;
    private final GardenConfig gardenConfig;
    private final RagConfig ragConfig;

    @Inject
    public CollectionMigration(
            Instance<SparseEmbedder> sparseEmbedderInstance,
            QdrantClient qdrantClient,
            EmbeddingIngestor embeddingIngestor,
            CursorStore cursorStore,
            GardenConfig gardenConfig,
            RagConfig ragConfig) {
        this.sparseEmbedderInstance = sparseEmbedderInstance;
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
        if (!sparseEmbedderInstance.isResolvable()) {
            return;
        }

        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        String collectionName = ragConfig.tenancyStrategy().collectionName(corpusRef);

        try {
            if (!qdrantClient.collectionExistsAsync(collectionName).get()) {
                return;
            }

            CollectionInfo info = qdrantClient.getCollectionInfoAsync(collectionName).get();
            CollectionParams params = info.getConfig().getParams();

            if (params.hasSparseVectorsConfig()) {
                Log.infof("Collection '%s' already has sparse vectors — no migration needed", collectionName);
                return;
            }

            Log.infof("Collection '%s' lacks sparse vectors — migrating to hybrid", collectionName);
            resetCorpus(corpusRef, gardenConfig.id());
            Log.info("Migration complete — collection deleted and cursor reset. Full re-index will run on next ingestion cycle.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.warn("Interrupted during collection migration check", e);
        } catch (ExecutionException e) {
            Log.warn("Failed to check collection for migration", e.getCause());
        }
    }
}
