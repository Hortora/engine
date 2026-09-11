# RAG Pipeline Design — casehub-neural-text #7

**Date:** 2026-06-08
**Status:** Approved — pending implementation
**Issue:** casehubio/neural-text#7
**Platform tracking:** casehubio/parent#164

---

## What This Delivers

`rag-api`, `rag`, `rag-tika`, and `rag-testing` published. CaseHub application repos can define named corpora in configuration, ingest documents via `CorpusStore`, and retrieve grounded context via `CaseRetriever`. Hybrid dense+sparse search operates across tenancy-isolated Qdrant collections. Optional cross-encoder reranking for precision mode.

---

## Module Structure

```
rag-api/       Pure Java, zero deps.
               CorpusStore SPI, CaseRetriever SPI, CorpusRef, ChunkInput, RetrievedChunk.
               Package: io.casehub.rag
               (matches inference-api convention: io.casehub.inference)
               Zero-deps for quality and testability — same reason inference-api
               is zero-deps. Not shared with Hortora (see Hortora Boundary below).

rag/           Quarkus library JAR (Jandex-indexed, not a Quarkus extension).
               QdrantCorpusStore, HybridCaseRetriever, TenancyStrategy, QdrantConfig.
               Package: io.casehub.rag.runtime

rag-tika/      NEW MODULE (not in original scaffold — must be created and added
               to parent pom <modules>). Optional. LangChain4j Tika parser →
               List<ChunkInput>. Callers add as compile dep to activate.
               Package: io.casehub.rag.tika

rag-testing/   In-memory CorpusStore + CaseRetriever stubs.
               @Alternative @Priority(1). No Qdrant, no embeddings.
               Package: io.casehub.rag.testing
```

### Dependencies

| Module | Depends on |
|--------|-----------|
| `rag-api` | nothing |
| `rag` | `rag-api`, `inference-splade`, `inference-tasks`, `casehub-platform-api`, `quarkus-arc`, `langchain4j-core`, `io.qdrant:client` |
| `rag-tika` | `rag-api`, `langchain4j`, `langchain4j-document-parser-apache-tika` |
| `rag-testing` | `rag-api`, `jakarta.enterprise.cdi-api` (provided) |

### POM changes required at implementation time

The scaffolded POMs predate this spec. The following changes are needed:

| File | Change | Reason |
|------|--------|--------|
| `pom.xml` (parent) | Add `<module>rag-tika</module>` | New module |
| `rag/pom.xml` | Remove `langchain4j-qdrant` | Unified Qdrant client — langchain4j-qdrant not used |
| `rag/pom.xml` | Remove `langchain4j-document-parser-apache-tika` | Moved to `rag-tika` |
| `rag/pom.xml` | Remove `casehub-inference-quarkus` | App provides SparseEmbedder/CrossEncoderReranker; rag does not own CDI model resolution |
| `rag/pom.xml` | Replace `langchain4j` with `langchain4j-core` | Only `EmbeddingModel`, `Embedding`, `TextSegment` needed — all in `langchain4j-core` |
| `rag/pom.xml` | Remove `langchain4j-embeddings` | `EmbeddingModel` is in `langchain4j-core`, not `langchain4j-embeddings` |

### What was dropped and why

**`langchain4j-qdrant` removed.** LangChain4j does not support Qdrant hybrid search with named vector spaces. The generic hybrid search feature request ([langchain4j#4087](https://github.com/langchain4j/langchain4j/issues/4087)) was closed — resolved via PR #4124 for store-agnostic hybrid retrieval. But the Qdrant-specific implementation ([langchain4j#4994](https://github.com/langchain4j/langchain4j/issues/4994) — "Support Hybrid Search for Qdrant vector store") remains open with no timeline. Both dense and sparse legs use the Qdrant Java gRPC client (`io.qdrant:client`) directly for a unified architecture.

**`casehub-inference-quarkus` not a dependency of `rag`.** The `@Inference` qualifier uses `@Nonbinding` value with `InjectionPoint` dispatch — CDI resolves all `@Inference`-qualified injection points through a single producer. Making `CrossEncoderReranker` optional via `Instance<>` is trivial when the app provides the bean; it requires solving the "unconfigured model throws at CDI wiring time" problem if `rag` injects `@Inference`-qualified models directly. The app-provides pattern is cleaner: 6 lines of producer code per consuming app, full control over model selection, no transitive ONNX Runtime JNI forced onto `rag` consumers.

LangChain4j is still used for: `EmbeddingModel` (dense embedding generation), `Embedding`/`TextSegment` types (in `langchain4j-core`), `DocumentSplitter` + `ApacheTikaDocumentParser` (in `rag-tika`).

---

## rag-api Types

All types are records or interfaces. Zero external dependencies.

### CorpusRef

Identifies a tenant-scoped corpus. Mandatory on all SPI calls — no ambient tenancy.

```java
public record CorpusRef(String tenantId, String corpusName) {
    public CorpusRef {
        if (tenantId == null || tenantId.isBlank())
            throw new IllegalArgumentException("tenantId must not be null or blank");
        if (corpusName == null || corpusName.isBlank())
            throw new IllegalArgumentException("corpusName must not be null or blank");
    }
}
```

### ChunkInput

Pre-chunked text with metadata. What callers pass to ingest.

```java
public record ChunkInput(String content, String sourceDocumentId, Map<String, String> metadata) {
    public ChunkInput {
        if (content == null || content.isBlank())
            throw new IllegalArgumentException("content must not be null or blank");
        if (sourceDocumentId == null || sourceDocumentId.isBlank())
            throw new IllegalArgumentException("sourceDocumentId must not be null or blank");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
```

### RetrievedChunk

What retrieval returns. Simple relevance score — no per-leg provenance. After RRF fusion, every result is hybrid; the fused score is what matters for ranking.

```java
public record RetrievedChunk(String content, String sourceDocumentId,
                             double relevanceScore, Map<String, String> metadata) {
    public RetrievedChunk {
        if (content == null)
            throw new IllegalArgumentException("content must not be null");
        if (sourceDocumentId == null)
            throw new IllegalArgumentException("sourceDocumentId must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
```

### CorpusStore

Write-oriented SPI. Ingest pre-chunked content, delete documents, manage corpora.

```java
public interface CorpusStore {
    void ingest(CorpusRef corpus, List<ChunkInput> chunks);
    void deleteDocument(CorpusRef corpus, String sourceDocumentId);
    void deleteCorpus(CorpusRef corpus);
    List<String> listDocuments(CorpusRef corpus);
}
```

### CaseRetriever

Read-oriented SPI. Retrieval entry point for engine case steps and the fact space prompt compiler.

```java
public interface CaseRetriever {
    List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults);
}
```

### Design decisions

- `CorpusStore.ingest()` accepts pre-chunked `ChunkInput`, not raw documents. Callers chunk themselves or use `rag-tika`. Keeps Tika dependency optional.
- `CaseRetriever.retrieve()` takes `maxResults` as a parameter — different case steps may want different result counts.
- Separate SPIs for write (CorpusStore) and read (CaseRetriever). Different implementations, different CDI beans, different lifecycle concerns.
- `CorpusRef` is the tenancy enforcement point. Every SPI method requires it explicitly.

---

## rag Runtime

### Tenancy validation

`QdrantCorpusStore` and `HybridCaseRetriever` are data access classes. Per the platform tenancy protocol (`casehub/garden: docs/protocols/casehub/tenancy-repository-pattern.md`), they validate tenancy internally — never trust the caller.

Both beans inject `CurrentPrincipal` and call `MemoryPermissions.assertTenant(corpusRef.tenantId(), currentPrincipal)` at the top of every operation. This is the same pattern used by all `CaseMemoryStore` adapters (`casehub/garden: docs/protocols/casehub/casememorystore-adapter-asserttenant-contract.md`). A caller that constructs `CorpusRef` with a tenant ID that doesn't match the authenticated principal gets `SecurityException`.

This is why `rag` depends on `casehub-platform-api` — not as a future placeholder, but for active defense-in-depth tenancy validation at every data access point.

### QdrantConfig

`@ConfigMapping(prefix = "casehub.rag")` for Qdrant connection and pipeline tuning.

```
casehub.rag.qdrant.host = localhost
casehub.rag.qdrant.port = 6334
casehub.rag.qdrant.api-key =            # optional
casehub.rag.qdrant.use-tls = false

casehub.rag.tenancy-strategy = SEPARATE_COLLECTIONS   # or SHARED_COLLECTION
casehub.rag.dense-vector-name = dense
casehub.rag.sparse-vector-name = sparse

casehub.rag.retrieval.dense-top-k = 40
casehub.rag.retrieval.sparse-top-k = 40
casehub.rag.retrieval.rrf-k = 60
casehub.rag.retrieval.rerank-enabled = true
casehub.rag.retrieval.rerank-top-n = 10
```

### TenancyStrategy

Maps `CorpusRef` to Qdrant collection name + optional filter. Configurable — operational decision, not architectural.

**SEPARATE_COLLECTIONS (default):**
- Collection name: `{tenantId}_{corpusName}`
- Filter: none (isolation by collection boundary)
- `deleteCorpus` → delete entire collection

**SHARED_COLLECTION:**
- Collection name: `{corpusName}`
- Filter: `must[match("tenantId", ref.tenantId())]` on every query and delete
- `deleteCorpus` → delete all points matching tenantId filter

### QdrantCorpusStore

`@ApplicationScoped`. Implements `CorpusStore`. Injects `CurrentPrincipal`.

**Ingest pipeline:**
1. Validate tenancy: `MemoryPermissions.assertTenant(corpus.tenantId(), currentPrincipal)`
2. Resolve collection name via `TenancyStrategy`
3. Ensure collection exists — create with two named vector spaces if absent (see Collection auto-creation below)
4. Batch embed all chunks: dense via `EmbeddingModel.embedAll()`, sparse via `SparseEmbedder.embedBatch()`
5. Build Qdrant `PointStruct` per chunk — UUID id, both named vectors, payload containing `content`, `sourceDocumentId`, `tenantId`, and all `metadata` entries
6. Batch upsert to Qdrant

**Collection auto-creation:** On first `ingest()` to a new corpus, the collection is created with two named vector spaces: `dense` (float vector) and `sparse` (sparse vector). The dense vector dimension is discovered via `EmbeddingModel.dimension()` — LangChain4j's default implementation embeds the string `"test"` and reads the result vector length. This costs one extra embedding call, once per model lifetime (`DimensionAwareEmbeddingModel` caches the result). First-ingest latency includes this dimension discovery + collection creation (typically 50–200ms total). Subsequent ingests to the same corpus skip both. Explicit pre-creation at app startup is not provided in v1.

**Delete operations:**
- `deleteDocument`: validate tenancy, scroll + delete by `sourceDocumentId` payload filter (+ tenantId filter in shared mode)
- `deleteCorpus`: validate tenancy, delete collection (separate mode) or delete all points by tenantId filter (shared mode)
- `listDocuments`: validate tenancy, scroll with payload filter, extract distinct `sourceDocumentId` values

### HybridCaseRetriever

`@ApplicationScoped`. Implements `CaseRetriever`. Injects `CurrentPrincipal`.

**Retrieval pipeline:**
1. Validate tenancy: `MemoryPermissions.assertTenant(corpus.tenantId(), currentPrincipal)`
2. Resolve collection name + filter via `TenancyStrategy`
3. Embed query: dense via `EmbeddingModel.embed()`, sparse via `SparseEmbedder.embed()`
4. Issue Qdrant Query API request (gRPC):
   - Prefetch 1: dense nearest-neighbor search, top-K results
   - Prefetch 2: sparse nearest-neighbor search, top-K results
   - Fusion: RRF with configurable k constant
   - Limit: `maxResults` (or `rerank-top-n` candidates if reranking enabled)
5. Map Qdrant scored points → `RetrievedChunk` list
6. If reranking enabled and `CrossEncoderReranker` available: rerank candidates, re-sort by cross-encoder score, truncate to final `maxResults`
7. Return `List<RetrievedChunk>` sorted by descending relevance

### CDI injection

Both beans inject:
- `CurrentPrincipal` — for tenancy validation via `MemoryPermissions.assertTenant()`
- `QdrantClient` — produced internally from `QdrantConfig`
- `EmbeddingModel` — provided by the consuming app
- `SparseEmbedder` — provided by the consuming app
- `Instance<CrossEncoderReranker>` — optional, provided by the consuming app if reranking is desired
- `TenancyStrategy` — produced internally from config

---

## rag-tika

NEW MODULE — not in the original scaffold. Must be created during implementation: directory, pom.xml, parent pom `<modules>` entry.

Optional module. `@ApplicationScoped` CDI bean.

```java
public class TikaDocumentParser {
    List<ChunkInput> parse(InputStream content, String sourceDocumentId,
                           String contentType, Map<String, String> metadata);
}
```

Pipeline:
1. `ApacheTikaDocumentParser` (LangChain4j) parses InputStream → `Document`
2. `DocumentSplitter` (recursive character, configurable chunk size + overlap) splits → `List<TextSegment>`
3. Maps each segment → `ChunkInput` with `sourceDocumentId` and merged metadata (Tika-extracted + caller-provided)

Configuration:
```
casehub.rag.tika.chunk-size = 512
casehub.rag.tika.chunk-overlap = 64
```

Callers add `casehub-rag-tika` as compile dep to activate. Without it, `CorpusStore.ingest()` still works — callers provide their own `List<ChunkInput>`.

---

## rag-testing

Deterministic in-memory stubs. Both `@Alternative @Priority(1)`.

**`InMemoryCorpusStore`** — `Map<CorpusRef, Map<String, List<ChunkInput>>>`. All operations in-memory. Displaces `QdrantCorpusStore` on test classpath.

**`InMemoryCaseRetriever`** — two modes:
- Default: backed by an `InMemoryCorpusStore` instance (injected). Returns stored chunks for the corpus as `RetrievedChunk` (content + sourceDocumentId from the `ChunkInput`, relevanceScore = 1.0), insertion order, truncated to `maxResults`. No embedding, no scoring. Tests that use both stubs together get consistent ingest → retrieve behavior.
- Programmatic: `InMemoryCaseRetriever.returning(List<RetrievedChunk>)` — factory method producing a standalone instance with fixed responses regardless of query. For tests that need controlled retrieval without ingesting.

Consumer test classpath:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-rag-testing</artifactId>
    <scope>test</scope>
</dependency>
```

---

## Consumer Integration

`rag` is a library JAR, not a Quarkus extension. The consuming Quarkus app provides:

| Bean | How | Source |
|------|-----|--------|
| `EmbeddingModel` | App-level CDI producer (quarkus-langchain4j ONNX, remote API, etc.) | App |
| `SparseEmbedder` | App produces from `@Inference("splade")` model via `inference-quarkus` | App |
| `CrossEncoderReranker` | App produces from `@Inference("cross-encoder")` model | App (optional) |
| `QdrantClient` | Produced by `rag` module from `QdrantConfig` | `rag` |
| `TenancyStrategy` | Produced by `rag` module from config | `rag` |

**Why the app provides SparseEmbedder and CrossEncoderReranker (not rag internally):**

`inference-quarkus` uses `@Inference` with `@Nonbinding` value — all `@Inference`-qualified injection points route through a single `InferenceModelProducer` that extracts the model name at runtime via `InjectionPoint`. This works well for required models but makes optional injection (CrossEncoderReranker when reranking is disabled) problematic: `InferenceModelProducer.createModel()` throws `IllegalStateException` for unconfigured model names, and CDI wiring fails before any conditional logic can run.

The app-provides pattern avoids this: `rag` injects `Instance<CrossEncoderReranker>` (standard CDI optional injection). The app either provides the bean or doesn't. No special handling needed.

Example app producers (6 lines total):
```java
@Produces @ApplicationScoped
SparseEmbedder sparseEmbedder(@Inference("splade") InferenceModel model) {
    return new SparseEmbedder(model);
}

@Produces @ApplicationScoped
CrossEncoderReranker reranker(@Inference("cross-encoder") InferenceModel model) {
    return new CrossEncoderReranker(model);
}
```

This follows the platform's established pattern: `rag` provides the runtime, the app provides the configuration and infrastructure beans.

---

## Hortora Boundary

The sharing boundary with Hortora is the `inference-*` modules only — `inference-api`, `inference-runtime`, `inference-tasks`, `inference-splade`, `inference-inmem`. These have zero casehub dependencies (ArchUnit enforced) and are designed for cross-project consumption.

`rag-api`, despite being zero-deps, is NOT shared with Hortora. `CaseRetriever` embeds the "Case" domain concept — Hortora has no cases. The zero-deps design is for architectural quality and testability (the same reason `inference-api` is zero-deps), not for cross-project sharing.

`rag` is casehub-specific — depends on `casehub-platform-api` for tenancy validation. Hortora writes their own retrieval SPIs, their own Qdrant wiring, and their own tenancy model.

The design brief confirms this: "No code is shared between the two LangChain4j wiring layers."

---

## ArchUnit Constraints

- `rag-api`: zero deps on Quarkus, LangChain4j, Qdrant, casehub domain types. Same enforcement pattern as `inference-api`.
- `rag-testing`: no Qdrant client, no LangChain4j embeddings. Pure Java + `rag-api` only.
- `rag`: no casehub domain types beyond `casehub-platform-api` (`CurrentPrincipal`, `MemoryPermissions` — used for tenancy validation).

---

## What This Does NOT Cover

- Qdrant server deployment / DevServices (future: `inference-quarkus` Dev Services could manage Qdrant lifecycle)
- Fact space prompt compiler integration (casehub-engine concern — consumes `CaseRetriever` SPI)
- Dense embedding model selection (app-level concern)
- SPLADE / cross-encoder model selection (app-level concern)
- Native image support for Qdrant client (not a gate — Qdrant client is pure Java/gRPC)
- Explicit collection pre-creation at startup (first-ingest auto-creates; explicit warmup deferred to future need)