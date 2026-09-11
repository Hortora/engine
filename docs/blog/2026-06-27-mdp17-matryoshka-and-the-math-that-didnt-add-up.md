---
layout: post
title: "Matryoshka and the Math That Didn't Add Up"
date: 2026-06-27
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [matryoshka, quantization, qdrant, rag, embeddings]
series: issue-31-matryoshka-binary-quantization
---

The issue for #31 described a tiered search pipeline: store both full-precision and truncated+binary-quantized vectors per point, use the compact one for fast first-pass retrieval, rescore against the full-precision one for accuracy. The idea is sound — at scale. The question was whether CaseHub operates at that scale.

I worked through the RAM arithmetic for CaseHub's deployment profile. At 200 tenants with 100K chunks each, Qdrant's native binary quantization on the existing full-dimension vectors reduces RAM from ~80GB to ~2.5GB. That's a 32x improvement from a config flag — no new vectors, no new search logic, no ingestion changes.

The dual-vector approach would reduce it further to ~320MB. But Qdrant builds an HNSW graph for every named vector, including the full "dense" vector that's only used for rescoring — never searched directly. That graph costs ~128 bytes per vector. The binary index savings from the compact vector (128→16 bytes per vector) are almost entirely eaten by the wasted HNSW graph. Net benefit: negligible below 10M vectors per collection.

So I killed the dual-vector design and built two simpler things instead.

First, a Matryoshka truncation decorator. It wraps LangChain4j's `EmbeddingModel`, truncates every embedding to a configurable dimension, and renormalises. The key property: `dimension()` returns the truncated size, so everything downstream — collection creation, point building, search queries — automatically uses the right dimension with zero code changes. Matryoshka-trained models (nomic-embed-text, the BGE family, jina-embeddings-v2) front-load semantic information in the first N dimensions, so truncation preserves ranking quality. The trade-off is explicit: smaller vectors, slightly less precision, but the compression compounds with Qdrant's quantization.

Second, Qdrant quantization as a collection config. Binary or scalar, applied to the dense vector's `VectorParams` at collection creation time. Qdrant handles the rest — builds the binary index, uses it for first-pass candidate selection, rescores against the float32 originals.

The interesting wrinkle was search-time oversampling. Qdrant's default oversampling of 1.0 means the binary index retrieves exactly `limit` candidates before rescoring. For binary quantization, where hamming distance is a coarse approximation of cosine similarity, that's too few — good candidates get filtered before the rescore step has a chance to correct the ranking. We added configurable oversampling via `QuantizationSearchParams` on the dense prefetch leg. Claude found that `PrefetchQuery.Builder.setParams()` accepts per-leg search parameters — not prominently documented but exactly what's needed. It means oversampling targets only the dense leg in an RRF hybrid query, leaving the sparse SPLADE leg unaffected.

A pre-existing bug surfaced during the design: `ReactiveRagBeanProducer` cached `denseDimension` from the raw CDI-injected model in `@PostConstruct`, before any decorator wrapping. With Matryoshka active, this would create collections with the original dimension while upserting truncated vectors — a runtime failure with no startup validation. The fix was to remove the `denseDimension` constructor parameter entirely. The reactive ingestor now computes it from its `embeddingModel` argument in the constructor, which runs during CDI startup on the main thread. The field stays cached because `buildCreateRequest()` runs on Qdrant's gRPC callback thread via `directExecutor()` — calling `dimension()` there could block if the default implementation embeds "test".

We also added dimension validation: when `ensureCollection()` finds an existing collection, it reads the vector config and compares dimensions. If someone redeploys with a different Matryoshka dimension, the ingestor fails fast with a clear message instead of silently sending wrong-sized vectors to Qdrant.

Naming the config enum `DenseQuantization` instead of `QuantizationType` was a deliberate collision avoidance — Qdrant client 1.18.1 already defines `io.qdrant.client.grpc.Collections.QuantizationType` with values `UnknownQuantization` and `Int8`. Both enums appear in the same method bodies. I initially thought the Qdrant enum didn't exist — `ide_find_class` returned nothing for it — but `ide_find_symbol` found it immediately. Protobuf-generated nested enums inside 20K-line outer classes don't always surface in the class index.

Six commits, 15 files changed, 146 tests passing. All opt-in via config: `casehub.rag.matryoshka.dimension`, `casehub.rag.quantization.type`, `casehub.rag.quantization.oversampling`. Existing deployments see zero behaviour change.