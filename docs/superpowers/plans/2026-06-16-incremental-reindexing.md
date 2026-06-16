# Incremental Re-Indexing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace startup-only full re-index with cursor-based incremental indexing and live filesystem watching via `casehub-corpus` infrastructure.

**Architecture:** Consume `casehub-corpus-api` + `casehub-corpus` (Hortora-eligible) for change detection. Engine-local `GardenIngestionService` handles the cursor-based ingest loop with `ReentrantLock` concurrency control. Direct `io.qdrant:client` for all Qdrant operations. `FlatChangeSource` (implements `WatchableChangeSource`) provides push-based filesystem watching via `directory-watcher` with 500ms debounce.

**Tech Stack:** Java 25, Quarkus 3.36.x, `casehub-corpus-api` + `casehub-corpus`, `io.qdrant:client` (gRPC), `quarkus-langchain4j-ollama` (EmbeddingModel), Testcontainers (Qdrant test lifecycle)

**Spec:** `docs/superpowers/specs/2026-06-16-incremental-reindexing-design.md` (revision 4)

---

## File Structure

**Create:**
| File | Responsibility |
|------|---------------|
| `src/main/java/io/hortora/garden/index/ExtractionResult.java` | Content + metadata record from frontmatter extraction |
| `src/main/java/io/hortora/garden/index/GardenMetadataExtractor.java` | YAML frontmatter parser — returns title+body as content |
| `src/main/java/io/hortora/garden/index/FileCursorStore.java` | File-backed cursor persistence at `_state/garden.cursor` |
| `src/main/java/io/hortora/garden/index/GardenIngestionService.java` | Cursor-based ingest loop with ReentrantLock concurrency |
| `src/main/java/io/hortora/garden/config/QdrantConfig.java` | Config mapping for `hortora.qdrant.*` |
| `src/main/java/io/hortora/garden/index/QdrantClientProducer.java` | CDI producer for QdrantClient |
| `src/test/java/io/hortora/garden/index/GardenMetadataExtractorTest.java` | Unit tests |
| `src/test/java/io/hortora/garden/index/FileCursorStoreTest.java` | Unit tests |
| `src/test/java/io/hortora/garden/index/GardenIngestionServiceTest.java` | Unit tests with mock QdrantClient |
| `src/test/java/io/hortora/garden/test/QdrantResource.java` | Testcontainers lifecycle manager for @QuarkusTest |

**Modify:**
| File | Change |
|------|--------|
| `pom.xml` | Add corpus-api, corpus, io.qdrant:client, testcontainers; remove quarkus-langchain4j-qdrant |
| `src/main/java/io/hortora/garden/index/GardenIndexer.java` | Refactor to startup wiring + lifecycle (@PreDestroy) |
| `src/main/java/io/hortora/garden/search/SearchResource.java` | Replace EmbeddingStore with QdrantClient QueryPoints |
| `src/main/java/io/hortora/garden/mcp/GardenMcpTools.java` | Replace GardenIndexer injection with QdrantClient |
| `src/main/resources/application.properties` | Add hortora.qdrant.* config |
| `src/test/resources/application.properties` | Add Qdrant test config |
| `src/test/java/io/hortora/garden/search/SearchResourceTest.java` | Use Testcontainers Qdrant |

**Remove:**
| File | Reason |
|------|--------|
| `src/main/java/io/hortora/garden/entry/GardenEntry.java` | Replaced by ExtractionResult |
| `src/main/java/io/hortora/garden/entry/GardenEntryParser.java` | Replaced by GardenMetadataExtractor |
| `src/test/java/io/hortora/garden/entry/GardenEntryParserTest.java` | Replaced by GardenMetadataExtractorTest |
| `src/test/java/io/hortora/garden/test/TestEmbeddingStore.java` | Replaced by Testcontainers Qdrant |

---

### Task 1: Dependencies and Config

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/io/hortora/garden/config/QdrantConfig.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`

- [ ] **Step 1: Update pom.xml — add new dependencies**

Add to `<dependencies>`:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-corpus-api</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-corpus</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.qdrant</groupId>
    <artifactId>client</artifactId>
    <version>1.18.1</version>
</dependency>
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

- [ ] **Step 2: Update pom.xml — remove old dependency**

Remove:
```xml
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-qdrant</artifactId>
</dependency>
```

- [ ] **Step 3: Create QdrantConfig**

```java
package io.hortora.garden.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "hortora.qdrant")
public interface QdrantConfig {

    @WithDefault("localhost")
    String host();

    @WithDefault("6334")
    int port();

    @WithDefault("garden")
    String collection();
}
```

- [ ] **Step 4: Update application.properties**

Replace:
```properties
quarkus.langchain4j.qdrant.collection.name=garden
```

With:
```properties
hortora.qdrant.host=localhost
hortora.qdrant.port=6334
hortora.qdrant.collection=garden
```

- [ ] **Step 5: Update test application.properties**

Replace:
```properties
quarkus.langchain4j.qdrant.devservices.enabled=false
```

With:
```properties
hortora.qdrant.collection=garden-test
```

(Qdrant host/port will be set dynamically by the Testcontainers lifecycle manager in Task 9.)

- [ ] **Step 6: Verify project compiles**

Run: `./mvnw compile -q`

Expected: Compilation succeeds. Tests may fail (TestEmbeddingStore references EmbeddingStore which is no longer on classpath from the Qdrant extension) — that's expected and fixed in Task 9.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/io/hortora/garden/config/QdrantConfig.java src/main/resources/application.properties src/test/resources/application.properties
git commit -m "chore: add corpus-api, corpus, qdrant client deps; remove langchain4j-qdrant  Refs #7"
```

---

### Task 2: GardenMetadataExtractor (TDD)

**Files:**
- Create: `src/main/java/io/hortora/garden/index/ExtractionResult.java`
- Create: `src/main/java/io/hortora/garden/index/GardenMetadataExtractor.java`
- Create: `src/test/java/io/hortora/garden/index/GardenMetadataExtractorTest.java`

- [ ] **Step 1: Create ExtractionResult record**

```java
package io.hortora.garden.index;

import java.util.Map;

public record ExtractionResult(String content, Map<String, String> metadata) {
    public ExtractionResult {
        if (content == null)
            throw new IllegalArgumentException("content must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
```

- [ ] **Step 2: Write failing tests**

```java
package io.hortora.garden.index;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GardenMetadataExtractorTest {

    private final GardenMetadataExtractor extractor = new GardenMetadataExtractor();

    @Test
    void parsesYamlFrontmatterAndConcatenatesTitleWithBody() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-hibernate-lazy.md"));

        ExtractionResult result = extractor.extract("jvm/ge-test.md", content);

        assertThat(result.content())
                .startsWith("Hibernate lazy loading fails outside transaction\n\n")
                .contains("LazyInitializationException");
        assertThat(result.metadata())
                .containsEntry("title", "Hibernate lazy loading fails outside transaction")
                .containsEntry("domain", "jvm")
                .containsEntry("type", "gotcha")
                .containsEntry("score", "8");
    }

    @Test
    void nonMdFileReturnsEmptyContent() {
        byte[] content = "some image data".getBytes(StandardCharsets.UTF_8);

        ExtractionResult result = extractor.extract("images/diagram.png", content);

        assertThat(result.content()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void mdFileWithoutFrontmatterReturnsEmptyContent() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-no-frontmatter.md"));

        ExtractionResult result = extractor.extract("notes/readme.md", content);

        assertThat(result.content()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void extractsTagsAsCommaSeparatedString() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-hibernate-lazy.md"));

        ExtractionResult result = extractor.extract("jvm/ge-test.md", content);

        assertThat(result.metadata()).containsEntry("tags", "hibernate, lazy-loading, transactions");
    }

    @Test
    void extractsSubmittedDate() throws Exception {
        byte[] content = Files.readAllBytes(
                Path.of("src/test/resources/fixtures/ge-test-hibernate-lazy.md"));

        ExtractionResult result = extractor.extract("jvm/ge-test.md", content);

        assertThat(result.metadata()).containsEntry("submitted", "2026-04-15");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=GardenMetadataExtractorTest -q`

Expected: FAIL — `GardenMetadataExtractor` class does not exist.

- [ ] **Step 4: Implement GardenMetadataExtractor**

```java
package io.hortora.garden.index;

import jakarta.enterprise.context.ApplicationScoped;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GardenMetadataExtractor {

    public ExtractionResult extract(String path, byte[] content) {
        if (!path.endsWith(".md")) {
            return new ExtractionResult("", Map.of());
        }

        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.startsWith("---")) {
            return new ExtractionResult("", Map.of());
        }

        int closingIndex = text.indexOf("\n---", 3);
        if (closingIndex < 0) {
            return new ExtractionResult("", Map.of());
        }

        String frontmatterBlock = text.substring(4, closingIndex).trim();
        String body = text.substring(closingIndex + 4).trim();

        Map<String, Object> fm = new Yaml().load(frontmatterBlock);
        if (fm == null) {
            return new ExtractionResult("", Map.of());
        }

        String title = fm.get("title") instanceof String s ? s : null;
        String combinedContent = (title != null ? title + "\n\n" : "") + body;

        Map<String, String> metadata = new LinkedHashMap<>();
        if (title != null) metadata.put("title", title);
        if (fm.get("domain") instanceof String s) metadata.put("domain", s);
        if (fm.get("type") instanceof String s) metadata.put("type", s);
        if (fm.get("score") instanceof Number n) metadata.put("score", String.valueOf(n.intValue()));
        if (fm.get("tags") instanceof List<?> tags) {
            metadata.put("tags", String.join(", ", tags.stream().map(Object::toString).toList()));
        }
        if (fm.get("submitted") != null) {
            metadata.put("submitted", String.valueOf(fm.get("submitted")));
        }

        return new ExtractionResult(combinedContent, metadata);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=GardenMetadataExtractorTest -q`

Expected: All 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/hortora/garden/index/ExtractionResult.java src/main/java/io/hortora/garden/index/GardenMetadataExtractor.java src/test/java/io/hortora/garden/index/GardenMetadataExtractorTest.java
git commit -m "feat: GardenMetadataExtractor with title+body concatenation  Refs #7"
```

---

### Task 3: FileCursorStore (TDD)

**Files:**
- Create: `src/main/java/io/hortora/garden/index/FileCursorStore.java`
- Create: `src/test/java/io/hortora/garden/index/FileCursorStoreTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.hortora.garden.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileCursorStoreTest {

    @TempDir Path tempDir;

    @Test
    void loadReturnsEmptyWhenNoFileExists() {
        var store = new FileCursorStore(tempDir);

        assertThat(store.load()).isEmpty();
    }

    @Test
    void saveAndLoadRoundTrip() {
        var store = new FileCursorStore(tempDir);
        String cursor = "{\"jvm/GE-0144.md\":1718500000000}";

        store.save(cursor);

        assertThat(store.load()).hasValue(cursor);
    }

    @Test
    void saveCreatesStateDirectory() {
        var store = new FileCursorStore(tempDir);

        store.save("{}");

        assertThat(Files.isDirectory(tempDir.resolve("_state"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("_state/garden.cursor"))).isTrue();
    }

    @Test
    void saveOverwritesPreviousCursor() {
        var store = new FileCursorStore(tempDir);

        store.save("{\"a.md\":1}");
        store.save("{\"a.md\":2}");

        assertThat(store.load()).hasValue("{\"a.md\":2}");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=FileCursorStoreTest -q`

Expected: FAIL — `FileCursorStore` class does not exist.

- [ ] **Step 3: Implement FileCursorStore**

```java
package io.hortora.garden.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class FileCursorStore {

    private final Path cursorFile;

    public FileCursorStore(Path gardenPath) {
        this.cursorFile = gardenPath.resolve("_state/garden.cursor");
    }

    public Optional<String> load() {
        if (!Files.exists(cursorFile)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(cursorFile).trim();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read cursor: " + cursorFile, e);
        }
    }

    public void save(String cursor) {
        try {
            Files.createDirectories(cursorFile.getParent());
            Files.writeString(cursorFile, cursor);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write cursor: " + cursorFile, e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=FileCursorStoreTest -q`

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/hortora/garden/index/FileCursorStore.java src/test/java/io/hortora/garden/index/FileCursorStoreTest.java
git commit -m "feat: FileCursorStore — file-backed cursor at _state/garden.cursor  Refs #7"
```

---

### Task 4: QdrantClientProducer

**Files:**
- Create: `src/main/java/io/hortora/garden/index/QdrantClientProducer.java`

- [ ] **Step 1: Implement QdrantClientProducer**

```java
package io.hortora.garden.index;

import io.hortora.garden.config.QdrantConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class QdrantClientProducer {

    @Inject
    QdrantConfig config;

    @Produces
    @ApplicationScoped
    QdrantClient qdrantClient() {
        var grpcClient = QdrantGrpcClient.newBuilder(config.host(), config.port(), false).build();
        return new QdrantClient(grpcClient);
    }

    void close(@Disposes QdrantClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile -q`

Expected: Compiles.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/hortora/garden/index/QdrantClientProducer.java
git commit -m "feat: QdrantClientProducer — CDI producer for direct Qdrant gRPC client  Refs #7"
```

---

### Task 5: GardenIngestionService (TDD)

**Files:**
- Create: `src/main/java/io/hortora/garden/index/GardenIngestionService.java`
- Create: `src/test/java/io/hortora/garden/index/GardenIngestionServiceTest.java`

This is the core component. TDD with mock `QdrantClient` and `EmbeddingModel`.

- [ ] **Step 1: Write failing tests**

```java
package io.hortora.garden.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeType;
import io.casehub.corpus.ChangedEntry;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GardenIngestionServiceTest {

    @TempDir Path gardenDir;
    private QdrantClient qdrantClient;
    private EmbeddingModel embeddingModel;
    private GardenMetadataExtractor extractor;
    private FileCursorStore cursorStore;
    private GardenIngestionService service;

    @BeforeEach
    void setUp() {
        qdrantClient = mock(QdrantClient.class);
        embeddingModel = mock(EmbeddingModel.class);
        extractor = new GardenMetadataExtractor();
        cursorStore = new FileCursorStore(gardenDir);

        when(qdrantClient.upsertAsync(anyString(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(qdrantClient.deleteAsync(anyString(), any(io.qdrant.client.grpc.Common.Filter.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        float[] vector = new float[768];
        Embedding embedding = Embedding.from(vector);
        when(embeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(embedding));
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(embedding)));

        service = new GardenIngestionService(
                qdrantClient, embeddingModel, extractor, cursorStore,
                gardenDir, "garden-test");
    }

    @Test
    void fullScanIngestsAllEntriesAndSavesCursor() throws Exception {
        writeFixture("jvm/test.md", """
                ---
                title: "Test entry"
                domain: jvm
                type: gotcha
                score: 8
                tags: [java]
                submitted: "2026-06-16"
                ---
                Body text here.
                """);

        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/test.md", ChangeType.ADDED)),
                "{\"jvm/test.md\":1718500000000}");

        service.ingest(changes);

        verify(qdrantClient).upsertAsync(eq("garden-test"), anyList(), any());
        assertThat(cursorStore.load()).isPresent();
    }

    @Test
    void deletionUsesPayloadFilterOnSourceDocumentId() throws Exception {
        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/old.md", ChangeType.DELETED)),
                "{}");

        service.ingest(changes);

        verify(qdrantClient).deleteAsync(eq("garden-test"), any(io.qdrant.client.grpc.Common.Filter.class));
        assertThat(cursorStore.load()).isPresent();
    }

    @Test
    void extractionFailureSkipsFileButCursorAdvances() throws Exception {
        // No file on disk — read will fail
        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/missing.md", ChangeType.ADDED)),
                "{\"jvm/missing.md\":1}");

        service.ingest(changes);

        verify(qdrantClient, never()).upsertAsync(anyString(), anyList(), any());
        assertThat(cursorStore.load()).isPresent();
    }

    @Test
    void infrastructureFailureDoesNotAdvanceCursor() throws Exception {
        writeFixture("jvm/test.md", """
                ---
                title: "Test"
                domain: jvm
                type: gotcha
                score: 5
                tags: []
                submitted: "2026-06-16"
                ---
                Body.
                """);

        when(qdrantClient.upsertAsync(anyString(), anyList(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Qdrant unreachable")));

        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("jvm/test.md", ChangeType.ADDED)),
                "{\"jvm/test.md\":1}");

        service.ingest(changes);

        assertThat(cursorStore.load()).isEmpty();
    }

    @Test
    void emptyChangeSetSavesCursorWithoutQdrantCalls() {
        ChangeSet changes = new ChangeSet(List.of(), "{\"a.md\":1}");

        service.ingest(changes);

        verify(qdrantClient, never()).upsertAsync(anyString(), anyList(), any());
        assertThat(cursorStore.load()).hasValue("{\"a.md\":1}");
    }

    @Test
    void nonMdFilesAreSkipped() throws Exception {
        writeFixture("README", "not a markdown file");

        ChangeSet changes = new ChangeSet(
                List.of(new ChangedEntry("README", ChangeType.ADDED)),
                "{}");

        service.ingest(changes);

        verify(qdrantClient, never()).upsertAsync(anyString(), anyList(), any());
    }

    private void writeFixture(String relativePath, String content) throws Exception {
        Path file = gardenDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
```

Note: The exact mock setup for `QdrantClient.upsertAsync()` may need adjustment based on the actual overload signatures — the Qdrant client has multiple `upsertAsync` overloads. Adjust argument matchers during implementation to match the overload used.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest=GardenIngestionServiceTest -q`

Expected: FAIL — `GardenIngestionService` class does not exist.

Note: Mockito dependency may need to be added to pom.xml if not already available. Add `quarkus-junit5-mockito` or `mockito-core` test-scoped.

- [ ] **Step 3: Implement GardenIngestionService**

```java
package io.hortora.garden.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.ChangeType;
import io.casehub.corpus.ChangedEntry;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vector;
import io.quarkus.logging.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

public class GardenIngestionService {

    static final UUID NAMESPACE = UUID.nameUUIDFromBytes("hortora.garden".getBytes(StandardCharsets.UTF_8));
    private static final String DENSE_VECTOR_NAME = "dense";

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final GardenMetadataExtractor extractor;
    private final FileCursorStore cursorStore;
    private final Path gardenPath;
    private final String collectionName;
    private final ReentrantLock lock = new ReentrantLock();

    public GardenIngestionService(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            GardenMetadataExtractor extractor,
            FileCursorStore cursorStore,
            Path gardenPath,
            String collectionName) {
        this.qdrantClient = qdrantClient;
        this.embeddingModel = embeddingModel;
        this.extractor = extractor;
        this.cursorStore = cursorStore;
        this.gardenPath = gardenPath;
        this.collectionName = collectionName;
    }

    public void ingest(ChangeSet changes) {
        if (!lock.tryLock()) {
            Log.debug("Skipping ingest — already in progress");
            return;
        }
        try {
            doIngest(changes);
        } finally {
            lock.unlock();
        }
    }

    public void onChanges(List<ChangedEntry> entries) {
        if (!lock.tryLock()) {
            Log.debug("Skipping watcher batch — ingest in progress");
            return;
        }
        try {
            doProcessEntries(entries);
            cursorStore.save(buildMtimeCursor(entries));
        } catch (Exception e) {
            Log.errorf("Infrastructure failure during watcher batch: %s", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private void doIngest(ChangeSet changes) {
        if (changes.entries().isEmpty()) {
            cursorStore.save(changes.newCursor());
            return;
        }

        try {
            doProcessEntries(changes.entries());
            cursorStore.save(changes.newCursor());
        } catch (Exception e) {
            Log.errorf("Infrastructure failure during ingest — cursor NOT advanced: %s", e.getMessage());
        }
    }

    private void doProcessEntries(List<ChangedEntry> entries) {
        // Phase 1: deletions
        for (ChangedEntry entry : entries) {
            if (entry.type() == ChangeType.DELETED || entry.type() == ChangeType.MODIFIED) {
                deleteDocument(entry.path());
            }
        }

        // Phase 2: additions/modifications
        List<PointStruct> points = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        List<ChangedEntry> toEmbed = new ArrayList<>();

        for (ChangedEntry entry : entries) {
            if (entry.type() == ChangeType.ADDED || entry.type() == ChangeType.MODIFIED) {
                try {
                    byte[] content = Files.readAllBytes(gardenPath.resolve(entry.path()));
                    ExtractionResult result = extractor.extract(entry.path(), content);
                    if (result.content().isEmpty()) continue;

                    segments.add(TextSegment.from(result.content()));
                    toEmbed.add(entry);
                } catch (IOException e) {
                    Log.warnf("Extraction failure — skipping %s: %s", entry.path(), e.getMessage());
                }
            }
        }

        if (segments.isEmpty()) return;

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        for (int i = 0; i < toEmbed.size(); i++) {
            ChangedEntry entry = toEmbed.get(i);
            byte[] content;
            try {
                content = Files.readAllBytes(gardenPath.resolve(entry.path()));
            } catch (IOException e) {
                continue;
            }
            ExtractionResult result = extractor.extract(entry.path(), content);
            points.add(buildPoint(entry.path(), result, embeddings.get(i)));
        }

        if (!points.isEmpty()) {
            upsertPoints(points);
        }
    }

    private void deleteDocument(String path) {
        Filter filter = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("sourceDocumentId", path))
                .build();
        try {
            qdrantClient.deleteAsync(collectionName, filter).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during delete", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Delete failed for " + path, e.getCause());
        }
    }

    private void upsertPoints(List<PointStruct> points) {
        try {
            qdrantClient.upsertAsync(collectionName, points, null).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during upsert", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Upsert failed", e.getCause());
        }
    }

    private PointStruct buildPoint(String path, ExtractionResult result, Embedding embedding) {
        UUID pointId = UUID.nameUUIDFromBytes(
                (NAMESPACE + "/" + path).getBytes(StandardCharsets.UTF_8));

        Vector denseVector = VectorFactory.vector(embedding.vectorAsList());

        var payload = new java.util.HashMap<String, io.qdrant.client.grpc.JsonWithInt.Value>();
        payload.put("content", ValueFactory.value(result.content()));
        payload.put("sourceDocumentId", ValueFactory.value(path));
        for (Map.Entry<String, String> meta : result.metadata().entrySet()) {
            payload.put(meta.getKey(), ValueFactory.value(meta.getValue()));
        }

        return PointStruct.newBuilder()
                .setId(PointIdFactory.id(pointId))
                .setVectors(VectorsFactory.namedVectors(Map.of(DENSE_VECTOR_NAME, denseVector)))
                .putAllPayload(payload)
                .build();
    }

    private String buildMtimeCursor(List<ChangedEntry> entries) {
        // Simplified cursor for watcher batches — the FlatChangeSource
        // maintains the authoritative cursor via currentCursor()
        return "{}";
    }
}
```

Note: The `doProcessEntries` reads files twice (once for extraction, once for point building). Refactor during implementation to read once and cache the result. The double-read is shown here for clarity of the two-phase flow.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=GardenIngestionServiceTest -q`

Expected: All 6 tests PASS. Adjust mock setup if Qdrant client overload signatures differ.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/hortora/garden/index/GardenIngestionService.java src/test/java/io/hortora/garden/index/GardenIngestionServiceTest.java
git commit -m "feat: GardenIngestionService — cursor-based ingest with concurrency control  Refs #7"
```

---

### Task 6: GardenIndexer Refactoring

**Files:**
- Modify: `src/main/java/io/hortora/garden/index/GardenIndexer.java`

- [ ] **Step 1: Rewrite GardenIndexer**

Replace the entire file:

```java
package io.hortora.garden.index;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.corpus.ChangeSet;
import io.casehub.corpus.WatchableChangeSource;
import io.casehub.corpus.zip.FlatCorpusStore;
import io.casehub.corpus.zip.FlatChangeSource;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.config.QdrantConfig;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@ApplicationScoped
public class GardenIndexer {

    private static final String DENSE_VECTOR_NAME = "dense";
    private static final int DENSE_DIMENSIONS = 768;

    @Inject GardenConfig gardenConfig;
    @Inject QdrantConfig qdrantConfig;
    @Inject QdrantClient qdrantClient;
    @Inject EmbeddingModel embeddingModel;
    @Inject GardenMetadataExtractor extractor;

    private WatchableChangeSource changeSource;
    private GardenIngestionService ingestionService;

    void onStart(@Observes StartupEvent event) {
        Path gardenPath = gardenConfig.path();
        String collection = qdrantConfig.collection();

        ensureCollection(collection);

        FileCursorStore cursorStore = new FileCursorStore(gardenPath);
        FlatCorpusStore corpusStore = new FlatCorpusStore(gardenPath);
        FlatChangeSource flatChangeSource = new FlatChangeSource(corpusStore, gardenPath);

        ingestionService = new GardenIngestionService(
                qdrantClient, embeddingModel, extractor, cursorStore,
                gardenPath, collection);

        // Initial sync: fullScan or changesSince
        Optional<String> cursor = cursorStore.load();
        ChangeSet changes = cursor.isEmpty()
                ? flatChangeSource.fullScan()
                : flatChangeSource.changesSince(cursor.get());

        ingestionService.ingest(changes);

        // Start filesystem watcher
        try {
            flatChangeSource.watch(entries -> ingestionService.onChanges(entries));
            this.changeSource = flatChangeSource;
            Log.infof("Garden watcher started on %s", gardenPath);
        } catch (Exception e) {
            Log.errorf("Failed to start garden watcher: %s", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (changeSource != null) {
            try {
                changeSource.close();
                Log.info("Garden watcher stopped");
            } catch (Exception e) {
                Log.warnf("Error closing garden watcher: %s", e.getMessage());
            }
        }
    }

    private void ensureCollection(String collection) {
        try {
            if (qdrantClient.collectionExistsAsync(collection).get()) {
                return;
            }

            VectorParams denseParams = VectorParams.newBuilder()
                    .setSize(DENSE_DIMENSIONS)
                    .setDistance(Distance.Cosine)
                    .build();

            CreateCollection createRequest = CreateCollection.newBuilder()
                    .setCollectionName(collection)
                    .setVectorsConfig(VectorsConfig.newBuilder()
                            .setParamsMap(VectorParamsMap.newBuilder()
                                    .putMap(DENSE_VECTOR_NAME, denseParams)
                                    .build())
                            .build())
                    .build();

            qdrantClient.createCollectionAsync(createRequest).get();
            Log.infof("Created Qdrant collection: %s", collection);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during collection creation", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create collection: " + collection, e.getCause());
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile -q`

Expected: Compiles. Tests not run yet (existing tests depend on removed infrastructure).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/hortora/garden/index/GardenIndexer.java
git commit -m "refactor: GardenIndexer — startup wiring + lifecycle for incremental indexing  Refs #7"
```

---

### Task 7: SearchResource Refactoring

**Files:**
- Modify: `src/main/java/io/hortora/garden/search/SearchResource.java`

- [ ] **Step 1: Rewrite SearchResource to use QdrantClient**

Replace the `searchOwnQdrant` method and field declarations. The federation logic (`doSearch`, `parseVisited`, chain walker) stays. Only the local Qdrant query changes.

Replace fields:
```java
// Remove:
// @Inject EmbeddingModel embeddingModel;
// @Inject EmbeddingStore<TextSegment> embeddingStore;

// Add:
@Inject EmbeddingModel embeddingModel;
@Inject QdrantClient qdrantClient;
@Inject QdrantConfig qdrantConfig;
```

Replace `searchOwnQdrant`:
```java
private List<SearchResult> searchOwnQdrant(String query, List<String> domains, int maxResults) {
    Embedding queryEmbedding = embeddingModel.embed(query).content();

    QueryPoints.Builder queryBuilder = QueryPoints.newBuilder()
            .setCollectionName(qdrantConfig.collection())
            .setQuery(QueryFactory.nearest(queryEmbedding.vectorAsList()))
            .setUsing(DENSE_VECTOR_NAME)
            .setLimit(maxResults)
            .setWithPayload(WithPayloadSelectorFactory.enable(true));

    Filter domainFilter = buildDomainFilter(domains);
    if (domainFilter != null) {
        queryBuilder.setFilter(domainFilter);
    }

    List<ScoredPoint> scored;
    try {
        scored = qdrantClient.queryAsync(queryBuilder.build()).get();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted during search", e);
    } catch (ExecutionException e) {
        throw new RuntimeException("Search failed", e.getCause());
    }

    return scored.stream()
            .map(point -> {
                var payload = point.getPayloadMap();
                return new SearchResult(
                        extractString(payload, "sourceDocumentId"),
                        extractString(payload, "title"),
                        extractString(payload, "domain"),
                        extractString(payload, "type"),
                        parseScore(extractString(payload, "score")),
                        extractString(payload, "content"),
                        point.getScore(),
                        federationConfig.gardenId(),
                        federationConfig.idPrefix());
            })
            .toList();
}
```

Replace `buildDomainFilter` to use Qdrant `ConditionFactory`:
```java
private static Filter buildDomainFilter(List<String> domains) {
    if (domains == null || domains.isEmpty()) return null;
    List<String> nonBlank = domains.stream().filter(d -> d != null && !d.isBlank()).toList();
    if (nonBlank.isEmpty()) return null;
    if (nonBlank.size() == 1) {
        return Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("domain", nonBlank.get(0)))
                .build();
    }
    // Multiple domains — OR semantics via individual must-not-exclude
    Filter.Builder filterBuilder = Filter.newBuilder();
    for (String domain : nonBlank) {
        filterBuilder.addShould(ConditionFactory.matchKeyword("domain", domain));
    }
    return filterBuilder.build();
}
```

Add helper:
```java
private static String extractString(Map<String, Value> payload, String key) {
    Value v = payload.get(key);
    return v != null && v.hasStringValue() ? v.getStringValue() : "";
}

private static int parseScore(String s) {
    try { return s != null && !s.isEmpty() ? Integer.parseInt(s) : 0; }
    catch (NumberFormatException e) { return 0; }
}
```

Add required imports for `QueryPoints`, `QueryFactory`, `ScoredPoint`, `WithPayloadSelectorFactory`, `Filter`, `ConditionFactory`, `Value`. Remove `EmbeddingSearchRequest`, `EmbeddingStore`, `IsEqualTo`, `IsIn` imports.

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile -q`

Expected: Compiles.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/hortora/garden/search/SearchResource.java
git commit -m "refactor: SearchResource — QdrantClient QueryPoints instead of EmbeddingStore  Refs #7"
```

---

### Task 8: GardenMcpTools Refactoring

**Files:**
- Modify: `src/main/java/io/hortora/garden/mcp/GardenMcpTools.java`

- [ ] **Step 1: Replace GardenIndexer injection with QdrantClient**

Replace fields:
```java
// Remove:
// @Inject GardenIndexer indexer;

// Add:
@Inject QdrantClient qdrantClient;
@Inject QdrantConfig qdrantConfig;
```

Replace `gardenStatus()`:
```java
@Tool(description = "Get the status of the garden index: how many entries are indexed and where the garden is located.")
String gardenStatus() {
    long count;
    try {
        count = qdrantClient.countAsync(qdrantConfig.collection()).get();
    } catch (Exception e) {
        count = -1;
    }
    return "Garden path: " + config.path() + "\nIndexed entries: " + count;
}
```

Add import for `QdrantConfig`.

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile -q`

Expected: Compiles.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/hortora/garden/mcp/GardenMcpTools.java
git commit -m "refactor: GardenMcpTools — QdrantClient countAsync for garden status  Refs #7"
```

---

### Task 9: Test Infrastructure

**Files:**
- Create: `src/test/java/io/hortora/garden/test/QdrantResource.java`
- Modify: `src/test/java/io/hortora/garden/search/SearchResourceTest.java`
- Remove: `src/test/java/io/hortora/garden/test/TestEmbeddingStore.java`

- [ ] **Step 1: Delete TestEmbeddingStore**

Remove `src/test/java/io/hortora/garden/test/TestEmbeddingStore.java`. It injected as a `@Mock` for `EmbeddingStore<TextSegment>` which is no longer used.

- [ ] **Step 2: Create QdrantResource — Testcontainers lifecycle manager**

```java
package io.hortora.garden.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class QdrantResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> qdrant;

    @Override
    public Map<String, String> start() {
        qdrant = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.14.1"))
                .withExposedPorts(6333, 6334);
        qdrant.start();

        return Map.of(
                "hortora.qdrant.host", qdrant.getHost(),
                "hortora.qdrant.port", String.valueOf(qdrant.getMappedPort(6334))
        );
    }

    @Override
    public void stop() {
        if (qdrant != null) {
            qdrant.stop();
        }
    }
}
```

- [ ] **Step 3: Add @WithTestResource to SearchResourceTest**

Add to the class:
```java
@QuarkusTest
@WithTestResource(QdrantResource.class)
class SearchResourceTest {
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test -q`

Expected: All tests pass. The Testcontainers Qdrant starts, `GardenIndexer.onStart()` indexes the fixtures from `src/test/resources/fixtures/`, and `SearchResourceTest` queries work against real Qdrant.

Note: If tests fail due to timing (Qdrant not ready when `onStart` fires), add a readiness wait in `QdrantResource.start()`:
```java
qdrant.waitingFor(Wait.forHttp("/readyz").forPort(6333).forStatusCode(200));
```

- [ ] **Step 5: Commit**

```bash
git add src/test/java/io/hortora/garden/test/QdrantResource.java src/test/java/io/hortora/garden/search/SearchResourceTest.java
git rm src/test/java/io/hortora/garden/test/TestEmbeddingStore.java
git commit -m "test: Testcontainers Qdrant for integration tests; remove TestEmbeddingStore  Refs #7"
```

---

### Task 10: Cleanup

**Files:**
- Remove: `src/main/java/io/hortora/garden/entry/GardenEntry.java`
- Remove: `src/main/java/io/hortora/garden/entry/GardenEntryParser.java`
- Remove: `src/test/java/io/hortora/garden/entry/GardenEntryParserTest.java`

- [ ] **Step 1: Delete old entry parsing code**

These are fully replaced by `GardenMetadataExtractor` + `ExtractionResult`.

- [ ] **Step 2: Run full test suite**

Run: `./mvnw verify -q`

Expected: All tests pass. No remaining references to `GardenEntry` or `GardenEntryParser`.

- [ ] **Step 3: Commit**

```bash
git rm src/main/java/io/hortora/garden/entry/GardenEntry.java src/main/java/io/hortora/garden/entry/GardenEntryParser.java src/test/java/io/hortora/garden/entry/GardenEntryParserTest.java
git commit -m "chore: remove GardenEntry + GardenEntryParser — replaced by GardenMetadataExtractor  Refs #7"
```

- [ ] **Step 4: Update DESIGN.md**

Update the Module Structure and Key Abstractions sections in `docs/DESIGN.md` to reflect the new architecture. Key changes:
- `io.hortora.garden.index` now contains `GardenIndexer`, `GardenIngestionService`, `GardenMetadataExtractor`, `ExtractionResult`, `FileCursorStore`, `QdrantClientProducer`
- `io.hortora.garden.entry` package removed
- `io.hortora.garden.config` now includes `QdrantConfig`
- Dependencies section: `casehub-corpus-api` + `casehub-corpus`, `io.qdrant:client`, no `quarkus-langchain4j-qdrant`
- Key Abstractions: document `GardenIngestionService` (cursor-based, two error classes, tryLock concurrency), `FlatChangeSource` (WatchableChangeSource from casehub-corpus)

- [ ] **Step 5: Commit**

```bash
git add docs/DESIGN.md
git commit -m "docs: update DESIGN.md for incremental re-indexing architecture  Refs #7"
```
