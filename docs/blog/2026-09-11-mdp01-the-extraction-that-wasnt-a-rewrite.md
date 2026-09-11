---
layout: post
title: "The Extraction That Wasn't a Rewrite"
date: 2026-09-11
entry_type: note
subtype: diary
projects: [casehubio/neocortex]
tags: [rag, spi, extraction, cbr, federation]
series: issue-304-neocortex-garden-platform
---

# The Extraction That Wasn't a Rewrite

The goal was straightforward: pull generic RAG capabilities out of hortora/engine into neocortex, so a second consumer could use them without depending on a garden-specific deployment artifact. Engine had grown to ~3.5K LOC, and most of it — adaptive search, post-retrieval scoring, provenance tracking, federation — wasn't garden-specific at all.

The interesting constraint was *what not to extract*. SPI-first extraction means defining the right abstraction in `rag-api` and leaving the implementation in engine until a second consumer validates it. ProvenanceTracker is a good example: engine's `ProvenanceStore` has columns named `issueRepo` and `issueNumber` — Hortora-specific schema baked into a supposedly generic capability. The platform SPI uses `actionId` and `actionType` instead. Engine maps its domain onto those generic parameters.

Federation was the design decision I spent the most time on. The original plan had it as a `CaseRetriever` decorator — which felt natural until I looked at how `ChainWalker` actually works. It operates at the REST layer via `RemoteGardenClient.search()`, not at the retrieval pipeline layer. Remote results arrive already scored by their own pipeline. Making federation a decorator would disguise a routing operation as a pipeline transformation, and remote results would get double-processed through local CRAG and reranking. Federation is routing, not decoration — a `FederationStrategy` SPI with its own query/result types.

The CBR rename was mechanical but clarifying. `PlanCbrCase` to `ResolvedCase`, `TextualCbrCase` to `ResolutionGuide`, `PlanTrace` to `ResolutionStep`. IntelliJ handled 305 references across 47 files. The one thing it didn't catch: a string literal inside `Objects.requireNonNull()` that still said `"planTrace required"` after the field became `resolutionStep`. Rename tools update identifiers, not string literals — obvious in hindsight, easy to miss in practice.

The two new modules — `rag-scoring` and `rag-query-augmentation` — follow the existing pattern. `rag-scoring` is pure Java with zero external dependencies: `TemporalDecayScorer` and `VersionScorer` implement `PostRetrievalScorer` with configurable metadata key names, so the generic scorer doesn't embed domain-specific field names. `AdaptiveSearchWrapper` composes a `CaseRetriever` with a scorer chain and `AdaptiveFilter` into a single search operation. `rag-query-augmentation` uses `AgentProvider` for LLM-backed query generation at ingest time — distinct from `QueryExpander` which operates at query time.

What's left is the engine side: swapping in the platform implementations, implementing the new SPIs, and verifying the MCP tools still work. Three tasks, all deferred until this branch lands and engine can depend on the new modules.
