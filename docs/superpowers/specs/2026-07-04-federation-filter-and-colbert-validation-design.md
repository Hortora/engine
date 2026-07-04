# Federation Filter Propagation + ColBERT Sequence Validation

*2026-07-04 · Refs #30, #37*

## Context

Two correctness fixes. Issue #30 is engine-only. Issue #37 adds `maxSequenceLength()` to `MultiModalEmbedder` and `maxMultivectorFloats()` to `RagConfig`, avoiding coupling engine migration logic to inference configuration internals.

### Cross-repo changes (Issue #37)

| Module | Change |
|--------|--------|
| `inference-api` | Add `int maxSequenceLength()` to `MultiModalEmbedder` |
| `inference-api` | `MatryoshkaMultiModalEmbedder` delegates `maxSequenceLength()` to wrapped embedder |
| `inference-api` test | Anonymous `MultiModalEmbedder` in `MatryoshkaMultiModalEmbedderTest` implements new method |
| `inference-bge-m3` | `BgeM3Embedder` constructor gains `int maxSequenceLength` parameter |
| `rag` | `RagConfig.maxMultivectorFloats()` — `@WithDefault("1000000") int maxMultivectorFloats()` |
| `rag` test | `StubMultiModalEmbedder` in `RagTestFixtures` implements `maxSequenceLength()` |

Test fixture updates are mechanical — they return hardcoded values to satisfy the new interface method.

## Issue #30 — Federation type/tags propagation

### Problem

`SearchResource.doSearch()` builds a `PayloadFilter` from domain, type, and tags for local search, but only passes `domains` to `ChainWalker.walk()`. Type and tags are silently dropped for all federated queries — upstream walks and peer fan-outs return unfiltered results when the caller specified a type or tag constraint.

The remote `/search` endpoint already accepts `type` and `tags` query parameters. The plumbing between `SearchResource` → `ChainWalker` → `RemoteGardenClient` simply doesn't carry them.

### Design

Thread `type` and `tags` as discrete `String` parameters through the federation chain. Three files change:

**`RemoteGardenClient`** — add `@QueryParam("type") String type` and `@QueryParam("tags") String tags` to the `search()` method. Null values produce no query parameter (JAX-RS omits null `@QueryParam`s).

**`ChainWalker`** — `walk()` gains `String type` and `String tags`. Both are forwarded to every `client.search()` call (upstream sequential and peer parallel). No filtering logic in ChainWalker itself — it passes the parameters through; the remote endpoint applies them.

**`SearchResource`** — `doSearch()` already accepts `type` and `tags` parameters and uses them for local filtering via `buildFilter()`. The only change is at the `chainWalker.walk()` call site (line 94), which currently passes only `query` and `domains` — add `type` and `tags` to the call.

### Why not a filter object?

Two extra string parameters is simpler than a new `SearchFilter` record. If a third filter dimension appears, the refactor is mechanical. No abstraction until the pressure is real.

### Tests

- `ChainWalkerTest` — existing tests gain null type/tags args. New test: `typeAndTagsPassedToUpstream` verifying the recording client receives the values.
- `SearchResourceTest` — verify type/tags reach the chain walker. Test that searches with type+tags through federation produce filtered results.
- `FederationIntegrationTest` — verify type/tags propagation through the WireMock-based end-to-end chain.

## Issue #37 — ColBERT sequence length validation

### Problem

The tokenizer truncates input at `max-sequence-length` (768 in dev, 512 default), so ColBERT output rows are bounded in practice. But no startup validation enforces that the total floats per multi-vector point (`max-sequence-length × colbert-dimension`) stays within a safe limit. A misconfiguration — e.g. `max-sequence-length=8192` with `colbert-dimension=1024` producing 8,388,608 floats — would create grossly oversized multi-vectors that either fail at Qdrant write time or degrade storage performance.

The upper bound is configurable via `RagConfig.maxMultivectorFloats()` (`casehub.rag.max-multivector-floats`, default: 1,000,000). `RagConfig` already contains Qdrant-schema properties (`colbertVectorName()`, `denseVectorName()`, `sparseVectorName()`) and `CollectionMigration` already injects it — no new dependency. This default is conservative — at 1024-dim ColBERT, it allows up to ~976 tokens per point, which covers the current production config (768 tokens) with headroom. Operators running shorter sequences or willing to accept higher storage costs can raise it.

### Design

**Interface addition (cross-repo):** Add `int maxSequenceLength()` to `MultiModalEmbedder` in `inference-api`. This is an intrinsic property of the embedder — it determines the maximum output geometry of ColBERT vectors (`maxSequenceLength` rows × `colbertDimension` columns). `BgeM3Embedder` gains a constructor parameter for this value and returns it from the method. `MatryoshkaMultiModalEmbedder` delegates to the wrapped embedder.

**Producer change (engine):** `HybridSearchProducer` injects `InferenceModelConfig`, extracts `maxSequenceLength` for the `bge-m3` model entry, and passes it to `BgeM3Embedder`. This is the natural wiring point — the producer's job is bridging config to runtime.

**Validation (engine):** In `CollectionMigration.onStartup()`, after resolving the `MultiModalEmbedder`:

1. Check `embedder.colbertDimension()`. If empty, skip (no ColBERT vectors).
2. Compute `embedder.maxSequenceLength() * colbertDim`.
3. If this exceeds `ragConfig.maxMultivectorFloats()`, throw `IllegalStateException` with a message naming the configured values and the limit.

This runs at `@Priority(10)` before ingestion starts, failing fast before any data reaches Qdrant. No `InferenceModelConfig` is injected into `CollectionMigration` — validation uses only the embedder interface, preserving the existing dependency boundary.

### Tests

- `CollectionMigrationTest` — **failing case:** configure `maxSequenceLength=1024` with `colbertDimension=1024` (1,048,576 > 1,000,000), verify `IllegalStateException`. **Passing case:** `768 × 1024 = 786,432 < 1,000,000`. **Boundary case:** exact limit value (e.g., 976 × 1024 = 999,424 passes; 977 × 1024 = 1,000,448 fails) — limit is exclusive (`>`). **No ColBERT case:** `colbertDimension()` returns `OptionalInt.empty()` — verify validation is skipped entirely.

## Out of scope

- **[#33](https://github.com/Hortora/engine/issues/33) (CC vs RRF fusion)** — requires configurable fusion strategy in `casehub-neocortex-rag`. Deferred to a cross-repo session. Status: OPEN.
- **[#38](https://github.com/Hortora/engine/issues/38) (.git/ filtering)** — requires changes to `FlatCorpusStore` in `casehub-neocortex-corpus`. Deferred to a cross-repo session. Status: OPEN.
