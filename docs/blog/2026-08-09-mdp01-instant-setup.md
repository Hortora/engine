---
layout: post
title: "From ninety minutes to none"
date: 2026-08-09
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [installer, qdrant, snapshot, github-actions, ci-cd]
---

The engine's first-time setup has a 90-minute wall. Every entry in the garden corpus — 2,600 of them — needs BGE-M3 embeddings computed via ONNX on CPU, one at a time, at about 4.5 seconds each. Contributors who clone the repo and run `install` get a script that references Docker infrastructure we retired two months ago. End users hit the same wall. Nobody should have to wait an hour and a half to find out whether the garden search is any good.

The fix is to pre-compute everything on CI and ship the result. A GitHub Actions workflow indexes the full corpus, creates a Qdrant snapshot, packages the ONNX models, compresses and splits the archives (GitHub Releases caps at 2 GiB per file), and publishes the lot. A new installer script — `hortora-setup.sh` — downloads the artifacts, restores the snapshot, installs native Qdrant and the engine as launchd services, and gets out of the way. Total wait: download time plus a two-minute Maven build. Delta entries added since the snapshot get embedded on first startup — seconds, not hours.

The interesting design problem was cursor portability. The garden cursor — the file that tracks which entries have already been indexed — stores absolute file paths from the CI runner. Restore that cursor on a different machine and every path misses. The engine treats every entry as new and re-embeds the entire corpus, which defeats the whole point of shipping a snapshot. We solved it with placeholder substitution: the CI workflow rebases paths to `__GARDEN_PATH__` at packaging time, and the installer substitutes the actual path at restore time. Cheap, portable, no upstream changes needed.

The design review flagged a second subtlety: the `latest` release tag. If two workflow runs overlap, one can delete the release while the other uploads, mixing artifacts from different builds. Checksums fail, users get a broken install. The fix is straightforward — create an immutable dated release first, then point `latest` at the complete artifact set. But it's the kind of race condition that only surfaces when someone actually runs the pipeline twice in quick succession, which is exactly what happens during iteration.

The old `update-engine.sh install` did five things and none of them worked for a fresh machine. The new split is clean: `hortora-setup.sh` owns first-time setup (Qdrant binary, ONNX models, snapshot restore, engine build, launchd plists). `update-engine.sh` keeps the dev loop — rebuild and restart after code changes. Each installer step is idempotent via a `version.json` manifest, so a failed download resumes where it left off.

The E2E test harness uses eight garden entries — six for the initial snapshot, two held back for delta re-indexing validation. The CI workflow can run against this test corpus in seconds or against the full garden in about three hours. The full run is a `workflow_dispatch` — manual for now while we iterate, weekly cron once the pipeline stabilises.

What this opens up: Trellis — the garden's UI — can eventually wrap the installer as a first-run experience. The subcommands are independently callable, so Trellis can invoke `install-qdrant` and `install-snapshot` directly or replace them with its own download logic. The engine stays self-installable for contributors; Trellis adds the polish layer for everyone else.
