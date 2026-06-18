package io.hortora.garden.inference;

import io.casehub.inference.splade.SparseEmbedder;
import io.casehub.inference.tasks.CrossEncoderReranker;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HybridSearchProducerAbsentTest {

    @Inject
    Instance<SparseEmbedder> sparseEmbedderInstance;

    @Inject
    Instance<CrossEncoderReranker> rerankerInstance;

    @Test
    void sparseEmbedderNotResolvableWithoutConfig() {
        assertThat(sparseEmbedderInstance.isResolvable()).isFalse();
    }

    @Test
    void rerankerNotResolvableWithoutConfig() {
        assertThat(rerankerInstance.isResolvable()).isFalse();
    }
}
