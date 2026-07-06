# grep vs gardenSearch — Definitive Comparison

*2026-07-05 · Comprehensive retrieval evaluation*

## Context

This report compares grep (regex pattern matching) against gardenSearch 
(BGE-M3 four-signal hybrid retrieval, config: `bge-m3-four-signal`) 
across 14 benchmark scenarios covering 6 real issues and 8 spec review domains.

Each scenario tests both keyword (KW) and natural language (NL) queries. 
grep uses the same query for both; gardenSearch uses tailored queries for each.

## Executive Summary

| Method | Avg Precision | Median Latency | Strengths |
|--------|--------------|----------------|-----------|
| grep | 66% | ~5-15ms (local file scan) | Exact term matching, zero false positives on exact patterns |
| gardenSearch (KW) | 87% | 47ms | Handles vocabulary gaps, semantic similarity |
| gardenSearch (NL) | 88% | 47ms | Natural language understanding, concept matching |

**Unique relevant finds:** gardenSearch found 92 relevant entries 
that grep missed. grep found 153 relevant entries 
that gardenSearch missed.

## Head-to-Head: Per-Scenario

| Scenario | Failure Mode | grep | gS (KW) | gS (NL) | Winner | grep-only relevant | gS-only relevant |
|---|---|---|---|---|---|---|---|
| issue-1-reactive-async | SEMANTIC_WIN | 40% | 100% | 100% | **gardenSearch** | 10 | 10 |
| issue-2-cdi-wiring | VOCABULARY_GAP | 95% | 100% | 100% | **tie** | 28 | 6 |
| issue-3-persistence-migrations | POLYSEMY, SEMANTIC_WIN | 53% | 88% | 100% | **gardenSearch** | 12 | 7 |
| issue-4-rest-messaging | POLYSEMY, SEMANTIC_WIN | 25% | 88% | 88% | **gardenSearch** | 1 | 5 |
| issue-5-ai-llm-inference | VOCABULARY_GAP, DOMAIN_ABSENCE | 45% | 100% | 100% | **gardenSearch** | 11 | 9 |
| issue-6-testing-ci | UNAMBIGUOUS_TERM | 60% | 100% | 62% | **gardenSearch** | 18 | 7 |
| spec1-d1-cdi-priority-tiers | VOCABULARY_GAP | 78% | 100% | 100% | **gardenSearch** | 10 | 12 |
| spec1-d2-thread-safety | UNAMBIGUOUS_TERM | 94% | 100% | 100% | **gardenSearch** | 20 | 4 |
| spec1-d3-extension-deactivation | SEMANTIC_WIN | 100% | 75% | 75% | **grep** | 0 | 10 |
| spec1-d4-protocol-compliance | DOMAIN_ABSENCE | 100% | 50% | 50% | **grep** | 2 | 6 |
| spec2-d1-cdi-tier-coexistence | VOCABULARY_GAP | 81% | 100% | 88% | **gardenSearch** | 14 | 3 |
| spec2-d2-chatmodel-adaptation | VOCABULARY_GAP | 70% | 75% | 100% | **gardenSearch** | 15 | 1 |
| spec2-d3-circular-deps | POLYSEMY, SEMANTIC_WIN | 21% | 62% | 88% | **gardenSearch** | 7 | 11 |
| spec2-d4-exception-mapper | VOCABULARY_GAP | 57% | 75% | 75% | **gardenSearch** | 5 | 1 |

**Score: gardenSearch 11, grep 2, ties 1**

## What grep catches that gardenSearch misses

- **issue-1-reactive-async/KW**: 4 relevant entries (out of 16 grep-only) not found by gardenSearch
- **issue-1-reactive-async/NL**: 6 relevant entries (out of 18 grep-only) not found by gardenSearch
- **issue-2-cdi-wiring/KW**: 15 relevant entries (out of 16 grep-only) not found by gardenSearch
- **issue-2-cdi-wiring/NL**: 13 relevant entries (out of 14 grep-only) not found by gardenSearch
- **issue-3-persistence-migrations/KW**: 5 relevant entries (out of 13 grep-only) not found by gardenSearch
- **issue-3-persistence-migrations/NL**: 7 relevant entries (out of 16 grep-only) not found by gardenSearch
- **issue-4-rest-messaging/NL**: 1 relevant entries (out of 16 grep-only) not found by gardenSearch
- **issue-5-ai-llm-inference/KW**: 5 relevant entries (out of 16 grep-only) not found by gardenSearch
- **issue-5-ai-llm-inference/NL**: 6 relevant entries (out of 17 grep-only) not found by gardenSearch
- **issue-6-testing-ci/KW**: 9 relevant entries (out of 17 grep-only) not found by gardenSearch
- **issue-6-testing-ci/NL**: 9 relevant entries (out of 17 grep-only) not found by gardenSearch
- **spec1-d1-cdi-priority-tiers/KW**: 4 relevant entries (out of 6 grep-only) not found by gardenSearch
- **spec1-d1-cdi-priority-tiers/NL**: 6 relevant entries (out of 8 grep-only) not found by gardenSearch
- **spec1-d2-thread-safety/KW**: 10 relevant entries (out of 11 grep-only) not found by gardenSearch
- **spec1-d2-thread-safety/NL**: 10 relevant entries (out of 11 grep-only) not found by gardenSearch
- **spec1-d4-protocol-compliance/KW**: 1 relevant entries (out of 1 grep-only) not found by gardenSearch
- **spec1-d4-protocol-compliance/NL**: 1 relevant entries (out of 1 grep-only) not found by gardenSearch
- **spec2-d1-cdi-tier-coexistence/KW**: 6 relevant entries (out of 9 grep-only) not found by gardenSearch
- **spec2-d1-cdi-tier-coexistence/NL**: 8 relevant entries (out of 10 grep-only) not found by gardenSearch
- **spec2-d2-chatmodel-adaptation/KW**: 9 relevant entries (out of 15 grep-only) not found by gardenSearch
- **spec2-d2-chatmodel-adaptation/NL**: 6 relevant entries (out of 12 grep-only) not found by gardenSearch
- **spec2-d3-circular-deps/KW**: 3 relevant entries (out of 18 grep-only) not found by gardenSearch
- **spec2-d3-circular-deps/NL**: 4 relevant entries (out of 19 grep-only) not found by gardenSearch
- **spec2-d4-exception-mapper/KW**: 3 relevant entries (out of 8 grep-only) not found by gardenSearch
- **spec2-d4-exception-mapper/NL**: 2 relevant entries (out of 8 grep-only) not found by gardenSearch

## What gardenSearch catches that grep misses

- **issue-1-reactive-async/KW**: 4 relevant entries (out of 4 gS-only) that grep cannot find
- **issue-1-reactive-async/NL**: 6 relevant entries (out of 6 gS-only) that grep cannot find
- **issue-2-cdi-wiring/KW**: 4 relevant entries (out of 4 gS-only) that grep cannot find
- **issue-2-cdi-wiring/NL**: 2 relevant entries (out of 2 gS-only) that grep cannot find
- **issue-3-persistence-migrations/KW**: 2 relevant entries (out of 2 gS-only) that grep cannot find
- **issue-3-persistence-migrations/NL**: 5 relevant entries (out of 5 gS-only) that grep cannot find
- **issue-4-rest-messaging/KW**: 2 relevant entries (out of 3 gS-only) that grep cannot find
- **issue-4-rest-messaging/NL**: 3 relevant entries (out of 4 gS-only) that grep cannot find
- **issue-5-ai-llm-inference/KW**: 4 relevant entries (out of 4 gS-only) that grep cannot find
- **issue-5-ai-llm-inference/NL**: 5 relevant entries (out of 5 gS-only) that grep cannot find
- **issue-6-testing-ci/KW**: 5 relevant entries (out of 5 gS-only) that grep cannot find
- **issue-6-testing-ci/NL**: 2 relevant entries (out of 5 gS-only) that grep cannot find
- **spec1-d1-cdi-priority-tiers/KW**: 5 relevant entries (out of 5 gS-only) that grep cannot find
- **spec1-d1-cdi-priority-tiers/NL**: 7 relevant entries (out of 7 gS-only) that grep cannot find
- **spec1-d2-thread-safety/KW**: 2 relevant entries (out of 2 gS-only) that grep cannot find
- **spec1-d2-thread-safety/NL**: 2 relevant entries (out of 2 gS-only) that grep cannot find
- **spec1-d3-extension-deactivation/KW**: 5 relevant entries (out of 7 gS-only) that grep cannot find
- **spec1-d3-extension-deactivation/NL**: 5 relevant entries (out of 7 gS-only) that grep cannot find
- **spec1-d4-protocol-compliance/KW**: 3 relevant entries (out of 7 gS-only) that grep cannot find
- **spec1-d4-protocol-compliance/NL**: 3 relevant entries (out of 7 gS-only) that grep cannot find
- **spec2-d1-cdi-tier-coexistence/KW**: 1 relevant entries (out of 1 gS-only) that grep cannot find
- **spec2-d1-cdi-tier-coexistence/NL**: 2 relevant entries (out of 2 gS-only) that grep cannot find
- **spec2-d2-chatmodel-adaptation/KW**: 1 relevant entries (out of 3 gS-only) that grep cannot find
- **spec2-d3-circular-deps/KW**: 4 relevant entries (out of 7 gS-only) that grep cannot find
- **spec2-d3-circular-deps/NL**: 7 relevant entries (out of 8 gS-only) that grep cannot find
- **spec2-d4-exception-mapper/KW**: 1 relevant entries (out of 2 gS-only) that grep cannot find

## By Failure Mode

| Failure Mode | grep avg | gS (KW) avg | gS (NL) avg | Interpretation |
|---|---|---|---|---|
| DOMAIN_ABSENCE | 72% | 75% | 75% | Neither method finds entries that don't exist in the corpus |
| POLYSEMY | 33% | 79% | 92% | Common terms (Instance, filter) return noise in both methods |
| SEMANTIC_WIN | 48% | 82% | 90% | gardenSearch finds conceptually related entries grep cannot match |
| UNAMBIGUOUS_TERM | 77% | 100% | 81% | Exact technical terms — grep's natural strength |
| VOCABULARY_GAP | 71% | 92% | 94% | gardenSearch overcomes vocab mismatch via learned embeddings |

## Recommendation

*TODO: fill after full analysis*
