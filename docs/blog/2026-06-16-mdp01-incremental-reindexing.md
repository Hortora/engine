---
layout: post
title: "Tearing out the abstraction layer and finding the platform underneath"
date: 2026-06-16
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [qdrant, incremental-indexing, casehub-corpus, architecture]
---

## Hortora Engine — Tearing out the abstraction layer and finding the platform underneath

The engine had a startup-only indexer. Every restart walked the garden directory, embedded every entry, and bulk-pushed to Qdrant. No change detection, no file watching, no cursor. Worse — each restart added duplicate copies of every entry because `addAll()` generates random UUIDs. The fix was issue #7: incremental re-indexing with filesystem watching.

I expected to build the change detection infrastructure from scratch. A `ChangeSource` SPI, a `DirectoryWatcher` wrapper, cursor persistence — the usual. Then I looked at what `casehub-neural-text` had already built.

The corpus module (`casehub-corpus-api` + `casehub-corpus`) already had exactly the abstractions I needed: `ChangeSource` with `fullScan()` and `changesSince(cursor)`, mtime-based cursors, and `FlatChangeSource` — which implements `WatchableChangeSource` and wraps `io.methvin:directory-watcher` with 500ms debounce and overflow recovery. The hard part was done.

The design question was which layer to consume. My first instinct was `casehub-rag` — it has `CorpusIngestionService`, a full cursor-based ingestion loop with error recovery, reconciliation, and a `QdrantEmbeddingIngestor`. Why build what exists? But the ARC42STORIES constraint is explicit: *"Hortora shares inference-\* only — rag-\* modules are casehub-specific."* And the constraint exists for sound reasons — `QdrantEmbeddingIngestor` unconditionally calls `sparseEmbedder.embedBatch()` (no dense-only mode), every method calls `MemoryPermissions.assertTenant()` requiring a `CurrentPrincipal` CDI bean, and the config machinery assumes multi-tenant multi-corpus deployment. Three dependency couplings that can't be worked around without stubs. Stubs are workarounds; workarounds are bad design.

The right boundary turned out to be `casehub-corpus-api` + `casehub-corpus` (Hortora-eligible) with an engine-local ingestion service. About 220 lines: read cursor, get changes, delete-then-upsert, save cursor. Two error classes — extraction failures (bad YAML, missing file) skip the file and advance the cursor; infrastructure failures (Ollama down, Qdrant unreachable) don't advance the cursor so the batch retries. `ReentrantLock.tryLock()` prevents the startup ingest and the watcher callback from interleaving.

The other big change was dropping LangChain4j's `EmbeddingStore<TextSegment>` for direct `io.qdrant:client` gRPC access. `EmbeddingStore` can't do named vectors, payload-scoped deletes, scroll pagination, or collection creation with explicit vector config. All of which Phase 2 needs for hybrid dense+sparse search. Building on `EmbeddingStore` now means replacing it entirely at Phase 2. The Qdrant client gives us `QueryPoints` with named vectors from day one — Phase 2 adds prefetch queries for RRF fusion without changing the calling pattern.

The cursor location was a subtle catch from the spec review. I'd put it at `${garden-path}/.cursor`. That's inside the directory that `FlatChangeSource` watches. Every cursor write triggers a filesystem event, the watcher delivers it, the ingestion service reads it, skips it (not `.md`), and writes a new cursor — which triggers another event. The `_` prefix convention (`_state/garden.cursor`) makes both `FlatCorpusStore.list()` and `FlatChangeSource.onRawEvent()` ignore it. One character fixes the feedback loop.

Two protocols came out of this session and landed in the garden: `PP-20260616-f5a372` (use `io.methvin:directory-watcher` for filesystem watching) and `PP-20260616-896634` (use `io.qdrant:client` directly, not `quarkus-langchain4j-qdrant`). Both are "preferred dependency" decisions — the kind of thing that gets rediscovered independently in every project without a shared record. We also filed `casehubio/parent#255` to design a dependency argumentation graph — the observation that dependency choices form a graph of mutual exclusions and contextual defeats, not a flat registry.

The collection schema uses a named `"dense"` vector. Phase 2 will add a `"sparse"` vector name for SPLADE — `casehub-neural-text`'s `inference-splade` module is Hortora-eligible and already ships the `SparseEmbedder` the ingestor will need.
