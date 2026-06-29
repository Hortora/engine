#!/usr/bin/env python3
"""Extract #27 benchmark scores from real-world-benchmark.md into baseline_scores.json."""

import json
import re
import sys
from pathlib import Path

REPORT_PATH = Path(__file__).parent.parent.parent / "docs" / "comparison" / "real-world-benchmark.md"
OUTPUT_PATH = Path(__file__).parent / "baseline_scores.json"

ISSUE_ID_MAP = {
    1: "issue-1-reactive-async",
    2: "issue-2-cdi-wiring",
    3: "issue-3-persistence-migrations",
    4: "issue-4-rest-messaging",
    5: "issue-5-ai-llm-inference",
    6: "issue-6-testing-ci",
}

DOMAIN_ID_MAP = {
    (1, 1): "spec1-d1-cdi-priority-tiers",
    (1, 2): "spec1-d2-thread-safety",
    (1, 3): "spec1-d3-extension-deactivation",
    (1, 4): "spec1-d4-protocol-compliance",
    (2, 1): "spec2-d1-cdi-tier-coexistence",
    (2, 2): "spec2-d2-chatmodel-adaptation",
    (2, 3): "spec2-d3-circular-deps",
    (2, 4): "spec2-d4-exception-mapper",
}


def parse_results_table(table_text: str) -> list[tuple[str, int]]:
    results = []
    for line in table_text.strip().split("\n"):
        line = line.strip()
        if not line.startswith("|") or line.startswith("|---") or line.startswith("| #"):
            continue
        cols = [c.strip() for c in line.split("|")[1:-1]]
        if len(cols) < 3:
            continue
        ge_id = cols[1].strip()
        if not re.match(r"GE-\d{8}-[0-9a-f]{6}", ge_id):
            continue
        score_str = cols[-1].strip()
        try:
            score = int(score_str)
        except ValueError:
            continue
        results.append((ge_id, score))
    return results


def extract_scenario_id(heading: str, spec_num: int = 0) -> str:
    issue_match = re.match(r"###\s+Issue\s+(\d+):", heading)
    if issue_match:
        num = int(issue_match.group(1))
        return ISSUE_ID_MAP.get(num, "")
    domain_match = re.match(r"####\s+Domain\s+(\d+):\s+(.+)", heading)
    if domain_match:
        d_num = int(domain_match.group(1))
        # Use the hardcoded map for spec domains
        if spec_num > 0:
            return DOMAIN_ID_MAP.get((spec_num, d_num), "")
    return ""


def extract_all(report_text: str) -> dict:
    scores: dict[str, dict[str, dict]] = {}
    lines = report_text.split("\n")
    current_scenario = ""
    current_method = ""
    current_spec = 0
    table_lines: list[str] = []
    in_table = False

    def flush_table():
        nonlocal table_lines, in_table
        if not table_lines or not current_scenario or not current_method:
            table_lines = []
            in_table = False
            return
        results = parse_results_table("\n".join(table_lines))
        for ge_id, score in results:
            if ge_id not in scores:
                scores[ge_id] = {}
            if current_scenario not in scores[ge_id]:
                scores[ge_id][current_scenario] = {"benchmark_score": score, "methods": []}
            entry = scores[ge_id][current_scenario]
            if current_method not in entry["methods"]:
                entry["methods"].append(current_method)
        table_lines = []
        in_table = False

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("### Issue"):
            flush_table()
            current_scenario = extract_scenario_id(stripped)
            current_method = ""
        elif stripped.startswith("### Spec"):
            flush_table()
            spec_match = re.match(r"### Spec (\d+):", stripped)
            if spec_match:
                current_spec = int(spec_match.group(1))
        elif stripped.startswith("#### Domain"):
            flush_table()
            current_scenario = extract_scenario_id(stripped, current_spec)
            current_method = ""
        elif "grep (keywords)" in stripped:
            flush_table()
            current_method = "grep"
        elif "gardenSearch (keywords)" in stripped or "gardenSearch (KW)" in stripped:
            flush_table()
            current_method = "gardenSearch-KW"
        elif "gardenSearch (natural language)" in stripped or "gardenSearch (NL)" in stripped:
            flush_table()
            current_method = "gardenSearch-NL"
        elif stripped.startswith("| #") or stripped.startswith("|---"):
            in_table = True
            table_lines.append(stripped)
        elif in_table and stripped.startswith("| ") and re.match(r"\|\s*\d+\s*\|", stripped):
            table_lines.append(stripped)
        elif in_table and not stripped.startswith("|"):
            flush_table()

    flush_table()
    return scores


def main():
    report_path = Path(sys.argv[1]) if len(sys.argv) > 1 else REPORT_PATH
    report_text = report_path.read_text()
    scores = extract_all(report_text)

    total_entries = sum(len(scenarios) for scenarios in scores.values())
    output_path = Path(sys.argv[2]) if len(sys.argv) > 2 else OUTPUT_PATH
    output_path.write_text(json.dumps(scores, indent=2, sort_keys=True))
    print(f"Extracted {len(scores)} unique GE-IDs across {total_entries} (entry, scenario) pairs")
    print(f"Written to {output_path}")


if __name__ == "__main__":
    main()
