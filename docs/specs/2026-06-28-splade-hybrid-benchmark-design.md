# SPLADE Hybrid Benchmark — Design Spec

*2026-06-28 · Refs #28*

## S1: Context

Issue #27 benchmarked gardenSearch (dense-only, nomic-embed-text) vs grep across
14 real-world scenarios. Results: gardenSearch-NL tied grep 6-6-2;
gardenSearch-KW was catastrophic (lost 12/14). The keyword failures stem from
nomic-embed-text treating Java class names and CDI annotations as generic tokens.

The engine already has Phase 2 hybrid search — SPLADE sparse embeddings + cross-encoder
reranking via `casehub-inference-quarkus`, which bundles `SparseEmbedder`
(from `casehub-inference-splade`) and `CrossEncoderReranker`
(from `casehub-inference-tasks`), both running on ONNX Runtime.

Models:
- SPLADE: `prithivida/Splade_PP_en_v1` (DistilBERT-based, trained on MS MARCO passage retrieval)
- Cross-encoder: `cross-encoder/ms-marco-MiniLM-L-6-v2` (MiniLM, trained on MS MARCO passage retrieval)

Both are general-domain web-search models. Neither has been exposed to Java, CDI,
Quarkus, or code-domain vocabulary during training. Domain vocabulary coverage
gaps are expected behavior for these models, not defects.

This benchmark measures whether hybrid search fixes the failures and where it
doesn't, diagnoses why.

## S2: Goal

Answer four questions with data, not speculation:

1. Does SPLADE fix the gardenSearch-KW catastrophe?
2. Does the cross-encoder reranker improve gardenSearch-NL precision above 62%?
3. Does hybrid search shift the 6-6-2 grep vs gardenSearch-NL split?
4. What is the latency cost of hybrid vs dense-only?

And a fifth, diagnostic question that #27 couldn't answer:

5. WHERE are the remaining gaps, and what class of fix do they need (model-level,
   tokenizer-level, or architecture-level)?

## S3: Single-Axis Isolation

Single-axis isolation — change one variable per config step, measure the effect.
We test two hybrid configurations to attribute improvements precisely:

| Config | Dense | SPLADE | Reranker | What it isolates |
|--------|-------|--------|----------|------------------|
| `dense-only` | ✅ | ❌ | ❌ | Baseline (#27) |
| `dense+splade` | ✅ | ✅ | ❌ | SPLADE's contribution (recall + RRF ranking) |
| `full-hybrid` | ✅ | ✅ | ✅ | Reranker's contribution (reordering + expanded candidate window) |

Attribution:
- **dense-only → dense+splade** = SPLADE's effect (recall changes + RRF fusion)
- **dense+splade → full-hybrid** = reranker's effect (reordering + expanded candidate window — see S7.4)
- **dense-only → full-hybrid** = combined pipeline benefit

## S4: Benchmark Architecture

Three independent Python scripts, run in sequence:

### S4.1: run_queries.py \<config-name\>

Runs all 28 queries (14 scenarios × KW + NL) against the engine's REST API
(`/search?q=...&limit=8`). For each query, captures:

- All returned entries: id, title, domain, type, body, score (garden metadata quality), relevance (machine retrieval score), source, sourcePrefix
- Rank: derived from response array position (0-indexed), not an API field
- Wall-clock latency (HTTP request round-trip)

Writes structured JSON to `scripts/benchmark/results/<config-name>.json`.

Run three times, reconfiguring the engine between runs:
1. `run_queries.py dense-only`
2. `run_queries.py dense+splade`
3. `run_queries.py full-hybrid`

The script verifies complete indexing before executing queries:
1. Poll `/search` until results are returned (engine is running)
2. Poll Qdrant collection point count (via `GET /collections/{name}` on port 6333)
   until it reaches ≥1,900 and stabilises for two consecutive checks 5s apart
3. After readiness confirmed, run a warmup pass: execute all 28 queries, discard
   results (JIT compilation, ONNX session warmup, Qdrant cache priming)
4. Then execute the three measurement passes

All queries execute sequentially with a brief pause between to avoid measuring
connection-pool effects.

**Note:** This benchmark supersedes `scripts/benchmark_search.py` (the #23
benchmark script — 6 queries, different output format, writes to
`docs/comparison/garden-search-vs-grep.md`). The new scripts in
`scripts/benchmark/` are the canonical tooling for hybrid benchmarking.

### S4.2: splade_vocab.py

Loads the SPLADE ONNX model (`~/.hortora/models/splade/model.onnx`) and tokenizer
(`~/.hortora/models/splade/tokenizer.json`) directly — no engine needed.

For each of the 28 queries:
1. Tokenizes the query text (BERT WordPiece tokenizer)
2. Runs through the ONNX model to get logits (expected: 30,522 = BERT vocab size;
      verified at startup against the ONNX model's actual output dimension)
3. Applies log-saturation: `weight = log1p(max(0, activation))`
4. Thresholds at 0.01 (same as `SparseEmbedder`)
5. Decodes activated token IDs back to vocabulary tokens
6. Classifies each token on two orthogonal axes:
   - **Source:** `INPUT` (produced by tokenizing the query text) or `EXPANSION`
     (activated by SPLADE, not present in query)
   - **Form:** `WHOLE_WORD` (complete vocabulary token) or `SUBWORD` (WordPiece
     fragment, `##` prefix)
   
   This yields four categories: INPUT/WHOLE_WORD, INPUT/SUBWORD,
   EXPANSION/WHOLE_WORD, EXPANSION/SUBWORD. The distinction matters for S8
   diagnosis: INPUT/SUBWORD indicates tokenizer fragmentation (the query term
   was split), while EXPANSION/SUBWORD indicates SPLADE learned a subword
   association (model behavior).
7. Checks activated tokens against the two-tier domain vocabulary list (S8.1),
   reporting hit rates separately for Tier 1 (unambiguous) and Tier 2 (ambiguous)

Writes to `scripts/benchmark/results/splade-vocab.json`.

Dependencies: `onnxruntime`, `tokenizers` (Python).

### S4.3: analyze.py

Reads all result JSONs, baseline scores, and SPLADE vocabulary data. Computes:

- **Delta analysis** per (scenario, query_type): new entries, lost entries, shared
  entries with rank changes
- **Precision metrics** per config: precision, highly-relevant precision, score=2 count,
  RR of first score=2 entry (aggregated to MRR across scenarios)
- **Attribution** — SPLADE contribution vs reranker contribution per scenario
- **Failure mode tracking** — which failure categories improved, unchanged, or regressed
- **Vocabulary cross-reference** — correlates SPLADE token activations with retrieval
  outcomes per failure mode

Generates the report skeleton at `docs/comparison/hybrid-benchmark.md`.

Flags new entries (in hybrid but not baseline) in `to_score.json` for manual scoring.
After manual scores are entered in `hybrid_scores.json`, re-run to produce the
final report.

## S5: Query Data Model

### S5.0: Scoring Terminology

Three distinct scoring dimensions appear in this benchmark — do not conflate:

| Term | Source | Type | Meaning |
|------|--------|------|---------|
| `score` | REST API field | int | Entry quality from garden YAML frontmatter — not a search signal |
| `relevance` | REST API field | double | Machine retrieval score — **not comparable across configs**: cosine similarity (dense-only, ~0.5–0.9), RRF fusion score (dense+splade, ~0.01–0.03), or cross-encoder logit (full-hybrid, ~-5–+10) |
| `benchmark_score` | Manual rubric | int (0/1/2) | Human relevance judgment for this scenario — the primary evaluation metric |

14 scenarios from #27, each with a KW query and an NL query. Defined in
`scripts/benchmark/queries.py`.

```python
@dataclass
class Scenario:
    id: str              # "issue-1-reactive-async"
    type: str            # "issue" | "spec-domain"
    description: str     # one-line summary
    context: str         # full issue/spec description for scoring calibration
    tech_band: str       # "reactive-async", "cdi-wiring", etc.
    kw_query: str        # keyword query (work-start Step 3b)
    nl_query: str        # natural language query
    grep_pattern: str    # reference only
    baseline_verdict: str  # #27 result: "grep-win" | "gs-nl-win" | "tie"
    failure_modes: list[str]  # from #27 analysis (multiple per scenario, see S5.1)
```

### S5.1: Failure Mode Classifications

Derived from #27's per-scenario analysis:

| Mode | Description | #27 Scenarios |
|------|-------------|---------------|
| `VOCABULARY_GAP` | Java class names / CDI annotations tokenized as generic subwords | Issues 2, 5; Spec: CDI tiers, ChatModel, CDI coexistence |
| `POLYSEMY` | Keyword has multiple unrelated meanings across domains | Issues 3, 4 |
| `DOMAIN_ABSENCE` | Topic not represented in corpus | Issue 5 (partial) |
| `SEMANTIC_WIN` | Dense embedding correctly captured conceptual similarity | Issues 1, 3, 4; Spec: ext deactivation, circular deps |
| `UNAMBIGUOUS_TERM` | Keyword unique enough for both exact match and embedding | Issue 6; Spec: thread safety |

These are the diagnostic lens — after hybrid runs, we track which failure modes each
component addresses.

Scenarios may carry multiple failure modes (e.g., Issue 3 is both POLYSEMY and
SEMANTIC_WIN; Issue 5 is both VOCABULARY_GAP and DOMAIN_ABSENCE). Each scenario
is counted in ALL applicable categories. Cross-category overlaps are documented
in S7.5 so that a single scenario improvement is not misread as independent
improvements across modes.

## S6: Engine Configuration Per Phase

### S6.1: Phase 0 — Dense-only (baseline)

Current default. ONNX model paths commented out in `application.properties`.
`HybridSearchProducer` does not produce `SparseEmbedder` or `CrossEncoderReranker`.
`HybridCaseRetriever` uses dense-only path.

### S6.2: Phase 1 — Dense + SPLADE (no reranker)

Uncomment in `application.properties`:
```properties
%dev.casehub.inference.models.splade.model-path=${user.home}/.hortora/models/splade/model.onnx
%dev.casehub.inference.models.splade.tokenizer-path=${user.home}/.hortora/models/splade/tokenizer.json
```

Reranker paths stay commented. On engine restart:
- `HybridSearchProducer` creates `SparseEmbedder` (`@LookupIfProperty` matches `.+`)
- `CrossEncoderReranker` stays null
- `CollectionMigration` detects dense-only collection → deletes collection + resets cursor
- Ingestion re-indexes ~1,960 entries with dense + sparse vectors
- `HybridCaseRetriever` uses RRF fusion (dense top-40 + sparse top-40, k=60), no reranking

### S6.3: Phase 2 — Full hybrid

Additionally uncomment:
```properties
%dev.casehub.inference.models.reranker.model-path=${user.home}/.hortora/models/reranker/model.onnx
%dev.casehub.inference.models.reranker.tokenizer-path=${user.home}/.hortora/models/reranker/tokenizer.json
```

On engine restart:
- Collection already has sparse vectors → no re-index
- `CrossEncoderReranker` now resolvable
- `HybridCaseRetriever` applies cross-encoder reranking to top-10 RRF results

### S6.4: RRF and Retrieval Defaults (measured as-is, not tuned)

| Parameter | Default | Source |
|-----------|---------|--------|
| `denseTopK` | 40 | `RagConfig.RetrievalConfig` |
| `sparseTopK` | 40 | `RagConfig.RetrievalConfig` |
| `rrfK` | 60 | `RagConfig.RetrievalConfig` |
| `rerankTopN` | 10 | `RagConfig.RetrievalConfig` |
| `rerankEnabled` | true | `RagConfig.RetrievalConfig` |
| `sparseThreshold` | 0.01 | `SparseEmbedder` |

**Note:** `rerankEnabled=true` is the default for all configs, but reranking only
occurs when `CrossEncoderReranker` is resolvable (model path configured). In
dense-only and dense+splade, reranking is structurally prevented by the absence
of the reranker bean, not by disabling the flag. This also affects `queryLimit`
in `HybridCaseRetriever`: `rerankEnabled && reranker != null` — the null check
on `reranker` is the discriminator (see S7.4).

Tuning is neural-text#46 scope. This benchmark establishes the baseline before tuning.

## S7: Delta Analysis & Scoring

### S7.1: Result Set Comparison

For each (scenario, query_type), match entries across configs by GE-ID:

| Category | Definition | Scoring |
|----------|------------|---------|
| **Shared** | In both dense-only and hybrid | Inherit #27 score. Track rank change (cross-config). |
| **New** | In hybrid but not dense-only | Manual scoring required (flagged in `to_score.json`). |
| **Lost** | In dense-only but not hybrid | Already scored. Flag as regression if score=2. |

Score inheritance is sound: an entry's relevance to an issue is a property of the
entry-issue pair, invariant to retrieval method.

**Note on `relevance` field:** The `relevance` API field carries different scoring
functions per config — cosine similarity (dense-only), RRF fusion score
(dense+splade), or cross-encoder logit (full-hybrid). These are on incomparable
scales. Rank change (ordinal position in the result set) is the valid cross-config
comparison metric. The `relevance` value is captured per config for within-config
analysis (e.g., machine relevance vs benchmark_score correlation) but is not
meaningful as a cross-config delta.

### S7.2: Scoring Workflow

1. `analyze.py` generates `to_score.json` listing all new entries grouped by scenario
2. Each entry includes: ID, title, full body text, query that produced it
3. Each scenario group includes: full issue/spec description from `queries.py`
   (the problem description, fix, affected components, technology band — same
   level of context available during #27 scoring) and a cross-reference to the
   corresponding #27 report section in `docs/comparison/real-world-benchmark.md`
4. Manual scoring with same 0/1/2 rubric as #27
5. Scores entered in `hybrid_scores.json`
6. Re-run `analyze.py` to produce final report

### S7.3: Per-Scenario Metrics (computed per config)

- Precision (score≥1 / total returned)
- Highly relevant precision (score=2 / total)
- Score=2 count
- Reciprocal rank (RR) of first score=2 entry (1/rank, or 0 if none)
- New score=2 discoveries (not in baseline)
- Score=2 regressions (baseline score=2 entries that disappeared)

### S7.4: Attribution

| Comparison | Measures |
|------------|----------|
| dense+splade vs dense-only | SPLADE: recall changes (new/lost) + RRF ranking |
| full-hybrid vs dense+splade | Reranker: reordering + expanded candidate window |
| full-hybrid vs dense-only | Combined benefit |

SPLADE attribution decomposes further:
- **Recall** — new relevant entries surfaced via sparse matching
- **Ranking** — same entries, different order from RRF fusion

Reranker attribution is reordering + expanded candidate window. With `maxResults=8`
(API default) and `rerankTopN=10`, the reranker path fetches 10 RRF candidates
(vs 8 without reranking) before selecting the top 8. The dense+splade → full-hybrid
delta therefore conflates two effects: (a) reranking reorders candidates, and
(b) the reranker draws from RRF positions 9-10 that dense+splade never sees.

The benchmark measures the production configuration as-is (S6.4). This confound
is an inherent property of the pipeline design — the expanded candidate window IS
part of what the reranker contributes in practice.

### S7.5: Failure Mode Tracking

Per failure mode, track across scenarios:

```
VOCABULARY_GAP (5 scenarios):
  Fixed by SPLADE:    N/5
  Fixed by reranker:  N/5
  Unchanged:          N/5
  Regressed:          N/5
```

"Fixed" = precision improved or new score=2 finds. "Regressed" = precision dropped
or score=2 entries lost.

### S7.6: Verdict Criteria

Same as #27: precision as primary, unique score=2 finds as secondary. Applied
per-scenario per-config.

**Three-way comparison (14 scenarios × KW + NL):** dense-only vs dense+splade
vs full-hybrid. Win/loss/tie tallies reported separately per query type.

**Four-way comparison with grep (14 scenarios × KW only):** grep vs dense-only vs
dense+splade vs full-hybrid. NL queries are excluded because grep has no NL
equivalent — grep used regex keyword patterns in #27.

**Result set size caveat:** #27 scored the first 20 grep results vs 8 gardenSearch
results. Precision (score≥1 / total) is valid across different result set sizes.
Raw score=2 counts are reported but not used for win/loss verdicts — grep has
2.5× more opportunities to surface score=2 entries.

## S8: SPLADE Vocabulary Analysis — Diagnostic Questions

For each scenario, the vocabulary analysis answers three questions:

1. **Expansion quality:** Do SPLADE's learned expansions include terms semantically
   relevant to the scenario's domain? Or generic programming/English words?

2. **Subword fragmentation:** Does BERT WordPiece split Java class names into
   meaningful subwords (`concurrent`, `hash`, `map`) or meaningless fragments
   (`con`, `##cu`, `##rrent`)? Tokenizer limitation — no fine-tuning can fix it.

3. **Retrieval cross-reference:** For new score=2 entries, do SPLADE expansions
   explain why they were found? For persistent failures, do expansions show why?

### S8.1: Domain Vocabulary Reference

Curated list of ~50 Java/CDI/Quarkus terms, split into two tiers to avoid
inflating hit rate with common English words that SPLADE activates for
non-domain reasons. Hit rates are reported per tier.

**Tier 1 — Unambiguous domain terms** (rarely appear in general English web text;
activation likely reflects domain awareness):
CDI (`applicationscoped`, `qualifier`, `interceptor`, `produces`, `singleton`),
JPA (`persist`, `cascade`), Quarkus (`panache`, `devservices`, `jandex`, `arc`),
concurrency (`synchronized`, `volatile`), reactive (`mutiny`)

**Tier 2 — Ambiguous/common terms** (common English words that also serve as
framework terms; activation may reflect general English, not domain awareness):
CDI (`inject`, `alternative`, `scope`, `observer`), JPA (`entity`, `merge`,
`lazy`, `fetch`), concurrency (`atomic`, `concurrent`, `lock`),
reactive (`uni`, `multi`, `subscribe`, `emit`)

A high Tier 1 hit rate is meaningful signal for SPLADE's domain vocabulary
coverage. A high Tier 2 hit rate requires cross-referencing with the query
context to determine whether activation reflects domain or general English usage.

## S9: Report Structure

Deliverable: `docs/comparison/hybrid-benchmark.md`

```
# Hybrid Benchmark: Dense-Only vs SPLADE vs Full Hybrid

## S10: Configuration
## S11: Methodology
## S12: Headline Results
  Three-way win/loss/tie (KW + NL): dense-only vs dense+splade vs full-hybrid
  Four-way win/loss/tie (KW only): grep vs dense-only vs dense+splade vs full-hybrid
  Aggregate precision deltas. Net new score=2 discoveries. Regressions.
  MRR per config (averaged across scenarios).

## S13: Failure Mode Analysis
  Per failure mode: fixed/unchanged/regressed by each component.
  Representative examples.

## S14: SPLADE Vocabulary Analysis
  Token activation findings per failure-mode category.
  Domain vocabulary hit rate per tier (Tier 1 unambiguous, Tier 2 ambiguous).
  Subword fragmentation analysis.
  Verdict: model-level vs tokenizer-level vs architecture-level limitation.

## S15: Per-Scenario Results (14 sections)
  Delta table: dense-only → dense+splade → full-hybrid.
  SPLADE token activations for KW query.
  Attribution. Verdict.

## S16: Latency Impact
  Per-query timing across configs.
  Overhead: dense+splade minus dense-only = sparse+RRF cost.
  Full-hybrid minus dense+splade = reranker cost.
  Median and p95 per config.
  Scale caveat: brute-force scan at 1,960 points (below HNSW threshold) —
  measurements valid for this corpus, not extrapolatable to production scale.

## S17: Attribution Summary
## S18: Recommendations
  What to fix in neural-text (#46) — distinguishing "model trained for the
  wrong domain" from "model is broken" (see S1 model identities).
  What needs complementary approaches (#29).
  What's fundamentally out of reach for embedding search.
  Latency at production scale (HNSW vs brute-force considerations).
```

## S19: Latency Measurement

Wall-clock time for each HTTP request to `/search`. This is what skills
experience — includes embedding, retrieval, fusion, and reranking.

Overhead attribution by config subtraction:
- SPLADE overhead ≈ dense+splade latency − dense-only latency
- Reranker overhead ≈ full-hybrid latency − dense+splade latency

Each query runs sequentially with a 500ms pause between queries to avoid
measuring connection-pool effects. Before the three measurement passes, a warmup
pass runs all 28 queries and discards results (JIT compilation, ONNX Runtime
session warmup, Qdrant connection establishment, initial GC). The full 28-query
suite then runs three times per config (three measurement passes), all within the
same `quarkus:dev` JVM session (no restart between passes). Per-query latency is
the median across the three passes.

**Scale caveat:** With 1,960 indexed points below Qdrant's HNSW threshold (10,000),
vectors use brute-force scan, not HNSW. Retrieval latency is O(N) and trivially
fast at this corpus size. At production scale with HNSW, retrieval latency
increases (O(log N) with higher constant factors), making the reranker's fixed
cross-encoder overhead a proportionally smaller share of total latency. These
measurements should not be extrapolated to larger corpora.

## S20: File Layout

```
scripts/benchmark/
  run_queries.py           # Query runner (per config)
  splade_vocab.py          # SPLADE vocabulary analysis
  analyze.py               # Delta analysis + report generation
  extract_baseline.py      # Extracts #27 scores from real-world-benchmark.md
  queries.py               # 14 scenarios × 2 queries = 28 query definitions
  baseline_scores.json     # #27 human scores (~300 entries), generated by extract_baseline.py
  results/                 # Output JSONs (committed for reproducibility)
    dense-only.json
    dense+splade.json
    full-hybrid.json
    splade-vocab.json
  hybrid_scores.json       # Manual scores for new entries (added after scoring)
  to_score.json            # Generated: new entries needing scoring

docs/comparison/
  hybrid-benchmark.md      # Final report
```

### S20.1: Baseline Extraction

`baseline_scores.json` is generated by `scripts/benchmark/extract_baseline.py`,
which parses the scored markdown tables in `docs/comparison/real-world-benchmark.md`.

Schema — keyed by GE-ID, then by scenario. An entry's relevance is a property
of the (entry, scenario) pair, not the entry alone — the same GE-ID can have
different scores in different scenarios:
```json
{
  "GE-20260530-4387cb": {
    "issue-3-persistence-migrations": {
      "benchmark_score": 0,
      "methods": ["gardenSearch-KW"]
    },
    "issue-6-testing-ci": {
      "benchmark_score": 2,
      "methods": ["gardenSearch-NL"]
    }
  }
}
```

Includes grep scores (required for four-way comparison in S7.6). When the same
GE-ID appears in multiple method columns for a single scenario, the methods are
merged — the `benchmark_score` is invariant to retrieval method within a scenario.

### S20.2: Dense-Only Consistency Check

Before hybrid analysis, compare the dense-only re-run results against #27 baseline:
1. Match result sets by GE-ID per (scenario, query_type)
2. Per-(scenario, query_type): flag if ANY entry differs (new or missing). Even one
   changed entry means that scenario's score inheritance from #27 is suspect —
   mark it for per-scenario re-baseline (re-score all entries for that scenario)
3. Document discrepancies with likely cause (corpus drift, engine version changes,
   embedding model updates, Qdrant version drift)
4. Aggregate check: if total changed entries across all scenarios exceed 10%
   (>22 of ~224), halt and investigate before proceeding — the retrieval surface
   has shifted enough to invalidate the benchmark's comparative basis with #27

## S21: Prerequisites

- ONNX models downloaded: `scripts/download-models.sh`
- Ollama running with `nomic-embed-text`
- Qdrant running: `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant`
- Engine running in `quarkus:dev` mode
- Python: `onnxruntime`, `tokenizers` packages installed

## S22: Non-Goals

- RRF parameter tuning (neural-text#46)
- Domain-specific model fine-tuning (neural-text#46)
- Complementary retrieval approaches (#29)
- Human relevance judgments as alternative evaluator (future iteration)
- CI-integrated benchmark pipeline (future iteration)

## S23: Related Issues

- **#27** — dense-only real-world benchmark (baseline)
- **#29** — complementary retrieval capabilities (blocked on #28 results)
- **casehubio/neural-text#46** — SPLADE model quality and RRF fusion weight tuning
