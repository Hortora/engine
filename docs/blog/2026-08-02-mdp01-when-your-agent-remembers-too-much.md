---
layout: post
title: "When Your Agent Remembers Too Much"
date: 2026-08-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, retention, memory, rag, fusion]
---

## When Your Agent Remembers Too Much

An agent that learns from experience has a problem most software doesn't: its memory grows without bound. Every case it stores, every outcome it records, every interaction it files away — all of it accumulates. And unlike a database table that just gets bigger, a case base that grows uncurated gets *worse*. Irrelevant cases dilute retrieval. Low-quality precedents contaminate decisions. The agent's experience becomes noise.

Three things control this: what stays, what matters, and what gets found first.

## Trust as a Retention Signal

CBR stores cases with a trust score — a snapshot of how trustworthy the producing agent was at the time the case was created. Until now, retention policies could purge cases by age (stale) or by count (overflow), but trust was invisible to the retention system. A case from an agent that was later found unreliable sat at equal weight with one from a consistently accurate source.

The fix adds `minTrustScore` to `CbrRetentionPolicy`. Cases with a stored trust score below the threshold are purge-eligible. The criteria combine with OR semantics — a case that's too old, or from a low-trust source, or exceeding the count limit is independently removable.

There's also a trajectory layer. A scheduled service scans the case base, looks up each producing agent's *current* trust via `AgentTrustProvider`, and removes cases from agents whose trust has dropped below a configurable threshold since the case was stored. This catches a different failure mode: the agent was trusted when it produced the case, but its subsequent decisions revealed it shouldn't have been. The stored trust snapshot can't detect this — it's frozen at creation time. The trajectory check catches it.

The separation matters. Stored-trust purging is a data filter — every backend implements it in its own query language. Trajectory purging requires a runtime dependency (the trust provider) and runs as a scheduled background job, not as part of the `purge()` SPI. Mixing these would force trust-provider awareness onto every store implementation.

## Importance on Memories

The general memory store (`CaseMemoryStore`) had a different gap. It's append-only — every memory is equally important until explicitly erased. A compliance-critical diagnosis and a routine status update occupy the same space with the same retention weight.

Adding `confidence` to `MemoryInput` gives the caller a way to signal significance at creation time. A landmark event gets 1.0. A routine interaction gets 0.3. An ephemeral greeting gets 0.1. The field is nullable — existing memories without importance are treated as fully important (1.0), so they're never purge-eligible by importance alone.

The retention semantics here differ from CBR deliberately. CBR uses OR: any criterion independently triggers purging. Memory uses AND: a memory must be *both* old and unimportant to be purged. Important memories survive regardless of age — a key diagnosis from two years ago should persist. Recent unimportant memories also survive — they might still be contextually relevant. Only old *and* unimportant memories are garbage-collected.

This is the conservative choice. In CBR, the criteria represent independent quality signals — staleness, overflow, and untrustworthiness are each sufficient reasons to remove a case. In memory, importance *modulates* what "too old" means. The question isn't "does this memory fail a quality check?" — it's "is this memory still worth keeping?" That's a conjunctive judgment.

## Dynamic Fusion Weights

The RAG pipeline runs three retrieval legs in parallel: dense embedding (semantic similarity), sparse embedding (term-level matching), and BM25 (keyword search). Each leg's contribution to the final ranking is controlled by a configured weight. Until now, those weights were static — the same ratio for every query.

When a query contains specific identifiers — a class name, a config key, an error message — BM25 becomes disproportionately valuable. It matches exact tokens that dense embeddings rank lower because they compress meaning into a vector where "CbrRetentionPolicy" and "retention policy" look similar. But the user searching for `CbrRetentionPolicy` wants the exact class, not documents about retention policies in general.

`RetrievalQuery` now carries per-query weight multipliers. The caller specifies `query.withBm25Boost(2.0)` and the BM25 leg's configured weight is doubled for that query. The multiplier is general — any leg can be adjusted, not just BM25. When per-query multipliers make the effective weights non-equal, the retriever automatically falls back from server-side fusion (which doesn't support per-leg weights) to client-side fusion (which does).

The design avoids automatic detection. An earlier proposal would have inferred keyword presence from query structure, but the heuristics were fragile — HyDE expansion changes the query text without implying keywords are present. Explicit multipliers let the caller express intent without the retriever guessing.

## What This Opens Up

These three features share a theme: the system's memory should get better over time, not just bigger. Trust-based retention means low-quality precedents are actively removed. Importance-based retention means routine memories don't crowd out landmark ones. Dynamic fusion means the retrieval system adapts to what the query is actually looking for.

The next step is connecting these signals to the engine's routing layer — wiring `AgentTrustProvider` so trust data flows from the ledger into CBR automatically, and using the strategy classifier's output as a retrieval feature so the case base can answer "what did I do last time I faced this situation?"
