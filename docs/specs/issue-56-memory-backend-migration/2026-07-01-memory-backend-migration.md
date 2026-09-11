# Memory Backend Migration — Design Spec

**Issues:** #58 (spec update), #59 (reconstructCase discriminator), #56 (memory backend migration)
**Branch:** `issue-56-memory-backend-migration`
**Date:** 2026-07-01

---

## 1. #58 — CBR Spec Update

Three corrections to `specs/issue-20-cbr-retrieval-architecture/2026-06-30-cbr-retrieval-architecture.md`:

1. **`store()` signature** — add missing `caseType` parameter (2nd position)
2. **`erase()`/`eraseEntity()` return types** — `Integer` (boxed), not `int` (required for `Uni<Integer>` reactive parity)
3. **`NoOpCbrCaseMemoryStore` delegation** — spec says it delegates to `CaseMemoryStore`; implementation is pure no-op. Update spec to match.

---

## 2. #59 — reconstructCase Discriminator

### Root cause

`QdrantCbrCaseMemoryStore.reconstructCase()` uses `_features_json` presence as a proxy for type identity. This is a data-presence signal being used as a type discriminator.

### Confirmed failure modes

| # | Failure | Severity |
|---|---------|----------|
| FM3 | `FeatureVectorCbrCase` with empty features → null (data loss) | High |
| FM4 | Future subtypes (PlanCbrCase) → null or wrong type | High |
| FM5 | `_case_class` stored but never read (dead data) | Low |
| FM6 | JSON deser failure → silent type downgrade to TextualCbrCase | Medium |

### Solution: explicit discriminator

**CbrCase interface** — add `String cbrType()` (no default, compiler-enforced):
```java
public interface CbrCase {
    String cbrType();
    String problem();
    String solution();
    String outcome();
    Double confidence();
}
```

**Subtypes** — stable string constants:
- `TextualCbrCase.CBR_TYPE = "textual"`
- `FeatureVectorCbrCase.CBR_TYPE = "feature-vector"`

**CbrPointBuilder** — replace `_case_class` (Java FQCN) with `_cbr_type` (stable string via `cbrCase.cbrType()`). Always write `_features_json` for FeatureVectorCbrCase (remove `!fv.features().isEmpty()` guard for the JSON blob; keep guard for `f_<name>` individual filter fields).

**reconstructCase** — discriminator-first dispatch:
```java
String cbrType = extractString(payload, "_cbr_type");
CbrCase reconstructed = switch (cbrType) {
    case FeatureVectorCbrCase.CBR_TYPE -> reconstructFeatureVector(payload, ...);
    case TextualCbrCase.CBR_TYPE -> new TextualCbrCase(problem, solution, outcome, confidence);
    case null -> throw new IllegalStateException("Missing _cbr_type in CBR point");
    default -> throw new IllegalArgumentException("Unknown CBR type: " + cbrType);
};
if (!caseClass.isInstance(reconstructed)) return null;
return caseClass.cast(reconstructed);
```

No backward compatibility fallback — research project, no production data.

**serializeToMemoryInput** — use `cbrCase.cbrType()` instead of `instanceof` chain.

**No changes to:** InMemoryCbrCaseMemoryStore (stores actual objects), NoOpCbrCaseMemoryStore, BlockingToReactiveCbrBridge, CbrCaseMemoryStore SPI, contract tests.

---

## 3. #56 — Memory Backend Migration

### Package rename

Two separate package renames (distinct IntelliJ refactoring operations):

1. `io.casehub.platform.api.memory.*` → `io.casehub.memory.*` (SPI types in platform-api, 17 types after CbrCaseEntry deletion)
2. `io.casehub.platform.memory.*` → `io.casehub.memory.*` (backend implementations in platform modules — NoOp, Bridge, InMem, JPA, SQLite, Mem0, Graphiti)

Breaking change. Rename in platform first (IntelliJ propagates imports across all open repos), then move files to neural-text.

**Prerequisite:** all consumer repos (including neural-text and casehub-all) must be open in the same IntelliJ workspace before step 1, so import propagation reaches all source trees.

### Module layout after migration

```
memory-api/         io.casehub.memory.*           — CaseMemoryStore, all value types (17 types — CbrCaseEntry deleted)
                    io.casehub.memory.cbr.*        — CbrCaseMemoryStore, CbrCase hierarchy (existing)

memory/             io.casehub.memory.runtime.*    — NoOpCaseMemoryStore (implements GraphCaseMemoryStore — satisfies both CaseMemoryStore and GraphCaseMemoryStore injection points), BlockingToReactiveBridge
                    io.casehub.memory.cbr.runtime.* — existing CBR CDI wiring

memory-testing/     io.casehub.memory.testing.*    — CaseMemoryStoreContractTest (35 tests)
                    io.casehub.memory.cbr.testing.* — existing CBR contract test

memory-inmem/       io.casehub.memory.inmem.*      — InMemoryMemoryStore @Alternative @Priority(10)
memory-jpa/         io.casehub.memory.jpa.*        — JpaMemoryStore @ApplicationScoped + Flyway
memory-sqlite/      io.casehub.memory.sqlite.*     — SqliteMemoryStore @Alternative @Priority(1)
memory-mem0/        io.casehub.memory.mem0.*        — Mem0CaseMemoryStore @Alternative @Priority(1)
memory-graphiti/    io.casehub.memory.graphiti.*    — GraphitiCaseMemoryStore @Alternative @Priority(2)

memory-cbr-inmem/   (existing, unchanged)
memory-qdrant/      (existing, updated per #59)
```

### Dependency graph

`memory-api` depends on `platform-api` for `CurrentPrincipal` only (used by `MemoryPermissions`). Both are Tier 1 pure Java — protocol-compliant.

### What moves

| From (platform) | To (neural-text) |
|---|---|
| `platform-api/…/memory/` (17 types — CbrCaseEntry deleted, not migrated) | `memory-api/…/memory/` |
| `platform/…/memory/` (NoOp + Bridge) | `memory/…/runtime/` |
| `testing/…/memory/` (contract test) | `memory-testing/…/testing/` |
| `memory-inmem/` (whole module) | `memory-inmem/` |
| `memory-jpa/` (whole module) | `memory-jpa/` |
| `memory-sqlite/` (whole module) | `memory-sqlite/` |
| `memory-mem0/` (whole module) | `memory-mem0/` |
| `memory-graphiti/` (whole module) | `memory-graphiti/` |

### What stays in platform

Everything non-memory: CurrentPrincipal, Path, Preferences, EndpointRegistry, agent providers, streams modules, SCIM, config, OIDC.

### Execution sequence

**Prerequisite:** open all consumer repos (neural-text, devtown, aml, clinical, soc, fsitrading, parent, casehub-all) in the same IntelliJ workspace before step 1.

1. Delete `CbrCaseEntry.java` and `CbrCaseEntryTest.java` from platform-api (superseded by `TextualCbrCase` — zero external consumers)
2. Rename package `io.casehub.platform.api.memory` → `io.casehub.memory` in platform-api (IntelliJ propagates all imports across workspace)
3. Rename package `io.casehub.platform.memory` → `io.casehub.memory` in platform backend modules (separate refactoring operation — different source package from step 2)
4. Create target module POMs in neural-text
5. Move files from platform to neural-text via `ide_move_file`
6. Move backend modules (copy module structure, update POMs, move source)
7. **Flyway:** migration path `db/memory/migration/V1000__memory_entry.sql` stays unchanged — classpath resolution is JAR-agnostic, moving the module does not change the resource path
8. Update Maven dependencies across all consumer repos
9. Update `casehub-parent` BOM: replace `casehub-platform-memory-*` version properties with `casehub-memory-*` equivalents
10. Remove memory modules from platform parent POM
11. Update prior CBR spec (issue #20, §2.2) — remove "All existing memory infrastructure remains in casehub-platform unchanged" and reference this spec as superseding
12. Update `neural-text/ARC42STORIES.MD` — add memory-* module entries (memory-api, memory, memory-testing, memory-inmem, memory-jpa, memory-sqlite, memory-mem0, memory-graphiti) as a new layer
13. Build all affected repos to verify

### Push strategy

All changes are made locally across all repos simultaneously (IntelliJ workspace). CI will see intermediate states during the push window. This is a research project with no production deployments — temporary CI breakage during migration is accepted.

Recommended push order (dependency-first):
1. `casehub-parent` (BOM with new `casehub-memory-*` coordinates)
2. `casehub-neural-text` (publishes new memory modules)
3. `casehub-platform` (memory modules removed)
4. All consumers in any order (devtown, aml, clinical, soc, fsitrading, casehub-all)

Pushes 1 and 2 should land before 3 and 4, so consumers can resolve the new artifacts. In practice, push all repos in rapid succession — CI pipelines take minutes to start, giving enough time for artifacts to publish.

### Consumer changes (mechanical — rebuilt from POM search)

| Repo | Current platform-memory deps | Action |
|---|---|---|
| platform | All memory-* modules (source) | Remove memory-* modules, delete source |
| parent | BOM version properties for all 5 `casehub-platform-memory-*` artifacts | Replace with `casehub-memory-*` artifact coordinates |
| devtown | `casehub-platform-memory-inmem` (app/pom.xml) | Swap → `casehub-memory-inmem` |
| aml | `casehub-platform-memory-jpa` + `casehub-platform-memory-inmem` (app/pom.xml) | Swap → `casehub-memory-jpa` + `casehub-memory-inmem` |
| clinical | `casehub-platform-memory-jpa` + `casehub-platform-memory-inmem` (runtime/pom.xml + parent pom.xml) | Swap → `casehub-memory-jpa` + `casehub-memory-inmem` (both runtime and parent POM) |
| soc | `casehub-platform-memory-jpa` + `casehub-platform-memory-inmem` (app/pom.xml) | Swap → `casehub-memory-jpa` + `casehub-memory-inmem` |
| fsitrading | `casehub-platform-memory-jpa` (app/pom.xml) | Swap → `casehub-memory-jpa` |
| casehub-all | Aggregator POMs mirroring devtown, aml, clinical deps + self-referential `all/platform/memory-*` modules | Swap artifact coordinates in aggregator POMs; remove or redirect `all/platform/memory-*` aggregates to `all/neural-text/memory-*` |

**Not affected (verified):**
- **engine** — no `casehub-platform-memory-*` deps. `casehub-engine-persistence-memory` is engine's own in-memory persistence module (case instances), unrelated to CaseMemoryStore.
- **life** — no `casehub-platform-memory-*` deps. `casehub-engine-persistence-memory` is engine's module, not platform memory.

### PLATFORM.md updates

- **Capability Ownership:** memory owner → `casehub-neural-text/memory-api`
- **Cross-Repo Dependency Map:** `casehub-memory-api` replaces `casehub-platform-api` for memory types
- **Build Order:** neural-text publishes before engine, aml, devtown, clinical, life, soc, fsitrading
- **Repository Map:** update `casehub-platform` one-liner to remove memory module references; update `casehub-neural-text` one-liner to include memory modules
- **Per-repo deep dives:** remove memory modules from `docs/repos/casehub-platform.md`; add memory modules to `docs/repos/casehub-neural-text.md`

### Discriminator key naming

After §2, the CBR type discriminator is stored in two storage systems with conventionally different key names:

| Storage system | Key name | Convention | Writer |
|---|---|---|---|
| Qdrant payload | `_cbr_type` | Underscore-prefixed metadata fields (Qdrant convention) | `CbrPointBuilder` |
| CaseMemoryStore attributes | `cbr.type` | Dot-separated attribute keys (CaseMemoryStore convention) | `serializeToMemoryInput` |

This is intentional — each storage system uses its own naming convention. `serializeToMemoryInput` does not write `_case_class` (that was Qdrant-only via `CbrPointBuilder`), so no removal is needed there.

### Superseding prior spec

This spec supersedes §2.2 of the CBR retrieval architecture spec (issue #20), which stated "All existing memory infrastructure remains in casehub-platform unchanged." That decision was appropriate at the time — the CBR layer was being built on top of the existing memory infrastructure. Now that CBR is stable and the memory SPI has matured, consolidating ownership in neural-text is the right architectural move.

---

## 4. Post-stabilisation: #57 — Rename to neocortex

After #56 is stable and all repos build, rename neural-text → neocortex on a separate branch while the IntelliJ workspace is still open. Not part of this spec — tracked as #57.