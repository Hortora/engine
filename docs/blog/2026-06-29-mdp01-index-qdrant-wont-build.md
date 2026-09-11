---
layout: post
title: "The index Qdrant won't build for you"
date: 2026-06-29
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [qdrant, bm25, full-text-search, retrieval]
---

The Hortora engine benchmark proved what I suspected — nomic-embed-text treats `@DefaultBean` and `ConcurrentHashMap` as generic English tokens. Dense vector search returns WireMock entries when you search for CDI annotations. SPLADE might help, but it's also web-trained BERT. The vocabulary gap is structural.

The fix is older than embeddings: exact token matching. Qdrant supports full-text payload indexes with BM25 scoring, composable with the existing RRF fusion pipeline. We added a `TextIndexParams` index on the `content` field — Word tokenizer, lowercase, min token length 2 — so that Java identifiers become searchable as exact terms.

The tokenizer choice took more thought than I expected. Word tokenizer doesn't split camelCase — `ConcurrentHashMap` stays as one token `concurrenthashmap`. That means searching for `HashMap` won't match it. No built-in Qdrant tokenizer does camelCase splitting. For the primary use case — searching for known full identifiers — this is acceptable. For exploratory sub-identifier search, it's a gap we've filed separately.

Whitespace tokenizer was worse on every dimension: `close()` stays as a single token (search for `close` won't match), and `@DefaultBean` requires the `@` in the query. Word tokenizer strips the `@` and gives you `defaultbean` — which is what people actually search for.

While we were in `ensureCollection`, we also added keyword indexes on `sourceDocumentId` and `tenantId`. These fields are used as filters on every retrieval, delete, and list operation — without an index, Qdrant does a linear payload scan. The `tenantId` index is the real performance win: every shared-tenancy `retrieve()` call filters on it, and that's the hottest path in the system.

One surprise from the design review: `createCollectionAsync` is not idempotent — Qdrant errors if the collection already exists. But `createPayloadIndexAsync` is idempotent for same-type indexes. The blocking ingestor has a pre-existing check-then-act race on `knownCollections`, and this asymmetry means the loser's collection creation fails but the index creation calls are safe. The error handling already covers it — the failed creation prevents caching, so the next call retries through the existing-collection path.

The full-text index exists now but is unused. Issue #48 adds it as a third BM25 prefetch leg in the RRF fusion query — that's where the benchmark numbers will actually change.
