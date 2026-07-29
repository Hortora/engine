#!/usr/bin/env python3
"""Run benchmark queries against the engine REST API and capture results with latency."""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from benchmark.create_snapshot import SNAPSHOT_DIR, _compute_sha256
from benchmark.qdrant_utils import (
    ENGINE_URL, QDRANT_URL, COLLECTION_NAME,
    MIN_INDEXED_POINTS, check_qdrant_ready, wait_for_readiness,
)
from benchmark.queries import SCENARIOS

RESULTS_DIR = Path(__file__).parent / "results"
BASELINE_PATH = Path(__file__).parent / "baseline_scores.json"
NUM_PASSES = 3
QUERY_PAUSE_S = 0.5


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


def compute_unscored_pct(results: list[dict], baseline: dict) -> float:
    total = 0
    unscored = 0
    for r in results:
        scenario_id = r["scenario_id"]
        for entry in r.get("entries", []):
            total += 1
            ge_id = Path(entry["id"]).stem
            entry_scores = baseline.get(ge_id, {})
            if scenario_id not in entry_scores:
                unscored += 1
    if total == 0:
        return 0.0
    return unscored / total


def restore_snapshot(name: str, qdrant_url: str = QDRANT_URL) -> dict:
    snap_dir = SNAPSHOT_DIR / name
    snapshot_path = snap_dir / "collection.snapshot"
    manifest_path = snap_dir / "manifest.json"

    if not snapshot_path.exists() or not manifest_path.exists():
        print(f"Error: snapshot '{name}' not found at {snap_dir}", file=sys.stderr)
        sys.exit(1)

    manifest = json.loads(manifest_path.read_text())
    print(f"Restoring snapshot '{name}':")
    print(f"  Points: {manifest.get('point_count')}")
    print(f"  Created: {manifest.get('created')}")
    print(f"  Engine: {manifest.get('engine_commit')}")
    print(f"  Garden: {manifest.get('garden_sha')}")

    actual_sha = _compute_sha256(snapshot_path)
    expected_sha = manifest.get("snapshot_sha256", "")
    if actual_sha != expected_sha:
        print(f"Error: snapshot integrity check failed.\n"
              f"  Expected SHA-256: {expected_sha[:16]}...\n"
              f"  Actual SHA-256:   {actual_sha[:16]}...",
              file=sys.stderr)
        sys.exit(1)

    actual_size = snapshot_path.stat().st_size
    expected_size = manifest.get("snapshot_size_bytes", 0)
    if actual_size != expected_size:
        print(f"Error: snapshot size mismatch.\n"
              f"  Expected: {expected_size} bytes\n"
              f"  Actual:   {actual_size} bytes",
              file=sys.stderr)
        sys.exit(1)

    if BASELINE_PATH.exists():
        try:
            result = subprocess.run(
                ["git", "hash-object", str(BASELINE_PATH)],
                capture_output=True, text=True, timeout=10,
            )
            current_scoring_sha = result.stdout.strip()[:8] if result.returncode == 0 else ""
        except Exception:
            current_scoring_sha = ""
        manifest_scoring_sha = manifest.get("scoring_sha", "")
        if current_scoring_sha and manifest_scoring_sha and current_scoring_sha != manifest_scoring_sha:
            print(f"  Note: scoring data has changed since snapshot was created "
                  f"(was {manifest_scoring_sha}, now {current_scoring_sha})")

    try:
        req = urllib.request.Request(f"{qdrant_url}/")
        with urllib.request.urlopen(req, timeout=10) as resp:
            qdrant_info = json.loads(resp.read())
        running_version = qdrant_info.get("version", "unknown")
        manifest_version = manifest.get("qdrant_version", "unknown")
        if running_version.split(".")[0] != manifest_version.split(".")[0]:
            print(f"Error: Qdrant major version mismatch.\n"
                  f"  Snapshot created with: {manifest_version}\n"
                  f"  Running Qdrant: {running_version}",
                  file=sys.stderr)
            sys.exit(1)
        if running_version != manifest_version:
            print(f"  Note: Qdrant version differs "
                  f"(snapshot: {manifest_version}, running: {running_version})")
    except Exception as e:
        print(f"  Warning: could not check Qdrant version: {e}")

    print("  Deleting existing collection...")
    try:
        delete_url = f"{qdrant_url}/collections/{COLLECTION_NAME}"
        req = urllib.request.Request(delete_url, method="DELETE")
        with urllib.request.urlopen(req, timeout=30) as resp:
            resp.read()
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    except Exception:
        pass

    print(f"  Uploading snapshot ({actual_size / 1024 / 1024:.1f} MB)...")
    upload_url = f"{qdrant_url}/collections/{COLLECTION_NAME}/snapshots/upload"
    boundary = "----SnapshotUploadBoundary"
    with open(snapshot_path, "rb") as f:
        snapshot_data = f.read()

    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="snapshot"; filename="collection.snapshot"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode() + snapshot_data + f"\r\n--{boundary}--\r\n".encode()

    req = urllib.request.Request(
        upload_url, data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            resp.read()
    except Exception as e:
        print(f"Error: snapshot upload failed. Collection was deleted.\n"
              f"  Re-run with --corpus-snapshot to retry — "
              f"the snapshot file on disk is intact.\n"
              f"  Details: {e}", file=sys.stderr)
        sys.exit(1)

    print("  Waiting for collection to be ready...")
    expected_points = manifest.get("point_count", 0)
    for attempt in range(24):
        try:
            info_url = f"{qdrant_url}/collections/{COLLECTION_NAME}"
            req = urllib.request.Request(info_url)
            with urllib.request.urlopen(req, timeout=10) as resp:
                info = json.loads(resp.read())
            status = info["result"].get("status", "")
            points = info["result"].get("points_count", 0)
            if status == "green" and points >= expected_points:
                print(f"  Collection ready: {points} points")
                return manifest
        except Exception:
            pass
        time.sleep(5)

    print("Error: collection did not become ready after restore.\n"
          "  Check Qdrant logs for index corruption or resource issues.",
          file=sys.stderr)
    sys.exit(1)


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
    parser.add_argument("--corpus-snapshot", default=None,
                        help="Restore named snapshot before running (skips live indexing)")
    parser.add_argument("--qdrant-url", default=QDRANT_URL,
                        help=f"Qdrant REST API URL (default: {QDRANT_URL})")
    args = parser.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    if args.corpus_snapshot:
        manifest = restore_snapshot(args.corpus_snapshot, args.qdrant_url)
        point_count = manifest["point_count"]
    else:
        point_count = wait_for_readiness(args.engine_url, qdrant_url=args.qdrant_url,
                                         min_points=args.min_points)

    print(f"\nRunning benchmark for config: {args.config_name}")
    results = run_all_queries(args.engine_url, limit=args.limit)

    baseline = {}
    if BASELINE_PATH.exists():
        baseline = json.loads(BASELINE_PATH.read_text())

    unscored_pct = compute_unscored_pct(results, baseline)
    if unscored_pct > 0.05:
        total_entries = sum(len(r.get("entries", [])) for r in results)
        unscored_count = int(unscored_pct * total_entries)
        print(f"\n⚠️  {unscored_pct:.1%} of returned entries are unscored "
              f"({unscored_count}/{total_entries}). "
              f"Score new entries before accepting results.")

    output = {
        "config": args.config_name,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "point_count": point_count,
        "num_passes": NUM_PASSES,
        "unscored_pct": round(unscored_pct, 4),
        "results": results,
    }

    if args.corpus_snapshot:
        output["corpus_snapshot"] = args.corpus_snapshot
        output["snapshot_manifest"] = {
            "point_count": manifest["point_count"],
            "engine_commit": manifest.get("engine_commit"),
            "garden_sha": manifest.get("garden_sha"),
            "qdrant_version": manifest.get("qdrant_version"),
        }

    output_path = RESULTS_DIR / f"{args.config_name}.json"
    output_path.write_text(json.dumps(output, indent=2))
    print(f"\nResults written to {output_path}")


if __name__ == "__main__":
    main()
