# Matryoshka Embeddings + Qdrant Quantization

**Issue:** casehubio/neural-text#31
**Date:** 2026-06-26
**Status:** Approved

## Problem

Dense embeddings stored in Qdrant consume significant RAM across tenants. At 1024-dim float32,
each vector is 4096 bytes. With 200 tenants at 100K chunks each, that's ~80GB of vectors in
memory. Qdrant supports server-side quantization (binary, scalar) that can reduce this to
~2.5GB, and Matryoshka-trained models allow dimension truncation that compounds the savings
further.

## Decision: No Dual-Vector Tiered Search

Qdrant's native quantization already provides tiered search server-side — binary index for fast
candidate selection, float32 rescore for accuracy. Storing a second truncated+quantized named
vector per point was evaluated and rejected: at CaseHub's per-tenant scale (<1M vectors per
collection), the binary index RAM savings (~112 bytes/vec) are offset by the wasted HNSW graph
on the unused full "dense" vector (~128 bytes/vec). Dual-vector tiered search pays off at
10M+ vectors per collection. The named-vector infrastructure already supports adding it later.

## Design

Two independent, opt-in capabilities in the `rag` module:

### 1. Matryoshka Truncation — EmbeddingModel Decorator

`MatryoshkaEmbeddingModel` wraps any LangChain4j `EmbeddingModel`. When
`casehub.rag.matryoshka.dimension` is set, `RagBeanProducer` and `ReactiveRagBeanProducer`
wrap the injected model before passing it to ingestors and retrievers.

**Location:** `rag/src/main/java/io/casehub/rag/runtime/MatryoshkaEmbeddingModel.java`

**Behaviour:**
- `embedAll()`: delegates to wrapped model, truncates each embedding to `targetDimension`
  dimensions via `Arrays.copyOf(vector, targetDimension)`, then L2-renormalizes
- `dimension()`: returns `targetDimension`
- `modelName()`: returns `delegate.modelName() + "/matryoshka-" + targetDimension` — makes
  truncation visible in logs and diagnostics without inspecting config

**Validation:** Constructor rejects `targetDimension <= 0` or `targetDimension > delegate.dimension()`.
Fails fast at application startup via the CDI producer.

**Transparency:** All downstream code sees the truncated dimension. `ensureCollection()` calls
`embeddingModel.dimension()` → gets truncated size → creates correctly-sized Qdrant vectors.
No changes to `QdrantPointBuilder`.

**Model requirement:** Matryoshka truncation only preserves ranking quality with models trained
using Matryoshka Representation Learning (e.g., nomic-embed-text, BAAI/bge family,
jina-embeddings-v2, mxbai-embed-large). Truncating non-Matryoshka models causes
significant quality degradation. This is a deployment concern — the code cannot verify
whether a model is Matryoshka-trained.

### 2. Qdrant Quantization Config

Config-driven quantization applied to the dense vector's `VectorParams` during collection
creation. Qdrant handles binary index creation and float32 rescore server-side.

**Config additions to `RagConfig`:**

```java
MatryoshkaConfig matryoshka();

interface MatryoshkaConfig {
    OptionalInt dimension();
}

QuantizationConfig quantization();

interface QuantizationConfig {
    @WithDefault("NONE")
    DenseQuantization type();

    @WithDefault("true")
    boolean alwaysRam();

    OptionalDouble oversampling(); // recommended 2.0-3.0 for BINARY
}
```

**`DenseQuantization` enum:** `NONE`, `BINARY`, `SCALAR`

Named `DenseQuantization` (not `QuantizationType`) because Qdrant client 1.18.1 already defines
`io.qdrant.client.grpc.Collections.QuantizationType` (values: `UnknownQuantization`, `Int8`,
`UNRECOGNIZED`) — used internally by `ScalarQuantization.type`. Both enums would appear in
`ensureCollection()` and `buildCreateRequest()`, making unqualified usage ambiguous.

- `BINARY` → Qdrant `BinaryQuantization` — 32x compression, uses hamming distance for
  first-pass, rescores with float32 originals. Best RAM savings.
- `SCALAR` → Qdrant `ScalarQuantization` with `type=Int8` — 4x compression (float32→uint8),
  less aggressive but better quality preservation. Recommended for quality-sensitive deployments.
- `NONE` → current behaviour, no quantization.

**Collection creation:** `ensureCollection()` (blocking) and `buildCreateRequest()` (reactive)
add quantization config to `VectorParams.Builder` when type is not `NONE`.

**Dimension validation on existing collections:** When `ensureCollection()` finds an existing
collection, it reads the collection's vector config via `getCollectionInfoAsync()` and compares
the configured dense dimension against the existing collection's dimension. On mismatch, fails
fast with a clear error: `"Configured embedding dimension (256) does not match existing
collection dimension (1024). Re-index the collection or adjust matryoshka.dimension."` This
prevents silent upsert failures when Matryoshka dimension is changed after collections exist.
One extra RPC per collection, guarded by the existing `knownCollections`/`ensuredCollections`
memoization.

**Ingestor constructor change:** `QdrantEmbeddingIngestor` and `ReactiveQdrantEmbeddingIngestor`
gain `DenseQuantization type` and `boolean alwaysRam` constructor parameters for collection
creation. `oversampling` is not passed to ingestors — it is a search-time concern, not an
ingestion concern. Package-private constructors — only called from producers.

**Search-time oversampling:** When quantization is configured and `oversampling` is set,
the retrievers (`HybridCaseRetriever`, `ReactiveHybridCaseRetriever`) apply
`QuantizationSearchParams.setOversampling()` via `SearchParams` on the dense prefetch leg.
This compensates for the lossy quantized index by retrieving more candidates before float32
rescoring. Without oversampling, Qdrant's default (1.0) retrieves exactly `limit` candidates
from the quantized index — insufficient for binary quantization where hamming distance is
a coarse approximation. Qdrant docs recommend 1.5-3x oversampling for binary quantization.

Oversampling is applied only to the dense prefetch, not the sparse prefetch — sparse vectors
are not quantized.

**Deferred quantization knobs:** The following Qdrant capabilities exist but are intentionally
omitted from this iteration:
- `ScalarQuantization.quantile` — clamping fraction for extreme values (default 0.99)
- `BinaryQuantization.encoding` — OneBit (default), TwoBits, OneAndHalfBits (Qdrant 1.15+)
- `BinaryQuantization.queryEncoding` — per-query encoding override
These can be added as config options if operators need fine-grained tuning. The current
`type` + `alwaysRam` + `oversampling` covers the primary deployment scenarios.

**Migration note:** Existing collections aren't affected — quantization applies only to newly
created collections. Re-indexing existing corpora requires deleting and re-ingesting the
collection (standard Qdrant behaviour). The dimension validation check catches mismatches
on the next ingest attempt.

### 3. CDI Wiring

Both producers (`RagBeanProducer`, `ReactiveRagBeanProducer`) add a private
`effectiveEmbeddingModel()` method:

```java
private EmbeddingModel effectiveEmbeddingModel() {
    return config.matryoshka().dimension().isPresent()
        ? new MatryoshkaEmbeddingModel(embeddingModel, config.matryoshka().dimension().getAsInt())
        : embeddingModel;
}
```

Both `corpusStore()` and `caseRetriever()` producers use `effectiveEmbeddingModel()`.
This guarantees ingestion and retrieval use the same truncated model.

**Reactive dimension handling:** `ReactiveQdrantEmbeddingIngestor` computes `denseDimension`
from its `embeddingModel` argument in the constructor — not from a separately cached value.
The constructor runs during CDI startup (main thread), so `dimension()` can safely block.
The `@PostConstruct` in `ReactiveRagBeanProducer` that previously cached `denseDimension`
from the raw model is removed. The `denseDimension` constructor parameter is also removed —
the ingestor derives it internally from the `embeddingModel` it receives.

This fixes a pre-existing bug: the old `@PostConstruct` captured dimension from the raw
CDI-injected model, which would produce the wrong dimension when Matryoshka wrapping is
active. The field itself is retained (not eliminated) because `buildCreateRequest()` runs
inside a `Uni` chain on Qdrant's gRPC callback thread via `directExecutor()` — calling
`embeddingModel.dimension()` there risks blocking if the default implementation calls
`embed("test")`. Caching at construction time avoids this.

### 4. Retriever Changes

Both `HybridCaseRetriever` and `ReactiveHybridCaseRetriever` gain constructor parameters
for `DenseQuantization type` and `OptionalDouble oversampling`.

When `type != NONE` and `oversampling` is present, `SearchParams` with
`QuantizationSearchParams` are applied to the dense search in **both** code paths:

**Hybrid path** (dense + sparse prefetch with RRF fusion) — applied to the dense prefetch
leg only, not the sparse prefetch:

```java
PrefetchQuery.Builder densePrefetch = PrefetchQuery.newBuilder()
    .setQuery(QueryFactory.nearest(denseEmbedding.vectorAsList()))
    .setUsing(denseVectorName)
    .setLimit(denseTopK);

if (quantizationType != DenseQuantization.NONE && oversampling.isPresent()) {
    densePrefetch.setParams(quantizationSearchParams());
}
```

**Dense-only path** (no sparse embedder) — applied to the top-level `QueryPoints`:

```java
QueryPoints.Builder builder = QueryPoints.newBuilder()
    .setCollectionName(collection)
    .setQuery(QueryFactory.nearest(denseEmbedding.vectorAsList()))
    .setUsing(denseVectorName)
    .setLimit(queryLimit)
    .setWithPayload(WithPayloadSelectorFactory.enable(true));

if (quantizationType != DenseQuantization.NONE && oversampling.isPresent()) {
    builder.setParams(quantizationSearchParams());
}
```

**Shared helper:**

```java
private SearchParams quantizationSearchParams() {
    return SearchParams.newBuilder()
        .setQuantization(QuantizationSearchParams.newBuilder()
            .setOversampling(oversampling.getAsDouble())
            .setRescore(true)
            .build())
        .build();
}
```

`rescore` is hardcoded to `true` because oversampling without rescoring is wasteful —
oversampling retrieves extra candidates from the lossy quantized index, but without
rescoring them against the original float32 vectors, the final ranking still uses the
lossy quantized distance. The extra I/O buys nothing if the ranking doesn't improve.

## What Changes

| File | Change |
|------|--------|
| `rag/runtime/MatryoshkaEmbeddingModel.java` | New — decorator class |
| `rag/runtime/DenseQuantization.java` | New — enum (NONE, BINARY, SCALAR) |
| `rag/runtime/RagConfig.java` | Add `matryoshka()` and `quantization()` config groups |
| `rag/runtime/QdrantEmbeddingIngestor.java` | Constructor gains quantization params; `ensureCollection()` applies quantization config + dimension validation |
| `rag/runtime/ReactiveQdrantEmbeddingIngestor.java` | Constructor computes `denseDimension` from `embeddingModel`; `denseDimension` constructor param removed; `buildCreateRequest()` applies quantization config |
| `rag/runtime/HybridCaseRetriever.java` | Constructor gains quantization + oversampling params; dense prefetch applies `SearchParams` when configured |
| `rag/runtime/ReactiveHybridCaseRetriever.java` | Same as blocking retriever |
| `rag/runtime/RagBeanProducer.java` | `effectiveEmbeddingModel()` wrapping; pass quantization config to ingestor + retriever |
| `rag/runtime/ReactiveRagBeanProducer.java` | `effectiveEmbeddingModel()` wrapping; remove `@PostConstruct` dimension caching; pass quantization config |

## What Does Not Change

- `rag-api` — no SPI changes (`EmbeddingIngestor`, `CaseRetriever` unchanged)
- `QdrantPointBuilder` — point construction unchanged
- `inference-*` modules — no inference layer changes
- Existing collections — quantization applies only to newly created collections

## Testing

**MatryoshkaEmbeddingModel unit tests:**
- Truncation produces correct dimension
- Output is L2-normalized after truncation
- `dimension()` returns target dimension
- `modelName()` includes `/matryoshka-{dim}` suffix
- Rejects `targetDimension > delegate.dimension()`
- Rejects `targetDimension <= 0`
- Batch: `embedAll` truncates every embedding in the list

**Quantization config tests:**
- `NONE`: `VectorParams` has no quantization config
- `BINARY`: `VectorParams` has `BinaryQuantization` with correct `alwaysRam`
- `SCALAR`: `VectorParams` has `ScalarQuantization` with correct `alwaysRam`

**Dimension validation tests:**
- Existing collection with matching dimension: `ensureCollection` succeeds
- Existing collection with mismatched dimension: `ensureCollection` throws with clear message

**Retriever oversampling tests:**
- `NONE` quantization: no `SearchParams` on any query path
- `BINARY` with oversampling=2.0, hybrid path: dense prefetch has `SearchParams` with
  `QuantizationSearchParams(oversampling=2.0, rescore=true)`; sparse prefetch has none
- `BINARY` with oversampling=2.0, dense-only path: `QueryPoints` has `SearchParams` with
  `QuantizationSearchParams(oversampling=2.0, rescore=true)`
- `rescore` is always `true` when oversampling is set

These assert on the protobuf message structure — no running Qdrant needed.

**Existing tests:** Pass unchanged — both features are opt-in with defaults matching
current behaviour (`NONE` quantization, no truncation, no oversampling).

## Config Reference

```properties
# Matryoshka truncation — empty = disabled (default)
casehub.rag.matryoshka.dimension=256

# Qdrant quantization — NONE (default), BINARY, SCALAR
casehub.rag.quantization.type=BINARY
casehub.rag.quantization.always-ram=true

# Oversampling for quantized search — empty = Qdrant default (1.0)
# Recommended 2.0-3.0 for BINARY quantization
casehub.rag.quantization.oversampling=2.0
```