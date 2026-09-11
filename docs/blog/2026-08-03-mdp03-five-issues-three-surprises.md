---
layout: post
title: "Five Issues, Three Surprises"
date: 2026-08-03
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [retrieval, embedding, evaluation, bge-m3, colbert, onnx]
---

The retrieval quality epic started with five issues and a clear thesis: code-domain embedding models would outperform general-purpose ones on a Java/Quarkus knowledge corpus, because better tokenization means better embeddings means better retrieval. Four layers of evaluation, a custom ONNX export, ColBERT late interaction, and a dedicated relevance classifier. Clean plan, logical progression.

Three of those assumptions turned out to be wrong.

The first surprise was the vocabulary gap. It's real — BERT WordPiece fragments `ConcurrentHashMap` into `concurrent ##has ##hma ##p`, while code-trained BPE keeps it as two meaningful tokens. UniXcoder and jina-code handle CamelCase boundaries cleanly. BGE-M3's SentencePiece tokenizer turned out to be the worst of the lot — 5.3 tokens per identifier, worse than WordPiece's 4.8. A multilingual vocabulary budget buys language coverage but costs you programming identifiers.

But tokenization quality doesn't predict retrieval precision. The full benchmark across 14 scenarios showed nomic-embed-text — the oldest, simplest baseline — at 60.4% overall precision. BGE-M3 at 56.5%. The code-trained candidates below both. jina-code was the only model that correctly discriminated between `@DefaultBean` and the English word "default" (0.40 cosine vs nomic's 0.75), yet it scored 6 points below nomic on actual retrieval. Better embedding discrimination does not survive the distance from vector similarity to ranked results when corpus size, vocabulary distribution, and score thresholds intervene.

The recommendation: stay with the current multi-leg retrieval architecture. BGE-M3 earns its place through multi-modal capability — dense, sparse, and ColBERT from one forward pass — not through better dense embeddings.

The second surprise was ColBERT. The epic had a dedicated issue for ColBERT late interaction retrieval: a new inference module, ONNX export of ColBERTv2, integration in the RAG pipeline, RRF fusion extension. Scale: L, Complexity: High. When we sat down to start it, every deliverable was already done. The BGE-M3 adoption work had landed ColBERT as a head of the three-head model. `BgeM3Embedder` extracts ColBERT embeddings. `HybridCaseRetriever` does MAX_SIM rescoring via Qdrant multi-vectors. `QdrantEmbeddingIngestor` stores ColBERT token vectors at ingestion time. The issue was written before the design evolved from "separate ColBERT module" to "ColBERT as one head of BGE-M3." The work was done — just tracked under different issues.

The third surprise was the dedicated relevance classifier. The issue scope said "when a consumer demonstrates that cross-encoder thresholds produce insufficient classification accuracy for their domain." None of the consumer apps have shipped yet. Training a three-way classifier without evidence that existing thresholds are inadequate is building for a problem that might not exist. Deferred — the issue stays open for when the signal arrives.

Five issues entered. Two produced real deliverables: the evaluation pipeline with its counter-intuitive findings, and the ONNX export script that turns a HuggingFace model into a three-headed inference artifact. One was already done. One was premature. The fifth — running the export script to actually produce the model file — is a fifteen-minute manual step waiting for someone with 8GB of RAM and a Python environment.

The retrieval pipeline is the same architecture it was before the epic. What changed is confidence: we tested the alternatives, measured the gaps, and confirmed the design choices with data instead of intuition. That's worth the trip even when the answer is "stay the course."
