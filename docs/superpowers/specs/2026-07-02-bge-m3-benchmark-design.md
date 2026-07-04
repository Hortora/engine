# BGE-M3 Four-Signal Retrieval Benchmark — Design Spec

*2026-07-02 · Refs #36*

## Goal

Establish baseline retrieval metrics for the BGE-M3 four-signal pipeline (dense 1024-dim + learned sparse + BM25 + ColBERT reranking) using the 14 real-world scenarios from #27. Compare against the three-leg nomic-embed-text baseline (94% precision from #29).

This is a **pipeline-level go/no-go gate** for the BGE-M3 adoption (#32) — it measures whether the target pipeline as designed meets the quality bar. It is not a controlled model-vs-model comparison: the pipeline changes the dense model, sparse model, and adds ColBERT reranking simultaneously. Attributing precision changes to individual signals is deferred to #33 (signal isolation experiments).

If the benchmark shows precision below 94%, assess per-failure-mode data to identify which scenarios regressed and whether the regressions are addressable (e.g., scoring artifacts from unscored entries) before deciding whether to proceed with #33 signal isolation or revert to the three-leg pipeline.

This baseline feeds #33 (CC vs RRF fusion) and #34 (Matryoshka/ColBERT quantization).

## Prerequisite: Fix ONNX Export

The current `model.onnx` at `~/.hortora/models/bge-m3/` is 3.1MB — graph structure only, weights missing. `torch.onnx.export` for models >2GB requires `use_external_data_format=True` to produce `model.onnx` (graph) + `model.onnx.data` (weights, ~2.2GB).

### Changes to `scripts/export_bge_m3.py`

1. **`export_onnx()`** — add `use_external_data_format=True` to the `torch.onnx.export` call
2. **`check_idempotent()`** — also verify `model.onnx.data` exists and its checksum matches. Early-return requires all three files (`model.onnx`, `model.onnx.data`, `tokenizer.json`) present with matching checksums
3. **`write_checksums()`** — include `model.onnx.data` in `bge-m3-checksums.sha256`
4. **Move ordering in `main()`** — move `model.onnx.data` first (2.2GB weights), then `model.onnx` (3MB graph). If the process crashes between moves, the graph file is absent and `check_idempotent()` triggers re-export on next run. The reverse order (graph first) would leave a loadable-looking `model.onnx` pointing at missing weights — ONNX Runtime would crash on load

### Changes to `scripts/download-models.sh`

Add a verification block for `model.onnx.data` alongside the existing `model.onnx` check. Same checksum-based verification pattern.

### Java side

No changes needed. ONNX Runtime's `OrtSession(path)` automatically discovers `.data` files alongside the `.onnx` file. `casehub-inference-quarkus` and `BgeM3Embedder` work transparently with external data format.

## Benchmark Execution

### Harness

`run_queries.py` is model-agnostic — hits `/search?q=...&limit=8`. Config name is a label: `run_queries.py bge-m3-four-signal`.

28 queries (14 scenarios × KW + NL), 1 warmup pass + 3 measurement passes per query, 0.5s pause between queries. Median latency per query.

### Pre-run steps

1. Re-export model: `python scripts/export_bge_m3.py` (~3-5 min, ~8GB RAM)
2. Verify: `bash scripts/download-models.sh`
3. Uncomment `%dev` properties in `application.properties`:
   ```properties
   %dev.casehub.inference.models.bge-m3.model-path=${user.home}/.hortora/models/bge-m3/model.onnx
   %dev.casehub.inference.models.bge-m3.tokenizer-path=${user.home}/.hortora/models/bge-m3/tokenizer.json
   %dev.casehub.inference.models.bge-m3.maxSequenceLength=8192
   ```
4. Start fresh Qdrant: `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.18.0` (pinned — matches test infrastructure and ensures reproducibility across #33/#34 benchmarks)
5. Start engine: `./mvnw quarkus:dev`
6. Wait for indexing (harness polls via `wait_for_readiness`)
7. Run: `python scripts/benchmark/run_queries.py bge-m3-four-signal`

### Fresh Qdrant

Start with a clean Qdrant instance (no existing collections). Avoids migration confusion from old 768-dim nomic vectors. `CollectionMigration` creates the collection with BGE-M3's schema: 1024-dim dense + sparse named vectors + ColBERT multi-vectors + BM25 Document vectors.

### MIN_INDEXED_POINTS

`run_queries.py` uses `MIN_INDEXED_POINTS = 1900` as the readiness floor. This spec adds a `--min-points N` CLI argument that overrides the constant without modifying source.

Before running, compute the expected corpus size:

```
find ~/.hortora/garden -name '*.md' -not -name 'GARDEN.md' -not -name 'CHECKED.md' -not -name 'DISCARDED.md' -not -name 'INDEX.md' | wc -l
```

Pass 90% of the count as the threshold:

```
python scripts/benchmark/run_queries.py bge-m3-four-signal --min-points 1890
```

This avoids the harness starting on a partial corpus if the garden has grown past the hardcoded default.

### Output

`scripts/benchmark/results/bge-m3-four-signal.json` — same format as existing result files.

## Analysis: `analyze_bge_m3.py`

New script in `scripts/benchmark/`. Imports utility functions from `analyze.py` — no duplication.

### Imports from `analyze.py`

- `compute_delta(baseline_entries, hybrid_entries)` — shared/new/lost entry computation
- `compute_precision(scores, threshold=1)` — fraction of entries above score threshold
- `compute_rr(scores)` — reciprocal rank for first score ≥ 2
- `find_result(results, scenario_id, query_type)` — extract entries for a scenario/query pair
- `extract_ge_id(path)` — normalise entry IDs
- `load_baseline_scores()` — ground truth relevance judgments
- `load_results(config_name)` — load a result JSON file

### Logic

For each of 14 scenarios × 2 query types:

1. Load BGE-M3 four-signal results (`load_results("bge-m3-four-signal")`) and three-leg results (`load_results("three-leg")` — existing `scripts/benchmark/results/three-leg.json`)
2. Compute delta (shared/new/lost entries vs three-leg)
3. Look up scores from `baseline_scores.json` for all retrieved entries
4. Compute precision (score ≥ 1) and MRR (score ≥ 2)
5. Collect unscored entries (score lookup returns None)

Aggregate:
- Overall precision (BGE-M3 vs three-leg 94%)
- Per-failure-mode breakdown (VOCABULARY_GAP, SEMANTIC_WIN, POLYSEMY, UNAMBIGUOUS_TERM, DOMAIN_ABSENCE)
- Per-query-type (KW vs NL)
- Latency comparison (BGE-M3 median vs three-leg 256ms)

### Unscored entries

Entries not in `baseline_scores.json` are written to `scripts/benchmark/results/bge-m3-to-score.json` in the same format as existing `to_score.json`:

```json
{
  "scenario-id": {
    "context": "scenario description for scoring calibration",
    "entries": [
      {"ge_id": "GE-...", "query_type": "KW", "config": "bge-m3-four-signal", "title": "...", "body": "first 500 chars"}
    ]
  }
}
```

Precision is computed from scored entries only. The report notes the unscored count as a caveat.

### Sparse Signal Expectations

BGE-M3 sparse uses XLM-RoBERTa tokenizer (250K vocab) which fragments Java identifiers into subword tokens, the same fundamental limitation as SPLADE's BERT tokenizer. The model was trained on general-domain multilingual web text, not code. The larger vocabulary (8× BERT's 30K) may capture more subwords but does not fundamentally solve the domain vocabulary gap — `DefaultBean`, `ConcurrentHashMap`, and method signatures will still fragment.

BGE-M3 sparse's value in this pipeline is complementary, not domain-targeted: it provides learned term importance weighting that may catch general-language patterns BM25's exact matching misses. BM25 (via CamelCaseExpander + Qdrant Document vectors) is expected to remain the dominant lexical signal for Java vocabulary, consistent with the #28 benchmark finding that BM25 was the primary contributor to closing the keyword gap.

The sparse signal is essentially free — it comes from the same forward pass as dense and ColBERT. If it adds marginal value, it justifies inclusion. If it is inert (similar to SPLADE's weak contribution), that is an expected outcome, not a surprise.

### Report: `docs/comparison/bge-m3-benchmark.md`

Structure:

1. **Configuration** — BGE-M3 model details, vector dimensions, RRF params, Qdrant version, indexed points
2. **Executive Summary** — headline precision vs 94% three-leg baseline, key finding in 2-3 sentences
3. **Headline Results** — table: scenario | failure mode | three-leg precision | BGE-M3 precision | delta
4. **Per-Failure-Mode Analysis** — precision delta by failure mode (VOCABULARY_GAP, SEMANTIC_WIN, POLYSEMY, UNAMBIGUOUS_TERM, DOMAIN_ABSENCE). Observational breakdown only — since this benchmark changes the entire pipeline (dense model, sparse model, and adds ColBERT reranking), precision changes cannot be attributed to individual signals. Causal attribution is #33 scope.
5. **Latency** — table: config | median | overhead. Reference the adoption spec's 50% regression criterion: three-leg baseline is 256ms, budget is 384ms. If exceeded, document actual regression and contributing factors (550M model on CPU, ColBERT reranking stage). Latency optimization is #34 scope (quantization, GPU offload).
6. **Regressions** — any scenario where BGE-M3 does worse, with explanation
7. **Caveats** — the three-leg 94% baseline was computed from scored entries only (87 entries from the three-leg run are unscored, per retrieval-research.md). BGE-M3 precision uses the same scored-only methodology, so the comparison is methodologically consistent even if absolute precisions may adjust once all entries are scored. Also note corpus size delta vs three-leg run and unscored entry count for both pipelines.
8. **What Comes Next** — pointers to #33 and #34

## Testing: `test_analyze_bge_m3.py`

Matching the established pattern (`test_analyze.py` tests `analyze.py`).

1. **Delta computation** — canned BGE-M3 and three-leg results for 2-3 scenarios. Verify shared/new/lost entry classification.
2. **Precision with partial scores** — mix of scored and unscored entries. Verify precision computed from scored entries only, unscored count correct.
3. **MRR calculation** — verify reciprocal rank computation with score ≥ 2 threshold.
4. **Unscored entry collection** — verify entries not in `baseline_scores.json` are collected into to-score format with correct fields (`ge_id`, `query_type`, `config`, `title`, `body`).
5. **Empty results** — scenario with no BGE-M3 results. Verify graceful handling.
6. **Report structure** — verify generated report contains required sections (configuration, headline results, per-failure-mode, latency, caveats).

## Out of Scope

- **Signal isolation** (dense-only, dense+sparse configs) — #33
- **Scoring unscored entries** — manual, post-report
- **Changes to `analyze.py`** — old pipeline untouched, old report is committed artifact for #28
- **Changes to neocortex or casehub-inference** — Java side handles external ONNX data transparently
- **ColBERT quantization** — #34

## Garden Context

- **GE-20260630-db5dce** — BGE-M3 sparse post-processing uses ReLU threshold, not log-saturation like SPLADE
- **GE-20260701-f7e1d5** — ColBERT ONNX output must include CLS token for batch inference
