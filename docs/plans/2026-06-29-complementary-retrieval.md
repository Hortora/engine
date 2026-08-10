# Complementary Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add BM25 keyword matching as a third retrieval leg alongside dense and sparse, with Java-side RRF fusion and metadata filter enrichment.

**Architecture:** In-process BM25 index (CamelCase-aware tokenizer + inverted index + BM25 scoring) in neural-text's `rag` module. Java-side RRF fusion replaces Qdrant-internal RRF, combining dense, sparse, and BM25 results via `RrfFusion.fuse()`. Engine gains type/tags filters on the `gardenSearch` MCP tool.

**Tech Stack:** Java 25, Quarkus 3.36.x, io.qdrant:client, LangChain4j

**Spec:** `docs/superpowers/specs/2026-06-29-complementary-retrieval-design.md`

## Global Constraints

- No new external dependencies — BM25 is ~200 lines of Java
- BM25 parameters: k1=1.2, b=0.75 (standard BM25)
- RRF parameter: k=60 (unchanged from current Qdrant-internal value)
- BM25 uses `query.text()` for keyword precision, not `query.searchText()` (HyDE expansion defeats keyword matching)
- Qdrant is source of truth; BM25 index is a derived, best-effort cache reconciled on startup
- Startup rebuild priority: `@Priority(15)` (after `CollectionMigration` at `@Priority(10)`)
- Tags stored as Qdrant list values (not comma-separated strings)
- Metadata indexes: `domain`, `type`, `tags` as Keyword in `ensureCollection()`
- Cross-repo: Tasks 1-4 in `casehub-neural-text`, Task 5 in `Hortora/engine`
- neural-text project: `/Users/mdproctor/claude/casehub/neural-text`
- engine project: `/Users/mdproctor/claude/hortora/engine`
- Tests: `./mvnw verify` in each repo
- Packages: neural-text rag-api is `io.casehub.rag`, rag runtime is `io.casehub.rag.runtime`

---

### Task 1: CamelCase Tokenizer + BM25Index

**Files:**
- Create: `neural-text/rag/src/main/java/io/casehub/rag/runtime/CodeDomainTokenizer.java`
- Create: `neural-text/rag/src/main/java/io/casehub/rag/runtime/BM25Index.java`
- Create: `neural-text/rag/src/test/java/io/casehub/rag/runtime/CodeDomainTokenizerTest.java`
- Create: `neural-text/rag/src/test/java/io/casehub/rag/runtime/BM25IndexTest.java`

**Interfaces:**
- Consumes: `PayloadFilter` sealed interface from `io.casehub.rag`
- Produces: `CodeDomainTokenizer.tokenize(String text) → List<String>`, `BM25Index` with `addChunk(String id, String content, Map<String,String> metadata, Map<String,List<String>> listMetadata)`, `removeDocument(String sourceDocumentId)`, `search(String query, int topK, PayloadFilter filter) → List<ScoredEntry>`, `clear()`, `size() → int`
- `BM25Index.ScoredEntry` record: `(String id, double score, String content, Map<String,String> metadata, Map<String,List<String>> listMetadata)`

- [ ] **Step 1: Write CodeDomainTokenizer failing tests**

```java
package io.casehub.rag.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodeDomainTokenizerTest {

    @Test
    void camelCaseSplit() {
        assertTokens("ChatModel", "chatmodel", "chat", "model");
    }

    @Test
    void allUppersThenCamel() {
        assertTokens("HTTPClient", "httpclient", "http", "client");
    }

    @Test
    void mixedAlphaNumeric() {
        assertTokens("BM25", "bm25", "bm", "25");
    }

    @Test
    void annotationStripped() {
        assertTokens("@DefaultBean", "defaultbean", "default", "bean");
    }

    @Test
    void dotSeparatedPackage() {
        var tokens = CodeDomainTokenizer.tokenize("io.casehub.platform");
        assertTrue(tokens.containsAll(List.of("io", "casehub", "platform")));
    }

    @Test
    void singleLowercaseWord() {
        assertTokens("xml", "xml");
    }

    @Test
    void emptyAndNull() {
        assertEquals(List.of(), CodeDomainTokenizer.tokenize(""));
        assertEquals(List.of(), CodeDomainTokenizer.tokenize(null));
    }

    @Test
    void preservesCompoundAlongsideComponents() {
        var tokens = CodeDomainTokenizer.tokenize("DefaultBean");
        assertTrue(tokens.contains("defaultbean"));
        assertTrue(tokens.contains("default"));
        assertTrue(tokens.contains("bean"));
    }

    @Test
    void geIdTokenization() {
        var tokens = CodeDomainTokenizer.tokenize("GE-20260629-63d619");
        assertTrue(tokens.containsAll(List.of("ge", "20260629", "63d619")));
    }

    @Test
    void configProperty() {
        var tokens = CodeDomainTokenizer.tokenize("quarkus.langchain4j.ollama.devservices.enabled");
        assertTrue(tokens.containsAll(List.of("quarkus", "langchain4j", "ollama", "devservices", "enabled")));
    }

    private void assertTokens(String input, String... expected) {
        var tokens = CodeDomainTokenizer.tokenize(input);
        for (String exp : expected) {
            assertTrue(tokens.contains(exp),
                "Expected token '" + exp + "' in " + tokens + " for input '" + input + "'");
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl rag -Dtest=CodeDomainTokenizerTest -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: compilation failure — `CodeDomainTokenizer` does not exist

- [ ] **Step 3: Implement CodeDomainTokenizer**

```java
package io.casehub.rag.runtime;

import java.util.ArrayList;
import java.util.List;

final class CodeDomainTokenizer {

    private CodeDomainTokenizer() {}

    static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();

        List<String> tokens = new ArrayList<>();
        // Step 1: split on non-alphanumeric boundaries
        String[] segments = text.split("[^a-zA-Z0-9]+");
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            List<String> parts = splitCamelCase(segment);
            for (String part : parts) {
                String lower = part.toLowerCase();
                if (!lower.isEmpty()) tokens.add(lower);
            }
            // Compound preservation: keep the full segment if it was split
            if (parts.size() > 1) {
                tokens.add(segment.toLowerCase());
            }
        }
        return List.copyOf(tokens);
    }

    private static List<String> splitCamelCase(String s) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < s.length(); i++) {
            boolean split = false;
            char prev = s.charAt(i - 1);
            char curr = s.charAt(i);
            // lowercase followed by uppercase: chatModel -> chat|Model
            if (Character.isLowerCase(prev) && Character.isUpperCase(curr)) {
                split = true;
            }
            // digit-letter or letter-digit boundary: BM25 -> BM|25
            if (Character.isDigit(prev) != Character.isDigit(curr)) {
                split = true;
            }
            // uppercase followed by uppercase+lowercase: HTTPClient -> HTTP|Client
            if (i + 1 < s.length() && Character.isUpperCase(prev)
                    && Character.isUpperCase(curr) && Character.isLowerCase(s.charAt(i + 1))) {
                split = true;
            }
            if (split) {
                parts.add(s.substring(start, i));
                start = i;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }
}
```

- [ ] **Step 4: Run CodeDomainTokenizer tests**

Run: `./mvnw test -pl rag -Dtest=CodeDomainTokenizerTest -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: all tests PASS

- [ ] **Step 5: Write BM25Index failing tests**

```java
package io.casehub.rag.runtime;

import io.casehub.rag.PayloadFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BM25IndexTest {

    private BM25Index index;

    @BeforeEach
    void setUp() {
        index = new BM25Index();
    }

    @Test
    void emptyIndexReturnsNoResults() {
        var results = index.search("ChatModel", 10, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void singleDocumentMatch() {
        index.addChunk("doc1", "ChatModel is a LangChain4j interface",
            Map.of("domain", "jvm"), Map.of());
        var results = index.search("ChatModel", 10, null);
        assertEquals(1, results.size());
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void idfWeightsRareTermsHigher() {
        index.addChunk("doc1", "ChatModel adapter for streaming responses",
            Map.of(), Map.of());
        index.addChunk("doc2", "adapter pattern for legacy systems",
            Map.of(), Map.of());
        // "ChatModel" appears in 1 doc (high IDF), "adapter" in 2 docs (low IDF)
        var results = index.search("ChatModel adapter", 10, null);
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void exactCompoundMatchRanksHigher() {
        index.addChunk("doc1", "The ChatModel interface provides chat capabilities",
            Map.of(), Map.of());
        index.addChunk("doc2", "A generic model for chat applications",
            Map.of(), Map.of());
        // doc1 has "chatmodel" compound token (rare, high IDF) + "chat" + "model"
        // doc2 has only "chat" and "model" (no compound)
        var results = index.search("ChatModel", 10, null);
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void removeDocument() {
        index.addChunk("doc1", "ChatModel is an interface", Map.of(), Map.of());
        assertEquals(1, index.size());
        index.removeDocument("doc1");
        assertEquals(0, index.size());
        assertTrue(index.search("ChatModel", 10, null).isEmpty());
    }

    @Test
    void payloadFilterEq() {
        index.addChunk("doc1", "CDI beans in Quarkus", Map.of("domain", "jvm"), Map.of());
        index.addChunk("doc2", "CDI beans in Python", Map.of("domain", "python"), Map.of());
        var results = index.search("CDI beans", 10, PayloadFilter.eq("domain", "jvm"));
        assertEquals(1, results.size());
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void payloadFilterInWithListMetadata() {
        index.addChunk("doc1", "Qdrant vector search",
            Map.of(), Map.of("tags", List.of("qdrant", "search")));
        index.addChunk("doc2", "PostgreSQL full text search",
            Map.of(), Map.of("tags", List.of("postgresql", "fts")));
        var results = index.search("search", 10, PayloadFilter.in("tags", List.of("qdrant")));
        assertEquals(1, results.size());
        assertEquals("doc1", results.getFirst().id());
    }

    @Test
    void clearRemovesEverything() {
        index.addChunk("doc1", "something", Map.of(), Map.of());
        index.addChunk("doc2", "else", Map.of(), Map.of());
        index.clear();
        assertEquals(0, index.size());
    }

    @Test
    void topKLimitsResults() {
        for (int i = 0; i < 20; i++) {
            index.addChunk("doc" + i, "Quarkus CDI bean " + i, Map.of(), Map.of());
        }
        var results = index.search("Quarkus CDI", 5, null);
        assertEquals(5, results.size());
    }

    @Test
    void resultsContainContentAndMetadata() {
        index.addChunk("doc1", "ChatModel content here",
            Map.of("domain", "jvm", "type", "gotcha"),
            Map.of("tags", List.of("langchain4j")));
        var results = index.search("ChatModel", 10, null);
        var entry = results.getFirst();
        assertEquals("ChatModel content here", entry.content());
        assertEquals("jvm", entry.metadata().get("domain"));
        assertEquals(List.of("langchain4j"), entry.listMetadata().get("tags"));
    }
}
```

- [ ] **Step 6: Run BM25Index tests to verify they fail**

Run: `./mvnw test -pl rag -Dtest=BM25IndexTest -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: compilation failure — `BM25Index` does not exist

- [ ] **Step 7: Implement BM25Index**

Create `BM25Index.java` in `io.casehub.rag.runtime`:

Key implementation details:
- `record Posting(int docIndex, int tf)` — inverted index entry
- `record ScoredEntry(String id, double score, String content, Map<String,String> metadata, Map<String,List<String>> listMetadata)` — search result
- `Map<String, List<Posting>> invertedIndex` — term → posting list
- Parallel arrays indexed by docIndex: `docIds[]`, `docLengths[]`, `docContents[]`, `docMetadata[]`, `docListMetadata[]`
- `Map<String, Integer> docIdToIndex` — reverse lookup for remove
- BM25 scoring: standard formula with k1=1.2, b=0.75
- PayloadFilter evaluation: exhaustive switch on sealed hierarchy, checks `docMetadata` for single-valued fields and `docListMetadata` for list-valued fields
- `ReadWriteLock` for thread safety

- [ ] **Step 8: Run BM25Index tests**

Run: `./mvnw test -pl rag -Dtest=BM25IndexTest -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: all tests PASS

- [ ] **Step 9: Commit**

```
git -C /Users/mdproctor/claude/casehub/neural-text add rag/src/main/java/io/casehub/rag/runtime/CodeDomainTokenizer.java rag/src/main/java/io/casehub/rag/runtime/BM25Index.java rag/src/test/java/io/casehub/rag/runtime/CodeDomainTokenizerTest.java rag/src/test/java/io/casehub/rag/runtime/BM25IndexTest.java
```

Message: `feat(rag): CamelCase tokenizer + BM25 inverted index with PayloadFilter evaluation — Refs Hortora/engine#29`

---

### Task 2: SPI Evolution + QdrantPointBuilder + Metadata Indexes

**Files:**
- Modify: `neural-text/rag-api/src/main/java/io/casehub/rag/ExtractionResult.java`
- Modify: `neural-text/rag-api/src/main/java/io/casehub/rag/ChunkInput.java`
- Modify: `neural-text/rag/src/main/java/io/casehub/rag/runtime/QdrantPointBuilder.java`
- Modify: `neural-text/rag/src/main/java/io/casehub/rag/runtime/QdrantEmbeddingIngestor.java` (ensurePayloadIndexes only)
- Create: `neural-text/rag-api/src/test/java/io/casehub/rag/ExtractionResultTest.java`
- Create: `neural-text/rag-api/src/test/java/io/casehub/rag/ChunkInputTest.java`

**Interfaces:**
- Consumes: existing `ExtractionResult`, `ChunkInput`, `QdrantPointBuilder`
- Produces: `ExtractionResult(body, metadata, listMetadata)` with backward-compatible `ExtractionResult(body, metadata)` constructor; same for `ChunkInput`; `QdrantPointBuilder.buildPoint()` stores list metadata via `ValueFactory.list()`

- [ ] **Step 1: Write ExtractionResult listMetadata tests**

```java
package io.casehub.rag;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExtractionResultTest {
    @Test
    void backwardCompatibleConstructor() {
        var result = new ExtractionResult("body", Map.of("key", "val"));
        assertEquals(Map.of(), result.listMetadata());
    }

    @Test
    void fullConstructorWithListMetadata() {
        var result = new ExtractionResult("body", Map.of("domain", "jvm"),
            Map.of("tags", List.of("cdi", "quarkus")));
        assertEquals(List.of("cdi", "quarkus"), result.listMetadata().get("tags"));
    }

    @Test
    void listMetadataIsImmutable() {
        var result = new ExtractionResult("body", Map.of(),
            Map.of("tags", List.of("one")));
        assertThrows(UnsupportedOperationException.class,
            () -> result.listMetadata().put("new", List.of()));
    }
}
```

- [ ] **Step 2: Run to verify failure, then implement**

Add `listMetadata` to `ExtractionResult`:

```java
public record ExtractionResult(String body, Map<String, String> metadata,
                                Map<String, List<String>> listMetadata) {
    public ExtractionResult {
        if (body == null) throw new IllegalArgumentException("body must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        listMetadata = listMetadata == null ? Map.of() : deepCopyListMetadata(listMetadata);
    }

    public ExtractionResult(String body, Map<String, String> metadata) {
        this(body, metadata, Map.of());
    }

    private static Map<String, List<String>> deepCopyListMetadata(Map<String, List<String>> m) {
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        m.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Map.copyOf(copy);
    }
}
```

- [ ] **Step 3: Same for ChunkInput — add listMetadata with backward-compatible constructor**

```java
public record ChunkInput(String content, String sourceDocumentId,
                          Map<String, String> metadata,
                          Map<String, List<String>> listMetadata) {
    public ChunkInput {
        if (content == null || content.isBlank())
            throw new IllegalArgumentException("content must not be null or blank");
        if (sourceDocumentId == null || sourceDocumentId.isBlank())
            throw new IllegalArgumentException("sourceDocumentId must not be null or blank");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        listMetadata = listMetadata == null ? Map.of() : deepCopyListMetadata(listMetadata);
    }

    public ChunkInput(String content, String sourceDocumentId, Map<String, String> metadata) {
        this(content, sourceDocumentId, metadata, Map.of());
    }

    private static Map<String, List<String>> deepCopyListMetadata(Map<String, List<String>> m) {
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        m.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Map.copyOf(copy);
    }
}
```

- [ ] **Step 4: Update QdrantPointBuilder to store list metadata**

In `QdrantPointBuilder.buildPoint()`, after the existing metadata loop, add list metadata storage:

```java
// After: for (Map.Entry<String, String> meta : chunk.metadata().entrySet()) { ... }
// Add:
for (Map.Entry<String, List<String>> meta : chunk.listMetadata().entrySet()) {
    payload.put(meta.getKey(), ValueFactory.list(
        meta.getValue().stream().map(ValueFactory::value).toList()));
}
```

- [ ] **Step 5: Add metadata indexes to ensurePayloadIndexes**

In `QdrantEmbeddingIngestor.ensurePayloadIndexes()`, add after the existing `tenantId` index:

```java
if (!existingSchema.containsKey("domain")) {
    client.createPayloadIndexAsync(collection, "domain",
        PayloadSchemaType.Keyword, null, true, null, null).get();
}
if (!existingSchema.containsKey("type")) {
    client.createPayloadIndexAsync(collection, "type",
        PayloadSchemaType.Keyword, null, true, null, null).get();
}
if (!existingSchema.containsKey("tags")) {
    client.createPayloadIndexAsync(collection, "tags",
        PayloadSchemaType.Keyword, null, true, null, null).get();
}
```

Also add the corresponding `checkIndexType` calls at the top of `ensurePayloadIndexes`.

- [ ] **Step 6: Run full rag-api + rag tests**

Run: `./mvnw verify -pl rag-api,rag -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: all existing tests still PASS (backward-compatible constructors)

- [ ] **Step 7: Commit**

Message: `feat(rag-api): ExtractionResult + ChunkInput gain listMetadata(); QdrantPointBuilder stores list values; metadata indexes — Refs Hortora/engine#29`

---

### Task 3: BM25IndexRegistry + Ingestion Integration + Startup Rebuild

**Files:**
- Create: `neural-text/rag/src/main/java/io/casehub/rag/runtime/BM25IndexRegistry.java`
- Modify: `neural-text/rag/src/main/java/io/casehub/rag/runtime/QdrantEmbeddingIngestor.java` (inject registry, call addChunk/removeDocument/clear)
- Modify: `neural-text/rag/src/main/java/io/casehub/rag/runtime/ReactiveQdrantEmbeddingIngestor.java` (same)
- Create: `neural-text/rag/src/test/java/io/casehub/rag/runtime/BM25IndexRegistryTest.java`

**Interfaces:**
- Consumes: `BM25Index` from Task 1, `TenancyStrategy`, `CorpusRef`, `QdrantClient` (for startup rebuild)
- Produces: `BM25IndexRegistry` CDI bean — `getOrCreate(CorpusRef) → BM25Index`, `search(CorpusRef, query, topK, filter) → List<RetrievedChunk>`, `clear(CorpusRef)`, `rebuildFromQdrant(CorpusRef, QdrantClient)`

- [ ] **Step 1: Write BM25IndexRegistry tests**

```java
package io.casehub.rag.runtime;

import io.casehub.rag.CorpusRef;
import io.casehub.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BM25IndexRegistryTest {

    @Test
    void separateCorpusesAreIsolated() {
        var registry = new BM25IndexRegistry(TenancyStrategy.SEPARATE_COLLECTIONS);
        var corpus1 = new CorpusRef("tenant1", "corpus1");
        var corpus2 = new CorpusRef("tenant2", "corpus2");

        registry.addChunk(corpus1, "doc1", "ChatModel in Quarkus",
            Map.of("domain", "jvm"), Map.of());
        registry.addChunk(corpus2, "doc2", "Python ML model",
            Map.of("domain", "python"), Map.of());

        var results1 = registry.search(corpus1, "ChatModel", 10, null);
        assertEquals(1, results1.size());
        assertEquals("doc1", results1.getFirst().sourceDocumentId());

        var results2 = registry.search(corpus2, "ChatModel", 10, null);
        assertTrue(results2.isEmpty());
    }

    @Test
    void clearRemovesOnlyTargetCorpus() {
        var registry = new BM25IndexRegistry(TenancyStrategy.SEPARATE_COLLECTIONS);
        var corpus1 = new CorpusRef("t1", "c1");
        var corpus2 = new CorpusRef("t2", "c2");

        registry.addChunk(corpus1, "doc1", "content1", Map.of(), Map.of());
        registry.addChunk(corpus2, "doc2", "content2", Map.of(), Map.of());
        registry.clear(corpus1);

        assertTrue(registry.search(corpus1, "content1", 10, null).isEmpty());
        assertEquals(1, registry.search(corpus2, "content2", 10, null).size());
    }

    @Test
    void searchReturnsRetrievedChunks() {
        var registry = new BM25IndexRegistry(TenancyStrategy.SEPARATE_COLLECTIONS);
        var corpus = new CorpusRef("hortora", "garden");
        registry.addChunk(corpus, "doc1", "ChatModel is a LangChain4j interface",
            Map.of("domain", "jvm"), Map.of("tags", List.of("langchain4j")));

        List<RetrievedChunk> results = registry.search(corpus, "ChatModel", 10, null);
        assertEquals(1, results.size());
        var chunk = results.getFirst();
        assertEquals("doc1", chunk.sourceDocumentId());
        assertEquals("jvm", chunk.metadata().get("domain"));
        assertTrue(chunk.relevanceScore() > 0);
    }
}
```

- [ ] **Step 2: Implement BM25IndexRegistry**

```java
package io.casehub.rag.runtime;

import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.RetrievedChunk;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BM25IndexRegistry {

    private final TenancyStrategy tenancyStrategy;
    private final Map<String, BM25Index> indexes = new ConcurrentHashMap<>();

    @Inject
    BM25IndexRegistry(TenancyStrategy tenancyStrategy) {
        this.tenancyStrategy = tenancyStrategy;
    }

    // Test-visible constructor
    BM25IndexRegistry(TenancyStrategy strategy) {
        this.tenancyStrategy = strategy;
    }

    public void addChunk(CorpusRef corpus, String sourceDocumentId, String content,
                         Map<String, String> metadata, Map<String, List<String>> listMetadata) {
        String key = tenancyStrategy.collectionName(corpus);
        indexes.computeIfAbsent(key, k -> new BM25Index())
            .addChunk(sourceDocumentId, content, metadata, listMetadata);
    }

    public void removeDocument(CorpusRef corpus, String sourceDocumentId) {
        String key = tenancyStrategy.collectionName(corpus);
        BM25Index index = indexes.get(key);
        if (index != null) index.removeDocument(sourceDocumentId);
    }

    public List<RetrievedChunk> search(CorpusRef corpus, String query, int topK, PayloadFilter filter) {
        String key = tenancyStrategy.collectionName(corpus);
        BM25Index index = indexes.get(key);
        if (index == null) return List.of();

        List<BM25Index.ScoredEntry> entries = index.search(query, topK, filter);
        List<RetrievedChunk> results = new ArrayList<>(entries.size());
        for (var entry : entries) {
            results.add(new RetrievedChunk(entry.content(), entry.id(),
                entry.score(), entry.metadata()));
        }
        return results;
    }

    public void clear(CorpusRef corpus) {
        String key = tenancyStrategy.collectionName(corpus);
        BM25Index index = indexes.remove(key);
        if (index != null) index.clear();
    }
}
```

- [ ] **Step 3: Integrate with QdrantEmbeddingIngestor**

Inject `BM25IndexRegistry` into `QdrantEmbeddingIngestor`. In `ingest()`, after each Qdrant upsert batch:

```java
// After: client.upsertAsync(collection, points).get();
// Add: update BM25 index
for (ChunkInput chunk : batch) {
    bm25Registry.addChunk(corpus, chunk.sourceDocumentId(), chunk.content(),
        chunk.metadata(), chunk.listMetadata());
}
```

In `deleteDocument()`:
```java
bm25Registry.removeDocument(corpus, sourceDocumentId);
```

In `deleteCorpus()`:
```java
bm25Registry.clear(corpus);
```

- [ ] **Step 4: Same changes for ReactiveQdrantEmbeddingIngestor**

Mirror the same `bm25Registry` calls in the reactive variants. BM25 index operations are sub-millisecond, safe to call from any thread context.

- [ ] **Step 5: Run all rag tests**

Run: `./mvnw verify -pl rag -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: all tests PASS (BM25IndexRegistry is injected via CDI; existing tests using `InMemoryEmbeddingIngestor` are unaffected)

- [ ] **Step 6: Commit**

Message: `feat(rag): BM25IndexRegistry — corpus-scoped coordinator + ingestion integration — Refs Hortora/engine#29`

---

### Task 4: Java-Side RRF + BM25 Retrieval Leg

**Files:**
- Modify: `neural-text/rag/src/main/java/io/casehub/rag/runtime/HybridCaseRetriever.java`
- Modify: `neural-text/rag/src/main/java/io/casehub/rag/runtime/ReactiveHybridCaseRetriever.java`
- Modify: `neural-text/rag/src/main/java/io/casehub/rag/runtime/RagBeanProducer.java` (inject BM25IndexRegistry into retriever)
- Create: `neural-text/rag/src/main/java/io/casehub/rag/runtime/BM25StartupRebuilder.java`
- Modify: `neural-text/rag/src/test/java/io/casehub/rag/runtime/HybridCaseRetrieverTest.java`

**Interfaces:**
- Consumes: `BM25IndexRegistry` from Task 3, `RrfFusion.fuse()` from `rag-api`, `QdrantClient` for separate queries
- Produces: modified `HybridCaseRetriever.retrieve()` — three-leg retrieval with Java-side RRF

- [ ] **Step 1: Write test for three-leg RRF behavior**

Add to `HybridCaseRetrieverTest`:

```java
@Test
void bm25LegContributesToResults() {
    // Ingest a document with a distinctive Java identifier
    ingestor.ingest(CORPUS, List.of(
        new ChunkInput("ChatModel is a LangChain4j interface for LLM interaction",
            "chatmodel-doc", Map.of("domain", "jvm"))
    ));

    // Query with the identifier — BM25 should boost this even if dense is weak
    var results = retriever.retrieve(RetrievalQuery.of("ChatModel"), CORPUS, 5, null);
    assertFalse(results.isEmpty());
    assertTrue(results.stream().anyMatch(r -> r.sourceDocumentId().equals("chatmodel-doc")));
}
```

- [ ] **Step 2: Refactor HybridCaseRetriever.retrieve() to use Java-side RRF**

Replace the single Qdrant query with three parallel legs:

```java
@Override
public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                      int maxResults, PayloadFilter filter) {
    tenantGuard.assertTenant(corpus.tenantId());

    String collection = tenancyStrategy.collectionName(corpus);
    if (!collectionExists(collection)) return List.of();

    // Build merged Qdrant filter
    Optional<Filter> mergedFilter = buildMergedFilter(corpus, filter);

    // Determine query limit (more candidates if reranking)
    int queryLimit = rerankEnabled && reranker != null
        ? Math.max(maxResults, rerankTopN) : maxResults;

    // --- Leg 1: Dense embedding ---
    Embedding denseEmbedding = embeddingModel.embed(TextSegment.from(query.searchText())).content();
    CompletableFuture<List<ScoredPoint>> denseFuture = buildDenseQuery(
        collection, denseEmbedding, queryLimit, mergedFilter);

    // --- Leg 2: Sparse embedding (optional) ---
    CompletableFuture<List<ScoredPoint>> sparseFuture = null;
    if (sparseEmbedder != null) {
        Map<Integer, Float> sparseMap = sparseEmbedder.embed(query.text());
        sparseFuture = buildSparseQuery(collection, sparseMap, queryLimit, mergedFilter);
    }

    // --- Leg 3: BM25 (in-process) ---
    List<RetrievedChunk> bm25Results = bm25Registry != null
        ? bm25Registry.search(corpus, query.text(), queryLimit, filter)
        : List.of();

    // Wait for Qdrant legs
    List<RetrievedChunk> denseResults = scoredPointsToChunks(getResult(denseFuture));
    List<RetrievedChunk> sparseResults = sparseFuture != null
        ? scoredPointsToChunks(getResult(sparseFuture)) : null;

    // --- RRF Fusion ---
    List<List<RetrievedChunk>> legs = new ArrayList<>();
    legs.add(denseResults);
    if (sparseResults != null) legs.add(sparseResults);
    if (!bm25Results.isEmpty()) legs.add(bm25Results);

    List<RetrievedChunk> fused = legs.size() == 1
        ? denseResults  // skip RRF overhead when only dense
        : RrfFusion.fuse(legs, queryLimit, rrfK);

    // Optional reranking (unchanged)
    if (rerankEnabled && reranker != null && !fused.isEmpty()) {
        return rerank(query, fused, maxResults);
    }

    return fused.size() > maxResults
        ? List.copyOf(fused.subList(0, maxResults))
        : fused;
}
```

Extract `buildDenseQuery()`, `buildSparseQuery()`, `scoredPointsToChunks()` as private methods from the existing monolithic implementation. The current payload extraction logic (lines 169-191 of the existing code) moves into `scoredPointsToChunks()`.

- [ ] **Step 3: Add BM25IndexRegistry to HybridCaseRetriever constructor**

Add `BM25IndexRegistry bm25Registry` as a nullable constructor parameter. Update `RagBeanProducer` to inject and pass it.

- [ ] **Step 4: Create BM25StartupRebuilder**

```java
package io.casehub.rag.runtime;

import io.casehub.rag.CorpusRef;
import io.casehub.rag.CorpusIngestionBinding;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.*;
import io.qdrant.client.grpc.JsonWithInt.Value;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BM25StartupRebuilder {

    @Inject BM25IndexRegistry registry;
    @Inject QdrantClient client;
    @Inject TenancyStrategy tenancyStrategy;
    @Inject Instance<CorpusIngestionBinding> bindings;

    void onStartup(@Observes @Priority(15) StartupEvent event) {
        for (CorpusIngestionBinding binding : bindings) {
            CorpusRef corpus = binding.corpusRef();
            String collection = tenancyStrategy.collectionName(corpus);
            try {
                if (!client.collectionExistsAsync(collection).get()) continue;
                rebuildFromQdrant(corpus, collection);
            } catch (Exception e) {
                Log.warnf("BM25 rebuild failed for %s — keyword search degraded until next restart", collection, e);
            }
        }
    }

    private void rebuildFromQdrant(CorpusRef corpus, String collection) throws Exception {
        registry.clear(corpus);
        io.qdrant.client.grpc.Common.PointId offset = null;
        int count = 0;

        while (true) {
            ScrollPoints.Builder scroll = ScrollPoints.newBuilder()
                .setCollectionName(collection)
                .setLimit(100)
                .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true));
            if (offset != null) scroll.setOffset(offset);

            ScrollResponse response = client.scrollAsync(scroll.build()).get();

            for (RetrievedPoint point : response.getResultList()) {
                Map<String, Value> payload = point.getPayloadMap();
                String content = extractString(payload, "content");
                String docId = extractString(payload, "sourceDocumentId");
                if (content == null || docId == null) continue;

                Map<String, String> metadata = new java.util.HashMap<>();
                Map<String, List<String>> listMetadata = new java.util.HashMap<>();
                for (var entry : payload.entrySet()) {
                    if (Set.of("content", "sourceDocumentId", "tenantId").contains(entry.getKey())) continue;
                    Value v = entry.getValue();
                    if (v.hasStringValue()) {
                        metadata.put(entry.getKey(), v.getStringValue());
                    } else if (v.hasListValue()) {
                        listMetadata.put(entry.getKey(),
                            v.getListValue().getValuesList().stream()
                                .filter(Value::hasStringValue)
                                .map(Value::getStringValue)
                                .toList());
                    }
                }
                registry.addChunk(corpus, docId, content, metadata, listMetadata);
                count++;
            }

            if (!response.hasNextPageOffset()) break;
            offset = response.getNextPageOffset();
        }
        Log.infof("BM25 index rebuilt for %s: %d chunks", collection, count);
    }

    private static String extractString(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        return v != null && v.hasStringValue() ? v.getStringValue() : null;
    }
}
```

- [ ] **Step 5: Apply same RRF refactoring to ReactiveHybridCaseRetriever**

Mirror the three-leg approach using `Uni.combine().all().unis(denseUni, sparseUni).asTuple()`. BM25 search is inline (sub-millisecond).

- [ ] **Step 6: Run full rag test suite**

Run: `./mvnw verify -pl rag -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: all tests PASS

- [ ] **Step 7: Run full neural-text verify**

Run: `./mvnw verify -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 8: Install neural-text locally**

Run: `./mvnw install -DskipTests -f /Users/mdproctor/claude/casehub/neural-text/pom.xml`
Expected: BUILD SUCCESS — artifacts available for engine consumption

- [ ] **Step 9: Commit**

Message: `feat(rag): Java-side RRF fusion with BM25 third retrieval leg + startup rebuild — Refs Hortora/engine#29`

---

### Task 5: Engine MCP Tool Enrichment

**Files:**
- Modify: `engine/src/main/java/io/hortora/garden/index/GardenMetadataExtractor.java`
- Modify: `engine/src/main/java/io/hortora/garden/mcp/GardenMcpTools.java`
- Modify: `engine/src/main/java/io/hortora/garden/search/SearchResource.java`
- Modify: `engine/src/test/java/io/hortora/garden/index/GardenMetadataExtractorTest.java`
- Modify: `engine/src/test/java/io/hortora/garden/mcp/GardenMcpToolsTest.java`
- Modify: `engine/src/test/java/io/hortora/garden/search/SearchResourceTest.java`

**Interfaces:**
- Consumes: `ExtractionResult(body, metadata, listMetadata)` from Task 2
- Produces: `gardenSearch(query, domain, type, tags, limit)` MCP tool

- [ ] **Step 1: Write GardenMetadataExtractor test for listMetadata tags**

Add to `GardenMetadataExtractorTest`:

```java
@Test
void tagsExtractedAsListMetadata() {
    String content = """
        ---
        title: "Test entry"
        domain: jvm
        tags: [cdi, quarkus, bean-discovery]
        ---
        Body text here.
        """;
    var result = extractor.extract("test.md", content.getBytes(StandardCharsets.UTF_8));
    assertEquals(List.of("cdi", "quarkus", "bean-discovery"), result.listMetadata().get("tags"));
    assertNull(result.metadata().get("tags")); // no longer in string metadata
}
```

- [ ] **Step 2: Update GardenMetadataExtractor to use listMetadata**

Change the tags extraction from `String.join()` into string metadata to returning tags in `listMetadata()`:

```java
// Replace:
// if (fm.get("tags") instanceof List<?> tags) {
//     metadata.put("tags", String.join(", ", tags.stream().map(Object::toString).toList()));
// }

// With:
Map<String, List<String>> listMetadata = new LinkedHashMap<>();
if (fm.get("tags") instanceof List<?> tags) {
    listMetadata.put("tags", tags.stream().map(Object::toString).toList());
}

return new ExtractionResult(combinedContent, metadata, listMetadata);
```

- [ ] **Step 3: Write SearchResource filter construction test**

Add to `SearchResourceTest`:

```java
@Test
void buildFilterWithTypeAndTags() {
    var filter = SearchResource.buildFilter(List.of("jvm"), "gotcha", "qdrant,cdi");
    assertNotNull(filter);
    // Should be And(eq(domain, jvm), eq(type, gotcha), in(tags, [qdrant, cdi]))
    assertInstanceOf(PayloadFilter.And.class, filter);
}

@Test
void buildFilterDomainOnly() {
    var filter = SearchResource.buildFilter(List.of("jvm"), null, null);
    assertInstanceOf(PayloadFilter.Eq.class, filter);
}

@Test
void buildFilterNullReturnsNull() {
    assertNull(SearchResource.buildFilter(null, null, null));
}
```

- [ ] **Step 4: Refactor SearchResource.buildDomainFilter to buildFilter**

Rename `buildDomainFilter(List<String> domains)` to `buildFilter(List<String> domains, String type, String tags)`:

```java
static PayloadFilter buildFilter(List<String> domains, String type, String tags) {
    List<PayloadFilter> filters = new ArrayList<>();

    if (domains != null && !domains.isEmpty()) {
        List<String> nonBlank = domains.stream().filter(d -> d != null && !d.isBlank()).toList();
        if (!nonBlank.isEmpty()) {
            filters.add(nonBlank.size() == 1
                ? PayloadFilter.eq("domain", nonBlank.getFirst())
                : PayloadFilter.in("domain", nonBlank));
        }
    }
    if (type != null && !type.isBlank()) {
        filters.add(PayloadFilter.eq("type", type));
    }
    if (tags != null && !tags.isBlank()) {
        List<String> tagList = java.util.Arrays.stream(tags.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!tagList.isEmpty()) {
            filters.add(PayloadFilter.in("tags", tagList));
        }
    }

    if (filters.isEmpty()) return null;
    if (filters.size() == 1) return filters.getFirst();
    return new PayloadFilter.And(filters);
}
```

Update all call sites: `searchLocal()`, `doSearch()`, and the REST `search()` endpoint (add `@QueryParam("type")` and `@QueryParam("tags")`). Update `searchFor()` signature to accept type and tags.

- [ ] **Step 5: Add type and tags to GardenMcpTools.gardenSearch**

```java
@Tool(description = "Search the Hortora knowledge garden for relevant entries...")
String gardenSearch(
        @ToolArg(description = "Natural language description of the problem, symptom, or topic to search for") String query,
        @ToolArg(description = "Optional: filter by domain (e.g. jvm, tools, python)", required = false) String domain,
        @ToolArg(description = "Optional: filter by entry type (gotcha, technique, undocumented, pattern)", required = false) String type,
        @ToolArg(description = "Optional: comma-separated tags to filter by (entries matching ANY tag are returned)", required = false) String tags,
        @ToolArg(description = "Maximum number of entries to return (default 8)", required = false) Integer limit) {

    List<SearchResult> results = searchResource.searchFor(query,
            domain != null && !domain.isBlank() ? List.of(domain) : null,
            type, tags, limit);
    // ... rest unchanged
}
```

- [ ] **Step 6: Run engine tests**

Run: `./mvnw verify -f /Users/mdproctor/claude/hortora/engine/pom.xml`
Expected: all tests PASS

- [ ] **Step 7: Commit**

Message: `feat: gardenSearch gains type/tags filters; GardenMetadataExtractor emits list metadata — Refs #29`

---

## Post-Implementation

After all tasks are complete:

1. **Re-index**: Run `gardenReindex()` via MCP to rebuild the Qdrant collection with list-valued tags and new metadata indexes
2. **Benchmark validation**: Re-run #27 methodology to measure BM25's impact on keyword queries
3. **Update DESIGN.md**: Document the three-leg architecture change
