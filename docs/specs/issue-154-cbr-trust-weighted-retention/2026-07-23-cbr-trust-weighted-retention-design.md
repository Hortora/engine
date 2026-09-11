# CBR Trust-Weighted Retrieval Scoring — Design Spec

**Issue:** casehubio/neocortex#154
**Date:** 2026-07-23
**Status:** Draft

## Problem

CBR retrieval scores cases by feature similarity, outcome confidence, and cross-encoder
reranking — but ignores the authority of the source that produced the case. A case
created by a highly trusted agent should carry more weight than one from an untrusted
agent, and an agent whose trust has been declining since the decision should produce
less authoritative precedents.

The ledger already computes per-agent, per-capability trust scores (`TrustScoreSource`
SPI in `casehub-ledger-api`), consumed by engine routing strategies. CBR needs to
bridge this signal into retrieval scoring without creating a direct dependency on ledger.

## Design

### Model Changes (memory-api)

**CbrCase interface** — two new methods with null defaults:

```java
default Double trustScore() { return null; }
default String producerAgentId() { return null; }
```

`trustScore` is the source authority at decision time — a snapshot of the producing
agent's trust when the case was stored. Immutable after creation. Nullable (unknown
trust is valid — the decorator skips weighting for null). Validated in [0,1] when
non-null — same constraint as `origin`.

`producerAgentId` is intrinsic provenance — identifies who produced this case, for
trajectory lookup at retrieval time. Nullable (trajectory is skipped without it).

Both fields are intrinsic to the case's creation context. `trustScore` captures
decision-time authority. `producerAgentId` captures authorship — it identifies who
made the decision, not how the case was stored. Unlike `storedAt` and `scope` (which
are assigned by the storage layer at `store()` time and naturally live on
`ScoredCbrCase`), both trust fields originate from the case producer and travel with
the case through the entire lifecycle. Placing them on `CbrCase` means they are
available at store time, at retrieval time, and during adaptation — without requiring
backends to decompose and reconstruct them.

**FeatureVectorCbrCase:**

```java
public record FeatureVectorCbrCase(
    String problem, String solution,
    String outcome, Double confidence,
    Map<String, FeatureValue> features,
    Double trustScore, String producerAgentId
) implements CbrCase { ... }
```

`withOutcome()` and `withFeatures()` preserve both fields.

**PlanCbrCase:**

```java
public record PlanCbrCase(
    String problem, String solution,
    String outcome, Double confidence,
    Map<String, FeatureValue> features,
    List<PlanTrace> planTrace,
    Double trustScore, String producerAgentId
) implements CbrCase { ... }
```

**TextualCbrCase:**

```java
public record TextualCbrCase(
    String problem, String solution,
    String outcome, Double confidence,
    Double trustScore, String producerAgentId
) implements CbrCase { ... }
```

### AgentTrustProvider SPI (memory-api)

```java
package io.casehub.neocortex.memory.cbr;

import java.util.OptionalDouble;

@FunctionalInterface
public interface AgentTrustProvider {
    OptionalDouble currentTrustScore(String agentId);
}
```

Provides the agent's current trust score for trajectory computation. Implementations
bridge to the ledger's `TrustScoreSource.globalScore(actorId)` or any other trust
source.

No `@DefaultBean` no-op — the decorator injects `Instance<AgentTrustProvider>` and
checks `isResolvable()`. If absent, trajectory is disabled (authority-only mode). A
no-op would mask the absence.

**Reactive counterpart (memory-api):**

```java
package io.casehub.neocortex.memory.cbr;

import io.smallrye.mutiny.Uni;

@FunctionalInterface
public interface ReactiveAgentTrustProvider {
    Uni<OptionalDouble> currentTrustScore(String agentId);
}
```

The reactive SPI returns `Uni<OptionalDouble>` so the reactive decorator can
compose trust lookups without blocking the event loop. Implementations that
wrap a blocking `TrustScoreSource` use `Uni.createFrom().item(...).runSubscriptionOn(executor)`.
If no `ReactiveAgentTrustProvider` is resolvable, the reactive decorator falls back to
authority-only mode (same as the imperative decorator without `AgentTrustProvider`).

**Blocking-to-reactive bridge (memory module):**

```java
@DefaultBean
@ApplicationScoped
public class BlockingAgentTrustProviderBridge implements ReactiveAgentTrustProvider {

    private final AgentTrustProvider delegate;

    @Inject
    BlockingAgentTrustProviderBridge(Instance<AgentTrustProvider> delegateInstance) {
        this.delegate = delegateInstance.isResolvable() ? delegateInstance.get() : null;
    }

    @Override
    public Uni<OptionalDouble> currentTrustScore(String agentId) {
        if (delegate == null) return Uni.createFrom().item(OptionalDouble.empty());
        return Uni.createFrom().item(() -> delegate.currentTrustScore(agentId))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
```

This follows the established `BlockingToReactive*Bridge` pattern used throughout
neocortex (`BlockingToReactiveCbrBridge`, `BlockingToReactiveCbrRetrievalTracker`,
`BlockingToReactiveEmbeddingIngestor`, etc.). Consumers provide a single blocking
`AgentTrustProvider` bean and both imperative and reactive decorators get trajectory
support. If a consumer provides a real `ReactiveAgentTrustProvider`, it takes
precedence over this `@DefaultBean` bridge.

### TrustWeightingFunction (memory-api)

```java
package io.casehub.neocortex.memory.cbr;

import java.util.OptionalDouble;

@FunctionalInterface
public interface TrustWeightingFunction {
    double apply(double similarity, double trustScore, OptionalDouble trustTrajectory);
}
```

Analogous to `OutcomeWeightingFunction(double similarity, double confidence)`. The
trajectory is the delta between stored trust and current trust (negative = declining).

### DefaultTrustWeightingFunction (memory module, @DefaultBean)

```java
@DefaultBean
@ApplicationScoped
public class DefaultTrustWeightingFunction implements TrustWeightingFunction {

    private final double influence;
    private final double trajectorySensitivity;

    @Inject
    DefaultTrustWeightingFunction(TrustWeightingConfig config) {
        this.influence = config.influence();
        this.trajectorySensitivity = config.trajectorySensitivity();
    }

    @Override
    public double apply(double similarity, double trustScore,
                        OptionalDouble trustTrajectory) {
        double weighted = similarity * (1.0 - influence + influence * trustScore);
        if (trustTrajectory.isPresent()) {
            double delta = trustTrajectory.getAsDouble();
            if (delta < 0) {
                weighted *= Math.max(0.5, 1.0 + trajectorySensitivity * delta);
            }
        }
        return weighted;
    }
}
```

**Authority formula:** `score * (1 - α + α * trustScore)` — same linear interpolation
as `DefaultOutcomeWeightingFunction`. With α=0.3 and trustScore=0.8: score * 0.94.
With trustScore=0.2: score * 0.76.

**Trajectory formula:** only applied when delta is negative (declining trust). Multiplier:
`max(0.5, 1.0 + β * delta)` where β=0.5 (default). A -0.4 trust decline → multiplier
0.8. Floor at 0.5 prevents complete zeroing.

Improving trust (positive delta) is ignored — the stored snapshot already captured the
authority at decision time. If the agent improved since then, that doesn't retroactively
make this specific decision better. The case's outcome quality is a fact about what
happened, not about the agent's current capability. The asymmetry is intentional:
declining trust is evidence that the agent was *overrated* when the case was created
(the decision may have been made with unwarranted authority), while improving trust
says nothing about whether this *particular* decision was good — it means the agent
made better decisions *elsewhere* since then.

**Multiplicative interaction with outcome weighting:** Both decorators apply independent
score multipliers. Because TrustWeighting@60 processes results after OutcomeWeighting@65
(lower priority = outermost in the decorator chain), the two multiply:

```
finalScore = rawScore
    * (1 - α_outcome + α_outcome * confidence)
    * (1 - α_trust + α_trust * trustScore)
    * max(0.5, 1.0 + β * trajectory)
```

Combined range with default parameters (α_outcome=0.3, α_trust=0.3, β=0.5):

| Scenario | Outcome factor | Trust factor | Trajectory | Combined |
|----------|---------------|--------------|------------|----------|
| Best case (conf=1, trust=1, rising) | 1.0 | 1.0 | 1.0 | score * 1.0 |
| Neutral (conf=0.5, trust=0.5, flat) | 0.85 | 0.85 | 1.0 | score * 0.72 |
| Worst case (conf=0, trust=0, -1.0) | 0.7 | 0.7 | 0.5 | score * 0.245 |

The ~25% floor in the worst case is intentional — it demotes but does not suppress.
Cases with zero confidence AND zero trust AND maximum decline are extreme outliers;
a 4x penalty relative to best-case is appropriate. No combined floor is needed beyond
the individual floors already in place. Operators who want less aggressive interaction
can reduce α values (lower influence = narrower range).

### TrustWeightingConfig (memory module)

```java
@ConfigMapping(prefix = "casehub.cbr.trust-weighting")
public interface TrustWeightingConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("0.3")
    double influence();

    @WithDefault("0.5")
    double trajectorySensitivity();
}
```

### ScoredCbrCase changes (memory-api)

`ScoredCbrCase` gains a `Double trustTrajectory` field — the numeric delta between
current and stored trust (negative = declining). Nullable; null when trust data is
absent or trajectory could not be computed.

```java
public record ScoredCbrCase<C extends CbrCase>(
    C cbrCase, String caseId, double score, boolean reranked,
    Map<String, Double> featureSimilarities, Instant storedAt,
    io.casehub.platform.api.path.Path scope, Double trustTrajectory) {
    ...
    public ScoredCbrCase<C> withTrustTrajectory(Double delta) {
        return new ScoredCbrCase<>(cbrCase, caseId, score, reranked,
                featureSimilarities, storedAt, scope, delta);
    }
}
```

This follows the precedent of `boolean reranked` and `withReranked()` —
decorators attach metadata to `ScoredCbrCase` so outer decorators (specifically
the Tracking decorator at @Priority(50)) can observe it. The numeric delta is
stored, not a direction label — `ScoredCbrCase` stores raw values consistently
(`double score`, `boolean reranked`, `Double confidence`). Direction
categorization is a presentation concern handled at `TracedCase` construction.

Existing constructors that don't set `trustTrajectory` pass `null`.

### TrustWeightedCbrCaseMemoryStore (memory module)

```java
@Decorator
@Priority(60)
@IfBuildProperty(name = "casehub.cbr.trust-weighting.enabled", stringValue = "true")
public class TrustWeightedCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private final CbrCaseMemoryStore delegate;
    private final TrustWeightingFunction weightingFunction;
    private final AgentTrustProvider trustProvider;

    @Inject
    TrustWeightedCbrCaseMemoryStore(
            @Delegate @Any CbrCaseMemoryStore delegate,
            TrustWeightingFunction weightingFunction,
            Instance<AgentTrustProvider> trustProviderInstance) {
        this.delegate = delegate;
        this.weightingFunction = weightingFunction;
        this.trustProvider = trustProviderInstance.isResolvable()
                ? trustProviderInstance.get() : null;
    }
```

**retrieveSimilar():**

```java
@Override
public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
        CbrQuery query, Class<C> caseClass) {
    List<ScoredCbrCase<C>> results = delegate.retrieveSimilar(query, caseClass);
    if (results.isEmpty()) return results;

    Map<String, OptionalDouble> trajectoryCache = new HashMap<>();
    List<ScoredCbrCase<C>> weighted = new ArrayList<>(results.size());
    for (ScoredCbrCase<C> scored : results) {
        Double trust = scored.cbrCase().trustScore();
        if (trust == null) {
            weighted.add(scored);
            continue;
        }
        OptionalDouble trajectory = computeTrajectory(scored.cbrCase(), trajectoryCache);
        double newScore = weightingFunction.apply(scored.score(), trust, trajectory);
        Double delta = trajectory.isPresent() ? trajectory.getAsDouble() : null;
        weighted.add(scored.withScore(newScore).withTrustTrajectory(delta));
    }
    weighted.sort((a, b) -> Double.compare(b.score(), a.score()));
    return Collections.unmodifiableList(weighted);
}

private OptionalDouble computeTrajectory(CbrCase cbrCase,
        Map<String, OptionalDouble> cache) {
    if (trustProvider == null || cbrCase.producerAgentId() == null
            || cbrCase.trustScore() == null) {
        return OptionalDouble.empty();
    }
    String agentId = cbrCase.producerAgentId();
    OptionalDouble current = cache.computeIfAbsent(agentId,
            id -> trustProvider.currentTrustScore(id));
    if (current.isEmpty()) return OptionalDouble.empty();
    return OptionalDouble.of(current.getAsDouble() - cbrCase.trustScore());
}
```

The `trajectoryCache` is a local `Map<String, OptionalDouble>` scoped to a single
`retrieveSimilar()` invocation. It eliminates redundant `AgentTrustProvider` lookups
when multiple cases share the same `producerAgentId` — common when an agent retains
many cases. The cache is discarded after the method returns; no cross-invocation
statefulness.

### ReactiveTrustWeightedCbrCaseMemoryStore (memory module)

```java
@Decorator
@Priority(60)
@IfBuildProperty(name = "casehub.cbr.trust-weighting.enabled", stringValue = "true")
public class ReactiveTrustWeightedCbrCaseMemoryStore implements ReactiveCbrCaseMemoryStore {

    private final ReactiveCbrCaseMemoryStore delegate;
    private final TrustWeightingFunction weightingFunction;
    private final ReactiveAgentTrustProvider trustProvider;

    @Inject
    ReactiveTrustWeightedCbrCaseMemoryStore(
            @Delegate @Any ReactiveCbrCaseMemoryStore delegate,
            TrustWeightingFunction weightingFunction,
            Instance<ReactiveAgentTrustProvider> trustProviderInstance) {
        this.delegate = delegate;
        this.weightingFunction = weightingFunction;
        this.trustProvider = trustProviderInstance.isResolvable()
                ? trustProviderInstance.get() : null;
    }

    @Override
    public <C extends CbrCase> Uni<List<ScoredCbrCase<C>>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        return delegate.retrieveSimilar(query, caseClass)
                .chain(results -> {
                    if (results.isEmpty()) return Uni.createFrom().item(results);

                    Set<String> agentIds = results.stream()
                            .map(s -> s.cbrCase().producerAgentId())
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

                    if (agentIds.isEmpty() || trustProvider == null) {
                        return Uni.createFrom().item(applyWeighting(results, Map.of()));
                    }

                    List<Uni<Map.Entry<String, OptionalDouble>>> lookups = agentIds.stream()
                            .map(id -> trustProvider.currentTrustScore(id)
                                    .map(score -> Map.entry(id, score)))
                            .toList();

                    return Uni.join().all(lookups).andFailFast()
                            .map(entries -> {
                                Map<String, OptionalDouble> cache = entries.stream()
                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                                return applyWeighting(results, cache);
                            });
                });
    }

    private <C extends CbrCase> List<ScoredCbrCase<C>> applyWeighting(
            List<ScoredCbrCase<C>> results, Map<String, OptionalDouble> trustCache) {
        List<ScoredCbrCase<C>> weighted = new ArrayList<>(results.size());
        for (ScoredCbrCase<C> scored : results) {
            Double trust = scored.cbrCase().trustScore();
            if (trust == null) {
                weighted.add(scored);
                continue;
            }
            OptionalDouble trajectory = computeTrajectory(scored.cbrCase(), trustCache);
            double newScore = weightingFunction.apply(scored.score(), trust, trajectory);
            Double delta = trajectory.isPresent() ? trajectory.getAsDouble() : null;
            weighted.add(scored.withScore(newScore).withTrustTrajectory(delta));
        }
        weighted.sort((a, b) -> Double.compare(b.score(), a.score()));
        return Collections.unmodifiableList(weighted);
    }
}
```

The reactive variant differs from the imperative version in two ways:

1. **Reactive SPI:** Uses `ReactiveAgentTrustProvider` (returns `Uni<OptionalDouble>`)
   instead of the blocking `AgentTrustProvider`. This avoids calling blocking I/O from
   within the Mutiny pipeline — a reactive antipattern that can starve the event loop.

2. **Batched lookups:** Collects all distinct `producerAgentId` values upfront, issues
   all trust lookups concurrently via `Uni.join().all()`, then applies weighting with
   the resolved cache. This is the reactive-idiomatic equivalent of the imperative
   version's `computeIfAbsent` cache — both eliminate redundant lookups, but the
   reactive version can additionally parallelise lookups for distinct agents.

**Decorator chain after this change:**

```
ErasureNotification@45 → Tracking@50 → TrustWeighting@60 → OutcomeWeighting@65
→ Reranking@75 → TemporalDecay@80 → ScopeDecay@85 → TrendEnrichment@90 → Base
```

### Retrieval trace observability

**CbrRetrievalTrace.TracedCase** gains three fields:

```java
public record TracedCase(
    String caseId,
    double score,
    boolean reranked,
    Map<String, Double> featureSimilarities,
    Double confidence,
    Double trustScore,
    String producerAgentId,
    String trustTrajectory
) { ... }
```

**Data path:** The `TrackingCbrCaseMemoryStore` at @Priority(50) reads these from the
`ScoredCbrCase` objects returned after trust weighting:

- `trustScore` → `scored.cbrCase().trustScore()` (carried by CbrCase)
- `producerAgentId` → `scored.cbrCase().producerAgentId()` (carried by CbrCase)
- `trustTrajectory` → derived from `scored.trustTrajectory()` (the numeric delta
  stored on ScoredCbrCase by the trust weighting decorator via `withTrustTrajectory()`)

The Tracking decorator converts the numeric delta to a direction label at
`TracedCase` construction:

- `delta < 0` → `"declining"`
- `delta > 0` → `"improving"`
- `delta == 0` → `"stable"`
- `null` → `null` (no trajectory data)

Strict comparison, no dead zone. Exact equality (delta == 0) is uncommon in practice
(requires the agent's trust to be unchanged between case storage and retrieval) but
mathematically clean. The label is informational for debugging and audit — the
scoring formula only uses the numeric delta.

### Backend Changes

**InMemoryCbrCaseMemoryStore:** No changes needed. `StoredCase` wraps `CbrCase`
directly (`new StoredCase(id, cbrCase, ...)`), so `trustScore` and `producerAgentId`
are carried by the `CbrCase` record component fields. The in-memory backend stores
the CbrCase object graph as-is and returns it unchanged at retrieval.

**ReactiveQdrantCbrCaseMemoryStore:** Two new payload fields in `CbrPointBuilder`:
- `trust_score` (double, nullable)
- `producer_agent_id` (string, nullable)

Read at retrieval time, written at store time. No collection schema change (payload
fields are schemaless in Qdrant).

**JpaCbrCaseMemoryStore:** Two new nullable columns on `CbrCaseEntity`:
- `trust_score DOUBLE`
- `producer_agent_id VARCHAR(255)`

Flyway migration adds two nullable columns — no data backfill needed.

### Migration and transition semantics

Existing cases in all backends will have null `trustScore` and null `producerAgentId`.
The decorator gracefully handles null (skips weighting for null cases), so the feature
degrades safely.

Three explicit statements about transition behavior:

1. **Inert until wired:** The feature produces no scoring changes until engine wiring
   (#174) populates trust data on newly stored cases. With all cases having null trust,
   the decorator is a pass-through.

2. **No backfill:** There is no backfill strategy for existing cases. Cases stored
   before trust wiring will never have trust scores unless explicitly re-stored.

3. **Mixed-corpus asymmetry:** In a corpus with both old (null-trust) and new
   (trust-scored) cases, trust weighting only applies to new cases. Old cases are
   immune to trust penalties. This asymmetry is acceptable because: (a) it is
   transient — over time, old cases age out via retention policies or are superseded,
   (b) it biases toward the status quo, which is safer than retroactively penalising
   cases that were stored under different assumptions, and (c) the alternative
   (backfilling synthetic trust scores) would introduce false precision.

### Contract Tests

`CbrCaseMemoryStoreContractTest` gains:

| Test | What it verifies |
|------|-----------------|
| `trustScore_roundTrip` | Store FeatureVectorCbrCase with trustScore=0.85, retrieve, verify trustScore=0.85 |
| `producerAgentId_roundTrip` | Store with producerAgentId="agent-1", retrieve, verify |
| `trustScore_nullPreserved` | Store with null trustScore, retrieve, verify null |
| `trustScore_withOutcome_preserved` | Store with trustScore, recordOutcome, retrieve, verify trustScore unchanged |
| `trustScore_withFeatures_preserved` | Store with trustScore, withFeatures, verify trustScore unchanged |
| `trustScore_validation_rejectsOutOfRange` | FeatureVectorCbrCase with trustScore=1.5 throws IllegalArgumentException |
| `producerAgentId_withOutcome_preserved` | Store with producerAgentId, recordOutcome, retrieve, verify unchanged |
| `planCbrCase_trustFields_roundTrip` | Store PlanCbrCase with both trust fields, retrieve, verify |
| `textualCbrCase_trustFields_roundTrip` | Store TextualCbrCase with both trust fields, retrieve, verify |

### Unit Tests

**DefaultTrustWeightingFunctionTest** (analogous to `DefaultOutcomeWeightingFunctionTest`):

| Test | What it verifies |
|------|-----------------|
| `authorityOnly_highTrust` | trustScore=0.9, no trajectory → score near original |
| `authorityOnly_lowTrust` | trustScore=0.1 → score reduced by influence factor |
| `authorityOnly_zeroTrust` | trustScore=0.0 → score * (1 - influence) |
| `trajectory_declining_appliesPenalty` | negative delta → additional multiplicative penalty |
| `trajectory_declining_floor` | delta=-2.0 → multiplier floors at 0.5 |
| `trajectory_improving_ignored` | positive delta → no effect on score |
| `trajectory_absent_noEffect` | OptionalDouble.empty() → authority-only weighting |

**TrustWeightedCbrCaseMemoryStoreTest** (analogous to `OutcomeWeightingCbrCaseMemoryStoreTest`):

| Test | What it verifies |
|------|-----------------|
| `nullTrustScore_passesThrough` | Cases with null trustScore are unchanged |
| `mixedNullAndNonNull_onlyWeightsNonNull` | Mixed list: only non-null cases are weighted; sort order correct |
| `resortsAfterWeighting` | Trust weighting changes relative ordering |
| `emptyResults_passesThrough` | Empty delegate result returned unchanged |
| `trajectoryCache_singleCallPerAgent` | Multiple cases from same agent → one `AgentTrustProvider.currentTrustScore()` call |
| `noAgentTrustProvider_authorityOnly` | Without provider: trajectory is empty, authority-only mode |
| `trustTrajectory_storedOnScoredCbrCase` | After weighting, `scored.trustTrajectory()` contains the numeric delta |

**ReactiveTrustWeightedCbrCaseMemoryStoreTest**:

| Test | What it verifies |
|------|-----------------|
| `batchedLookups_distinctAgentIds` | N distinct agents → N concurrent `Uni` lookups via `Uni.join().all()` |
| `noReactiveProvider_authorityOnly` | Without `ReactiveAgentTrustProvider`: authority-only mode |
| `mixedNullAndNonNull_reactiveWeighting` | Same mixed-list semantics as imperative |

### Trust provenance — scalar-only rationale

The issue comment from @0xbrainkid raises whether the captured trust should include
evidence pointers beyond the scalar (capability tag, sampling timestamp, attestation
reference). For this initial implementation, scalar-only is sufficient:

- **Capability tag:** The `producerAgentId` combined with the `caseType` implicitly
  scopes the trust dimension. An agent's trust in the CBR context is the trust
  relevant to producing cases of that type. If multi-dimensional trust becomes needed,
  `TrustWeightingFunction` can be extended to accept a capability parameter without
  changing the model — the dimension is derivable from the case's caseType at retrieval.
- **Sampling timestamp:** The trust score is a decision-time snapshot, and `storedAt`
  on `ScoredCbrCase` already provides the temporal anchor. Adding a separate
  `trustSampledAt` field would be redundant — trust is sampled at store time.
- **Attestation reference:** Audit trail for the trust score itself belongs to the
  ledger, not to CBR. The ledger's `TrustScoreSource` maintains provenance for its
  scores. CBR consumes the scalar; it doesn't need to duplicate the provenance chain.

These decisions are revisitable if multi-dimensional trust or cross-system trust
portability becomes a requirement.

### What This Does NOT Include

- **Engine wiring** — `CbrCaseRetainObserver` passing trust from routing context to
  CbrCase constructor. Tracked in #174.
- **AgentTrustProvider implementation** — bridging `TrustScoreSource` to
  `AgentTrustProvider`. Tracked in #175.
- **Trust-based retention policies** — purging low-trust cases. Tracked in #176.

These are consumer-side concerns. Neocortex provides the SPI and decorator; consumers
provide the trust data and bridge implementation.
