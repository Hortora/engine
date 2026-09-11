---
layout: post
title: "The memory that retrieves"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [cbr, case-based-reasoning, architecture, memory, retrieval, qdrant]
---

I started this session thinking I was adding a similarity search API. I finished it questioning whether the repo should be renamed.

The trigger was neural-text#20 — the `CaseRetriever` CBR contract. The issue proposed wiring CBR retrieval through the existing `CaseRetriever` SPI, the one that does RAG text-chunk retrieval against Qdrant. The engine would call it at plan-creation time to find similar past cases.

That design was wrong, and it took a while to see why.

`CaseRetriever` takes text in, searches a document corpus, returns text chunks. CBR takes structured feature vectors in, searches case memories, returns full case records with solutions and outcomes. Different inputs, different outputs, different backends, different similarity functions. Trying to unify them would have meant either a lowest-common-denominator interface that serves neither well, or a bloated interface with two parallel method families sharing nothing but a name.

The deeper question was where CBR retrieval belongs. PLATFORM.md said neural-text. The issue said neural-text. But when I traced what the engine actually needs — feature vectors matched against past case outcomes, with optional plan-trace composition for CHEF-style adaptation — none of that is text retrieval. The CBR contract is about case plans and routing decisions. The similarity *mechanism* might use neural-text's infrastructure (embeddings, Qdrant, payload filters), but the *abstraction* is about cases, not text.

I went down the CBR literature to make sure we weren't reinventing a wheel. Seven distinct paradigms: Textual, Feature-Vector, Plan-Based, Structural, Graph/Process-Oriented, Conversational, Knowledge-Intensive. Each with different case representations, retrieval mechanisms, and adaptation methods. CaseHub needs at least three of them — Textual (already works via `CaseMemoryStore.query(question, RELEVANCE)`), Feature-Vector (the immediate target), and Plan-Based (CHEF-style compositional adaptation for engine routing).

The CHEF reference changed how I was thinking about it. CHEF doesn't just find "a similar recipe" — it retrieves the plan structure and adapts it. Swap chicken for beef, adjust cooking time. In CaseHub terms: retrieve similar past cases, look at which bindings fired, which workers were selected, what the per-step outcomes were. The retrieved plan becomes a template for the current plan, with substitutions where the current situation differs.

That framing led to the `CbrCase` type hierarchy — an open interface (not sealed) with `TextualCbrCase`, `FeatureVectorCbrCase`, and eventually `PlanCbrCase`. The base carries `problem()`, `solution()`, `outcome()`, `confidence()` — universal across all CBR paradigms. Subtypes add structured features or plan traces. The open hierarchy means new paradigms (Structural, Graph) can be added without modifying the base.

Then the overlap became obvious. `CaseMemoryStore` in platform already does text-based CBR retrieval — `query(question, RELEVANCE)` with mem0's vector embeddings is exactly Textual CBR. The gap is Feature-Vector CBR — `CaseMemoryStore` has no way to query by structured attribute similarity. What's needed isn't a new retrieval system alongside `CaseMemoryStore` — it's CBR-specific query capabilities composed with the existing memory system.

The design review caught the critical issue I'd missed: `CbrCaseMemoryStore extends CaseMemoryStore` creates CDI bean displacement across repos. A `QdrantCbrCaseMemoryStore` in neural-text would also satisfy `CaseMemoryStore` injection points in platform, displacing JPA and SQLite backends. `GraphCaseMemoryStore extends CaseMemoryStore` works within platform because everything shares one CDI archive. Cross-repo, the `extends` relationship is a trap. The fix is a standalone SPI with composition via delegation — `CbrCaseMemoryStore` delegates to an injected `CaseMemoryStore` for durable storage, handles its own CBR indexing separately.

The Qdrant implementation uses what the spec calls "Approach 3": payload filters for categorical features (exact match — "Zerg" matches "Zerg", not "insect strategy"), float indexes for numeric features (range queries), and an optional dense vector for the `problem()` text. The feature schema drives index creation — applications declare which fields are categorical, numeric, or text, and the backend creates the right indexes. This sidesteps the embedding vocabulary problem that killed SPLADE for Java domain terms (GE-20260629-63d619). Categorical precision comes from exact payload matching, not from hoping an embedding model has the right vocabulary.

The session ended with a bigger architectural question: should all the memory backends (JPA, SQLite, inmem, mem0, graphiti) move from platform into this repo? The argument is coherence — one repo owns all knowledge storage and retrieval, shared infrastructure, a single research base for comparing backends. The counterargument is that JPA and SQLite are CRUD stores with no connection to neural text. We filed it as a separate epic (#56) — the CBR work ships first with delegation, the migration happens after.

And the name question. "Neural-text" was right when this repo was ONNX text inference. With memory backends, CBR retrieval, corpus storage, and RAG hybrid search, it's the platform's knowledge brain. Someone suggested "neocortex" — pairs with the existing `casehub-ras` (Reticular Activating System). RAS detects situations. The neocortex stores, retrieves, and reasons about them. Filed as #57 — after the current work lands.