package io.hortora.garden.search;

import io.casehub.neocortex.rag.CaseRetriever;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.PayloadFilter;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.RetrievedChunk;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.federation.ChainWalker;
import io.hortora.garden.federation.FederationConfig;
import io.hortora.garden.index.QueryAugmentingExtractor;
import io.quarkus.logging.Log;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SearchResource {

    static final int DEFAULT_LIMIT = 16;
    static final int MAX_LIMIT = 50;
    static final int OVERFETCH_MULTIPLIER = 2;
    static final int OVERFETCH_MULTIPLIER_KEYWORDS = 3;


    @Inject CaseRetriever caseRetriever;
    @Inject GardenConfig gardenConfig;
    @Inject FederationConfig federationConfig;
    @Inject ChainWalker chainWalker;
    @Inject SearchConfig searchConfig;
    @Inject SearchScoringConfig scoringConfig;
    @Inject SearchProfileStore profileStore;

    @GET
    public List<SearchResult> search(
            @QueryParam("q") String query,
            @QueryParam("domain") List<String> domains,
            @QueryParam("type") String type,
            @QueryParam("tags") String tags,
            @QueryParam("limit") Integer limit,
            @HeaderParam("X-Federation-Visited") String visited) {

        if (query == null || query.isBlank()) {
            throw new WebApplicationException("query parameter 'q' is required", Response.Status.BAD_REQUEST);
        }

        int maxResults = resolveLimit(limit);
        return doSearch(query, null, domains, type, tags, maxResults, visited);
    }

    public List<SearchResult> searchFor(String query, List<String> domains, String type, String tags, Integer limit) {
        int maxResults = resolveLimit(limit);
        return doSearch(query, null, domains, type, tags, maxResults, null);
    }

    public List<SearchResult> fetchByDocumentIds(String query, List<String> documentIds) {
        if (documentIds.isEmpty()) {return List.of();}
        CorpusRef     corpusRef = new CorpusRef("hortora", gardenConfig.id());
        PayloadFilter filter    = PayloadFilter.in("sourceDocumentId", documentIds);

        List<RetrievedChunk> chunks = caseRetriever.retrieve(
                RetrievalQuery.of(query), corpusRef, documentIds.size(), filter);

        List<SearchResult> results = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            String seeAlsoRaw = chunk.metadata().getOrDefault("see_also_ids", "");
            List<String> seeAlsoIds = seeAlsoRaw.isEmpty() ? List.of()
                                                           : List.of(seeAlsoRaw.split("[|]"));
            results.add(new SearchResult(
                    chunk.sourceDocumentId(),
                    chunk.metadata().getOrDefault("title", ""),
                    chunk.metadata().getOrDefault("domain", ""),
                    chunk.metadata().getOrDefault("type", ""),
                    parseScore(chunk.metadata().get("score")),
                    QueryAugmentingExtractor.stripQueries(chunk.content()),
                    chunk.relevanceScore(),
                    parseDouble(chunk.metadata().get("_crossEncoderScore")),
                    federationConfig.gardenId(),
                    federationConfig.idPrefix(),
                    seeAlsoIds,
                    chunk.metadata()));
        }
        return results;
    }


    public AdaptiveResult searchAdaptive(String query, String keywords, List<String> domains, String type, String tags, Integer limit) {
        return searchAdaptive(query, keywords, domains, type, tags, limit, null, null);
    }

    public AdaptiveResult searchAdaptive(String query, String keywords, List<String> domains, String type, String tags, Integer limit, String profile, String stack) {
        int                requestedLimit = resolveLimit(limit);
        boolean            hasKeywords    = keywords != null && !keywords.isBlank();
        int                multiplier     = hasKeywords ? OVERFETCH_MULTIPLIER_KEYWORDS : OVERFETCH_MULTIPLIER;
        int                fetchLimit     = Math.min(requestedLimit * multiplier, MAX_LIMIT);
        List<SearchResult> candidates     = doSearch(query, keywords, domains, type, tags, fetchLimit, null);
        double             boostWeight    = searchConfig.scoreBoostWeight();

        Map<String, String> bom = resolveBom(profile, stack);
        String queryText = keywords != null ? query + " " + keywords : query;
        candidates = applyScoring(candidates, queryText, bom);

        List<SearchResult> sorted = candidates.stream()
                                              .sorted(Comparator.comparing(
                                                                        (SearchResult r) -> r.crossEncoderScore() != null ? 0 : 1)
                                                                .thenComparing(r -> -boostedScore(r, boostWeight)))
                                              .toList();

        double gapThreshold = hasKeywords ? Double.MAX_VALUE : searchConfig.gapThreshold();
        double scoreFloor   = hasKeywords ? Double.NEGATIVE_INFINITY : searchConfig.scoreFloor();
        return adaptiveFilter(sorted, requestedLimit,
                              scoreFloor, gapThreshold,
                              searchConfig.minResults(), boostWeight);
    }

    @GET
    @Path("/adaptive")
    public AdaptiveResult adaptiveSearchEndpoint(
            @QueryParam("q") String query,
            @QueryParam("keywords") String keywords,
            @QueryParam("domain") List<String> domains,
            @QueryParam("type") String type,
            @QueryParam("tags") String tags,
            @QueryParam("limit") Integer limit) {
        if (query == null || query.isBlank()) {
            throw new WebApplicationException("query parameter 'q' is required", Response.Status.BAD_REQUEST);
        }
        String expandedKeywords = keywords != null && !keywords.isBlank()
                                  ? keywords.replace("|", " ") : null;
        try {
            return searchAdaptive(query, expandedKeywords, domains, type, tags, limit);
        } catch (Exception e) {
            Log.warn("Adaptive search failed — Qdrant may be unavailable", e);
            return new AdaptiveResult(List.of(), resolveLimit(limit), 0, false, false, 0, false);
        }
    }


    private Map<String, String> resolveBom(String profile, String stack) {
        if (stack != null && !stack.isBlank()) {
            return SearchProfileStore.parseStack(stack);
        }
        if (profile != null && !profile.isBlank()) {
            return profileStore.get(profile).orElse(Map.of());
        }
        return Map.of();
    }

    List<SearchResult> applyScoring(List<SearchResult> results, String queryText, Map<String, String> bom) {
        TemporalDecayScorer temporalScorer = new TemporalDecayScorer();
        VersionScorer       versionScorer  = new VersionScorer();
        VersionScorer.Config vConfig = new VersionScorer.Config(
                scoringConfig.versionDecayFactor(), scoringConfig.versionDecayFloor(),
                scoringConfig.versionTopicWeightDefault());

        boolean applyTemporal = scoringConfig.temporalDecayEnabled();
        boolean applyVersion  = scoringConfig.versionScoringEnabled() && !bom.isEmpty();

        if (!applyTemporal && !applyVersion) {return results;}

        return results.stream().map(r -> {
            if (r.metadata() == null) {return r;}
            double multiplier = 1.0;

            if (applyTemporal) {
                multiplier *= temporalScorer.score(
                        r.metadata().get("submitted"),
                        r.metadata().get("decay_tier"));
            }

            if (applyVersion) {
                multiplier *= versionScorer.score(
                        r.metadata().get("verified_on"),
                        bom, queryText, vConfig);
            }

            if (multiplier >= 0.999) {return r;}

            Double adjustedCe = r.crossEncoderScore() != null ? r.crossEncoderScore() * multiplier : null;
            return r.withAdjustedScores(r.relevance() * multiplier, adjustedCe);
        }).toList();
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    List<SearchResult> doSearch(String query, String keywords, List<String> domains, String type, String tags, int maxResults, String visitedHeader) {
        Set<String> visited = parseVisited(visitedHeader);

        if (visited.contains(federationConfig.gardenId())) {
            return List.of();
        }

        visited.add(federationConfig.gardenId());

        boolean depthExceeded = visited.size() > federationConfig.maxDepth();

        List<SearchResult> ownResults = searchLocal(query, keywords, domains, type, tags, maxResults);

        if (depthExceeded) {
            return ownResults;
        }

        String federationQuery = keywords != null ? query + " " + keywords : query;
        return chainWalker.walk(federationQuery, domains, type, tags, maxResults, ownResults, visited);
    }

    private List<SearchResult> searchLocal(String query, String keywords, List<String> domains, String type, String tags, int maxResults) {
        CorpusRef     corpusRef = new CorpusRef("hortora", gardenConfig.id());
        PayloadFilter filter    = buildFilter(domains, type, tags);

        RetrievalQuery retrievalQuery;
        if (keywords != null && !keywords.isBlank()) {
            // text = keywords → BM25 focuses on exact keyword matching
            // expandedText = NL query + keywords → dense/sparse get full semantic context
            retrievalQuery = new RetrievalQuery(keywords, query + " " + keywords, java.util.Map.of());
        } else {
            retrievalQuery = RetrievalQuery.of(query);
        }

        List<RetrievedChunk> chunks = caseRetriever.retrieve(retrievalQuery, corpusRef, maxResults, filter);

        List<SearchResult> results = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            String seeAlsoRaw = chunk.metadata().getOrDefault("see_also_ids", "");
            List<String> seeAlsoIds = seeAlsoRaw.isEmpty() ? List.of()
                    : List.of(seeAlsoRaw.split("[|]"));
            results.add(new SearchResult(
                    chunk.sourceDocumentId(),
                    chunk.metadata().getOrDefault("title", ""),
                    chunk.metadata().getOrDefault("domain", ""),
                    chunk.metadata().getOrDefault("type", ""),
                    parseScore(chunk.metadata().get("score")),
                    QueryAugmentingExtractor.stripQueries(chunk.content()),
                    chunk.relevanceScore(),
                    parseDouble(chunk.metadata().get("_crossEncoderScore")),
                    federationConfig.gardenId(),
                    federationConfig.idPrefix(),
                    seeAlsoIds,
                    chunk.metadata()));
        }
        return results;
    }


    public static List<SearchResult> expandWithAdjacent(
            List<SearchResult> results,
            java.util.function.Function<List<String>, List<SearchResult>> adjacentResolver) {

        Set<String> presentIds = new java.util.HashSet<>();
        for (SearchResult r : results) {
            String id = r.id();
            if (id.contains("/")) {id = id.substring(id.lastIndexOf('/') + 1);}
            if (id.endsWith(".md")) {id = id.substring(0, id.length() - 3);}
            presentIds.add(id);
        }

        Set<String> missingIds = new java.util.LinkedHashSet<>();
        for (SearchResult r : results) {
            for (String seeAlsoId : r.seeAlsoIds()) {
                if (!presentIds.contains(seeAlsoId)) {
                    missingIds.add(seeAlsoId);
                }
            }
        }

        if (missingIds.isEmpty()) {
            return results;
        }

        List<SearchResult> adjacentResults = adjacentResolver.apply(List.copyOf(missingIds));
        if (adjacentResults.isEmpty()) {
            return results;
        }

        List<SearchResult> combined = new ArrayList<>(results);
        combined.addAll(adjacentResults);
        return combined;
    }

    static AdaptiveResult adaptiveFilter(List<SearchResult> candidates,
                                         int requestedLimit,
                                         double scoreFloor,
                                         double gapThreshold,
                                         int minResults, double boostWeight) {
        if (candidates.isEmpty()) {
            return new AdaptiveResult(List.of(), requestedLimit, 0, false, false, 0);
        }

        boolean ceMode = candidates.stream().anyMatch(r -> r.crossEncoderScore() != null);

        List<SearchResult> survivors = new ArrayList<>();
        int floorFiltered = 0;
        for (SearchResult r : candidates) {
            double score = boostedScore(r, boostWeight);
            if (score >= scoreFloor) {
                survivors.add(r);
            } else {
                floorFiltered++;
            }
        }

        int availableAboveFloor = survivors.size();

        if (survivors.isEmpty()) {
            return new AdaptiveResult(List.of(), requestedLimit, 0, false,
                    requestedLimit > 0, floorFiltered);
        }

        int cutoff;
        boolean gapFound = false;
        if (ceMode) {
            int gapCutoff = findCeGapCutoff(survivors, gapThreshold, minResults);
            if (gapCutoff >= 0) {
                cutoff = gapCutoff;
                gapFound = true;
            } else {
                cutoff = Math.min(survivors.size(), requestedLimit);
            }
        } else {
            cutoff = findDenseOnlyCutoff(survivors, requestedLimit);
        }

        boolean extended = cutoff > requestedLimit;
        int effectiveCount = Math.min(cutoff, survivors.size());
        boolean trimmed = effectiveCount < requestedLimit && (floorFiltered > 0 || gapFound);

        return new AdaptiveResult(
                survivors.subList(0, effectiveCount),
                requestedLimit,
                availableAboveFloor,
                extended,
                trimmed,
                floorFiltered);
    }

    private static int findCeGapCutoff(List<SearchResult> survivors,
                                        double gapThreshold, int minResults) {
        for (int i = 0; i < survivors.size() - 1; i++) {
            Double currentCe = survivors.get(i).crossEncoderScore();
            Double nextCe = survivors.get(i + 1).crossEncoderScore();
            if (currentCe != null && nextCe != null) {
                double gap = currentCe - nextCe;
                if (gap >= gapThreshold) {
                    return Math.max(i + 1, minResults);
                }
            } else if (currentCe != null && nextCe == null) {
                return Math.max(i + 1, minResults);
            }
        }
        return -1;
    }

    private static int findDenseOnlyCutoff(List<SearchResult> survivors,
                                            int requestedLimit) {
        if (survivors.size() <= requestedLimit) {
            return survivors.size();
        }
        int cutoff = requestedLimit;
        for (int i = requestedLimit - 1; i < survivors.size() - 1; i++) {
            double gap = survivors.get(i).relevance() - survivors.get(i + 1).relevance();
            if (gap < 0.05) {
                cutoff = i + 2;
            } else {
                break;
            }
        }
        return cutoff;
    }

    private static double primaryScore(SearchResult r) {
        return r.crossEncoderScore() != null ? r.crossEncoderScore() : r.relevance();
    }

    static double boostedScore(SearchResult r, double boostWeight) {
        return primaryScore(r) + (r.score() * boostWeight);
    }

    static PayloadFilter buildFilter(List<String> domains, String type, String tags) {
        List<PayloadFilter> filters = new ArrayList<>();

        if (domains != null && !domains.isEmpty()) {
            List<String> nonBlank = domains.stream().filter(d -> d != null && !d.isBlank()).toList();
            if (!nonBlank.isEmpty()) {
                filters.add(nonBlank.size() == 1
                    ? PayloadFilter.eq("domain", nonBlank.getFirst())
                    : PayloadFilter.in("domain", nonBlank));
            }
        }
        if (type != null && !type.isBlank()) {
            filters.add(PayloadFilter.eq("type", type));
        }
        if (tags != null && !tags.isBlank()) {
            List<String> tagList = Arrays.stream(tags.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (!tagList.isEmpty()) {
                filters.add(PayloadFilter.in("tags", tagList));
            }
        }

        if (filters.isEmpty()) return null;
        if (filters.size() == 1) return filters.getFirst();
        return new PayloadFilter.And(filters);
    }

    static PayloadFilter buildDomainFilter(List<String> domains) {
        return buildFilter(domains, null, null);
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

    private static Double parseDouble(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
