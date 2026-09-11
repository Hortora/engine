# Knowledge Representation Model — Design Spec

**Date:** 2026-09-09
**Epic:** #285 (Knowledge Representation Model — triples, cores, and dynamic type semantics)
**Children:** #278 (design), #281 (SubgraphType → dynamic string), #282 (dynamic property schema)
**Status:** Final

---

## 1. Problem Statement

The casehub cognitive platform has a knowledge graph (MindMap) that already functions as a Thing system: MindMapNode carries properties, traits, and edges; TraitRule evaluates interfaces from properties/edges; TraitProxy creates typed JDK Proxy views. But this is implicit — there is no formal Thing concept, no `is()`/`as()` API, no core-type registry, and the trait-to-interface bridge isn't wired to Subject (the typed entity reference in memory-api).

Three gaps:

1. **No formal Thing type.** The knowledge representation base concept exists informally but has no interface. Consumers must know about TraitProxy, call `traits().contains()` manually, and depend on the full MindMapNode with its cognitive features (confidence, PAD, provenance) even when they only want identity, properties, and traits.
2. **Fixed SubgraphType enum.** The 6-value enum (PERSON, PROJECT, RESEARCH_AREA, ORGANISATION, CONCEPT, GENERAL) prevents runtime type discovery by the LLM.
3. **No property schema for dynamic types.** Core types get schema from Java interfaces (Personable defines birthday, role, email, phone). Dynamic types (LLM-discovered) have no schema definition.

### 1.1 Prior Art

From Mark's Drools/PHREAK work: a Thing system where ThingInstances start with a core type, accumulate triples (properties) over time, and rules dynamically determine which Java interfaces the Thing satisfies. MindMap already implements this pattern informally — this epic formalizes it.

### 1.2 Design Principles

- **Core types feel like Java.** Typed fields, IDE support, compile-time safety via trait interfaces.
- **Dynamic types feel like maps with conventions.** String-keyed properties, no recompile needed.
- **Promotion is a conscious developer act.** When a dynamic type crystallises, a developer creates a Java interface. Future automation possible but out of scope.
- **Not OWL-DL.** Lightweight type semantics — type hierarchy as data, property schemas as metadata, advisory validation. No formal reasoner.
- **Not RDFS-style "everything is a triple."** Core objects are normal interfaces. Properties and edges are the flexible layer underneath.

## 2. Architecture

### 2.1 Base Interface Hierarchy — Content vs Cognition

```
Thing (interface, thing-api, zero deps)
  — id(), name(), type(), property(), properties(), traits()
  — is(), as()  [default methods]

    └── MindMapNode (interface, mindmap-api, extends Thing)
        — confidence(), PAD, temporal bounds, provenance
        — principalId(), sharedWith(), refs()
        — subgraphId(), subgraphType()
```

**Thing** is the semantic knowledge representation base. Identity, properties, traits, and type — what something IS. Every entity in the knowledge graph is a Thing.

**MindMapNode** extends Thing with cognitive features — what the agent BELIEVES about the entity. Epistemic certainty (confidence), emotional assessment (PAD), temporal validity, provenance, visibility controls.

All MindMapNodes are Things. Content nodes (notes, links) and semantic entities (persons, projects) are both Things — they differ by type and richness of properties/traits.

### 2.2 Dependency Direction

```
thing-api              (zero deps)
    ↑
mindmap-api            (depends on thing-api + cognitive-api + platform-api)
    ↑
mindmap-intelligence   (depends on mindmap-api, TypeRegistry CDI bean)
    ↑
cognitive-index        (depends on mindmap-intelligence)
```

Consumers who want semantic knowledge representation depend on thing-api only (zero deps). Cognitive system components depend on mindmap-api for the full picture.

### 2.3 Module Changes

```
thing-api/              — NEW: zero deps, tier-0. Thing interface,
                          JDK Proxy handler for as()
mindmap-api/            — MODIFIED: MindMapNode extends Thing,
                          SubgraphType enum → String + SubgraphTypes constants,
                          MindMapNode gains subgraphType(),
                          RuleCondition.InSubgraphType uses String
mindmap-intelligence/   — MODIFIED: TypeRegistry CDI bean,
                          TraitProxy.as() deprecated (delegates to Thing.as())
mindmap/                — MODIFIED: CognitiveLoader delegates type bootstrap
                          to TypeRegistry, store implementations resolve
                          subgraphType() on nodes
```

## 3. Thing Interface

```java
package io.casehub.neocortex.thing;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface Thing {

    String id();
    String name();
    String type();

    Optional<String> property(String key);
    Map<String, String> properties();

    Set<String> traits();

    default boolean is(String traitName) {
        return traits().contains(traitName);
    }

    default <T> T as(Class<T> traitInterface) {
        if (!traitInterface.isInterface()) {
            throw new IllegalArgumentException(
                "Trait must be an interface: " + traitInterface.getName());
        }
        return ThingProxyHandler.createProxy(this, traitInterface);
    }
}
```

### 3.1 Key Characteristics

- **Read-only projection.** Thing is a snapshot of entity state. Consumers re-query MindMapStore for fresh state.
- **No edges.** Relationships are a graph concern queried via MindMapStore. Properties ARE the datatype triples (this, key, value). Edge queries give the object triples.
- **No tenantId.** Tenant context is ambient (CDI principal or passed alongside).
- **type() returns the subgraph type** — the entity's primary identity type. A node in a "person" subgraph has type "person". A note in a "general" subgraph has type "general".

### 3.2 ThingProxyHandler

Package-private class in thing-api. Maps interface method names to `thing.property(methodName)` calls with return type coercion:

| Return type | Coercion |
|-------------|----------|
| String | property value as-is |
| Optional\<String\> | Optional.ofNullable(property value) |
| Integer / int | Integer.parseInt |
| Long / long | Long.parseLong |
| Double / double | Double.parseDouble |
| Boolean / boolean | Boolean.parseBoolean |

Missing property behavior depends on return type:
- `Optional<String>` → `Optional.empty()`
- Boxed types (String, Integer, Long, Double, Boolean) → `null`
- Primitive types (int, long, double, boolean) → type default (0, 0L, 0.0, false)

Handles toString (node id + trait class), hashCode (node id hash), equals (same node id + same trait class).

This consolidates the existing TraitInvocationHandler from mindmap-intelligence (51 lines of type coercion logic) into thing-api. Boolean coercion is new — an enhancement over the existing handler.

### 3.3 Trait Interfaces

Platform-provided trait interfaces (Personable, Projectlike, Organisational, Eventlike) remain in mindmap-intelligence as optional conveniences.

Consumers define domain-specific traits — `as()` works with ANY interface by convention:

```java
interface Customer {
    Optional<String> accountId();
    Optional<String> tier();
}

MindMapNode node = store.getNode(nodeId, tenantId);
Thing thing = node; // widening — MindMapNode IS a Thing
if (thing.is("Customer")) {
    Customer c = thing.as(Customer.class);
    c.accountId(); // reads property("accountId")
}
```

**Trait naming convention:** Trait names are PascalCase, matching the Java interface simple name. All existing platform traits follow this: "Personable", "Projectlike", "Organisational", "Appointable", "Aspirational", "Threatening", "Opportunistic". `Thing.is()` is case-sensitive — `is("Personable")` matches, `is("personable")` does not.

This is intentionally asymmetric with type names (which are lowercase — §4.0). Types are domain concepts ("person", "project"); traits are Java interface identifiers ("Personable", "Projectlike"). The casing reflects the origin: types come from data (SubgraphInput, LLM extraction), traits come from code (TraitRule implementations).

### 3.4 TraitProxy Migration

The existing `TraitProxy.as()` in mindmap-intelligence delegates to `Thing.as()`:

```java
@Deprecated
public static <T> T as(MindMapNode node, Class<T> traitInterface) {
    return node.as(traitInterface); // MindMapNode inherits as() from Thing
}
```

Existing callers migrate at their own pace. No breaking change.

## 4. SubgraphType → Dynamic String (#281)

Replace the `SubgraphType` enum with `String` throughout:

- `MindMapSubgraph.type()` → `String`
- `SubgraphInput.type()` → `String`
- `RuleCondition.InSubgraphType.type()` → `String`

Well-known types become constants in `SubgraphTypes`:

```java
package io.casehub.neocortex.mindmap;

public final class SubgraphTypes {
    public static final String PERSON = "person";
    public static final String PROJECT = "project";
    public static final String RESEARCH_AREA = "research-area";
    public static final String ORGANISATION = "organisation";
    public static final String CONCEPT = "concept";
    public static final String GENERAL = "general";
    public static final String TYPE_SYSTEM = "type-system";

    private SubgraphTypes() {}
}
```

Lowercase-normalized (same convention as `Subject.type()`).

### 4.0 Type String Normalization

`SubgraphInput` normalizes the type string in its compact constructor: `type = type.strip().toLowerCase()`. This matches Subject's normalization convention (`type = type.strip().toLowerCase()` in Subject's compact constructor) and prevents case-variant duplicates ("Person" vs "person" vs "PERSON"). `TypeRegistry.registerType()` creates subgraphs via `SubgraphInput`, inheriting the same normalization. The Flyway migration (§4.5) lowercases existing data as part of the enum → string conversion.

### 4.1 MindMapNode.subgraphType()

New method on MindMapNode:

```java
String subgraphType();
```

Returns the subgraph's type string for this node. Resolved by the store when constructing the node — the store already has `subgraphId()` and can resolve the subgraph's type via a join or cache lookup.

This is preferred over a magic `subgraph-type` property because:
- Type-safe and discoverable on the MindMapNode interface
- Cannot be overwritten by callers via NodeUpdate
- No consistency burden — value comes from the subgraph, not a copied property
- The store already resolves subgraph-dependent data during node construction

**Resolution approach by store:**
- **SqliteMindMapStore**: All node-returning queries (`getNode`, `nodesIn`, `search`, `resolveNode`) JOIN with `mindmap_subgraph` on `subgraph_id`. Cost is negligible — `subgraph_id` is the primary key of `mindmap_subgraph`, so each resolution is a single B-tree index lookup. The `toNode(ResultSet)` method reads the joined `type` column into the new `SqliteNode` field.
- **InMemoryMindMapStore**: `StoredNode` resolves the subgraph type at construction time from the in-memory `subgraphs` map (`subgraphs.get(subgraphId).type()`). Stored as a field on `StoredNode`.

### 4.2 InSubgraphType Implementation

```java
record InSubgraphType(String type) implements RuleCondition {
    @Override
    public boolean evaluate(MindMapNode node, List<MindMapEdge> edges) {
        return type.equals(node.subgraphType());
    }
}
```

Currently returns `false` — this gives it a real implementation.

### 4.3 Thing.type() Derivation

`Thing.type()` returns the same value as `MindMapNode.subgraphType()`. MindMapNode defines a default method that codifies this invariant:

```java
default String type() { return subgraphType(); }
```

This eliminates redundant implementation across `InMemoryMindMapStore.StoredNode`, `SqliteMindMapStore.SqliteNode`, `NoOpMindMapStore`, and any future MindMapNode implementor. The invariant lives in one place — no implementor can accidentally diverge.

After SubgraphType → String, the subgraph type IS the entity type. No separate `type` property needed — no consistency problem between two sources of truth.

### 4.4 MindMapExtractor Impact

Two changes to MindMapExtractor:

1. **Parsing**: `parseSubgraphType()` becomes `normalizeType()` — passes LLM type strings through with `strip().toLowerCase()` normalization instead of collapsing unknown types to GENERAL. Unknown types are registered via TypeRegistry on first encounter.

2. **Prompting**: The `SYSTEM_PROMPT` type constraint (`"type": "PERSON|PROJECT|..."`) becomes dynamic. At extraction time, MindMapExtractor queries TypeRegistry for the tenant's known types and constructs the prompt with the current type list, plus an explicit instruction that the LLM may propose new types not in the list. This closes the gap between enabling dynamic types (parsing) and activating them (prompting).

3. **Casing change**: `ExtractedEntity.subgraphType` will carry lowercase type strings (e.g., "person") instead of the current uppercase enum names ("PERSON") produced by `sgType.name()`. Downstream consumers comparing against hardcoded uppercase strings must be updated.

### 4.5 SQLite Data Migration

The SQLite `mindmap_subgraph` table stores type as the enum name (e.g., "PERSON"). A Flyway migration converts to lowercase strings ("person"). Store implementations updated to read/write strings.

## 5. Type Registry — Types as MindMap Nodes

Types are first-class data in MindMap, stored as nodes in a designated TYPE_SYSTEM subgraph. All access is mediated through a TypeRegistry CDI bean.

### 5.1 Type Subgraph Structure

```
TYPE_SYSTEM subgraph
├── node: "person"        (properties: java-class=io.casehub...Personable)
├── node: "project"       (properties: java-class=io.casehub...Projectlike)
├── node: "organisation"  (properties: java-class=io.casehub...Organisational)
├── node: "concept"       (no java-class — dynamic type)
├── node: "general"       (no java-class)
│
├── edge: "person" --subtype-of--> "general"
├── edge: "project" --subtype-of--> "general"
└── ...
```

- **Core types** carry a `java-class` property linking to their Java interface.
- **Dynamic types** are nodes without `java-class` — LLM-discovered types.
- **Type hierarchy** is formed by `subtype-of` edges — graph traversal.

Edge isolation is by construction, not enforcement: TypeRegistry is the sole writer of edges in the TYPE_SYSTEM subgraph. `MindMapStore.neighbors()` is node-scoped — querying neighbors of "Alice" returns Alice's domain edges, not type hierarchy edges, because type hierarchy edges connect type nodes to other type nodes, never to instance nodes. No validation is added to `MindMapStore.addEdge()` — store-level enforcement would couple the generic graph store to type-system-specific concepts, violating the layering.

### 5.2 TypeRegistry

`@ApplicationScoped` CDI bean in mindmap-intelligence. Mediates all type operations:

```java
public class TypeRegistry {
    boolean typeExists(String typeName, String tenantId);
    List<String> subtypesOf(String typeName, String tenantId);
    Optional<Class<?>> javaClass(String typeName, String tenantId);
    Map<String, SchemaField> schemaFor(String typeName, String tenantId);
    void registerType(String typeName, String tenantId);
    void registerType(String typeName, String parentType, String tenantId);
}
```

Uses `Instance<MindMapStore>` for graceful degradation.

### 5.3 Bootstrapping

TypeRegistry uses lazy bootstrapping: the TYPE_SYSTEM subgraph and core type nodes are created on first access for a given tenant, not eagerly at `@PostConstruct`. This avoids the problem of discovering which tenants exist at startup and automatically handles new tenants provisioned after the application starts.

Bootstrap sequence on first TypeRegistry call for a given tenant:
1. Warm cache: `listSubgraphs(tenantId)`, find TYPE_SYSTEM subgraph by type
2. If found, cache the subgraph ID and type nodes — done
3. If absent, create the TYPE_SYSTEM subgraph and core type nodes, cache the result

Thread safety: `ConcurrentHashMap.computeIfAbsent` on the per-tenant cache ensures at-most-once creation within a JVM instance (same pattern as `MindMapExtractor.findOrCreateSubgraph`). Cross-instance races (multiple JVMs starting against an empty database) are prevented by a unique constraint on `(tenant_id, type)` in `mindmap_subgraph`, added in the Flyway migration. On constraint violation (second JVM loses the race), TypeRegistry catches the `IllegalStateException` from `createSubgraph()`, re-queries `listSubgraphs(tenantId)` to find the subgraph the winning instance created, caches it, and continues normally. The constraint is on `type` (not `name`) because the type string ("type-system") is the semantic identity — a subgraph's human-readable name is presentation, not identity.

## 6. Subject ↔ Thing Bridge

Subject(String type, String id) in memory-api references a Thing by naming convention:

- `Subject.type()` == `Thing.type()` (both lowercase-normalized)
- `Subject.id()` == `Thing.id()` (MindMapNode UUID)

No code dependency between memory-api and thing-api. Resolution at the call site:

```java
Subject subject = Subject.of("person", nodeId);
MindMapNode node = store.getNode(subject.id(), tenantId);
Thing thing = node; // widening
assert thing.type().equals(subject.type());
```

## 7. Dynamic Property Schema (#282)

Property schema for dynamic types is stored as properties on type nodes in the TYPE_SYSTEM subgraph.

### 7.1 Convention

```
Type node "research-topic" properties:
  schema.title.type = string
  schema.title.required = true
  schema.field.type = string
  schema.methodology.type = string
  schema.year.type = number
```

Schema properties use the `schema.{fieldName}.{attribute}` naming convention. Supported types: string, number, boolean, date.

### 7.2 Access via TypeRegistry

`TypeRegistry.schemaFor(typeName, tenantId)` reads schema properties from the type node and returns structured `SchemaField` records. Consumers use this for:
- LLM prompt construction (expected properties for a type)
- Advisory validation (check if properties match schema)
- Documentation generation

No store-level enforcement. Schema is metadata for consumers, not constraints.

`SchemaField` is a record in mindmap-api:

```java
package io.casehub.neocortex.mindmap;

public record SchemaField(String name, String type, boolean required) {}
```

Supported `type` values: `"string"`, `"number"`, `"boolean"`, `"date"`. Lives in mindmap-api (not thing-api) because schema is a MindMap-layer concept — it describes type node metadata, not Thing identity. TypeRegistry in mindmap-intelligence uses it as a return type, and mindmap-intelligence already depends on mindmap-api.

### 7.3 Schema Lifecycle

1. **Platform bootstrap** — TypeRegistry derives schema for core types from their Java trait interfaces via reflection. For each core type with a `java-class` property, TypeRegistry:
   - Iterates declared methods of the interface (excluding Object methods and default methods)
   - Maps each method's return type to a schema type using the inverse of the ThingProxyHandler coercion table (§3.2): `String`/`Optional<String>` → `"string"`, `int`/`Integer`/`long`/`Long` → `"number"`, `double`/`Double` → `"number"`, `boolean`/`Boolean` → `"boolean"`
   - Uses the method name as the schema field name (e.g., `Personable.birthday()` → field "birthday", type "string")
   - All reflected fields default to `required = false` (trait interfaces use `Optional` return types — presence is a convention, not a constraint)
2. **LLM discovery** — The LLM extractor adds schema properties to type nodes when it discovers consistent property patterns. (Deferred: #292.)
3. **Developer promotion** — When creating a Java interface for a dynamic type, the developer updates the schema to match.

## 8. Promotion Path (Future)

When a dynamic type crystallises (stable schema, frequently queried), a developer creates a Java interface and adds the `java-class` property to the type node. This is a conscious developer act — no automation in this epic.

Future: code generation tooling that reads the type node's schema properties and generates a Java interface. Out of scope for #285. (Deferred: #293.)

## 9. Migration Impact

### 9.1 SubgraphType Callers

88 references to `SubgraphType` across the codebase. Migration is mechanical:
- `SubgraphType.PERSON` → `SubgraphTypes.PERSON`
- `SubgraphType.valueOf(s)` → direct string use (with `strip().toLowerCase()` normalization)
- Switch statements → if/else or pattern matching on strings

**cognitive-index impact**: `RuleConditionDeserializer` (line 75) uses `SubgraphType.valueOf(node.get("inSubgraphType").asText())` — must change to direct string use with lowercase normalization. Existing serialized rules contain uppercase enum names ("PERSON"); new rules use lowercase ("person"). The deserializer must normalize with `strip().toLowerCase()` to handle both formats transparently. `DeclarativeTraitRuleDeserializer` delegates to `RuleConditionDeserializer.parseCondition()`, so it is covered transitively.

### 9.2 SQLite Data Migration

Flyway migration to convert uppercase enum names to lowercase strings in the `mindmap_subgraph` table.

### 9.3 Store Implementations

`InMemoryMindMapStore.StoredNode` and `SqliteMindMapStore.SqliteNode` gain a `subgraphType()` method. `type()` is provided by the default method on MindMapNode (§4.3). The stores resolve the subgraph type when constructing nodes — SqliteMindMapStore via JOIN, InMemoryMindMapStore via cache lookup (§4.1).

### 9.4 TraitProxy

TraitProxy.as() in mindmap-intelligence deprecated, delegates to Thing.as(). TraitInvocationHandler logic consolidated into ThingProxyHandler in thing-api.

### 9.5 ARC42STORIES

ARC42STORIES.MD must be updated during implementation to include:
- `thing-api` in the layer table (L0 — zero deps, tier-0, shared with Hortora: no)
- `mindmap-api` layer entry updated to note Thing extension and SubgraphType → String
- TYPE_SYSTEM subgraph concept documented in the MindMap subsystem section (to be added)

## 10. Test Strategy

| Module | What's tested |
|--------|--------------|
| thing-api | Thing default methods: is() trait checking, as() proxy with type coercion (String, Integer, Long, Double, Boolean, Optional), ThingProxyHandler primitive default values for missing properties, ThingProxyHandler edge cases, **ArchUnit DependencyConstraintTest** (zero deps: no mindmap, cognitive, platform, Quarkus, Jakarta, Spring) |
| mindmap-api | SubgraphTypes constants, InSubgraphType real implementation via subgraphType(), MindMapNode extends Thing (compile check) |
| mindmap-intelligence | TypeRegistry (bootstrap, typeExists, subtypesOf, javaClass, schemaFor, registerType), TraitProxy deprecation delegation |
| mindmap | CognitiveLoader type subgraph bootstrap (idempotent, per-tenant) |
| mindmap-testing | MindMapStoreContractTest updated for String subgraph types, subgraphType() resolution |
| mindmap-sqlite | Flyway migration for SubgraphType enum → lowercase strings |

## References

- `mindmap-api/src/main/java/io/casehub/neocortex/mindmap/MindMapNode.java` — current interface, will extend Thing
- `mindmap-api/src/main/java/io/casehub/neocortex/mindmap/SubgraphType.java` — enum being replaced
- `mindmap-api/src/main/java/io/casehub/neocortex/mindmap/TraitRule.java` — trait evaluation SPI
- `mindmap-intelligence/src/main/java/io/casehub/neocortex/mindmap/intelligence/TraitProxy.java` — existing JDK Proxy (deprecated, delegating)
- `mindmap-intelligence/src/main/java/io/casehub/neocortex/mindmap/intelligence/TraitInvocationHandler.java` — logic moving to thing-api
- `mindmap-intelligence/src/main/java/io/casehub/neocortex/mindmap/intelligence/Personable.java` — core trait interface example
- `memory-api/src/main/java/io/casehub/neocortex/memory/Subject.java` — typed entity reference
- `mindmap-api/src/main/java/io/casehub/neocortex/mindmap/RuleCondition.java` — InSubgraphType placeholder
- Issue #278 — Thing model prior art and design principles
- Issue #281 — SubgraphType refactoring
- Issue #282 — Dynamic property schema
- `docs/specs/issue-253-cognitive-rearchitecture/decisions.md` — D1 (cognitive-api), D26 (acceptance criteria)
