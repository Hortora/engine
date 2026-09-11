# Declarative Rule DSL — YAML Trait Rules and Derived Edge Rules

**Issue:** casehubio/neocortex#249
**Depends on:** casehubio/neocortex#247 (YAML schema conventions)
**Blocks:** #250 (YAML-to-Java compiler)

## Purpose

Design a declarative condition/action DSL that YAML can express for TraitRule and DerivedEdgeRule. YAML rules compile to the same Java interfaces so programmatic and declarative rules coexist. Programmatic rules handle complex logic; YAML rules handle common structural patterns.

## Scope

1. Condition DSL — 11 structural predicates for trait rule matching
2. Action DSL — two-level derived edge creation (direct + traversal)
3. DeclarativeRuleRegistry — CDI bean that loads, merges, and exposes compiled rules
4. Global rules + per-agent overrides via cognitive profile
5. DerivedEdgeDecorator + trait matching modifications for registry integration

---

## §1 Condition DSL

**11 structural predicates (D80)** for `TraitRule.matches(node, edges)`:

### §1.1 Property Predicates

| Predicate | YAML | Semantics |
|---|---|---|
| hasProperty | `hasProperty: birthday` | `node.property("birthday").isPresent()` |
| propertyEquals | `propertyEquals: {eventKind: scheduled}` | `node.property("eventKind").map("scheduled"::equals).orElse(false)` |
| propertyIn | `propertyIn: {status: [ACTIVE, CONFIRMED]}` | `node.property("status").map(v -> Set.of("ACTIVE","CONFIRMED").contains(v)).orElse(false)` |
| notHasProperty | `notHasProperty: deletedAt` | `node.property("deletedAt").isEmpty()` |

### §1.2 Edge Predicates

| Predicate | YAML | Semantics |
|---|---|---|
| hasEdgeType | `hasEdgeType: parent-of` | `edges.stream().anyMatch(e -> "parent-of".equals(e.edgeType()))` |
| hasEdgeTypes | `hasEdgeTypes: [parent-of, child-of, works-at]` | `edges.stream().anyMatch(e -> types.contains(e.edgeType()))` |
| hasAnyEdge | `hasAnyEdge: true` | `!edges.isEmpty()` |

### §1.3 Subgraph Predicates

| Predicate | YAML | Semantics |
|---|---|---|
| inSubgraphType | `inSubgraphType: PERSON` | Node's subgraph has the specified SubgraphType |

### §1.4 Combinators

| Combinator | YAML | Semantics |
|---|---|---|
| anyOf | `anyOf: [cond1, cond2]` | OR — true if any condition matches |
| allOf | `allOf: [cond1, cond2]` | AND — true if all conditions match |
| not | `not: {hasProperty: deletedAt}` | Negation — inverts the inner condition |

### §1.5 Complete YAML Examples

Equivalent to existing `PersonableTraitRule`:

```yaml
traitRules:
  - trait: Personable
    when:
      anyOf:
        - hasProperty: birthday
        - hasProperty: role
        - hasProperty: email
        - hasProperty: phone
        - hasEdgeTypes: [parent-of, child-of, works-at]
```

Equivalent to existing `AppointableTraitRule`:

```yaml
  - trait: Appointable
    when:
      propertyEquals:
        eventKind: scheduled
```

Equivalent to existing `ProjectlikeTraitRule`:

```yaml
  - trait: Projectlike
    when:
      anyOf:
        - hasProperty: status
        - hasProperty: startDate
        - hasProperty: endDate
        - hasEdgeTypes: [contributes-to, depends-on]
```

Complex condition with AND + NOT:

```yaml
  - trait: ActiveProject
    when:
      allOf:
        - propertyIn:
            status: [ACTIVE, IN_PROGRESS]
        - not:
            hasProperty: completedAt
        - hasEdgeType: contributes-to
```

---

## §2 Action DSL — Derived Edge Rules

**Two levels (D81):** direct derivation and traversal derivation.

### §2.1 Level 1 — Direct Derivation (Inverse/Flip)

For structural edge creation without graph traversal. The `trigger` reference provides access to the edge that fired the rule.

```yaml
derivedEdgeRules:
  - name: inverse-knows
    on:
      edgeType: knows
    derive:
      - edgeType: known-by
        source: trigger.target
        target: trigger.source
```

**Trigger references:**
- `trigger.source` — source node ID of the triggering edge
- `trigger.target` — target node ID of the triggering edge

**Optional fields on `derive` entries:**
- `confidence` — Confidence shorthand (scalar or object per D69)
- `properties` — `Map<String, String>` additional properties on the derived edge

Example with properties:

```yaml
  - name: bidirectional-colleague
    on:
      edgeType: works-with
    derive:
      - edgeType: works-with
        source: trigger.target
        target: trigger.source
        properties:
          derived-reason: bidirectional
```

### §2.2 Level 2 — Traversal Derivation (Transitive Closure)

Optional `traverse` block walks the graph following a specified edge type. The `derive` block fires once per reached node.

```yaml
  - name: descendant-chain
    on:
      edgeType: child-of
    traverse:
      follow: child-of
      from: trigger.target
      direction: outbound
      maxDepth: 3
    derive:
      - edgeType: descendant-of
        source: trigger.source
        target: traversal.node
```

**Traverse fields:**
- `follow` (required) — edge type to follow
- `from` (required) — starting point: `trigger.source` or `trigger.target`
- `direction` (required) — `outbound` or `inbound`
- `maxDepth` (optional, default 3) — maximum traversal depth, capped by DerivedEdgeDecorator's own depth limit

**Traversal reference:**
- `traversal.node` — the current node reached during the walk

**Trigger conditions:**
- `on.edgeType` — single edge type trigger
- `on.edgeTypes` — list of edge types (rule fires if any match)

### §2.3 Complete Example — Organisational Hierarchy

```yaml
derivedEdgeRules:
  - name: inverse-reports-to
    on:
      edgeType: reports-to
    derive:
      - edgeType: manages
        source: trigger.target
        target: trigger.source

  - name: org-chain
    on:
      edgeType: reports-to
    traverse:
      follow: reports-to
      from: trigger.target
      direction: outbound
      maxDepth: 5
    derive:
      - edgeType: in-org-chain
        source: trigger.source
        target: traversal.node
```

---

## §3 YAML File Locations

**Global rules + per-agent overrides (D83).**

### §3.1 Global Rules

Classpath: `rules/*.yaml` (configurable via `casehub.cognitive.rules.path`, default `rules/`).

```yaml
# rules/mindmap-rules.yaml
traitRules:
  - trait: Personable
    when:
      anyOf:
        - hasProperty: birthday
        - hasProperty: role
        - hasEdgeTypes: [parent-of, child-of]

derivedEdgeRules:
  - name: inverse-knows
    on:
      edgeType: knows
    derive:
      - edgeType: known-by
        source: trigger.target
        target: trigger.source
```

### §3.2 Per-Agent Rules

Inside `cognitive-profiles/*.yaml` (existing CognitiveDefaults files):

```yaml
# cognitive-profiles/alice.yaml
agentId: alice
tenantId: family-1

personality:
  experience: 1.5

traitRules:
  - trait: FamilyMember
    when:
      hasEdgeTypes: [child-of, parent-of, sibling-of]

derivedEdgeRules:
  - name: inverse-knows
    on:
      edgeType: knows
    derive:
      - edgeType: known-by
        source: trigger.target
        target: trigger.source
        properties:
          perspective: alice
```

### §3.3 Merge Semantics

For each agent:
1. Load all global rules
2. Load agent-specific rules from their cognitive profile
3. Merge: **agent rule with the same name as a global rule overrides it** (global is suppressed)
4. Agents without cognitive profiles get only global rules

Name collision resolution is deterministic: local wins, no partial merge. If alice defines `inverse-knows`, her version replaces the global `inverse-knows`. All other global rules still apply.

---

## §4 Intermediate Types

### §4.1 Condition Model

Sealed interface in mindmap-api (zero deps):

```java
public sealed interface RuleCondition {
    record HasProperty(String name) implements RuleCondition {}
    record PropertyEquals(String name, String value) implements RuleCondition {}
    record PropertyIn(String name, Set<String> values) implements RuleCondition {}
    record NotHasProperty(String name) implements RuleCondition {}
    record HasEdgeType(String edgeType) implements RuleCondition {}
    record HasEdgeTypes(Set<String> edgeTypes) implements RuleCondition {}
    record HasAnyEdge() implements RuleCondition {}
    record InSubgraphType(SubgraphType type) implements RuleCondition {}
    record AnyOf(List<RuleCondition> conditions) implements RuleCondition {}
    record AllOf(List<RuleCondition> conditions) implements RuleCondition {}
    record Not(RuleCondition condition) implements RuleCondition {}

    boolean evaluate(MindMapNode node, List<MindMapEdge> edges);
}
```

`evaluate()` is a default method that pattern-matches on the sealed variants — the condition tree is both data (serializable to/from YAML) and executable (evaluates against a node).

### §4.2 Declarative Trait Rule

Record in mindmap-api:

```java
public record DeclarativeTraitRule(String traitName, RuleCondition when) 
    implements TraitRule {
    
    @Override
    public String traitName() { return traitName; }
    
    @Override
    public boolean matches(MindMapNode node, List<MindMapEdge> edges) {
        return when.evaluate(node, edges);
    }
}
```

### §4.3 Derived Edge Action Model

```java
public record EdgeDerivation(
    String edgeType,
    EdgeRef source,
    EdgeRef target,
    Confidence confidence,
    Map<String, String> properties
) {}

public enum EdgeRef {
    TRIGGER_SOURCE, TRIGGER_TARGET, TRAVERSAL_NODE
}

public record TraversalSpec(
    String follow,
    EdgeRef from,
    TraversalDirection direction,
    int maxDepth
) {
    public enum TraversalDirection { OUTBOUND, INBOUND }
}

public record DeclarativeDerivedEdgeRule(
    String name,
    Set<String> triggerEdgeTypes,
    TraversalSpec traverse,   // nullable — null means Level 1 (direct)
    List<EdgeDerivation> derivations
) implements DerivedEdgeRule {

    @Override
    public String name() { return name; }
    
    @Override
    public List<EdgeInput> derive(MindMapNode sourceNode, MindMapEdge trigger, 
                                   MindMapStore store) {
        if (!triggerEdgeTypes.contains(trigger.edgeType())) {
            return List.of();
        }
        if (traverse == null) {
            return deriveDirect(trigger);
        }
        return deriveWithTraversal(trigger, store);
    }
}
```

### §4.4 Type Placement

| Type | Module | Rationale |
|---|---|---|
| `RuleCondition` (sealed) | mindmap-api | Zero-dep predicate model, consumed by TraitRule interface in same module |
| `DeclarativeTraitRule` | mindmap-api | Implements TraitRule (same module) |
| `EdgeDerivation`, `EdgeRef`, `TraversalSpec` | mindmap-api | Zero-dep action model, consumed by DerivedEdgeRule in same module |
| `DeclarativeDerivedEdgeRule` | mindmap-api | Implements DerivedEdgeRule (same module) |
| `DeclarativeRuleRegistry` | mindmap-intelligence | CDI bean, depends on cognitive-index (CognitiveDefaultsRegistry) |
| YAML deserializers | mindmap-intelligence | Jackson YAML parsing, depends on mindmap-api types |

---

## §5 DeclarativeRuleRegistry

**Location (D82, D84):** `io.casehub.neocortex.mindmap.intelligence` in mindmap-intelligence.

```java
@ApplicationScoped
public class DeclarativeRuleRegistry {

    @Inject Instance<CognitiveDefaultsRegistry> cognitiveDefaults;
    
    private List<DeclarativeTraitRule> globalTraitRules = List.of();
    private List<DeclarativeDerivedEdgeRule> globalDerivedRules = List.of();

    @PostConstruct
    void init() {
        // Load global rules from rules/ classpath
        // Parse YAML, build DeclarativeTraitRule + DeclarativeDerivedEdgeRule
    }

    public List<TraitRule> traitRules(String agentId) {
        var merged = new LinkedHashMap<String, TraitRule>();
        globalTraitRules.forEach(r -> merged.put(r.traitName(), r));
        if (cognitiveDefaults.isResolvable()) {
            cognitiveDefaults.get().forAgent(agentId).ifPresent(defaults -> {
                if (defaults.traitRules() != null) {
                    defaults.traitRules().forEach(r -> merged.put(r.traitName(), r));
                }
            });
        }
        return List.copyOf(merged.values());
    }

    public List<DerivedEdgeRule> derivedEdgeRules(String agentId) {
        var merged = new LinkedHashMap<String, DerivedEdgeRule>();
        globalDerivedRules.forEach(r -> merged.put(r.name(), r));
        if (cognitiveDefaults.isResolvable()) {
            cognitiveDefaults.get().forAgent(agentId).ifPresent(defaults -> {
                if (defaults.derivedEdgeRules() != null) {
                    defaults.derivedEdgeRules().forEach(r -> merged.put(r.name(), r));
                }
            });
        }
        return List.copyOf(merged.values());
    }
}
```

### §5.1 DerivedEdgeDecorator Modification

```java
@Inject
public DerivedEdgeDecorator(@Delegate @Any MindMapStore delegate,
                            Instance<DerivedEdgeRule> programmaticRules,
                            Instance<DeclarativeRuleRegistry> registry) {
    List<DerivedEdgeRule> allRules = new ArrayList<>(programmaticRules.stream().toList());
    if (registry.isResolvable()) {
        // agentId resolution depends on caller context — 
        // for now, load all global rules (no agent context in store layer)
        allRules.addAll(registry.get().derivedEdgeRules(null));
    }
    this.rules = List.copyOf(allRules);
}
```

**Agent context note:** The MindMapStore layer doesn't know which agent is calling. For v1, the decorator loads global rules + rules for all agents (deduped by name). Per-agent rule scoping requires an agent context that `#251` (Identity-cognition derivation) will provide. For now, all declarative rules are effectively global — per-agent override only matters when two agents define the same rule name differently, in which case the last-loaded profile wins.

---

## §6 CognitiveDefaults Extension

`CognitiveDefaults` record gains two new nullable fields:

```java
public record CognitiveDefaults(
    String agentId,
    String tenantId,
    PersonalityWeights personality,
    MoodBaseline moodBaseline,
    CuriosityConfig curiosity,
    TemporalFocusConfig temporalFocus,
    MindMapVocabulary vocabulary,
    Map<String, String> services,
    List<DeclarativeTraitRule> traitRules,          // new
    List<DeclarativeDerivedEdgeRule> derivedEdgeRules  // new
) { ... }
```

Both nullable — null means "no per-agent rules, use global only."

This requires `mindmap-api` dependency in cognitive-index for `DeclarativeTraitRule` and `DeclarativeDerivedEdgeRule`. cognitive-index already depends on mindmap-api — no new dependency edge.

---

## §7 YAML Deserialization

Jackson custom deserializers in mindmap-intelligence for the condition and action models:

### §7.1 RuleConditionDeserializer

Reads a YAML mapping and determines the predicate type from the key:
- Single key `hasProperty: name` → `HasProperty("name")`
- Single key `propertyEquals: {k: v}` → `PropertyEquals("k", "v")`
- Single key `anyOf: [...]` → `AnyOf(List.of(...))`
- Single key `not: {...}` → `Not(deserialize(inner))`

The condition tree is recursive — `anyOf` and `allOf` contain nested conditions.

### §7.2 DerivedEdgeRule Deserializer

Reads `on`, optional `traverse`, and `derive` sections. Resolves `trigger.source`/`trigger.target`/`traversal.node` string references to `EdgeRef` enum values.

---

## §8 Testing Strategy

### §8.1 Unit Tests

- `RuleConditionTest` — evaluate each of 11 predicates against mock nodes/edges
- `DeclarativeTraitRuleTest` — parse YAML trait rules, verify matches
- `DeclarativeDerivedEdgeRuleTest` — parse YAML derived rules, verify edge creation (Level 1 + Level 2)
- `DeclarativeRuleRegistryTest` — global/per-agent merge, name-based override

### §8.2 Integration Tests

- `DeclarativeRuleIntegrationTest` — full flow: YAML → compile → register → DerivedEdgeDecorator fires rule → verify derived edges in store

### §8.3 Parity Tests

- For each of the 7 existing programmatic TraitRules, write the YAML equivalent and verify both produce identical results on the same test data

---

## §9 Convention Summary

| Concern | Convention | Decision |
|---|---|---|
| Condition predicates | 11 structural predicates | D80 |
| Derived edge actions | Direct + traversal (2 levels) | D81 |
| Module placement | mindmap-intelligence (compiler) + mindmap-api (types) | D82 |
| Rule locations | Global `rules/` + per-agent in cognitive profile | D83 |
| Merge semantics | Local name overrides global | D83 |
| CDI integration | DeclarativeRuleRegistry + decorator modification | D84 |
| YAML key naming | camelCase (from D65) | D65 |

---

## References

- casehubio/neocortex#249 — this issue
- casehubio/neocortex#247 — YAML schema conventions
- docs/specs/2026-09-01-yaml-schema-conventions.md — conventions doc
- DerivedEdgeRule.java — `derive(sourceNode, trigger, store) → List<EdgeInput>`
- TraitRule.java — `matches(node, edges) → boolean`
- DerivedEdgeDecorator.java — recursive rule application with depth limit
- PersonableTraitRule.java — property + edge type pattern
- ProjectlikeTraitRule.java — property + edge type pattern
- AppointableTraitRule.java — property value equality pattern
- CognitiveDefaults.java — per-agent config record
- CognitiveDefaultsRegistry.java — classpath YAML loading
