# Perspectival Affect Overlays — Per-Agent PAD on Shared MindMap Nodes

**Issue:** casehubio/neocortex#240
**Modules:** `mindmap-api` (convention constants), `cognitive-index` (resolver + merge utility)
**Packages:** `io.casehub.neocortex.mindmap` (OverlayRef), `io.casehub.neocortex.cognitive.index` (PerspectivalResolver, PerspectivalMerge)

## Problem

Shared MindMap nodes represent collective knowledge (e.g., "Grandma" in
a family graph). Different agents have different emotional relationships
with the same entity — Alice loves Grandma (pleasure=0.9), the teenager
finds her annoying (pleasure=-0.2). Currently PAD is on the node itself,
so a shared node can only carry one perspective.

## Design

### Core Principle: Affect is Always Perspectival

Shared nodes carry NO inherent PAD. Each agent stores a lightweight
overlay node in their private tenant, linked to the shared node via
NodeRef. At query time, the resolver merges shared base + private
overlay into a single MindMapNode reflecting the agent's perspective.

```
Shared tenant (smiths-family):
  Node "Grandma" — name, traits, properties, NO PAD

Private tenant (alice-priv):
  Overlay node — trait "overlay", NodeRef(overlay, grandma-id),
                  pleasure=0.9, arousal=0.3, dominance=0.5,
                  properties={notes: "love her"}
```

### Convention (mindmap-api)

**OverlayRef** — static utility in mindmap-api:

```java
public final class OverlayRef {
    public static final String SCHEME = "overlay";

    public static NodeRef of(String sharedNodeId) {
        return new NodeRef(SCHEME, sharedNodeId, null);
    }

    public static Optional<String> sharedNodeId(MindMapNode node) {
        return node.refs().stream()
            .filter(r -> SCHEME.equals(r.scheme()))
            .map(NodeRef::id)
            .findFirst();
    }
}
```

Overlay nodes are regular MindMapNodes with:
- Trait `"overlay"` (for trait-based query filtering)
- `NodeRef(scheme="overlay", id=<sharedNodeId>)` (link to shared node)
- PAD fields (the agent's emotional perspective)
- Optional confidence override and private properties

No store changes. Creating an overlay is a standard `addNode` call with
the overlay trait and NodeRef.

### Merge Utility (cognitive-index)

**PerspectivalMerge** — pure static utility:

```java
public final class PerspectivalMerge {
    public static MindMapNode merge(MindMapNode shared, MindMapNode overlay);
}
```

Merge rules:
- **Name, subgraphId, traits, refs, validFrom/validUntil, provenance:** from shared node
- **PAD (pleasure, arousal, dominance):** overlay wins when non-null, shared fills gaps
- **Confidence:** overlay wins when non-null, shared fills gaps
- **Properties:** overlay properties merged on top of shared (overlay wins on key conflict)
- **Timestamps (createdAt, updatedAt):** from shared node (the entity's timeline, not the overlay's)

Returns a new immutable MindMapNode record with merged fields.

### Resolver (cognitive-index)

**PerspectivalResolver** — `@ApplicationScoped` CDI bean:

```java
public class PerspectivalResolver {
    @Inject Instance<MindMapStore> mindMapStore;
    @Inject Instance<SpaceMembershipStore> spaceMembershipStore;

    public List<MindMapNode> resolve(
        List<MindMapNode> sharedNodes, String agentId, Instant asOf);
}
```

Resolution flow:
1. Call `spaceMembershipStore.spacesFor(agentId, asOf)` to find the agent's
   private space (type=PRIVATE)
2. Query the private tenant for overlay nodes:
   `search(MindMapQuery(privateTenantId, ..., traits={"overlay"}, ..., limit=1000))`
3. Build lookup map: extract `OverlayRef.sharedNodeId(node)` → overlay node
4. For each shared node: look up overlay, merge if found, return as-is if not

**Graceful degradation:** If MindMapStore or SpaceMembershipStore is
unavailable (`Instance.isUnsatisfied()`), return the input list unchanged.
Same behavior as CognitiveProfile's graceful degradation pattern.

### What This Issue Does NOT Cover

- **Creating overlays** — the convention is defined; creating overlay
  nodes is the caller's responsibility via standard `addNode`.
- **Multi-space query orchestration** — querying shared + private tenants
  and feeding shared results into the resolver. That's the visibility
  layer's job (future issue).
- **Overlay deletion/update** — standard `updateNode`/`eraseNode` on the
  overlay node in the private tenant. No special API needed.
- **Edge overlays** — only node PAD overlays are in scope. Edge-level
  perspective is a separate concern.

### Test Plan

**mindmap-api (convention, 2 tests):**
1. `OverlayRef.of` creates NodeRef with scheme="overlay" and correct id
2. `OverlayRef.sharedNodeId` extracts shared node id from overlay node

**cognitive-index (merge utility, 5 tests):**
3. Overlay PAD replaces shared null PAD
4. Overlay confidence replaces shared confidence
5. Overlay properties merge (overlay key wins on conflict)
6. Null overlay PAD keeps shared PAD unchanged
7. Shared name, traits, subgraphId preserved through merge

**cognitive-index (resolver, 6 tests):**
8. Resolver finds overlay in private tenant and merges
9. No overlay returns shared node as-is
10. Mixed: some shared nodes have overlays, some don't
11. Graceful degradation: no MindMapStore returns input unchanged
12. Graceful degradation: no SpaceMembershipStore returns input unchanged
13. Multiple shared nodes resolved in one batched query

## Decisions

D55-D57 in `decisions.md`. Key choices:
- Resolver in cognitive-index with Instance<T> graceful degradation (D55)
- No-overlay returns shared node as-is, null PAD (D56)
- Full overlay: PAD + confidence + properties (D57)

## References

- `docs/guides/shared-memory-design.md` — authoritative overlay model design
- `mindmap-api/.../NodeRef.java` — cross-store reference record
- `mindmap-api/.../MindMapNode.java` — node interface with PAD fields
- `mindmap-api/.../MindMapQuery.java` — query with trait filtering
- `cognitive-index/.../CognitiveProfile.java` — Instance<T> graceful degradation pattern
- `memory-space-api/.../SpaceMembershipStore.java` — space membership resolution
- `memory-space-api/.../MemorySpace.java` — SpaceType.PRIVATE for finding private space
- GitHub #240 — focal issue
- GitHub #253 — parent branch issue
- GitHub #230 — memory space model (dependency)
