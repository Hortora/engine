# HyDE Query Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #40 — feat: wire HyDE query expansion for vocabulary gap scenarios
**Issue group:** #40

**Goal:** Wire neocortex's existing `rag-expansion` module into engine via classpath activation + platform ChatModel bridge, then benchmark against the 14-scenario suite.

**Architecture:** Zero new Java classes. Add three Maven dependencies (rag-expansion, platform-agent-langchain4j, platform-agent-claude), set config properties, add a test verifying decorator activation. Claude via Vertex AI provides the ChatModel for HyDE generation.

**Tech Stack:** Quarkus 3.36.x, casehub-neocortex-rag-expansion, casehub-platform-agent-langchain4j, casehub-platform-agent-claude, Claude CLI (Vertex AI)

## Global Constraints

- All dependencies use `<version>0.2-SNAPSHOT</version>` (hardcoded, no BOM)
- `@IfBuildProperty` is build-time — decorator is compiled into CDI graph or excluded entirely
- Expansion disabled by default; enabled in `%dev` profile only
- Vertex AI auth via env vars: `ANTHROPIC_VERTEX_PROJECT_ID`, `CLOUD_ML_REGION`, `CLAUDE_CODE_USE_VERTEX`
- `rag-testing` provides `InMemoryQueryExpander` for tests — no LLM dependency in tests
- Garden entries as gotcha context: GE-248ce7 (ChatModel baking), GE-337397 (Ollama @Default clash)

---

### Task 1: Build and install prerequisite dependencies

**Files:**
- No engine files changed — this task operates on neocortex and platform repos

**Interfaces:**
- Produces: `casehub-neocortex-rag-expansion:0.2-SNAPSHOT`, `casehub-platform-agent-langchain4j:0.2-SNAPSHOT`, `casehub-platform-agent-claude:0.2-SNAPSHOT` in local Maven repository

- [ ] **Step 1: Install platform parent and agent modules**

The platform agent modules depend on the platform parent POM. Build from the platform root to ensure all transitive dependencies resolve.

```bash
cd /Users/mdproctor/claude/casehub/platform
./mvnw install -pl agent-api,agent-langchain4j,agent-claude -am -DskipTests
```

Expected: BUILD SUCCESS. Three artifacts installed to `~/.m2/repository/io/casehub/`.

- [ ] **Step 2: Install neocortex rag-expansion module**

```bash
cd /Users/mdproctor/claude/casehub/neocortex
./mvnw install -pl rag-api,rag-expansion -am -DskipTests
```

Expected: BUILD SUCCESS. `casehub-neocortex-rag-expansion:0.2-SNAPSHOT` installed.

- [ ] **Step 3: Verify all three artifacts exist in local Maven repo**

```bash
ls ~/.m2/repository/io/casehub/casehub-neocortex-rag-expansion/0.2-SNAPSHOT/*.jar
ls ~/.m2/repository/io/casehub/casehub-platform-agent-langchain4j/0.2-SNAPSHOT/*.jar
ls ~/.m2/repository/io/casehub/casehub-platform-agent-claude/0.2-SNAPSHOT/*.jar
```

Expected: Each directory contains a `.jar` file.

---

### Task 2: Add Maven dependencies and configuration

**Files:**
- Modify: `pom.xml:84` (after existing `casehub-neocortex-rag` dependency)
- Modify: `src/main/resources/application.properties:14` (append)
- Modify: `src/test/resources/application.properties:10` (append)

**Interfaces:**
- Consumes: Task 1 artifacts in local Maven repo
- Produces: Engine compiles with rag-expansion + platform-agent on classpath; expansion disabled in tests

- [ ] **Step 1: Add three dependencies to pom.xml**

Insert after the `casehub-neocortex-rag` dependency block (after line 84):

```xml
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-neocortex-rag-expansion</artifactId>
            <version>0.2-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-agent-langchain4j</artifactId>
            <version>0.2-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-agent-claude</artifactId>
            <version>0.2-SNAPSHOT</version>
        </dependency>
```

- [ ] **Step 2: Add expansion config to application.properties**

Append to `src/main/resources/application.properties`:

```properties

# Query expansion — HyDE via Claude (platform AgentProvider)
# Disabled by default; enabled in dev profile for benchmarking
casehub.rag.expansion.enabled=false
%dev.casehub.rag.expansion.enabled=true
casehub.rag.expansion.mode=llm
casehub.rag.expansion.hypothetical-count=1
casehub.rag.expansion.prompt-template=Given the question below, write a short technical knowledge-base entry (3-5 sentences) about Java, Quarkus, or software development that would directly answer it. Write as if the entry comes from a curated developer knowledge garden. Do not include the question itself.\\n\\nQuestion: %s\\n\\nEntry:

# Claude agent — Vertex AI auth from env vars
# Required env: ANTHROPIC_VERTEX_PROJECT_ID, CLOUD_ML_REGION, CLAUDE_CODE_USE_VERTEX
%dev.casehub.platform.agent.claude.default-timeout=PT30S
%dev.casehub.platform.agent.claude.max-concurrent-sessions=2
```

- [ ] **Step 3: Add test index dependency for rag-expansion CDI discovery**

Append to `src/test/resources/application.properties`:

```properties

# Index rag-expansion for CDI bean discovery in tests
quarkus.index-dependency.casehub-rag-expansion.group-id=io.casehub
quarkus.index-dependency.casehub-rag-expansion.artifact-id=casehub-neocortex-rag-expansion
```

- [ ] **Step 4: Verify engine compiles**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS. No dependency conflicts.

- [ ] **Step 5: Run existing tests**

```bash
./mvnw test
```

Expected: All existing tests pass. The decorator is disabled (`casehub.rag.expansion.enabled=false` in default profile, test profile inherits default) so no behavioral change.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/resources/application.properties
git commit -m "feat: add rag-expansion + platform agent dependencies for HyDE

Wire casehub-neocortex-rag-expansion, casehub-platform-agent-langchain4j,
and casehub-platform-agent-claude. Expansion disabled by default; enabled
in %dev profile only.

Refs #40"
```

---

### Task 3: Add test verifying decorator activation

**Files:**
- Create: `src/test/java/io/hortora/garden/search/QueryExpansionTest.java`

**Interfaces:**
- Consumes: `InMemoryQueryExpander` from `rag-testing` (already on test classpath), `InMemoryCaseRetriever` (existing), `SearchResource.searchFor()`
- Produces: Test proving the decorator intercepts retrieval when enabled

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/hortora/garden/search/QueryExpansionTest.java`:

```java
package io.hortora.garden.search;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.testing.InMemoryEmbeddingIngestor;
import io.casehub.neocortex.rag.testing.InMemoryQueryExpander;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(QueryExpansionTest.ExpansionEnabledProfile.class)
class QueryExpansionTest {

    public static class ExpansionEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "casehub.rag.expansion.enabled", "true",
                "casehub.rag.expansion.mode", "llm"
            );
        }
    }

    @Inject SearchResource searchResource;
    @Inject InMemoryEmbeddingIngestor ingestor;
    @Inject InMemoryQueryExpander queryExpander;

    private static final CorpusRef CORPUS = new CorpusRef("hortora", "garden");

    @BeforeEach
    void setup() {
        queryExpander.clear();
        ingestor.deleteCorpus(CORPUS);
        ingestor.ingest(CORPUS, List.of(
            new ChunkInput(
                "CDI producer methods for configuration.",
                "jvm/ge-test-cdi-producer.md",
                Map.of("title", "CDI producer pattern",
                       "domain", "jvm", "type", "technique", "score", "7"),
                Map.of("tags", List.of("cdi", "quarkus")))
        ));
    }

    @Test
    void queryExpansionDecoratorIsActive() {
        searchResource.searchFor("CDI configuration", null, null, null, null);

        assertThat(queryExpander.expandedQueries())
            .as("QueryExpandingCaseRetriever should have intercepted the retrieval")
            .isNotEmpty();
    }

    @Test
    void expandedQueryContainsOriginalText() {
        searchResource.searchFor("CDI configuration", null, null, null, null);

        assertThat(queryExpander.expandedQueries().getFirst().text())
            .isEqualTo("CDI configuration");
        assertThat(queryExpander.expandedQueries().getFirst().expandedText())
            .isNotNull()
            .contains("hypothetical:");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -pl . -Dtest=QueryExpansionTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — either CDI bean resolution error (if `InMemoryQueryExpander` isn't discovered) or assertion failure. This confirms the decorator wiring needs the `@IfBuildProperty` config to activate.

- [ ] **Step 3: Fix any CDI discovery issues**

If `InMemoryQueryExpander` isn't discovered, the `quarkus.index-dependency` entry from Task 2 Step 3 should handle it. If there's a conflict between `InMemoryQueryExpander` (`@Alternative @Priority(1)`) and `LlmQueryExpander` (`@IfBuildProperty`), the `@Alternative` wins in tests.

If `InMemoryQueryExpander` cannot be injected because the build-time property excluded it, add to the test profile overrides:

```java
return Map.of(
    "casehub.rag.expansion.enabled", "true",
    "casehub.rag.expansion.mode", "llm"
);
```

These are already present in the profile above. The `@Alternative @Priority(1)` on `InMemoryQueryExpander` should take precedence over `LlmQueryExpander` for the `QueryExpander` SPI.

- [ ] **Step 4: Run tests to verify they pass**

```bash
./mvnw test -pl . -Dtest=QueryExpansionTest
```

Expected: Both tests PASS. The decorator activates and `InMemoryQueryExpander` handles expansion deterministically.

- [ ] **Step 5: Run full test suite to check for regressions**

```bash
./mvnw test
```

Expected: All tests pass. The new `QueryExpansionTest` uses a separate `TestProfile` so it doesn't affect other tests.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/io/hortora/garden/search/QueryExpansionTest.java
git commit -m "test: verify HyDE query expansion decorator activates

QueryExpansionTest uses a QuarkusTestProfile to enable the
@IfBuildProperty decorator and InMemoryQueryExpander to verify
the decorator intercepts CaseRetriever.retrieve() calls.

Refs #40"
```

---

### Task 4: Run HyDE benchmark and capture results

**Files:**
- No new source files — this task uses existing benchmark tooling
- Create: `scripts/benchmark/results/benchmark-hyde-*.json` (output)
- Create: `docs/comparison/hyde-benchmark.md` (analysis)

**Interfaces:**
- Consumes: Running engine in dev mode (`mvn quarkus:dev`), benchmark scripts in `scripts/benchmark/`
- Produces: Benchmark results JSON + analysis document comparing HyDE vs baseline

**Prerequisites:** Engine running in dev mode with Qdrant, BGE-M3 model, and Vertex AI env vars.

- [ ] **Step 1: Start engine in dev mode with expansion enabled**

Ensure env vars are set:
```bash
export ANTHROPIC_VERTEX_PROJECT_ID=itpc-gcp-cp-pe-eng-claude
export CLAUDE_CODE_USE_VERTEX=1
export CLOUD_ML_REGION=us-east5
```

Start engine:
```bash
./mvnw quarkus:dev
```

Verify expansion is active — check startup logs for `QueryExpandingCaseRetriever` bean registration.

- [ ] **Step 2: Run benchmark with HyDE enabled**

```bash
python3 scripts/benchmark/run_queries.py --tag hyde-enabled
```

Expected: Benchmark runs all 14 scenarios (28 queries). Each query will take 3-10 seconds due to the Claude subprocess. Total runtime: ~2-5 minutes.

Output: `scripts/benchmark/results/benchmark-hyde-enabled-*.json`

- [ ] **Step 3: Run baseline benchmark (expansion disabled)**

Temporarily override the config to disable expansion. Stop dev mode, change `%dev.casehub.rag.expansion.enabled=true` to `false` in `application.properties`, restart dev mode, and re-run:

```bash
python3 scripts/benchmark/run_queries.py --tag hyde-disabled
```

Output: `scripts/benchmark/results/benchmark-hyde-disabled-*.json`

Restore `%dev.casehub.rag.expansion.enabled=true` after.

- [ ] **Step 4: Analyse results**

Write an analysis script or use the existing `analyze.py` pattern. Compare:
- Per-scenario precision (relevant entries in top-K)
- VOCABULARY_GAP scenario breakdown
- Latency delta (HyDE vs baseline p50, p95)

Apply success criteria from the spec:
- ≥ 5pp gain on any VOCABULARY_GAP scenario → supports adoption
- ≤ 3pp regression on non-VOCABULARY_GAP → acceptable
- > 3pp regression on non-VOCABULARY_GAP → investigate
- < 5pp everywhere → HyDE not justified

- [ ] **Step 5: Write benchmark report**

Create `docs/comparison/hyde-benchmark.md` with:
- Methodology (what was tested, config, model)
- Results table (per-scenario precision, baseline vs HyDE)
- Latency analysis
- Conclusion: adopt, reject, or investigate further

- [ ] **Step 6: Commit results and report**

```bash
git add scripts/benchmark/results/benchmark-hyde-*.json docs/comparison/hyde-benchmark.md
git commit -m "feat: HyDE query expansion benchmark results

[Fill in: N-scenario benchmark comparing BGE-M3 four-signal
retrieval with and without HyDE query expansion via Claude.
Summary: {adopt/reject/investigate}]

Refs #40"
```

---

## Self-Review

**Spec coverage:**
- ✅ Problem statement — covered by context
- ✅ Architecture — zero new Java classes, classpath activation
- ✅ Dependencies — Task 1 (install), Task 2 (add to pom.xml)
- ✅ Configuration — Task 2 (application.properties)
- ✅ `@IfBuildProperty` build-time semantics — Task 2 (default off, `%dev` on)
- ✅ Testing — Task 3 (decorator activation test with QuarkusTestProfile)
- ✅ Benchmark — Task 4 (run, compare, analyse)
- ✅ Success criteria — Task 4 Step 4 references spec thresholds
- ✅ Prerequisites — Task 1 handles `mvn install` of unpublished artifacts

**Placeholder scan:** No TBDs, TODOs, or "similar to Task N" references. Task 4 Step 6 has a fill-in bracket for the commit message — this is intentional (result-dependent).

**Type consistency:** `InMemoryQueryExpander.expandedQueries()`, `InMemoryQueryExpander.clear()`, `SearchResource.searchFor()` — all match the actual API signatures verified via IntelliJ.
