# CLAUDE.md

## Project Type

**Type:** java

## Repository Purpose

**engine** — the Hortora garden retrieval service. A Quarkus application that indexes garden entries into Qdrant and exposes them via a MCP server for AI assistant consumption.

Phase 1: dense-only retrieval — LangChain4j + Qdrant + Ollama + MCP HTTP server.
Phase 2: hybrid search — SPLADE sparse embeddings + cross-encoder reranking via `casehub-inference-quarkus` (ONNX Runtime).
Phase 3: three-leg retrieval — BM25 keyword matching via Qdrant Document vectors as a third RRF leg.
Phase 4 (current): BGE-M3 adoption — single ONNX model producing dense (1024-dim) + sparse (learned lexical) + ColBERT (multi-vector reranking) from one forward pass. Replaces nomic-embed-text + SPLADE + cross-encoder. BM25 stays as complementary lexical leg.

## Stack

- **Quarkus 3.36.x** — runtime
- **casehub-rag** — neural-text RAG module (`CaseRetriever`, `EmbeddingIngestor`, `CorpusIngestionService`); Qdrant integration via `casehub-rag`, not direct client
- **casehub-inference-quarkus** — ONNX inference CDI wiring; engine bridges `@Inference("bge-m3")` to `MultiModalEmbedder` via `BgeM3Embedder`
- **casehub-inference-bge-m3** — `BgeM3Embedder` producing dense + sparse + ColBERT from BGE-M3 ONNX model
- **casehub-corpus-api + casehub-corpus** — filesystem change detection (`FlatChangeSource`, `WatchableChangeSource`)
- **BGE-M3** — single ONNX embedding model (550M params, 1024-dim dense, learned sparse, ColBERT multi-vector)
- **MCP server** — `quarkus-mcp-server-http` (long-running, SSE/HTTP transport)
- **Java 25**

## Key Design Decisions

- **BGE-M3 via MultiModalEmbedder** — `HybridSearchProducer` produces a single `MultiModalEmbedder` bean from `@Inference("bge-m3")` via `@LookupIfProperty`; non-resolvable when the ONNX model path isn't configured. `MultiModalEmbedder` replaces the previous `EmbeddingModel` + `SparseEmbedder` + `CrossEncoderReranker` triple. `CollectionMigration` detects dimension mismatch (768→1024) or missing ColBERT config and triggers re-indexing.
- **Incremental re-indexing** — cursor-based change detection via `FlatChangeSource` (directory-watcher); live filesystem watching after startup sync
- **neural-text RAG delegation** — ingestion and retrieval via `casehub-rag` SPIs (`EmbeddingIngestor`, `CaseRetriever`); engine provides `CorpusIngestionBinding` via CDI, neural-text handles Qdrant lifecycle, collection schema, and cursor management
- **Fixed tenant ID** — `CorpusRef("hortora", gardenConfig.id())`; collection name `hortora_garden` under `SEPARATE_COLLECTIONS` tenancy strategy
- **Long-running service, not stdio** — Qdrant loads its index once; stdio per-session cold-start is unacceptable at corpus scale
- **Garden entries are the chunks** — no document splitting; entries (50–200 lines) are the retrieval unit
- **Federation in this service** — canonical/child chain walk is Hortora-specific logic, lives here not in any shared module
- **Four-signal retrieval with ColBERT reranking** — `HybridCaseRetriever` uses three server-side RRF prefetch legs: dense (BGE-M3 1024-dim), sparse (BGE-M3 learned lexical), and BM25 (Qdrant Document vectors with `qdrant/bm25` model). ColBERT MAX_SIM rescores the RRF results via Qdrant multi-vectors — replaces client-side cross-encoder reranking. BM25 stays as complementary lexical leg; `CamelCaseExpander` preprocesses text for BM25 at ingestion time.

## Build

```bash
./mvnw verify                          # JVM tests
./mvnw quarkus:dev                     # dev mode with live reload + Dev Services
```

CI runs JVM tests on every push. JVM is the production deployment mode — long-running services benefit from HotSpot JIT over AOT.

## Deployment Mode — JVM by Design

The engine is a long-running service — native image's fast startup provides no benefit, and HotSpot's JIT optimisation outperforms AOT for sustained workloads. Deploys in JVM mode. AI assistants consume the engine via MCP (SSE/HTTP) — no separate CLI client needed.

## Dev Services

In tests, `casehub-rag-testing` provides `InMemoryCaseRetriever` and `InMemoryEmbeddingIngestor` (`@Alternative @Priority(1)`, requires `quarkus.index-dependency` in test properties). `TestInferenceModelProducer` routes `@Inference("bge-m3")` to `InMemoryInferenceModel.returningMulti()` — no ONNX Runtime or real models needed. In dev mode, start Qdrant manually: `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant`. Run `scripts/export_bge_m3.py` to produce the BGE-M3 ONNX model (one-time, ~2.2GB download + export), then `scripts/download-models.sh` to verify checksums, then uncomment the `%dev` model paths in `application.properties`.

## Project Artifacts

Paths that are project content (not workspace noise).

| Path | What it is |
|------|------------|
| `CLAUDE.md` | Project conventions (build, test, naming) |
| `docs/adr/` | Architecture decision records |
| `docs/DESIGN.md` | Design document |
| `docs/superpowers/specs/` | Design specs |
| `docs/superpowers/plans/` | Implementation plans |
| `scripts/` | Development scripts (ONNX model download, benchmark harness) |
| `docs/comparison/` | Retrieval benchmark reports (#27 dense-only, #28 hybrid) |

## Work Tracking

Issue tracking: enabled
GitHub repo: Hortora/engine
All commits reference an issue — `Refs #N` (ongoing) or `Closes #N` (done).
