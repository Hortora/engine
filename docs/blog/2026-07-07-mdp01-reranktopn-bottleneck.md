---
layout: post
title: "The rerankTopN Bottleneck"
date: 2026-07-07
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [retrieval, colbert, benchmark]
---

ColBERT MAX_SIM was rescoring only 10 candidates out of the RRF fusion pool. I knew from the #36 benchmark that relevant entries existed at rank 11-20 — the three prefetch legs (dense, sparse, BM25) each fetch top-40, giving the fusion stage ~60-80 unique candidates. But the `rerankTopN` default of 10 meant ColBERT only saw the top 10 fusion results. Everything below that line was invisible.

The fix was one line in `application.properties`: `casehub.rag.retrieval.rerank-top-n=30`.

The benchmark came back at +74% more relevant entries (153→266 scored entries across 28 scenarios). 27 of 28 scenarios improved. Some jumps were dramatic — `spec1-d2-thread-safety` went from 2-3 relevant entries to 12-13, and `issue-5-ai-llm-inference/NL` from 1 to 11. The single regression was `issue-3-persistence-migrations/KW`, losing one entry — well within HyDE non-determinism noise.

The interesting part was the design work that happened alongside this. We brainstormed adding a client-side cross-encoder reranking decorator on top of the server-side ColBERT rescore — a second stage that uses full cross-attention instead of MAX_SIM's independent per-token matching. The cross-encoder model is already on disk (`~/.hortora/models/reranker/`, 87MB BERT-based), and neocortex has `CrossEncoderReranker` ready to go.

The design question that surfaced was how to prevent compound overfetch. If a reranking decorator overfetches 3x and the engine's `searchAdaptive` also overfetches 2x, the combined 6x would exhaust the ~60-80 candidate pool from the prefetch legs. The answer: use `max(limit, rerankPoolSize)` instead of `limit × multiplier`. Small requests still get a meaningful candidate pool (always ≥30), large requests don't compound (the `max()` just passes through), and the two layers compose without needing to know about each other.

That cross-encoder stage is filed as neocortex#121. When it lands, I'll benchmark A+B against the A-only baseline (`rerank-topn-30.json`) to see how much the second reranking pass adds over just widening the server-side pool. My gut says the +74% from step A captured most of the value — but the cross-encoder adds a genuinely different signal, and it'll be worth knowing the exact delta.
