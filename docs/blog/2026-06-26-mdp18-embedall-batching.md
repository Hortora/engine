---
layout: post
title: "embedAll batching — the fix that starts with a first-principles question"
date: 2026-06-26
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [rag, batching, spi-design, qdrant]
---

The Hortora engine's garden corpus hit ~6,500 entries. When `CorpusIngestionService` bootstraps from an empty cursor, it passes every chunk to `QdrantEmbeddingIngestor.ingest()` in a single call. Three unbounded operations fire in sequence: `embedAll` to Ollama (HTTP), `embedBatch` through ONNX (memory), `upsertAsync` to Qdrant (gRPC). Ollama rejected the 6,500-text request with 400 Bad Request.

The interesting part wasn't the fix — it was where to put it. The `EmbeddingIngestor` SPI sits in `rag-api` (tier 1, pure Java). Batch size is a tier-3 infrastructure concern: HTTP body limits, gRPC message size, ONNX memory. Leaking it into the SPI would violate the tier structure. Putting it in `CorpusIngestionService` would mean every future caller re-implements batching, and `doProcessBinding` and `doProcessWatchEvent` would need identical logic with no SPI to standardise it.

I put the batching in the implementation. The ingestor orchestrates all three subsystems with size limits — it's the only layer that can batch them in lockstep. Idempotent upsert (deterministic UUIDs from `sourceDocumentId + chunkIndex`) makes partial-batch failure safe. The caller doesn't advance the cursor on exception, so the next poll retries everything.

Pre-computing chunk indices before the batch loop was a deliberate clarity choice. A counter carried across batches produces identical results, but pre-computation makes the batch-size-independence of deterministic UUIDs trivially verifiable — indices are assigned once, before any I/O.

The `buildPoint()` duplication between blocking and reactive implementations was already present but tolerable. Adding `computeChunkIndices()` — also pure, also identical — tipped the balance. We extracted both to a package-private `QdrantPointBuilder`. No abstraction layer, just shared pure computation.

One gotcha from the implementation: using `Integer.MAX_VALUE` as a "no batching" sentinel triggers integer overflow in the standard ceiling division formula `(size + batchSize - 1) / batchSize`. The fix:

```java
int effectiveBatchSize = Math.min(batchSize, chunks.size());
int totalBatches = (chunks.size() + effectiveBatchSize - 1) / effectiveBatchSize;
```