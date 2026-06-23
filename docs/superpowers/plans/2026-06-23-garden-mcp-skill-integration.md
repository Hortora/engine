# Garden MCP Skill Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the engine's `gardenSearch` MCP tool the primary knowledge retrieval mechanism for soredium skills, with enriched output (entry IDs, metadata) and a `gardenReindex` tool for bulk re-ingestion.

**Architecture:** Three engine changes (gardenSearch output enrichment + title dedup, gardenReindex tool sharing logic with CollectionMigration, approach doc frontmatter) plus skill instruction updates in soredium. No new classes — modifications to existing `GardenMcpTools` and `CollectionMigration`.

**Tech Stack:** Quarkus 3.36.x, casehub-rag SPI, Java 25, soredium skills (Markdown)

## Global Constraints

- All commits reference issue: `Refs #21` or `Closes #21`
- Engine tests use `InMemoryCaseRetriever` and `InMemoryEmbeddingIngestor` from `casehub-rag-testing` — no Qdrant in tests
- `TestEmbeddingModel` (`@Mock`) satisfies the `EmbeddingModel` CDI injection point
- `GardenMcpToolsTest` is a `@QuarkusTest` — CDI container runs, test doubles via `@Alternative @Priority(1)`
- `CollectionMigrationTest` is a plain unit test with Mockito — no CDI container
- Skills live in `~/claude/hortora/soredium/` — edit the source of truth, then sync via `sync-local`

---

### Task 1: Enrich gardenSearch output and fix title duplication

**Files:**
- Modify: `src/main/java/io/hortora/garden/mcp/GardenMcpTools.java`
- Modify: `src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java`

**Interfaces:**
- Consumes: `SearchResult` record (id, title, domain, type, score, body, relevance, source, sourcePrefix)
- Produces: Enriched markdown output with `**ID:**`, `**Domain:**`, `**Type:**`, `**Relevance:**` line. Title appears once (not duplicated).

- [ ] **Step 1: Update existing test to expect enriched output**

In `GardenMcpToolsTest.java`, update `gardenSearchReturnsFormattedResults()`:

```java
@Test
void gardenSearchReturnsFormattedResults() {
    String result = mcpTools.gardenSearch("hibernate lazy", null, null);

    assertThat(result).contains("## [own] Hibernate lazy loading gotcha");
    assertThat(result).contains("**ID:** ge-test-hibernate-lazy");
    assertThat(result).contains("**Domain:** jvm");
    assertThat(result).contains("**Type:** gotcha");
    assertThat(result).contains("**Relevance:**");
    assertThat(result).contains("Hibernate lazy loading fails outside transaction.");
}
```

- [ ] **Step 2: Add test for title deduplication**

The `ChunkInput` content in `seedFixtures` is the raw text (not prepended with title — that's `GardenMetadataExtractor`'s job, which doesn't run with `InMemoryEmbeddingIngestor`). To test title dedup, add a fixture where the body starts with the title:

```java
@Test
void gardenSearchStripsDoubledTitle() {
    ingestor.deleteCorpus(CORPUS);
    ingestor.ingest(CORPUS, List.of(
            new ChunkInput(
                    "Hibernate lazy loading gotcha\n\nHibernate lazy loading fails outside transaction.",
                    "jvm/GE-20260518-d1e4b2.md",
                    Map.of("title", "Hibernate lazy loading gotcha",
                            "domain", "jvm", "type", "gotcha", "score", "8"))
    ));

    String result = mcpTools.gardenSearch("hibernate lazy", null, null);

    long titleCount = result.lines()
            .filter(l -> l.contains("Hibernate lazy loading gotcha"))
            .count();
    assertThat(titleCount).as("Title should appear once (heading), not twice").isEqualTo(1);
    assertThat(result).contains("Hibernate lazy loading fails outside transaction.");
}
```

- [ ] **Step 3: Add test for non-GE document ID (approach docs)**

```java
@Test
void gardenSearchKeepsRelativePathForNonGeDocuments() {
    ingestor.deleteCorpus(CORPUS);
    ingestor.ingest(CORPUS, List.of(
            new ChunkInput(
                    "Testing principles and TDD workflow.",
                    "approaches/testing.md",
                    Map.of("title", "Testing — Principles",
                            "domain", "approaches", "type", "reference", "score", "10"))
    ));

    String result = mcpTools.gardenSearch("testing TDD", null, null);

    assertThat(result).contains("**ID:** approaches/testing");
    assertThat(result).doesNotContain("**ID:** testing");
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: `gardenSearchReturnsFormattedResults` fails (output missing `**ID:**` line), `gardenSearchStripsDoubledTitle` fails (title appears twice), `gardenSearchKeepsRelativePathForNonGeDocuments` fails (output missing `**ID:** approaches/testing`).

- [ ] **Step 5: Implement output enrichment and title dedup in GardenMcpTools**

Replace the entire `GardenMcpTools.java`:

```java
package io.hortora.garden.mcp;

import io.casehub.rag.CorpusRef;
import io.casehub.rag.EmbeddingIngestor;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.federation.FederationConfig;
import io.hortora.garden.search.SearchResource;
import io.hortora.garden.search.SearchResult;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class GardenMcpTools {

    @Inject SearchResource searchResource;
    @Inject EmbeddingIngestor embeddingIngestor;
    @Inject GardenConfig config;
    @Inject FederationConfig federationConfig;

    @Tool(description = "Search the Hortora knowledge garden for relevant entries about non-obvious developer knowledge, gotchas, techniques, and undocumented behaviours. Returns full entry content for LLM consumption.")
    String gardenSearch(
            @ToolArg(description = "Natural language description of the problem, symptom, or topic to search for") String query,
            @ToolArg(description = "Optional: filter by domain (e.g. jvm, tools, python). Leave empty to search all domains.", required = false) String domain,
            @ToolArg(description = "Maximum number of entries to return (default 8)", required = false) Integer limit) {

        List<SearchResult> results = searchResource.searchFor(query,
                domain != null && !domain.isBlank() ? List.of(domain) : null, limit);

        if (results.isEmpty()) {
            return "No relevant garden entries found for: " + query;
        }

        return results.stream()
                .map(r -> "## " + provenanceLabel(r) + " " + r.title()
                        + "\n**ID:** " + extractDocumentId(r.id())
                        + " · **Domain:** " + r.domain()
                        + " · **Type:** " + r.type()
                        + " · **Relevance:** " + String.format("%.2f", r.relevance())
                        + "\n\n" + stripTitlePrefix(r.title(), r.body()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool(description = "Get the status of the garden index: how many entries are indexed and where the garden is located.")
    String gardenStatus() {
        CorpusRef corpusRef = new CorpusRef("hortora", config.id());
        int count;
        try {
            count = embeddingIngestor.listDocuments(corpusRef).size();
        } catch (Exception e) {
            Log.warn("Failed to count indexed entries", e);
            count = -1;
        }
        return "Garden path: " + config.path() + "\nIndexed entries: " + count;
    }

    private String provenanceLabel(SearchResult result) {
        if (federationConfig.gardenId().equals(result.source())) {
            return "[own]";
        }
        return "[" + result.sourcePrefix() + "]";
    }

    static String extractDocumentId(String path) {
        String withoutExt = path.replaceFirst("\\.md$", "");
        String filename = withoutExt.contains("/")
                ? withoutExt.substring(withoutExt.lastIndexOf('/') + 1)
                : withoutExt;
        if (filename.matches("GE-\\d{8}-[0-9a-f]{6}")) {
            return filename;
        }
        return withoutExt;
    }

    static String stripTitlePrefix(String title, String body) {
        if (title != null && body.startsWith(title + "\n\n")) {
            return body.substring(title.length() + 2);
        }
        return body;
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=GardenMcpToolsTest`

Expected: All 5 tests pass (3 original + 2 new).

- [ ] **Step 7: Run full test suite**

Run: `./mvnw verify`

Expected: All 67+ tests pass, BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/hortora/garden/mcp/GardenMcpTools.java src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java
git commit -m "feat: enrich gardenSearch output with entry ID, domain, type, relevance

Add metadata line below each entry heading. extractDocumentId() strips
directory prefix for GE entries, keeps relative path for non-GE docs.
stripTitlePrefix() removes the title duplication caused by
GardenMetadataExtractor prepending title for embedding quality.

Refs #21"
```

---

### Task 2: Add gardenReindex MCP tool, extract shared reindex logic

**Files:**
- Modify: `src/main/java/io/hortora/garden/inference/CollectionMigration.java`
- Modify: `src/main/java/io/hortora/garden/mcp/GardenMcpTools.java`
- Modify: `src/test/java/io/hortora/garden/inference/CollectionMigrationTest.java`
- Modify: `src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java`

**Interfaces:**
- Consumes: `EmbeddingIngestor.deleteCorpus(CorpusRef)`, `CursorStore.save(String, String)`, `EmbeddingIngestor.listDocuments(CorpusRef)`
- Produces: `CollectionMigration.resetCorpus(CorpusRef, String)` — shared method used by both `onStartup()` and `gardenReindex`

- [ ] **Step 1: Write test for gardenReindex in GardenMcpToolsTest**

```java
@Test
void gardenReindexDeletesCorpusAndResetsCursor() {
    String result = mcpTools.gardenReindex();

    assertThat(result).contains("Reindex triggered");
    assertThat(result).contains("garden");

    String status = mcpTools.gardenStatus();
    assertThat(status).contains("Indexed entries: 0");
}
```

- [ ] **Step 2: Write test for extracted resetCorpus in CollectionMigrationTest**

Add a test that verifies `resetCorpus` calls the same `deleteCorpus` + `cursorStore.save`:

```java
@Test
void resetCorpusDeletesAndResetsCursor() {
    CorpusRef corpusRef = new CorpusRef("hortora", "garden");

    migration.resetCorpus(corpusRef, "garden");

    verify(embeddingIngestor).deleteCorpus(eq(corpusRef));
    verify(cursorStore).save("garden", "");
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest="GardenMcpToolsTest,CollectionMigrationTest"`

Expected: `gardenReindexDeletesCorpusAndResetsCursor` fails (method doesn't exist), `resetCorpusDeletesAndResetsCursor` fails (method doesn't exist).

- [ ] **Step 4: Extract resetCorpus into CollectionMigration**

In `CollectionMigration.java`, extract the shared logic and update `onStartup()` to use it:

```java
public void resetCorpus(CorpusRef corpusRef, String gardenId) {
    embeddingIngestor.deleteCorpus(corpusRef);
    cursorStore.save(gardenId, "");
}

void onStartup(@Observes @Priority(10) StartupEvent event) {
    if (!sparseEmbedderInstance.isResolvable()) {
        return;
    }

    CorpusRef corpusRef = new CorpusRef("hortora", gardenConfig.id());
    String collectionName = ragConfig.tenancyStrategy().collectionName(corpusRef);

    try {
        if (!qdrantClient.collectionExistsAsync(collectionName).get()) {
            return;
        }

        CollectionInfo info = qdrantClient.getCollectionInfoAsync(collectionName).get();
        CollectionParams params = info.getConfig().getParams();

        if (params.hasSparseVectorsConfig()) {
            Log.infof("Collection '%s' already has sparse vectors — no migration needed", collectionName);
            return;
        }

        Log.infof("Collection '%s' lacks sparse vectors — migrating to hybrid", collectionName);
        resetCorpus(corpusRef, gardenConfig.id());
        Log.info("Migration complete — collection deleted and cursor reset. Full re-index will run on next ingestion cycle.");

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        Log.warn("Interrupted during collection migration check", e);
    } catch (ExecutionException e) {
        Log.warn("Failed to check collection for migration", e.getCause());
    }
}
```

- [ ] **Step 5: Add gardenReindex tool to GardenMcpTools**

Add the injection and method to `GardenMcpTools.java`:

```java
@Inject CollectionMigration collectionMigration;
```

```java
@Tool(description = "Trigger a full re-index of the garden corpus. Deletes the current Qdrant collection and resets the cursor so the next ingestion cycle re-embeds all entries. Use after bulk metadata changes, reclassification, or schema evolution.")
String gardenReindex() {
    CorpusRef corpusRef = new CorpusRef("hortora", config.id());
    int fileCount;
    try {
        fileCount = embeddingIngestor.listDocuments(corpusRef).size();
    } catch (Exception e) {
        fileCount = -1;
    }

    collectionMigration.resetCorpus(corpusRef, config.id());

    return "Reindex triggered for garden '" + config.id()
            + "'. Collection deleted, cursor reset. Re-embedding will complete on next ingestion cycle"
            + (fileCount >= 0 ? " (" + fileCount + " entries in corpus)." : ".");
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest="GardenMcpToolsTest,CollectionMigrationTest"`

Expected: All tests pass.

- [ ] **Step 7: Run full test suite**

Run: `./mvnw verify`

Expected: All tests pass, BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/hortora/garden/inference/CollectionMigration.java src/main/java/io/hortora/garden/mcp/GardenMcpTools.java src/test/java/io/hortora/garden/inference/CollectionMigrationTest.java src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java
git commit -m "feat: add gardenReindex MCP tool, extract shared resetCorpus

CollectionMigration.resetCorpus() is the shared delete+cursor-reset
logic used by both onStartup() migration and the new gardenReindex
tool. No duplication.

Refs #21"
```

---

### Task 3: Add frontmatter to approach docs in the garden

**Files:**
- Modify: `~/.hortora/garden/approaches/testing.md`
- Modify: `~/.hortora/garden/approaches/code-review.md`
- Modify: `~/.hortora/garden/approaches/security-audit.md`
- Modify: `~/.hortora/garden/approaches/dependency-management.md`
- Modify: `~/.hortora/garden/approaches/observability-patterns.md`
- Modify: `~/.hortora/garden/approaches/observability.md`

**Interfaces:**
- Consumes: nothing
- Produces: YAML frontmatter on each file so `GardenMetadataExtractor` indexes them

- [ ] **Step 1: Add frontmatter to each approach doc**

For each file in `~/.hortora/garden/approaches/`, prepend YAML frontmatter. The title comes from the first `#` heading. Domain is `approaches`, type is `reference`. Score reflects the human quality assessment — these are curated reference docs, score accordingly (not inflated).

`testing.md`:
```yaml
---
title: "Testing — Principles"
domain: approaches
type: reference
score: 10
tags: [testing, tdd, coverage, unit-tests, integration-tests]
submitted: "2026-05-29"
---
```

`code-review.md`:
```yaml
---
title: "Code Review — Principles"
domain: approaches
type: reference
score: 10
tags: [code-review, review, quality]
submitted: "2026-05-29"
---
```

`security-audit.md`:
```yaml
---
title: "Security Audit — Principles"
domain: approaches
type: reference
score: 10
tags: [security, audit, owasp, vulnerabilities]
submitted: "2026-05-29"
---
```

`dependency-management.md`:
```yaml
---
title: "Dependency Management — Principles"
domain: approaches
type: reference
score: 10
tags: [dependencies, maven, npm, pip, versioning]
submitted: "2026-05-29"
---
```

`observability-patterns.md`:
```yaml
---
title: "Observability Patterns"
domain: approaches
type: reference
score: 10
tags: [observability, logging, metrics, tracing]
submitted: "2026-05-29"
---
```

`observability.md`:
```yaml
---
title: "Observability — Principles"
domain: approaches
type: reference
score: 10
tags: [observability, logging, monitoring]
submitted: "2026-05-29"
---
```

- [ ] **Step 2: Verify GardenMetadataExtractor handles the frontmatter**

Run the existing `GardenMetadataExtractorTest` to confirm the extractor handles the frontmatter format:

Run: `./mvnw test -pl . -Dtest=GardenMetadataExtractorTest`

Expected: All tests pass — the extractor already handles YAML frontmatter. The approach docs just didn't have it before.

- [ ] **Step 3: Commit to garden repo**

```bash
git -C ~/.hortora/garden add approaches/
git -C ~/.hortora/garden commit -m "docs: add YAML frontmatter to approach docs for engine indexing

Enables GardenMetadataExtractor to index approach documents alongside
garden entries. domain=approaches, type=reference.

Refs Hortora/engine#21"
```

---

### Task 4: Update soredium skills with garden consultation blocks

**Files:**
- Modify: `~/claude/hortora/soredium/work-start/SKILL.md`
- Modify: `~/claude/hortora/soredium/forage/SKILL.md`

**Interfaces:**
- Consumes: `gardenSearch` MCP tool (engine)
- Produces: Updated skill instructions referencing MCP tool with git grep fallback

Note: brainstorming, systematic-debugging, code-review, java-dev, python-dev, ts-dev are superpowers plugin skills, not soredium skills. Their updates are listed in the spec but implemented in the superpowers plugin repo, not here. This task covers the two soredium-owned skills.

- [ ] **Step 1: Update work-start Step 3b**

In `~/claude/hortora/soredium/work-start/SKILL.md`, replace the Step 3b section. Find the section starting with `### Step 3b — Garden search` and replace with:

```markdown
### Step 3b — Garden search

Search the garden for entries relevant to the domain being worked. Extract 2–4
keywords from the work description (domain name, library, framework, key concept).

1. Call the `gardenSearch` MCP tool with a natural language query derived from
   the work description. Include domain filter if the work is domain-specific.
2. If `gardenSearch` is unavailable or returns an error, warn once:
   "⚠️ Garden MCP unavailable — using keyword fallback. Start engine per CLAUDE.md Dev Services."
   Then fall back to:
   git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'

If results found: surface entry IDs and titles to the user. Ask which are
relevant before proceeding. These form the initial **garden context set** —
carry it forward into brainstorming and implementation.

If no results: proceed silently.

**Skip** if the garden is not configured or the work description has no
searchable domain keywords (e.g., a pure tooling or docs task).
```

- [ ] **Step 2: Update forage SEARCH operation**

In `~/claude/hortora/soredium/forage/SKILL.md`, find the SEARCH operation section. Replace the `git grep` command with:

```markdown
Call the `gardenSearch` MCP tool with the user's search query.

If `gardenSearch` is unavailable or returns an error, fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

- [ ] **Step 3: Commit soredium changes**

```bash
git -C ~/claude/hortora/soredium add work-start/SKILL.md forage/SKILL.md
git -C ~/claude/hortora/soredium commit -m "feat: integrate gardenSearch MCP tool into work-start and forage

Replace git grep with gardenSearch MCP tool call as primary search.
Inline git grep fallback when engine is unavailable or errors.

Refs Hortora/engine#21"
```

- [ ] **Step 4: Sync installed skills**

Run: `sync-local` to pull soredium changes into `~/.claude/skills/`.

---

### Task 5: Configure Claude Code MCP and verify end-to-end

**Files:**
- Modify: `~/.claude/settings.json` (add hortora-garden MCP server)

**Interfaces:**
- Consumes: Running engine with indexed garden entries
- Produces: Working MCP connection, verified search results

- [ ] **Step 1: Add MCP server config to Claude Code settings**

Add to `~/.claude/settings.json` under `mcpServers`:

```json
"hortora-garden": {
  "type": "sse",
  "url": "http://localhost:8080/mcp/sse"
}
```

- [ ] **Step 2: Start engine dependencies**

Start Qdrant: `docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant` (skip if already running)

Start Ollama and pull model: `ollama pull nomic-embed-text` (skip if already pulled)

Download ONNX models: `scripts/download-models.sh`

- [ ] **Step 3: Start the engine**

Run: `./mvnw quarkus:dev`

Wait for startup log: `MCP HTTP transport endpoints [streamable: ...]`

- [ ] **Step 4: Verify gardenSearch returns enriched output**

In a separate terminal, test the REST endpoint:

```bash
curl -s "http://localhost:8080/search?q=hibernate+lazy&limit=3" | python3 -m json.tool
```

Expected: JSON array of `SearchResult` objects with id, title, domain, type fields populated.

- [ ] **Step 5: Verify gardenReindex works**

Call via REST or MCP client — trigger reindex and verify status returns 0 entries, then wait for re-embedding:

```bash
curl -s "http://localhost:8080/search?q=ping&limit=1"
```

After ingestion cycle completes, re-check status.

- [ ] **Step 6: Live session demo**

Start a new Claude Code session. Verify:
1. `gardenSearch` appears as an available MCP tool
2. Run `/work` on an issue — work-start Step 3b calls `gardenSearch`
3. The LLM surfaces relevant entries with GE-IDs and metadata
4. Test a query that returns results via vector search but not via keyword grep (the semantic advantage)

- [ ] **Step 7: Commit MCP configuration**

```bash
git add ~/.claude/settings.json
git commit -m "chore: configure hortora-garden MCP server in Claude Code

SSE transport to engine at localhost:8080. Enables gardenSearch,
gardenStatus, and gardenReindex tools in all Claude Code sessions.

Refs #21"
```
