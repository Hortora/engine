# Qdrant Payload Indexes — Full-Text and Keyword

**Date:** 2026-06-29
**Issues:** casehubio/neural-text#47, casehubio/neural-text#50
**Module:** `rag` (implementation tier)
**Status:** Design approved

## Problem

Hortora/engine#27 benchmarked gardenSearch (dense-only, nomic-embed-text) against grep across 14 real-world scenarios. Grep won every keyword scenario — queries using Java class names (`@DefaultBean`, `ConcurrentHashMap`, `ChatModel`, `ExceptionMapper`) failed catastrophically with vector search because the embedding model treats domain identifiers as generic English tokens.

Separately, `tenantId` and `sourceDocumentId` are used as keyword filters via `ConditionFactory.matchKeyword()` across multiple operations, but no explicit payload indexes exist — Qdrant does a linear payload scan for each filter.

The highest-impact consumer is the retrieval path: every shared-tenancy `retrieve()` call in `HybridCaseRetriever` and `ReactiveHybridCaseRetriever` includes `matchKeyword("tenantId", corpus.tenantId())` as a filter condition. Without a keyword index, Qdrant applies this filter after the vector search (post-filtering), which is significantly slower than pre-filtering for large shared collections. Retrieval is the hottest path — far more queries per second than deletes — making the `tenantId` keyword index the primary performance justification.

On the ingestor side, `deleteDocument()` filters on both `sourceDocumentId` (via `matchKeyword`) and `tenantId` (via `tenantFilter()` in shared mode). `deleteCorpus()` in shared mode filters only by `tenantId`. `listDocuments()` in shared mode filters by `tenantId` but does not filter on `sourceDocumentId` — it scrolls all tenant-scoped points and extracts unique document IDs from the payload map.

## Solution

Add three payload indexes to every Qdrant collection, created during `ensureCollection()`:

| Field | Index Type | Parameters |
|-------|-----------|------------|
| `content` | Text (full-text) | `Word` tokenizer, `lowercase=true`, `min_token_len=2`, `max_token_len=40` |
| `sourceDocumentId` | Keyword | defaults |
| `tenantId` | Keyword | defaults |

For existing collections, missing indexes are detected via `CollectionInfo.payload_schema` and added lazily — no re-ingestion required.

## Tokenizer Choice: Word

Evaluated against actual garden content (40% code, 35% prose, 25% identifiers/config):

- **CamelCase preserved:** `ConcurrentHashMap` → single token `concurrenthashmap`. Exact matching works, but partial identifier search fails — searching for `HashMap` will not match documents containing `ConcurrentHashMap`, and `Retriever` will not match `CaseRetriever`. No built-in Qdrant tokenizer splits camelCase. For the target scenarios (users searching for known full identifiers), this is acceptable. For exploratory sub-identifier search, it produces false negatives. See casehubio/neural-text#53 for future mitigation options.
- **Dots split:** `io.casehub.rag.EmbeddingIngestor` → `io`, `casehub`, `rag`, `embeddingingestor`. Enables both component and full-path search.
- **Annotations:** `@DefaultBean` → `defaultbean`. Users search for `DefaultBean`, not `@DefaultBean`.
- **Code punctuation:** `close()` → `close`. Code is searchable by identifier.

`Whitespace` was rejected: `close()` stays as one token (search for `close` won't match), and `@DefaultBean` requires the `@` in the query.

**No stemming.** BM25's purpose is exact matching — what embeddings can't do. Stemming moves back toward fuzzy matching.

**No stopwords.** BM25's IDF weighting naturally down-weights common words.

**Fixed parameters, non-configurable.** The index set is infrastructure, not a user-tunable knob. Different tokenizer settings per corpus would require a much larger design change with no current demand.

## Architecture

### Constraint

Full-text indexes cannot be defined in `CreateCollection` — they require a separate `createPayloadIndexAsync` call afterward. Keyword indexes have the same constraint.

### ensureCollection flow

```
1. knownCollections cache hit → return
2. collectionExistsAsync →
   exists:
     validate dimension (unchanged)
     check payload_schema for missing indexes and type correctness
     createPayloadIndexAsync for each missing index
     add to cache
   not exists:
     createCollectionAsync (dense + sparse + quantization — unchanged)
     createPayloadIndexAsync for all three indexes
     add to cache
```

### Blocking ingestor (QdrantEmbeddingIngestor)

Private `ensurePayloadIndexes(String collection, Map<String, PayloadSchemaInfo> existingSchema)` method. For each expected index field, checks `existingSchema.containsKey()` for presence and validates `PayloadSchemaInfo.getDataType()` against the expected `PayloadSchemaType` (Text for `content`, Keyword for `sourceDocumentId` and `tenantId`). A type mismatch throws `IllegalStateException` — the index was manually changed or corrupted, and automatic correction would be unsafe. Creates missing indexes sequentially via `.get()`. Called from both the existing-collection and new-collection paths.

### Reactive ingestor (ReactiveQdrantEmbeddingIngestor)

Equivalent `ensurePayloadIndexes` returning `Uni<Void>`, including the same type validation. Independent index creation calls run concurrently via `Uni.join().all().andFailFast()`. Chained after dimension validation (existing path) or after `createCollectionAsync` (new path).

### Java API shape

Text index creation requires `PayloadIndexParams` with nested `TextIndexParams`:

```java
PayloadIndexParams textParams = PayloadIndexParams.newBuilder()
    .setTextIndexParams(TextIndexParams.newBuilder()
        .setTokenizer(TokenizerType.Word)
        .setLowercase(true)
        .setMinTokenLen(2)
        .setMaxTokenLen(40)
        .build())
    .build();

client.createPayloadIndexAsync(collection, "content",
    PayloadSchemaType.Text, textParams, true, null, null);
```

Keyword indexes pass `null` for `indexParams`:

```java
client.createPayloadIndexAsync(collection, "sourceDocumentId",
    PayloadSchemaType.Keyword, null, true, null, null);
```

### Concurrency

The blocking ingestor's `ensureCollection()` has a pre-existing check-then-act race on `knownCollections` — two concurrent `ingest()` calls for the same new collection can both attempt collection creation and index creation.

For new collections, the race is benign via retry recovery: `createCollectionAsync` is NOT idempotent — the losing thread's call fails with an error because the collection already exists. The exception propagates before `knownCollections.add()`, so the collection is not cached. The next `ingest()` call retries, finds the collection exists via `collectionExistsAsync`, and proceeds through the existing-collection path (which validates dimension, checks indexes, and creates any missing).

For existing collections missing indexes, concurrent threads may both detect the same missing indexes and both call `createPayloadIndexAsync` for the same fields. This is benign because `createPayloadIndexAsync` is idempotent for existing indexes of the same type — Qdrant returns success. The existing code relies on this: there is no guard against concurrent index creation in the existing-collection path.

The reactive ingestor avoids both races via `ConcurrentHashMap.computeIfAbsent()` + `memoize().indefinitely()`. This spec adds index creation inside the racy section but does not introduce the race.

### Migration

Lazy — triggered when `ensureCollection` encounters an existing collection without the expected indexes. `getCollectionInfoAsync` is already called for dimension validation; `payload_schema` is checked from the same response. No extra Qdrant round-trip.

`createPayloadIndexAsync` builds the index from existing payload data — no re-ingestion needed. The `wait=true` parameter ensures the index is built before returning. For large existing collections (100K+ points), building a full-text index is O(n) — the first `ingest()` after upgrade will have elevated latency while indexes are built. This is a one-time cost per collection. `wait=false` is not suitable because index creation failures would be hidden — the collection would be added to `knownCollections` and the failure never retried, breaking the existing error-handling pattern.

Migration is lazy on next ingest — `ensureCollection()` is only called from `ingest()`. Operations that benefit from the indexes (`retrieve()`, `deleteDocument()`) do not trigger index creation. Full performance improvement requires at least one `ingest()` call per collection after upgrade. This is an acceptable trade-off: adding index creation to the retriever would require retriever changes explicitly excluded from this spec's scope.

If index creation fails mid-way (e.g., content index created but tenantId fails), the exception propagates before the collection is added to `knownCollections`. The next `ingest()` call retries `ensureCollection`, which re-checks `payload_schema` and only creates the still-missing indexes. This is the existing error-handling pattern — no new retry logic needed.

## Tests

Four new test cases per ingestor class (8 total), using Testcontainers with Qdrant v1.18.0:

1. **New collection gets all payload indexes.** Ingest a chunk, verify `payload_schema` contains `content` (Text), `sourceDocumentId` (Keyword), `tenantId` (Keyword).

2. **Migration — existing collection without indexes.** Create a collection manually (vectors only, no payload indexes). Ingest via the ingestor, triggering `ensureCollection`. Verify all three indexes are added.

3. **Idempotent — already-indexed collection.** Use a fresh ingestor instance against an already-indexed collection. Verify `ensureCollection` re-checks, finds indexes present, completes without error.

4. **Type mismatch detection.** Create a collection with a Keyword index on `content` (wrong type — should be Text). Attempt to ingest via the ingestor. Verify `ensureCollection` throws `IllegalStateException` with a message identifying the field and type mismatch.

## Scope boundaries

- **No retrieval changes.** Full-text index is unused until #48 adds a BM25 prefetch leg.
- **No SPI changes.** `EmbeddingIngestor`, `CaseRetriever` interfaces unchanged.
- **No RagConfig changes.** No new configuration properties.
- **No rag-api changes.** Everything stays in `rag` module (Tier 3).
- **No CDI wiring changes.** No new constructor parameters.

## Related

- casehubio/neural-text#48 — BM25 as third RRF leg (depends on this index existing)
- casehubio/neural-text#53 — camelCase tokenizer limitation (deferred; affects #48's BM25 retrieval quality)
- casehubio/neural-text#54 — metadata key collision with reserved payload fields (pre-existing; amplified by payload indexes)
- Hortora/engine#27 — dense-only benchmark proving the keyword gap
- Hortora/engine#28 — SPLADE hybrid benchmark (in progress)
- GE-20260627-4712de — garden entry documenting the nomic-embed-text keyword gap