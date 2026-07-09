import json
import sys


def primary_score(entry):
    ce = entry.get("crossEncoderScore")
    return ce if ce is not None else entry.get("relevance", 0)


def adaptive_filter(entries, limit, floor, gap_threshold, min_results):
    survivors = [e for e in entries if primary_score(e) >= floor]
    floor_filtered = len(entries) - len(survivors)
    if not survivors:
        return [], floor_filtered, False, True

    ce_mode = any(e.get("crossEncoderScore") is not None for e in survivors)
    cutoff = -1

    if ce_mode:
        for i in range(len(survivors) - 1):
            cur = survivors[i].get("crossEncoderScore")
            nxt = survivors[i + 1].get("crossEncoderScore")
            if cur is not None and nxt is not None:
                if cur - nxt >= gap_threshold:
                    cutoff = max(i + 1, min_results)
                    break
            elif cur is not None and nxt is None:
                cutoff = max(i + 1, min_results)
                break

    if cutoff < 0:
        effective = min(len(survivors), limit)
    else:
        effective = min(cutoff, len(survivors))

    trimmed = effective < limit and (floor_filtered > 0 or cutoff >= 0)
    extended = effective > limit
    return survivors[:effective], floor_filtered, extended, trimmed


def main():
    with open("scripts/benchmark/results/crossencoder-pool50-scored.json") as f:
        data = json.load(f)

    floor, gap_th, min_r, limit = 0.0, 2.0, 3, 16
    print("Config: scoreFloor=%.1f, gapThreshold=%.1f, minResults=%d, limit=%d" % (floor, gap_th, min_r, limit))
    print("%-45s %6s %5s %5s %5s %4s %9s" % ("Scenario", "Before", "After", "Floor", "Trim", "Ext", "Lost>3.0"))
    print("-" * 90)

    total_before, total_after, total_lost = 0, 0, 0
    for scenario in data["results"]:
        sid = scenario["scenario_id"]
        entries = scenario["entries"]
        filtered, ff, ext, trim = adaptive_filter(entries, limit, floor, gap_th, min_r)
        relevant_before = sum(1 for e in entries if e.get("crossEncoderScore", 0) > 3.0)
        relevant_after = sum(1 for e in filtered if e.get("crossEncoderScore", 0) > 3.0)
        lost = relevant_before - relevant_after
        total_before += len(entries)
        total_after += len(filtered)
        total_lost += lost
        print("%-45s %6d %5d %5d %5s %4s %9d" % (
            sid, len(entries), len(filtered), ff,
            "Y" if trim else "N", "Y" if ext else "N", lost))

    print("-" * 90)
    noise_removed = total_before - total_after
    print("Total: %d -> %d (%d noise entries removed, %d relevant entries lost)" % (
        total_before, total_after, noise_removed, total_lost))
    if total_lost > 0:
        print("WARNING: %d clearly relevant entries (CE > 3.0) were lost by filtering" % total_lost)
        sys.exit(1)
    else:
        print("OK: No clearly relevant entries lost")


if __name__ == "__main__":
    main()
