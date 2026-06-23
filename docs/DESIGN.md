# engine — Design

## Architecture

Quarkus JVM service. Delegates ingestion and retrieval to neural-text's `casehub-rag` module, exposes search via REST and MCP HTTP server. Long-running — Qdrant loads its vector index once at startup. Supports federation: upstream chain walk to parent gardens via HTTP, peer fan-out for supplementary results.

## Module Structure

Single-module Maven project (`io.hortora:engine`).

| Package | Purpose |
|---------|---------|
| `io.hortora.garden.config` | `GardenConfig` — garden path, ID, and federation schema config |
| `io.hortora.garden.index` | `GardenBindingProducer` (CDI integration with neural-text), `GardenMetadataExtractor` (implements `MetadataExtractor` SPI) |
| `io.hortora.garden.search` | `SearchResource` (REST, delegates to `CaseRetriever`) + `SearchResult` |
| `io.hortora.garden.mcp` | `GardenMcpTools` — `garden_search` + `garden_status` MCP tools |
| `io.hortora.garden.inference` | `HybridSearchProducer` (bridges `@Inference` models to `SparseEmbedder`/`CrossEncoderReranker`), `CollectionMigration` (dense→hybrid re-index) |
| `io.hortora.garden.federation` | `FederationConfig`, `FederationConfigParser`, `ChainWalker`, `RemoteGardenClient` |

## Key Abstractions

**`GardenBindingProducer`** — CDI producer that creates a `CorpusIngestionBinding` for the garden. This is the single integration point with neural-text's `CorpusIngestionService`. Creates `FlatCorpusStore` + `FlatChangeSource` from `GardenConfig.path()`, wraps them with a fixed `CorpusRef("hortora", gardenConfig.id())`. Neural-text handles startup scanning, filesystem watching, cursor persistence, collection schema creation, and embedding — the engine provides only the binding.

**`GardenMetadataExtractor`** — implements neural-text's `MetadataExtractor` SPI. Parses `.md` files with YAML frontmatter. Returns `ExtractionResult(body, metadata)` where body = `title + "\n\n" + body` for embedding quality. Non-`.md` files and files without frontmatter return empty content (skipped by ingestion). Extracts: title, domain, type, score, tags, submitted.

**`SearchResource`** — `GET /search?q=&domain=&limit=`. Delegates local search to `CaseRetriever.retrieve()` with `PayloadFilter` for domain filtering (single domain → `Eq`, multiple → `In`). Maps `RetrievedChunk` to `SearchResult` with metadata extraction and provenance tagging. Handles federation: cycle detection via `X-Federation-Visited` header, depth check, delegation to `ChainWalker` for upstream/peer queries.

**`GardenMcpTools`** — `@Tool`-annotated CDI bean. `garden_search` calls `SearchResource.searchFor()` and formats full entry text for LLM consumption with provenance labels (`[own]` for local, `[prefix]` for remote). `garden_status` returns index count (via `EmbeddingIngestor.listDocuments()`) and garden path.

**`FederationConfig`** — record parsed from SCHEMA.md `federation:` block. Holds garden identity, role (canonical/child/peer), upstream chain, peers, relevance threshold, and max depth.

**`FederationConfigParser`** — `@ApplicationScoped`. `@Observes StartupEvent` for fail-fast validation. `@Produces @Singleton FederationConfig`. Falls back to default canonical config when no SCHEMA.md or no `federation:` block.

**`ChainWalker`** — `@ApplicationScoped`. Creates `RemoteGardenClient` instances at `@PostConstruct`. `walk()` orchestrates the full federation path: upstream sequential walk with short-circuit, peer parallel fan-out via `ManagedExecutor`, deduplication by id+source, tier-grouped relevance-sorted merge, truncation to limit. Degrades gracefully on timeout/failure.

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

Relies on neural-text's RAG module and LangChain4j:
- `CaseRetriever` — neural-text SPI for vector search; `retrieve(RetrievalQuery, CorpusRef, int, PayloadFilter)` — takes `RetrievalQuery` (wraps query text + optional expanded text for query expansion)
- `EmbeddingIngestor` — neural-text SPI for ingestion and document lifecycle
- `CorpusIngestionService` — neural-text orchestrator; engine provides `CorpusIngestionBinding` via CDI
- `MetadataExtractor` — neural-text SPI; engine provides `GardenMetadataExtractor`
- `EmbeddingModel` — LangChain4j; Ollama (`nomic-embed-text`, 768-dim) in production; `TestEmbeddingModel` in tests
- `casehub-corpus-api` + `casehub-corpus` — `FlatCorpusStore` (file tree facade), `FlatChangeSource` (cursor-based change detection + live watching)

## Configuration

| Property | Default | Override |
|----------|---------|---------|
| `hortora.garden.path` | `${user.home}/.hortora/garden` | `HORTORA_GARDEN_PATH` env var or `-D` |
| `hortora.garden.id` | `garden` | config or SCHEMA.md `id:` |
| `hortora.garden.id-prefix` | `GE` | config or SCHEMA.md `id-prefix:` |
| `hortora.garden.schema-path` | `${hortora.garden.path}/SCHEMA.md` | config |
| `quarkus.langchain4j.ollama.embedding-model.model-name` | `nomic-embed-text` | config |
| `casehub.rag.qdrant.host` | `localhost` | config |
| `casehub.rag.qdrant.port` | `6334` | config |
| `casehub.rag.tenancy-strategy` | `SEPARATE_COLLECTIONS` | config |

## Phase 2 — Hybrid Search (complete)

SPLADE sparse embeddings + cross-encoder reranking via `casehub-inference-quarkus` (ONNX Runtime).

- `HybridSearchProducer` bridges `@Inference`-qualified ONNX models to `SparseEmbedder` and `CrossEncoderReranker` via `@LookupIfProperty` — beans are genuinely non-resolvable when ONNX models aren't configured, so the engine falls back to dense-only transparently.
- `CollectionMigration` detects dense-only Qdrant collections at startup and triggers re-indexing when SPLADE is newly enabled.
- Dense + sparse RRF fusion and cross-encoder reranking activate when ONNX models are configured via `casehub.inference.models.splade.model-path` and `casehub.inference.models.reranker.model-path`.
