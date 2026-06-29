#!/usr/bin/env python3
"""Delta analysis across benchmark configs + report generation."""

import json
import re
import sys
from pathlib import Path

from benchmark.queries import SCENARIOS

RESULTS_DIR = Path(__file__).parent / "results"
BASELINE_PATH = Path(__file__).parent / "baseline_scores.json"
HYBRID_SCORES_PATH = Path(__file__).parent / "hybrid_scores.json"
TO_SCORE_PATH = Path(__file__).parent / "to_score.json"
REPORT_PATH = Path(__file__).parent.parent.parent / "docs" / "comparison" / "hybrid-benchmark.md"


def extract_ge_id(path: str) -> str:
    """Strip .md extension and path prefix if it's a GE-ID, otherwise keep the full path."""
    without_ext = re.sub(r"\.md$", "", path)
    filename = without_ext.split("/")[-1] if "/" in without_ext else without_ext
    # If the filename starts with GE-, treat it as a GE-ID and return just the filename
    if filename.startswith("GE-"):
        return filename
    # For non-GE-ID entries, return the full path (without .md)
    return without_ext


def compute_delta(baseline_entries: list[dict], hybrid_entries: list[dict]) -> dict:
    baseline_ids = {extract_ge_id(e["id"]): e for e in baseline_entries}
    hybrid_ids = {extract_ge_id(e["id"]): e for e in hybrid_entries}
    shared, new, lost = [], [], []
    for ge_id, entry in hybrid_ids.items():
        if ge_id in baseline_ids:
            shared.append({
                "ge_id": ge_id,
                "baseline_rank": baseline_ids[ge_id]["rank"],
                "hybrid_rank": entry["rank"],
                "rank_change": baseline_ids[ge_id]["rank"] - entry["rank"],
            })
        else:
            new.append({"ge_id": ge_id, "hybrid_rank": entry["rank"],
                        "title": entry.get("title", ""), "body": entry.get("body", "")})
    for ge_id, entry in baseline_ids.items():
        if ge_id not in hybrid_ids:
            lost.append({"ge_id": ge_id, "baseline_rank": entry["rank"],
                         "title": entry.get("title", "")})
    return {"shared": shared, "new": new, "lost": lost}


def compute_precision(scores: list[int], threshold: int = 1) -> float:
    if not scores:
        return 0.0
    return sum(1 for s in scores if s >= threshold) / len(scores)


def compute_rr(scores: list[int]) -> float:
    for i, s in enumerate(scores):
        if s >= 2:
            return 1.0 / (i + 1)
    return 0.0


def load_results(config_name: str) -> dict:
    path = RESULTS_DIR / f"{config_name}.json"
    if not path.exists():
        return {}
    return json.loads(path.read_text())


def load_baseline_scores() -> dict:
    if not BASELINE_PATH.exists():
        return {}
    return json.loads(BASELINE_PATH.read_text())


def load_hybrid_scores() -> dict:
    if not HYBRID_SCORES_PATH.exists():
        return {}
    return json.loads(HYBRID_SCORES_PATH.read_text())


def get_score(ge_id: str, scenario_id: str, baseline: dict, hybrid_scores: dict) -> int | None:
    if ge_id in hybrid_scores and scenario_id in hybrid_scores[ge_id]:
        return hybrid_scores[ge_id][scenario_id]["benchmark_score"]
    if ge_id in baseline and scenario_id in baseline[ge_id]:
        return baseline[ge_id][scenario_id]["benchmark_score"]
    return None


def find_result(results: dict, scenario_id: str, query_type: str) -> list[dict]:
    for r in results.get("results", []):
        if r["scenario_id"] == scenario_id and r["query_type"] == query_type:
            return r.get("entries", [])
    return []


def check_consistency(baseline_ids: set[str], rerun_ids: set[str],
                      scenario_id: str, query_type: str) -> dict:
    """Check if rerun results match baseline results.

    Returns a dict with:
    - scenario_id: the scenario being checked
    - query_type: the query type (KW or NL)
    - match: True if results are identical
    - added: set of IDs in rerun but not baseline
    - removed: set of IDs in baseline but not rerun
    """
    added = rerun_ids - baseline_ids
    removed = baseline_ids - rerun_ids
    return {
        "scenario_id": scenario_id,
        "query_type": query_type,
        "match": added == set() and removed == set(),
        "added": added,
        "removed": removed,
    }


def run_consistency_check():
    """Validate dense-only results against #27 baseline.

    Loads baseline_scores and dense-only results, compares per-scenario
    result sets, and reports divergence. Halts if >10% of entries changed.
    """
    baseline_scores = load_baseline_scores()
    dense_only = load_results("dense-only")
    if not dense_only:
        print("No dense-only results found. Run: run_queries.py dense-only")
        return False

    total_checked = 0
    total_diverged = 0
    diverged_scenarios = []

    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            entries = find_result(dense_only, scenario.id, qt)
            rerun_ids = {extract_ge_id(e["id"]) for e in entries}

            baseline_ids = set()
            method_key = f"gardenSearch-{qt}"
            for ge_id, scenarios in baseline_scores.items():
                if scenario.id in scenarios:
                    if method_key in scenarios[scenario.id].get("methods", []):
                        baseline_ids.add(ge_id)

            result = check_consistency(baseline_ids, rerun_ids, scenario.id, qt)
            total_checked += 1
            if not result["match"]:
                total_diverged += 1
                diverged_scenarios.append(result)
                print(f"  ⚠️  {scenario.id}/{qt}: "
                      f"+{len(result['added'])} new, -{len(result['removed'])} missing")

    total_entries = sum(len(find_result(dense_only, s.id, qt))
                        for s in SCENARIOS for qt in ["KW", "NL"])
    total_changed = sum(len(r["added"]) + len(r["removed"]) for r in diverged_scenarios)

    print(f"\nConsistency: {total_checked - total_diverged}/{total_checked} scenarios match #27")
    if total_changed > total_entries * 0.1:
        print(f"⛔ {total_changed}/{total_entries} entries changed (>{10}%) — halt and investigate")
        return False
    if diverged_scenarios:
        print(f"⚠️  {total_diverged} scenarios diverged — re-baseline those scenarios")
    else:
        print("✅ All scenarios match #27 baseline")
    return True


def analyze_all():
    configs = ["dense-only", "dense+splade", "full-hybrid"]
    loaded = {c: load_results(c) for c in configs}
    baseline_scores = load_baseline_scores()
    hybrid_scores = load_hybrid_scores()
    vocab_data = {}
    vocab_path = RESULTS_DIR / "splade-vocab.json"
    if vocab_path.exists():
        for entry in json.loads(vocab_path.read_text()):
            vocab_data[(entry["scenario_id"], entry["query_type"])] = entry

    to_score_entries = []
    scenario_results = []

    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            baseline_entries = find_result(loaded.get("dense-only", {}), scenario.id, qt)
            results_per_config = {}
            for config in configs:
                entries = find_result(loaded.get(config, {}), scenario.id, qt)
                results_per_config[config] = entries

            deltas = {}
            for config in ["dense+splade", "full-hybrid"]:
                if results_per_config.get(config):
                    deltas[config] = compute_delta(baseline_entries, results_per_config[config])
                    for new_entry in deltas[config]["new"]:
                        if get_score(new_entry["ge_id"], scenario.id, baseline_scores, hybrid_scores) is None:
                            to_score_entries.append({
                                "ge_id": new_entry["ge_id"],
                                "scenario_id": scenario.id,
                                "query_type": qt,
                                "config": config,
                                "title": new_entry.get("title", ""),
                                "body": new_entry.get("body", ""),
                                "context": scenario.context,
                            })

            scores_per_config = {}
            for config in configs:
                entries = results_per_config.get(config, [])
                scored = []
                for e in entries:
                    ge_id = extract_ge_id(e["id"])
                    s = get_score(ge_id, scenario.id, baseline_scores, hybrid_scores)
                    scored.append(s if s is not None else -1)
                scores_per_config[config] = scored

            latencies = {}
            for config in configs:
                for r in loaded.get(config, {}).get("results", []):
                    if r["scenario_id"] == scenario.id and r["query_type"] == qt:
                        latencies[config] = r.get("latency_median_ms", 0)

            scenario_results.append({
                "scenario_id": scenario.id,
                "query_type": qt,
                "failure_modes": scenario.failure_modes,
                "baseline_verdict": scenario.baseline_verdict,
                "deltas": {k: v for k, v in deltas.items()},
                "scores": scores_per_config,
                "latencies": latencies,
                "vocab": vocab_data.get((scenario.id, qt)),
            })

    if to_score_entries:
        grouped = {}
        for entry in to_score_entries:
            key = entry["scenario_id"]
            if key not in grouped:
                grouped[key] = {"context": entry["context"], "entries": []}
            grouped[key]["entries"].append({
                "ge_id": entry["ge_id"],
                "query_type": entry["query_type"],
                "config": entry["config"],
                "title": entry["title"],
                "body": entry["body"][:500],
            })
        TO_SCORE_PATH.write_text(json.dumps(grouped, indent=2))
        print(f"  {len(to_score_entries)} new entries need scoring → {TO_SCORE_PATH}")

    return scenario_results


def generate_report(scenario_results: list[dict]) -> str:
    lines = ["# Hybrid Benchmark: Dense-Only vs SPLADE vs Full Hybrid\n"]
    lines.append(f"*Generated {__import__('datetime').date.today()}*\n")
    lines.append("## Configuration\n")
    lines.append("See spec: `docs/superpowers/specs/2026-06-28-splade-hybrid-benchmark-design.md`\n")

    configs = ["dense-only", "dense+splade", "full-hybrid"]
    lines.append("## Headline Results\n")

    for qt in ["KW", "NL"]:
        qt_results = [r for r in scenario_results if r["query_type"] == qt]
        lines.append(f"### {qt} Queries\n")
        lines.append("| Scenario | Failure Mode | " + " | ".join(
            f"{c} prec" for c in configs) + " | Delta (SPLADE) | Delta (Full) |")
        lines.append("|---|---|" + "|".join(["---"] * len(configs)) + "|---|---|")
        for r in qt_results:
            precs = []
            for c in configs:
                scored = [s for s in r["scores"].get(c, []) if s >= 0]
                p = compute_precision(scored) if scored else float("nan")
                precs.append(f"{p:.0%}" if not (p != p) else "—")
            d_splade = ""
            d_full = ""
            delta_s = r["deltas"].get("dense+splade")
            delta_f = r["deltas"].get("full-hybrid")
            if delta_s:
                d_splade = f"+{len(delta_s['new'])}/-{len(delta_s['lost'])}"
            if delta_f:
                d_full = f"+{len(delta_f['new'])}/-{len(delta_f['lost'])}"
            fm = ", ".join(r["failure_modes"])
            lines.append(f"| {r['scenario_id']} | {fm} | " + " | ".join(precs) +
                         f" | {d_splade} | {d_full} |")
        lines.append("")

    lines.append("## Latency\n")
    lines.append("| Scenario | Query | dense-only | dense+splade | full-hybrid |")
    lines.append("|---|---|---|---|---|")
    for r in scenario_results:
        lats = []
        for c in configs:
            lat = r['latencies'].get(c)
            lats.append(f"{lat:.0f}ms" if lat is not None else "—")
        lines.append(f"| {r['scenario_id']} | {r['query_type']} | " + " | ".join(lats) + " |")
    lines.append("")

    lines.append("## Per-Scenario Results\n")
    for r in scenario_results:
        lines.append(f"### {r['scenario_id']} / {r['query_type']}\n")
        lines.append(f"**Failure modes:** {', '.join(r['failure_modes'])}")
        lines.append(f"**Baseline verdict:** {r['baseline_verdict']}\n")

        for config_name in ["dense+splade", "full-hybrid"]:
            delta = r["deltas"].get(config_name)
            if not delta:
                continue
            lines.append(f"**Delta vs dense-only ({config_name}):**")
            lines.append(f"- Shared: {len(delta['shared'])}, New: {len(delta['new'])}, Lost: {len(delta['lost'])}")
            if delta["shared"]:
                rank_changes = [s["rank_change"] for s in delta["shared"]]
                lines.append(f"- Rank changes: {rank_changes}")
            lines.append("")

        vocab = r.get("vocab")
        if vocab and r["query_type"] == "KW":
            lines.append("**SPLADE vocabulary (KW):**")
            top5 = vocab.get("top_20", [])[:5]
            for t in top5:
                lines.append(f"- `{t['token']}` ({t['weight']:.3f}) [{t['source']}/{t['form']}]")
            lines.append(f"- T1 hits: {vocab.get('tier1_hits', 0)}, T2 hits: {vocab.get('tier2_hits', 0)}")
            lines.append("")
        lines.append("---\n")

    return "\n".join(lines)


def main():
    print("Running delta analysis...")
    results = analyze_all()

    unscored = sum(1 for r in results
                   for s in r["scores"].values()
                   for score in s if score == -1)
    if unscored > 0:
        print(f"\n⚠️  {unscored} entries have no score. Score new entries in hybrid_scores.json, "
              f"then re-run analyze.py.")

    report = generate_report(results)
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(report)
    print(f"Report written to {REPORT_PATH}")


if __name__ == "__main__":
    main()
