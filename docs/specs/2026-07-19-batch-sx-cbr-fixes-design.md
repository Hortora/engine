# Batch S/XS CBR Fixes — Design Spec

**Date:** 2026-07-19
**Branch:** issue-130-batch-sx-cbr-fixes
**Covers:** #130, #143, #128, #162, #141, #155

Six small issues batched on one branch. All feed back into CaseHub foundation and apps.

---

## #130 — Examples aggregator POM

**Problem:** `casehubio/examples` lists each neocortex example individually. Other repos (work, qhorus, eidos) already have aggregator POMs so the parent can reference a single `<module>`.

**Design:** Create `examples/pom.xml` — aggregator-only, no dependencies, no plugins. Parent = `casehub-neocortex-parent`, packaging = `pom`, three module entries: `example-text-analysis`, `example-rag-pipeline`, `example-cbr`.

**Follow-up:** File issue on `casehubio/examples` to collapse three neocortex entries into one.

---

## #162 — InMemoryCbrCaseMemoryStore.clearCases()

**Root cause:** `InMemoryCbrCaseMemoryStore` is `@ApplicationScoped` — persists across `@QuarkusTest` methods. No way to reset state between tests. `purge()` requires `maxAgeDays >= 1` / `maxCasesPerType >= 1` — can't clear fresh data. Documented in GE-20260716-986cd1.

**Note:** The specific empty-results symptom reported in #162 (store then retrieve returns empty in a single test) may have additional causes beyond test isolation (parameter ordering, schema registration timing). `clearCases()` establishes the prerequisite for reliable testing; the specific retrieval failure should be investigated once test isolation is in place.

**Design:** Add `clearCases()` to the concrete `InMemoryCbrCaseMemoryStore` and `ReactiveInMemoryCbrCaseMemoryStore` — not to the SPI. Test isolation is a test concern. Clears the `cases` list only — schemas are configuration registered at `@Startup` and must survive between test methods.

**Contract test hook:** Add `protected void clearStore()` to `CbrCaseMemoryStoreContractTest` with a no-op default. In-memory test subclasses override it to call `clearCases()`. Call in `@BeforeEach` to ensure test isolation.

---

## #141 — memory-cbr-jpa Map→FeatureValue cleanup

**Problem:** `JpaCbrCaseMemoryStore.deserializeMap()` returns `Map<String, Object>`, then `reconstruct()` round-trips through `FeatureValue.toFeatureMap()`. The intermediate raw map is unnecessary — type information is lost and re-inferred.

**Note:** The compilation errors described in #141 have already been resolved in the current codebase — `reconstruct()` already calls `FeatureValue.toFeatureMap(deserializeMap(...))` and `matchesSingleFilter()` already takes `FeatureValue`. This is a cleanup that internalises the conversion for a cleaner API boundary, not a compilation fix.

**Design:** Rename `deserializeMap` → `deserializeFeatures`. Return `Map<String, FeatureValue>` by applying `FeatureValue.toFeatureMap()` internally. Remove the `MAP_TYPE` field. Update `reconstruct()` to use the clean return type directly. Store path stays unchanged (`toRawMap` → JSON is correct for JSONB column compatibility).

---

## #143 — Configurable learning rate per case type

**Problem:** `CbrOutcome.DEFAULT_LEARNING_RATE = 0.2` is global. Different domains need different speeds: AML (conservative, low rate) vs game strategy (fast-moving, high rate).

**Design:** Add `Double learningRate()` to `CbrFeatureSchema`. Nullable — `null` means use `CbrOutcome.DEFAULT_LEARNING_RATE`. Schema is already per-caseType and already available in all store implementations at `recordOutcome` time.

Changes:
- `CbrFeatureSchema`: add `learningRate` field, validate in [0,1] when non-null
- `InMemoryCbrCaseMemoryStore.recordOutcome()`: look up schema, use `schema.learningRate()` if present
- `JpaCbrCaseMemoryStore.recordOutcome()`: same
- `QdrantCbrCaseMemoryStore.recordOutcome()` (reactive): same
- Contract test: add test with custom learning rate verifying EMA convergence speed differs

`CbrFeatureSchema` is a record. Adding a field is a source-breaking change (constructor call sites). Pre-release — breaking changes cost nothing. All existing callers add the new parameter. Provide a static factory that defaults learningRate to null for convenience.

---

## #128 — Graded similarity scoring for structured fields

**Problem:** `CbrSimilarityScorer.scoreDetailed()` hard-skips `CategoricalList`, `NumericList`, `NestedObject`, and `ObjectList` fields before checking caller-provided `LocalSimilarityFunction` overrides. This means overrides for structured fields are silently ignored.

**Design:** Move the structured-field skip to after the override lookup. If a caller provides a `LocalSimilarityFunction` override for a structured field, use it. If no override, skip the field (same as today — default is 0 contribution, not 0 similarity). This unblocks domain-specific scoring without building generalised list/object similarity.

The change is minimal: in `scoreDetailed()`, check for override before the `instanceof` skip. Same change in both overloads (with and without `dtwAbandonCostThreshold`).

After this change, the structured-field `throw` cases in `localSimilarity()` become unreachable dead code (structured fields only reach `localSimilarity` when an override exists, and overrides short-circuit before the switch). Change these to `return 0.0` — the cases must remain for exhaustive sealed-type matching, and 0.0 is semantically consistent with "no default scoring" while degrading gracefully if control flow changes in the future.

**Deferred:** Issue #128 also requests documentation of `LocalSimilarityFunction` patterns and consideration of built-in defaults (Jaccard for CategoricalList, recursive weighted for NestedObject). These are deferred to follow-up issue #169.

---

## #155 — Supersession status + audit metadata SPI

**Problem:** `supersede()` and `reinstate()` write metadata but it's unreachable through the SPI. Audit consumers must bypass the SPI and query storage directly.

**Design:** Two new abstract methods on `CbrCaseMemoryStore` + `ReactiveCbrCaseMemoryStore`:

```java
SupersessionStatus getSupersessionStatus(String caseId, String tenantId);
List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain);
```

Abstract methods preserve compile-time safety for the decorator chain. All existing methods on both interfaces are abstract — every decorator manually overrides every method for delegation. Adding default methods would let a decorator silently compile without delegation, returning `NOT_SUPERSEDED` / empty list instead of forwarding to the wrapped store. The 23 test stub updates are mechanical one-liners; the compile-time guarantee that all 14 decorators have correct delegation is permanent.

New value type in `memory-api`:

```java
public record SupersessionStatus(
    String caseId,
    boolean superseded,
    Instant supersededAt,
    String supersedingCaseId,
    String reason,
    Instant reinstatedAt
) {
    public static final SupersessionStatus NOT_SUPERSEDED =
        new SupersessionStatus(null, false, null, null, null, null);

    public boolean wasReinstated() { return reinstatedAt != null; }
}
```

`caseId` is included so that `findSupersededCases` results are self-describing (no N+1 correlation needed). `reinstatedAt` preserves the audit distinction between "never superseded" and "was superseded then reinstated" — after `reinstate()`, supersession fields are nulled but `reinstatedAt` records when reinstatement occurred. `supersede()` nulls `reinstatedAt` to prevent ambiguous state — a re-superseded case shows `superseded=true, reinstatedAt=null`, not a stale reinstatement timestamp from a previous lifecycle.

`findSupersededCases` returns `List<SupersessionStatus>` (not `List<String>`) to avoid the N+1 query pattern — a single call returns all supersession details. This is an administrative/audit operation that intentionally operates across all scope boundaries for the given tenant and domain, consistent with `purge()`. Scope-aware visibility is a retrieval concern (`retrieveSimilar`), not an audit concern.

Backend implementations:
- **InMemory:** scan `StoredCase` fields. Add `reinstatedAt` field to `StoredCase`. `reinstate()` sets `reinstatedAt = now` while nulling supersession fields. `supersede()` nulls `reinstatedAt`.
- **JPA:** JPQL query on `CbrCaseEntity`. Add `reinstated_at` column. `reinstate()` sets `reinstatedAt`, `supersede()` nulls it.
- **Qdrant:** payload filter query (supersededAt IS NOT NULL). Add `reinstatedAt` payload field. Same lifecycle semantics.
- **NoOp:** return `NOT_SUPERSEDED` / empty list.

**Blast radius — neocortex-internal:**

Blocking decorators (7 — delegation override): `TemporalDecayCbrCaseMemoryStore`, `ScopeDecayCbrCaseMemoryStore`, `OutcomeWeightingCbrCaseMemoryStore`, `TrendEnrichmentCbrCaseMemoryStore`, `TrackingCbrCaseMemoryStore`, `ErasureNotificationCbrCaseMemoryStore`, `RerankingCbrCaseMemoryStore`.

Reactive decorators (7 — delegation override): `ReactiveTemporalDecayCbrCaseMemoryStore`, `ReactiveScopeDecayCbrCaseMemoryStore`, `ReactiveOutcomeWeightingCbrCaseMemoryStore`, `ReactiveTrendEnrichmentCbrCaseMemoryStore`, `ReactiveTrackingCbrCaseMemoryStore`, `ReactiveRerankingCbrCaseMemoryStore`, `BlockingToReactiveCbrBridge` (Uni wrapping).

Test stubs (~20 in neocortex, ~3 in engine): add one-liner no-op returns (`NOT_SUPERSEDED` / empty list). Mechanical, compile-time enforced.

**Blast radius — cross-repo:** Abstract methods mean downstream test stubs (engine, IoT, quarkmind, etc.) must also add the two new methods. This is consistent with #143's constructor breakage — all cross-repo breakage is compile-time caught, mechanical to fix, and pre-release.

Contract test: add tests for both methods — verify status after supersede, after reinstate (checking `reinstatedAt` is set), and that findSupersededCases filters correctly and returns full status details.

---

## Dependency Order

No inter-issue dependencies. All can be implemented in any order. Natural grouping:

1. **#130** (examples POM) — standalone, zero code impact
2. **#143** (learning rate) — changes `CbrFeatureSchema` record; do this early since #128 and #155 also touch the scorer/store
3. **#128** (structured field overrides) — changes `CbrSimilarityScorer`
4. **#162** (clearCases) — changes `InMemoryCbrCaseMemoryStore`
5. **#141** (JPA deserialize) — changes `JpaCbrCaseMemoryStore`
6. **#155** (supersession queries) — adds SPI methods; do last since it touches every backend

---

## Cross-repo ripple

- **casehubio/examples:** follow-up issue to collapse neocortex entries (#130)
- **All CBR consumers (engine, blocks, IoT, quarkmind, life, clinical):** `CbrFeatureSchema` constructor changes (#143) — source-breaking, compile-time caught. Pre-release, no backward compat required.
- **#155 SPI additions:** abstract methods on the interface cause compile-time failures in downstream test stubs that implement `CbrCaseMemoryStore`. Fix is mechanical (one-liner no-op returns). Combined with #143, all cross-repo breakage is compile-time caught and pre-release.
- **Parent docs:** `docs/repos/casehub-neocortex.md` update for new SPI methods (#155)
