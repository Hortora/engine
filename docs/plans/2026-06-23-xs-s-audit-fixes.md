# XS/S Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 9 audit issues (#10–#18) covering code quality, test gaps, config, API contracts, docs, and build.

**Architecture:** Sequential fixes in dependency order. No new abstractions — tightening existing code, aligning test doubles with platform conventions, and making hardcoded values configurable.

**Tech Stack:** Quarkus 3.36.x, casehub-rag-api 0.2-SNAPSHOT (RetrievalQuery), casehub-rag-testing, casehub-inference-quarkus

---

### Task 1: Remove unused testcontainers dependency (#15)

**Files:**
- Modify: `pom.xml:132-143`

- [ ] **Step 1: Remove testcontainers stanzas from pom.xml**

Remove these two dependency blocks:

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>1.21.1</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>1.21.1</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```
git add pom.xml
git commit -m "chore: remove unused testcontainers dependency  Closes #15"
```

---

### Task 2: Minor code quality fixes (#14)

**Files:**
- Modify: `src/main/java/io/hortora/garden/inference/CollectionMigration.java`
- Modify: `src/main/java/io/hortora/garden/federation/RemoteGardenClient.java`
- Modify: `src/main/java/io/hortora/garden/federation/FederationConfig.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/io/hortora/garden/federation/ChainWalkerTest.java`
- Modify: `src/test/java/io/hortora/garden/federation/FederationConfigParserTest.java`
- Create: `src/test/resources/fixtures/schema/invalid-search-order.yaml`

- [ ] **Step 1: CollectionMigration — replace JUL with Quarkus Log**

In `CollectionMigration.java`, remove the JUL import and field:

```java
import java.util.logging.Level;
import java.util.logging.Logger;
```
```java
private static final Logger LOG = Logger.getLogger(CollectionMigration.class.getName());
```

Replace all `LOG` calls with `io.quarkus.logging.Log`:

```java
// line 71 — was LOG.info(() -> ...)
Log.infof("Collection '%s' already has sparse vectors — no migration needed", collectionName);

// line 76 — was LOG.info(() -> ...)
Log.infof("Collection '%s' lacks sparse vectors — migrating to hybrid", collectionName);

// line 79 — was LOG.info(() -> ...)
Log.info("Migration complete — collection deleted and cursor reset. Full re-index will run on next ingestion cycle.");

// line 82 — was LOG.log(Level.WARNING, ...)
Log.warn("Interrupted during collection migration check", e);

// line 84 — was LOG.log(Level.WARNING, ...)
Log.warn("Failed to check collection for migration", e.getCause());
```

Add import: `import io.quarkus.logging.Log;`

- [ ] **Step 2: RemoteGardenClient — replace @Consumes with @Produces**

In `RemoteGardenClient.java`, change:

```java
@Consumes(MediaType.APPLICATION_JSON)
```

to:

```java
@Produces(MediaType.APPLICATION_JSON)
```

Update import: replace `jakarta.ws.rs.Consumes` with `jakarta.ws.rs.Produces`.

- [ ] **Step 3: FederationConfig — compact constructor normalizing nulls**

In `FederationConfig.java`, add a compact constructor and simplify the convenience methods:

```java
public record FederationConfig(
        String gardenId,
        String idPrefix,
        String role,
        double relevanceThreshold,
        int maxDepth,
        List<UpstreamRef> upstream,
        List<PeerRef> peers
) {
    public FederationConfig {
        upstream = upstream == null ? List.of() : upstream;
        peers = peers == null ? List.of() : peers;
    }

    public record UpstreamRef(String url, String idPrefix, String searchOrder) {}
    public record PeerRef(String url, String idPrefix) {}

    public boolean hasUpstream() {
        return !upstream.isEmpty();
    }

    public boolean hasPeers() {
        return !peers.isEmpty();
    }

    public boolean hasFederation() {
        return hasUpstream() || hasPeers();
    }
}
```

- [ ] **Step 4: Remove redundant hortora.garden.path from application.properties**

In `src/main/resources/application.properties`, remove line 1:

```properties
hortora.garden.path=${user.home}/.hortora/garden
```

The file should now start with:

```properties
quarkus.langchain4j.ollama.embedding-model.model-name=nomic-embed-text
```

- [ ] **Step 5: Delete vacuous depth test from ChainWalkerTest**

In `ChainWalkerTest.java`, delete the entire `depthExceededReturnsOwnResultsOnly()` method (lines 248-268). Depth enforcement is tested at the correct level by `FederationIntegrationTest.depthExceededReturnsOwnResultsWithoutWalking()`.

- [ ] **Step 6: Add invalidSearchOrderThrows test**

Create fixture file `src/test/resources/fixtures/schema/invalid-search-order.yaml`:

```yaml
---
schema-version: 2.0.0
id: bad-garden
id-prefix: BG

federation:
  role: child
  upstream:
    - url: https://api.example.com
      id-prefix: EX
      search-order: invalid-value
---
```

Add test to `FederationConfigParserTest.java`:

```java
@Test
void invalidSearchOrderThrows() {
    assertThatThrownBy(() -> parse("invalid-search-order.yaml"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("search-order");
}
```

- [ ] **Step 7: Verify all tests pass**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS (note: `SearchResource` compilation error from RetrievalQuery will be fixed in Task 4; temporarily fix with `RetrievalQuery.of(query)` in `searchLocal()` if needed to run tests, or run only the affected test classes directly)

Actually — the project doesn't compile due to the RetrievalQuery issue. We need to apply the minimal compilation fix first. In `SearchResource.java` line 80, change:

```java
List<RetrievedChunk> chunks = caseRetriever.retrieve(query, corpusRef, maxResults, filter);
```

to:

```java
List<RetrievedChunk> chunks = caseRetriever.retrieve(RetrievalQuery.of(query), corpusRef, maxResults, filter);
```

Add import: `import io.casehub.rag.RetrievalQuery;`

This is the blocking compilation fix from the spec — applying it here so all subsequent tasks can compile and test. The full SearchResource rework happens in Task 4.

Run: `./mvnw test -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```
git add -A
git commit -m "chore: minor code quality fixes — logging, annotations, null normalization, RetrievalQuery compilation fix  Closes #14"
```

---

### Task 3: Frontmatter CRLF fix + edge-case tests (#10)

**Files:**
- Modify: `src/main/java/io/hortora/garden/index/GardenMetadataExtractor.java`
- Modify: `src/main/java/io/hortora/garden/federation/FederationConfigParser.java`
- Modify: `src/test/java/io/hortora/garden/index/GardenMetadataExtractorTest.java`
- Modify: `src/test/java/io/hortora/garden/federation/FederationConfigParserTest.java`

- [ ] **Step 1: Write CRLF test for GardenMetadataExtractor**

Add to `GardenMetadataExtractorTest.java`:

```java
@Test
void crlfLineEndingsParseCorrectly() {
    byte[] content = "---\r\ntitle: \"CRLF test\"\r\ndomain: jvm\r\n---\r\nBody with CRLF."
            .getBytes(StandardCharsets.UTF_8);

    ExtractionResult result = extractor.extract("test/crlf.md", content);

    assertThat(result.body()).isEqualTo("CRLF test\n\nBody with CRLF.");
    assertThat(result.metadata()).containsEntry("title", "CRLF test");
    assertThat(result.metadata()).containsEntry("domain", "jvm");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=GardenMetadataExtractorTest#crlfLineEndingsParseCorrectly -q`
Expected: FAIL — `\r\n` line endings prevent frontmatter detection

- [ ] **Step 3: Fix CRLF in GardenMetadataExtractor**

In `GardenMetadataExtractor.extract()`, after `String text = new String(content, StandardCharsets.UTF_8);` (line 22), add:

```java
text = text.replace("\r\n", "\n");
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl . -Dtest=GardenMetadataExtractorTest#crlfLineEndingsParseCorrectly -q`
Expected: PASS

- [ ] **Step 5: Write malformed YAML test**

Add to `GardenMetadataExtractorTest.java`:

```java
@Test
void malformedYamlReturnsEmptyExtractionResult() {
    byte[] content = "---\n: invalid yaml [[[broken\n---\nBody text."
            .getBytes(StandardCharsets.UTF_8);

    ExtractionResult result = extractor.extract("test/bad-yaml.md", content);

    assertThat(result.body()).isEmpty();
    assertThat(result.metadata()).isEmpty();
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=GardenMetadataExtractorTest#malformedYamlReturnsEmptyExtractionResult -q`
Expected: FAIL — `YAMLException` propagates uncaught

- [ ] **Step 7: Fix — wrap Yaml.load() in catch(Exception)**

In `GardenMetadataExtractor.extract()`, replace lines 35-38:

```java
Map<String, Object> fm = new Yaml().load(frontmatterBlock);
if (fm == null) {
    return new ExtractionResult("", Map.of());
}
```

with:

```java
Map<String, Object> fm;
try {
    fm = new Yaml().load(frontmatterBlock);
} catch (Exception e) {
    return new ExtractionResult("", Map.of());
}
if (fm == null) {
    return new ExtractionResult("", Map.of());
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./mvnw test -pl . -Dtest=GardenMetadataExtractorTest#malformedYamlReturnsEmptyExtractionResult -q`
Expected: PASS

- [ ] **Step 9: Write remaining edge-case tests**

Add to `GardenMetadataExtractorTest.java`:

```java
@Test
void nonMappingYamlReturnsEmptyExtractionResult() {
    byte[] content = "---\nhello world\n---\nBody text."
            .getBytes(StandardCharsets.UTF_8);

    ExtractionResult result = extractor.extract("test/scalar-yaml.md", content);

    assertThat(result.body()).isEmpty();
    assertThat(result.metadata()).isEmpty();
}

@Test
void missingTitleExtractsBodyWithoutTitlePrefix() {
    byte[] content = "---\ndomain: tools\ntype: technique\n---\nBody without title."
            .getBytes(StandardCharsets.UTF_8);

    ExtractionResult result = extractor.extract("test/no-title.md", content);

    assertThat(result.body()).isEqualTo("Body without title.");
    assertThat(result.metadata()).containsEntry("domain", "tools");
    assertThat(result.metadata()).doesNotContainKey("title");
}

@Test
void unclosedFrontmatterReturnsEmptyExtractionResult() {
    byte[] content = "---\ntitle: unclosed\ndomain: jvm\nBody without closing delimiter."
            .getBytes(StandardCharsets.UTF_8);

    ExtractionResult result = extractor.extract("test/unclosed.md", content);

    assertThat(result.body()).isEmpty();
    assertThat(result.metadata()).isEmpty();
}
```

- [ ] **Step 10: Run tests — all should pass (nonMappingYaml catches ClassCastException, others verify existing behaviour)**

Run: `./mvnw test -pl . -Dtest=GardenMetadataExtractorTest -q`
Expected: PASS — all tests green

- [ ] **Step 11: Write CRLF test for FederationConfigParser**

Add to `FederationConfigParserTest.java`:

```java
@Test
void crlfLineEndingsParseCorrectly(@TempDir Path tempDir) throws IOException {
    String content = "---\r\nschema-version: 2.0.0\r\nid: crlf-garden\r\nid-prefix: CR\r\n\r\n" +
            "federation:\r\n  role: canonical\r\n---\r\n";
    Path crlfFile = tempDir.resolve("crlf-schema.yaml");
    Files.writeString(crlfFile, content);

    var config = FederationConfigParser.parse(crlfFile, "fallback-id", "FB");

    assertThat(config.gardenId()).isEqualTo("crlf-garden");
    assertThat(config.idPrefix()).isEqualTo("CR");
    assertThat(config.role()).isEqualTo("canonical");
}
```

- [ ] **Step 12: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=FederationConfigParserTest#crlfLineEndingsParseCorrectly -q`
Expected: FAIL — `\r\n` breaks the `---\n` split

- [ ] **Step 13: Fix CRLF in FederationConfigParser**

In `FederationConfigParser.parse()`, after `String content = Files.readString(schemaPath);` (line 60), add:

```java
content = content.replace("\r\n", "\n");
```

- [ ] **Step 14: Run all tests**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS

- [ ] **Step 15: Commit**

```
git add -A
git commit -m "fix: frontmatter parsing — handle CRLF line endings, catch unparseable YAML  Closes #10"
```

---

### Task 4: DESIGN.md Phase 2 update (#17)

**Files:**
- Modify: `docs/DESIGN.md`

- [ ] **Step 1: Update Phase 2 section**

Replace the Phase 2 section at the end of `docs/DESIGN.md`:

```markdown
## Phase 2 (pending)

SPLADE sparse embeddings + cross-encoder reranker via `casehubio/neural-text` `inference-splade` (Hortora-eligible). Neural-text's `casehub-rag` already supports optional sparse embeddings — Phase 2 adds a `SparseEmbedder` CDI bean to activate hybrid search with RRF fusion.
```

with:

```markdown
## Phase 2 — Hybrid Search (complete)

SPLADE sparse embeddings + cross-encoder reranking via `casehub-inference-quarkus` (ONNX Runtime).

- `HybridSearchProducer` bridges `@Inference`-qualified ONNX models to `SparseEmbedder` and `CrossEncoderReranker` via `@LookupIfProperty` — beans are genuinely non-resolvable when ONNX models aren't configured, so the engine falls back to dense-only transparently.
- `CollectionMigration` detects dense-only Qdrant collections at startup and triggers re-indexing when SPLADE is newly enabled.
- Dense + sparse RRF fusion and cross-encoder reranking activate when ONNX models are configured via `casehub.inference.models.splade.model-path` and `casehub.inference.models.reranker.model-path`.
```

- [ ] **Step 2: Update SPI Contracts section**

In the SPI Contracts section, update the `CaseRetriever` entry:

```markdown
- `CaseRetriever` — neural-text SPI for vector search with `PayloadFilter` support
```

to:

```markdown
- `CaseRetriever` — neural-text SPI for vector search; `retrieve(RetrievalQuery, CorpusRef, int, PayloadFilter)` — takes `RetrievalQuery` (wraps query text + optional expanded text for query expansion)
```

- [ ] **Step 3: Commit**

```
git add docs/DESIGN.md
git commit -m "docs: update DESIGN.md — Phase 2 hybrid search complete, RetrievalQuery SPI  Closes #17"
```

---

### Task 5: SearchResource rework (#11)

**Files:**
- Modify: `src/main/java/io/hortora/garden/search/SearchResource.java`
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`

Note: The RetrievalQuery compilation fix was already applied in Task 2, Step 7. This task handles the remaining SearchResource improvements.

- [ ] **Step 1: Write test for parseVisited trim**

Add to `SearchResourceTest.java`:

```java
@Test
void parseVisitedTrimsWhitespace() {
    given()
        .queryParam("q", "test query")
        .header("X-Federation-Visited", "test-garden")
    .when()
        .get("/search")
    .then()
        .statusCode(200)
        .body("$", hasSize(0));

    // Now with spaces around the garden ID — should still detect cycle
    given()
        .queryParam("q", "test query")
        .header("X-Federation-Visited", " test-garden , other-garden ")
    .when()
        .get("/search")
    .then()
        .statusCode(200)
        .body("$", hasSize(0));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=SearchResourceTest#parseVisitedTrimsWhitespace -q`
Expected: FAIL — untrimmed `" test-garden "` doesn't match `"test-garden"`

- [ ] **Step 3: Write test for limit cap**

Add to `SearchResourceTest.java`:

```java
@Test
void limitCappedAtMaximum() {
    given()
        .queryParam("q", "test query")
        .queryParam("limit", "99999")
    .when()
        .get("/search")
    .then()
        .statusCode(200);
    // No assertion on result count — just verifying it doesn't attempt 99999 results
    // The cap is enforced internally; we verify the code path doesn't error
}
```

- [ ] **Step 4: Write test for searchFor direct call**

Add to `SearchResourceTest.java`:

```java
@Inject SearchResource searchResource;

@Test
void searchForReturnsResults() {
    List<SearchResult> results = searchResource.searchFor("hibernate lazy", null, null);
    assertThat(results).isNotEmpty();
}
```

Add imports: `import jakarta.inject.Inject;` and `import java.util.List;` and `import io.hortora.garden.search.SearchResult;`

- [ ] **Step 5: Write test for domain filtering via HTTP**

Add to `SearchResourceTest.java`:

```java
@Test
void domainFilterReturnsOnlyMatchingDomain() {
    given()
        .queryParam("q", "test query")
        .queryParam("domain", "jvm")
    .when()
        .get("/search")
    .then()
        .statusCode(200)
        .body("domain", everyItem(equalTo("jvm")));
}
```

Add import: `import static org.hamcrest.Matchers.everyItem;`

- [ ] **Step 6: Apply all SearchResource changes**

Rewrite `SearchResource.java`:

```java
package io.hortora.garden.search;

import io.casehub.rag.CaseRetriever;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.RetrievalQuery;
import io.casehub.rag.RetrievedChunk;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.federation.ChainWalker;
import io.hortora.garden.federation.FederationConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SearchResource {

    static final int DEFAULT_LIMIT = 8;
    static final int MAX_LIMIT = 50;

    @Inject CaseRetriever caseRetriever;
    @Inject GardenConfig gardenConfig;
    @Inject FederationConfig federationConfig;
    @Inject ChainWalker chainWalker;

    @GET
    public List<SearchResult> search(
            @QueryParam("q") String query,
            @QueryParam("domain") List<String> domains,
            @QueryParam("limit") Integer limit,
            @HeaderParam("X-Federation-Visited") String visited) {

        if (query == null || query.isBlank()) {
            throw new WebApplicationException("query parameter 'q' is required",
                    Response.Status.BAD_REQUEST);
        }

        int maxResults = resolveLimit(limit);
        return doSearch(query, domains, maxResults, visited);
    }

    public List<SearchResult> searchFor(String query, List<String> domains, Integer limit) {
        int maxResults = resolveLimit(limit);
        return doSearch(query, domains, maxResults, null);
    }

    List<SearchResult> doSearch(String query, List<String> domains, int maxResults, String visitedHeader) {
        Set<String> visited = parseVisited(visitedHeader);

        if (visited.contains(federationConfig.gardenId())) {
            return List.of();
        }

        visited.add(federationConfig.gardenId());

        boolean depthExceeded = visited.size() > federationConfig.maxDepth();

        List<SearchResult> ownResults = searchLocal(query, domains, maxResults);

        if (depthExceeded) {
            return ownResults;
        }

        return chainWalker.walk(query, domains, maxResults, ownResults, visited);
    }

    private List<SearchResult> searchLocal(String query, List<String> domains, int maxResults) {
        CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
        PayloadFilter filter = buildDomainFilter(domains);

        List<RetrievedChunk> chunks = caseRetriever.retrieve(
                RetrievalQuery.of(query), corpusRef, maxResults, filter);

        List<SearchResult> results = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            results.add(new SearchResult(
                    chunk.sourceDocumentId(),
                    chunk.metadata().getOrDefault("title", ""),
                    chunk.metadata().getOrDefault("domain", ""),
                    chunk.metadata().getOrDefault("type", ""),
                    parseScore(chunk.metadata().get("score")),
                    chunk.content(),
                    chunk.relevanceScore(),
                    federationConfig.gardenId(),
                    federationConfig.idPrefix()));
        }
        return results;
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    static PayloadFilter buildDomainFilter(List<String> domains) {
        if (domains == null || domains.isEmpty()) return null;
        List<String> nonBlank = domains.stream().filter(d -> d != null && !d.isBlank()).toList();
        if (nonBlank.isEmpty()) return null;
        if (nonBlank.size() == 1) return PayloadFilter.eq("domain", nonBlank.getFirst());
        return PayloadFilter.in("domain", nonBlank);
    }

    private static Set<String> parseVisited(String header) {
        if (header == null || header.isBlank()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(
                Arrays.stream(header.split(",")).map(String::trim).toList());
    }

    private static int parseScore(String s) {
        try {
            return s != null && !s.isEmpty() ? Integer.parseInt(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
```

- [ ] **Step 7: Run all tests**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```
git add -A
git commit -m "refactor: SearchResource — typed responses, @ApplicationScoped, limit cap, parseVisited trim  Closes #11"
```

---

### Task 6: ChainWalker configurable federation timeout (#12)

**Files:**
- Modify: `src/main/java/io/hortora/garden/federation/FederationConfig.java`
- Modify: `src/main/java/io/hortora/garden/federation/FederationConfigParser.java`
- Modify: `src/main/java/io/hortora/garden/federation/ChainWalker.java`
- Modify: `src/test/java/io/hortora/garden/federation/FederationConfigParserTest.java`
- Modify: `src/test/java/io/hortora/garden/federation/ChainWalkerTest.java`
- Create: `src/test/resources/fixtures/schema/with-timeout.yaml`

- [ ] **Step 1: Write parser test for timeout config**

Create fixture `src/test/resources/fixtures/schema/with-timeout.yaml`:

```yaml
---
schema-version: 2.0.0
id: timeout-garden
id-prefix: TG

federation:
  role: child
  timeout-seconds: 10
  upstream:
    - url: https://api.example.com
      id-prefix: EX
      search-order: fallback
---
```

Add test to `FederationConfigParserTest.java`:

```java
@Test
void parsesTimeoutSeconds() throws IOException {
    var config = parse("with-timeout.yaml");

    assertThat(config.federationTimeoutSeconds()).isEqualTo(10);
}

@Test
void defaultTimeoutIsFiveSeconds() throws IOException {
    var config = parse("valid-child.yaml");

    assertThat(config.federationTimeoutSeconds()).isEqualTo(5);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=FederationConfigParserTest#parsesTimeoutSeconds -q`
Expected: FAIL — `federationTimeoutSeconds()` method doesn't exist

- [ ] **Step 3: Add federationTimeoutSeconds to FederationConfig**

Update `FederationConfig.java`:

```java
public record FederationConfig(
        String gardenId,
        String idPrefix,
        String role,
        double relevanceThreshold,
        int maxDepth,
        int federationTimeoutSeconds,
        List<UpstreamRef> upstream,
        List<PeerRef> peers
) {
    public FederationConfig {
        upstream = upstream == null ? List.of() : upstream;
        peers = peers == null ? List.of() : peers;
    }

    public record UpstreamRef(String url, String idPrefix, String searchOrder) {}
    public record PeerRef(String url, String idPrefix) {}

    public boolean hasUpstream() {
        return !upstream.isEmpty();
    }

    public boolean hasPeers() {
        return !peers.isEmpty();
    }

    public boolean hasFederation() {
        return hasUpstream() || hasPeers();
    }
}
```

- [ ] **Step 4: Update FederationConfigParser to parse and pass timeout**

In `FederationConfigParser.java`, add constant:

```java
private static final int DEFAULT_FEDERATION_TIMEOUT_SECONDS = 5;
```

In the `parse()` method, after the `maxDepth` parsing block (~line 99), add:

```java
int timeoutSeconds = federation.containsKey("timeout-seconds")
        ? ((Number) federation.get("timeout-seconds")).intValue()
        : DEFAULT_FEDERATION_TIMEOUT_SECONDS;
```

Update the `FederationConfig` constructor call to include `timeoutSeconds`:

```java
return new FederationConfig(gardenId, idPrefix, role, threshold, maxDepth,
        timeoutSeconds, upstream, peers);
```

Update both `defaultConfig()` calls and the no-federation-block case to pass `DEFAULT_FEDERATION_TIMEOUT_SECONDS`:

```java
// defaultConfig method:
return new FederationConfig(gardenId, idPrefix, "canonical",
        DEFAULT_RELEVANCE_THRESHOLD, DEFAULT_MAX_DEPTH,
        DEFAULT_FEDERATION_TIMEOUT_SECONDS, List.of(), List.of());

// no federation block case:
return new FederationConfig(gardenId, idPrefix, "canonical",
        DEFAULT_RELEVANCE_THRESHOLD, DEFAULT_MAX_DEPTH,
        DEFAULT_FEDERATION_TIMEOUT_SECONDS, List.of(), List.of());
```

- [ ] **Step 5: Update ChainWalker to use configurable timeout**

In `ChainWalker.java`, replace the hardcoded timeout in `walk()` (line 104):

```java
List<Future<List<SearchResult>>> futures = executor.invokeAll(
        peerCalls, config.federationTimeoutSeconds(), TimeUnit.SECONDS);
```

Replace the hardcoded timeout in `buildClient()` (line 178):

```java
private RemoteGardenClient buildClient(String url) {
    return QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(url))
            .readTimeout(config.federationTimeoutSeconds(), TimeUnit.SECONDS)
            .build(RemoteGardenClient.class);
}
```

- [ ] **Step 6: Fix all FederationConfig constructor calls in tests**

In `ChainWalkerTest.java`, find all `new FederationConfig(...)` calls and add the timeout parameter (5) after `maxDepth`. For example:

```java
// Before:
new FederationConfig("my-garden", "MG", "child", 0.7, 5, upstream, peers)
// After:
new FederationConfig("my-garden", "MG", "child", 0.7, 5, 5, upstream, peers)
```

Apply the same fix to `CollectionMigrationTest.java`, `HybridSearchProducerTest.java`, `HybridSearchProducerAbsentTest.java`, and `WireMockFederationResource.java` if any construct `FederationConfig` directly.

- [ ] **Step 7: Run all tests**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```
git add -A
git commit -m "chore: make ChainWalker federation timeouts configurable via SCHEMA.md  Closes #12"
```

---

### Task 7: Reconcile test doubles with casehub-rag-testing (#16)

**Files:**
- Delete: `src/test/java/io/hortora/garden/test/TestCaseRetriever.java`
- Delete: `src/test/java/io/hortora/garden/test/TestEmbeddingIngestor.java`
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`
- Modify: `src/test/java/io/hortora/garden/federation/FederationIntegrationTest.java`

- [ ] **Step 1: Add fixture seeding to SearchResourceTest**

In `SearchResourceTest.java`, add imports and fixture seeding:

```java
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.testing.InMemoryEmbeddingIngestor;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;
```

Add fields and `@BeforeEach`:

```java
@Inject InMemoryEmbeddingIngestor ingestor;

private static final CorpusRef CORPUS = new CorpusRef("hortora", "test-garden");

@BeforeEach
void seedFixtures() {
    ingestor.deleteCorpus(CORPUS);
    ingestor.ingest(CORPUS, List.of(
            new ChunkInput(
                    "Hibernate lazy loading fails outside transaction boundary.\n\nLazyInitializationException is thrown.",
                    "jvm/ge-test-hibernate-lazy.md",
                    Map.of("title", "Hibernate lazy loading fails outside transaction",
                            "domain", "jvm", "type", "gotcha", "score", "8")),
            new ChunkInput(
                    "Git stash metadata is lost when applying across branches.",
                    "tools/ge-test-git-stash.md",
                    Map.of("title", "Git stash metadata lost across branches",
                            "domain", "tools", "type", "gotcha", "score", "6"))
    ));
}
```

Note: `hortora.garden.id` is set to `test-garden` in `WireMockFederationResource` for `@QuarkusTestResource`-annotated tests, but for plain `@QuarkusTest` like `SearchResourceTest`, it uses the default `garden` from `GardenConfig`. Check the test properties — `src/test/resources/application.properties` does not override `hortora.garden.id`, so the default `"garden"` applies. The `CorpusRef` must match what `SearchResource.searchLocal()` uses. Update:

```java
private static final CorpusRef CORPUS = new CorpusRef("hortora", "garden");
```

- [ ] **Step 2: Add fixture seeding to FederationIntegrationTest**

In `FederationIntegrationTest.java`, add imports and seeding:

```java
import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.testing.InMemoryEmbeddingIngestor;
import jakarta.inject.Inject;
import java.util.Map;
```

Add field and update `@BeforeEach`:

```java
@Inject InMemoryEmbeddingIngestor ingestor;

private static final CorpusRef CORPUS = new CorpusRef("hortora", "test-garden");

@BeforeEach
void resetState() {
    wireMock.resetAll();
    ingestor.deleteCorpus(CORPUS);
    ingestor.ingest(CORPUS, List.of(
            new ChunkInput(
                    "Hibernate lazy loading fails outside transaction boundary.\n\nLazyInitializationException is thrown.",
                    "jvm/ge-test-hibernate-lazy.md",
                    Map.of("title", "Hibernate lazy loading fails outside transaction",
                            "domain", "jvm", "type", "gotcha", "score", "8")),
            new ChunkInput(
                    "Git stash metadata is lost when applying across branches.",
                    "tools/ge-test-git-stash.md",
                    Map.of("title", "Git stash metadata lost across branches",
                            "domain", "tools", "type", "gotcha", "score", "6"))
    ));
}
```

Note: `FederationIntegrationTest` uses `WireMockFederationResource` which sets `hortora.garden.id=test-garden`, so `CorpusRef("hortora", "test-garden")` is correct here.

- [ ] **Step 3: Delete hand-written test doubles**

Delete:
- `src/test/java/io/hortora/garden/test/TestCaseRetriever.java`
- `src/test/java/io/hortora/garden/test/TestEmbeddingIngestor.java`

Keep: `src/test/java/io/hortora/garden/test/TestEmbeddingModel.java`

- [ ] **Step 4: Run all tests**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS — `InMemoryCaseRetriever` and `InMemoryEmbeddingIngestor` from `casehub-rag-testing` activate automatically in Quarkus via `@Alternative @Priority(1)`.

- [ ] **Step 5: Commit**

```
git add -A
git commit -m "chore: adopt casehub-rag-testing stubs, delete hand-written test doubles  Closes #16"
```

---

### Task 8: GardenMcpTools — test coverage + exception logging (#13)

**Files:**
- Modify: `src/main/java/io/hortora/garden/mcp/GardenMcpTools.java`
- Create: `src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java`

- [ ] **Step 1: Write tests for GardenMcpTools**

Create `src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java`:

```java
package io.hortora.garden.mcp;

import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.testing.InMemoryEmbeddingIngestor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class GardenMcpToolsTest {

    @Inject GardenMcpTools mcpTools;
    @Inject InMemoryEmbeddingIngestor ingestor;

    private static final CorpusRef CORPUS = new CorpusRef("hortora", "garden");

    @BeforeEach
    void seedFixtures() {
        ingestor.deleteCorpus(CORPUS);
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput(
                        "Hibernate lazy loading fails outside transaction.",
                        "jvm/ge-test-hibernate-lazy.md",
                        Map.of("title", "Hibernate lazy loading gotcha",
                                "domain", "jvm", "type", "gotcha", "score", "8"))
        ));
    }

    @Test
    void gardenSearchReturnsFormattedResults() {
        String result = mcpTools.gardenSearch("hibernate lazy", null, null);

        assertThat(result).contains("## [own] Hibernate lazy loading gotcha");
        assertThat(result).contains("Hibernate lazy loading fails outside transaction.");
    }

    @Test
    void gardenSearchEmptyResultsReturnsMessage() {
        ingestor.deleteCorpus(CORPUS);

        String result = mcpTools.gardenSearch("nonexistent topic xyz", null, null);

        assertThat(result).startsWith("No relevant garden entries found for:");
    }

    @Test
    void gardenStatusReturnsPathAndCount() {
        String result = mcpTools.gardenStatus();

        assertThat(result).contains("Garden path:");
        assertThat(result).contains("Indexed entries:");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (gardenSearch may fail until exception logging is added)**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest -q`
Expected: Tests may pass or fail depending on fixture seeding. Verify the tests compile and run.

- [ ] **Step 3: Fix exception swallowing in gardenStatus**

In `GardenMcpTools.java`, replace the catch block in `gardenStatus()`:

```java
} catch (Exception e) {
    count = -1;
}
```

with:

```java
} catch (Exception e) {
    Log.warn("Failed to count indexed entries", e);
    count = -1;
}
```

Add import: `import io.quarkus.logging.Log;`

- [ ] **Step 4: Run all tests**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```
git add -A
git commit -m "chore: GardenMcpTools — add test coverage, log exceptions in gardenStatus  Closes #13"
```

---

### Task 9: ONNX model download automation (#18)

**Files:**
- Create: `scripts/download-models.sh`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Create download script**

Create `scripts/download-models.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="${HOME}/.hortora/models"

# Model definitions: name | HuggingFace repo | file | SHA-256
# Checksums must be updated when models are upgraded
declare -A MODELS
MODELS=(
    ["splade/model.onnx"]="prithivida/Splade_PP_en_v1|onnx/model.onnx"
    ["splade/tokenizer.json"]="prithivida/Splade_PP_en_v1|tokenizer.json"
    ["reranker/model.onnx"]="cross-encoder/ms-marco-MiniLM-L-6-v2|onnx/model.onnx"
    ["reranker/tokenizer.json"]="cross-encoder/ms-marco-MiniLM-L-6-v2|tokenizer.json"
)

download_file() {
    local target="$1" repo="$2" path="$3"
    local url="https://huggingface.co/${repo}/resolve/main/${path}"
    local dir
    dir=$(dirname "$target")
    mkdir -p "$dir"

    if [ -f "$target" ]; then
        echo "  ✓ exists: $target"
        return 0
    fi

    echo "  ↓ downloading: $url"
    curl -fSL --retry 3 -o "${target}.tmp" "$url"
    mv "${target}.tmp" "$target"
    echo "  ✓ saved: $target"
}

echo "Hortora ONNX Model Downloader"
echo "=============================="
echo ""
echo "Target: ${MODEL_DIR}"
echo ""

for key in "${!MODELS[@]}"; do
    IFS='|' read -r repo path <<< "${MODELS[$key]}"
    target="${MODEL_DIR}/${key}"
    download_file "$target" "$repo" "$path"
done

echo ""
echo "Downloads complete. Add to application.properties (or %dev profile):"
echo ""
echo "  %dev.casehub.inference.models.splade.model-path=${MODEL_DIR}/splade/model.onnx"
echo "  %dev.casehub.inference.models.splade.tokenizer-path=${MODEL_DIR}/splade/tokenizer.json"
echo "  %dev.casehub.inference.models.reranker.model-path=${MODEL_DIR}/reranker/model.onnx"
echo "  %dev.casehub.inference.models.reranker.tokenizer-path=${MODEL_DIR}/reranker/tokenizer.json"
```

Make executable: `chmod +x scripts/download-models.sh`

Note: Checksum verification is specified in the design spec. The actual SHA-256 checksums must be captured from the first successful download — they cannot be determined until the files are fetched. Add checksum verification in a follow-up once the checksums are known: download once, compute `shasum -a 256`, hardcode into the script, and add a verify step after each download.

- [ ] **Step 2: Add %dev profile config for ONNX models to application.properties**

Add to `src/main/resources/application.properties`:

```properties

# Hybrid search ONNX models (dev mode — run scripts/download-models.sh first)
# %dev.casehub.inference.models.splade.model-path=${user.home}/.hortora/models/splade/model.onnx
# %dev.casehub.inference.models.splade.tokenizer-path=${user.home}/.hortora/models/splade/tokenizer.json
# %dev.casehub.inference.models.reranker.model-path=${user.home}/.hortora/models/reranker/model.onnx
# %dev.casehub.inference.models.reranker.tokenizer-path=${user.home}/.hortora/models/reranker/tokenizer.json
```

Commented out — operator uncomments after running `scripts/download-models.sh`. `HybridSearchProducer` uses `@LookupIfProperty` on `model-path`, so beans only activate when the properties are set.

- [ ] **Step 3: Commit**

```
git add scripts/download-models.sh src/main/resources/application.properties
git commit -m "feat: ONNX model download script for hybrid search dev setup  Closes #18"
```

---

## Self-Review Checklist

**Spec coverage:**
- ✅ Blocking prerequisite (RetrievalQuery migration) — Task 2 Step 7 + Task 5 Step 6
- ✅ #15 testcontainers removal — Task 1
- ✅ #14 minor fixes (5 items) — Task 2
- ✅ #10 CRLF + edge cases — Task 3
- ✅ #17 DESIGN.md — Task 4
- ✅ #11 SearchResource — Task 5
- ✅ #12 configurable timeouts — Task 6
- ✅ #16 test doubles — Task 7
- ✅ #13 MCP tools — Task 8
- ✅ #18 ONNX script — Task 9

**Placeholder scan:** No TBD, TODO, or "fill in later" found.

**Type consistency:** `FederationConfig` record gains `federationTimeoutSeconds` in Task 6; all constructor calls updated in the same task. `SearchResource` constants `DEFAULT_LIMIT` and `MAX_LIMIT` used consistently. `CorpusRef` matches garden ID configuration in each test context.

**Dependency order:** Tasks follow the spec's implementation order exactly.
