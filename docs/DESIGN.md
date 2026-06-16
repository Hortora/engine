# engine — Design

## Architecture

Quarkus native image service. Indexes garden entries into Qdrant on startup, exposes search via REST and MCP HTTP server. Long-running — Qdrant loads its vector index once at startup. Supports federation: upstream chain walk to parent gardens via HTTP, peer fan-out for supplementary results.

## Module Structure

Single-module Maven project (`io.hortora:engine`).

| Package | Purpose |
|---------|---------|
| `io.hortora.garden.config` | `GardenConfig` + `QdrantConfig` — config mappings |
| `io.hortora.garden.index` | `GardenIndexer` (startup wiring + lifecycle), `GardenIngestionService` (cursor-based ingest), `GardenMetadataExtractor`, `ExtractionResult`, `FileCursorStore`, `QdrantClientProducer` |
| `io.hortora.garden.search` | `SearchResource` (REST) + `SearchResult` |
| `io.hortora.garden.mcp` | `GardenMcpTools` — `garden_search` + `garden_status` MCP tools |
| `io.hortora.garden.federation` | `FederationConfig`, `FederationConfigParser`, `ChainWalker`, `RemoteGardenClient` |

## Key Abstractions

**`GardenMetadataExtractor`** — parses `.md` files with YAML frontmatter. Returns `ExtractionResult(content, metadata)` where content = `title + "\n\n" + body` for embedding quality. Non-`.md` files and files without frontmatter return empty content (skipped by ingestion).

**`GardenIngestionService`** — cursor-based incremental ingestion. Consumes `ChangeSet` from `FlatChangeSource` (casehub-corpus). Two error classes: extraction failure (skip file, cursor advances) vs infrastructure failure (Qdrant/Ollama down, cursor stays). `ReentrantLock.tryLock()` for concurrency between startup ingest and watcher callbacks. Deterministic UUID v3 point IDs from path.

**`GardenIndexer`** — `@Observes StartupEvent`. Creates `FlatCorpusStore` + `FlatChangeSource` + `GardenIngestionService`. Eagerly creates Qdrant collection with named "dense" vector (768-dim cosine). Triggers initial sync via cursor-based `fullScan()` or `changesSince()`. Starts `DirectoryWatcher` for live changes via `FlatChangeSource.watch()`. `@PreDestroy` stops the watcher.

**`SearchResource`** — `GET /search?q=&domain=&limit=`. Embeds query, builds `QueryPoints` request with optional payload filter for domain, queries `QdrantClient` directly. Handles federation: cycle detection via `X-Federation-Visited` header, depth check, provenance tagging on own results, delegation to `ChainWalker` for upstream/peer queries. Returns `List<SearchResult>` with provenance (id, source, sourcePrefix).

**`GardenMcpTools`** — `@Tool`-annotated CDI bean. `garden_search` calls `SearchResource.searchFor()` and formats full entry text for LLM consumption with provenance labels (`[own]` for local, `[prefix]` for remote). `garden_status` returns index count and garden path.

**`FederationConfig`** — record parsed from SCHEMA.md `federation:` block. Holds garden identity, role (canonical/child/peer), upstream chain, peers, relevance threshold, and max depth.

**`FederationConfigParser`** — `@ApplicationScoped`. `@Observes StartupEvent` for fail-fast validation. `@Produces @Singleton FederationConfig`. Falls back to default canonical config when no SCHEMA.md or no `federation:` block.

**`ChainWalker`** — `@ApplicationScoped`. Creates `RemoteGardenClient` instances at `@PostConstruct`. `walk()` orchestrates the full federation path: upstream sequential walk with short-circuit, peer parallel fan-out via `ManagedExecutor`, deduplication by id+source, tier-grouped relevance-sorted merge, truncation to limit. Returns the final merged result set. Degrades gracefully on timeout/failure.

**`RemoteGardenClient`** — package-private JAX-RS interface for HTTP calls to upstream/peer gardens. Clients created programmatically via `QuarkusRestClientBuilder` with 5s read timeout.

## Federation Model

Gardens have three relationship types:
- **Canonical** — root authority, no upstream. Serves as target for child gardens.
- **Child** — extends a parent. Searches own Qdrant first, walks upstream to parents via HTTP if results are insufficient.
- **Peer** — equals sharing knowledge. Searched in parallel for supplementary results.

Federation config lives in the garden's SCHEMA.md. Chain walk is transparent to callers — the `/search` endpoint returns merged results with provenance.

Provenance: `source` always holds the originating garden's ID (never a relative label). `[own]` is display-only in MCP output. Upstream results pass through unchanged — no re-tagging.

Priority ordering: tier-grouped (own > parent > grandparent > peer), relevance-sorted within each tier. Tier is the structural position of the HTTP call, not the source field.

Cycle detection: `X-Federation-Visited` header carries a comma-separated set of garden IDs. Depth bounded by `max-depth` config (default 5).

## SPI Contracts

Relies on LangChain4j and direct Qdrant client:
- `EmbeddingModel` — Ollama (`nomic-embed-text`, 768-dim) in production; `TestEmbeddingModel` in tests
- `io.qdrant:client` — direct Qdrant gRPC client for vector operations, no LangChain4j `EmbeddingStore` abstraction
- `casehub-corpus-api` + `casehub-corpus` — `FlatCorpusStore` (file tree facade), `FlatChangeSource` (cursor-based change detection), `DirectoryWatcher` (live change stream)

## Configuration

| Property | Default | Override |
|----------|---------|---------|
| `hortora.garden.path` | `${user.home}/.hortora/garden` | `HORTORA_GARDEN_PATH` env var or `-D` |
| `hortora.garden.id` | `garden` | config or SCHEMA.md `id:` |
| `hortora.garden.id-prefix` | `GE` | config or SCHEMA.md `id-prefix:` |
| `hortora.garden.schema-path` | `${hortora.garden.path}/SCHEMA.md` | config |
| `quarkus.langchain4j.ollama.embedding-model.model-name` | `nomic-embed-text` | config |
| `quarkus.langchain4j.qdrant.collection.name` | `garden` | config |

## Phase 2 (pending)

SPLADE sparse embeddings + cross-encoder reranker via `casehubio/onnx-inference` (not yet published). Gated on ONNX Runtime JNI + Quarkus native image prototype on macOS ARM.
