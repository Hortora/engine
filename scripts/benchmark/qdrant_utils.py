"""Shared Qdrant utilities for benchmark scripts."""

import json
import time
import urllib.parse
import urllib.request

ENGINE_URL = "http://localhost:8080"
QDRANT_URL = "http://localhost:6333"
COLLECTION_NAME = "hortora_garden"
MIN_INDEXED_POINTS = 1900
READINESS_POLL_S = 5


def check_qdrant_ready(qdrant_url: str = QDRANT_URL) -> int:
    url = f"{qdrant_url}/collections/{COLLECTION_NAME}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    return data["result"]["points_count"]


def wait_for_readiness(engine_url: str = ENGINE_URL, qdrant_url: str = QDRANT_URL,
                       min_points: int = MIN_INDEXED_POINTS) -> int:
    print("Waiting for engine readiness...")
    for attempt in range(60):
        try:
            req = urllib.request.Request(
                f"{engine_url}/search?q={urllib.parse.quote('test query')}"
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                resp.read()
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
