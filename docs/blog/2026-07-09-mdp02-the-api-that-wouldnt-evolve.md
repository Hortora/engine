---
layout: post
title: "The API That Wouldn't Evolve"
date: 2026-07-09
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [cbr, splade, bm25, qdrant, fusion]
series: issue-124-cbr-fusion-consolidation
---

*Part of a series on [#124 — Consolidate RrfFusion with generic CbrFusion](https://github.com/casehubio/neocortex/issues/124). Previous: [Three Implementations of the Same Algorithm](2026-07-09-mdp01-three-implementations-of-the-same-algorithm.md).*

## The API That Wouldn't Evolve

The first surprise was Qdrant's `updateCollection` API. It has a `sparse_vectors_config` field — same type as on creation, same map structure. Everything about it says "add or modify sparse vectors on an existing collection." It doesn't. It only modifies parameters of vectors that already exist. Try to add a new sparse vector to a collection that was created without one, and you get:

```
INVALID_ARGUMENT: Wrong input: Not existing vector name error: sparse
```

The field name lies. The proto type lies. The error message is the only honest actor.

This matters because CBR collections start dense-only. When you enable SPLADE or BM25 later — which is the whole point of config-driven activation — the collection schema needs to grow. Since Qdrant won't do it incrementally, the only option is delete-and-recreate. Same pattern as the existing dimension migration path: blow away the collection, recreate with the full vector config, log a warning that reconciliation will repopulate the data.

## Four Legs and a Weight Problem

With the schema evolution sorted, the actual SPLADE and BM25 implementation was straightforward. `CbrPointBuilder` gains two optional vector parameters — a sparse embedding `Map<Integer, Float>` for SPLADE and a `Document` text for BM25 server-side inference. `store()` calls `SparseEmbedder.embed()` when SPLADE is enabled, runs `CamelCaseExpander.expand()` for BM25 text pre-processing. Both use the same patterns already proven in the RAG module.

The interesting part is `retrieveHybrid()`. It was a 2-leg fusion (feature + dense). Now it's dynamic 2-4 legs: feature (always present) plus up to three semantic legs — dense, SPLADE, and BM25. The legs that participate depend on what's configured and available.

For RRF this doesn't matter — rank-based, all legs participate equally. For Convex Combination, it matters a lot. The weight model from the spec: `vectorWeight` controls the feature/semantic split (application concern), while `ccWeights.dense/sparse/bm25` controls the breakdown within semantic legs (infrastructure tuning). When a leg is disabled, its config weight redistributes among the remaining active legs. Dense defaults to 0.6, sparse and BM25 each 0.2.

The renormalization keeps `vectorWeight` sacred. A query with `vectorWeight=0.3` always gives features 70% of the fused score, regardless of how many semantic legs are active. The semantic 30% just splits differently — three active legs get 0.6:0.2:0.2; two active legs get 0.75:0.25.

## The Second Proto Surprise

Qdrant's gRPC protos have two vector types: `Vector` (for writes) and `VectorOutput` (for reads). They're structurally identical but separate proto messages. When you scroll existing points to add missing sparse vectors, you can't copy the existing vectors into a new `PointStruct` — the types don't match, and there's no conversion.

The fix is `updateVectorsAsync` with `PointVectors` — it updates only the specified named vectors without touching existing ones. No need to read, copy, or rebuild the full point. The reconciliation enrichment phase scrolls all points, checks which are missing SPLADE or BM25 vectors, and calls `updateVectorsAsync` in batches for just the new vectors.

The three-phase reconciliation model — orphan cleanup, reindex, vector enrichment — means enabling SPLADE on a deployment with thousands of existing cases is a config change plus a reconciliation run. No migration scripts, no downtime.
