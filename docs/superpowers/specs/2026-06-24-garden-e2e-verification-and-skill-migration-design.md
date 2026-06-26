# Garden E2E Verification & Skill Migration — Design Spec

*2026-06-24 · Issue #23*

Supersedes the skill integration sections of `2026-06-23-garden-mcp-skill-integration-design.md`.
That spec defined the engine-side changes (gardenSearch, gardenReindex, gardenStatus) and the
skill integration pattern. This spec covers the remaining work: proving the engine works
end-to-end against the real corpus and migrating the soredium skills.

## Problem

The engine's MCP tools (`gardenSearch`, `gardenReindex`, `gardenStatus`) were implemented
and unit-tested in #21, but have never run against the real garden corpus (~6,500 entries).
Skills still use `git grep` for knowledge retrieval. Two things are missing:

1. **Evidence that vector search is better than grep** — concrete comparison, not assumed.
2. **Skill migration** — soredium skills need garden consultation blocks that call
   `gardenSearch` with grep fallback.

## Scope

**In scope:**
- E2e verification: infrastructure setup, MCP transport verification, comparison benchmark
- Skill migration: `code-review`, `java-dev`, `python-dev`, `ts-dev`
- Comparison report artifact

**Out of scope:**
- Superpowers plugin skills (`brainstorming`, `systematic-debugging`) — third-party, not ours.
  These inherit garden context from work-start's Step 3b.
- Retrieval tracking (#24)
- `start-engine.sh` (deferred, separate spec)
- Corpus quality curation (depends on #24)
- Removing soredium Python MCP search server (next step, not this branch)
- `work-start` and `forage` — already updated in #21

## Phase 1: E2E Verification

### 1.1 Infrastructure setup

Prerequisites (all local, all manual for now):

1. **Qdrant:** `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant`
2. **Ollama:** running with `nomic-embed-text` pulled (`ollama pull nomic-embed-text`)
3. **Engine:** `./mvnw quarkus:dev` — watches `~/.hortora/garden/` by default

On first start, the engine indexes the full corpus. Watch logs for ingestion progress.

### 1.2 MCP transport verification

Configure the engine as an SSE MCP server in `~/.claude/settings.json`:

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

Verification sequence (manual, in a Claude Code session):

1. Call `gardenStatus` — confirm indexed entry count matches corpus (~6,500)
2. Call `gardenSearch` with a natural language query — confirm results include
   ID, domain, type, relevance metadata in the expected format
3. Call `gardenReindex` — confirm reset triggers, re-indexing completes
4. Stop engine — confirm `gardenSearch` becomes unavailable (expected MCP behaviour)

One-time verification. Not automated — the MCP transport is a thin wrapper over
`SearchResource.searchFor()`, which is already tested.

### 1.3 Comparison benchmark

**Goal:** Produce a concrete artifact comparing grep vs gardenSearch on the same
queries. Honest comparison — show where each approach excels.

**Speed is NOT the advantage.** `git grep` against the index is ~0.1–0.5s.
`gardenSearch` takes ~0.5–1s (Ollama embedding) + ~10ms (Qdrant HNSW) + network.
For single queries, grep is probably faster. The advantage is **relevance quality**:
semantic recall, ranking, natural language queries, cross-domain discovery.

**Comparison queries — three styles:**

| # | Query (gardenSearch) | Grep keywords | Style |
|---|---------------------|---------------|-------|
| 1 | `qdrant java client` | `qdrant.*client\|qdrant.*java` | Keywords — both should find entries; does vector rank better? |
| 2 | `quarkus MCP` | `quarkus.*mcp\|mcp.*quarkus` | Keywords — grep hits many; vector should rank actionable gotchas higher |
| 3 | `reactive thread scheduling problems` | `thread\|scheduling\|emitOn\|Mutiny` | Natural language — semantic recall for entries without "scheduling" |
| 4 | `test passes locally fails in CI` | `CI\|locally\|flaky\|test.*fail` | Symptom description — grep has no single good keyword |
| 5 | `CDI bean not found at runtime` | `CDI\|bean.*not.*found\|AmbiguousResolution\|LookupIfProperty` | Symptom → root cause mapping |
| 6 | `GE-20260609-2abdfd` | `GE-20260609-2abdfd` | Exact ID — grep finds the exact file; vector search treats the ID as text, produces a meaningless embedding, and returns unrelated results |

Keywords for grep are explicitly chosen to represent what a skill would realistically
extract. This makes the comparison fair — each method receives the input it would
actually get in practice.

**Implementation:** Python script at `scripts/benchmark-search.py`.

- Calls the engine's REST endpoint `GET /search?q=...` (same `SearchResource.searchFor()`
  code path as the MCP tool)
- Calls `git grep` against the garden repo with the defined keywords
- For each query, outputs: gardenSearch results (ID, title, relevance) and grep results
  (matching filenames)
- Writes a markdown comparison report

**Report output:** Committed alongside the spec as evidence. Includes per-query results
and a summary table showing unique hits per method.

## Phase 2: Skill Migration

### Design principles (from first-principles review)

1. **gardenSearch is additive, not a replacement.** Approach files (`code-review.md`,
   `testing.md`) are always-loaded reference documents. gardenSearch finds task-specific
   gotchas. Both are needed.

2. **Search once before work, not twice.** Pre-implementation search covers the primary
   domain. Post-implementation search was considered and rejected — code-review catches
   domain-specific issues before committing, and the LLM's natural tendency to explore
   available tools when encountering unfamiliar domains is sufficient. Adding prescriptive
   re-search triggers for every domain transition creates noise without proportional benefit.

3. **Inline fallback, not extracted.** The fallback pattern (3-4 lines) is duplicated
   across skills. This is acceptable — skills are standalone instructions, and a broken
   reference to a shared file would be worse than a few duplicated lines.

4. **"Warn once per session."** The fallback warning should appear once per conversation,
   not once per skill invocation. The LLM's conversation context is the session state —
   the instruction says "skip if already warned earlier in this conversation."

### 2.1 code-review

Insert into each language-specific review file (`java.md`, `typescript.md`,
`python.md`) after the diff is collected and technical domains are identified,
before the review checklist analysis begins:

```markdown
## Garden consultation

Search the garden for gotchas relevant to the technical domains in the diff.
Include domain filter if the changed files are domain-specific.

  Call `gardenSearch` with the primary technical domains in the changed files
  (e.g. "Quarkus CDI bean resolution", "reactive Mutiny chain").

Surface any relevant gotchas or techniques that bear on the code under review.

If `gardenSearch` is unavailable or returns an error, warn once per session
(skip if already warned earlier in this conversation):
  "⚠️ Garden MCP unavailable — using keyword fallback. Start engine per CLAUDE.md Dev Services."
  Then fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

The consultation lives in the language-specific files rather than the main
`code-review/SKILL.md` because the diff — and therefore the technical domains —
aren't available until the language-specific review workflow runs. Placing it
in `SKILL.md` between Step 1 (project type detection) and Step 2 (load checklist)
would force the LLM to search before the diff exists.

### 2.2 java-dev / python-dev / ts-dev

Insert in the `## Prerequisites` section, above the existing approach file load:

```markdown
## Prerequisites

Search the garden for the domain being implemented before writing code.
Include domain filter if the work targets a specific domain.

  Call `gardenSearch` with the technical topic (e.g. "LangChain4j embedding
  model CDI", "ONNX Runtime JNI native image", "asyncio gather error handling").

If `gardenSearch` is unavailable or returns an error, warn once per session
(skip if already warned earlier in this conversation):
  "⚠️ Garden MCP unavailable — using keyword fallback. Start engine per CLAUDE.md Dev Services."
  Then fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'

**Load `~/.hortora/garden/approaches/testing.md`** before proceeding.
```

The garden consultation is a prerequisite — same kind of thing as loading the
approach file. Keep prerequisites together.

The example search topics are tailored per skill:
- **java-dev:** Java/Quarkus/CDI examples
- **python-dev:** asyncio/pytest examples
- **ts-dev:** TypeScript/Node examples

### 2.3 Cross-repo commits

Skill changes land in soredium, not the engine repo. Commits reference
`Hortora/engine#23` as a cross-repo reference. After committing to soredium,
run `sync-local` to install updated skills.

## Deliverables

| # | What | Repo | Commit ref |
|---|------|------|-----------|
| 1 | Comparison benchmark script | engine `scripts/benchmark-search.py` | `Refs #23` |
| 2 | Comparison report | engine `docs/comparison/garden-search-vs-grep.md` | `Refs #23` |
| 3 | code-review garden consultation (java.md, typescript.md, python.md) | soredium | `Refs Hortora/engine#23` |
| 4 | java-dev garden consultation | soredium | `Refs Hortora/engine#23` |
| 5 | python-dev garden consultation | soredium | `Refs Hortora/engine#23` |
| 6 | ts-dev garden consultation | soredium | `Refs Hortora/engine#23` |
| 7 | Final close | engine | `Closes #23` |

## Success Criteria

- Engine starts, indexes ~6,500 entries, responds to gardenSearch via MCP
- Comparison report demonstrates: vector search finds relevant entries grep misses
  (semantic recall), ranks results by relevance (grep cannot), handles natural language
  queries (grep requires keyword extraction)
- Comparison is honest: acknowledges grep is faster for single queries, grep wins
  for exact ID lookups
- code-review, java-dev, python-dev, ts-dev have garden consultation blocks
- Fallback to git grep works when engine is unavailable
- Fallback warning appears once per session, not per skill invocation

## What This Spec Does NOT Cover

- Superpowers plugin skill edits (brainstorming, systematic-debugging) — not ours
- Retrieval tracking (#24)
- start-engine.sh orchestration (separate spec)
- Corpus quality/pruning (depends on #24)
- Retiring soredium Python MCP search server (next step)
