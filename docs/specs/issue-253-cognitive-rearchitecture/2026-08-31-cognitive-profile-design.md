# CognitiveProfile — Cross-Store Entity Resolution

**Issue:** casehubio/neocortex#243
**Module:** `cognitive-index`
**Package:** `io.casehub.neocortex.cognitive.index`

## Problem

The cognitive subsystem stores knowledge about entities across three
independent stores: MindMap (graph structure, properties, traits, affect),
CaseMemoryStore (text memories across 6+ domains), and CbrCaseMemoryStore
(structured feature-vector cases). NodeRefs on MindMap nodes link to
memories and CBR cases. The affect trajectory is computed from
domain="affect" memories.

There is no single operation to answer "tell me everything the system
knows about Alice." A caller must: resolve the MindMap node, query 6
memory domains, follow NodeRefs, compute the affect trajectory, get
edges — 8+ store calls orchestrated manually.

## Design

### New Types

**CognitiveProfileQuery** — immutable record with factory methods:

```java
public record CognitiveProfileQuery(
    String nodeId,
    String entityName,
    String subgraphId,
    String tenantId,
    Set<MemoryDomain> domains,
    boolean includeEdges,
    int memoryLimit
) {
    // Exactly one of nodeId/entityName must be non-null.
    // Empty domains = all known domains.

    public static CognitiveProfileQuery byId(String nodeId, String tenantId);
    public static CognitiveProfileQuery byName(String entityName, String tenantId);
    public static CognitiveProfileQuery byName(String entityName, String subgraphId, String tenantId);

    public CognitiveProfileQuery withDomains(Set<MemoryDomain> domains);
    public CognitiveProfileQuery withIncludeEdges(boolean includeEdges);
    public CognitiveProfileQuery withMemoryLimit(int memoryLimit);
}
```

Defaults: `domains = Set.of()` (all), `includeEdges = true`,
`memoryLimit = 50`.

**EntityKnowledge** — the result record:

```java
public record EntityKnowledge(
    MindMapNode node,
    List<MindMapEdge> edges,
    Map<MemoryDomain, List<Memory>> memories,
    AffectTrajectory trajectory,
    Set<NodeRef> unresolvedRefs,
    String tenantId
) {
    // node is non-null (Optional.empty() returned if not found)
    // edges is empty if includeEdges=false or MindMapStore unavailable
    // memories contains only requested domains
    // trajectory is null if no affect data or CaseMemoryStore unavailable
    // unresolvedRefs contains NodeRefs that couldn't be followed (e.g., scheme="cbr")
}
```

**CognitiveProfile** — CDI bean:

```java
@ApplicationScoped
public class CognitiveProfile {

    // CDI constructor — Instance<T> for graceful degradation
    @Inject
    public CognitiveProfile(Instance<MindMapStore> mindMapStore,
                            Instance<CaseMemoryStore> memoryStore,
                            Instance<CbrCaseMemoryStore> cbrStore);

    // Test constructor — direct injection
    CognitiveProfile(MindMapStore mindMapStore,
                     CaseMemoryStore memoryStore,
                     CbrCaseMemoryStore cbrStore);

    public Optional<EntityKnowledge> resolve(CognitiveProfileQuery query);
}
```

### Resolution Algorithm

`resolve(query)` executes these steps:

1. **Resolve node.** If `query.nodeId()` is set, call
   `mindMapStore.getNode(nodeId, tenantId)`. If `query.entityName()` is
   set, call `mindMapStore.resolveNode(entityName, subgraphId, tenantId)`.
   If the node is null, return `Optional.empty()`.

2. **Collect entity IDs.** Build a list of IDs for memory queries:
   `[node.id(), node.name()]`. Add `ref.id()` for each
   `NodeRef(scheme="memory")` from `node.refs()`. Deduplicate. This
   catches memories stored under either the internal UUID (affect domain)
   or the human-readable name (other domains).

3. **Collect unresolved refs.** Any NodeRef where scheme is NOT "memory"
   goes into the unresolvedRefs set.

4. **Resolve edges.** If `query.includeEdges()` and MindMapStore is
   available, call `mindMapStore.neighbors(node.id(), tenantId)`.

5. **Resolve memories.** Determine domains to query: if
   `query.domains()` is empty, use all known domains (experience,
   relationship, reflection, mood, engagement, affect); otherwise use
   the specified set. For each domain, call
   `memoryStore.query(MemoryQuery.forEntities(entityIds, domain, tenantId).withLimit(memoryLimit).withOrder(CHRONOLOGICAL))`.
   Collect into `Map<MemoryDomain, List<Memory>>`. Skip if
   CaseMemoryStore is unavailable.

6. **Compute trajectory.** The trajectory is always computed regardless
   of domain selection — it's cheap and frequently needed. If affect
   memories were already fetched in step 5, reuse them. If the caller
   excluded the affect domain from their query, fetch affect memories
   separately (they won't appear in the `memories` map). Pass to
   `AffectTrajectoryAnalyzer.analyze()`. Null if no affect data exists
   or CaseMemoryStore is unavailable.

7. **Assemble.** Return
   `Optional.of(new EntityKnowledge(node, edges, memories, trajectory, unresolvedRefs, tenantId))`.

### Known Domains

The following memory domains are queried when `domains` is empty.
CognitiveProfile maintains a `DEFAULT_DOMAINS` constant set referencing
the converter DOMAIN constants. New cognitive domains added to the
system require updating this set.

| Domain | Converter constant | Content |
|--------|-------------------|---------|
| experience | `ExperienceEvents.DOMAIN` | Agent actions, observations, outcomes |
| relationship | `RelationshipEvents.DOMAIN` | Per-agent-pair interaction records |
| reflection | `ReflectionEvents.DOMAIN` | Synthesized insights |
| mood | `MoodEvents.DOMAIN` | Agent emotional state snapshots |
| engagement | `EngagementEvents.DOMAIN` | Per-interaction social measurements |
| affect | `AffectEvents.DOMAIN` | PAD changes on nodes (trajectory source) |

### Graceful Degradation

| Store unavailable | Behavior |
|-------------------|----------|
| MindMapStore | `resolve()` always returns `Optional.empty()` — the node is the anchor |
| CaseMemoryStore | `memories` map is empty, `trajectory` is null |
| CbrCaseMemoryStore | No effect in v1 — CBR refs are already recorded as unresolved |

### CBR Gap

CbrCaseMemoryStore has no get-by-ID or get-by-entityId method.
NodeRef(scheme="cbr") cannot be followed to retrieve the actual case
data. These refs are recorded in `unresolvedRefs` so callers know the
link exists. Filling this gap requires extending the CBR SPI — tracked
separately, not in scope for this issue.

### Space Awareness

v1 operates on a single tenant. The `CognitiveProfileQuery` record's
immutable-builder pattern supports future extension via
`withTenantIds(Set<String>)` when the memory space model (#230) lands.
Multi-space resolution is multi-tenant querying — each space maps to
a tenant. This extension is mechanical (add field, loop over tenants,
merge results).

### Module Impact

No new module. Three new types in `cognitive-index`:
- `CognitiveProfileQuery` — query record
- `EntityKnowledge` — result record
- `CognitiveProfile` — CDI bean

cognitive-index already has the required dependencies (mindmap-api,
memory-api, CDI). Test deps (mindmap-inmem, memory-inmem) are also
present.

### Test Plan

1. **Resolution by ID** — getNode path, verify all sections populated
2. **Resolution by name** — resolveNode path, same verification
3. **Not found** — returns Optional.empty()
4. **Domain selection** — request subset, verify only those domains in result
5. **Edge inclusion/exclusion** — `includeEdges=false` yields empty edges
6. **Affect trajectory computation** — verify trajectory from affect memories
7. **NodeRef following** — scheme="memory" refs queried; scheme="cbr" in unresolvedRefs
8. **Dual entity ID resolution** — memories stored under nodeId AND nodeName both found
9. **Graceful degradation (no MindMapStore)** — always empty
10. **Graceful degradation (no CaseMemoryStore)** — node and edges present, memories/trajectory absent
11. **Memory limit** — verify per-domain limit applied
12. **Empty entity** — node exists but has no memories, edges, or trajectory

## Decisions

D33-D43 in `decisions.md`. Key choices:
- CDI bean + query object following TemporalIndex pattern (D33)
- cognitive-index module (D34)
- Factory methods byId/byName on query record (D35)
- Map<MemoryDomain, List<Memory>> for domain-keyed results (D36)
- Entity + direct edges, no recursive neighbor resolution (D37)
- Configurable domain set, default all (D38)
- Query both nodeId + nodeName as entityIds (D39)
- scheme="memory" followed, scheme="cbr" recorded as unresolved (D40)
- Optional<EntityKnowledge> return type (D41)
- AffectTrajectoryAnalyzer composition (D42)
- Single tenantId, designed for future multi-tenant extension (D43)

## References

- TemporalIndex.java — CDI + Instance<T> graceful degradation pattern
- TemporalFocus.java — derived scoring from raw entries pattern
- AffectTrajectoryAnalyzer.java — trajectory computation (composed)
- AffectTrajectoryDecorator.java:83 — entityId=nodeId convention
- AffectEvents.java — domain="affect" constant
- NodeRef.java — cross-store reference type (scheme/id/qualifier)
- MindMapStore.java — resolveNode, getNode, neighbors API
- CaseMemoryStore.java — query API
- MemoryQuery.java — forEntities multi-entityId support
- cognitive-architecture-roadmap.md §4a — roadmap definition
- GE-20260805-a28f5b — three-tier CDI composition pattern
- GE-20260630-815259 — cross-repo SPI displacement gotcha (CBR standalone pattern)
