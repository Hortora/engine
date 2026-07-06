# Fusion Strategy Benchmark — CC / DBSF vs RRF

*2026-07-06 · Refs #33*

## Configuration

| Parameter | Value |
|-----------|-------|
| Embedding model | BGE-M3 (BAAI/bge-m3, ONNX, 550M params) |
| Signals | Dense (1024-dim) + Sparse (learned) + BM25 + ColBERT rescore |
| Baseline fusion | RRF (k=60) |
| Indexed points (bge-m3-four-signal) | 2091 |
| Indexed points (bge-m3-limit16) | 2121 |

## Executive Summary

**LIMIT16 vs RRF:** precision 87% → 87% (-0pp), latency 47ms → 49ms

Scenario breakdown: 9 improved, 4 regressed, 15 unchanged

## LIMIT16 vs RRF — Per-Scenario

### KW Queries

| Scenario | Failure Mode | RRF | LIMIT16 | Delta | Latency RRF→LIMIT16 | Shared/New/Lost |
|---|---|---|---|---|---|---|
| issue-1-reactive-async | SEMANTIC_WIN | 100% | 100% | — | 33→33ms | 8/2/0 |
| issue-2-cdi-wiring | VOCABULARY_GAP | 100% | 100% | — | 40→38ms | 8/2/0 |
| issue-3-persistence-migrations | POLYSEMY, SEMANTIC_WIN | 88% | 89% | +1pp | 29→30ms | 8/2/0 |
| issue-4-rest-messaging | POLYSEMY, SEMANTIC_WIN | 88% | 88% | — | 31→31ms | 8/2/0 |
| issue-5-ai-llm-inference | VOCABULARY_GAP, DOMAIN_ABSENCE | 100% | 100% | — | 30→28ms | 8/2/0 |
| issue-6-testing-ci | UNAMBIGUOUS_TERM | 100% | 100% | — | 34→29ms | 8/2/0 |
| spec1-d1-cdi-priority-tiers | VOCABULARY_GAP | 100% | 100% | — | 35→33ms | 6/4/2 |
| spec1-d2-thread-safety | UNAMBIGUOUS_TERM | 100% | 100% | — | 29→32ms | 8/2/0 |
| spec1-d3-extension-deactivation | SEMANTIC_WIN | 75% | 80% | +5pp | 35→35ms | 8/2/0 |
| spec1-d4-protocol-compliance | DOMAIN_ABSENCE | 50% | 44% | -6pp | 30→32ms | 8/2/0 |
| spec2-d1-cdi-tier-coexistence | VOCABULARY_GAP | 100% | 100% | — | 50→58ms | 8/2/0 |
| spec2-d2-chatmodel-adaptation | VOCABULARY_GAP | 75% | 78% | +3pp | 39→28ms | 8/2/0 |
| spec2-d3-circular-deps | POLYSEMY, SEMANTIC_WIN | 62% | 62% | — | 53→51ms | 8/2/0 |
| spec2-d4-exception-mapper | VOCABULARY_GAP | 75% | 67% | -8pp | 35→30ms | 8/2/0 |

### NL Queries

| Scenario | Failure Mode | RRF | LIMIT16 | Delta | Latency RRF→LIMIT16 | Shared/New/Lost |
|---|---|---|---|---|---|---|
| issue-1-reactive-async | SEMANTIC_WIN | 100% | 100% | — | 50→51ms | 8/2/0 |
| issue-2-cdi-wiring | VOCABULARY_GAP | 100% | 100% | — | 45→49ms | 8/2/0 |
| issue-3-persistence-migrations | POLYSEMY, SEMANTIC_WIN | 100% | 100% | — | 44→51ms | 8/2/0 |
| issue-4-rest-messaging | POLYSEMY, SEMANTIC_WIN | 88% | 89% | +1pp | 51→55ms | 8/2/0 |
| issue-5-ai-llm-inference | VOCABULARY_GAP, DOMAIN_ABSENCE | 100% | 90% | -10pp | 47→49ms | 8/2/0 |
| issue-6-testing-ci | UNAMBIGUOUS_TERM | 62% | 67% | +4pp | 51→54ms | 8/2/0 |
| spec1-d1-cdi-priority-tiers | VOCABULARY_GAP | 100% | 100% | — | 51→47ms | 8/2/0 |
| spec1-d2-thread-safety | UNAMBIGUOUS_TERM | 100% | 100% | — | 47→49ms | 8/2/0 |
| spec1-d3-extension-deactivation | SEMANTIC_WIN | 75% | 78% | +3pp | 53→51ms | 8/2/0 |
| spec1-d4-protocol-compliance | DOMAIN_ABSENCE | 50% | 56% | +6pp | 49→46ms | 8/2/0 |
| spec2-d1-cdi-tier-coexistence | VOCABULARY_GAP | 88% | 89% | +1pp | 60→61ms | 8/2/0 |
| spec2-d2-chatmodel-adaptation | VOCABULARY_GAP | 100% | 100% | — | 48→52ms | 8/2/0 |
| spec2-d3-circular-deps | POLYSEMY, SEMANTIC_WIN | 88% | 89% | +1pp | 77→50ms | 8/2/0 |
| spec2-d4-exception-mapper | VOCABULARY_GAP | 75% | 67% | -8pp | 60→59ms | 8/2/0 |

## Regressions

- **LIMIT16 issue-5-ai-llm-inference/NL**: 100% → 90% (-10pp) — 0 entries lost
- **LIMIT16 spec1-d4-protocol-compliance/KW**: 50% → 44% (-6pp) — 0 entries lost
- **LIMIT16 spec2-d4-exception-mapper/KW**: 75% → 67% (-8pp) — 0 entries lost
- **LIMIT16 spec2-d4-exception-mapper/NL**: 75% → 67% (-8pp) — 0 entries lost

## Improvements

- **LIMIT16 issue-3-persistence-migrations/KW**: 88% → 89% (+1pp) — 2 new entries
- **LIMIT16 issue-4-rest-messaging/NL**: 88% → 89% (+1pp) — 2 new entries
- **LIMIT16 issue-6-testing-ci/NL**: 62% → 67% (+4pp) — 2 new entries
- **LIMIT16 spec1-d3-extension-deactivation/KW**: 75% → 80% (+5pp) — 2 new entries
- **LIMIT16 spec1-d3-extension-deactivation/NL**: 75% → 78% (+3pp) — 2 new entries
- **LIMIT16 spec1-d4-protocol-compliance/NL**: 50% → 56% (+6pp) — 2 new entries
- **LIMIT16 spec2-d1-cdi-tier-coexistence/NL**: 88% → 89% (+1pp) — 2 new entries
- **LIMIT16 spec2-d2-chatmodel-adaptation/KW**: 75% → 78% (+3pp) — 2 new entries
- **LIMIT16 spec2-d3-circular-deps/NL**: 88% → 89% (+1pp) — 2 new entries

## Verdict

*TODO: fill in after reviewing results*
