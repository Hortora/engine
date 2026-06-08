package io.hortora.garden.search;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    EmbeddingStore<TextSegment> embeddingStore;

    @GET
    public Response search(
            @QueryParam("q") String query,
            @QueryParam("domain") List<String> domains,
            @QueryParam("limit") Integer limit) {

        if (query == null || query.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"query parameter 'q' is required\"}")
                    .build();
        }

        int maxResults = limit != null && limit > 0 ? limit : 8;
        return Response.ok(doSearch(query, domains, maxResults)).build();
    }

    public List<SearchResult> searchFor(String query, List<String> domains, Integer limit) {
        int maxResults = limit != null && limit > 0 ? limit : 8;
        return doSearch(query, domains, maxResults);
    }

    private List<SearchResult> doSearch(String query, List<String> domains, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .filter(buildDomainFilter(domains))
                .build();

        return embeddingStore.search(request).matches().stream()
                .map(m -> new SearchResult(
                        m.embedded().metadata().getString("title"),
                        m.embedded().metadata().getString("domain"),
                        m.embedded().metadata().getString("type"),
                        parseCurationScore(m.embedded().metadata().getString("score")),
                        m.embedded().text(),
                        m.score()))
                .toList();
    }

    private static Filter buildDomainFilter(List<String> domains) {
        if (domains == null || domains.isEmpty()) return null;
        List<String> nonBlank = domains.stream().filter(d -> d != null && !d.isBlank()).toList();
        if (nonBlank.isEmpty()) return null;
        if (nonBlank.size() == 1) return new IsEqualTo("domain", nonBlank.get(0));
        return new IsIn("domain", nonBlank);
    }

    private static int parseCurationScore(String s) {
        try {
            return s != null ? Integer.parseInt(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
