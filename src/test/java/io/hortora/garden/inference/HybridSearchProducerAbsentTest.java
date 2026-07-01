package io.hortora.garden.inference;

import io.casehub.inference.MultiModalEmbedder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HybridSearchProducerAbsentTest {

    @Inject
    Instance<MultiModalEmbedder> multiModalEmbedderInstance;

    @Test
    void multiModalEmbedderNotResolvableWithoutConfig() {
        assertThat(multiModalEmbedderInstance.isResolvable()).isFalse();
    }
}
