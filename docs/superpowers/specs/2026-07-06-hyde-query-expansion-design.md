# HyDE Query Expansion — Design Spec

**Issue:** #40  
**Date:** 2026-07-06  
**Approach:** B — classpath activation + custom prompt

## Problem

VOCABULARY_GAP is the #1 remaining retrieval failure mode. With the current BGE-M3 four-signal architecture (dense + learned-sparse + BM25 + ColBERT reranking), gardenSearch achieves 93% average precision on vocabulary-gap scenarios (per the BGE-M3 benchmark, 2026-07-04). Entries are still missed when the query and entry use entirely different vocabulary for the same concept (e.g. "ChatModel" vs "LangChain4j adapter pattern").

BM25 solved exact keyword matching via CamelCase expansion. Dense and learned-sparse embeddings handle semantic proximity. HyDE targets a different layer — conceptual gaps where no term overlap exists between query and corpus entry, and the query's embedding is too distant from the corpus entry's embedding for even dense retrieval to bridge.

## Decision

Prior research (retrieval-research.md, 2026-06-30) recommended skipping HyDE based on the financial QA benchmark (arxiv 2604.01733) which found HyDE "consistently underperforms vanilla dense retrieval" due to noise from fabricated content, and on the finding that BM25 closed the keyword gap that query expansion was trying to address. That decision was sound given the evidence available: literature benchmarks showed HyDE hurting, and BM25 eliminated the vocabulary gap scenarios we could measure.

What has changed since: (1) BGE-M3 replaced nomic-embed-text, introducing learned-sparse and ColBERT reranking — a fundamentally different retrieval surface; (2) VOCABULARY_GAP precision dropped from 96% (three-leg) to 93% (BGE-M3 four-signal), reopening ~7% of scenarios; (3) the prior decision was never empirically tested on this corpus — the HyDE conclusion was literature-derived.

**Specific hypothesis:** HyDE-generated passages in the domain language of the knowledge garden will improve dense embedding alignment on the ~7% of VOCABULARY_GAP scenarios where BGE-M3 four-signal retrieval currently fails — queries where no term overlap exists and the query embedding is too distant for dense retrieval to bridge.

## Architecture

Zero new Java classes in engine. Pure classpath activation via neocortex's existing `rag-expansion` module (originally `rag-hyde`, renamed during multi-query redesign — issue #40's body references the old `HydeCaseRetriever` name, now `QueryExpandingCaseRetriever`) + platform's `AgentProvider` → `ChatModel` bridge.

### Decorator chain

```
SearchResource
  → CaseRetriever (@Decorator interception)
    → QueryExpandingCaseRetriever (@Priority 200, @IfBuildProperty)
      → LlmQueryExpander (QueryExpander SPI)
        → ChatModel.chat(hydePrompt)
          → AgentProviderChatModel (platform bridge)
            → AgentProvider.invoke()
              → ClaudeAgentProvider → Claude CLI subprocess → Vertex AI
      → delegate.retrieve(query.withExpansion(hydeText))
        → HybridCaseRetriever
          → embedder.embed(query.searchText()) ← uses hydeText for dense+sparse+ColBERT
          → Qdrant (dense + sparse + BM25 + ColBERT)
```

Key: `HybridCaseRetriever` calls `embedder.embed(query.searchText())` which returns `expandedText` when set. This single `MultiModalEmbedder` call produces dense, learned-sparse (BGE-M3 XLM-RoBERTa vocabulary), AND ColBERT vectors — all three vector legs embed the hypothetical document. BM25 uses the original query terms via `CamelCaseExpander.expand(query.text())`.

**Note:** The neocortex ARC42STORIES C11 entry states "sparse embedding and reranking continue to use original `text()`" — this is inconsistent with the code, which feeds all embedding modes through a single `embedder.embed(searchText())` call. Filed as [neocortex#113](https://github.com/casehubio/neocortex/issues/113) to resolve whether the docs or code should be corrected. The benchmark will measure the combined effect on all legs. If learned-sparse expansion proves harmful, separating the embedding calls is a future optimization (neocortex scope, not engine).

### Failure handling

`QueryExpandingCaseRetriever` catches exceptions from `QueryExpander.expand()` and falls back to the original query. If the LLM call fails (timeout, API error), retrieval continues without expansion.

## Dependencies

Three new Maven dependencies:

| Artifact | Purpose | Source |
|----------|---------|--------|
| `casehub-neocortex-rag-expansion` | `@Decorator` + `LlmQueryExpander` + `ExpansionConfig` | `neocortex/rag-expansion/` |
| `casehub-platform-agent-langchain4j` | `AgentProviderChatModel` — bridges `ChatModel` → `AgentProvider` | `platform/agent-langchain4j/` |
| `casehub-platform-agent-claude` | `ClaudeAgentProvider` — Claude CLI Agent SDK backend | `platform/agent-claude/` |

Versioned as `0.2-SNAPSHOT` (hardcoded, same as existing neocortex dependencies — no BOM imports exist for neocortex or platform modules).

### Prerequisites

These three artifacts exist as source modules but are **not yet published** to the local Maven repository. Before this work can begin:

1. Build and `mvn install` the platform modules: `agent-langchain4j` and `agent-claude`
2. Build and `mvn install` the neocortex module: `rag-expansion`
3. Verify no transitive dependency conflicts with engine's existing Quarkus stack

## Configuration

```properties
# Query expansion — HyDE via Claude (platform AgentProvider)
# Disabled by default; enabled in dev profile for benchmarking
casehub.rag.expansion.enabled=false
%dev.casehub.rag.expansion.enabled=true
casehub.rag.expansion.mode=llm
casehub.rag.expansion.hypothetical-count=1
casehub.rag.expansion.prompt-template=Given the question below, write a short \
  technical knowledge-base entry (3-5 sentences) about Java, Quarkus, or software \
  development that would directly answer it. Write as if the entry comes from a \
  curated developer knowledge garden. Do not include the question itself.\n\n\
  Question: %s\n\nEntry:

# Claude agent — Vertex AI auth from env vars
# Required env: ANTHROPIC_VERTEX_PROJECT_ID, CLOUD_ML_REGION, CLAUDE_CODE_USE_VERTEX
%dev.casehub.platform.agent.claude.default-timeout=PT30S
%dev.casehub.platform.agent.claude.max-concurrent-sessions=2
```

The `@IfBuildProperty` on the decorator is a **build-time** annotation — the decorator is either compiled into the CDI bean graph at augmentation time or excluded entirely. This is not a runtime toggle:

- **Dev build** (`mvn quarkus:dev`): `%dev` profile resolves at build time → decorator included
- **Production build** (`mvn package`): default profile → decorator excluded, zero overhead

This is the correct model for benchmarking: the expansion code is only present in dev mode builds. A production build never incurs the decorator interception cost.

## Latency

`ClaudeAgentProvider` spawns a Claude CLI subprocess per invocation. For a 3-sentence HyDE response: expect 3-10 seconds per query expansion. For the 14-scenario benchmark (28 queries), total LLM time: ~70-280 seconds vs ~1.3 seconds baseline.

### Alternatives evaluated

| Option | Latency | Trade-off |
|--------|---------|-----------|
| **Claude via AgentProvider** (chosen) | 3-10s/query | Slow, but uses top-tier model — if HyDE doesn't help with Claude, smaller models won't do better |
| **Ollama local** (issue #40 option) | ~200-500ms/query | Faster, but quarkus-langchain4j Ollama extension registers `@Default ChatModel` which clashes with `AgentProviderChatModel` (see GE-337397). Also: lower-quality expansions may confound benchmark results |
| **Direct Vertex AI API** | ~1-3s/query | No existing ChatModel implementation; would require new platform module |

**Rationale for Claude first:** This is a benchmark-first exercise. Use the best available model to establish whether HyDE has any value on this corpus. If HyDE fails with Claude, it fails everywhere. If it succeeds, evaluate cheaper alternatives (Ollama, direct API) as a follow-up optimization.

## Testing

- `InMemoryQueryExpander` from `rag-testing` (`@Alternative @Priority(1)`) replaces the LLM expander in tests — deterministic, no Claude dependency
- Tests that don't set `casehub.rag.expansion.enabled=true` get no decorator activation
- Existing `InMemoryCaseRetriever` and `InMemoryEmbeddingIngestor` remain unchanged
- Add test verifying the decorator activates and transforms queries when enabled

## Benchmark Plan

Run the existing 14-scenario benchmark suite with expansion enabled vs disabled:

| Metric | Baseline (BGE-M3, no HyDE) | With HyDE |
|--------|----------------------------:|----------:|
| Precision (overall) | 87% | ? |
| VOCABULARY_GAP scenarios | 93% | ? |
| Latency (p50) | ~47ms | ? |

Focus on VOCABULARY_GAP scenarios — that's where HyDE should show improvement if it helps.

### Success criteria

| Criterion | Threshold | Outcome |
|-----------|-----------|---------|
| VOCABULARY_GAP precision gain | ≥ 5pp on any scenario | Supports HyDE adoption |
| Non-VOCABULARY_GAP regression | ≤ 3pp on any scenario | Acceptable collateral |
| Non-VOCABULARY_GAP regression | > 3pp on any scenario | Investigate before deciding |
| VOCABULARY_GAP gain < 5pp on ALL scenarios | — | HyDE not justified — close issue |

If precision improvement is marginal (< 5pp everywhere), HyDE does not justify its complexity and latency cost. Disable via config and close the issue with benchmark findings.

## Garden Context

Relevant garden entries surfaced during design:
- **GE-20260618-248ce7** — Agent.build() bakes ChatModel once; @InjectMock silently ignored
- **GE-20260614-337397** — quarkus-langchain4j Ollama extension registers @Default ChatModel, clashes with @DefaultBean
- **GE-20260629-63d619** — SPLADE has zero Java domain vocabulary

The ChatModel gotchas (GE-248ce7, GE-337397) are informational — they apply to LangChain4j's ChatModel wiring in general, but `AgentProviderChatModel` is `@DefaultBean @Priority(10)` and avoids the Ollama extension clash.

## Current Retrieval Architecture

The engine uses BGE-M3 four-signal retrieval (since #36, 2026-07-04):

| Signal | Source | What it catches |
|--------|--------|-----------------|
| Dense | BGE-M3 (1024-dim, cosine) | Semantic concept matching |
| Learned sparse | BGE-M3 (XLM-RoBERTa 250K vocab, ReLU) | Learned term expansion |
| BM25 | Qdrant Document vectors (`qdrant/bm25`) | Exact keyword matching — Java identifiers, annotations |
| ColBERT reranking | BGE-M3 (per-token MAX_SIM) | Fine-grained relevance reranking |

All four signals execute via Qdrant: three prefetch legs (dense, sparse, BM25) fused via RRF, then ColBERT rescoring of the top-N fused results. `MultiModalEmbedder.embed()` produces dense, sparse, and ColBERT vectors from a single BGE-M3 forward pass.

HyDE interacts with this architecture by replacing the query text fed to `MultiModalEmbedder.embed()` — affecting dense, sparse, and ColBERT signals simultaneously. BM25 is unaffected (uses `query.text()` directly).

## Scope Boundary

This issue is benchmark-first: wire, measure, decide. If benchmarks show marginal or negative improvement, disable via config and close the issue with findings. No commitment to ship HyDE to production.
