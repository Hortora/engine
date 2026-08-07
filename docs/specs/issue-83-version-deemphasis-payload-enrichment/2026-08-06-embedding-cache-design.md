# Embedding Cache for MultiModalEmbedder

**Issue:** Hortora/engine#83 (branch: issue-83-version-deemphasis-payload-enrichment)
**Date:** 2026-08-06
**Status:** Design approved

## Problem

BGE-M3 ONNX inference takes ~4.5s per entry on CPU, producing dense (1024-dim), sparse (learned lexical), and ColBERT (multi-vector) embeddings. A full reindex of 2,590 garden entries takes ~90 minutes. At 100k entries, that's ~128 hours. End users cannot tolerate this.

Reindexing is triggered by Qdrant collection deletion, migration (dimension/schema changes), or fresh deployment. In all cases except model upgrade, the content hasn't changed — the embeddings are deterministic and recomputable, but the computation is wasted.

## Solution

A `CachingMultiModalEmbedder` CDI decorator on `MultiModalEmbedder` in `casehub-neocortex-rag`. Caches computed `MultiModalEmbedding` results in SQLite, keyed by content hash + model version. On cache hit, returns the stored embedding without calling ONNX. On cache miss, delegates to the real embedder and stores the result.

## Architecture

### Decorator placement

```
Caller (QdrantEmbeddingIngestor, DedupEmbeddingIngestor, HybridCaseRetriever, ...)
  → CachingMultiModalEmbedder (@Decorator, @Priority(100))
    → real MultiModalEmbedder (BgeM3Embedder)
      → ONNX Runtime (only on cache miss)
```

All callers benefit transparently. No changes to any existing class.

### Why the embedder level, not the ingestor level

A decorator on `EmbeddingIngestor` would need to replicate `QdrantEmbeddingIngestor`'s batch splitting, point building, and upsert logic — it can't delegate to the ingestor because the delegate would re-embed everything. That's a rewrite, not a decorator.

Wrapping the embedder is strictly better:
- Zero changes to `QdrantEmbeddingIngestor`, `DedupEmbeddingIngestor`, `CorpusIngestionService`
- Fixes `DedupEmbeddingIngestor`'s double-embed problem for free (dedup's `embed()` writes to cache, subsequent `embedBatch()` in the ingestor is a cache hit)
- Correct abstraction — caching avoids redundant computation, which is the embedder's concern

### Query-time caching

Query embeddings (~4KB each, just dense) are cached too. This is negligible overhead — even 100k queries = 400MB vs 5GB+ of ingestion embeddings. Repeat queries get instant results.

## Data Flow

### `embedBatch(texts)` — the hot path

1. Hash each text: `SHA-256(text)` → 64-char hex key
2. Batch lookup: `SELECT content_hash, dense, sparse, colbert FROM embedding_cache WHERE content_hash IN (?, ...) AND model_version = ?`
3. Partition into hits (cached) and misses (need ONNX)
4. Call `delegate.embedBatch(missTexts)` for misses only
5. Store new results: `INSERT OR REPLACE INTO embedding_cache ...`
6. Reassemble in original order, return `List<MultiModalEmbedding>`

### `embed(text)`

Delegates to `embedBatch(List.of(text)).get(0)`. One code path, one cache.

### `embedSeparate(denseText, nonDenseText)`

Two texts cached independently. If equal, falls through to `embed()`. Otherwise caches each separately and assembles `MultiModalEmbedding(dense from text1, sparse+colbert from text2)`.

### Passthrough methods

`supportedModes()`, `denseDimension()`, `colbertDimension()`, `maxSequenceLength()` delegate directly to the wrapped embedder.

## Cache Key and Model Versioning

**Cache key:** `SHA-256(content)` — 64-char hex string. Content identity only.

**Model version:** Separate column, computed at startup from `delegate.denseDimension() + ":" + delegate.maxSequenceLength() + ":" + config.versionSuffix()`. Cache lookups only match rows where `model_version` equals the current version.

On model upgrade (e.g., BGE-M3 → future model), dimension or sequence length changes automatically invalidate the cache. The `versionSuffix` config is the escape hatch for same-dimension model replacements.

Old entries are silently ignored and lazily replaced. No manual purge needed.

## Storage

### SQLite schema

```sql
CREATE TABLE embedding_cache (
    content_hash  TEXT NOT NULL,
    model_version TEXT NOT NULL,
    dense         BLOB NOT NULL,
    sparse        BLOB,
    colbert       BLOB,
    created_at    INTEGER NOT NULL DEFAULT (unixepoch()),
    PRIMARY KEY (content_hash, model_version)
);
```

WAL mode. HikariCP connection pool (same pattern as `SqliteRetrievalTracker`).

### Binary serialization

Pure numeric data, no framework:

```
dense:   [4 bytes: length] [length x 4 bytes: floats]
sparse:  [4 bytes: entry count] [count x (4 bytes int key + 4 bytes float value)]
colbert: [4 bytes: rows] [4 bytes: cols] [rows x cols x 4 bytes: floats]
```

Sparse and ColBERT BLOBs are NULL when the embedder doesn't produce those modes.

### Size estimates

| Scale | Entries | Cache size |
|-------|---------|------------|
| Hortora garden | 2,590 | ~130 MB |
| Medium corpus | 10,000 | ~500 MB |
| Large corpus | 100,000 | ~5 GB |

## CDI Wiring

```java
@Decorator
@Priority(100)
public class CachingMultiModalEmbedder implements MultiModalEmbedder {
    @Inject @Delegate @Any MultiModalEmbedder delegate;
    @Inject EmbeddingCache cache;
    @Inject EmbeddingCacheConfig config;
}
```

Activation: runtime check in each method (`if (!config.enabled()) return delegate.embedBatch(texts)`). Not `@LookupIfProperty` — the decorator bean must always exist so CDI wiring succeeds; the config check is a fast-path bypass.

## Configuration

```java
@ConfigMapping(prefix = "casehub.rag.embedding-cache")
public interface EmbeddingCacheConfig {
    @WithDefault("false")
    boolean enabled();

    String path();  // SQLite DB path, required when enabled

    @WithDefault("")
    String versionSuffix();  // bump to force invalidation
}
```

### Engine application.properties

```properties
casehub.rag.embedding-cache.enabled=true
casehub.rag.embedding-cache.path=${user.home}/.hortora/cache/embeddings.db
```

### Test properties

```properties
casehub.rag.embedding-cache.enabled=false
```

## Error Handling

The cache is a performance optimization, never a correctness gate:

- **SQLite unavailable at startup:** log warning, disable cache, pass through to delegate
- **Cache read fails (corrupt DB, IO error):** log warning, treat as cache miss, compute normally
- **Cache write fails:** log warning, continue — embedding is still returned, just not cached
- Every failure mode degrades to "compute embedding normally"

## Out of Scope

- **Cache eviction / TTL** — old model versions are ignored automatically. A manual `DELETE FROM embedding_cache WHERE model_version != ?` covers disk pressure.
- **Cache warming from Qdrant** — extracting embeddings from Qdrant to populate cache. Possible but complex. First reindex after enabling cache pays full cost; every subsequent one is fast.
- **Parallel ONNX inference (#82)** — orthogonal. Cache reduces *how many* embeddings to compute; parallelism reduces *how long* each takes.

## Module

`casehub-neocortex-rag` — depends on `casehub-neocortex-inference-api` (for `MultiModalEmbedder` interface). All consumers of the shared RAG module benefit, not just engine.

## Performance Impact

| Scenario | Before | After |
|----------|--------|-------|
| First index (cold cache) | ~90 min / 2.6k entries | ~90 min (same — must compute all) |
| Reindex (unchanged content) | ~90 min | ~2-3 min (SHA-256 + SQLite lookups) |
| Reindex 100k (unchanged) | ~128 hours | ~15-20 min |
| Model upgrade (cache miss) | N/A | Full recompute (correct — new model) |
