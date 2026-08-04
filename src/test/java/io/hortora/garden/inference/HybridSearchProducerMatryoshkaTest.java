package io.hortora.garden.inference;

import io.casehub.neocortex.inference.MultiModalEmbedder;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@QuarkusTest
@TestProfile(HybridSearchProducerMatryoshkaTest.MatryoshkaProfile.class)
class HybridSearchProducerMatryoshkaTest {

    @Inject
    Instance<MultiModalEmbedder> multiModalEmbedderInstance;

    @Test
    void denseDimensionMatchesMatryoshkaConfig() {
        var embedder = multiModalEmbedderInstance.get();
        assertThat(embedder.denseDimension()).isEqualTo(512);
    }

    @Test
    void embeddingIsTruncatedToConfiguredDimension() {
        var embedder = multiModalEmbedderInstance.get();
        var embedding = embedder.embed("test text");
        assertThat(embedding.dense()).hasSize(512);
    }

    @Test
    void truncatedEmbeddingIsRenormalized() {
        var embedder = multiModalEmbedderInstance.get();
        var embedding = embedder.embed("test text");
        float[] dense = embedding.dense();
        double norm = 0;
        for (float v : dense) norm += v * v;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void sparseAndColbertPassedThrough() {
        var embedder = multiModalEmbedderInstance.get();
        var embedding = embedder.embed("test text");
        assertThat(embedding.sparse()).isNotNull();
        assertThat(embedding.colbert()).isNotNull();
    }

    public static class MatryoshkaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("casehub.inference.models.bge-m3.model-path", "stub"),
                    Map.entry("casehub.inference.models.bge-m3.tokenizer-path", "stub"),
                    Map.entry("casehub.inference.models.bge-m3.max-sequence-length", "768"),
                    Map.entry("casehub.inference.models.reranker.model-path", "stub"),
                    Map.entry("casehub.inference.models.reranker.tokenizer-path", "stub"),
                    Map.entry("casehub.inference.models.reranker.max-sequence-length", "512"),
                    Map.entry("casehub.rag.matryoshka.dimension", "512"),
                    Map.entry("quarkus.arc.exclude-types", "io.hortora.garden.inference.CollectionMigration,io.hortora.garden.mcp.GardenMcpTools,io.hortora.garden.index.GardenReindexService,io.hortora.garden.index.ReindexResource")
                                );}
    }
}
