# RAG Hortora Shared-Code Gaps — Design Spec

**Date:** 2026-06-17
**Status:** Approved — pending implementation
**Issue:** casehubio/neural-text#35
**Cross-refs:** casehubio/engine#521 (CaseRetriever call-site update), casehubio/parent#270 (PLATFORM.md dependency map)

---

## Problem

Hortora's garden retrieval engine (`Hortora/engine`) duplicates significant code from neural-text's `rag` module: Qdrant integration, ingestion orchestration, cursor management, change detection, metadata extraction. The duplication exists because `rag` has three hard blockers preventing Hortora from consuming it directly:

1. Sparse embedding is mandatory — no dense-only mode
2. Point IDs are random — no idempotent upserts
3. No query-time payload filtering on `CaseRetriever`

This spec addresses all three. After implementation, Hortora drops 6 classes and depends on `casehub-rag-api` + `casehub-rag` directly.

---

## Change 1: PayloadFilter — sealed filter algebra in rag-api

New type in `rag-api/src/main/java/io/casehub/rag/PayloadFilter.java`:

```java
public sealed interface PayloadFilter {

    record Eq(String field, String value) implements PayloadFilter {}
    record In(String field, List<String> values) implements PayloadFilter {}
    record Not(PayloadFilter inner) implements PayloadFilter {}
    record And(List<PayloadFilter> filters) implements PayloadFilter {
        public And { if (filters.isEmpty()) throw new IllegalArgumentException("And requires at least one filter"); filters = List.copyOf(filters); }
    }
    record Or(List<PayloadFilter> filters) implements PayloadFilter {
        public Or { if (filters.isEmpty()) throw new IllegalArgumentException("Or requires at least one filter"); filters = List.copyOf(filters); }
    }

    static PayloadFilter eq(String field, String value) { return new Eq(field, value); }
    static PayloadFilter in(String field, List<String> values) { return new In(field, List.copyOf(values)); }
    static PayloadFilter not(PayloadFilter inner) { return new Not(inner); }
    static PayloadFilter and(PayloadFilter... filters) { return new And(List.of(filters)); }
    static PayloadFilter or(PayloadFilter... filters) { return new Or(List.of(filters)); }
}
```

Tier 1 pure — zero dependencies beyond `java.util`. Sealed for exhaustive pattern matching in implementations. Five operations: keyword equality, multi-value membership, negation, conjunction, disjunction. Adding `Or` now avoids a source-breaking SPI change later — sealed interfaces force exhaustive pattern matches in every consumer, so the first release must be algebraically complete.

`PayloadFilter` operates on **user-provided metadata only** — the fields present in `ChunkInput.metadata()`. Synthetic payload fields added by the ingestor (`content`, `sourceDocumentId`, `tenantId`) are not filterable via `PayloadFilter`. Tenancy isolation is handled separately via `CorpusRef` and `TenancyStrategy`.

---

## Change 2: CaseRetriever SPI signature — add filter parameter

**`CaseRetriever`:**

```java
public interface CaseRetriever {
    List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter);
}
```

**`ReactiveCaseRetriever`:**

```java
public interface ReactiveCaseRetriever {
    Uni<List<RetrievedChunk>> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter);
}
```

`filter` is nullable — `null` means no payload filtering. No backward-compatible overload — all callers update explicitly.

### Call sites to update (all within this repo)

| Class | Module | Change |
|---|---|---|
| `HybridCaseRetriever` | rag | Implementation — apply filter |
| `ReactiveHybridCaseRetriever` | rag | Implementation — apply filter |
| `BlockingToReactiveCaseRetriever` | rag | Pass through |
| `InMemoryCaseRetriever` | rag-testing | Apply filter to in-memory chunks |
| `InMemoryReactiveCaseRetriever` | rag-testing | Apply filter |
| `HybridSearchDemo` | examples | Pass `null` |
| `RagPipelineIT` | examples | Pass `null` |
| `HybridSearchSmokeTest` | examples | Pass `null` |
| `BlockingToReactiveCaseRetrieverTest` | rag tests | Pass `null` |
| `BlockingReactiveParityTest` | rag-api tests | Update method reference |

### External callers

`casehub-engine` consumes `CaseRetriever` — tracked in casehubio/engine#521. Mechanical migration: pass `null` at every call site.

---

## Change 3: Optional sparse embeddings

### Bean producers

Both `RagBeanProducer` and `ReactiveRagBeanProducer`:

```java
// Before:
@Inject SparseEmbedder sparseEmbedder;

// After:
@Inject Instance<SparseEmbedder> sparseEmbedderInstance;

// In producer method:
SparseEmbedder sparseEmbedder = sparseEmbedderInstance.isResolvable()
    ? sparseEmbedderInstance.get() : null;
```

Follows the pattern already used for `CrossEncoderReranker`.

### Constructor signature changes — nullable SparseEmbedder

All four implementation constructors change from requiring non-null `SparseEmbedder` to accepting nullable:

| Class | Parameter change |
|---|---|
| `QdrantEmbeddingIngestor(QdrantClient, EmbeddingModel, SparseEmbedder, ...)` | `SparseEmbedder` becomes `@Nullable` |
| `HybridCaseRetriever(QdrantClient, EmbeddingModel, SparseEmbedder, ...)` | `SparseEmbedder` becomes `@Nullable` |
| `ReactiveQdrantEmbeddingIngestor(QdrantClient, EmbeddingModel, SparseEmbedder, ...)` | `SparseEmbedder` becomes `@Nullable` |
| `ReactiveHybridCaseRetriever(QdrantClient, EmbeddingModel, SparseEmbedder, ...)` | `SparseEmbedder` becomes `@Nullable` |

When `sparseEmbedder` is null, the class operates in dense-only mode (see below).

### QdrantEmbeddingIngestor — dense-only mode

`sparseEmbedder` field becomes nullable:

- **`ingest()`**: skip `sparseEmbedder.embedBatch()` when null. Build points with dense vector only.
- **`ensureCollection()`**: skip `SparseVectorConfig` when null. Collection gets dense `VectorParamsMap` only.
- **`buildPoint()`**: when sparse map is null, named vectors contains only `denseVectorName`.

### HybridCaseRetriever — dense-only query path

`sparseEmbedder` field becomes nullable. When null, the retriever uses a fundamentally different query shape:

**Hybrid mode (sparseEmbedder present) — unchanged:**

```java
QueryPoints queryPoints = QueryPoints.newBuilder()
    .setCollectionName(collection)
    .addPrefetch(densePrefetch)     // dense nearest-neighbor
    .addPrefetch(sparsePrefetch)    // sparse nearest-neighbor
    .setQuery(QueryFactory.rrf(Rrf.newBuilder().setK(rrfK).build()))
    .setLimit(queryLimit)
    .setWithPayload(WithPayloadSelectorFactory.enable(true))
    .build();
```

**Dense-only mode (sparseEmbedder null) — new code path:**

```java
QueryPoints.Builder builder = QueryPoints.newBuilder()
    .setCollectionName(collection)
    .setQuery(QueryFactory.nearest(denseEmbedding.vectorAsList()))
    .setUsing(denseVectorName)
    .setLimit(queryLimit)
    .setWithPayload(WithPayloadSelectorFactory.enable(true));
mergedFilter.ifPresent(builder::setFilter);
QueryPoints queryPoints = builder.build();
```

No prefetch, no RRF, no fusion. Direct dense nearest-neighbor query with optional payload + tenancy filter. This is a different `QueryPoints` structure, not a subset of the hybrid path.

### Reactive variants

`ReactiveQdrantEmbeddingIngestor` and `ReactiveHybridCaseRetriever` — same changes mirrored, including the dense-only query path.

### Config

`RagConfig.sparseVectorName()` stays but is only read when `sparseEmbedder` is non-null. No config changes.

---

## Change 4: Deterministic point IDs

### QdrantEmbeddingIngestor.ingest() and buildPoint()

Replace `UUID.randomUUID()` with `UUID.nameUUIDFromBytes()` (v3, JDK built-in). Use per-document chunk counters, not batch position — `CorpusIngestionService` batches chunks from multiple documents into a single `ingest()` call, so batch position is unstable across different ingestion paths (bootstrap vs watcher vs reconcile).

```java
// In ingest():
Map<String, Integer> counters = new HashMap<>();
for (int i = 0; i < chunks.size(); i++) {
    ChunkInput chunk = chunks.get(i);
    int chunkIndex = counters.merge(chunk.sourceDocumentId(), 0, Integer::sum);
    counters.put(chunk.sourceDocumentId(), chunkIndex + 1);
    String idInput = chunk.sourceDocumentId() + "#" + chunkIndex;
    UUID pointId = UUID.nameUUIDFromBytes(idInput.getBytes(StandardCharsets.UTF_8));
    // ... build point with pointId
}
```

Every chunk gets a deterministic ID from `sourceDocumentId + "#" + perDocumentIndex`. Document A with 3 chunks always gets `A#0`, `A#1`, `A#2` regardless of whether it's ingested alone or batched with other documents. Stable as long as the same splitter produces the same chunk count and order — which `DocumentSplitters.recursive()` guarantees for the same input.

Same change in `ReactiveQdrantEmbeddingIngestor`.

### Migration

Existing Qdrant collections have random UUIDs. After this change:

- **Documents modified after the upgrade:** the normal ingestion cycle calls `deleteDocument()` (which deletes by `sourceDocumentId` payload filter, not by point ID), then `ingest()` creates new points with deterministic IDs. Old random-UUID points are correctly cleaned up.
- **Unchanged documents:** old random-UUID points persist but are functionally correct — they have the right content, metadata, and `sourceDocumentId` payload. They just have non-deterministic point IDs.
- **Over time:** as documents are modified, all points converge to deterministic IDs through the normal delete-then-ingest cycle.
- **Immediate full migration:** drop the Qdrant collection, clear the cursor file, restart the service. Full re-ingest creates all points with deterministic IDs.

`reconcile()` does NOT help with this migration — it compares by `sourceDocumentId` (via `listDocuments()` which extracts from payload), so existing points look "already present" and are not re-ingested. `reconcile()` is a selective diff (ingest missing, delete stale), not a delete-all + re-ingest.

---

## Change 5: PayloadFilter translation in rag implementations

New package-private class in `rag/src/main/java/io/casehub/rag/runtime/PayloadFilterTranslator.java`:

```java
final class PayloadFilterTranslator {
    static Optional<Filter> toQdrantFilter(PayloadFilter filter) {
        if (filter == null) return Optional.empty();
        return Optional.of(Filter.newBuilder()
            .addMust(toCondition(filter))
            .build());
    }

    private static Condition toCondition(PayloadFilter filter) {
        return switch (filter) {
            case PayloadFilter.Eq eq -> ConditionFactory.matchKeyword(eq.field(), eq.value());
            case PayloadFilter.In in -> ConditionFactory.matchKeywords(in.field(), in.values());
            case PayloadFilter.Not not -> ConditionFactory.filter(
                Filter.newBuilder().addMustNot(toCondition(not.inner())).build());
            case PayloadFilter.And and -> {
                var nested = Filter.newBuilder();
                for (var f : and.filters()) nested.addMust(toCondition(f));
                yield ConditionFactory.filter(nested.build());
            }
            case PayloadFilter.Or or -> {
                var nested = Filter.newBuilder();
                for (var f : or.filters()) nested.addShould(toCondition(f));
                yield ConditionFactory.filter(nested.build());
            }
        };
    }
}
```

Exhaustive pattern match on the sealed interface — adding a new variant forces a compile error.

### Filter composition in retrievers

`HybridCaseRetriever.retrieve()` and `ReactiveHybridCaseRetriever.retrieve()` compose payload filter with tenancy filter:

```java
Optional<Filter> tenantFilter = tenancyStrategy.tenantFilter(corpus);
Optional<Filter> payloadFilter = PayloadFilterTranslator.toQdrantFilter(filter);

Filter.Builder combined = Filter.newBuilder();
tenantFilter.ifPresent(tf -> combined.addAllMust(tf.getMustList()));
payloadFilter.ifPresent(pf -> combined.addAllMust(pf.getMustList()));
Optional<Filter> mergedFilter = combined.getMustCount() > 0
    ? Optional.of(combined.build()) : Optional.empty();
```

Applied to both dense and sparse prefetch queries (hybrid mode) or the single dense query (dense-only mode).

### InMemoryCaseRetriever

Filter applied to in-memory chunk list via metadata matching. `Eq` checks `metadata.get(field).equals(value)`, `In` checks `values.contains(metadata.get(field))`, `Or` matches if any sub-filter matches, etc. Operates on `ChunkInput.metadata()` only — synthetic payload fields (`content`, `sourceDocumentId`, `tenantId`) are not present in the in-memory metadata map, consistent with the `PayloadFilter` scope defined in Change 1.

---

## Test Plan

### PayloadFilter (rag-api)

- Unit tests for all five record types: null field/value rejection, `In` defensive copy (`List.copyOf`), factory method correctness
- `And` and `Or` with empty lists — `IllegalArgumentException` (empty conjunction/disjunction is a caller bug; Qdrant treats empty `should` as match-all which is semantically wrong for `Or`)
- Nested composition: `And(Eq, Not(In))`, `Or(Eq, And(Eq, Eq))`

### PayloadFilterTranslator (rag)

- Unit tests verifying correct Qdrant `Filter`/`Condition` construction for each variant:
  - `Eq` → `ConditionFactory.matchKeyword`
  - `In` → `ConditionFactory.matchKeywords`
  - `Not(Eq)` → `ConditionFactory.filter(Filter.newBuilder().addMustNot(matchKeyword).build())`
  - `And(Eq, Eq)` → `Filter` with two `must` conditions
  - `Or(Eq, Eq)` → `Filter` with two `should` conditions
  - Nested: `And(Eq, Or(Eq, Not(In)))` — verify structure
- `null` input → `Optional.empty()`

### InMemoryCaseRetriever with filter (rag-testing)

- `Eq`: matching and non-matching metadata
- `In`: value in set, value not in set
- `Not`: inverted match
- `And`: all conditions must match
- `Or`: any condition matches
- `null` filter: returns unfiltered results (backward-compatible behavior)
- Filtering on a field not present in metadata: no match (not an error)

### Dense-only mode (rag)

- **Ingestion:** when no `SparseEmbedder` bean, collection created without sparse vector config, points built with dense vector only
- **Retrieval:** when no `SparseEmbedder` bean, query uses direct dense nearest-neighbor (no prefetch, no RRF), results correctly returned and ranked
- **Hybrid mode unchanged:** when `SparseEmbedder` present, full prefetch + RRF behavior preserved

### Deterministic point IDs (rag)

- Same `sourceDocumentId` + same chunk index → same UUID every time
- Different `sourceDocumentId` → different UUID
- Multi-chunk document: each chunk gets a distinct deterministic UUID
- Re-ingest same content → idempotent (same point IDs, upsert overwrites)

---

## What Hortora drops after this ships

| Hortora class | Replaced by |
|---|---|
| `GardenIngestionService` | `CorpusIngestionService` via `CorpusIngestionBinding` |
| `GardenIndexer` | `CorpusIngestionService` startup lifecycle |
| `FileCursorStore` | `rag:FileCursorStore` |
| `ExtractionResult` | `rag-api:ExtractionResult` |
| `QdrantClientProducer` | `rag:QdrantClientProducer` (`rag/src/main/java/io/casehub/rag/runtime/QdrantClientProducer.java`) |
| `GardenMetadataExtractor` | Own impl of `MetadataExtractor` SPI (stays in engine) |

**ExtractionResult field name change:** neural-text's `ExtractionResult` uses `body()`, Hortora's uses `content()`. When Hortora adopts neural-text's type, `GardenMetadataExtractor.extract()` must return `ExtractionResult(body, metadata)` instead of `ExtractionResult(content, metadata)`. Mechanical rename — Hortora-side only.

## What stays in Hortora

- Federation: `ChainWalker`, `FederationConfig`, `FederationConfigParser`, `RemoteGardenClient`
- MCP server: `GardenMcpTools`
- REST: `SearchResource` — slimmed to delegate local search to `CaseRetriever` + federate via `ChainWalker`
- CDI: `CorpusIngestionBinding` producer, no-op `CurrentPrincipal` `@Alternative`

---

## Tenancy

Not in scope. `CorpusRef.tenantId` remains required. Hortora provides a constant tenant ID and a matching no-op `CurrentPrincipal` `@Alternative`. `MemoryPermissions.assertTenant()` is already a no-op for background operations (watcher/startup) where `RequestContextCheck.isActive()` returns false.

---

## ARC42STORIES impact

After this ships, ARC42STORIES §2 constraint "Hortora shares inference-* only — rag-* modules are casehub-specific" becomes stale. Hortora will consume `rag-api` and `rag` directly. Update §2, §5 layer table (L6/L7 Hortora column → ✅ yes), and §1 stakeholders table (Hortora row: add rag consumption).

---

## Platform coherence

- **Tier 1 purity**: `PayloadFilter` is pure Java, sealed, zero dependencies. No Qdrant types leak into `rag-api`.
- **Reactive parity**: all SPI changes mirror between blocking and reactive variants.
- **Cross-repo propagation**: casehubio/engine#521 tracks `CaseRetriever` call-site updates.
- **PLATFORM.md**: casehubio/parent#270 tracks dependency map update.