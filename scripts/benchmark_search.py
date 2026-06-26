#!/usr/bin/env python3
"""Compare gardenSearch (vector) vs git grep (keyword) on the same queries."""

import json
import subprocess
import urllib.parse
import urllib.request
from dataclasses import dataclass
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
    import sys
    base_url = sys.argv[1] if len(sys.argv) > 1 else ENGINE_URL
    print(f"Running benchmark against {base_url}...")
    results = run_benchmark(base_url=base_url)
    report = format_report(results)

    output_path = (Path(__file__).parent.parent
                   / "docs" / "comparison" / "garden-search-vs-grep.md")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(report)
    print(f"Report written to {output_path}")


if __name__ == "__main__":
    main()
