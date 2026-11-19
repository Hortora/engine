---
layout: post
title: "The Grep Firehose vs. Eight Ranked Answers"
date: 2026-06-26
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [vector-search, grep, benchmark, garden, qdrant, ollama]
---

# Hortora Engine — The Grep Firehose vs. Eight Ranked Answers

**Date:** 2026-06-26
**Type:** phase-update

---

## What I was trying to prove: vector search earns its keep over grep

The engine's `gardenSearch` MCP tool was implemented and unit-tested in #21, but it had never run against the real garden corpus — 6,537 entries across dozens of domains. Skills still use `git grep` for knowledge retrieval. The question wasn't whether vector search *could* work. It was whether it actually returns better results than the keyword matching it replaces.

## What we believed going in: the advantage would be relevance ranking

I expected the comparison to show two things: vector search finds entries that grep misses (semantic recall), and it ranks results by relevance where grep returns an unordered list. I also expected grep to win on speed and exact ID lookups. Honesty about both sides was the point — a rigged comparison proves nothing.

## Three bugs between us and a running engine

Before we could benchmark anything, the engine wouldn't start. The first failure was bizarre: the application booted, logged all its features, reported "Listening on port 8180" — then immediately printed `FAIL: model directory argument required` and the CDI container died. No stack trace pointing to our code. No indication of what was wrong.

Claude traced it to `NativeImageGateCommand` in `casehub-inference-quarkus` — a one-off ONNX verification tool annotated `@QuarkusMain`. That single annotation hijacked the entry point of every application on the classpath. Quarkus scans all JARs for `@QuarkusMain` and silently makes whatever it finds the main class. There's no conflict detection, no warning. The fix was one line: remove the annotation.

With the engine running, the first full corpus scan failed: Ollama returned 400 Bad Request. The error message from the LangChain4j REST client said only "Bad Request, status code 400" — no hint about what was wrong. We enabled request logging and found the actual Ollama response: `"the input length exceeds the context length"`. The `testing.md` approach file was ~8,000 tokens; `nomic-embed-text` has a 2,048-token context window. The fix: configure a recursive document splitter (6,000-char chunks with 500-char overlap) so large documents get split before embedding. Small garden entries pass through unchanged.

A third issue lurked: the `CorpusIngestionService`'s checkpoint timer silently overwrote a failed scan's cursor with the watcher's file state. On restart, `changesSince(cursor)` saw no changes because the cursor already reflected all files — even though none had been ingested. The corpus appeared fully indexed while Qdrant had zero points. This one we documented but didn't fix; it's a `casehub-rag` design issue.

## What the benchmark actually showed

Six queries, three styles. The results were clearer than I expected.

For keyword queries ("qdrant java client", "quarkus MCP"), both methods find relevant entries. But grep returns 21 and 77 files respectively — unsorted, including labels, indexes, and summary files. Vector search returns 8 ranked results with the most relevant entry at the top. For "qdrant java client", the first hit was "Qdrant Java client Filter type is Common.Filter not Points.Filter" at 0.61 relevance. That's the exact gotcha a developer needs.

The gap widens dramatically for natural language and symptom queries. "Reactive thread scheduling problems" — grep matches `thread|scheduling|emitOn|Mutiny` across 515 files. An LLM receiving 515 filenames can't do anything useful with that. Vector search returns 8 results: "runSubscriptionOn deadlocks when callers are already on the worker pool" at #1, "emitOn(workerPool) — correct way to shift blocking I/O off Vert.x IO thread" at #2. Both directly actionable.

"Test passes locally fails in CI" is the query that exposes the fundamental problem with keyword search against a large corpus. Grep matches `CI|locally|flaky|test.*fail` in 2,176 files. Two thousand files. The LLM would need to read them all to find the relevant ones. Vector search surfaces "JSDOM location.hash persists across vitest test cases" and "REST Assured Instant equality fails intermittently" — both real local-vs-CI gotchas ranked by semantic similarity.

"CDI bean not found at runtime" produced 1,098 grep matches and 8 vector results. The vector results read like a diagnostic checklist: missing Jandex index, `@IfBuildProfile` resolved at build time, `Instance<T>` for optional injection. Each one maps a symptom to a root cause. Grep returns everything that mentions "CDI" or "bean" — which in a Quarkus-heavy garden is almost everything.

The one query where grep wins: exact ID lookup. `GE-20260609-2abdfd` — grep finds the file and its cross-references (9 hits). Vector search treats the hash as text, produces a meaningless embedding, and returns unrelated results. This is expected and honest.

## Where this leaves the engine

The comparison isn't subtle. For keyword queries, vector search is better at ranking. For natural language queries, it's the difference between 8 useful results and 2,000 filenames. The garden has 6,500+ entries — keyword matching at that scale is a firehose, not a search.

The skill migration is done: `code-review`, `java-dev`, `python-dev`, and `ts-dev` now call `gardenSearch` with `git grep` fallback when the engine isn't running. The synthetic benchmark served its purpose, but the real test is whether developers get better garden context when working on actual issues — that's #27.
