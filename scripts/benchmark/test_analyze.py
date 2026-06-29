# scripts/benchmark/test_analyze.py
from benchmark.analyze import (
    extract_ge_id, compute_delta, compute_precision, compute_rr,
    check_consistency,
)

def test_extract_ge_id():
    assert extract_ge_id("jvm/GE-20260428-fd7a65.md") == "GE-20260428-fd7a65"
    assert extract_ge_id("GE-20260428-fd7a65") == "GE-20260428-fd7a65"
    assert extract_ge_id("tools/approaches/testing.md") == "tools/approaches/testing"

def test_compute_delta_shared():
    baseline = [{"id": "jvm/GE-aaa.md", "rank": 0}, {"id": "jvm/GE-bbb.md", "rank": 1}]
    hybrid = [{"id": "jvm/GE-bbb.md", "rank": 0}, {"id": "jvm/GE-aaa.md", "rank": 1}]
    delta = compute_delta(baseline, hybrid)
    assert len(delta["shared"]) == 2
    assert len(delta["new"]) == 0
    assert len(delta["lost"]) == 0
    shared_ids = {s["ge_id"] for s in delta["shared"]}
    assert "GE-aaa" in shared_ids
    assert "GE-bbb" in shared_ids

def test_compute_delta_new_and_lost():
    baseline = [{"id": "jvm/GE-aaa.md", "rank": 0}]
    hybrid = [{"id": "jvm/GE-bbb.md", "rank": 0}]
    delta = compute_delta(baseline, hybrid)
    assert len(delta["shared"]) == 0
    assert len(delta["new"]) == 1
    assert delta["new"][0]["ge_id"] == "GE-bbb"
    assert len(delta["lost"]) == 1
    assert delta["lost"][0]["ge_id"] == "GE-aaa"

def test_compute_precision():
    scored = [2, 1, 0, 2, 0, 1, 0, 0]
    assert compute_precision(scored, threshold=1) == 4 / 8
    assert compute_precision(scored, threshold=2) == 2 / 8

def test_compute_precision_empty():
    assert compute_precision([], threshold=1) == 0.0

def test_compute_rr():
    scored = [0, 0, 2, 1]
    assert compute_rr(scored) == 1 / 3  # first score=2 at rank 3 (1-indexed)

def test_compute_rr_no_relevant():
    scored = [0, 0, 0]
    assert compute_rr(scored) == 0.0

def test_consistency_check_identical():
    baseline_ids = {"GE-aaa", "GE-bbb", "GE-ccc"}
    rerun_ids = {"GE-aaa", "GE-bbb", "GE-ccc"}
    result = check_consistency(baseline_ids, rerun_ids, "test-scenario", "KW")
    assert result["match"] is True
    assert result["added"] == set()
    assert result["removed"] == set()

def test_consistency_check_diverged():
    baseline_ids = {"GE-aaa", "GE-bbb"}
    rerun_ids = {"GE-aaa", "GE-ccc"}
    result = check_consistency(baseline_ids, rerun_ids, "test-scenario", "KW")
    assert result["match"] is False
    assert result["added"] == {"GE-ccc"}
    assert result["removed"] == {"GE-bbb"}
