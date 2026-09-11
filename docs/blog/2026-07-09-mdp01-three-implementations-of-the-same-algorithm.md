---
layout: post
title: "Three Implementations of the Same Algorithm"
date: 2026-07-09
type: phase-update
entry_type: note
subtype: diary
projects: [neocortex]
tags: [fusion, module-structure, cbr, rag]
series: issue-124-cbr-fusion-consolidation
---

## Three Implementations of the Same Algorithm

neocortex had three separate classes implementing Reciprocal Rank Fusion. `ScoreFusion` in `memory-api` — generic, parameterised over `<T>`. `RrfFusion` in `rag-api` — hardcoded to `RetrievedChunk`. `ConvexCombinationFusion` also in `rag-api` — same hardcoding, same algorithm, different strategy. All three doing the same math.

The duplication wasn't laziness. It was a module boundary constraint. `memory-api` and `rag-api` are independent Tier 1 modules — zero cross-dependency, by design. ScoreFusion sat in memory-api because that's where it was written during CBR Phase 3. RAG couldn't reach it. So RAG had its own copies.

I'd been meaning to clean this up since Phase 3. Issues #122 (SPLADE for CBR) and #123 (BM25 for CBR) forced the question — both need fusion, and duplicating the algorithm a fourth and fifth time wasn't acceptable.

## The Module Boundary Problem

The interesting design question wasn't "which copy survives" — obviously the generic one. It was where to put it.

Option A: make `rag-api` depend on `memory-api`. Wrong — every RAG consumer pulls in all CBR types. Option B: the reverse dependency. Equally wrong. These are peer Tier 1 modules. Neither should depend on the other.

The answer was a third module. `fusion-api` — Tier 1, zero first-party deps, pure Java. It hosts `ScoreFusion`, a unified `FusionStrategy` enum (replacing both `CbrFusionStrategy` and rag-api's `FusionStrategy`), and `CamelCaseExpander` (BM25 token pre-processing, needed by both RAG and the upcoming CBR BM25 leg).

Both `memory-api` and `rag` depend on `fusion-api`. Neither depends on the other. The peer relationship is preserved.

## What the Review Caught

The design review was thorough — 4 rounds, 17 issues, 16 verified, 1 accepted. Three findings shaped the final spec significantly.

First, the CC weight renormalization problem. When SPLADE or BM25 is disabled, `ScoreFusion.convexCombination()` renormalizes weights across all remaining legs. That sounds correct, but it shifts the feature/semantic balance. If `vectorWeight` is 0.3 (meaning 30% semantic, 70% feature) and the SPLADE leg is disabled, the naive renormalization gives dense a larger share of the total — but the total semantic budget also shrinks, so features end up with more than 70%. The fix: renormalize semantic sub-weights among active semantic legs *before* computing per-leg weights. The `vectorWeight` split is sacred — it's the application's contract.

Second, the reconciliation gap. When you enable SPLADE on a deployment with 10,000 existing cases, the current reconciliation service would backfill zero. All points exist in both Qdrant and the delegate store, so the reindex phase considers them "consistent" and skips them. A third reconciliation phase — vector enrichment — scrolls existing points and adds missing named vectors.

Third, `RrfFusion.fuse()` returned raw RRF scores (~0.032 for 2-leg k=60). `ScoreFusion.rrf()` normalizes to [0, 1]. That's an intentional change, but it needed documenting — downstream consumers don't depend on raw score magnitude (CRAG replaces scores entirely), but the behavioral note prevents future confusion.

## What's Done, What's Left

The consolidation is complete. Four commits, net reduction of ~300 lines. `RrfFusion`, `ConvexCombinationFusion`, `CbrFusionStrategy`, and rag-api's `FusionStrategy` are gone. Every caller now uses `ScoreFusion` from `fusion-api`.

What remains is the reason the consolidation happened: SPLADE and BM25 legs for CBR. The spec and plan are written — 6 tasks covering config, collection schema evolution, ingestion, retrieval, reconciliation, and docs. The consolidation cleared the path; the next session builds on it.
