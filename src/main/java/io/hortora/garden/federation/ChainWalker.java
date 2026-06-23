package io.hortora.garden.federation;

import io.hortora.garden.search.SearchResult;
import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChainWalker {

    static final int TIER_OWN = 0;
    static final int TIER_PEER = Integer.MAX_VALUE;

    @Inject
    FederationConfig config;

    @Inject
    org.eclipse.microprofile.context.ManagedExecutor managedExecutor;

    private ExecutorService executor;
    private final Map<String, RemoteGardenClient> upstreamClients = new LinkedHashMap<>();
    private final Map<String, RemoteGardenClient> peerClients = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        executor = managedExecutor;
        for (var ref : config.upstream()) {
            upstreamClients.put(ref.url(), buildClient(ref.url()));
            Log.infof("Federation upstream client: %s (%s)", ref.url(), ref.idPrefix());
        }
        for (var ref : config.peers()) {
            peerClients.put(ref.url(), buildClient(ref.url()));
            Log.infof("Federation peer client: %s (%s)", ref.url(), ref.idPrefix());
        }
    }

    public List<SearchResult> walk(String query, List<String> domains, int limit,
                                    List<SearchResult> ownResults, Set<String> visited) {
        if (!config.hasFederation()) {
            return ownResults;
        }

        List<TieredResult> all = new ArrayList<>();
        ownResults.forEach(r -> all.add(new TieredResult(r, TIER_OWN)));

        boolean sufficient = isSufficient(ownResults, limit);

        // Upstream walk — sequential, in declared order
        if (!sufficient || hasAlwaysUpstream()) {
            String visitedHeader = String.join(",", visited);
            int upstreamIndex = 0;
            for (var ref : config.upstream()) {
                boolean shouldQuery = "always".equals(ref.searchOrder()) || !sufficient;
                if (!shouldQuery) {
                    upstreamIndex++;
                    continue;
                }

                int tier = upstreamIndex + 1; // parent=1, grandparent=2, etc.
                try {
                    RemoteGardenClient client = upstreamClients.get(ref.url());
                    List<SearchResult> results = client.search(query, domains, limit, visitedHeader);
                    results.forEach(r -> all.add(new TieredResult(r, tier)));

                    if (!sufficient && isSufficientCombined(all, limit)) {
                        sufficient = true;
                        break; // short-circuit
                    }
                } catch (Exception e) {
                    Log.warnf("Federation upstream %s failed: %s", ref.url(), e.getMessage());
                }
                upstreamIndex++;
            }
        }

        // Peer fan-out — parallel, only if still insufficient
        if (!isSufficientCombined(all, limit) && config.hasPeers()) {
            String visitedHeader = String.join(",", visited);
            List<Callable<List<SearchResult>>> peerCalls = config.peers().stream()
                    .map(ref -> (Callable<List<SearchResult>>) () -> {
                        RemoteGardenClient client = peerClients.get(ref.url());
                        return client.search(query, domains, limit, visitedHeader);
                    })
                    .toList();

            try {
                List<Future<List<SearchResult>>> futures = executor.invokeAll(
                        peerCalls, config.federationTimeoutSeconds(), TimeUnit.SECONDS);
                for (var future : futures) {
                    try {
                        if (future.isDone() && !future.isCancelled()) {
                            future.get().forEach(r -> all.add(new TieredResult(r, TIER_PEER)));
                        }
                    } catch (ExecutionException e) {
                        Log.warnf("Federation peer failed: %s", e.getCause().getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.warn("Federation peer fan-out interrupted");
            }
        }

        return merge(all, limit);
    }

    void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    void setUpstreamClient(String url, RemoteGardenClient client) {
        upstreamClients.put(url, client);
    }

    void setPeerClient(String url, RemoteGardenClient client) {
        peerClients.put(url, client);
    }

    private boolean isSufficient(List<SearchResult> results, int limit) {
        long aboveThreshold = results.stream()
                .filter(r -> r.relevance() >= config.relevanceThreshold())
                .count();
        return aboveThreshold >= limit;
    }

    private boolean isSufficientCombined(List<TieredResult> results, int limit) {
        long aboveThreshold = results.stream()
                .filter(r -> r.result.relevance() >= config.relevanceThreshold())
                .count();
        return aboveThreshold >= limit;
    }

    private boolean hasAlwaysUpstream() {
        return config.upstream().stream().anyMatch(ref -> "always".equals(ref.searchOrder()));
    }

    static List<SearchResult> merge(List<TieredResult> all, int limit) {
        Set<DedupKey> seen = new LinkedHashSet<>();
        List<TieredResult> deduped = new ArrayList<>();
        for (var tr : all) {
            if (seen.add(new DedupKey(tr.result.id(), tr.result.source()))) {
                deduped.add(tr);
            }
        }

        // Group by tier, sort by relevance within tier, concatenate
        return deduped.stream()
                .collect(Collectors.groupingBy(tr -> tr.tier))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream()
                        .sorted(Comparator.comparingDouble((TieredResult tr) -> tr.result.relevance()).reversed())
                        .map(tr -> tr.result))
                .limit(limit)
                .toList();
    }

    private RemoteGardenClient buildClient(String url) {
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(url))
                .readTimeout(config.federationTimeoutSeconds(), TimeUnit.SECONDS)
                .build(RemoteGardenClient.class);
    }

    record TieredResult(SearchResult result, int tier) {}
    record DedupKey(String id, String source) {}
}
