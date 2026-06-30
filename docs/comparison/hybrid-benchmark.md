# Hybrid Benchmark: Dense-Only vs SPLADE vs Full Hybrid

*2026-06-29 · Refs #28*

## Configuration

| Parameter | Value |
|-----------|-------|
| Dense embedding model | nomic-embed-text (Ollama, 768-dim, cosine) |
| SPLADE model | prithivida/Splade_PP_en_v1 (DistilBERT, MS MARCO) |
| Cross-encoder | cross-encoder/ms-marco-MiniLM-L-6-v2 (MS MARCO) |
| RRF parameters | denseTopK=40, sparseTopK=40, k=60 |
| Reranker | rerankTopN=10, rerankEnabled=true |
| Sparse threshold | 0.01 |
| Indexed points | 1,984 (dense-only), 1,994 (dense+splade) |
| HNSW | Below threshold (1,994 < 10,000) — brute-force scan |

**Configs tested:**
- `dense-only` — baseline (nomic-embed-text only)
- `dense+splade` — RRF fusion of dense + SPLADE sparse vectors
- `full-hybrid` — dense + SPLADE + cross-encoder reranker (**crashed after 1 query**)

## Executive Summary

**SPLADE with `Splade_PP_en_v1` does not fix the keyword catastrophe.** The model
has zero Java/CDI domain vocabulary — it expands `ChatModel` to "hotel, beauty,
renovation" and `DefaultBean` to "ambiguity, unclear." The BERT WordPiece tokenizer
fragments Java class names into meaningless subwords before SPLADE even runs.

**The hybrid search integration was never end-to-end tested.** Two ONNX model
incompatibilities blocked startup: non-standard input names (neural-text#51) and
rank-3 output tensor (neural-text#52). Both required local workarounds to proceed.

**The real fix is BM25 keyword matching** (neural-text#47/#48), not a better sparse
model from the same web-trained architecture. Issues filed and work started in
neural-text.

## Key Findings

### 1. SPLADE Has Zero Java Domain Vocabulary

The SPLADE vocabulary analysis ran all 28 queries through the ONNX model and decoded
the activated tokens. Results:

- **Zero Tier 1 domain hits** across all 14 KW queries. Not one activation of
  `panache`, `jandex`, `qualifier`, `interceptor`, `singleton`, `persist`, or any
  unambiguous Java/CDI term.
- **SPLADE expansions are web-search associations:**

| Query | Top SPLADE Expansions | Expected (CDI domain) |
|-------|----------------------|----------------------|
| `DefaultBean\|AmbiguousResolutionException` | ambiguity, groups, unclear | cdi, qualifier, alternative, inject |
| `ChatModel\|AgentSession\|prompt.cach\|LangChain4j` | agent, talk, beauty, renovation, genre | model, inference, provider, adapter |
| `ChatModel\|doChat\|StreamingChatModel` | stream, renovation, hotel, beauty | adapter, provider, chat, response |
| `Priority(100)\|CDI priority` | priorities, cds (music), urgency, precedence | qualifier, scope, alternative, bean |

- **Tokenizer fragmentation compounds the problem:** `AmbiguousResolutionException`
  becomes `["am", "##bi", "##guous", "##reso", "##lution", "##exception"]`. The
  semantic content is destroyed before SPLADE runs.

**Diagnosis:** This is not a model defect — it is expected behavior for a model
trained on MS MARCO web passages with a general BERT tokenizer. The fix requires
either a code-domain model (neural-text#49) or a fundamentally different retrieval
method (BM25, neural-text#47/#48).

### 2. SPLADE Churns Results Without Improving Quality

Dense+SPLADE changed **28/28 query results** compared to dense-only. On average,
~50% of entries changed per query (108 shared, 116 new, 115 lost across 224 total
entries).

**What SPLADE displaced** (from entries with known #27 scores):

| Lost entries | Count | Assessment |
|-------------|-------|------------|
| Score 2 (highly relevant) | 3 | **Regression** — SPLADE pushed out entries that would have influenced the work |
| Score 1 (tangentially relevant) | 17 | Moderate regression |
| Score 0 (noise) | 40 | **Good** — noise removed |
| Unscored (not in #27) | 55 | Unknown — from corpus drift between #27 and rerun |

SPLADE is not selective — it churns the entire result set because its generic
web-domain sparse vectors interfere with the dense cosine similarity ranking via
RRF fusion. The noise removal (40 score-0 entries) is offset by the loss of 3
highly relevant and 17 tangentially relevant entries.

### 3. KW Embedding Is Unstable

The dense-only re-run (same engine, same model, same queries as #27) showed:

- **NL queries: 92% overlap** with #27 — stable and reproducible
- **KW queries: 24% overlap** with #27 — 76% of results changed

The corpus grew by only 24 entries (1,960 → 1,984). For NL queries, this caused
minimal drift. For KW queries (pipe-separated Java class names), it caused near-
complete result set replacement.

**Diagnosis:** Pipe-separated keyword strings produce embeddings near the decision
boundary in the vector space. Small corpus changes shift which entries are nearest
neighbors. This is a second failure mode on top of the vocabulary gap — keyword
embedding is not just low-quality, it is *unreliable*.

### 4. ONNX Integration Was Never End-to-End Tested

Two incompatibilities blocked engine startup with the SPLADE model:

| Issue | Problem | Workaround |
|-------|---------|------------|
| neural-text#51 | Model uses `input_mask`/`segment_ids` (original BERT), runtime expects `attention_mask`/`token_type_ids` (HuggingFace) | Renamed inputs in ONNX graph |
| neural-text#52 | Model outputs rank-3 `[batch, seq_len, vocab]`, runtime expects rank-2 `[batch, vocab]` | Added ReduceMax node to ONNX graph |

Both are `OnnxInferenceModel` validation issues in neural-text. The Phase 2 hybrid
search code (CDI wiring, `HybridSearchProducer`, `CollectionMigration`) is correctly
implemented — the models simply never loaded.

### 5. Full Hybrid Crashes the Engine

With both SPLADE and cross-encoder enabled, the engine crashed after the first
measurement query:

- Warmup: 28 queries completed (reranker working)
- First measurement: issue-1/KW at **212.7ms** (vs 45ms dense+splade, 29ms dense-only)
- Second query: "Remote end closed connection" → all subsequent queries failed

The cross-encoder adds ~170ms per query (scoring 10 candidates). Combined with
SPLADE inference and dense embedding, the resource pressure crashes the JVM in
dev mode.

## Latency Comparison

| Config | Median | Range | Overhead |
|--------|--------|-------|----------|
| dense-only | 28ms | 22–36ms | baseline |
| dense+splade | 43ms | 30–53ms | +15ms (SPLADE inference + RRF) |
| full-hybrid | 213ms* | — | +170ms (cross-encoder on 10 candidates) |

*Single data point before crash.

**Scale caveat:** With 1,994 points below Qdrant's HNSW threshold (10,000), all
retrieval uses brute-force scan. These latency numbers are not extrapolatable to
larger corpora.

## Phase 3 — Three-Leg Retrieval (dense + SPLADE + BM25)

*2026-06-30 · Refs #29*

BM25 was implemented as a third RRF retrieval leg via Qdrant-native Document vectors
(v1.18+), not the originally planned Java-side RRF with in-process index. All three
legs fuse inside Qdrant in a single gRPC call. `CamelCaseExpander` preprocesses text
at ingestion so BM25 tokenizes `DefaultBean` → `Default Bean DefaultBean`.

### Configuration

| Parameter | Value |
|-----------|-------|
| Dense embedding model | nomic-embed-text (Ollama, 768-dim, cosine) |
| SPLADE model | prithivida/Splade_PP_en_v1 (DistilBERT, MS MARCO) |
| Cross-encoder | cross-encoder/ms-marco-MiniLM-L-6-v2 (MS MARCO) |
| BM25 | Qdrant Document vectors (`qdrant/bm25` model) |
| Named vectors | `dense`, `sparse` (SPLADE), `bm25` |
| RRF | Qdrant-native, three prefetch legs |
| Indexed points | 2,026 |

### Headline Results

**Overall precision: 45% → 94%.** BM25 closes the keyword gap.

| Scenario | Dense | Three-leg | Delta | Failure mode |
|----------|-------|-----------|-------|-------------|
| issue-2-cdi-wiring/KW | 0% | 100% | +100pp | VOCABULARY_GAP |
| issue-5-ai-llm-inference/KW | 0% | 100% | +100pp | VOCABULARY_GAP |
| issue-5-ai-llm-inference/NL | 17% | 100% | +83pp | VOCABULARY_GAP |
| spec1-d1-cdi-priority-tiers/KW | 0% | 100% | +100pp | VOCABULARY_GAP |
| spec1-d2-thread-safety/KW | 50% | 100% | +50pp | UNAMBIGUOUS_TERM |
| spec2-d1-cdi-tier-coexistence/KW | 40% | 100% | +60pp | VOCABULARY_GAP |
| spec2-d2-chatmodel-adaptation/KW | 50% | 100% | +50pp | VOCABULARY_GAP |
| spec2-d2-chatmodel-adaptation/NL | 50% | 100% | +50pp | VOCABULARY_GAP |

Keyword-gap average: 43% → 89%. Non-keyword-gap: 47% → 98% (zero regressions).

### BM25's Marginal Contribution Beyond SPLADE

Comparing three-leg vs dense+splade isolates what BM25 adds:

| Scenario | Dense+SPLADE | Three-leg | BM25 added |
|----------|-------------|-----------|------------|
| issue-2-cdi-wiring/KW | 57% | 100% | +43pp |
| spec1-d1-cdi-priority-tiers/KW | 50% | 100% | +50pp |
| spec1-d4-protocol-compliance/NL | 50% | 100% | +50pp |
| spec2-d3-circular-deps/KW | 60% | 100% | +40pp |

BM25 is the dominant contributor. SPLADE alone did not close the keyword gap.

### Latency

| Config | Median | Overhead |
|--------|--------|----------|
| dense-only | 28ms | baseline |
| dense+splade | 43ms | +15ms |
| three-leg | 256ms | +228ms (BM25 + three-way RRF) |

256ms is 9.1x dense-only but still sub-second — acceptable for AI assistant retrieval.

### Caveats

- 87 new entries are unscored. True precision may be higher once scored.
- Corpus grew 1,984 → 2,026 between runs.

Raw results: `scripts/benchmark/results/three-leg.json`

## What Comes Next

### Completed (neural-text):

| # | Issue | Status |
|---|-------|--------|
| #47 | Qdrant full-text index on content | ✅ Closed |
| #48 | BM25 as third RRF retrieval leg | ✅ Closed |
| #50 | Keyword payload indexes | ✅ Closed |
| #53 | CamelCase tokenizer | ✅ Closed |
| #54 | Metadata validation | ✅ Closed |

### Outstanding (neural-text):

| # | Issue | Priority | Notes |
|---|-------|----------|-------|
| #51 | OnnxInferenceModel input name validation | Low | Workaround in download-models.sh |
| #52 | OnnxInferenceModel rank-3 output support | Low | Workaround in download-models.sh |
| #46 | SPLADE/reranker tuning | Low | BM25 solved the problem SPLADE couldn't |
| #49 | Code-domain embedding model evaluation | Deferred | Evaluate only if BGE-M3 doesn't handle Java identifiers |

### Architecture roadmap:

See `docs/comparison/retrieval-research.md` for the full literature survey and track 3
recommendations (BGE-M3 adoption, ColBERT reranking, convex combination fusion).

## Methodology

Design spec: `docs/superpowers/specs/2026-06-28-splade-hybrid-benchmark-design.md`

Benchmark harness: `scripts/benchmark/` — query runner, SPLADE vocabulary analyzer,
delta analysis with report generation. Results committed in `scripts/benchmark/results/`.

Single-axis isolation: dense-only → dense+splade isolates SPLADE's contribution.
Full-hybrid (SPLADE + reranker) was tested but crashed — reranker contribution not
measurable with current setup.

Baseline scores from #27 report, extracted via `extract_baseline.py` (296 GE-IDs,
370 entry-scenario pairs). Dense-only consistency check showed 92% NL overlap, 24%
KW overlap with #27 — KW embedding instability documented as a finding.

## Related Issues

- **#27** — dense-only real-world benchmark (baseline)
- **#29** — complementary retrieval capabilities (informed by these findings)
- **casehubio/neural-text#46** — SPLADE model quality and RRF tuning
