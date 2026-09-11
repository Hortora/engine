# Trajectory-Aware Curiosity Design

**Issue:** casehubio/neocortex#242
**Epic:** casehubio/neocortex#226
**Branch:** `issue-253-cognitive-rearchitecture`
**Scale:** S | **Complexity:** Low

## Summary

Update `CuriositySignalGenerator.applyAffectDampening()` to use affect trajectory slope instead of PAD snapshot. Escalating negative affect boosts curiosity (festering). Stable negative dampens (known). Improving dampens lightly (coping working). High arousal volatility boosts (unresolved ambivalence). PROXIMITY signals participate in trajectory modulation.

## Dependencies

| Dependency | Status |
|-----------|--------|
| #239 — Affect trajectory log | DONE |

## Design

### New Dependencies

Add `memory-api` and `cognitive-index` to `mindmap-intelligence/pom.xml`. `CuriositySignalGenerator` injects `Instance<CaseMemoryStore>` for graceful degradation (same pattern as `AffectTrajectoryDecorator`). (D27)

### CuriosityConfig Record

New record in `mindmap-intelligence` absorbing existing hardcoded constants and adding trajectory thresholds:

```java
public record CuriosityConfig(
    double proximityScale,         // 7.0
    long staleDaysThreshold,       // 90
    int maxBfsDepth,               // 4
    int topCentrality,             // 3
    double volatilityThreshold,    // 0.3
    double maxBoostFactor,         // 1.0
    double minDampenFactor,        // 0.1
    double improvingDampenCap,     // 0.7
    double volatilityBoostCap,     // 0.5
    int trajectoryLimit            // 20
) {
    public static CuriosityConfig defaults() {
        return new CuriosityConfig(7.0, 90, 4, 3, 0.3, 1.0, 0.1, 0.7, 0.5, 20);
    }
}
```

Injected via `Instance<CuriosityConfig>` — `defaults()` when not resolvable. (D29)

### Updated applyAffectDampening

For each signal with a target node:
1. Query `MemoryQuery.forEntity(nodeId, AffectEvents.DOMAIN, tenantId)` with `limit=trajectoryLimit`
2. If < 2 entries or no `CaseMemoryStore`: fall back to current snapshot logic
3. Compute `AffectTrajectory` via `AffectTrajectoryAnalyzer.analyze(memories)`
4. Determine factor based on trend:

| Trend | Factor | Rationale |
|-------|--------|-----------|
| WORSENING | `1.0 + min(maxBoostFactor, abs(pleasureSlope))` | Festering — probe it |
| STABLE + negative pleasure | `max(minDampenFactor, 1.0 + currentPleasure)` | Known negative — current behavior |
| STABLE + non-negative | `1.0` | No modification |
| IMPROVING | `max(minDampenFactor, 1.0 - min(improvingDampenCap, abs(pleasureSlope)))` | Coping working — light monitoring |

5. Volatility modifier: if `arousalVolatility > volatilityThreshold`, multiply factor by `1.0 + min(volatilityBoostCap, arousalVolatility)`

6. Cache trajectories per node ID within a single `computeSignals` call (avoid re-querying)

### PROXIMITY Participation

Remove `if (signal.category() == SignalCategory.PROXIMITY) continue`. PROXIMITY signals get trajectory modulation — approaching + worsening = highest priority. (D28)

## Module Impact

| Module | Changes |
|--------|---------|
| `mindmap-intelligence` | + `CuriosityConfig` record, modified `CuriositySignalGenerator` constructor + `applyAffectDampening()`, new pom deps |
| `mindmap-intelligence/pom.xml` | + `memory-api`, `cognitive-index` dependencies |

## Testing Strategy

| Test | What it verifies |
|------|-----------------|
| Worsening trajectory boosts score | Factor > 1.0 for WORSENING trend |
| Stable negative dampens as before | Factor < 1.0 matches current snapshot behavior |
| Improving trajectory dampens | Factor < 1.0 for IMPROVING trend |
| Volatility modifier boosts | High arousalVolatility multiplies factor up |
| PROXIMITY participates | PROXIMITY signals get trajectory modulation |
| No memory store fallback | Graceful degradation to snapshot logic |
| Config injection | Custom CuriosityConfig changes thresholds |

## References

- `CuriositySignalGenerator.java:182-197` — current applyAffectDampening
- `AffectTrajectoryAnalyzer.java` — trajectory computation
- `AffectEvents.java` — affect domain constant
- `AffectTrajectoryDecorator.java` — Instance<CaseMemoryStore> pattern
- `cognitive-architecture-roadmap.md` §3e — trajectory-aware curiosity design
- Decisions D27-D29
