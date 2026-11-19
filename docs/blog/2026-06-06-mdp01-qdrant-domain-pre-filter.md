---
layout: post
title: "Pushing domain filters into Qdrant"
date: 2026-06-06
type: phase-update
entry_type: note
subtype: diary
projects: [engine]
tags: [langchain4j, qdrant, retrieval]
---

The first real feature work on engine was fixing something that looked
correct but wasn't.

The search endpoint was filtering by domain after Qdrant returned results. At
small corpus size — a handful of fixture entries — it's invisible. But at scale
it's wrong: Qdrant returns the top-N entries by vector similarity, then Java
discards those that don't match the requested domain. If the top 8 results
happen to be from the wrong domain, the caller gets nothing back. No error, no
warning, just zero results.

The fix is LangChain4j's `Filter` API. Pass an `IsEqualTo("domain", domain)` to
`EmbeddingSearchRequest.builder().filter()` and the filter travels server-side —
Qdrant applies it before scoring, so only matching entries compete for the `limit`
slots.

```java
Filter domainFilter = (domain != null && !domain.isBlank())
        ? new IsEqualTo("domain", domain)
        : null;

var request = EmbeddingSearchRequest.builder()
        .queryEmbedding(queryEmbedding)
        .maxResults(maxResults)
        .filter(domainFilter)
        .build();
```

I knew Qdrant had payload filtering — I didn't know LangChain4j exposed it through
`EmbeddingSearchRequest`. We verified the chain from bytecode: `IsEqualTo` →
`QdrantFilterConverter.buildEqCondition()` → gRPC `FieldCondition`. Not in the
docs but clearly supported.

Writing the tests surfaced a different problem. REST Assured's Groovy GPath
`$.findAll { }` returns `null` when no elements match — not `[]`. So `hasSize(0)`
throws even when the filter is working correctly. The fix is
`anyOf(nullValue(), hasSize(0))`. Small thing, but the failure message ("was null")
reads like the assertion found a problem, not that the predicate returned nothing.

Both findings went into the knowledge garden — the GPath null return as a gotcha,
the LangChain4j filter technique as a technique. Both would have cost time to
discover independently.
