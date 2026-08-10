# Garden E2E Verification & Skill Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the engine works end-to-end against the real garden corpus and migrate soredium skills from `git grep` to `gardenSearch` MCP tool.

**Architecture:** Two-phase delivery. Phase 1: Python benchmark script that calls the engine REST API and `git grep` on the same queries, producing a markdown comparison report. Phase 2: Edit soredium skill files to add `gardenSearch` consultation blocks with `git grep` fallback.

**Tech Stack:** Python 3 (benchmark script), Markdown (skill edits), Engine REST API (`GET /search?q=`), soredium skill files.

## Global Constraints

- Engine commits reference `#23` (`Refs #23` ongoing, `Closes #23` final)
- Soredium commits reference `Hortora/engine#23`
- Python benchmark script uses only stdlib (no pip dependencies)
- All skill edits go to `~/claude/hortora/soredium/`, then `sync-local` to install
- Garden consultation blocks include domain filter guidance, matching work-start's phrasing
- Fallback warning: "once per session" — instruction says "skip if already warned earlier in this conversation"

---

### Task 1: Benchmark Script

**Files:**
- Create: `scripts/benchmark_search.py`
- Create: `scripts/test_benchmark_search.py`

**Interfaces:**
- Consumes: Engine REST API `GET /search?q={query}` returning JSON array of `{id, title, domain, type, relevance, ...}`
- Produces: Markdown report at `docs/comparison/garden-search-vs-grep.md`

- [ ] **Step 1: Write failing tests for report formatting**

```python
"""Tests for benchmark_search report formatting."""
import pytest
from benchmark_search import (
    Query, SearchResult, QueryResult, format_report, QUERIES,
)


def _query(name: str = "test query") -> Query:
    return Query(name=name, search_query=name, grep_pattern="test", style="test style")


def _search_result(doc_id: str = "GE-20260101-abc123", title: str = "Test Entry",
                   domain: str = "jvm", type_: str = "gotcha",
                   relevance: float = 0.85) -> SearchResult:
    return SearchResult(doc_id=doc_id, title=title, domain=domain, type=type_, relevance=relevance)


class TestFormatReport:
    def test_includes_title(self) -> None:
        report = format_report([])
        assert "# Garden Search vs Grep — Comparison Report" in report

    def test_per_query_section_with_results(self) -> None:
        qr = QueryResult(
            query=_query("qdrant java client"),
            search_results=[_search_result()],
            grep_files=["HEAD:jvm/GE-20260101-abc123.md"],
        )
        report = format_report([qr])
        assert "### Query 1: `qdrant java client`" in report
        assert "GE-20260101-abc123" in report
        assert "jvm/GE-20260101-abc123.md" in report

    def test_search_error_displayed(self) -> None:
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=[],
            search_error="Connection refused",
        )
        report = format_report([qr])
        assert "ERROR — Connection refused" in report

    def test_grep_error_displayed(self) -> None:
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=[],
            grep_error="git not found",
        )
        report = format_report([qr])
        assert "ERROR — git not found" in report

    def test_empty_results_shown(self) -> None:
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=[],
        )
        report = format_report([qr])
        assert "No results" in report
        assert "No matches" in report

    def test_summary_table_counts(self) -> None:
        qr = QueryResult(
            query=_query("test"),
            search_results=[_search_result(), _search_result(doc_id="GE-20260102-def456")],
            grep_files=["HEAD:a.md", "HEAD:b.md", "HEAD:c.md"],
        )
        report = format_report([qr])
        assert "| 1 | `test` | 2 | 3 |" in report

    def test_grep_files_stripped_of_head_prefix(self) -> None:
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=["HEAD:jvm/test.md"],
        )
        report = format_report([qr])
        lines_after_grep = report.split("grep results")[1]
        assert "`jvm/test.md`" in lines_after_grep
        assert "HEAD:" not in lines_after_grep

    def test_grep_files_truncated_at_20(self) -> None:
        files = [f"HEAD:jvm/GE-{i:08d}-000000.md" for i in range(25)]
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=files,
        )
        report = format_report([qr])
        assert "... and 5 more" in report


class TestQueryDefinitions:
    def test_six_queries_defined(self) -> None:
        assert len(QUERIES) == 6

    @pytest.mark.parametrize("idx", range(6))
    def test_query_has_required_fields(self, idx: int) -> None:
        q = QUERIES[idx]
        assert q.name
        assert q.search_query
        assert q.grep_pattern
        assert q.style
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest scripts/test_benchmark_search.py -v`
Expected: ImportError — `benchmark_search` module does not exist yet.

- [ ] **Step 3: Implement the benchmark script**

```python
#!/usr/bin/env python3
"""Compare gardenSearch (vector) vs git grep (keyword) on the same queries."""

import json
import subprocess
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

GARDEN_PATH = Path.home() / ".hortora" / "garden"
ENGINE_URL = "http://localhost:8080"


@dataclass
class Query:
    name: str
    search_query: str
    grep_pattern: str
    style: str


@dataclass
class SearchResult:
    doc_id: str
    title: str
    domain: str
    type: str
    relevance: float


@dataclass
class QueryResult:
    query: Query
    search_results: list[SearchResult]
    grep_files: list[str]
    search_error: str | None = None
    grep_error: str | None = None


QUERIES = [
    Query("qdrant java client", "qdrant java client",
          r"qdrant.*client|qdrant.*java",
          "Keywords — both should find entries; does vector rank better?"),
    Query("quarkus MCP", "quarkus MCP",
          r"quarkus.*mcp|mcp.*quarkus",
          "Keywords — grep hits many; vector should rank actionable gotchas higher"),
    Query("reactive thread scheduling problems",
          "reactive thread scheduling problems",
          r"thread|scheduling|emitOn|Mutiny",
          "Natural language — semantic recall for entries without 'scheduling'"),
    Query("test passes locally fails in CI",
          "test passes locally fails in CI",
          r"CI|locally|flaky|test.*fail",
          "Symptom description — grep has no single good keyword"),
    Query("CDI bean not found at runtime",
          "CDI bean not found at runtime",
          r"CDI|bean.*not.*found|AmbiguousResolution|LookupIfProperty",
          "Symptom → root cause mapping"),
    Query("GE-20260609-2abdfd", "GE-20260609-2abdfd",
          r"GE-20260609-2abdfd",
          "Exact ID — grep finds the exact file; vector search returns unrelated results"),
]


def search_engine(query: str, base_url: str = ENGINE_URL) -> list[SearchResult]:
    url = f"{base_url}/search?q={urllib.parse.quote(query)}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    return [
        SearchResult(
            doc_id=r.get("id", ""),
            title=r.get("title", ""),
            domain=r.get("domain", ""),
            type=r.get("type", ""),
            relevance=r.get("relevance", 0.0),
        )
        for r in data
    ]


def grep_garden(pattern: str, garden_path: Path = GARDEN_PATH) -> list[str]:
    result = subprocess.run(
        ["git", "-C", str(garden_path), "grep", "-il", "-E", pattern,
         "HEAD", "--", "*.md",
         ":!GARDEN.md", ":!CHECKED.md", ":!DISCARDED.md"],
        capture_output=True, text=True, timeout=30,
    )
    if result.returncode not in (0, 1):
        raise RuntimeError(f"git grep failed: {result.stderr}")
    return [line.strip() for line in result.stdout.strip().split("\n")
            if line.strip()]


def run_benchmark(
    base_url: str = ENGINE_URL,
    garden_path: Path = GARDEN_PATH,
) -> list[QueryResult]:
    results: list[QueryResult] = []
    for q in QUERIES:
        search_results: list[SearchResult] = []
        search_error: str | None = None
        grep_files: list[str] = []
        grep_error: str | None = None
        try:
            search_results = search_engine(q.search_query, base_url)
        except Exception as e:
            search_error = str(e)
        try:
            grep_files = grep_garden(q.grep_pattern, garden_path)
        except Exception as e:
            grep_error = str(e)
        results.append(QueryResult(q, search_results, grep_files,
                                   search_error, grep_error))
    return results


def format_report(results: list[QueryResult]) -> str:
    lines = [
        "# Garden Search vs Grep — Comparison Report\n",
        "*Generated from `scripts/benchmark_search.py`*\n",
        "## Per-Query Results\n",
    ]
    for i, r in enumerate(results, 1):
        lines.append(f"### Query {i}: `{r.query.name}`\n")
        lines.append(f"**Style:** {r.query.style}\n")
        lines.append(f"- **gardenSearch query:** `{r.query.search_query}`")
        lines.append(f"- **grep pattern:** `{r.query.grep_pattern}`\n")

        if r.search_error:
            lines.append(f"**gardenSearch:** ERROR — {r.search_error}\n")
        elif r.search_results:
            lines.append("**gardenSearch results:**\n")
            lines.append("| # | ID | Title | Domain | Relevance |")
            lines.append("|---|-----|-------|--------|-----------|")
            for j, sr in enumerate(r.search_results, 1):
                lines.append(
                    f"| {j} | {sr.doc_id} | {sr.title} | {sr.domain}"
                    f" | {sr.relevance:.2f} |")
            lines.append("")
        else:
            lines.append("**gardenSearch:** No results\n")

        if r.grep_error:
            lines.append(f"**grep:** ERROR — {r.grep_error}\n")
        elif r.grep_files:
            lines.append(f"**grep results:** {len(r.grep_files)} files\n")
            for f in r.grep_files[:20]:
                display = f.removeprefix("HEAD:")
                lines.append(f"- `{display}`")
            if len(r.grep_files) > 20:
                lines.append(f"- ... and {len(r.grep_files) - 20} more")
            lines.append("")
        else:
            lines.append("**grep:** No matches\n")

    lines.append("## Summary\n")
    lines.append("| # | Query | gardenSearch hits | grep hits | Style |")
    lines.append("|---|-------|-------------------|-----------|-------|")
    for i, r in enumerate(results, 1):
        s_count = str(len(r.search_results)) if not r.search_error else "ERROR"
        g_count = str(len(r.grep_files)) if not r.grep_error else "ERROR"
        lines.append(
            f"| {i} | `{r.query.name}` | {s_count} | {g_count}"
            f" | {r.query.style} |")
    lines.append("")

    return "\n".join(lines)


def main() -> None:
    print("Running benchmark...")
    results = run_benchmark()
    report = format_report(results)

    output_path = (Path(__file__).parent.parent
                   / "docs" / "comparison" / "garden-search-vs-grep.md")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(report)
    print(f"Report written to {output_path}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/test_benchmark_search.py -v`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/benchmark_search.py scripts/test_benchmark_search.py
git commit -m "feat: add benchmark script comparing gardenSearch vs grep

Refs #23"
```

---

### Task 2: Skill Migration

**Files:**
- Modify: `~/claude/hortora/soredium/code-review/java.md` — insert Garden Consultation section
- Modify: `~/claude/hortora/soredium/code-review/python.md` — insert Garden Consultation section
- Modify: `~/claude/hortora/soredium/code-review/typescript.md` — insert Garden Consultation section
- Modify: `~/claude/hortora/soredium/java-dev/SKILL.md` — add garden search to Prerequisites
- Modify: `~/claude/hortora/soredium/python-dev/SKILL.md` — add garden search to Prerequisites
- Modify: `~/claude/hortora/soredium/ts-dev/SKILL.md` — add garden search to Prerequisites

**Interfaces:**
- Consumes: `gardenSearch` MCP tool (engine must be configured as SSE MCP server)
- Produces: Updated skill instructions with gardenSearch + git grep fallback

The same pattern is applied to all files with minor variations in example queries.

- [ ] **Step 1: Edit code-review language files**

Insert a `## Garden Consultation` section in each file between `## Workflow` and the severity/checklist sections. The block goes after the workflow description (where the diff is collected) and before the review analysis begins.

**code-review/java.md** — insert after the Workflow section (after line 28 "Step 2 uses the Java Review Checklist below."):

```markdown
## Garden Consultation

After collecting the diff (Step 1), search the garden for gotchas relevant
to the technical domains in the changed files. Include domain filter if the
changed files are domain-specific.

  Call `gardenSearch` with the primary technical domains in the changed files
  (e.g. "Quarkus CDI bean resolution", "reactive Mutiny chain").

Surface any relevant gotchas or techniques that bear on the code under review.

If `gardenSearch` is unavailable or returns an error, warn once per session
(skip if already warned earlier in this conversation):
  "⚠️ Garden MCP unavailable — using keyword fallback. Start engine per CLAUDE.md Dev Services."
  Then fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'
```

**code-review/python.md** — insert after line 25 ("Step 2 uses the Python Review Checklist below."):

Same block, different examples:
```
  (e.g. "asyncio gather error handling", "pytest fixture scope gotchas").
```

**code-review/typescript.md** — insert after line 25 ("Step 2 uses the TypeScript Review Checklist below."):

Same block, different examples:
```
  (e.g. "TypeScript strict null checks", "Promise.allSettled error handling").
```

- [ ] **Step 2: Edit *-dev skill files**

In each `SKILL.md`, modify the `## Prerequisites` section to add garden search above the existing `testing.md` load.

**java-dev/SKILL.md** — replace the Prerequisites section:

```markdown
## Prerequisites

Search the garden for the domain being implemented before writing code.
Include domain filter if the work targets a specific domain.

  Call `gardenSearch` with the technical topic (e.g. "LangChain4j embedding
  model CDI", "ONNX Runtime JNI native image").

If `gardenSearch` is unavailable or returns an error, warn once per session
(skip if already warned earlier in this conversation):
  "⚠️ Garden MCP unavailable — using keyword fallback. Start engine per CLAUDE.md Dev Services."
  Then fall back to:
  git -C ${HORTORA_GARDEN:-~/.hortora/garden} grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'

**Load `~/.hortora/garden/approaches/testing.md`** before proceeding.
Apply all principles from that file.

Also apply all rules from **`ide-tooling`**: IntelliJ MCP tool guide — which tool to use for rename, move, find-references, navigation, diagnostics.
```

**python-dev/SKILL.md** — same pattern, different examples:
```
  (e.g. "asyncio gather error handling", "pytest parametrize fixtures").
```

**ts-dev/SKILL.md** — same pattern, different examples:
```
  (e.g. "TypeScript strict mode migration", "Vitest mock patterns").
```

- [ ] **Step 3: Sync and verify**

Run: `sync-local`
Verify: the installed skills in `~/.claude/skills/` match the soredium source.

- [ ] **Step 4: Commit to soredium**

```bash
git -C ~/claude/hortora/soredium add code-review/java.md code-review/python.md code-review/typescript.md java-dev/SKILL.md python-dev/SKILL.md ts-dev/SKILL.md
git -C ~/claude/hortora/soredium commit -m "feat: add gardenSearch consultation to code-review and *-dev skills

Refs Hortora/engine#23"
```

---

### Task 3: E2E Verification & Report

This task requires running infrastructure. It produces the comparison report artifact.

- [ ] **Step 1: Start infrastructure**

Prerequisites (verify each is running):
1. Qdrant: `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant`
2. Ollama: running with `nomic-embed-text` pulled
3. Engine: `./mvnw quarkus:dev`

Wait for the engine to complete initial corpus indexing (~6,500 entries). Watch logs for completion.

- [ ] **Step 2: MCP transport verification**

In a Claude Code session with the engine configured as SSE MCP server (`hortora-garden` in `~/.claude/settings.json`):

1. Call `gardenStatus` — confirm indexed entry count ~6,500
2. Call `gardenSearch` with "qdrant java client" — confirm results include ID, domain, type, relevance
3. Call `gardenReindex` — confirm reset triggers
4. Wait for re-indexing to complete, verify `gardenStatus` shows correct count

- [ ] **Step 3: Run benchmark**

Run: `python3 scripts/benchmark_search.py`
Expected: Report written to `docs/comparison/garden-search-vs-grep.md`

Review the report. Verify:
- Keyword queries show both methods finding entries
- Natural language queries show vector search finding entries grep misses
- Exact ID query shows grep winning
- Summary table counts are reasonable

- [ ] **Step 4: Commit report**

```bash
git add docs/comparison/garden-search-vs-grep.md
git commit -m "docs: garden search vs grep comparison report

Refs #23"
```

- [ ] **Step 5: Close issue**

```bash
git commit --allow-empty -m "Closes #23"
```

---

## Verification

1. **Benchmark tests pass:** `python3 -m pytest scripts/test_benchmark_search.py -v`
2. **Skills installed:** After `sync-local`, verify `~/.claude/skills/code-review/java.md` contains "Garden Consultation" section
3. **Comparison report committed:** `docs/comparison/garden-search-vs-grep.md` exists with per-query results and summary table
4. **E2E verified:** gardenSearch returns results via MCP, fallback to grep works when engine is stopped
