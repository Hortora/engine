# Prospective Event Model Design

**Issue:** casehubio/neocortex#241
**Epic:** casehubio/neocortex#226
**Branch:** `issue-253-cognitive-rearchitecture`
**Scale:** M | **Complexity:** Med

## Summary

Richer semantics for future-dated MindMap nodes. Four concerns: event trait classification, event lifecycle convention, anticipatory affect annotation, and recurring event generation.

All production code lands in two existing modules — `mindmap-api` (types) and `mindmap-intelligence` (rules, interfaces, generator). `cognitive-api` gains one two-value enum (`AffectType`). `memory-api` gains one overloaded converter method. No new modules.

## Dependencies

| Dependency | Status | What it provides |
|-----------|--------|------------------|
| #234 — Temporal taxonomy | DONE | `TemporalMark`, `validFrom`/`validUntil` on nodes |
| #239 — Affect trajectory | DONE | `AffectEvents` converter, `AffectTrajectoryDecorator`, `AffectRecorded` event |

## 1. Event Traits

### 1.1 Property Convention

Two orthogonal properties classify event nodes:

| Property | Values | Semantics |
|----------|--------|-----------|
| `eventKind` | `scheduled`, `anticipated` | Temporal fixedness |
| `eventValence` | `positive`, `negative`, `aspirational` | Emotional anticipation |

CamelCase keys match the existing convention (`birthday`, `startDate`, `endDate`) and enable direct mapping via `TraitProxy` — `TraitInvocationHandler` resolves method names to `node.property(methodName)` with no transformation.

These compose — a funeral can be `eventKind=scheduled` AND `eventValence=negative`.

### 1.2 TraitRule Implementations

Four new `@ApplicationScoped` TraitRule beans in `mindmap-intelligence`:

| TraitRule | Trait name | Match criteria |
|-----------|-----------|----------------|
| `AppointableTraitRule` | `Appointable` | `eventKind=scheduled` |
| `AspirationalTraitRule` | `Aspirational` | `eventKind=anticipated` + `eventValence=aspirational` |
| `ThreateningTraitRule` | `Threatening` | `eventKind=anticipated` + `eventValence=negative` |
| `OpportunisticTraitRule` | `Opportunistic` | `eventKind=anticipated` + `eventValence=positive` |

All four follow the existing pattern (`PersonableTraitRule`, `ProjectlikeTraitRule`): check `node.property()` values. The existing `TraitApplicationDecorator` (@Priority 70) automatically evaluates these rules after every `addNode`, `updateNode`, `addEdge`, and `removeEdge`.

### 1.3 Trait Interface

One `Eventlike` interface providing typed property access:

```java
public interface Eventlike {
    Optional<String> eventKind();
    Optional<String> eventValence();
    Optional<String> status();
    Optional<String> rrule();
}
```

Accessible via `TraitProxy.as(node, Eventlike.class)`. Uses the same JDK Proxy mechanism as `Personable`, `Projectlike`, `Organisational` — maps method names to `node.property()`.

A single interface rather than per-trait interfaces because event traits share a property namespace. The trait name on `Set<String> traits()` carries the specific classification; `Eventlike` provides access to the shared properties. (D24)

### 1.4 Integration with Existing Traits

`ProjectlikeTraitRule` already checks `node.property("status").isPresent()`. A node with `status=planned` and `event-kind=scheduled` will be classified as both `Projectlike` and `Appointable`. This is correct — a project milestone is both project-like and a scheduled event.

## 2. Event Lifecycle

### 2.1 Status Values

Property key: `status`. Values form a lifecycle convention (not enforced):

```
PLANNED → CONFIRMED → ACTIVE → COMPLETED
                              → CANCELLED
                              → REVIEWED
```

| Value | Semantics |
|-------|-----------|
| `planned` | Intended but not committed |
| `confirmed` | Committed, will happen |
| `active` | Currently in progress |
| `completed` | Finished as expected |
| `cancelled` | Abandoned before completion |
| `reviewed` | Post-completion reflection done |

### 2.2 No Transition Enforcement

MindMapStore is a generic graph store. Lifecycle enforcement belongs in the application layer. The `status` property is a free-form string with documented conventions, following the same pattern as `ProjectlikeTraitRule`'s existing `status` check. (D21)

### 2.3 TraitRule Interaction

TraitRules react to status changes automatically via `TraitApplicationDecorator`. An `AppointableTraitRule` implementation can optionally require non-cancelled status — adding `&& !node.property("status").map("cancelled"::equals).orElse(false)` to exclude cancelled events from the Appointable classification. Initial implementation does NOT filter by status — all four rules check only `eventKind` and `eventValence`, not `status`.

If `eventKind` is not set on a node, no event traits fire. This is deliberate — not every future-dated node is an "event." (D19)

## 3. Anticipatory Affect

### 3.1 AffectType Enum

New enum in `cognitive-api` (`io.casehub.neocortex.cognitive`):

```java
public enum AffectType {
    INHERENT,
    ANTICIPATORY
}
```

Placed alongside `ConfidenceOrigin` and `TemporalMark` — it's a cognitive classification of emotional relationship to events, not a memory-specific implementation detail. (D25, D26)

### 3.2 AffectEvents Overload

New overload in `memory-api`:

```java
public static MemoryInput toMemoryInput(String nodeId, String tenantId,
                                        double pleasure, double arousal, double dominance,
                                        AffectType affectType) {
    return new MemoryInput(nodeId, DOMAIN, tenantId, null, "PAD update",
                           Map.of("affect-type", affectType.name().toLowerCase()),
                           null, pleasure, arousal, dominance);
}
```

The existing 5-arg overload delegates to the new one with `AffectType.INHERENT` as default. Backward compatible — existing callers are unaffected.

### 3.3 Entry Points

| Actor | What it logs | AffectType |
|-------|-------------|------------|
| `AffectTrajectoryDecorator` | Node PAD changes (inherent emotional quality) | `INHERENT` |
| External caller (blocks/engine) | Agent's anticipatory emotional response | `ANTICIPATORY` |

The decorator always logs `INHERENT` because node PAD represents the event's inherent emotional quality. Anticipatory affect is the agent's emotional response to the event approaching — it's logged by the agent framework, not by the MindMap decorator.

### 3.4 Querying by Affect Type

`AffectTrajectoryAnalyzer.analyze(List<Memory>)` operates on a `List<Memory>`. Callers filter by attribute before analyzing:

```java
List<Memory> inherent = memories.stream()
    .filter(m -> !"anticipatory".equals(m.attributes().get("affect-type")))
    .toList();
AffectTrajectory trajectory = AffectTrajectoryAnalyzer.analyze(inherent);
```

No changes to `AffectTrajectoryAnalyzer` itself — filtering is the caller's responsibility. The attribute value `"anticipatory"` is the discriminator; entries without the attribute default to inherent (backward compatible).

## 4. Recurring Events

### 4.1 RecurrenceRule Record

New record in `mindmap-api` (`io.casehub.neocortex.mindmap`):

```java
public record RecurrenceRule(
    Frequency freq,
    int interval,
    Integer count,
    Instant until,
    Set<DayOfWeek> byDay
) {
    public enum Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

    public static RecurrenceRule parse(String rrule) { ... }

    @Override
    public String toString() { ... }  // produces RRULE string
}
```

Minimal RFC 5545 subset: FREQ, INTERVAL, COUNT, UNTIL, BYDAY. Covers core recurrence patterns for cognitive modelling. EXDATE is a planned v2 extension — for now, exceptions are handled by cancelling individual instance nodes (`status=cancelled`), which captures the cancellation as a cognitively meaningful event. (D22)

### 4.2 RecurrenceGenerator

Static utility in `mindmap-intelligence`:

```java
public final class RecurrenceGenerator {
    public static List<NodeInput> generateInstances(
        MindMapNode template,
        RecurrenceRule rule,
        Instant horizon) { ... }
}
```

Produces `NodeInput` instances from a template node up to the given time horizon. Each instance:
- Inherits: `name`, `subgraphId`, `confidence`, `provenance`, traits, refs, properties (copied from template)
- Computed: `validFrom` (from RRULE expansion)
- Added properties: `template-node-id=<templateId>`, `recurrence-index=<N>`
- Removed: `rrule` property (instances are not templates)

The generator is a pure function — no CDI, no store interaction. The caller decides when to generate and is responsible for calling `store.addNode()` for each instance.

### 4.3 Template and Instance Properties

| Property | On template | On instance |
|----------|------------|-------------|
| `rrule` | RRULE string | Not present |
| `template-node-id` | Not present | Template node ID |
| `recurrence-index` | Not present | 0-based sequence number |
| `eventKind` | Inherited | Inherited |
| `eventValence` | Inherited | Inherited |
| `status` | Not typically set | `planned` (default) |

## Module Impact

| Module | Changes |
|--------|---------|
| `cognitive-api` | + `AffectType` enum (2 values) |
| `mindmap-api` | + `RecurrenceRule` record, `Frequency` enum |
| `mindmap-intelligence` | + 4 TraitRule implementations, `Eventlike` interface, `RecurrenceGenerator` utility |
| `memory-api` | + `AffectEvents.toMemoryInput()` 6-arg overload |

No new modules. No changes to MindMapStore SPI, store backends, or the decorator chain.

## Testing Strategy

| Component | Test approach |
|-----------|--------------|
| 4 TraitRules | Unit tests following `StandardTraitRulesTest` pattern — InMemoryMindMapStore, assert trait assignment |
| `Eventlike` via TraitProxy | Unit test following `TraitProxyTest` pattern |
| `RecurrenceRule.parse()` | Unit tests for DAILY/WEEKLY/MONTHLY/YEARLY × INTERVAL/COUNT/UNTIL/BYDAY |
| `RecurrenceGenerator` | Unit tests: template + rule → verify instance count, validFrom values, inherited/added properties |
| `AffectType` enum | Trivial — verify values exist |
| `AffectEvents` overload | Unit test: verify `affect-type` attribute present with correct value |
| Integration | `TraitApplicationDecorator` integration: add node with event properties → verify traits auto-applied |

## Non-Scope

- **Lifecycle enforcement** — convention only; validation utilities are a follow-up (#241 scope is the model)
- **EXDATE/RDATE** — planned v2 extension to RecurrenceRule
- **MindMapExtractor prompt updates** — extractor changes to produce `event-kind`/`event-valence` properties are a blocks-side concern
- **TemporalIndex domain filtering** — D13 revision (configurable memoryDomains) is a #237 follow-up, not #241

## References

- `TraitRule.java` — SPI interface for trait classification
- `PersonableTraitRule.java`, `ProjectlikeTraitRule.java`, `OrganisationalTraitRule.java` — existing implementations
- `TraitApplicationDecorator.java` — automatic trait evaluation after mutations
- `TraitProxy.java`, `TraitInvocationHandler.java` — JDK Proxy for typed property access
- `AffectEvents.java` — affect trajectory converter
- `AffectTrajectoryDecorator.java` — PAD change interceptor
- `AffectTrajectoryAnalyzer.java` — trajectory computation
- `DerivedEdgeRule.java`, `DerivedEdgeDecorator.java` — analogous rule/decorator pattern
- RFC 5545 §3.3.10 — iCalendar RRULE specification
- `cognitive-architecture-roadmap.md` §3c — Prospective Event Model roadmap section
- Decisions D19-D26 — design decisions for this issue
