---
layout: post
title: "The Right Cache at the Wrong Layer"
date: 2026-08-06
type: pivot
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [onnx, qdrant, embedding-cache, performance]
---

BGE-M3 runs at 4.5 seconds per entry on CPU. That's the full ONNX forward pass — dense 1024-dim, sparse learned lexical, ColBERT multi-vector — all from one model. At 2590 garden entries, a full reindex takes 90 minutes. Scale that to 100k entries and you're looking at five days straight.

The `ReentrantLock` from the SIGSEGV fix isn't the bottleneck. I checked — ingestion runs single-threaded during startup. The lock is never contended. The bottleneck is raw CPU time: 550 million parameters, no GPU, no parallelism.

We built an embedding cache to fix this. `CachingMultiModalEmbedder` wraps the real embedder, caches `MultiModalEmbedding` results in SQLite keyed by SHA-256 of content plus model version. On reindex, unchanged entries hit the cache and skip ONNX entirely. New `casehub-neocortex-rag-cache` module in neocortex — serializer, SQLite store, wrapper. The cache populates on first run and pays off on every subsequent reindex.

The CDI wiring was educational. I tried a `@Decorator` on `MultiModalEmbedder` first — clean pattern, same approach as `DedupEmbeddingIngestor`. Quarkus Arc had other ideas. The decorator was discovered, method interception worked, but `@PostConstruct` never fired. No error, no warning. The decorator appeared completely dead. Turns out Arc silently skips lifecycle callbacks on decorators. The fix was wrapping the embedder at the `@Produces` site in `HybridSearchProducer` instead — the producer method has full access to config and can conditionally wrap. Simpler, and it sidesteps Arc's decorator limitations entirely.

The cache works. It populates during ingestion and serves hits on reindex. But then the obvious question: who actually reindexes?

End users don't. They set up once. A developer might reindex after Qdrant corruption or a model migration — that's our problem, not theirs. The cache turns a 90-minute developer annoyance into a 3-minute one. It does nothing for the end user staring at a 90-minute first-time setup. At 100k entries, "please wait five days" is not a product.

Qdrant has native snapshot/restore. Create a snapshot of the fully indexed collection, ship it, restore in seconds. No embedding, no upserting. The infrastructure for generating those snapshots benefits from the cache we built — generate once, cache the embeddings, snapshot the result. But the user-facing solution isn't "faster embedding" — it's "no embedding at all."

The cache was the right engineering instinct (don't recompute what hasn't changed) applied at the wrong layer (the user never recomputes at all). The pivot is from caching computation to distributing results.
