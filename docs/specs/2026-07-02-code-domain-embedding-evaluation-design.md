# Code-Domain Embedding Model Evaluation

**Issue:** casehubio/neural-text#49
**Date:** 2026-07-02
**Status:** Draft

## Context

Hortora/engine#27 identified a vocabulary gap: general-purpose embedding models treat
Java class names (`ConcurrentHashMap`, `@DefaultBean`, `ExceptionMapper`) as generic
tokens. BERT WordPiece splits them into meaningless subwords (`con`, `##current`,
`##hash`, `##map`), degrading both dense and sparse retrieval for code-domain corpora
like the knowledge garden.

Since #49 was filed, two significant changes occurred:
1. **Three-leg retrieval** — the current production system uses nomic-embed-text (dense,
   768-dim, Ollama) + Splade_PP_en_v1 (sparse, ONNX) + BM25 (Qdrant Document vectors)
   via server-side RRF fusion. This pushed precision from 45% to 94%, with BM25 doing
   the heavy lifting on keyword-gap scenarios (Hortora/engine#29 research →
   neural-text#47/#48 implementation; benchmark in `retrieval-research.md` line 27).
2. **BGE-M3 planned** (neural-text#30, still open) — will replace the three-model stack
   with a single model producing dense (1024d) + sparse + ColBERT embeddings. Uses
   XLM-RoBERTa BPE tokenizer (SentencePiece), not BERT WordPiece. Not yet deployed.

The 94% precision figure comes from the current nomic+SPLADE+BM25 system, documented in
`~/claude/hortora/engine/docs/comparison/retrieval-research.md` and
`~/claude/hortora/engine/docs/comparison/hybrid-benchmark.md`. Methodology: 14 scenarios
(6 real issues + 2 spec domains × keyword + natural-language queries) scored against
human-judged relevance baselines from Hortora/engine#27.

**Sequencing rationale:** `retrieval-research.md` line 165 says "Evaluate [code-domain
models] only if BGE-M3 dense doesn't handle Java identifiers." This evaluation runs the
two questions simultaneously rather than sequentially: it includes BGE-M3 as a baseline
alongside nomic-embed-text, so Layers 1–3 answer both "does BGE-M3 improve over
nomic-embed-text on Java identifiers?" AND "does any code-domain model beat BGE-M3?"
in a single evaluation pass. If BGE-M3 proves sufficient, the evaluation concludes
without needing a second round.

This evaluation answers: does BGE-M3's tokenizer handle Java identifiers better than
nomic's WordPiece? Can a code-domain model beat BGE-M3's dense embeddings? And does
any dense-only model approach what the current three-leg system already delivers?

## Goal

Research and evaluation only. Build understanding of how tokenization, training data,
and model architecture affect code-domain retrieval quality. Produce reusable evaluation
scripts and a findings report. No code changes to neocortex inference/rag modules.

## Model Roster

### Baselines
| Model | Params | Tokenizer | Training data | Dimensions | Status |
|-------|--------|-----------|---------------|------------|--------|
| nomic-embed-text | 137M | BERT WordPiece | Web text | 768 (dense) | **Current** — deployed via Ollama |
| BGE-M3 | ~568M | XLM-RoBERTa BPE | Web + multilingual | 1024 (dense+sparse+ColBERT) | **Planned** — #30 open |

### Original #49 candidates (continuity)
| Model | Params | Tokenizer | Training data | Dimensions |
|-------|--------|-----------|---------------|------------|
| CodeBERT | 125M | RoBERTa BPE | CodeSearchNet (6 languages) | 768 |
| UniXcoder | 125M | RoBERTa BPE | Code + NL pairs | 768 |
| jina-embeddings-v2-base-code | 161M | JinaBERT ALiBi | 150M code pairs | 768 |
| nomic-embed-text-v1.5 | 137M | — | Web + code mix | 768 |

### Skyline (upper bound, not deployable)
| Model | Params | Tokenizer | Training data | Dimensions |
|-------|--------|-----------|---------------|------------|
| [nomic-embed-code](https://huggingface.co/nomic-ai/nomic-embed-code) | 7B | Qwen BPE | CoRNStack (code+docstrings) | 768 |

Source: `retrieval-research.md` §Code-Domain Embedding Models. Model metadata will
be verified against the HuggingFace model card before Layer 1 execution — if any
field (param count, tokenizer, dimensions) differs, the roster is updated.

The skyline model sets an upper bound. If the 7B code model doesn't beat BGE-M3 on
Java identifiers, no code-domain model will. If it does, the gap quantifies what
deployable models leave on the table.

**Execution environment:** Skyline evaluation requires GPU (7B at float16 ≈ 14GB VRAM).
If no GPU is available, the skyline model is evaluated via the `nomic` API or excluded
with a note in the report explaining the gap.

## Evaluation Layers

### Layer 1 — Tokenizer Analysis

Tokenize Java identifiers from the garden benchmark scenarios and compare how each
model's tokenizer splits them. Both baselines are included: nomic-embed-text (BERT
WordPiece, current) and BGE-M3 (XLM-RoBERTa BPE, planned). Candidates must improve
upon at least the current baseline; improvement over BGE-M3 is a higher bar.

**Test vocabulary:**
- Class names: `ConcurrentHashMap`, `CopyOnWriteArrayList`, `ExceptionMapper`,
  `ChatModel`, `InMemoryWorkItemTemplateStore`
- CDI annotations: `@DefaultBean`, `@Alternative`, `@ApplicationScoped`, `@Priority`
- Framework identifiers: `AmbiguousResolutionException`, `websearch_to_tsquery`,
  `quarkus.datasource.active`
- Compound concepts: `shadowing`, `fireAsync`, `doChat`

**Measurements:**
- Token count per identifier (fewer = better preservation)
- Whether meaningful subwords survive (e.g. does `HashMap` stay intact?)
- Tokenizer type classification: WordPiece, BPE, SentencePiece, Unigram
- Delta vs baselines: which candidates produce fewer tokens or preserve more
  meaningful subwords than nomic-embed-text (current)? Than BGE-M3 (planned)?

**Output:** Table of model × identifier → token sequence, with nomic-embed-text and
BGE-M3 as the first two rows. Diagnostic — explains the mechanism of vocabulary gap
and whether BGE-M3's BPE tokenizer already resolves it. No models eliminated at this
stage.

### Layer 2 — Embedding Discrimination

Quick proxy: can each model distinguish semantically different Java concepts that share
surface-level tokens?

**"Should be far" pairs:**
- `ConcurrentHashMap` vs `HashMap`
- `@DefaultBean` vs `default`
- `ChatModel` vs `chat`
- `shadowing` vs `Shadow DOM`
- `stream` (Java Streams) vs `stream` (SSE/HTTP streaming)

**"Should be close" pairs:**
- `@DefaultBean` vs `CDI ambiguous dependency resolution`
- `ConcurrentHashMap` vs `thread-safe map for lock-free concurrency`
- `ExceptionMapper` vs `JAX-RS error handling`

**Polysemy pairs** (same token, different Java concepts — should be far):
- `Optional.map()` vs `HashMap.put()` — "map" as functional transform vs data structure
- `@Inject` (CDI dependency injection) vs `inject` (security context, SQL injection)

**Measurements:**
- Cosine similarity for each pair
- Delta between "far" and "close" groups — a model with good code-domain understanding
  produces a wide gap; a model treating Java identifiers as generic English produces a
  narrow gap or inverts the expected ordering

**BGE-M3 multi-modal baseline:** In addition to dense similarity, test BGE-M3's sparse
and ColBERT outputs on the same pairs:
- **Sparse:** Do BGE-M3 learned sparse weights for `ConcurrentHashMap` activate on
  `concurrent`, `hash`, `map` tokens? If sparse already disambiguates, the dense gap
  matters less for end-to-end retrieval.
- **ColBERT:** Does token-level MAX_SIM scoring on the "should be far" pairs produce
  better separation than dense cosine? ColBERT preserves subword relationships that
  dense pooling destroys.

**Decision criteria:** First, measure nomic-embed-text's cosine similarity on the
`@DefaultBean` / `default` pair as the vocabulary-gap reference score. Models
producing "should be far" scores within ±0.05 of nomic-embed-text's score on this
pair are flagged as having an equivalent vocabulary gap. This calibrates the
threshold to observed data rather than an arbitrary number. Flagged models are not
automatically eliminated — Layer 3 tests whether the gap matters for retrieval.

**Output:** Similarity scores per model per pair, with nomic-embed-text and BGE-M3
dense/sparse/ColBERT as baseline rows.

### Layer 3 — Full Benchmark

Reuse the Hortora engine's 14-scenario harness with existing scored judgments.

**Corpus snapshot:** Before any model evaluation begins, export the garden corpus to a
frozen snapshot directory (`evaluation/code-domain-embeddings/corpus-snapshot/`). All
models embed this same snapshot. The snapshot records entry count and export timestamp
in a manifest file. This ensures cross-model comparisons are valid even if the garden
grows during evaluation.

**Method:**
1. Embed all garden entries from the frozen snapshot + 28 queries (14 scenarios × KW + NL) per model
2. Cosine similarity retrieval, top-20 per query
3. Score against human-judged baseline (0/1/2 scores from `baseline_scores.json`)
4. Calculate precision metrics matching Hortora/engine#27 methodology

**Comparisons:**
- Each candidate dense-only vs nomic-embed-text dense-only vs BGE-M3 dense-only
  (isolates embedding quality across current, planned, and candidate models)
- Each candidate dense-only vs BGE-M3 three-head (dense+sparse+ColBERT) — answers
  "can a better dense model match multi-modal retrieval?"
- **Each candidate dense + BM25 via RRF** vs **nomic dense + BM25** vs **BGE-M3
  dense + BM25** — answers the deployment question: "would swapping the dense model
  into `HybridCaseRetriever` improve end-to-end retrieval?" This uses
  CamelCaseExpander-processed BM25 matching alongside each dense model.
- **Current three-leg baseline** (nomic + SPLADE + BM25 = 94%) as the reference ceiling
- Breakdown by failure mode: VOCABULARY_GAP / POLYSEMY / SEMANTIC_WIN / DOMAIN_ABSENCE

**Why the sparse leg is omitted from the pipeline comparison:** Code-domain models
produce dense-only embeddings. In any deployment, the sparse model (SPLADE currently,
BGE-M3 sparse after #30) would be the same regardless of which dense model is used.
Adding identical sparse scores to both sides of a comparison shifts both scores equally
and does not change which dense model wins. The dense + BM25 comparison isolates the
variable that actually differs between candidates.

**Key questions:**
1. Does BGE-M3 dense itself improve over nomic-embed-text dense on vocabulary-gap
   scenarios? (If yes, BGE-M3 adoption alone may suffice.)
2. Does any code-domain model beat BGE-M3 dense? (If no, code-domain models add no value.)
3. Does the best candidate dense + BM25 approach the current three-leg 94% baseline?

**Decision criteria:** A candidate advances to Layer 4 if it demonstrates at least one of:
- Higher precision than BGE-M3 dense-only on VOCABULARY_GAP scenarios
- Higher end-to-end precision than BGE-M3 dense + BM25 on any scenario class
- Comparable precision with materially lower dimensionality or inference cost

If BGE-M3 dense-only already closes the vocabulary gap (Layer 1 tokenization +
Layer 2 discrimination confirm this), and no candidate exceeds it, the evaluation
concludes with "adopt BGE-M3, skip code-domain models" — no Layer 4 needed.

**Output:** Precision table per model per scenario, with aggregate scores and
failure-mode breakdowns. Directly comparable to Hortora/engine#27 baseline.

**Data sources:**
- Garden corpus: `$GARDEN_PATH` (default: `~/.hortora/garden/`)
- Queries: `$BENCHMARK_PATH/queries.py` (default: `~/claude/hortora/engine/scripts/benchmark/queries.py`)
- Baseline scores: `$BENCHMARK_PATH/baseline_scores.json` (default: `~/claude/hortora/engine/scripts/benchmark/baseline_scores.json`)

### Layer 4 — Deployment Feasibility

For models meeting Layer 3 decision criteria, assess practical deployment on JVM.

**Prerequisites:** #51 (BERT input name compatibility) and #52 (rank-3 SPLADE output)
are currently open. Layers 1–3 use Python `transformers`/`sentence-transformers`
directly and are unaffected by these issues. Layer 4 is where `OnnxInferenceModel`
compatibility matters — the ONNX export check below tests whether a candidate model's
input tensor names (`input_mask` vs `attention_mask`, `segment_ids` vs
`token_type_ids`) would require #51's fix.

**Checks:**
- ONNX export: clean export? What opset? Custom ops that break ONNX Runtime JVM?
- Model size: ONNX file on disk, memory footprint at inference
- Inference latency: time per embedding on CPU (single text, batch of 20)
- Input constraints: max sequence length, required input tensor names — document
  whether each candidate uses BERT-convention names (blocked by #51) or
  HuggingFace-convention names (works today)
- **Integration path:** Code-domain models produce dense-only embeddings. Deployment
  in `HybridCaseRetriever` requires `SeparateModelEmbedder` (#61) — an adapter
  wrapping the code-domain model for dense and delegating sparse/ColBERT to BGE-M3.
  This means running two models at inference time. Layer 4 must assess:
  - Combined memory footprint (candidate + BGE-M3)
  - Combined latency (serial or parallel inference)
  - Whether the marginal dense improvement from Layer 3 justifies doubling model count

**Output:** Feasibility table per viable candidate with size, latency, ONNX compatibility,
`OnnxInferenceModel` input name compatibility, `SeparateModelEmbedder` integration
assessment, and blockers.

## Deliverables

### Evaluation scripts
```
evaluation/code-domain-embeddings/
├── tokenizer_analysis.py         # Layer 1
├── embedding_discrimination.py   # Layer 2
├── benchmark_runner.py           # Layer 3
├── deployment_check.py           # Layer 4
├── snapshot_corpus.py            # Corpus snapshot export
└── requirements.txt
```

Self-contained. Configurable via environment variables:
- `GARDEN_PATH` — garden corpus (default: `~/.hortora/garden/`)
- `BENCHMARK_PATH` — benchmark scripts directory (default: `~/claude/hortora/engine/scripts/benchmark/`)

Python-only — uses `transformers` and `sentence-transformers` for model loading.
Does not use `OnnxInferenceModel`; JVM compatibility is assessed in Layer 4 only.

### Results data
```
evaluation/code-domain-embeddings/results/
├── tokenizer_splits.json
├── discrimination_scores.json
├── benchmark_precision.json
└── deployment_feasibility.json
```

Machine-readable for future model comparisons.

### Research report
```
evaluation/code-domain-embeddings/REPORT.md
```

Synthesizes all four layers. Written after evaluation runs. Answers: adopt a code-domain
model, supplement BGE-M3, or stay with current multi-leg retrieval?

## Non-goals

- No changes to `inference-*`, `rag-*`, or `memory-*` modules
- No ONNX model files committed (too large — download instructions in scripts)
- No fine-tuning or training — evaluation of existing pre-trained models only
- No production deployment — that's a separate branch if a winner emerges

## Decision Framework

This evaluation produces data and a recommendation. The three possible outcomes:

1. **Adopt a code-domain model** — a candidate beats BGE-M3 dense on vocabulary-gap
   scenarios AND the improvement persists in the full pipeline (candidate + BM25 vs
   BGE-M3 + BM25). Layer 4 confirms deployment is feasible via `SeparateModelEmbedder`.
2. **Supplement BGE-M3** — BGE-M3 dense handles Java identifiers adequately (XLM-RoBERTa
   BPE is better than BERT WordPiece), but a code-domain model adds marginal value on
   specific scenario classes. Worth the two-model cost only if the gap is significant.
3. **Stay with current multi-leg retrieval** — the current system (nomic-embed-text +
   SPLADE + BM25) already delivers 94% precision. If BGE-M3 adoption (#30) further
   improves the dense leg, there is even less room for a code-domain model to add value.
   No code-domain model improves end-to-end retrieval enough to justify added complexity.
   This is the most likely outcome and is a valid, defensible conclusion.

Specific numeric thresholds are not set a priori — the evaluation is research, not a
go/no-go gate. The report will present the data and argue for one of the three outcomes
based on the observed results.

## References

- Garden entry: GE-20260627-4712de — vocabulary gap diagnosis
- Hortora/engine#27: dense-only baseline benchmark (`docs/comparison/real-world-benchmark.md`)
- Hortora/engine#28: SPLADE hybrid benchmark (`docs/comparison/hybrid-benchmark.md`)
- Hortora/engine#29: complementary retrieval research → three-leg validation (`docs/comparison/retrieval-research.md`)
- neural-text#47: Qdrant full-text index
- neural-text#48: BM25 as third RRF retrieval leg
- neural-text#30: BGE-M3 single-model multi-mode retrieval
- neural-text#51: OnnxInferenceModel BERT input name compatibility (open — Layer 4 dependency)
- neural-text#52: OnnxInferenceModel rank-3 SPLADE output (open — Layer 4 dependency)
- neural-text#61: SeparateModelEmbedder adapter (open — Layer 4 integration path)
- Benchmark scripts: `~/claude/hortora/engine/scripts/benchmark/`
- BGE-M3 integration: `inference-bge-m3/` module in this repo
