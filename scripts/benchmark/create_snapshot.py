#!/usr/bin/env python3
"""Create and download Qdrant collection snapshots for reproducible benchmarking."""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from benchmark.qdrant_utils import (
    ENGINE_URL, QDRANT_URL, COLLECTION_NAME, wait_for_readiness,
)

SNAPSHOT_DIR = Path.home() / ".hortora" / "snapshots"
BASELINE_SCORES_PATH = Path(__file__).parent / "baseline_scores.json"
GARDEN_PATH_DEFAULT = Path.home() / ".hortora" / "garden"


def create_manifest(*, name: str, point_count: int, qdrant_snapshot_name: str,
                    snapshot_sha256: str, snapshot_size_bytes: int,
                    qdrant_version: str, engine_commit: str,
                    garden_sha: str, scoring_sha: str) -> dict:
    return {
        "name": name,
        "point_count": point_count,
        "created": datetime.now(timezone.utc).isoformat(),
        "engine_commit": engine_commit,
        "garden_sha": garden_sha,
        "qdrant_version": qdrant_version,
        "qdrant_snapshot_name": qdrant_snapshot_name,
        "snapshot_sha256": snapshot_sha256,
        "snapshot_size_bytes": snapshot_size_bytes,
        "scoring_sha": scoring_sha,
    }


def list_snapshots() -> list[dict]:
    if not SNAPSHOT_DIR.exists():
        return []
    result = []
    for d in sorted(SNAPSHOT_DIR.iterdir()):
        manifest_path = d / "manifest.json"
        if d.is_dir() and manifest_path.exists():
            try:
                result.append(json.loads(manifest_path.read_text()))
            except (json.JSONDecodeError, OSError):
                pass
    return result


def _get_qdrant_version(qdrant_url: str) -> str:
    req = urllib.request.Request(f"{qdrant_url}/")
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    return data.get("version", "unknown")


def _get_git_describe(repo_path: str = ".") -> str:
    try:
        result = subprocess.run(
            ["git", "-C", repo_path, "describe", "--dirty", "--always"],
            capture_output=True, text=True, timeout=10,
        )
        return result.stdout.strip() if result.returncode == 0 else "unknown"
    except Exception:
        return "unknown"


def _get_git_short_sha(repo_path: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", repo_path, "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=10,
        )
        return result.stdout.strip() if result.returncode == 0 else "unknown"
    except Exception:
        return "unknown"


def _get_scoring_sha() -> str:
    if not BASELINE_SCORES_PATH.exists():
        return "unknown"
    try:
        result = subprocess.run(
            ["git", "hash-object", str(BASELINE_SCORES_PATH)],
            capture_output=True, text=True, timeout=10,
        )
        return result.stdout.strip()[:8] if result.returncode == 0 else "unknown"
    except Exception:
        return "unknown"


def _compute_sha256(file_path: Path) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def create_snapshot(name: str, engine_url: str = ENGINE_URL,
                    qdrant_url: str = QDRANT_URL) -> dict:
    snap_dir = SNAPSHOT_DIR / name
    if snap_dir.exists():
        print(f"Error: snapshot '{name}' already exists at {snap_dir}", file=sys.stderr)
        sys.exit(1)

    point_count = wait_for_readiness(engine_url, qdrant_url)

    print("Creating Qdrant snapshot...")
    create_url = f"{qdrant_url}/collections/{COLLECTION_NAME}/snapshots"
    req = urllib.request.Request(create_url, method="POST",
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        create_result = json.loads(resp.read())
    qdrant_snapshot_name = create_result["result"]["name"]
    print(f"  Snapshot created: {qdrant_snapshot_name}")

    snap_dir.mkdir(parents=True, exist_ok=True)
    snapshot_path = snap_dir / "collection.snapshot"
    try:
        print("Downloading snapshot...")
        download_url = (f"{qdrant_url}/collections/{COLLECTION_NAME}"
                        f"/snapshots/{qdrant_snapshot_name}")
        req = urllib.request.Request(download_url)
        with urllib.request.urlopen(req, timeout=600) as resp:
            with open(snapshot_path, "wb") as f:
                while True:
                    chunk = resp.read(8192)
                    if not chunk:
                        break
                    f.write(chunk)
    except Exception as e:
        print(f"Error downloading snapshot: {e}", file=sys.stderr)
        if snap_dir.exists():
            shutil.rmtree(snap_dir)
        raise

    snapshot_sha256 = _compute_sha256(snapshot_path)
    snapshot_size = snapshot_path.stat().st_size
    print(f"  Downloaded: {snapshot_size / 1024 / 1024:.1f} MB, "
          f"SHA-256: {snapshot_sha256[:16]}...")

    print("Cleaning up server-side snapshot...")
    try:
        delete_url = (f"{qdrant_url}/collections/{COLLECTION_NAME}"
                      f"/snapshots/{qdrant_snapshot_name}")
        req = urllib.request.Request(delete_url, method="DELETE")
        with urllib.request.urlopen(req, timeout=30) as resp:
            resp.read()
    except Exception:
        print("  Warning: failed to delete server-side snapshot (non-fatal)")

    garden_path = os.environ.get("HORTORA_GARDEN_PATH", str(GARDEN_PATH_DEFAULT))
    manifest = create_manifest(
        name=name,
        point_count=point_count,
        qdrant_snapshot_name=qdrant_snapshot_name,
        snapshot_sha256=snapshot_sha256,
        snapshot_size_bytes=snapshot_size,
        qdrant_version=_get_qdrant_version(qdrant_url),
        engine_commit=_get_git_describe(),
        garden_sha=_get_git_short_sha(garden_path),
        scoring_sha=_get_scoring_sha(),
    )
    (snap_dir / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"\nSnapshot '{name}' saved to {snap_dir}")
    print(f"  Points: {point_count}")
    print(f"  Size: {snapshot_size / 1024 / 1024:.1f} MB")
    print(f"  SHA-256: {snapshot_sha256[:16]}...")
    return manifest


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Create and manage Qdrant collection snapshots for benchmarking"
    )
    parser.add_argument("name", nargs="?", help="Snapshot name")
    parser.add_argument("--engine-url", default=ENGINE_URL, help="Engine base URL")
    parser.add_argument("--qdrant-url", default=QDRANT_URL, help="Qdrant REST API URL")
    parser.add_argument("--list", action="store_true", help="List available snapshots")
    args = parser.parse_args()

    if args.list:
        snapshots = list_snapshots()
        if not snapshots:
            print("No snapshots found.")
            return
        print(f"{'NAME':<20} {'POINTS':<8} {'CREATED':<25} {'SIZE':<10} {'ENGINE'}")
        for s in snapshots:
            size_mb = s.get("snapshot_size_bytes", 0) / 1024 / 1024
            print(f"{s['name']:<20} {s.get('point_count', '?'):<8} "
                  f"{s.get('created', '?'):<25} {size_mb:<10.0f} MB "
                  f"{s.get('engine_commit', '?')}")
        return

    if not args.name:
        parser.error("snapshot name is required (or use --list)")

    create_snapshot(args.name, args.engine_url, args.qdrant_url)


if __name__ == "__main__":
    main()
