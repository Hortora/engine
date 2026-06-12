# engine — Design

## Architecture

Quarkus native image service. Indexes garden entries into Qdrant on startup, exposes search via REST and MCP HTTP server. Long-running — Qdrant loads its vector index once at startup. Supports federation: upstream chain walk to parent gardens via HTTP, peer fan-out for supplementary results.

## Module Structure

Single-module Maven project (`io.hortora:engine`).

| Package | Purpose |
|---------|---------|
| `io.hortora.garden.config` | `GardenConfig` — config mapping for `hortora.garden.*` |
| `io.hortora.garden.entry` | `GardenEntry` record + `GardenEntryParser` (YAML frontmatter + body) |
| `io.hortora.garden.index` | `GardenIndexer` — startup scan, embed, upsert |
| `io.hortora.garden.search` | `SearchResource` (REST) + `SearchResult` |
| `io.hortora.garden.mcp` | `GardenMcpTools` — `garden_search` + `garden_status` MCP tools |
| `io.hortora.garden.federation` | `FederationConfig`, `FederationConfigParser`, `ChainWalker`, `RemoteGardenClient` |

## Key Abstractions

**`GardenEntry`** — parsed garden entry: id (file path), title, domain, type, score, tags, submitted, body.

**`GardenEntryParser`** — reads `.md` files, splits on `---` YAML frontmatter delimiter, parses with SnakeYAML. Throws `IllegalArgumentException` for files without frontmatter (silently skipped by indexer).

**`GardenIndexer`** — `@Observes StartupEvent`. Walks garden directory, parses all `.md` files with frontmatter, embeds body+title via `EmbeddingModel`, upserts to `EmbeddingStore` with metadata payload (domain, type, score, title).

**`SearchResource`** — `GET /search?q=&domain=&limit=`. Embeds query, builds `EmbeddingSearchRequest` with optional `IsEqualTo("domain", domain)` payload pre-filter, searches `EmbeddingStore`. Handles federation: cycle detection via `X-Federation-Visited` header, depth check, provenance tagging on own results, delegation to `ChainWalker` for upstream/peer queries. Returns `List<SearchResult>` with provenance (id, source, sourcePrefix).

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

Relies on LangChain4j Quarkus extension SPIs:
- `EmbeddingModel` — Ollama (`nomic-embed-text`, 768-dim) in production; `TestEmbeddingModel` in tests
- `EmbeddingStore<TextSegment>` — Qdrant in production; `TestEmbeddingStore` (in-memory) in tests

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
