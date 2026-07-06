#!/usr/bin/env python3
"""grep vs gardenSearch definitive comparison across all benchmark configs."""

import json
from collections import defaultdict
from pathlib import Path

from benchmark.analyze import (
    compute_precision, compute_rr, find_result, extract_ge_id,
    load_baseline_scores, load_results, get_score,
)
from benchmark.queries import SCENARIOS

RESULTS_DIR = Path(__file__).parent / "results"
REPORT_PATH = Path(__file__).parent.parent.parent / "docs" / "comparison" / "grep-vs-gardensearch.md"


def compute_grep_metrics(baseline_scores: dict) -> list[dict]:
    """Compute grep precision per scenario from baseline scores."""
    results = []
    for scenario in SCENARIOS:
        grep_scores = []
        grep_relevant = 0
        grep_total = 0
        for ge_id, scenarios_data in baseline_scores.items():
            if scenario.id in scenarios_data:
                entry = scenarios_data[scenario.id]
                if "grep" in entry.get("methods", []):
                    grep_total += 1
                    score = entry.get("benchmark_score", 0)
                    grep_scores.append(score)
                    if score >= 1:
                        grep_relevant += 1
        results.append({
            "scenario_id": scenario.id,
            "grep_precision": compute_precision(grep_scores),
            "grep_mrr": compute_rr(grep_scores),
            "grep_total": grep_total,
            "grep_relevant": grep_relevant,
        })
    return results


def compute_gs_metrics(config_name: str, baseline_scores: dict) -> list[dict]:
    """Compute gardenSearch metrics for a config."""
    data = load_results(config_name)
    if not data:
        return []
    results = []
    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            entries = find_result(data, scenario.id, qt)
            scores = []
            relevant = 0
            unscored = 0
            for e in entries:
                ge_id = extract_ge_id(e["id"])
                s = get_score(ge_id, scenario.id, baseline_scores, {})
                if s is not None:
                    scores.append(s)
                    if s >= 1:
                        relevant += 1
                else:
                    unscored += 1

            latency = None
            for r in data.get("results", []):
                if r["scenario_id"] == scenario.id and r["query_type"] == qt:
                    latency = r.get("latency_median_ms")

            results.append({
                "scenario_id": scenario.id,
                "query_type": qt,
                "config": config_name,
                "precision": compute_precision(scores),
                "mrr": compute_rr(scores),
                "total": len(entries),
                "scored": len(scores),
                "relevant": relevant,
                "unscored": unscored,
                "latency_ms": latency,
            })
    return results


def find_unique_hits(config_name: str, baseline_scores: dict) -> dict:
    """Find entries that only gardenSearch found (not in grep) and vice versa."""
    data = load_results(config_name)
    if not data:
        return {}

    result = {}
    for scenario in SCENARIOS:
        grep_ids = set()
        for ge_id, scenarios_data in baseline_scores.items():
            if scenario.id in scenarios_data:
                entry = scenarios_data[scenario.id]
                if "grep" in entry.get("methods", []):
                    grep_ids.add(ge_id)

        for qt in ["KW", "NL"]:
            entries = find_result(data, scenario.id, qt)
            gs_ids = {extract_ge_id(e["id"]) for e in entries}

            gs_only = gs_ids - grep_ids
            grep_only = grep_ids - gs_ids
            both = gs_ids & grep_ids

            gs_only_relevant = 0
            for ge_id in gs_only:
                s = get_score(ge_id, scenario.id, baseline_scores, {})
                if s is not None and s >= 1:
                    gs_only_relevant += 1

            grep_only_relevant = 0
            for ge_id in grep_only:
                s = get_score(ge_id, scenario.id, baseline_scores, {})
                if s is not None and s >= 1:
                    grep_only_relevant += 1

            key = f"{scenario.id}/{qt}"
            result[key] = {
                "gs_only": len(gs_only),
                "gs_only_relevant": gs_only_relevant,
                "grep_only": len(grep_only),
                "grep_only_relevant": grep_only_relevant,
                "both": len(both),
            }
    return result


def generate_report(grep_metrics: list[dict], gs_metrics: list[dict],
                    unique_hits: dict, config_name: str) -> str:
    lines = ["# grep vs gardenSearch — Definitive Comparison\n"]
    lines.append(f"*{__import__('datetime').date.today()} · Comprehensive retrieval evaluation*\n")

    lines.append("## Context\n")
    lines.append("This report compares grep (regex pattern matching) against gardenSearch ")
    lines.append(f"(BGE-M3 four-signal hybrid retrieval, config: `{config_name}`) ")
    lines.append("across 14 benchmark scenarios covering 6 real issues and 8 spec review domains.\n")
    lines.append("Each scenario tests both keyword (KW) and natural language (NL) queries. ")
    lines.append("grep uses the same query for both; gardenSearch uses tailored queries for each.\n")

    # Executive summary
    lines.append("## Executive Summary\n")
    grep_precs = [g["grep_precision"] for g in grep_metrics]
    grep_avg = sum(grep_precs) / len(grep_precs) if grep_precs else 0

    gs_kw = [g for g in gs_metrics if g["query_type"] == "KW"]
    gs_nl = [g for g in gs_metrics if g["query_type"] == "NL"]
    gs_kw_avg = sum(g["precision"] for g in gs_kw) / len(gs_kw) if gs_kw else 0
    gs_nl_avg = sum(g["precision"] for g in gs_nl) / len(gs_nl) if gs_nl else 0
    gs_all_avg = sum(g["precision"] for g in gs_metrics) / len(gs_metrics) if gs_metrics else 0

    grep_lats = "~5-15ms (local file scan)"
    gs_lats = [g["latency_ms"] for g in gs_metrics if g["latency_ms"]]
    gs_med_lat = sorted(gs_lats)[len(gs_lats) // 2] if gs_lats else 0

    lines.append("| Method | Avg Precision | Median Latency | Strengths |")
    lines.append("|--------|--------------|----------------|-----------|")
    lines.append(f"| grep | {grep_avg:.0%} | {grep_lats} | Exact term matching, zero false positives on exact patterns |")
    lines.append(f"| gardenSearch (KW) | {gs_kw_avg:.0%} | {gs_med_lat:.0f}ms | Handles vocabulary gaps, semantic similarity |")
    lines.append(f"| gardenSearch (NL) | {gs_nl_avg:.0%} | {gs_med_lat:.0f}ms | Natural language understanding, concept matching |")
    lines.append("")

    # Grep has high recall on exact terms but returns lots of noise (low precision)
    # gardenSearch has lower recall on exact terms but higher precision
    total_gs_only_relevant = sum(v["gs_only_relevant"] for v in unique_hits.values())
    total_grep_only_relevant = sum(v["grep_only_relevant"] for v in unique_hits.values())
    lines.append(f"**Unique relevant finds:** gardenSearch found {total_gs_only_relevant} relevant entries ")
    lines.append(f"that grep missed. grep found {total_grep_only_relevant} relevant entries ")
    lines.append("that gardenSearch missed.\n")

    # Per-scenario head-to-head
    lines.append("## Head-to-Head: Per-Scenario\n")
    lines.append("| Scenario | Failure Mode | grep | gS (KW) | gS (NL) | Winner | grep-only relevant | gS-only relevant |")
    lines.append("|---|---|---|---|---|---|---|---|")

    grep_wins = 0
    gs_wins = 0
    ties = 0

    for scenario in SCENARIOS:
        grep_data = next((g for g in grep_metrics if g["scenario_id"] == scenario.id), None)
        gs_kw_data = next((g for g in gs_metrics
                          if g["scenario_id"] == scenario.id and g["query_type"] == "KW"), None)
        gs_nl_data = next((g for g in gs_metrics
                          if g["scenario_id"] == scenario.id and g["query_type"] == "NL"), None)

        if not grep_data or not gs_kw_data or not gs_nl_data:
            continue

        gp = grep_data["grep_precision"]
        kp = gs_kw_data["precision"]
        np_ = gs_nl_data["precision"]
        best_gs = max(kp, np_)

        uh_kw = unique_hits.get(f"{scenario.id}/KW", {})
        uh_nl = unique_hits.get(f"{scenario.id}/NL", {})
        grep_only_rel = uh_kw.get("grep_only_relevant", 0) + uh_nl.get("grep_only_relevant", 0)
        gs_only_rel = uh_kw.get("gs_only_relevant", 0) + uh_nl.get("gs_only_relevant", 0)

        if best_gs > gp + 0.05:
            winner = "gardenSearch"
            gs_wins += 1
        elif gp > best_gs + 0.05:
            winner = "grep"
            grep_wins += 1
        else:
            winner = "tie"
            ties += 1

        fm = ", ".join(scenario.failure_modes) if scenario.failure_modes else "—"
        lines.append(f"| {scenario.id} | {fm} | {gp:.0%} | {kp:.0%} | {np_:.0%} | "
                     f"**{winner}** | {grep_only_rel} | {gs_only_rel} |")

    lines.append("")
    lines.append(f"**Score: gardenSearch {gs_wins}, grep {grep_wins}, ties {ties}**\n")

    # What grep catches that gardenSearch misses
    lines.append("## What grep catches that gardenSearch misses\n")
    grep_advantages = []
    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            key = f"{scenario.id}/{qt}"
            uh = unique_hits.get(key, {})
            if uh.get("grep_only_relevant", 0) > 0:
                grep_advantages.append({
                    "scenario": scenario.id,
                    "qt": qt,
                    "count": uh["grep_only_relevant"],
                    "total_grep_only": uh["grep_only"],
                })
    if grep_advantages:
        for ga in grep_advantages:
            lines.append(f"- **{ga['scenario']}/{ga['qt']}**: {ga['count']} relevant entries "
                         f"(out of {ga['total_grep_only']} grep-only) not found by gardenSearch")
    else:
        lines.append("None — gardenSearch found everything grep found.\n")

    # What gardenSearch catches that grep misses
    lines.append("\n## What gardenSearch catches that grep misses\n")
    gs_advantages = []
    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            key = f"{scenario.id}/{qt}"
            uh = unique_hits.get(key, {})
            if uh.get("gs_only_relevant", 0) > 0:
                gs_advantages.append({
                    "scenario": scenario.id,
                    "qt": qt,
                    "count": uh["gs_only_relevant"],
                    "total_gs_only": uh["gs_only"],
                })
    if gs_advantages:
        for ga in gs_advantages:
            lines.append(f"- **{ga['scenario']}/{ga['qt']}**: {ga['count']} relevant entries "
                         f"(out of {ga['total_gs_only']} gS-only) that grep cannot find")
    else:
        lines.append("None.\n")

    # Failure mode analysis
    lines.append("\n## By Failure Mode\n")
    lines.append("| Failure Mode | grep avg | gS (KW) avg | gS (NL) avg | Interpretation |")
    lines.append("|---|---|---|---|---|")
    fm_data = defaultdict(lambda: {"grep": [], "kw": [], "nl": []})
    for scenario in SCENARIOS:
        grep_data = next((g for g in grep_metrics if g["scenario_id"] == scenario.id), None)
        gs_kw_data = next((g for g in gs_metrics
                          if g["scenario_id"] == scenario.id and g["query_type"] == "KW"), None)
        gs_nl_data = next((g for g in gs_metrics
                          if g["scenario_id"] == scenario.id and g["query_type"] == "NL"), None)
        if not grep_data or not gs_kw_data or not gs_nl_data:
            continue
        for fm in scenario.failure_modes:
            fm_data[fm]["grep"].append(grep_data["grep_precision"])
            fm_data[fm]["kw"].append(gs_kw_data["precision"])
            fm_data[fm]["nl"].append(gs_nl_data["precision"])

    interpretations = {
        "VOCABULARY_GAP": "gardenSearch overcomes vocab mismatch via learned embeddings",
        "SEMANTIC_WIN": "gardenSearch finds conceptually related entries grep cannot match",
        "POLYSEMY": "Common terms (Instance, filter) return noise in both methods",
        "DOMAIN_ABSENCE": "Neither method finds entries that don't exist in the corpus",
        "UNAMBIGUOUS_TERM": "Exact technical terms — grep's natural strength",
    }
    for fm, data in sorted(fm_data.items()):
        ga = sum(data["grep"]) / len(data["grep"]) if data["grep"] else 0
        ka = sum(data["kw"]) / len(data["kw"]) if data["kw"] else 0
        na = sum(data["nl"]) / len(data["nl"]) if data["nl"] else 0
        interp = interpretations.get(fm, "")
        lines.append(f"| {fm} | {ga:.0%} | {ka:.0%} | {na:.0%} | {interp} |")
    lines.append("")

    # Recommendation
    lines.append("## Recommendation\n")
    lines.append("*TODO: fill after full analysis*\n")

    return "\n".join(lines)


def main():
    baseline_scores = load_baseline_scores()
    config = "bge-m3-four-signal"

    print("Computing grep metrics...")
    grep_metrics = compute_grep_metrics(baseline_scores)

    print("Computing gardenSearch metrics...")
    gs_metrics = compute_gs_metrics(config, baseline_scores)

    print("Finding unique hits...")
    unique_hits = find_unique_hits(config, baseline_scores)

    print("Generating report...")
    report = generate_report(grep_metrics, gs_metrics, unique_hits, config)

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(report)
    print(f"Report written to {REPORT_PATH}")

    # Print summary
    grep_precs = [g["grep_precision"] for g in grep_metrics]
    gs_precs = [g["precision"] for g in gs_metrics]
    print(f"\ngrep avg precision: {sum(grep_precs)/len(grep_precs):.0%}")
    print(f"gardenSearch avg precision: {sum(gs_precs)/len(gs_precs):.0%}")


if __name__ == "__main__":
    main()
