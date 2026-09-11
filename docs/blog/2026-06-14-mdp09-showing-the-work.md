---
layout: post
title: "Showing the Work"
date: 2026-06-14
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [examples, onnx, rag, inference, architecture]
---

# Showing the Work

The inference and RAG layers have been shipping for two weeks now. NLI, classification, reranking, SPLADE, hybrid search with RRF fusion, corpus ingestion with cursor persistence — all of it tested, documented, reviewed. But there was no way for someone evaluating the library to see any of it working without reading the test suites and mentally assembling the picture.

I wanted to fix that. Not a README with code snippets — an actual runnable project that downloads real ONNX models, runs real inference, and produces real scores. Something you can clone, build, and demo in under a minute.

## The architecture justification came first

Before writing any example code, I went looking for evidence. Not "I think this is good" evidence — published benchmarks, named studies, specific numbers. The question was simple: is this approach to RAG state of the art, or are we behind?

The answer was unambiguous. Hybrid dense+sparse retrieval improves NDCG by 26–31% over dense-only. SPLADE as the sparse leg — what we use instead of BM25 — cuts hallucination by 43% relative to pure vector search in a May 2026 enterprise benchmark. Cross-encoder reranking adds +17.2 percentage points to MRR@3. And running it all in-process via ONNX Runtime, with versioned model artifacts, is exactly what the InfoQ enterprise architecture guide recommends for regulated domains where inference must be deterministic and auditable.

Every one of those numbers is cited, every claim linked to a source. The whole thing is at `docs/architecture-justification.md`. When someone asks "why this approach?" — point them there.

## Two modules, one infrastructure boundary

The split was obvious once I thought about it. The inference layer needs nothing — no Quarkus, no Docker, no vector database. You load an ONNX model and call `nli.classify()`. That's one module. The RAG pipeline needs Qdrant, CDI wiring, corpus storage. That's another.

`example-text-analysis` has five demos, each with a `main()` you can run directly:
- **NliDemo** — premise/hypothesis pairs across tech, news, and legal domains. "CDI performs injection at runtime" vs "CDI uses compile-time injection" — the model correctly identifies the contradiction.
- **ZeroShotClassificationDemo** — the same NLI model repurposed for classification. For each candidate label, construct hypothesis "This text is about {label}", run NLI, pick the highest entailment score. No separate classification model needed.
- **ScoringDemo** — sentiment and toxicity scoring with `TextClassifier`. "The service was absolutely terrible" scores strongly negative. "Revenue declined 12% year over year" scores neutral — factual, not sentiment.
- **RerankingDemo** — ten candidates of varying relevance to "How does ONNX inference work on the JVM?" The cross-encoder reshuffles them and the ONNX-related candidates rise to the top.
- **SparseEmbeddingDemo** — SPLADE sparse embeddings showing which vocabulary tokens get the highest weights. "Dependency injection" produces weights for "inject", "bean", "container" — the term expansion that makes SPLADE better than BM25 for domain-specific queries.

`example-rag-pipeline` tells the end-to-end story: store documents in a corpus, ingest them into Qdrant via `CorpusIngestionService.processBinding()`, then search with `CaseRetriever.retrieve()`. Flat storage and ZIP storage each get their own demo. Compaction gets demonstrated — rollover the active ZIP, find the closed entry, compact tombstones.

## The three-domain trick

Every demo uses the same three content domains: tech documentation, financial news, and legal clauses. The same inference pipeline processes "CDI performs dependency injection at runtime" and "Early termination requires 90 days written notice" and "The Federal Reserve held rates steady." Same code, different content, all producing meaningful results.

This was the right call. A single-domain example would look like a toy. Three domains sell generality without the reader having to imagine it.

## What the spec review caught

The spec went through three review rounds before implementation. Claude caught several things I would have shipped wrong:

The original Act 1 manually wired `ChangeSource → CorpusReader → MetadataExtractor → chunk → EmbeddingIngestor`. That's not how any real application uses the system — `CorpusIngestionService.processBinding()` does all of that internally. The example would have taught the wrong pattern.

Act 2 claimed to show the intermediate search stages — dense results, sparse results, RRF fusion, then reranking. But `CaseRetriever.retrieve()` returns final results. `HybridCaseRetriever` runs all four stages as a single pipeline. There's no API to peek at intermediate results. The spec was promising something the SPI doesn't deliver.

`ClassificationDemo` was supposed to exercise `TextClassifier`, but the NLI-based zero-shot pattern exercises `NliClassifier` in a loop. Different API entirely. The coverage grid would have been wrong.

## The SPLADE model trap

I almost shipped with the wrong model. `naver/splade-cocondenser-ensembledistil` is the SPLADE model every tutorial references. It's also Creative Commons NonCommercial — a fact that isn't prominent on the HuggingFace model card. For a commercial platform, that's a non-starter.

The alternative — `prithivida/Splade_PP_en_v1` — achieves equivalent MRR@10 (~37.2) with lower FLOPS and a permissive license. It's an independent reimplementation that matches the original's quality without the licensing constraint. The Xenova ONNX export of the naver model also returns HTTP 401 silently — gated despite the source model being public. The `onnx-models/Splade_PP_en_v1-onnx` export works without authentication.

Two garden entries submitted: the licensing gotcha and the silent gating behaviour.

## The testing taxonomy

Five test categories. Smoke tests run on every PR — plain JUnit with `InMemoryInferenceModel`, no models, no Docker, under 30 seconds. Integration tests run nightly with real ONNX models downloaded from HuggingFace and Testcontainers Qdrant.

The smoke tests catch "the example code is broken." The integration tests catch "the model produces garbage" or "reranking is silently a pass-through." Directional assertions only — `entailment > 0.7`, not `entailment == 0.8347`. Model inference isn't bit-reproducible across platforms.

The SPLADE model is 436MB. Not something you want downloading on every PR.
