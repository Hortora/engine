# Unified Confidence Model — Design Spec

**Issue:** casehubio/neocortex#229
**Epic:** #224 Structural Foundation / #253 Cognitive Architecture Re-architecture
**Scope:** M (multi-module, mechanical per-module)
**Stage:** pre-release — breaking changes are free

---

## Problem

Three incompatible confidence representations exist across the cognitive subsystems:

| Store | Current type | Origin tracking | Decay reference | Range |
|-------|-------------|----------------|-----------------|-------|
| MindMap | `ConfidenceOrigin` enum + `double confidence` | STATED/INFERRED/SPECULATED | `confirmedAt` (nodes), `updatedAt` (edges) | [0,1] |
| Memory | `Double importance` | None | None | [0,1] nullable |
| CBR | `Double confidence` + `CbrOutcome` EMA | None | `storedAt` on ScoredCbrCase | [0,1] nullable |

A MindMap node at confidence 0.7 and a CBR case at confidence 0.7 mean different things (origin-initial vs EMA-adjusted). Cross-store sorting, filtering, and comparison are impossible. Decay is computed differently per entity type.

## Solution

A single `Confidence` record in a new `cognitive-api` module, replacing all three representations.

---

## Phase Scope

This spec covers **Phase 1a** of the cognitive architecture roadmap: the unified type, store-layer migration, and all public API types that carry confidence/importance values in their store or query contracts.

**Explicitly deferred to Phase 1d (#232 — Naming Audit):**
- **Event producer interfaces** — `ExperienceEvent.importance()` (sealed interface, 3 subtypes: Observation, Action, Outcome), `EngagementEvent.importance`, `RelationshipEvent.importance`, `ReflectionEvent.importance`. These 7 public API types carry `Double importance` fields with NaN-vulnerable validation. Phase 1a handles the converter layer that bridges events to stores (wrapping importance in `Confidence.unknown()`); Phase 1d renames the event fields themselves and fixes their validation.
- **Cross-repo terminology** — blocks and engine adoption of the `origin` term.

The converters (`ExperienceEvents`, `MoodEvents`, `RelationshipEvents`, `ReflectionEvents`, `EngagementEvents`) already form the boundary: they translate event-layer `confidence` into store-layer `Confidence`. Phase 1a migrates everything below that boundary; Phase 1d migrates everything above.

---

## New Module: `cognitive-api`

**Maven coordinates:** `io.casehub:casehub-neocortex-cognitive-api`
**Package:** `io.casehub.neocortex.cognitive`
**Dependencies:** none (tier-0, pure Java)
**Depended on by:** `mindmap-api`, `memory-api`

Starts with 2 types. Growth path: `TemporalMark` (#234), `MemorySpace`/`Visibility` (#230), `Affect` (#238).

---

## Type Design

### `ConfidenceOrigin` (moved from `mindmap-api`)

```java
package io.casehub.neocortex.cognitive;

public enum ConfidenceOrigin {
    STATED,
    INFERRED,
    SPECULATED,
    UNKNOWN;
}
```

**Changes from current:**
- **Moved** from `io.casehub.neocortex.mindmap` to `io.casehub.neocortex.cognitive`
- **Added** `UNKNOWN` — represents absence of provenance information (used by Memory and CBR where origin was never tracked)
- **Removed** `initialConfidence()` — the values (STATED=1.0, INFERRED=0.7, SPECULATED=0.3) are MindMap extraction defaults, not cognitive axioms. A stated fact CAN have any confidence level. Defaults move to the MindMap store layer.
- **Non-nullable** on the `Confidence` record — exhaustive switch coverage everywhere

### `Confidence`

```java
package io.casehub.neocortex.cognitive;

import java.time.Instant;
import java.util.Objects;

public record Confidence(
    ConfidenceOrigin origin,
    double value,
    Instant decayReference
) {
    public Confidence {
        Objects.requireNonNull(origin, "origin required");
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException("value must be in [0,1], got: " + value);
        }
    }

    // --- MindMap factories (decay-aware, non-null decayReference enforced) ---

    public static Confidence stated(double value, Instant decayReference) {
        Objects.requireNonNull(decayReference, "MindMap confidences require a decayReference");
        return new Confidence(ConfidenceOrigin.STATED, value, decayReference);
    }

    public static Confidence inferred(double value, Instant decayReference) {
        Objects.requireNonNull(decayReference, "MindMap confidences require a decayReference");
        return new Confidence(ConfidenceOrigin.INFERRED, value, decayReference);
    }

    public static Confidence speculated(double value, Instant decayReference) {
        Objects.requireNonNull(decayReference, "MindMap confidences require a decayReference");
        return new Confidence(ConfidenceOrigin.SPECULATED, value, decayReference);
    }

    // --- Memory/CBR factory (non-decaying) ---

    public static Confidence unknown(double value) {
        return new Confidence(ConfidenceOrigin.UNKNOWN, value, null);
    }

    // --- Transformers ---

    public Confidence withValue(double newValue) {
        return new Confidence(origin, newValue, decayReference);
    }

    public Confidence withDecayReference(Instant newRef) {
        return new Confidence(origin, value, newRef);
    }
}
```

**Factory API rationale:** The factory methods partition into two categories by usage context:
- **MindMap factories** (`stated`, `inferred`, `speculated`) require a non-null `Instant decayReference` — enforced by `Objects.requireNonNull`. MindMap confidences MUST decay; both the parameter type AND the runtime check enforce this. `Confidence.stated(1.0, null)` throws `NullPointerException`.
- **Memory/CBR factory** (`unknown`) produces non-decaying confidence with null `decayReference` — appropriate for contexts where decay is not configured.

There is no `of(ConfidenceOrigin, double)` convenience factory. The 3-arg constructor remains nullable on `decayReference` — it is the escape hatch for deserialization and edge cases where the caller has already validated their inputs.

**Validation:** Uses `!(value >= 0.0 && value <= 1.0)` — the inverted range check that structurally rejects NaN via IEEE 754 semantics (per GE-20260604-043617).

**`decayReference`** is nullable. Null means "this confidence does not decay" — used by Memory entries where decay is not configured. When non-null, `ConfidenceDecayDecorator` applies exponential decay from this instant.

---

## Migration by Store

### MindMap (`mindmap-api`)

#### `MindMapNode`

| Field | Before | After |
|-------|--------|-------|
| `confidenceOrigin()` | `ConfidenceOrigin` | Removed — access via `confidence().origin()` |
| `confidence()` | `double` | `Confidence` — the unified record |
| `confirmedAt()` | `Instant` | **Removed** — subsumed by `confidence().decayReference()`. `updatedAt()` serves audit needs. |

#### `MindMapEdge`

| Field | Before | After |
|-------|--------|-------|
| `confidenceOrigin()` | `ConfidenceOrigin` | Removed |
| `confidence()` | `double` | `Confidence` |

No fields removed — edges never had `confirmedAt`. The decay decorator previously used `updatedAt()` as the decay reference; now uses `confidence().decayReference()`.

#### `NodeInput`

| Field | Before | After |
|-------|--------|-------|
| `ConfidenceOrigin confidenceOrigin` | Separate field, defaults to STATED | Removed |
| `Double confidence` | Separate nullable field | Removed |
| `Confidence confidence` | — | **New** nullable field. Null means "use store defaults" |

When `origin` is null on NodeInput, the store layer applies MindMap-specific defaults via `MindMapConfidenceDefaults`:
- `origin = STATED` (existing behaviour)
- `value = MindMapConfidenceDefaults.defaultValue(STATED)` → 1.0
- `decayReference = Instant.now()`

**`MindMapConfidenceDefaults`** is a public utility class in `mindmap-api` (not package-private in the store layer). Both the store layer and the intelligence layer (`MindMapExtractor` in `mindmap-intelligence`) need access to these defaults:

```java
package io.casehub.neocortex.mindmap;

public final class MindMapConfidenceDefaults {
    public static double defaultValue(ConfidenceOrigin origin) {
        return switch (origin) {
            case STATED -> 1.0;
            case INFERRED -> 0.7;
            case SPECULATED -> 0.3;
            case UNKNOWN -> 1.0;
        };
    }

    public static Confidence forOrigin(ConfidenceOrigin origin, Instant decayReference) {
        return switch (origin) {
            case STATED -> Confidence.stated(defaultValue(origin), decayReference);
            case INFERRED -> Confidence.inferred(defaultValue(origin), decayReference);
            case SPECULATED -> Confidence.speculated(defaultValue(origin), decayReference);
            case UNKNOWN -> Confidence.unknown(defaultValue(origin));
        };
    }
}
```

These values were previously on `ConfidenceOrigin.initialConfidence()`. They are MindMap extraction defaults (not cognitive axioms), but they are domain contract — both store implementations and the intelligence layer need them. `mindmap-api` is the correct module: `mindmap-intelligence` depends on it, and store modules either are part of it or depend on it.

#### `EdgeInput`

| Field | Before | After |
|-------|--------|-------|
| `ConfidenceOrigin confidenceOrigin` | Separate field, defaults to STATED | Removed |
| `Double confidence` | Separate nullable field | Removed |
| `Confidence confidence` | — | **New** nullable field. Null means "use store defaults" |

When `origin` is null on EdgeInput, the store layer applies MindMap-specific defaults:
- `origin = STATED` (existing behaviour)
- `value = 1.0` (same as NodeInput default)
- `decayReference = Instant.now()`

Note: edges never had `confirmedAt`. The decay decorator previously used `updatedAt()` as the decay reference for edges; under the new model, edges use `confidence().decayReference()` like nodes. The `updatedAt()` field remains as an entity audit timestamp.

#### `NodeUpdate`

| Field | Before | After |
|-------|--------|-------|
| `ConfidenceOrigin confidenceOrigin` | Separate field | Removed |
| `Double confidence` | Separate field | Removed |
| `Instant confirmedAt` | Separate field | Removed |
| `Confidence confidence` | — | **New** nullable field. Null = "don't change confidence". Non-null replaces entire confidence including decayReference. |

**Behavioral change from D6:** The current store contract implicitly resets confidence to 1.0 when `confirmedAt` is set without an explicit confidence value (enforced by contract test `updateNode_confirmedAtWithoutConfidence_resetsTo1`). Under the new model, "confirmation" means updating decayReference only — value and origin are preserved unless the caller explicitly provides new values. A SPECULATED node at 0.3 that is "confirmed" stays at 0.3 with a fresh decay clock, rather than silently jumping to 1.0. To reset value to 1.0, the caller constructs `new Confidence(origin, 1.0, Instant.now())` explicitly. This is a deliberate improvement: the implicit reset erased the epistemic distinction between SPECULATED and STATED knowledge.

#### `MindMapQuery`

`Double minConfidence` and `ConfidenceOrigin confidenceOrigin` **stay as separate query predicates**. These are independent filters ("find nodes with confidence above X" / "find nodes that were stated"), not a value composition. They do not become a `Confidence` field on the query.

#### `ParsedEntity` (`mindmap-intelligence`)

`ConfidenceOrigin confidence` renamed to `ConfidenceOrigin origin` for clarity. ParsedEntity carries origin only — the confidence value is derived at store time via defaults.

ParsedEntity is package-private in `mindmap-intelligence` (package `io.casehub.neocortex.mindmap.intelligence`), not in `mindmap-api`. The import changes from `io.casehub.neocortex.mindmap.ConfidenceOrigin` to `io.casehub.neocortex.cognitive.ConfidenceOrigin` — the transitive dependency chain (`mindmap-intelligence` → `mindmap-api` → `cognitive-api`) provides the import.

**`ExtractedRelationship`** (`mindmap-intelligence`, public) also carries `ConfidenceOrigin confidenceOrigin` — same import update, no field rename needed (it captures the origin of the extracted relationship for the caller).

**`ParsedRelationship`** (`mindmap-intelligence`, package-private) carries `ConfidenceOrigin confidence` → renamed to `ConfidenceOrigin origin` (same as ParsedEntity).

#### `MindMapExtractor` conversion path (`mindmap-intelligence`)

`MindMapExtractor.applyExtraction()` constructs `NodeInput` and `EdgeInput` from parsed origins. After field collapse, it must construct full `Confidence` records instead of passing separate `ConfidenceOrigin` and `null` confidence:

```java
// Before:
store.addNode(new NodeInput(
    pe.name(), sgId, pe.confidence(), null, ...));
//                  ↑ ConfidenceOrigin   ↑ Double confidence (null → store defaults)

// After:
store.addNode(new NodeInput(
    pe.name(), sgId,
    MindMapConfidenceDefaults.forOrigin(pe.origin(), Instant.now()),
    ...));
//  ↑ Full Confidence with origin-appropriate default value + decay reference
```

Same pattern for `EdgeInput` construction from `ParsedRelationship.origin()`.

Without this, passing `null` for NodeInput.confidence would default to STATED/1.0, losing the parsed origin (e.g., an LLM-extracted INFERRED entity at 0.7 would become STATED at 1.0).

#### `ConfidenceDecayDecorator`

Migrates from reading entity-specific timestamps to reading `confidence().decayReference()`:

```java
// Before:
double decayed = applyDecay(node.confidence(), node.confirmedAt(), halfLifeDays);
// After:
Confidence c = node.confidence();
double decayed = applyDecay(c.value(), c.decayReference(), halfLifeDays);
```

Returns wrapped nodes/edges with a new `Confidence` carrying the decayed value (same origin, same decayReference):

```java
private MindMapNode withDecayedConfidence(MindMapNode node) {
    Confidence c = node.confidence();
    double decayed = applyDecay(c.value(), c.decayReference(), defaultHalfLifeDays);
    if (decayed == c.value()) return node;
    return new DecayedNode(node, c.withValue(decayed));
}
```

`DecayedNode`/`DecayedEdge` simplify — `confirmedAt()` delegation is no longer needed on DecayedNode (field removed from interface). One fewer method to delegate. `DecayedNode.confidence()` and `DecayedEdge.confidence()` return the new `Confidence` record with decayed value (via `c.withValue(decayed)`), not a raw double.

The post-decay filter in `search()` also updates:

```java
// Before:
results.removeIf(n -> n.confidence() < query.minConfidence());
// After:
results.removeIf(n -> n.confidence().value() < query.minConfidence());
```

This is a compile error without the fix — `Confidence < Double` does not compile.

#### `MindMapAnalyzer`

Two methods require migration:

**`staleNodes`** — currently reads `confirmedAt` for staleness detection. Migrates to `confidence().decayReference()`.

**`lowConfidenceCluster`** — currently reads `node.confidence()` expecting a `double` return for comparison against `threshold`. Migrates to `node.confidence().value() < threshold`.

#### Backends

**InMemoryMindMapStore:** `StoredNode` and `StoredEdge` replace their separate `confidenceOrigin`/`origin` fields with a single `Confidence confidence` field. `confirmedAt` removed from `StoredNode`. Defaulting logic (when NodeInput.confidence is null) moves to `addNode()`.

**SqliteMindMapStore:** Schema change — `confidence_origin` + `origin` + `confirmed_at` columns on `nodes` table replaced with `confidence_origin` + `confidence_value` + `decay_reference`. Same pattern for `edges` table (`confidence_origin` + `origin` → `confidence_origin` + `confidence_value` + `decay_reference`). Row-mapping methods (`toNode`, `toEdge`) construct `Confidence` from the three columns.

**Flyway migration data strategy** (data-preserving, not DROP+CREATE):
1. **Nodes:** Rename `origin` → `confidence_value`. Rename `confirmed_at` → `decay_reference`. All existing node rows have `confirmed_at` values (SqliteMindMapStore.addNode always sets `Instant.now()`), so `decay_reference` is populated for all existing rows — decay anchors are preserved.
2. **Edges:** Rename `origin` → `confidence_value`. Add `decay_reference` column. Populate from `updated_at` for existing rows — this matches the current `ConfidenceDecayDecorator` behavior where edges use `updatedAt()` for decay. `updated_at` column remains (entity audit timestamp).
3. **Both:** `confidence_origin` column unchanged — existing values (STATED, INFERRED, SPECULATED) are preserved.

The settled decision "clean storage format breaks, not lazy migration" (R1-06) applies to Qdrant (schemaless, no migration tooling). SQL stores use Flyway, which handles data-preserving migrations — these are the correct approach for structured stores with existing data.

**MindMapStoreContractTest:** Test helpers and assertions update to use `Confidence`. Tests that set `confirmedAt` separately now construct a `Confidence` with a `decayReference`.

### Memory (`memory-api`)

#### `MemoryInput`

| Field | Before | After |
|-------|--------|-------|
| `Double importance` | Nullable, validated [0,1] | **Removed** |
| `Confidence confidence` | — | **New** nullable. Null means "no confidence assessment" |

`withAttribute`, `withAttributes`, `withText` methods updated to carry `origin` through.

#### `Memory`

| Field | Before | After |
|-------|--------|-------|
| `Double importance` | Record component | **Removed** |
| `Confidence confidence` | — | **New** nullable |

#### Converters

All converters (`ExperienceEvents`, `MoodEvents`, `RelationshipEvents`, `ReflectionEvents`, `EngagementEvents`) currently pass `confidence` when constructing `MemoryInput`. They update to pass `Confidence`:

```java
// Before (ReflectionEvents):
new MemoryInput(entityId, domain, tenantId, null, text, attrs,
    Math.min(0.3 + level * 0.2, 1.0))

// After:
new MemoryInput(entityId, domain, tenantId, null, text, attrs,
    Confidence.unknown(Math.min(0.3 + level * 0.2, 1.0)))
```

#### `MemoryRetentionPolicy`

| Field | Before | After |
|-------|--------|-------|
| `Double minImportance` | Nullable, validated [0,1] (NaN-vulnerable) | **Renamed** to `Double minConfidence`. Validation adopts NaN-safe pattern: `!(minConfidence >= 0.0 && minConfidence <= 1.0)` |

The `CaseMemoryStore.purge(MemoryRetentionPolicy)` SPI signature is unchanged — it takes the policy record. All store implementations that read `policy.minImportance()` update to `policy.minConfidence()`. The record constructor validation message updates accordingly.

#### `MemoryRetentionConfig` and `MemoryRetentionScheduler` (`memory` runtime)

The Quarkus config interface that feeds `MemoryRetentionPolicy` also updates:

| Component | Before | After |
|-----------|--------|-------|
| `MemoryRetentionConfig.minImportance()` | `Optional<Double>` method | Renamed to `minConfidence()` |
| Config property | `casehub.memory.retention.min-importance` | `casehub.memory.retention.min-confidence` |
| `MemoryRetentionScheduler.purgeExpired()` | reads `config.minImportance().orElse(null)` | reads `config.minConfidence().orElse(null)` |

The config property rename is a **deployment-visible breaking change** — any `application.properties` or deployment config using `casehub.memory.retention.min-importance` must update. Pre-release platform: this is acceptable.

`MemoryRetentionSchedulerTest` updates to use the renamed config method.

#### `MemoryOrder.SALIENCE`

Javadoc currently references `Memory#importance()`: "null importance treated as 1.0." Updates to reference `Memory#confidence()`: "null confidence treated as value 1.0."

#### `PersonalityWeightedRetrieval`, `MoodModulatedRetrieval`, and `InMemoryMemoryStore`

All three read `memory.importance()` with the same null-coalescing pattern. Update to read `memory.confidence()`:

```java
// Before:
double importance = m.importance() != null ? m.importance() : 1.0;
// After:
double confidence = m.confidence() != null ? m.confidence().value() : 1.0;
```

**`InMemoryMemoryStore.salience()`** — uses the null-coalesced value for recency × importance ranking.

**`InMemoryMemoryStore.purge()`** — reads both `m.importance()` (→ `m.confidence().value()`) and `policy.minImportance()` (→ `policy.minConfidence()`) for retention filtering.

#### Backends

**InMemoryMemoryStore:** `store()` method passes `input.importance()` → `input.confidence()` when constructing `Memory`. `salience()` and `purge()` read confidence value with null-coalescing (see §PersonalityWeightedRetrieval above).

**SqliteMemoryStore:** Column rename `confidence` → `confidence_value`. Add `confidence_origin` column (stores enum name, defaults to `UNKNOWN`). Flyway migration.

**JpaMemoryStore:** Entity field rename. Flyway migration for the PostgreSQL table.

**Mem0CaseMemoryStore / GraphitiCaseMemoryStore:** REST payload field mapping update.

### CBR (`memory-api`)

#### `CbrCase`

| Method | Before | After |
|--------|--------|-------|
| `Double confidence()` | Returns nullable Double | `Confidence confidence()` — returns nullable Confidence |
| `withOutcome(String, Double)` | Double confidence param | `withOutcome(String, Confidence)` |

#### `TextualCbrCase`, `FeatureVectorCbrCase`, `PlanCbrCase`

Record component `Double confidence` → `Confidence confidence` (nullable). Validation changes from manual `< 0.0 || > 1.0` check to relying on `Confidence` constructor validation. `withOutcome` signature updates.

#### `CbrOutcome.adjustConfidence`

```java
// Before:
public static double adjustConfidence(Double oldConfidence, double successRate,
                                      double learningRate)

// After:
public static Confidence adjustConfidence(Confidence old, double successRate,
                                          double learningRate) {
    double oldValue = old != null ? old.value() : 1.0;
    double newValue = (1.0 - learningRate) * oldValue + learningRate * successRate;
    ConfidenceOrigin origin = old != null ? old.origin() : ConfidenceOrigin.UNKNOWN;
    return new Confidence(origin, newValue, null);
}
```

Preserves origin (how the case was originally established), updates value via EMA. `decayReference` is null — CBR has no decay mechanism (`ConfidenceDecayDecorator` wraps MindMap stores only). The `observedAt` timestamp is already on `CbrOutcome.observedAt()` — it records when the outcome was observed, which is CBR-specific metadata, not a decay anchor. Setting non-null `decayReference` on CBR confidences would create semantic inconsistency: in Phase 4 cross-store queries, CBR confidences would be incorrectly decayed.

#### `OutcomeWeightingCbrCaseMemoryStore`

Currently reads `cbrCase.confidence()` as a Double for score modulation. Updates to read `cbrCase.confidence().value()`, null-guarded.

#### `CbrRetrievalTrace.TracedCase`

| Field | Before | After |
|-------|--------|-------|
| `Double confidence` | Nullable scalar snapshot | `Confidence confidence` — nullable, captures full confidence state at retrieval time |

TracedCase is a diagnostic record in `memory-api` (package `io.casehub.neocortex.memory.cbr`). It snapshots the case's confidence at retrieval time for tracing. After migration, the snapshot includes origin and decayReference — useful for debugging why a case was ranked a certain way (e.g., was it INFERRED at 0.7 or STATED at 0.7? Was decay applied?).

Writers (`TrackingCbrCaseMemoryStore`, `SqliteCbrRetrievalTracker`, `InMemoryCbrRetrievalTracker`) update to read `cbrCase.confidence()` as a `Confidence` instead of `Double`.

#### `ScoredCbrCase`

No change to the record itself — `ScoredCbrCase.score()` is a retrieval score, not a confidence value. `ScoredCbrCase.storedAt()` stays — it's an entity timestamp, distinct from `Confidence.decayReference`.

#### CBR Backends

**InMemoryCbrCaseMemoryStore:** `recordOutcome` updates — constructs `Confidence` from EMA result.

**QdrantCbrCaseMemoryStore:** Payload serialization — `origin` field becomes a structured object `{origin, value, decayReference}` in Qdrant payload. `recordOutcome` updates. This is a **breaking storage format change** — existing flat-Double payloads are not forward-compatible. Pre-release platform: Qdrant collections are recreated, not lazily migrated.

The serialization layer consists of two paths:
- **`CbrPointBuilder`** (direct Qdrant) — currently writes `ValueFactory.value(cbrCase.confidence())` as a flat Double. Updates to write a structured payload: `{origin: "UNKNOWN", value: 0.85, decayReference: null}` (CBR confidences have null decayReference per settled R2-03 decision).
- **`CbrMemorySerializer`** (via CaseMemoryStore) — currently calls `MemoryAttributeKeys.formatConfidence(cbrCase.confidence())`. Updates to serialize the full `Confidence` record into attributes (or the scalar value if the Memory store handles confidence natively after migration).

**JpaCbrCaseMemoryStore:** Entity column changes + Flyway migration.

---

## Dependency Graph Change

```
Before:
  mindmap-api (zero deps)
  memory-api → fusion-api, platform-api

After:
  cognitive-api (zero deps)
  mindmap-api → cognitive-api
  memory-api → cognitive-api, fusion-api, platform-api
```

Both `mindmap-api` and `memory-api` gain a dependency on the new tier-0 module. `cognitive-api` has no dependencies — it is pure Java records and enums.

---

## What Does NOT Change

- **`CbrOutcome` record** stays in `memory-api` — it carries CBR-specific fields (result, successRate, detail, observedAt)
- **`MindMapNode.updatedAt()`** stays — it's an entity audit timestamp
- **`ScoredCbrCase`** record is unchanged — `score()` is a retrieval ranking signal, not a confidence value
- **`MindMapQuery.minConfidence` and `MindMapQuery.confidenceOrigin`** stay as separate query predicates
- **`TemporalDecay`** (memory-api) is unrelated — it's post-scoring retrieval decay, not knowledge confidence decay
- **`MoodState` PAD values** are affect, not confidence — orthogonal (#238)

---

## Validation

The `Confidence` constructor uses the inverted range check pattern:

```java
if (!(value >= 0.0 && value <= 1.0)) {
    throw new IllegalArgumentException("value must be in [0,1], got: " + value);
}
```

This structurally rejects `Double.NaN` via IEEE 754 semantics — `NaN >= 0.0` is `false`, so `!(false && ...)` is `true`, and the guard fires. Explicit `Double.isNaN()` is not needed.

**`MemoryAttributeKeys.formatConfidence(double v)`** currently uses the NaN-vulnerable pattern `if (v < 0 || v > 1)`. This method serializes confidence values for Qdrant payload storage. After migration, most callers pass values already validated by the `Confidence` constructor — but `formatConfidence` is a public utility and defense-in-depth demands the NaN-safe guard. Updates to `if (!(v >= 0.0 && v <= 1.0))`.

**`MemoryRetentionPolicy`** validation also adopts the NaN-safe pattern (see §Memory migration).

---

## Testing Strategy

1. **cognitive-api:** Unit tests for `Confidence` — construction, validation (boundary values, NaN rejection), factory methods (`stated`, `inferred`, `speculated`, `unknown`), `withValue`/`withDecayReference`
2. **mindmap-api:** `MindMapStoreContractTest` updates (72 tests) — all test helpers and assertions migrate. **Exception:** `updateNode_confirmedAtWithoutConfidence_resetsTo1` is intentionally **removed** (not migrated) — the implicit 1.0 reset behavior it tested is eliminated by D6. Replaced by a new test verifying that confirmation (updating decayReference) preserves the existing confidence value and origin. `MindMapConfidenceDefaults` unit tests: `defaultValue` returns correct values for all 4 origins (STATED→1.0, INFERRED→0.7, SPECULATED→0.3, UNKNOWN→1.0); `forOrigin` routes through named factories (verified by testing that STATED/INFERRED/SPECULATED reject null decayReference while UNKNOWN accepts it via `Confidence.unknown`).
3. **memory-api:** `CbrCaseMemoryStoreContractTest` updates (151 tests) — confidence field changes
4. **memory-testing:** `CaseMemoryStoreContractTest` — `importance_roundTrip` and `importance_nullDefault` rename to `confidence_roundTrip` and `confidence_nullDefault`, reading `confidence()` instead of `importance()`. `purge_importanceBased` updates to use `minConfidence` on `MemoryRetentionPolicy`.
5. **memory-testing:** `CbrOutcomeTest` — `adjustConfidence` now returns `Confidence`
6. **Backends:** Each backend's integration tests verify persistence of the three Confidence components (origin, value, decayReference)
7. **Decorators:** `ConfidenceDecayDecoratorTest` — decay reads `decayReference` from `Confidence`, not `confirmedAt`/`updatedAt`

---

## Documentation Updates

The following hand-maintained documentation references `confidence` in descriptions of APIs changed by this spec. They must be updated as part of implementation:

- **`docs/guides/consumer-guide.md`** — 8 references to `confidence` in Memory API descriptions: store input field, retention purge, salience ranking, config properties (`casehub.memory.retention.min-importance`). All update to `origin`/`min-confidence`.
- **`docs/guides/contributor-guide.md`** — 6 references to `confidence` in module descriptions, MemoryInput field, MemoryOrder.SALIENCE, MemoryRetentionPolicy. All update to `origin`.
- **`docs/guides/cognitive-types-guide.md`** — 1 reference to `confirmedAt` (line 400): "gets reinforced by confirmation (`confirmedAt`)." After this spec, confidence is reinforced by updating `decayReference` via a new `Confidence` with a fresh `Instant`.
- **`docs/guides/cognitive-coherence-audit.md`** — 8 references to `confirmedAt` including temporal field descriptions (line 45), node confirmation mechanism (line 81), missing edge confirmation (line 86), decay mechanism descriptions (lines 92-93), and the recommendation "Add `confirmedAt` to MindMapEdge" (line 99) which is now moot — edges use `confidence().decayReference()`. These are architectural findings that describe the pre-unified model; the audit's gap analysis for this dimension is resolved by this spec.

---

## References

- [cognitive-architecture-roadmap.md §1a](../../docs/guides/cognitive-architecture-roadmap.md) — original design direction
- [cognitive-coherence-audit.md §Dimension 1](../../docs/guides/cognitive-coherence-audit.md) — identifies the three-model gap
- [GE-20260604-043617] — NaN silently passes range guards; inverted check pattern
- [GE-20260722-a9b61b] — ScoredCbrCase.score vs EnsemblePlan.ensembleConfidence range mismatch
- [ConfidenceOrigin.java](../../mindmap-api/src/main/java/io/casehub/neocortex/mindmap/ConfidenceOrigin.java) — current enum
- [ConfidenceDecayDecorator.java](../../mindmap/src/main/java/io/casehub/neocortex/mindmap/runtime/ConfidenceDecayDecorator.java) — current decay logic
- [CbrOutcome.java](../../memory-api/src/main/java/io/casehub/neocortex/memory/cbr/CbrOutcome.java) — current EMA
- [MemoryInput.java](../../memory-api/src/main/java/io/casehub/neocortex/memory/MemoryInput.java) — current importance field
- [decisions.md](decisions.md) — D1-D7 captured decisions
