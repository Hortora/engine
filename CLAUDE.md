# CLAUDE.md

## Project Type

**Type:** java

## Repository Purpose

**engine** — the Hortora garden retrieval service. A Quarkus LangChain4j application that indexes garden entries into Qdrant and exposes them via a MCP server for AI assistant consumption.

Phase 1 (current): dense-only retrieval — LangChain4j + Qdrant + Ollama + MCP HTTP server.
Phase 2 (future): hybrid search — adds SPLADE sparse embeddings and cross-encoder reranking via `casehubio/neural-text` `inference-splade` (Hortora-eligible).

## Stack

- **Quarkus 3.36.x** — runtime
- **LangChain4j Quarkus extension** — `quarkus-langchain4j-ollama` (EmbeddingModel only)
- **Qdrant** — vector store via direct `io.qdrant:client` gRPC (protocol `PP-20260616-896634`)
- **casehub-corpus-api + casehub-corpus** — filesystem change detection (`FlatChangeSource`, `WatchableChangeSource`)
- **Ollama** — dense embedding model (`nomic-embed-text`, 768-dim)
- **MCP server** — `quarkus-mcp-server-http` (long-running, SSE/HTTP transport)
- **Java 25**

## Key Design Decisions

- **Dense-only first** — no ONNX/SPLADE dependency in phase 1; CI goes green without cross-repo deps
- **Incremental re-indexing** — cursor-based change detection via `FlatChangeSource` (directory-watcher); live filesystem watching after startup sync
- **Direct Qdrant client** — `io.qdrant:client` gRPC, not LangChain4j `EmbeddingStore` abstraction (named vectors, payload filters, scroll pagination)
- **Long-running service, not stdio** — Qdrant loads its index once; stdio per-session cold-start is unacceptable at corpus scale
- **One collection per garden** — single-tenant; no namespace isolation needed
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

Ollama Dev Services starts automatically in dev/test mode. Qdrant uses Testcontainers in tests (`QdrantResource`); in dev mode, start Qdrant manually: `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant`.

## Project Artifacts

Paths that are project content (not workspace noise).

| Path | What it is |
|------|------------|
| `CLAUDE.md` | Project conventions (build, test, naming) |
| `docs/adr/` | Architecture decision records |
| `docs/DESIGN.md` | Design document |
| `docs/superpowers/specs/` | Design specs |
| `docs/superpowers/plans/` | Implementation plans |

## Work Tracking

Issue tracking: enabled
GitHub repo: Hortora/engine
All commits reference an issue — `Refs #N` (ongoing) or `Closes #N` (done).
