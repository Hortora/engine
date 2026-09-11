---
layout: post
title: "The Rename That Touched Everything"
date: 2026-07-01
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [rename, maven, packaging]
---

# The Rename That Touched Everything

The repo was called `neural-text` because that's what it was — ONNX text inference. Then came RAG, then corpus storage, then CBR retrieval, then the memory backend migration. By the time we finished #20, the name described maybe a quarter of what lived there.

`neocortex` is the brain's centre for memory formation, pattern recognition, and knowledge retrieval. That maps directly to what the repo actually does: memory storage and recall via `CaseMemoryStore`, pattern recognition via CBR, knowledge retrieval via RAG, and perceptual processing via ONNX inference. It also pairs with `casehub-ras` (Reticular Activating System) — RAS detects situations, neocortex stores and reasons about them.

## The scope decision

I started with the conservative option — rename only the parent artifactId, keep module names as-is. Zero consumer impact. Then I caught myself: `casehub-inference-api` sitting alongside `casehub-engine-api` and `casehub-platform-api` would be the only repo in the platform without its name in its artifacts. Every other repo prefixes: `casehub-engine-*`, `casehub-qhorus-*`, `casehub-ras-*`. Leaving the old names would be carrying forward drift that gets harder to fix with every release.

So we went full: parent artifactId, all 28 module artifactIds, all Java packages. `casehub-inference-api` became `casehub-neocortex-inference-api`. `io.casehub.inference` became `io.casehub.neocortex.inference`. 426 files changed across the project.

## What the rename exposed

The paused #56 branch (memory backend migration) had already landed on platform's main — platform deleted `io.casehub.platform.api.memory.*` — but neural-text main hadn't absorbed it yet. So main was already broken for the memory modules before we touched anything. We merged #56 into the rename branch to get the SPI types, then re-applied the full neocortex rename to everything #56 brought in. The merge had 45 conflicting files, all from the package name collision — #56 used `io.casehub.memory`, our rename needed `io.casehub.neocortex.memory`.

Claude missed the `import static` pattern on the first pass — `import static io.casehub.inference.tasks.NliLabel.ENTAILMENT` has `static` between `import` and the package, so the string replacement didn't match. That plus 11 `DependencyConstraintTest` files using old packages as string literals in ArchUnit rules. Both caught on the first build attempt.

## What's left before this is real

The code changes are done. The parent repo is updated — aggregator, CI workflows, dependency management, PLATFORM.md, all docs. Two consumer issues filed: engine#627 for casehub engine (2 artifacts, 3 Java files) and engine#628 for Hortora engine (8 artifacts, 20 Java files). After merge, the coordinated steps: `gh repo rename`, local directory moves, git remote updates, Claude project memory rename, IntelliJ re-open. The #56 branch in the pause stack needs dropping since its content merged here.
