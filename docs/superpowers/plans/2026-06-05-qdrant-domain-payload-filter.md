# Qdrant Domain Payload Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace post-retrieval Java domain filtering in `SearchResource` with a Qdrant payload pre-filter using LangChain4j's `IsEqualTo` filter, so `limit` is respected correctly regardless of domain distribution.

**Architecture:** Add `IsEqualTo("domain", domain)` to `EmbeddingSearchRequest.filter()` when domain is non-blank. Remove the post-retrieval Java `.filter()` stream call. `QdrantFilterConverter.buildEqCondition()` translates `IsEqualTo` to a Qdrant gRPC `FieldCondition` applied before vector scoring. `InMemoryEmbeddingStore` (used in tests) also honours the filter.

**Tech Stack:** Java 25, Quarkus 3.36, LangChain4j `dev.langchain4j.store.embedding.filter.comparison.IsEqualTo`, `dev.langchain4j.store.embedding.EmbeddingSearchRequest`, REST Assured, `@QuarkusTest`

---

### Task 1: Add tools-domain fixture

**Files:**
- Create: `src/test/resources/fixtures/ge-test-qdrant-mcp-tool.md`

The test suite currently only has one fixture entry (`domain: jvm`). Domain filter tests need at least one entry from a different domain to verify filtering excludes it.

- [ ] **Step 1: Create the fixture**

Create `src/test/resources/fixtures/ge-test-qdrant-mcp-tool.md`:

```markdown
---
title: "Qdrant MCP tool returns stale results after collection recreation"
domain: tools
type: gotcha
score: 7
tags: [qdrant, mcp, collections]
submitted: "2026-06-05"
---

When a Qdrant collection is deleted and recreated with the same name, an active
MCP tool session may return stale cached results for several seconds. The Qdrant
client caches collection metadata. Restart the garden-engine service after
recreating collections to force a fresh connection.
```

- [ ] **Step 2: Verify the fixture parses**

```bash
mvn -f /Users/mdproctor/claude/hortora/garden-engine/pom.xml test -Dtest=GardenEntryParserTest -q
```

Expected: `Tests run: 8, Failures: 0` — existing parser tests still pass, confirming the new fixture format is valid (parser tests cover the fixture directory indirectly via the indexer path, but the parser itself is exercised directly).

---

### Task 2: Write the failing tests

**Files:**
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`

Add two tests that verify domain filter behaviour. With the current post-retrieval implementation, `domainFilterReturnsOnlyMatchingDomain` passes coincidentally (Java filter also removes non-matching entries). `domainFilterExcludesOtherDomains` may fail if the tools fixture entry is the top similarity result for the query and limit=1 excludes it — the post-filter then returns 0 jvm entries. The pre-filter always returns the correct entries regardless of similarity rank.

- [ ] **Step 1: Add the two domain filter tests**

Open `src/test/java/io/hortora/garden/search/SearchResourceTest.java` and add these two tests after the existing `limitIsRespected` test:

```java
@Test
void domainFilterReturnsOnlyMatchingDomain() {
    given()
        .queryParam("q", "lazy loading transaction boundary")
        .queryParam("domain", "jvm")
    .when()
        .get("/search")
    .then()
        .statusCode(200)
        .body("$.findAll { it.domain != 'jvm' }", hasSize(0));
}

@Test
void domainFilterExcludesOtherDomains() {
    given()
        .queryParam("q", "collection metadata cache")
        .queryParam("domain", "tools")
    .when()
        .get("/search")
    .then()
        .statusCode(200)
        .body("$.findAll { it.domain == 'jvm' }", hasSize(0));
}
```

Add the Groovy path import to the top of the file alongside the existing static imports:

```java
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
```

(These are already present — no new import needed. The `$.findAll { }` Groovy path is supported by REST Assured's JsonPath by default.)

- [ ] **Step 2: Run the new tests to establish baseline**

```bash
mvn -f /Users/mdproctor/claude/hortora/garden-engine/pom.xml test -Dtest=SearchResourceTest -q 2>&1 | grep -E "Tests run|FAIL|ERROR"
```

Expected: Tests run: 6 (4 existing + 2 new), all pass or the domain tests reveal the truncation bug — either outcome is acceptable. The important thing is the tests compile and run cleanly.

---

### Task 3: Implement the Qdrant payload pre-filter

**Files:**
- Modify: `src/main/java/io/hortora/garden/search/SearchResource.java`

Replace the post-retrieval Java domain filter with a LangChain4j `IsEqualTo` filter on the `EmbeddingSearchRequest`. The full replacement of the `search()` method body is shown below.

- [ ] **Step 1: Add the import for IsEqualTo**

At the top of `SearchResource.java`, add after the existing LangChain4j imports:

```java
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.Filter;
```

- [ ] **Step 2: Replace the search() method body**

Current `search()` method body (the part inside the method, after the null/blank check):

```java
int maxResults = limit != null && limit > 0 ? limit : 8;

Embedding queryEmbedding = embeddingModel.embed(query).content();
var request = EmbeddingSearchRequest.builder()
        .queryEmbedding(queryEmbedding)
        .maxResults(maxResults)
        .build();

List<SearchResult> results = embeddingStore.search(request).matches().stream()
        .filter(m -> domain == null || domain.isBlank()
                || domain.equals(m.embedded().metadata().getString("domain")))
        .map(m -> new SearchResult(
                m.embedded().metadata().getString("title"),
                m.embedded().metadata().getString("domain"),
                m.embedded().metadata().getString("type"),
                parseScore(m.embedded().metadata().getString("score")),
                m.embedded().text(),
                m.score()))
        .toList();
```

Replace with:

```java
int maxResults = limit != null && limit > 0 ? limit : 8;

Filter domainFilter = (domain != null && !domain.isBlank())
        ? new IsEqualTo("domain", domain)
        : null;

Embedding queryEmbedding = embeddingModel.embed(query).content();
var request = EmbeddingSearchRequest.builder()
        .queryEmbedding(queryEmbedding)
        .maxResults(maxResults)
        .filter(domainFilter)
        .build();

List<SearchResult> results = embeddingStore.search(request).matches().stream()
        .map(m -> new SearchResult(
                m.embedded().metadata().getString("title"),
                m.embedded().metadata().getString("domain"),
                m.embedded().metadata().getString("type"),
                parseScore(m.embedded().metadata().getString("score")),
                m.embedded().text(),
                m.score()))
        .toList();
```

The post-retrieval `.filter()` call is removed. `domainFilter` is `null` when no domain is specified — `EmbeddingSearchRequest` accepts `null` filter to mean no filter.

- [ ] **Step 3: Run the full test suite**

```bash
mvn -f /Users/mdproctor/claude/hortora/garden-engine/pom.xml test 2>&1 | grep -E "Tests run|BUILD|FAIL|ERROR"
```

Expected:
```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0  -- GardenEntryParserTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0  -- SearchResourceTest
Tests run: 14, Failures: 0, Errors: 0
BUILD SUCCESS
```

If any test fails, check:
- `IsEqualTo` import: `dev.langchain4j.store.embedding.filter.comparison.IsEqualTo`
- `Filter` import: `dev.langchain4j.store.embedding.filter.Filter`
- That `null` is passed to `.filter()` when domain is absent (not omitted — omitting leaves the field unset which is equivalent but less explicit)

---

### Task 4: Update DESIGN.md and commit

**Files:**
- Modify: `docs/DESIGN.md`

The `SearchResource` description in DESIGN.md mentions post-retrieval domain filtering. Update it to reflect the pre-filter approach.

- [ ] **Step 1: Update the SearchResource description in DESIGN.md**

In `docs/DESIGN.md`, find the `SearchResource` entry under `## Key Abstractions` and update it:

Old:
```
**`SearchResource`** — `GET /search?q=&domain=&limit=`. Embeds query, searches `EmbeddingStore`, filters domain post-retrieval. Returns `List<SearchResult>`.
```

New:
```
**`SearchResource`** — `GET /search?q=&domain=&limit=`. Embeds query, builds `EmbeddingSearchRequest` with optional `IsEqualTo("domain", domain)` payload pre-filter, searches `EmbeddingStore`. Returns `List<SearchResult>`. Pre-filtering ensures `limit` is respected correctly regardless of domain distribution in the corpus.
```

- [ ] **Step 2: Commit**

```bash
git -C /Users/mdproctor/claude/hortora/garden-engine add \
  src/test/resources/fixtures/ge-test-qdrant-mcp-tool.md \
  src/test/java/io/hortora/garden/search/SearchResourceTest.java \
  src/main/java/io/hortora/garden/search/SearchResource.java \
  docs/DESIGN.md

git -C /Users/mdproctor/claude/hortora/garden-engine commit -m "perf: Qdrant payload pre-filter for domain — replaces post-retrieval Java filter  Closes #2"
```

- [ ] **Step 3: Push**

```bash
git -C /Users/mdproctor/claude/hortora/garden-engine push -u origin issue-2-qdrant-domain-payload-filter
```
