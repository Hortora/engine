package io.hortora.garden.index;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.WatchableChangeSource;
import io.casehub.corpus.zip.FlatChangeSource;
import io.casehub.corpus.zip.FlatCorpusStore;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.config.QdrantConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@ApplicationScoped
public class GardenIndexer {

    @Inject
    GardenConfig gardenConfig;

    @Inject
    QdrantConfig qdrantConfig;

    @Inject
    QdrantClient qdrantClient;

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    GardenMetadataExtractor extractor;

    private WatchableChangeSource changeSource;

    void onStart(@Observes StartupEvent event) {
        Path gardenPath = gardenConfig.path();
        String collection = qdrantConfig.collection();

        ensureCollection(collection);

        FileCursorStore cursorStore = new FileCursorStore(gardenPath);
        FlatCorpusStore corpusStore = new FlatCorpusStore(gardenPath);
        FlatChangeSource flatChangeSource = new FlatChangeSource(corpusStore, gardenPath);
        this.changeSource = flatChangeSource;

        GardenIngestionService ingestionService = new GardenIngestionService(
                qdrantClient, embeddingModel, extractor,
                cursorStore, gardenPath, collection);

        Optional<String> cursor = cursorStore.load();
        ChangeSet changes = cursor.isPresent()
                ? flatChangeSource.changesSince(cursor.get())
                : flatChangeSource.fullScan();

        ingestionService.ingest(changes);

        try {
            flatChangeSource.watch(entries -> ingestionService.onChanges(entries));
            Log.infof("Garden indexer started — watching %s", gardenPath);
        } catch (Exception e) {
            Log.warnf("Failed to start garden watcher on %s: %s — operating without live updates",
                    gardenPath, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (changeSource != null) {
            changeSource.close();
            Log.info("Garden watcher stopped");
        }
    }

    private void ensureCollection(String collection) {
        try {
            if (qdrantClient.collectionExistsAsync(collection).get()) {
                return;
            }
            VectorParams denseParams = VectorParams.newBuilder()
                    .setSize(768)
                    .setDistance(Distance.Cosine)
                    .build();
            CreateCollection createRequest = CreateCollection.newBuilder()
                    .setCollectionName(collection)
                    .setVectorsConfig(VectorsConfig.newBuilder()
                            .setParamsMap(VectorParamsMap.newBuilder()
                                    .putMap("dense", denseParams)
                                    .build())
                            .build())
                    .build();
            qdrantClient.createCollectionAsync(createRequest).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during collection creation", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create collection: " + collection, e.getCause());
        }
    }
}
