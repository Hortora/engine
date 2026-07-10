# Hybrid Benchmark: Dense-Only vs SPLADE vs Full Hybrid

*Generated 2026-07-09*

## Configuration

See spec: `docs/superpowers/specs/2026-06-28-splade-hybrid-benchmark-design.md`

## Headline Results

### KW Queries

| Scenario | Failure Mode | dense-only prec | dense+splade prec | full-hybrid prec | Delta (SPLADE) | Delta (Full) |
|---|---|---|---|---|---|---|
| issue-1-reactive-async | SEMANTIC_WIN | 12% | 62% | 75% | +5/-5 | +6/-6 |
| issue-2-cdi-wiring | VOCABULARY_GAP | 0% | 50% | — | +4/-4 |  |
| issue-3-persistence-migrations | POLYSEMY, SEMANTIC_WIN | 0% | 50% | — | +4/-4 |  |
| issue-4-rest-messaging | POLYSEMY, SEMANTIC_WIN | 0% | 50% | — | +4/-4 |  |
| issue-5-ai-llm-inference | VOCABULARY_GAP | 12% | 75% | — | +5/-5 |  |
| issue-6-testing-ci | UNAMBIGUOUS_TERM | 0% | 50% | — | +5/-5 |  |
| spec1-d1-cdi-priority-tiers | VOCABULARY_GAP | 12% | 62% | — | +4/-4 |  |
| spec1-d2-thread-safety | UNAMBIGUOUS_TERM | 25% | 50% | — | +3/-3 |  |
| spec1-d3-extension-deactivation | SEMANTIC_WIN | 75% | 100% | — | +3/-3 |  |
| spec1-d4-protocol-compliance | POLYSEMY | 0% | 12% | — | +4/-4 |  |
| spec2-d1-cdi-tier-coexistence | VOCABULARY_GAP | 25% | 100% | — | +6/-6 |  |
| spec2-d2-chatmodel-adaptation | VOCABULARY_GAP | 12% | 50% | — | +5/-5 |  |
| spec2-d3-circular-deps | POLYSEMY, SEMANTIC_WIN | 38% | 38% | — | +4/-4 |  |
| spec2-d4-exception-mapper | VOCABULARY_GAP | 12% | 38% | — | +4/-4 |  |

### NL Queries

| Scenario | Failure Mode | dense-only prec | dense+splade prec | full-hybrid prec | Delta (SPLADE) | Delta (Full) |
|---|---|---|---|---|---|---|
| issue-1-reactive-async | SEMANTIC_WIN | 88% | 100% | — | +3/-3 |  |
| issue-2-cdi-wiring | VOCABULARY_GAP | 100% | 100% | — | +4/-4 |  |
| issue-3-persistence-migrations | POLYSEMY, SEMANTIC_WIN | 62% | 100% | — | +3/-3 |  |
| issue-4-rest-messaging | POLYSEMY, SEMANTIC_WIN | 62% | 100% | — | +4/-4 |  |
| issue-5-ai-llm-inference | VOCABULARY_GAP | 12% | 75% | — | +5/-5 |  |
| issue-6-testing-ci | UNAMBIGUOUS_TERM | 100% | 100% | — | +4/-4 |  |
| spec1-d1-cdi-priority-tiers | VOCABULARY_GAP | 12% | 75% | — | +6/-6 |  |
| spec1-d2-thread-safety | UNAMBIGUOUS_TERM | 50% | 75% | — | +3/-2 |  |
| spec1-d3-extension-deactivation | SEMANTIC_WIN | 88% | 88% | — | +2/-2 |  |
| spec1-d4-protocol-compliance | POLYSEMY | 0% | 38% | — | +6/-6 |  |
| spec2-d1-cdi-tier-coexistence | VOCABULARY_GAP | 75% | 100% | — | +4/-4 |  |
| spec2-d2-chatmodel-adaptation | VOCABULARY_GAP | 50% | 100% | — | +6/-6 |  |
| spec2-d3-circular-deps | POLYSEMY, SEMANTIC_WIN | 88% | 100% | — | +3/-3 |  |
| spec2-d4-exception-mapper | VOCABULARY_GAP | 62% | 88% | — | +3/-3 |  |

## Latency

| Scenario | Query | dense-only | dense+splade | full-hybrid |
|---|---|---|---|---|
| issue-1-reactive-async | KW | 29ms | 45ms | 213ms |
| issue-1-reactive-async | NL | 30ms | 44ms | — |
| issue-2-cdi-wiring | KW | 22ms | 30ms | — |
| issue-2-cdi-wiring | NL | 30ms | 44ms | — |
| issue-3-persistence-migrations | KW | 25ms | 37ms | — |
| issue-3-persistence-migrations | NL | 27ms | 45ms | — |
| issue-4-rest-messaging | KW | 23ms | 36ms | — |
| issue-4-rest-messaging | NL | 29ms | 41ms | — |
| issue-5-ai-llm-inference | KW | 22ms | 35ms | — |
| issue-5-ai-llm-inference | NL | 32ms | 42ms | — |
| issue-6-testing-ci | KW | 25ms | 34ms | — |
| issue-6-testing-ci | NL | 30ms | 49ms | — |
| spec1-d1-cdi-priority-tiers | KW | 26ms | 47ms | — |
| spec1-d1-cdi-priority-tiers | NL | 31ms | 51ms | — |
| spec1-d2-thread-safety | KW | 29ms | 46ms | — |
| spec1-d2-thread-safety | NL | 27ms | 49ms | — |
| spec1-d3-extension-deactivation | KW | 30ms | 52ms | — |
| spec1-d3-extension-deactivation | NL | 29ms | 45ms | — |
| spec1-d4-protocol-compliance | KW | 31ms | 41ms | — |
| spec1-d4-protocol-compliance | NL | 31ms | 44ms | — |
| spec2-d1-cdi-tier-coexistence | KW | 27ms | 46ms | — |
| spec2-d1-cdi-tier-coexistence | NL | 30ms | 49ms | — |
| spec2-d2-chatmodel-adaptation | KW | 22ms | 35ms | — |
| spec2-d2-chatmodel-adaptation | NL | 36ms | 49ms | — |
| spec2-d3-circular-deps | KW | 29ms | 47ms | — |
| spec2-d3-circular-deps | NL | 31ms | 46ms | — |
| spec2-d4-exception-mapper | KW | 31ms | 38ms | — |
| spec2-d4-exception-mapper | NL | 28ms | 39ms | — |

## Per-Scenario Results

### issue-1-reactive-async / KW

**Failure modes:** SEMANTIC_WIN
**Baseline verdict:** gs-nl-win

**Delta vs dense-only (dense+splade):**
- Shared: 3, New: 5, Lost: 5
- Rank changes: [1, -5, -5]

**Delta vs dense-only (full-hybrid):**
- Shared: 2, New: 6, Lost: 6
- Rank changes: [-5, -7]

**SPLADE vocabulary (KW):**
- `entity` (1.961) [INPUT/WHOLE_WORD]
- `##mana` (1.722) [INPUT/SUBWORD]
- `io` (1.652) [INPUT/WHOLE_WORD]
- `block` (1.536) [EXPANSION/WHOLE_WORD]
- `##ception` (1.524) [INPUT/SUBWORD]
- T1 hits: 0, T2 hits: 1

---

### issue-1-reactive-async / NL

**Failure modes:** SEMANTIC_WIN
**Baseline verdict:** gs-nl-win

**Delta vs dense-only (dense+splade):**
- Shared: 5, New: 3, Lost: 3
- Rank changes: [-1, 0, 2, -3, -2]

---

### issue-2-cdi-wiring / KW

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [0, -1, -3, -3]

**SPLADE vocabulary (KW):**
- `default` (2.267) [INPUT/WHOLE_WORD]
- `##be` (2.002) [INPUT/SUBWORD]
- `##vid` (1.883) [INPUT/SUBWORD]
- `##mber` (1.663) [INPUT/SUBWORD]
- `ambiguous` (1.611) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 0

---

### issue-2-cdi-wiring / NL

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [2, 4, 1, -3]

---

### issue-3-persistence-migrations / KW

**Failure modes:** POLYSEMY, SEMANTIC_WIN
**Baseline verdict:** gs-nl-advantage

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [-1, -1, -2, -3]

**SPLADE vocabulary (KW):**
- `shadow` (2.011) [INPUT/WHOLE_WORD]
- `##ancy` (1.903) [INPUT/SUBWORD]
- `ledger` (1.890) [INPUT/WHOLE_WORD]
- `ten` (1.760) [INPUT/WHOLE_WORD]
- `join` (1.495) [EXPANSION/WHOLE_WORD]
- T1 hits: 0, T2 hits: 0

---

### issue-3-persistence-migrations / NL

**Failure modes:** POLYSEMY, SEMANTIC_WIN
**Baseline verdict:** gs-nl-advantage

**Delta vs dense-only (dense+splade):**
- Shared: 5, New: 3, Lost: 3
- Rank changes: [0, 1, -1, 1, 0]

---

### issue-4-rest-messaging / KW

**Failure modes:** POLYSEMY, SEMANTIC_WIN
**Baseline verdict:** gs-nl-win

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [-1, -2, -4, -4]

**SPLADE vocabulary (KW):**
- `stream` (1.845) [INPUT/WHOLE_WORD]
- `cloud` (1.686) [INPUT/WHOLE_WORD]
- `fire` (1.639) [INPUT/WHOLE_WORD]
- `##yn` (1.541) [INPUT/SUBWORD]
- `##eve` (1.468) [INPUT/SUBWORD]
- T1 hits: 0, T2 hits: 0

---

### issue-4-rest-messaging / NL

**Failure modes:** POLYSEMY, SEMANTIC_WIN
**Baseline verdict:** gs-nl-win

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [1, -1, 0, 1]

---

### issue-5-ai-llm-inference / KW

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 3, New: 5, Lost: 5
- Rank changes: [1, -3, -3]

**SPLADE vocabulary (KW):**
- `##chai` (1.806) [INPUT/SUBWORD]
- `chat` (1.749) [INPUT/WHOLE_WORD]
- `prompt` (1.658) [INPUT/WHOLE_WORD]
- `##ess` (1.600) [INPUT/SUBWORD]
- `lang` (1.513) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 0

---

### issue-5-ai-llm-inference / NL

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 3, New: 5, Lost: 5
- Rank changes: [2, -5, -5]

---

### issue-6-testing-ci / KW

**Failure modes:** UNAMBIGUOUS_TERM
**Baseline verdict:** gs-kw-advantage

**Delta vs dense-only (dense+splade):**
- Shared: 3, New: 5, Lost: 5
- Rank changes: [-1, -3, -4]

**SPLADE vocabulary (KW):**
- `##stor` (1.965) [INPUT/SUBWORD]
- `##me` (1.712) [INPUT/SUBWORD]
- `pan` (1.640) [INPUT/WHOLE_WORD]
- `##ache` (1.494) [INPUT/SUBWORD]
- `selected` (1.484) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 1

---

### issue-6-testing-ci / NL

**Failure modes:** UNAMBIGUOUS_TERM
**Baseline verdict:** gs-kw-advantage

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [3, 1, -4, -3]

---

### spec1-d1-cdi-priority-tiers / KW

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [4, -4, -4, -5]

**SPLADE vocabulary (KW):**
- `priority` (2.226) [INPUT/WHOLE_WORD]
- `tier` (2.074) [INPUT/WHOLE_WORD]
- `cd` (1.927) [INPUT/WHOLE_WORD]
- `##end` (1.902) [INPUT/SUBWORD]
- `##hem` (1.797) [INPUT/SUBWORD]
- T1 hits: 0, T2 hits: 1

---

### spec1-d1-cdi-priority-tiers / NL

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 2, New: 6, Lost: 6
- Rank changes: [-5, -6]

---

### spec1-d2-thread-safety / KW

**Failure modes:** UNAMBIGUOUS_TERM
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 5, New: 3, Lost: 3
- Rank changes: [6, 3, -5, -5, -5]

**SPLADE vocabulary (KW):**
- `##hma` (2.156) [INPUT/SUBWORD]
- `concurrent` (2.087) [INPUT/WHOLE_WORD]
- `##has` (1.794) [INPUT/SUBWORD]
- `copy` (1.744) [INPUT/WHOLE_WORD]
- `lock` (1.712) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 2

---

### spec1-d2-thread-safety / NL

**Failure modes:** UNAMBIGUOUS_TERM
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 5, New: 3, Lost: 2
- Rank changes: [1, 2, -2, 1, 3]

---

### spec1-d3-extension-deactivation / KW

**Failure modes:** SEMANTIC_WIN
**Baseline verdict:** gs-win

**Delta vs dense-only (dense+splade):**
- Shared: 5, New: 3, Lost: 3
- Rank changes: [0, 0, 0, 0, 0]

**SPLADE vocabulary (KW):**
- `##ber` (1.930) [INPUT/SUBWORD]
- `active` (1.870) [INPUT/WHOLE_WORD]
- `data` (1.803) [INPUT/WHOLE_WORD]
- `##nate` (1.739) [INPUT/SUBWORD]
- `dea` (1.724) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 0

---

### spec1-d3-extension-deactivation / NL

**Failure modes:** SEMANTIC_WIN
**Baseline verdict:** gs-win

**Delta vs dense-only (dense+splade):**
- Shared: 6, New: 2, Lost: 2
- Rank changes: [0, 2, 2, -2, 2, -4]

---

### spec1-d4-protocol-compliance / KW

**Failure modes:** POLYSEMY
**Baseline verdict:** grep-marginal

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [-2, -3, -4, -4]

**SPLADE vocabulary (KW):**
- `aggregate` (1.959) [INPUT/WHOLE_WORD]
- `delegation` (1.821) [INPUT/WHOLE_WORD]
- `scan` (1.661) [INPUT/WHOLE_WORD]
- `##gina` (1.645) [INPUT/SUBWORD]
- `##un` (1.550) [INPUT/SUBWORD]
- T1 hits: 0, T2 hits: 1

---

### spec1-d4-protocol-compliance / NL

**Failure modes:** POLYSEMY
**Baseline verdict:** grep-marginal

**Delta vs dense-only (dense+splade):**
- Shared: 2, New: 6, Lost: 6
- Rank changes: [0, 0]

---

### spec2-d1-cdi-tier-coexistence / KW

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 2, New: 6, Lost: 6
- Rank changes: [1, 2]

**SPLADE vocabulary (KW):**
- `default` (2.210) [INPUT/WHOLE_WORD]
- `##be` (2.062) [INPUT/SUBWORD]
- `priority` (1.950) [INPUT/WHOLE_WORD]
- `suppress` (1.861) [INPUT/WHOLE_WORD]
- `coe` (1.822) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 1

---

### spec2-d1-cdi-tier-coexistence / NL

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [1, -1, 4, -3]

---

### spec2-d2-chatmodel-adaptation / KW

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 3, New: 5, Lost: 5
- Rank changes: [2, -4, -5]

**SPLADE vocabulary (KW):**
- `chat` (1.806) [INPUT/WHOLE_WORD]
- `##hat` (1.610) [INPUT/SUBWORD]
- `##lang` (1.552) [INPUT/SUBWORD]
- `##ode` (1.545) [INPUT/SUBWORD]
- `streaming` (1.377) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 0

---

### spec2-d2-chatmodel-adaptation / NL

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-win

**Delta vs dense-only (dense+splade):**
- Shared: 2, New: 6, Lost: 6
- Rank changes: [2, -7]

---

### spec2-d3-circular-deps / KW

**Failure modes:** POLYSEMY, SEMANTIC_WIN
**Baseline verdict:** gs-nl-advantage

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [6, -2, -4, -5]

**SPLADE vocabulary (KW):**
- `graceful` (2.022) [INPUT/WHOLE_WORD]
- `circular` (1.838) [INPUT/WHOLE_WORD]
- `chat` (1.807) [INPUT/WHOLE_WORD]
- `dea` (1.637) [INPUT/WHOLE_WORD]
- `depend` (1.625) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 0

---

### spec2-d3-circular-deps / NL

**Failure modes:** POLYSEMY, SEMANTIC_WIN
**Baseline verdict:** gs-nl-advantage

**Delta vs dense-only (dense+splade):**
- Shared: 5, New: 3, Lost: 3
- Rank changes: [0, 4, -1, 0, 1]

---

### spec2-d4-exception-mapper / KW

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-advantage

**Delta vs dense-only (dense+splade):**
- Shared: 4, New: 4, Lost: 4
- Rank changes: [6, -3, -5, -5]

**SPLADE vocabulary (KW):**
- `##pper` (2.261) [INPUT/SUBWORD]
- `exception` (1.942) [INPUT/WHOLE_WORD]
- `map` (1.910) [INPUT/WHOLE_WORD]
- `missing` (1.753) [INPUT/WHOLE_WORD]
- `forbidden` (1.603) [INPUT/WHOLE_WORD]
- T1 hits: 0, T2 hits: 0

---

### spec2-d4-exception-mapper / NL

**Failure modes:** VOCABULARY_GAP
**Baseline verdict:** grep-advantage

**Delta vs dense-only (dense+splade):**
- Shared: 5, New: 3, Lost: 3
- Rank changes: [0, 2, 4, -1, -6]

---
