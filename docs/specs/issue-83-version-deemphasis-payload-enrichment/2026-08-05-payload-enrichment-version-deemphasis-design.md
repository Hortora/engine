# Payload Enrichment and Version-Aware Search Scoring

**Date:** 2026-08-05
**Issues:** #80 (payload enrichment), #83 (version de-emphasis)
**Status:** Design approved

---

## Purpose

Two complementary changes to the garden retrieval pipeline:

1. **Payload enrichment** — promote frontmatter fields from garden entries into top-level Qdrant payload fields at ingestion time, eliminating runtime YAML parsing by downstream consumers (Grove).

2. **Version-aware search scoring** — apply two layers of temporal and version relevance scoring so that stale or version-mismatched entries rank lower than current, relevant ones.

## Payload Enrichment (#80)

### Fields to promote

Extend `GardenMetadataExtractor` to extract these additional frontmatter fields at ingestion time:

| Field | Source | Qdrant payload type | Purpose |
|-------|--------|-------------------|---------|
| `staleness_threshold` | frontmatter | keyword | Per-entry decay rate string (e.g. "90d", "never") |
| `staleness_days` | computed from staleness_threshold | integer | Numeric form for decay tier classification |
| `decay_tier` | computed from staleness_days | integer (0-3) | Decay tier for Qdrant query branching |
| `verified_on` | frontmatter | keyword | Version anchor (format: `stack:version`, e.g. "quarkus:3.20") |
| `author` | frontmatter | keyword | Attribution, filtering |
| `last_reviewed` | frontmatter | keyword | Freshness signal |

Already extracted: `title`, `domain`, `type`, `score`, `submitted`, `tags`, `see_also`, `see_also_ids`.

### staleness_days and decay_tier conversion

| staleness_threshold | staleness_days | decay_tier | Label |
|-------------------|----------------|-----------|-------|
| `30d` | 30 | 0 | fast |
| `90d` | 90 | 1 | standard |
| `365d` | 365 | 2 | slow |
| `never` | 0 | 3 | evergreen |
| absent | 90 | 1 | standard (default) |

`decay_tier` is the primary field used at query time (see Layer 1). `staleness_days` is retained for informational/filtering purposes but is not used in the decay formula directly.

### `verified_on` format

Colon-separated `stack:version` — e.g., `quarkus:3.20`, `onnx-runtime:1.26.0`, `jdk:26.0.2`. The colon is the delimiter; stack names may contain hyphens. Parsing rule: split on the first colon. If no colon present, treat the entire value as a stack name with no version (no penalty applied).

### Typed payload fields — engine-local conversion

The neocortex-rag `ExtractionResult` SPI only supports string metadata. Rather than extending the SPI (cross-repo change), the engine converts string metadata to typed Qdrant payload fields locally.

`GardenMetadataExtractor` extracts all fields as strings via the existing SPI. A new engine-local `TypedPayloadEnricher` runs after extraction, before the Qdrant upsert, and converts:
- `staleness_days` string → integer payload field
- `decay_tier` string → integer payload field
- `submitted` string → `submitted_at` datetime payload field (ISO-8601)

This keeps the neocortex-rag SPI unchanged. The typed fields are added directly to the Qdrant point payload alongside the string metadata.

### Backfill

Existing entries are backfilled on the next reindex — no migration needed. The fields appear in the payload when entries are re-ingested.

**Backfill window:** During reindex, entries are processed incrementally. Entries not yet re-ingested will lack the new payload fields. Scoring layers handle this gracefully: Layer 1 treats missing `decay_tier` as tier 1 (standard). Layer 2 treats missing `verified_on` as no penalty.

Qdrant payload indexes should be created for `decay_tier` (integer), `submitted_at` (datetime), `verified_on` (keyword), and `last_reviewed` (keyword) for efficient filtering and formula queries.

## Version-Aware Search Scoring (#83)

Two independent layers, each handling a different question:

### Layer 1 — Corpus-Level Temporal Decay (Qdrant Tier-Based Decay)

**Question:** "Is this entry still true?"

An entry-intrinsic signal based on how old the entry is and how fast its content type ages. Applied during Qdrant retrieval, before cross-encoder reranking.

**Mechanism — tier-based prefetch branching:** Rather than using Qdrant formula queries with per-entry payload parameters (unverified feasibility), use the existing prefetch + filter architecture. Run separate prefetch legs per decay tier, each with a tier-appropriate `exp_decay` scale:

```
prefetch_tier_0: filter(decay_tier=0) + exp_decay(submitted_at, scale=30d)
prefetch_tier_1: filter(decay_tier=1) + exp_decay(submitted_at, scale=90d)
prefetch_tier_2: filter(decay_tier=2) + exp_decay(submitted_at, scale=365d)
prefetch_tier_3: filter(decay_tier=3) — no decay (evergreen)
→ fuse results with RRF
```

Each tier's prefetch filters to entries in that tier and applies the appropriate decay function. The results are fused via RRF, producing a single ranked list where older entries in fast-decay tiers are penalised more heavily than entries in slow-decay tiers.

**Implementation location:** This modifies `HybridCaseRetriever` in casehub-neocortex-rag. The change is scoped via the existing `@LookupIfProperty` conditional — only active when temporal decay is enabled. Other consumers of neocortex-rag are unaffected unless they opt in via config.

**Decay tiers:**

| decay_tier | staleness_threshold | Content type | Decay scale | Example |
|-----------|-------------------|--------------|------------|---------|
| 0 | `30d` | Version-specific gotchas, API changes | 30 days | "Quarkus 3.20 CDI bug workaround" |
| 1 | `90d` (default) | Standard gotchas, techniques | 90 days | "ONNX Runtime thread pool crash" |
| 2 | `365d` | Patterns, architecture decisions | 365 days | "Event sourcing with Qdrant" |
| 3 | `never` | Evergreen conventions, fundamentals | No decay | "Java sealed interface patterns" |

**Pipeline position:** Layer 1 operates inside Qdrant, modifying retrieval scores. These modified scores feed into the cross-encoder reranker. Layer 2 (version scoring) operates after the cross-encoder, on cross-encoder scores. The two layers operate on different score spaces and do not compose as simple multiplication (see Interaction Between Layers).

### Layer 2 — BOM-Relative Version Scoring (Engine-Side)

**Question:** "Is this relevant to MY stack?"

A user-relative signal based on how far the entry's verified software version is from the caller's current stack. Applied post-retrieval in `SearchResource`, after cross-encoder reranking.

**Version distance calculation:**

1. Parse `verified_on` payload field — split on first colon (e.g. `quarkus:3.20` → stack=`quarkus`, version=`3.20`)
2. Look up stack name in the caller's BOM
3. Compute version distance (minor version gap, or major version flag)
4. Apply topic-weighted multiplier

**Scoring formula:**

```
multiplier = max(0.5, 1.0 - distance * decay_factor * topic_weight)
final_score = ce_score * multiplier
```

| Parameter | Default | Config key |
|-----------|---------|-----------|
| `decay_factor` | 0.03 | `hortora.search.version-decay-factor` |
| `floor` | 0.5 | `hortora.search.version-decay-floor` |

**Topic weighting mechanism:** Tokenize the query text on whitespace and hyphens. For each result's `verified_on` stack name, check if any query token is a case-insensitive substring of the stack name (e.g., query "quarkus CDI" matches stack "quarkus"; query "ONNX thread pool" matches stack "onnx-runtime"). If match → `topic_weight = 1.0` (full decay). No match → `topic_weight = 0.3` (reduced — the version mismatch is less relevant when the query isn't about that stack). The default topic weight (0.3) is configurable.

**Example scoring (topic_weight=1.0, decay_factor=0.03):**

| Distance | Multiplier | Effect |
|----------|-----------|--------|
| 0 (current) | 1.0 | No change |
| 5 minor | 0.85 | Mild de-emphasis |
| 10 minor | 0.70 | Noticeable |
| 16 minor | 0.52 | Strong de-emphasis |
| 1 major | 0.50 (floor) | Maximum penalty |

**Rules:**
- No `verified_on` on entry → no penalty (treat as current)
- No BOM provided by caller → Layer 2 skipped entirely
- Stack name in `verified_on` not in BOM → no penalty
- Major version change → floor penalty immediately

## Search Profiles

### Purpose

BOMs can be large (hundreds of dependencies). Rather than sending the full BOM with every search, the client sends it once and the engine caches it as a named "search profile". Subsequent searches reference the profile by name.

### API

Profile management is REST-only. Search references profiles by name via MCP.

**REST endpoints:**
- `PUT /api/garden/profiles/{name}` — create or update a profile
  ```json
  {"stack": "quarkus:3.36.1|jdk:26.0.2|onnx-runtime:1.26.0"}
  ```
- `GET /api/garden/profiles/{name}` — retrieve a profile
- `GET /api/garden/profiles` — list all profiles
- `DELETE /api/garden/profiles/{name}` — delete a profile

**gardenSearch changes:**
- New optional `profile` param — references a stored profile by name
- New optional `stack` param — inline BOM override (pipe-separated `name:version` pairs, takes precedence over profile)
- When both absent → Layer 2 scoring skipped

No `gardenSetProfile` MCP tool — the client hook calls the REST endpoint directly to set up the profile, then `gardenSearch` references it by name. This keeps the MCP tool surface focused on search.

**Profile storage:** SQLite database at `~/.hortora/stats/profiles.db`. Simple schema:
```sql
CREATE TABLE search_profiles (
    name TEXT PRIMARY KEY,
    stack TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

### BOM-to-query relevance

The engine does not use the entire BOM for every query. For each search result, it checks only the `verified_on` stack name against the BOM. Only matching entries produce a version distance score. The BOM is a lookup table, not a scoring vector.

Topic weighting further focuses the scoring: BOM entries whose stack name appears in the query text get full scoring weight; others get reduced weight.

## Client-Side BOM Resolution

### File format

Both files use identical YAML — flat key-value, stack name to version:

```yaml
# ~/.hortora/profile.yaml (user default)
jdk: 26.0.2
python: 3.14

# <project>/.hortora/bom.yaml (project-specific)
quarkus: 3.36.1
onnxruntime: 1.26.0
jdk: 26.0.2
```

### Precedence (high → low)

1. `stack` param on `gardenSearch` (explicit one-off override)
2. `profile` param on `gardenSearch` (references stored profile)
3. Project BOM at `<cwd>/.hortora/bom.yaml`
4. User default at `~/.hortora/profile.yaml`

### Resolution flow

A PreToolUse hook on `mcp__hortora__gardenSearch`:

1. Read `~/.hortora/profile.yaml` as base (if exists)
2. Overlay `<cwd>/.hortora/bom.yaml` (project wins per-key, if exists)
3. Merge into pipe-separated format
4. Derive profile name from project directory (e.g., `basename $(git rev-parse --show-toplevel)` → `engine`)
5. `PUT /api/garden/profiles/{name}` with the merged BOM via `curl` (REST, not MCP)
6. Inject `profile={name}` into the `gardenSearch` call parameters

On subsequent calls in the same session, the profile is already cached server-side — the hook only needs to inject the `profile` param (skip steps 1-5 via a session-local flag file).

**Failure handling:** If the engine is unreachable (curl fails), the hook proceeds without injecting `profile`. gardenSearch works normally — Layer 2 scoring is simply skipped. No error surfaced to the user; BOM scoring is best-effort.

### Resolver script

`~/.hortora/resolve-bom.py` — reads both files, merges, outputs the pipe-separated string. Called by the PreToolUse hook.

## Interaction Between Layers

The two layers operate at different points in the retrieval pipeline and on different score spaces:

```
Qdrant prefetch (dense + sparse + BM25)
  → Layer 1: temporal decay modifies prefetch scores (inside Qdrant)
  → ColBERT MAX_SIM rescore (inside Qdrant)
  → Cross-encoder reranking (engine-side, produces CE scores)
  → Layer 2: version scoring modifies CE scores (engine-side)
  → Adaptive filtering (engine-side)
```

Layer 1 affects which entries Qdrant returns and in what order — it influences the candidate pool that reaches the cross-encoder. Layer 2 re-orders the cross-encoder's output based on version relevance. They are not multiplicative on the same score; they operate sequentially on different score spaces.

**Combined effect examples:**
- Evergreen entry, current stack: full retrieval rank + no version penalty = top result
- Old fast-decay entry, outdated stack: suppressed in retrieval + version penalty = strongly de-emphasised
- Fresh entry, outdated stack: normal retrieval rank + version penalty = mid-rank
- Old fast-decay entry, current stack: suppressed in retrieval + no version penalty = lower rank but not penalised twice

**Adaptive filtering impact:** Layer 2's version multiplier changes the CE score distribution. The existing adaptive filter (score floor + gap detection in `SearchResource`) was tuned for unmodified CE scores. When version scoring is active, adaptive filtering thresholds may need recalibration. Initial approach: disable gap detection when version scoring is active (`hortora.search.adaptive-gap.enabled=false` when `version-scoring.enabled=true`), keeping only the score floor. Revisit after benchmarking.

## Configuration

All scoring parameters are configurable via `application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `hortora.search.temporal-decay.enabled` | `true` | Enable/disable Layer 1 |
| `hortora.search.temporal-decay.default-staleness-days` | `90` | Default when staleness_threshold absent |
| `hortora.search.version-scoring.enabled` | `true` | Enable/disable Layer 2 |
| `hortora.search.version-decay-factor` | `0.03` | Per-minor-version decay rate |
| `hortora.search.version-decay-floor` | `0.5` | Minimum multiplier |
| `hortora.search.version-topic-weight-default` | `0.3` | Decay weight for non-topic stacks |

## What Changes Where

| Component | Change |
|-----------|--------|
| `GardenMetadataExtractor` (engine) | Extract additional frontmatter fields; compute `staleness_days` |
| `HybridCaseRetriever` (casehub-neocortex-rag) | Wrap RRF prefetch in formula query with temporal decay |
| `SearchResource` (engine) | Add BOM-relative version scoring post-reranking |
| `GardenMcpTools` (engine) | Add `profile` and `stack` params to `gardenSearch` |
| New: `ProfileResource` (engine) | REST endpoints for profile CRUD (`/api/garden/profiles`) |
| New: `TypedPayloadEnricher` (engine) | Converts string metadata to typed Qdrant payload fields (integer, datetime) |
| New: `SearchProfileStore` (engine) | SQLite-backed profile CRUD |
| New: `VersionScorer` (engine) | Version distance calculation + topic weighting |
| New: `resolve-bom.py` (client) | Merges project + user BOM files |
| New: PreToolUse hook (client) | Wires BOM resolution into gardenSearch calls |
