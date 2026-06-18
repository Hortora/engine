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

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class CollectionMigration {

    private static final Logger LOG = Logger.getLogger(CollectionMigration.class.getName());

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
                LOG.info(() -> "Collection '" + collectionName + "' already has sparse vectors — no migration needed");
                return;
            }

            LOG.info(() -> "Collection '" + collectionName + "' lacks sparse vectors — migrating to hybrid");
            embeddingIngestor.deleteCorpus(corpusRef);
            cursorStore.save(gardenConfig.id(), "");
            LOG.info(() -> "Migration complete — collection deleted and cursor reset. Full re-index will run on next ingestion cycle.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Interrupted during collection migration check", e);
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Failed to check collection for migration", e.getCause());
        }
    }
}
