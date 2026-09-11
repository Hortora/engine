---
title: "One Dependency, One Module"
date: 2026-07-08
entry_type: note
subtype: diary
phase: rag-crossencoder
projects: [casehub-neocortex]
tags: [module-architecture, cross-encoder, cdi, rag]
---

The reranking issue asked for the decorator to live in `rag/` alongside `HybridCaseRetriever`. That felt wrong before I could articulate why — a gut reaction to the dependency graph. `rag/` depends on `inference-api` and `inference-splade`. It does not depend on `inference-tasks`. Adding `inference-tasks` would drag ONNX Runtime JNI into every consumer of the RAG pipeline, including Hortora's garden engine which has no use for cross-encoder inference.

The obvious alternative was a separate `rag-reranking/` module. Clean, follows the pattern — every other decorator (`rag-crag`, `rag-expansion`, `rag-tracking`) lives in its own module. But then I looked at what `rag-crag/` already depends on: `inference-tasks`. The same dependency the reranker needs. And both features use the same class — `CrossEncoderReranker` — for the same underlying operation: scoring query-candidate pairs with a BERT forward pass.

Two modules with identical dependencies is a code smell. The module boundary should align with the dependency boundary. One optional dependency, one module.

We renamed `rag-crag/` to `rag-crossencoder/` and put both features there. The corrective RAG classes moved to a `corrective/` sub-package; the new reranking classes went into `reranking/`. Each feature keeps its own `@IfBuildProperty` gate — you can enable CRAG without reranking, reranking without CRAG, or both.

The payoff showed up immediately. When both are enabled, the decorator chain runs CRAG (priority 100) inside the reranker (priority 75). CRAG scores every chunk with the cross-encoder for quality filtering. Without score propagation, the reranker would re-score the survivors — a second BERT forward pass on the same query-candidate pairs. Because both classes live in the same module, we added `evaluateBatchWithScores()` to `CrossEncoderRelevanceEvaluator` and had CRAG attach raw scores to chunk metadata. The reranker checks for pre-computed scores before invoking the model. One inference pass instead of two.

The subtle part: the metadata key that carries scores (`_crossEncoderScore`) must be distinct from the stamp key that guards against bridge double-application (`_reranked`). If the reranker's guard checked for the score key, CRAG's score propagation would trigger it — the reranker would see pre-scored chunks and conclude it had already run. Data propagation keys and execution guard keys serve different purposes and must never be conflated.

The retention side was straightforward by comparison. `purgeOlderThan(Instant cutoff)` on the `RetrievalTracker` SPI, cascade DELETE in `SqliteRetrievalTracker` (children first — SQLite foreign keys lack CASCADE and can't be altered), a `RetentionScheduler` with a daemon thread and exception-safe purge loop. The design review caught the `ScheduledExecutorService` gotcha — an uncaught exception in `scheduleAtFixedRate` silently kills all subsequent executions. A try-catch in the purge method is the fix; the scheduler itself stays alive.
