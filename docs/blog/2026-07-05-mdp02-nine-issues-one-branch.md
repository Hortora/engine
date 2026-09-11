---
layout: post
title: "Nine Issues, One Branch, and a Chicken-and-Egg Problem"
date: 2026-07-05
type: phase-update
entry_type: note
subtype: diary
projects: [neocortex]
tags: [cbr, memory-spi, cdi, enrichment]
---

## Nine Issues, One Branch, and a Chicken-and-Egg Problem

I'd been putting off the neocortex cleanup queue — a stack of S and XS issues filed during the CBR reconciliation work, plus a medium-sized tenant discovery problem that kept surfacing in the design but never getting addressed. Nine issues isn't a natural branch size. But they were entangled enough that doing them separately would mean touching the same SPI surface area nine times, and the dependency chain between them was real: tenant discovery needed a new SPI method, batch reconciliation needed tenant discovery, observability needed the CDI restructuring, and the enrichment pipeline needed the SPI too.

The interesting design question was tenant discovery. After a dimension change, `CbrCollectionManager.ensureCollection()` deletes and recreates the Qdrant collection — every point gone. An operator needs to reconcile all affected tenants, but first needs to know which tenants have CBR data. I initially assumed the answer was to query Qdrant directly — scroll the collection's payloads, extract unique `tenantId` values. Simple, contained, no SPI change.

Wrong. After dimension migration, Qdrant is empty. There are no tenants to discover there. The delegate (`CaseMemoryStore` — JPA or SQLite in production) is the only store that survives collection recreation. And `MemoryScanRequest` enforces non-null `tenantId` at the type level — you can't scan across tenants. That isolation is intentional and correct for the normal code path.

The fix was `discoverTenants()` on `CaseMemoryStore` itself — a capability-gated cross-tenant admin operation following the precedent of `eraseEntityAcrossTenants()`, which already calls `MemoryPermissions.assertCrossTenantAdmin()`. The pattern was established; I just hadn't seen that tenant discovery was the same kind of operation.

The CDI restructuring was the other design decision worth noting. `CbrReconciliationService` was a POJO created by `@Produces` in `QdrantCbrBeanProducer`. CDI interceptors don't work on producer-created instances — which meant `@Timed` annotations would never fire. Every memory backend in the project (JPA, SQLite, InMemory) uses `@Timed` on CDI-managed beans. The reconciliation service was the odd one out because it shared a `CbrCollectionManager` with `QdrantCbrCaseMemoryStore` via the producer. The fix: produce only `CbrCollectionManager` from the producer, make both service classes `@ApplicationScoped` with `@Inject` constructors. Standard CDI, standard Micrometer — nothing clever.

The enrichment pipeline SPI had a subtlety that Claude caught during brainstorming. The issue proposed routing enrichment steps by `caseType()`, but `CbrAttributeKeys` lives in `memory-qdrant` — the SPI in `memory-api` can't reference it. We went with `appliesTo(MemoryInput)` instead, letting each step define its own routing. More general, and the module boundary problem disappears.

One CDI gotcha worth recording: when you write a `@Decorator` on a multi-method interface like `CaseMemoryStore`, default methods on the interface call `this` — which is the decorator proxy, not the delegate. If you only override `store()`, a call to `eraseEntity()` hits the interface default and throws `MemoryCapabilityException` instead of reaching the backend. Every method must be explicitly overridden to delegate. The existing decorators in this project (CorrectiveCaseRetriever, QueryExpandingCaseRetriever) are on single-method interfaces, so the pattern hadn't been tested with multi-method SPIs before.

The design review caught two real problems the implementation missed: backend `discoverTenants()` overrides bypassed the interface default's mixed-null parameter validation (calling `discoverTenants(null, "value")` would silently return all tenants), and `NoOpCaseMemoryStore.requireCapability()` was overridden as a no-op — breaking the fail-fast contract that `reconcileAll()` depends on.
