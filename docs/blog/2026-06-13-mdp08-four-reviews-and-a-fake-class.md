---
layout: post
title: "Four Reviews and a Fake Class"
date: 2026-06-13
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [rag, ingestion, corpus, spi, design-review]
---

# Four Reviews and a Fake Class

The corpus modules shipped yesterday — storage, change tracking, integrity checking. Today I needed the thing that actually makes them useful: the bridge that watches for changes and pushes them into Qdrant as embeddings.

## The design that changed four times

The spec started clean. `CorpusIngestionService` polls `ChangeSource`, reads from `CorpusReader`, extracts metadata, chunks, embeds, pushes to Qdrant. Three new SPIs in `rag-api` — `MetadataExtractor`, `CursorStore`, `ExtractionResult`. A binding record per corpus carrying everything the service needs.

Then the reviews started.

The first catch was a runtime killer I'd missed entirely. `QdrantEmbeddingIngestor` calls `MemoryPermissions.assertTenant()` on every operation — the 2-arg form that assumes a CDI request context is active. The ingestion bridge runs on `@Scheduled`. No request context, no authenticated user, instant `SecurityException`. The fix was already documented in the platform protocols — the 3-arg `assertTenant(tenantId, principal, requestContextActive())` pattern from `PP-20260529-57cc3b`. The RAG adapters should have used it from the start. Ten call sites across four classes. The breakage forced every adapter to handle both request-scoped and background contexts explicitly.

The second catch was architectural. I'd put `CorpusIngestionBinding` in `rag-api` because it's the bridge's input contract. But the binding references `ChangeSource` and `CorpusReader` from `corpus-api`. Adding that dependency breaks rag-api's zero-dep invariant — the one thing that keeps it a clean Tier 1 SPI module. The binding moved to `rag/`. Any consumer producing custom bindings already depends on `rag/` for the ingestion service, so there's no coupling cost.

The third was a fabricated class name. I'd written `RecursiveCharacterTextSplitter` in the spec. It doesn't exist in LangChain4j 1.14.1. The actual API is `DocumentSplitters.recursive(int, int)` — character-based, 2-arg. And `EmbeddingModel` doesn't implement `TokenCountEstimator`, which I'd assumed. The type hierarchies are completely separate. Worth a garden entry on its own — submitted as `GE-20260613-1e5ba4`.

The fourth was subtler: reconciliation error semantics. The poll loop blocks cursor advancement on any error — all-or-nothing. But reconciliation is admin-triggered, and blocking the entire reconciliation because one corrupted document fails extraction defeats the purpose. Reconciliation is best-effort: failures are logged, cursor advances regardless. A subsequent reconciliation rediscovers the same gaps.

## The two-source binding pattern

Standard CDI `@Produces` creates one bean per method. You can't dynamically produce N beans from a config map. The working design: `CorpusBindingProducer` is a regular `@ApplicationScoped` bean with a `bindings()` method that reads config and returns a list. Separately, `Instance<CorpusIngestionBinding>` discovers any manually-produced CDI beans from application code. The service merges both sources.

This gives config-driven convenience (add properties, get ingestion) alongside custom extension (produce a binding with a git-backed `ChangeSource`, and the service picks it up automatically).

## What `ExtractionResult` fixes

The parent spec had `MetadataExtractor` returning `Map<String, String>`. That's wrong. A YAML-frontmattered document has two parts — the frontmatter (metadata) and the body (content to embed). If the extractor only returns metadata, the raw YAML gets embedded alongside the body, polluting the semantic vectors. `ExtractionResult(String body, Map<String, String> metadata)` returns both. The extractor understands the format; it knows where metadata ends and body begins.

## The numbers

Fifteen commits on the branch. Twenty-seven files changed, about 1,870 lines. Sixty-nine tests across the rag module — all using real implementations (`InMemoryEmbeddingIngestor`, `InMemoryCursorStore`), no mocks. The service has thirteen tests covering every change type, bootstrap, error semantics, reconciliation, and chunking.

Three GitHub issues filed alongside: #21 for the assertTenant protocol extension, #22 for extracting corpus CDI integration to its own module when the second consumer appears, #23 for updating the parent spec config keys.

The corpus modules now have a consumer. Documents written to a corpus will be embedded and searchable without any manual wiring. That was the gap this repo existed to close.
