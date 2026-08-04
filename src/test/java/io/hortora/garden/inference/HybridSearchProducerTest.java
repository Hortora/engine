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

@QuarkusTest
@TestProfile(HybridSearchProducerTest.WithModelsProfile.class)
class HybridSearchProducerTest {

    @Inject
    Instance<MultiModalEmbedder> multiModalEmbedderInstance;

    @Test
    void multiModalEmbedderIsResolvableWhenConfigured() {
        assertThat(multiModalEmbedderInstance.isResolvable()).isTrue();
        assertThat(multiModalEmbedderInstance.get()).isNotNull();
    }

    @Test
    void multiModalEmbedderProducesDenseEmbedding() {
        var embedder = multiModalEmbedderInstance.get();
        var embedding = embedder.embed("test text");
        assertThat(embedding.dense()).hasSize(embedder.denseDimension());
    }

    @Test
    void multiModalEmbedderProducesSparseEmbedding() {
        var embedder = multiModalEmbedderInstance.get();
        var embedding = embedder.embed("test text");
        assertThat(embedding.sparse()).isNotNull();
    }

    public static class WithModelsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("casehub.inference.models.bge-m3.model-path", "stub"),
                    Map.entry("casehub.inference.models.bge-m3.tokenizer-path", "stub"),
                    Map.entry("casehub.inference.models.bge-m3.max-sequence-length", "768"),
                    Map.entry("casehub.inference.models.reranker.model-path", "stub"),
                    Map.entry("casehub.inference.models.reranker.tokenizer-path", "stub"),
                    Map.entry("casehub.inference.models.reranker.max-sequence-length", "512"),
                    Map.entry("quarkus.arc.exclude-types", "io.hortora.garden.inference.CollectionMigration,io.hortora.garden.mcp.GardenMcpTools,io.hortora.garden.index.GardenReindexService,io.hortora.garden.index.ReindexResource")
                                );}
    }
}
