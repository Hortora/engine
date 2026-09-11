---
layout: post
title: "Clones, Not Branches"
date: 2026-08-03
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [workflow, git, slots, isolation, multi-repo]
---

Git branches share a working tree. That's fine when you're switching between features in one repo, but it falls apart when an epic touches multiple repositories and you want to keep working on the main codebase while a long-running piece marinates.

The retrieval quality epic ran across four batches over two days. During that time, the main neocortex repo kept moving — other epics landing, dependency bumps, CLAUDE.md changes. A branch would have forced a choice: stay on the epic branch and miss main's progress, or keep rebasing and deal with the churn. Neither is appealing when an epic has natural pause points between batches.

Slots solve this with `git clone --shared`. The slot at `worktrees/75/neocortex` was a full clone that shared the object store with the original — so it cost almost nothing in disk space — but had its own working tree, its own branch, its own index. The original repo stayed on main the entire time. Two independent working contexts, one object store.

The interesting part is the two-phase close. Phase A runs in the slot: pre-close sweep, code review, squash commits, push the branch to origin. At this point the branch exists on the original repo's remote but main hasn't moved. You can walk away, do other work, come back days later. Phase B runs against the original: fetch, rebase onto current main, fast-forward merge, push to fork and upstream. If the rebase conflicts, you're in the slot's working tree — the original is untouched.

For this epic, Phase A squashed three commits into two clean ones (one per issue that produced code), pushed the branch, then Phase B rebased onto a main that had moved 20+ commits since the slot was created. One `.gitignore` conflict, resolved in seconds. The original repo's main fast-forwarded cleanly.

The archive step moves the entire slot to `worktrees/attic/75/` — clone repos, `.slot` metadata, phase markers, workspace artifacts. Nothing is deleted. If something turns out wrong after the merge, the full context is recoverable.

The workflow matters because the alternative — long-lived branches on the original repo — creates a class of problems that compound with time: stale refs, forgotten rebases, surprise conflicts at merge time that require understanding code you wrote two weeks ago. The slot model trades a few seconds of clone setup for complete isolation, predictable merges, and the freedom to leave work mid-epic without contaminating the repo you use every day.
