---
layout: post
title: "Fifteen Branches and Nine Missing Posts"
date: 2026-06-18
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [code-review, hygiene, blog-routing, cross-repo]
---

# CaseHub Neural-Text — Fifteen Branches and Nine Missing Posts

**Date:** 2026-06-18
**Type:** phase-update

A code review came back on #35 and #36 — the Hortora integration gaps work. Three findings: a backwards-incompatible SPI change, a misleading parameter name, and a null-safety fragility. All valid.

The SPI finding was the interesting one. `CaseRetriever.retrieve()` gained a fourth parameter (`PayloadFilter`) but the old three-parameter signature was gone. Any existing consumer breaks at compile time. The reviewer flagged it as a warning with a reasonable hedge: if all consumers are updating in lockstep, the break is acceptable.

I added the default method anyway. A `default retrieve(query, corpus, maxResults)` that delegates to the four-parameter version costs nothing — one line per interface, zero runtime overhead — and means any consumer that doesn't care about payload filtering keeps compiling. The same treatment went to `ReactiveCaseRetriever`. The parity test (`BlockingReactiveParityTest`) needed updating too — it collected methods by name into a map, and the overload created a duplicate key. Filtering out `isDefault()` methods from both directions fixed it cleanly.

The other two findings were straightforward. `ReactiveHybridCaseRetriever.executeQuery` received its filter parameter as `tenantFilter` when the caller was actually passing `mergedFilter` — the combined tenant + payload filter. The blocking variant got this right; the reactive one had the wrong name. And `InMemoryCaseRetriever.matches()` relied on `List.copyOf` rejecting null containment checks to handle missing metadata fields in the `In` filter case. An explicit `metadata.containsKey()` guard removed the implicit dependency.

After the code work, I audited every branch in both repos. Sixteen issue branches and three backup branches in the project repo, fifteen matching branches in the workspace. All the issue branches had their work on main via squash merge, but three project branches and all fifteen workspace branches were missing the `chore: branch closed` closure stamp. The workspace branches had a different convention — `docs: mark closed` — that didn't match what the tooling looks for.

The branch audit led to a broader sweep. The corpus-storage-module spec had been sitting in workspace staging since June 11th, never promoted to `docs/specs/` in the project repo. One commit ahead of upstream that hadn't been pushed. Both fixed.

Then the blog routing audit. Only two of fifteen casehub workspaces had `blog-routing.yaml` — neural-text (just created) and platform. The other thirteen were missing it entirely, meaning `publish-blog` had no routing config to follow. Created all thirteen, committed and pushed. Four of them were on feature branches at the time — and here's where the session got instructive. A batch script that stamped closure commits cycled through branches, and when it returned to main, the routing files vanished from the working tree. They'd been committed to the feature branches, not main. Had to recreate and commit to main explicitly.

The blog audit also found nine unpublished entries across five workspaces — claudony, devtown, ledger, life, and qhorus. All on main, all publishable immediately. Copied to `_notes/` in mdproctor.github.io and pushed. Seven more entries are stranded on feature branches in other repos — those publish when the branches close.

The CLAUDE.md audit found drafthouse missing its entire artifact locations table, routing table, and structure section. Platform was missing the `design/JOURNAL.md` line from its artifact table. Both fixed and pushed.

The default-method decision is the kind of thing that looks trivial but compounds. Every SPI in the rag-api module will eventually gain parameters — filters, options, configuration. If each addition breaks the signature, consumers accumulate a tax on every upgrade. Default methods make additive changes non-breaking. The cost of adding them retroactively is always higher than including them from the start.
