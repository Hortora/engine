---
layout: post
title: "The HyDE Wall"
date: 2026-07-25
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [retrieval, hyde, benchmark, research, negative-result]
series: issue-50-re-enable-hyde
---

*Previous: [Cross-Encoder Reranking and the Pool-Size Sweet Spot](2026-07-09-mdp01-crossencoder-lands.md).*

I spent three days trying to make HyDE work. Four different architectures, two repos, one local LLM, one academic paper, and the same result every time: -2pp precision. The retriever is too strong for expansion to help.

## The hypothesis

HyDE (Hypothetical Document Embeddings) generates a hypothetical document from the query before retrieval. The idea is that a synthetic answer is closer in embedding space to the real documents than a short query would be. It was disabled after the cross-encoder session because all retrieval legs used the hypothetical — the original query's lexical signal was lost everywhere.

Neocortex #113 landed per-leg embedding separation: dense uses `searchText()` (the hypothetical), sparse and BM25 use `text()` (the original query). Dense gets semantic expansion while sparse and BM25 retain the original vocabulary. The hypothesis was simple: with per-leg separation, HyDE should be purely additive.

## Four approaches, same result

| Approach | What it does | Precision | Delta |
|----------|-------------|-----------|-------|
| No HyDE (baseline) | Five-signal pipeline, cross-encoder, adaptive filter | 61.6% | — |
| Double-retrieval HyDE | Two full retrieval cycles (original + expanded), merged via RRF | 59.4% | -2.2pp |
| Single-retrieval HyDE | One cycle, per-leg separation routes dense→hypothetical, sparse/BM25→original | 59.4% | -2.2pp |
| Inverted HyDE | Generate hypothetical *queries* at index time via local Ollama, embed alongside content | 59.2% | -2.5pp |

The first approach ran two complete retrieval cycles — one with the original query, one with the hypothetical — and merged them via RRF. I expected the original cycle to act as a safety net. Instead, the RRF merge diluted good results with noise candidates from the expanded set. 10 of 28 scenario/query-type combinations regressed. One improved.

Neocortex #117 then promoted the per-leg separation from a retriever workaround into a proper `MultiModalEmbedder` contract — `embedSeparate(denseText, nonDenseText)`. I filed #173 to remove the original-query prepend from `QueryExpandingCaseRetriever`, turning double-retrieval into single-retrieval. One cycle, no RRF dilution. The hypothesis was that eliminating the merge would eliminate the noise.

Same result. -2.2pp, 9 regressions. The noise wasn't from RRF — it was from the hypothetical itself degrading the dense embedding.

## Flipping the approach: inverted HyDE

The literature pointed to an alternative: instead of generating hypothetical documents from queries at query time, generate hypothetical queries from documents at index time. The queries bridge the vocabulary gap between how developers search and how garden entries are written. No query-time latency penalty. Grounded in actual corpus content.

I built `OllamaQueryGenerator` — a simple HTTP client calling gemma3:4b locally — and `QueryAugmentingExtractor`, a CDI decorator on `MetadataExtractor` that appends 3 generated queries to each entry's content before embedding. The queries get embedded by BGE-M3 alongside the original text. Sidecar `.queries` files cache the generated queries with version headers so they only regenerate when the entry, prompt, or model changes.

The full garden re-index took ~30 minutes (2300 entries through Ollama, then BGE-M3 embedding). Latency at query time was identical to baseline — no LLM in the retrieval path.

The result: -2.5pp. Worse than both query-time approaches. The augmented vocabulary was introducing noise into all four embedding signals (dense, sparse, BM25, ColBERT), not just dense.

## What the literature says

Claude surfaced a paper that explains exactly what we saw: Weller et al., "When do Generative Query and Document Expansions Fail?" (EACL 2024). They tested 11 expansion techniques across 24 retrieval models and 12 datasets. The finding: **strong negative correlation between retriever performance and expansion benefit.** Expansion helps weak models. It harms strong ones.

Their error analysis: expansions provide extra information (improving recall) but add noise that makes it difficult to discern between the top relevant documents, introducing false positives.

That's the garden retriever in a sentence. Five signals, cross-encoder reranking, adaptive filtering — the pipeline is already strong enough that expansion noise outweighs expansion recall. The hypothetical document isn't better at semantic matching than the original query against a domain-specific corpus full of Java class names, CDI annotations, and Qdrant API references. A generic LLM doesn't know that `@Produces @DefaultBean @ApplicationScoped` is the specific vocabulary a matching entry would use.

## What we learned

HyDE is definitively closed for this corpus. Not "needs tuning" or "try a better model" — the architecture of the problem is wrong. The retriever is too strong for expansion to add value.

The infrastructure isn't wasted. `OllamaQueryGenerator`, `QueryAugmentingExtractor`, and the sidecar cache are built and tested. If a future corpus with a weaker retriever needs index-time augmentation, it's a config flag away. The code stays, disabled.

The precision plateau at ~62% isn't an expansion problem. It's the same ranking problem identified in the July 4 benchmark analysis: some entries that should rank in the top 16 get bumped by topically adjacent noise. The cross-encoder handles most of this, but a few scenarios still have marginal entries displacing better ones. The next lever isn't expansion — it's either better entries (curation) or better ranking (fine-tuned rerankers).

The retrieval frequency tracking from #24 feeds directly into the curation path. `gardenUnretrieved` surfaces entries that have never been retrieved — candidates for retirement, rewriting, or domain reassignment. If the problem is ranking precision within a strong pipeline, cleaning up the corpus may move the number more than any retrieval architecture change.
