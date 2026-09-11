# Corpus Storage Module Design

**Date:** 2026-06-11
**Status:** Approved (rev 5 — post round-4 review)
**Tracks:** casehubio/neural-text (new modules: `corpus-api/`, `corpus/`, plus ingestion bridge in `rag/`)

## Problem

casehub-neural-text provides a RAG pipeline (embedding, Qdrant, hybrid search) but has no document storage layer. Consumers must manage their own document lifecycle — storage, versioning, change tracking, distribution — before feeding documents into the RAG pipeline.

The Hortora knowledge garden (~1,900 entries today, potentially millions at scale) is the first consumer. Other casehub domains (legal, clinical, compliance) have the same pattern: a corpus of text documents that need ingestion, versioning, change tracking, and semantic retrieval.

## Design Principles

1. **Corpus module has zero dependency on RAG/LangChain4j/Qdrant** — pure document storage
2. **ZIP is the primary store** — flat files are a mirror, not the source of truth (see §ZIP-as-Primary Justification)
3. **Append-only with compaction** — no in-place modification in the hot path; tombstone-based deletion; periodic compaction reclaims space
4. **Chain integrity** — verifiable linked sequence of ZIP archives with external manifest as source of truth
5. **Developer UX** — configure a corpus in properties, inject a ready-to-use service
6. **Per-instance tenancy** — each CorpusStore instance is inherently single-scope; multi-tenant deployments use multiple instances

## Prerequisite: Rename Existing CorpusStore

The existing `io.casehub.rag.CorpusStore` pushes pre-chunked text into Qdrant via `ingest(CorpusRef, List<ChunkInput>)`. It is an embedding ingestor, not a corpus store. The name is misleading.

**Rename before implementing this spec:**

| Current | New |
|---------|-----|
| `io.casehub.rag.CorpusStore` | `io.casehub.rag.EmbeddingIngestor` |
| `io.casehub.rag.ReactiveCorpusStore` | `io.casehub.rag.ReactiveEmbeddingIngestor` |
| `QdrantCorpusStore` | `QdrantEmbeddingIngestor` |
| `ReactiveQdrantCorpusStore` | `ReactiveQdrantEmbeddingIngestor` |
| `BlockingToReactiveCorpusStore` | `BlockingToReactiveEmbeddingIngestor` |
| `InMemoryCorpusStore` (rag-testing) | `InMemoryEmbeddingIngestor` |
| `InMemoryReactiveCorpusStore` (rag-testing) | `InMemoryReactiveEmbeddingIngestor` |

19 references across 7 files — all internal to this repo. Mechanical migration. The breakage is the point: every caller becomes explicit about whether they're storing documents or pushing embeddings.

## Module Structure

```
corpus-api/     — SPI + value types. Zero dependencies. Hortora-eligible.
corpus/         — Zip4j implementation. Depends on corpus-api + zip4j. Hortora-eligible.
rag/            — Ingestion bridge additions. Depends on corpus-api + existing RAG infrastructure.
```

### Layer Placement

| Layer | Module | Tier | Hortora? |
|---|---|---|---|
| L1 | `inference-api`, `inference-inmem` | SPI Foundation | yes |
| L2 | `inference-runtime` | Runtime Core | yes |
| L3 | `inference-tasks` | Task Adapters | yes |
| L4 | `inference-splade` | Sparse Embeddings | yes |
| L5 | `inference-quarkus` | Quarkus Integration | casehub only |
| L6 | `rag-api`, `rag-testing` | RAG SPI | casehub only |
| L7 | `rag`, `rag-tika` | RAG Runtime | casehub only |
| **L8** | **`corpus-api`** | **Corpus SPI — Pure Java, zero deps** | **yes** |
| **L9** | **`corpus`** | **Corpus Runtime — Zip4j** | **yes** |

L8 and L9 are peer layers to L6/L7, not below them. Both are Hortora-eligible (domain-free, zero casehub deps in the SPI). Same selective dependency model as inference-* (AD-001).

**Repo placement decision:** stays in neural-text. Extract trigger: when a non-neural-text, non-Hortora repo needs corpus-api.

### Architecture

```
Document producers (forage, harvest, etc.)
        |
        |  (write .md files directly to flat filesystem — no SPI call)
        |
   corpus/ COMPOSITE mode (L9)
        |  CompositeChangeSource.changesSince():
        |    1. sync: detect new/modified flat files, write them into ZIP (internal)
        |    2. report: scan ZIP state post-sync, return changes
        |  CorpusReader reads from ZIP (always consistent post-sync)
        |
   corpus-api SPI (L8)
        |
   rag/ CorpusIngestionService (L7 — read -> chunk -> embed -> Qdrant)
        |  depends on CorpusReader + ChangeSource only (never CorpusStore)
        |
   Qdrant (vectors + metadata payloads)
```

## corpus-api — SPI

### CorpusStore (write — data plane)

```java
public interface CorpusStore {
    void append(String path, byte[] content);
    void append(String path, InputStream content);
    void append(String path, Path file);
    void delete(String path);
}
```

Size guidance: `byte[]` for documents under 10MB. `InputStream`/`Path` for larger documents (PDFs, Office docs via Tika).

No metadata parameter — metadata is extracted from content by `MetadataExtractor` in the ingestion bridge (YAML frontmatter for garden entries, Tika for PDFs/Office). If out-of-band metadata is needed later (e.g. binary files), it can be added as a coherent feature: `append()` with metadata + `metadata()` on `CorpusReader` + ZIP persistence mechanism.

**Path validation:** paths starting with `_` are reserved for internal use (`_chain/`, `_tombstones/`). `append()` throws `IllegalArgumentException` for reserved paths.

`delete(path)` records a tombstone — the document is marked deleted in the index. Physical removal happens during compaction.

### CorpusReader (read — data plane)

```java
public interface CorpusReader {
    Optional<byte[]> read(String path);
    Optional<InputStream> readStream(String path);
    Optional<byte[]> readVersion(String path, int version);
    List<VersionInfo> versions(String path);
    List<String> list();
    List<String> list(String domainPrefix);
    boolean exists(String path);
}
```

`readStream()` for large document reads without loading into heap.

`readVersion()` always returns `byte[]` — no streaming variant for historical versions. Version reads are rare (DEDUPE, audit) and the documents most likely to have version history (garden entries) are small. A streaming version read may be added if the use case materialises.

`readVersion()` / `versions()` — older versions may be unavailable after FULL compaction (see §Compaction Modes).

### ChangeSource (delta plane)

```java
public interface ChangeSource {
    ChangeSet changesSince(String cursor);
    ChangeSet fullScan();
}

public record ChangeSet(List<ChangedEntry> entries, String newCursor) {}
public record ChangedEntry(String path, ChangeType type) {}
public enum ChangeType { ADDED, MODIFIED, DELETED }
```

`DELETED` enables the ingestion bridge to sync deletions to Qdrant.

In COMPOSITE mode, `changesSince()` first **syncs** new/modified flat files into the ZIP (internal write, not via the public `CorpusStore` SPI), then scans the combined ZIP state post-sync. This sync-then-report model ensures `CorpusReader` always finds synced content in the ZIP. The sync is internal to `CompositeChangeSource` in corpus/ — the caller only sees changes from a consistent store.

This is how forage writes enter the ZIP chain without forage calling the SPI (see §Forage Integration).

### CorpusIntegrity (ops plane)

```java
public interface CorpusIntegrity {
    IntegrityReport check();
    IntegrityReport checkAndRecover();
    IntegrityReport fullHashVerification();
}
```

- `check()` — read-only, runs on startup. Reports issues via logging and health endpoint.
- `checkAndRecover()` — explicit administrative action. Modifies ZIPs. Never called during boot.
- `fullHashVerification()` — explicit, expensive. Never automatic.

### Reactive Variants

```java
public interface ReactiveCorpusStore { /* Mutiny Uni<Void> mirrors of CorpusStore */ }
public interface ReactiveCorpusReader { /* Mutiny Uni mirrors of CorpusReader */ }
public interface ReactiveChangeSource { /* Mutiny Uni mirrors of ChangeSource */ }
```

Follows established repo pattern (every blocking SPI has a reactive counterpart). Implementation in `corpus/` provides blocking-to-reactive bridges offloading to the worker pool.

### Value Types

```java
public record VersionInfo(int version, Instant timestamp, String zipFile) {}

public record IntegrityReport(
    String corpusName,
    int chainLength,
    long totalEntries,
    String status,
    List<IntegrityIssue> issues,
    List<String> recovered
) {}

public record IntegrityIssue(Severity severity, String zipFile, String message) {}

public enum Severity { INFO, WARNING, ERROR }
```

### What is NOT in corpus-api

- `StorageMode` (ZIP/FLAT/COMPOSITE) — implementation config, lives in `corpus/`
- `IngestionMode` (AUTO/MANUAL/NONE) — RAG pipeline config, lives in `rag/`
- `IdStrategy` — index-builder config, lives in `corpus/`
- `CorpusIdentity` / configuration records — implementation config, lives in `corpus/`

The SPI contains only interfaces and value types. No configuration records.

### Interface Separation Justification

Three planes, each independently implementable:

| Plane | Interfaces | Why separate |
|-------|-----------|-------------|
| Data | `CorpusStore`, `CorpusReader` | Write vs read separation. Ingestion bridge needs only `CorpusReader` + `ChangeSource`, never `CorpusStore`. Write/read split enables read-only consumers. |
| Delta | `ChangeSource` | Independently implementable. Could be backed by git commit log, filesystem events, or ZIP central directory diffs. The ingestion bridge depends on this + `CorpusReader`, not on `CorpusStore`. In COMPOSITE mode, `CompositeChangeSource` syncs flat files into ZIP internally before reporting — the caller only sees a consistent post-sync state. |
| Ops | `CorpusIntegrity` | Administrative. No document consumer uses this. Operates on chain manifest, not on documents. Separate deployment concern. |

Implementation split: `ZipCorpusStore` (data plane), `ZipChangeSource` (delta plane), `ZipIntegrityChecker` (ops plane).

## corpus — Implementation

### Dependencies

- `corpus-api`
- `net.lingala.zip4j:zip4j:2.11.6` (Apache-2.0, zero transitive deps)

### Configuration (implementation-level, not SPI)

```properties
# Storage mode
casehub.corpus.garden.source=/path/to/garden
casehub.corpus.garden.mode=composite        # ZIP, FLAT, COMPOSITE
casehub.corpus.garden.max-zip-size=104857600

# ID strategy (optional — path-based lookup always available)
casehub.corpus.garden.id-strategy=frontmatter
casehub.corpus.garden.id-field=id
casehub.corpus.garden.id-pattern=GE-\\d{8}-[0-9a-f]{6}
```

### Tenancy Model

Per-instance scoping. Each `CorpusStore` instance is a single corpus. No tenant identity in the SPI. Multi-tenant deployments use separate `CorpusStore` instances per `(tenant, corpus)` pair. The ingestion bridge maps `CorpusRef(tenantId, corpusName)` to the correct `CorpusStore` instance.

### Rolling ZIP Manager

**ZIP lifecycle:**

1. **Startup** — read `chain.json`, scan ZIP central directories, build in-memory master index. Run `CorpusIntegrity.check()` (detection only, no recovery).
2. **Append** — write entry to active ZIP via Zip4j. Check size threshold after write.
3. **Delete** — record tombstone in active ZIP (a marker entry at `_tombstones/<path>.deleted`). Update index. Physical removal happens during compaction.
4. **Rollover** — close active ZIP (append `_chain/meta.json` with all metadata), write new `chain.json` atomically (temp file + rename), create new active ZIP.
5. **Compaction** — rewrite a closed ZIP according to compaction mode (see §Compaction Modes). Generates a new UUID; old UUID retired in `chain.json`.
6. **Shutdown** — flush, update `chain.json`.

### Append-Only With Compaction

Garden entries are written once and revised occasionally (forage REVISE when new knowledge enriches an existing entry). Each revision appends a new version at the same path. The old version becomes orphaned bytes — still in the ZIP, still accessible via version history.

Over time, orphaned bytes accumulate. **Compaction** reclaims space by rewriting closed ZIPs. Compaction is:

- **Explicit** — triggered by admin action or scheduled maintenance, not automatic
- **Per-ZIP** — each closed ZIP can be compacted independently
- **Safe** — writes to a new temp ZIP, verifies, then replaces the original atomically

### Compaction Modes

| Mode | Removes tombstones | Removes old versions | Version history preserved |
|------|-------------------|---------------------|--------------------------|
| `TOMBSTONES_ONLY` (default) | Yes | No | Yes |
| `FULL` | Yes | Yes, keeps only latest | No — `readVersion()` for compacted versions returns empty |

Default: `TOMBSTONES_ONLY`. `FULL` is an explicit, intentional choice for archival/distribution where space matters more than history.

**Compaction and chain integrity:** compaction generates a **new UUID** for the rewritten ZIP. The old UUID is retired in `chain.json` with a `replacedBy` reference:

```json
{
  "uuid": "old-uuid",
  "status": "compacted",
  "replacedBy": "new-uuid"
}
```

This preserves the integrity property: every UUID's contentHash is immutable once written. Compaction doesn't mutate — it replaces. A receiver with a pre-compaction ZIP can see from `chain.json` that it was replaced and download the new version.

### Crash Safety

ZIP append via Zip4j is **not atomic**. Appending involves three sequential writes (local file header + data, central directory rewrite, end-of-central-directory). A crash mid-central-directory-rewrite can leave the active ZIP unreadable.

Crash safety is provided by the chain model:
- **Closed ZIPs** are complete and verified — their contentHash in `chain.json` confirms integrity
- **Active ZIP** is the only one at risk during a crash. Only entries since the last rollover may be lost.
- **COMPOSITE mode** — flat files serve as a recovery source for active ZIP content. If the active ZIP is corrupted, its entries can be reconstructed from the flat file mirror.
- **Integrity check on startup** — `check()` detects active ZIP corruption and reports it. Admin can reconstruct from flat files or accept the loss.

Mitigation: keep rollover size threshold reasonable so the window of at-risk entries is bounded.

### Chain Manifest (`chain.json`)

External file alongside the ZIPs. Atomic source of truth for chain state. Written via temp file + rename.

```json
{
  "corpus": "garden",
  "schemaVersion": 1,
  "chain": [
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "file": "garden-001.zip",
      "sequence": 0,
      "status": "closed",
      "predecessor": null,
      "entryCount": 5200,
      "cumulativeEntryCount": 5200,
      "contentHash": "sha256:abc123...",
      "domains": {"tools": 2100, "jvm": 1800, "quarkus": 1300},
      "dateRange": {"earliest": "2026-01-15", "latest": "2026-03-22"}
    }
  ]
}
```

Forward links (successor) are derived from the chain array order — not stored redundantly per entry.

### Internal ZIP Metadata

Inside each closed ZIP, `_chain/meta.json` is a **copy** of the chain entry for self-describing distribution. Written once at close time when all metadata is known. Not written to active ZIPs.

### ZIP States

| State | Has `_chain/meta.json` | Meaning |
|-------|------------------------|---------|
| Active | No | Current append target |
| Closed | Yes, `status: "closed"` | No more entries, chain continues |
| Sealed | Yes, `status: "sealed"` | Final ZIP in a frozen chain |

### Rollover Atomicity

Closing ZIP N and opening ZIP N+1 is atomic via `chain.json`:

1. Write new `chain.json` atomically (temp + rename) — ZIP N closed, ZIP N+1 active
2. Append `_chain/meta.json` to ZIP N (can fail — `chain.json` is the truth)
3. Create ZIP N+1 on disk

If the process crashes at any point, startup integrity check detects and recovers from `chain.json` or from the ZIPs themselves.

### Master Index (in-memory)

```java
Map<String, EntryLocation> pathIndex;     // path -> (zipFile, latest)
Map<String, List<EntryLocation>> history; // path -> all versions, ordered
Set<String> tombstones;                   // deleted paths
```

Built on startup from ZIP central directories. Rebuilt from ZIPs if lost.

### Version Numbering

Versions are 1-based, dense, path-scoped. Ordering determined by `(zip_sequence, central_directory_offset)` — files within a ZIP are ordered by append time.

If `tools/git-rebase.md` is appended three times — twice in ZIP-001 and once in ZIP-003 — versions are 1, 2, 3. The master index history map stores all three locations across ZIPs.

After FULL compaction of ZIP-001, versions 1 and 2 are gone. `versions(path)` returns `[3]` only. `readVersion(path, 1)` returns empty. This is documented in the SPI.

### Composite Mode

`CompositeCorpusStore` wraps `ZipCorpusStore` + `FlatCorpusStore`:

- **SPI writes** (`CorpusStore.append()`) fan out to both ZIP and flat filesystem
- **Reads** come from ZIP (authoritative, always consistent post-sync)
- **Flat→ZIP sync** — handled internally by `CompositeChangeSource` before reporting changes. Not visible to consumers.

| Mode | Read from | Write to | ChangeSource behaviour |
|------|-----------|----------|----------------------|
| `ZIP` | ZIP | ZIP only | Scans ZIP only |
| `FLAT` | filesystem | filesystem only | Scans filesystem only |
| `COMPOSITE` | ZIP | ZIP + filesystem | Syncs flat→ZIP internally, then scans ZIP |

The flat-file-to-ZIP sync flow in COMPOSITE mode:
1. External writer (forage) creates/modifies a `.md` file in the flat directory
2. Next `ChangeSource.changesSince()` poll triggers
3. `CompositeChangeSource` internally syncs new/modified flat files into ZIP (direct write to `ZipCorpusStore` internals — not via the public `CorpusStore` SPI)
4. `CompositeChangeSource` scans ZIP state post-sync, reports changes
5. Ingestion bridge reads from `CorpusReader` (finds content in ZIP) → embeds → Qdrant
6. ZIP and flat file are consistent; ingestion bridge never touches `CorpusStore`

### Integrity System

Startup runs `check()` only (detection, read-only). Recovery via `checkAndRecover()` is explicit admin action.

**Detection matrix:**

| Check | Detects | Severity | Recovery (admin-triggered) |
|-------|---------|----------|----------|
| ZIP in directory, not in `chain.json` | Orphaned ZIP | WARNING | Link to chain via predecessor |
| Entry in `chain.json`, ZIP missing | Gap in chain | ERROR | Report: sequence N missing, M entries lost |
| Internal `_chain/meta.json` missing | Crash before copy written | INFO | Reconstruct from `chain.json`, append to ZIP |
| Internal meta disagrees with `chain.json` | Corruption/partial write | WARNING | `chain.json` wins, rewrite internal copy |
| Predecessor UUID mismatch | Broken chain link | ERROR | Report broken link |
| Content hash mismatch (opt-in) | Tampered ZIP | ERROR | Report tampered ZIP |
| Entry count mismatch | Partial write/corruption | WARNING | Report expected vs actual |
| `chain.json` missing entirely | Lost manifest | WARNING | Reconstruct from internal `_chain/meta.json` across all ZIPs |
| Active ZIP corrupted | Crash during append | ERROR | Report; in COMPOSITE mode, flat files are recovery source |
| Flat file not in ZIP (COMPOSITE) | External write not yet synced | INFO | Report; synced on next ChangeSource poll |

### ID-Based Lookup (optional)

When configured with an `id-strategy`, the master index also builds `ID -> (zip, path)` mappings. Path-based lookup always works regardless.

## rag/ — Ingestion Bridge

### CorpusIngestionService

New class in `rag/` module. Wires `ChangeSource` to the existing embedding + Qdrant infrastructure.

**Configuration (lives in rag/, prefix `casehub.rag.ingestion.*`):**

```properties
casehub.rag.ingestion.corpora.garden.mode=auto           # AUTO, MANUAL, NONE
casehub.rag.ingestion.interval=30s
casehub.rag.ingestion.corpora.garden.chunking=none
casehub.rag.ingestion.corpora.legal.chunking=recursive
casehub.rag.ingestion.corpora.legal.chunking-max-size=300
casehub.rag.ingestion.corpora.legal.chunking-overlap-size=30
```

**Dependencies:** `CorpusReader` + `ChangeSource` only. Never `CorpusStore`. The ingestion bridge is a read-only consumer of the corpus — flat→ZIP sync is handled internally by corpus/ in COMPOSITE mode.

**Flow:**

1. Poll `ChangeSource.changesSince(cursor)` on configurable schedule (in COMPOSITE mode, this triggers internal flat→ZIP sync before reporting)
2. For each `ADDED`/`MODIFIED`: read from `CorpusReader`, extract metadata, chunk, embed, push to Qdrant
3. For each `DELETED`: remove vectors from Qdrant
4. Persist cursor (simple file alongside corpus)
5. Reconciliation: `ChangeSource.fullScan()` -> compare against Qdrant -> report/fix gaps

### MetadataExtractor SPI

```java
public interface MetadataExtractor {
    Map<String, String> extract(String path, byte[] content);
}
```

Default: YAML frontmatter parser. Consumers provide their own for other document types.

### Chunking

Delegates to LangChain4j `DocumentSplitter`. `none` = whole document body is one chunk.

## ZIP-as-Primary Justification

Why ZIP-as-truth rather than flat-files-as-truth with optional ZIP export:

1. **Distribution** — ZIPs are the distribution format. If flat files are truth, every distribution requires a full export. If ZIPs are truth, distribution is "ship the files."
2. **Versioning** — ZIP's central directory naturally supports multiple versions at the same path. Flat files require git or a separate versioning system.
3. **Scale** — at 1M files, flat file operations degrade (filesystem stat, directory scanning). ZIP central directories are compact indexes — scanning 10 ZIPs takes milliseconds.
4. **Integrity** — content hashes, chain verification, and tamper detection are built into the ZIP chain model. Flat files have no equivalent without external tooling.

COMPOSITE mode preserves flat file accessibility for browsing, grep, and IDE access while ZIPs hold the truth. Crash safety for the active ZIP comes from the chain model and COMPOSITE mode's flat file recovery source — not from ZIP write atomicity (see §Crash Safety).

## Chain Model Justification

Why UUID-linked chain rather than standalone ZIPs with a flat registry:

1. **Integrity verification** — each ZIP's `_chain/meta.json` carries its predecessor UUID. A receiver can verify the chain is complete and unbroken without trusting the manifest alone.
2. **Incremental sync** — "I have up to UUID X" is precise. "I have files 1-5" relies on filename conventions that could be wrong.
3. **Tamper detection** — content hashes per ZIP, linked by UUIDs. If a ZIP is swapped or modified, the hash breaks. Compaction generates new UUIDs rather than mutating existing ones, preserving hash immutability.
4. **Sealed chains** — a `sealed` status on the final ZIP tells receivers "this is a complete, frozen corpus snapshot." Without it, a receiver can't distinguish "still being written" from "author forgot to send the last file."

Consumers: garden distribution, Hortora knowledge corpus, casehub application-tier corpora (legal, clinical, compliance document libraries).

## Integration Path for Existing Garden

### Migration

`CorpusMigrator.migrate(Path sourceDir, CorpusStore target)` — scans flat `.md` files, preserves directory structure as path prefixes (e.g. `tools/GE-20260412-2523eb.md`), appends each to the corpus store. One-time operation. The first thing Issue 2 tests.

### Forage Integration

Forage does not change. In COMPOSITE mode, `CompositeChangeSource.changesSince()` detects new/modified flat files, syncs them into ZIP internally, and reports the changes. The ingestion bridge reads the synced content from `CorpusReader` (ZIP), embeds, and pushes to Qdrant. The flat file write IS the inbox. The poll IS the detection. The sync is invisible.

Later: forage can optionally be updated to call the corpus SPI directly. At that point, COMPOSITE mode becomes optional.

### Harvest Integration

No changes required. DEDUPE and REVIEW currently read entries via `git show`. They can migrate to `CorpusReader` in a follow-on change. Not gating.

## Maven Coordinates

| Module | artifactId |
|--------|-----------|
| Corpus API | `casehub-corpus-api` |
| Corpus | `casehub-corpus` |

Ingestion bridge lives in existing `casehub-rag`.

## Implementation Sequence

**Issue 1 (Session 1):** Rename `CorpusStore` → `EmbeddingIngestor` across rag-api, rag, rag-testing

**Issue 2 (Session 2):** `corpus-api` + `corpus`
- SPI interfaces and value types (blocking + reactive)
- `ZipCorpusStore` with Zip4j append
- `ZipChangeSource` (delta plane) + `CompositeChangeSource` (sync-then-report in COMPOSITE mode)
- `ZipIntegrityChecker` (ops plane, separate class)
- Rolling ZIPs with size threshold and rollover atomicity
- Chain manifest (`chain.json`) with atomic writes
- Internal `_chain/meta.json` for self-describing distribution
- Master index from central directories, version numbering
- Tombstone-based deletion
- Compaction modes (TOMBSTONES_ONLY, FULL) with UUID replacement
- Composite mode (ZIP + flat files) with internal flat→ZIP sync in CompositeChangeSource
- Path validation: reject `_` prefix (reserved for `_chain/`, `_tombstones/`)
- `FlatCorpusStore` implementation
- `CorpusMigrator` for garden onboarding
- Unit tests, integration tests

**Issue 3 (Session 3):** Ingestion bridge in `rag/`
- `CorpusIngestionService` wiring `ChangeSource` + `CorpusReader` to RAG pipeline (no `CorpusStore` dependency)
- `MetadataExtractor` SPI + YAML frontmatter default implementation
- Chunking configuration per corpus (prefix: `casehub.rag.ingestion.corpora.<name>.*`)
- Cursor persistence
- Deletion sync to Qdrant
- Reconciliation mode (full scan vs Qdrant)
- Integration tests with Qdrant

## Upstream Dependencies

Track quarkus-langchain4j composition annotations (casehubio/neural-text#16). When `@DocumentIngestion` ships, the ingestion bridge can adopt it. When `@Corpus` qualifier ships, multi-corpus CDI injection simplifies.
