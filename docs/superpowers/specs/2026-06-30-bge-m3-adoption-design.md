# BGE-M3 Adoption — Single Model Multi-Modal Retrieval

*2026-06-30 · Refs #32, neural-text #30*

## Summary

Replace the three-model embedding stack (nomic-embed-text dense + SPLADE sparse + cross-encoder reranker) with BGE-M3, a single 550M-parameter model producing dense, sparse, and ColBERT embeddings from one forward pass. Introduces `MultiModalEmbedder` as the sole embedding interface in casehub-rag, evolves `InferenceOutput` to support multi-output models, and replaces cross-encoder reranking with ColBERT MAX_SIM via Qdrant multi-vectors.

BM25 (Qdrant Document vectors + CamelCaseExpander) stays as a complementary lexical leg — BGE-M3 sparse is learned, not lexical.

Net result: four retrieval signals (dense + learned-sparse + BM25 + ColBERT-rerank) from two sources (BGE-M3 + Qdrant BM25), simpler than today's three-model setup.

## Motivation

The current architecture requires three separate ONNX/Ollama models with independent inference calls:

| Component | Model | Dimension | Source |
|-----------|-------|-----------|--------|
| Dense | nomic-embed-text | 768 | Ollama (LangChain4j) |
| Sparse | Splade_PP_en_v1 | DistilBERT vocab | ONNX |
| Reranker | ms-marco-MiniLM-L-6-v2 | 1 (score) | ONNX |

Problems:
- Three model lifecycles, three config sets, three download scripts
- SPLADE has zero Java vocabulary (trained on MS MARCO web text) — BM25 already superseded it for our domain
- Cross-encoder requires N forward passes at query time (one per candidate)
- Dense dimension (768) is smaller than BGE-M3's (1024) — less representational capacity
- `InferenceModel` SPI cannot represent multi-output or multi-rank tensors

BGE-M3 unifies all three in one model, adds ColBERT (faster reranking than cross-encoder with precomputed document vectors), and has been validated at MIRACL nDCG@10 of 70.0 (vs 67.8 dense-only).

## Architecture

### Before

```
                 ┌─────────────────┐
Query ──────────►│  Ollama          │──► dense (768-dim)
                 │  nomic-embed-text│                     ┌──────────────┐
                 └─────────────────┘                     │              │
                 ┌─────────────────┐                     │  Qdrant RRF  │──► results
Query ──────────►│  ONNX SPLADE    │──► sparse           │  (3 legs)    │
                 └─────────────────┘                     │              │
                 ┌─────────────────┐                     └──────┬───────┘
Text  ──────────►│  CamelCase +    │──► BM25 Document            │
                 │  Qdrant BM25    │                             │
                 └─────────────────┘                     ┌──────▼───────┐
                 ┌─────────────────┐                     │ Cross-Encoder│
Query+Doc ──────►│  ONNX Reranker  │──► score            │  (client)    │──► reranked
                 └─────────────────┘                     └──────────────┘
```

### After

```
                 ┌─────────────────┐
                 │                 │──► dense (1024-dim) ─┐
Query ──────────►│  ONNX BGE-M3   │──► sparse (learned) ─┤  ┌──────────────┐
                 │  (one pass)     │──► ColBERT (multi)  ─┤  │              │
                 └─────────────────┘                      ├─►│  Qdrant RRF  │
                 ┌─────────────────┐                      │  │  (3 legs)    │
Text  ──────────►│  CamelCase +    │──► BM25 Document  ───┘  │              │
                 │  Qdrant BM25    │                          └──────┬───────┘
                 └─────────────────┘                                 │
                                                              ┌──────▼───────┐
                                                              │  ColBERT     │
                                                              │  MAX_SIM     │
                                                              │  (Qdrant)    │──► reranked
                                                              └──────────────┘
```

## Design

### 1. InferenceOutput Evolution

**Module:** `inference-api`

Current `InferenceOutput` returns a single flat array. Evolves to carry named, multi-rank outputs with preserved defensive copy and value-based equality contracts.

**Final class, not a record.** A record auto-generates a public `outputs()` accessor returning the component directly. Even with `Collections.unmodifiableMap()`, the `float[][]` arrays inside are mutable internal state — a caller can mutate via `out.outputs().get("dense")[0][0] = 999f`. Since the design overrides `equals`, `hashCode`, and adds 3 custom accessors, the record syntax works against the design. A final class with private fields eliminates the auto-generated accessor entirely — the only public access is through defensive-copy methods.

```java
public final class InferenceOutput {

    private final Map<String, float[][]> outputs;

    public InferenceOutput(Map<String, float[][]> outputs) {
        Objects.requireNonNull(outputs, "outputs must not be null");
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("outputs must not be empty");
        }
        // Deep defensive copy: clone map, each float[][], and each float[]
        var copy = new LinkedHashMap<String, float[][]>(outputs.size());
        for (var entry : outputs.entrySet()) {
            float[][] original = entry.getValue();
            float[][] cloned = new float[original.length][];
            for (int i = 0; i < original.length; i++) {
                cloned[i] = original[i].clone();
            }
            copy.put(entry.getKey(), cloned);
        }
        this.outputs = Collections.unmodifiableMap(copy);
    }

    /** Convenience factory for single-output models. */
    public static InferenceOutput of(float[] values) {
        return new InferenceOutput(Map.of("output", new float[][] { values }));
    }

    /** Backward-compatible access for single-output models. */
    public float[] values() {
        if (outputs.size() != 1) {
            throw new IllegalStateException(
                "values() requires exactly one output, got " + outputs.keySet());
        }
        float[] row = outputs.values().iterator().next()[0];
        return row.clone();
    }

    public float[][] output(String name) {
        float[][] result = outputs.get(name);
        if (result == null) {
            throw new IllegalArgumentException(
                "No output named '" + name + "', available: " + outputs.keySet());
        }
        float[][] cloned = new float[result.length][];
        for (int i = 0; i < result.length; i++) {
            cloned[i] = result[i].clone();
        }
        return cloned;
    }

    public float[] vector(String name) {
        return output(name)[0];
    }

    /** Output names for introspection (e.g., test assertions). */
    public Set<String> outputNames() {
        return outputs.keySet();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof InferenceOutput other)) return false;
        if (!outputs.keySet().equals(other.outputs.keySet())) return false;
        for (String key : outputs.keySet()) {
            if (!Arrays.deepEquals(outputs.get(key), other.outputs.get(key))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (var entry : outputs.entrySet()) {
            h = 31 * h + entry.getKey().hashCode() + Arrays.deepHashCode(entry.getValue());
        }
        return h;
    }
}
```

Every output is `float[][]` — rank-1 outputs are `float[1][dim]`, rank-2 (ColBERT) are `float[seq_len][dim]`. ColBERT's ONNX tensor is rank-3 `[batch, seq_len, 1024]`, but `OnnxInferenceModel` strips the batch dimension per-sample, yielding `float[seq_len][1024]` which fits `float[][]`.

The `values()` convenience method preserves backward compatibility for `SparseEmbedder` and `CrossEncoderReranker`, which consume single-output models and call `output.values()`.

**`InMemoryInferenceModel`** factory methods adapt:
- `returning(float...)` uses `InferenceOutput.of(snapshot.clone())`
- `withFunction(int, Function)` wraps the function result via `InferenceOutput.of()`
- New `returningMulti(Map<String, float[][]>)` factory for multi-output test stubs

**`InferenceModel.outputSize()`** returns `OptionalInt.empty()` for multi-output models. Existing adapters (`NliClassifier`, `CrossEncoderReranker`, `SparseEmbedder`) validate `outputSize()` at construction — they correctly reject multi-output models. `BgeM3Embedder` does not validate `outputSize()` because it knows the expected output structure.

**`OnnxInferenceModel`** evolves:

1. **Validation relaxed**: output rank check accepts rank 2 (`[batch, dim]`) and rank 3 (`[batch, seq_len, dim]`). Models with only rank-2 outputs preserve `outputSize` detection; multi-output or rank-3 models return `OptionalInt.empty()`.

2. **`run()` iterates all named outputs**:
   ```java
   try (OrtSession.Result result = session.run(inputMap)) {
       Map<String, float[][]> outputs = new LinkedHashMap<>();
       for (Map.Entry<String, OnnxValue> entry : result) {
           Object value = entry.getValue().getValue();
           if (value instanceof float[][] rank2) {
               outputs.put(entry.getKey(), new float[][] { rank2[0] });
           } else if (value instanceof float[][][] rank3) {
               outputs.put(entry.getKey(), rank3[0]);
           }
       }
       return new InferenceOutput(outputs);
   }
   ```

3. **`runBatch()` adapted symmetrically** — iterates all outputs for each batch index, constructs per-sample `InferenceOutput` with all named outputs. For rank-3 outputs (ColBERT), `runBatch()` strips padding vectors using the attention mask (`batchMask`) that is already constructed for input padding. Each sample's actual sequence length is `sum(batchMask[i])`. The per-sample rank-3 output is truncated from `float[maxLen][dim]` to `float[actualLen][dim]`, eliminating zero vectors from padded positions. This keeps padding as an `OnnxInferenceModel` implementation detail — consumers never see padding artifacts, and Qdrant stores only real token vectors.

### 2. MultiModalEmbedder Interface

**Module:** `inference-api`

Multi-modal embedding is an inference capability, not a RAG concern. Placing the interface in `inference-api` preserves the established dependency direction: `inference-api` ← `inference-*` ← `rag-api` ← `rag`. `BgeM3Embedder` in `inference-bge-m3` depends only on `inference-api`, not on any RAG module.

```java
public interface MultiModalEmbedder {

    MultiModalEmbedding embed(String text);

    List<MultiModalEmbedding> embedBatch(List<String> texts);

    Set<EmbeddingMode> supportedModes();

    int denseDimension();

    OptionalInt colbertDimension();
}

public final class MultiModalEmbedding {

    private final float[] dense;
    private final Map<Integer, Float> sparse;
    private final float[][] colbert;

    public MultiModalEmbedding(float[] dense, Map<Integer, Float> sparse,
                               float[][] colbert) {
        this.dense = dense.clone();
        this.sparse = sparse != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(sparse)) : null;
        if (colbert != null) {
            this.colbert = new float[colbert.length][];
            for (int i = 0; i < colbert.length; i++) {
                this.colbert[i] = colbert[i].clone();
            }
        } else {
            this.colbert = null;
        }
    }

    public float[] dense() { return dense.clone(); }
    public Map<Integer, Float> sparse() { return sparse; }
    public float[][] colbert() {
        if (colbert == null) return null;
        float[][] copy = new float[colbert.length][];
        for (int i = 0; i < colbert.length; i++) {
            copy[i] = colbert[i].clone();
        }
        return copy;
    }
}

public enum EmbeddingMode {
    DENSE, SPARSE, COLBERT
}
```

- **Dense is mandatory** — `supportedModes()` always includes `DENSE`.
- **Sparse and ColBERT are optional** — null in `MultiModalEmbedding` when mode not supported.
- **No LangChain4j types** — the RAG SPI owns its own embedding contract. No `Embedding`, `TextSegment`, or `Response<>`.
- **Post-processing is internal** — sparse is ReLU-thresholded (`extractSparse()`), ColBERT is L2-normalized per row. Consumers get ready-to-use vectors.

### 3. BgeM3Embedder Implementation

**Module:** New `inference-bge-m3` in neural-text (sibling to `inference-splade`, `inference-tasks`)

```java
public final class BgeM3Embedder implements MultiModalEmbedder {

    private final InferenceModel model;
    private final float sparseThreshold;

    public BgeM3Embedder(InferenceModel model) { ... }

    @Override
    public MultiModalEmbedding embed(String text) {
        InferenceOutput output = model.run(InferenceInput.of(text));

        float[] dense = normalize(output.vector("dense"));
        Map<Integer, Float> sparse = extractSparse(output.vector("sparse"));
        float[][] colbert = normalizeRows(output.output("colbert"));

        return new MultiModalEmbedding(dense, sparse, colbert);
    }

    @Override
    public Set<EmbeddingMode> supportedModes() {
        return Set.of(DENSE, SPARSE, COLBERT);
    }

    @Override
    public int denseDimension() { return 1024; }

    @Override
    public OptionalInt colbertDimension() { return OptionalInt.of(1024); }
}
```

**ONNX export** requires a custom script — HuggingFace Optimum exports only the transformer backbone (`last_hidden_state`). The three heads (dense CLS pooling, sparse token-to-vocab scatter, ColBERT projection + normalization) are custom Python logic in BAAI's `modeling.py` and must be baked into the ONNX graph explicitly. The export script must:
1. Wrap the model with all three head computations in `forward()`
2. Name the three outputs explicitly (`dense`, `sparse`, `colbert`)
3. Handle dynamic sequence length for ColBERT
4. Validate outputs against the PyTorch original
5. Bake the sparse token-to-vocab scatter into the graph (avoids requiring token IDs in Java)

The three-head ONNX variant does not exist on HuggingFace. This is a prerequisite task — `download-models.sh` must include the export script, not just a download URL. File neural-text issue for export script delivery.

Three named output tensors:
- `dense`: `[batch, 1024]` — CLS token embedding, L2-normalized in-graph
- `sparse`: `[batch, vocab_size]` — per-vocab aggregated weights (scatter baked in). For XLM-RoBERTa (BGE-M3's backbone), vocab_size = 250,002.
- `colbert`: `[batch, seq_len, 1024]` — per-token embeddings

Post-processing in Java (distinct from SPLADE):
- **Dense**: L2-normalize (if not already normalized in-graph)
- **Sparse**: ReLU → threshold at 0.01 → `Map<Integer, Float>`. No log-saturation — BGE-M3 computes `relu(linear(hidden_state))` per-token then max-pools to vocab indices. This differs from SPLADE's `log(1 + relu(MLM_head_logits))`.
- **ColBERT**: L2-normalize each row

Batch calls use `model.runBatch()` — one ONNX session execution per batch.

### 4. HybridCaseRetriever Evolution

**Module:** `rag`

Constructor simplifies:

```java
// Before:
HybridCaseRetriever(QdrantClient, EmbeddingModel, SparseEmbedder, TenantGuard, CrossEncoderReranker, RagConfig)

// After:
HybridCaseRetriever(QdrantClient, MultiModalEmbedder, TenantGuard, RagConfig)
```

Retrieval flow:

1. **One embed call**: `embedder.embed(query.text())` → `MultiModalEmbedding` with dense, sparse, ColBERT vectors from one forward pass.

2. **Build prefetch legs** from the single embedding result:
   - Dense leg (always): `embedding.dense()` → nearest() prefetch
   - Sparse leg (if SPARSE mode): `embedding.sparse()` → sparse nearest() prefetch
   - BM25 leg (if config): CamelCaseExpander → Document prefetch (unchanged)

3. **RRF fusion** — unchanged, server-side Qdrant.

4. **ColBERT reranking** replaces cross-encoder reranking:
   - If COLBERT mode supported: rescore RRF candidates using Qdrant MAX_SIM on stored multi-vectors
   - If absent: return RRF results directly (no reranking)

**Reactive counterpart:** `ReactiveHybridCaseRetriever` receives identical changes — same constructor simplification (`QdrantClient, MultiModalEmbedder, TenantGuard, RagConfig`), same retrieval flow adapted for `Uni<>` chains. `BlockingToReactiveCaseRetriever` bridge also adapts to `MultiModalEmbedder`.

### 5. ColBERT Reranking via Qdrant MAX_SIM

ColBERT reranking uses Qdrant's two-stage query API in a single gRPC call:

```
Stage 1 (RRF fusion — prefetch):
  ├─ Dense nearest, limit=denseTopK
  ├─ Sparse nearest, limit=sparseTopK    (if SPARSE)
  └─ BM25 Document, limit=bm25TopK      (if config)
  → query: RRF(k=60)
  → limit: rerankTopN                    (over-fetch for reranking)

Stage 2 (ColBERT rescore — outer query):
  prefetch: [stage 1 results]
  query: nearest(colbertQueryVectors)    (MAX_SIM on stored multi-vectors)
  using: "colbert"
  limit: maxResults                      (final result count)
```

Both stages execute server-side. No per-pair inference calls. Document ColBERT vectors are precomputed at ingestion time.

Storage cost estimates at 1024-dim, 4 bytes per float:
- 200 tokens (short chunk): 200 × 1024 × 4 = ~800KB per entry → ~1.6GB for 2000 entries
- 400 tokens (median chunk): 400 × 1024 × 4 = ~1.6MB per entry → ~3.2GB for 2000 entries
- 600 tokens (long chunk): 600 × 1024 × 4 = ~2.4MB per entry → ~4.8GB for 2000 entries

Actual garden entries after chunking are typically 300-500 tokens. Realistic estimate: **~2.4–4.0GB** for 2000 entries at median-to-p75 chunk lengths. This is significant for local deployment — Qdrant must fit ColBERT multi-vectors alongside dense, sparse, and BM25 vectors plus HNSW indexes. ColBERT quantization (deferred to #34) can reduce this by 4-8×.

### 6. QdrantEmbeddingIngestor Evolution

**Module:** `rag`

Same constructor simplification:

```java
// Before:
QdrantEmbeddingIngestor(QdrantClient, EmbeddingModel, SparseEmbedder, TenantGuard, RagConfig)

// After:
QdrantEmbeddingIngestor(QdrantClient, MultiModalEmbedder, TenantGuard, RagConfig)
```

One `embedder.embedBatch(texts)` call replaces separate `embeddingModel.embedAll()` + `sparseEmbedder.embedBatch()`.

`QdrantPointBuilder.buildPoint()` evolves to accept `MultiModalEmbedding` instead of separate dense/sparse parameters. Adds ColBERT multi-vector to named vectors map.

Collection creation adds ColBERT config when COLBERT mode is active:

```java
VectorParams colbertParams = VectorParams.newBuilder()
    .setSize(embedder.colbertDimension().orElseThrow())
    .setDistance(Distance.Cosine)
    .setMultivectorConfig(MultiVectorConfig.newBuilder()
        .setComparator(MultiVectorComparator.MaxSim)
        .build())
    .build();
paramsMap.putMap(config.colbertVectorName(), colbertParams);
```

ColBERT goes in the dense `VectorParamsMap` (not `SparseVectorConfig`) — it is a multi-vector of dense embeddings.

**Reactive counterpart:** `ReactiveQdrantEmbeddingIngestor` receives identical changes — same constructor simplification, same `MultiModalEmbedding` point building. `ReactiveRagBeanProducer` adapts to inject `MultiModalEmbedder` instead of `EmbeddingModel + SparseEmbedder`.

### 7. Engine-Side Changes

**HybridSearchProducer** — one producer, one model:

```java
@ApplicationScoped
public class HybridSearchProducer {

    @Produces
    @Singleton
    @LookupIfProperty(name = "casehub.inference.models.bge-m3.model-path",
                       stringValue = ".+", match = StringValueMatch.REGEX)
    MultiModalEmbedder multiModalEmbedder(@Inference("bge-m3") InferenceModel model) {
        return new BgeM3Embedder(model);
    }
}
```

**Drop Ollama** — remove `quarkus-langchain4j-ollama` dependency. Dense embeddings come from ONNX. Delete `TestEmbeddingModel`.

**CollectionMigration** — detects dimension mismatch (768→1024) or missing ColBERT multi-vector config. Triggers full re-index via delete collection + reset cursor.

**Configuration** simplifies to one model:

```properties
casehub.inference.models.bge-m3.model-path=${user.home}/.hortora/models/bge-m3/model.onnx
casehub.inference.models.bge-m3.tokenizer-path=${user.home}/.hortora/models/bge-m3/tokenizer.json
casehub.inference.models.bge-m3.maxSequenceLength=8192
```

New RagConfig property:
```properties
casehub.rag.colbert-vector-name=colbert
```

**`download-models.sh`** — fetches BGE-M3 ONNX model instead of separate SPLADE + reranker.

### 8. Migration and Re-indexing

First startup with BGE-M3 triggers automatic full re-index:

1. `CollectionMigration` detects dense dim 768 ≠ 1024 or missing ColBERT config
2. Deletes collection, resets ingestion cursor
3. `CorpusIngestionService` scans full garden directory
4. `QdrantEmbeddingIngestor.ensureCollection()` creates new schema with four named vectors
5. Full re-embed via `embedder.embedBatch()` — one BGE-M3 forward pass per batch
6. All points upserted with dense + sparse + BM25 + ColBERT vectors

No data loss risk — garden entries live on the filesystem. Qdrant is a derived index.

Rollback is symmetric — removing BGE-M3 and restoring nomic-embed-text triggers re-indexing in the other direction.

### 9. Testing

**Neural-text:**
- `InferenceOutput` tests adapt to `Map<String, float[][]>` constructor; `values()` backward compat verified
- `OnnxInferenceModel` tests verify multi-output extraction
- `BgeM3Embedder` unit tests with `InMemoryInferenceModel` returning canned three-output results
- `HybridCaseRetriever` tests use a test `MultiModalEmbedder`; verify three-leg + ColBERT rescore query construction
- `QdrantEmbeddingIngestor` tests verify ColBERT multi-vector in point structure and collection schema
- `SparseEmbedder` / `CrossEncoderReranker` tests unchanged — still work with single-output models via `values()`
- `MatryoshkaMultiModalEmbedder` tests: truncation to target dimension, L2 re-normalization, sparse/ColBERT pass-through, `denseDimension()` returns target
- `casehub-rag-testing` (`InMemoryCaseRetriever`, `InMemoryEmbeddingIngestor`) adapt to `MultiModalEmbedder`

**Engine:**
- `TestInferenceModelProducer` produces a `bge-m3` model returning three-output `InferenceOutput`
- `HybridSearchProducerTest` verifies `MultiModalEmbedder` produced/absent based on config
- `CollectionMigrationTest` adds dimension mismatch and missing ColBERT detection cases
- `SearchResourceTest` uses updated `InMemoryEmbeddingIngestor`
- `TestEmbeddingModel` deleted (no more LangChain4j mock)

## Qdrant Collection Schema (Final State)

```
Collection: hortora_garden

Dense Vectors (VectorParamsMap):
  "dense":   size=1024, distance=Cosine
  "colbert": size=1024, distance=Cosine, multivector=MaxSim

Sparse Vectors (SparseVectorConfig):
  "sparse":  default params (learned lexical weights)
  "bm25":    modifier=Idf (Qdrant Document vectors)

Payload Indexes:
  "content":          Text (tokenizer=Word, lowercase, minLen=2, maxLen=40)
  "sourceDocumentId": Keyword
  "tenantId":         Keyword
  "domain":           Keyword
  "type":             Keyword
  "tags":             Keyword
```

## What Changes vs What Stays

| Component | Before | After |
|-----------|--------|-------|
| Dense embedding | Ollama nomic-embed-text (768-dim) | BGE-M3 ONNX dense (1024-dim) |
| Sparse embedding | SPLADE ONNX (separate model) | BGE-M3 ONNX sparse (same model) |
| Reranking | Cross-encoder ONNX (client-side, N passes) | ColBERT MAX_SIM (server-side, zero passes) |
| BM25 | Qdrant Document + CamelCaseExpander | **Unchanged** |
| RRF fusion | Qdrant server-side, k=60 | **Unchanged** |
| Payload filtering | domain/type/tags | **Unchanged** |
| Embedding SPI | EmbeddingModel + SparseEmbedder + CrossEncoderReranker | MultiModalEmbedder |
| InferenceModel SPI | InferenceOutput(float[]) — record | InferenceOutput(Map<String, float[][]>) — final class |
| Ollama dependency | Required (nomic-embed-text) | Removed |
| Model count | 3 (nomic + SPLADE + reranker) | 1 (BGE-M3) |
| Forward passes per query | 3 (dense + sparse + rerank×N) | 1 (BGE-M3) |

## Reactive Variants — Full Enumeration

All blocking runtime classes have reactive counterparts requiring symmetric changes:

| Blocking | Reactive | Changes |
|----------|----------|---------|
| `HybridCaseRetriever` | `ReactiveHybridCaseRetriever` | Constructor: `MultiModalEmbedder`, ColBERT rescore in Uni chain |
| `QdrantEmbeddingIngestor` | `ReactiveQdrantEmbeddingIngestor` | Constructor: `MultiModalEmbedder`, ColBERT multi-vector in point builder |
| `RagBeanProducer` | `ReactiveRagBeanProducer` | Inject `MultiModalEmbedder` instead of `EmbeddingModel + SparseEmbedder + CrossEncoderReranker` |
| `BlockingToReactiveCaseRetriever` | — | Adapts to `MultiModalEmbedder` (bridge delegates to blocking `HybridCaseRetriever`) |

## Casehub Consumer Impact

The `MultiModalEmbedder` SPI replaces `EmbeddingModel + SparseEmbedder` as the sole embedding dependency in `rag` and `rag-testing`. This affects all casehub deployments (engine, clinical, aml) that consume shared `rag` modules.

**Migration path:** Each casehub deployment provides a `MultiModalEmbedder` bean. For deployments adopting BGE-M3: use `BgeM3Embedder`. For deployments remaining on separate models (nomic-embed-text + SPLADE): provide a deployment-specific `MultiModalEmbedder` that delegates to their existing models. This is not a backward-compat shim — it's a genuine implementation producing dense-only or dense+sparse embeddings through `supportedModes()`.

File neural-text issue for `SeparateModelEmbedder` — a `MultiModalEmbedder` implementation wrapping `EmbeddingModel + optional SparseEmbedder` for deployments not ready to adopt BGE-M3.

## Matryoshka Truncation Continuity

`MatryoshkaEmbeddingModel` (rag/runtime) wraps `EmbeddingModel` to truncate dense embeddings to a target dimension and L2-re-normalize. It is production code with 7 tests, documented in ARC42STORIES §C9, and wired into both `RagBeanProducer` and `ReactiveRagBeanProducer` via `effectiveEmbeddingModel()`.

Replacing `EmbeddingModel` with `MultiModalEmbedder` loses this capability. This is a **regression**, not a deferral — the spec must preserve it.

**Fix:** `MatryoshkaMultiModalEmbedder` decorator in `inference-api`:

```java
public final class MatryoshkaMultiModalEmbedder implements MultiModalEmbedder {

    private final MultiModalEmbedder delegate;
    private final int targetDimension;

    public MatryoshkaMultiModalEmbedder(MultiModalEmbedder delegate, int targetDimension) {
        this.delegate = delegate;
        this.targetDimension = targetDimension;
    }

    @Override
    public MultiModalEmbedding embed(String text) {
        MultiModalEmbedding result = delegate.embed(text);
        return new MultiModalEmbedding(
            truncateAndNormalize(result.dense()),
            result.sparse(), result.colbert());
    }

    @Override
    public int denseDimension() { return targetDimension; }
    // delegate all other methods
}
```

This truncates only the dense component — sparse and ColBERT are unaffected. `RagBeanProducer.effectiveEmbeddingModel()` becomes `effectiveEmbedder()` wrapping `MultiModalEmbedder` with `MatryoshkaMultiModalEmbedder` when `config.matryoshka().dimension()` is set. Same logic, new interface.

BGE-M3 supports Matryoshka natively (trained with MRL), so dense truncation is a valid operation on its embeddings.

## CRAG Compatibility

`CragBeanProducer` hard-depends on `Instance<CrossEncoderReranker>` to produce a `RelevanceEvaluator`. Removing the cross-encoder means CRAG cannot produce its default `CrossEncoderRelevanceEvaluator`.

This has **zero runtime impact** for Hortora: CRAG is config-gated (`casehub.rag.crag.enabled=true`) and Hortora does not enable it (retrieval-research.md: "Skip CRAG — cannot match hybrid fusion"). `CragBeanProducer` is `@IfBuildProperty`-gated and never activates.

For casehub deployments that use CRAG: they must keep a cross-encoder model or provide an alternative `RelevanceEvaluator`. ColBERT MAX_SIM scores could serve as per-chunk relevance grades — a `ColBertRelevanceEvaluator` that thresholds individual MAX_SIM scores against configurable correct/incorrect boundaries. File neural-text issue.

## ARC42STORIES §2 Constraint Update

ARC42STORIES §2 states: "LangChain4j for dense RAG — not negotiable." This spec replaces LangChain4j `OnnxEmbeddingModel` with BGE-M3 via the `InferenceModel` SPI for dense embeddings.

The constraint was written before the inference SPI layer existed. BGE-M3 unifying three models via the established `InferenceModel` SPI supersedes the rationale: rebuilding the dense pipeline is no longer duplication — the SPI already exists and is production-proven through 11 chapters.

LangChain4j remains in use for: `DocumentSplitter`, Tika parsing (`rag-tika`), and any casehub deployment not yet migrated to BGE-M3.

**Commit:** Update ARC42STORIES §2 and §4 as part of this work. New constraint text: "Dense embeddings use the InferenceModel SPI (BGE-M3 via ONNX). LangChain4j for document processing (splitting, Tika parsing)."

## Benchmark

neural-text #30 scope includes "Benchmark against current separate-model approach." This spec must deliver a comparison benchmark.

**Methodology:** Reuse the #27 / three-leg benchmark framework (14 scenarios, KW + NL queries). Run BGE-M3 four-leg retrieval against the same queries and score against the same ground truth. Compare: overall precision, keyword-gap precision, per-scenario deltas, average latency.

**Success criterion:** BGE-M3 must match or exceed current three-leg precision (94%) on the benchmark suite. Latency regression ≤ 50% (acceptable given single forward pass vs three).

**Deliverable:** `docs/comparison/bge-m3-benchmark.md` with raw results in `scripts/benchmark/results/bge-m3.json`.

## Deferred

- **Convex Combination fusion** (#33) — test CC (α=0.5) vs RRF. Config-only experiment, independent of BGE-M3.
- **ColBERT quantization** (#34) — evaluate storage optimization (4-8× reduction) after BGE-M3 baseline is established. Matryoshka dense truncation is preserved via `MatryoshkaMultiModalEmbedder` (see §Matryoshka Truncation Continuity).

## References

- [BGE-M3 Paper](https://arxiv.org/html/2402.03216v3) — Multi-Lingual, Multi-Functionality, Multi-Granularity
- [BGE-M3 HuggingFace](https://huggingface.co/BAAI/bge-m3) — 550M params, 8192 token context
- [Qdrant ColBERT Multivectors](https://qdrant.tech/documentation/tutorials-search-engineering/using-multivector-representations/)
- [Qdrant Hybrid Search Query API](https://qdrant.tech/articles/hybrid-search/)
- `docs/comparison/retrieval-research.md` — landscape analysis and roadmap
- `docs/comparison/hybrid-benchmark.md` — three-leg benchmark validation
