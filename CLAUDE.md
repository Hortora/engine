# CLAUDE.md

## Project Type

**Type:** java

## Repository Purpose

**engine** — the Hortora garden retrieval service. A Quarkus LangChain4j application that indexes garden entries into Qdrant and exposes them via a MCP server for AI assistant consumption.

Phase 1: dense-only retrieval — LangChain4j + Qdrant + Ollama + MCP HTTP server.
Phase 2 (current): hybrid search — SPLADE sparse embeddings + cross-encoder reranking via `casehub-inference-quarkus` (ONNX Runtime). Dense+sparse RRF fusion and reranking activate when ONNX models are configured.

## Stack

- **Quarkus 3.36.x** — runtime
- **LangChain4j Quarkus extension** — `quarkus-langchain4j-ollama` (EmbeddingModel only)
- **casehub-rag** — neural-text RAG module (`CaseRetriever`, `EmbeddingIngestor`, `CorpusIngestionService`); Qdrant integration via `casehub-rag`, not direct client
- **casehub-inference-quarkus** — ONNX inference CDI wiring, native image metadata; engine bridges `@Inference` models to `SparseEmbedder`/`CrossEncoderReranker`
- **casehub-corpus-api + casehub-corpus** — filesystem change detection (`FlatChangeSource`, `WatchableChangeSource`)
- **Ollama** — dense embedding model (`nomic-embed-text`, 768-dim)
- **MCP server** — `quarkus-mcp-server-http` (long-running, SSE/HTTP transport)
- **Java 25**

## Key Design Decisions

- **Hybrid search via inference-quarkus** — `HybridSearchProducer` bridges `@Inference`-qualified ONNX models to `SparseEmbedder`/`CrossEncoderReranker` via `@LookupIfProperty`; beans are genuinely non-resolvable when ONNX models aren't configured, so the engine falls back to dense-only transparently. `CollectionMigration` detects dense-only Qdrant collections at startup and triggers re-indexing when SPLADE is newly enabled.
- **Incremental re-indexing** — cursor-based change detection via `FlatChangeSource` (directory-watcher); live filesystem watching after startup sync
- **neural-text RAG delegation** — ingestion and retrieval via `casehub-rag` SPIs (`EmbeddingIngestor`, `CaseRetriever`); engine provides `CorpusIngestionBinding` via CDI, neural-text handles Qdrant lifecycle, collection schema, and cursor management
- **Fixed tenant ID** — `CorpusRef("hortora", gardenConfig.id())`; collection name `hortora_garden` under `SEPARATE_COLLECTIONS` tenancy strategy
- **Long-running service, not stdio** — Qdrant loads its index once; stdio per-session cold-start is unacceptable at corpus scale
- **Garden entries are the chunks** — no document splitting; entries (50–200 lines) are the retrieval unit
- **Federation in this service** — canonical/child chain walk is Hortora-specific logic, lives here not in any shared module

## Build

```bash
./mvnw verify                          # JVM tests
./mvnw verify -Pnative                 # native image (requires GraalVM 25)
./mvnw quarkus:dev                     # dev mode with live reload + Dev Services
```

CI runs two jobs: JVM (every push, fast) and native image (push to main only, ~15 min). Native is the production deployment artifact.

## Dev Services

Ollama Dev Services starts automatically in dev mode (disabled in tests via `quarkus.langchain4j.ollama.devservices.enabled=false`). In tests, `casehub-rag-testing` provides `InMemoryCaseRetriever` and `InMemoryEmbeddingIngestor` (`@Alternative @Priority(1)`, requires `quarkus.index-dependency` in test properties). `TestEmbeddingModel` (`@Mock`) satisfies the `EmbeddingModel` CDI injection point without starting Ollama. In dev mode, start Qdrant manually: `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant`. For hybrid search in dev mode, run `scripts/download-models.sh` to fetch ONNX models, then uncomment the `%dev` model paths in `application.properties`.

## Project Artifacts

Paths that are project content (not workspace noise).

| Path | What it is |
|------|------------|
| `CLAUDE.md` | Project conventions (build, test, naming) |
| `docs/adr/` | Architecture decision records |
| `docs/DESIGN.md` | Design document |
| `docs/superpowers/specs/` | Design specs |
| `docs/superpowers/plans/` | Implementation plans |
| `scripts/` | Development scripts (ONNX model download) |

## Work Tracking

Issue tracking: enabled
GitHub repo: Hortora/engine
All commits reference an issue — `Refs #N` (ongoing) or `Closes #N` (done).
