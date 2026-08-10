# Re-enable HyDE Query Expansion

**Issue:** Hortora/engine#50
**Date:** 2026-07-23
**Status:** Approved

## Context

HyDE (Hypothetical Document Embeddings) query expansion was implemented in `SessionQueryExpander` but disabled after benchmarking (issue #40) showed it was net-zero at limit=16 with four-signal retrieval. The root cause: all retrieval legs used `searchText()`, so the hypothetical replaced the original query everywhere — dense got better semantic coverage but sparse/BM25 lost their lexical signal. Prior benchmark results are in `scripts/benchmark/results/` (`hyde-session.json`, `hyde-tuned.json`, `hyde-enabled.json`, `no-hyde-baseline.json`).

Since then, neocortex #113 landed per-leg embedding separation in `HybridCaseRetriever` and `ReactiveHybridCaseRetriever`:

- **Dense** → `searchText()` (hypothetical — semantic match)
- **Sparse/ColBERT** → `text()` (original — lexical signal preserved)
- **BM25** → `query.text()` (original — keyword match preserved)

The separation is tested. HyDE should now be purely additive.

Neocortex #117 then promoted the separation from a retriever-level workaround into a proper `MultiModalEmbedder` contract: `embedSeparate(denseText, nonDenseText)` routes each mode to the correct input text. `SeparateModelEmbedder` drops from 4 model calls to 2. Retrievers collapse from a 7-line if/else branch to a single `embedder.embedSeparate(query.searchText(), query.text())` call.

## Approach

**Config-only change — no code modifications.**

### application.properties

Uncomment three lines:

```properties
casehub.rag.expansion.enabled=true
casehub.rag.expansion.mode=session
casehub.rag.expansion.prompt-template=Question: %s
```

Remove the "DISABLED" comment explaining the old rationale.

### Activation path

Three CDI decorators wrap `CaseRetriever` in priority order (highest = outermost):

1. **`QueryExpandingCaseRetriever`** (`@Priority(200)`, enabled by `casehub.rag.expansion.enabled=true`)
2. **`RerankingCaseRetriever`** (`@Priority(75)`, enabled by `casehub.rag.reranking.enabled=true`)
3. **`HybridCaseRetriever`** (the actual bean — four-signal retrieval)

With HyDE enabled, the full data flow is:

1. `QueryExpandingCaseRetriever` intercepts `retrieve()`, calls `SessionQueryExpander.expand(query)`
2. `SessionQueryExpander` opens an `AgentSession` via `AgentProvider` (Claude on Vertex AI), generates a hypothetical garden entry. The entire `expand()` method is `synchronized(sessionLock)` — concurrent queries serialise behind this lock (~2.4s per LLM call). This is acceptable for single-user benchmark use; concurrent scenarios require architectural changes (see #52).
3. `expand()` returns `List.of(query.withExpansion(hypothetical))` — one query with expansion set
4. `QueryExpandingCaseRetriever` checks `expanded.contains(query)` — this is **false** because `RetrievalQuery` is a record and `(text, null) ≠ (text, hypothetical)`. The decorator prepends the original: `[query, query.withExpansion(hypothetical)]`
5. **Two full retrieval cycles** execute through the decorator chain:
   - **Original query** (no expansion): `embed(searchText)` → four-leg Qdrant retrieval → cross-encoder rerank — identical to the no-HyDE baseline
   - **Expanded query**: `embedSeparate(searchText, text)` → four-leg retrieval with per-leg separation (dense uses hypothetical, sparse/BM25 use original) → cross-encoder rerank using `query.text()` (original query, not the hypothetical)
6. `QueryExpandingCaseRetriever` merges both result sets via RRF fusion (k=60)

The cross-encoder always evaluates against the original query text regardless of expansion, providing a consistent relevance signal and guarding against expansion noise in the candidate set.

### Dependencies

- Claude agent via Vertex AI (`ANTHROPIC_VERTEX_PROJECT_ID`, `CLOUD_ML_REGION`, `CLAUDE_CODE_USE_VERTEX` env vars)
- `casehub-neocortex-rag-expansion` module (already a dependency)
- `casehub-platform-agent-claude` module (already a dependency)

## Verification

Re-run the existing benchmark harness (`scripts/benchmark/`) against all 14 real-world scenarios.

**Prior results:** Issue #40 benchmarks (`scripts/benchmark/results/no-hyde-baseline.json`, `hyde-session.json`, etc.) established that HyDE was net-zero at limit=16 WITHOUT per-leg separation. The re-benchmark should show improvement now that sparse/BM25 retain their lexical signal.

**Comparison:**
- **Baseline:** current config (HyDE off, four-signal + cross-encoder + adaptive filtering)
- **Treatment:** HyDE on (same pipeline, expansion enabled)

**Acceptance criteria:**
- Zero precision regressions on any individual scenario
- At least 1 scenario shows improvement (HyDE is net-positive, not net-zero)
- Latency measured and documented. Expected overhead per query: ~2.5-3.0s total, comprising LLM round-trip (~2.4s) + second retrieval cycle (embedding ~50-200ms, Qdrant retrieval ~50-150ms, cross-encoder rerank ~50-200ms) + RRF fusion (negligible). The LLM call dominates.

### Testing

Existing `SessionQueryExpanderTest` covers `shouldSkip()` — the confidence gating and hedging-detection logic. The expansion flow itself (prompt template → LLM call → `withExpansion()`) and session management are not unit-tested; the benchmark serves as the integration test. No new unit tests needed — the change is config, not code.

## Implementation Steps

Beyond the config change, these housekeeping steps are required to close #50:

1. **Update neocortex dependency** — `mvn install` neocortex main to pick up #117's `embedSeparate()` API. The retrievers now use the proper contract instead of the `embedBatch` workaround.
2. **Update #50 acceptance criteria:** replace "#117 dependency resolved" with "#113/#117 dependency resolved (per-leg separation merged and promoted to API contract)."
3. **Uncomment config** (the three `application.properties` lines above)
4. **Remove the DISABLED comment** from `application.properties`
5. **Run benchmark** and document results per Verification section

## Out of Scope

- Latency optimisation (caching, async prefetch, session lock granularity) — #52
- Expansion drift metrics (#51) — independent, blocked on neocortex #118/#120
