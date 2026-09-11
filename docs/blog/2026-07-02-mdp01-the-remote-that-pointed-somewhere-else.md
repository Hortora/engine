---
layout: post
title: "The Remote That Pointed Somewhere Else"
date: 2026-07-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [rename, git, workspace]
---

# The Remote That Pointed Somewhere Else

The previous session did the hard rename — 426 files, 28 Maven modules, every Java package from `io.casehub.inference` to `io.casehub.neocortex.inference`. But the GitHub repo was still called `neural-text`, the local directories still said `neural-text`, and all the git remotes pointed to the old URLs. Seven coordinated steps sitting in the handoff, waiting.

## The blast radius was smaller than expected

Before touching anything, I wanted to know what would break. Claude searched every sibling repo under `~/claude/casehub/` for `neural-text` references. The answer: one live dependency. Platform's `publish.yml` CI workflow iterates a hardcoded list of downstream repos to dispatch builds — `neural-text` is in that list. Everything else was documentation: a spec in clinical, a spec in openclaw, a plan in platform. None of those would break; they'd just be cosmetically stale.

With all the dependent repos paused, the rename was safe to run.

## Three repos, not two

The GitHub rename itself was straightforward — `gh repo rename neocortex --repo casehubio/neural-text`, then the same for the fork. Directory moves, remote updates, symlink recreation, Claude project memory directory rename. Mechanical.

Then Claude updated the workspace remote to `mdproctor/neocortex.git` — the project fork URL. The next `git fetch` showed a forced update on `origin/main`. The next `git pull --rebase` failed with a conflict: `blog-routing.yaml deleted in HEAD`. The file exists in the workspace but not in the project repo, because the workspace has a completely separate commit history.

The workspace repo isn't the project repo. It has its own GitHub repository: `mdproctor/wsp-casehub-neural-text`. Every workspace across the platform follows this pattern — `wsp-casehub-platform`, `wsp-casehub-ledger`, `wsp-casehub-engine`. Nothing in the local directory structure hints at this. Both repos live under `~/claude/casehub/` and `~/claude/public/casehub/` with matching names, and both have a remote called `origin`. The only way to discover the convention was to check a peer workspace's remote.

Once we knew the pattern, the fix was a third GitHub rename (`wsp-casehub-neural-text` → `wsp-casehub-neocortex`) and correcting the workspace remote URL. The rebase conflict vanished, the push went through, and both repos were clean and synced.

The gotcha made it into the garden — the kind of thing you'd only hit during a rename, but the symptom (forced update + conflict on files the remote shouldn't know about) is genuinely confusing if you don't know there are two repos behind what looks like one project.
