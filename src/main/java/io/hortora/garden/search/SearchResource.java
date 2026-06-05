package io.hortora.garden.search;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
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
            @QueryParam("domain") String domain,
            @QueryParam("limit") Integer limit) {

        if (query == null || query.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"query parameter 'q' is required\"}")
                    .build();
        }

        int maxResults = limit != null && limit > 0 ? limit : 8;

        Filter domainFilter = (domain != null && !domain.isBlank())
                ? new IsEqualTo("domain", domain)
                : null;

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .filter(domainFilter)
                .build();

        List<SearchResult> results = embeddingStore.search(request).matches().stream()
                .map(m -> new SearchResult(
                        m.embedded().metadata().getString("title"),
                        m.embedded().metadata().getString("domain"),
                        m.embedded().metadata().getString("type"),
                        parseScore(m.embedded().metadata().getString("score")),
                        m.embedded().text(),
                        m.score()))
                .toList();

        return Response.ok(results).build();
    }

    public List<SearchResult> searchFor(String query, String domain, Integer limit) {
        var response = search(query, domain, limit);
        if (response.getStatus() != 200) {
            return List.of();
        }
        //noinspection unchecked
        return (List<SearchResult>) response.getEntity();
    }

    private static int parseScore(String s) {
        try {
            return s != null ? Integer.parseInt(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
