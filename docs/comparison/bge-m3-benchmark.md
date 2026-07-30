# BGE-M3 Four-Signal Retrieval Benchmark

*2026-07-29 · Refs #36*

## Configuration

| Parameter | Value |
|-----------|-------|
| Embedding model | BGE-M3 (BAAI/bge-m3, ONNX, 550M params) |
| Dense | 1024-dim, cosine, CLS pooling |
| Sparse | Learned lexical (XLM-RoBERTa 250K vocab, ReLU threshold) |
| ColBERT | Multi-vector reranking (1024-dim per token, MAX_SIM) |
| BM25 | Qdrant Document vectors (`qdrant/bm25` model) |
| RRF | Qdrant-native, three prefetch legs + ColBERT rescore |
| Qdrant | v1.18.0 (pinned) |
| Indexed points | 2091 (BGE-M3), 2026 (three-leg) |

## Executive Summary

**Overall precision: 86% (three-leg) → 87% (BGE-M3) (+1pp)**

## Headline Results

### KW Queries

| Scenario | Failure Mode | Three-leg | BGE-M3 | Delta | Shared/New/Lost |
|---|---|---|---|---|---|
| issue-1-reactive-async | SEMANTIC_WIN | 100% | 100% | — | 6/2/2 |
| issue-2-cdi-wiring | VOCABULARY_GAP | 88% | 100% | +12pp | 5/3/3 |
| issue-3-persistence-migrations | POLYSEMY, SEMANTIC_WIN | 75% | 88% | +12pp | 4/4/4 |
| issue-4-rest-messaging | POLYSEMY, SEMANTIC_WIN | 100% | 88% | -12pp | 5/3/3 |
| issue-5-ai-llm-inference | VOCABULARY_GAP | 88% | 100% | +12pp | 5/3/3 |
| issue-6-testing-ci | UNAMBIGUOUS_TERM | 88% | 100% | +12pp | 6/2/2 |
| spec1-d1-cdi-priority-tiers | VOCABULARY_GAP | 88% | 100% | +12pp | 3/5/5 |
| spec1-d2-thread-safety | UNAMBIGUOUS_TERM | 100% | 100% | — | 5/3/3 |
| spec1-d3-extension-deactivation | SEMANTIC_WIN | 100% | 75% | -25pp | 4/4/4 |
| spec1-d4-protocol-compliance | POLYSEMY | 38% | 50% | +12pp | 4/4/4 |
| spec2-d1-cdi-tier-coexistence | VOCABULARY_GAP | 100% | 100% | — | 6/2/2 |
| spec2-d2-chatmodel-adaptation | VOCABULARY_GAP | 100% | 75% | -25pp | 6/2/2 |
| spec2-d3-circular-deps | POLYSEMY, SEMANTIC_WIN | 75% | 62% | -12pp | 5/3/3 |
| spec2-d4-exception-mapper | VOCABULARY_GAP | 50% | 75% | +25pp | 5/3/3 |

### NL Queries

| Scenario | Failure Mode | Three-leg | BGE-M3 | Delta | Shared/New/Lost |
|---|---|---|---|---|---|
| issue-1-reactive-async | SEMANTIC_WIN | 100% | 100% | — | 4/4/4 |
| issue-2-cdi-wiring | VOCABULARY_GAP | 100% | 100% | — | 5/3/3 |
| issue-3-persistence-migrations | POLYSEMY, SEMANTIC_WIN | 100% | 100% | — | 6/2/2 |
| issue-4-rest-messaging | POLYSEMY, SEMANTIC_WIN | 100% | 88% | -12pp | 5/3/3 |
| issue-5-ai-llm-inference | VOCABULARY_GAP | 100% | 100% | — | 6/2/2 |
| issue-6-testing-ci | UNAMBIGUOUS_TERM | 88% | 62% | -25pp | 4/4/4 |
| spec1-d1-cdi-priority-tiers | VOCABULARY_GAP | 100% | 100% | — | 2/6/6 |
| spec1-d2-thread-safety | UNAMBIGUOUS_TERM | 75% | 100% | +25pp | 4/4/4 |
| spec1-d3-extension-deactivation | SEMANTIC_WIN | 88% | 75% | -12pp | 7/1/1 |
| spec1-d4-protocol-compliance | POLYSEMY | 50% | 50% | — | 5/3/3 |
| spec2-d1-cdi-tier-coexistence | VOCABULARY_GAP | 100% | 88% | -12pp | 6/2/2 |
| spec2-d2-chatmodel-adaptation | VOCABULARY_GAP | 100% | 100% | — | 6/2/2 |
| spec2-d3-circular-deps | POLYSEMY, SEMANTIC_WIN | 62% | 88% | +25pp | 4/4/4 |
| spec2-d4-exception-mapper | VOCABULARY_GAP | 62% | 75% | +12pp | 6/2/2 |

## Per-Failure-Mode Analysis

*Observational only — this benchmark changes the entire pipeline (dense model, sparse model, ColBERT reranking). Precision changes cannot be attributed to individual signals. Causal attribution is #33 scope.*

| Failure Mode | Three-leg avg | BGE-M3 avg | Delta |
|---|---|---|---|
| POLYSEMY | 75% | 77% | +2pp |
| SEMANTIC_WIN | 90% | 86% | -4pp |
| UNAMBIGUOUS_TERM | 88% | 91% | +3pp |
| VOCABULARY_GAP | 90% | 93% | +3pp |

## Latency

*Adoption spec criterion: ≤50% regression over three-leg baseline (256ms → budget 384ms).*

| Config | Median | Overhead |
|--------|--------|----------|
| three-leg | 240ms | baseline |
| BGE-M3 four-signal | 47ms | -193ms (-81%) — ✅ within budget |

## Regressions

- **issue-4-rest-messaging/KW**: 100% → 88% (-12pp) — 3 entries lost, 0 unscored
- **issue-4-rest-messaging/NL**: 100% → 88% (-12pp) — 3 entries lost, 0 unscored
- **issue-6-testing-ci/NL**: 88% → 62% (-25pp) — 4 entries lost, 0 unscored
- **spec1-d3-extension-deactivation/KW**: 100% → 75% (-25pp) — 4 entries lost, 0 unscored
- **spec1-d3-extension-deactivation/NL**: 88% → 75% (-12pp) — 1 entries lost, 0 unscored
- **spec2-d1-cdi-tier-coexistence/NL**: 100% → 88% (-12pp) — 2 entries lost, 0 unscored
- **spec2-d2-chatmodel-adaptation/KW**: 100% → 75% (-25pp) — 2 entries lost, 0 unscored
- **spec2-d3-circular-deps/KW**: 75% → 62% (-12pp) — 3 entries lost, 0 unscored

## Caveats

- The three-leg 94% baseline was computed from scored entries only (87 entries from the three-leg run are unscored, per retrieval-research.md). BGE-M3 precision uses the same scored-only methodology — the comparison is methodologically consistent even if absolute precisions may adjust once all entries are scored.
- Corpus size: 2026 (three-leg) → 2091 (BGE-M3). Delta: 65 entries.

## What Comes Next

| # | Description | Dependency |
|---|-------------|------------|
| #33 | Convex Combination fusion test — CC (α=0.5) vs RRF | This baseline |
| #34 | Matryoshka truncation + ColBERT quantization | This baseline |
