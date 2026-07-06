#!/usr/bin/env python3
"""Fusion strategy comparison — CC/DBSF vs RRF baseline on BGE-M3 four-signal."""

import json
import sys
from pathlib import Path

from benchmark.analyze import (
    compute_delta, compute_precision, compute_rr, find_result,
    extract_ge_id, load_baseline_scores, load_results, get_score,
)
from benchmark.queries import SCENARIOS

RESULTS_DIR = Path(__file__).parent / "results"
REPORT_PATH = Path(__file__).parent.parent.parent / "docs" / "comparison" / "fusion-benchmark.md"

BASELINE_CONFIG = "bge-m3-four-signal"


def compute_comparison(baseline_data: dict, variant_data: dict,
                       variant_name: str, baseline_scores: dict) -> list[dict]:
    results = []
    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            base_entries = find_result(baseline_data, scenario.id, qt)
            var_entries = find_result(variant_data, scenario.id, qt)
            delta = compute_delta(base_entries, var_entries)

            base_scores = []
            for e in base_entries:
                s = get_score(extract_ge_id(e["id"]), scenario.id, baseline_scores, {})
                if s is not None:
                    base_scores.append(s)

            var_scores = []
            unscored = 0
            for e in var_entries:
                s = get_score(extract_ge_id(e["id"]), scenario.id, baseline_scores, {})
                if s is not None:
                    var_scores.append(s)
                else:
                    unscored += 1

            base_lat = None
            for r in baseline_data.get("results", []):
                if r["scenario_id"] == scenario.id and r["query_type"] == qt:
                    base_lat = r.get("latency_median_ms")
            var_lat = None
            for r in variant_data.get("results", []):
                if r["scenario_id"] == scenario.id and r["query_type"] == qt:
                    var_lat = r.get("latency_median_ms")

            results.append({
                "scenario_id": scenario.id,
                "query_type": qt,
                "failure_modes": scenario.failure_modes,
                "base_precision": compute_precision(base_scores),
                "var_precision": compute_precision(var_scores),
                "base_mrr": compute_rr(base_scores),
                "var_mrr": compute_rr(var_scores),
                "base_latency_ms": base_lat,
                "var_latency_ms": var_lat,
                "delta": delta,
                "unscored": unscored,
                "base_scored_count": len(base_scores),
                "var_scored_count": len(var_scores),
            })
    return results


def generate_report(comparisons: dict[str, list[dict]],
                    point_counts: dict[str, int]) -> str:
    lines = ["# Fusion Strategy Benchmark — CC / DBSF vs RRF\n"]
    lines.append(f"*{__import__('datetime').date.today()} · Refs #33*\n")

    lines.append("## Configuration\n")
    lines.append("| Parameter | Value |")
    lines.append("|-----------|-------|")
    lines.append("| Embedding model | BGE-M3 (BAAI/bge-m3, ONNX, 550M params) |")
    lines.append("| Signals | Dense (1024-dim) + Sparse (learned) + BM25 + ColBERT rescore |")
    lines.append("| Baseline fusion | RRF (k=60) |")
    for name in comparisons:
        if name == "bge-m3-cc":
            lines.append("| Variant: CC | Convex Combination (dense=0.5, sparse=0.3, bm25=0.2) |")
        elif name == "bge-m3-dbsf":
            lines.append("| Variant: DBSF | Distribution-Based Score Fusion |")
    for name, count in point_counts.items():
        lines.append(f"| Indexed points ({name}) | {count} |")
    lines.append("")

    lines.append("## Executive Summary\n")
    for name, results in comparisons.items():
        scored = [r for r in results if r["var_precision"] is not None and r["base_scored_count"] > 0]
        if scored:
            base_avg = sum(r["base_precision"] for r in scored) / len(scored)
            var_avg = sum(r["var_precision"] for r in scored) / len(scored)
            delta_pp = (var_avg - base_avg) * 100

            base_lats = [r["base_latency_ms"] for r in results if r["base_latency_ms"]]
            var_lats = [r["var_latency_ms"] for r in results if r["var_latency_ms"]]
            base_med = sorted(base_lats)[len(base_lats) // 2] if base_lats else 0
            var_med = sorted(var_lats)[len(var_lats) // 2] if var_lats else 0

            label = name.replace("bge-m3-", "").upper()
            lines.append(f"**{label} vs RRF:** precision {base_avg:.0%} → {var_avg:.0%} "
                         f"({delta_pp:+.0f}pp), latency {base_med:.0f}ms → {var_med:.0f}ms\n")

    improved = 0
    regressed = 0
    unchanged = 0
    for name, results in comparisons.items():
        for r in results:
            d = r["var_precision"] - r["base_precision"]
            if d > 0.001:
                improved += 1
            elif d < -0.001:
                regressed += 1
            else:
                unchanged += 1
    lines.append(f"Scenario breakdown: {improved} improved, {regressed} regressed, {unchanged} unchanged\n")

    for name, results in comparisons.items():
        label = name.replace("bge-m3-", "").upper()
        lines.append(f"## {label} vs RRF — Per-Scenario\n")
        for qt in ["KW", "NL"]:
            qt_results = [r for r in results if r["query_type"] == qt]
            lines.append(f"### {qt} Queries\n")
            lines.append("| Scenario | Failure Mode | RRF | " + label +
                         " | Delta | Latency RRF→" + label + " | Shared/New/Lost |")
            lines.append("|---|---|---|---|---|---|---|")
            for r in qt_results:
                fm = ", ".join(r["failure_modes"]) if r["failure_modes"] else "—"
                bp = f"{r['base_precision']:.0%}"
                vp = f"{r['var_precision']:.0%}"
                d = r["var_precision"] - r["base_precision"]
                ds = f"{d * 100:+.0f}pp" if abs(d) > 0.001 else "—"
                bl = f"{r['base_latency_ms']:.0f}" if r["base_latency_ms"] else "?"
                vl = f"{r['var_latency_ms']:.0f}" if r["var_latency_ms"] else "?"
                snl = (f"{len(r['delta']['shared'])}/"
                       f"{len(r['delta']['new'])}/"
                       f"{len(r['delta']['lost'])}")
                lines.append(f"| {r['scenario_id']} | {fm} | {bp} | {vp} | {ds} | "
                             f"{bl}→{vl}ms | {snl} |")
            lines.append("")

    lines.append("## Regressions\n")
    any_regression = False
    for name, results in comparisons.items():
        label = name.replace("bge-m3-", "").upper()
        for r in results:
            d = r["var_precision"] - r["base_precision"]
            if d < -0.001:
                any_regression = True
                lines.append(f"- **{label} {r['scenario_id']}/{r['query_type']}**: "
                             f"{r['base_precision']:.0%} → {r['var_precision']:.0%} "
                             f"({d * 100:+.0f}pp) — {len(r['delta']['lost'])} entries lost")
    if not any_regression:
        lines.append("No regressions detected.\n")

    lines.append("\n## Improvements\n")
    any_improvement = False
    for name, results in comparisons.items():
        label = name.replace("bge-m3-", "").upper()
        for r in results:
            d = r["var_precision"] - r["base_precision"]
            if d > 0.001:
                any_improvement = True
                lines.append(f"- **{label} {r['scenario_id']}/{r['query_type']}**: "
                             f"{r['base_precision']:.0%} → {r['var_precision']:.0%} "
                             f"({d * 100:+.0f}pp) — {len(r['delta']['new'])} new entries")
    if not any_improvement:
        lines.append("No improvements detected.\n")

    lines.append("\n## Verdict\n")
    lines.append("*TODO: fill in after reviewing results*\n")

    return "\n".join(lines)


def main():
    baseline_data = load_results(BASELINE_CONFIG)
    if not baseline_data:
        print(f"No baseline results found ({BASELINE_CONFIG}). Run the RRF benchmark first.")
        sys.exit(1)

    baseline_scores = load_baseline_scores()
    comparisons = {}
    point_counts = {BASELINE_CONFIG: baseline_data.get("point_count", 0)}

    for variant in sys.argv[1:] if len(sys.argv) > 1 else ["bge-m3-cc"]:
        var_data = load_results(variant)
        if not var_data:
            print(f"No results for {variant}, skipping.")
            continue
        comparisons[variant] = compute_comparison(baseline_data, var_data, variant, baseline_scores)
        point_counts[variant] = var_data.get("point_count", 0)

    if not comparisons:
        print("No variant results found to compare.")
        sys.exit(1)

    report = generate_report(comparisons, point_counts)
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(report)
    print(f"Report written to {REPORT_PATH}")

    for name, results in comparisons.items():
        label = name.replace("bge-m3-", "").upper()
        scored = [r for r in results if r["base_scored_count"] > 0]
        base_avg = sum(r["base_precision"] for r in scored) / len(scored) if scored else 0
        var_avg = sum(r["var_precision"] for r in scored) / len(scored) if scored else 0
        print(f"\n{label} vs RRF: {base_avg:.0%} → {var_avg:.0%} ({(var_avg - base_avg) * 100:+.0f}pp)")


if __name__ == "__main__":
    main()
