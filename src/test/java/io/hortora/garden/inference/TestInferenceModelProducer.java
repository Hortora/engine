package io.hortora.garden.inference;

import io.casehub.inference.InferenceModel;
import io.casehub.inference.inmem.InMemoryInferenceModel;
import io.casehub.inference.quarkus.Inference;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * Replaces the real {@code InferenceModelProducer} in tests.
 * <p>
 * Because {@code @Inference} has {@code @Nonbinding} on its {@code value()},
 * CDI considers all {@code @Inference} qualifiers equivalent. A single producer
 * method with {@code InjectionPoint} dispatches to the correct in-memory stub.
 * <p>
 * This class is annotated with {@code @Mock} so Quarkus Arc treats it as an
 * alternative that overrides the {@code @DefaultBean} real producer.
 */
@Mock
@ApplicationScoped
public class TestInferenceModelProducer {

    private static final int SPLADE_VOCAB_SIZE = 30522;

    @Produces
    @Dependent
    @Inference("")
    InferenceModel produce(InjectionPoint ip) {
        String name = extractName(ip);
        return switch (name) {
            case "splade" -> InMemoryInferenceModel.returning(new float[SPLADE_VOCAB_SIZE]);
            case "reranker" -> InMemoryInferenceModel.returning(0.5f);
            default -> throw new IllegalStateException(
                    "No test InferenceModel configured for name '" + name + "'");
        };
    }

    private static String extractName(InjectionPoint ip) {
        Inference inference = ip.getAnnotated().getAnnotation(Inference.class);
        if (inference == null) {
            throw new IllegalStateException(
                    "Injection point missing @Inference qualifier: " + ip);
        }
        return inference.value();
    }
}
