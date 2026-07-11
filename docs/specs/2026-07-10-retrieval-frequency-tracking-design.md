# Retrieval Frequency Tracking — Design Spec

**Issue:** #24 — Track retrieval frequency for garden entries — usage-based curation
**Date:** 2026-07-10

## Problem

The garden corpus has ~6,500 entries with suspected low signal-to-noise ratio. There is no
mechanism to identify which entries are actually retrieved by AI consumers. Entries with zero
or near-zero retrievals over a meaningful window are candidates for review or erasure.

## Design Decisions

1. **Use the existing `RetrievalTracker` SPI** — `casehub-neocortex-rag-api` already defines
   a `RetrievalTracker` interface with `record()`, `findRetrievedDocumentIds()`, `findRecords()`,
   `feedback()`, and `purgeOlderThan()`. The `casehub-neocortex-rag-tracking` module provides
   `SqliteRetrievalTracker` (durable persistence), `TrackingCaseRetriever` (transparent recording
   via CDI decorator), and `RetentionScheduler` (automated purge). There is no reason to create
   a parallel SPI when this infrastructure exists, is tested, and is architecturally aligned with
   the CBR Retain cycle.

2. **Per-retrieval event records, not document-level aggregates** — `RetrievalTracker` stores
   individual retrieval events (query, corpus, documents, timestamp). This is strictly more
   powerful than aggregate counters: you can compute counts, time-windowed frequencies, recency
   metrics, and decay functions from events. Per-retrieval records also support the feedback loop
   (`feedback()` with `RetrievalOutcome`) which aggregate counters cannot.

3. **`TrackingCaseRetriever` decorator for transparent recording** — the decorator wraps
   `CaseRetriever.retrieve()` and calls `RetrievalTracker.record()` on every retrieval.
   It is opt-in via build property (`casehub.rag.tracking.enabled=true`), stamps results with
   `_trackingId` metadata to prevent double-counting across federation walks, and degrades
   gracefully if tracking fails (returns results unchanged). The decorator records at the
   retriever level (pre-reranking, pre-adaptive filtering). For curation, this is the right
   level: a document that the vector search retrieves — even if adaptive filtering trims it
   before the consumer sees it — has demonstrated some relevance and should not be considered
   "unretrieved."

4. **SQLite persistence via `SqliteRetrievalTracker`** — WAL mode, HikariCP connection pool,
   Flyway-managed schema. Atomic writes via SQL transactions. No in-memory map, no dirty flag,
   no flush scheduler. Stats survive restarts and reindexing (SQLite file is separate from Qdrant).

5. **MCP tool for querying** — harvest/forage already consume the engine via MCP. A dedicated
   `gardenUnretrieved` tool fits the existing pattern. No REST endpoint until a non-MCP
   consumer exists.

## Existing SPI — `casehub-neocortex-rag-api`

The engine uses the existing `RetrievalTracker` SPI. No new interfaces are defined.

```java
public interface RetrievalTracker {
    String record(RetrievalQuery query, CorpusRef corpus, List<RetrievedChunk> results, int maxResults);
    void feedback(String retrievalId, String sourceDocumentId, RetrievalOutcome outcome);
    List<RetrievalRecord> findRecords(CorpusRef corpus, Instant since, Instant until);
    List<RetrievalFeedback> findFeedback(CorpusRef corpus, Instant since, Instant until);
    Set<String> findRetrievedDocumentIds(CorpusRef corpus, Instant since, Instant until);
    int purgeOlderThan(Instant cutoff);
}
```

Key types: `RetrievalRecord` (retrievalId, query, corpus, documents, maxResults, timestamp),
`RetrievedDocumentRef` (sourceDocumentId, relevanceScore), `RetrievalOutcome` (HIGHLY_RELEVANT,
RELEVANT, NOT_RELEVANT), `RetrievalFeedback` (retrievalId, sourceDocumentId, outcome, timestamp).

## Engine Integration

**New dependency:** `casehub-neocortex-rag-tracking` (runtime scope) — brings
`SqliteRetrievalTracker`, `TrackingCaseRetriever`, and `RetentionScheduler`.

**Configuration:**

| Property | Value | Purpose |
|----------|-------|---------|
| `casehub.rag.tracking.enabled` | `true` | Activates decorator and retention scheduler |
| `casehub.rag.tracking.sqlite.path` | `${hortora.garden.path}/../stats/retrieval-tracking.db` | SQLite database location, co-located with garden |
| `casehub.rag.tracking.retention.days` | `180` | Purge retrieval records older than 180 days |

**Directory creation:** `SqliteRetrievalTracker.init()` must call
`Files.createDirectories(Path.of(path).getParent())` before opening the data source. The xerial
SQLite JDBC driver creates the `.db` file but does NOT create parent directories. Without this,
first-run startup fails when `stats/` does not exist. This is a one-line addition to the
rag-tracking module (see §Cross-Repo Sequencing).

## Recording Point

Recording is handled transparently by `TrackingCaseRetriever`, a CDI `@Decorator` that wraps
`CaseRetriever.retrieve()`. When `gardenSearch()` calls `searchResource.searchAdaptive()` →
`doSearch()` → `searchLocal()` → `caseRetriever.retrieve()`, the decorator intercepts the
retriever call and records the results.

**Federation isolation:** the decorator only intercepts local `CaseRetriever.retrieve()` calls.
Federation results arrive via HTTP through `RemoteGardenClient` and never pass through the local
`CaseRetriever`. Each garden tracks its own local retrievals independently — upstream/peer
documents are tracked by their own garden's decorator.

**REST endpoints:** `SearchResource` REST endpoints trigger `CaseRetriever.retrieve()` when
serving federation requests from downstream gardens. The decorator records these retrievals,
which is correct: if a downstream garden's consumer retrieved a local document via federation,
that document IS being used and should not be flagged as unretrieved.

**Deduplication:** `TrackingLogic.isAlreadyTracked()` checks for `_trackingId` in chunk metadata.
If chunks were already tracked (e.g., from a decorator in the retrieval chain), they are not
re-recorded.

## MCP Tool — `gardenUnretrieved`

New tool in `GardenMcpTools`:

```java
@Tool(description = "List garden entries not retrieved within the tracking window, or stale-retrieved. Retrieval records are retained for a configurable period (default 180 days); 'unretrieved' means no retrieval record exists in that window. Use to identify candidates for review or erasure during harvest sessions.")
String gardenUnretrieved(
    @ToolArg(description = "Minimum age in days — entries indexed less than this many days ago are excluded (default 30)", required = false)
    Integer minDays,
    @ToolArg(description = "Stale threshold in days — entries retrieved at some point but not within this window are flagged as stale (default 90). Must be less than the retention period.", required = false)
    Integer staleDays)
```

**Logic:**
1. `embeddingIngestor.listDocuments(corpusRef)` → full document set
2. `tracker.findRetrievedDocumentIds(corpusRef, Instant.EPOCH, Instant.now())` → all retrieved IDs
   within the tracking window. Note: `Instant.EPOCH` means "no lower bound," but
   `RetentionScheduler` purges records older than `retentionDays`. The effective window
   is the retention period. This is intentional — for curation, "not retrieved in the last
   180 days" is the actionable signal.
3. Compute unretrieved: documents in (1) but not in (2)
4. Apply `minDays` filter to unretrieved set:
   - **GE-formatted IDs** (`GE-YYYYMMDD-XXXXXX`): parse date from the ID. Exclude if age < `minDays`.
   - **Non-GE IDs** (e.g., `approaches/testing.md`): include unconditionally — these predate the
     GE format and are old enough to evaluate.
5. Resolve `staleDays` (default 90). If `staleDays >= retentionDays`, include a warning in
   output that stale detection is unreliable because the stale window exceeds the retention window.
6. Compute stale: documents in (2) but not in
   `tracker.findRetrievedDocumentIds(corpusRef, Instant.now().minus(staleDays, DAYS), Instant.now())`
   — retrieved at some point within the retention window but not in the last `staleDays` days
7. **Domain derivation:** extract from the document ID path prefix. IDs follow the `domain/filename`
   convention (e.g., `jvm/GE-20260620-a1b2c3.md` → domain `jvm`). IDs without a path separator
   use domain `unknown`.
8. Return structured text grouped by category (unretrieved, stale), listing entry IDs and domains.
   Header states the effective tracking window: "Tracking window: {retentionDays} days.
   Stale threshold: {staleDays} days."

## Testing

### `GardenMcpToolsTest` additions (unit)
- `gardenSearch` triggers tracking via `TrackingCaseRetriever` (verify `InMemoryRetrievalTracker` receives records)
- `gardenSearch` with empty results does not record (decorator returns empty list, no tracking call)
- `gardenUnretrieved` returns documents not in tracker's retrieved set
- `gardenUnretrieved` excludes recently-indexed entries (GE date < minDays)
- `gardenUnretrieved` includes non-GE entries unconditionally
- `gardenUnretrieved` includes stale entries (retrieved but not within `staleDays` window)
- `gardenUnretrieved` uses custom `staleDays` parameter when provided
- `gardenUnretrieved` warns when `staleDays >= retentionDays`
- `gardenUnretrieved` derives domain from document ID path prefix

### Test infrastructure
- `InMemoryRetrievalTracker` as `@Alternative @Priority(1)` — already exists in
  `casehub-neocortex-rag-testing` (engine test dependency). No new test infrastructure needed.

## Cross-Repo Sequencing

1. **neocortex (rag-tracking module):** Add `Files.createDirectories(Path.of(path).getParent())`
   to `SqliteRetrievalTracker.init()` before creating the data source. Single-line change,
   single commit. This is a defensive improvement that benefits any consumer, not just the engine.

2. **engine:** Add `casehub-neocortex-rag-tracking` dependency, configure tracking properties,
   implement `gardenUnretrieved` MCP tool, add tests. Single commit.

No changes to `neocortex-rag-api` or `neocortex-rag-testing` are required.
The SPI and test infrastructure already exist.

## Out of Scope

- Result-set boosting based on retrieval frequency — future work, computable from tracker records
- Per-retrieval feedback integration — `RetrievalTracker.feedback()` exists but no MCP tool yet
- REST endpoint for stats — no non-MCP consumer exists yet
- Multi-instance coordination — engine is single-instance; SQLite WAL supports concurrent reads
- Post-adaptive-filter recording — may be added as a secondary recording point if pre-filter
  recording proves too conservative for curation decisions
