# Chronological Index — Cross-Store Temporal Aggregation

**Issue:** casehubio/neocortex#237
**Date:** 2026-08-31
**Status:** Design

## Problem

The system has four temporal data sources — MindMap nodes (future events via `validFrom`, recent changes via `updatedAt`), text memories (`createdAt`), experience events (`timestamp`), and CBR cases (`storedAt`) — but no unified way to answer "what happened recently?" or "what's coming up?" across them.

`CuriositySignalGenerator` in casehub-blocks currently iterates all nodes in all subgraphs checking `validFrom` — an O(n) full-graph scan. With temporal query predicates now on MindMapQuery (#236), `MemoryQuery.since`, and `CbrQuery.notBefore`, each store can answer time-range queries efficiently. What's missing is the aggregation layer that queries them all and merges the results into a single chronological timeline.

## Design

### Module: `cognitive-index`

New module in the build. This is the cross-store cognitive query tier — above the individual store SPIs, below application-level logic.

| Property | Value |
|----------|-------|
| artifactId | `casehub-neocortex-cognitive-index` |
| Root package | `io.casehub.neocortex.cognitive.index` |
| Dependencies | `cognitive-api`, `mindmap-api`, `memory-api` (all compile) |
| CDI | Yes — `@ApplicationScoped` bean |

Growth path: `CognitiveProfile` (#243) and `TemporalFocus` ranker (#244) land in this module.

### Design Intent — Derived View, Not a Store

TemporalIndex is a **stateless query aggregator** — a derived read-only view over data that is already persisted in the source stores (MindMapStore, CaseMemoryStore, CbrCaseMemoryStore). It does not own data, does not persist state, and does not need to survive process restarts. Every call re-queries the underlying stores and merges the results.

This is the same architectural category as `MindMapAnalyzer` and `RetrievalAnalyzer` — pure computation over store data, not a store itself. The SPI + inmem + SQLite pattern used by primary data stores does not apply here.

All public classes must carry javadoc explaining this intent to prevent future contributors from adding persistence or treating the index as a source of truth.

### TemporalEntry

A single temporal event from any store:

```java
package io.casehub.neocortex.cognitive.index;

import io.casehub.neocortex.cognitive.Confidence;
import java.time.Instant;
import java.util.Objects;

/**
 * A single temporal event from any cognitive store. This is a derived view —
 * the source data lives in the originating store (MindMap, Memory, or CBR).
 * TemporalEntry carries the full source object via {@link TemporalSource}
 * for zero-information-loss access.
 */
public record TemporalEntry(
    Instant timestamp,
    TemporalSource source,
    String tenantId,
    Confidence confidence
) implements Comparable<TemporalEntry> {

    public TemporalEntry {
        Objects.requireNonNull(timestamp, "timestamp required");
        Objects.requireNonNull(source, "source required");
        Objects.requireNonNull(tenantId, "tenantId required");
    }

    @Override
    public int compareTo(TemporalEntry other) {
        return this.timestamp.compareTo(other.timestamp);
    }
}
```

Natural ordering is chronological (oldest first). Confidence is nullable — Memory and MindMapNode carry `Confidence` (nullable per D7), CBR carries confidence on the underlying `CbrCase`. Null means "no confidence assessment" — the entry is still valid, just unscored.

### TemporalSource

Sealed interface preserving full store objects. Pattern matching gives callers type-safe access without information loss:

```java
package io.casehub.neocortex.cognitive.index;

import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.mindmap.MindMapNode;

/**
 * The originating store and full source object for a {@link TemporalEntry}.
 * Sealed — exhaustive switch coverage ensures new store variants are a
 * compile error at every consumer.
 */
public sealed interface TemporalSource {
    record FromMindMap(MindMapNode node) implements TemporalSource {}
    record FromMemory(Memory memory) implements TemporalSource {}
    record FromCbr(ScoredCbrCase<?> cbrCase) implements TemporalSource {}
}
```

Adding a new store variant (e.g., `FromEngagement`) is a compile error at every consumer via exhaustive switch coverage.

### TemporalQuery

What to query — time range, tenants, and which stores:

```java
package io.casehub.neocortex.cognitive.index;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Specifies what to query from the {@link TemporalIndex} — time range,
 * tenants, and which stores to include. Factory methods cover common
 * patterns: {@link #since}, {@link #window}, {@link #upcoming}.
 */
public record TemporalQuery(
    Collection<String> tenantIds,
    Instant from,
    Instant to,
    int limit,
    Set<StoreKind> sources
) {
    public enum StoreKind { MINDMAP, MEMORY, CBR }

    public TemporalQuery {
        Objects.requireNonNull(tenantIds, "tenantIds required");
        if (tenantIds.isEmpty()) throw new IllegalArgumentException("at least one tenantId required");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        if (sources == null || sources.isEmpty()) {
            sources = EnumSet.allOf(StoreKind.class);
        }
    }

    public static TemporalQuery since(Collection<String> tenantIds, Instant from, int limit) {
        return new TemporalQuery(tenantIds, from, null, limit, EnumSet.allOf(StoreKind.class));
    }

    public static TemporalQuery window(Collection<String> tenantIds, Instant from, Instant to, int limit) {
        return new TemporalQuery(tenantIds, from, to, limit, EnumSet.allOf(StoreKind.class));
    }

    public static TemporalQuery upcoming(Collection<String> tenantIds, Instant now, int limit) {
        return new TemporalQuery(tenantIds, now, null, limit, EnumSet.of(StoreKind.MINDMAP));
    }

    public TemporalQuery withSources(Set<StoreKind> sources) {
        return new TemporalQuery(tenantIds, from, to, limit, sources);
    }
}
```

- `from`/`to` are both nullable. Null `from` = no lower bound. Null `to` = no upper bound.
- `upcoming()` factory queries only MindMap (future events have `validFrom` — memories and CBR cases don't have future timestamps).
- `sources` controls which stores are queried. Default: all available. This is a query-level filter independent of classpath availability (D17 handles classpath).
- **MindMap timestamp selection heuristic:** When querying MindMap, TemporalIndex uses `updatedAfter` (recent changes) by default. `upcoming()` sets `sources` to `MINDMAP` only — TemporalIndex detects this and uses `validAfter` instead. The heuristic: if the query window starts at or after `Instant.now()` (± 1 second tolerance), use `validAfter`; otherwise use `updatedAfter`. This avoids adding an explicit mode field to TemporalQuery.

### TemporalRanker

Composable scoring function, orthogonal to the index (D13, D14):

```java
package io.casehub.neocortex.cognitive.index;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Composable scoring function for re-ordering {@link TemporalEntry} results.
 * Orthogonal to {@link TemporalIndex} — the index produces chronological
 * data, the ranker re-orders it by salience or any other criterion.
 *
 * <p>TemporalFocus (#244) is a TemporalRanker implementation, not a
 * separate aggregation utility.
 */
@FunctionalInterface
public interface TemporalRanker {

    double score(TemporalEntry entry, Instant now);

    default List<TemporalEntry> rank(List<TemporalEntry> entries, Instant now) {
        return entries.stream()
            .sorted(Comparator.comparingDouble((TemporalEntry e) -> score(e, now)).reversed())
            .toList();
    }

    static TemporalRanker recency() {
        return (entry, now) -> {
            long seconds = Duration.between(entry.timestamp(), now).abs().getSeconds();
            return 1.0 / (1.0 + seconds);
        };
    }
}
```

- `score()` returns a double — higher is more salient.
- `rank()` default method applies the scorer and returns a new sorted list (descending by score).
- `recency()` factory: inverse-time scoring. Newer entries score higher. Works for both past (recent memories) and future (approaching events — closer = higher score).
- TemporalFocus (#244) becomes a `TemporalRanker` implementation — not a separate utility.

### TemporalIndex CDI Bean

```java
package io.casehub.neocortex.cognitive.index;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.mindmap.MindMapQuery;
import io.casehub.neocortex.mindmap.MindMapStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Cross-store temporal aggregator — queries MindMap, Memory, and CBR stores
 * on demand and merges results into a single chronological timeline.
 *
 * <p>This is a <strong>stateless derived view</strong>, not a store. It holds
 * no persistent state and re-queries the underlying stores on every call.
 * All source data is owned by the originating stores — this class is pure
 * orchestration. The same architectural pattern as {@code MindMapAnalyzer}
 * and {@code RetrievalAnalyzer}.
 *
 * <p>Stores are injected via {@link Instance} for graceful degradation —
 * missing stores are silently skipped. An app using only MindMap gets
 * temporal indexing for MindMap nodes without pulling in memory backends.
 */
@ApplicationScoped
public class TemporalIndex {

    @Inject Instance<MindMapStore> mindMapStore;
    @Inject Instance<CaseMemoryStore> memoryStore;
    @Inject Instance<CbrCaseMemoryStore> cbrStore;

    public List<TemporalEntry> query(TemporalQuery query) {
        List<TemporalEntry> results = new ArrayList<>();

        if (query.sources().contains(TemporalQuery.StoreKind.MINDMAP)
                && mindMapStore.isResolvable()) {
            results.addAll(queryMindMap(mindMapStore.get(), query));
        }

        if (query.sources().contains(TemporalQuery.StoreKind.MEMORY)
                && memoryStore.isResolvable()) {
            results.addAll(queryMemory(memoryStore.get(), query));
        }

        if (query.sources().contains(TemporalQuery.StoreKind.CBR)
                && cbrStore.isResolvable()) {
            results.addAll(queryCbr(cbrStore.get(), query));
        }

        Collections.sort(results);
        if (results.size() > query.limit()) {
            results = new ArrayList<>(results.subList(0, query.limit()));
        }
        return results;
    }

    // Each queryXxx method iterates tenantIds, queries the store,
    // wraps results in TemporalEntry with the appropriate TemporalSource variant
}
```

### Store Query Mapping

| Store | `from` maps to | `to` maps to | Timestamp extracted from |
|-------|---------------|-------------|------------------------|
| MindMapStore | `since`/`window`: `updatedAfter`; `upcoming`: `validAfter` | `MindMapQuery.validBefore` | `since`/`window`: `node.updatedAt()`; `upcoming`: `node.validFrom()` |
| CaseMemoryStore | `MemoryQuery.since` | Post-query filter (`memory.createdAt().isBefore(to)`) | `memory.createdAt()` |
| CbrCaseMemoryStore | `CbrQuery.notBefore` | Post-query filter | `summary.storedAt()` via scan |

**MindMap dual-timestamp handling:** MindMap nodes carry two temporal signals: `updatedAt` (when the node was last changed) and `validFrom` (when the event occurs). These are distinct temporal events — "something was recently changed" vs. "something is coming up."

The index queries MindMapStore differently depending on the `TemporalQuery`:
- `since()` / `window()`: queries with `updatedAfter` — returns recently-changed nodes. Does NOT query `validAfter` to avoid returning all future-dated nodes.
- `upcoming()`: queries with `validAfter` — returns future-dated nodes by their event time. Uses `node.validFrom()` as the entry timestamp.
- Custom queries can request both by calling `query()` twice with different `from`/`to` bounds.

A node can legitimately appear in both a `since()` and `upcoming()` result set with different timestamps — this is correct, not duplication.

**CaseMemoryStore limitation:** `MemoryQuery` has `since` (lower bound) but no `until` (upper bound). The `to` bound is applied as a post-query filter on `memory.createdAt()`. If this becomes a performance concern, an `until` field can be added to `MemoryQuery` in a follow-up.

**CbrCaseMemoryStore:** Uses `scan()` when available (returns `CbrCaseSummary` with `storedAt`). Falls back to `retrieveSimilar()` with empty features and `minSimilarity(0.0)` if scan is not supported. The `notBefore` field on `CbrQuery` provides the lower bound.

### Multi-Tenant Merge

For each store, the index iterates `query.tenantIds()` and queries per tenant (all three stores are tenant-scoped). Results from all tenants are merged into a single list. Each `TemporalEntry` carries its `tenantId` so callers know the provenance.

When #254 lands (MemorySpace wiring), the caller resolves `agent → spaces → tenantIds` and passes the list. The index is unchanged.

### Memory Domain Filtering

`CaseMemoryStore.query()` requires a `MemoryDomain`. The index queries the `experience` domain by default (most temporally meaningful). A future enhancement could accept a `Set<MemoryDomain>` on `TemporalQuery` to query multiple domains. For now, the experience domain captures agent actions, observations, and outcomes — the primary temporal signal from text memory.

### What This Does NOT Include

- **Salience scoring** — ranking is TemporalRanker's job (D13, D14). TemporalFocus (#244) is a ranker implementation.
- **MemorySpace awareness** — uses `Collection<String> tenantIds` (D12). #254 wires the visibility layer.
- **Materialized index** — no persistent data structure. Queries stores on demand (pull-based). CuriositySignalGenerator runs on a tick loop, so on-demand is sufficient.
- **Affect trajectory integration** — depends on #239 (affect trajectory log). When #239 lands, a new `TemporalSource.FromAffect` variant can be added.

## Testing

### Unit Tests (cognitive-index module)

1. `TemporalEntry.compareTo()` — chronological ordering
2. `TemporalQuery` validation — empty tenantIds, non-positive limit, null sources defaults to all
3. `TemporalQuery` factories — `since()`, `window()`, `upcoming()` produce correct queries
4. `TemporalRanker.recency()` — newer entries score higher, works for past and future
5. `TemporalRanker.rank()` — re-orders entries by score descending

### Integration Tests (with InMemory stores)

6. Three stores populated — entries merge chronologically
7. Single store on classpath — missing stores silently skipped
8. Multi-tenant — entries from two tenants merge with correct tenantId provenance
9. Empty time window — no results
10. Limit enforcement — global limit trims merged results
11. `upcoming()` — queries only MindMap, returns future-dated nodes
12. `window()` — entries outside the window are excluded
13. MindMap `since()` — uses `updatedAt`, not `validFrom`; future-dated nodes not returned unless recently changed
14. MindMap `upcoming()` — uses `validFrom`; same node can appear in both `since()` and `upcoming()` with different timestamps
15. `TemporalQuery.withSources()` — only requested stores are queried

## References

- cognitive-architecture-roadmap.md §2d — roadmap design sketch
- cognitive-architecture-roadmap.md §4b — TemporalFocus (downstream consumer)
- cognitive-coherence-audit.md §Temporal — CuriositySignalGenerator O(n) scan
- TemporalMark.java — cognitive-api temporal type hierarchy (D8-D11)
- MindMapQuery.java:18 — validAfter, validBefore, updatedAfter predicates (#236)
- MemoryQuery.java:14 — since field
- CbrQuery.java:18 — notBefore field
- Memory.java:16 — createdAt field
- MindMapNode.java:24-28 — updatedAt, validFrom, validUntil
- D12-D18 in decisions.md — design decisions for this spec
- Issue #254 — follow-up for MemorySpace visibility layer wiring
