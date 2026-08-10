# Federation Review Findings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish the federation chain walk implementation — add provenance assertions to `SearchResourceTest` and replace hardcoded WireMock port 9999 with dynamic allocation in `FederationIntegrationTest`.

**Architecture:** Two independent test-only changes. Finding 5 adds a new test method. Finding 3 extracts WireMock lifecycle into a `QuarkusTestResourceLifecycleManager` that starts WireMock on a dynamic port, writes a temp YAML fixture, and injects the server instance via `TestInjector`.

**Tech Stack:** Quarkus 3.36.x, WireMock (bare `org.wiremock:wiremock`), JUnit 5, REST Assured

**Spec:** `docs/superpowers/specs/2026-06-14-federation-review-findings-design.md`

---

### Task 1: Add provenance field assertions to SearchResourceTest

**Files:**
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`

The default test config has `hortora.garden.id=garden` and `hortora.garden.id-prefix=GE` (from `GardenConfig` `@WithDefault` values). No custom test profile is active for `SearchResourceTest`. Every search result from the non-federated path should carry these provenance values.

- [ ] **Step 1: Write the new test**

Add this test method to `SearchResourceTest`:

```java
@Test
void searchResultsIncludeProvenanceFields() {
    given()
        .queryParam("q", "hibernate lazy loading exception")
    .when()
        .get("/search")
    .then()
        .statusCode(200)
        .body("$", not(empty()))
        .body("[0].source", equalTo("garden"))
        .body("[0].sourcePrefix", equalTo("GE"));
}
```

This requires adding `empty` and `equalTo` to the existing static imports. `empty` is already imported (used by `domainFilterReturnsOnlyMatchingDomain`). `equalTo` is already imported (used by `multiDomainFilterExcludesUnrequestedDomain`).

- [ ] **Step 2: Run the test to verify it passes**

Run: `./mvnw test -pl . -Dtest=SearchResourceTest#searchResultsIncludeProvenanceFields`

Expected: PASS — the provenance fields are already populated by `SearchResource.searchOwnQdrant()` (lines 94-105 of `SearchResource.java`). This test asserts existing behaviour that was previously uncovered.

- [ ] **Step 3: Run full test suite to verify no regressions**

Run: `./mvnw test -pl .`

Expected: All tests pass.

- [ ] **Step 4: Commit**

```
git add src/test/java/io/hortora/garden/search/SearchResourceTest.java
git commit -m "test: assert provenance fields on non-federated search path  Refs #6"
```

---

### Task 2: Create WireMockFederationResource

**Files:**
- Create: `src/test/java/io/hortora/garden/federation/WireMockFederationResource.java`

This `QuarkusTestResourceLifecycleManager` starts WireMock on a dynamic port, writes a temp YAML fixture with the dynamic URL, returns config overrides, injects the `WireMockServer` into the test class, and cleans up in `stop()`.

- [ ] **Step 1: Create the lifecycle manager**

Create `src/test/java/io/hortora/garden/federation/WireMockFederationResource.java`:

```java
package io.hortora.garden.federation;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class WireMockFederationResource implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wireMock;
    private Path tempYaml;

    @Override
    public Map<String, String> start() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();

        tempYaml = writeFederationYaml(wireMock.port());

        return Map.of(
                "hortora.garden.schema-path", tempYaml.toString(),
                "hortora.garden.id", "test-garden",
                "hortora.garden.id-prefix", "TG"
        );
    }

    @Override
    public void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
        if (tempYaml != null) {
            try {
                Files.deleteIfExists(tempYaml);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(wireMock,
                new TestInjector.MatchesType(WireMockServer.class));
    }

    private static Path writeFederationYaml(int port) {
        String yaml = """
                ---
                schema-version: 2.0.0
                id: test-garden
                id-prefix: TG

                federation:
                  role: child
                  relevance-threshold: 0.1
                  max-depth: 5
                  upstream:
                    - url: http://localhost:%d
                      id-prefix: UG
                      search-order: fallback
                ---
                """.formatted(port);
        try {
            Path tmp = Files.createTempFile("federation-test-", ".yaml");
            Files.writeString(tmp, yaml);
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./mvnw test-compile -pl .`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```
git add src/test/java/io/hortora/garden/federation/WireMockFederationResource.java
git commit -m "test: add WireMockFederationResource with dynamic port  Refs #6"
```

---

### Task 3: Migrate FederationIntegrationTest to use WireMockFederationResource

**Files:**
- Modify: `src/test/java/io/hortora/garden/federation/FederationIntegrationTest.java`

Replace the `@TestProfile` + manual WireMock lifecycle with `@QuarkusTestResource`. Keep `@BeforeEach` for per-test stub cleanup.

- [ ] **Step 1: Replace annotations and remove manual lifecycle**

Replace the class annotations:

```java
// Before
@QuarkusTest
@TestProfile(FederationIntegrationTest.FederationTestProfile.class)
class FederationIntegrationTest {

// After
@QuarkusTest
@QuarkusTestResource(value = WireMockFederationResource.class, restrictToAnnotatedClass = true)
class FederationIntegrationTest {
```

Replace the WireMock field — change from `static` to instance, remove initializer:

```java
// Before
static WireMockServer wireMock;

// After
WireMockServer wireMock;
```

Remove the `@BeforeAll startWireMock()` method (lines 35-39).

Remove the `@AfterAll stopWireMock()` method (lines 42-47).

Keep the `@BeforeEach resetWireMock()` method — but it no longer needs the null check since injection is guaranteed before test methods run:

```java
@BeforeEach
void resetWireMock() {
    wireMock.resetAll();
}
```

Remove the inner `FederationTestProfile` class (lines 166-175).

Update imports — remove:
```java
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
```

Add:
```java
import io.quarkus.test.common.QuarkusTestResource;
```

The remaining imports stay — `WireMockServer` (for the field type), `WireMock` static methods (`aResponse`, `get`, `getRequestedFor`, etc.), `BeforeEach`, and `Test` are all still used. Note: the `WireMock` import (`com.github.tomakehurst.wiremock.client.WireMock`) is actually not needed if all calls go through the instance (`wireMock.stubFor()`, `wireMock.verify()`). Check whether any test uses the static `WireMock.*` methods directly.

Looking at the current code: all stubs use `wireMock.stubFor(get(...))` and `wireMock.verify(getRequestedFor(...))`. The static import `import static com.github.tomakehurst.wiremock.client.WireMock.*` provides `get`, `getRequestedFor`, `aResponse`, `urlPathEqualTo`, `containing` — these are static factory methods, not calls to the static default client. They stay.

The non-static import `import com.github.tomakehurst.wiremock.client.WireMock;` was only used for `WireMock.configureFor("localhost", 9999)` in `startWireMock()`. That method is removed, so this import can be removed.

- [ ] **Step 2: Verify the full file looks correct**

The resulting file should be:

```java
package io.hortora.garden.federation;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@QuarkusTestResource(value = WireMockFederationResource.class, restrictToAnnotatedClass = true)
class FederationIntegrationTest {

    WireMockServer wireMock;

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void upstreamResultsMergedWithOwnResults() {
        wireMock.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [
                                {
                                    "id": "upstream-entry-1",
                                    "title": "Upstream gotcha",
                                    "domain": "jvm",
                                    "type": "gotcha",
                                    "score": 10,
                                    "body": "Upstream body",
                                    "relevance": 0.85,
                                    "source": "upstream-garden",
                                    "sourcePrefix": "UG"
                                }
                            ]
                            """)));

        given()
                .queryParam("q", "hibernate lazy loading exception")
                .queryParam("limit", "10")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("$", not(empty()))
                .body("source", hasItem("upstream-garden"))
                .body("source", hasItem("test-garden"));
    }

    @Test
    void visitedHeaderSentToUpstream() {
        wireMock.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        given()
                .queryParam("q", "test query")
        .when()
                .get("/search");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/search"))
                .withHeader("X-Federation-Visited", containing("test-garden")));
    }

    @Test
    void upstreamTimeoutReturnsOwnResults() {
        wireMock.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(10000)
                        .withBody("[]")));

        given()
                .queryParam("q", "hibernate lazy loading exception")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("$", not(empty()))
                .body("source", hasItem("test-garden"));
    }

    @Test
    void cycleDetectionReturnsEmpty() {
        given()
                .queryParam("q", "test query")
                .header("X-Federation-Visited", "test-garden")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void depthExceededReturnsOwnResultsWithoutWalking() {
        wireMock.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        given()
                .queryParam("q", "hibernate lazy loading exception")
                .header("X-Federation-Visited", "a,b,c,d,e,f")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("$", not(empty()))
                .body("source", hasItem("test-garden"));

        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void ownResultsLabelledWithGardenId() {
        given()
                .queryParam("q", "hibernate lazy loading exception")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("[0].source", equalTo("test-garden"))
                .body("[0].sourcePrefix", equalTo("TG"));
    }
}
```

- [ ] **Step 3: Run FederationIntegrationTest to verify it passes**

Run: `./mvnw test -pl . -Dtest=FederationIntegrationTest`

Expected: All 6 tests pass. WireMock starts on a dynamic port, stubs work via instance methods, `@BeforeEach` resets between tests.

- [ ] **Step 4: Run full test suite to verify no regressions**

Run: `./mvnw test -pl .`

Expected: All tests pass. `restrictToAnnotatedClass = true` ensures the WireMock resource's config overrides don't affect `SearchResourceTest` or any other `@QuarkusTest`.

- [ ] **Step 5: Commit**

```
git add src/test/java/io/hortora/garden/federation/FederationIntegrationTest.java
git commit -m "refactor: migrate FederationIntegrationTest to dynamic WireMock port  Refs #6"
```

---

### Task 4: Close issue #6

- [ ] **Step 1: Run full test suite one final time**

Run: `./mvnw test -pl .`

Expected: All tests pass.

- [ ] **Step 2: Verify no production code was changed**

Run: `git diff main -- src/main/`

Expected: Empty — all changes are test-only.

- [ ] **Step 3: Issue will be closed at branch close via work-end**

No manual action needed — `work-end` reads `covers:` from `.meta` and closes #6.
