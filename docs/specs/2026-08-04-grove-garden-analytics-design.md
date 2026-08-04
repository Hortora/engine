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

---

## Hortora Distribution and Installer

### Overview

A single installer script sets up the complete Hortora stack for a developer. One command after cloning the garden repo — everything else is automated.

```bash
git clone https://github.com/Hortora/garden.git ~/.hortora/garden
~/.hortora/garden/scripts/hortora-setup.sh
```

### Prerequisites

- git
- Java 25+

No Docker, Podman, or Python required.

### What gets installed

Everything lives under `~/.hortora/`:

| Path | Repo | What it is |
|------|------|------------|
| `~/.hortora/garden` | `Hortora/garden` | Knowledge corpus (git clone, source of truth) |
| `~/.hortora/engine` | `Hortora/engine` | RAG retrieval service (Quarkus app, port 8080) |
| `~/.hortora/grove` | `Hortora/grove` | Analytics + curation dashboard (Quarkus app, port 8090) |
| `~/.hortora/soredium` | `Hortora/soredium` | Skills (synced to `~/.claude/skills/`) |
| `~/.hortora/qdrant/` | — | Qdrant native binary + storage (port 6333) |
| `~/.hortora/models/` | — | ONNX model files (BGE-M3 + reranker, ~90MB) |
| `~/.hortora/stats/` | — | SQLite databases (created by engine at runtime) |
| `~/.hortora/cursors/` | — | Ingestion cursor state (created by engine at runtime) |
| `~/.hortora/logs/` | — | Service log files |

### Installer steps (`hortora-setup.sh`)

```
1.  Detect OS and architecture
    ├── macOS: aarch64-apple-darwin
    └── Linux: x86_64-unknown-linux-musl / aarch64-unknown-linux-musl

2.  Check prerequisites
    └── git, java (25+)

3.  Install Qdrant (native binary)
    ├── Download pre-built binary from qdrant/qdrant GitHub releases
    ├── Extract to ~/.hortora/qdrant/
    ├── Write config (storage path: ~/.hortora/qdrant/storage/, port 6333)
    └── Install service: io.hortora.qdrant (launchd / systemd)

4.  Clone repos (garden already cloned by the user)
    ├── git clone Hortora/engine    → ~/.hortora/engine
    ├── git clone Hortora/grove     → ~/.hortora/grove
    └── git clone Hortora/soredium  → ~/.hortora/soredium

5.  Download ONNX models
    ├── Download BGE-M3 + reranker from Hortora/engine GitHub Release assets
    ├── Extract to ~/.hortora/models/bge-m3/ and ~/.hortora/models/reranker/
    └── Verify checksums (scripts/download-models.sh)

6.  Build apps
    ├── ~/.hortora/engine: ./mvnw package -DskipTests -q
    └── ~/.hortora/grove:  ./mvnw package -DskipTests -q

7.  Install services
    ├── io.hortora.qdrant  — port 6333, KeepAlive/Restart=always
    ├── io.hortora.engine  — port 8080, KeepAlive/Restart=always
    ├── io.hortora.grove   — port 8090, KeepAlive/Restart=always
    └── io.hortora.update  — daily timer (see Auto-update below)

8.  Install skills
    └── python3 ~/.hortora/soredium/scripts/claude-skill sync-local --all -y

9.  Configure Claude Code
    └── Add hortora MCP server to ~/.claude/settings.json mcpServers

10. Set up contributor submission
    ├── Create submissions/<username> branch
    └── Install post-commit hook for auto-push (see Contributor Pipeline)

11. Verify
    ├── Qdrant responding on :6333
    ├── Engine responding on :8080
    ├── Grove responding on :8090
    ├── gardenSearch MCP available
    └── First reconcile triggering (entries being indexed — ~60 min for full corpus)
```

### Services

| Service | Port | Lifecycle | Restart |
|---------|------|-----------|---------|
| `io.hortora.qdrant` | 6333 | Persistent | KeepAlive / Restart=always |
| `io.hortora.engine` | 8080 | Persistent | KeepAlive / Restart=always |
| `io.hortora.grove` | 8090 | Persistent | KeepAlive / Restart=always |
| `io.hortora.update` | — | Daily timer (3am) | Calendar interval |

All survive reboots. No manual intervention after install.

### Auto-update (`io.hortora.update`)

Daily job that keeps all repos current and rebuilds when source changes:

```
1. git pull --ff-only in all 4 repos (garden, engine, grove, soredium)
2. If engine source changed → ./mvnw package -DskipTests -q → restart engine service
3. If grove source changed  → ./mvnw package -DskipTests -q → restart grove service
4. If soredium changed      → python3 scripts/claude-skill sync-local --all -y
5. Engine's ReconcileScheduler (every 6h) detects new garden entries
   and indexes them automatically via cursor-based change detection
```

New garden entries merged to main by the curator are pulled overnight, indexed at next reconcile, and available in search — no manual action.

### Garden distribution model

The garden is distributed as a **git clone**. No pre-indexed binary — each machine indexes locally on first startup.

**Why local indexing:**
- Qdrant indexes aren't portable across architectures (ARM vs x86)
- ONNX model output varies by platform (floating-point ordering in SIMD)
- One-time cost (~60 min for 5K entries); after that, daily `git pull` brings a handful of new entries that index in seconds via cursor-based change detection

**ONNX models** (~90MB total for BGE-M3 + reranker) are hosted as GitHub Release assets on `Hortora/engine`. The installer downloads and verifies checksums. No Python, torch, or HuggingFace export step needed by the developer.

---

## Contributor Pipeline

### Branch model

```
main                    ← curator-approved content (what installs pull from)
  └── staging           ← CI-validated submissions waiting for curator review
        ├── PR from submissions/alice
        ├── PR from submissions/bob
        └── PR from submissions/carol
```

### Submission flow

1. Contributor captures an entry (forage CAPTURE in their local garden clone)
2. Post-commit hook pushes to `submissions/<username>` on the garden remote
3. First push opens a PR: `submissions/<username>` → `staging`
4. Subsequent pushes add commits to the same open PR — it accumulates
5. CI runs on each push (GitHub Actions, free tier):
   - `validate_pr.py` on every new/changed `GE-*.md` file
   - Dedup check against `main` branch (title similarity + tag overlap via garden.db, not Qdrant)
   - Auto-label: `validated` if all pass, `needs-fix` if any fail
   - Comment on failures with fix instructions
6. Auto-merge to staging when CI passes and PR has `validated` label
7. Curator promotes staging → main periodically (weekly, or at 20+ new entries)

### Auto-flush threshold

When a PR accumulates 25+ validated entries, CI adds `ready-for-review` label and posts a summary:
```
📦 25 entries ready (12 gotchas, 8 techniques, 5 undocumented)
Domains: jvm (14), tools (6), web (3), python (2)
All validated.
```

### CI workflow (`.github/workflows/validate-submissions.yml`)

Runs on PRs targeting `staging` when `*/GE-*.md` files change:
- `validate_pr.py` for score, format, required fields
- `dedup_check.py` comparing against `main` branch garden.db (Jaccard similarity on titles + tags, threshold 0.8)
- Auto-label and auto-merge on pass

### Contributor hook (installed by `hortora-setup.sh`)

Post-commit hook in `~/.hortora/garden/.git/hooks/post-commit`:
```bash
#!/bin/bash
BRANCH="submissions/$(git config user.name | tr ' ' '-' | tr '[:upper:]' '[:lower:]')"
git push origin HEAD:$BRANCH 2>/dev/null
gh pr list --head "$BRANCH" --base staging --json number --jq length | \
  grep -q 0 && \
  gh pr create --head "$BRANCH" --base staging \
    --title "[$(git config user.name)] garden submissions" \
    --body "Auto-created by hortora-setup"
```

### What lives where

| Component | Repo |
|-----------|------|
| `hortora-setup.sh` | `Hortora/garden` |
| `validate-submissions.yml` | `Hortora/garden` |
| `dedup_check.py` | `Hortora/garden` |
| `staging` branch | `Hortora/garden` |
| ONNX model release assets | `Hortora/engine` |

### Scaling notes

Git handles thousands of small markdown files well. At 5K files, operations are sub-second. At 50K, use `--filter=blob:none` for shallow clones. At 100K+, consider a different storage model — but that's years away at current growth rates.
