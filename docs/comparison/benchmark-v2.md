# Benchmark v2 — Fixed-Corpus Retrieval Quality

*2026-07-28 · Refs #55, #56*

## Why v2

All benchmark results prior to this document are **not comparable to each other**. The garden corpus grew continuously (~230 entries between July 7-28), but the scoring files (`baseline_scores.json`, `bge-m3-to-score.json`) were frozen at July 7-10. New entries that appeared in search results but weren't scored defaulted to 0, systematically deflating precision measurements.

This was discovered during the HyDE investigation (#50) and score boosting experiment (#55). A -2.2pp "regression" attributed first to HyDE, then to neocortex code changes (#181), turned out to be entirely from unscored entries displacing scored ones in the top-16. The scored-only precision was identical at ~85%.

**Prior benchmark documents** (`bge-m3-benchmark.md`, `regression-analysis.md`, `hybrid-benchmark.md`) remain valid as internal comparisons within their own sessions — each session's runs used the same corpus state. Cross-session comparisons are invalid.

## Methodology

**Fixed corpus:** All benchmarks in this document use a garden corpus frozen at a specific git SHA, paired with scoring files that cover all entries appearing in results. The SHA and scoring file versions are recorded with each benchmark run.

**Scoring:** Each entry in each scenario is scored 0 (irrelevant), 1 (relevant), or 2 (highly relevant) in `baseline_scores.json` + `bge-m3-to-score.json`. Precision = fraction of returned entries scoring ≥ 1. Entries not in the scoring files are flagged — benchmarks are invalid if >5% of returned entries are unscored.

**14 scenarios, 2 query types each (KW + NL) = 28 measurements per run.**

## Baseline (to be established)

**Status:** Pending. The scoring files need updating to cover entries added between July 7-28. Once scored, the baseline run establishes the reference precision for all subsequent comparisons in this document.

| Config | Corpus SHA | Points | Avg Precision | Notes |
|--------|-----------|--------|---------------|-------|
| `v2-baseline` | TBD | ~2400 | TBD | Current pipeline, no HyDE, no score boost |

## Experiments queue

Pending baseline establishment:

| Experiment | What it tests | Config change |
|------------|--------------|---------------|
| Score boost (0.5) | Entry quality score as ranking signal | `hortora.search.score-boost-weight=0.5` |
| Score boost (1.0) | Stronger quality signal | `hortora.search.score-boost-weight=1.0` |
| HyDE (inverted, frozen corpus) | Re-evaluate inverted HyDE with correct baseline | `hortora.inverted-hyde.enabled=true` |
| HyDE (query-time, frozen corpus) | Re-evaluate query-time HyDE with correct baseline | Re-enable expansion config |

## Historical context — invalidated measurements

These results from the #50 branch used a growing corpus against frozen scoring files. The absolute precision numbers are deflated. Relative comparisons within the same run may still hold (same corpus state).

| Config | File | Reported Precision | Actual status |
|--------|------|--------------------|---------------|
| No HyDE baseline | `crossencoder-pool50-scored.json` | 61.6% | Valid at time of measurement (Jul 7-8) |
| Double-retrieval HyDE | `hyde-perleg-separation.json` | 59.4% | Measured on larger corpus — not comparable to 61.6% baseline |
| Single-retrieval HyDE | `hyde-single-retrieval.json` | 59.4% | Same corpus growth issue |
| Inverted HyDE | `inverted-hyde.json` | 59.2% | Same corpus growth issue |
| Score boost 0.1 | `score-boost-0.1.json` | 59.4% | Same corpus growth issue |
| Control (boost=0.0) | `new-baseline-control.json` | 59.4% | Confirmed identical to boost=0.1 (boost has no effect at 0.1) |
| Post-#181 v1 fix | `post-181-fix-baseline.json` | 59.4% | Same corpus growth issue |
| Post-#181 v2 fix | `post-181-v2-baseline.json` | 58.7% | Same corpus growth issue, v2 changes also degraded |
| Frozen corpus (SHA 973b326a) | `frozen-corpus-baseline.json` | 58.7% | Still had 230 more entries than original baseline — not frozen far enough |

## Key learnings

1. **Never benchmark against a live corpus.** The scoring files and corpus must be frozen together. Issue #56 tracks the permanent fix.
2. **Unscored entries default to 0.** Even a few unscored entries in the top-16 systematically deflate precision.
3. **The scored-only signal was stable at ~85% throughout.** The retrieval pipeline is sound — the measurement methodology was broken.
4. **Weller et al. (EACL 2024) finding still stands** — expansion doesn't help strong retrievers — but the magnitude of HyDE's harm was overstated by the scoring gap.
