# Remove Memory-Space Modules

**Issue:** casehubio/neocortex#255
**Modules affected:** `memory-space-api`, `memory-space`, `memory-space-inmem`,
`memory-space-sqlite`, `memory-space-testing`, `cognitive-index`

## Problem

The memory-space model (D44-D50 from #230) built a second tenancy system
inside the first. Each "space" was modeled as a tenant — private space =
private tenant, shared space = shared tenant. This is architecturally
wrong:

- **Tenant** = organisation (hard isolation boundary). No joins cross
  this line except admin/ops.
- **Individual vs common** memory is a property of the memory itself
  (`entityId` captures "whose"), not a partitioning system.
- `SpaceMembershipStore` is authorization infrastructure that belongs in
  the platform, not in the cognitive subsystem.

The 5 memory-space modules implement this wrong abstraction. They must
be removed.

## Scope

### Delete (5 modules)

| Module | Contents | Lines |
|--------|----------|-------|
| `memory-space-api` | MemorySpace, SpaceType, Visibility, SpaceMembership, SpaceMembershipStore SPI, NoOpSpaceMembershipStore | ~200 |
| `memory-space` | CDI wiring (empty — NoOp lives in api) | ~20 |
| `memory-space-inmem` | InMemorySpaceMembershipStore | ~100 |
| `memory-space-sqlite` | SqliteSpaceMembershipStore + Flyway migration | ~200 |
| `memory-space-testing` | SpaceMembershipStoreContractTest (10 tests) | ~150 |

Plus tests: MemorySpaceTest, SpaceMembershipTest, VisibilityTest,
NoOpSpaceMembershipStoreTest, InMemorySpaceMembershipStoreTest,
SqliteSpaceMembershipStoreTest.

### Modify (1 module)

**cognitive-index — PerspectivalResolver:**

Current: Uses `SpaceMembershipStore.spacesFor(agentId, asOf)` to find
the agent's private tenant, then queries MindMapStore for overlay nodes
in that tenant.

After: Overlay nodes live in the same tenant as shared nodes. Overlay
nodes carry an `agentId` property. PerspectivalResolver searches by
trait `"overlay"` within the caller-provided tenant, filters by
`agentId` property client-side.

Signature change:
```java
// Before
resolve(List<MindMapNode> sharedNodes, String agentId, Instant asOf)
// After
resolve(List<MindMapNode> sharedNodes, String agentId, String tenantId)
```

Constructor change:
```java
// Before
PerspectivalResolver(Instance<MindMapStore>, Instance<SpaceMembershipStore>)
// After
PerspectivalResolver(Instance<MindMapStore>)
```

`findPrivateTenant()` method deleted. `loadOverlays()` takes tenantId
and agentId, filters by property.

### OverlayRef convention update

Add `AGENT_ID = "agentId"` constant to `OverlayRef`. Overlay nodes
must include `Map.of(OverlayRef.AGENT_ID, agentId)` in their
properties. PerspectivalResolver uses this constant for client-side
filtering.

### Update docs

- **CLAUDE.md** — remove all memory-space module descriptions, Maven
  coordinates, and package references (~11 occurrences)
- **cognitive-architecture-roadmap.md** — update MemorySpace/Visibility
  references to note removal and rationale

### Parent pom.xml

Remove 5 module declarations and 5 dependencyManagement entries.

## What This Does NOT Cover

- **MindMapQuery property filtering** — client-side filtering is
  sufficient for pre-release overlay counts. Server-side property
  filtering is a natural future enhancement if needed.
- **Platform authorization model** — the correct multi-agent
  authorization design is a platform concern, not a neocortex concern.
- **Multi-agent memory sharing redesign** — the right model for
  individual vs common memory within a tenant is a separate design
  exercise, scoped to when the use case materialises.

## Test Plan

1. Existing PerspectivalResolver tests updated — overlay nodes carry
   agentId property, resolver takes tenantId instead of asOf
2. Graceful degradation test for missing SpaceMembershipStore removed
   (dependency no longer exists)
3. Graceful degradation test for missing MindMapStore preserved
4. OverlayRef test — verify AGENT_ID constant exists
5. Full build passes (`mvn clean install`)

## Decisions

D62-D64 in `decisions.md`. Key choices:
- Delete all 5 modules, not gut-and-keep (D62)
- PerspectivalResolver uses agentId property + tenantId param (D63)
- OverlayRef owns the agentId property constant (D64)

## References

- D58 in decisions.md — space parameters dropped from builder APIs
- D44-D50 in decisions.md — original space model decisions (superseded)
- PerspectivalResolver.java — sole external consumer of space modules
- cognitive-index/pom.xml — sole external dependency on space modules
- #230 — original memory space model issue
- #252 — Memory space YAML (blocked by this, likely to be dropped)
