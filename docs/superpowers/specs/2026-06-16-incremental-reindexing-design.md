# Incremental Re-Indexing Design

**Issue:** Hortora/engine#7
**Date:** 2026-06-16
**Revision:** 4

## Problem

The engine performs a full re-index of all garden entries at startup via `GardenIndexer`. This means:
- Changes to garden entries are not reflected until the service restarts
- The current `addAll(embeddings, segments)` call generates random UUIDs, so every restart adds duplicate copies of every entry
- No way to add/update/remove entries without downtime

## Architectural Boundary

**ARC42STORIES §2 constraint:** *"Hortora shares inference-\* only — rag-\* modules are casehub-specific. Hortora wires their own LangChain4j RAG stack independently."*

The engine consumes only the Hortora-eligible layers of `casehub-neural-text`:
- `casehub-corpus-api` — pure Java, zero deps. `ChangeSource`, `WatchableChangeSource`, `ChangeSet`, `ChangedEntry`, `ChangeType`, `ChangeListener`, `CorpusReader` SPIs.
- `casehub-corpus` — `corpus-api` + zip4j + `directory-watcher`. `FlatCorpusStore`, `FlatChangeSource` (implements `WatchableChangeSource` with `DirectoryWatcher` + 500ms debounce + overflow recovery).

The engine does NOT depend on `casehub-rag`, `casehub-rag-api`, or `casehub-platform-api`. The ingestion pipeline, cursor store, metadata extraction, and Qdrant access are engine-local.

### Why not `casehub-rag`

`casehub-rag` is casehub-specific for three reasons that cannot be worked around without wrappers:
1. `QdrantEmbeddingIngestor` unconditionally calls `sparseEmbedder.embedBatch()` — no dense-only mode. Providing a no-op `SparseEmbedder` stub is a workaround, not a design.
2. Every `EmbeddingIngestor` method calls `MemoryPermissions.assertTenant()` requiring a `CurrentPrincipal` CDI bean. The engine is single-tenant with no auth.
3. `CorpusIngestionService` brings `@Scheduled` polling, multi-corpus config, `CorpusBindingProducer`, and `RagConfig` — machinery for a multi-tenant multi-corpus platform. Unnecessary complexity for a single-corpus service.

### What IS reused

The genuinely valuable infrastructure — filesystem change detection with `directory-watcher`, mtime-based cursors, debounced event delivery, overflow recovery — lives in `casehub-corpus` (Hortora-eligible). This is the hard part, and it's already built and tested.

## Architecture

### Dependency changes

**Add:**
- `casehub-corpus-api` — change detection SPIs
- `casehub-corpus` — `FlatCorpusStore` + `FlatChangeSource` (includes `io.methvin:directory-watcher` transitively)
- `io.qdrant:client` — direct Qdrant gRPC client (protocol `PP-20260616-896634`)

**Remove:**
- `quarkus-langchain4j-qdrant` — replaced by direct `io.qdrant:client`

**Keep:**
- `quarkus-langchain4j-ollama` — `EmbeddingModel` for dense embedding generation
- `quarkus-langchain4j-core` — `TextSegment`, `Embedding` types

### Components

**`GardenMetadataExtractor`** — engine-local. Parses YAML frontmatter from garden `.md` files. Returns `title + "\n\n" + body` as the content (preserving title in the embedding for retrieval quality — the current `GardenIndexer.toSegment()` does this). Metadata map includes title, domain, type, score, tags, submitted. For files without frontmatter or non-`.md` files: returns empty content string, which causes the ingestion service to skip them.

Note: `YamlFrontmatterExtractor` exists in `casehub-rag` (`@DefaultBean`) and does similar work, but the engine cannot depend on it (wrong dependency layer per §2 constraint). Key difference: `YamlFrontmatterExtractor` returns full text as body for files without frontmatter; `GardenMetadataExtractor` returns empty content, which is the desired filtering behaviour for the garden.

**`GardenIngestionService`** — engine-local, `@ApplicationScoped`. ~100 lines. Implements the cursor-based ingestion loop with concurrency control:

- **Concurrency:** `ReentrantLock.tryLock()` guards all ingestion paths. Startup ingest holds the lock; watcher callbacks use `tryLock()` and skip on contention. Skipped events are not lost — the mtime cursor catches up on the next watcher batch.
- Reads cursor from `FileCursorStore`
- Calls `changeSource.fullScan()` (no cursor) or `changeSource.changesSince(cursor)` (cursor exists)
- Phase 1: deletions first (Qdrant payload filter on `sourceDocumentId`), then additions/modifications
- Additions/modifications: batch all upsertable entries, embed via `embeddingModel.embedAll()`, build points, upsert
- Watcher-driven single-file changes: `embeddingModel.embed()` (single entry per batch is correct for live events)
- No chunking — garden entries are the retrieval unit

**Error model — two failure classes:**

- **Extraction failure** (per-file — malformed YAML, unreadable file): skip the file, log its path. Cursor advances — filesystem state IS correct even though this file wasn't indexed. The file stays un-indexed until it's fixed (its mtime changes, triggering reprocessing). This avoids the re-processing-everything-on-every-restart problem that occurs when one bad file blocks cursor advancement.
- **Infrastructure failure** (Ollama down, Qdrant unreachable — `embedAll()` or `upsertAsync()` throws): cursor does NOT advance. The entire batch retries on next startup or watcher cycle. Without this distinction, an Ollama outage during startup silently loses the entire corpus — the cursor would advance as if everything was indexed, and `changesSince()` would return an empty changeset on restart.

**`FileCursorStore`** — engine-local. File-backed cursor persistence. `save(cursor)` writes to `${hortora.garden.path}/_state/garden.cursor`. `load()` reads it. ~30 lines.

**Cursor location:** The cursor file lives at `_state/garden.cursor` inside the garden directory. The `_` prefix ensures both `FlatCorpusStore.list()` and `FlatChangeSource.onRawEvent()` ignore it — no feedback loop from cursor writes triggering watcher events.

**`QdrantClientProducer`** — engine-local CDI producer. Reads Qdrant host/port from engine config, produces `@ApplicationScoped QdrantClient`.

**`GardenIndexer` (refactored)** — startup wiring and lifecycle management. `@ApplicationScoped`.

On `@Observes StartupEvent`:
1. Creates `FlatCorpusStore` + `FlatChangeSource` pointing at `hortora.garden.path`
2. **Eagerly creates the Qdrant collection** with dense vector params (768-dim, cosine). This guarantees the collection exists before any search or ingest, even if the garden is empty.
3. Triggers `GardenIngestionService.ingest()` for initial full-scan-or-incremental sync
4. Calls `changeSource.watch(listener)` — since `FlatChangeSource` implements `WatchableChangeSource`, this starts the `DirectoryWatcher` for push-based live events
5. The watcher listener calls `GardenIngestionService.onChanges(entries)` for each debounced batch

On `@PreDestroy`:
- Calls `changeSource.close()` — stops the `DirectoryWatcher` thread and debounce `ScheduledExecutorService`. Without this, resources leak on shutdown and `FlatChangeSource` throws `IllegalStateException("Already watching")` on Quarkus live reload.

**Operational model:** After startup, changes flow through the `DirectoryWatcher` → 500ms debounce → `onChanges()` path, NOT through `@Scheduled` polling. `FlatChangeSource` is a `WatchableChangeSource` — push-based filesystem watching with native macOS FSEvents (via JNA) or Linux inotify.

**Cursor checkpointing:** Cursor is saved after every watcher batch (inside `onChanges()`), not on a timer. For a ~200 file garden with infrequent changes, the I/O overhead of writing a small JSON cursor file per batch is negligible. This avoids adding `quarkus-scheduler` as a dependency for a single timer.

**`SearchResource` (refactored)** — replaces `EmbeddingStore.search()` with direct `QdrantClient` queries using `QueryPoints` (not `SearchPoints`). `QueryPoints` is used from Phase 1 so that Phase 2 only adds prefetch queries for RRF fusion — the calling pattern stays the same.

Dense-only query uses `QueryPoints` with the `"dense"` named vector. Payload filtering for domain via `ConditionFactory.matchKeyword()`. Results extracted from point payload: content, title, domain, type, score, sourceDocumentId.

`SearchResult.id` is populated from `sourceDocumentId` in the point payload — they are the same value (the file's relative path within the garden).

`EmbeddingModel` stays for query embedding.

**`GardenMcpTools` (refactored)** — `gardenSearch()` unchanged (delegates to `SearchResource`). `gardenStatus()` refactored: replaces `GardenIndexer` injection with `QdrantClient` injection. Uses `QdrantClient.countAsync(collection)` for the indexed count instead of a cached `indexedCount` field — this is the truth, not a drifting counter. Needs the collection name from config.

### Qdrant collection schema

The collection uses a named dense vector. Phase 2 will add a sparse vector — but Phase 1 creates only the dense vector params. Collection creation does not eagerly create sparse vector config; that is Phase 2's responsibility.

| Vector name | Type | Phase 1 |
|-------------|------|---------|
| `dense` | float[768] (nomic-embed-text, cosine) | Active |

### Payload schema

Each Qdrant point carries:

| Field | Type | Purpose |
|-------|------|---------|
| `content` | string | Full entry text (title + body) for retrieval |
| `sourceDocumentId` | string | Relative path within garden (e.g., `jvm/GE-0144.md`) — used for delete-by-document and maps to `SearchResult.id` |
| `title` | string | Entry title from frontmatter |
| `domain` | string | Entry domain — used for domain filter queries |
| `type` | string | Entry type from frontmatter |
| `score` | string | Curation score from frontmatter |

### Point IDs

UUID v3 (name-based MD5) derived from the relative path within the garden directory.

Namespace UUID: `UUID.nameUUIDFromBytes("hortora.garden".getBytes(UTF_8))` — a fixed constant declared in `GardenIngestionService`.

Deterministic — same file always maps to the same point ID. Upserts are idempotent; no duplicates on restart.

### File filtering

`FlatCorpusStore.list()` returns all regular files, filtering only `_`-prefixed paths. Non-`.md` files (README, images, SCHEMA.md) will be handed to `GardenMetadataExtractor`. The extractor returns empty content for these, and the ingestion service skips entries with empty content. This is mildly inefficient (every file is read) but acceptable for a corpus of ~200 files. The garden directory contains almost exclusively `.md` files.

### Ingestion flow

**Startup (first run, no cursor):**
1. Eager collection creation (dense vector params)
2. `GardenIngestionService.ingest()` acquires lock → `FlatChangeSource.fullScan()` → all files as `ADDED`
3. For each `.md` file with frontmatter: `GardenMetadataExtractor` → content (title + body) + metadata
4. Batch embed all entries via `embeddingModel.embedAll(segments)`
5. Build `PointStruct` per entry: UUID v5 ID, named `"dense"` vector, payload
6. Batch upsert to Qdrant via `QdrantClient.upsertAsync()`
7. Cursor saved to `_state/garden.cursor`
8. Lock released
9. `changeSource.watch(listener)` starts the `DirectoryWatcher`

**Startup (subsequent, cursor exists):**
1. Eager collection creation (idempotent — checks existence first)
2. `GardenIngestionService.ingest()` acquires lock → `FlatChangeSource.changesSince(cursor)` → only changed/added/deleted files
3. Deletions processed first (payload filter on `sourceDocumentId`), then additions/modifications batch-embedded
4. Cursor advanced on success (partial success: cursor advances, failed files logged)
5. Lock released
6. Watcher started

**Runtime (watcher-driven):**
1. File event arrives via native FSEvents (macOS) or inotify (Linux)
2. 500ms debounce batches rapid changes
3. `GardenIngestionService.onChanges(entries)` calls `tryLock()` — skips if startup ingest is still running
4. Processes batch: delete/upsert as above (single-entry `embed()` for live events)
5. Cursor saved after successful batch processing

**Overflow recovery:** If the OS event queue overflows, `FlatChangeSource.handleOverflow()` does a full mtime diff against the last known state — no events are lost.

### Dev Services and testing

**Removing `quarkus-langchain4j-qdrant` removes Qdrant Dev Services.** The engine must provide its own Qdrant lifecycle for dev and test modes.

**Dev mode:** Document that Qdrant must be started manually: `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant`. Engine config points to `localhost:6334`.

**Test mode:** Testcontainers for Qdrant in integration tests (`@QuarkusTest`). Unit tests for `GardenMetadataExtractor`, `FileCursorStore`, and `GardenIngestionService` use no Qdrant — mock `QdrantClient` or test the ingestion logic in isolation.

**Config migration:**

| Old property | New property |
|-------------|-------------|
| `quarkus.langchain4j.qdrant.collection.name` | `hortora.qdrant.collection` (default: `garden`) |
| *(implicit via Dev Services)* | `hortora.qdrant.host` (default: `localhost`) |
| *(implicit via Dev Services)* | `hortora.qdrant.port` (default: `6334`) |

### What gets removed

- `GardenEntryParser` — replaced by `GardenMetadataExtractor`
- `GardenEntry` record — metadata now in extractor output; body in content string
- Direct `EmbeddingStore<TextSegment>` injection — replaced by `QdrantClient`
- `TestEmbeddingStore` — replaced by Testcontainers Qdrant or mock `QdrantClient`
- `quarkus-langchain4j-qdrant` dependency

### What stays (unchanged)

- `GardenConfig` — garden path, id, prefix, schema path
- `FederationConfig`, `FederationConfigParser`, `ChainWalker`, `RemoteGardenClient` — federation is orthogonal to indexing
- `SearchResult` record — API return type (`id` field populated from `sourceDocumentId`)
- `EmbeddingModel` — query embedding in `SearchResource`, document embedding in `GardenIngestionService`

Note: `GardenMcpTools` is listed under Components (refactored) — it gains a `QdrantClient` injection and loses the `GardenIndexer` injection.

## Testing

- **`GardenMetadataExtractor`** — unit tests with fixture `.md` files. Verify: frontmatter parsed to metadata map; title prepended to body in content; non-`.md` and no-frontmatter files return empty content.
- **`FileCursorStore`** — unit tests with temp directory. Verify: save/load round-trip, load returns empty when no file exists, `_state/` directory created automatically.
- **`GardenIngestionService`** — unit tests with mock `QdrantClient` and `EmbeddingModel`. Verify: full scan batch-embeds all entries via `embedAll()`; `changesSince` processes only deltas; deletions use payload filter on `sourceDocumentId`; extraction failure (bad YAML) skips file and cursor advances; infrastructure failure (embed/upsert throws) does NOT advance cursor; `tryLock()` contention skips gracefully; cursor saved after each successful watcher batch.
- **`SearchResource`** — integration test with Testcontainers Qdrant. Verify: `QueryPoints` with domain filtering, provenance fields, relevance ordering, `SearchResult.id` populated from `sourceDocumentId`.
- **Incremental indexing integration** — Testcontainers Qdrant. Index fixtures, modify a file, trigger `onChanges()`, verify updated content appears in search results.
- **Delete detection** — Testcontainers Qdrant. Index fixtures, delete a file, trigger `onChanges()`, verify entry no longer appears.
- **Concurrency** — verify `tryLock()` behaviour: concurrent `ingest()` and `onChanges()` don't corrupt cursor or interleave Qdrant operations.

## Phase 2 alignment

This design prepares for Phase 2 (SPLADE + cross-encoder reranking) but does not pretend Phase 2 is free:

**What carries over unchanged:** `FlatChangeSource`, `GardenMetadataExtractor`, `FileCursorStore`, `GardenConfig`, federation, MCP tools, `QueryPoints` calling pattern.

**What requires structural changes at Phase 2:**
- `GardenIngestionService` — must add sparse embedding generation via `SparseEmbedder` from `inference-splade` (Hortora-eligible)
- Collection creation — must add sparse vector params (`SparseVectorConfig`)
- Point construction — must include both named dense and sparse vectors
- `SearchResource` — must add `PrefetchQuery` legs (dense + sparse) with RRF fusion to the existing `QueryPoints` call
- New dependency: `casehub-inference-splade` (Hortora-eligible per ARC42STORIES)

The `QdrantClient` + `QueryPoints` approach chosen here means Phase 2 extends the existing ingestor and retriever rather than replacing them — named vectors, prefetch queries, and hybrid search are `QdrantClient` native capabilities.
