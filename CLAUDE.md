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
- **casehub-neocortex-rag** — neocortex RAG module (`CaseRetriever`, `EmbeddingIngestor`, `CorpusIngestionService`); Qdrant integration via `casehub-neocortex-rag`, not direct client
- **casehub-inference-quarkus** — ONNX inference CDI wiring; engine bridges `@Inference("bge-m3")` to `MultiModalEmbedder` via `BgeM3Embedder`
- **casehub-inference-bge-m3** — `BgeM3Embedder` producing dense + sparse + ColBERT from BGE-M3 ONNX model
- **casehub-corpus-api + casehub-corpus** — filesystem change detection (`FlatChangeSource`, `WatchableChangeSource`)
- **BGE-M3** — single ONNX embedding model (550M params, 1024-dim dense, learned sparse, ColBERT multi-vector)
- **casehub-neocortex-rag-tracking** — `TrackingCaseRetriever` CDI decorator + `SqliteRetrievalTracker` + `RetentionScheduler`; records every `CaseRetriever.retrieve()` call for usage-based curation
- **casehub-neocortex-memory-cbr-jpa** — `JpaCbrCaseMemoryStore` for CBR outcome tracking; H2 file-persistent datasource at `~/.hortora/stats/cbr`
- **MCP server** — `quarkus-mcp-server-http` (long-running, SSE/HTTP transport)
- **Java 25**

## Key Design Decisions

- **BGE-M3 via MultiModalEmbedder** — `HybridSearchProducer` produces a single `MultiModalEmbedder` bean from `@Inference("bge-m3")` via `@LookupIfProperty`; non-resolvable when the ONNX model path isn't configured. `MultiModalEmbedder` replaces the previous `EmbeddingModel` + `SparseEmbedder` + `CrossEncoderReranker` triple. Optional Matryoshka truncation via `casehub.rag.matryoshka.dimension` — wraps with `MatryoshkaMultiModalEmbedder` (truncate + L2 re-normalize). `CollectionMigration` detects dimension mismatch (768→1024) or missing ColBERT config and triggers re-indexing. Also validates ColBERT multi-vector size at startup (`maxSequenceLength × colbertDimension ≤ maxMultivectorFloats`) — fails fast before any data reaches Qdrant. Clears stale cursors when the Qdrant collection is absent — prevents silent zero-ingestion on fresh deployments.
- **Incremental re-indexing** — cursor-based change detection via `FlatChangeSource` (directory-watcher); live filesystem watching after startup sync
- **neocortex RAG delegation** — ingestion and retrieval via `casehub-neocortex-rag` SPIs (`EmbeddingIngestor`, `CaseRetriever`); engine provides `CorpusIngestionBinding` via CDI, neural-text handles Qdrant lifecycle, collection schema, and cursor management
- **Fixed tenant ID** — `CorpusRef("hortora", gardenConfig.id())`; collection name `hortora_garden` under `SEPARATE_COLLECTIONS` tenancy strategy
- **Long-running service, not stdio** — Qdrant loads its index once; stdio per-session cold-start is unacceptable at corpus scale
- **Garden entries are the chunks** — no document splitting; entries (50–200 lines) are the retrieval unit
- **Federation in this service** — canonical/child chain walk is Hortora-specific logic, lives here not in any shared module
- **Five-signal retrieval with cross-encoder reranking** — `HybridCaseRetriever` uses three server-side RRF prefetch legs: dense (BGE-M3 1024-dim), sparse (BGE-M3 learned lexical), and BM25 (Qdrant Document vectors with `qdrant/bm25` model). ColBERT MAX_SIM rescores the RRF top-50 via Qdrant multi-vectors. `RerankingCaseRetriever` (from `casehub-neocortex-rag-crossencoder`) then applies client-side cross-encoder reranking via ONNX model (`@Inference("reranker")` → `CrossEncoderProducer`), scoring the top-50 candidates and returning the best 16. Pool size 50 is the sweet spot: +8 highly-relevant entries over baseline at 1.15s median latency (pool-100 gains nothing but 2.5x latency). Cross-encoder scores drive adaptive result filtering via `SearchConfig` (score floor + gap detection + minResults) — `adaptiveFilter()` in `SearchResource` trims noise entries and extends into dense clusters, making result count fully adaptive. BM25 stays as complementary lexical leg; `CamelCaseExpander` preprocesses text for BM25 at ingestion time.
- **Retrieval frequency tracking** — `TrackingCaseRetriever` (CDI `@Decorator`, opt-in via `casehub.rag.tracking.enabled=true`) transparently records every `CaseRetriever.retrieve()` call via the `RetrievalTracker` SPI. `SqliteRetrievalTracker` persists records to SQLite (WAL mode, HikariCP). `RetentionScheduler` purges records older than `casehub.rag.tracking.retention.days` (default 180). `gardenUnretrieved` MCP tool uses `RetrievalAnalyzer.qualitySignals()` to surface entries with zero retrievals (NEVER_RETRIEVED), stale retrievals (STALE), or poor feedback (HIGH_RETRIEVAL_LOW_QUALITY). Records at the retriever level (pre-reranking, pre-adaptive-filter) — a document in the retrieval pool has demonstrated relevance even if filtering trims it.
- **CBR outcome tracking** — `GardenOutcomeService` stores `TextualCbrCase` per GE-ID via `JpaCbrCaseMemoryStore` (H2 file-persistent at `~/.hortora/stats/cbr`). Store-once/record-many lifecycle: first outcome creates the case, subsequent outcomes adjust confidence via `CbrOutcome.adjustConfidence()`. `gardenRecordOutcome` MCP tool + REST `POST /api/garden/outcomes` for recording. `gardenOutcomeReport` MCP tool + REST `GET /api/garden/outcomes/report` surfaces entries sorted by confidence for curation. Operates at per-issue granularity (not per-retrieval — complements, not replaces, `RetrievalTracker.feedback()`).
- **HyDE query expansion (definitively closed)** — four approaches benchmarked (double-retrieval, single-retrieval, inverted HyDE, no-HyDE baseline), all -2.2 to -2.5pp precision. Root cause confirmed by Weller et al. (EACL 2024): expansion harms strong multi-signal retrievers. `SessionQueryExpander` removed. Inverted HyDE infrastructure (`OllamaQueryGenerator`, `QueryAugmentingExtractor`, sidecar `.queries` cache) is built and tested but disabled (`hortora.inverted-hyde.enabled=false`) — available for future corpora with weaker retrievers.
- **BM25 keyword separation** — when `gardenSearch` receives keywords, `SearchResource.searchLocal()` builds `RetrievalQuery(text=keywords, expandedText=NL+keywords)`. BM25 sees focused keywords via `query.text()`; dense/sparse see full context via `query.searchText()`. Prevents keyword dilution where 1 keyword among 15+ NL tokens gets drowned in BM25 scoring. When keywords are present, adaptive score filtering (floor + gap trimming) is disabled — cross-encoder assigns negative scores to semantically distant but keyword-matching entries that are still relevant.
- **See Also expansion** — `GardenMetadataExtractor` parses "See also" cross-references from entry body text (922 entries, 3500 refs). Stored as `see_also` listMetadata and `see_also_ids` string metadata. At query time, `GardenMcpTools.expandWithSeeAlso()` collects referenced GE-IDs from returned results, resolves to document paths via `embeddingIngestor.listDocuments()`, and fetches adjacent entries. Surfaces entries in the same problem space that use different vocabulary.
- **Startup readiness probe** — `CollectionMigration.waitForQdrant()` retries `listCollectionsAsync()` up to 5 times with 2s delay before migration checks. Fixes gRPC `TRANSIENT_FAILURE` when Qdrant starts after the engine (concurrent launchd services). Without this, the gRPC channel is poisoned on startup and all RPCs fail immediately until the backoff timer fires.
- **Periodic reconcile** — `ReconcileScheduler` runs `CorpusIngestionService.reconcile()` every 6h (configurable via `hortora.reconcile.interval`). Compares files on disk vs Qdrant — adds missing entries, removes orphans. Safety net for filesystem watcher misses.

## Build

```bash
./mvnw verify                          # JVM tests
./mvnw quarkus:dev                     # dev mode with live reload + Dev Services
```

CI runs JVM tests on every push. JVM is the production deployment mode — long-running services benefit from HotSpot JIT over AOT.

## Deployment Mode — JVM by Design

The engine is a long-running service — native image's fast startup provides no benefit, and HotSpot's JIT optimisation outperforms AOT for sustained workloads. Deploys in JVM mode. AI assistants consume the engine via MCP (SSE/HTTP) — no separate CLI client needed.

## Engine Service

The engine runs as a persistent launchd service so `gardenSearch` MCP is always available:

```bash
scripts/update-engine.sh install    # first time: build, install plist, start
scripts/update-engine.sh update     # after code changes: rebuild + restart
scripts/update-engine.sh status     # check health
scripts/update-engine.sh logs       # tail log files
```

Qdrant runs as a Docker container (`qdrant-bench`) with `restart=unless-stopped`. Both survive reboots. The engine is registered as an MCP server in `~/.claude/mcp_servers.json` — all Claude sessions have `gardenSearch` available via `mcp__hortora__gardenSearch`. Ingestion cursor persists at `~/.hortora/cursors/garden.cursor` (not tmpdir — survives reboots). Podman VM resized to 4GB (`podman machine set --memory 4096`).

## Dev Services

In tests, `casehub-rag-testing` provides `InMemoryCaseRetriever` and `InMemoryEmbeddingIngestor` (`@Alternative @Priority(1)`, requires `quarkus.index-dependency` in test properties). `TestInferenceModelProducer` routes `@Inference("bge-m3")` to `InMemoryInferenceModel.returningMulti()` — no ONNX Runtime or real models needed. Run `scripts/export_bge_m3.py` to produce the BGE-M3 ONNX model (one-time, ~2.2GB download + export), then `scripts/download-models.sh` to verify checksums. The `%dev` model paths are pre-configured in `application.properties`. Sequence length is capped at 768 tokens (Qdrant ColBERT multi-vector limit: 1M floats = 1024 dim × ~1023 max tokens).

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
| `docs/comparison/` | Retrieval benchmark reports (#27 dense-only, #28 hybrid, #36 BGE-M3) |

## RAG Shadow Harness (#58)

A PostToolUse hook silently observes every garden grep Claude makes, fires a parallel RAG query to the engine, and logs both result sets to `~/.hortora/logs/rag-comparison.jsonl`. Check performance periodically:

```bash
PYTHONPATH=scripts python3 scripts/shadow/analyze_comparison.py
```

Reports coverage (did RAG find what grep found?), discovery (did RAG find extras?), and efficiency (1 RAG call vs n grep calls). Spec: `docs/superpowers/specs/2026-07-30-rag-shadow-comparison-harness-design.md`.

## Work Tracking

Issue tracking: enabled
GitHub repo: Hortora/engine
All commits reference an issue — `Refs #N` (ongoing) or `Closes #N` (done).
