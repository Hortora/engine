package io.hortora.garden.index;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.inference.CollectionMigration;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GardenReindexService {

    @Inject EmbeddingIngestor embeddingIngestor;
    @Inject GardenConfig config;
    @Inject CollectionMigration collectionMigration;

    public ReindexResult reindex() {
        CorpusRef corpusRef = new CorpusRef("hortora", config.id());
        int fileCount;
        try {
            fileCount = embeddingIngestor.listDocuments(corpusRef).size();
        } catch (Exception e) {
            fileCount = -1;
        }

        try {
            collectionMigration.resetCorpus(corpusRef, config.id());
        } catch (Exception e) {
            Log.warn("Failed to trigger reindex", e);
            return new ReindexResult("error",
                    "Reindex failed for garden '" + config.id() + "': " + e.getMessage());
        }

        String message = "Reindex triggered for garden '" + config.id()
                + "'. Collection deleted, cursor reset. Re-embedding will complete on next ingestion cycle"
                + (fileCount >= 0 ? " (" + fileCount + " entries in corpus)." : ".");
        return new ReindexResult("ok", message);
    }

    public record ReindexResult(String status, String message) {}
}
