# Builder APIs — with*() Methods for Query and Input Records

**Issue:** casehubio/neocortex#231
**Modules:** `mindmap-api`, `memory-api`
**Packages:** `io.casehub.neocortex.mindmap`, `io.casehub.neocortex.memory`

## Problem

Five record types in neocortex use multi-argument constructors where
most fields are optional. Callers pass walls of positional nulls:

```java
// MindMapQuery — 12 args, 10 are null/default
new MindMapQuery(TENANT, null, "Alice", null, null, null, null, false, null, null, null, 5)

// NodeInput — 12 args, 10 are null/empty
new NodeInput("Alice", subgraphId, null, null, null, null, null, null, null, null, null, null)
```

This is error-prone (swapping adjacent nulls is silent), hard to read,
and breaks on every field addition.

`CbrQuery` already solved this with the `of()` + `withX()` pattern —
16 fields, a 5-arg factory, and 13 individual withers. Call sites read:

```java
CbrQuery.of(tenantId, domain, scope, caseType, features, 10)
    .withProblem("similar incidents")
    .withMinSimilarity(0.3)
    .withRetrievalMode(RetrievalMode.HYBRID)
```

This spec applies the same pattern to the remaining 5 types.

## Design

### Pattern

Each type gets:

1. **`of()` static factory** — takes the minimum required fields, sets
   sensible defaults (null, empty collection, false) for everything else.
2. **Individual `withX()` methods** — each returns a new record instance
   via the canonical constructor, swapping one field. Validation in the
   compact constructor fires on every call.
3. **Convenience helpers** where the pattern is established:
   - `withPad(Double p, Double a, Double d)` — set all three PAD fields
   - `withProperty(String key, String value)` — single-entry map append
     (following `CbrQuery.withFilter`)

No mutable builders, no intermediate state, no new dependencies.

### Conventions

- Collections default to `Set.of()` / `Map.of()`, never null
- Booleans default to `false`
- Primitives use the record's existing default (`limit` has no safe
  default — it stays in `of()`)
- The canonical constructor and its validation are unchanged
- Existing `withX()` methods on MemoryInput are kept as-is

---

### 1. MindMapQuery (mindmap-api)

**Record fields (12):**

| Field | Type | Required | Default |
|-------|------|----------|---------|
| tenantId | String | yes | — |
| subgraphId | String | no | null |
| text | String | no | null |
| edgeType | String | no | null |
| traits | Set\<String\> | no | null |
| minConfidence | Double | no | null |
| confidenceOrigin | ConfidenceOrigin | no | null |
| includeSuperseded | boolean | no | false |
| validAfter | Instant | no | null |
| validBefore | Instant | no | null |
| updatedAfter | Instant | no | null |
| limit | int | yes | — |

**Factory:**

```java
public static MindMapQuery of(String tenantId, int limit) {
    return new MindMapQuery(tenantId, null, null, null, null,
                            null, null, false, null, null, null, limit);
}
```

**Withers (10):**

```java
public MindMapQuery withSubgraphId(String subgraphId)
public MindMapQuery withText(String text)
public MindMapQuery withEdgeType(String edgeType)
public MindMapQuery withTraits(Set<String> traits)
public MindMapQuery withMinConfidence(Double minConfidence)
public MindMapQuery withConfidenceOrigin(ConfidenceOrigin confidenceOrigin)
public MindMapQuery withIncludeSuperseded(boolean includeSuperseded)
public MindMapQuery withValidAfter(Instant validAfter)
public MindMapQuery withValidBefore(Instant validBefore)
public MindMapQuery withUpdatedAfter(Instant updatedAfter)
```

**Call-site improvement:**

```java
// Before
new MindMapQuery(TENANT, null, "Alice", null, null, null, null, false, null, null, null, 5)

// After
MindMapQuery.of(TENANT, 5).withText("Alice")
```

---

### 2. NodeInput (mindmap-api)

**Record fields (12):**

| Field | Type | Required | Default |
|-------|------|----------|---------|
| name | String | yes | — |
| subgraphId | String | yes | — |
| confidence | Confidence | no | null |
| provenance | String | no | null |
| traits | Set\<String\> | no | null → Set.of() |
| refs | Set\<NodeRef\> | no | null → Set.of() |
| validFrom | Instant | no | null |
| validUntil | Instant | no | null |
| pleasure | Double | no | null |
| arousal | Double | no | null |
| dominance | Double | no | null |
| properties | Map\<String, String\> | no | null → Map.of() |

**Factory:**

```java
public static NodeInput of(String name, String subgraphId) {
    return new NodeInput(name, subgraphId, null, null, null, null,
                         null, null, null, null, null, null);
}
```

**Withers (12):**

```java
public NodeInput withConfidence(Confidence confidence)
public NodeInput withProvenance(String provenance)
public NodeInput withTraits(Set<String> traits)
public NodeInput withRefs(Set<NodeRef> refs)
public NodeInput withValidFrom(Instant validFrom)
public NodeInput withValidUntil(Instant validUntil)
public NodeInput withPleasure(Double pleasure)
public NodeInput withArousal(Double arousal)
public NodeInput withDominance(Double dominance)
public NodeInput withPad(Double pleasure, Double arousal, Double dominance)
public NodeInput withProperties(Map<String, String> properties)
public NodeInput withProperty(String key, String value)
```

`withProperty` merges into existing properties (copy + put), matching
the `CbrQuery.withFilter` pattern.

**Call-site improvement:**

```java
// Before
new NodeInput("Alice", subgraphId, new Confidence(STATED, 0.9, null),
              null, Set.of("person"), null, null, null, null, null, null, null)

// After
NodeInput.of("Alice", subgraphId)
    .withConfidence(new Confidence(STATED, 0.9, null))
    .withTraits(Set.of("person"))
```

---

### 3. EdgeInput (mindmap-api)

**Record fields (11):**

| Field | Type | Required | Default |
|-------|------|----------|---------|
| sourceNodeId | String | yes | — |
| targetNodeId | String | yes | — |
| edgeType | String | yes | — |
| confidence | Confidence | no | null |
| provenance | String | no | null |
| validFrom | Instant | no | null |
| validUntil | Instant | no | null |
| pleasure | Double | no | null |
| arousal | Double | no | null |
| dominance | Double | no | null |
| properties | Map\<String, String\> | no | null → Map.of() |

**Factory:**

```java
public static EdgeInput of(String sourceNodeId, String targetNodeId, String edgeType) {
    return new EdgeInput(sourceNodeId, targetNodeId, edgeType, null, null,
                         null, null, null, null, null, null);
}
```

**Withers (10):**

```java
public EdgeInput withConfidence(Confidence confidence)
public EdgeInput withProvenance(String provenance)
public EdgeInput withValidFrom(Instant validFrom)
public EdgeInput withValidUntil(Instant validUntil)
public EdgeInput withPleasure(Double pleasure)
public EdgeInput withArousal(Double arousal)
public EdgeInput withDominance(Double dominance)
public EdgeInput withPad(Double pleasure, Double arousal, Double dominance)
public EdgeInput withProperties(Map<String, String> properties)
public EdgeInput withProperty(String key, String value)
```

**Call-site improvement:**

```java
// Before
new EdgeInput(srcId, tgtId, "KNOWS", conf, null, null, null, null, null, null, Map.of())

// After
EdgeInput.of(srcId, tgtId, "KNOWS").withConfidence(conf)
```

---

### 4. NodeUpdate (mindmap-api)

**Record fields (13):** All optional — NodeUpdate describes a mutation.

| Field | Type | Default |
|-------|------|---------|
| name | String | null |
| confidence | Confidence | null |
| traitsToAdd | Set\<String\> | Set.of() |
| traitsToRemove | Set\<String\> | Set.of() |
| refsToAdd | Set\<NodeRef\> | Set.of() |
| refsToRemove | Set\<NodeRef\> | Set.of() |
| validFrom | Instant | null |
| validUntil | Instant | null |
| pleasure | Double | null |
| arousal | Double | null |
| dominance | Double | null |
| propertiesToSet | Map\<String, String\> | Map.of() |
| propertiesToRemove | Set\<String\> | Set.of() |

**Factory:**

```java
public static NodeUpdate empty() {
    return new NodeUpdate(null, null, Set.of(), Set.of(), Set.of(), Set.of(),
                          null, null, null, null, null, Map.of(), Set.of());
}
```

**Withers (13):**

```java
public NodeUpdate withName(String name)
public NodeUpdate withConfidence(Confidence confidence)
public NodeUpdate withTraitsToAdd(Set<String> traitsToAdd)
public NodeUpdate withTraitsToRemove(Set<String> traitsToRemove)
public NodeUpdate withRefsToAdd(Set<NodeRef> refsToAdd)
public NodeUpdate withRefsToRemove(Set<NodeRef> refsToRemove)
public NodeUpdate withValidFrom(Instant validFrom)
public NodeUpdate withValidUntil(Instant validUntil)
public NodeUpdate withPleasure(Double pleasure)
public NodeUpdate withArousal(Double arousal)
public NodeUpdate withDominance(Double dominance)
public NodeUpdate withPad(Double pleasure, Double arousal, Double dominance)
public NodeUpdate withPropertiesToSet(Map<String, String> propertiesToSet)
public NodeUpdate withPropertiesToRemove(Set<String> propertiesToRemove)
```

**Call-site improvement:**

```java
// Before
new NodeUpdate("New Name", null, Set.of(), Set.of(), Set.of(), Set.of(),
               null, null, 0.8, 0.3, 0.5, Map.of(), Set.of())

// After
NodeUpdate.empty()
    .withName("New Name")
    .withPad(0.8, 0.3, 0.5)
```

---

### 5. MemoryInput (memory-api)

**Record fields (10):** Already has 4 withers. Completing coverage.

| Field | Type | Required | Default | Existing wither |
|-------|------|----------|---------|----------------|
| entityId | String | yes | — | — |
| domain | MemoryDomain | yes | — | — |
| tenantId | String | yes | — | — |
| caseId | String | no | null | — |
| text | String | yes | — | withText ✓ |
| attributes | Map\<String, String\> | yes | Map.of() | withAttribute, withAttributes ✓ |
| confidence | Confidence | no | null | — |
| pleasure | Double | no | null | withPad ✓ |
| arousal | Double | no | null | withPad ✓ |
| dominance | Double | no | null | withPad ✓ |

**Factory:**

```java
public static MemoryInput of(String entityId, MemoryDomain domain,
                              String tenantId, String text) {
    return new MemoryInput(entityId, domain, tenantId, null, text,
                           Map.of(), null, null, null, null);
}
```

**New withers (2):**

```java
public MemoryInput withCaseId(String caseId)
public MemoryInput withConfidence(Confidence confidence)
```

**Existing withers (kept unchanged):**

- `withAttribute(String key, String value)`
- `withAttributes(Map<String, String> additional)`
- `withText(String newText)`
- `withPad(Double pleasure, Double arousal, Double dominance)`

**Call-site improvement:**

```java
// Before
new MemoryInput(entityId, domain, tenantId, caseId, text,
                Map.of(), confidence, null, null, null)

// After
MemoryInput.of(entityId, domain, tenantId, text)
    .withCaseId(caseId)
    .withConfidence(confidence)
```

---

## What Does NOT Change

- **No new modules** — all changes land in `mindmap-api` and `memory-api`
- **No store changes** — stores and their SPIs are untouched
- **Canonical constructors** — unchanged; same validation, same fields
- **CbrQuery** — already has builders, not modified
- **SubgraphInput** — 3 required fields, no optionals, excluded (D60)
- **Existing MemoryInput withers** — kept as-is

## Test Plan

Tests are added alongside the builder methods in each module's
existing test directory.

**Per type (5 types × 6 checks = 30 test methods):**

1. **Factory creates valid instance with defaults** —
   `of()` returns a record where required fields match args and
   optionals are null / empty / false.

2. **Each withX() returns new instance** —
   original unchanged, returned instance has only the target field
   modified. One test method per wither is excessive; test a
   representative sample (first, last, collection-valued).

3. **Chaining works** —
   `of(...).withA(...).withB(...)` produces a record with both
   fields set. Verifies withers compose without interference.

4. **Validation fires through withX()** —
   e.g., `MindMapQuery.of(null, 5)` throws `NullPointerException`,
   `NodeInput.of("", "sg")` throws `IllegalArgumentException`.
   Compact constructor validation is not bypassed.

5. **Collection-valued withers defensively copy** —
   Pass a mutable set/map, mutate it after the call, verify the
   record's copy is unaffected.

6. **Convenience helpers** —
   `withPad(0.5, 0.3, 0.7)` sets all three PAD fields.
   `withProperty("k", "v")` merges into existing properties.

**MemoryInput additions:**
- Existing tests for `withAttribute`, `withAttributes`, `withText`,
  `withPad` are not duplicated.
- New tests for `withCaseId` and `withConfidence` only.
- Test that `of()` factory is equivalent to the full constructor with
  defaults.

## Decisions

D58–D61 in `decisions.md`:

- **D58:** No space parameters — space-as-tenant model is a design
  mistake (#255). Builder APIs do not add spaceId.
- **D59:** CbrQuery-style `of()` + `withX()` pattern — proven in
  codebase, works naturally with records, no mutable intermediaries.
- **D60:** SubgraphInput excluded — 3 fields, all required, no benefit.
- **D61:** NodeUpdate uses `withX()` only — no accumulating helpers
  (`addTrait`, `removeTrait`), consistent with the other types.

## References

- CbrQuery.java — proven `of()` + `withX()` pattern (16 fields, 13 withers)
- MindMapQuery.java — 12-field record, current pain point
- NodeInput.java — 12-field record, current pain point
- EdgeInput.java — 11-field record, current pain point
- NodeUpdate.java — 13-field mutation record, current pain point
- MemoryInput.java — 10-field record, partially done (4 existing withers)
- SubgraphInput.java — 3-field record, excluded
- MindMapStoreContractTest.java — call sites showing positional-null pattern
- #255 — space model rearchitecture (space params excluded from builders)
- D58–D61 in specs/issue-253-cognitive-rearchitecture/decisions.md
