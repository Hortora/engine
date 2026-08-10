# Fixed-Corpus Benchmark Snapshots

*2026-07-29 · Refs #56*

## Problem

The benchmark harness (`run_queries.py`) indexes the live garden corpus before each run. Corpus growth between runs makes cross-session precision comparisons invalid — the investigation across #50 (HyDE) and #55 (score boosting) surfaced a -2.2pp "regression" that was entirely phantom, caused by 18 unscored entries displacing scored ones in the top-16 (see casehubio/neural-text#181).

## Solution

Qdrant collection snapshots downloaded to local storage. Create a snapshot after indexing a known corpus state, restore it before benchmark runs. Both corpus and embeddings are constant across runs — only retrieval/ranking code changes affect results.

## Storage

```
~/.hortora/snapshots/
  <name>/
    collection.snapshot    # Qdrant snapshot binary
    manifest.json          # provenance metadata
```

Snapshot names are user-chosen (e.g. `v2-baseline`, `2026-07-28-pre-hyde`). Not git-tracked — files are large (ColBERT multi-vectors dominate). Survives Qdrant container recreation.

### manifest.json

```json
{
  "name": "v2-baseline",
  "point_count": 2400,
  "created": "2026-07-29T10:30:00Z",
  "engine_commit": "7abba11",
  "garden_sha": "973b326a",
  "qdrant_version": "1.12.6",
  "qdrant_snapshot_name": "hortora_garden-2026-07-29-10-30-00.snapshot",
  "snapshot_sha256": "a3f2b8c1d4e5f6...",
  "snapshot_size_bytes": 524288000,
  "scoring_sha": "e8f1a2b3"
}
```

- `engine_commit` — captured via `git describe --dirty --always`. Flags uncommitted changes (e.g. `7abba11-dirty`).
- `qdrant_version` — from `GET /`. Enables compatibility check before restore.
- `snapshot_sha256` and `snapshot_size_bytes` — integrity verification after download and before restore.
- `scoring_sha` — git blob SHA of `baseline_scores.json` at snapshot creation time (`git hash-object scripts/benchmark/baseline_scores.json`). Enables drift detection at restore time.

## Shared module: `benchmark/qdrant_utils.py`

Extract shared Qdrant utilities from `run_queries.py` into `scripts/benchmark/qdrant_utils.py`:

- `QDRANT_URL`, `COLLECTION_NAME` constants
- `check_qdrant_ready(qdrant_url) -> int`
- `wait_for_readiness(engine_url, qdrant_url, min_points) -> int`

Both `run_queries.py` and `create_snapshot.py` import from this module.

## New script: `create_snapshot.py`

Location: `scripts/benchmark/create_snapshot.py`

### Usage

```bash
python3 scripts/benchmark/create_snapshot.py <name> [--engine-url URL] [--qdrant-url URL]
python3 scripts/benchmark/create_snapshot.py --list
```

### Workflow

1. Verify engine readiness — import `wait_for_readiness()` from `benchmark.qdrant_utils`.
2. Create snapshot: `POST /collections/hortora_garden/snapshots` — Qdrant returns the snapshot name.
3. Download snapshot: `GET /collections/hortora_garden/snapshots/{snapshot_name}` — write to `~/.hortora/snapshots/<name>/collection.snapshot`.
4. Delete server-side snapshot: `DELETE /collections/hortora_garden/snapshots/{snapshot_name}`. Non-fatal — the local copy is the important one. Prevents accumulation on the Qdrant host.
5. Compute SHA-256 hash and record file size of the downloaded snapshot.
6. Query Qdrant version: `GET /` — extract version string.
7. Write `manifest.json` with: name, point count (from Qdrant collection info), creation timestamp, engine git SHA (`git describe --dirty --always`), garden git SHA (`git -C $HORTORA_GARDEN_PATH rev-parse --short HEAD` — reads `HORTORA_GARDEN_PATH` env var, defaults to `~/.hortora/garden` per `docs/DESIGN.md`), Qdrant version, snapshot SHA-256, snapshot size, scoring files SHA (`git hash-object scripts/benchmark/baseline_scores.json`).
8. Print summary: snapshot path, point count, SHA-256, size on disk.

### `--list` flag

Print all available snapshots with metadata from each manifest:

```
NAME            POINTS  CREATED              SIZE      ENGINE
v2-baseline     2400    2026-07-29T10:30:00Z  524 MB    7abba11
pre-hyde        2400    2026-07-28T14:15:00Z  523 MB    a1b2c3d
```

### Error handling

- Engine not ready after timeout → exit with error, no snapshot created.
- Qdrant snapshot creation fails → exit with error.
- Snapshot download fails → clean up partial directory, exit with error.
- Snapshot name already exists → exit with error (no silent overwrite).
- Snapshot integrity check fails (SHA-256 mismatch after download) → clean up, exit with error.

## Modified `run_queries.py`

### New flags

- `--corpus-snapshot <name>` — restore a named snapshot before running queries.
- `--qdrant-url <url>` — Qdrant REST API base URL (default: `http://localhost:6333`).

### Behaviour when `--corpus-snapshot` is provided

`--min-points` is ignored — the expected point count comes from the snapshot manifest.

1. Verify snapshot exists at `~/.hortora/snapshots/<name>/collection.snapshot`.
2. Read `manifest.json` for provenance — print snapshot metadata.
3. Verify snapshot integrity: check SHA-256 hash and file size against manifest.
4. Check scoring file drift: compare current `baseline_scores.json` git blob SHA against `scoring_sha` in manifest. If different, print informational message noting that scoring data has changed since the snapshot was created.
5. Check Qdrant version compatibility: query running Qdrant version via `GET /`, compare against manifest's `qdrant_version`. Abort on major version mismatch (e.g. manifest says 1.x, running Qdrant is 2.x).
6. Delete existing collection: `DELETE /collections/hortora_garden`. 404 is ignored — the collection may not exist on a fresh Qdrant instance or after a previous failed restore.
7. Recover from snapshot: upload the snapshot file via `POST /collections/hortora_garden/snapshots/upload` (multipart form). This works regardless of whether Qdrant runs in Docker or natively — the file is uploaded over HTTP, not accessed via filesystem path.
8. Wait for collection ready: poll `GET /collections/hortora_garden` until `status == "green"` and point count matches manifest. Timeout after 120 seconds.
9. Run queries as normal.

### Restore error handling

- **DELETE returns 404 (step 6):** Ignored — the collection may not exist.
- **Upload fails (step 7):** Exit with error: "Snapshot upload failed. Collection was deleted. Re-run with `--corpus-snapshot` to retry — the snapshot file on disk is intact."
- **Collection never reaches green (step 8):** After 120 seconds, exit with error: "Collection did not become ready after restore. Check Qdrant logs for index corruption or resource issues."

### Behaviour when `--corpus-snapshot` is not provided

Unchanged — waits for live engine indexing via `wait_for_readiness()`.

### Unscored entry warning

After query execution, before writing results:

1. Load `baseline_scores.json`.
2. For each result entry, extract GE-ID from the entry's `id` field: `Path(entry['id']).stem` (strips domain prefix and `.md` extension — e.g. `"jvm/GE-20260518-e4fa52.md"` → `"GE-20260518-e4fa52"`).
3. Check if the GE-ID has a score in `baseline_scores.json` for that scenario.
4. Compute unscored percentage across all results.
5. If >5%: print warning and include `unscored_pct` in output JSON.

```
⚠️  12.3% of returned entries are unscored (28/228). Score new entries before accepting results.
```

### Result metadata changes

When `--corpus-snapshot` is used, the output JSON includes:

```json
{
  "config": "v2-baseline",
  "corpus_snapshot": "v2-baseline",
  "snapshot_manifest": {
    "point_count": 2400,
    "engine_commit": "7abba11",
    "garden_sha": "973b326a",
    "qdrant_version": "1.12.6"
  },
  "timestamp": "...",
  "point_count": 2400,
  "unscored_pct": 0.0,
  "results": [...]
}
```

When `--corpus-snapshot` is not used, `corpus_snapshot` and `snapshot_manifest` are absent. `unscored_pct` is always present.

## Scoring files

No structural change. `baseline_scores.json` remains in git, manually maintained. The `bge-m3-to-score.json` is a worklist output identifying entries that need scoring — not a scoring data source.

Extra scores in `baseline_scores.json` for entries not in a snapshot's results are harmless. Drift detection uses the manifest's `scoring_sha` to warn when scoring data has changed since the snapshot was created. The unscored-entry warning catches the gap case (entries in results but missing from scoring data).

## Qdrant API reference

| Operation | Method | Endpoint |
|-----------|--------|----------|
| Qdrant version | GET | `/` |
| Create snapshot | POST | `/collections/hortora_garden/snapshots` |
| List snapshots | GET | `/collections/hortora_garden/snapshots` |
| Download snapshot | GET | `/collections/hortora_garden/snapshots/{name}` |
| Delete snapshot | DELETE | `/collections/hortora_garden/snapshots/{name}` |
| Delete collection | DELETE | `/collections/hortora_garden` |
| Upload snapshot to recover | POST | `/collections/hortora_garden/snapshots/upload` |
| Collection info | GET | `/collections/hortora_garden` |

Upload recovery: multipart form with snapshot file. Works with Docker-hosted Qdrant (file uploaded over HTTP, not filesystem access).

## Out of scope

- **Mode A (frozen garden dir + re-index)** — tracked by #56. This spec implements Mode B only; #56 remains open until Mode A is addressed.
- **Snapshot pruning/rotation** — tracked by #57. Manual (`rm -rf ~/.hortora/snapshots/<old>`) until then.
- **Scoring the 18 new entries** — tracked as #56 "immediate action." Manual scoring task, not harness code.
- **Automated snapshot creation in CI** — not needed for dev-local benchmarking.

## Test plan

- `test_create_snapshot.py` — unit tests for manifest generation (including SHA-256, Qdrant version, scoring SHA), directory creation, `--list` output, error cases (existing name, missing engine, integrity check failure).
- `test_run_queries.py` — extend existing tests for `--corpus-snapshot` flag parsing, `--qdrant-url` plumbing, `--min-points` ignored with snapshot, unscored percentage calculation (including GE-ID extraction), integrity verification, scoring drift detection, restore error handling (DELETE 404, upload failure, readiness timeout), result metadata inclusion.
- `test_qdrant_utils.py` — unit tests for extracted shared utilities.
- Manual verification: create a snapshot, run benchmark with `--corpus-snapshot`, confirm results match a live-index run on the same corpus.
