# Cross-Store Retrieval Modulation — Generic RetrievalModulator

**Issue:** casehubio/neocortex#233
**Modules:** `cognitive-api` (framework types), `cognitive-index` (pre-built factors + profiles)
**Packages:** `io.casehub.neocortex.cognitive` (framework), `io.casehub.neocortex.cognitive.index` (instances)

## Problem

The current retrieval modulation utilities (`MoodModulatedRetrieval`,
`PersonalityWeightedRetrieval`) are Memory-only. They duplicate the same
recency decay formula and can't be applied to MindMapNode or ScoredCbrCase
results. The cognitive architecture needs a generic modulation layer that
works across all store types with composable scoring factors.

## Design

### Core Principle: Composable Factors over Accessor Profiles

Modulation is post-retrieval re-ranking. A `ModulationProfile<T>` describes
how to extract fields from result type T (confidence, PAD, timestamp) via
lambda accessors. Individual `ModulationFactor<T>` implementations each
produce a double multiplier. Factors compose via multiplication — the
composite score is the product of all factor scores for each item.

This is post-retrieval only. CBR store decorators (trust weighting, outcome
weighting, scope decay) remain unchanged — they handle store-specific
scoring inside the retrieval pipeline. The modulator adds cross-cutting
cognitive factors (mood, personality, recency) on top.

### Framework Types (cognitive-api, zero deps)

**ModulationProfile<T>** — accessor record for extracting fields:

```java
public record ModulationProfile<T>(
    Function<T, Confidence> confidence,
    Function<T, Double> pleasure,
    Function<T, Double> arousal,
    Function<T, Double> dominance,
    Function<T, Instant> timestamp
) {}
```

**ModulationFactor<T>** — single scoring factor:

```java
@FunctionalInterface
public interface ModulationFactor<T> {
    double apply(T item, ModulationProfile<T> profile);
}
```

**RetrievalModulator** — static utility:

```java
public final class RetrievalModulator {
    public static <T> List<T> modulate(
        List<T> items, ModulationProfile<T> profile,
        List<ModulationFactor<T>> factors);
}
```

Applies all factors to each item (multiplicative composition), sorts
descending by composite score. Empty items or empty factors return the
input unchanged.

### Pre-Built Instances (cognitive-index)

**ModulationProfiles** — constants:

```java
public final class ModulationProfiles {
    public static final ModulationProfile<Memory> MEMORY = ...;
    public static final ModulationProfile<MindMapNode> NODE = ...;
}
```

`MEMORY` extracts via `Memory::confidence`, `Memory::pleasure`, etc.
`NODE` extracts via `MindMapNode::confidence`, `MindMapNode::pleasure`, etc.

No CBR profile is needed — ScoredCbrCase lacks PAD and its confidence
is on the wrapped CbrCase. CBR results are already scored by the store's
decorator stack. If CBR modulation is needed in the future, a profile
can be added without changing the framework.

**ModulationFactors** — factory methods:

| Factory | Type | Parameters | Formula |
|---------|------|-----------|---------|
| `recencyDecay` | `<T>` | `Duration halfLife, Instant now` | `exp(-hoursElapsed / halfLifeHours)`. Null timestamp returns 0.5. |
| `confidenceWeight` | `<T>` | none | `confidence.value()`. Null confidence returns 1.0. |
| `moodCongruence` | `<T>` | `MoodState mood, double influence` | `1.0 + influence * (alignment - 0.5)` where alignment = `1 - padDistance / sqrt(12)`. No PAD on item returns 1.0. Influence must be in [0, 1]. |
| `domainWeight` | `Memory` | `PersonalityWeights weights` | `weights.getWeight(domain)`. Returns `ModulationFactor<Memory>` (not generic — domain is Memory-specific). |

All formulas are preserved from the existing utilities. The 7-day
half-life becomes a parameter (`Duration.ofDays(7)`) rather than a
hardcoded constant.

**ModulationContext** — convenience record:

```java
public record ModulationContext(
    MoodState currentMood,
    PersonalityWeights personality,
    Instant now
) {
    public ModulationContext {
        Objects.requireNonNull(now, "now");
    }
}
```

Optional convenience — callers can construct factors directly without
a context object. Provides `of(Instant now)`, `withMood(MoodState)`,
`withPersonality(PersonalityWeights)` builders.

### Cleanup

Delete from memory-api (zero production callers):
- `MoodModulatedRetrieval` + `MoodModulatedRetrievalTest`
- `PersonalityWeightedRetrieval` + `PersonalityWeightedRetrievalTest`

`PersonalityWeights`, `MoodState`, `MoodAttributeKeys` stay in
memory-api — they are data types, not modulation utilities.

### What This Issue Does NOT Cover

- **CBR modulation** — CBR results are already scored by the decorator
  stack. No CBR profile or CBR-specific factors. Can be added later.
- **Memory space awareness** — the modulator operates on a flat list
  of results. Space-aware retrieval (querying across multiple tenant
  IDs) is the caller's responsibility. The modulator is space-agnostic.
- **Filtering** — factors can only re-weight, not remove items. If
  threshold-based filtering is needed, it's a separate concern.
- **Store-internal changes** — no decorator changes, no SPI changes.

### Test Plan

**cognitive-api (framework, 4 tests):**
1. modulate with empty list returns empty
2. modulate sorts by composite score (2 factors, 3 items)
3. Single factor — items sorted by that factor alone
4. Neutral factor (always 1.0) doesn't change relative ordering

**cognitive-index (factors, 9 tests):**
5. recencyDecay — recent items score higher
6. recencyDecay — null timestamp returns 0.5
7. confidenceWeight — higher confidence scores higher
8. confidenceWeight — null confidence returns 1.0
9. moodCongruence — PAD-aligned items score higher
10. moodCongruence — no PAD on item returns 1.0
11. moodCongruence — influence=0.0 returns 1.0
12. domainWeight — weighted domain scores higher
13. domainWeight — unweighted domain defaults to 1.0

**cognitive-index (profiles, 2 tests):**
14. MEMORY profile extracts correctly from Memory
15. NODE profile extracts correctly from MindMapNode

**cognitive-index (integration, 2 tests):**
16. Full pipeline (recency + confidence + mood) on Memory list matches
    old MoodModulatedRetrieval output
17. Same pipeline on MindMapNode list

## Decisions

D51-D54 in `decisions.md`. Key choices:
- Post-retrieval only — CBR decorators unchanged (D51)
- Accessor functions via ModulationProfile<T> (D52)
- Composable factors via multiplication (D53)
- Framework in cognitive-api, instances in cognitive-index (D54)

## References

- `memory-api/.../MoodModulatedRetrieval.java` — existing mood utility (replaced)
- `memory-api/.../PersonalityWeightedRetrieval.java` — existing personality utility (replaced)
- `memory-api/.../TrustWeightingFunction.java` — CBR trust pattern (not replaced)
- `memory-api/.../Memory.java` — Memory record fields
- `mindmap-api/.../MindMapNode.java` — MindMapNode interface fields
- `memory-api/.../ScoredCbrCase.java` — CBR result wrapper
- `memory-api/.../CbrCase.java` — CbrCase interface (confidence, trustScore)
- `cognitive-api/.../Confidence.java` — shared confidence type
- `cognitive-architecture-roadmap.md` §1c — roadmap definition
- GitHub #233 — focal issue
- GitHub #253 — parent branch issue
