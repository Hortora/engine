---
layout: post
title: "Cross-Encoder Reranking and the Pool-Size Sweet Spot"
date: 2026-07-09
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [retrieval, cross-encoder, benchmark, adaptive-filtering]
---

*Previous: [The rerankTopN Bottleneck](2026-07-07-mdp01-reranktopn-bottleneck.md).*

The cross-encoder stage I designed in the last entry landed. `RerankingCaseRetriever` sits as a CDI decorator above `HybridCaseRetriever` — it overfetches 50 candidates from the four-signal pipeline (dense, sparse, BM25, ColBERT), scores each against the original query with a BERT-based cross-encoder via ONNX, and returns the top 16 by cross-encoder score.

The pool-size question turned out to have a clean answer. I benchmarked pool-30, pool-50, and pool-100. Pool-50 was the sweet spot: +8 highly-relevant entries (score=2) over the pool-30 baseline, at 1.15s median latency. Pool-100 gained nothing but 2.5x the latency. The cross-encoder can't improve what the prefetch legs don't surface, and 50 candidates is deep enough to cover the relevant entries that ColBERT's server-side rescore occasionally ranks at 11-30.

The more interesting addition was adaptive score filtering. With 50 candidates, the bottom of the pool contains noise — entries that are topically adjacent but not useful. Instead of returning a fixed 16, `adaptiveFilter()` in `SearchResource` uses the cross-encoder scores to find natural cluster boundaries. A configurable score floor (default 0.0) removes the worst noise, and gap detection (threshold 2.0) finds the drop-off between relevant and marginal entries. The result count is fully adaptive: a strong query that retrieves 12 clean matches returns 12. A broad query with 16+ good matches returns all of them.

The `minResults` guard (default 3) prevents the filter from being too aggressive on weak queries where even the best results have low scores. This matters for the protocol-compliance scenarios in the benchmark — highly specific queries where few garden entries exist.

HyDE was also implemented in this session — `SessionQueryExpander` generates a hypothetical garden entry via Claude and uses it as an alternate dense embedding. The benchmark showed it was net-zero at limit=16: the hypothetical helped dense recall but the improvement was cancelled by the loss of original query vocabulary in the sparse and BM25 legs. All three legs were using `searchText()`, which returns the hypothetical when expansion is active. I disabled it in config and filed #50 to revisit after neocortex #117 (per-leg embedding separation) lands.

The pipeline now runs five signals — dense, sparse, BM25, ColBERT, and cross-encoder — with adaptive result filtering. The benchmark is at 87% precision across 14 scenarios. Whether that number moves depends on what happens with HyDE once the per-leg separation is in place.
