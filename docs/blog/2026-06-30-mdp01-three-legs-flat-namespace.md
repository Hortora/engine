---
layout: post
title: "Three Legs and a Flat Namespace"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [rag, bm25, qdrant, retrieval, metadata]
---

## Three Legs and a Flat Namespace

The RAG pipeline had two retrieval legs — dense embeddings for semantic similarity and SPLADE for learned term expansion. Neither catches exact keyword matches reliably. Java class names like `ConcurrentHashMap` and CDI annotations like `@DefaultBean` live in a vocabulary gap: semantically meaningful but lexically opaque to both retrieval strategies.

Issue #48 proposed BM25 as a third leg, using Qdrant's full-text payload index with a filter-based prefetch. I spent time verifying whether this actually works for scored retrieval — and it doesn't. A filter-only prefetch produces boolean matches: every matching document gets equal rank. RRF needs meaningfully different scores per document to produce useful fusion. A leg where everything scores the same contributes nothing.

The right approach turned out to be server-side BM25 sparse vectors. Qdrant's `Document` inference API takes raw text and a model name (`"qdrant/bm25"`), generates BM25 sparse vectors on the server, and stores them as a named sparse vector space with `Modifier.Idf` for IDF weighting. The Java client has the API surface — `VectorFactory.vector(Document)`, `QueryFactory.nearest(Document)` — but the Javadoc calls it "cloud inference," which is misleading. It works on any self-hosted Qdrant v1.12+.

Three-way RRF fusion is straightforward once the BM25 vectors exist: dense prefetch, SPLADE prefetch, BM25 prefetch, all fused via `QueryFactory.rrf()`. The retriever needed restructuring though — the old code branched on `sparseEmbedder != null` to choose between hybrid and dense-only modes. With BM25 as an independent toggle, the condition becomes `sparseEmbedder != null || config.bm25Enabled()`, and each leg is conditionally added within the RRF block. Four modes fall out naturally: dense-only, dense+SPLADE, dense+BM25, dense+SPLADE+BM25.

The camelCase problem affects both the text payload index and BM25. Qdrant's word tokenizer treats `ConcurrentHashMap` as a single token — searching for `HashMap` finds nothing. The fix is a `CamelCaseExpander` that appends split sub-tokens: `ConcurrentHashMap` becomes `ConcurrentHashMap Concurrent Hash Map`. Applied only to BM25 Document text, not to the stored content or SPLADE input. SPLADE uses WordPiece subword tokenisation which already decomposes identifiers at the sub-word level.

The metadata collision issue was a first-principles exercise. `QdrantPointBuilder` writes `content`, `sourceDocumentId`, and `tenantId` as payload fields, then iterates user metadata into the same flat map. A metadata key named `"content"` silently overwrites the stored content. With payload indexes, the damage is amplified — wrong BM25 results, missed deletes, cross-tenant data leakage if `tenantId` gets overwritten.

The question was where to validate. `content` and `sourceDocumentId` are fields on `ChunkInput` itself — a metadata entry shadowing them is a structural contradiction regardless of storage backend. That validation belongs in the API tier, in `ChunkInput`'s constructor. `tenantId` comes from `CorpusRef`, not `ChunkInput` — the collision happens because Qdrant's flat payload namespace puts both sources into the same map. A PostgreSQL implementation using separate columns wouldn't have this problem. That validation belongs in `QdrantPointBuilder`. Two layers, each validating what it owns.

The constructor redesign was overdue. `HybridCaseRetriever` had fifteen parameters — adding three more for BM25 would have made it unverifiable by inspection. Passing `RagConfig` directly drops the retriever to six parameters and the ingestor to five. Adding future config properties is zero-cost.

The design review caught twelve issues across four rounds — the most significant being that `bm25Enabled` was initially placed in `RetrievalConfig` even though it governs ingestion too, and that the retrieval mode selection logic didn't support BM25-only mode without SPLADE. Both were fixed before implementation began.
