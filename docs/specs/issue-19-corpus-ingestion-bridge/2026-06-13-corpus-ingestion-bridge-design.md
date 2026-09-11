# Corpus Ingestion Bridge Design

**Date:** 2026-06-13
**Status:** Approved (rev 4)
**Tracks:** casehubio/neural-text#19
**Parent spec:** `specs/2026-06-11-corpus-storage-module-design.md` (rev 5, §rag/ — Ingestion Bridge)

## Problem

The corpus modules (`corpus-api/`, `corpus/`) provide document storage with change tracking. The RAG modules (`rag-api/`, `rag/`) provide embedding and vector search via Qdrant. Nothing connects them. Documents written to a corpus are not automatically embedded and made searchable. Consumers must manually read documents, chunk them, and call `EmbeddingIngestor` — duplicating pipeline logic across every integration.

## Solution

A config-driven ingestion bridge in `rag/` that polls `ChangeSource`, reads from `CorpusReader`, extracts metadata, chunks, and pushes to Qdrant via the existing `EmbeddingIngestor`. Two new SPIs in `rag-api` provide format-level extension points: `MetadataExtractor` and `CursorStore`. A `CorpusIngestionBinding` record in `rag/` carries the complete per-corpus descriptor.

## Design Principles

1. **Bridge depends on corpus-api SPIs only** — `ChangeSource` and `CorpusReader`, never `CorpusStore`. The bridge is a read-only consumer. A producer class in `rag/` handles corpus instance creation from config.
2. **Per-binding MetadataExtractor** — different corpora have different document formats (YAML frontmatter, Tika, CSV). The extractor belongs per-corpus in the binding, not as a global CDI bean.
3. **Blocking pipeline** — every operation (ZIP I/O, embedding inference, Qdrant gRPC) is blocking. No reactive variant needed for a background poller.
4. **Delete-then-reingest for MODIFIED** — `EmbeddingIngestor` generates random UUIDs per point. Re-ingesting without deleting first creates duplicates. The bridge always deletes old vectors before ingesting new ones for MODIFIED entries.
5. **Cursor advances only on full success** — cursor advances when every entry in the ChangeSet was either successfully processed or legitimately skipped (document gone between detect and read). Any actual error blocks cursor advancement — the entire set is retried next poll. Safe because delete-then-reingest is idempotent. **Stuck cursor escape hatch:** if a single entry blocks cursor advancement across multiple polls (e.g. chronically corrupted content), triggering `reconcile()` resets the cursor via `fullScan()`. Reconciliation has best-effort error semantics (see §Reconciliation), so it advances past the stuck entry and restores normal incremental operation.
6. **Explicit bootstrapping** — when no cursor exists, the service calls `fullScan()`, not `changesSince(null)`. Does not rely on ChangeSource implementations treating null cursors as full scans.

## Prerequisite: Fix assertTenant in EmbeddingIngestor + CaseRetriever

`QdrantEmbeddingIngestor`, `ReactiveQdrantEmbeddingIngestor`, `HybridCaseRetriever`, and `ReactiveHybridCaseRetriever` use the 2-arg `MemoryPermissions.assertTenant(tenantId, principal)` on every operation. This assumes a request context is active. The ingestion bridge runs on a `@Scheduled` timer — no request context, no authenticated user. Every call would throw `SecurityException`.

**Fix:** Switch all four classes to the 3-arg form: `MemoryPermissions.assertTenant(tenantId, principal, requestContextActive())`, with a private helper:

```java
private boolean requestContextActive() {
    var c = Arc.container();
    return c == null || c.requestContext().isActive();
}
```

This extends the established platform pattern from protocol `PP-20260529-57cc3b` (`casememorystore-adapter-asserttenant-contract`). That protocol scopes to `CaseMemoryStore` implementations; applying it to `EmbeddingIngestor` and `CaseRetriever` implementations is a scope extension of the same security principle. The prerequisite issue should also update the protocol's `applies_to` field to include RAG adapter implementations.

When request context is absent (background poller), the tenantId is trusted from config. When present (REST endpoint), the tenantId is validated against the authenticated principal. 10 call sites across 4 classes. File a separate issue before implementing.

## rag-api Additions (Tier 1)

rag-api remains "pure Java, Mutiny provided" — no new module dependencies.

### MetadataExtractor SPI

```java
package io.casehub.rag;

public interface MetadataExtractor {
    ExtractionResult extract(String path, byte[] content);
}
```

Returns both the cleaned body (frontmatter stripped) and the extracted metadata. The body goes to the chunker. The metadata becomes `ChunkInput.metadata()` and Qdrant payload fields.

### ExtractionResult

```java
package io.casehub.rag;

import java.util.Map;

public record ExtractionResult(String body, Map<String, String> metadata) {
    public ExtractionResult {
        if (body == null)
            throw new IllegalArgumentException("body must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
```

### CursorStore SPI

```java
package io.casehub.rag;

import java.util.Optional;

public interface CursorStore {
    Optional<String> load(String corpusName);
    void save(String corpusName, String cursor);
}
```

Persists the ChangeSource cursor per corpus. Pluggable backend — file-based default, in-memory for tests, DB-backed for multi-instance deployments.

## rag/ Additions (Tier 3)

### CorpusIngestionBinding

```java
package io.casehub.rag.runtime;

import io.casehub.corpus.ChangeSource;
import io.casehub.corpus.CorpusReader;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.MetadataExtractor;
import java.util.Objects;

public record CorpusIngestionBinding(
    String name,
    CorpusRef corpusRef,
    ChangeSource changeSource,
    CorpusReader corpusReader,
    MetadataExtractor metadataExtractor
) {
    public CorpusIngestionBinding {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name must not be null or blank");
        Objects.requireNonNull(corpusRef, "corpusRef");
        Objects.requireNonNull(changeSource, "changeSource");
        Objects.requireNonNull(corpusReader, "corpusReader");
        Objects.requireNonNull(metadataExtractor, "metadataExtractor");
    }
}
```

Lives in `rag/` (not `rag-api`) because it references corpus-api types (`ChangeSource`, `CorpusReader`). Placing it in rag-api would break rag-api's zero-dep invariant. Any consumer producing custom bindings already depends on `rag/` for `CorpusIngestionService` — no additional coupling.

**`name` vs `corpusRef.corpusName()`:** `name` is the binding identity — used for cursor storage and config lookup. `corpusRef.corpusName()` is the Qdrant collection identity. They can differ in multi-tenant scenarios: binding `garden-default` targets `CorpusRef("default", "garden")`, binding `garden-acme` targets `CorpusRef("acme-corp", "garden")`. Same corpus name, different tenants, different bindings and cursors.

### CorpusIngestionService

`@ApplicationScoped`. Injects `EmbeddingIngestor`, `CursorStore`, `CorpusBindingProducer`, and `Instance<CorpusIngestionBinding>`.

**Binding discovery — two sources:**

1. **Config-driven:** `CorpusBindingProducer.bindings()` returns a `List<CorpusIngestionBinding>` built from `IngestionConfig.corpora()`. This is a regular method call, not CDI producer resolution — standard CDI `@Produces` creates one bean per method and cannot dynamically produce N beans from a config map.
2. **Custom:** `Instance<CorpusIngestionBinding>` discovers any manually-produced CDI beans from application code (e.g. a custom `ChangeSource` backed by git or an external API).

The service merges both sources. The two are semantically distinct: config-driven bindings are deterministic startup state; custom bindings are application-produced CDI beans for non-standard corpus backends.

**Poll loop:**

Single `@Scheduled` method with configurable global interval (`casehub.rag.ingestion.interval`, default `30s`). Iterates all merged bindings, processes each independently. Per-corpus `ReentrantLock` prevents concurrent processing (poll vs reconciliation overlap). Only runs for corpora with `mode=AUTO` in config (config-driven bindings) or unconditionally (custom bindings — no config entry to check). `MANUAL` corpora are processed on explicit trigger. `NONE` are skipped.

**Processing a ChangeSet:**

```
1. CursorStore.load(binding.name()) → Optional<String>
2. If empty → ChangeSource.fullScan() → ChangeSet
   If present → ChangeSource.changesSince(cursor) → ChangeSet
3. If ChangeSet.entries() is empty → return

4. Phase 1 — Deletions:
   For DELETED: EmbeddingIngestor.deleteDocument(corpusRef, path)
   For MODIFIED: EmbeddingIngestor.deleteDocument(corpusRef, path)

5. Phase 2 — Ingestion:
   For each ADDED/MODIFIED:
     a. CorpusReader.read(path) → Optional<byte[]>
        If empty → skip (document deleted between detect and read — legitimate, not an error)
     b. MetadataExtractor.extract(path, content) → ExtractionResult(body, metadata)
     c. Chunk body via DocumentSplitter (resolved from config per corpus name)
     d. Create ChunkInput(chunkText, sourceDocumentId=path, metadata) per chunk
   Batch all ChunkInputs → EmbeddingIngestor.ingest(corpusRef, allChunks)

6. If all entries either succeeded or were legitimately skipped (read returned empty):
     CursorStore.save(name, changeSet.newCursor())
   If any entry threw an exception:
     Log failures, do NOT advance cursor — retry entire set next poll
```

**sourceDocumentId:** the corpus path (e.g. `tools/git-rebase.md`). Stable across updates, unique within a corpus.

**Reconciliation:**

Separate from the poll loop. Triggerable via `reconcile(String corpusName)` or `reconcileAll()`. Best-effort error semantics — failed entries are logged but do not block cursor advancement. A subsequent reconciliation will re-detect and retry them, because reconciliation compares Qdrant state against corpus state every time. This is intentionally different from the poll loop's all-or-nothing semantics: reconciliation is an admin-triggered corrective operation where blocking on a single corrupted document would prevent fixing all other gaps.

```
1. ChangeSource.fullScan() → all paths in corpus
2. EmbeddingIngestor.listDocuments(corpusRef) → all sourceDocumentIds in Qdrant
3. In corpus but not in Qdrant → reingest (failures logged, not fatal)
4. In Qdrant but not in corpus → deleteDocument() (failures logged, not fatal)
5. CursorStore.save(name, fullScan.newCursor()) — always, regardless of entry-level failures
```

**Error handling (poll loop):**

| Failure | Action | Cursor |
|---------|--------|--------|
| ChangeSource throws | Log error, skip this corpus, continue to next binding | Not advanced |
| CorpusReader.read() returns `Optional.empty()` | Log info, skip entry — document gone, not an error | Advances (legitimate skip) |
| MetadataExtractor throws | Log error, skip entry | Not advanced (actual error) |
| EmbeddingIngestor throws | Log error | Not advanced (actual error) |

**Error handling (reconciliation):**

| Failure | Action | Cursor |
|---------|--------|--------|
| ChangeSource.fullScan() throws | Log error, abort reconciliation for this corpus | Not advanced |
| CorpusReader.read() returns `Optional.empty()` | Skip entry — document gone | N/A |
| MetadataExtractor throws | Log error, skip entry, continue | Advances (best-effort) |
| EmbeddingIngestor throws | Log error, skip entry, continue | Advances (best-effort) |

### YamlFrontmatterExtractor

`@DefaultBean @ApplicationScoped`. Parses YAML frontmatter delimited by `---` lines. Returns body (everything after closing `---`) and metadata (flat `key: value` pairs). Pure string parsing — no SnakeYAML dependency. If no frontmatter found, returns entire content as body with empty metadata.

Not injected globally — used by `CorpusBindingProducer` as the default extractor for bindings that don't specify one.

### FileCursorStore

`@DefaultBean @ApplicationScoped`. Stores cursor values as text files: `<cursor-dir>/<corpusName>.cursor`. Atomic write via temp file + rename. Config: `casehub.rag.ingestion.cursor-dir` (defaults to `${java.io.tmpdir}/casehub-ingestion-cursors`).

### IngestionConfig

```java
@ConfigMapping(prefix = "casehub.rag.ingestion")
public interface IngestionConfig {

    @WithDefault("30s")
    Duration interval();

    @WithDefault("${java.io.tmpdir}/casehub-ingestion-cursors")
    String cursorDir();

    Map<String, CorpusIngestionConfig> corpora();

    interface CorpusIngestionConfig {
        @WithDefault("AUTO")
        IngestionMode mode();

        String tenantId();
        String corpusName();

        @WithDefault("none")
        String chunking();

        Optional<Integer> chunkingMaxSize();
        Optional<Integer> chunkingOverlapSize();
    }
}
```

**Config key deviation from parent spec:** the parent spec uses `casehub.rag.ingestion.garden.mode=auto`. This spec uses `casehub.rag.ingestion.corpora.garden.mode=auto` — the extra `corpora` level is required for Quarkus `@ConfigMapping` with `Map<String, CorpusIngestionConfig>`. The parent spec should be updated to match.

Config example:
```properties
casehub.rag.ingestion.interval=30s
casehub.rag.ingestion.corpora.garden.tenant-id=default
casehub.rag.ingestion.corpora.garden.corpus-name=garden
casehub.rag.ingestion.corpora.garden.mode=auto
casehub.rag.ingestion.corpora.garden.chunking=none
casehub.rag.ingestion.corpora.legal.tenant-id=acme-corp
casehub.rag.ingestion.corpora.legal.corpus-name=legal
casehub.rag.ingestion.corpora.legal.chunking=recursive
casehub.rag.ingestion.corpora.legal.chunking-max-size=1000
casehub.rag.ingestion.corpora.legal.chunking-overlap-size=200
```

### IngestionMode

```java
package io.casehub.rag.runtime;

public enum IngestionMode { AUTO, MANUAL, NONE }
```

### CorpusBindingProducer

`@ApplicationScoped` bean (not a CDI producer). Exposes `List<CorpusIngestionBinding> bindings()` which reads `IngestionConfig.corpora()` map and constructs a binding per entry. For each named corpus entry, it creates the corpus implementation objects (`ZipCorpusStore`, `FlatCorpusStore`, or `CompositeCorpusStore` depending on corpus-level config at `casehub.corpus.<name>.*`). The corpus-level config (`casehub.corpus.<name>.source`, `casehub.corpus.<name>.mode`, etc.) is read via a separate `@ConfigMapping` within this class. Uses `YamlFrontmatterExtractor` as the default extractor for each binding.

This is the only class in `rag/` that depends on `casehub-corpus` implementation types. The rest of the ingestion service depends only on corpus-api types via the binding.

**Design debt:** Corpus instance construction (choosing between `ZipCorpusStore`, `FlatCorpusStore`, `CompositeCorpusStore` based on config) is a corpus concern, not a RAG concern. It lives here because there is no corpus CDI integration layer yet (analogous to `inference-quarkus/` for inference). When the second consumer materialises (harvest via `CorpusReader`, engine fact retrieval), extract this to a dedicated `corpus-quarkus/` module. Track extraction as a follow-on issue.

### Chunking

Resolved internally by `CorpusIngestionService` from `IngestionConfig` per corpus name. Uses LangChain4j `DocumentSplitters` API:

| Config value | DocumentSplitter |
|---|---|
| `none` | null — whole body is one chunk |
| `recursive` | `DocumentSplitters.recursive(maxSegmentSizeInChars, maxOverlapSizeInChars)` |

Uses the character-based 2-arg overload. `EmbeddingModel` does not implement `TokenCountEstimator`, so the token-based 3-arg overload is not available without a separate tokenizer. Character-based splitting is sufficient — segment sizes are approximate by nature, and character counts avoid coupling the chunking step to a specific tokenizer implementation.

LangChain4j types stay internal to `rag/` — never leak into rag-api or the binding.

## rag-testing/ Additions

### InMemoryCursorStore

`@Alternative @Priority(1) @ApplicationScoped`. `ConcurrentHashMap<String, String>` backing store. Provides `reset()` and `getAll()` for test assertions. Follows the existing rag-testing pattern.

No reactive CursorStore variant — the bridge is blocking-only.

No MetadataExtractor test stub — tests construct bindings with inline implementations or use `YamlFrontmatterExtractor` directly.

## Dependency Changes

**rag/ pom.xml — changes:**
```xml
<!-- REPLACE langchain4j-core with langchain4j (full artifact) -->
<!-- langchain4j transitively includes langchain4j-core -->
<!-- Needed: DocumentSplitters for chunking + EmbeddingModel/TextSegment from core -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
</dependency>

<!-- NEW: Corpus implementation — used only by CorpusBindingProducer -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-corpus</artifactId>
</dependency>

<!-- NEW: Quarkus scheduler — @Scheduled for polling loop -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-scheduler</artifactId>
</dependency>
```

**rag-api pom.xml — no changes.** rag-api retains its zero-dep invariant (Mutiny `provided` only).

**No new modules.** All changes are additions to existing modules.

## File Inventory

| Module | New files | Purpose |
|--------|-----------|---------|
| rag-api | `MetadataExtractor.java` | SPI |
| rag-api | `ExtractionResult.java` | Value type |
| rag-api | `CursorStore.java` | SPI |
| rag | `CorpusIngestionBinding.java` | Bridge input contract (references corpus-api types) |
| rag | `CorpusIngestionService.java` | Poll loop + reconciliation orchestrator |
| rag | `YamlFrontmatterExtractor.java` | `@DefaultBean` MetadataExtractor |
| rag | `FileCursorStore.java` | `@DefaultBean` CursorStore |
| rag | `IngestionConfig.java` | `@ConfigMapping` |
| rag | `IngestionMode.java` | Enum |
| rag | `CorpusBindingProducer.java` | Regular bean — builds bindings from config (design debt — extract to corpus-quarkus/ when second consumer appears) |
| rag-testing | `InMemoryCursorStore.java` | Test stub |

## Prerequisite Issues

| Issue | Description | Scope |
|-------|-------------|-------|
| TBD | Fix assertTenant 2-arg → 3-arg in QdrantEmbeddingIngestor, ReactiveQdrantEmbeddingIngestor, HybridCaseRetriever, ReactiveHybridCaseRetriever. Update protocol PP-20260529-57cc3b `applies_to` to include RAG adapter implementations. | S / Low — 10 call sites, 1 helper method per class, 1 protocol update |
| TBD | Update parent spec config keys: `casehub.rag.ingestion.garden.*` → `casehub.rag.ingestion.corpora.garden.*` | XS / Low |

## Follow-on Issues

| Issue | Description | Trigger |
|-------|-------------|---------|
| TBD | Extract corpus CDI integration to `corpus-quarkus/` module | When second consumer of corpus instances materialises |

## Coherence Review

| Check | Result |
|-------|--------|
| Module-tier-structure (PP-20260512) | rag-api stays Tier 1 (pure Java + Mutiny provided, zero new deps). CorpusIngestionBinding in rag/ (Tier 3) because it references corpus-api types. rag-testing @Alternative @Priority(1). |
| Reactive SPI bridge (PP-20260529-5745c1) | No reactive variants — background poller, all ops blocking. |
| Persistence-backend-cdi-priority | FileCursorStore @DefaultBean → InMemoryCursorStore @Alternative @Priority(1). Standard ladder. |
| assertTenant contract (PP-20260529-57cc3b) | Prerequisite fix extends the protocol's scope from CaseMemoryStore to include EmbeddingIngestor + CaseRetriever implementations. Same security principle, explicitly noted as a scope extension. Protocol `applies_to` update included in the prerequisite. |
| CursorStore as store SPI | File-based default is the production backend (analogous to casehub-platform-config). Cursor data is ephemeral — no persistence-memory/ module needed. |
| Plane separation (parent spec) | Bridge depends on CorpusReader + ChangeSource only. CorpusBindingProducer is the sole corpus/ dependent. |
| Platform capability ownership | Extends existing "Knowledge corpus retrieval (RAG)" capability. No new ownership entry. Deep-dive update tracked as parent#214. |
| Cross-repo deps | None new. corpus-api and corpus are in the same repo. |