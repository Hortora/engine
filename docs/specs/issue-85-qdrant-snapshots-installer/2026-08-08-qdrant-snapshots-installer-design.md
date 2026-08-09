# Qdrant Snapshots + Installer — Design Spec

**Issue:** Hortora/engine#85
**Date:** 2026-08-08
**Status:** Reviewed (light + cross-cutting)

## Problem

First-time engine setup requires computing BGE-M3 embeddings for the entire
garden corpus via ONNX on CPU — ~4.5s per entry, ~90 min for 2,600 entries.
End users, contributors, and the developer across machines all hit this cost.

Additionally, `update-engine.sh install` references retired Docker/Podman
infrastructure, doesn't download Qdrant or ONNX models, and doesn't restore
snapshots. A contributor who clones the repo and runs `install` gets a broken
setup.

## Solution Overview

1. A new `hortora-setup.sh` installer with modular subcommands
2. A GitHub Actions workflow that builds a Qdrant snapshot from the garden
   corpus and publishes all artifacts (snapshot, ONNX models, cursor) to
   GitHub Releases as split archives
3. A test harness with a small corpus for fast end-to-end validation
4. Cleanup of `update-engine.sh` (drop Docker, drop `install` subcommand)

**Target audiences:** Developer across machines, contributors, end users.
All require the engine repo to be cloned. Standalone installer (no repo)
deferred to Trellis.

## Section 1: Directory Structure & Version Pinning

The `~/.hortora/` layout is already established. The installer formalises it:

```
~/.hortora/
  qdrant/
    qdrant              — native binary (downloaded)
    config.yaml         — generated at install
    storage/            — Qdrant data (restored from snapshot)
    snapshots/          — Qdrant snapshots
  models/
    bge-m3/
      model.onnx        — downloaded from Release
      model.onnx.data   — downloaded from Release
      tokenizer.json    — downloaded from Release
    reranker/
      model.onnx        — downloaded from Release
      tokenizer.json    — downloaded from Release
  cursors/
    garden.cursor       — downloaded from Release (matches snapshot)
  cache/                — embedding cache DB
  logs/                 — service logs
  stats/                — CBR outcome data
  version.json          — installed version manifest
```

`version.json` tracks what's installed so the script can skip steps on
re-run and detect when an upgrade is needed. Written incrementally — each
step updates `version.json` on success, so a partial failure resumes from
the last completed step.

```json
{
  "qdrant": "1.19.0",
  "snapshot": "garden-2026-08-08",
  "cursor": "sha256:789xyz...",
  "bge-m3": "sha256:abc123...",
  "reranker": "sha256:def456...",
  "installed": "2026-08-08T12:00:00Z"
}
```

Version pinning lives in `hortora-setup.sh` itself — a block of constants
at the top declaring Qdrant version, Release tag for models/snapshot, and
expected checksums. When a new Release is published, update these constants
and commit.

## Section 2: Installer Script Architecture

One new script `hortora-setup.sh` with subcommands for each step.

```
hortora-setup.sh install           — full first-time setup (all steps)
hortora-setup.sh install-qdrant    — download + install native Qdrant + plist
hortora-setup.sh install-models    — download pre-exported ONNX models
hortora-setup.sh install-snapshot  — download + restore Qdrant snapshot + cursor
hortora-setup.sh install-engine    — build engine from source + install plist
hortora-setup.sh status            — check what's installed, what's outdated
hortora-setup.sh uninstall         — stop services, remove plists (keep data)
```

`install` runs all four `install-*` steps in order. Each step is idempotent —
checks `version.json` and checksums before downloading. Re-running `install`
after a partial failure picks up where it left off.

**Trellis integration point:** Each subcommand is independently callable.
Trellis can invoke individual subcommands or replace them with its own
download logic.

### Plist Templating

Current plists hardcode absolute paths. Replace with templates in the repo:

```
scripts/
  io.hortora.engine.plist.template
  io.hortora.qdrant.plist.template
```

Placeholders: `__HOME__`, `__JAVA_HOME__`, `__ENGINE_DIR__`, `__PATH__`,
`__QUARKUS_PROFILE__`. The install step substitutes via sed, detecting
JAVA_HOME from `/usr/libexec/java_home` on macOS and PATH from the
current shell environment. `__QUARKUS_PROFILE__` defaults to `dev`.
The Qdrant plist template is new to the repo — currently only exists
in `~/Library/LaunchAgents/`.

### Prerequisites (checked up front)

- JDK 25+ (via `java -version`)
- `curl` and `zstd` available
- Garden corpus cloned at a known path (default: `~/.hortora/garden`)
- macOS only for now (Linux systemd support is future work)

The installer does not clone the garden — it must already be present.
`hortora.garden.path` is set in the engine plist template via
`__GARDEN_PATH__` (defaults to `~/.hortora/garden`). The `status`
subcommand checks whether the garden path exists and warns if missing.

## Section 3: GitHub Actions Snapshot Pipeline

A `workflow_dispatch` workflow that builds the full artifact set and publishes
to a GitHub Release.

**Trigger:** Manual (`workflow_dispatch`) with inputs for release tag and
corpus type (`test` or `full`). Weekly cron schedule to be added once the
pipeline is stable.

### Workflow Steps

```
1.  Free disk space (~25 GB reclaimed from dotnet/android/ghc)
2.  Checkout engine repo + garden corpus
3.  Setup JDK 25 (Temurin)
4.  Restore ONNX models from Actions cache (key: model checksums)
5.  If cache miss → install Python deps, run export_bge_m3.py
6.  Build engine (./mvnw package -DskipTests)
7.  Download + start native Qdrant binary (from qdrant/qdrant releases)
8.  Start engine, wait for initial indexing to complete
    — poll GET /collections/hortora_garden until points_count equals
      the expected entry count (derived from `find garden/ -name '*.md'
      -not -name 'GARDEN.md' -not -name 'INDEX.md' | wc -l`), with a
      10-minute timeout
9.  Create snapshot via POST /collections/hortora_garden/snapshots
10. Package artifacts:
    - Qdrant snapshot → zstd compress → split -b 1900m
    - BGE-M3 model files → tar + zstd (single archive, ~1.5 GB)
    - Reranker model → tar + zstd (~80 MB)
    - garden.cursor (copy from cursors dir)
    - checksums.sha256 for every file
11. Create GitHub Release with tag, upload all parts
```

### Resource Budget (GitHub-hosted runner, free for public repos)

| Resource | Available | Estimated usage |
|----------|-----------|-----------------|
| vCPUs | 4 | 4 (ONNX is CPU-bound) |
| RAM | 16 GiB | ~8 GiB (ONNX + Qdrant + JVM) |
| Disk | ~30 GB (after cleanup) | ~20 GB (storage + models + build) |
| Time | 6 hr max | ~3 hr (full corpus), seconds (test corpus) |

### Artifact Sizes & Splitting

GitHub Releases: 2 GiB per file, 1,000 assets per release, no total size
or bandwidth limit.

| Artifact | Raw | Compressed (est.) | Parts |
|----------|-----|-------------------|-------|
| Qdrant snapshot | 6.6 GB | ~3-4 GB | 2 |
| BGE-M3 model | 2.2 GB | ~1.5 GB | 1 |
| Reranker model | 91 MB | ~80 MB | 1 |
| Cursor + checksums | <1 MB | <1 MB | 1 |

### Model Caching

BGE-M3 export requires PyTorch (~5 min). Actions cache (10 GB default)
stores the exported models (~2.3 GB) keyed on the checksum file hash.
Cache hit skips export entirely. Cache miss only on model version change.

### Release Naming

`garden-YYYY-MM-DD` for dated releases (kept for rollback). The workflow
also updates a `latest` release tag to point at the newest dated release.
The setup script downloads from `latest` by default, so users always get
the freshest snapshot without updating the script's constants.

**Atomicity:** The workflow creates the dated release first (with all
artifacts uploaded), then updates the `latest` tag to point to it. This
avoids the race condition where a concurrent run could mix artifacts —
`latest` always points to a complete, consistent release. The old `latest`
tag is moved (not deleted and recreated) via `gh release edit --tag`.

## Section 4: Installer Download & Restore Flow

What happens when a user runs `hortora-setup.sh install`:

### Step 1 — Create directories

```
mkdir -p ~/.hortora/{qdrant,models/bge-m3,models/reranker,cursors,cache,logs,stats}
```

### Step 2 — Install Qdrant (`install-qdrant`)

- Detect platform (`uname -m` → arm64/x86_64, `uname -s` → Darwin/Linux)
- Download binary from `qdrant/qdrant` GitHub releases (pinned version)
- Extract to `~/.hortora/qdrant/qdrant`
- Write `config.yaml` (storage path, ports 6333/6334)
- Template + install `io.hortora.qdrant.plist` → `~/Library/LaunchAgents/`
- Start Qdrant via `launchctl bootstrap`
- Wait for health: poll `GET http://localhost:6333/` (up to 30s — snapshot
  restore can delay startup)

### Step 3 — Install models (`install-models`)

- Download from GitHub Release (`Hortora/engine`, tag: `latest`):
  - `bge-m3-models.tar.zst` → extract to `~/.hortora/models/bge-m3/`
  - `reranker-models.tar.zst` → extract to `~/.hortora/models/reranker/`
- Verify checksums against `checksums.sha256` from the Release

### Step 4 — Restore snapshot (`install-snapshot`)

- Download snapshot parts from Release:
  `snapshot.tar.zst.part-aa`, `snapshot.tar.zst.part-ab`, ...
- Verify per-part checksums against `checksums.sha256` before reassembly
  (detect partial/corrupt downloads early, avoid wasting time on reassembly)
- Reassemble: `cat parts | zstd -d | tar x`
- Restore via `POST /collections/hortora_garden/snapshots/upload`
  (multipart upload — the Qdrant REST API uses POST, not PUT)
- Download `garden.cursor` → `~/.hortora/cursors/garden.cursor`
- Verify: `GET /collections/hortora_garden` → check `points_count > 0`

**Cursor portability:** The garden cursor stores absolute file paths from
the CI runner. The packaging step (CI workflow step 10) rebases cursor
paths to use `__GARDEN_PATH__` as a placeholder. The installer's
`install-snapshot` step substitutes `__GARDEN_PATH__` with the actual
garden path on the target machine. This ensures delta re-indexing only
processes entries added after the snapshot, not the entire corpus.

**CollectionMigration ordering:** The engine's `CollectionMigration` runs
at startup and may re-create the collection if vector config has changed.
The installer restores the snapshot BEFORE starting the engine. If
`CollectionMigration` detects a config mismatch (e.g. dimension change),
it will re-index — this is correct behaviour, not a bug. The snapshot
is a fast-start optimisation, not a guarantee against re-indexing.

### Step 5 — Install engine (`install-engine`)

- Detect JAVA_HOME via `/usr/libexec/java_home` (macOS)
- `./mvnw package -DskipTests`
- Template + install `io.hortora.engine.plist` → `~/Library/LaunchAgents/`
- Start engine via `launchctl bootstrap`
- Wait for health: poll `GET http://localhost:8080/` (up to 15s)
- Engine starts, detects cursor, embeds only delta entries since snapshot

### Result

**Total user wait:** Download time (~5-6 GB) + build time (~2 min) + delta
embedding (seconds to minutes). Down from ~90 min.

**Idempotency:** Each step checks `version.json` before doing work.
Re-running after a network failure skips completed steps.

## Section 5: `update-engine.sh` Changes

- **Remove line 28** — Docker `qdrant-bench` restart policy
- **Remove `install` subcommand** — `hortora-setup.sh install` owns setup
- **Keep `update`, `status`, `logs`** — dev rebuild workflow
- **Remove `uninstall`** — moves to `hortora-setup.sh uninstall` (single
  owner for service lifecycle)

`update-engine.sh` becomes: `update | status | logs`.

### Existing Script Fate

- `export_bge_m3.py` — **kept**. Used by the CI workflow (step 5) to
  produce ONNX models on cache miss. Not needed by end users.
- `download-models.sh` — **replaced** by `hortora-setup.sh install-models`.
  The old script only verified checksums (misleading name); the new
  subcommand downloads from Releases and verifies. Remove the old script.
- `io.hortora.engine.plist` — **replaced** by `.plist.template`. Remove
  the hardcoded version.

## Section 6: End-to-End Test Harness

### Test Corpus

8 garden entries committed to `src/test/resources/test-garden/` in the
engine repo:

**Initial set (6 entries — indexed into snapshot):**

| ID | Type | Domain |
|----|------|--------|
| GE-20260718-95e11e | gotcha | jvm (CBR parameter ordering) |
| GE-20260705-1cda0b | gotcha | web (JavaScript truthiness) |
| GE-20260707-674928 | gotcha | casehub-qhorus (FK constraint) |
| GE-20260604-21b1fa | gotcha | jvm (Mem0 scores) |
| GE-20260808-47dc40 | technique | casehub-engine (CasePlanModel) |
| GE-20260422-70b817 | technique | jvm (OTel, has See also refs) |

**Held back (2 entries — for delta re-indexing validation):**

| ID | Type | Domain |
|----|------|--------|
| GE-20260528-35a81c | gotcha | jvm (WorkItemPriority enum) |
| GE-20260604-9d91f9 | technique | jvm (CDI interceptor bindings) |

### CI Workflow Input

The snapshot workflow has a `corpus` input:
- `test` (default) — uses `src/test/resources/test-garden/`, seconds
- `full` — uses the real garden repo, ~3 hours (production snapshots)

### Validation Sequence (runs on PRs touching `scripts/` or `.github/workflows/`)

```
Phase 1 — Build snapshot from test corpus
  Install native Qdrant on runner
  Build engine, start pointed at test-garden/initial/ (6 entries)
  Wait for indexing (seconds)
  Create snapshot, package, split

Phase 2 — Fresh install from artifacts
  Stop engine + Qdrant, wipe storage
  Run hortora-setup.sh install (from local artifacts, not Releases)
  Verify: Qdrant running, 6 entries queryable, health check passes

Phase 3 — Delta re-indexing
  Copy 2 held-back entries into garden dir
  Trigger reindex via POST /api/garden/reindex (deterministic, no
  filesystem watcher timing dependency)
  Verify: 8 entries now queryable

Phase 4 — Idempotency
  Run hortora-setup.sh install again
  Verify: completes in seconds, no re-download, no re-restore
```

## Deliverables

| Deliverable | What |
|-------------|------|
| `scripts/hortora-setup.sh` | New installer with modular subcommands |
| `scripts/io.hortora.engine.plist.template` | Templated engine plist |
| `scripts/io.hortora.qdrant.plist.template` | Templated Qdrant plist |
| `.github/workflows/snapshot.yml` | Snapshot build + publish workflow |
| `.github/workflows/test-installer.yml` | E2E test workflow (PR trigger) |
| `src/test/resources/test-garden/` | 8 garden entries for testing |
| `update-engine.sh` | Drop `install`, `uninstall`, Docker references |
| Remove `download-models.sh` | Replaced by `install-models` subcommand |
| Remove `io.hortora.engine.plist` | Replaced by `.plist.template` |
| CLAUDE.md | Updated setup instructions |

## Out of Scope

- Standalone installer without engine repo (deferred to Trellis)
- Linux systemd support (future work)
- Automated weekly cron trigger (add once pipeline is stable)
- Native image / GraalVM build (JVM by design — see CLAUDE.md)
