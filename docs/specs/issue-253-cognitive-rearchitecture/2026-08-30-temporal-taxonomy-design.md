# Temporal Taxonomy — TemporalMark Sealed Hierarchy

**Issue:** casehubio/neocortex#234
**Date:** 2026-08-30
**Status:** Design

## Problem

The system has three kinds of time, currently handled ad-hoc:

| Kind | Example | Current representation | Gap |
|---|---|---|---|
| **Wall-clock** | "meeting Dec 25 at 3pm" | `Instant` (`validFrom`, `createdAt`) | Works, but not queryable on MindMap |
| **Relative** | "3 days from now", "last week" | Not represented | Must be resolved to wall-clock at capture time |
| **Ordinal** | "turn 42", "after the third message" | `String turnId` on events | No mapping to wall-clock; not sortable across conversations |

LLM-extracted temporal references can be any of these three kinds. There is no common type to represent "a point in time however it was originally expressed."

## Design

### TemporalMark — sealed interface in cognitive-api

```java
package io.casehub.neocortex.cognitive;

import java.time.Duration;
import java.time.Instant;

public sealed interface TemporalMark {

    Instant resolveToInstant(Instant now);

    record WallClock(Instant instant) implements TemporalMark {
        public WallClock {
            Objects.requireNonNull(instant, "instant required");
        }

        @Override
        public Instant resolveToInstant(Instant now) {
            return instant;
        }
    }

    record Relative(Duration offset, Instant anchor) implements TemporalMark {
        public Relative {
            Objects.requireNonNull(offset, "offset required");
            // anchor is nullable — null means "relative to now"
        }

        @Override
        public Instant resolveToInstant(Instant now) {
            Instant base = anchor != null ? anchor : now;
            return base.plus(offset);
        }
    }

    record Ordinal(String turnId, Instant resolved) implements TemporalMark {
        public Ordinal {
            Objects.requireNonNull(turnId, "turnId required");
            Objects.requireNonNull(resolved, "resolved required");
        }

        @Override
        public Instant resolveToInstant(Instant now) {
            return resolved;
        }
    }
}
```

### Module placement

`cognitive-api` (D8) — the zero-deps cross-cutting cognitive types module alongside `Confidence` and `ConfidenceOrigin`. TemporalMark is a cognitive concept used by MindMap (temporal bounds), Memory (event timestamps), and CBR (temporal features).

### Resolution semantics

- **WallClock**: Returns its `instant` directly. Ignores `now`.
- **Relative**: Returns `anchor + offset` when anchor is non-null, `now + offset` when anchor is null (D10). Floating relative marks (null anchor) produce different values on each call — consumers that persist the resolved value should call once and store the result.
- **Ordinal**: Returns the pre-resolved `Instant` (D9). Resolution happens at construction time by the caller. The `turnId` preserves the original reference for display; the `resolved` timestamp enables sorting and proximity computation.

### Factory methods

Static factories for readability:

```java
static TemporalMark wallClock(Instant instant) {
    return new WallClock(instant);
}

static TemporalMark relativeToNow(Duration offset) {
    return new Relative(offset, null);
}

static TemporalMark relativeToAnchor(Duration offset, Instant anchor) {
    return new Relative(offset, anchor);
}

static TemporalMark ordinal(String turnId, Instant resolved) {
    return new Ordinal(turnId, resolved);
}
```

### What this does NOT include

- **Adoption on existing types** — existing `Instant validFrom`/`validUntil` on MindMap nodes and `String turnId` on events stay unchanged. Adoption is scoped to #235 (event timestamps) and #236 (temporal MindMapQuery) (D11).
- **Temporal index** — the cross-store `TemporalIndex` is #237 (chronological index).
- **MindMapExtractor integration** — LLM temporal parsing integration is a follow-up.

## Testing

- `WallClock.resolveToInstant()` returns the wrapped instant regardless of `now`
- `Relative.resolveToInstant()` with null anchor returns `now + offset`
- `Relative.resolveToInstant()` with non-null anchor returns `anchor + offset`
- `Relative` with negative offset (past reference) works correctly
- `Ordinal.resolveToInstant()` returns the pre-resolved instant regardless of `now`
- Validation: null instant on WallClock, null offset on Relative, null turnId/resolved on Ordinal all throw
- `equals`/`hashCode` via record semantics
- Pattern matching exhaustiveness via sealed interface

## References

- cognitive-architecture-roadmap.md §2a — roadmap design sketch
- cognitive-api/src/main/java/io/casehub/neocortex/cognitive/ — module location
- mindmap-api MindMapNode.java:26, MindMapEdge.java:29 — `validFrom` (wall-clock usage)
- memory-api ExperienceEvent.java:9 — `turnId` (ordinal usage)
- D8, D9, D10, D11 — design decisions
