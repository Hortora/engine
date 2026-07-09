# Adaptive Cross-Encoder Score Filtering — Design Spec

**Issue:** #44
**Date:** 2026-07-09

## Problem

After cross-encoder reranking, ~40% of returned garden entries have negative CE scores — the cross-encoder actively judges them as irrelevant. The engine returns a fixed count (default 16) regardless of how many results are actually relevant. This pollutes the calling LLM's context window with noise, displacing useful content and risking hallucinated citations.

Three distinct query profiles exist in the benchmark data:

1. **High-signal** — all 16 results have CE > 5.0 (everything relevant)
2. **Mixed** — 2–4 results at CE 3.0–5.0, then a steep drop to noise
3. **No-match** — all 16 results have CE < 0 (nothing in the corpus is relevant)

The engine currently treats all three identically: return 16 results.

## Root Cause

The existing `adaptiveExtend()` in `SearchResource` operates on vector similarity scores (`relevance()`) for gap detection. After CE reranking reorders results by CE score, vector similarity is no longer monotonically decreasing in the result list — the gap detection operates on noise. The existing logic only *extends* (adds more results into dense clusters) but never *trims* (removes noise entries within the requested limit).

## Design

### Score Pipeline

`CaseRetriever` returns `RetrievedChunk` with CE scores in metadata (`_crossEncoderScore`). `SearchResource.searchLocal()` maps this to `SearchResult` with both `relevance()` (vector similarity) and `crossEncoderScore()` (CE score). The adaptive filter uses `crossEncoderScore()` when present, falling back to `relevance()` for dense-only deployments.

### Unified Adaptive Filter

Replace `adaptiveExtend()` with `adaptiveFilter()` — a single function that handles both trimming and extension.

**Caller contract — `searchAdaptive()`:**

`doSearch()` returns candidates sorted by tier + vector relevance (federation merge order). Before calling `adaptiveFilter()`, `searchAdaptive()` must re-sort candidates by CE score descending. Entries without CE scores (`crossEncoderScore == null`) are placed after all CE-scored entries, sorted by vector relevance descending. This ensures gap detection operates on the quality signal, not on federation tier boundaries.

**Algorithm:**

```
Input: candidates (re-sorted by CE score desc), requestedLimit, scoreFloor, gapThreshold, minResults

1. Select primary score per entry: crossEncoderScore if present, else relevance
2. Apply score floor — remove candidates with primaryScore < scoreFloor
3. If none survive → return empty AdaptiveResult
4. Gap detection on survivors:
   a. CE mode (any survivors have crossEncoderScore):
      - Walk the CE-scored prefix from position 0
      - At position i, compute gap = score[i] - score[i+1]
      - First gap >= gapThreshold → mark as cutoff
   b. Dense-only mode (no survivors have crossEncoderScore):
      - Extension only — walk forward from the requestedLimit boundary
      - Extend while gap between adjacent relevance scores < 0.05
        and next entry's relevance >= scoreFloor
      - No gap-based trimming in this mode (floor is the only trim mechanism)
5. Determine effective count:
   - CE mode, gap within requestedLimit → trim to max(cutoffPosition + 1, minResults),
     capped by survivor count
   - CE mode, gap beyond requestedLimit → extend to cutoffPosition + 1,
     capped by candidate pool size (= min(requestedLimit × OVERFETCH_MULTIPLIER, MAX_LIMIT))
   - Dense-only with extension → return extended count
   - No gap (either mode) → return min(survivors, requestedLimit)
6. Return AdaptiveResult with filtering metadata
```

In CE mode, the effective count governs the entire result list — non-CE entries participate in the count but not in gap detection. After re-sort, CE entries precede non-CE entries, so the effective count naturally includes the highest-quality entries regardless of scoring regime.

**Why first gap, not largest:** The first significant gap marks where the CE model's confidence drops from "relevant cluster" to the next tier. The largest gap could be between two mediocre entries — not a useful signal.

**Why minResults:** The first-gap heuristic conflates two patterns: (1) an outlier top result with a strong cluster below it, and (2) a genuine boundary between relevant and noise. In benchmark data, ~18% of scenarios have their first gap >= 1.5 at position 0 (between the top result and an excellent second result). Without a floor, the algorithm would trim to a single result — discarding entries the cross-encoder rates as clearly relevant. `minResults` prevents this pathology while preserving gap detection for the common case.

**Gap detection coverage:** The floor (0.0) is the primary noise filter — it removes obviously irrelevant entries in all scenarios. Gap detection is a secondary signal that activates in ~40% of benchmark scenarios where a clear score boundary exists. In the remaining ~60%, scores either decline gradually (no sharp boundary to detect) or are uniformly high/low (no trimming needed). This is correct behavior — the gap detection adds precision when a clear cluster boundary exists, and correctly defers to the floor otherwise.

### Configuration

New `SearchConfig` interface (separate from `FederationConfig` — search concerns, not federation):

```java
@ConfigMapping(prefix = "hortora.search")
public interface SearchConfig {
    @WithDefault("0.0")
    double scoreFloor();       // minimum CE score to include

    @WithDefault("2.0")
    double gapThreshold();     // minimum score drop to trigger cutoff

    @WithDefault("3")
    int minResults();          // gap detection never trims below this floor
}
```

`scoreFloor` default 0.0: the principled boundary where the cross-encoder switches from "better than random" to "worse than random."

`gapThreshold` default 2.0: activates gap-based trimming when a clear score boundary exists in the result set. Calibrated against 28 benchmark scenarios: 1.5 was too aggressive (cut between quality tiers where both were relevant, losing 4 clearly relevant entries); 2.0 removes 205 noise entries with zero relevant entries lost.

`minResults` default 3: prevents the gap heuristic from trimming to a single result when the top entry is an outlier with a strong cluster below it. Gap detection still determines WHERE to cut — `minResults` only prevents cutting below this floor.

**Relationship to `FederationConfig.relevanceThreshold`:** These are different concerns on different scales. `relevanceThreshold` (vector similarity, 0–1 scale, per-garden SCHEMA.md) controls federation sufficiency — "do we have enough local matches to skip upstream queries?" `scoreFloor` (CE score, -10 to +10 scale, per-deployment `application.properties`) controls result filtering — "which CE-reranked results are good enough to show the LLM?" They operate at different pipeline stages (pre-CE vs post-CE) and are expected to make independent decisions.

### Data Model Changes

`AdaptiveResult` gains trimming metadata:

```java
public record AdaptiveResult(
    List<SearchResult> results,
    int requestedLimit,
    int availableAboveFloor,
    boolean extended,
    boolean trimmed,          // NEW — returned fewer than requestedLimit
    int floorFiltered) {}     // NEW — candidates removed by score floor
```

`SearchResult` unchanged — `crossEncoderScore` field stays (needed as the primary filter signal).

### MCP Output

Trimmed results get an explanatory header:

```
<!-- search_meta: returned=2 available=16 requested=16 trimmed=true floor_filtered=0 -->
*Showing 2 results (16 requested, trimmed at score gap).*
```

Each result shows CE score when present, falling back to vector relevance:

```
**Score:** 6.7 (CE)        ← when crossEncoderScore is present
**Relevance:** 0.67        ← when crossEncoderScore is null (dense-only)
```

This replaces the current `**Relevance:** 0.67` format, which shows vector similarity even when the CE score drove the filtering decision.

All-noise queries return the existing empty message: `"No relevant garden entries found for: " + query`

### REST Endpoint (`GET /search`)

The REST `search()` endpoint intentionally returns unfiltered results from `doSearch()`. It serves federation — `RemoteGardenClient` calls remote gardens via this endpoint. Pre-filtering at the source garden would compound with the requesting garden's own adaptive filter, losing data. Only the MCP endpoint (`gardenSearch`) applies adaptive filtering, because it is the LLM-facing consumer.

### What's NOT In Scope

- Subagent-mediated retrieval (#45) — separate concern, filed
- `FederationConfig.relevanceThreshold` recalibration for CE scores in `ChainWalker` — federation concern, separate issue if needed
- Platform-level CE score promotion to `relevanceScore` — neocortex concern, the engine reads `crossEncoderScore` directly

## Test Strategy

Pure unit tests on `adaptiveFilter()` — static method, no CDI needed.

| Test | Input CE scores | Limit | Expected | Validates |
|------|----------------|-------|----------|-----------|
| highSignal_noTrimming | 6.7, 6.4, 6.2, 6.0, 5.8, 5.5 | 6 | returns 6 | all relevant, no trimming |
| mixed_gapTrims | 5.1, 4.2, 0.7, 0.1, -0.5 | 16 | returns 3 | gap at 3.5 would trim to 2, minResults=3 overrides |
| noMatch_allBelowFloor | -5.9, -6.6, -7.2, -8.0 | 16 | returns 0 | floor catches all-noise |
| floorAndGapCooperate | 4.5, 4.0, 3.4, -0.4, -0.7 | 16 | returns 3 | floor removes 2, survivors cluster (no gap ≥ 1.5) |
| denseClusterExtends | 5.0, 4.8, 4.7, 4.6, 4.5, 4.3, 2.0 | 4 | returns 6 | extension preserved |
| noGap_normalTruncation | 3.7, 2.4, 1.5, 1.3, 1.0, 0.8 | 4 | returns 4 | no gap, standard limit |
| fallbackToRelevance | no CE, relevance: 0.9, 0.8, 0.3 | 4 | uses relevance | dense-only mode |
| emptyInput | [] | 16 | returns 0 | edge case |
| singleAboveFloor | 3.5 | 16 | returns 1 | single result |
| singleBelowFloor | -1.0 | 16 | returns 0 | single noise filtered |

| minResultsPreventsOverTrim | 5.5, 3.6, 3.1, 3.0 | 16 | returns 3 | gap at 0 (1.9), minResults=3 prevents trim to 1 |
| mixedCeAndNonCe | CE: 5.0, 3.0; non-CE rel: 0.8, 0.6 | 16 | returns 3 | CE gap at 0 (2.0), effective count max(1,3)=3; non-CE counted but not gap-detected |
| denseOnly_extension | no CE, rel: 0.9, 0.88, 0.87, 0.86, 0.85, 0.84, 0.5 | 4 | returns 6 | dense-only mode extends through cluster, stops at 0.84→0.5 gap |

Integration tests on `searchAdaptive()`:

| Test | Setup | Validates |
|------|-------|-----------|
| searchAdaptive_reSortsByCeScore | Feed results with CE scores in tier order (not CE order); verify adaptiveFilter receives them CE-ordered | Re-sort wiring from caller contract |

Existing `SearchResourceTest` cases updated for the renamed method. `GardenMcpToolsTest` verifies metadata format changes (CE score display).

### Benchmark Validation

Development-time validation against the 28 benchmark scenarios in `scripts/benchmark/results/crossencoder-pool50-scored.json`:

1. Run `adaptiveFilter()` on each scenario's score list with default config (scoreFloor=0.0, gapThreshold=1.5, minResults=3)
2. Verify no scenario returns fewer than `minResults` results (unless fewer survivors exist)
3. Compare pre-filter vs post-filter result counts per scenario — confirm noise reduction without loss of clearly relevant entries (CE > 3.0)
4. Document results as a benchmark report alongside the implementation PR

This is a one-time development validation, not a regression test — benchmark data changes as garden entries are added or modified.
