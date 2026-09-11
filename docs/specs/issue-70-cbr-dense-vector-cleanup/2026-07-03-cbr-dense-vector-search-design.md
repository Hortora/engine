# CBR Dense Vector Search + minSimilarity + Spec Sync

**Issues:** #70, #71, #58, #59 (closed)
**Date:** 2026-07-03
**Status:** Approved
**Parent spec:** [CBR Retrieval Architecture](../issue-20-cbr-retrieval-architecture/2026-06-30-cbr-retrieval-architecture.md)

---

## 1. Problem

`QdrantCbrCaseMemoryStore` embeds `CbrCase.problem()` at store time but never uses it for retrieval. `executeFilterQuery()` uses `scrollAsync` (payload-filter-only), returning synthetic score 1.0 for every match. CBR retrieval is reduced to exact-match feature filtering — no semantic similarity on the problem dimension.

`CbrQuery.minSimilarity` is declared but never applied by either backend.

The root gap: `CbrQuery` has no text field for query-side embedding. The spec §4.2 says "embed query.problem()" but `CbrQuery` doesn't have a `problem` field.

Additionally, the CBR spec has accumulated divergences from implementation across #20 and #68.

## 2. Scope

| Issue | Change | Type |
|-------|--------|------|
| #70 | Dense vector search — add `problem` to CbrQuery, dual-path retrieval | API + impl |
| #71 | Wire `minSimilarity` via Qdrant `score_threshold` | impl |
| #59 | Closed — discriminator-based switch already in place | none |
| #58 | Spec update to match code | docs |

## 3. CbrQuery API Change

CbrQuery gains a nullable `String problem` field:

```java
public record CbrQuery(
    String tenantId,
    MemoryDomain domain,
    String caseType,
    Map<String, Object> features,
    int topK,
    double minSimilarity,
    Instant notBefore,
    String problem              // NEW: nullable — text for dense search
) {
    public CbrQuery {
        Objects.requireNonNull(tenantId, "tenantId required");
        Objects.requireNonNull(domain, "domain required");
        Objects.requireNonNull(caseType, "caseType required");
        Objects.requireNonNull(features, "features required");
        features = Map.copyOf(features);
        if (topK < 1) throw new IllegalArgumentException("topK must be >= 1, got: " + topK);
        if (minSimilarity < 0.0 || minSimilarity > 1.0)
            throw new IllegalArgumentException("minSimilarity must be in [0,1], got: " + minSimilarity);
        if (problem != null && problem.isBlank())
            throw new IllegalArgumentException("problem must not be blank when provided");
    }

    public static CbrQuery of(String tenantId, MemoryDomain domain,
                               String caseType, Map<String, Object> features, int topK) {
        return new CbrQuery(tenantId, domain, caseType, features, topK, 0.0, null, null);
    }

    public CbrQuery withProblem(String problem) {
        return new CbrQuery(tenantId, domain, caseType, features, topK,
                            minSimilarity, notBefore, problem);
    }

    public CbrQuery withMinSimilarity(double minSimilarity) {
        return new CbrQuery(tenantId, domain, caseType, features, topK,
                            minSimilarity, notBefore, problem);
    }

    public CbrQuery withNotBefore(Instant notBefore) {
        return new CbrQuery(tenantId, domain, caseType, features, topK,
                            minSimilarity, notBefore, problem);
    }
}
```

**Naming:** `problem` mirrors `CbrCase.problem()` — the same concept from the query perspective. Follows existing conventions (`MemoryQuery.question()`).

**Breaking change:** Direct `new CbrQuery(...)` calls gain an 8th argument. `CbrQuery.of()` factory returns `problem=null`, so factory users compile unchanged. The compile error on direct construction is intentional — forces each caller to decide whether to provide a problem.

## 3.5 Scored Return Type

`retrieveSimilar()` return type changes from `List<C>` to `List<ScoredCbrCase<C>>`:

```java
// memory-api
public record ScoredCbrCase<C extends CbrCase>(C cbrCase, double score) {
    public ScoredCbrCase {
        Objects.requireNonNull(cbrCase, "cbrCase required");
    }
}
```

Updated SPI signatures:

```java
// CbrCaseMemoryStore
<C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseType);

// ReactiveCbrCaseMemoryStore
<C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(CbrQuery query, Class<C> caseType);
```

**Why:** CBR retrieval's core value is ranking by similarity. The score is the retrieval signal — callers need it for adaptation confidence weighting, business-logic thresholds beyond the binary `minSimilarity` cutoff, and retrieval quality observability. Without scores, a 0.95 match is indistinguishable from a 0.51 match.

**Score semantics by path:**
- Dense search: actual cosine similarity from Qdrant `ScoredPoint.getScore()`
- Filter-only: `score = 1.0` (synthetic — all matches equally ranked)
- InMemory: `score = 1.0` (no scoring capability)

**Breaking change:** All `CbrCaseMemoryStore` and `ReactiveCbrCaseMemoryStore` implementations update their return type. Callers unwrap via `scoredCase.cbrCase()` and `scoredCase.score()`.

## 4. Dual-Path Retrieval

`QdrantCbrCaseMemoryStore.retrieveSimilar()` selects between two paths:

```
retrieveSimilar(query, caseClass)
  ├─ validate features against schema
  ├─ check collection exists
  ├─ build filter via CbrQueryTranslator.toFilter(query, schema)
  │
  ├─ embeddingModel != null && query.problem() != null?
  │   ├─ YES → executeDenseSearch(collection, filter, query)
  │   └─ NO  → executeFilterQuery(collection, filter, query.topK())
  │
  ├─ reconstruct cases from ScoredPoints
  └─ wrap each result in ScoredCbrCase(case, point.getScore())
```

### Dense search path

1. Embed `query.problem()` via `embeddingModel.embed()` → query vector
2. Build Qdrant `SearchPoints`:
   - Named vector (`config.denseVectorName()`) + query vector
   - Payload filter from `CbrQueryTranslator.toFilter()` (unchanged)
   - `limit` = `query.topK()`
   - `score_threshold` = `query.minSimilarity()`
   - `with_payload` = true
3. `searchAsync()` returns `List<ScoredPoint>` ordered by cosine similarity descending

### Filter-only path

Current `scrollAsync` behavior unchanged. Synthetic `score = 1.0` for all matches.

### Unchanged components

- `CbrPointBuilder` — store-time embedding already works
- `CbrQueryTranslator` — filter construction unchanged
- `reconstructCase` — payload reconstruction unchanged

### Embedding failure behavior

If `embeddingModel.embed()` fails during `retrieveSimilar()`, the exception propagates to the caller. No silent fallback to filter-only — the caller explicitly requested semantic ranking by providing `problem` text, and silent degradation would mask infrastructure failures and produce misleadingly unranked results. Callers that want degraded results can catch the exception and retry with `query.withProblem(null)`.

This is consistent with store-time behavior: `embeddingModel.embed()` failures in `store()` also propagate immediately.

### Missing embedding model logging

When `query.problem() != null` but `embeddingModel == null`, log at `Level.INFO`: "Dense search unavailable — problem text ignored, returning filter-only results for caseType={caseType}". This ensures operators are aware the system is running without semantic ranking capability when callers expect it. Additionally, with `ScoredCbrCase`, callers see `score = 1.0` on all results — an implicit signal that dense search was not used.

### Collection dimension validation

`CbrCollectionManager.ensureCollection()` must validate the vector dimension of existing collections. Upgrade scenario:

1. System starts without an embedding model → collection created with dim=1 (placeholder)
2. System upgraded to include an embedding model (dim=384)
3. `ensureCollection()` finds existing dim=1 collection → **dimension mismatch**
4. `store()` builds a 384-dim vector → Qdrant rejects the upsert

**Fix:** When `ensureCollection()` finds an existing collection with a vector dimension that differs from the expected dimension, it recreates the collection with the correct dimension. This drops existing Qdrant points — acceptable for a pre-production system with no end users. The delegate `CaseMemoryStore` (JPA/SQLite) retains the durable records. Until the reconciliation job (#74) is implemented, manual reindexing is required — call `store()` for each affected case to rebuild the Qdrant index. Automated recovery from dimension migration is blocked by #74.

The recreation is logged at `Level.WARNING` with the old and new dimensions.

## 5. minSimilarity Wiring

**Qdrant dense path:** `score_threshold = query.minSimilarity()` on `SearchPoints`. Always set, even when 0.0. Server-side filtering — results below threshold never returned. When `minSimilarity == 0.0` (default), `score_threshold = 0.0` excludes any negative-similarity results while passing all non-negative scores.

**Qdrant filter-only path:** No-op. All results score 1.0. Any `minSimilarity ≤ 1.0` passes.

**InMemory:** No change. No scoring capability. `minSimilarity` is structurally a no-op (implicit score 1.0).

**Score semantics:** Collection uses `Distance.Cosine`. Qdrant returns cosine similarity in [-1, 1] mathematically. In practice, text embedding models (BGE, sentence-transformers, etc.) produce vectors with non-negative components, yielding scores in [0, 1] for all semantically meaningful inputs. The `minSimilarity` validation range of [0, 1] is sufficient — negative cosine similarity indicates anti-correlated content that should never be a CBR match. `score_threshold = 0.0` (the default) correctly excludes any negative-similarity results.

## 6. Contract Tests

### CbrCaseMemoryStoreContractTest (new tests)

- `retrieveSimilar_withProblem_null_returnsFilteredResults` — `problem=null` still works (all backends)
- `retrieveSimilar_withProblem_nonNull_returnsFilteredResults` — non-null `problem` doesn't break filter-only backends; stores a case, queries with `problem` text and matching features, asserts results are returned (all backends)
- `retrieveSimilar_minSimilarity_zero_returnsAllMatches` — default threshold never filters (all backends)

### QdrantCbrDenseSearchTest (new test class)

Testcontainers Qdrant + deterministic EmbeddingModel stub:

- `denseSearch_ranksResultsBySimilarity` — different `problem()` texts, verify ordering
- `denseSearch_minSimilarity_filtersLowScoreResults` — threshold excludes distant cases
- `denseSearch_fallsBackToFilterOnly_whenProblemNull` — EmbeddingModel present but `problem=null` → all matches, no ranking
- `denseSearch_withPayloadFilters_combinesVectorAndFilter` — filter + dense search combined

### Existing tests

`CbrQuery.of()` returns `problem=null`, so CbrQuery construction in tests that use `of()` compiles unchanged. The `retrieveSimilar_notBefore_filtersOlderCases` test uses the direct 7-arg constructor and needs the 8th argument added. All 19 tests calling `retrieveSimilar()` must update result access from `result.problem()` to `result.cbrCase().problem()` due to the `ScoredCbrCase` wrapper (§3.5). Only 3 tests that don't call `retrieveSimilar()` (`store_returnsNonBlankId`, `erase_removesMatchingCases`, `eraseEntity_removesAllEntityCases`) compile unchanged.

## 7. Spec Update (#58)

Divergences to fix in `specs/issue-20-cbr-retrieval-architecture/2026-06-30-cbr-retrieval-architecture.md`:

| Section | Divergence | Fix |
|---------|-----------|-----|
| §3.3 | PlanCbrCase location: spec says `engine-api`, code has `memory-api` | Update to `memory-api` |
| §4 | No `NumericRange` value type | Add NumericRange, document range query semantics |
| §4.2 | Dense search described aspirationally | Update to describe implemented dual-path |
| §5 | No `problem` field, no `notBefore` field | Add both fields, document semantics |
| §5 | No `minSimilarity` semantics | Document wiring to `score_threshold` |
| §6 | `store()` missing `caseType` parameter | Add parameter |
| §6 | `erase()`/`eraseEntity()` return `int` | Update to `Integer` (boxed for reactive parity) |
| §3 | No `cbrType()` method on CbrCase interface | Add — discriminator for type-based reconstruction (#59) |
| §3 | No `features()` default method on CbrCase interface | Add — enables type-safe feature access without casting |
| §6 | `retrieveSimilar()` returns `List<C>` | Change to `List<ScoredCbrCase<C>>` — scored wrapper carries similarity |
| §6.3 | NoOp "delegates to CaseMemoryStore" | Update: no delegation, standalone no-op |
| §7 | Retrieval: single path | Document dual-path (dense + filter-only) |

## 8. Files Changed

| Module | File | Change |
|--------|------|--------|
| memory-api | `CbrQuery.java` | Add `problem` field, `withProblem()`, `withMinSimilarity()`, `withNotBefore()` builders, validation |
| memory-api | `ScoredCbrCase.java` (NEW) | Scored wrapper for retrieval results |
| memory-api | `CbrCaseMemoryStore.java` | Return type `List<C>` → `List<ScoredCbrCase<C>>` |
| memory-api | `ReactiveCbrCaseMemoryStore.java` | Return type `Uni<List<C>>` → `Uni<List<ScoredCbrCase<C>>>` |
| memory-qdrant | `QdrantCbrCaseMemoryStore.java` | Add `executeDenseSearch()`, branch in `retrieveSimilar()`, wrap results in `ScoredCbrCase` |
| memory-qdrant | `CbrCollectionManager.java` | Add dimension validation in `ensureCollection()` |
| memory-cbr-inmem | `InMemoryCbrCaseMemoryStore.java` | Return type update, `score = 1.0` for all results |
| memory-testing | `CbrCaseMemoryStoreContractTest.java` | 3 new contract tests, fix `notBefore` test constructor, update result type assertions |
| memory-qdrant | `QdrantCbrDenseSearchTest.java` (NEW) | Dense search integration tests |
| workspace/specs | CBR spec | Divergence fixes per §7 |
