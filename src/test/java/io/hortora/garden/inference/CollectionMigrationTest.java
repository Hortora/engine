package io.hortora.garden.inference;

import com.google.common.util.concurrent.Futures;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.CursorStore;
import io.casehub.rag.EmbeddingIngestor;
import io.casehub.rag.runtime.RagConfig;
import io.casehub.rag.runtime.TenancyStrategy;
import io.casehub.inference.splade.SparseEmbedder;
import io.hortora.garden.config.GardenConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CollectionMigrationTest {

    private Instance<SparseEmbedder> sparseEmbedderInstance;
    private QdrantClient qdrantClient;
    private EmbeddingIngestor embeddingIngestor;
    private CursorStore cursorStore;
    private GardenConfig gardenConfig;
    private RagConfig ragConfig;
    private CollectionMigration migration;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        sparseEmbedderInstance = mock(Instance.class);
        qdrantClient = mock(QdrantClient.class);
        embeddingIngestor = mock(EmbeddingIngestor.class);
        cursorStore = mock(CursorStore.class);
        gardenConfig = mock(GardenConfig.class);
        ragConfig = mock(RagConfig.class);

        when(gardenConfig.id()).thenReturn("garden");
        when(ragConfig.tenancyStrategy()).thenReturn(TenancyStrategy.SEPARATE_COLLECTIONS);

        migration = new CollectionMigration(
                sparseEmbedderInstance, qdrantClient,
                embeddingIngestor, cursorStore,
                gardenConfig, ragConfig);
    }

    @Test
    void migratesWhenCollectionExistsWithoutSparseVectors() throws Exception {
        when(sparseEmbedderInstance.isResolvable()).thenReturn(true);
        when(qdrantClient.collectionExistsAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(true));

        CollectionParams params = CollectionParams.newBuilder().build();
        CollectionConfig config = CollectionConfig.newBuilder().setParams(params).build();
        CollectionInfo info = CollectionInfo.newBuilder().setConfig(config).build();
        when(qdrantClient.getCollectionInfoAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(info));

        migration.onStartup(null);

        verify(embeddingIngestor).deleteCorpus(eq(new CorpusRef("hortora", "garden")));
        verify(cursorStore).save("garden", "");
    }

    @Test
    void noOpWhenCollectionAlreadyHasSparseVectors() throws Exception {
        when(sparseEmbedderInstance.isResolvable()).thenReturn(true);
        when(qdrantClient.collectionExistsAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(true));

        CollectionParams params = CollectionParams.newBuilder()
                .setSparseVectorsConfig(
                        SparseVectorConfig.newBuilder()
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
        when(sparseEmbedderInstance.isResolvable()).thenReturn(true);
        when(qdrantClient.collectionExistsAsync("hortora_garden"))
                .thenReturn(Futures.immediateFuture(false));

        migration.onStartup(null);

        verify(qdrantClient, never()).getCollectionInfoAsync(any());
        verify(embeddingIngestor, never()).deleteCorpus(any());
    }

    @Test
    void noOpWhenSparseEmbedderNotResolvable() {
        when(sparseEmbedderInstance.isResolvable()).thenReturn(false);

        migration.onStartup(null);

        verifyNoInteractions(qdrantClient, embeddingIngestor, cursorStore);
    }
}
