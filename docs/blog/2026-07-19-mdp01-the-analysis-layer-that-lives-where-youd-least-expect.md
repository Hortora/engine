---
layout: post
title: "The analysis layer that lives where you'd least expect"
date: 2026-07-19
type: phase-update
entry_type: note
subtype: diary
projects: [neocortex]
tags: [rag, retrieval-tracking, analysis]
---

The retrieval tracking SPI landed a few weeks ago — raw event recording: which documents came back for which query, optional graduated feedback on whether they helped. Useful data, but useless without interpretation. A consumer staring at thousands of retrieval records needs aggregation and quality signals, not a raw event log.

The interesting design question was where the analysis layer belongs. Engine already has `gardenUnretrieved` — an MCP tool doing inline set-diffing to find zero-retrieval garden entries. That's ~60 lines of generic computation with garden-specific formatting bolted on. The generic part has no business in a domain-specific engine.

We traced the dependency graph back to first principles. neocortex is Foundation tier. It owns retrieval, scoring, and memory. The analysis computation — frequency counting, quality signal classification — is domain-agnostic. "This document was retrieved 87 times with 73% negative feedback" is a fact. "Remove this garden entry" is a domain decision. The analysis service returns the fact; the consumer decides what it means.

The module placement was the second surprise. Both inputs — `RetrievalTracker` and `EmbeddingIngestor` — already live in `rag-api`. `memory-api` already contains pure computation classes alongside its SPIs: `CbrSimilarityScorer`, `DtwSimilarity`, `TrendAnalyzer`. So the analysis service goes in `rag-api` too. No new module, no new dependencies.

Three static methods on `RetrievalAnalyzer`: `documentStats` computes per-document retrieval count, timestamps, average score, and feedback distribution. `unretrievedDocuments` diffs the tracker's retrieved set against the ingestor's document inventory. `qualitySignals` classifies documents into `NEVER_RETRIEVED`, `HIGH_RETRIEVAL_LOW_QUALITY`, or `STALE` using caller-controlled thresholds.

The adversarial design review caught something I would have missed. `findFeedback(corpus, since, until)` filters on the *feedback submission* timestamp, not the retrieval's timestamp. The method signatures are symmetric — both take `(CorpusRef, Instant, Instant)` — but the semantics diverge silently. Call `findFeedback` with the same `until` as `findRecords` and you drop every piece of late-submitted feedback. The fix: widen the upper bound to `Instant.MAX` and post-filter by `retrievalId`. Five rounds, 16 issues, all resolved — but that one finding alone justified the review.

The `QualityThresholds` record puts the heuristic knobs in the caller's hands: minimum retrievals before checking quality, minimum feedback count to avoid sparse-sample ratios, the low-quality threshold itself, and a stale window. Sensible defaults, but no domain assumptions hardcoded.

Engine's `gardenUnretrieved` can now replace its inline analysis with two calls to `RetrievalAnalyzer`. The garden-specific parts — GE date parsing, domain grouping, human-readable output — stay in engine where they belong. That refactoring is tracked as a follow-up, not part of this work.
