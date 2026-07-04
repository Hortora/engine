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
