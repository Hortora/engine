# Scope-Based Bulk Erasure + Aggregate Erasure Notification

**Issues:** #158, #159
**Date:** 2026-07-17
**Status:** Approved

## Context

#153 introduced hierarchical scoping to CBR — cases are stored at `Path` scopes and retrieved with scope visibility (`isAncestorOf`). Two follow-on needs emerged:

1. **#158 — eraseByScope:** Operational lifecycle cleanup. When a scope is decommissioned ("close this trial site"), all cases stored at that scope and below should be deleted. Not GDPR Art.17 (that is entity-anchored via `eraseEntity`).

2. **#159 — aggregate adjustment on entity erasure:** Higher-scope aggregates that track counts or statistics from lower-scope cases become stale after cases are erased. No mechanism exists for domains to know that source cases were removed so they can recompute.

## Design

### #158: eraseByScope(Path scope, String tenantId)

**SPI addition** — new method on `CbrCaseMemoryStore` and `ReactiveCbrCaseMemoryStore`:

```java
// CbrCaseMemoryStore
Integer eraseByScope(Path scope, String tenantId);

// ReactiveCbrCaseMemoryStore
Uni<Integer> eraseByScope(Path scope, String tenantId);
```

**Subtree semantics:** Erases all cases where `scope.equals(storedScope) || scope.isAncestorOf(storedScope)`. Root scope (`Path.root()`) erases all cases for the tenant.

**Return value:** Count of erased cases, consistent with `erase` and `eraseEntity`.

**Implementations:**

| Store | Approach |
|-------|----------|
| InMemory | `removeIf` with scope equality + `isAncestorOf` check |
| JPA | Root: `DELETE WHERE tenantId = :t`. Non-root: `DELETE WHERE tenantId = :t AND (scope = :s OR scope LIKE :prefix)` where prefix = `scope.value() + "/%"` |
| Qdrant | Scroll with tenantId filter, client-side scope prefix check, batch delete by point IDs. For `CaseMemoryStore` delegate (nullable): build per-case `EraseRequest(entityId, domain, tenantId, caseId)` from scroll results and delegate individually — `caseId` is required to avoid over-deletion of entries at other scopes for the same entity. Acceptable for operational method. |
| NoOp | `return 0` |

**Forwarding:** All decorators (Tracking/50, OutcomeWeighting/65, Reranking/75, TemporalDecay/80, ScopeDecay/85, TrendEnrichment/90) and `BlockingToReactiveCbrBridge` gain pass-through forwarding. Same pattern as existing erasure methods.

### #159: CbrCasesErased CDI Event

**Event type** — sealed interface in memory-api:

```java
public sealed interface CbrCasesErased {
    String tenantId();
    int erasedCount();
    Instant erasedAt();

    record ByRequest(String tenantId, int erasedCount,
                     String entityId, MemoryDomain domain, String caseId,
                     Instant erasedAt) implements CbrCasesErased {}
    record ByEntity(String tenantId, int erasedCount,
                    String entityId,
                    Instant erasedAt) implements CbrCasesErased {}
    record ByScope(String tenantId, int erasedCount,
                   Path scope,
                   Instant erasedAt) implements CbrCasesErased {}
}
```

Each subtype carries exactly the fields relevant to its erasure method — no nullable field ambiguity, no discriminator needed. Observers can listen for `CbrCasesErased` (all erasures) or a specific subtype like `CbrCasesErased.ByScope` (scope erasures only), using CDI event type assignability.

**Mechanism:** CDI event, following the established pattern (`CbrRetrievalRecorded`, `CbrAdaptationRecorded`). Loosely coupled, supports multiple observers. Blocking decorator fires via `Event.fire()` (synchronous — consistent with `TrackingCbrCaseMemoryStore` pattern, provides transactional visibility).

**Scope:** Fired for `erase`, `eraseEntity`, `eraseByScope` when `count > 0`. Not fired for `purge` — retention-based cleanup has different lifecycle expectations; aggregates built from purged data drift naturally with age-based policies.

**Decorator:** `ErasureNotificationCbrCaseMemoryStore` at `@Decorator @Priority(45)`, blocking side only. Outermost position ensures the event fires after the entire chain completes. Injects per-subtype `Event` instances (`Event<ByRequest>`, `Event<ByEntity>`, `Event<ByScope>`) and a `Clock` via test constructor pattern (defaulting to `Clock.systemUTC()` in CDI constructor). No reactive counterpart — `BlockingToReactiveCbrBridge` (`@DefaultBean`, the sole `ReactiveCbrCaseMemoryStore` implementation) routes all reactive calls through the blocking chain, where this decorator fires exactly once. This eliminates double-firing by design rather than relying on the `BridgedCbrStore` `instanceof` guard, which is ineffective when intervening decorators separate the guard from the bridge.

## Decisions

| Decision | Rationale |
|----------|-----------|
| Subtree semantics for eraseByScope | "Decommission this site" naturally means the entire subtree. Exact-scope deletion can use `erase(EraseRequest)` with future scope filter if needed. |
| CDI event over SPI callback or periodic recomputation | Established pattern in this module. Decoupled from store logic. Fire-and-forget. Multiple observers. |
| Not firing on purge | Purge is retention lifecycle, not explicit erasure. Aggregates drift naturally with age policies. |
| Qdrant scroll+delete | eraseByScope is operational, not query-path. Qdrant lacks keyword prefix matching. Scroll is correct and proportional. |
| Abstract method (not default) | All implementations are in this repo. SPI break is self-contained. Consistent with existing erase methods. |
| @Priority(45) for notification decorator | Below Tracking(50) = outermost. Event fires after complete erasure. |
| Sealed interface over union record | Each subtype carries exactly its fields — no nullable ambiguity, enables selective CDI observation by type, pattern-matchable. |
| Event.fire() blocking / Event.fireAsync() reactive | Matches established TrackingCbrCaseMemoryStore pattern. Blocking side provides transactional visibility; reactive side avoids blocking the event loop. |
| Blocking-only notification decorator (no reactive counterpart) | `BlockingToReactiveCbrBridge` is the sole reactive implementation — all reactive calls route through the blocking chain. No reactive counterpart eliminates double-firing by design, avoiding the `BridgedCbrStore` `instanceof` guard which is ineffective with intervening decorators. |
| No SPI-level authorization for eraseByScope | Authorization belongs at the API boundary. SPI callers are trusted platform code. Audit logging is supported via CbrCasesErased CDI event observers. |
| Qdrant eraseByScope delegates per-case to CaseMemoryStore | CaseMemoryStore lacks scope concept. Per-case EraseRequest with caseId from scroll results maintains dual-store invariant without scope-creeping the CaseMemoryStore API. |

## Contract Tests

8 new tests in `CbrCaseMemoryStoreContractTest`:
1. Erase at exact scope only erases that scope's cases
2. Erase at parent erases all descendant cases
3. Erase at root erases all cases for tenant
4. Tenant isolation preserved
5. Returns correct count
6. No cases at scope returns 0
7. Sibling scope isolation — erasing `/site-a` does not affect `/site-b` (catches prefix-matching bugs, e.g. `/site-a` vs `/site-ab`)
8. Superseded cases at target scope are also erased

Dedicated unit test for `ErasureNotificationCbrCaseMemoryStore`.

## Files Changed

| Module | Changes |
|--------|---------|
| memory-api | `CbrCaseMemoryStore` + `ReactiveCbrCaseMemoryStore` (add method), new `CbrCasesErased` sealed interface + 3 subtypes |
| memory | `NoOpCbrCaseMemoryStore`, `BlockingToReactiveCbrBridge`, 4 blocking decorators + 4 reactive decorators (TemporalDecay, OutcomeWeighting, ScopeDecay, TrendEnrichment — forwarding), new `ErasureNotificationCbrCaseMemoryStore` (blocking only) |
| memory-cbr-inmem | `InMemoryCbrCaseMemoryStore` (implement) |
| memory-cbr-jpa | `JpaCbrCaseMemoryStore` (implement) |
| memory-qdrant | `QdrantCbrCaseMemoryStore` (implement) |
| memory-cbr-crossencoder | 1 blocking + 1 reactive decorator (Reranking — forwarding) |
| memory-cbr-tracking | 1 blocking + 1 reactive decorator (Tracking — forwarding) |
| memory-testing | `CbrCaseMemoryStoreContractTest` (new tests) |

No Flyway migration — JPA delete query uses existing `scope` column from V4.
