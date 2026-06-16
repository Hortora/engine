package io.hortora.garden.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeType;
import io.casehub.corpus.ChangedEntry;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.PointStruct;
import io.quarkus.logging.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core ingestion component for the incremental re-indexing pipeline.
 * Receives change sets from FlatChangeSource and writes to Qdrant.
 *
 * <p>Not a CDI bean — created by GardenIndexer with all dependencies.
 *
 * <p>Concurrency: a ReentrantLock guards all ingestion paths.
 * Both ingest() and onChanges() use tryLock() and skip on contention.
 */
public class GardenIngestionService {

    record PendingEmbed(ChangedEntry entry, ExtractionResult result, TextSegment segment) {}

    static final UUID NAMESPACE = UUID.nameUUIDFromBytes(
            "hortora.garden".getBytes(StandardCharsets.UTF_8));
    private static final String DENSE_VECTOR_NAME = "dense";

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final GardenMetadataExtractor extractor;
    private final FileCursorStore cursorStore;
    private final Path gardenPath;
    private final String collectionName;
    private final ReentrantLock lock = new ReentrantLock();

    public GardenIngestionService(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            GardenMetadataExtractor extractor,
            FileCursorStore cursorStore,
            Path gardenPath,
            String collectionName) {
        this.qdrantClient = qdrantClient;
        this.embeddingModel = embeddingModel;
        this.extractor = extractor;
        this.cursorStore = cursorStore;
        this.gardenPath = gardenPath;
        this.collectionName = collectionName;
    }

    /**
     * Called at startup with fullScan or changesSince result.
     * Uses tryLock — skips if another ingest is already running.
     */
    public void ingest(ChangeSet changes) {
        if (!lock.tryLock()) {
            Log.debug("Skipping ingest — already in progress");
            return;
        }
        try {
            doIngest(changes);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called by watcher callback for file-system changes.
     * Uses tryLock — skips if startup ingest is still running.
     */
    public void onChanges(List<ChangedEntry> entries) {
        if (!lock.tryLock()) {
            Log.debug("Skipping watcher batch — ingest in progress");
            return;
        }
        try {
            doProcessEntries(entries);
        } catch (Exception e) {
            Log.errorf("Infrastructure failure during watcher batch: %s", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Exposed for testing only — allows tests to simulate lock contention.
     */
    ReentrantLock lockForTesting() {
        return lock;
    }

    private void doIngest(ChangeSet changes) {
        if (changes.entries().isEmpty()) {
            cursorStore.save(changes.newCursor());
            return;
        }

        try {
            doProcessEntries(changes.entries());
            cursorStore.save(changes.newCursor());
        } catch (Exception e) {
            Log.errorf("Infrastructure failure during ingest — cursor NOT advanced: %s",
                    e.getMessage());
        }
    }

    private void doProcessEntries(List<ChangedEntry> entries) {
        // Phase 1: deletions first
        for (ChangedEntry entry : entries) {
            if (entry.type() == ChangeType.DELETED || entry.type() == ChangeType.MODIFIED) {
                deleteDocument(entry.path());
            }
        }

        // Phase 2: additions/modifications — extract and batch embed
        List<PendingEmbed> pending = new ArrayList<>();

        for (ChangedEntry entry : entries) {
            if (entry.type() == ChangeType.ADDED || entry.type() == ChangeType.MODIFIED) {
                try {
                    byte[] content = Files.readAllBytes(gardenPath.resolve(entry.path()));
                    ExtractionResult result = extractor.extract(entry.path(), content);
                    if (result.content().isEmpty()) {
                        continue;
                    }
                    TextSegment segment = TextSegment.from(result.content());
                    pending.add(new PendingEmbed(entry, result, segment));
                } catch (IOException e) {
                    Log.warnf("Extraction failure — skipping %s: %s",
                            entry.path(), e.getMessage());
                }
            }
        }

        if (pending.isEmpty()) {
            return;
        }

        List<TextSegment> segments = pending.stream()
                .map(PendingEmbed::segment)
                .toList();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        List<PointStruct> points = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            PendingEmbed p = pending.get(i);
            points.add(buildPoint(p.entry().path(), p.result(), embeddings.get(i)));
        }

        if (!points.isEmpty()) {
            upsertPoints(points);
        }
    }

    private void deleteDocument(String path) {
        Filter filter = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("sourceDocumentId", path))
                .build();
        try {
            qdrantClient.deleteAsync(collectionName, filter).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during delete", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Delete failed for " + path, e.getCause());
        }
    }

    private void upsertPoints(List<PointStruct> points) {
        try {
            qdrantClient.upsertAsync(collectionName, points).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during upsert", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Upsert failed", e.getCause());
        }
    }

    private PointStruct buildPoint(String path, ExtractionResult result, Embedding embedding) {
        UUID pointId = UUID.nameUUIDFromBytes(
                (NAMESPACE + "/" + path).getBytes(StandardCharsets.UTF_8));

        Points.Vector denseVector = VectorFactory.vector(embedding.vectorAsList());

        Map<String, Value> payload = new HashMap<>();
        payload.put("content", ValueFactory.value(result.content()));
        payload.put("sourceDocumentId", ValueFactory.value(path));
        for (Map.Entry<String, String> meta : result.metadata().entrySet()) {
            payload.put(meta.getKey(), ValueFactory.value(meta.getValue()));
        }

        return PointStruct.newBuilder()
                .setId(PointIdFactory.id(pointId))
                .setVectors(VectorsFactory.namedVectors(Map.of(DENSE_VECTOR_NAME, denseVector)))
                .putAllPayload(payload)
                .build();
    }
}
