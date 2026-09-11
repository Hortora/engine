---
layout: post
title: "Query Expansion Grows Up"
date: 2026-06-25
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [rag, query-expansion, step-back, hyde, rrf]
---

The `rag-hyde` module had the wrong name from the start. It was always a generic query expansion decorator — it called `QueryExpander.expand()` and delegated. It didn't know or care about HyDE. But HyDE was the first strategy we built, so the module got named after it.

That's fine when there's one strategy. It becomes a lie when there are three.

This branch adds step-back prompting (#43) and multi-query HyDE (#42), which together force a rethink of the `QueryExpander` SPI. The original contract returned a single `RetrievalQuery` — one query in, one query out. Step-back needs to return two (the original plus an abstract reformulation). Multi-query HyDE needs to return N hypothetical documents. The SPI had to change.

`QueryExpander.expand()` now returns `List<RetrievalQuery>`. Single-query expansion returns a one-element list. Step-back returns `[original, abstract]`. Multi-query HyDE returns N hypothetical documents. The cardinality decision belongs to the implementation, not the framework.

The decorator handles the fan-out. When the list has one element, it's the same fast path as before — no RRF, no merging, no overhead. When there are multiple, each gets its own retrieval (through the full decorator chain, including CRAG if active), and the results merge via Reciprocal Rank Fusion. I put the RRF implementation in `rag-api` — it's pure Java, operates on `RetrievedChunk`, and any module that needs to merge ranked lists can use it.

The module rename came with the SPI change: `rag-hyde` → `rag-expansion`, `HydeCaseRetriever` → `QueryExpandingCaseRetriever`, `casehub.rag.hyde.*` → `casehub.rag.expansion.*`. No external consumers, so the blast radius was zero.

The step-back expander itself is straightforward — an LLM call that abstracts "side effects of ibuprofen for liver patients" into "pharmacological properties of NSAIDs", then retrieves with both. The design choice I want to call out is that each sub-retrieval gets independently CRAG-evaluated against its own query text. The original retrieval is judged against the original intent. The step-back retrieval is judged against the broader intent. This is intentional — evaluating the step-back results against the narrow original query would penalise chunks that are relevant to the broader context. RRF then merges two quality-checked result sets.

One thing we caught during the spec review that I wouldn't have predicted: `Uni.combine().all().unis()` throws `IllegalArgumentException` on an empty list, while the blocking equivalent (iterating an empty list) silently returns nothing. The asymmetry means the reactive path crashes where the blocking path degrades silently. We added empty-list guards to both decorators, making the behaviour consistent: empty expansion falls back to the original query.

The query expansion surface is now ready for the downstream consumers — devtown, AML, clinical — whenever engine wires up `CaseRetriever` for application case steps. Step-back prompting should help most with AML's investigation queries, where a specific suspicious-transaction search needs to also surface broader typology patterns.