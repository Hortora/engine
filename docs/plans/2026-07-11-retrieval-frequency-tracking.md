# Retrieval Frequency Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #24 — Track retrieval frequency for garden entries — usage-based curation
**Issue group:** #24

**Goal:** Enable usage-based curation by tracking which garden entries are retrieved
and surfacing unretrieved/stale entries via an MCP tool.

**Architecture:** Add `casehub-neocortex-rag-tracking` dependency to activate the existing
`TrackingCaseRetriever` CDI decorator, which transparently records every
`CaseRetriever.retrieve()` call via the `RetrievalTracker` SPI. A new `gardenUnretrieved`
MCP tool queries the tracker to surface entries with zero or stale retrievals.

**Tech Stack:** Quarkus 3.36.x, casehub-neocortex-rag-tracking (SQLite + Flyway + HikariCP),
RetrievalTracker SPI, InMemoryRetrievalTracker (testing)

## Global Constraints

- Java 25, Quarkus 3.36.x
- All tests use `@QuarkusTest` with `InMemoryRetrievalTracker` (`@Alternative @Priority(1)`)
- No new SPI definitions — use existing `RetrievalTracker` from `casehub-neocortex-rag-api`
- `casehub-neocortex-rag-tracking` is `0.2-SNAPSHOT` — already in local Maven repo
- Build property `casehub.rag.tracking.enabled=true` activates decorator + retention scheduler
- Pre-release: no backward compatibility constraints

---

### Task 1: Add rag-tracking dependency and configuration

**Files:**
- Modify: `pom.xml` — add `casehub-neocortex-rag-tracking` dependency
- Modify: `src/main/resources/application.properties` — add tracking config
- Modify: `src/test/resources/application.properties` — add tracking build property for tests

**Interfaces:**
- Consumes: nothing
- Produces: `TrackingCaseRetriever` active in CDI (decorator wrapping `CaseRetriever`),
  `InMemoryRetrievalTracker` available in tests via existing `casehub-neocortex-rag-testing`

- [ ] **Step 1: Add Maven dependency**

In `pom.xml`, add after the `casehub-neocortex-rag-crossencoder` dependency:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-rag-tracking</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

- [ ] **Step 2: Add Quarkus index-dependency for CDI discovery**

In `src/main/resources/application.properties`, add after the existing `quarkus.index-dependency`
entries:

```properties
quarkus.index-dependency.casehub-rag-tracking.group-id=io.casehub
quarkus.index-dependency.casehub-rag-tracking.artifact-id=casehub-neocortex-rag-tracking
```

- [ ] **Step 3: Add tracking configuration to application.properties**

In `src/main/resources/application.properties`, add a new section:

```properties
# Retrieval frequency tracking — SQLite-backed, records every CaseRetriever.retrieve() call
casehub.rag.tracking.enabled=true
casehub.rag.tracking.sqlite.path=${hortora.garden.path}/../stats/retrieval-tracking.db
casehub.rag.tracking.retention.days=180
```

- [ ] **Step 4: Enable tracking in test properties**

In `src/test/resources/application.properties`, add:

```properties
# Retrieval tracking — InMemoryRetrievalTracker overrides SqliteRetrievalTracker via @Alternative
casehub.rag.tracking.enabled=true
casehub.rag.tracking.sqlite.path=:memory:
```

- [ ] **Step 5: Verify build passes**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — existing tests pass with the new dependency. The
`InMemoryRetrievalTracker` from `casehub-neocortex-rag-testing` (`@Alternative @Priority(1)`)
overrides `SqliteRetrievalTracker` in tests. `TrackingCaseRetriever` decorator is active
because `casehub.rag.tracking.enabled=true` is set in test properties.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/resources/application.properties
git commit -m "feat: add casehub-neocortex-rag-tracking dependency

Activates TrackingCaseRetriever decorator and RetentionScheduler.
SQLite persistence co-located with garden at stats/retrieval-tracking.db.
InMemoryRetrievalTracker used in tests via @Alternative.

Refs #24"
```

---

### Task 2: Add gardenUnretrieved MCP tool — tests first

**Files:**
- Modify: `src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java` — add tests
- Modify: `src/main/java/io/hortora/garden/mcp/GardenMcpTools.java` — add tool + inject tracker

**Interfaces:**
- Consumes: `RetrievalTracker.findRetrievedDocumentIds(CorpusRef, Instant, Instant)` → `Set<String>`,
  `EmbeddingIngestor.listDocuments(CorpusRef)` → `List<String>` (both from Task 1 CDI context)
- Produces: `gardenUnretrieved(Integer minDays, Integer staleDays)` → `String` (MCP tool)

- [ ] **Step 1: Write failing test — unretrieved entries surface**

Add to `GardenMcpToolsTest.java`. Inject `InMemoryRetrievalTracker`. The corpus is already
seeded with two entries in `@BeforeEach`. Without any retrievals recorded, both should appear
as unretrieved (but only if they pass the `minDays` age filter).

```java
@Inject InMemoryRetrievalTracker retrievalTracker;

@BeforeEach
void clearTracking() {
    retrievalTracker.clear();
}

@Test
void gardenUnretrievedReturnsEntriesNeverRetrieved() {
    // Corpus seeded with jvm/GE-20260620-a1b2c3.md and jvm/GE-20260621-d4e5f6.md
    // Both are old enough (>30 days) — no retrievals recorded
    String result = mcpTools.gardenUnretrieved(null, null);

    assertThat(result).contains("GE-20260620-a1b2c3");
    assertThat(result).contains("GE-20260621-d4e5f6");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenUnretrievedReturnsEntriesNeverRetrieved`
Expected: FAIL — `gardenUnretrieved` method does not exist yet.

- [ ] **Step 3: Write minimal gardenUnretrieved implementation**

In `GardenMcpTools.java`:

1. Add field: `@Inject RetrievalTracker retrievalTracker;`
2. Add method:

```java
@Tool(description = "List garden entries not retrieved within the tracking window, or stale-retrieved. Retrieval records are retained for a configurable period (default 180 days); 'unretrieved' means no retrieval record exists in that window. Use to identify candidates for review or erasure during harvest sessions.")
String gardenUnretrieved(
        @ToolArg(description = "Minimum age in days — entries indexed less than this many days ago are excluded (default 30)", required = false)
        Integer minDays,
        @ToolArg(description = "Stale threshold in days — entries retrieved at some point but not within this window are flagged as stale (default 90). Must be less than the retention period.", required = false)
        Integer staleDays) {

    int effectiveMinDays = minDays != null && minDays > 0 ? minDays : 30;
    int effectiveStaleDays = staleDays != null && staleDays > 0 ? staleDays : 90;

    CorpusRef corpusRef = new CorpusRef("hortora", config.id());

    List<String> allDocuments = embeddingIngestor.listDocuments(corpusRef);
    Set<String> everRetrieved = retrievalTracker.findRetrievedDocumentIds(
            corpusRef, Instant.EPOCH, Instant.now());

    // Unretrieved: in corpus but never retrieved within tracking window
    List<String> unretrieved = allDocuments.stream()
            .filter(id -> !everRetrieved.contains(id))
            .filter(id -> passesMinDaysFilter(id, effectiveMinDays))
            .sorted()
            .toList();

    // Stale: retrieved at some point but not within staleDays window
    Set<String> recentlyRetrieved = retrievalTracker.findRetrievedDocumentIds(
            corpusRef,
            Instant.now().minus(effectiveStaleDays, ChronoUnit.DAYS),
            Instant.now());
    List<String> stale = allDocuments.stream()
            .filter(everRetrieved::contains)
            .filter(id -> !recentlyRetrieved.contains(id))
            .sorted()
            .toList();

    if (unretrieved.isEmpty() && stale.isEmpty()) {
        return "All " + allDocuments.size() + " entries have been retrieved within the tracking window.";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("Tracking window: retrieval records retained for configured period. ")
      .append("Stale threshold: ").append(effectiveStaleDays).append(" days.\n\n");

    if (!unretrieved.isEmpty()) {
        sb.append("## Unretrieved entries (").append(unretrieved.size()).append(")\n\n");
        Map<String, List<String>> byDomain = groupByDomain(unretrieved);
        for (var entry : byDomain.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n");
            entry.getValue().forEach(id -> sb.append("- ").append(extractDocumentId(id)).append("\n"));
            sb.append("\n");
        }
    }

    if (!stale.isEmpty()) {
        sb.append("## Stale entries (").append(stale.size())
          .append(") — not retrieved in the last ").append(effectiveStaleDays).append(" days\n\n");
        Map<String, List<String>> byDomain = groupByDomain(stale);
        for (var entry : byDomain.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n");
            entry.getValue().forEach(id -> sb.append("- ").append(extractDocumentId(id)).append("\n"));
            sb.append("\n");
        }
    }

    return sb.toString();
}
```

3. Add private helper methods:

```java
static boolean passesMinDaysFilter(String documentId, int minDays) {
    // Extract filename from path (e.g., "jvm/GE-20260620-a1b2c3.md" → "GE-20260620-a1b2c3.md")
    String filename = documentId.contains("/")
            ? documentId.substring(documentId.lastIndexOf('/') + 1) : documentId;
    if (filename.matches("GE-\\d{8}-[0-9a-f]{6}\\.md")) {
        String dateStr = filename.substring(3, 11); // YYYYMMDD
        try {
            LocalDate entryDate = LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE);
            return ChronoUnit.DAYS.between(entryDate, LocalDate.now()) >= minDays;
        } catch (DateTimeParseException e) {
            return true; // unparseable → include
        }
    }
    return true; // non-GE entries always included
}

private static Map<String, List<String>> groupByDomain(List<String> documentIds) {
    return documentIds.stream()
            .collect(Collectors.groupingBy(id -> id.contains("/")
                    ? id.substring(0, id.indexOf('/')) : "unknown",
                    TreeMap::new, Collectors.toList()));
}
```

4. Add imports:

```java
import io.casehub.neocortex.rag.RetrievalTracker;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenUnretrievedReturnsEntriesNeverRetrieved`
Expected: PASS

- [ ] **Step 5: Write test — retrieved entries excluded from unretrieved list**

```java
@Test
void gardenUnretrievedExcludesRetrievedEntries() {
    // Record a retrieval for one entry
    CorpusRef corpus = new CorpusRef("hortora", "garden");
    retrievalTracker.record(
            RetrievalQuery.of("hibernate"),
            corpus,
            List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
            16);

    String result = mcpTools.gardenUnretrieved(null, null);

    assertThat(result).doesNotContain("GE-20260620-a1b2c3");
    assertThat(result).contains("GE-20260621-d4e5f6");
}
```

- [ ] **Step 6: Run test — should pass (already implemented)**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenUnretrievedExcludesRetrievedEntries`
Expected: PASS

- [ ] **Step 7: Write test — minDays excludes recent entries**

```java
@Test
void gardenUnretrievedExcludesRecentEntries() {
    // Add an entry with today's date
    String todayId = "jvm/GE-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-aabbcc.md";
    ingestor.ingest(new CorpusRef("hortora", "garden"), List.of(
            new ChunkInput("Recent entry.", todayId,
                    Map.of("title", "Recent", "domain", "jvm", "type", "gotcha"))));

    String result = mcpTools.gardenUnretrieved(30, null);

    // Today's entry should be excluded (age < 30 days)
    assertThat(result).doesNotContain("aabbcc");
    // Old entries should still appear
    assertThat(result).contains("GE-20260620-a1b2c3");
}
```

- [ ] **Step 8: Run test**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenUnretrievedExcludesRecentEntries`
Expected: PASS

- [ ] **Step 9: Write test — non-GE entries always included**

```java
@Test
void gardenUnretrievedIncludesNonGeEntriesUnconditionally() {
    ingestor.ingest(new CorpusRef("hortora", "garden"), List.of(
            new ChunkInput("Testing principles.", "approaches/testing.md",
                    Map.of("title", "Testing", "domain", "approaches", "type", "reference"))));

    String result = mcpTools.gardenUnretrieved(null, null);

    assertThat(result).contains("approaches/testing");
}
```

- [ ] **Step 10: Run test**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenUnretrievedIncludesNonGeEntriesUnconditionally`
Expected: PASS

- [ ] **Step 11: Write test — stale entries detected**

```java
@Test
void gardenUnretrievedDetectsStaleEntries() {
    // Record a retrieval for one entry — the InMemoryRetrievalTracker records at Instant.now(),
    // so with staleDays=0 it won't be in the "recent" window
    CorpusRef corpus = new CorpusRef("hortora", "garden");
    retrievalTracker.record(
            RetrievalQuery.of("hibernate"),
            corpus,
            List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
            16);

    // With staleDays=0, "recently retrieved" window is 0 days — effectively nothing is recent
    // The entry should appear as stale (retrieved ever, but not "recently")
    // Note: InMemoryRetrievalTracker records at Instant.now(), so staleDays=0 means
    // the window is [now, now) which is empty
    String result = mcpTools.gardenUnretrieved(null, 0);

    assertThat(result).contains("Stale entries");
    assertThat(result).contains("GE-20260620-a1b2c3");
}
```

- [ ] **Step 12: Run test**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenUnretrievedDetectsStaleEntries`
Expected: PASS

- [ ] **Step 13: Write test — domain derivation from path**

```java
@Test
void gardenUnretrievedGroupsByDomain() {
    ingestor.ingest(new CorpusRef("hortora", "garden"), List.of(
            new ChunkInput("Python gotcha.", "python/GE-20260101-aabbcc.md",
                    Map.of("title", "Python gotcha", "domain", "python", "type", "gotcha"))));

    String result = mcpTools.gardenUnretrieved(null, null);

    assertThat(result).contains("### jvm");
    assertThat(result).contains("### python");
}
```

- [ ] **Step 14: Run test**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenUnretrievedGroupsByDomain`
Expected: PASS

- [ ] **Step 15: Write test — all entries retrieved returns positive message**

```java
@Test
void gardenUnretrievedAllRetrievedReturnsPositiveMessage() {
    CorpusRef corpus = new CorpusRef("hortora", "garden");
    // Record retrievals for both seeded entries
    retrievalTracker.record(
            RetrievalQuery.of("hibernate"),
            corpus,
            List.of(new RetrievedChunk("content", "jvm/GE-20260620-a1b2c3.md", 0.9, Map.of())),
            16);
    retrievalTracker.record(
            RetrievalQuery.of("CDI"),
            corpus,
            List.of(new RetrievedChunk("content", "jvm/GE-20260621-d4e5f6.md", 0.8, Map.of())),
            16);

    String result = mcpTools.gardenUnretrieved(null, null);

    assertThat(result).contains("All");
    assertThat(result).contains("entries have been retrieved");
}
```

- [ ] **Step 16: Run test**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenUnretrievedAllRetrievedReturnsPositiveMessage`
Expected: PASS

- [ ] **Step 17: Write test — gardenSearch triggers tracking via decorator**

```java
@Test
void gardenSearchRecordsRetrievalsViaDecorator() {
    // Clear and perform a search
    retrievalTracker.clear();
    mcpTools.gardenSearch("hibernate lazy", null, null, null, null);

    // The TrackingCaseRetriever decorator should have recorded the retrieval
    CorpusRef corpus = new CorpusRef("hortora", "garden");
    Set<String> retrievedIds = retrievalTracker.findRetrievedDocumentIds(
            corpus, Instant.EPOCH, Instant.now());

    assertThat(retrievedIds).isNotEmpty();
}
```

- [ ] **Step 18: Run test**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#gardenSearchRecordsRetrievalsViaDecorator`
Expected: PASS — `TrackingCaseRetriever` is active because `casehub.rag.tracking.enabled=true`
is set in test properties, and the decorator wraps the `InMemoryCaseRetriever`.

- [ ] **Step 19: Run full test suite**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — all existing tests pass, all new tests pass.

- [ ] **Step 20: Commit**

```bash
git add src/main/java/io/hortora/garden/mcp/GardenMcpTools.java src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java
git commit -m "feat: add gardenUnretrieved MCP tool for usage-based curation

Surfaces entries with zero or stale retrievals, grouped by domain.
Supports minDays filter (excludes recently-indexed entries) and
configurable staleDays threshold. Uses existing RetrievalTracker SPI
via TrackingCaseRetriever decorator for transparent recording.

Refs #24"
```

---

### Task 3: Add passesMinDaysFilter unit tests

**Files:**
- Modify: `src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java` — add unit tests for
  the static `passesMinDaysFilter` method

**Interfaces:**
- Consumes: `GardenMcpTools.passesMinDaysFilter(String, int)` (package-private static from Task 2)
- Produces: nothing — pure verification

- [ ] **Step 1: Write tests for passesMinDaysFilter edge cases**

```java
@Test
void passesMinDaysFilterExcludesRecentGeEntries() {
    String recentId = "jvm/GE-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-aabbcc.md";
    assertThat(GardenMcpTools.passesMinDaysFilter(recentId, 30)).isFalse();
}

@Test
void passesMinDaysFilterIncludesOldGeEntries() {
    assertThat(GardenMcpTools.passesMinDaysFilter("jvm/GE-20250101-aabbcc.md", 30)).isTrue();
}

@Test
void passesMinDaysFilterIncludesNonGeEntries() {
    assertThat(GardenMcpTools.passesMinDaysFilter("approaches/testing.md", 30)).isTrue();
}

@Test
void passesMinDaysFilterIncludesGeWithoutPath() {
    assertThat(GardenMcpTools.passesMinDaysFilter("GE-20250101-aabbcc.md", 30)).isTrue();
}
```

- [ ] **Step 2: Run tests**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest#passesMinDaysFilter*`
Expected: PASS

- [ ] **Step 3: Run full test suite**

Run: `./mvnw verify`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java
git commit -m "test: add passesMinDaysFilter edge case coverage

Covers GE-formatted IDs (recent and old), non-GE IDs, and
path-less GE IDs.

Refs #24"
```
