---
layout: post
title: "When Your Bridge Is the Problem It Solves"
date: 2026-06-10
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [reactive, mutiny, qdrant, grpc, memoize]
---

# When Your Bridge Is the Problem It Solves

The reactive SPIs shipped in the last session — `ReactiveCorpusStore`, `ReactiveCaseRetriever`, `@DefaultBean` bridges that wrap the blocking Qdrant implementations on the Mutiny worker pool via `runSubscriptionOn()`. Clean, functional, correct. The bridges let reactive consumers use the RAG pipeline without blocking the Vert.x event loop.

But every call goes: event loop → worker pool thread → `ListenableFuture.get()` → Qdrant gRPC → response → back to the subscribing context. The Qdrant client already returns `ListenableFuture`. We're paying for a thread hop to call `.get()` on something that was natively async to begin with.

## The bridge pattern

`Futures.addCallback` with `directExecutor()` on a `ListenableFuture`, completing a Mutiny emitter directly. No thread hop. The callback runs on the gRPC Netty event loop — the response is already there, just hand it to Mutiny.

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

`onTermination` wires Uni cancellation to the underlying future — if a timeout fires, the gRPC call gets cancelled too. `cancel(false)` because you don't interrupt Netty event loops.

The tricky part isn't the bridge. It's what feeds into it.

## Blocking ONNX in a reactive pipeline

`EmbeddingModel.embed()` and `SparseEmbedder.embed()` are blocking — synchronous ONNX Runtime inference through JNI. There's no async variant and there won't be one. So the reactive implementations can't be purely async end-to-end. The pattern is:

```
assertTenant (inline)
  → embed on worker pool → Uni<embeddings>
    → chain natively async Qdrant call via toUni() → Uni<result>
```

Blocking work goes to the worker pool. Qdrant I/O stays natively async. Methods with no embedding (`deleteDocument`, `deleteCorpus`, `listDocuments`) are purely async — no worker pool involvement at all.

## The ensureCollection puzzle

The blocking `QdrantCorpusStore` has a lazy collection creation guard — `knownCollections.contains(collection)` check, then `collectionExistsAsync().get()`, then `createCollectionAsync().get()` if needed. Standard check-then-act. Works fine on a worker thread where concurrent calls are unlikely.

In the reactive path, concurrent `ingest()` calls for the same corpus are normal — multiple Uni subscriptions in flight simultaneously. The check-then-act races. Two concurrent callers both pass `contains()`, both issue `collectionExistsAsync`, both try to create.

`ConcurrentHashMap<String, Uni<Void>>` with `computeIfAbsent` and `.memoize().indefinitely()`. The lambda in `computeIfAbsent` creates a memoized Uni — only one subscription happens, all callers share the result. Concurrent creation is deduplicated at the Uni level.

But memoize has a subtlety I had to verify from source. The spec review assumed `memoize().indefinitely()` might not cache failures — "replays successes, re-subscribes on failure." I read the actual Mutiny source: `UniMemoizeOp.onFailure()`, line 131: `state = State.CACHING; cachedResult = failure`. Identical to the success path. Memoize caches failures permanently.

Without explicit eviction, a transient Qdrant failure during collection creation permanently poisons the cache. Every subsequent `ingest()` for that collection instantly fails with the original error. The fix is `.onFailure().invoke(() -> ensuredCollections.remove(k))` placed *before* `.memoize().indefinitely()` — the eviction fires before memoize commits the failure.

## CDI wiring — a producer class as a gate

The reactive impls are plain POJOs with constructors — no CDI annotations on the classes themselves. A dedicated `ReactiveRagBeanProducer` produces them, gated at the class level with `@IfBuildProperty(name = "casehub.rag.reactive.enabled", stringValue = "true")`. When the property is false (default), the entire producer is absent from the bean graph and the `@DefaultBean` bridges serve `ReactiveCorpusStore` injection points.

One subtlety: `@Startup` on the producer class eagerly creates the producer instance and runs its `@PostConstruct`, but does *not* eagerly invoke `@Produces` methods. Those remain lazy. The `@PostConstruct` caches `embeddingModel.dimension()` — which can trigger a full `embed("test")` call on first invocation — so the blocking work happens on the main thread at startup, not on the event loop when the first request arrives.

The blocking `QdrantCorpusStore` and `HybridCaseRetriever` stay active. Unlike eidos, where enabling reactive mode suppresses the blocking JPA impls (because running both against the same datasource is problematic), neural-text's `QdrantClient` is thread-safe. Both paths share the same client instance. Consumers injecting `CorpusStore` (blocking) get the blocking impl regardless of the reactive flag.

## What's deferred

When the reactive impls prove stable in a consuming deployment, the blocking Qdrant impls become candidates for replacement with reactive-to-blocking wrappers — `blockingAwait()` on the reactive impl. That eliminates the duplicate Qdrant interaction code entirely, making the reactive path the single source of truth. Not yet — the reactive path needs to be exercised first.
