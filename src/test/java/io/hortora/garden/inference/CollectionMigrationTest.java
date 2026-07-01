package io.hortora.garden.inference;

import com.google.common.util.concurrent.Futures;
import io.casehub.inference.EmbeddingMode;
import io.casehub.inference.MultiModalEmbedder;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.CursorStore;
import io.casehub.rag.EmbeddingIngestor;
import io.casehub.rag.runtime.RagConfig;
import io.casehub.rag.runtime.TenancyStrategy;
import io.hortora.garden.config.GardenConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CollectionMigrationTest {

    private Instance<MultiModalEmbedder> multiModalEmbedderInstance;
    private MultiModalEmbedder multiModalEmbedder;
    private QdrantClient qdrantClient;
    private EmbeddingIngestor embeddingIngestor;
    private CursorStore cursorStore;
    private GardenConfig gardenConfig;
    private RagConfig ragConfig;
    private CollectionMigration migration;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        multiModalEmbedderInstance = mock(Instance.class);
        multiModalEmbedder = mock(MultiModalEmbedder.class);
        qdrantClient = mock(QdrantClient.class);
        embeddingIngestor = mock(EmbeddingIngestor.class);
        cursorStore = mock(CursorStore.class);
        gardenConfig = mock(GardenConfig.class);
        ragConfig = mock(RagConfig.class);

        when(gardenConfig.id()).thenReturn("garden");
        when(ragConfig.tenancyStrategy()).thenReturn(TenancyStrategy.SEPARATE_COLLECTIONS);
        when(multiModalEmbedder.denseDimension()).thenReturn(1024);
        when(multiModalEmbedder.colbertDimension()).thenReturn(OptionalInt.of(1024));
        when(multiModalEmbedder.supportedModes()).thenReturn(
                Set.of(EmbeddingMode.DENSE, EmbeddingMode.SPARSE, EmbeddingMode.COLBERT));
        when(multiModalEmbedderInstance.get()).thenReturn(multiModalEmbedder);

        migration = new CollectionMigration(
                multiModalEmbedderInstance, qdrantClient,
                embeddingIngestor, cursorStore,
                gardenConfig, ragConfig);
    }

    @Test
    void migratesWhenCollectionExistsWithoutSparseVectors() throws Exception {
        when(multiModalEmbedderInstance.isResolvable()).thenReturn(true);
        when(qdrantClient.collectionExistsAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(true));

        // Collection with dense vectors at 1024 dim, but no sparse and no colbert
        CollectionParams params = CollectionParams.newBuilder()
                .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParamsMap(VectorParamsMap.newBuilder()
                                .putMap("dense", VectorParams.newBuilder().setSize(1024).build())
                                .build())
                        .build())
                .build();
        CollectionConfig config = CollectionConfig.newBuilder().setParams(params).build();
        CollectionInfo info = CollectionInfo.newBuilder().setConfig(config).build();
        when(qdrantClient.getCollectionInfoAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(info));

        migration.onStartup(null);

        verify(embeddingIngestor).deleteCorpus(eq(new CorpusRef("hortora", "garden")));
        verify(cursorStore).save("garden", "");
    }

    @Test
    void migratesWhenDenseDimensionMismatches() throws Exception {
        when(multiModalEmbedderInstance.isResolvable()).thenReturn(true);
        when(qdrantClient.collectionExistsAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(true));

        // Old 768-dim dense vectors (from Ollama nomic-embed-text)
        CollectionParams params = CollectionParams.newBuilder()
                .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParamsMap(VectorParamsMap.newBuilder()
                                .putMap("dense", VectorParams.newBuilder().setSize(768).build())
                                .putMap("colbert", VectorParams.newBuilder().setSize(1024).build())
                                .build())
                        .build())
                .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                        .putMap("sparse", SparseVectorParams.getDefaultInstance())
                        .build())
                .build();
        CollectionConfig config = CollectionConfig.newBuilder().setParams(params).build();
        CollectionInfo info = CollectionInfo.newBuilder().setConfig(config).build();
        when(qdrantClient.getCollectionInfoAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(info));

        migration.onStartup(null);

        verify(embeddingIngestor).deleteCorpus(eq(new CorpusRef("hortora", "garden")));
        verify(cursorStore).save("garden", "");
    }

    @Test
    void migratesWhenColbertConfigMissing() throws Exception {
        when(multiModalEmbedderInstance.isResolvable()).thenReturn(true);
        when(qdrantClient.collectionExistsAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(true));

        // Has sparse and correct dense dim, but missing ColBERT
        CollectionParams params = CollectionParams.newBuilder()
                .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParamsMap(VectorParamsMap.newBuilder()
                                .putMap("dense", VectorParams.newBuilder().setSize(1024).build())
                                .build())
                        .build())
                .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                        .putMap("sparse", SparseVectorParams.getDefaultInstance())
                        .build())
                .build();
        CollectionConfig config = CollectionConfig.newBuilder().setParams(params).build();
        CollectionInfo info = CollectionInfo.newBuilder().setConfig(config).build();
        when(qdrantClient.getCollectionInfoAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(info));

        migration.onStartup(null);

        verify(embeddingIngestor).deleteCorpus(eq(new CorpusRef("hortora", "garden")));
        verify(cursorStore).save("garden", "");
    }

    @Test
    void noOpWhenCollectionFullyMigrated() throws Exception {
        when(multiModalEmbedderInstance.isResolvable()).thenReturn(true);
        when(qdrantClient.collectionExistsAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(true));

        // Full BGE-M3 config: dense 1024, sparse, colbert
        CollectionParams params = CollectionParams.newBuilder()
                .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParamsMap(VectorParamsMap.newBuilder()
                                .putMap("dense", VectorParams.newBuilder().setSize(1024).build())
                                .putMap("colbert", VectorParams.newBuilder().setSize(1024).build())
                                .build())
                        .build())
                .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                        .putMap("sparse", SparseVectorParams.getDefaultInstance())
                        .build())
                .build();
        CollectionConfig config = CollectionConfig.newBuilder().setParams(params).build();
        CollectionInfo info = CollectionInfo.newBuilder().setConfig(config).build();
        when(qdrantClient.getCollectionInfoAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(info));

        migration.onStartup(null);

        verify(embeddingIngestor, never()).deleteCorpus(any());
        verify(cursorStore, never()).save(any(), any());
    }

    @Test
    void noOpWhenCollectionDoesNotExist() throws Exception {
        when(multiModalEmbedderInstance.isResolvable()).thenReturn(true);
        when(qdrantClient.collectionExistsAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(false));

        migration.onStartup(null);

        verify(qdrantClient, never()).getCollectionInfoAsync(any());
        verify(embeddingIngestor, never()).deleteCorpus(any());
    }

    @Test
    void noOpWhenMultiModalEmbedderNotResolvable() {
        when(multiModalEmbedderInstance.isResolvable()).thenReturn(false);

        migration.onStartup(null);

        verifyNoInteractions(qdrantClient, embeddingIngestor, cursorStore);
    }

    @Test
    void resetCorpusDeletesAndResetsCursor() {
        CorpusRef corpusRef = new CorpusRef("hortora", "garden");

        migration.resetCorpus(corpusRef, "garden");

        verify(embeddingIngestor).deleteCorpus(eq(corpusRef));
        verify(cursorStore).save("garden", "");
    }
}
