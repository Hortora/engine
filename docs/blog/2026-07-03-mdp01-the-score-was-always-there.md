---
layout: post
title: "The score was always there"
date: 2026-07-03
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, dense-search, scored-results, qdrant, design-review]
series: issue-70-cbr-dense-vector-cleanup
---

The CBR store has been embedding `problem()` text at write time since it was built. Every case goes in with a dense vector alongside its payload filters. But retrieval never used it — `scrollAsync` with payload filters, score 1.0 for every match, no ranking. The embedding was stored and ignored.

Fixing that was the obvious part. `CbrQuery` needed a `problem` field so callers could say what text to embed on the query side. When that field is present and an `EmbeddingModel` is available, the store switches from `scrollAsync` to `searchAsync` — payload filters still reduce the candidate set, but the dense vector ranks within it. `minSimilarity` maps to Qdrant's `score_threshold`, which always gets set even at 0.0. There's an undocumented distinction there: omitting `score_threshold` returns everything including negative cosine similarities, while setting it to 0.0 actively excludes them. The spec originally said "omit at zero" — a design review caught the contradiction.

The design review caught something more fundamental. The original design returned `List<C>` from `retrieveSimilar()` — bare cases, no scores. The reviewer's argument was simple: CBR retrieval exists to answer "how similar?" and the score IS the answer. Without it, a 0.95 match is indistinguishable from a 0.51 match. Callers can't do confidence weighting, can't set business-logic thresholds beyond the binary `minSimilarity` cutoff, can't observe retrieval quality.

That was right, and it changed the shape of the API. `retrieveSimilar()` now returns `List<ScoredCbrCase<C>>` — a wrapper carrying the case and its similarity score. Dense search returns actual cosine similarity from Qdrant. Filter-only mode returns 1.0 for all matches. The score semantics are honest about what the backend can provide, and callers can distinguish the two modes by inspecting scores.

The other issue worth noting: collection dimension validation. The store creates Qdrant collections with a 1-dimensional placeholder vector when no embedding model is available. If someone later adds a model (say, 384 dimensions), the existing collection's vectors don't match. Without validation, `store()` silently fails when Qdrant rejects the dimension mismatch. Now `ensureCollection()` checks and recreates the collection on mismatch — the delegate `CaseMemoryStore` keeps the durable records, so Qdrant is just a rebuildable index.

Issue #59 — the `reconstructCase` type resolution ordering — turned out to be already resolved. The code had a discriminator-based switch on `_cbr_type` covering all three subtypes. The if-chain the issue described was replaced during the Tier 2 implementation. A strategy pattern would have added indirection for three cases in the same repo. Closed it.
