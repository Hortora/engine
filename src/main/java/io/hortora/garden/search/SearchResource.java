package io.hortora.garden.search;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.RetrievalQuery;
import io.casehub.rag.RetrievedChunk;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.federation.ChainWalker;
import io.hortora.garden.federation.FederationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SearchResource {

    static final int DEFAULT_LIMIT = 8;
    static final int MAX_LIMIT = 50;

    @Inject CaseRetriever caseRetriever;
    @Inject GardenConfig gardenConfig;
    @Inject FederationConfig federationConfig;
    @Inject ChainWalker chainWalker;

    @GET
    public List<SearchResult> search(
            @QueryParam("q") String query,
            @QueryParam("domain") List<String> domains,
            @QueryParam("limit") Integer limit,
            @HeaderParam("X-Federation-Visited") String visited) {

        if (query == null || query.isBlank()) {
            throw new WebApplicationException("query parameter 'q' is required", Response.Status.BAD_REQUEST);
        }

        int maxResults = resolveLimit(limit);
        return doSearch(query, domains, maxResults, visited);
    }

    public List<SearchResult> searchFor(String query, List<String> domains, Integer limit) {
        int maxResults = resolveLimit(limit);
        return doSearch(query, domains, maxResults, null);
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    List<SearchResult> doSearch(String query, List<String> domains, int maxResults, String visitedHeader) {
        Set<String> visited = parseVisited(visitedHeader);

        if (visited.contains(federationConfig.gardenId())) {
            return List.of();
        }

        visited.add(federationConfig.gardenId());

        boolean depthExceeded = visited.size() > federationConfig.maxDepth();

        List<SearchResult> ownResults = searchLocal(query, domains, maxResults);

        if (depthExceeded) {
            return ownResults;
        }

        return chainWalker.walk(query, domains, maxResults, ownResults, visited);
    }

    private List<SearchResult> searchLocal(String query, List<String> domains, int maxResults) {
        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        PayloadFilter filter = buildDomainFilter(domains);

        List<RetrievedChunk> chunks = caseRetriever.retrieve(RetrievalQuery.of(query), corpusRef, maxResults, filter);

        List<SearchResult> results = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            results.add(new SearchResult(
                    chunk.sourceDocumentId(),
                    chunk.metadata().getOrDefault("title", ""),
                    chunk.metadata().getOrDefault("domain", ""),
                    chunk.metadata().getOrDefault("type", ""),
                    parseScore(chunk.metadata().get("score")),
                    chunk.content(),
                    chunk.relevanceScore(),
                    federationConfig.gardenId(),
                    federationConfig.idPrefix()));
        }
        return results;
    }

    static PayloadFilter buildDomainFilter(List<String> domains) {
        if (domains == null || domains.isEmpty()) return null;
        List<String> nonBlank = domains.stream().filter(d -> d != null && !d.isBlank()).toList();
        if (nonBlank.isEmpty()) return null;
        if (nonBlank.size() == 1) return PayloadFilter.eq("domain", nonBlank.getFirst());
        return PayloadFilter.in("domain", nonBlank);
    }

    private static Set<String> parseVisited(String header) {
        if (header == null || header.isBlank()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(
                Arrays.stream(header.split(",")).map(String::trim).toList());
    }

    private static int parseScore(String s) {
        try {
            return s != null && !s.isEmpty() ? Integer.parseInt(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
