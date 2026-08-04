# Grove — Garden Analytics and Curation Dashboard

**Date:** 2026-08-04
**Issue:** TBD (to be filed)
**Status:** Design approved

---

## Purpose

A standalone web application for understanding garden data quality, finding low-value content, and curating entries. Provides visual analytics over the garden corpus — domain density, staleness drift, retrieval effectiveness, vector-based quality signals — plus full curation actions (edit, retire, bulk operations, version lifecycle management).

Separate from Trellis (which has a simple search + provenance viewer for developers). Grove is a curator's tool.

## Architecture

**Standalone Quarkus app** in its own repo (`hortora/grove`). Uses casehub pages/block-ui for the frontend (consistent with Trellis and the broader casehub UI family).

### Data sources

| Source | Access method | What it provides |
|--------|--------------|------------------|
| Qdrant (`hortora_garden`) | REST API (localhost:6333) | Full entry content, core metadata (title, type, domain, score, submitted), all vector types (dense, sparse, ColBERT, BM25) |
| retrieval-tracking.db | SQLite (direct file read) | Retrieval frequency per entry, query history, relevance scores |
| garden git repo | Filesystem + git commands | Mutations (edit, retire, move, commit). YAML frontmatter for fields not in Qdrant (staleness_threshold, tags, last_reviewed, author, verified_on) |
| garden.db | SQLite (direct file read) | Indexed entries, checked duplicate pairs, discarded entries |
| Engine REST API | HTTP (localhost:8080) | gardenReindex trigger, Qdrant stats when needed |

### Data flow

```
Qdrant ──→ Grove backend ──→ pages/block-ui frontend
  (vectors + content + metadata)

retrieval-tracking.db ──→ Grove backend
  (usage frequency, retrieval counts)

garden.db ──→ Grove backend
  (checked pairs, index reconciliation)

garden git repo ←── Grove backend
  (mutations: edit, retire, move, commit)
```

### Metadata gap

Qdrant stores 8 payload fields: title, score, content, sourceDocumentId, submitted, tenantId, type, domain. Missing from payload: staleness_threshold, tags, last_reviewed, author, verified_on, constraints, invalidation_triggers.

**Phase 1:** Parse YAML frontmatter from the `content` payload field at query time. Works immediately, no engine changes.

**Later:** Enrich Qdrant payload at ingestion time in the engine to include all frontmatter fields. Eliminates runtime parsing.

## Landing Page — Domain Map

Visual overview of the garden organised by domain. Each domain rendered as a card/tile showing:

- **Entry count** — total entries in that domain
- **Type breakdown** — stacked indicator: gotcha / technique / undocumented / convention
- **Staleness indicator** — percentage of entries past staleness threshold (red/amber/green)
- **Retrieval activity** — entries retrieved in last 30 days vs total entries (coverage ratio)
- **Average score** — mean score, flagging domains averaging below 9

Top-level health metrics above the domain map:
- Total entries (Qdrant count vs garden.db count — discrepancy is itself a signal)
- Stale entries overdue for review
- Entries never retrieved (zero hits in tracking window)
- Entries with no tags

### Domain detail view

Clicking a domain drills into a sortable/filterable table:
- Columns: GE-ID, title, type, score, submitted, staleness status, retrieval count, version status
- Filters: type, staleness, score range, retrieval activity, version status

## Vector-Based Quality Signals

Qdrant's vectors enable quality analysis beyond metadata:

### Near-duplicate detection

Pairwise cosine similarity within each domain. Flag pairs above 0.92 threshold. Show side-by-side for keep/merge/retire decisions. Exclude pairs already recorded in garden.db `checked_pairs` table.

### Semantic outliers

Per domain, compute centroid of all dense vectors. Rank entries by distance from centroid. Furthest entries are candidates for miscategorisation or overly niche content.

### Coverage density

Cluster entries per domain (k-means or DBSCAN on dense vectors). Domains with one tight cluster have redundant coverage. Scattered points indicate thin, diverse coverage. Visualise as cluster count + spread metric.

### Cross-domain similarity

Entries semantically closer to another domain's centroid than their own. Suggests potential miscategorisation.

### Computation model

These are compute-intensive. Run as batch jobs on demand ("analyse domain X"), not on every page load. Results cached in `grove.db` (local SQLite) and refreshed when triggered.

## Curation Actions

### Entry-level (from entry detail view)

- **Edit** — modify content, update frontmatter fields. Writes to garden git file, commits.
- **Retire/Deprecate** — adds `**Deprecated:** [reason] — [date]` to entry body. Entry preserved for history.
- **Move domain** — reassign to a different domain directory. Moves file, updates indexes, commits.
- **Confirm freshness** — sets `last_reviewed: today` in frontmatter. Clears staleness flag.

### Bulk (from filtered table views)

- **Bulk confirm** — confirm freshness on multiple entries at once.
- **Bulk retire** — retire multiple low-value entries with a shared reason.
- **Bulk re-tag** — add or remove tags across selected entries.

### System

- **Trigger reindex** — call engine gardenReindex or manual Qdrant REST fallback.
- **Refresh vector analysis** — re-run duplicate/outlier/coverage for a domain or whole garden.
- **Reconcile indexes** — compare Qdrant point count vs garden.db vs file count, report gaps, offer to fix.

All mutations commit to the garden git repo with messages like `grove: retire GE-XXXX — [reason]` or `grove: bulk confirm 12 entries`.

## Version-Aware Content Lifecycle

### Version registry

Grove maintains a registry of tracked stacks and their current versions:

```yaml
quarkus: 3.36.1
onnxruntime: 1.21.0
jdk: 26.0.2
python: 3.14
```

Curator sets and updates these manually.

### Version distance scoring

For entries with `verified_on`, compute distance from current version. Three tiers:

| Tier | Condition | Search behaviour |
|------|-----------|-----------------|
| **Current** | Verified on current or recent version | Full weight |
| **Aging** | 2+ minor versions behind | Shown with version warning badge, ranked lower |
| **Legacy** | Major version behind or past curator threshold | De-emphasised unless query explicitly targets that version |

### De-emphasis mechanism

Phase 1: Grove surfaces version-aging entries as a curation queue — "47 entries verified on Quarkus < 3.34. Review, confirm, or retire?" No engine changes needed.

Later: Add `version_status: current | aging | legacy` payload field to Qdrant points via batch update. Engine search uses this as a score modifier for search-time de-emphasis.

## Stack

- **Quarkus** — runtime (consistent with engine and trellis)
- **casehub pages/block-ui** — frontend framework
- **Qdrant Java client** — direct Qdrant access for vectors and payloads
- **SQLite (JDBC)** — read retrieval-tracking.db and garden.db; write grove.db for cached analysis
- **JGit or shell git** — garden repo mutations and commits

## Repo

`hortora/grove` — standalone repository. Own port, own lifecycle. Reads garden data from shared filesystem paths (`~/.hortora/`), calls Qdrant on localhost:6333.
