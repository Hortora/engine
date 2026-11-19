---
layout: post
title: "BM25 Closes the Gap"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [engine]
tags: [retrieval, benchmark, bm25, research]
series: issue-29-complementary-retrieval
---

*Part of a series on [#29 — Complementary retrieval capabilities](https://github.com/Hortora/engine/issues/29). Previous: [The Keyword Fix Lands](2026-06-30-mdp01-keyword-fix-lands.md).*

## The number that matters

45% → 94%. That's overall precision across all 14 benchmark scenarios — the same methodology as the #27 real-world benchmark, re-run with three-leg retrieval (dense + SPLADE + BM25) enabled.

The keyword-gap scenarios — where grep won over gardenSearch in #27 — went from 43% to 89%. The scenarios where gardenSearch already won went from 47% to 98% with zero regressions. BM25 didn't just fix the weak spots; it lifted everything.

## BM25 was the dominant contributor

Comparing three-leg against dense+SPLADE (no BM25) isolates what BM25 adds. On the keyword-gap scenarios, BM25 contributed +43pp on `issue-2-cdi-wiring/KW`, +50pp on `spec1-d1-cdi-priority-tiers/KW`, +50pp on `spec1-d4-protocol-compliance/NL`. SPLADE alone didn't close these gaps — it couldn't, because its BERT tokenizer shreds `DefaultBean` into meaningless subwords before the model even runs.

BM25 works because it does what grep does — literal term matching — but with scoring. `CamelCaseExpander` handles the tokenisation so `DefaultBean` becomes `Default Bean DefaultBean`, and Qdrant's native BM25 Document vectors handle the rest server-side.

The cost is latency: 28ms → 256ms. Nine times slower, but still sub-second. For an AI assistant retrieving context, that's acceptable.

## What the research says we got right

I spent time surveying the 2025-2026 retrieval literature to see where we stand. The short answer: our three-leg architecture is the consensus production pattern. A benchmark on financial QA (April 2026) confirmed that hybrid dense + BM25 + neural reranking outperforms all single-stage methods. BM25 outperforms dense retrieval on domain-specific terminology — exactly what we found with Java identifiers.

Three things I hadn't considered:

**ColBERT is a reranker, not a retriever.** Research consistently recommends ColBERT for reranking top-k candidates, not as a first-stage retrieval leg. Qdrant supports it natively since v1.10 via MAX_SIM multivectors. This changes the BGE-M3 story — its ColBERT output would replace our cross-encoder reranker, not add a fourth retrieval leg.

**HyDE hurts more than it helps.** Hypothetical Document Embeddings — generating synthetic documents and embedding them as queries — consistently underperforms vanilla dense retrieval. The generated pseudo-documents introduce noise. BM25 already solved what HyDE was trying to address.

**Nomic released a code embedding model.** `nomic-embed-code` is 7B params, trained on code and docstrings. It would handle `ConcurrentHashMap` and `@DefaultBean` natively. But at 50x the size of nomic-embed-text, it's only worth evaluating if BGE-M3's dense output doesn't already handle Java identifiers.

## Where we go from here

Track 1 (maximise what we had) and Track 2 (complementary algorithms) are done. Track 3 is BGE-M3 adoption: a single model that produces dense + learned-sparse + ColBERT from one forward pass, replacing our three-model stack. Qdrant BM25 stays as a complementary lexical leg — BGE-M3 sparse is learned, not lexical, so they catch different things.

But first: 87 new entries in the three-leg results are unscored. The 94% number is based on overlap with the #27 baseline. Until those entries are scored, we don't know the true precision — and we don't know whether there's a remaining gap worth chasing with BGE-M3 or whether we should invest elsewhere.
