# Complementary Retrieval Design

*2026-06-29 -- Refs #29*

## Problem

Dense embedding search fails catastrophically on keyword/identifier queries. The #27 benchmark proved it: gardenSearch-KW lost 12 of 14 scenarios against grep. The #28 benchmark proved SPLADE doesn't fix it -- `Splade_PP_en_v1` has zero Java domain vocabulary, expanding `ChatModel` to "hotel, beauty, renovation."

The root cause is a vocabulary mismatch. `nomic-embed-text` encodes `ChatModel` in a generic semantic space where it's near "chat" and "model" in the English sense, not `dev.langchain4j.model.chat.ChatModel`. The BERT WordPiece tokenizer fragments Java identifiers into meaningless subwords before any model can reason about them.

grep succeeds because it does literal substring matching -- no tokenization, no semantic interpretation, no vocabulary mismatch. It finds ALL documents containing the term, ranked by nothing.

The fix is BM25 keyword matching: scored term-based retrieval that finds documents containing query terms and ranks by term frequency, inverse document frequency, and document length. BM25 + dense embedding via RRF fusion gives the best of both worlds -- semantic concept matching for NL queries, exact term matching for keyword queries.

## Scope

This design covers three changes:

1. **BM25 as a third RRF retrieval leg** -- in-process, in neural-text's `rag` module
2. **Java-side RRF fusion** replacing Qdrant-internal RRF -- required for three-way fusion
3. **Payload index and filter enrichment** -- metadata indexes, MCP tool parameters

Out of scope (subsumed or unnecessary):

- **Exact-match index** -- subsumed by BM25 (handles exact term matching)
- **Query routing/classification** -- unnecessary; always run all legs, let RRF handle it. The #27 benchmark showed dense and keyword retrieval are complementary -- trying to classify queries adds complexity without benefit.
- **Elasticsearch/OpenSearch** -- wrong scale for ~2k entries. In-process BM25 rebuilds in <1 second.

## Architecture

### Three-Leg Retrieval with Java-Side RRF

```
Query
  |
  +---> [Dense NN]     Qdrant "dense" vector search     --> top-K scored results
  |     (parallel)
  +---> [Sparse NN]    Qdrant "sparse" vector search    --> top-K scored results (optional)
  |     (parallel)
  +---> [BM25]         In-process keyword index search   --> top-K scored results
  |
  +---> [RRF Fusion]   Java-side reciprocal rank fusion  --> unified ranked results
  |
  +---> [Reranker]     Cross-encoder reranking           --> final results (optional)
```

All three legs execute concurrently. Dense and sparse run as separate Qdrant `queryAsync()` calls (CompletableFuture/Uni). BM25 runs synchronously in <1ms. All three legs apply the same `PayloadFilter` — Qdrant legs as gRPC filter conditions, BM25 via an in-memory evaluator. RRF fusion via `RrfFusion.fuse()` merges by composite key (`sourceDocumentId + content`), sorts by fused score, takes top-maxResults.

### Why Move RRF from Qdrant to Java

Qdrant's `QueryFactory.rrf()` only works with vector-based prefetch queries. BM25 is a term-scoring operation, not a vector operation -- it cannot be a Qdrant prefetch. To include BM25 in RRF, fusion must move to Java.

This is a net architectural improvement: explicit control over fusion, ability to add/remove legs, per-leg weight tuning in the future.

## Component Design

### 1. BM25Index

**Module**: `casehub-neural-text`, `rag`, package-private.
**Not an SPI.** Internal implementation detail of the retrieval pipeline.

#### Corpus Scoping

BM25Index is per-corpus. A coordinator class (`BM25IndexRegistry`) maintains a `Map<String, BM25Index>` keyed by Qdrant collection name (derived from `TenancyStrategy.collectionName(CorpusRef)`). All lifecycle operations — `addChunks`, `removeDocument`, `clear`, `rebuild` — receive a `CorpusRef` and route to the correct index. Search also routes by `CorpusRef`, ensuring BM25 results never cross corpus boundaries.

This matches Qdrant's scoping model: each corpus maps to a separate collection (or tenant-filtered partition under `SHARED_COLLECTION`). The coordinator handles index creation lazily on first write and cleanup on `clear`/`deleteCorpus`.

#### CamelCase-Aware Tokenizer

The tokenizer is the critical component -- this is where the benchmark failures are fixed. Three operations in sequence:

1. **Boundary split**: split on non-alphanumeric characters
   - `@DefaultBean` --> `DefaultBean`
   - `io.casehub.platform` --> `io`, `casehub`, `platform`

2. **CamelCase split**: each segment splits at case transitions
   - `DefaultBean` --> `Default`, `Bean`
   - `HTTPClient` --> `HTTP`, `Client`
   - `BM25` --> `BM`, `25`

3. **Compound preservation**: the unsplit compound is kept alongside components
   - `DefaultBean` --> `defaultbean`, `default`, `bean`
   - `ChatModel` --> `chatmodel`, `chat`, `model`

All tokens lowercased. This means a query for `ChatModel` produces tokens `chatmodel`, `chat`, `model`. A document containing `ChatModel` produces the same tokens. BM25 scores high because all three match. A document containing unrelated "chat" and "model" only matches two tokens -- `chatmodel` (high IDF, rare) is missing, resulting in a lower score.

**Non-Java content**: CamelCase splitting is additive — it only adds splits at case transitions, which don't exist in normal prose. For non-CamelCase text (natural language, medical terminology, legal text), the tokenizer behaves identically to a standard word tokenizer. The compound preservation step ensures exact matches still work for all content types. This matters because neural-text's `rag` module is consumed by application repos (AML, clinical) that deal with non-Java text — the tokenizer is harmless there.

#### Data Structure

Inverted index with compact arrays:

```java
// term --> list of postings (docIndex + termFrequency)
Map<String, List<Posting>> invertedIndex;
record Posting(int docIndex, int tf) {}

// docIndex-keyed parallel arrays
String[] docIds;                    // sourceDocumentId
int[] docLengths;                   // token count per document
String[] docContents;               // full content
Map<String, String>[] docMetadata;  // single-valued metadata
Map<String, List<String>>[] docListMetadata;  // multi-valued metadata (e.g., tags)
```

Memory at 2k documents: ~20MB (1M posting entries x 16 bytes + content + overhead).

#### Search with PayloadFilter

`BM25Index.search(query, topK, PayloadFilter filter)` accepts the same `PayloadFilter` used by Qdrant queries. An in-memory evaluator pattern-matches the sealed hierarchy against stored metadata:

- `Eq(field, value)` — checks `docMetadata[i].get(field).equals(value)`
- `In(field, values)` — for single-valued fields: `values.contains(docMetadata[i].get(field))`. For list-valued fields (checked first via `docListMetadata`): true if any element in the stored list appears in `values`.
- `Not(inner)` — negates the inner predicate
- `And(filters)` — all must match
- `Or(filters)` — any must match

Documents that fail the filter are excluded before BM25 scoring, so the top-K is always filter-compliant. This mirrors Qdrant's pre-filter behavior — BM25 results respect the same domain/type/tag constraints as the vector legs.

#### BM25 Scoring

Standard BM25 with fixed parameters:

```
score(D, Q) = SUM(t in Q) IDF(t) * (TF(t,D) * (k1+1)) / (TF(t,D) + k1*(1 - b + b*|D|/avgdl))
IDF(t) = log((N - n(t) + 0.5) / (n(t) + 0.5) + 1)
```

- k1 = 1.2 (term frequency saturation)
- b = 0.75 (document length normalization)
- N = total document count
- n(t) = documents containing term t
- |D| = document length in tokens
- avgdl = average document length

#### Lifecycle

- **Ingestion**: `QdrantEmbeddingIngestor.ingest()` calls `bm25Index.addChunks()` for each batch alongside Qdrant upsert.
- **Deletion**: `deleteDocument()` calls `bm25Index.removeDocument()`.
- **Corpus clear**: `deleteCorpus()` calls `bm25Index.clear()`.
- **Startup rebuild**: `@Observes @Priority(15) StartupEvent` -- scroll all Qdrant points, extract content from payload, rebuild index. Priority 15 runs after `CollectionMigration` (priority 10), which may reset the corpus and delete the Qdrant collection. Running BM25 rebuild before migration would populate the index with stale data that migration then wipes. Priority 15 ensures the rebuild sees the post-migration collection state. At 2k documents: <1 second.

#### Consistency Model

**Qdrant is the source of truth. BM25 is a derived, best-effort cache.**

If a Qdrant upsert succeeds but `bm25Index.addChunks()` throws (or vice versa), the two stores diverge. No cross-store transaction spans both writes. This is acceptable because:

1. Startup rebuild reconciles: on every restart, BM25 is rebuilt from Qdrant's current state. Any divergence is resolved.
2. The divergence window is bounded: between failure and next restart (or manual rebuild via `gardenReindex()`).
3. The failure mode is graceful: a missing BM25 entry means that document won't appear in keyword-match results, but it's still findable via dense/sparse vector search. An extra BM25 entry (document deleted from Qdrant but still in BM25) produces a candidate that has no Qdrant match — it appears via BM25 only, which is harmless for result quality.

#### Thread Safety

`ReadWriteLock`. Searches acquire read lock (concurrent). Writes (add/remove/rebuild) acquire write lock (exclusive). At 2k documents, contention is negligible.

### 2. Java-Side RRF Fusion

Replaces `QueryFactory.rrf()` in `HybridCaseRetriever`. Reuses the existing `RrfFusion.fuse()` utility in `rag-api`, which is already used by `QueryExpandingCaseRetriever` and `ReactiveQueryExpandingCaseRetriever` for multi-query RRF merge.

#### Retrieval Legs

Each leg produces `List<RetrievedChunk>` directly — no intermediate type. `RetrievedChunk(content, sourceDocumentId, relevanceScore, metadata, grade)` carries all fields needed for fusion. Qdrant legs construct `RetrievedChunk` from `ScoredPoint` payload (as they do today). The BM25 leg constructs `RetrievedChunk` from stored content and metadata.

| Leg | Source | Query text | Optional |
|-----|--------|------------|----------|
| Dense | Qdrant `queryAsync()` on "dense" vector | `query.searchText()` (benefits from HyDE expansion) | No (always runs) |
| Sparse | Qdrant `queryAsync()` on "sparse" vector | `query.text()` (lexical precision) | Yes (`@LookupIfProperty`) |
| BM25 | `BM25Index.search(query, topK, filter)` | `query.text()` (keyword matching — HyDE expansion generates hypothetical documents like "The CDI container resolves beans..." which would match concept words rather than the specific identifier the user searched for) | No (always runs when index populated) |

Dense and sparse each run as separate Qdrant `queryAsync()` calls (no longer combined into a single server-side RRF query, since BM25 cannot be a Qdrant prefetch). Both Qdrant queries and BM25 execute concurrently:

- **Reactive path** (`ReactiveHybridCaseRetriever`): dense and sparse run as concurrent `Uni` chains. BM25 runs inline (sub-millisecond).
- **Blocking path** (`HybridCaseRetriever`): dense and sparse run as `CompletableFuture`s submitted in parallel. `CompletableFuture.allOf(denseFuture, sparseFuture)` waits for both. BM25 runs during the wait (effectively free).

#### Fusion

```java
List<List<RetrievedChunk>> legs = new ArrayList<>();
legs.add(denseResults);
if (sparseResults != null) legs.add(sparseResults);
legs.add(bm25Results);

List<RetrievedChunk> fused = RrfFusion.fuse(legs, maxResults, rrfK);
```

`RrfFusion.fuse()` handles dedup by composite key (`sourceDocumentId + "\0" + content`), preserves the best `RelevanceGrade`, and sorts by fused RRF score. The composite dedup key is correct for this use case: the same `sourceDocumentId` can appear with different content when a document has multiple chunks, and these should be treated as separate candidates. rrfK = 60 (unchanged).

#### Performance

**Reactive path**: expected latency `max(dense ~15ms, sparse ~15ms, bm25 ~1ms) + fusion ~0.1ms = ~15ms`. Current Qdrant-internal RRF: ~28ms. Faster because the two Qdrant queries run concurrently rather than as sequential prefetch stages within a single server-side operation.

**Blocking path**: `embed(dense) + embed(sparse) + max(queryDense ~15ms, querySparse ~15ms, bm25 ~1ms) + fusion = embed_time + ~15ms`. Current blocking path: `embed(dense) + embed(sparse) + singleQuery ~28ms`. The parallel approach is faster by ~13ms even on the blocking path, because `CompletableFuture.allOf()` runs both Qdrant queries concurrently.

### 3. Payload Index Enrichment

#### Metadata Indexes

Add keyword indexes to `ensureCollection()`:

| Field | Index Type | Purpose |
|-------|-----------|---------|
| `domain` | Keyword | Filter by entry domain (jvm, tools, python) |
| `type` | Keyword | Filter by entry type (gotcha, technique, undocumented) |
| `tags` | Keyword | Filter by tags |

Qdrant's `createPayloadIndex` is idempotent -- calling on an already-indexed field is a no-op. No explicit migration needed for existing collections.

#### Tags Storage

Currently stored as a comma-separated string (`"qdrant, java, cdi"`). Change to a Qdrant list value (JSON array `["qdrant", "java", "cdi"]`), enabling individual-tag filtering via `matchKeyword("tags", "qdrant")`.

#### MetadataExtractor SPI Change

The current data flow blocks list-valued storage:

1. `MetadataExtractor.extract()` → `ExtractionResult(body, Map<String, String>)` — tags as `String.join(", ", tagList)`
2. `ChunkInput(content, sourceDocumentId, Map<String, String> metadata)` — carries the joined string
3. `QdrantPointBuilder.buildPoint()` → `ValueFactory.value(String)` — stores as a single string

To store tags as a Qdrant list, both `ExtractionResult` and `ChunkInput` gain a `listMetadata()` accessor:

```java
// ExtractionResult — add overloaded constructor and accessor
public record ExtractionResult(String body, Map<String, String> metadata,
                                Map<String, List<String>> listMetadata) {
    public ExtractionResult(String body, Map<String, String> metadata) {
        this(body, metadata, Map.of());
    }
}

// ChunkInput — same pattern
public record ChunkInput(String content, String sourceDocumentId,
                          Map<String, String> metadata,
                          Map<String, List<String>> listMetadata) {
    public ChunkInput(String content, String sourceDocumentId, Map<String, String> metadata) {
        this(content, sourceDocumentId, metadata, Map.of());
    }
}
```

`GardenMetadataExtractor` returns tags in `listMetadata()` instead of joining them into a comma-separated string. `QdrantPointBuilder` stores list metadata via `ValueFactory.list(values.stream().map(ValueFactory::value).toList())`. The string `metadata()` map continues to work for all existing consumers unchanged.

**Migration**: Existing Qdrant points have comma-separated tag strings. Qdrant's `matchKeyword` does **exact** string matching — `matchKeyword("tags", "qdrant")` does NOT match the string `"qdrant, java, cdi"`. Tag filtering only works after a full re-index (via `CollectionMigration` or manual `gardenReindex()`). No seamless transition — deploy and re-index.

### 4. MCP Tool Enrichment

**Current**: `gardenSearch(query, domain, limit)`

**Proposed**: `gardenSearch(query, domain, type, tags, limit)`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | String | Yes | Natural language description of the problem or concept |
| `domain` | String | No | Filter by domain (jvm, tools, python, etc.) |
| `type` | String | No | Filter by entry type (gotcha, technique, undocumented, pattern) |
| `tags` | String | No | Comma-separated tags -- entries matching ANY tag are returned |
| `limit` | Integer | No | Max results (default 8, max 50) |

`SearchResource` constructs `PayloadFilter`:

```java
List<PayloadFilter> filters = new ArrayList<>();
if (domain != null) filters.add(PayloadFilter.eq("domain", domain));
if (type != null)   filters.add(PayloadFilter.eq("type", type));
if (tags != null)   filters.add(PayloadFilter.in("tags", splitTags(tags)));
PayloadFilter combined = filters.isEmpty() ? null : new PayloadFilter.And(filters);
```

## Cross-Repo Ownership

| Change | Repo | Module |
|--------|------|--------|
| `BM25Index` + CamelCase tokenizer | neural-text | `rag` |
| `BM25IndexRegistry` (corpus-scoped coordinator) | neural-text | `rag` |
| `BM25PayloadFilterEvaluator` (in-memory filter) | neural-text | `rag` |
| `ExtractionResult` -- add `listMetadata()` | neural-text | `rag-api` |
| `ChunkInput` -- add `listMetadata()` | neural-text | `rag-api` |
| `QdrantEmbeddingIngestor` -- BM25 index updates | neural-text | `rag` |
| `ReactiveQdrantEmbeddingIngestor` -- same | neural-text | `rag` |
| `HybridCaseRetriever` -- Java-side RRF via `RrfFusion.fuse()` | neural-text | `rag` |
| `ReactiveHybridCaseRetriever` -- same | neural-text | `rag` |
| `ensureCollection()` -- metadata indexes | neural-text | `rag` |
| Startup BM25 rebuild (@Priority 15) | neural-text | `rag` |
| Tags as list in `QdrantPointBuilder` | neural-text | `rag` |
| `GardenMcpTools` -- type/tags params | engine | `mcp` |
| `SearchResource` -- filter construction | engine | `search` |

Neural-text carries the bulk of the work. Engine changes are limited to MCP tool enrichment.

## Decorator Chain Interaction

BM25 results are transparent to the decorator chain. The retrieval pipeline is: query expansion → retrieval (dense + sparse + BM25 via RRF) → CRAG evaluation → optional reranking.

BM25-sourced chunks pass through `CrossEncoderRelevanceEvaluator` (CRAG) alongside vector-sourced chunks. The cross-encoder evaluates each chunk against the original query text regardless of retrieval method — it doesn't know or care how the chunk was retrieved. BM25-sourced chunks may have high keyword relevance but low semantic relevance; CRAG may grade them `INCORRECT`. This is correct behavior — it prevents keyword-matched but semantically irrelevant results from reaching the LLM. The cross-encoder acts as a quality gate that complements BM25's recall advantage.

## Testing Strategy

### BM25Index Tests (neural-text)

- **Tokenizer**: CamelCase splitting edge cases (`HTTPClient`, `BM25`, `IOThread`, `xml`, single char, empty string, Unicode)
- **Indexing**: add/remove/rebuild correctness; document count; term frequency
- **BM25 scoring**: known queries against known documents with expected rankings; IDF correctness (rare terms rank higher)
- **Thread safety**: concurrent read/write under contention
- **Rebuild from Qdrant**: mock scroll, verify index matches

### PayloadFilter Evaluator Tests (neural-text)

- **Eq**: exact match on single-valued metadata field
- **In (single-valued)**: field value is one of the specified values
- **In (list-valued)**: any element in the stored list matches any specified value
- **Not**: negation of inner predicate
- **And/Or**: composite predicates with short-circuit behavior
- **Missing field**: document with missing metadata field does not match `Eq` or `In`
- **Cross-corpus isolation**: filter evaluation scoped to correct corpus index

### RRF Fusion Tests (neural-text)

- **Single leg**: dense-only produces same results as current behavior
- **Two legs**: dense + BM25 produces correct fusion (verify RRF formula)
- **Three legs**: dense + sparse + BM25 with overlapping and non-overlapping results
- **Edge cases**: document found by only one leg; all legs return same document; empty results from one leg

### Integration Tests (engine)

- **MCP tool**: `gardenSearch` with type/tags filters returns filtered results
- **End-to-end**: keyword query finds documents that dense-only missed (the benchmark scenario)

### Benchmark Validation

Re-run the #27 benchmark methodology with the new three-leg retrieval. Compare against dense-only baseline. The key metric: **does BM25 close the gap between gardenSearch-KW and grep?**

## Platform Coherence

| Check | Status |
|-------|--------|
| Module tier structure | BM25Index internal to Tier 3 `rag`. No SPI leakage. |
| Qdrant client library protocol | All Qdrant via `io.qdrant:client`. No LangChain4j EmbeddingStore. |
| Consumer SPI placement | No new consumer-facing SPIs. CaseRetriever unchanged. |
| PLATFORM.md boundary | BM25 in neural-text, not engine. "Do not implement CBR retrieval logic in application repos." |
| PLATFORM.md capability | "Knowledge corpus retrieval (RAG)" owned by neural-text. BM25 extends it. |
| Optional module pattern | BM25 is part of `rag` (on by default). No external deps, trivial memory. Separate module adds complexity without benefit. |
| No backwards compatibility shims | RRF migration changes result ordering. Net improvement, not regression. |

## Dependencies

No new external dependencies. BM25 scoring, tokenization, and inverted index are implemented in ~200 lines of Java. The `rag` module's existing dependencies (Qdrant client, LangChain4j, quarkus-scheduler) are unchanged.
