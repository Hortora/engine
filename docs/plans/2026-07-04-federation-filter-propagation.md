# Federation Filter Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use hortora:subagent-driven-development (recommended) or hortora:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Propagate `type` and `tags` filter parameters through the federation chain so federated queries apply the same constraints as local queries.

**Architecture:** Thread `type` and `tags` as discrete `String` parameters through `RemoteGardenClient` → `ChainWalker` → `SearchResource.doSearch()`. The remote `/search` endpoint already accepts these query parameters — only the internal plumbing is missing.

**Tech Stack:** Quarkus, JAX-RS REST client, WireMock (integration tests)

## Global Constraints

- Java 25
- All commits reference issue #30: `Refs #30` or `Closes #30`
- TDD: failing test first, then implementation
- The `RecordingClient` in `ChainWalkerTest` implements `RemoteGardenClient` — its signature must match

---

### Task 1: Thread type/tags through RemoteGardenClient and ChainWalker

**Files:**
- Modify: `src/main/java/io/hortora/garden/federation/RemoteGardenClient.java`
- Modify: `src/main/java/io/hortora/garden/federation/ChainWalker.java`
- Modify: `src/test/java/io/hortora/garden/federation/ChainWalkerTest.java`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: `ChainWalker.walk(String query, List<String> domains, String type, String tags, int limit, List<SearchResult> ownResults, Set<String> visited)` — callers in Task 2

- [ ] **Step 1: Write the failing test — verify type and tags are passed to upstream**

Add to `ChainWalkerTest.java`:

```java
@Test
void typeAndTagsPassedToUpstream() {
    configureChild();
    upstreamClient.response = List.of();

    var visited = new LinkedHashSet<>(Set.of("my-garden"));
    walker.walk("query", null, "gotcha", "qdrant,cdi", LIMIT,
            List.of(result("e1", LOW, "my-garden", "MG")), visited);

    assertThat(upstreamClient.lastType).isEqualTo("gotcha");
    assertThat(upstreamClient.lastTags).isEqualTo("qdrant,cdi");
}
```

This requires `RecordingClient` to capture `lastType` and `lastTags` fields — add them in Step 3.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=ChainWalkerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `walk()` signature mismatch and `RecordingClient` missing fields.

- [ ] **Step 3: Implement the changes**

**`RemoteGardenClient.java`** — add type and tags parameters:

```java
@GET
@Path("/search")
List<SearchResult> search(
        @QueryParam("q") String query,
        @QueryParam("domain") List<String> domains,
        @QueryParam("type") String type,
        @QueryParam("tags") String tags,
        @QueryParam("limit") int limit,
        @HeaderParam("X-Federation-Visited") String visited
);
```

**`ChainWalker.java`** — update `walk()` signature and all `client.search()` calls:

```java
public List<SearchResult> walk(String query, List<String> domains, String type, String tags,
                                int limit, List<SearchResult> ownResults, Set<String> visited) {
```

In the upstream loop, change:
```java
List<SearchResult> results = client.search(query, domains, limit, visitedHeader);
```
to:
```java
List<SearchResult> results = client.search(query, domains, type, tags, limit, visitedHeader);
```

In the peer fan-out lambda, change:
```java
return client.search(query, domains, limit, visitedHeader);
```
to:
```java
return client.search(query, domains, type, tags, limit, visitedHeader);
```

**`ChainWalkerTest.java`** — update `RecordingClient`:

```java
static class RecordingClient implements RemoteGardenClient {
    List<SearchResult> response = List.of();
    boolean shouldThrow = false;
    int callCount = 0;
    String lastVisited;
    String lastType;
    String lastTags;

    @Override
    public List<SearchResult> search(String query, List<String> domains, String type, String tags,
                                     int limit, String visited) {
        callCount++;
        lastVisited = visited;
        lastType = type;
        lastTags = tags;
        if (shouldThrow) {
            throw new RuntimeException("Connection timeout");
        }
        return response;
    }
}
```

Update all existing `walker.walk(...)` calls in test methods to include `null, null` for type and tags (position 3 and 4). For example:

```java
// Before:
walker.walk("query", null, LIMIT, own, visited());
// After:
walker.walk("query", null, null, null, LIMIT, own, visited());
```

Every test method that calls `walker.walk()` needs this update. There are 12 such calls across the test class.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=ChainWalkerTest`
Expected: all tests PASS including the new `typeAndTagsPassedToUpstream`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/hortora/garden/federation/RemoteGardenClient.java \
       src/main/java/io/hortora/garden/federation/ChainWalker.java \
       src/test/java/io/hortora/garden/federation/ChainWalkerTest.java
git commit -m "feat: thread type/tags through federation chain walker

RemoteGardenClient gains type and tags query parameters.
ChainWalker.walk() forwards them to all upstream and peer calls.

Refs #30"
```

---

### Task 2: Wire SearchResource call site and add integration test

**Files:**
- Modify: `src/main/java/io/hortora/garden/search/SearchResource.java` (line 94)
- Modify: `src/test/java/io/hortora/garden/federation/FederationIntegrationTest.java`

**Interfaces:**
- Consumes: `ChainWalker.walk(String query, List<String> domains, String type, String tags, int limit, List<SearchResult> ownResults, Set<String> visited)` from Task 1

- [ ] **Step 1: Write the failing integration test — verify type/tags are forwarded to upstream**

Add to `FederationIntegrationTest.java`:

```java
@Test
void typeAndTagsForwardedToUpstream() {
    wireMock.stubFor(get(urlPathEqualTo("/search"))
            .willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));

    given()
            .queryParam("q", "test query")
            .queryParam("type", "gotcha")
            .queryParam("tags", "hibernate,cdi")
    .when()
            .get("/search");

    wireMock.verify(getRequestedFor(urlPathEqualTo("/search"))
            .withQueryParam("type", equalTo("gotcha"))
            .withQueryParam("tags", equalTo("hibernate,cdi")));
}
```

Import `equalTo` from WireMock (already imported as `com.github.tomakehurst.wiremock.client.WireMock.equalTo` — check if the existing static import for `equalTo` from Hamcrest conflicts; if so, use `com.github.tomakehurst.wiremock.client.WireMock.equalTo` explicitly via `import static com.github.tomakehurst.wiremock.client.WireMock.equalTo as wmEqualTo` or use the fully qualified form in the verify call).

Actually, looking at the existing imports: `equalTo` is already imported from Hamcrest (`org.hamcrest.Matchers.equalTo`). WireMock's `equalTo` lives in `com.github.tomakehurst.wiremock.client.WireMock`. Use the WireMock static method directly:

```java
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
```

This will conflict with the Hamcrest import. Resolve by using WireMock's `WireMock.equalTo()` qualified in the verify call:

```java
wireMock.verify(getRequestedFor(urlPathEqualTo("/search"))
        .withQueryParam("type", com.github.tomakehurst.wiremock.client.WireMock.equalTo("gotcha"))
        .withQueryParam("tags", com.github.tomakehurst.wiremock.client.WireMock.equalTo("hibernate,cdi")));
```

Or rename the Hamcrest import. Simplest: use fully qualified WireMock calls in this test method.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=FederationIntegrationTest#typeAndTagsForwardedToUpstream`
Expected: FAIL — WireMock verification failure because type/tags query params are not present in the upstream request (SearchResource line 94 doesn't pass them yet).

- [ ] **Step 3: Fix SearchResource call site**

In `SearchResource.java`, line 94, change:
```java
return chainWalker.walk(query, domains, maxResults, ownResults, visited);
```
to:
```java
return chainWalker.walk(query, domains, type, tags, maxResults, ownResults, visited);
```

This is the only production code change in this task — one line.

- [ ] **Step 4: Run all tests to verify everything passes**

Run: `./mvnw test -pl .`
Expected: all tests PASS — ChainWalkerTest (from Task 1), FederationIntegrationTest (including new test), SearchResourceTest (unchanged, still passing).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/hortora/garden/search/SearchResource.java \
       src/test/java/io/hortora/garden/federation/FederationIntegrationTest.java
git commit -m "feat: wire type/tags from SearchResource to federation chain

SearchResource.doSearch() now passes type and tags to
chainWalker.walk(), completing the filter propagation.

Integration test verifies upstream receives the query parameters.

Closes #30"
```
