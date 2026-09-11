---
layout: post
title: "Features are not filters"
date: 2026-07-05
type: phase-update
entry_type: note
subtype: diary
projects: [CaseHub Neural-Text]
tags: [cbr, rag, onnx, qdrant]
---

The batch that closed six issues taught me something I should have seen earlier: neocortex's CBR retrieval was architecturally wrong.

Not broken — it worked. Cases went in, cases came out, payload filters matched. But the design treated feature fields as database WHERE clauses. `opponent_race=ZERG` was a hard gate — cases that didn't match vanished entirely. A case with `opponent_race=PROTOSS` that was otherwise a near-perfect match for your situation? Invisible.

That's not how Case-Based Reasoning works. In CBR, every feature comparison produces a graduated similarity value. A categorical mismatch scores 0.0, not "excluded from results." A numeric value 20% away from the query scores 0.8, not "filtered out." The global similarity is a weighted sum of these local scores — `Σ(wᵢ · simᵢ) / Σ(wᵢ)` — and the caller controls the weights to express what matters more for their domain.

The fix was `CbrSimilarityScorer` — a pure-Java utility in `memory-api` that computes per-field local similarity (categorical exact match, numeric linear decay over the schema's declared range, text exact match for now) and combines them with caller-supplied weights. Both the Qdrant and in-memory backends now use identity fields (tenant, domain, caseType) as hard Qdrant filters for candidate selection, then score features client-side. The dense vector cosine score, when present, gets blended in via a configurable `vectorWeight`.

This is the right layering. Identity is partitioning. Features are similarity. Mixing them was the mistake.

The other pieces in the batch were more mechanical but had their own interesting corners. `OnnxInferenceModel` had hardcoded HuggingFace input names — `input_ids`, `attention_mask` — which meant any ONNX model using the original BERT convention (`input_mask`, `segment_ids`) failed at load time. A static alias table with a `ModelConfig` override fallback fixed it. `SparseEmbedder` couldn't handle rank-3 SPLADE outputs — the max-pool reduction across the sequence dimension belonged in SparseEmbedder, not in the generic inference layer, because SparseEmbedder owns the SPLADE semantics.

The fusion strategy work surfaced a Qdrant limitation I hadn't expected. Convex Combination — weighted score-based fusion across retrieval legs — can't be expressed server-side. Qdrant's `Formula` query type can reference named vectors and payload values, but not individual prefetch leg scores. So CC requires separate `SearchPoints` queries per leg and client-side score combination with min-max normalization. RRF and DBSF stay server-side in one round trip; CC trades that for configurable per-leg weights at the cost of three.

The `Double.MIN_VALUE` bug was the session's best reminder that review catches what confidence misses. `ConvexCombinationFusion.minMax()` initialized `max` with `Double.MIN_VALUE` — the smallest *positive* double, not the most negative. When a retrieval leg returned scores at 0.0, the normalization silently corrupted. It passed every test because Qdrant returns positive scores for real searches. Claude caught it in the final whole-branch review.
