package io.hortora.garden.inference;

import io.casehub.neocortex.inference.InferenceModel;
import io.casehub.neocortex.inference.inmem.InMemoryInferenceModel;
import io.casehub.neocortex.inference.quarkus.Inference;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.util.Map;
import java.util.Random;

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

    private static final int BGE_M3_DENSE_DIM = 1024;
    private static final int BGE_M3_SPARSE_VOCAB = 250002;

    @Produces
    @Dependent
    @Inference("")
    InferenceModel produce(InjectionPoint ip) {
        String name = extractName(ip);
        return switch (name) {
            case "bge-m3" -> InMemoryInferenceModel.returningMulti(Map.of(
                    "dense", new float[][]{ deterministicDense(BGE_M3_DENSE_DIM) },
                    "sparse", new float[][]{ deterministicSparse(BGE_M3_SPARSE_VOCAB) },
                    "colbert", new float[][]{ {0.5f, 0.5f}, {0.3f, 0.7f} }
            ));
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

    private static float[] deterministicDense(int dim) {
        var rng = new Random(42);
        float[] v = new float[dim];
        float norm = 0;
        for (int i = 0; i < dim; i++) {
            v[i] = rng.nextFloat() * 2 - 1;
            norm += v[i] * v[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dim; i++) {
            v[i] /= norm;
        }
        return v;
    }

    private static float[] deterministicSparse(int vocabSize) {
        float[] v = new float[vocabSize];
        // A few non-zero values at deterministic positions
        v[100] = 1.5f;
        v[500] = 0.8f;
        v[1000] = 2.1f;
        v[5000] = 0.3f;
        return v;
    }
}
