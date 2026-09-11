# C7 RAG Pipeline — Design Spec

**Date:** 2026-06-09  
**Status:** Design approved — pending implementation  
**Branch:** `issue-7-rag-pipeline`  
**Tracking:** casehubio/neural-text#7, casehubio/parent#164  
**ARC42:** C7 (L6 RAG SPI, L7 RAG Runtime)

---

## What This Delivers

`rag-api`, `rag`, and `rag-testing` published. casehub application repos can ingest
documents into named, tenancy-isolated corpora and retrieve grounded context via
`CaseRetriever` for use in engine case steps and the typed fact space prompt compiler.
Hybrid dense+sparse search operates over Qdrant with server-side RRF fusion.
Optional `CrossEncoderReranker` enables precision mode.

---

## Module Roles

| Module | Tier | Purpose |
|--------|------|---------|
| `rag-api` | 1 — Pure Java, zero deps | `CorpusStore` SPI, `CaseRetriever` SPI, all value types |
| `rag` | 3 — Quarkus + Qdrant gRPC | `QdrantCorpusStore`, `QdrantCaseRetriever`, config, client lifecycle |
| `rag-testing` | Library | `@DefaultBean` in-memory stubs; no Qdrant, no JNI |

ArchUnit enforced in `rag-api`: zero LangChain4j, Quarkus, casehub domain dependencies.  
ArchUnit enforced in `rag`: only `casehub-platform-api` from casehub; no other casehub modules.

---

## rag-api — SPIs and Value Types

### Value types

```java
// Tenancy + corpus identity — mandatory on every SPI call
record CorpusRef(String tenantId, String corpusName) {
    public CorpusRef {
        Objects.requireNonNull(tenantId,    "tenantId required");
        Objects.requireNonNull(corpusName,  "corpusName required");
    }
}

// Document submitted for ingestion
// Caller is responsible for extracting text from binary formats before calling ingest().
// content is a UTF-8 text stream. mimeType is informational only — rag does not parse binary.
record IngestableDocument(
    InputStream          content,     // UTF-8 text
    String               mimeType,    // e.g. "text/plain", "text/markdown"
    String               externalId,  // caller-assigned; stable; used for delete
    Map<String, String>  metadata     // arbitrary tags; stored as Qdrant payload
) {}

// One retrieved chunk returned by CaseRetriever
record RetrievedChunk(
    String sourceExternalId,  // externalId of the source document
    String content,           // chunk text
    float  relevanceScore,    // RRF-fused score from Qdrant
    EmbeddingStrategy strategy
) {}

enum EmbeddingStrategy { DENSE, SPARSE, HYBRID }
```

### CorpusStore SPI

```java
public interface CorpusStore {
    void ingest(CorpusRef corpus, IngestableDocument document);
    void delete(CorpusRef corpus, String externalId);   // delete all chunks for this doc
    void deleteCorpus(CorpusRef corpus);                // delete all docs in corpus
}
```

`ingest()` is void — `externalId` is caller-assigned, not generated. Callers know
what they ingested and reference it for deletion.

### CaseRetriever SPI

```java
public interface CaseRetriever {
    List<RetrievedChunk> retrieve(String query, CorpusRef corpus);

    default List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults) {
        return retrieve(query, corpus);  // implementations override for maxResults support
    }
}
```

The default method allows implementations to override `retrieve(query, corpus, maxResults)`
without forcing every caller to supply a maxResults argument. The real implementation
overrides both; the default is a compile-time convenience only.

---

## rag — Quarkus Implementation

### CDI injection points (consumer-provided)

`rag` does not own model construction. These beans must be present on the classpath:

| Bean type | Source | Notes |
|-----------|--------|-------|
| `EmbeddingModel` (langchain4j-core) | Consumer | Dense embedding — any implementation |
| `SparseEmbedder` (inference-splade) | Consumer | Wraps an `InferenceModel` |
| `Instance<CrossEncoderReranker>` (inference-tasks) | Consumer (optional) | Absent = RRF-only mode |

Consumers provide these via `@Produces @ApplicationScoped` methods in their own CDI producers.
`rag` injects them — it does not configure or construct them.

### Configuration

```
@ConfigMapping(prefix = "casehub.rag.qdrant")
```

| Property | Default | Notes |
|----------|---------|-------|
| `host` | `localhost` | Qdrant gRPC host |
| `port` | `6334` | Qdrant gRPC port |
| `use-tls` | `false` | TLS for Qdrant Cloud |
| `api-key` | _(empty)_ | Optional; for Qdrant Cloud |
| `chunk-size` | `512` | Characters per chunk |
| `chunk-overlap` | `50` | Overlap between adjacent chunks |
| `max-results` | `10` | Default for two-arg `retrieve()` |
| `rerank-top-n` | `3` | Top-N after reranking (if reranker present) |

### QdrantClientProducer

`@ApplicationScoped` CDI producer. Builds a `QdrantClient @Singleton` from `QdrantConfig`
at startup. Closes the client on `@Observes ShutdownEvent`.

### Qdrant collection topology

- **One collection per `corpusName`**, shared across tenants.
  Collection name = `corpusName` value from `CorpusRef`.
- **Named vector spaces:** `dense` (float[], dimension from `EmbeddingModel.dimension()`)
  and `sparse` (Qdrant `SparseVectorParams`).
- **Payload fields:** `tenantId`, `externalId`, `chunkIndex`, `content`, plus all
  `metadata` entries from `IngestableDocument`.
- **tenantId filter:** applied unconditionally on every read and write operation —
  no conditional bypass regardless of deployment mode. This is not negotiable.

Collections are created on first ingest if absent (`createCollectionIfNotExists()`).

### QdrantCorpusStore

`@ApplicationScoped`, implements `CorpusStore`. Injects `EmbeddingModel`, `SparseEmbedder`,
`QdrantClient`, `QdrantConfig`, `CurrentPrincipal`.

**`ingest(corpus, document)` flow:**
1. Assert `corpus.tenantId()` matches `currentPrincipal.tenancyId()` — `IllegalArgumentException` if not.
2. Read `content` InputStream as UTF-8 string.
3. Chunk with `FixedSizeChunker` (internal; no external dep — splits by `chunk-size` chars
   with `chunk-overlap` overlap, respecting word boundaries).
4. For each chunk: compute `denseVector = embeddingModel.embed(TextSegment.from(chunk)).content().vector()`,
   compute `sparseVector = sparseEmbedder.embed(chunk)`.
5. Upsert all chunks in a single `QdrantClient.upsertPoints()` call. Each point:
   id = random UUID, named vectors `{dense, sparse}`, payload `{tenantId, externalId, chunkIndex, content, ...metadata}`.

**`delete(corpus, externalId)` flow:**
Delete all points where `tenantId == corpus.tenantId() AND externalId == externalId`.

**`deleteCorpus(corpus)` flow:**
Delete all points where `tenantId == corpus.tenantId()`.

### QdrantCaseRetriever

`@ApplicationScoped`, implements `CaseRetriever`. Injects `EmbeddingModel`, `SparseEmbedder`,
`Instance<CrossEncoderReranker>`, `QdrantClient`, `QdrantConfig`, `CurrentPrincipal`.

**`retrieve(query, corpus, maxResults)` flow:**
1. Assert tenancy.
2. `denseVector = embeddingModel.embed(TextSegment.from(query)).content().vector()`
3. `sparseVector = sparseEmbedder.embed(query)`
4. Issue single Qdrant gRPC `query()` using native hybrid search:
   ```
   prefetch: [
     { query: denseVector, using: "dense", limit: prefetchLimit },
     { query: sparseVector, using: "sparse", limit: prefetchLimit }
   ],
   query:  { fusion: RRF },
   filter: { must: [{ key: "tenantId", match: { value: corpusRef.tenantId() } }] },
   limit:  rerankerPresent ? config.rerankTopN() * 4 : maxResults
   ```
   where `prefetchLimit = max(20, maxResults * 4)`.
5. Map Qdrant results → `List<RetrievedChunk>` (`strategy = HYBRID`).
6. If `!reranker.isUnsatisfied()`:
   - Extract texts: `List<String> texts = chunks.stream().map(RetrievedChunk::content).toList()`
   - `List<RankedResult> ranked = reranker.get().rerank(query, texts)`
   - Re-sort `chunks` by `RankedResult.score()` (using `originalIndex` to correlate), take top `rerank-top-n`.
7. Return results.

RRF fusion is Qdrant-native — no manual rank merging, single network round-trip.

---

## rag-testing — In-Memory Stubs

Both stubs are `@DefaultBean @ApplicationScoped`. They yield to `QdrantCorpusStore` /
`QdrantCaseRetriever` when `casehub-rag` is on the classpath, per the
persistence-backend-cdi-priority protocol. Tests that include only `casehub-rag-testing`
get the stubs automatically — no Qdrant, no JNI required.

### InMemoryCorpusStore

Stores ingested documents in `ConcurrentHashMap<CorpusRef, Map<String, String>>`
(corpusRef → externalId → full text). Reads InputStream as UTF-8 on `ingest()`.
No chunking, no embedding — one entry per document.

`delete()` removes by externalId. `deleteCorpus()` removes the corpus map entirely.

### InMemoryCaseRetriever

Two factory modes:

```java
// Mode 1 — return all docs stored in the in-memory corpus (default)
InMemoryCaseRetriever.returningAll(InMemoryCorpusStore store)

// Mode 2 — return a fixed list for any query (unit test control)
InMemoryCaseRetriever.returning(List<RetrievedChunk> fixed)
```

`returningAll()` pulls from `InMemoryCorpusStore` and returns all stored content as
`RetrievedChunk` objects with `relevanceScore = 1.0f` and `strategy = HYBRID`.

`returning(fixed)` ignores the corpus and returns the pre-configured list for every
`retrieve()` call — the primary test tool for asserting downstream consumer behaviour
given specific retrieval results.

---

## Tenancy Invariants

- `CorpusRef.tenantId()` is validated against `CurrentPrincipal.tenancyId()` at the
  start of every `CorpusStore` and `CaseRetriever` call. Mismatch = hard fail.
- `tenantId` is a Qdrant payload filter on every read and write — applied unconditionally.
- `CorpusRef` is an internal SPI parameter between casehub services. It does not appear
  in any REST DTO, JSON schema, or client-facing API surface.

---

## Deferred (GitHub issues to file)

| Item | Reason deferred |
|------|----------------|
| Reactive `CorpusStore` / `CaseRetriever` variants | Follow `reactive-service-build-gating` protocol; needs separate chapter |
| LangChain4j #4994 migration | When native Qdrant hybrid search ships in LangChain4j, migrate from direct gRPC |
| `casehub-neural-text.md` deep-dive update in parent | Current state reflects C3; needs C7 update |
| PLATFORM.md cross-dependency table rows for `casehub-rag-api` | engine + eidos will consume it; rows needed |

---

## Protocols Referenced

| Protocol | Where it applies |
|----------|-----------------|
| `module-tier-structure` | rag-api Tier 1, rag Tier 3, rag-testing library |
| `consumer-spi-placement` | CorpusStore + CaseRetriever live in rag-api |
| `persistence-backend-cdi-priority` | @DefaultBean stubs in rag-testing; @ApplicationScoped in rag |
| `no-conditional-tenancy-filtering` | tenantId filter always applied |
| `tenancyid-server-side-only` | CorpusRef is internal SPI, not client-facing |
| `optional-module-pattern` | rag-testing @DefaultBean yields to rag when present |