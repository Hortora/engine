package io.hortora.garden.inference;

import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
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
    Instance<SparseEmbedder> sparseEmbedderInstance;

    @Inject
    Instance<CrossEncoderReranker> rerankerInstance;

    @Test
    void sparseEmbedderIsResolvableWhenConfigured() {
        assertThat(sparseEmbedderInstance.isResolvable()).isTrue();
        assertThat(sparseEmbedderInstance.get()).isNotNull();
    }

    @Test
    void rerankerIsResolvableWhenConfigured() {
        assertThat(rerankerInstance.isResolvable()).isTrue();
        assertThat(rerankerInstance.get()).isNotNull();
    }

    public static class WithModelsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "casehub.inference.models.splade.model-path", "stub",
                "casehub.inference.models.splade.tokenizer-path", "stub",
                "casehub.inference.models.reranker.model-path", "stub",
                "casehub.inference.models.reranker.tokenizer-path", "stub",
                "quarkus.arc.exclude-types", "io.hortora.garden.inference.CollectionMigration"
            );
        }
    }
}
