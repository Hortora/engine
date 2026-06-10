# Qdrant Domain Payload Filter — Design Spec

**Date:** 2026-06-05  
**Status:** Approved  
**Issue:** Hortora/engine#2  
**Deferred:** Hortora/engine#3 (multi-domain `IsIn` variant)

---

## Problem

`SearchResource.search()` currently applies the domain filter post-retrieval in Java:

```java
.filter(m -> domain == null || domain.isBlank()
        || domain.equals(m.embedded().metadata().getString("domain")))
```

Qdrant returns up to `limit` (default 8) entries ranked by vector similarity. Java then discards entries whose domain doesn't match. If 7 of the top 8 results are from a different domain, the caller receives 1 result instead of 8 — silently truncated, no error, no indication that filtering occurred. At corpus scale, this degrades recall significantly.

---

## Fix

Push the domain filter into Qdrant as a payload pre-filter using LangChain4j's `Filter` API. Qdrant evaluates the filter before vector scoring; only matching entries participate, and `limit` is respected correctly.

---

## What Changes

**`SearchResource.java` only.** `GardenIndexer`, `GardenMcpTools`, and all test infrastructure are unchanged.

### SearchResource — before

```java
List<SearchResult> results = embeddingStore.search(request).matches().stream()
    .filter(m -> domain == null || domain.isBlank()
            || domain.equals(m.embedded().metadata().getString("domain")))
    .map(...)
    .toList();
```

### SearchResource — after

```java
Filter domainFilter = (domain != null && !domain.isBlank())
    ? new IsEqualTo("domain", domain)
    : null;

var request = EmbeddingSearchRequest.builder()
        .queryEmbedding(queryEmbedding)
        .maxResults(maxResults)
        .filter(domainFilter)
        .build();

List<SearchResult> results = embeddingStore.search(request).matches().stream()
        .map(...)
        .toList();
```

The post-retrieval `.filter()` call is deleted entirely.

### Filter chain (verified)

`IsEqualTo("domain", domain)` → `EmbeddingSearchRequest.filter()` → `QdrantFilterConverter.buildEqCondition()` → Qdrant gRPC `FieldCondition` — server-side payload filter applied before vector scoring.

### Domain filter semantics

| `domain` value | Behaviour |
|----------------|-----------|
| `null` | No filter — all domains returned |
| blank string | No filter — all domains returned |
| non-blank string | `IsEqualTo("domain", domain)` — Qdrant filters to matching domain only |

---

## Testing

Add one fixture entry with `domain: tools` (distinct from the existing `jvm` fixture) so domain filtering can be verified.

New tests in `SearchResourceTest`:

**`domainFilterReturnsOnlyMatchingDomain`** — searches with `?domain=jvm`, asserts no result has `domain != jvm`.

**`domainFilterExcludesOtherDomains`** — searches with `?domain=tools`, asserts no result has `domain == jvm`.

`TestEmbeddingStore` delegates to `InMemoryEmbeddingStore.search(EmbeddingSearchRequest)` which applies the `Filter` in-memory — no Qdrant infrastructure needed in tests, no test changes beyond fixtures and assertions.

---

## What Does Not Change

- `GardenIndexer` — stores `"domain"` as metadata payload string; correct as-is
- `GardenMcpTools` — calls `searchResource.searchFor(query, domain, limit)`; filter moves transparently into `search()`
- `TestEmbeddingStore`, `TestEmbeddingModel` — unchanged
- REST API shape — `GET /search?q=&domain=&limit=` unchanged

---

## Deferred

**#3 — Multi-domain filter:** extend to `?domain=jvm&domain=tools` using `IsIn("domain", List.of(...))`. Same Filter API, JAX-RS collects repeated params into `List<String>` automatically. Deferred to keep this issue focused.
