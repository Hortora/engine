---
title: "The Great Memory Migration"
date: 2026-07-01
author: mdp
type: delivery
projects: [casehub-neural-text]
tags: [migration, architecture, memory, cross-repo, intellij-mcp]
---

# The Great Memory Migration

The CBR work last session left a loose thread. `CbrCaseMemoryStore` in neural-text delegates durable storage to `CaseMemoryStore` in platform. Two repos, two SPIs, one conceptual domain. The delegation pattern worked — CDI displacement forced it — but the split meant every memory evolution required cross-repo coordination.

Issue #56 proposed the obvious fix: move everything. All of `CaseMemoryStore`, all five backends (JPA, SQLite, in-memory, Mem0, Graphiti), the reactive bridge, the contract test, the lot. From platform to neural-text.

I wasn't sure this was a single-session job.

## The discriminator fix

Before touching the migration, we fixed a design flaw in `reconstructCase` (#59). The method used `_features_json` presence as a type discriminator — checking whether a JSON blob existed to decide between `FeatureVectorCbrCase` and `TextualCbrCase`. Five failure modes fell out of the analysis: empty features caused data loss, future subtypes would return null or the wrong type, JSON corruption silently downgraded types.

The fix was clean. Added `cbrType()` to the `CbrCase` interface — each subtype declares a stable string constant. `CbrPointBuilder` writes `_cbr_type` to the Qdrant payload. `reconstructCase` dispatches by switch on the discriminator, with explicit failure for unknown types. The old `_case_class` field (which stored the Java FQCN but was never read) got removed.

We ran the design review — four rounds, fifteen issues, all resolved. The reviewer caught things I'd missed: two separate package renames needed (SPI types and backend implementations live in different packages), `CbrCaseEntry` had zero external consumers and should be deleted rather than migrated, the contract test count was wrong in the spec.

## Twenty-six repos in one window

The migration strategy was rename-first, move-second. Rename the package while the types are still in platform (where IntelliJ has full resolution context), then move the files to neural-text (no import changes needed, just physical relocation).

We opened all twenty-six casehub repos as a single IntelliJ workspace via `ide_open_workspace`. Cross-project code intelligence across the entire platform. `ide_move_file` on `CaseMemoryStore.java` — IntelliJ updated imports in eleven files across five repos in one operation.

Then the workspace bit us. IntelliJ's `ide_move_file` resolves the destination module by nearest matching package. When we moved types to `io.casehub.memory`, IntelliJ placed them in the `memory/` module (CDI wiring, which had `io.casehub.memory.cbr.runtime` as a subpackage) instead of `memory-api/` (the Tier 1 SPI module, which didn't have the `io/casehub/memory/` directory yet). The package declarations were correct, imports across all repos were correct — the files were just in the wrong Maven module. Invisible unless you check.

We caught it, relocated with `mv`, and submitted the gotcha to the garden.

## Sixty-three files, ten repos

The full import propagation touched sixty-three Java files across nine repos. IntelliJ handled some; a targeted Python script handled the rest (IntelliJ missed engine entirely, and several files in devtown, aml, clinical, and life had stale imports from wildcard-to-explicit conversions that the workspace refactoring didn't reach).

The backend module migration was mechanical: copy source with package rename, create POMs adapted from platform's parent to neural-text's parent, fix version property references (platform uses `${quarkus.platform.version}`, neural-text uses `${quarkus.version}`), add missing dependency versions (SQLite JDBC and HikariCP weren't managed by neural-text's parent BOM).

Consumer repos needed two new dependencies: `casehub-memory-api` (for the SPI types that moved out of `platform-api`) and `casehub-memory` at runtime scope (for `NoOpCaseMemoryStore` and `BlockingToReactiveBridge` — CDI needs these beans for augmentation validation even when a real backend is present).

Everything compiles. soc and fsitrading build clean end-to-end. The other consumer repos have pre-existing compilation issues unrelated to the migration — zero memory-related errors anywhere.

## What moved

Seventeen SPI types from `io.casehub.platform.api.memory` to `io.casehub.memory`. Five backend modules with their Flyway migrations, REST clients, DTOs, and WireMock test infrastructure. Two CDI wiring classes. One contract test with thirty-five test methods. `CbrCaseEntry` was deleted — `TextualCbrCase` supersedes it, and IntelliJ confirmed zero external consumers.

The package rename is the breaking change that matters. Every consumer updates one import prefix. The breakage is the point — it forces every caller to be explicit about where memory types come from.