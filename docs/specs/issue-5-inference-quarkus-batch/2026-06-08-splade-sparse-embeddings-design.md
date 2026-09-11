# SPLADE Sparse Embeddings — inference-splade

**Issue:** casehubio/neural-text#6
**Status:** Approved
**Scope:** inference-splade module — pure Java, depends only on inference-api

## Design

Single class `SparseEmbedder` in `io.casehub.inference.splade`. Follows the task adapter pattern (same as NliClassifier, TextClassifier).

### API

```java
public final class SparseEmbedder {
    public SparseEmbedder(InferenceModel model)                    // threshold = 0.01f
    public SparseEmbedder(InferenceModel model, float threshold)
    
    public Map<Integer, Float> embed(String text)
    public List<Map<Integer, Float>> embedBatch(List<String> texts)
}
```

### Post-processing

Per vocabulary index `i`:
1. `raw = values[i]`
2. `activated = max(0, raw)` — ReLU
3. `weight = (float) log1p(activated)` — log-saturation
4. If `weight >= threshold` → include `(i, weight)` in output map

Output: `Map.copyOf(sparse)` — immutable. Keys = vocabulary token indices, values = weights.

### Constructor validation

- Model must not be null
- Threshold must be positive and finite
- Model output size must be present (SPLADE vocab size is fixed, not dynamic)

### Testing

Uses `InMemoryInferenceModel.withFunction()` for deterministic vocab-sized outputs. Covers: log-saturation math, threshold filtering, empty result, batch consistency, null rejection. ArchUnit dependency constraints enforced.