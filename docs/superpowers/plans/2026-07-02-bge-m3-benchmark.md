# BGE-M3 Four-Signal Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the ONNX export, run the BGE-M3 four-signal retrieval benchmark against 14 real-world scenarios, and produce a comparative report against the three-leg baseline (94% precision).

**Architecture:** Fix `export_bge_m3.py` to produce external data format ONNX files, add a `--min-points` CLI argument to `run_queries.py` for corpus size safety, write `analyze_bge_m3.py` importing utility functions from `analyze.py` with its own report generator comparing BGE-M3 vs three-leg results.

**Tech Stack:** Python 3.14, PyTorch, ONNX Runtime, Bash

## Global Constraints

- All Python scripts live under `scripts/benchmark/` and import via `from benchmark.<module> import ...`
- Tests use pytest, naming convention `test_<module>.py`, in the same directory
- Existing `analyze.py` must not be modified — it generates the committed `hybrid-benchmark.md` report for #28
- Benchmark result files go to `scripts/benchmark/results/<config-name>.json`
- Reports go to `docs/comparison/<name>.md`

---

### Task 1: Fix ONNX export pipeline

**Files:**
- Modify: `scripts/export_bge_m3.py` — `export_onnx()`, `check_idempotent()`, `write_checksums()`, `main()`
- Modify: `scripts/download-models.sh` — add `model.onnx.data` verification block
- Modify: `scripts/test_export_bge_m3.py` — add non-@slow test for idempotency with external data

**Interfaces:**
- Consumes: nothing (standalone scripts)
- Produces: `~/.hortora/models/bge-m3/model.onnx` (graph, ~3MB) + `model.onnx.data` (weights, ~2.2GB) + `tokenizer.json`. `scripts/bge-m3-checksums.sha256` with three entries.

- [ ] **Step 1: Write failing test for idempotency with external data file**

In `scripts/test_export_bge_m3.py`, add a test that verifies `check_idempotent` returns False when `model.onnx.data` is missing. This test does NOT need the real model — it tests the filesystem check logic.

```python
import tempfile
import os
from pathlib import Path


def test_check_idempotent_fails_without_data_file():
    """check_idempotent must return False when model.onnx.data is missing."""
    from export_bge_m3 import check_idempotent, MODEL_DIR, CHECKSUM_FILE

    original_model_dir = MODEL_DIR
    original_checksum_file = CHECKSUM_FILE

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_path = Path(tmpdir)
        # Create model.onnx and tokenizer.json but NOT model.onnx.data
        (tmp_path / "model.onnx").write_bytes(b"fake-onnx-graph")
        (tmp_path / "tokenizer.json").write_bytes(b"fake-tokenizer")

        checksum_path = tmp_path / "checksums.sha256"
        # Write checksums matching the fake files
        import hashlib
        model_hash = hashlib.sha256(b"fake-onnx-graph").hexdigest()
        tokenizer_hash = hashlib.sha256(b"fake-tokenizer").hexdigest()
        checksum_path.write_text(
            f"{model_hash}  model.onnx\n"
            f"fakehash  model.onnx.data\n"
            f"{tokenizer_hash}  tokenizer.json\n"
        )

        # Monkey-patch module globals
        import export_bge_m3
        export_bge_m3.MODEL_DIR = tmp_path
        export_bge_m3.CHECKSUM_FILE = checksum_path
        try:
            assert check_idempotent() is False
        finally:
            export_bge_m3.MODEL_DIR = original_model_dir
            export_bge_m3.CHECKSUM_FILE = original_checksum_file
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/test_export_bge_m3.py::test_check_idempotent_fails_without_data_file -v`

Expected: FAIL — `check_idempotent` does not check for `model.onnx.data` yet, so it returns True when only `model.onnx` and `tokenizer.json` exist.

- [ ] **Step 3: Update `export_onnx()` — add external data format**

In `scripts/export_bge_m3.py`, modify the `export_onnx` function:

```python
def export_onnx(model: BGEM3InferenceModel, output_path: str, opset_version: int = OPSET_VERSION):
    """Export the model to ONNX format with external data for large models."""
    dummy_input = {
        "input_ids": torch.randint(0, model.config.vocab_size, (1, 32)),
        "attention_mask": torch.ones(1, 32, dtype=torch.long),
    }
    torch.onnx.export(
        model,
        (dummy_input["input_ids"], dummy_input["attention_mask"]),
        output_path,
        opset_version=opset_version,
        input_names=["input_ids", "attention_mask"],
        output_names=["dense", "sparse", "colbert"],
        dynamic_axes={
            "input_ids":      {0: "batch_size", 1: "sequence"},
            "attention_mask": {0: "batch_size", 1: "sequence"},
            "dense":          {0: "batch_size"},
            "sparse":         {0: "batch_size"},
            "colbert":        {0: "batch_size", 1: "sequence"},
        },
        use_external_data_format=True,
    )
```

- [ ] **Step 4: Update `check_idempotent()` — require `model.onnx.data`**

```python
def check_idempotent() -> bool:
    """Return True if model already exported and checksums match."""
    model_path = MODEL_DIR / "model.onnx"
    data_path = MODEL_DIR / "model.onnx.data"
    tokenizer_path = MODEL_DIR / "tokenizer.json"
    if not model_path.exists() or not data_path.exists() or not tokenizer_path.exists():
        return False
    if not CHECKSUM_FILE.exists():
        return False
    expected = {}
    for line in CHECKSUM_FILE.read_text().splitlines():
        parts = line.strip().split("  ", 1)
        if len(parts) == 2:
            expected[parts[1]] = parts[0]
    if "model.onnx" not in expected or "model.onnx.data" not in expected or "tokenizer.json" not in expected:
        return False
    if sha256(model_path) != expected["model.onnx"]:
        return False
    if sha256(data_path) != expected["model.onnx.data"]:
        return False
    if sha256(tokenizer_path) != expected["tokenizer.json"]:
        return False
    return True
```

- [ ] **Step 5: Update `write_checksums()` — include `model.onnx.data`**

```python
def write_checksums(model_path: Path, tokenizer_path: Path):
    """Write SHA-256 checksums to scripts/bge-m3-checksums.sha256."""
    data_path = model_path.parent / "model.onnx.data"
    model_hash = sha256(model_path)
    data_hash = sha256(data_path)
    tokenizer_hash = sha256(tokenizer_path)
    content = f"{model_hash}  model.onnx\n{data_hash}  model.onnx.data\n{tokenizer_hash}  tokenizer.json\n"
    CHECKSUM_FILE.write_text(content)
    print(f"\nChecksums written to {CHECKSUM_FILE}")
    print(f"  model.onnx:      {model_hash}")
    print(f"  model.onnx.data: {data_hash}")
    print(f"  tokenizer.json:  {tokenizer_hash}")
```

- [ ] **Step 6: Update `main()` — move data file first, then graph**

Replace the atomic rename section in `main()`. Move `model.onnx.data` first (2.2GB weights), then `model.onnx` (3MB graph). If the process crashes between moves, the graph file is absent and `check_idempotent()` triggers re-export on next run.

```python
        # Atomic rename: move data first, then graph
        # If crash occurs between moves, graph is absent → idempotency triggers re-export
        final_model = MODEL_DIR / "model.onnx"
        final_data = MODEL_DIR / "model.onnx.data"
        final_tokenizer = MODEL_DIR / "tokenizer.json"
        if final_data.exists():
            os.remove(final_data)
        if final_model.exists():
            os.remove(final_model)
        if final_tokenizer.exists():
            os.remove(final_tokenizer)
        shutil.move(str(tmp_dir / "model.onnx.data"), str(final_data))
        shutil.move(str(tmp_dir / "model.onnx"), str(final_model))
        shutil.move(str(tokenizer_tmp), str(final_tokenizer))

        # Write checksums
        write_checksums(final_model, final_tokenizer)
```

- [ ] **Step 7: Run test to verify it passes**

Run: `python3 -m pytest scripts/test_export_bge_m3.py::test_check_idempotent_fails_without_data_file -v`

Expected: PASS — `check_idempotent` now checks for `model.onnx.data`.

- [ ] **Step 8: Update `download-models.sh`**

Add a verification block for `model.onnx.data` between the existing `model.onnx` and `tokenizer.json` checks:

```bash
# model.onnx.data — external weights produced by export script
if [ -f "${MODEL_DIR}/bge-m3/model.onnx.data" ]; then
    if verify_checksum "${MODEL_DIR}/bge-m3/model.onnx.data" "model.onnx.data"; then
        echo "  ✓ verified: ${MODEL_DIR}/bge-m3/model.onnx.data"
    else
        MISSING=1
    fi
else
    echo "  ✗ not found: ${MODEL_DIR}/bge-m3/model.onnx.data"
    MISSING=1
fi
```

Also update the "Add to application.properties" output at the end — no changes needed there, the properties reference `model.onnx` which ONNX Runtime resolves to include the `.data` file.

- [ ] **Step 9: Commit**

```
feat: fix ONNX export for external data format

torch.onnx.export requires use_external_data_format=True for models
>2GB (protobuf limit). Produces model.onnx (graph) + model.onnx.data
(weights). Move ordering: data first, then graph — crash between moves
triggers re-export via idempotency check.

Refs #36
```

---

### Task 2: Add `--min-points` CLI argument to `run_queries.py`

**Files:**
- Modify: `scripts/benchmark/run_queries.py` — add `--min-points` argument to `main()`
- Test: `scripts/benchmark/test_run_queries.py` — add test for argument override

**Interfaces:**
- Consumes: nothing
- Produces: `MIN_INDEXED_POINTS` override via CLI. Existing callers (`python run_queries.py <config>`) unchanged — default is still 1900.

- [ ] **Step 1: Write failing test**

In `scripts/benchmark/test_run_queries.py`:

```python
def test_main_accepts_min_points_argument(monkeypatch, tmp_path):
    """--min-points overrides MIN_INDEXED_POINTS for wait_for_readiness."""
    import benchmark.run_queries as rq

    captured_min = {}

    original_wait = rq.wait_for_readiness
    def fake_wait(engine_url=rq.ENGINE_URL, qdrant_url=rq.QDRANT_URL, min_points=rq.MIN_INDEXED_POINTS):
        captured_min["value"] = min_points
        return min_points

    monkeypatch.setattr(rq, "wait_for_readiness", fake_wait)
    monkeypatch.setattr(rq, "run_all_queries", lambda eu: [])
    monkeypatch.setattr(rq, "RESULTS_DIR", tmp_path)
    monkeypatch.setattr("sys.argv", ["run_queries.py", "test-config", "--min-points", "2500"])

    rq.main()

    assert captured_min["value"] == 2500
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/benchmark/test_run_queries.py::test_main_accepts_min_points_argument -v`

Expected: FAIL — `main()` doesn't accept `--min-points` yet.

- [ ] **Step 3: Modify `wait_for_readiness` to accept `min_points` parameter**

```python
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
```

- [ ] **Step 4: Add argparse to `main()`**

Replace the current `sys.argv` handling in `main()`:

```python
def main():
    import argparse
    parser = argparse.ArgumentParser(description="Run benchmark queries against the engine REST API")
    parser.add_argument("config_name", help="Configuration name (used as output filename)")
    parser.add_argument("engine_url", nargs="?", default=ENGINE_URL, help="Engine base URL")
    parser.add_argument("--min-points", type=int, default=MIN_INDEXED_POINTS,
                        help=f"Minimum indexed points before starting (default: {MIN_INDEXED_POINTS})")
    args = parser.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    point_count = wait_for_readiness(args.engine_url, min_points=args.min_points)

    print(f"\nRunning benchmark for config: {args.config_name}")
    results = run_all_queries(args.engine_url)

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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `python3 -m pytest scripts/benchmark/test_run_queries.py -v`

Expected: ALL PASS — including the new `test_main_accepts_min_points_argument`.

- [ ] **Step 6: Commit**

```
feat: add --min-points CLI argument to run_queries.py

Overrides MIN_INDEXED_POINTS (default 1900) to prevent the harness
starting on a partial corpus when the garden has grown.

Refs #36
```

---

### Task 3: Write `analyze_bge_m3.py` with tests (TDD)

**Files:**
- Create: `scripts/benchmark/analyze_bge_m3.py`
- Create: `scripts/benchmark/test_analyze_bge_m3.py`

**Interfaces:**
- Consumes: `analyze.py` utility functions (`compute_delta`, `compute_precision`, `compute_rr`, `find_result`, `extract_ge_id`, `load_baseline_scores`, `load_results`, `get_score`), `queries.py` `SCENARIOS` list
- Produces: `scripts/benchmark/results/bge-m3-to-score.json` (unscored entries), `docs/comparison/bge-m3-benchmark.md` (report)

The analysis script has three logical units: (a) scenario analysis producing per-scenario metrics, (b) unscored entry collection, (c) report generation. All are tested through the public `analyze_bge_m3()` and `generate_report()` functions.

- [ ] **Step 1: Write test fixtures**

Create `scripts/benchmark/test_analyze_bge_m3.py` with canned data:

```python
#!/usr/bin/env python3
"""Tests for BGE-M3 benchmark analysis."""

import json
import textwrap
from pathlib import Path

from benchmark.analyze import compute_delta, compute_precision, compute_rr


def _make_entry(ge_id: str, rank: int, title: str = "", body: str = "") -> dict:
    return {"id": f"jvm/{ge_id}.md", "title": title, "body": body, "rank": rank,
            "domain": "jvm", "type": "gotcha", "score": 5, "relevance": 0.8,
            "source": "garden", "sourcePrefix": "GE"}


def _make_result(scenario_id: str, query_type: str, entries: list[dict],
                 latency_median_ms: float = 100.0) -> dict:
    return {
        "scenario_id": scenario_id,
        "query_type": query_type,
        "query_text": "test query",
        "entries": entries,
        "latency_ms": [latency_median_ms] * 3,
        "latency_median_ms": latency_median_ms,
    }


def _make_results_file(config: str, results: list[dict], point_count: int = 2000) -> dict:
    return {
        "config": config,
        "timestamp": "2026-07-02T00:00:00Z",
        "point_count": point_count,
        "num_passes": 3,
        "results": results,
    }


BASELINE_SCORES = {
    "GE-shared-1": {
        "issue-1-reactive-async": {"benchmark_score": 2, "methods": ["gardenSearch-KW"]},
    },
    "GE-shared-2": {
        "issue-1-reactive-async": {"benchmark_score": 1, "methods": ["gardenSearch-KW"]},
    },
    "GE-lost-1": {
        "issue-1-reactive-async": {"benchmark_score": 2, "methods": ["gardenSearch-NL"]},
    },
}
```

- [ ] **Step 2: Write test — delta computation with canned data**

```python
def test_delta_bge_m3_vs_three_leg():
    """Delta between BGE-M3 and three-leg results for a single scenario."""
    three_leg_entries = [
        _make_entry("GE-shared-1", 0),
        _make_entry("GE-shared-2", 1),
        _make_entry("GE-lost-1", 2),
    ]
    bge_m3_entries = [
        _make_entry("GE-shared-1", 0),
        _make_entry("GE-shared-2", 1),
        _make_entry("GE-new-1", 2, title="New entry", body="New entry body"),
    ]
    delta = compute_delta(three_leg_entries, bge_m3_entries)
    assert len(delta["shared"]) == 2
    assert len(delta["new"]) == 1
    assert delta["new"][0]["ge_id"] == "GE-new-1"
    assert len(delta["lost"]) == 1
    assert delta["lost"][0]["ge_id"] == "GE-lost-1"
```

- [ ] **Step 3: Write test — precision with partial scores**

```python
def test_precision_from_scored_entries_only():
    """Precision computed from scored entries, unscored excluded."""
    from benchmark.analyze_bge_m3 import compute_scenario_metrics

    three_leg = _make_results_file("three-leg", [
        _make_result("issue-1-reactive-async", "KW", [
            _make_entry("GE-shared-1", 0),
            _make_entry("GE-shared-2", 1),
            _make_entry("GE-lost-1", 2),
        ]),
    ])
    bge_m3 = _make_results_file("bge-m3-four-signal", [
        _make_result("issue-1-reactive-async", "KW", [
            _make_entry("GE-shared-1", 0),  # score 2
            _make_entry("GE-shared-2", 1),  # score 1
            _make_entry("GE-new-1", 2),     # unscored
        ]),
    ])

    metrics = compute_scenario_metrics(bge_m3, three_leg, BASELINE_SCORES)
    m = metrics[0]
    assert m["scenario_id"] == "issue-1-reactive-async"
    assert m["query_type"] == "KW"
    # Precision: 2 scored entries (scores 2 and 1), both >= 1 → 100%
    assert m["bge_m3_precision"] == 1.0
    assert m["unscored_count"] == 1
    assert len(m["unscored_entries"]) == 1
    assert m["unscored_entries"][0]["ge_id"] == "GE-new-1"
```

- [ ] **Step 4: Write test — MRR calculation**

```python
def test_mrr_calculation():
    """MRR uses score >= 2 threshold."""
    # Scores: [1, 2, 0] → first score>=2 at rank 2 → RR = 1/2
    scores = [1, 2, 0]
    assert compute_rr(scores) == 0.5
```

- [ ] **Step 5: Write test — unscored entry collection format**

```python
def test_unscored_entries_have_required_fields():
    """Unscored entries include ge_id, query_type, config, title, body."""
    from benchmark.analyze_bge_m3 import compute_scenario_metrics

    bge_m3 = _make_results_file("bge-m3-four-signal", [
        _make_result("issue-1-reactive-async", "KW", [
            _make_entry("GE-new-1", 0, title="New Title", body="x" * 600),
        ]),
    ])
    three_leg = _make_results_file("three-leg", [
        _make_result("issue-1-reactive-async", "KW", []),
    ])

    metrics = compute_scenario_metrics(bge_m3, three_leg, {})
    unscored = metrics[0]["unscored_entries"]
    assert len(unscored) == 1
    entry = unscored[0]
    assert entry["ge_id"] == "GE-new-1"
    assert entry["query_type"] == "KW"
    assert entry["config"] == "bge-m3-four-signal"
    assert entry["title"] == "New Title"
    assert len(entry["body"]) <= 500  # truncated
```

- [ ] **Step 6: Write test — empty results handling**

```python
def test_empty_bge_m3_results():
    """Scenario with no BGE-M3 results produces zero precision."""
    from benchmark.analyze_bge_m3 import compute_scenario_metrics

    bge_m3 = _make_results_file("bge-m3-four-signal", [
        _make_result("issue-1-reactive-async", "KW", []),
    ])
    three_leg = _make_results_file("three-leg", [
        _make_result("issue-1-reactive-async", "KW", [
            _make_entry("GE-shared-1", 0),
        ]),
    ])

    metrics = compute_scenario_metrics(bge_m3, three_leg, BASELINE_SCORES)
    assert metrics[0]["bge_m3_precision"] == 0.0
    assert metrics[0]["bge_m3_mrr"] == 0.0
```

- [ ] **Step 7: Write test — report structure**

```python
def test_report_contains_required_sections():
    """Generated report has all required sections."""
    from benchmark.analyze_bge_m3 import generate_report

    scenario_results = [{
        "scenario_id": "issue-1-reactive-async",
        "query_type": "KW",
        "failure_modes": ["SEMANTIC_WIN"],
        "three_leg_precision": 1.0,
        "bge_m3_precision": 0.75,
        "three_leg_mrr": 1.0,
        "bge_m3_mrr": 0.5,
        "three_leg_latency_ms": 256.0,
        "bge_m3_latency_ms": 180.0,
        "delta": {"shared": [], "new": [], "lost": []},
        "unscored_count": 0,
        "unscored_entries": [],
    }]

    report = generate_report(scenario_results, bge_m3_point_count=2050,
                             three_leg_point_count=2026, total_unscored=0)

    assert "## Configuration" in report
    assert "## Executive Summary" in report
    assert "## Headline Results" in report
    assert "## Per-Failure-Mode Analysis" in report
    assert "## Latency" in report
    assert "## Regressions" in report or "No regressions" in report
    assert "## Caveats" in report
    assert "## What Comes Next" in report
    assert "v1.18.0" in report  # Qdrant version pin
    assert "#33" in report
    assert "#34" in report
```

- [ ] **Step 8: Run all tests to verify they fail**

Run: `python3 -m pytest scripts/benchmark/test_analyze_bge_m3.py -v`

Expected: FAIL — `analyze_bge_m3` module does not exist.

- [ ] **Step 9: Implement `analyze_bge_m3.py`**

Create `scripts/benchmark/analyze_bge_m3.py`:

```python
#!/usr/bin/env python3
"""BGE-M3 four-signal benchmark analysis — delta vs three-leg baseline + report."""

import json
import sys
from pathlib import Path

from benchmark.analyze import (
    compute_delta, compute_precision, compute_rr, find_result,
    extract_ge_id, load_baseline_scores, load_results, get_score,
)
from benchmark.queries import SCENARIOS

RESULTS_DIR = Path(__file__).parent / "results"
TO_SCORE_PATH = RESULTS_DIR / "bge-m3-to-score.json"
REPORT_PATH = Path(__file__).parent.parent.parent / "docs" / "comparison" / "bge-m3-benchmark.md"

BGE_M3_CONFIG = "bge-m3-four-signal"
THREE_LEG_CONFIG = "three-leg"


def compute_scenario_metrics(bge_m3_data: dict, three_leg_data: dict,
                             baseline_scores: dict) -> list[dict]:
    """Compute per-scenario metrics comparing BGE-M3 vs three-leg."""
    results = []

    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            bge_entries = find_result(bge_m3_data, scenario.id, qt)
            tl_entries = find_result(three_leg_data, scenario.id, qt)

            delta = compute_delta(tl_entries, bge_entries)

            # Score lookup for BGE-M3 entries
            bge_scores = []
            unscored = []
            for e in bge_entries:
                ge_id = extract_ge_id(e["id"])
                s = get_score(ge_id, scenario.id, baseline_scores, {})
                if s is None:
                    unscored.append({
                        "ge_id": ge_id,
                        "scenario_id": scenario.id,
                        "query_type": qt,
                        "config": BGE_M3_CONFIG,
                        "title": e.get("title", ""),
                        "body": e.get("body", "")[:500],
                    })
                else:
                    bge_scores.append(s)

            # Score lookup for three-leg entries
            tl_scores = []
            for e in tl_entries:
                ge_id = extract_ge_id(e["id"])
                s = get_score(ge_id, scenario.id, baseline_scores, {})
                if s is not None:
                    tl_scores.append(s)

            # Latencies
            bge_lat = None
            for r in bge_m3_data.get("results", []):
                if r["scenario_id"] == scenario.id and r["query_type"] == qt:
                    bge_lat = r.get("latency_median_ms")
            tl_lat = None
            for r in three_leg_data.get("results", []):
                if r["scenario_id"] == scenario.id and r["query_type"] == qt:
                    tl_lat = r.get("latency_median_ms")

            results.append({
                "scenario_id": scenario.id,
                "query_type": qt,
                "failure_modes": scenario.failure_modes,
                "three_leg_precision": compute_precision(tl_scores),
                "bge_m3_precision": compute_precision(bge_scores),
                "three_leg_mrr": compute_rr(tl_scores),
                "bge_m3_mrr": compute_rr(bge_scores),
                "three_leg_latency_ms": tl_lat,
                "bge_m3_latency_ms": bge_lat,
                "delta": delta,
                "unscored_count": len(unscored),
                "unscored_entries": unscored,
            })

    return results


def generate_report(scenario_results: list[dict], bge_m3_point_count: int,
                    three_leg_point_count: int, total_unscored: int) -> str:
    """Generate the benchmark report markdown."""
    lines = ["# BGE-M3 Four-Signal Retrieval Benchmark\n"]
    lines.append(f"*{__import__('datetime').date.today()} · Refs #36*\n")

    # §1 Configuration
    lines.append("## Configuration\n")
    lines.append("| Parameter | Value |")
    lines.append("|-----------|-------|")
    lines.append("| Embedding model | BGE-M3 (BAAI/bge-m3, ONNX, 550M params) |")
    lines.append("| Dense | 1024-dim, cosine, CLS pooling |")
    lines.append("| Sparse | Learned lexical (XLM-RoBERTa 250K vocab, ReLU threshold) |")
    lines.append("| ColBERT | Multi-vector reranking (1024-dim per token, MAX_SIM) |")
    lines.append("| BM25 | Qdrant Document vectors (`qdrant/bm25` model) |")
    lines.append("| RRF | Qdrant-native, three prefetch legs + ColBERT rescore |")
    lines.append(f"| Qdrant | v1.18.0 (pinned) |")
    lines.append(f"| Indexed points | {bge_m3_point_count} (BGE-M3), {three_leg_point_count} (three-leg) |")
    lines.append("")

    # §2 Executive Summary
    lines.append("## Executive Summary\n")
    scored_results = [r for r in scenario_results
                      if r["bge_m3_precision"] is not None]
    if scored_results:
        bge_avg = sum(r["bge_m3_precision"] for r in scored_results) / len(scored_results)
        tl_avg = sum(r["three_leg_precision"] for r in scored_results) / len(scored_results)
        delta_pp = (bge_avg - tl_avg) * 100
        lines.append(f"**Overall precision: {tl_avg:.0%} (three-leg) → {bge_avg:.0%} (BGE-M3) "
                      f"({delta_pp:+.0f}pp)**\n")
    if total_unscored > 0:
        lines.append(f"*{total_unscored} entries unscored — precision may adjust once scored.*\n")

    # §3 Headline Results
    lines.append("## Headline Results\n")
    for qt in ["KW", "NL"]:
        qt_results = [r for r in scenario_results if r["query_type"] == qt]
        lines.append(f"### {qt} Queries\n")
        lines.append("| Scenario | Failure Mode | Three-leg | BGE-M3 | Delta | Shared/New/Lost |")
        lines.append("|---|---|---|---|---|---|")
        for r in qt_results:
            fm = ", ".join(r["failure_modes"]) if r["failure_modes"] else "—"
            tl_p = f"{r['three_leg_precision']:.0%}"
            bge_p = f"{r['bge_m3_precision']:.0%}"
            d = r["bge_m3_precision"] - r["three_leg_precision"]
            delta_str = f"{d * 100:+.0f}pp" if d != 0 else "—"
            delta_data = r["delta"]
            sn_l = f"{len(delta_data['shared'])}/{len(delta_data['new'])}/{len(delta_data['lost'])}"
            lines.append(f"| {r['scenario_id']} | {fm} | {tl_p} | {bge_p} | {delta_str} | {sn_l} |")
        lines.append("")

    # §4 Per-Failure-Mode Analysis
    lines.append("## Per-Failure-Mode Analysis\n")
    lines.append("*Observational only — this benchmark changes the entire pipeline (dense model, "
                 "sparse model, ColBERT reranking). Precision changes cannot be attributed to "
                 "individual signals. Causal attribution is #33 scope.*\n")
    failure_modes = {}
    for r in scenario_results:
        for fm in r["failure_modes"]:
            if fm not in failure_modes:
                failure_modes[fm] = {"tl": [], "bge": []}
            failure_modes[fm]["tl"].append(r["three_leg_precision"])
            failure_modes[fm]["bge"].append(r["bge_m3_precision"])
    if failure_modes:
        lines.append("| Failure Mode | Three-leg avg | BGE-M3 avg | Delta |")
        lines.append("|---|---|---|---|")
        for fm, data in sorted(failure_modes.items()):
            tl_avg = sum(data["tl"]) / len(data["tl"])
            bge_avg = sum(data["bge"]) / len(data["bge"])
            d = (bge_avg - tl_avg) * 100
            lines.append(f"| {fm} | {tl_avg:.0%} | {bge_avg:.0%} | {d:+.0f}pp |")
        lines.append("")

    # §5 Latency
    lines.append("## Latency\n")
    lines.append("*Adoption spec criterion: ≤50% regression over three-leg baseline (256ms → budget 384ms).*\n")
    bge_lats = [r["bge_m3_latency_ms"] for r in scenario_results if r["bge_m3_latency_ms"] is not None]
    tl_lats = [r["three_leg_latency_ms"] for r in scenario_results if r["three_leg_latency_ms"] is not None]
    if bge_lats and tl_lats:
        bge_median = sorted(bge_lats)[len(bge_lats) // 2]
        tl_median = sorted(tl_lats)[len(tl_lats) // 2]
        lines.append(f"| Config | Median | Overhead |")
        lines.append(f"|--------|--------|----------|")
        lines.append(f"| three-leg | {tl_median:.0f}ms | baseline |")
        overhead = bge_median - tl_median
        pct = (overhead / tl_median * 100) if tl_median > 0 else 0
        status = "✅ within budget" if bge_median <= 384 else "⚠️ exceeds 384ms budget"
        lines.append(f"| BGE-M3 four-signal | {bge_median:.0f}ms | {overhead:+.0f}ms ({pct:+.0f}%) — {status} |")
        lines.append("")

    # §6 Regressions
    lines.append("## Regressions\n")
    regressions = [r for r in scenario_results
                   if r["bge_m3_precision"] < r["three_leg_precision"]]
    if regressions:
        for r in regressions:
            d = (r["bge_m3_precision"] - r["three_leg_precision"]) * 100
            lines.append(f"- **{r['scenario_id']}/{r['query_type']}**: "
                         f"{r['three_leg_precision']:.0%} → {r['bge_m3_precision']:.0%} "
                         f"({d:+.0f}pp) — {len(r['delta']['lost'])} entries lost, "
                         f"{r['unscored_count']} unscored")
    else:
        lines.append("No regressions detected.\n")

    # §7 Caveats
    lines.append("\n## Caveats\n")
    lines.append("- The three-leg 94% baseline was computed from scored entries only "
                 "(87 entries from the three-leg run are unscored, per retrieval-research.md). "
                 "BGE-M3 precision uses the same scored-only methodology — the comparison is "
                 "methodologically consistent even if absolute precisions may adjust once all "
                 "entries are scored.")
    lines.append(f"- Corpus size: {three_leg_point_count} (three-leg) → "
                 f"{bge_m3_point_count} (BGE-M3). "
                 f"Delta: {bge_m3_point_count - three_leg_point_count} entries.")
    if total_unscored > 0:
        lines.append(f"- {total_unscored} BGE-M3 entries have no score. "
                     f"Score them in `bge-m3-to-score.json`, then re-run analysis.")
    lines.append("")

    # §8 What Comes Next
    lines.append("## What Comes Next\n")
    lines.append("| # | Description | Dependency |")
    lines.append("|---|-------------|------------|")
    lines.append("| #33 | Convex Combination fusion test — CC (α=0.5) vs RRF | This baseline |")
    lines.append("| #34 | Matryoshka truncation + ColBERT quantization | This baseline |")
    lines.append("")

    return "\n".join(lines)


def main():
    print("Loading results...")
    bge_m3_data = load_results(BGE_M3_CONFIG)
    three_leg_data = load_results(THREE_LEG_CONFIG)
    baseline_scores = load_baseline_scores()

    if not bge_m3_data:
        print(f"No BGE-M3 results found. Run: run_queries.py {BGE_M3_CONFIG}")
        sys.exit(1)
    if not three_leg_data:
        print(f"No three-leg results found at {RESULTS_DIR / f'{THREE_LEG_CONFIG}.json'}")
        sys.exit(1)

    print("Computing scenario metrics...")
    metrics = compute_scenario_metrics(bge_m3_data, three_leg_data, baseline_scores)

    # Collect unscored entries
    all_unscored = []
    for m in metrics:
        all_unscored.extend(m["unscored_entries"])

    if all_unscored:
        grouped = {}
        for entry in all_unscored:
            key = entry["scenario_id"]
            if key not in grouped:
                scenario = next((s for s in SCENARIOS if s.id == key), None)
                grouped[key] = {
                    "context": scenario.context if scenario else "",
                    "entries": [],
                }
            grouped[key]["entries"].append({
                "ge_id": entry["ge_id"],
                "query_type": entry["query_type"],
                "config": entry["config"],
                "title": entry["title"],
                "body": entry["body"],
            })
        TO_SCORE_PATH.write_text(json.dumps(grouped, indent=2))
        print(f"  {len(all_unscored)} entries need scoring → {TO_SCORE_PATH}")

    # Generate report
    bge_point_count = bge_m3_data.get("point_count", 0)
    tl_point_count = three_leg_data.get("point_count", 0)
    report = generate_report(metrics, bge_point_count, tl_point_count, len(all_unscored))

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(report)
    print(f"Report written to {REPORT_PATH}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 10: Run all tests to verify they pass**

Run: `python3 -m pytest scripts/benchmark/test_analyze_bge_m3.py -v`

Expected: ALL PASS

- [ ] **Step 11: Run full test suite**

Run: `python3 -m pytest scripts/benchmark/ -v --ignore=scripts/benchmark/__pycache__`

Expected: ALL PASS — no regressions in existing tests.

- [ ] **Step 12: Commit**

```
feat: BGE-M3 benchmark analysis script with tests

analyze_bge_m3.py imports utility functions from analyze.py and
compares BGE-M3 four-signal results against the three-leg baseline.
Generates docs/comparison/bge-m3-benchmark.md with per-scenario
precision, failure-mode breakdown, latency, and regressions.

Refs #36
```

---

### Task 4: Run the benchmark (manual + harness)

This task is a sequence of manual operations and automated harness runs. It produces the raw benchmark data.

**Files:**
- Modify: `src/main/resources/application.properties` — uncomment `%dev` BGE-M3 properties
- Produces: `scripts/benchmark/results/bge-m3-four-signal.json`

**Prerequisites:**
- Task 1 committed (export fix)
- Task 2 committed (--min-points)
- Task 3 committed (analysis script)
- Python export dependencies installed (`pip install -r scripts/requirements-export.txt`)
- Docker available for Qdrant

- [ ] **Step 1: Re-export the BGE-M3 model**

Delete the broken model files and re-export:

```bash
rm -f ~/.hortora/models/bge-m3/model.onnx ~/.hortora/models/bge-m3/model.onnx.data
python3 scripts/export_bge_m3.py
```

Expected output: model exported to `~/.hortora/models/bge-m3/model.onnx` + `model.onnx.data`, validation passed, checksums written.

This takes ~3-5 minutes and ~8GB RAM. The export downloads ~2.2GB of PyTorch weights on first run.

- [ ] **Step 2: Verify the export**

```bash
bash scripts/download-models.sh
```

Expected: all three files verified (model.onnx, model.onnx.data, tokenizer.json).

- [ ] **Step 3: Commit updated checksums**

The export produces new `scripts/bge-m3-checksums.sha256` with three entries.

```
chore: update BGE-M3 checksums for external data format

Refs #36
```

- [ ] **Step 4: Uncomment `%dev` properties**

In `src/main/resources/application.properties`, uncomment:

```properties
%dev.casehub.inference.models.bge-m3.model-path=${user.home}/.hortora/models/bge-m3/model.onnx
%dev.casehub.inference.models.bge-m3.tokenizer-path=${user.home}/.hortora/models/bge-m3/tokenizer.json
%dev.casehub.inference.models.bge-m3.maxSequenceLength=8192
```

- [ ] **Step 5: Check corpus size and compute min-points**

```bash
find ~/.hortora/garden -name '*.md' -not -name 'GARDEN.md' -not -name 'CHECKED.md' -not -name 'DISCARDED.md' -not -name 'INDEX.md' | wc -l
```

Compute 90% of the result for the `--min-points` argument.

- [ ] **Step 6: Start fresh Qdrant**

```bash
docker run -d --rm -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.18.0
```

- [ ] **Step 7: Start engine in dev mode**

```bash
./mvnw quarkus:dev
```

Wait for startup and indexing. Watch logs for `CollectionMigration` creating the BGE-M3 collection schema.

- [ ] **Step 8: Run the benchmark**

```bash
python3 scripts/benchmark/run_queries.py bge-m3-four-signal --min-points <computed-value>
```

Expected: 28 queries run (14 scenarios × KW + NL), results written to `scripts/benchmark/results/bge-m3-four-signal.json`.

- [ ] **Step 9: Generate the report**

```bash
python3 scripts/benchmark/analyze_bge_m3.py
```

Expected: report written to `docs/comparison/bge-m3-benchmark.md`, unscored entries to `scripts/benchmark/results/bge-m3-to-score.json`.

- [ ] **Step 10: Review the report and commit results**

Review `docs/comparison/bge-m3-benchmark.md` for completeness. Check the go/no-go gate: precision vs 94%, latency vs 384ms budget.

```
feat: BGE-M3 four-signal benchmark results and report

Overall precision: X% (vs 94% three-leg baseline).
Latency: Xms median (vs 256ms three-leg, budget 384ms).
N entries unscored — score in bge-m3-to-score.json.

Closes #36
```

- [ ] **Step 11: Re-comment `%dev` properties**

Restore `application.properties` to its committed state (dev properties commented out) so the main branch builds without the ONNX model.

```
chore: re-comment %dev BGE-M3 properties after benchmark

Refs #36
```
