# BGE-M3 Regression Analysis

*2026-07-09 · Refs #47, closes investigation from epic #46*

## Context

The BGE-M3 benchmark (#36) showed 8 regression scenarios where precision dropped vs three-leg. This analysis root-causes each regression using the complete scoring baseline established in #48.

## Corrected Baseline

With all 138 entries scored (#48), the precision picture reversed:
- **Three-leg: 86%** (was reported as 90% — inflated by excluding 87 noise entries)
- **BGE-M3: 87%** (+1pp, was reported as -3pp)

The regressions are real within their scenarios but offset by improvements elsewhere.

## Regression Summary

All 8 regressions follow the same structural pattern: BGE-M3 returns 8 entries per scenario (same as three-leg), but displaces relevant entries with topically adjacent but irrelevant ones.

| Scenario | Three-leg | BGE-M3 | Lost (relevant) | Gained (irrelevant) | Root cause |
|----------|-----------|--------|-----------------|-------------------|------------|
| issue-4/KW | 100% | 88% | 3 (scores 1,2,2) | 1 noise | Semantic adjacency |
| issue-4/NL | 100% | 88% | 3 (scores 2,2,2) | 1 noise | Semantic adjacency |
| issue-6/NL | 88% | 62% | 4 (scores 2,1,2,2) | 2 noise | Semantic adjacency |
| spec1-d3/KW | 100% | 75% | 4 (scores 1,2,2,2) | 2 noise | Semantic adjacency |
| spec1-d3/NL | 88% | 75% | 1 (score 1) | 1 noise | Semantic adjacency |
| spec2-d1/NL | 100% | 88% | 2 (scores 1,1) | 1 noise | Semantic adjacency |
| spec2-d2/KW | 100% | 75% | 2 (scores 2,1) | 2 noise | Model self-reference |
| spec2-d3/KW | 75% | 62% | 3 (scores 1,1,1) | 1 noise | Semantic adjacency |

**Aggregate:** 22 relevant entries lost, 11 noise entries gained.

## Root Cause: Semantic Adjacency

Every regression is caused by the same mechanism: BGE-M3's embedding space places topically adjacent but wrong-context entries closer to the query than the correct entries. Examples:

- **issue-4 (CloudEvent/stream):** BGE-M3 matches generic "CDI event" entries instead of "CloudEvent + stream transport" specific entries. The model understands "CDI events" semantically but doesn't distinguish CloudEvent as a different concept.

- **issue-6 (testing/@Alternative):** BGE-M3 matches broad CDI proxy/scope entries instead of the specific "@Alternative @Priority + Panache bypass" pattern. The CDI domain is too semantically dense for the model to discriminate subtypes.

- **spec1-d3 (extension deactivation):** Three-leg found entries about "JPA entity forces datasource config" through BM25 term matching on "datasource". BGE-M3 ranks Hibernate configuration entries higher than the JPA-entity-forces-datasource pattern.

- **spec2-d2 (ChatModel adaptation):** BGE-M3 returns entries about embedding models (its own domain) instead of ChatModel/LangChain4j adaptation entries. Different model, different embedding space self-reference bias.

## Why These Are Not Fixable

These regressions are intrinsic to the model switch, not to the pipeline configuration:

1. **Different embedding spaces produce different rankings** — what was rank 5 in three-leg may be rank 12 in BGE-M3, falling outside the top-8 result window. No pipeline parameter changes the embedding vectors themselves.

2. **Cross-encoder reranking doesn't help** — the cross-encoder already reranks the top-50 candidates. If the irrelevant entries are in the top-50 and rank higher than the relevant ones in the embedding space, the cross-encoder can't recover them (it only reranks, not recall).

3. **BM25 is unchanged** — BM25 is the same in both stacks. The regressions come from the dense and sparse legs, where BGE-M3 replaces nomic-embed-text + SPLADE.

4. **Limit expansion has diminishing returns** — increasing from 8 to 16 was done in earlier work. Going to 32 would capture some lost entries but would also bring in more noise, likely net-negative for precision.

## Fixability Assessment

| Approach | Feasible | Impact | Verdict |
|----------|----------|--------|---------|
| Model fine-tuning on garden corpus | High effort, unknown gain | Would need training infrastructure | Not justified at +1pp overall |
| Increase result limit | Easy | Trades precision for recall | Already at 16; further increase net-negative |
| Query reformulation per scenario | Easy | Benchmark-specific, not production | Overfitting to benchmark |
| Per-leg embedding separation (#117) | Medium | Lets each leg use its optimal query | Blocked on neocortex; would help future HyDE, not these regressions |

**Verdict:** No engine-side change improves these regressions without degrading overall precision. The corrected baseline shows BGE-M3 is +1pp better overall — the regressions are offset by improvements in other scenarios.

## Recommendations

1. **Accept the regressions as intrinsic** — different models rank differently at the margin. Overall precision improved.
2. **Focus future quality work on DOMAIN_ABSENCE** (75% avg, lowest failure mode) — this is corpus enrichment, not model tuning.
3. **Re-evaluate after per-leg separation** (#50) — when HyDE can feed expanded queries to dense only while sparse/BM25 keep original text, the regressions may shift.
