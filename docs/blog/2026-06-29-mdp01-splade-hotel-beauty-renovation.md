---
layout: post
title: "The Web-Search Model That Thought ChatModel Was a Hotel"
date: 2026-06-29
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [splade, hybrid-search, onnx, vocabulary, benchmark]
series: issue-28-splade-hybrid-benchmark
---

*Part of a series on [#28 — SPLADE hybrid benchmark](https://github.com/Hortora/engine/issues/28). Related: [The Embedding Vocabulary Gap](2026-06-27-mdp01-embedding-vocabulary-gap.md).*

The #27 benchmark left a clear diagnosis: nomic-embed-text treats Java class names as generic tokens. `@DefaultBean`, `ConcurrentHashMap`, `ExceptionMapper` — all invisible to the embedding model. gardenSearch-NL tied grep 6-6-2 on natural language queries, but keyword queries were catastrophic.

The obvious next question: does SPLADE fix it? SPLADE learns vocabulary expansion — given a query, it activates additional tokens the query didn't contain. The theory is that `DefaultBean` might expand to `inject`, `qualifier`, `alternative`. The engine already had the hybrid search wired: `HybridSearchProducer`, `CollectionMigration`, RRF fusion. All built, never tested end-to-end.

That last part turned out to be the first finding.

## Two ONNX incompatibilities

The SPLADE model (`prithivida/Splade_PP_en_v1`) wouldn't load. `OnnxInferenceModel` expects HuggingFace input names (`attention_mask`, `token_type_ids`); the model uses original BERT names (`input_mask`, `segment_ids`). Same tensors, different labels. Then the output: rank-3 `[batch, seq_len, 30522]` where the runtime expects rank-2 `[batch, 30522]`. SPLADE outputs per-token activations that need max-pooling across the sequence dimension — standard in Python SPLADE implementations, but the ONNX export doesn't include it and the Java runtime doesn't handle it.

I renamed the inputs and added a `ReduceMax` node to the ONNX graph using the Python `onnx` library. Quick workaround — the proper fixes are neural-text issues now.

The real takeaway: the Phase 2 hybrid search code was architecturally sound but had never loaded the actual models. CDI wiring, `@LookupIfProperty` conditional beans, `CollectionMigration` — all correctly implemented. The integration gap was at the ONNX model boundary, invisible until someone uncommented the config and pressed start.

## What SPLADE actually sees

Before running the hybrid benchmark, I ran the SPLADE model independently and decoded its sparse vectors — what tokens does it activate for each query?

The results were definitive. For `ChatModel|AgentSession|prompt.cach|LangChain4j`, SPLADE expanded to: `agent`, `talk`, `beauty`, `renovation`, `genre`. For `DefaultBean|AmbiguousResolutionException`, it produced: `ambiguity`, `groups`, `unclear`. For `Priority(100)|CDI priority`: `priorities`, `cds` (as in music CDs), `urgency`, `precedence`.

Zero Tier 1 domain term activations across all 14 keyword queries. Not one hit on `panache`, `jandex`, `qualifier`, `interceptor`, `singleton`, or any unambiguous Java/CDI term. SPLADE's MS MARCO training contains web-search associations: "chat" maps to hotels and beauty parlors, not to LangChain4j adapters.

This is not a model defect. It is expected behaviour for a model trained on web passages with a general BERT tokenizer. The tokenizer fragments `AmbiguousResolutionException` into `["am", "##bi", "##guous", "##reso", "##lution", "##exception"]` — the semantic content is destroyed before SPLADE even runs.

## The hybrid benchmark

I ran the full benchmark anyway — dense-only baseline, then dense+SPLADE with RRF fusion. SPLADE changed every single query result (28/28). It displaced 3 highly relevant entries while removing 40 noise entries. The full hybrid (adding the cross-encoder reranker) crashed the JVM after one query — 213ms latency per query was the single data point before the engine died.

The dense+SPLADE latency overhead was modest: ~15ms per query (43ms vs 28ms). But the result quality was a wash — SPLADE's generic web-domain expansions interfere with the dense cosine similarity via RRF fusion without adding domain-relevant signal.

A second finding emerged from the baseline comparison. The dense-only re-run against #27's results showed NL queries at 92% overlap — stable and reproducible. But keyword queries showed only 24% overlap. Same engine, same model, 24 new entries in a 1,984-entry corpus. Pipe-separated Java class names produce embeddings near the decision boundary — small corpus changes cause near-complete result set replacement. Keyword embedding is not just low-quality, it is unreliable.

## Where the fix actually lives

The benchmark confirmed what the vocabulary analysis already proved: general-purpose web-trained models cannot fix the Java class name problem. The BERT tokenizer fragments them; the models have no Java ecosystem knowledge to learn from.

The fix is BM25 keyword matching inside Qdrant — exact token matching that does what grep does, but inside the vector database and composable with RRF fusion. Three-way fusion: dense for concepts, BM25 for keywords, sparse for learned expansion (once a code-domain model exists). The first two legs are the immediate priority; the third waits for neural-text to evaluate code-domain models.

Six issues filed on neural-text. The most important: full-text index on the content payload field, then BM25 as a third retrieval leg. Those two changes give us grep-equivalent keyword matching without leaving the Qdrant infrastructure.
