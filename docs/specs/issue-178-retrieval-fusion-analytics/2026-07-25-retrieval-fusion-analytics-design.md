# Retrieval Fusion Weights, Payload Boosting, and Query Analytics

**Issues:** #178, #179, #180
**Date:** 2026-07-25
**Status:** Design

## Context

Cross-module feedback from Hortora/engine#50 (HyDE investigation) — benchmark data
across scenarios shows keyword queries and NL queries have different failure patterns.
Keyword queries benefit more from BM25/sparse signal; NL queries benefit more from
dense signal. With equal-weight RRF fusion, retrieval cannot be tuned per corpus to
emphasize the stronger signal for a given workload.

Weighted RRF preserves RRF's rank-based robustness (no score comparability assumption)
while allowing the user to de-emphasize legs that contribute noise for their corpus.
This is lighter than switching to CC (which requires score comparability) and different
from per-leg topK adjustment (which controls candidate volume, not score influence).
With equal weights (the default), weighted RRF is identical to vanilla parameter-free RRF.

Three gaps identified:

1. All fusion legs have equal weight — no way to tune signal balance per corpus
2. Human-assigned quality scores in Qdrant payload are ignored during ranking
3. Retrieval tracking data exists but has no query-level analytical layer

## #178 — Unified Fusion Weights

### Problem

CC fusion uses per-leg weights via `CcWeightsConfig` (dense 0.5, sparse 0.3, bm25 0.2).
RRF fusion ignores `ScoredLeg.weight()` entirely — all legs get uniform `1/(k + rank + 1)`.
Server-side RRF/DBSF (Qdrant native) has no per-leg weight support. Two separate weight
regimes with no config unification.

### Design

Replace `CcWeightsConfig` with unified `FusionWeightsConfig` under
`casehub.rag.retrieval.weights.*`. All fusion strategies consume the same weights.

**Config:**

```properties
casehub.rag.retrieval.weights.dense=1.0    # default
casehub.rag.retrieval.weights.sparse=1.0   # default
casehub.rag.retrieval.weights.bm25=1.0     # default
```

All default to 1.0 (equal weighting). This replaces CC's former 0.5/0.3/0.2 defaults —
pre-release breaking change, acceptable. Rationale: equal weights is the neutral baseline;
any deviation from 1.0 is an explicit tuning choice. For RRF (the default strategy), equal
weights gives vanilla parameter-free behavior. Previous benchmarks
(architecture-justification.md, Recall@5 0.726 with CC at 0.5/0.3/0.2) used non-equal
weights — callers who were explicitly configuring CC weights should review their settings.

**Startup validation:** All weight values must be >= 0. Negative weights produce
nonsensical score contributions in both RRF (`leg.weight() * 1/(k + rank + 1)`)
and CC (negative normalized weight). Fail fast at startup if any weight < 0.

**RRF weighted fusion (`ScoreFusion.rrf()`):**

Currently: `scores.merge(id, 1.0 / (k + rank + 1), Double::sum)`

Becomes: `scores.merge(id, leg.weight() * 1.0 / (k + rank + 1), Double::sum)`

Normalization: `maxScore = totalWeight / (k + 1)` where `totalWeight = sum of all leg weights`.
Output remains in [0, 1].

**CC fusion (`ScoreFusion.convexCombination()`):**

Already uses `ScoredLeg.weight()` with auto-normalization. No algorithm change needed.

**HybridCaseRetriever — client-side weighted RRF:**

When strategy is RRF and weights are non-equal, server-side Qdrant RRF cannot apply
per-leg weights. `HybridCaseRetriever` falls back to client-side fusion: execute
individual prefetch queries per leg and fuse using `ScoreFusion.rrf()` — structurally
identical to the existing `executeConvexCombinationFusion()` but calling `ScoreFusion.rrf()`
instead of `ScoreFusion.convexCombination()`.

| Strategy | Weights | Execution |
|----------|---------|-----------|
| RRF | Equal (all active legs same value) | Server-side Qdrant RRF (current, faster) |
| RRF | Non-equal (among active legs) | Client-side `ScoreFusion.rrf()` (weighted) |
| DBSF | Any | Server-side Qdrant DBSF (no per-leg weight support) |
| CC | Any | Client-side `ScoreFusion.convexCombination()` (current) |

"Active legs" means legs whose vectors are actually present/enabled: dense is always
active; sparse is active when the embedder produces sparse vectors; BM25 is active when
`bm25Enabled=true`. The equality check compares only active legs' configured weights —
disabled legs' values are irrelevant to the server/client decision.

DBSF is a Qdrant-proprietary server-side algorithm with no client-side implementation.
Startup warning logged when non-equal weights are configured with DBSF strategy:
"Non-equal fusion weights have no effect with DBSF strategy — DBSF uses server-side
equal-weight fusion. Consider RRF or CC for per-leg weight control."

**Cross-cutting impact — CBR:**

`ScoreFusion.rrf()` is shared with CBR (`QdrantCbrCaseMemoryStore.fuseAndScore()`).
CBR constructs `ScoredLeg` instances with CC-derived weights (from `vectorWeight` and
`ccWeights`) regardless of fusion strategy. The weighted RRF change means CBR's RRF
path will now use those weights — a silent behavioral regression.

**Guard (in scope):** In `QdrantCbrCaseMemoryStore.fuseAndScore()`, when
`query.fusionStrategy() == RRF`, construct ALL legs (semantic and feature)
with `weight=1.0`. This matches current behavior exactly — `ScoreFusion.rrf()`
currently ignores all leg weights, so all legs contribute equally. The guard
preserves this equal contribution under the new weighted RRF implementation.

When `fusionStrategy == CC`, the existing CC-derived weights are unchanged —
feature leg at `1.0 - vectorWeight`, semantic legs at proportional shares of
`vectorWeight` scaled by `ccWeights`.

CBR's `QdrantCbrConfig.CcWeightsConfig` (with different defaults: 0.6/0.2/0.2)
is a separate config in a separate module — not renamed by this spec.

**Config mapping:**

```java
interface FusionWeightsConfig {
    @WithDefault("1.0") double dense();
    @WithDefault("1.0") double sparse();
    @WithDefault("1.0") double bm25();
}
```

Replaces `CcWeightsConfig`. `RetrievalConfig.ccWeights()` becomes
`RetrievalConfig.weights()`.

### Files Changed

| File | Change |
|------|--------|
| `rag/RagConfig.java` | Replace `CcWeightsConfig` with `FusionWeightsConfig`, rename accessor, add startup validation |
| `fusion-api/ScoreFusion.java` | `rrf()` uses `ScoredLeg.weight()`, update normalization |
| `fusion-api/ScoreFusionTest.java` | Add weighted RRF tests |
| `rag/HybridCaseRetriever.java` | Update config references; add `executeRrfFusion()` for client-side weighted RRF; DBSF startup warning |
| `rag/HybridCaseRetrieverTest.java` | Update config stubs, add weighted RRF client-side tests |
| `memory-qdrant/QdrantCbrCaseMemoryStore.java` | Guard: RRF strategy uses weight=1.0 for all legs |
| `memory-qdrant/QdrantCbrCaseMemoryStoreTest.java` | CBR guard tests: RRF uses equal weights, CC uses CC-derived weights |

## #180 — Payload Quality Boost

### Problem

Documents have human-assigned quality scores (1-10) stored as Qdrant payload metadata.
These scores are ignored during ranking. When a score=9 and score=3 document are close
in retrieval score, the higher-quality one should win.

### Analysis: Quality Is an Authority Signal

The quality score is **query-independent** — the document has the same quality regardless
of query. This is fundamentally different from dense/sparse/BM25, which are
**query-dependent** relevance signals. In IR theory: relevance vs authority.

The correct integration mode depends on the fusion strategy's mathematical properties:

| Fusion | Score model | Quality integration | Why |
|--------|------------|-------------------|-----|
| CC | Score-aware (min-max normalized) | Fusion leg | Magnitude preserved in convex combination |
| RRF | Rank-based (1/k+rank) | Post-fusion rescore | Rank conversion loses magnitude — quality 9→10 same delta as 1→2 |
| DBSF | Score-aware, server-side | Post-fusion rescore | Can't inject non-vector legs server-side |

The integration mode is **derived from the fusion strategy**, not independently configured.
No separate `BoostMode` enum needed.

### Design

**Config:**

```properties
casehub.rag.retrieval.weights.dense=1.0          # default
casehub.rag.retrieval.weights.sparse=1.0         # default
casehub.rag.retrieval.weights.bm25=1.0           # default
casehub.rag.retrieval.weights.quality=0.0        # default: disabled
casehub.rag.retrieval.quality-payload-field=      # payload field name (required when quality > 0)
casehub.rag.retrieval.quality-max=10.0           # scale ceiling for normalization
```

`quality > 0` activates boosting. No separate `enabled` flag — the weight IS the toggle.
`quality-payload-field` names the Qdrant payload field containing the numeric score (e.g. "score").
`quality-max` is the fixed scale ceiling used for normalization in the RRF/DBSF boost path
(default 10 for the 1-10 quality scale). Clamped: `normalizedQuality = min(payloadValue / qualityMax, 1.0)`.

Startup validation:
- `quality > 0` requires `quality-payload-field` non-empty AND `quality-max > 0`
- `quality < 0` fails (same >= 0 validation as other weights)

```java
interface RetrievalConfig {
    // ... existing: fusionStrategy, denseTopK, sparseTopK, bm25TopK, rrfK ...
    FusionWeightsConfig weights();
    Optional<String> qualityPayloadField();
    @WithDefault("10.0") double qualityMax();
}

interface FusionWeightsConfig {
    @WithDefault("1.0") double dense();
    @WithDefault("1.0") double sparse();
    @WithDefault("1.0") double bm25();
    @WithDefault("0.0") double quality();
}
```

`qualityPayloadField` and `qualityMax` are data-access/scale parameters — they define
where to read quality data and its numeric scale. These belong in `RetrievalConfig`
(alongside vector names and topK values), not in `FusionWeightsConfig` (which is purely
about numeric influence). `quality` weight stays in `FusionWeightsConfig`.

**CC path (fusion leg):**

When `quality > 0` and strategy is CC, `executeConvexCombinationFusion()` constructs
a quality leg from payload values of documents retrieved by the retrieval legs:

1. Collect all unique documents from dense/sparse/BM25 legs
2. Extract numeric payload value from `qualityField` for each document
3. Build `ScoredLeg<RetrievedChunk>` with quality scores and `weights.quality()` weight
4. Include in `ScoreFusion.convexCombination()` alongside retrieval legs

Quality participates in CC's min-max normalization and weighted summation — magnitude
preserved, naturally competes with retrieval signals. Documents with missing or
non-numeric quality fields are excluded from the quality leg (they receive no quality
contribution in the convex combination).

**RRF/DBSF path (decorator rescore):**

`PayloadBoostCaseRetriever` — `@Decorator @Priority(60)` on `CaseRetriever`.
Positioned after cross-encoder reranking and before tracking in the decorator chain:

```
CaseRetriever (HybridCaseRetriever, priority 0)
  → QueryExpandingCaseRetriever (@Priority 200)
  → CorrectiveCaseRetriever (@Priority 100)
  → CrossEncoderRerankingRetriever (@Priority 75)
  → PayloadBoostCaseRetriever (@Priority 60)    ← NEW
  → TrackingCaseRetriever (@Priority 50)
```

@Priority(60) ensures the quality boost applies AFTER cross-encoder reranking
(which replaces input scores entirely via `RerankingLogic.rerank()`) and BEFORE
tracking (which records the final result). If PayloadBoost were inside the
cross-encoder (higher priority), the boost would be overwritten by cross-encoder
rescoring.

Decorator logic:
1. Delegate to inner `CaseRetriever.retrieve()`
2. If strategy is CC → no-op (quality already integrated via fusion leg)
3. Read `metadata.get(qualityPayloadField)` from each `RetrievedChunk`, parse to double
4. Apply: `boostedScore = score * (1 + normalizedQuality * qualityWeight)`
   where `normalizedQuality = min(payloadValue / qualityMax, 1.0)`
5. Documents with missing or non-numeric quality fields retain their original score
6. If no documents have valid quality values → no-op (return results unchanged)
7. Re-sort by boosted score, return

**Activation:** `casehub.rag.retrieval.weights.quality > 0` checked at runtime.
PayloadBoost intentionally does not use `@IfBuildProperty` (unlike other decorators):

1. **The weight IS the toggle.** `quality=0.0` (default) = disabled; `quality>0` = enabled.
   A separate boolean flag creates config redundancy and a consistency risk — what does
   `boost-enabled=true` + `quality=0.0` mean? The weight already encodes the intent.
2. **No external service dependency.** Other decorators gate on boolean flags because they
   require external services (cross-encoder model, LLM, relevance evaluator) that may not
   be available. PayloadBoost reads from metadata already present in the result set.
3. **Negligible overhead.** When disabled, the decorator is one condition check per
   `retrieve()` call — dominated by network I/O to Qdrant. No model loading, no
   connection pooling, no resource allocation.

**Numeric payload extraction:**

`HybridCaseRetriever.mapToChunks()` currently only extracts string payload values.
Extend to also extract numeric values (double) into metadata as string representations.
This enables the decorator to read quality scores from `RetrievedChunk.metadata()`.

### Files Changed

| File | Change |
|------|--------|
| `rag/RagConfig.java` | Add `quality` to `FusionWeightsConfig`, add `qualityPayloadField` + `qualityMax` to `RetrievalConfig` |
| `rag/HybridCaseRetriever.java` | CC path: construct quality leg. `mapToChunks()`: extract numeric payload. |
| `rag/PayloadBoostCaseRetriever.java` | **New.** Decorator for RRF/DBSF rescore path, @Priority(60). |
| `rag/HybridCaseRetrieverTest.java` | Quality leg tests for CC path |
| `rag/PayloadBoostCaseRetrieverTest.java` | **New.** Decorator unit tests |

## #179 — RetrievalAnalyzer Query-Level Analytics

### Problem

`RetrievalAnalyzer` in `rag-api` provides document-centric analytics (documentStats,
unretrievedDocuments, qualitySignals). No query-level analysis exists — engine's
`gardenUnretrieved` MCP tool does crude inline frequency counting (~60 lines).

### Design

Extend the existing static utility with three query-level methods. Keep static — pure
computation over passed-in data, no CDI, stays in `rag-api` (tier 1).

**New methods:**

```java
public static List<QueryQualitySignal> lowRelevanceQueries(
    RetrievalTracker tracker, CorpusRef corpus,
    Instant since, Instant until, double scoreThreshold)
```

Queries where average result relevance score < `scoreThreshold`. Identifies retrieval
gaps — queries the system handles poorly. Note: `scoreThreshold` is strategy-specific.
RRF scores are normalized to [0,1] via `totalWeight / (k+1)` and cluster differently
than CC scores or cross-encoder scores. The caller is responsible for choosing an
appropriate threshold for their fusion and reranking configuration.

```java
public static List<QueryQualitySignal> zeroHitQueries(
    RetrievalTracker tracker, CorpusRef corpus,
    Instant since, Instant until)
```

Queries that returned zero results. Identifies corpus coverage gaps.

```java
public static Map<String, QueryFrequencyStats> queryFrequency(
    RetrievalTracker tracker, CorpusRef corpus,
    Instant since, Instant until)
```

Query text → frequency + quality metrics. Sorted by descending count. Identifies hot
topics and their retrieval quality.

**New value types in `rag-api`:**

```java
public record QueryQualitySignal(
    String queryText,
    double averageRelevanceScore,
    int retrievalCount,
    Instant lastSeen) {}
```

```java
public record QueryFrequencyStats(
    int count,
    double averageScore,
    Instant firstSeen,
    Instant lastSeen) {}
```

**Implementation approach:**

All three methods iterate `tracker.findRecords(corpus, since, until)`. Each
`RetrievalRecord` contains the `RetrievalQuery` (with `.text()`) and the list of
`RetrievedDocumentRef` (with `.relevanceScore()`).

- `lowRelevanceQueries`: group by query text, compute average score across all
  retrievals of that query, filter by threshold
- `zeroHitQueries`: filter records where `documents` list is empty
- `queryFrequency`: group by query text, count occurrences, compute avg score,
  track first/last timestamp

### Files Changed

| File | Change |
|------|--------|
| `rag-api/RetrievalAnalyzer.java` | Add three query-level methods |
| `rag-api/QueryQualitySignal.java` | **New.** Value type |
| `rag-api/QueryFrequencyStats.java` | **New.** Value type |
| `rag-api/RetrievalAnalyzerQueryTest.java` | **New.** Tests for query-level methods |

## Module Impact

All changes are within existing modules:

- `fusion-api` — weighted RRF algorithm change
- `rag-api` — new value types, extended RetrievalAnalyzer
- `rag` — config changes, HybridCaseRetriever updates, new decorator

No new modules. No new dependencies. No Flyway migrations.

## Test Strategy

| Area | Tests |
|------|-------|
| Weighted RRF | Weight=1.0 matches current behavior, non-equal weights shift rankings, weight=0 excludes leg, normalization stays [0,1], negative weight rejected at startup |
| Client-side RRF fallback | Non-equal weights trigger client-side fusion, equal weights use server-side, DBSF logs warning with non-equal weights |
| CC quality leg | Quality weight=0 no change, quality>0 shifts rankings, missing payload values excluded from quality leg |
| Payload boost decorator | RRF strategy applies boost after cross-encoder, CC strategy is no-op, missing field retains original score, non-numeric field retains original score, all-missing no-op, disabled config no-op, qualityMax clamps normalization |
| CBR guard | RRF strategy uses equal-weight legs (feature and semantic all 1.0), CC strategy uses CC-derived weights, guard does not alter CC path |
| Query analytics | Low-relevance filtering, zero-hit detection, frequency counting, empty tracker returns empty, edge cases (single record, all zero-hit) |
