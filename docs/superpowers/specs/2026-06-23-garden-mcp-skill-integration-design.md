# Garden MCP Skill Integration — Design Spec

*2026-06-23 · Revised after review (round 3)*

## Problem

Skills currently use `git grep` against the garden repo for knowledge retrieval. This is keyword-based — it misses semantically relevant entries that don't contain the exact search terms. The Hortora engine already provides semantic vector search over the garden corpus via MCP tools (`gardenSearch`, `gardenStatus`), but no skill uses it.

Additionally, soredium already contains a Python MCP server (`garden_mcp_server.py`) wrapping `mcp_garden_search.py` (228 lines, 3-tier keyword search). This server is keyword-based and superseded by the engine's hybrid vector search. The spec must address this overlap directly.

## Decision

The engine is the single search authority for the garden corpus. Skills call the engine's `gardenSearch` MCP tool directly — no Python script intermediary. When the engine is unavailable, skills fall back to inline `git grep` as a degraded search.

The soredium Python MCP server (`garden_mcp_server.py`) is deprecated for search. Its `garden_capture` tool remains (write path — different concern) until capture moves to the engine.

## Architecture

```
┌─────────────────────────────────────────────┐
│  Skills (work-start, brainstorming, etc.)   │
│  "call gardenSearch MCP tool"               │
└──────────────────┬──────────────────────────┘
                   │ MCP tool call (native)
┌──────────────────▼──────────────────────────┐
│  Engine MCP Server (Quarkus, SSE)           │
│  gardenSearch — hybrid vector search        │
│  gardenStatus — index health                │
│  gardenReindex — bulk re-index              │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  SearchResource → CaseRetriever → Qdrant    │
│  + Federation (ChainWalker)                 │
└─────────────────────────────────────────────┘

Fallback (when engine MCP unavailable):
  Skills use inline git grep — 3 lines in skill instruction, not a script.
```

### Why not a Python script layer

The original spec proposed `garden_search.py` as an abstraction between skills and the engine. This is the wrong layer:

- Skills are instructions for an LLM. The LLM already has a native mechanism for calling external services: MCP tools.
- The engine already exposes `gardenSearch` as an MCP tool. The LLM would have it available in any Claude Code session where the engine is configured.
- A Python script that shells out to REST to reach the same `SearchResource.searchFor()` that `GardenMcpTools.gardenSearch()` calls is needless indirection.
- The MCP tool returns markdown-formatted results that the LLM consumes natively. No custom text format to parse.

### Consolidation — one search path

Before this spec, five paths to the same corpus existed:

| Path | Transport | Search type | Status |
|------|-----------|-------------|--------|
| soredium Python MCP server | stdio MCP | Keyword (3-tier) | **Deprecated for search** |
| Engine Quarkus MCP server | SSE MCP | Vector (hybrid) | **Primary** |
| Engine REST API `/search` | HTTP | Vector (hybrid) | Internal (federation) |
| Inline `git grep` in skills | Shell | Keyword (raw) | **Degraded fallback** |

After this spec: engine MCP for search, `git grep` as fallback. Two paths, clearly ordered.

### Separation of concerns

- **Retrieval** — engine MCP `gardenSearch` tool (this spec)
- **Submission** — stays git-based. `garden_capture` in soredium's Python MCP server continues to work (write path, different concern). The engine's filesystem watcher re-indexes after commits.
- **Storage** — git is the source of truth today; Qdrant is a derived search index. The corpus source abstraction (`casehub-corpus-api`) allows the storage layer to change independently.

## Engine Changes

### 1. Approach doc frontmatter

Add YAML frontmatter to files in `~/.hortora/garden/approaches/` so the `GardenMetadataExtractor` indexes them:

```yaml
---
title: "Testing — Principles"
domain: approaches
type: reference
tags: [testing, tdd, coverage]
submitted: "2026-05-29"
---
```

`domain: approaches` and `type: reference` distinguish these from regular entries.

Score is not inflated. The `score` field is a human quality assessment — it should not be manipulated to influence retrieval ranking. If approach documents need boosting in search results, the retrieval layer should boost by `type: reference` as a semantic property. More likely, vector similarity handles ranking correctly without any boost — if the doc is relevant to the query, it ranks naturally.

Note: the approaches folder may be partially superseded by protocols. Whatever is in the garden with valid frontmatter gets indexed. Content curation is a separate concern — the engine indexes what's there.

### 2. gardenSearch output format — enrich with metadata

`GardenMcpTools.gardenSearch()` currently drops entry ID, domain, type, and relevance from the output. Skills need entry IDs to reference specific entries in design decisions. The output format must be enriched.

**Current output:**
```
## [own] Hibernate lazy loading fails outside transaction

body content...
```

**Required output:**
```
## [own] Hibernate lazy loading fails outside transaction
**ID:** GE-20260518-d1e4b2 · **Domain:** jvm · **Type:** gotcha · **Relevance:** 0.82

body content...
```

**Implementation:** Change the stream map in `gardenSearch()`:

```java
.map(r -> "## " + provenanceLabel(r) + " " + r.title()
        + "\n**ID:** " + extractDocumentId(r.id()) + " · **Domain:** " + r.domain()
        + " · **Type:** " + r.type() + " · **Relevance:** " + String.format("%.2f", r.relevance())
        + "\n\n" + stripTitlePrefix(r.title(), r.body()))
```

`SearchResult.id()` is `chunk.sourceDocumentId()` from `FlatChangeSource` — the file path relative to the garden root (e.g. `jvm/GE-20260518-d1e4b2.md` or `approaches/testing.md`). `extractDocumentId()` handles both:

- **GE entries:** detects the `GE-YYYYMMDD-xxxxxx` pattern, strips directory prefix and `.md` suffix → `GE-20260518-d1e4b2`
- **Other documents:** strips only the `.md` suffix, keeps the directory → `approaches/testing`

```java
private String extractDocumentId(String path) {
    String withoutExt = path.replaceFirst("\\.md$", "");
    String filename = withoutExt.contains("/") ? withoutExt.substring(withoutExt.lastIndexOf('/') + 1) : withoutExt;
    if (filename.matches("GE-\\d{8}-[0-9a-f]{6}")) {
        return filename;
    }
    return withoutExt;
}
```

**Title duplication fix:** `GardenMetadataExtractor.extract()` prepends the title to the body for embedding quality (`combinedContent = title + "\n\n" + body`). This `combinedContent` becomes `RetrievedChunk.content()` → `SearchResult.body()`. Then `gardenSearch()` prepends the title again as a markdown heading. Every result starts with the title twice.

Fix: `stripTitlePrefix(title, body)` — if `body` starts with `title + "\n\n"`, strip that prefix. This is a one-line method:

```java
private String stripTitlePrefix(String title, String body) {
    if (title != null && body.startsWith(title + "\n\n")) {
        return body.substring(title.length() + 2);
    }
    return body;
}
```

### 3. gardenReindex MCP tool

Add a `gardenReindex` tool to `GardenMcpTools`. This shares the existing re-index logic from `CollectionMigration.onStartup()` rather than duplicating it.

**Refactoring:** Extract the delete-and-reset logic from `CollectionMigration` into a shared method:

```java
// Shared re-index logic (used by both CollectionMigration and gardenReindex)
public void reindex(CorpusRef corpusRef, String gardenId) {
    embeddingIngestor.deleteCorpus(corpusRef);
    cursorStore.save(gardenId, "");
    // CorpusIngestionService picks up the empty cursor and runs a full scan
}
```

`CollectionMigration.onStartup()` calls this when it detects a dense-only collection. `gardenReindex` calls it on demand.

**Behaviour:**
- Synchronous — blocks until the collection is deleted and cursor is reset. The actual re-embedding happens asynchronously via `CorpusIngestionService`'s next scan cycle.
- Returns: `"Reindex triggered for garden '<id>'. Collection deleted, cursor reset. Re-embedding will complete on next ingestion cycle (<N> files in corpus)."`
- For a garden with hundreds of entries, the delete+reset is near-instant. The re-embedding (Ollama dense + SPLADE sparse) takes seconds to low minutes depending on corpus size.

### 4. Deprecate soredium Python MCP search

`garden_mcp_server.py`'s `garden_search` tool is superseded by the engine's `gardenSearch`. The deprecation path:

1. **This spec:** skills stop referencing the Python MCP server for search. Skills call the engine's `gardenSearch` directly.
2. **Next:** remove `garden_search` from `garden_mcp_server.py`. The server retains `garden_capture` and `garden_status` (write path and status — different concerns).
3. **Future:** when submission moves to the engine, `garden_mcp_server.py` is fully retired.

`mcp_garden_search.py` (228 lines, 3-tier keyword search) is retained as a standalone script for any non-MCP use case, but skills do not call it.

### 5. start-engine.sh

Deferred to a separate spec. Orchestrating Docker (Qdrant), Ollama, ONNX models, and Quarkus in an idempotent script is non-trivial DevOps — port conflicts, health checks, foreground/background process management. A docker-compose approach for Qdrant + Ollama is likely the right path, with the engine running separately.

For this spec, the fallback warning in skills references the existing manual setup documented in CLAUDE.md's Dev Services section.

## Claude Code MCP Configuration

Configure the engine as an SSE MCP server in `.claude/settings.json`:

```json
{
  "mcpServers": {
    "hortora-garden": {
      "type": "sse",
      "url": "http://localhost:8080/mcp/sse"
    }
  }
}
```

The engine is long-running — started once, left running. Claude Code connects on session start. If the engine isn't running, `gardenSearch` is not available, and skills fall back to `git grep`.

The soredium Python MCP server (`garden_mcp_server.py`) should be removed from MCP configuration for search. If `garden_capture` is still needed, it can remain configured separately.

## Skill Integration Pattern

### Search triggers

Skills search the garden at two clear moments:

1. **On entry to the skill** — search for the current topic/domain before starting work.
2. **When a new technical domain surfaces** — if the work reveals a domain not covered by previously gathered entries, search again.

A third trigger exists for debugging:

3. **After two failed fix attempts** — circuit breaker. Stop grinding, search the garden for the symptom/domain, then retry with any relevant knowledge.

"Every phase boundary" is too aggressive — most phase transitions within a skill don't change the technical domain. The LLM either searches every time (wasteful) or rationalises skipping (defeating the purpose). Two clear triggers plus the debugging circuit breaker are precise and actionable.

### Accumulating set

The LLM maintains a running set of relevant garden entries across phases. The set grows when new domains surface, thins when approaches are rejected, and stabilises as the design solidifies.

**Context compression recovery:** If context compression drops previously gathered entries, re-search for the current topic. The search is sub-second; re-searching is the recovery mechanism. No scratch file or persistence layer is needed — the cost of a redundant search is negligible compared to the complexity of maintaining a side-channel.

### Fallback pattern

When the `gardenSearch` MCP tool is unavailable (engine not running, MCP not configured) or returns an error (engine is up but Qdrant is down, embedding model unavailable), skills use inline git grep. This is three lines in the skill instruction, not a separate script:

```
Search the garden for entries related to the current topic:
1. Call the gardenSearch MCP tool (preferred — semantic vector search)
2. If gardenSearch is unavailable or returns an error, warn once:
   "⚠️ Garden MCP unavailable — using keyword fallback. Start engine per CLAUDE.md Dev Services."
   Then use:
   git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

## Skill Changes — Exact Diffs

### work-start (Step 3b — Garden search)

**Before (current):**
```
### Step 3b — Garden search

Search the garden for GEs relevant to the domain being worked...

    git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -i "<keyword1>\|<keyword2>" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'

If matches found: surface the GE filenames and titles to the user...
```

**After:**
```
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

### brainstorming

**Insert after the existing "Explore project context" section:**
```
## Garden Consultation

On entry to brainstorming, review the garden context set from work-start.
If no set exists (brainstorming invoked without work-start), search the garden
for the topic being brainstormed:

  Call `gardenSearch` with a natural language description of the problem/feature.

As the conversation explores approaches, watch for new technical domains not
covered by the current set. When one surfaces, search again:

  Call `gardenSearch` with the specific technical topic (e.g. "reactive Mutiny
  thread scheduling", "CDI LookupIfProperty qualifier").

Reference specific entries (by GE-ID) when they bear on design decisions.

If `gardenSearch` is unavailable or returns an error, fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'

If context compression has dropped previously gathered entries, re-search for
the current topic.
```

### systematic-debugging

**Insert after the "Reproduce the bug" step:**
```
## Garden Consultation

Before attempting a fix, search the garden for the symptom and domain:

  Call `gardenSearch` with a description of the symptom (e.g. "Uni.createFrom
  supplier runs on wrong thread", "Qdrant collection missing sparse vectors").

Review results for matching gotchas, known workarounds, or related techniques.

**Circuit breaker:** After two failed fix attempts, stop and search again with
the refined understanding of the root cause. The garden may have an entry that
matches the actual problem rather than the initial symptom.

If `gardenSearch` is unavailable or returns an error, fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

### code-review

**Insert at the start of the review process (before reviewing the diff):**
```
## Garden Consultation

Before reviewing, search the garden for the domains touched by the diff:

  Call `gardenSearch` with the primary technical domains in the changed files
  (e.g. "Quarkus CDI bean resolution", "reactive Mutiny chain").

Surface any relevant gotchas or techniques that bear on the code under review.

If `gardenSearch` is unavailable or returns an error, fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

### java-dev / python-dev / ts-dev

**Insert before the implementation step:**
```
## Garden Consultation

Before implementing, search the garden for the domain being worked:

  Call `gardenSearch` with the technical topic (e.g. "LangChain4j embedding
  model CDI", "ONNX Runtime JNI native image").

After implementation, if the changed code touches a domain with known gotchas,
search again to verify the implementation doesn't hit a known issue.

If `gardenSearch` is unavailable or returns an error, fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

### forage (SEARCH operation)

**Before (current):**
```
git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep "keywords" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

**After:**
```
Call the `gardenSearch` MCP tool with the user's search query.

If `gardenSearch` is unavailable or returns an error, fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

## Testing

### Automated test

1. Start engine with test garden (subset of real entries with known content)
2. Verify `gardenSearch` MCP tool returns semantically relevant results for natural language queries that would miss keyword grep (e.g. "thread scheduling problems" finds an entry titled "Uni.createFrom().item(supplier) with emitOn()")
3. Verify `gardenReindex` MCP tool triggers re-index (collection deleted, cursor reset, re-embedding completes)
4. Verify `gardenStatus` returns correct entry count after re-index
5. Stop engine — verify `gardenSearch` tool becomes unavailable (expected MCP behaviour)

### Live session demo

1. Start engine against real garden
2. Configure Claude Code MCP (`hortora-garden` SSE server)
3. Run `work-start` on an issue in a domain with known garden entries
4. Verify: work-start calls `gardenSearch` and surfaces relevant entries
5. Run brainstorming — verify garden entries are referenced when relevant technical domains emerge
6. Demonstrate: a query that returns relevant results via vector search but would return nothing via keyword grep (the semantic advantage)

## What This Does NOT Cover

- **Submission/write path** — stays git-based via soredium's `garden_capture`. Evolves independently.
- **Approaches folder curation** — whether approaches are stale, duplicated by protocols, etc. Separate concern.
- **Content storage migration** — git → database/object store. The corpus source abstraction handles this.
- **Federation topology** — already implemented in the engine. This spec adds skill integration, not federation changes.
- **start-engine.sh** — deferred to separate spec. Non-trivial DevOps that deserves its own design.
- **Full retirement of garden_mcp_server.py** — depends on when `garden_capture` moves to the engine. This spec deprecates search only.

## Success Criteria

- `gardenSearch` output includes entry ID, domain, type, and relevance — skills can reference entries by GE-ID
- Title duplication eliminated — each entry's title appears once, not twice
- Skills call `gardenSearch` MCP tool directly — no Python intermediary in the call chain
- Fallback to `git grep` works as inline skill instruction when engine is unavailable or errors
- `gardenReindex` shares logic with `CollectionMigration` — no duplication
- Soredium Python MCP server deprecated for search; skills no longer reference it for search
- Live demo: a brainstorming session references garden entries by GE-ID that keyword grep would not have found
- Automated test: vector search returns semantically relevant results with full metadata for queries that miss keyword matching
