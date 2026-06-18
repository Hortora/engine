package io.hortora.garden.test;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.RetrievedChunk;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Mock
@ApplicationScoped
public class TestCaseRetriever implements CaseRetriever {

    private final List<RetrievedChunk> store = new CopyOnWriteArrayList<>();

    public TestCaseRetriever() {
        store.add(new RetrievedChunk(
                "Hibernate lazy loading fails outside transaction boundary.\n\nLazyInitializationException is thrown.",
                "jvm/ge-test-hibernate-lazy.md",
                0.92,
                Map.of("title", "Hibernate lazy loading fails outside transaction",
                        "domain", "jvm", "type", "gotcha", "score", "8")));
        store.add(new RetrievedChunk(
                "Git stash metadata is lost when applying across branches.",
                "tools/ge-test-git-stash.md",
                0.85,
                Map.of("title", "Git stash metadata lost across branches",
                        "domain", "tools", "type", "gotcha", "score", "6")));
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter) {
        List<RetrievedChunk> results = new ArrayList<>();
        for (RetrievedChunk chunk : store) {
            if (filter != null && !matches(chunk.metadata(), filter)) {
                continue;
            }
            results.add(chunk);
            if (results.size() >= maxResults) break;
        }
        return Collections.unmodifiableList(results);
    }

    private static boolean matches(Map<String, String> metadata, PayloadFilter filter) {
        return switch (filter) {
            case PayloadFilter.Eq eq -> eq.value().equals(metadata.get(eq.field()));
            case PayloadFilter.In in -> metadata.containsKey(in.field()) && in.values().contains(metadata.get(in.field()));
            case PayloadFilter.Not not -> !matches(metadata, not.inner());
            case PayloadFilter.And and -> and.filters().stream().allMatch(f -> matches(metadata, f));
            case PayloadFilter.Or or -> or.filters().stream().anyMatch(f -> matches(metadata, f));
        };
    }
}
