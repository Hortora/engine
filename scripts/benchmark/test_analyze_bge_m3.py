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


def test_mrr_calculation():
    """MRR uses score >= 2 threshold."""
    # Scores: [1, 2, 0] → first score>=2 at rank 2 → RR = 1/2
    scores = [1, 2, 0]
    assert compute_rr(scores) == 0.5


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
