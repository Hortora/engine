# BGE-M3 Three-Head ONNX Export Script

*2026-07-01 · Refs #35*

## Summary

Deliver a Python export script that produces a single ONNX model with three named output heads (dense, sparse, ColBERT) from the BAAI/bge-m3 weights. The Java pipeline (`OnnxInferenceModel` + `BgeM3Embedder`) is already wired and tested with stubs — this script produces the real model that makes end-to-end retrieval functional.

Adapted from [aapot/bge-m3-onnx](https://huggingface.co/aapot/bge-m3-onnx)'s proven wrapper with three modifications: bake sparse scatter into the graph, rename outputs to match Java expectations, and include CLS in ColBERT output.

## Motivation

The BGE-M3 adoption (engine `ee7cc47`, neural-text `50c5a04`) replaced three separate models with `MultiModalEmbedder`, but all tests use `InMemoryInferenceModel` stubs. HuggingFace Optimum's standard ONNX export produces only the transformer backbone — the three heads (dense CLS pooling, sparse token-to-vocab scatter, ColBERT projection + normalization) are custom Python logic in BAAI's `modeling.py` and must be baked into the ONNX graph explicitly.

Existing three-head exports on HuggingFace (aapot, philipchung) output sparse as per-token weights requiring `input_ids` for post-processing. `BgeM3Embedder.extractSparse()` expects vocab-scattered `[batch, 250002]` where the array index is the vocab token ID. The scatter must be in-graph.

## Architecture

### Script Layout

Two files in `scripts/`:

| File | Role |
|------|------|
| `scripts/bgem3_model.py` | PyTorch `nn.Module` wrapper — loads BAAI/bge-m3 backbone + head weights, custom `forward()` with three named outputs and sparse scatter |
| `scripts/export_bge_m3.py` | Entrypoint — download weights, export via `torch.onnx.export`, optimize with ONNX Runtime, validate against PyTorch, copy to `~/.hortora/models/bge-m3/` |

### Developer Flow

```bash
pip install -r scripts/requirements-export.txt
python scripts/export_bge_m3.py
# → ~/.hortora/models/bge-m3/model.onnx + tokenizer.json
```

**`scripts/requirements-export.txt`:**
```
torch>=2.2,<2.5
transformers>=4.38,<5.0
onnx>=1.14
onnxruntime>=1.17,<2.0
huggingface_hub>=0.20
```

Note: `optimum` is not required — the export uses `torch.onnx.export` directly. `onnx` is required by both `torch.onnx.export` (protobuf serialization) and `optimize_model()` (graph manipulation) but is an optional dependency of both `torch` and `onnxruntime`, so it must be listed explicitly. `onnxruntime` is needed for O2 optimization (`onnxruntime.transformers.optimizer`) and ONNX model validation.

Developer manages their own Python environment. One-time operation — the ONNX file persists across engine restarts. Minimum 8GB RAM required (16GB recommended) — peak memory during O2 optimization is approximately 3× model size (~6–8GB).

## Model Wrapper (`bgem3_model.py`)

### Base

Adapted from [aapot/bge-m3-onnx](https://huggingface.co/aapot/bge-m3-onnx) `bgem3_model.py`. The wrapper loads:
- `model.safetensors` — XLM-RoBERTa backbone (550M params)
- `colbert_linear.pt` — ColBERT projection head (hidden_size → 1024)
- `sparse_linear.pt` — Sparse weight head (hidden_size → 1)

All via `huggingface_hub.snapshot_download("BAAI/bge-m3")`.

### forward() Signature

```python
def forward(self, input_ids: Tensor, attention_mask: Tensor) -> Dict[str, Tensor]:
```

Inputs are `input_ids` and `attention_mask` — matches `OnnxInferenceModel`'s hard validation (lines 78–85 reject models without these exact names). No `token_type_ids` — XLM-RoBERTa doesn't use them.

### Three Output Heads

| Output | Name | Shape | Computation |
|--------|------|-------|-------------|
| Dense | `dense` | `[batch, 1024]` | CLS pooling (`last_hidden_state[:, 0]`) → L2-normalize |
| Sparse | `sparse` | `[batch, 250002]` | `sparse_linear(hidden)` → ReLU → `scatter_reduce` to vocab via `input_ids` with max reduction |
| ColBERT | `colbert` | `[batch, seq_len, 1024]` | `colbert_linear(hidden)` on all tokens including CLS → mask padding → L2-normalize per row |

### Sparse Scatter Detail

aapot's wrapper outputs per-token `[batch, seq_len, 1]`. Our wrapper adds in-graph scatter:

```python
token_weights = torch.relu(self.sparse_linear(last_hidden_state)).squeeze(-1)  # [batch, seq_len]
sparse = torch.zeros(batch_size, self.config.vocab_size, device=token_weights.device)
sparse = torch.scatter_reduce(sparse, 1, input_ids, token_weights, reduce='amax')
```

This produces `[batch, vocab_size]` — one weight per vocab entry (250002 for XLM-RoBERTa). Uses non-in-place `torch.scatter_reduce()` for reliable ONNX export (avoids in-place mutation tracing issues). `include_self` defaults to `True`, which is semantically correct here: since the target is zero-initialized and all source values are non-negative (post-ReLU), `max(0, values) == max(values)`. The default also avoids ONNX `ScatterElements` compatibility issues — the ONNX op has no `include_self` parameter. The Java-side `extractSparse()` applies ReLU (no-op since values are already ≥ 0 from in-graph ReLU) then thresholds at 0.01 to produce `Map<Integer, Float>` for Qdrant sparse vectors.

Post-processing differs from SPLADE (garden entry GE-20260630-db5dce): BGE-M3 uses `relu(linear(hidden))` per-token then max-pools to vocab, not SPLADE's `log(1 + relu(MLM_logits))`.

### ColBERT: CLS Included

Deviation from BAAI's reference (which uses `last_hidden_state[:, 1:]`). Required because `OnnxInferenceModel.runBatch()` strips padding using `actualLen = sum(attention_mask)` which counts CLS. If ColBERT excludes CLS, `actualLen > output_seq_len` and `Arrays.copyOf` pads with null → NPEs in `normalizeRows()`.

Including CLS is safe — the CLS vector carries semantic information (it IS the dense embedding before normalization) and adds a holistic-meaning signal to MAX_SIM scoring.

### In-Graph Normalization

| Head | Normalization | Java-side effect |
|------|--------------|------------------|
| Dense | L2-normalized | `BgeM3Embedder.normalize()` is idempotent — re-normalizing a normalized vector is identity |
| Sparse | None (raw ReLU weights) | `extractSparse()` applies ReLU (no-op) + threshold (0.01) |
| ColBERT | L2-normalized per row | `BgeM3Embedder.normalizeRows()` is idempotent |

## Export Configuration (`export_bge_m3.py`)

### Export via `torch.onnx.export`

Uses `torch.onnx.export` directly — not Optimum's `onnx_export_from_model`. The wrapper is a plain `nn.Module` subclass (matching the aapot reference), and `onnx_export_from_model` expects `PreTrainedModel` with HuggingFace-specific infrastructure (`config`, `save_pretrained()`, model-type routing). Using `torch.onnx.export` directly gives full control over dynamic axes, opset version, and output naming without requiring the wrapper to fake HuggingFace model semantics.

```python
dummy_input = {
    "input_ids": torch.randint(0, config.vocab_size, (1, 32)),
    "attention_mask": torch.ones(1, 32, dtype=torch.long),
}

torch.onnx.export(
    model, dummy_input,
    output_path,
    opset_version=16,
    input_names=["input_ids", "attention_mask"],
    output_names=["dense", "sparse", "colbert"],
    dynamic_axes={
        "input_ids":      {0: "batch_size", 1: "sequence"},
        "attention_mask": {0: "batch_size", 1: "sequence"},
        "dense":          {0: "batch_size"},
        "sparse":         {0: "batch_size"},
        "colbert":        {0: "batch_size", 1: "sequence"},
    },
)
```

O2 optimization is applied separately via `onnxruntime.transformers.optimizer.optimize_model()` after export. This two-step approach (export then optimize) is standard when not using the Optimum pipeline.

### Export Parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Opset | 16 | Minimum for `ScatterElements` with max reduction |
| Optimization | O2 (post-export) | Basic + transformer fusions via `optimize_model()`; O3/O4 add GELU approximation and fp16 not wanted for CPU |
| Device | cpu | Export runs on CPU; model runs on CPU in production |

If `scatter_reduce` export fails on opset 16, fall back to opset 18. Known at export time — not a silent failure.

### Validation

Validation runs on the **final** model (after O2 optimization), not on the intermediate pre-optimization export. The full pipeline is: `torch.onnx.export` → `optimize_model()` (O2) → validate → atomic rename. This ensures O2 fusion passes haven't corrupted the scatter_reduce subgraph or altered output semantics.

After O2 optimization, the script automatically:
1. Loads the PyTorch wrapper and the **optimized** ONNX model
2. Runs individual test sentences (batch_size=1) through both, covering edge cases:
   - Standard English sentences (2-3)
   - Very short input (1-2 tokens after tokenization) — exercises scatter_reduce with minimal indices
   - Near-max-length input (~8192 tokens) — exercises truncation boundary
   - Multilingual input (CJK, Arabic) — validates non-ASCII code paths
   - Input with repeated tokens — exercises scatter_reduce amax pooling across duplicate input_ids
3. Runs a batch test (2-3 sentences as a single batch, batch_size > 1) through both PyTorch (stacked tensors) and ONNX (batched input). This verifies that dynamic batch dimensions propagate correctly through the scatter_reduce path — `torch.zeros(batch_size, vocab_size)` must trace as shape-dependent, not as a fixed `[1, vocab_size]` constant.
4. Asserts `allclose(atol=1e-4)` on all three outputs for every test (individual and batch)
5. Prints shape and value-range summaries
6. Writes SHA-256 checksums to `scripts/bge-m3-checksums.sha256` and prints them

Export fails if validation fails — no silent corruption.

### Idempotency

Before exporting, the script checks if `~/.hortora/models/bge-m3/model.onnx` exists. If found, it computes the SHA-256 checksum. If a prior checksum file exists at `scripts/bge-m3-checksums.sha256` and the hash matches, the script prints "Model already exported and verified" and exits 0. Otherwise it proceeds with a full re-export.

### Atomic Output

Export writes to a temporary directory (`~/.hortora/models/bge-m3/.export-tmp/`) and atomically renames to the final location on success. If the export fails mid-write (disk full, OOM during O2 optimization), the temp directory is cleaned up. This prevents partial 2.2GB files from causing cryptic ONNX Runtime load errors.

### Output

Writes to `~/.hortora/models/bge-m3/`:
- `model.onnx` — the three-head ONNX model (~2.2GB)
- `tokenizer.json` — copied from HuggingFace download cache

## download-models.sh Integration

Both `model.onnx` and `tokenizer.json` are produced by the export script. `download-models.sh` downloads **neither** — it only verifies that both files exist and match the committed checksums. This eliminates the dual-source version inconsistency that would arise from downloading `tokenizer.json` independently from HuggingFace (which could return a different revision than the one used during export).

The existing `download_file` calls for both `model.onnx` and `tokenizer.json` must be **removed**. The `model.onnx` download pointed to the backbone-only ONNX from `BAAI/bge-m3/onnx/model.onnx` which has a single `last_hidden_state` output and would crash `BgeM3Embedder`. Replaced with existence-and-checksum verification:
- Reads expected checksums from `scripts/bge-m3-checksums.sha256` (committed to git after first export)
- Both present and valid → skip
- Either missing or invalid → print instructions to run the export script, exit non-zero

The export script and download script are independent — no circular dependency. The export script writes checksums to `scripts/bge-m3-checksums.sha256` automatically; the developer commits that file after first successful export.

## Verification Strategy

### Level 1 — Export-time (automatic)

PyTorch vs ONNX output comparison on ~7 test sentences covering edge cases (see §Validation). `allclose(atol=1e-4)`. Built into `export_bge_m3.py` — export fails if verification fails.

### Level 2 — Engine dev mode (manual)

Start engine with real model (`quarkus:dev`, `%dev` properties uncommented). Index garden. Spot-check queries via `/search`. Confirms `OnnxInferenceModel` loads the model, `BgeM3Embedder` processes outputs, Qdrant stores/retrieves all four vector types.

### Level 3 — Benchmark (deferred)

Run existing benchmark harness (`scripts/benchmark/run_queries.py`) against BGE-M3-powered engine. Compare against 94% baseline from #27. This is #33/#34 scope — not part of this issue.

## Scope

### In Scope

- `scripts/bgem3_model.py` — PyTorch wrapper
- `scripts/export_bge_m3.py` — export entrypoint with validation
- `scripts/requirements-export.txt` — pinned Python dependencies for export
- `scripts/download-models.sh` — update for export-based flow (remove broken download, add existence check)
- `scripts/bge-m3-checksums.sha256` — machine-readable checksums (written by export script, committed to git)

### Not In Scope

- Java code changes — none needed
- Benchmark run — #33 territory
- Hosting ONNX on HuggingFace — local export (Approach A)
- `SeparateModelEmbedder` — separate neural-text issue
- ColBERT quantization / Matryoshka truncation — #34

## Risks

| Risk | Mitigation |
|------|------------|
| `scatter_reduce` with `reduce='amax'` may not export cleanly on opset 16 | Uses non-in-place `torch.scatter_reduce()` (better ONNX tracing than the in-place `scatter_reduce_()` variant). If opset 16 fails, escalate to opset 18 (broader `ScatterElements` coverage). Decision made at export time — failure is immediate, not silent. No manual loop fallback — if `scatter_reduce` doesn't export on opset 18, the issue is a PyTorch/ONNX version incompatibility requiring dependency version investigation. |
| Partial model file on export failure | Atomic write via temp directory + rename prevents partial 2.2GB artifacts (see §Atomic Output) |
| Model size ~2.2GB | Inherent to 550M params; quantization deferred to #34 |
| Export requires ~2.2GB PyTorch download | One-time; weights can be deleted after export |
| Export memory ~6–8GB peak | Documented in Developer Flow; O2 optimization is the peak phase |

## Garden Context

- **GE-20260630-db5dce**: BGE-M3 sparse post-processing — ReLU threshold, not log-saturation. Confirms sparse head uses `relu(linear(hidden))` not SPLADE's `log(1 + relu())`.
- **GE-20260629-10e3dc**: HuggingFace ONNX exports use original BERT input names. Sidestepped — our custom export names inputs correctly (`input_ids`, `attention_mask`).

## References

- [aapot/bge-m3-onnx](https://huggingface.co/aapot/bge-m3-onnx) — base wrapper and export script
- [philipchung/bge-m3-onnx](https://huggingface.co/philipchung/bge-m3-onnx) — alternative three-head export
- [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) — source model weights
- `docs/superpowers/specs/2026-06-30-bge-m3-adoption-design.md` — BGE-M3 adoption design
- `docs/comparison/hybrid-benchmark.md` — SPLADE benchmark results (#28)
