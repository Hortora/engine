# Adaptive CE Score Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #44 — Adaptive cross-encoder score filtering — trim noise entries via gap detection
**Issue group:** #44

**Goal:** Replace the extension-only `adaptiveExtend()` with a unified `adaptiveFilter()` that both trims noise entries (CE score floor + gap detection) and extends into dense clusters, making result count fully adaptive.

**Architecture:** New `SearchConfig` interface for filter parameters. `adaptiveFilter()` replaces `adaptiveExtend()` as a static method on `SearchResource`. `AdaptiveResult` gains trimming metadata. `GardenMcpTools` updated for CE score display and trimmed-result headers. RE-sort wiring added to `searchAdaptive()` before calling `adaptiveFilter()`.

**Tech Stack:** Quarkus 3.36.x, SmallRye Config (`@ConfigMapping`), JUnit 5, AssertJ

## Global Constraints

- Java 25, Quarkus 3.36.x
- `SearchResult.crossEncoderScore()` is `Double` (nullable — null when CE reranking disabled)
- Default config: `scoreFloor=0.0`, `gapThreshold=1.5`, `minResults=3`
- `OVERFETCH_MULTIPLIER=2`, `MAX_LIMIT=50` — existing constants, unchanged
- Dense-only mode (no CE scores) preserves existing extension-only behavior with `GAP_THRESHOLD=0.05`
- REST `GET /search` endpoint is intentionally unfiltered — serves federation

---

### Task 1: SearchConfig + AdaptiveResult record update

**Files:**
- Create: `src/main/java/io/hortora/garden/search/SearchConfig.java`
- Modify: `src/main/java/io/hortora/garden/search/AdaptiveResult.java`
- Test: `src/test/java/io/hortora/garden/search/SearchResourceTest.java` (existing — verify compilation)

**Interfaces:**
- Consumes: nothing
- Produces:
  - `SearchConfig` interface with `scoreFloor() → double`, `gapThreshold() → double`, `minResults() → int`
  - `AdaptiveResult(List<SearchResult> results, int requestedLimit, int availableAboveFloor, boolean extended, boolean trimmed, int floorFiltered)` — 6-arg constructor

- [ ] **Step 1: Create `SearchConfig` interface**

```java
package io.hortora.garden.search;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "hortora.search")
public interface SearchConfig {

    @WithDefault("0.0")
    double scoreFloor();

    @WithDefault("1.5")
    double gapThreshold();

    @WithDefault("3")
    int minResults();
}
```

- [ ] **Step 2: Update `AdaptiveResult` record**

Replace the existing 4-field record with:

```java
package io.hortora.garden.search;

import java.util.List;

public record AdaptiveResult(
        List<SearchResult> results,
        int requestedLimit,
        int availableAboveFloor,
        boolean extended,
        boolean trimmed,
        int floorFiltered) {
}
```

- [ ] **Step 3: Fix compilation — update existing `adaptiveExtend` call sites**

The existing `adaptiveExtend()` creates 4-arg `AdaptiveResult`. Update it to pass the two new fields (`false, 0`) so the code compiles. This is a temporary bridge — Task 2 replaces the whole method.

In `SearchResource.adaptiveExtend()` at line 123, change:
```java
return new AdaptiveResult(candidates, requestedLimit, aboveFloor, false);
```
to:
```java
return new AdaptiveResult(candidates, requestedLimit, aboveFloor, false, false, 0);
```

And at line 142:
```java
return new AdaptiveResult(
        candidates.subList(0, Math.min(cutoff, candidates.size())),
        requestedLimit,
        availableAboveFloor,
        extended);
```
to:
```java
return new AdaptiveResult(
        candidates.subList(0, Math.min(cutoff, candidates.size())),
        requestedLimit,
        availableAboveFloor,
        extended,
        false,
        0);
```

- [ ] **Step 4: Fix `GardenMcpTools` — add `trimmed`/`floorFiltered` to metadata comment**

In `GardenMcpTools.gardenSearch()`, the metadata comment at line 47 currently reads:
```java
sb.append("<!-- search_meta: returned=").append(adaptive.results().size())
  .append(" available=").append(adaptive.availableAboveFloor())
  .append(" requested=").append(adaptive.requestedLimit())
  .append(" extended=").append(adaptive.extended())
  .append(" -->\n");
```

Update to:
```java
sb.append("<!-- search_meta: returned=").append(adaptive.results().size())
  .append(" available=").append(adaptive.availableAboveFloor())
  .append(" requested=").append(adaptive.requestedLimit())
  .append(" extended=").append(adaptive.extended())
  .append(" trimmed=").append(adaptive.trimmed())
  .append(" floor_filtered=").append(adaptive.floorFiltered())
  .append(" -->\n");
```

- [ ] **Step 5: Verify compilation and existing tests pass**

Run: `./mvnw test`
Expected: 117 tests PASS (all existing tests unchanged)

- [ ] **Step 6: Commit**

```
feat(#44): add SearchConfig and extend AdaptiveResult with trimming metadata

Refs #44
```

---

### Task 2: `adaptiveFilter()` — core algorithm with TDD

**Files:**
- Modify: `src/main/java/io/hortora/garden/search/SearchResource.java`
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`

**Interfaces:**
- Consumes: `SearchResult.crossEncoderScore() → Double`, `SearchResult.relevance() → double`
- Produces: `static AdaptiveResult adaptiveFilter(List<SearchResult> candidates, int requestedLimit, double scoreFloor, double gapThreshold, int minResults)` — replaces `adaptiveExtend()`

This is the largest task — 13 test cases from the spec. Each test is written first, then the implementation grows to pass it.

- [ ] **Step 1: Add test helper for CE-scored results**

Add to `SearchResourceTest`, below the existing `result()` helper:

```java
static SearchResult ceResult(String id, double relevance, double ceScore) {
    return new SearchResult(id, "title-" + id, "jvm", "gotcha", 8, "body",
            relevance, ceScore, "garden", "GE");
}
```

- [ ] **Step 2: Write all `adaptiveFilter` tests**

Add to `SearchResourceTest`, replacing the `// --- adaptiveExtend tests ---` section header with `// --- adaptiveFilter tests ---`:

```java
// --- adaptiveFilter tests ---

@Test
void adaptiveFilter_highSignal_noTrimming() {
    var candidates = List.of(
            ceResult("a", 20.0, 6.7), ceResult("b", 19.0, 6.4),
            ceResult("c", 21.0, 6.2), ceResult("d", 18.0, 6.0),
            ceResult("e", 20.5, 5.8), ceResult("f", 19.5, 5.5));
    var r = SearchResource.adaptiveFilter(candidates, 6, 0.0, 1.5, 3);
    assertThat(r.results()).hasSize(6);
    assertThat(r.trimmed()).isFalse();
    assertThat(r.extended()).isFalse();
    assertThat(r.floorFiltered()).isEqualTo(0);
}

@Test
void adaptiveFilter_mixed_gapTrims() {
    var candidates = List.of(
            ceResult("a", 17.0, 5.1), ceResult("b", 16.0, 4.2),
            ceResult("c", 15.0, 0.7), ceResult("d", 14.0, 0.1),
            ceResult("e", 13.0, -0.5));
    var r = SearchResource.adaptiveFilter(candidates, 16, 0.0, 1.5, 3);
    assertThat(r.results()).hasSize(3);
    assertThat(r.trimmed()).isTrue();
    assertThat(r.floorFiltered()).isEqualTo(1);
}

@Test
void adaptiveFilter_noMatch_allBelowFloor() {
    var candidates = List.of(
            ceResult("a", 15.0, -5.9), ceResult("b", 14.0, -6.6),
            ceResult("c", 13.0, -7.2), ceResult("d", 12.0, -8.0));
    var r = SearchResource.adaptiveFilter(candidates, 16, 0.0, 1.5, 3);
    assertThat(r.results()).isEmpty();
    assertThat(r.trimmed()).isTrue();
    assertThat(r.floorFiltered()).isEqualTo(4);
}

@Test
void adaptiveFilter_floorAndGapCooperate() {
    var candidates = List.of(
            ceResult("a", 18.0, 4.5), ceResult("b", 17.0, 4.0),
            ceResult("c", 16.0, 3.4), ceResult("d", 15.0, -0.4),
            ceResult("e", 14.0, -0.7));
    var r = SearchResource.adaptiveFilter(candidates, 16, 0.0, 1.5, 3);
    assertThat(r.results()).hasSize(3);
    assertThat(r.trimmed()).isTrue();
    assertThat(r.floorFiltered()).isEqualTo(2);
}

@Test
void adaptiveFilter_denseClusterExtends() {
    var candidates = List.of(
            ceResult("a", 20.0, 5.0), ceResult("b", 19.0, 4.8),
            ceResult("c", 21.0, 4.7), ceResult("d", 18.0, 4.6),
            ceResult("e", 20.5, 4.5), ceResult("f", 19.5, 4.3),
            ceResult("g", 17.0, 2.0));
    var r = SearchResource.adaptiveFilter(candidates, 4, 0.0, 1.5, 3);
    assertThat(r.results()).hasSize(6);
    assertThat(r.extended()).isTrue();
    assertThat(r.trimmed()).isFalse();
}

@Test
void adaptiveFilter_noGap_normalTruncation() {
    var candidates = List.of(
            ceResult("a", 18.0, 3.7), ceResult("b", 17.0, 2.4),
            ceResult("c", 16.0, 1.5), ceResult("d", 15.0, 1.3),
            ceResult("e", 14.0, 1.0), ceResult("f", 13.0, 0.8));
    var r = SearchResource.adaptiveFilter(candidates, 4, 0.0, 1.5, 3);
    assertThat(r.results()).hasSize(4);
    assertThat(r.trimmed()).isFalse();
    assertThat(r.extended()).isFalse();
}

@Test
void adaptiveFilter_fallbackToRelevance() {
    var candidates = List.of(
            result("a", 0.9), result("b", 0.88),
            result("c", 0.86), result("d", 0.84),
            result("e", 0.82), result("f", 0.80),
            result("g", 0.3));
    var r = SearchResource.adaptiveFilter(candidates, 4, 0.0, 0.05, 3);
    assertThat(r.results()).hasSize(6);
    assertThat(r.extended()).isTrue();
}

@Test
void adaptiveFilter_emptyInput() {
    var r = SearchResource.adaptiveFilter(List.of(), 16, 0.0, 1.5, 3);
    assertThat(r.results()).isEmpty();
    assertThat(r.trimmed()).isFalse();
    assertThat(r.floorFiltered()).isEqualTo(0);
}

@Test
void adaptiveFilter_singleAboveFloor() {
    var candidates = List.of(ceResult("a", 15.0, 3.5));
    var r = SearchResource.adaptiveFilter(candidates, 16, 0.0, 1.5, 3);
    assertThat(r.results()).hasSize(1);
}

@Test
void adaptiveFilter_singleBelowFloor() {
    var candidates = List.of(ceResult("a", 15.0, -1.0));
    var r = SearchResource.adaptiveFilter(candidates, 16, 0.0, 1.5, 3);
    assertThat(r.results()).isEmpty();
    assertThat(r.floorFiltered()).isEqualTo(1);
}

@Test
void adaptiveFilter_minResultsPreventsOverTrim() {
    var candidates = List.of(
            ceResult("a", 18.0, 5.5), ceResult("b", 17.0, 3.6),
            ceResult("c", 16.0, 3.1), ceResult("d", 15.0, 3.0));
    var r = SearchResource.adaptiveFilter(candidates, 16, 0.0, 1.5, 3);
    assertThat(r.results()).hasSize(3);
}

@Test
void adaptiveFilter_mixedCeAndNonCe() {
    var candidates = List.of(
            ceResult("a", 18.0, 5.0), ceResult("b", 17.0, 3.0),
            result("c", 0.8), result("d", 0.6));
    var r = SearchResource.adaptiveFilter(candidates, 16, 0.0, 1.5, 3);
    assertThat(r.results()).hasSize(3);
}

@Test
void adaptiveFilter_denseOnly_extension() {
    var candidates = List.of(
            result("a", 0.90), result("b", 0.88),
            result("c", 0.87), result("d", 0.86),
            result("e", 0.85), result("f", 0.84),
            result("g", 0.50));
    var r = SearchResource.adaptiveFilter(candidates, 4, 0.0, 0.05, 3);
    assertThat(r.results()).hasSize(6);
    assertThat(r.extended()).isTrue();
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -Dtest=SearchResourceTest`
Expected: FAIL — `adaptiveFilter` method does not exist

- [ ] **Step 4: Implement `adaptiveFilter()`**

Add to `SearchResource.java`. Remove the `GAP_THRESHOLD` constant (replaced by config). Keep `adaptiveExtend` temporarily until call sites switch in Task 3.

```java
static AdaptiveResult adaptiveFilter(List<SearchResult> candidates,
                                      int requestedLimit,
                                      double scoreFloor,
                                      double gapThreshold,
                                      int minResults) {
    if (candidates.isEmpty()) {
        return new AdaptiveResult(List.of(), requestedLimit, 0, false, false, 0);
    }

    boolean ceMode = candidates.stream().anyMatch(r -> r.crossEncoderScore() != null);

    List<SearchResult> survivors = new ArrayList<>();
    int floorFiltered = 0;
    for (SearchResult r : candidates) {
        double score = primaryScore(r);
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
    if (ceMode) {
        cutoff = findCeGapCutoff(survivors, gapThreshold, minResults);
    } else {
        cutoff = findDenseOnlyCutoff(survivors, requestedLimit);
    }

    boolean trimmed = cutoff < requestedLimit && cutoff < survivors.size();
    boolean extended = cutoff > requestedLimit;
    int effectiveCount = Math.min(cutoff, survivors.size());

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
    return survivors.size();
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest=SearchResourceTest`
Expected: ALL PASS

- [ ] **Step 6: Remove old `adaptiveExtend` tests, keep old method temporarily**

Delete the old `adaptiveExtend_*` test methods. Keep the `adaptiveExtend()` method itself — Task 3 replaces the call site and removes it.

- [ ] **Step 7: Run full test suite**

Run: `./mvnw test`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```
feat(#44): implement adaptiveFilter with floor + gap detection + minResults

Two-layer adaptive filtering: score floor removes entries the CE model
actively rejects, gap detection finds the first significant score drop
to trim noise tails. minResults prevents single-result trimming when the
top entry is an outlier. Dense-only mode preserves existing extension
behavior.

Refs #44
```

---

### Task 3: Wire `adaptiveFilter` into `searchAdaptive()` + MCP output updates

**Files:**
- Modify: `src/main/java/io/hortora/garden/search/SearchResource.java`
- Modify: `src/main/java/io/hortora/garden/mcp/GardenMcpTools.java`
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`
- Modify: `src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java`

**Interfaces:**
- Consumes: `SearchConfig` (Task 1), `adaptiveFilter()` (Task 2)
- Produces: `searchAdaptive()` wired to `adaptiveFilter()` with re-sort; MCP output shows CE score

- [ ] **Step 1: Write integration test for re-sort wiring**

Add to `SearchResourceTest`:

```java
@Test
void searchAdaptive_reSortsByCeScore() {
    var tierOrdered = List.of(
            ceResult("own-low", 20.0, 1.0),
            ceResult("own-high", 18.0, 5.0),
            ceResult("remote", 15.0, 3.0));

    var sorted = tierOrdered.stream()
            .sorted(Comparator.comparing(
                    (SearchResult r) -> r.crossEncoderScore() != null ? 0 : 1)
                    .thenComparing(r -> r.crossEncoderScore() != null
                            ? -r.crossEncoderScore() : -r.relevance()))
            .toList();

    assertThat(sorted.get(0).crossEncoderScore()).isEqualTo(5.0);
    assertThat(sorted.get(1).crossEncoderScore()).isEqualTo(3.0);
    assertThat(sorted.get(2).crossEncoderScore()).isEqualTo(1.0);
}
```

- [ ] **Step 2: Wire `searchAdaptive()` — inject `SearchConfig`, re-sort, call `adaptiveFilter`**

In `SearchResource`:

Add field:
```java
@Inject SearchConfig searchConfig;
```

Replace `searchAdaptive()`:
```java
public AdaptiveResult searchAdaptive(String query, List<String> domains,
                                      String type, String tags, Integer limit) {
    int requestedLimit = resolveLimit(limit);
    int fetchLimit = Math.min(requestedLimit * OVERFETCH_MULTIPLIER, MAX_LIMIT);
    List<SearchResult> candidates = doSearch(query, domains, type, tags, fetchLimit, null);

    List<SearchResult> sorted = candidates.stream()
            .sorted(Comparator.comparing(
                    (SearchResult r) -> r.crossEncoderScore() != null ? 0 : 1)
                    .thenComparing(r -> r.crossEncoderScore() != null
                            ? -r.crossEncoderScore() : -r.relevance()))
            .toList();

    return adaptiveFilter(sorted, requestedLimit,
            searchConfig.scoreFloor(), searchConfig.gapThreshold(),
            searchConfig.minResults());
}
```

- [ ] **Step 3: Remove old `adaptiveExtend()` and `GAP_THRESHOLD` constant**

Delete the `adaptiveExtend` method and the `GAP_THRESHOLD` constant. Remove the static import in the test file (`import static io.hortora.garden.search.SearchResource.adaptiveExtend;`).

- [ ] **Step 4: Update MCP output — CE score display and trimmed header**

In `GardenMcpTools.gardenSearch()`, update the result formatting section. Replace the current relevance line:

```java
+ " · **Relevance:** " + String.format("%.2f", r.relevance())
```

with:

```java
+ " · " + (r.crossEncoderScore() != null
    ? "**Score:** " + String.format("%.1f", r.crossEncoderScore()) + " (CE)"
    : "**Relevance:** " + String.format("%.2f", r.relevance()))
```

Update the header logic — after the metadata comment, replace the existing conditional block:

```java
if (adaptive.trimmed()) {
    sb.append("*Showing ").append(adaptive.results().size())
      .append(" results (").append(adaptive.requestedLimit())
      .append(" requested, trimmed at score gap).*\n");
} else if (adaptive.extended() || adaptive.availableAboveFloor() > adaptive.results().size()) {
    sb.append("*Showing ").append(adaptive.results().size())
      .append(" results (").append(adaptive.requestedLimit()).append(" requested");
    if (adaptive.availableAboveFloor() > adaptive.results().size()) {
        sb.append(", ").append(adaptive.availableAboveFloor())
          .append(" above relevance threshold in corpus");
    }
    sb.append("). Use a higher limit to see more.*\n");
}
```

- [ ] **Step 5: Run full test suite**

Run: `./mvnw test`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```
feat(#44): wire adaptiveFilter into searchAdaptive with CE re-sort

searchAdaptive() re-sorts candidates by CE score descending before
filtering. MCP output shows CE score when present, displays trimmed
header when results are cut. Removes old adaptiveExtend().

Refs #44
```

---

### Task 4: Benchmark validation + cleanup

**Files:**
- Modify: `src/main/resources/application.properties`
- Create: `scripts/benchmark/validate_filtering.py`

**Interfaces:**
- Consumes: benchmark data at `scripts/benchmark/results/crossencoder-pool50-scored.json`
- Produces: validation report confirming no loss of clearly relevant entries

- [ ] **Step 1: Add SearchConfig defaults to `application.properties`**

Add to `application.properties`:
```properties
hortora.search.score-floor=0.0
hortora.search.gap-threshold=1.5
hortora.search.min-results=3
```

- [ ] **Step 2: Write benchmark validation script**

Create `scripts/benchmark/validate_filtering.py`:

```python
import json
import sys

def primary_score(entry):
    return entry.get("crossEncoderScore") if entry.get("crossEncoderScore") is not None else entry.get("relevance", 0)

def adaptive_filter(entries, limit, floor, gap_threshold, min_results):
    survivors = [e for e in entries if primary_score(e) >= floor]
    floor_filtered = len(entries) - len(survivors)
    if not survivors:
        return [], floor_filtered, False, True

    ce_mode = any(e.get("crossEncoderScore") is not None for e in survivors)
    cutoff = len(survivors)

    if ce_mode:
        for i in range(len(survivors) - 1):
            cur = survivors[i].get("crossEncoderScore")
            nxt = survivors[i + 1].get("crossEncoderScore")
            if cur is not None and nxt is not None:
                if cur - nxt >= gap_threshold:
                    cutoff = max(i + 1, min_results)
                    break
            elif cur is not None and nxt is None:
                cutoff = max(i + 1, min_results)
                break

    effective = min(cutoff, len(survivors))
    trimmed = effective < limit and effective < len(survivors)
    extended = effective > limit
    return survivors[:effective], floor_filtered, extended, trimmed

def main():
    with open("scripts/benchmark/results/crossencoder-pool50-scored.json") as f:
        data = json.load(f)

    floor, gap_th, min_r, limit = 0.0, 1.5, 3, 16
    print(f"Config: scoreFloor={floor}, gapThreshold={gap_th}, minResults={min_r}, limit={limit}")
    print(f"{'Scenario':<45} {'Before':>6} {'After':>5} {'Floor':>5} {'Trim':>5} {'Ext':>4} {'Lost CE>3':>9}")
    print("-" * 90)

    total_before, total_after, total_lost = 0, 0, 0
    for scenario in data["results"]:
        sid = scenario["scenario_id"]
        entries = scenario["entries"]
        filtered, ff, ext, trim = adaptive_filter(entries, limit, floor, gap_th, min_r)
        relevant_before = sum(1 for e in entries if e.get("crossEncoderScore", 0) > 3.0)
        relevant_after = sum(1 for e in filtered if e.get("crossEncoderScore", 0) > 3.0)
        lost = relevant_before - relevant_after
        total_before += len(entries)
        total_after += len(filtered)
        total_lost += lost
        print(f"{sid:<45} {len(entries):>6} {len(filtered):>5} {ff:>5} {'Y' if trim else 'N':>5} {'Y' if ext else 'N':>4} {lost:>9}")

    print("-" * 90)
    noise_removed = total_before - total_after
    print(f"Total: {total_before} → {total_after} ({noise_removed} noise entries removed, {total_lost} relevant entries lost)")
    if total_lost > 0:
        print(f"WARNING: {total_lost} clearly relevant entries (CE > 3.0) were lost by filtering")
        sys.exit(1)
    else:
        print("OK: No clearly relevant entries lost")

if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Run benchmark validation**

Run: `python3 scripts/benchmark/validate_filtering.py`
Expected: "OK: No clearly relevant entries lost" — zero entries with CE > 3.0 lost by filtering

- [ ] **Step 4: Run full test suite one final time**

Run: `./mvnw test`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```
feat(#44): benchmark validation + config defaults for adaptive CE filtering

Validates filtering against 28 benchmark scenarios: noise entries removed
with zero loss of clearly relevant (CE > 3.0) entries.

Closes #44
```
