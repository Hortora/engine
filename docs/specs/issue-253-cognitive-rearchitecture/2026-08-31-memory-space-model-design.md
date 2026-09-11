# Memory Space Model — Private, Shared, and Selective Visibility

**Issue:** casehubio/neocortex#230
**Module:** `memory-space-api` (new, tier-0, zero deps)
**Package:** `io.casehub.neocortex.memory.space`

## Problem

The current neocortex model: one tenant = one agent = fully isolated
memory. Every store method takes `tenantId` and enforces strict
isolation. For multi-agent scenarios (families, teams, organisations),
agents need to share memory — some fully, some selectively — while
retaining private cognitive spaces.

Memory spaces are a cross-cutting architectural property. Every query
builder, every confidence model, every affective annotation must
account for visibility. This issue defines the foundational types and
SPI — existing stores remain unchanged.

## Design

### Core Principle: Space-as-Tenant

Each memory space IS a tenant. Private space = individual tenant.
Shared space = group tenant. The visibility layer maintains membership
(which agents see which spaces) and resolves the set of tenant IDs to
query for a given agent.

```
┌──────────────────────────────────────────────────────┐
│                  Visibility Layer                     │
│  Resolves: which spaces does this agent see?          │
│  Result: Set<String> tenantIds to query               │
├──────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ alice-priv  │  │ smiths-family│  │ bob-priv   │  │
│  │ (tenantId)  │  │ (tenantId)   │  │ (tenantId) │  │
│  └─────────────┘  └──────────────┘  └────────────┘  │
└──────────────────────────────────────────────────────┘
```

Stores don't change — they still receive `tenantId`. The abstraction
sits above them.

### New Types

**SpaceType** — enum:

```java
public enum SpaceType {
    PRIVATE,
    SHARED
}
```

**MemorySpace** — a named space that wraps a tenant:

```java
public record MemorySpace(
    String id,           // the tenantId used for store operations
    SpaceType type,
    String name,         // human-readable
    String ownerId       // owning agent for PRIVATE; null for SHARED
) {
    // id, type, name required non-null
    // ownerId required when type=PRIVATE, null when type=SHARED
}
```

Factory methods:
- `MemorySpace.privateSpace(String id, String name, String ownerId)`
- `MemorySpace.sharedSpace(String id, String name)`

**Visibility** — sealed hierarchy for per-record visibility (type
defined now, per-record filtering deferred to future issue):

```java
public sealed interface Visibility {
    record Private(String ownerId) implements Visibility {}
    record Shared(String spaceId) implements Visibility {}
    record Selective(String spaceId, Set<String> recipientIds) implements Visibility {}
}
```

Per-record Selective filtering requires adding a `Visibility` field to
`MemoryInput`/`NodeInput` and query-time filtering in stores — out of
scope for this issue. The type is defined and stable for when stores
are extended.

**SpaceMembership** — who belongs to which space, with temporal validity:

```java
public record SpaceMembership(
    String agentId,
    String spaceId,
    Set<String> roles,      // opaque strings (admin, member, etc.)
    Instant validFrom,
    Instant validUntil      // nullable — null means current member
) {
    // agentId, spaceId, validFrom required non-null
    // roles defensive-copied to immutable set
}
```

Roles are opaque strings — the platform provides the mechanism,
consumers (casehub-life) define the semantics (financial-authority,
school-authority, etc.). Follows the MindMap traits pattern.

Temporal validity enables membership changes: kids grow up (access
expands), partners separate (access changes). `revokeMember` sets
`validUntil` rather than deleting — temporal history is preserved.

### SpaceMembershipStore SPI

```java
public interface SpaceMembershipStore {

    void createSpace(MemorySpace space);

    Optional<MemorySpace> getSpace(String spaceId);

    void addMember(SpaceMembership membership);

    void revokeMember(String agentId, String spaceId, Instant revokedAt);

    List<MemorySpace> spacesFor(String agentId, Instant asOf);

    List<SpaceMembership> membersOf(String spaceId, Instant asOf);
}
```

**Key query: `spacesFor(agentId, asOf)`** — returns all spaces where
the agent has an active membership at the given instant
(`validFrom <= asOf` AND (`validUntil == null` OR `validUntil > asOf`)).
This includes the agent's own private space. CognitiveProfile,
TemporalIndex, and future space-aware utilities call this to resolve
which tenant IDs to query.

**Revocation:** `revokeMember` sets `validUntil = revokedAt` on the
active membership. Does not delete — temporal history is queryable
(`membersOf` with a past `asOf` returns former members).

### Module Structure

```
memory-space-api/       — MemorySpace, SpaceType, Visibility,
                          SpaceMembership, SpaceMembershipStore SPI
                          (zero deps, tier-0)

memory-space-inmem/     — InMemorySpaceMembershipStore
                          @Alternative @Priority(2) — tests

memory-space-sqlite/    — SqliteSpaceMembershipStore
                          @Alternative @Priority(1) — production
                          (HikariCP WAL + Flyway)
```

`NoOpSpaceMembershipStore` (`@DefaultBean`) — returns a singleton
private space derived from the agentId. Single-agent apps work without
adding space infrastructure. Multi-agent apps displace with the real
implementation. Follows the MindMapStore/CaseMemoryStore graceful
degradation pattern. Lives in the `memory-space-api` module alongside
the SPI (same pattern as NoOpMindMapStore in `mindmap`).

### Integration Notes

This issue defines the space model. Integration with existing query
types is mechanical and deferred to the consuming issues:

| Consumer | Integration | When |
|----------|-------------|------|
| `CognitiveProfile` (#243) | `withTenantIds(Set<String>)` on CognitiveProfileQuery, call `spacesFor` to resolve | Next session on this branch |
| `TemporalIndex` | Accept `Set<String> tenantIds` (already loops over `query.tenantIds()`) | Already designed for multi-tenant |
| `MindMapQuery` | Add `withSpaces()` or multi-tenant support | Builder APIs issue (#231) |
| `MemoryQuery` | Accept space-resolved tenant set | Builder APIs issue (#231) |
| `CbrQuery` | Accept space-resolved tenant set | Builder APIs issue (#231) |
| Perspectival overlays (#240) | Overlay nodes in private tenant linked to shared nodes | Depends on this + #238 |
| YAML config (#252) | YAML surface for space definitions | Phase 5 |

### What This Issue Does NOT Cover

- **Per-record Selective filtering** — requires `Visibility` field on
  MemoryInput/NodeInput and store-level query filtering. Separate issue.
- **Perspectival affect overlays** — per-viewer PAD on shared nodes.
  Depends on this model but is issue #240.
- **YAML configuration** — `memory-spaces:` YAML block. Phase 5, issue #252.
- **Store implementation changes** — existing stores remain unchanged.
  They see `tenantId`; the space model sits above them.
- **Space deletion** — deferred. Spaces can be created but not deleted
  in v1. Add when the data model stabilises.

### Test Plan

**memory-space-api (type validation):**
1. MemorySpace factory methods — private requires ownerId, shared rejects it
2. MemorySpace rejects null id/name/type
3. SpaceMembership defensive copies on roles
4. SpaceMembership rejects null agentId/spaceId/validFrom
5. Visibility sealed hierarchy — all three variants construct correctly

**memory-space-inmem (SpaceMembershipStore contract):**
6. createSpace + getSpace round-trip
7. addMember + spacesFor — agent sees their spaces
8. spacesFor includes private space
9. spacesFor respects temporal validity (validFrom/validUntil)
10. revokeMember sets validUntil — agent no longer sees space
11. membersOf returns active members at asOf
12. membersOf with past asOf returns historical membership
13. spacesFor with past asOf returns historical access
14. Multiple spaces — agent in private + 2 shared sees all 3
15. Revoked member — spacesFor excludes, membersOf(past) includes

**memory-space-sqlite (contract reuse):**
16. Contract test base class — same 10 tests run against SQLite impl
17. Flyway migration creates tables correctly

## Decisions

D44-D49 in `decisions.md`. Key choices:
- New `memory-space-api` module, tier-0, zero deps (D44)
- Three-tier CDI: SPI + in-memory + SQLite (D45)
- Visibility sealed type defined now, per-record filtering deferred (D46)
- MemorySpace record with space-as-tenant, SpaceType enum (D47)
- SpaceMembership with opaque string roles, temporal validity (D48)
- Minimal SpaceMembershipStore SPI — spacesFor as key query (D49)
- NoOpSpaceMembershipStore @DefaultBean for graceful degradation (D50)

## References

- docs/guides/shared-memory-design.md — authoritative design document
- cognitive-api/pom.xml — zero-dep module pattern
- cognitive-architecture-roadmap.md §1f — roadmap definition
- MindMapStore.java — three-tier CDI priority ladder pattern
- InMemoryMindMapStore.java — in-memory test implementation pattern
- SqliteMindMapStore.java — SQLite + HikariCP WAL + Flyway pattern
- MindMap traits (Set<String>) — opaque string roles pattern
- D26 — cognitive-api acceptance criteria (why NOT cognitive-api)
- D43 — CognitiveProfile space-aware API design
