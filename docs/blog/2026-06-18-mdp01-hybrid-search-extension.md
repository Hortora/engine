---
layout: post
title: "The extension you already built"
date: 2026-06-18
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [onnx, splade, hybrid-search, cross-encoder, casehub-inference-quarkus, cdi]
series: issue-9-hybrid-search-splade-reranker
---

*Part of a series on [#9 — hybrid search](https://github.com/Hortora/engine/issues/9). Previous: [Tearing out the abstraction layer](2026-06-16-mdp01-incremental-reindexing.md).*

## Hortora Engine — The extension you already built

The previous entry ended with a forward pointer: Phase 2 will add a `"sparse"` vector name for SPLADE. Today was Phase 2.

I expected the hard work to be ONNX model loading — getting the JNI libraries right, managing model lifecycle, handling the native image metadata. The neural-text repo has five inference modules and the ONNX Runtime JNI wrapper alone is 280 lines of session management and tokenization. Then I looked at what was already built.

`casehub-inference-quarkus` exists. It handles all of it — config mapping (`casehub.inference.models.<name>.*`), a `ConcurrentHashMap` model pool with `ShutdownEvent` cleanup, and 1578 lines of GraalVM reachability metadata. The engine doesn't need to know what ONNX Runtime is. It adds one dependency and gets `@Inference`-qualified `InferenceModel` beans by name.

The engine's contribution is a bridge — 31 lines. `HybridSearchProducer` takes an `@Inference("splade") InferenceModel` and wraps it in a `SparseEmbedder`. Takes an `@Inference("reranker") InferenceModel` and wraps it in a `CrossEncoderReranker`. That's the entire integration.

The interesting design question was conditionality. SPLADE and the reranker should be genuinely absent when ONNX models aren't configured — not null stubs, not no-ops, actually non-existent in the CDI container. `RagBeanProducer` checks `Instance<SparseEmbedder>.isResolvable()` and falls back to dense-only when it returns false. The bean needs to not exist, not exist-but-be-null.

My first attempt used `@Produces @Singleton` methods that returned null when config was absent. The spec review caught this — `@Singleton` is a pseudo-scope and returning null is technically legal per CDI spec, but it makes `isResolvable()` return true. The consumer sees a resolvable bean that happens to be null. Semantically wrong.

The fix is `@LookupIfProperty` with `StringValueMatch.REGEX`:

```java
@LookupIfProperty(name = "casehub.inference.models.splade.model-path",
                   stringValue = ".+", match = StringValueMatch.REGEX)
SparseEmbedder sparseEmbedder(@Inference("splade") InferenceModel spladeModel) {
    return new SparseEmbedder(spladeModel);
}
```

Property absent → bean doesn't exist. Property empty → doesn't exist. Property set to a real path → exists. Clean signal.

The CDI wiring in tests surfaced a gotcha worth a garden entry. `@Inference` has `@Nonbinding` on its `value()`, which means CDI treats all `@Inference("splade")` and `@Inference("reranker")` qualifiers as identical. Two `@Produces` methods with different qualifier values cause `AmbiguousResolutionException`. The fix is a single producer with `InjectionPoint` dispatch — read the qualifier value at runtime, switch on it. Not obvious until it breaks.

The other piece is `CollectionMigration` — a startup observer that runs before the ingestion service. Existing Qdrant collections were created dense-only. When SPLADE is newly enabled, the collection needs a sparse vector space that can't be added after creation. The migration detects this by querying the collection schema directly — `hasSparseVectorsConfig()` on the protobuf `CollectionParams`. If the collection exists but lacks sparse vectors, it deletes and resets the ingestion cursor. The next startup poll sees no cursor, runs `fullScan()`, and re-ingests everything with both dense and sparse embeddings.

The detection is stateless. The collection schema is the state. No marker files, no flags — just ask Qdrant what the collection looks like.

With `inference-quarkus` handling the ONNX lifecycle and `casehub-rag` handling the hybrid search pipeline, the engine's Phase 2 is six files and zero framework code. The platform did the work; the engine plugs in.
