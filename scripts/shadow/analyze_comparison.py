#!/usr/bin/env python3
"""Analyze RAG shadow comparison log — MCP usage, grep coverage, quality trends."""

import json
import sys
from collections import defaultdict
from pathlib import Path

LOG_PATH = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / ".hortora/logs/rag-comparison.jsonl"


def load_records(path: Path) -> list[dict]:
    if not path.exists():
        return []
    records = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if line:
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return records


def analyze_mcp(records: list[dict]) -> None:
    if not records:
        return

    print(f"  Searches: {len(records)}")

    latencies = [r["latency_ms"] for r in records if "latency_ms" in r]
    if latencies:
        latencies.sort()
        median = latencies[len(latencies) // 2]
        print(f"  Latency:  median {median}ms, range {min(latencies)}–{max(latencies)}ms")

    result_counts = [r.get("result_count", 0) for r in records]
    trimmed = sum(1 for r in records if r.get("trimmed"))
    extended = sum(1 for r in records if r.get("extended"))
    empty = sum(1 for r in records if r.get("result_count", 0) == 0)
    print(f"  Results:  avg {sum(result_counts)/len(result_counts):.1f}, empty {empty}/{len(records)}")
    print(f"  Adaptive: {trimmed} trimmed, {extended} extended")

    ce_all = []
    for r in records:
        for entry in r.get("results", []):
            if entry.get("crossEncoderScore") is not None:
                ce_all.append(entry["crossEncoderScore"])
    if ce_all:
        positive = sum(1 for s in ce_all if s > 0)
        print(f"  CE scores: {positive}/{len(ce_all)} positive ({positive/len(ce_all)*100:.0f}%)")

    filters_used = sum(1 for r in records if r.get("domain") or r.get("type") or r.get("tags"))
    if filters_used:
        print(f"  Filtered: {filters_used}/{len(records)} used domain/type/tag filters")

    print()
    print("  Recent queries:")
    for r in records[-5:]:
        query = r.get("query", "?")[:60]
        count = r.get("result_count", 0)
        ms = r.get("latency_ms", 0)
        ts = r.get("timestamp", "?")[:10]
        print(f"    [{ts}] {count} results, {ms}ms — {query}")


def analyze_hook(records: list[dict]) -> None:
    if not records:
        return

    available = [r for r in records if r.get("rag_status") == "ok"]
    unavailable = len(records) - len(available)

    print(f"  Intents:  {len(records)} ({len(available)} with RAG, {unavailable} engine unavailable)")

    if not available:
        print("  No entries with RAG results to analyze.")
        return

    full_coverage = 0
    partial_coverage = 0
    no_coverage = 0
    total_grep_only = 0
    total_rag_only = 0
    total_both = 0
    total_grep_calls = 0

    for r in available:
        grep_set = set(r.get("grep_union", []))
        rag_set = set(entry["id"] for entry in r.get("rag_results", []))

        total_both += len(grep_set & rag_set)
        total_grep_only += len(grep_set - rag_set)
        total_rag_only += len(rag_set - grep_set)
        total_grep_calls += len(r.get("grep_calls", []))

        if not grep_set:
            full_coverage += 1
        elif grep_set <= rag_set:
            full_coverage += 1
        elif grep_set & rag_set:
            partial_coverage += 1
        else:
            no_coverage += 1

    print(f"  Coverage: full {full_coverage}, partial {partial_coverage}, none {no_coverage}")
    print(f"  Overlap:  {total_both} both, {total_grep_only} grep-only, {total_rag_only} RAG-only")
    print(f"  Efficiency: {total_grep_calls} grep calls / {len(available)} intents "
          f"(avg {total_grep_calls/len(available):.1f})")

    if total_grep_only > 0:
        print()
        print("  Grep-only entries (RAG missed):")
        shown = 0
        for r in available:
            grep_set = set(r.get("grep_union", []))
            rag_set = set(entry["id"] for entry in r.get("rag_results", []))
            missed = grep_set - rag_set
            if missed:
                query = r.get("rag_query", "?")[:50]
                for ge_id in sorted(missed):
                    print(f"    {ge_id}  query: {query}")
                    shown += 1
                    if shown >= 10:
                        remaining = total_grep_only - shown
                        if remaining > 0:
                            print(f"    ... and {remaining} more")
                        return


def analyze(records: list[dict]) -> None:
    if not records:
        print("No data yet. Run gardenSearch or garden grep to start collecting.")
        return

    mcp_records = [r for r in records if r.get("source") == "mcp"]
    hook_records = [r for r in records if r.get("source") != "mcp"]

    print(f"=== RAG Shadow Harness — {len(records)} total entries ===")
    print()

    if mcp_records:
        print(f"gardenSearch MCP ({len(mcp_records)} calls)")
        analyze_mcp(mcp_records)

    if hook_records:
        if mcp_records:
            print()
        print(f"Grep fallback ({len(hook_records)} intents)")
        analyze_hook(hook_records)

    if not mcp_records and not hook_records:
        print("No data yet.")


def main() -> None:
    records = load_records(LOG_PATH)
    analyze(records)


if __name__ == "__main__":
    main()
