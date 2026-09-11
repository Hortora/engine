---
layout: post
title: "The String That Did Three Jobs"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [rag, hyde, query-expansion, spi-design]
---

I'd been staring at `CaseRetriever.retrieve(String query, ...)` for weeks without seeing the problem. The `String query` parameter was doing three separate things: providing text for dense embedding, providing text for sparse SPLADE embedding, and providing text for cross-encoder reranking. When all three use the same text — the user's raw question — nobody notices. It works fine.

HyDE breaks that assumption. The whole point of Hypothetical Document Embeddings is to generate a fake document passage that sounds like a real answer, then embed *that* for vector search instead of the raw query. The hypothetical document is closer in embedding space to real relevant documents than a short question would be. But if you just swap the query string in a decorator, you corrupt reranking — the cross-encoder would score "does this chunk match the hypothetical document?" instead of "does this chunk answer the user's question?"

The fix was to stop treating the query as a string and model what it actually is. `RetrievalQuery` carries both `text()` (the original question) and `expandedText()` (the hypothetical document). A convenience method `searchText()` resolves which to use for similarity matching. The CaseRetriever SPI now takes `RetrievalQuery` — a breaking change to every consumer, but the migration is mechanical and the breakage is the point. Every caller is now explicit about what they're passing.

The dense/sparse split was the second insight I hadn't anticipated. HyDE targets dense retrieval — the hypothetical document bridges the semantic gap in embedding space. But SPLADE already does its own learned term expansion for lexical matching. Feeding SPLADE a hallucinated passage risks introducing phantom terms that don't appear in real documents. For clinical and legal text, where precise terminology matters, that's poison. So `HybridCaseRetriever` now uses `searchText()` for dense embedding and `text()` for sparse — each leg gets the text it's designed for.

The CRAG retrofit came along for the ride. I'd been meaning to tighten CRAG's activation model anyway — pure classpath activation is too loose when a dependency pulls in the module transitively. Both CRAG and HyDE now require classpath presence *and* a config property. Same principle: make activation explicit, don't let it happen by accident.

The `rag-hyde` module follows the same `@Decorator` pattern as CRAG — `@Priority(200)` puts HyDE outermost, CRAG at 100 sits inside. The decorator calls a `QueryExpander` SPI (two implementations: LLM-backed for real work, template-based for structured domains or testing), wraps it in fail-safe error handling, and delegates. If the LLM call fails, retrieval continues with the original query. An LLM timeout shouldn't take down the whole RAG pipeline.

Forty-one files changed across seven modules. The kind of change where the blast radius looks alarming but every individual edit is obvious. The `RetrievalQuery` type is fifteen lines of code. The architectural insight it encodes took longer to see than to implement.