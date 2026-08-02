#!/usr/bin/env python3
"""One-time dedup scan — finds near-duplicate garden entries using Qdrant cosine similarity."""

import json
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

ENGINE_URL = "http://localhost:8080"
SIMILARITY_THRESHOLD = 0.92
TOP_K = 5


def search(query_text: str, limit: int = TOP_K) -> list[dict]:
    url = f"{ENGINE_URL}/search?q={urllib.parse.quote(query_text)}&limit={limit}"
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        print(f"  ERROR: {e}", file=sys.stderr)
        return []


def extract_ge_id(doc_id: str) -> str:
    if doc_id.endswith(".md"):
        doc_id = doc_id[:-3]
    if "/" in doc_id:
        doc_id = doc_id.rsplit("/", 1)[-1]
    return doc_id


def main():
    print("Fetching all document IDs from engine...")
    all_docs_url = f"{ENGINE_URL}/search?q=knowledge&limit=50"

    # We can't list all docs via REST — use gardenStatus to get count,
    # then scan by querying each entry's title against the corpus.
    # Better approach: use the Qdrant REST API directly.

    QDRANT_URL = "http://localhost:6333"
    COLLECTION = "hortora_garden"

    # Scroll all points to get IDs and titles
    print("Scrolling Qdrant collection for all entries...")
    all_points = []
    next_offset = None

    while True:
        scroll_body = {
            "limit": 100,
            "with_payload": ["sourceDocumentId", "title", "content"],
            "with_vector": ["dense"]
        }
        if next_offset is not None:
            scroll_body["offset"] = next_offset

        req = urllib.request.Request(
            f"{QDRANT_URL}/collections/{COLLECTION}/points/scroll",
            data=json.dumps(scroll_body).encode(),
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            result = json.loads(resp.read().decode())

        points = result.get("result", {}).get("points", [])
        all_points.extend(points)
        next_offset = result.get("result", {}).get("next_page_offset")

        if not next_offset or not points:
            break

    print(f"Found {len(all_points)} points in Qdrant")

    # For each point, query its nearest neighbors using its dense vector
    pairs_seen = set()
    duplicates = []

    for i, point in enumerate(all_points):
        if (i + 1) % 100 == 0:
            print(f"  Scanning {i+1}/{len(all_points)}...", file=sys.stderr)

        doc_id = ""
        title = ""
        for k, v in point.get("payload", {}).items():
            if k == "sourceDocumentId":
                doc_id = v
            elif k == "title":
                title = v

        dense_vector = point.get("vector", {}).get("dense", [])
        if not dense_vector:
            continue

        # Query nearest neighbors
        search_body = {
            "vector": {
                "name": "dense",
                "vector": dense_vector
            },
            "limit": TOP_K + 1,  # +1 because the point itself will be the top match
            "with_payload": ["sourceDocumentId", "title"],
            "score_threshold": SIMILARITY_THRESHOLD
        }

        req = urllib.request.Request(
            f"{QDRANT_URL}/collections/{COLLECTION}/points/search",
            data=json.dumps(search_body).encode(),
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                result = json.loads(resp.read().decode())
        except Exception as e:
            print(f"  Search failed for {doc_id}: {e}", file=sys.stderr)
            continue

        neighbors = result.get("result", [])

        for neighbor in neighbors:
            n_doc_id = ""
            n_title = ""
            for k, v in neighbor.get("payload", {}).items():
                if k == "sourceDocumentId":
                    n_doc_id = v
                elif k == "title":
                    n_title = v

            score = neighbor.get("score", 0)

            # Skip self-match
            if n_doc_id == doc_id:
                continue

            # Skip already-seen pairs
            pair_key = tuple(sorted([doc_id, n_doc_id]))
            if pair_key in pairs_seen:
                continue
            pairs_seen.add(pair_key)

            duplicates.append({
                "score": round(score, 4),
                "entry_a": extract_ge_id(doc_id),
                "title_a": title,
                "entry_b": extract_ge_id(n_doc_id),
                "title_b": n_title,
                "path_a": doc_id,
                "path_b": n_doc_id,
            })

    # Sort by similarity (highest first)
    duplicates.sort(key=lambda x: -x["score"])

    # Report
    print(f"\n{'='*80}")
    print(f"DEDUP SCAN RESULTS")
    print(f"{'='*80}")
    print(f"Total entries scanned: {len(all_points)}")
    print(f"Pairs above {SIMILARITY_THRESHOLD} threshold: {len(duplicates)}")
    print()

    if not duplicates:
        print("No near-duplicates found. Corpus is clean.")
        return

    # Group by similarity bands
    bands = defaultdict(list)
    for d in duplicates:
        if d["score"] >= 0.98:
            bands["0.98+ (near-identical)"].append(d)
        elif d["score"] >= 0.95:
            bands["0.95-0.98 (likely duplicate)"].append(d)
        elif d["score"] >= 0.92:
            bands["0.92-0.95 (possible duplicate)"].append(d)

    for band_name in ["0.98+ (near-identical)", "0.95-0.98 (likely duplicate)", "0.92-0.95 (possible duplicate)"]:
        band = bands.get(band_name, [])
        if not band:
            continue
        print(f"\n## {band_name}: {len(band)} pairs\n")
        for d in band:
            print(f"  {d['score']:.4f}  {d['entry_a']:25s}  ↔  {d['entry_b']}")
            print(f"           {d['title_a'][:60]}")
            print(f"           {d['title_b'][:60]}")
            print()

    # Write full results to JSON
    output_path = Path(__file__).parent / "dedup-scan-results.json"
    with open(output_path, "w") as f:
        json.dump({
            "total_entries": len(all_points),
            "threshold": SIMILARITY_THRESHOLD,
            "pairs_found": len(duplicates),
            "duplicates": duplicates
        }, f, indent=2)
    print(f"\nFull results written to: {output_path}")


if __name__ == "__main__":
    main()
