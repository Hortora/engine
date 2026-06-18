package io.hortora.garden.test;

import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.EmbeddingIngestor;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mock
@ApplicationScoped
public class TestEmbeddingIngestor implements EmbeddingIngestor {

    private final Map<CorpusRef, List<ChunkInput>> data = new ConcurrentHashMap<>();

    @Override
    public void ingest(CorpusRef corpus, List<ChunkInput> chunks) {
        data.computeIfAbsent(corpus, k -> new ArrayList<>()).addAll(chunks);
    }

    @Override
    public void deleteDocument(CorpusRef corpus, String sourceDocumentId) {
        data.computeIfPresent(corpus, (k, v) -> {
            v.removeIf(c -> c.sourceDocumentId().equals(sourceDocumentId));
            return v.isEmpty() ? null : v;
        });
    }

    @Override
    public void deleteCorpus(CorpusRef corpus) {
        data.remove(corpus);
    }

    @Override
    public List<String> listDocuments(CorpusRef corpus) {
        var chunks = data.get(corpus);
        if (chunks == null) return List.of();
        return chunks.stream().map(ChunkInput::sourceDocumentId).distinct().toList();
    }
}
