---
layout: post
title: "The Guard That Wasn't Obvious"
date: 2026-06-20
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [cdi, decorator, reactive, crag, rag, idempotency]
series: issue-38-cursorstore-delete-reactive-crag
---

The spec for reactive CRAG looked straightforward: mirror the blocking `CorrectiveCaseRetriever` as a `@Decorator` on `ReactiveCaseRetriever`, swap `fire()` for `fireAsync()`, wrap the evaluator call in `runSubscriptionOn()`. Two issues on one branch — #38 (add `CursorStore.delete()`, a one-method SPI extension) and #41 (the reactive decorator). The estimate said S and XS. Should be quick.

The spec review caught a composition bug I'd missed entirely. In default mode — the common mode — reactive consumers use `BlockingToReactiveCaseRetriever`, which wraps the blocking `CaseRetriever`. That blocking retriever is already decorated by `CorrectiveCaseRetriever`. Add a reactive `@Decorator` on `ReactiveCaseRetriever` and the chain becomes: reactive CRAG → bridge → blocking CRAG → actual retriever. CRAG fires twice on every retrieval. Quality counts double. Expansion may re-trigger on already-filtered results. No error, no warning.

The fix is to use `RelevanceGrade.UNGRADED` as an idempotency signal. CRAG's job is to grade ungraded chunks. If chunks arrive already graded — by the blocking decorator, through the bridge — there's nothing to correct. Return them as-is, fire no event. The grade enum was already on `RetrievedChunk` from the original CRAG implementation; it just needed a second purpose. One field, two roles: consumer-facing quality signal and decorator idempotency marker.

This led to extracting `CragEvaluationLogic` — a package-private class of pure static functions that both decorators call. The grading loop, filtering, dedup, sort-and-truncate, quality event construction — all extracted once, tested directly with 12 unit tests, consumed by both the blocking and reactive paths. The dedup key also got fixed along the way: the original used `content.hashCode()` (32-bit, collision-prone); the shared helper uses the full content string. Small sets, negligible cost, zero collision risk.

`CursorStore.delete()` was the palate cleanser. The issue existed because Hortora's hybrid search migration needed to reset ingestion cursors, and the only option was `save(name, "")` — coupling to `FileCursorStore`'s empty-string-means-empty behaviour. The fix is `void delete(String corpusName)` on the SPI with no default body. Two implementations, both in this repo, both get explicit overrides. The spec review pushed back on the default body I'd proposed — correctly. The problem statement said `save("")` is a hack; the default body WAS that hack.

The reactive decorator itself is a nested `Uni` chain with three async boundaries: the delegate call, the first evaluator offload to a worker thread, and conditionally a second delegate + evaluator for expansion. Not a simple mirror of the blocking code, despite using the same shared logic. The Uni chain is orchestration; the pure functions are computation. Clean separation, but the nesting is real — four levels of `transformToUni` with conditional branching.

Three review rounds, twelve findings total, zero pushback from me. Every finding was correct on the code. The critical one — double CRAG — would have shipped as a silent data corruption bug in the most common deployment mode. The CDI decorator composition gotcha is now in the garden as GE-20260620-9d043b.