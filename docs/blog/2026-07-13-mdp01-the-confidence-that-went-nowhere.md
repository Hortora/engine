---
layout: post
title: "The Confidence That Went Nowhere"
date: 2026-07-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, outcome-weighting, retrieval-traceability, decorator]
series: issue-84-cbr-outcome-retrieval-trace
---

Every CBR case in neocortex has carried a `origin` field since #140 landed the Revise SPI. `recordOutcome` updates it via EMA. Retrieval ignores it completely.

That's a feedback loop with no loop. Outcome data accumulates, adjusts confidence, and then nothing happens — `retrieveSimilar` ranks cases purely by feature similarity. A case that failed every time it was applied scores the same as one with a 95% success rate, as long as their features match.

I wanted three things from #84: make confidence actually influence retrieval ranking, make every retrieval decision auditable, and give consumers a way to render traces into human-readable explanations.

## Confidence as a scoring signal

The weighting sits at `@Priority(65)` — between the cross-encoder reranker at 75 and the tracking decorator at 50. After the base store and reranker produce their scores, the weighting decorator modulates each result by confidence: `score * (1 - α + α * confidence)` where α defaults to 0.3. A case with confidence 1.0 is untouched. A case at 0.5 loses about 15% of its score. Null confidence (no outcome data yet) is treated as 1.0 — new cases aren't penalised.

The formula is behind an `OutcomeWeightingFunction` SPI. The default is linear interpolation, but a clinical deployment that needs aggressive down-weighting of failed cases can provide its own bean. The α parameter is config-driven.

## Retrieval traceability

Every `retrieveSimilar` call now produces a `CbrRetrievalTrace` — the query, the results with their scores and per-feature breakdowns, and the case's confidence at the moment of retrieval. The tracker persists these to SQLite (same HikariCP + Flyway pattern as RAG tracking) and fires a `CbrRetrievalRecorded` CDI event for consumers like ledger that need real-time observation.

The design review caught something I'd missed: `ScoredCbrCase` had no `caseId`. The case identifier was available inside every store implementation — InMemory, Qdrant, JPA — but never surfaced in retrieval results. Without it, traces can't reference specific cases. Pre-release, so we added it as a first-class field. That change rippled through every store and every decorator, but it closes a gap that would have blocked more than just tracing.

## The double-recording problem

When `BlockingToReactiveCbrBridge` is active, a reactive consumer's call traverses both decorator chains — the reactive tracking decorator wraps the bridge, which delegates to the fully-decorated blocking chain. Without a guard, the same retrieval gets recorded twice.

RAG tracking solves this by stamping results with metadata. CBR can't — `ScoredCbrCase` is a record with no metadata map. Instead, the reactive decorator checks whether its delegate implements `BridgedCbrStore` (a marker interface in memory-api). If the bridge is present, it becomes a pass-through — the blocking decorator handles all tracking. The marker keeps the dependency on memory-api only, no concrete class coupling from the tracking module to the runtime module.

`ExplanationRenderer` rounds it out — an SPI that transforms a trace into a structural explanation. The default produces field names and scores; clinical or AML deployments provide domain-specific renderers.

The CBR feedback loop has a loop now. Cases that work well rise; cases that don't, fade.
