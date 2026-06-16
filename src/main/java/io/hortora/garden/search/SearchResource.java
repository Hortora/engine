package io.hortora.garden.search;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.hortora.garden.config.QdrantConfig;
import io.hortora.garden.federation.ChainWalker;
import io.hortora.garden.federation.FederationConfig;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    QdrantClient qdrantClient;

    @Inject
    QdrantConfig qdrantConfig;

    @Inject
    FederationConfig federationConfig;

    @Inject
    ChainWalker chainWalker;

    @GET
    public Response search(
            @QueryParam("q") String query,
            @QueryParam("domain") List<String> domains,
            @QueryParam("limit") Integer limit,
            @HeaderParam("X-Federation-Visited") String visited) {

        if (query == null || query.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"query parameter 'q' is required\"}")
                    .build();
        }

        int maxResults = limit != null && limit > 0 ? limit : 8;
        return Response.ok(doSearch(query, domains, maxResults, visited)).build();
    }

    public List<SearchResult> searchFor(String query, List<String> domains, Integer limit) {
        int maxResults = limit != null && limit > 0 ? limit : 8;
        return doSearch(query, domains, maxResults, null);
    }

    List<SearchResult> doSearch(String query, List<String> domains, int maxResults, String visitedHeader) {
        Set<String> visited = parseVisited(visitedHeader);

        // Cycle detection: if we've already been visited in this chain, return empty
        if (visited.contains(federationConfig.gardenId())) {
            return List.of();
        }

        visited.add(federationConfig.gardenId());

        // Depth check: if visited set exceeds max-depth, return own results only (no walking)
        boolean depthExceeded = visited.size() > federationConfig.maxDepth();

        List<SearchResult> ownResults = searchOwnQdrant(query, domains, maxResults);

        if (depthExceeded) {
            return ownResults;
        }

        return chainWalker.walk(query, domains, maxResults, ownResults, visited);
    }

    private List<SearchResult> searchOwnQdrant(String query, List<String> domains, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        QueryPoints.Builder queryBuilder = QueryPoints.newBuilder()
                .setCollectionName(qdrantConfig.collection())
                .setQuery(QueryFactory.nearest(queryEmbedding.vectorAsList()))
                .setUsing("dense")
                .setLimit(maxResults)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));

        io.qdrant.client.grpc.Common.Filter domainFilter = buildQdrantDomainFilter(domains);
        if (domainFilter != null) {
            queryBuilder.setFilter(domainFilter);
        }

        List<ScoredPoint> scored;
        try {
            scored = qdrantClient.queryAsync(queryBuilder.build()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during search", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Search failed", e.getCause());
        }

        return scored.stream()
                .map(point -> {
                    var payload = point.getPayloadMap();
                    return new SearchResult(
                            extractString(payload, "sourceDocumentId"),
                            extractString(payload, "title"),
                            extractString(payload, "domain"),
                            extractString(payload, "type"),
                            parseScore(extractString(payload, "score")),
                            extractString(payload, "content"),
                            point.getScore(),
                            federationConfig.gardenId(),
                            federationConfig.idPrefix());
                })
                .toList();
    }

    private static Set<String> parseVisited(String header) {
        if (header == null || header.isBlank()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(Arrays.asList(header.split(",")));
    }

    private static io.qdrant.client.grpc.Common.Filter buildQdrantDomainFilter(List<String> domains) {
        if (domains == null || domains.isEmpty()) return null;
        List<String> nonBlank = domains.stream().filter(d -> d != null && !d.isBlank()).toList();
        if (nonBlank.isEmpty()) return null;
        if (nonBlank.size() == 1) {
            return io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword("domain", nonBlank.get(0)))
                    .build();
        }
        var filterBuilder = io.qdrant.client.grpc.Common.Filter.newBuilder();
        for (String domain : nonBlank) {
            filterBuilder.addShould(ConditionFactory.matchKeyword("domain", domain));
        }
        return filterBuilder.build();
    }

    private static String extractString(Map<String, JsonWithInt.Value> payload, String key) {
        var v = payload.get(key);
        return v != null && v.hasStringValue() ? v.getStringValue() : "";
    }

    private static int parseScore(String s) {
        try {
            return s != null && !s.isEmpty() ? Integer.parseInt(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
