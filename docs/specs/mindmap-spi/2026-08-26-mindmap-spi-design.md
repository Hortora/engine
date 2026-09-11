# MindMap SPI — Design Specification

## 0. Tracking

**Epic:** TBD — a tracking epic must be created in `casehubio/neocortex` before
implementation begins.

## 1. Problem Statement

The casehub platform has rich cognitive capabilities distributed across neocortex (memory
SPIs, CBR, experience, reflection, mood, engagement) and blocks (orchestrated agent
patterns with mental models, drives, strategies, narratives, user profiles). Each subsystem
stores knowledge in isolated backends — `CaseMemoryStore` for flat text memories,
`CbrCaseMemoryStore` for feature-vector similarity, and domain-specific CBR wrappers
(`CbrMentalModelStore`, `CbrStrategyStore`, `CbrUserProfileStore`, `CbrNarrativeStore`).

The missing capability is **structural knowledge** — typed relationships between entities
across subsystems. An agent knows facts about a user (mental model), has strategies for
interacting with them (strategy store), remembers conversations (narrative store), and
tracks engagement (engagement events). But these are disconnected silos. There is no way
to ask "what do I know about Alice across all subsystems?" without querying each store
separately and reconciling results.

The mind map SPI provides a graph of typed nodes and edges that serves as connective
tissue linking these subsystems. It is not a replacement for the existing stores — CBR
keeps doing similarity retrieval, `CaseMemoryStore` keeps storing text memories. The
graph adds the relationship layer that makes cross-system connections explicit, queryable,
and maintainable.

The mental model is "mind maps that merge" — each topic, person, or project is its own
map with internal structure, and the interesting knowledge emerges when maps connect.

### 1.1 Relationship to GraphCaseMemoryStore

`GraphCaseMemoryStore` (memory-api, issue #185) is a **memory store** — it stores text
memories (`Memory` records with `text()`, `domain()`, `entityId()`) and offers semantic
graph queries via `graphQuery(GraphMemoryQuery)`. The "graph" is the Graphiti backend's
internal representation; the SPI consumer sees `List<Memory>`, not graph structure. Its
purpose: _retrieve text facts semantically_ — "what do I know about Alice?" returns prose
facts like "Alice mentioned she prefers working remotely."

`MindMapStore` is a **structural knowledge graph** — typed nodes with properties, typed
edges with temporal bounds, subgraph partitioning, merge, alias resolution, vocabulary
governance, and cross-store references. Its purpose: _model entities and their
relationships_ — "how is Alice connected?" returns typed edges like `works-at → Company X`,
`parent-of → Bob`, `involved-in → Project Y`.

These are complementary, not overlapping:

| Concern | GraphCaseMemoryStore | MindMapStore |
|---|---|---|
| Data model | `Memory` (text + metadata) | `MindMapNode` + `MindMapEdge` (typed graph) |
| Query model | Semantic search (`graphQuery`) | Graph traversal (`neighbors`, `search`) |
| Storage | Text facts with entity scoping | Entities, relationships, properties |
| Example query | "What did Alice say about remote work?" | "Who does Alice work with?" |
| Backend | Graphiti (external service) | SQLite (embedded, self-contained) |

The MindMap graph references memories via `NodeRef(scheme="memory", id=memoryId)` — it
doesn't duplicate them. A consumer wanting "everything about Alice" queries the MindMap
for structural knowledge and follows NodeRefs to retrieve the underlying text memories
from `CaseMemoryStore`. This is unification by reference, not unification by duplication.

The SPI does not extend `GraphCaseMemoryStore` or `CaseMemoryStore` because they model
fundamentally different data. `CaseMemoryStore` stores text with `String store(MemoryInput)`
and retrieves via semantic search. `MindMapStore` stores typed graph structure with
`String addNode(NodeInput)` and retrieves via graph traversal. Forcing these into a
shared type hierarchy would require one to pretend to be the other — an adapter pattern
with no architectural benefit.

## 2. Architecture

### 2.1 Module Structure

New top-level module family, parallel to `memory-*`, `rag-*`, `inference-*`:

```
mindmap-api/          — SPI + value types (pure Java, zero deps)
mindmap/              — CDI wiring, NoOp default, decorators (vocabulary normalization,
                        derived edge rules, confidence decay), graph analysis
                        (pure computation — no LLM dependency)
mindmap-inmem/        — In-memory adapter for tests
mindmap-sqlite/       — SQLite adapter (day-one production backend)
mindmap-testing/      — Contract test suite + integration test scenarios
mindmap-intelligence/ — LLM extraction, gap detection, active learning, trait proxy,
                        curiosity engine (all require AgentProvider)
```

The SPI is standalone — it does not extend `CaseMemoryStore`, `GraphCaseMemoryStore`,
or `CbrCaseMemoryStore`. This avoids CDI bean displacement across repo boundaries
(GE-20260630-815259). Integration with other memory stores is via URI-style typed
references and CDI observers.

### 2.2 Dependency Direction

```
mindmap-api  ←  mindmap  ←  mindmap-sqlite
                         ←  mindmap-inmem
                         ←  mindmap-intelligence (also depends on mindmap-api)
                         ←  mindmap-testing

blocks       →  mindmap-api  (observers create/update graph from domain events)
engine       →  mindmap-api  (optional — query graph for routing/context)
```

The mindmap-api module has zero dependencies on other casehub modules. It defines its
own tenant model, capability enum, and value types.

### 2.3 Intelligence Layer Split

Following the CBR decorator pattern, intelligence concerns split into two tiers:

**Transparent (CDI `@Decorator` on `MindMapStore`):**
- Vocabulary normalization — alias resolution on every `addEdge()`
- Derived edge insertion — forward-chaining rules fire on edge creation
  (e.g., has-child → infer parent, father/mother)
- Confidence decay — applied at query time via decorator

**Computation (mindmap CDI module, no LLM dependency):**
- Graph analysis — centrality, clustering, structural hole detection
- Index maintenance — FTS rebuild, temporal index updates

**Explicit (mindmap-intelligence module, requires AgentProvider):**
- LLM-based entity/relationship extraction from conversation
- Gap detection and curiosity signal generation
- Active learning question generation
- Contradiction analysis and resolution prompts
- Trait proxy generation and truth maintenance

## 3. SPI — `MindMapStore`

### 3.1 Core Interface

```java
package io.casehub.neocortex.mindmap;

public interface MindMapStore {

    // --- Vocabulary ---
    void registerVocabulary(MindMapVocabulary vocabulary);

    // --- Nodes ---
    String addNode(NodeInput input, String tenantId);
    MindMapNode getNode(String nodeId, String tenantId);
    void updateNode(String nodeId, NodeUpdate update, String tenantId);

    // --- Edges ---
    String addEdge(EdgeInput input, String tenantId);
    MindMapEdge getEdge(String edgeId, String tenantId);
    void removeEdge(String edgeId, String tenantId);

    // --- Aliases ---
    void addAlias(String nodeId, String alias, String tenantId);
    void removeAlias(String nodeId, String alias, String tenantId);
    MindMapNode resolveNode(String nameOrAlias, String subgraphId,
                            String tenantId);  // subgraphId nullable

    // --- Merge ---
    MergeResult mergeNodes(String keepNodeId, String removeNodeId,
                           String tenantId);

    // --- Subgraphs ---
    String createSubgraph(SubgraphInput input, String tenantId);
    MindMapSubgraph getSubgraph(String subgraphId, String tenantId);
    List<MindMapNode> nodesIn(String subgraphId, String tenantId);
    List<MindMapEdge> bridgeEdges(String subgraphId, String tenantId);

    // --- Traversal ---
    List<MindMapEdge> neighbors(String nodeId, String tenantId);
    List<MindMapEdge> neighbors(String nodeId, String edgeType,
                                String tenantId);
    List<MindMapNode> search(MindMapQuery query);

    // --- Lifecycle ---
    void supersede(String targetId, String supersedingId, String reason,
                   String tenantId);
    void reinstate(String targetId, String tenantId);
    SupersessionStatus getSupersessionStatus(String targetId,
                                             String tenantId);

    // --- Erasure ---
    int eraseNode(String nodeId, String tenantId);
    int eraseSubgraph(String subgraphId, String tenantId);
    int eraseEntity(String entityName, String tenantId);
    int eraseEntityAcrossTenants(String entityName, Set<String> tenantIds);

    // --- Capabilities ---
    Set<MindMapCapability> capabilities();
    void requireCapability(MindMapCapability capability);
}
```

Every mutating and querying method requires `tenantId` for tenant isolation.
Implementations must enforce tenant boundaries — a node in tenant A is invisible
to queries in tenant B.

**Deletion semantics:**

All node deletion is hard deletion with cascading cleanup. There is no
separate `removeNode()` — following the existing store patterns where
`CaseMemoryStore` and `CbrCaseMemoryStore` provide only `erase*()` methods
for deletion. `eraseNode()` serves both application-level deletion ("this
node is wrong, remove it") and GDPR compliance erasure. The `int` return
value supports GDPR Art.5(2) audit logging in all cases.

- `eraseNode()` — hard-deletes a specific node by ID, including all edges,
  aliases, NodeRefs, and dynamic properties. Returns count of deleted records.
- `eraseSubgraph()` — hard-deletes all nodes in the subgraph plus the subgraph
  itself. Returns total count of deleted records.
- `eraseEntity()` — entity-scoped erasure. Finds all nodes whose `name` matches
  `entityName` (case-insensitive) OR who have an alias matching `entityName`,
  and erases them. This handles the "erase everything about Alice" case without
  requiring the caller to know node IDs.
- `eraseEntityAcrossTenants()` — cross-tenant entity erasure, matching
  `CaseMemoryStore.eraseEntityAcrossTenants()`. Caller must be a cross-tenant
  admin. Supply the complete set of tenantIds from the tenant management system.

Edge deletion uses `removeEdge()` — edges are structural elements without
independent identity for GDPR purposes. Edge deletion cascades nothing (edges
are leaf elements in the graph).

**`resolveNode()` — alias and name resolution:**

`subgraphId` is nullable. When null, resolution searches aliases across all
subgraphs within the tenant. Aliases are unique per tenant (§6.2), so
alias-based resolution always returns at most one node regardless of
`subgraphId`. When non-null, resolution is scoped to that subgraph — useful
for name-based disambiguation when multiple nodes share a name across
subgraphs.

**Traversal result sets:**

`neighbors()`, `nodesIn()`, and `bridgeEdges()` return structurally
complete, unbounded result sets. These are graph-structural queries, not
search — truncating neighbors arbitrarily would produce incorrect results
for graph analysis (centrality, clustering, structural hole detection).
The expected scale is per-agent mind maps (hundreds to low thousands of
nodes per tenant), not social-network-scale graphs. Backends may impose
an implementation cap (e.g., 10,000) with an exception if exceeded.

### 3.2 Node Model — Hybrid Core + Dynamic Properties

```java
public interface MindMapNode {
    // Core fields — always present, column-backed, indexed
    String id();
    String name();
    String subgraphId();
    ConfidenceOrigin confidenceOrigin();
    double confidence();         // [0.0, 1.0] — numeric, decayable
    String provenance();
    Instant createdAt();
    Instant updatedAt();
    Instant confirmedAt();       // last explicit confirmation (resets decay)
    Instant validFrom();         // nullable — temporal bound start
    Instant validUntil();        // nullable — temporal bound end
    Set<String> traits();        // applied trait names (opaque to SPI)
    Set<NodeRef> refs();         // references to entities in other stores

    // Affective metadata — optional
    Double pleasure();           // [-1, 1] positive/negative (PAD model)
    Double arousal();            // [-1, 1] high/low activation
    Double dominance();          // [-1, 1] empowering/threatening

    // Dynamic properties — EAV or JSON-backed
    Optional<String> property(String key);
    Map<String, String> properties();  // core + extension unified view
}
```

The `property(key)` method provides unified access — it resolves from core fields
first, then falls back to dynamic properties. Consumers don't know or care which
storage path a value takes. This enables compaction: when a dynamic property
appears consistently (e.g., "birthday" on nodes with the Personable trait), it
can be promoted to a core column. The proxy layer hides this migration.

### 3.3 Edge Model

```java
public interface MindMapEdge {
    String id();
    String sourceNodeId();
    String targetNodeId();
    String edgeType();           // governed by vocabulary
    ValidationTier tier();       // REGISTERED or UNVALIDATED
    ConfidenceOrigin confidenceOrigin();
    double confidence();         // [0.0, 1.0] — numeric, decayable
    String provenance();
    Instant createdAt();
    Instant updatedAt();
    Instant validFrom();         // nullable — temporal bound start
    Instant validUntil();        // nullable — temporal bound end

    // Affective metadata — optional
    Double pleasure();           // [-1, 1] positive/negative (PAD model)
    Double arousal();            // [-1, 1] high/low activation
    Double dominance();          // [-1, 1] empowering/threatening

    // Dynamic properties
    Optional<String> property(String key);
    Map<String, String> properties();
}
```

Edges carry a `ValidationTier` — `REGISTERED` edges have been normalised through
the controlled vocabulary; `UNVALIDATED` edges were accepted but flagged for review.
The intelligence layer can promote validated types from UNVALIDATED to REGISTERED.

### 3.4 Input and Update Types

```java
public record NodeInput(
    String name,
    String subgraphId,
    ConfidenceOrigin confidenceOrigin,
    Double confidence,          // nullable — defaults to origin's initial value
    String provenance,
    Set<String> traits,
    Set<NodeRef> refs,
    Instant validFrom,
    Instant validUntil,
    Double pleasure,
    Double arousal,
    Double dominance,
    Map<String, String> properties
) {}

public record NodeUpdate(
    String name,              // nullable — only update if non-null
    ConfidenceOrigin confidenceOrigin,
    Double confidence,
    Instant confirmedAt,      // nullable — explicit confirmation resets decay clock
    Set<String> traitsToAdd,
    Set<String> traitsToRemove,
    Set<NodeRef> refsToAdd,
    Set<NodeRef> refsToRemove,
    Instant validFrom,
    Instant validUntil,
    Double pleasure,
    Double arousal,
    Double dominance,
    Map<String, String> propertiesToSet,
    Set<String> propertiesToRemove
) {}

public record EdgeInput(
    String sourceNodeId,
    String targetNodeId,
    String edgeType,
    ConfidenceOrigin confidenceOrigin,
    Double confidence,          // nullable — defaults to origin's initial value
    String provenance,
    Instant validFrom,
    Instant validUntil,
    Double pleasure,
    Double arousal,
    Double dominance,
    Map<String, String> properties
) {}
```

### 3.5 Cross-Store References

```java
public record NodeRef(
    String scheme,      // "memory", "cbr", "experience", "external"
    String id,          // entity/case ID
    String qualifier    // domain, caseType, URL etc. — nullable
) {}
```

Loose coupling — the graph stores the reference without depending on the other
SPI's classpath. Resolution is lazy and requires the consuming module to have
the target SPI available.

**Lifecycle semantics:**

1. **Creation** — optimistic. The referenced entity is not validated at
   `NodeRef` creation time. The MindMap SPI has no dependency on the source
   store's classpath and cannot verify existence.

2. **GDPR erasure cascade** — when a source store erases an entity, the
   `mindmap` CDI module must be notified to remove all `NodeRef`s pointing
   to the erased entity. Under GDPR Art.17, references that identify a data
   subject are themselves personal data. Cleanup is proactive, not lazy.

   **Integration prerequisite:** a unified erasure notification event must
   be defined in `memory-api`. The existing pattern is `CbrCasesErased`
   (sealed interface with `ByRequest`, `ByEntity`, `ByScope` variants),
   fired by `ErasureNotificationCbrCaseMemoryStore` (`@Decorator
   @Priority(45)`). This covers `CbrCaseMemoryStore` erasures only.

   For MindMap NodeRef cleanup, either:
   - (a) Define a new `MemoryEntityErased` event in `memory-api` fired by
     a new `ErasureNotificationCaseMemoryStore` decorator on
     `CaseMemoryStore`, plus observe the existing `CbrCasesErased.ByEntity`,
     or
   - (b) Define a unified `EntityErased` event that both store decorators
     fire.

   Either approach requires a new decorator on `CaseMemoryStore`'s chain
   (which currently has no erasure notification decorator). This is a
   cross-cutting platform prerequisite — tracked as a separate issue
   before MindMap implementation begins.

3. **Non-GDPR dangling refs** — when a referenced entity is deleted for
   non-GDPR reasons (e.g., supersession, consolidation), the `NodeRef`
   becomes a curiosity signal (§4.3). The intelligence layer detects
   dangling refs during analysis and generates knowledge gap questions.

4. **Bidirectionality** — source stores do not know they are referenced.
   The GDPR cascade relies on CDI events, not bidirectional pointers.

### 3.6 Vocabulary

```java
public record MindMapVocabulary(
    List<EdgeTypeDefinition> edgeTypes
) {
    public static Builder builder() { ... }
}

public record EdgeTypeDefinition(
    String canonical,           // the normalised name
    Set<String> aliases,        // alternative names that map to canonical
    Double defaultDecayHalfLifeDays  // nullable — type-specific decay rate
) {}
```

Registration is additive — multiple `registerVocabulary()` calls merge
definitions. The store normalises alias edge types to their canonical form on
`addEdge()`. Unregistered types are accepted but stored with
`ValidationTier.UNVALIDATED`.

**Thread safety:** `registerVocabulary()` is synchronized — concurrent
registrations produce the correct union of edge types and aliases. The
runtime module uses a `ReadWriteLock`: vocabulary registration takes the
write lock; alias resolution on `addEdge()` takes the read lock.

**Retroactive normalization:** existing `UNVALIDATED` edges are NOT
retroactively promoted when a matching vocabulary is later registered.
Promotion happens only on explicit re-save or batch normalization via the
intelligence layer. This prevents silent data modification — an important
invariant for audit traceability.

**Canonical uniqueness:** canonical names must be globally unique across all
registered vocabularies. If vocabulary A defines canonical `"works-at"` and
vocabulary B also defines canonical `"works-at"` with a different alias set,
the aliases are merged. If vocabulary B defines `"employed-by"` as canonical
but vocabulary A already maps `"employed-by"` as an alias of `"works-at"`,
registration throws `VocabularyConflictException`.

### 3.7 Subgraphs

```java
public record SubgraphInput(
    String name,
    SubgraphType type,
    String rootNodeId     // nullable — can be set after creation
) {}

public record MindMapSubgraph(
    String id,
    String name,
    SubgraphType type,
    String rootNodeId,
    String tenantId,
    Instant createdAt
) {}

public enum SubgraphType {
    PERSON, PROJECT, RESEARCH_AREA, ORGANISATION, CONCEPT, GENERAL
}
```

Every node belongs to exactly one subgraph. Nodes that don't fit a specific
type go to a GENERAL subgraph. Cross-subgraph edges are "bridges" — queryable
via `bridgeEdges(subgraphId, tenantId)`.

### 3.8 Query

```java
public record MindMapQuery(
    String tenantId,
    String subgraphId,        // nullable — search across all subgraphs
    String text,              // nullable — text search across node names/properties
    String edgeType,          // nullable — filter by edge type
    Set<String> traits,       // nullable — filter by applied traits
    Double minConfidence,     // nullable — numeric threshold, applied after decay
    ConfidenceOrigin confidenceOrigin,  // nullable — filter by provenance
    boolean includeSuperseded,      // default false
    int limit
) {}
```

### 3.9 Merge

```java
public record MergeResult(
    String survivingNodeId,
    int edgesRepointed,
    int aliasesMerged,
    int duplicateEdgesRemoved,
    Set<String> traitsMerged,
    List<MergeConflict> propertyConflicts
) {}
```

Merge keeps `keepNodeId`, removes `removeNodeId`, unions their edges (deduplicating
by source+target+edgeType), unions aliases, unions traits, and reassigns subgraph
membership to the surviving node's subgraph.

**Edge deduplication resolution:** when two edges match on
source+target+edgeType but differ in confidence, temporal bounds, properties,
or provenance, the most-recently-updated edge wins (compared by `updatedAt`).
This is consistent with the property conflict resolution strategy below —
newer information is presumed more current. The discarded edge's data is not
recorded in `MergeResult` (edges are structural, not identity-bearing; the
`duplicateEdgesRemoved` count is sufficient for auditing).

**Property conflict resolution:** when both nodes have a dynamic property with
the same key but different values, the most-recently-updated node's value wins
(compared by `updatedAt`). Conflicting properties are recorded in
`MergeResult.propertyConflicts` for downstream review. Core fields (name,
confidence, provenance) always take the `keepNode`'s values.

```java
public record MergeConflict(
    String key,
    String keptValue,
    String discardedValue
) {}
```

### 3.10 Confidence and Decay

```java
public enum ConfidenceOrigin {
    STATED,       // directly asserted by a source — initial confidence 1.0
    INFERRED,     // derived by rules from other knowledge — initial confidence 0.7
    SPECULATED    // LLM-suggested, not confirmed — initial confidence 0.3
}
```

Confidence is a two-part model following the `CbrOutcome` pattern:
`ConfidenceOrigin` records **how** the knowledge was established (provenance
classification); `origin` is a numeric value in `[0.0, 1.0]` that is
subject to continuous decay.

When a node or edge is created without an explicit `origin` value, the
initial confidence is set from `ConfidenceOrigin`'s default (STATED=1.0,
INFERRED=0.7, SPECULATED=0.3). The caller can override the initial value.

Decay is applied at query time by the confidence decay decorator. The decay
formula is:

```
effectiveConfidence = confidence × 2^(-hoursSinceConfirmed / (halfLifeDays × 24))
```

**Edge decay rates:** each edge type can declare a `defaultDecayHalfLifeDays`
in its vocabulary definition (§3.6). Edges without a vocabulary decay rate
use a global default from the decorator's configuration.

**Node decay rates:** configured per `SubgraphType` via the decorator's
runtime configuration. Different knowledge categories decay at different
rates — a PERSON node's identity is stable (high half-life), while a
PROJECT node's status evolves frequently (low half-life). Recommended
defaults:

| SubgraphType | Half-life (days) | Rationale |
|---|---|---|
| PERSON | 365 | Identity is stable |
| PROJECT | 90 | Status evolves quarterly |
| ORGANISATION | 180 | Structure changes slowly |
| RESEARCH_AREA | 120 | Knowledge evolves moderately |
| CONCEPT | 730 | Definitions are durable |
| GENERAL | 180 | Conservative default |

These are decorator configuration, not SPI types — they live in the
`mindmap` CDI module's `@ConfigMapping`, not in `mindmap-api`.

**Explicit confirmation:** setting `confirmedAt` via `NodeUpdate` (§3.4)
resets the decay clock. If `origin` is also set in the same update,
the new confidence is used as the base; otherwise confidence is restored
to 1.0. This enables both "I re-confirmed this is true" (confirmedAt
only) and "I have new evidence at this confidence" (confirmedAt +
confidence together).

### 3.11 Capabilities

```java
public enum MindMapCapability {
    TRAVERSAL,
    MERGE,
    VOCABULARY,
    ALIAS,
    SUBGRAPH,
    SEARCH,
    SUPERSESSION,
    ERASE_NODE,
    ERASE_SUBGRAPH,
    ERASE_ENTITY,
    CROSS_TENANT_ERASE,
    GRAPH_ANALYSIS
}
```

### 3.12 Supersession

```java
public record SupersessionStatus(
    String targetId,
    boolean superseded,
    Instant supersededAt,
    String supersedingId,
    String reason,
    Instant reinstatedAt
) {
    public boolean wasReinstated() {
        return reinstatedAt != null;
    }
}
```

## 4. Blocks Integration

### 4.1 The Problem of Isolated Cognitive Stores

Blocks' `InnerLifeOrchestrator` coordinates multiple cognitive subsystems, each with
its own CBR backend:

| Orchestrator | CBR Store | What it models |
|---|---|---|
| MentalModelOrchestrator | CbrMentalModelStore | BDI beliefs about users/agents |
| StrategyLearningOrchestrator | CbrStrategyStore | Interaction strategies |
| UserModelOrchestrator | CbrUserProfileStore | User preferences/profiles |
| NarrativeOrchestrator | CbrNarrativeStore | Conversation narratives |
| MoodOrchestrator | (neocortex MoodState) | Agent emotional state |
| DriveOrchestrator | — | Motivational drives |
| MemoryHygieneOrchestrator | CbrCaseMemoryStore | Memory consolidation |

These stores are silos. The mind map graph makes their connections explicit.

### 4.2 Integration Pattern

CDI observers on blocks' domain events create/update nodes and edges:

- `MentalStateSignal` → creates/updates belief nodes in the user's subgraph
- `EngagementSignal` → creates engagement edges between agent and user nodes
- `DriverEvent` → updates drive-related properties on agent nodes
- `NarrativeSynthesisTick` → creates narrative nodes with prose content, linked to
  participants
- `StrategyLearningTick` → creates strategy nodes linked to situations and users

The CBR stores continue to own domain-specific similarity retrieval. The graph adds
cross-system traversal — "what do I know about Alice?" traverses from Alice's node
through mental model edges, strategy edges, narrative edges, and engagement edges.

### 4.3 Curiosity Engine Integration

The mind map intelligence layer participates in `InnerLifeOrchestrator`'s tick loop.
On each tick, `MindMapAnalyzer` computes curiosity signals:

**Structural signals:**
- Sparse subgraphs — "I know Alice's name but nothing about her role"
- Orphan nodes — isolated knowledge with no connections
- Structural holes — dense clusters with no bridges between them
- Low clustering — a node's neighbors aren't connected to each other

**Quality signals:**
- Low-confidence clusters — many INFERRED/SPECULATED edges
- Contradiction clusters — conflicting current edges on the same node
- UNVALIDATED edges — vocabulary not yet governed
- Dangling NodeRefs — references to entities that may no longer exist

**Temporal signals:**
- Stale regions — nodes/edges not updated or confirmed recently
- Growth/decay rates — which subgraphs are expanding vs static
- Supersession chains — factual evolution over time

**Centrality signals:**
- Betweenness — bridge nodes worth knowing more about
- Degree — highly-connected entities worth keeping fresh

**Proximity signals:**
- Temporal proximity — nodes with future dates (events, deadlines, aspirations)
  act as attention magnets. As a date approaches, the curiosity engine
  intensifies knowledge-seeking in connected areas. "Visiting parents next
  Saturday" drives questions about the parents, the trip, what they want to
  discuss. After events pass, the engine checks: did it happen? what was the
  outcome?
- Spatial proximity — location-aware nodes. If the user is travelling to
  London, knowledge about London, people in London, London-based projects all
  get elevated.

A "calendar" is simply a query over temporally-marked nodes — no separate
calendar store needed. Future-dated nodes are regular graph nodes with temporal
properties that the analysis layer indexes for proximity-based prioritization.

These signals feed `CuriosityDrive` to generate questions, and feed
`MentalModelOrchestrator` to direct inference attention.

### 4.4 Affective Knowledge

Nodes and edges carry optional PAD-model annotations (pleasure, arousal, dominance)
representing the emotional character of the knowledge itself — not the agent's mood.
"Alice was promoted" carries positive pleasure. "Alice lost her job" carries negative
pleasure. The field is named `pleasure` — consistent with `MoodState.pleasure()`,
`MoodAttributeKeys.PLEASURE`, and `MoodModulatedRetrieval` throughout the existing
codebase. This enables:

- **Mood-congruent retrieval** — `MoodModulatedRetrieval` can weight graph results
  by affect alignment with the agent's current mood
- **Affect-aware curiosity** — the curiosity engine avoids probing sensitive topics
  when inappropriate
- **Emotional context** — narrative synthesis can assess the emotional arc of a
  relationship or situation

## 5. Trait System

### 5.1 SPI Boundary

The SPI stores trait names as opaque `Set<String>` metadata on nodes. It does not
know what trait names mean, how they are applied, or what interfaces they represent.

### 5.2 Intelligence Layer (mindmap-intelligence)

The intelligence layer owns:

- **Trait interfaces** — Java interfaces (`Personable`, `Projectlike`,
  `Organisational`) with typed accessors that read from node properties
- **Proxy generation** — creates runtime proxies that implement trait interfaces,
  abstracting over core fields and dynamic properties
- **Forward-chaining rules** — determine when traits should be applied or retracted
  based on node properties and edges. "If a node has 'birthday' property and edges
  of type 'parent-of', apply Personable trait."
- **Truth maintenance** — when the evidence for a trait is retracted (e.g., the
  'birthday' property is removed), the trait is automatically retracted
- **Compaction** — over time, when a property appears consistently on all nodes
  with a given trait, it can be promoted from dynamic properties to a core field.
  The proxy hides this migration from consumers.

#### 5.2.1 Rule Specification

Rules are Java CDI beans implementing `TraitRule`:

```java
public interface TraitRule {
    String traitName();
    boolean matches(MindMapNode node, List<MindMapEdge> edges);
}
```

**Evaluation trigger:** rules fire on node mutation — `addNode()`,
`updateNode()`, `addEdge()`, `removeEdge()`. The decorator intercepts
these calls and re-evaluates applicable rules for the affected node(s).
`addNode()` is included because a node created with properties matching a
rule (e.g., a node with a "birthday" property) must receive its traits
immediately — not after a subsequent `updateNode()`. Rules do NOT fire on
every query — only on state changes.

**Conflict resolution:** if multiple rules target the same trait on the same
node, application wins over retraction (a trait is applied if ANY rule
matches, retracted only when NO rules match). This is conservative — the
intelligence layer can always retract explicitly via `traitsToRemove`.

**Cycle prevention:** derived edge insertion from trait application (e.g.,
applying `Personable` creates a `person-type` edge) is limited to a maximum
chain depth of 3. If a derived edge triggers a rule that triggers another
derived edge, the chain terminates after 3 levels. Deeper chains indicate a
rule design problem, not a legitimate knowledge structure.

### 5.3 Future Reconciliation with Drools

The trait system is intentionally modelled after Drools Traits but implemented
independently. A future integration could delegate trait management to Drools when
available, using the same interfaces and rule definitions.

## 6. Storage Backends

### 6.1 In-Memory (`mindmap-inmem`)

`InMemoryMindMapStore` — `ConcurrentHashMap`-based implementation for tests.
`@Alternative @Priority(1)`. Supports all capabilities. Provides `clearAll()`
for test isolation.

### 6.2 SQLite (`mindmap-sqlite`)

`SqliteMindMapStore` — SQLite + HikariCP WAL + Flyway migrations. Production
backend for single-node deployments.

Schema:
```
nodes          — core columns + JSON properties blob
edges          — source, target, type, tier, confidence, temporal bounds, properties
aliases        — node_id, alias (unique per tenant)
subgraphs      — id, name, type, root_node_id
node_refs      — node_id, scheme, ref_id, qualifier
supersessions  — target_id, superseding_id, reason, timestamps
```

FTS5 index on node names and properties for text search. Indexes on edge type,
source/target, subgraph membership.

### 6.3 Future Backends

- **TinkerPop/ArcadeDB** — if traversal complexity outgrows recursive CTEs
- **Qdrant** — if semantic similarity search over node content is needed
- **PostgreSQL** — if deployed alongside existing JPA infrastructure

The SPI insulates consumers from backend changes. The capability enum allows
backends to declare which operations they support.

## 7. Testing

### 7.1 Contract Tests (`mindmap-testing`)

`MindMapStoreContractTest` — abstract base class that all backends must pass.
Tests cover:

- Node CRUD (add, get, update, remove)
- Edge CRUD with vocabulary normalization
- Alias add/remove/resolve
- Merge (edge deduplication, alias union, trait union, subgraph reassignment)
- Subgraph creation and membership
- Bridge edge detection
- Neighbor traversal (all types, filtered by type)
- Text search
- Vocabulary registration (registered normalisation, unvalidated acceptance)
- Supersession/reinstatement lifecycle
- Tenant isolation (cross-tenant invisibility)
- Cascading erasure (node removes edges, aliases, refs)
- Subgraph erasure
- Confidence levels and decay
- NodeRef storage and retrieval
- Trait metadata storage
- Affective annotations
- Capability self-description

### 7.2 Integration Tests

Dedicated integration tests that verify cross-store bridging:

- An experience recorded via `ExperienceStream` → observer creates a graph node
  and edges → querying the graph returns the connected structure
- A mental model signal from blocks → observer creates belief nodes in user
  subgraph → traversal from user node reaches the beliefs
- NodeRef to a CBR case → case is superseded in CBR → dangling ref detected by
  analysis
- Vocabulary normalization decorator fires transparently on addEdge
- Derived edge rules fire (has-child → parent edge created) and truth maintenance
  retracts when evidence removed

## 8. Module Coordinates

| Element | Value |
|---|---|
| groupId | `io.casehub` |
| Artifact prefix | `casehub-neocortex-mindmap-` |
| MindMap API | `casehub-neocortex-mindmap-api` |
| MindMap CDI | `casehub-neocortex-mindmap` |
| MindMap In-Memory | `casehub-neocortex-mindmap-inmem` |
| MindMap SQLite | `casehub-neocortex-mindmap-sqlite` |
| MindMap Testing | `casehub-neocortex-mindmap-testing` |
| MindMap Intelligence | `casehub-neocortex-mindmap-intelligence` |
| Root Java package | `io.casehub.neocortex.mindmap` |

## 9. ARC42STORIES Integration

### Journey

| Journey | Description | Chapters | Status |
|---|---|---|---|
| J6 Structural Knowledge | MindMap graph SPI — typed nodes, edges, vocabulary, intelligence | C13–C15 | Planned |

### Chapter Outline

| # | Chapter | Journey | Layers touched | Status |
|---|---|---|---|---|
| 13 | MindMap SPI + In-Memory + SQLite | J6 | L16, L17 | Planned |
| 14 | MindMap CDI + Decorators | J6 | L16, L17 | Planned |
| 15 | MindMap Intelligence | J6 | L18 | Planned |

### Layers

| Layer | Module | Tier |
|---|---|---|
| L16 MindMap SPI | `mindmap-api`, `mindmap-testing` | Pure Java, zero deps |
| L17 MindMap Runtime | `mindmap`, `mindmap-inmem`, `mindmap-sqlite` | CDI library / backends |
| L18 MindMap Intelligence | `mindmap-intelligence` | CDI library, requires AgentProvider |

## 10. Design Decisions

### No reactive SPI variant

The module-tier-structure protocol specifies: "Ship both a blocking SPI and a
reactive mirror (`Uni<>`) when the store is consumed from **both** contexts."

All identified MindMap consumers are blocking:
- `InnerLifeOrchestrator` — `@ApplicationScoped`, blocking tick loop with
  `ReentrantLock` per agent. Not on the Vert.x event loop.
- blocks CDI observers (`MentalStateSignal`, `EngagementSignal`) — synchronous
  CDI observers on worker threads.

A reactive variant will be added when a reactive consumer is identified,
following the established `@DefaultBean` bridge pattern (see L6 RAG SPI,
`BlockingToReactiveCaseRetriever`).

## References

- GE-20260630-815259 — Cross-repo SPI extends CDI displacement gotcha
- `CbrCaseMemoryStore` — standalone SPI pattern, supersession, outcome, schema registration
- `CaseMemoryStore` — tenant isolation, erasure, capability self-description
- `GraphCaseMemoryStore` / #185 — semantic graph query on text memories (complementary, not overlapping)
- `RetrievalAnalyzer` (rag-api) — pure computation analysis pattern
- blocks `InnerLifeOrchestrator` — tick-driven cognitive orchestration (blocking)
- blocks `MentalModelOrchestrator` — BDI mental model with decay and inference
- blocks `CuriosityDrive` — motivation for knowledge-seeking behavior
- blocks `MemoryHygieneOrchestrator` — memory consolidation and gap detection
- Drools Traits — dynamic trait application via proxy, truth maintenance
- MoodState / MoodModulatedRetrieval — PAD model for mood-congruent recall
- `CbrOutcome` — numeric confidence with categorical provenance (pattern for §3.10)
