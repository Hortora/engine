---
layout: post
title: "Making the engine a thin layer"
date: 2026-09-11
entry_type: note
subtype: diary
projects: [Hortora/engine]
tags: [neocortex, migration, spi, architecture]
series: issue-90-neocortex-thin-layer
---

# Making the engine a thin layer

The engine started as the place where everything got built first — adaptive filtering, provenance tracking, collection migration checks, post-retrieval scoring. Each capability was hand-rolled because the upstream neocortex modules didn't have the abstraction yet. That was fine when the engine was the only consumer. With a second garden (casehub issue resolution) on the horizon, the abstractions now exist upstream and the engine's copies are dead weight.

We audited every source file against what neocortex provides today. The picture was clear: ~70% of the provenance package duplicated the upstream `ProvenanceTracker` SPI, `CollectionMigration` was making raw gRPC calls that `CollectionCompatibility.check()` now wraps as a sealed `MigrationAction` switch, and the adaptive filter reimplemented the same floor-plus-gap logic that `AdaptiveFilter.filter()` handles generically.

The HyDE infrastructure was the easiest call — four files of dead code for a query expansion approach that benchmarked at -2.5pp across every variant we tried. It had been sitting disabled behind a config flag. Gone.

`CollectionMigration` shrank from 186 to 130 lines. The raw Qdrant gRPC imports — `CollectionInfo`, `CollectionParams`, `VectorParams`, `VectorsConfig` — replaced by a single `CollectionCompatibility.check()` call and a pattern match on the sealed result. The deployment policy (what to do when something's wrong) stays in the engine. The detection logic (what IS wrong) is now upstream.

The provenance migration exposed a design tension. The engine's `ProvenanceStore` has domain-specific parameters — `issueRepo` as a string, `issueNumber` as an int, `specName` for spec tracking. The upstream SPI uses generic parameters: `retrievalContext`, `actionId`, `actionType`. Making `ProvenanceStore` implement `ProvenanceTracker` meant keeping both the domain-specific convenience methods and the SPI methods, mapping between them. The shadow types — engine's own `ProvenanceRecord`, `ProvenanceStats`, `EntryRefCount` — all deleted in favour of the upstream equivalents.

The adaptive filter migration hit a wall: `AdaptiveFilter` constrained `gapThreshold` to [0,1], but the engine used 2.0 for cross-encoder score gaps where absolute differences routinely exceed 1.0. We filed neocortex#319 requesting generic scored-item support and CE-aware gap mode. It landed the same session — `AdaptiveFilterOptions<T>` with a `ToDoubleFunction` score extractor and a CE boundary predicate. The engine's ~90 lines of hand-rolled filtering collapsed into a single `AdaptiveFilter.filter()` call.

`GardenOutcomeService` was the last holdout — querying `CbrCaseEntity` directly via JPA `EntityManager` instead of going through the `CbrCaseMemoryStore` SPI. Now uses `scan()` for existence checks and `eraseByScope()` for cleanup. The report trades `confidence` for `trustScore` (the SPI's available signal), which is a minor output change but the right architectural trade.

Net result: -649 lines, 7 deleted files (4 HyDE source + 3 shadow types), 3 upstream SPIs adopted (`ProvenanceTracker`, `PostRetrievalScorer`, `AdaptiveFilter`). The engine still has its own `FeedbackContext` type and `ChainWalker` hasn't adopted the `FederationStrategy` SPI yet — those are next, but smaller than what landed today.
