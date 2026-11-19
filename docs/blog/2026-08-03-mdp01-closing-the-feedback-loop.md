---
layout: post
title: "Hortora Engine — Closing the Feedback Loop"
date: 2026-08-03
type: phase-update
entry_type: note
subtype: diary
projects: [engine]
tags: [cbr, retrieval-tracking, curation, h2, jpa]
---

# Hortora Engine — Closing the Feedback Loop

**Date:** 2026-08-03
**Type:** phase-update

---

## The three-signal curation problem

The garden has 2,400+ entries. Some are indispensable — a developer hits a gotcha, searches, finds the entry, and saves an hour. Others sit untouched for months, taking up retrieval space and diluting results. The question is which is which.

Until now we had one signal: retrieval frequency. `SqliteRetrievalTracker` records every `CaseRetriever.retrieve()` call, and `gardenUnretrieved` surfaces entries with zero or stale retrievals. That tells you what the retrieval engine *found* — but nothing about whether it *helped*.

The gap is obvious when you think about it. An entry can be retrieved frequently and still be useless — outdated advice, misleading symptoms, content that matches the query but not the problem. Retrieval frequency measures relevance to the vector space, not relevance to the developer. We needed two more signals: what was *consulted* (provenance, already built via `gardenRecordProvenance`) and what actually *helped* (outcome tracking — this session's work).

## What changed: RetrievalAnalyzer replaces inline analysis

The first piece was a refactor. `gardenUnretrieved` had ~40 lines of inline set-diffing: fetch all documents, fetch all retrieved IDs, compute the difference, then do the same dance again with a time window for stale detection. Classic "copy the algorithm into the caller" pattern.

`RetrievalAnalyzer.qualitySignals()` in `casehub-neocortex-rag-api` already does this — and more. One call returns three signal types: `NEVER_RETRIEVED`, `STALE`, and `HIGH_RETRIEVAL_LOW_QUALITY`. The last one is the interesting addition. It fires when an entry has been retrieved frequently but feedback data (once it exists) says it's consistently rated NOT_RELEVANT. The refactored `gardenUnretrieved` now surfaces all three, though the quality signal is dormant until feedback data flows in. That's where CBR comes in.

## CBR outcome tracking: the platform already had the infrastructure

I'd been thinking about outcome tracking as a custom feature — extend `ProvenanceStore` with an outcome field, build confidence adjustment, manage the lifecycle. Then I looked at what the platform already provides.

`casehub-neocortex-memory-api` has a complete CBR model: `CbrCase` with problem/solution/outcome/confidence, `CbrOutcome` with a learning-rate-based confidence adjustment (`adjustConfidence()` drifts the value toward the true success rate over time), `CbrCaseMemoryStore` with store/recordOutcome/scan/supersede. `TextualCbrCase` maps directly to garden entries: the problem is the work context ("designing retrieval tracking for hortora engine"), the solution is the GE-ID.

The mapping is clean enough that I went with the platform's CBR infrastructure rather than building a parallel system. `GardenOutcomeService` stores one `TextualCbrCase` per garden entry (first outcome creates it, subsequent outcomes call `recordOutcome()` to evolve confidence). `gardenRecordOutcome` as an MCP tool, plus a REST endpoint at `/api/garden/outcomes` for the trellis UI when that arrives.

## The gotchas of integrating platform JPA into a lightweight engine

The engine runs SQLite for retrieval tracking and Qdrant for embeddings. No JPA, no Hibernate, no relational database. Adding `casehub-neocortex-memory-cbr-jpa` changed that — and surfaced several sharp edges.

**The Flyway/H2 mismatch.** The JPA module ships Flyway migrations targeting PostgreSQL. One uses `GEN_RANDOM_UUID()` — a PostgreSQL-specific function that H2 doesn't recognise. The fix was to skip Flyway entirely and use Hibernate's `database.generation=update` instead. Hibernate generates H2-compatible DDL from the `@Entity` annotations. For a pre-release engine this is fine; production PostgreSQL deployments would use the original Flyway migrations.

**The default EntityManager trap.** `JpaCbrCaseMemoryStore` injects `@Inject EntityManager em` — the default persistence unit. I initially configured a named datasource (`quarkus.datasource.cbr.*`) which creates a named persistence unit the default injection can't resolve. The error message (`UnsatisfiedResolutionException for EntityManager`) doesn't point at the datasource naming mismatch. Switching to the unnamed default datasource fixed it instantly.

**The store() upsert assumption.** `CbrCaseMemoryStore.store()` looks like it should upsert — you pass a `caseId`, you'd expect it to update if exists. It doesn't. Every call creates a new row with `UUID.randomUUID()`. Recording multiple outcomes for the same garden entry without checking existence first creates duplicates that `recordOutcome()` silently ignores (it takes the first match). The fix is an existence check before `store()`, which means injecting `EntityManager` alongside `CbrCaseMemoryStore` — breaking the SPI abstraction to work around its API gap.

**The scan() confidence gap.** `CbrCaseSummary` from `scan()` has `trustScore` and `storedAt` but not `confidence` or `outcomeCount`. Building an outcome report — entries sorted by declining confidence — requires querying `CbrCaseEntity` directly, again bypassing the SPI. These are real gaps in the platform API that I've captured as garden entries and will file as neocortex issues.

## Snapshot pruning — the smallest useful feature

Qdrant snapshots for benchmarking accumulate at hundreds of megabytes each. `--prune` on `create_snapshot.py` with `--keep N` and `--max-age DAYS` handles it. Both criteria apply: always keep at least N most recent, then age-prune the rest. `--dry-run` shows what would go. Orphan directories without `manifest.json` get a warning to stderr rather than silent deletion.

## Where this leaves us

The three-signal curation system is now structurally complete: retrieval tracking (what was found), provenance (what was consulted), and outcome tracking (whether it helped). The signals feed into different parts of the system — retrieval analysis for `gardenUnretrieved`, provenance for traceability, CBR for confidence evolution — but together they give harvest sessions genuine data to work from instead of guesswork.

The `HIGH_RETRIEVAL_LOW_QUALITY` signal in `gardenUnretrieved` is wired but dormant. Once work-end skills start calling `gardenRecordOutcome`, it will light up automatically — entries that are frequently retrieved but consistently unhelpful will surface without any additional code. That's the payoff of building on existing platform infrastructure rather than bolting on a custom solution.

The CBR SPI gaps (store upsert, scan confidence, named datasource support) are real friction. I've filed them as garden entries. Whether they warrant neocortex issues depends on whether other consumers hit the same edges — the engine may be the first production CBR consumer outside the casehub platform itself.
