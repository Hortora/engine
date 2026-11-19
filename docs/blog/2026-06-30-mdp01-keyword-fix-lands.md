---
layout: post
title: "The Keyword Fix Lands"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [bm25, qdrant, retrieval, hybrid-search]
series: issue-29-complementary-retrieval
---

*Part of a series on [#29 — Complementary retrieval capabilities](https://github.com/Hortora/engine/issues/29). Previous: [SPLADE Hotel Beauty Renovation](2026-06-29-mdp01-splade-hotel-beauty-renovation.md).*

# Hortora Engine — The Keyword Fix Lands

**Date:** 2026-06-30
**Type:** phase-update

---

## What we were trying to achieve: close the keyword gap the benchmarks exposed

The #27 and #28 benchmarks painted a clear picture. Dense embedding search works for natural language queries — 88% precision, competitive with grep. But for keyword queries containing Java identifiers (`ChatModel`, `@DefaultBean`, `AmbiguousResolutionException`), it's catastrophic. gardenSearch-KW lost 12 of 14 scenarios against grep. SPLADE didn't fix it either — the model has zero Java vocabulary, expanding `ChatModel` to "hotel, beauty, renovation."

The fix was always obvious: BM25 keyword matching. The question was how to get it into the architecture.

## What I believed going in: we'd need Java-side RRF

The design assumed Qdrant couldn't do BM25 as a server-side retrieval leg. BM25 is term-based scoring, not a vector operation — it can't be a prefetch query in Qdrant's RRF fusion. So the spec called for an in-process BM25 index with a CamelCase-aware tokenizer, maintaining an inverted index in memory alongside Qdrant, rebuilding it on startup, and fusing results in Java via `RrfFusion.fuse()`.

This meant moving RRF from Qdrant to Java — two separate Qdrant queries (dense, sparse) running in parallel, a third BM25 leg in-process, and client-side fusion. More code, more complexity, a consistency model between two stores.

## Qdrant already speaks BM25

The implementation discovered that Qdrant v1.18+ supports Document vectors with a built-in `qdrant/bm25` model. Store CamelCase-expanded text as a named vector at ingestion time, query it with `QueryFactory.nearest(Document)` at search time, and it works as a third prefetch leg in server-side RRF alongside dense and sparse.

```java
PrefetchQuery bm25Prefetch = PrefetchQuery.newBuilder()
    .setQuery(QueryFactory.nearest(
        Document.newBuilder()
            .setText(CamelCaseExpander.expand(query.text()))
            .setModel("qdrant/bm25")
            .build()))
    .setUsing("bm25")
    .setLimit(40)
    .build();
```

One gRPC call. Three retrieval legs. Server-side fusion. No Java-side RRF, no in-process index to maintain, no consistency model. The entire Java-side RRF section of the spec became unnecessary.

The Qdrant Java client's Javadoc is misleading — `VectorFactory.vector(Document)` says "cloud inference" but it works on self-hosted instances. And `SparseVectorParams.setModifier(Modifier.Idf)` is required for actual BM25 scoring but isn't documented anywhere in the Javadoc. We found both by reading the gRPC protobuf definitions.

## CamelCase tokenization: the real design insight

The CamelCase expander preprocesses text before Qdrant's BM25 tokenizer sees it. `ChatModel is a LangChain4j interface` becomes `Chat Model is a Lang Chain 4 j interface`. Qdrant's word tokenizer then produces the right terms.

We also built an in-process `CodeDomainTokenizer` that takes a different approach — it produces both the compound AND the components: `ChatModel → [chatmodel, chat, model]`. The compound token `chatmodel` is rare (high IDF), so BM25 naturally ranks documents containing the exact identifier above documents that merely mention "chat" and "model" separately. The tokenizer drives ranking quality through IDF statistics, not through special scoring logic.

## What shipped

The work landed across two repos. In neural-text: `BM25Index` with PayloadFilter evaluation, `BM25IndexRegistry` for corpus scoping, `CamelCaseExpander` for Qdrant BM25 text preprocessing, `ExtractionResult` and `ChunkInput` gained `listMetadata()` for tags-as-list, `HybridCaseRetriever` gained a BM25 prefetch leg, and metadata indexes on `domain`, `type`, `tags`.

In the engine: `gardenSearch` now accepts `type` and `tags` filter parameters. `GardenMetadataExtractor` emits tags as list metadata. A re-index after deployment converts tags from comma-separated strings to Qdrant list values.

The architecture moved from "Phase 2: dense + sparse" to "Phase 3: dense + sparse + BM25" — three retrieval legs fusing inside Qdrant in a single call, with metadata payload filtering on top.

Whether BM25 actually closes the keyword gap is the next benchmark to run. The infrastructure is in place; the validation follows.
