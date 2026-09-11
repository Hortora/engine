# The RAG Pipeline That Dropped Its Framework

The original plan was LangChain4j all the way down — Tika for parsing, `OnnxEmbeddingModel` for dense embeddings, `QdrantEmbeddingStore` for storage, `EmbeddingStoreContentRetriever` for retrieval. A clean stack. One framework, one abstraction layer.

Then hybrid search happened.

Qdrant supports named vector spaces — store both dense and sparse vectors per point, query them independently, fuse with RRF. LangChain4j's `QdrantEmbeddingStore` does not. There's an [open issue](https://github.com/langchain4j/langchain4j/issues/4994) for it. No timeline. The generic hybrid search feature (#4087) shipped via PR #4124, but the Qdrant-specific implementation — named vectors, prefetch, fusion — remains a draft.

So the design question became: use `langchain4j-qdrant` for the dense leg and the direct Qdrant gRPC client for the sparse leg? Two clients, two code paths, one Qdrant instance. That split felt wrong from the start. If we need the Qdrant client for the sparse leg and for RRF fusion queries, why not use it for both?

We dropped `langchain4j-qdrant` entirely. One client, one code path. LangChain4j stays for what it's actually good at — `EmbeddingModel` (generating dense vectors), `DocumentSplitter` (chunking), and core types. The Qdrant gRPC client handles everything else: collection creation with named vector spaces, point upserts with both dense and sparse vectors, and the Query API with prefetch + fusion.

The unified architecture is cleaner. The Qdrant `QueryPoints` builder takes two prefetch legs (dense nearest-neighbor, sparse nearest-neighbor), fuses them with `Rrf.newBuilder().setK(60).build()`, and returns scored points. One call, both legs, server-side fusion. No client-side rank merging, no impedance mismatch between two client libraries.

## Tenancy as a configurable strategy

The spec called for tenant-isolated Qdrant collections. But the isolation model should be an operational decision, not an architectural one. Two strategies, selectable by config:

**SEPARATE_COLLECTIONS** — one Qdrant collection per tenant-corpus pair. Hard physical isolation. No filter needed. Default.

**SHARED_COLLECTION** — one collection per corpus name, tenancy via payload filter on every query. Simpler ops, more efficient for many small tenants.

`TenancyStrategy` is an enum that maps `CorpusRef(tenantId, corpusName)` to a collection name and an optional filter. The runtime code doesn't branch on strategy — it asks the strategy for the collection name and the filter, applies both unconditionally, and moves on.

Defense-in-depth: both `QdrantCorpusStore` and `HybridCaseRetriever` call `MemoryPermissions.assertTenant()` at every entry point, same as the `CaseMemoryStore` adapters in casehub-platform. A bug in a caller constructing `CorpusRef` with the wrong tenant throws `SecurityException` before any Qdrant operation.

## Four modules

The delivery is four modules, built in dependency order:

**rag-api** — pure Java, zero deps. `CorpusStore` (write), `CaseRetriever` (read), `CorpusRef`, `ChunkInput`, `RetrievedChunk`. ArchUnit enforced: no Quarkus, no LangChain4j, no Qdrant, no casehub domain types. Same pattern as `inference-api`.

**rag-testing** — in-memory stubs. `InMemoryCorpusStore` and `InMemoryCaseRetriever` with `@Alternative @Priority(1)`. Two retriever modes: store-backed (ingest then retrieve) and programmatic (`returning()` with fixed responses).

**rag** — the runtime. `QdrantCorpusStore` ingests via batch embedding (`EmbeddingModel.embedAll()` + `SparseEmbedder.embedBatch()`), auto-creates collections with named vector spaces on first ingest. `HybridCaseRetriever` runs prefetch + RRF + optional `CrossEncoderReranker`.

**rag-tika** — optional Apache Tika parsing. Parses InputStream to chunked `ChunkInput` via Tika's `AutoDetectParser` + LangChain4j's recursive `DocumentSplitter`. Add as a compile dep to activate; without it, callers provide their own chunks.

The app provides `EmbeddingModel`, `SparseEmbedder`, and optionally `CrossEncoderReranker` as CDI beans. The rag module doesn't own model selection — it injects what the app gives it.

## What the code review caught

The initial implementation had `@ApplicationScoped` on `QdrantCorpusStore` and `HybridCaseRetriever` — classes that are also produced by `RagBeanProducer` via `@Produces @ApplicationScoped`. CDI sees two beans of the same type. The class-level bean fails because its constructor takes `String` parameters CDI can't inject. The tests passed because they use Testcontainers with direct constructor calls — no CDI container.

Fix: remove `@ApplicationScoped` and `@Inject` from both classes. They're POJOs constructed by the producer, not CDI beans.

Also caught: the `rrfK` field was stored but never passed to the Qdrant API. `QueryFactory.fusion(Fusion.RRF)` uses server defaults. `QueryFactory.rrf(Rrf.newBuilder().setK(rrfK).build())` is the factory that accepts a custom k. The two methods look like aliases until you read the protobuf definition.
