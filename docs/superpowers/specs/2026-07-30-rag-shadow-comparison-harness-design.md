# RAG Shadow Comparison Harness

**Date:** 2026-07-30
**Status:** Draft
**Issue:** #58
**Scope:** Temporary comparison harness — remove when evaluation is complete

## Purpose

Determine whether the Hortora RAG engine can replace Claude's adaptive grep-based garden search. Run the RAG engine as a shadow alongside Claude's existing grep behaviour, log both result sets, and compare effectiveness post-process.

If RAG consistently matches or exceeds Claude's multi-step grep results, retire the grep-based approach from all soredium skills.

## Current State

Since the skill migration in #23, soredium skills search the garden via a two-tier pattern:

1. **Primary:** `gardenSearch` MCP tool (the engine's adaptive semantic search)
2. **Fallback:** `git -C $GARDEN grep -il -E "kw1|kw2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'`

All garden-searching skills — `work-start`, `java-dev`, `python-dev`, `ts-dev`, `code-review` (java/python/typescript), and `forage` — plus skills that delegate to forage SEARCH (`brainstorming`, `work-resume`, `systematic-debugging`) use gardenSearch as primary. The grep fallback fires only when the engine is unavailable (not running, MCP not configured, or returning errors).

However, Claude also performs **ad-hoc garden searches** outside of skill patterns — during exploration, investigation, and brainstorming. In these cases, no skill directs it to use gardenSearch, so it defaults to grep. Claude's ad-hoc grep loop is adaptive: it runs 1..n grep calls with keyword refinement, reads matched entries, and judges relevance between calls. These ad-hoc searches are the primary target of this harness.

A controlled benchmark (#27, `docs/comparison/grep-vs-gardensearch.md`) already compared grep vs gardenSearch across 14 scenarios with synthetic queries. Results: gardenSearch won 11 of 14 on precision, but grep found 153 unique relevant entries gardenSearch missed (vs 92 in the other direction). The recommendation was deferred (#59) — the mixed results didn't clearly support retiring grep.

This harness provides a different signal: **real-world query distribution**. Instead of predefined queries, it captures the actual grep patterns Claude uses during real work and measures whether a single RAG call would have found the same entries. This answers the question the synthetic benchmark couldn't: how often does RAG cover what grep finds in practice?

The RAG engine performs five-signal retrieval (dense + sparse + BM25 + ColBERT + cross-encoder reranking) in a single REST call. The question is whether one RAG call matches what Claude needs multiple adaptive grep calls to find.

## Architecture

Three components, no skill changes required:

```
Claude's grep loop (unchanged)
        │
        ▼
┌─────────────────┐     ┌──────────────────┐
│  PostToolUse     │────▶│  rag_shadow.py   │
│  Hook (Bash)     │     │  (hook handler)  │
└─────────────────┘     └────────┬─────────┘
                                 │
                    Records grep cmd + output
                    Appends keywords to pending
                    Spawns debouncer (if not running)
                                 │
                                 ▼
                        ┌────────────────┐
                        │ rag_fire.py    │
                        │ (debouncer)    │
                        └────────┬───────┘
                                 │
                    Waits for grep quiescence (30s)
                    Combines all keywords
                    Fires ONE RAG REST call
                    Logs both result sets
                                 │
                                 ▼
                        ~/.hortora/logs/
                        rag-comparison.jsonl
```

### Component 1: PostToolUse Hook

A `PostToolUse` hook on Bash tool calls. Receives `tool_input.command` and `tool_output` via stdin JSON.

**Detection:** pattern-match command for `git.*grep` where the path contains the garden directory (`${HORTORA_GARDEN:-~/.hortora/garden}`). The script resolves the garden path from the environment at startup.

**On match:**
1. Extract the grep regex pattern (the `-E` argument)
2. Extract grep results from `tool_output` (file paths, one per line)
3. Append a record to `~/.hortora/tmp/rag_pending.jsonl`
4. Spawn debouncer if not already running (check PID file)
5. Exit 0 (never block)

**On no match:** exit 0 immediately. The hook must be fast for non-garden commands.

**Hook configuration** (`~/.claude/settings.json`):

```json
{
  "PostToolUse": [
    {
      "matcher": "Bash",
      "hooks": [
        {
          "type": "command",
          "command": "~/.hortora/tools/rag_shadow.sh"
        }
      ]
    }
  ]
}
```

### Component 2: Hook Handler (`rag_shadow.sh`)

Shell wrapper that reads stdin, checks for garden grep pattern, and delegates to Python:

```bash
#!/bin/bash
INPUT=$(cat)
CMD=$(echo "$INPUT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null)

# Fast exit for non-garden grep commands.
# Check for garden path (both $HOME and ~ forms) AND git grep with -il flags.
# The -il pattern distinguishes garden file-listing grep from git log --grep.
GARDEN_ABS="${HORTORA_GARDEN:-$HOME/.hortora/garden}"
{ echo "$CMD" | grep -qF "$GARDEN_ABS" || echo "$CMD" | grep -qF '/.hortora/garden'; } && echo "$CMD" | grep -qE 'git\b.*\bgrep\b.*-[a-zA-Z]*i[a-zA-Z]*l' || exit 0

# Delegate to Python handler (backgrounded for zero latency impact)
echo "$INPUT" | python3 ~/.hortora/tools/rag_shadow.py &
exit 0
```

### Component 3: Python Handler (`rag_shadow.py`)

Receives the full hook JSON on stdin. Responsibilities:

1. **Extract session ID:** use `os.getppid()` — the parent process PID uniquely identifies the Claude session
2. **Extract keywords:** parse the grep `-E` pattern from the command string
3. **Extract grep results:** parse file paths from `tool_output`
4. **Append to pending file:** write `{timestamp, session_id, keywords, grep_results}` to `~/.hortora/tmp/rag_pending.jsonl`
5. **Spawn debouncer:** acquire an exclusive `flock` on `~/.hortora/tmp/rag_fire.lock`, check if `~/.hortora/tmp/rag_fire.pid` exists and the process is alive, spawn `rag_fire.py` if not, release lock. The flock prevents concurrent hook invocations from spawning duplicate debouncers.

### Component 4: Debouncer (`rag_fire.py`)

Background process that waits for the grep loop to finish, then fires one RAG call per session.

**Logic:**
1. Write PID to `~/.hortora/tmp/rag_fire.pid`
2. Loop: sleep 10s, check mtime of `~/.hortora/tmp/rag_pending.jsonl`
3. When pending file hasn't been modified for `RAG_SHADOW_QUIESCENCE` seconds (default: 60, configurable via environment variable) — grep loop is done:
   a. Read all records from the pending file
   b. **Group by `session_id`** — each session gets its own comparison entry
   c. Per session: combine keywords (union of all grep patterns, strip regex syntax, join with spaces)
   d. Per session: fire REST call `GET http://localhost:8080/search?q=<combined_keywords>`
   e. Per session: write comparison record to `~/.hortora/logs/rag-comparison.jsonl`
   f. Clear the pending file
   g. Remove PID file
   h. Exit

**Quiescence window:** 60 seconds by default. Claude's adaptive grep loop interleaves grep calls with file reads and LLM reasoning, which can introduce 30–60 second gaps between grep calls. The 60-second default avoids mid-loop firing for typical loops while keeping latency reasonable. Override via `RAG_SHADOW_QUIESCENCE` environment variable if needed.

**Timeout:** if the pending file is older than 5 minutes, fire anyway and clean up. Prevents stale state from a crashed session.

**Engine unavailable:** if the REST call fails (connection refused, timeout), log `"rag_status": "unavailable"` and still record the grep data. The comparison entry exists with grep results but no RAG results.

## Log Format

`~/.hortora/logs/rag-comparison.jsonl` — one JSON object per line, one entry per search intent per session:

```json
{
  "timestamp": "2026-07-30T10:15:03Z",
  "session_id": "48201",
  "grep_calls": [
    {
      "command": "git -C ~/.hortora/garden grep -il -E 'BlockingOperationNotAllowedException|Vert.x' HEAD -- '*.md'",
      "keywords": "BlockingOperationNotAllowedException|Vert.x",
      "results": ["reactive/GE-20260428-a67806.md", "reactive/GE-20260519-4a42e6.md"],
      "timestamp": "2026-07-30T10:14:30Z"
    },
    {
      "command": "git -C ~/.hortora/garden grep -il -E 'IO thread|event loop' HEAD -- '*.md'",
      "keywords": "IO thread|event loop",
      "results": ["reactive/GE-20260518-bee1b3.md"],
      "timestamp": "2026-07-30T10:14:45Z"
    }
  ],
  "grep_union": ["GE-20260428-a67806", "GE-20260519-4a42e6", "GE-20260518-bee1b3"],
  "rag_query": "BlockingOperationNotAllowedException Vert.x IO thread event loop",
  "rag_results": [
    {"id": "GE-20260428-a67806", "title": "Reactive thread scheduling gotcha", "relevance": 0.92, "crossEncoderScore": 0.87},
    {"id": "GE-20260518-bee1b3", "title": "IO thread detection in Vert.x", "relevance": 0.87, "crossEncoderScore": 0.82},
    {"id": "GE-20260519-4a42e6", "title": "Event loop blocking detection", "relevance": 0.84, "crossEncoderScore": null},
    {"id": "GE-20260526-399a43", "title": "Mutiny operator fusion", "relevance": 0.79, "crossEncoderScore": null}
  ],
  "rag_status": "ok",
  "rag_latency_ms": 1200
}
```

**Field mapping from `SearchResult` API:**
- `id` — from `SearchResult.id()` (normalized, see below)
- `title` — from `SearchResult.title()`
- `relevance` — from `SearchResult.relevance()` (dense vector similarity, `double`)
- `crossEncoderScore` — from `SearchResult.crossEncoderScore()` (nullable `Double`, omit when null)
- `body` is **not logged** — full entry content is irrelevant for comparison and would bloat the log

`SearchResult.score()` is a human quality assessment (`int`, 0–3), not a retrieval score. It is not logged.

**ID normalization:** both grep paths and RAG result IDs are normalized to bare GE IDs for comparison. grep returns paths like `reactive/GE-20260428-a67806.md` — strip the directory prefix and `.md` extension. `SearchResult.id()` returns paths like `jvm/GE-20260428-a67806.md` — apply the same normalization. The logic mirrors `GardenMcpTools.extractDocumentId()`: strip directory prefix, strip `.md` extension, validate the `GE-YYYYMMDD-XXXXXX` pattern. The comparison operates on these bare IDs.

## Post-Process Analysis

Once 30+ comparison entries accumulate (lower threshold than originally planned — ad-hoc grep is less frequent than all-grep was pre-#23), score them:

**Coverage:** For each entry, did RAG find everything grep found? `grep_union ⊆ rag_results`?

**Discovery:** Did RAG find relevant entries grep missed? `rag_results - grep_union` — manually review for relevance.

**Efficiency:** RAG makes 1 call. How many grep calls did Claude need for the same (or worse) results?

**Precision:** Of RAG's results, what fraction are relevant? (Same scoring approach as the #27 benchmark.)

**Decision framework:** The #27 benchmark showed grep finding 153 unique relevant entries gardenSearch missed across synthetic scenarios. A fixed 90% coverage threshold would be arbitrary without accounting for this prior evidence. Instead, evaluate the harness results as follows:

1. If RAG coverage of ad-hoc grep results is **consistently high** (≥80% of entries show full coverage), the data supports that RAG handles the real-world query distribution well — even though synthetic benchmarks showed gaps. The difference would indicate that grep's synthetic advantage doesn't manifest in practice.
2. If RAG coverage is **mixed** (50–80%), analyse which query patterns fall short. This informs whether targeted retrieval improvements (e.g., better BM25 sparse matching) could close the gap.
3. If RAG coverage is **poor** (<50%), the harness has demonstrated that grep remains necessary for ad-hoc searches and the retirement decision should wait for retrieval quality improvements.

This harness measures RAG recall with keyword input. If RAG-with-keywords achieves high coverage, RAG-with-natural-language (the actual gardenSearch experience) would do at least as well — keyword input is a strictly harder retrieval task than natural language (#27 data: NL avg 88% vs KW avg 87%).

## What This Does NOT Change

- Skill text — no skills are modified
- Claude's behaviour — Claude still greps exactly as before
- gardenSearch MCP — the existing MCP integration is unaffected
- Engine code — no engine changes needed (uses existing `/search` endpoint)

## Deployment

1. Write `rag_shadow.sh`, `rag_shadow.py`, `rag_fire.py` to `~/.hortora/tools/`
2. Create `~/.hortora/logs/` and `~/.hortora/tmp/` directories
3. Add PostToolUse hook entry to `~/.claude/settings.json`
4. Ensure engine is running (`./mvnw quarkus:dev` or systemd service)

## Removal

Delete the three scripts, remove the hook entry from settings.json, delete the log/tmp files. Zero residue.

## Trade-offs

**RAG gets keywords, not natural language.** The hook extracts grep regex patterns, not the skill's NL context. This is a stricter test — if RAG with just keywords beats adaptive grep, NL input would only widen the gap. The #27 benchmark supports this: NL queries consistently match or exceed keyword queries in precision (NL avg 88% vs KW avg 87%). If the keyword-based comparison is ambiguous, a second phase could add NL context via optional JSONL logging in the gardenSearch MCP tool itself (engine-side config flag, no skill changes needed).

**Basic `/search` endpoint, not adaptive.** The harness calls the basic `SearchResource.search()` endpoint, not `searchAdaptive()` which the MCP gardenSearch tool uses. This is intentional: the harness measures RAG's **recall ceiling** — can the retrieval engine find the entries? Adaptive filtering (score floor, gap detection, result extension) can only reduce the result set. If raw retrieval misses an entry, no amount of post-filtering helps. If raw retrieval finds it, the adaptive pipeline can be tuned separately. Logging both `relevance` and `crossEncoderScore` allows post-process simulation of adaptive filtering without re-running the harness.

**60-second debounce window.** Claude's adaptive grep loop interleaves grep calls with file reads and LLM reasoning that can introduce 30–60 second gaps. The 60-second default avoids mid-loop firing for most loops. Configurable via `RAG_SHADOW_QUIESCENCE` environment variable if real-world usage reveals longer gaps.

**Ad-hoc grep only.** The harness captures grep calls outside of skill patterns. Skill-mediated grep (the fallback path) fires when the engine is down, so the harness's REST call would also fail. This is a feature, not a bug — the harness measures the value of RAG for the searches where it's actually available as an alternative. Sample size will be lower than if grep were still the primary path; the 30-entry threshold reflects this.

**Engine must be running.** If the engine is down, grep data is logged but RAG results are empty (`"rag_status": "unavailable"`). This is informative (shows how often the engine is available) but doesn't produce comparison data.
