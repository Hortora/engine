# Native Reactive Qdrant Implementations + Bridge Thread-Offloading Tests

**Date:** 2026-06-09
**Issues:** casehubio/neural-text#13, casehubio/neural-text#14
**Branch:** `issue-13-native-reactive-qdrant`
**Module:** `rag/` (implementations), `rag-api/` (Javadoc only)

---

## Problem

The `@DefaultBean` bridges (`BlockingToReactiveCorpusStore`, `BlockingToReactiveCaseRetriever`)
wrap blocking Qdrant calls on the Mutiny worker pool via `runSubscriptionOn()`. This works but
adds a thread hop per call — the blocking impl calls `ListenableFuture.get()` on a worker thread
when the Qdrant client already provides natively async `ListenableFuture` methods.

Separately, the bridge tests verify delegation correctness but not that the delegate actually
runs on a worker thread. Removing `runSubscriptionOn()` would silently block the Vert.x event
loop with no test failure.

## Solution

### #13 — Native reactive Qdrant implementations

Two new classes in `rag/`, package `io.casehub.rag.runtime`:

**`ReactiveQdrantCorpusStore implements ReactiveCorpusStore`**
- Plain POJO with an explicit constructor (no CDI annotations on the class)
- Produced by `ReactiveRagBeanProducer` (see CDI wiring below)
- Displaces the `@DefaultBean` bridge when the build property is set

**`ReactiveHybridCaseRetriever implements ReactiveCaseRetriever`**
- Same pattern — plain POJO, produced by `ReactiveRagBeanProducer`

**Constructor parameters (matching blocking impls, with additions noted):**
- `QdrantClient client`
- `EmbeddingModel embeddingModel`
- `SparseEmbedder sparseEmbedder`
- `TenancyStrategy tenancyStrategy`
- `String denseVectorName`, `String sparseVectorName`
- `int denseDimension` — **new**, `ReactiveQdrantCorpusStore` only (see below)
- `CurrentPrincipal currentPrincipal`
- `CrossEncoderReranker reranker` (nullable — `ReactiveHybridCaseRetriever` only)
- Retrieval config values (`denseTopK`, `sparseTopK`, `rrfK`, `rerankEnabled`, `rerankTopN`
  — `ReactiveHybridCaseRetriever` only)

**`denseDimension` caching:** `EmbeddingModel.dimension()` is potentially blocking —
the default implementation calls `embed("test")` (a full ONNX inference). Even
`DimensionAwareEmbeddingModel` only caches after the first call. In the reactive path,
`dimension()` is needed inside `ensureCollection` (collection creation request), which
runs on the gRPC thread after `collectionExistsAsync`. Calling a blocking embedding
on the gRPC thread is wrong. Fix: `ReactiveRagBeanProducer` calls `embeddingModel.dimension()`
at startup and passes the cached value as `int denseDimension` to the constructor.
`ReactiveRagBeanProducer` is annotated `@Startup` — this eagerly creates the producer
instance and runs its `@PostConstruct`, which caches `embeddingModel.dimension()` on the
main thread. Note: `@Startup` on the class does NOT eagerly invoke `@Produces` methods —
those remain lazy. But that's fine: `@PostConstruct` caches the dimension in a field, and
the `@Produces` methods reference the cached value, not `embeddingModel.dimension()`
directly. Without `@Startup`, the producer itself would be lazily created, and `@PostConstruct`
would run on whatever thread first triggers injection — potentially the event loop.

#### CDI wiring — dedicated `ReactiveRagBeanProducer`

A new producer class, gated at the class level:

```java
@IfBuildProperty(name = "casehub.rag.reactive.enabled", stringValue = "true")
@ApplicationScoped
@Startup
public class ReactiveRagBeanProducer {
    @Inject RagConfig config;
    @Inject QdrantClient client;
    @Inject EmbeddingModel embeddingModel;
    @Inject SparseEmbedder sparseEmbedder;
    @Inject Instance<CrossEncoderReranker> rerankerInstance;
    @Inject CurrentPrincipal currentPrincipal;

    private int denseDimension;

    @PostConstruct
    void init() {
        denseDimension = embeddingModel.dimension();
    }

    @Produces @ApplicationScoped
    ReactiveQdrantCorpusStore corpusStore() {
        return new ReactiveQdrantCorpusStore(client, embeddingModel, sparseEmbedder,
            config.tenancyStrategy(), config.denseVectorName(), config.sparseVectorName(),
            denseDimension, currentPrincipal);
    }

    @Produces @ApplicationScoped
    ReactiveHybridCaseRetriever caseRetriever() {
        CrossEncoderReranker reranker = rerankerInstance.isResolvable()
            ? rerankerInstance.get() : null;
        return new ReactiveHybridCaseRetriever(client, embeddingModel, sparseEmbedder,
            config.tenancyStrategy(), config.denseVectorName(), config.sparseVectorName(),
            config.retrieval().denseTopK(), config.retrieval().sparseTopK(),
            config.retrieval().rrfK(), config.retrieval().rerankEnabled(),
            config.retrieval().rerankTopN(), reranker, currentPrincipal);
    }
}
```

`@IfBuildProperty` on the class gates all `@Produces` methods — when the property is
false (default), the entire producer is not installed and the `@DefaultBean` bridges
remain active. When true, the produced beans displace the bridges.

The reactive impls are plain POJOs with constructors — testable via `new` in plain JUnit,
matching the blocking impls' test pattern (`QdrantCorpusStoreTest` constructs
`QdrantCorpusStore` directly). No `RagConfig` stubbing needed in tests.

`RagBeanProducer` (blocking) is not modified.

#### CDI gating rationale

`@IfBuildProperty` without a `@ConfigRoot(phase = BUILD_TIME)` declaration — neural-text has
no `deployment/` module. The property is evaluated during the consuming app's augmentation.
Matches the casehub-eidos precedent (`casehub.eidos.reactive.enabled`).

Consuming apps opt in by setting `casehub.rag.reactive.enabled=true` in `application.properties`.
Default is `false` — only the `@DefaultBean` bridges are active.

#### Why the blocking impls do not need symmetric gating

In eidos, both `JpaAgentRegistry` and `JpaReactiveAgentRegistry` are symmetrically gated
(`@IfBuildProperty ... "false" enableIfMissing=true` / `"true"`) because they share a datasource
where running blocking JPA and Hibernate Reactive concurrently is problematic.

In neural-text, the blocking `QdrantCorpusStore` and `HybridCaseRetriever` share the same
`QdrantClient` instance, which is fully thread-safe (Netty/gRPC under the hood). No resource
conflict. The blocking impls must stay active because they serve `CorpusStore` and `CaseRetriever`
injection points — blocking consumers should not break when someone enables reactive mode.

CDI graph when `casehub.rag.reactive.enabled=true`:
- `CorpusStore` → `QdrantCorpusStore` (blocking consumers unaffected)
- `ReactiveCorpusStore` → `ReactiveQdrantCorpusStore` (native async, displaces bridge)
- `BlockingToReactiveCorpusStore` (`@DefaultBean`) — not installed. `@DefaultBean` means
  "only if no other bean of this type exists." The produced `ReactiveQdrantCorpusStore`
  satisfies `ReactiveCorpusStore`, so the bridge is removed from the bean graph entirely —
  not instantiated, no dependency injection overhead

#### ListenableFuture → Uni bridge

Package-private utility class `QdrantFutures` in `io.casehub.rag.runtime`:

```java
static <T> Uni<T> toUni(ListenableFuture<T> future) {
    return Uni.createFrom().emitter(em -> {
        em.onTermination(() -> future.cancel(false));
        Futures.addCallback(future, new FutureCallback<>() {
            public void onSuccess(T result) { em.complete(result); }
            public void onFailure(Throwable t) { em.fail(t); }
        }, MoreExecutors.directExecutor());
    });
}
```

Single static method. `directExecutor()` — callback runs on the gRPC executor (Qdrant's
internal Netty event loop), no extra thread hop. `onTermination` wires Uni cancellation to
`ListenableFuture.cancel()` — if the Uni is cancelled (e.g. timeout), the underlying gRPC
call is cancelled too.

**Threading contract:** `toUni()` delivers on the gRPC thread. Downstream operators after
`toUni()` run on that gRPC thread unless explicitly switched. Only lightweight mapping and
further async calls should follow — any blocking work (ONNX inference, etc.) must be
explicitly offloaded via `runSubscriptionOn(workerPool)`. No `emitOn()` is added — the
consumer controls their own execution context, consistent with the platform convention
(neither `ReactiveCaseMemoryStore` nor `ReactiveAgentRegistry` guarantee delivery thread).

#### Blocking embedding offload

`EmbeddingModel.embed()` and `SparseEmbedder.embed()` are blocking (synchronous ONNX inference).
Native reactive impls offload embedding to the worker pool, then chain natively async Qdrant calls:

```
assertTenant (inline, no I/O)
  → embed on worker pool via runSubscriptionOn(workerPool) → Uni<EmbeddingResult>
    → chain natively async Qdrant call via toUni() → Uni<result>
```

Methods with no embedding (`deleteDocument`, `deleteCorpus`, `listDocuments`) are purely
native async — no worker pool involved.

#### Method-by-method design

**`ReactiveQdrantCorpusStore`:**

| Method | Embedding | Qdrant call | Notes |
|---|---|---|---|
| `ingest` | Dense + sparse batch on worker pool | `ensureCollection` → `upsertAsync` via `toUni()` | ensureCollection first (see below), then embed, build points, upsert |
| `deleteDocument` | None | `deleteAsync` (by filter) via `toUni()` | Pure async |
| `deleteCorpus` | None | `deleteCollectionAsync` or `deleteAsync` (by filter) via `toUni()` | Depends on `TenancyStrategy`. SEPARATE_COLLECTIONS mode: evicts from `ensuredCollections` after successful deletion |
| `listDocuments` | None | `scrollAsync` via `toUni()` in recursive pagination | See pagination design below |

**`ReactiveHybridCaseRetriever`:**

| Method | Embedding | Qdrant call | Notes |
|---|---|---|---|
| `retrieve` | Dense + sparse on worker pool | `collectionExistsAsync` → `queryAsync` via `toUni()` | Optional reranking offloaded to worker pool (blocking ONNX) |

`retrieve` flow:
1. `assertTenant` — inline
2. `toUni(client.collectionExistsAsync(...))` — native async
3. If exists → offload dense + sparse embedding to worker pool
4. Chain `toUni(client.queryAsync(queryPoints))` — build prefetch queries with RRF fusion
5. Map `ScoredPoint` → `RetrievedChunk` — inline (CPU-only, lightweight on gRPC thread)
6. If reranking enabled → offload `reranker.rerank()` to worker pool (blocking ONNX)

**Note on collectionExistsAsync overhead (step 2):** This adds one gRPC round-trip before
every retrieval, matching the blocking `HybridCaseRetriever` behaviour. The cost is negligible
relative to embedding + query. Potential optimisation: catch the "collection not found" error
from `queryAsync` directly and map to empty results — eliminates the check. Deferred.

#### ensureCollection — concurrent-safe reactive design

The blocking `ensureCollection` has a check-then-act pattern (`if !known → check exists → create`)
that is racy under concurrent calls. In the reactive context, concurrent `ingest()` calls for the
same corpus make this race likely.

Design: `ConcurrentHashMap<String, Uni<Void>>` with `computeIfAbsent` + `.memoize().indefinitely()`.
Deduplicates concurrent creation attempts and caches the result.

```java
private final ConcurrentHashMap<String, Uni<Void>> ensuredCollections = new ConcurrentHashMap<>();

private Uni<Void> ensureCollection(String collection) {
    return ensuredCollections.computeIfAbsent(collection, k ->
        toUni(client.collectionExistsAsync(k))
            .chain(exists -> {
                if (exists) return Uni.createFrom().voidItem();
                // buildCreateRequest: new private helper extracting the inline
                // CreateCollection builder from blocking QdrantCorpusStore.ensureCollection()
                return toUni(client.createCollectionAsync(buildCreateRequest(k, denseDimension)))
                    .replaceWithVoid();
            })
            .onFailure().invoke(() -> ensuredCollections.remove(k))
            .memoize().indefinitely()
    );
}
```

- `computeIfAbsent` ensures only one Uni is created per collection name
- `.memoize().indefinitely()` ensures the Uni is subscribed to once; subsequent callers
  get the cached result
- **Verified from Mutiny source (`UniMemoizeOp.onFailure()`, line 131):** `memoize().indefinitely()`
  **DOES cache failures** — `state = CACHING; cachedResult = failure`. Without eviction,
  a failed collection creation would be permanently cached
- `.onFailure().invoke(() -> map.remove(k))` is placed before `.memoize()` in the pipeline.
  On failure: (1) the map entry is removed, (2) memoize caches the failure. Threads that
  already hold a reference to the failed Uni correctly receive the cached failure — their
  operation genuinely failed. Subsequent calls to `ensureCollection` create a fresh Uni
  because the map entry was removed at step (1)
- Qdrant's idempotent collection creation is the final safety net — if two Uni chains do
  race past the guard, the second `createCollectionAsync` fails gracefully

**Eviction on deleteCorpus:** In `SEPARATE_COLLECTIONS` mode, `deleteCorpus` deletes the
Qdrant collection. After successful deletion, `ensuredCollections.remove(collection)` must
be called — otherwise a subsequent `ingest()` would skip `ensureCollection` (cached "exists"
Uni) and the `upsertAsync` would fail against a deleted collection. This mirrors the blocking
impl's `knownCollections.remove(collection)`. In `SHARED_COLLECTION` mode, `deleteAsync` only
removes points by tenant filter — the collection still exists, no eviction needed.

#### listDocuments — reactive scroll pagination

Recursive `Uni.flatMap()` pattern. Mutiny handles this without stack overflow — each
`.flatMap()` is Uni composition (heap-allocated continuation), not stack recursion.

```java
Uni<List<String>> listDocuments(CorpusRef corpus) {
    // ... assertTenant, resolve collection ...
    return toUni(client.collectionExistsAsync(collection))
        .chain(exists -> {
            if (!exists) return Uni.createFrom().item(List.<String>of());
            return scrollPage(collection, tenantFilter, null, new LinkedHashSet<>())
                .map(List::copyOf);
        });
}

private Uni<Set<String>> scrollPage(String collection, Optional<Filter> tenantFilter,
        PointId offset, Set<String> accumulator) {
    ScrollPoints.Builder builder = ScrollPoints.newBuilder()
        .setCollectionName(collection)
        .setLimit(100)
        .setWithPayload(WithPayloadSelectorFactory.enable(true));
    tenantFilter.ifPresent(builder::setFilter);
    if (offset != null) builder.setOffset(offset);

    return toUni(client.scrollAsync(builder.build()))
        .flatMap(response -> {
            for (RetrievedPoint point : response.getResultList()) {
                Value docId = point.getPayloadMap().get("sourceDocumentId");
                if (docId != null && docId.hasStringValue()) {
                    accumulator.add(docId.getStringValue());
                }
            }
            if (!response.hasNextPageOffset()) {
                return Uni.createFrom().item(accumulator);
            }
            return scrollPage(collection, tenantFilter,
                response.getNextPageOffset(), accumulator);
        });
}
```

Each page is a native async gRPC call via `toUni()`. The `flatMap` chain composes on the
gRPC thread (lightweight — just extracting strings and checking a boolean). No worker pool
involvement.

#### Dependencies

No new Maven dependencies. Guava (`Futures`, `FutureCallback`, `MoreExecutors`) is
already transitive from the Qdrant client. Mutiny is already a dependency of `rag/`
(transitive from `quarkus-arc`).

---

### #14 — Bridge thread-offloading regression tests

#### Thread assertion on bridge tests

Both `BlockingToReactiveCorpusStoreTest` and `BlockingToReactiveCaseRetrieverTest` get
a new test that verifies the delegate executes on a worker thread.

Pattern follows the platform `BlockingToReactiveBridgeThreadingTest` precedent — thread ID
comparison via `AtomicLong`, no thread name matching:

```java
@Test
void delegateExecutesOnWorkerThread() {
    var capturedId = new AtomicLong(Thread.currentThread().getId());

    CorpusStore blocking = new CorpusStore() {
        public void ingest(CorpusRef corpus, List<ChunkInput> chunks) {
            capturedId.set(Thread.currentThread().getId());
        }
        // ... other methods
    };

    var bridge = new BlockingToReactiveCorpusStore(blocking);
    bridge.ingest(corpus, chunks).await().indefinitely();

    assertNotEquals(Thread.currentThread().getId(), capturedId.get(),
        "ingest() must offload delegate to a worker thread, "
        + "not run on the subscribing thread");
}
```

`AtomicLong` initialised to the test thread's ID — if the delegate is never called, the
assertion still fails correctly (captured == test thread). No thread name matching — the
assertion is "different thread", not "specific pool name", avoiding fragility if SmallRye
changes its worker pool naming convention.

Every method in each bridge gets its own test — matching the platform
`BlockingToReactiveBridgeThreadingTest` precedent (which tests all 6 `CaseMemoryStore`
methods). `BlockingToReactiveCorpusStore` has 4 methods (`ingest`, `deleteDocument`,
`deleteCorpus`, `listDocuments`) and `BlockingToReactiveCaseRetriever` has 1 (`retrieve`).
Total: 5 thread-offloading tests.

#### Parity test — no change

The manual `PAIRS` map in `BlockingReactiveParityTest` is adequate for two SPI pairs.
No third pair is being added in this branch.

#### Javadoc on reactive SPIs

Single-line class-level Javadoc on each reactive SPI in `rag-api/`. The contract is
"non-blocking, safe to call from the event loop" — no guarantee about delivery thread,
consistent with the platform precedent (`ReactiveCaseMemoryStore`, `ReactiveAgentRegistry`
— neither specifies a delivery thread):

```java
/** Non-blocking counterpart of {@link CorpusStore}. Safe to subscribe to from the Vert.x event loop. */
public interface ReactiveCorpusStore { ... }

/** Non-blocking counterpart of {@link CaseRetriever}. Safe to subscribe to from the Vert.x event loop. */
public interface ReactiveCaseRetriever { ... }
```

No method-level Javadoc — method signatures mirror the blocking SPI exactly and
the parity test enforces that.

---

## Testing

| Test class | Module | Type | What it verifies |
|---|---|---|---|
| `QdrantFuturesTest` | `rag` | Unit | `toUni()` propagates success, failure from `ListenableFuture` to `Uni`. Verifies cancellation propagation: cancelling the Uni calls `future.cancel()`. Uses Guava `SettableFuture`. |
| `ReactiveQdrantCorpusStoreTest` | `rag` | Plain JUnit + Testcontainers | Ingest → listDocuments round-trip, deleteDocument, deleteCorpus, tenant isolation, collection auto-creation. Mirrors `QdrantCorpusStoreTest`. Not `@QuarkusTest` — constructs the class directly. |
| `ReactiveHybridCaseRetrieverTest` | `rag` | Plain JUnit + Testcontainers | Ingest → retrieve round-trip, hybrid RRF fusion, cross-encoder reranking, tenant isolation. Mirrors `HybridCaseRetrieverTest`. Not `@QuarkusTest`. |
| `BlockingToReactiveCorpusStoreTest` | `rag` | Unit (addition) | New: all 4 methods verified — delegate executes on worker thread (thread ID), not subscribing thread. Platform `BlockingToReactiveBridgeThreadingTest` pattern. |
| `BlockingToReactiveCaseRetrieverTest` | `rag` | Unit (addition) | New: `retrieve` verified — same thread-offloading assertion. |

Tests are plain JUnit + Testcontainers, not `@QuarkusTest`. CDI wiring (build-property
gating, `@DefaultBean` displacement) is tested implicitly by consuming apps' `@QuarkusTest`
suites.

Existing tests (`QdrantCorpusStoreTest`, `HybridCaseRetrieverTest`, bridge delegation tests,
parity test) are unchanged.

---

## Files changed

| File | Action |
|---|---|
| `rag/src/main/java/io/casehub/rag/runtime/QdrantFutures.java` | New — `ListenableFuture` → `Uni` bridge utility with cancellation propagation |
| `rag/src/main/java/io/casehub/rag/runtime/ReactiveRagBeanProducer.java` | New — `@IfBuildProperty` on class, produces both reactive impls |
| `rag/src/main/java/io/casehub/rag/runtime/ReactiveQdrantCorpusStore.java` | New — plain POJO with constructor |
| `rag/src/main/java/io/casehub/rag/runtime/ReactiveHybridCaseRetriever.java` | New — plain POJO with constructor |
| `rag/src/test/java/io/casehub/rag/runtime/QdrantFuturesTest.java` | New |
| `rag/src/test/java/io/casehub/rag/runtime/ReactiveQdrantCorpusStoreTest.java` | New — plain JUnit + Testcontainers |
| `rag/src/test/java/io/casehub/rag/runtime/ReactiveHybridCaseRetrieverTest.java` | New — plain JUnit + Testcontainers |
| `rag/src/test/java/io/casehub/rag/runtime/BlockingToReactiveCorpusStoreTest.java` | Modified — thread-offloading assertion (thread ID pattern) |
| `rag/src/test/java/io/casehub/rag/runtime/BlockingToReactiveCaseRetrieverTest.java` | Modified — thread-offloading assertion (thread ID pattern) |
| `rag-api/src/main/java/io/casehub/rag/ReactiveCorpusStore.java` | Modified — class Javadoc (non-blocking contract, no delivery thread guarantee) |
| `rag-api/src/main/java/io/casehub/rag/ReactiveCaseRetriever.java` | Modified — class Javadoc |

**Not modified:** `RagBeanProducer` — blocking producers unchanged.

---

## Platform coherence

- **Module tier:** all changes in `rag/` (Tier 3) except Javadoc additions to `rag-api/` (Tier 1) — no tier violation.
- **CDI gating:** follows `reactive-service-build-gating` protocol (PP-20260519-39a9a5). Matches casehub-eidos precedent for library modules without `deployment/`. Blocking impls not symmetrically gated — justified by thread-safe `QdrantClient` (no resource conflict, unlike eidos's JPA/Hibernate Reactive split).
- **CDI wiring:** dedicated `ReactiveRagBeanProducer` with `@IfBuildProperty` on the class. Reactive impls are plain POJOs with constructors — testable in plain JUnit, matching the blocking test pattern.
- **Threading contract:** reactive SPIs are non-blocking and safe to subscribe to from the event loop. No delivery thread guarantee — consistent with `ReactiveCaseMemoryStore` and `ReactiveAgentRegistry` platform precedent.
- **Thread-offloading test pattern:** thread ID comparison via `AtomicLong`, matching platform `BlockingToReactiveBridgeThreadingTest`.
- **Parity:** existing `BlockingReactiveParityTest` covers structural parity. No new SPI methods.
- **No new dependencies.** Guava is transitive from Qdrant client.
- **Doc update needed:** `docs/repos/casehub-neural-text.md` in casehub-parent should note the reactive gating property after implementation ships.

## Out of scope

- Engine-side `casehub-engine-rag` adapter module — created when engine integrates (parent#164)
- Switching parity test to auto-discovery — not warranted for two pairs
- Reactive variants of `SparseEmbedder` or `EmbeddingModel` — upstream concern, not this repo
- Eliminating `collectionExistsAsync` check in `retrieve()` — potential optimisation, deferred
- Replacing blocking Qdrant impls with reactive-to-blocking wrappers — when the reactive impls
  are proven and stable, the blocking `QdrantCorpusStore` and `HybridCaseRetriever` could be
  replaced with wrappers that `blockingAwait()` on the reactive impl. This eliminates duplicate
  Qdrant interaction code, making the reactive impl the single source of truth. Deferred until
  the reactive path is validated in a consuming deployment