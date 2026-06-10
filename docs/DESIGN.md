# engine — Design

## Architecture

Quarkus native image service. Indexes garden entries into Qdrant on startup, exposes search via REST and MCP HTTP server. Long-running — Qdrant loads its vector index once at startup.

## Module Structure

Single-module Maven project (`io.hortora:engine`).

| Package | Purpose |
|---------|---------|
| `io.hortora.garden.config` | `GardenConfig` — config mapping for `hortora.garden.*` |
| `io.hortora.garden.entry` | `GardenEntry` record + `GardenEntryParser` (YAML frontmatter + body) |
| `io.hortora.garden.index` | `GardenIndexer` — startup scan, embed, upsert |
| `io.hortora.garden.search` | `SearchResource` (REST) + `SearchResult` |
| `io.hortora.garden.mcp` | `GardenMcpTools` — `garden_search` + `garden_status` MCP tools |

## Key Abstractions

**`GardenEntry`** — parsed garden entry: id (file path), title, domain, type, score, tags, submitted, body.

**`GardenEntryParser`** — reads `.md` files, splits on `---` YAML frontmatter delimiter, parses with SnakeYAML. Throws `IllegalArgumentException` for files without frontmatter (silently skipped by indexer).

**`GardenIndexer`** — `@Observes StartupEvent`. Walks garden directory, parses all `.md` files with frontmatter, embeds body+title via `EmbeddingModel`, upserts to `EmbeddingStore` with metadata payload (domain, type, score, title).

**`SearchResource`** — `GET /search?q=&domain=&limit=`. Embeds query, builds `EmbeddingSearchRequest` with optional `IsEqualTo("domain", domain)` payload pre-filter, searches `EmbeddingStore`. Returns `List<SearchResult>`. Pre-filtering ensures `limit` is respected correctly regardless of domain distribution in the corpus.

**`GardenMcpTools`** — `@Tool`-annotated CDI bean. `garden_search` calls `SearchResource.searchFor()` and formats full entry text for LLM consumption. `garden_status` returns index count and garden path.

## SPI Contracts

Relies on LangChain4j Quarkus extension SPIs:
- `EmbeddingModel` — Ollama (`nomic-embed-text`, 768-dim) in production; `TestEmbeddingModel` in tests
- `EmbeddingStore<TextSegment>` — Qdrant in production; `TestEmbeddingStore` (in-memory) in tests

## Configuration

| Property | Default | Override |
|----------|---------|---------|
| `hortora.garden.path` | `${user.home}/.hortora/garden` | `HORTORA_GARDEN_PATH` env var or `-D` |
| `quarkus.langchain4j.ollama.embedding-model.model-name` | `nomic-embed-text` | config |
| `quarkus.langchain4j.qdrant.collection.name` | `garden` | config |

## Phase 2 (pending)

SPLADE sparse embeddings + cross-encoder reranker via `casehubio/onnx-inference` (not yet published). Gated on ONNX Runtime JNI + Quarkus native image prototype on macOS ARM.
