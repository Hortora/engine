# Reactive CorpusStore + CaseRetriever Variants

**Issue:** casehubio/neural-text#11
**Date:** 2026-06-09
**Status:** Approved

## Problem

`CorpusStore` and `CaseRetriever` are blocking-only. The planned integration with
casehub-engine's `CaseContextChangedEventHandler` (which runs on the Vert.x IO thread
via `@ConsumeEvent`) will need `Uni<T>` variants — calling blocking SPIs from the
event loop causes `BlockingOperationNotAllowedException`. This is proactive enablement
ahead of parent#164 (engine-side CaseRetriever integration).

## Decision

Co-locate reactive SPI interfaces in `rag-api/` alongside blocking ones, with Mutiny
as `provided` scope. Follows the canonical pattern established by `casehub-ledger-api`
(`ReactiveOutcomeRecorder`) and `casehub-eidos-api` (`ReactiveAgentRegistry`).

No new modules required.

## Protocols governing this design

- `reactive-service-build-gating` (PP-20260519-39a9a5) — build-time gating via `@IfBuildProperty`
- `reactive-spi-bridge-default-bean` (PP-20260529-5745c1) — bridge is `@DefaultBean`, no `@IfBuildProperty`
- `spi-reactive-blocking-io` (PP-20260529-9f9627) — strategy SPIs that may block must return `Uni<T>`
- `reactive-blocking-tier-separation` (PP-20260519-f2e160) — separate beans, no mixing
- `module-tier-structure` (PP-20260512) — Mutiny `provided` acceptable in Tier 1 (casehubio/parent#213, casehubio/garden#3 track the protocol text update)

## Module changes

### `rag-api/` — reactive SPI interfaces

Add dependency:

```xml
<dependency>
    <groupId>io.smallrye.reactive</groupId>
    <artifactId>mutiny</artifactId>
    <scope>provided</scope>
</dependency>
```

New interfaces in `io.casehub.rag`:

**`ReactiveCorpusStore`** — mirrors `CorpusStore`:

```java
public interface ReactiveCorpusStore {
    Uni<Void> ingest(CorpusRef corpus, List<ChunkInput> chunks);
    Uni<Void> deleteDocument(CorpusRef corpus, String sourceDocumentId);
    Uni<Void> deleteCorpus(CorpusRef corpus);
    Uni<List<String>> listDocuments(CorpusRef corpus);
}
```

**`ReactiveCaseRetriever`** — mirrors `CaseRetriever`:

```java
public interface ReactiveCaseRetriever {
    Uni<List<RetrievedChunk>> retrieve(String query, CorpusRef corpus, int maxResults);
}
```

**Parity test** — ArchUnit test asserting blocking/reactive method parity. Matching rule:
same method name + same parameter types + return type wrapped in `Uni<>` (where `void` →
`Uni<Void>`). Vacuous-pass guard ensures the test cannot silently pass when no classes match.
Follows ledger's `BlockingReactiveParityTest` semantics.

### `rag/` — bridge + reactive Qdrant implementations

**Bridge beans** (`@DefaultBean @ApplicationScoped`, no `@IfBuildProperty`):

- `BlockingToReactiveCorpusStore implements ReactiveCorpusStore` — injects `CorpusStore`,
  wraps each call with `Uni.createFrom().item(...).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())`.
  Void methods use `Uni.createFrom().<Void>item(() -> { delegate.xxx(); return null; })`.

- `BlockingToReactiveCaseRetriever implements ReactiveCaseRetriever` — same pattern,
  injects `CaseRetriever`.

These are always active (per PP-20260529-5745c1 — bridges with no Hibernate Reactive
dependency are `@DefaultBean` without `@IfBuildProperty`). They provide a baseline
reactive path by offloading blocking calls to the worker pool.

Void method idiom for bridges (matches ledger `BlockingToReactiveOutcomeRecorder`):
`Uni.createFrom().<Void>item(() -> { delegate.xxx(); return null; }).runSubscriptionOn(...)`.
In-memory stubs use the cleaner `.invoke() + .replaceWithVoid()` idiom (no `runSubscriptionOn`
needed since they do no blocking I/O).

**Reactive Qdrant implementations** (build-gated):

- `ReactiveQdrantCorpusStore` — `@IfBuildProperty(name = "casehub.rag.reactive.enabled", stringValue = "true")`, `@ApplicationScoped`. Native reactive Qdrant gRPC calls via `ListenableFuture` → Mutiny bridge. Displaces the `@DefaultBean` bridge when active.

- `ReactiveHybridCaseRetriever` — same gating. Native reactive dense + sparse search,
  RRF fusion, optional cross-encoder reranking.

`@IfBuildProperty` + `@ApplicationScoped` takes precedence over the `@DefaultBean` bridge
automatically — no `@Alternative` annotation needed on the reactive Qdrant impls.

### `rag-testing/` — reactive in-memory stubs

- `InMemoryReactiveCorpusStore implements ReactiveCorpusStore` —
  `@Alternative @Priority(1) @ApplicationScoped`. No `@IfBuildProperty` gate — these
  stubs have no Hibernate Reactive dependency and are safe to activate unconditionally
  (same reasoning as PP-20260529-5745c1 for bridges). Injects `InMemoryCorpusStore`,
  delegates with `Uni.createFrom().item(...)`. Void methods use `.invoke() + .replaceWithVoid()`.

- `InMemoryReactiveCaseRetriever implements ReactiveCaseRetriever` — same pattern,
  injects `InMemoryCaseRetriever`.

Follows `casehub-eidos` `persistence-memory/` pattern (`InMemoryReactiveAgentRegistry` —
`@Alternative @Priority(1) @ApplicationScoped`, no build-property gate).

**Pre-existing fix:** The existing blocking stubs `InMemoryCorpusStore` and
`InMemoryCaseRetriever` are missing `@ApplicationScoped` — they are dependent-scoped
by default. Since the reactive wrappers `@Inject` them, scope mismatch would give each
injection point its own instance with its own backing data. Fix: add `@ApplicationScoped`
to both existing blocking stubs in this branch. This aligns with canonical impls
(qhorus `InMemoryChannelStore`, eidos `InMemoryAgentRegistry` — both `@ApplicationScoped`).

## Consumer dependency path

```
casehub-engine (or any consumer)
  └── casehub-rag-api (compile)          ← blocking + reactive SPIs, no heavy deps
  └── casehub-rag-testing (test)         ← in-memory stubs for both tiers
  └── casehub-rag (runtime, via app)     ← Qdrant impls + bridge
```

Engine depends on `casehub-rag-api` at compile time for both `CaseRetriever` and
`ReactiveCaseRetriever`. No Qdrant, LangChain4j, or other heavy deps on the
compile classpath. The deploying application brings `casehub-rag` at runtime.

## Build gating

Property: `casehub.rag.reactive.enabled` (default `false`).

- `false` (default): only the `@DefaultBean` bridges are active — blocking impls
  wrapped on the worker pool. Blocking-only consumers pay zero Hibernate Reactive cost.
- `true`: native reactive Qdrant impls + reactive in-memory stubs activate.

## ArchUnit constraints

Update `rag-api/` `DependencyConstraintTest`:
- Existing rules (no Quarkus, no Jakarta, no LangChain4j, no Qdrant, no casehub domain) remain.
- Mutiny is allowed — it is `provided` scope and passes the pragmatic infrastructure test.

New parity test in `rag-api/`:
- Auto-discover `Reactive*` interfaces by naming convention.
- Assert each blocking SPI method has a corresponding `Uni<T>` method on the reactive interface.
- Vacuous-pass guard: at least 1 pair must be found.

## Out of scope

- Reactive Qdrant gRPC implementation details — deferred to implementation phase.
  The `ListenableFuture` → `Uni` bridge pattern is well-established.
- Engine-side `casehub-engine-rag` adapter module — created when engine integrates
  (casehubio/parent#164).
- Native reactive in-memory stubs that use `Multi` streaming — not needed; `Uni<List<T>>`
  is sufficient parity with the blocking signatures.