package io.hortora.garden.test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Random;

/**
 * Deterministic test double for EmbeddingModel — no Ollama required.
 * Returns stable 768-dim vectors seeded from text hashCode so similar texts
 * get similar (not identical) vectors.
 */
@Mock
@ApplicationScoped
public class TestEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 768;

    @Override
    public Response<Embedding> embed(String text) {
        return Response.from(deterministicEmbedding(text));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return embed(segment.text());
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> embeddings = segments.stream()
                .map(s -> deterministicEmbedding(s.text()))
                .toList();
        return Response.from(embeddings);
    }

    private static Embedding deterministicEmbedding(String text) {
        var rng = new Random(text.hashCode());
        float[] vector = new float[DIMENSIONS];
        float norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = rng.nextFloat() * 2 - 1;
            norm += vector[i] * vector[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] /= norm;
        }
        return Embedding.from(vector);
    }
}
