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


def search(query: str, base_url: str = ENGINE_URL, limit: int = 8) -> tuple[str, float]:
    url = f"{base_url}/search?q={urllib.parse.quote(query)}&limit={limit}"
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


def wait_for_readiness(engine_url: str = ENGINE_URL, qdrant_url: str = QDRANT_URL):
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
            if count >= MIN_INDEXED_POINTS and count == prev_count:
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


def run_all_queries(engine_url: str = ENGINE_URL) -> list[dict]:
    queries = []
    for scenario in SCENARIOS:
        for qt, query_text in [("KW", scenario.kw_query), ("NL", scenario.nl_query)]:
            queries.append({"scenario_id": scenario.id, "query_type": qt, "query_text": query_text})

    print(f"Warmup pass ({len(queries)} queries)...")
    for q in queries:
        try:
            search(q["query_text"], engine_url)
        except Exception as e:
            print(f"  Warmup failed for {q['scenario_id']}/{q['query_type']}: {e}")
        time.sleep(QUERY_PAUSE_S)

    results = []
    for q in queries:
        latencies = []
        entries_per_pass = []
        for pass_num in range(NUM_PASSES):
            try:
                body, elapsed_ms = search(q["query_text"], engine_url)
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
    if len(sys.argv) < 2:
        print("Usage: run_queries.py <config-name> [engine-url]")
        sys.exit(1)

    config_name = sys.argv[1]
    engine_url = sys.argv[2] if len(sys.argv) > 2 else ENGINE_URL

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    point_count = wait_for_readiness(engine_url)

    print(f"\nRunning benchmark for config: {config_name}")
    results = run_all_queries(engine_url)

    output = {
        "config": config_name,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "point_count": point_count,
        "num_passes": NUM_PASSES,
        "results": results,
    }

    output_path = RESULTS_DIR / f"{config_name}.json"
    output_path.write_text(json.dumps(output, indent=2))
    print(f"\nResults written to {output_path}")


if __name__ == "__main__":
    main()
