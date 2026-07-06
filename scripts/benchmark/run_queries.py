#!/usr/bin/env python3
"""Run benchmark queries against the engine REST API and capture results with latency."""

import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from benchmark.queries import SCENARIOS

ENGINE_URL = "http://localhost:8080"
QDRANT_URL = "http://localhost:6333"
COLLECTION_NAME = "hortora_garden"
RESULTS_DIR = Path(__file__).parent / "results"
NUM_PASSES = 3
QUERY_PAUSE_S = 0.5
READINESS_POLL_S = 5
MIN_INDEXED_POINTS = 1900


def search(query: str, base_url: str = ENGINE_URL, limit: int | None = None) -> tuple[str, float]:
    url = f"{base_url}/search?q={urllib.parse.quote(query)}"
    if limit is not None:
        url += f"&limit={limit}"
    start = time.monotonic()
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = resp.read().decode()
    elapsed_ms = (time.monotonic() - start) * 1000
    return body, elapsed_ms


def parse_search_response(response_body: str) -> list[dict]:
    entries = json.loads(response_body)
    for i, entry in enumerate(entries):
        entry["rank"] = i
    return entries


def compute_median(values: list[float]) -> float | None:
    """Compute median, skipping None values. Returns None if all values are None."""
    valid_values = [v for v in values if v is not None]
    if not valid_values:
        return None
    s = sorted(valid_values)
    n = len(s)
    if n % 2 == 1:
        return s[n // 2]
    return (s[n // 2 - 1] + s[n // 2]) / 2


def check_qdrant_ready(qdrant_url: str = QDRANT_URL) -> int:
    url = f"{qdrant_url}/collections/{COLLECTION_NAME}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    return data["result"]["points_count"]


def wait_for_readiness(engine_url: str = ENGINE_URL, qdrant_url: str = QDRANT_URL,
                       min_points: int = MIN_INDEXED_POINTS):
    print("Waiting for engine readiness...")
    for attempt in range(60):
        try:
            search("test query", engine_url)
            break
        except Exception:
            time.sleep(READINESS_POLL_S)
    else:
        raise RuntimeError("Engine not responding after 5 minutes")

    print("Waiting for indexing to complete...")
    prev_count = -1
    stable_checks = 0
    for attempt in range(120):
        try:
            count = check_qdrant_ready(qdrant_url)
            print(f"  Indexed points: {count}")
            if count >= min_points and count == prev_count:
                stable_checks += 1
                if stable_checks >= 2:
                    print(f"Indexing complete: {count} points")
                    return count
            else:
                stable_checks = 0
            prev_count = count
        except Exception as e:
            print(f"  Qdrant check failed: {e}")
        time.sleep(READINESS_POLL_S)
    raise RuntimeError("Indexing did not stabilise")


def run_all_queries(engine_url: str = ENGINE_URL, limit: int | None = None) -> list[dict]:
    queries = []
    for scenario in SCENARIOS:
        for qt, query_text in [("KW", scenario.kw_query), ("NL", scenario.nl_query)]:
            queries.append({"scenario_id": scenario.id, "query_type": qt, "query_text": query_text})

    print(f"Warmup pass ({len(queries)} queries)...")
    for q in queries:
        try:
            search(q["query_text"], engine_url, limit=limit)
        except Exception as e:
            print(f"  Warmup failed for {q['scenario_id']}/{q['query_type']}: {e}")
        time.sleep(QUERY_PAUSE_S)

    results = []
    for q in queries:
        latencies = []
        entries_per_pass = []
        for pass_num in range(NUM_PASSES):
            try:
                body, elapsed_ms = search(q["query_text"], engine_url, limit=limit)
                entries = parse_search_response(body)
                latencies.append(elapsed_ms)
                entries_per_pass.append(entries)
            except Exception as e:
                print(f"  Measurement pass {pass_num + 1}/3 failed for {q['scenario_id']}/{q['query_type']}: {e}")
                latencies.append(None)
                entries_per_pass.append([])
            time.sleep(QUERY_PAUSE_S)
        median_ms = compute_median(latencies)
        first_entries = entries_per_pass[0] if entries_per_pass[0] else []
        results.append({
            "scenario_id": q["scenario_id"],
            "query_type": q["query_type"],
            "query_text": q["query_text"],
            "entries": first_entries,
            "latency_ms": latencies,
            "latency_median_ms": median_ms,
        })
        if median_ms is not None:
            print(f"  {q['scenario_id']}/{q['query_type']}: {len(first_entries)} results, "
                  f"median {median_ms:.1f}ms")
        else:
            print(f"  {q['scenario_id']}/{q['query_type']}: all measurement passes failed")

    return results


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Run benchmark queries against the engine REST API")
    parser.add_argument("config_name", help="Configuration name (used as output filename)")
    parser.add_argument("engine_url", nargs="?", default=ENGINE_URL, help="Engine base URL")
    parser.add_argument("--min-points", type=int, default=MIN_INDEXED_POINTS,
                        help=f"Minimum indexed points before starting (default: {MIN_INDEXED_POINTS})")
    parser.add_argument("--limit", type=int, default=None,
                        help="Override result limit per query (default: use server default)")
    args = parser.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    point_count = wait_for_readiness(args.engine_url, min_points=args.min_points)

    print(f"\nRunning benchmark for config: {args.config_name}")
    results = run_all_queries(args.engine_url, limit=args.limit)

    output = {
        "config": args.config_name,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "point_count": point_count,
        "num_passes": NUM_PASSES,
        "results": results,
    }

    output_path = RESULTS_DIR / f"{args.config_name}.json"
    output_path.write_text(json.dumps(output, indent=2))
    print(f"\nResults written to {output_path}")


if __name__ == "__main__":
    main()
