# TemporalFocus Utility Design

**Issue:** casehubio/neocortex#244
**Epic:** casehubio/neocortex#227
**Branch:** `issue-253-cognitive-rearchitecture`
**Scale:** M | **Complexity:** Med

## Summary

"What's on my mind right now?" — a pure static utility that scores and ranks `TemporalEntry` results into an attention list with salience scores and human-readable reasons. Composes with `TemporalIndex` (data) and `TemporalRanker` (scoring interface).

## Dependencies

| Dependency | Status |
|-----------|--------|
| #237 — Chronological index | DONE |
| #239 — Affect trajectory | DONE |

## Design

### TemporalFocusConfig Record

Tunable scoring parameters with sensible defaults:

```java
public record TemporalFocusConfig(
    double proximityScale,         // 7.0 — days until event half-life
    double worseningBoostCap,      // 1.0 — max trajectory boost multiplier
    double improvingDampenFactor,  // 0.5 — dampening for improving affect
    double volatilityBoostCap      // 0.5 — max volatility boost
) {
    public static TemporalFocusConfig defaults() {
        return new TemporalFocusConfig(7.0, 1.0, 0.5, 0.5);
    }
}
```

### AttentionItem Record

Scored entry with reason:

```java
public record AttentionItem(
    TemporalEntry entry, 
    double salience, 
    String reason
) implements Comparable<AttentionItem> {
    @Override
    public int compareTo(AttentionItem other) {
        return Double.compare(other.salience, this.salience); // descending
    }
}
```

### TemporalFocus Static Utility

```java
public final class TemporalFocus {
    // Full output with reasons
    public static List<AttentionItem> focus(
        List<TemporalEntry> entries, Instant now,
        Map<String, AffectTrajectory> trajectories,
        TemporalFocusConfig config) { ... }

    // Composable ranker (scoring only, no reasons)
    public static TemporalRanker ranker(
        Map<String, AffectTrajectory> trajectories,
        TemporalFocusConfig config) { ... }
}
```

### Scoring Algorithm

**Base score** by source type:

| Source | Condition | Formula | Reason |
|--------|----------|---------|--------|
| FromMindMap | validFrom > now (upcoming) | `1.0 / (1 + daysUntil / config.proximityScale())` | "approaching event" |
| FromMindMap | otherwise (recent update) | `1.0 / (1 + hoursSince)` | "recent update" |
| FromMemory | any | `1.0 / (1 + hoursSince)` | "recent experience" |
| FromCbr | any | `1.0 / (1 + hoursSince)` | "recent case" |

**Trajectory modifiers** — applied when a trajectory exists for the entry's entity ID:

| Trajectory | Modifier | Reason suffix |
|-----------|----------|---------------|
| WORSENING | `× (1 + min(config.worseningBoostCap(), abs(slope)))` | " (worsening affect)" |
| High volatility | `× (1 + min(config.volatilityBoostCap(), volatility))` | " (volatile)" |
| IMPROVING | `× config.improvingDampenFactor()` | — |
| STABLE | no modifier | — |

**Entity ID extraction** for trajectory lookup:
- FromMindMap → `node.id()`
- FromMemory → `memory.entityId()`
- FromCbr → `cbrCase.caseId()`

### Space Awareness

Already handled by TemporalIndex — `TemporalQuery` accepts `Collection<String> tenantIds`. Each memory space maps to a tenant (D12). No additional code needed.

## Module Impact

| Module | Changes |
|--------|---------|
| `cognitive-index` | + `TemporalFocusConfig` record, `AttentionItem` record, `TemporalFocus` static utility |

No new modules, no changes to existing types.

## Testing Strategy

| Test | What it verifies |
|------|-----------------|
| Upcoming MindMap event scores by proximity | Closer events score higher |
| Recent memory scores by recency | Newer memories score higher |
| Worsening trajectory boosts salience | Factor > 1.0 applied |
| Improving trajectory dampens salience | Factor < 1.0 applied |
| Reason strings match source type | "approaching event", "recent experience", etc. |
| Ranker factory produces composable TemporalRanker | `rank()` method returns sorted list |
| Config changes thresholds | Custom config produces different scores |
| Empty entries returns empty list | Edge case |

## References

- `TemporalRanker.java` — functional interface (D14)
- `TemporalEntry.java`, `TemporalSource.java` — entry types
- `TemporalIndex.java` — chronological aggregator (D13, D18)
- `AffectTrajectoryAnalyzer.java` — trajectory computation
- `cognitive-architecture-roadmap.md` §4b — TemporalFocus design
- Decisions D13, D14, D30-D32
