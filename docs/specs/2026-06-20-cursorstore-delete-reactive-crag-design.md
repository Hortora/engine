# CursorStore.delete() + ReactiveCorrectiveCaseRetriever Design

**Date:** 2026-06-20
**Issues:** casehubio/neural-text#38, casehubio/neural-text#41
**Branch:** issue-38-cursorstore-delete-reactive-crag
**Revision:** 3 (post-review — 8 + 4 findings addressed)

---

## #38 — CursorStore.delete()

### Problem

Hortora engine needs to reset the ingestion cursor when migrating from dense-only to hybrid search. The current workaround is `CursorStore.save(bindingName, "")` — this works with `FileCursorStore` (empty content returns `Optional.empty()` on load) but couples to an implementation detail. The SPI contract makes no guarantee about empty-string semantics.

### Design

**SPI change** (`rag-api`): Add `void delete(String corpusName)` to `CursorStore` — no default body. Every implementation must provide a semantically correct delete. There are exactly two implementations (`FileCursorStore`, `InMemoryCursorStore`), both in this repo. No external implementations exist (verified: casehub-engine has no CursorStore usage; Hortora uses `FileCursorStore` via the `casehub-rag` dependency). A default body of `save(corpusName, "")` would be the same implementation-coupling hack the problem statement identifies as wrong.

**FileCursorStore** (`rag`): Delete the `{baseDir}/{corpusName}.cursor` file. No-op if the file doesn't exist. Throws `UncheckedIOException` on I/O failure, matching existing `save`/`load` error handling.

**InMemoryCursorStore** (`rag-testing`): `cursors.remove(corpusName)`.

### Tests

| Test | Implementation | Assertion |
|------|---------------|-----------|
| `deleteRemovesCursor` | Both | save → delete → load returns empty |
| `deleteNonExistentIsNoOp` | Both | delete unknown corpus, no exception |
| `deleteRemovesFile` | FileCursorStore | verify `.cursor` file physically removed |

---

## #41 — ReactiveCorrectiveCaseRetriever

### Problem

CRAG (#33) ships with a blocking `CorrectiveCaseRetriever` `@Decorator`. The reactive path (`ReactiveCaseRetriever`) is not yet decorated — reactive consumers bypass CRAG entirely.

### Design

**Class:** `ReactiveCorrectiveCaseRetriever` in `rag-crag` module, package `io.casehub.rag.crag`.

**CDI wiring:** `@Decorator @Priority(100)` on `ReactiveCaseRetriever`. Classpath-activated — no `@IfBuildProperty` gate. Matches the blocking decorator's activation model: rag-crag on classpath = CRAG active for both paths.

**Constructor injection:**
- `@Delegate @Any ReactiveCaseRetriever delegate`
- `RelevanceEvaluator evaluator`
- `CragConfig config`
- `Event<RetrievalQuality> qualityEvent`

**Maven dependency** (`rag-crag/pom.xml`): Add Mutiny as provided scope. The reactive decorator needs `Uni` and `Infrastructure`. `rag-api` has Mutiny as provided — not transitive in Maven. Explicit dependency required:

```xml
<dependency>
    <groupId>io.smallrye.reactive</groupId>
    <artifactId>mutiny</artifactId>
    <scope>provided</scope>
</dependency>
```

### Double-CRAG Prevention

In default mode (no native reactive), the CDI chain is:

```
ReactiveCorrectiveCaseRetriever (@Decorator on ReactiveCaseRetriever)
  → BlockingToReactiveCaseRetriever (@DefaultBean, injects CaseRetriever)
    → CorrectiveCaseRetriever (@Decorator on CaseRetriever)
      → HybridCaseRetriever
```

Without a guard, CRAG applies twice — the blocking decorator grades chunks, then the reactive decorator re-grades already-graded chunks. This corrupts quality counts, may re-expand, and fires two `RetrievalQuality` events.

**Fix:** Already-graded guard as a shared precondition in `CragEvaluationLogic`:

```java
static boolean isAlreadyGraded(List<RetrievedChunk> chunks) {
    return !chunks.isEmpty()
        && chunks.stream().noneMatch(c -> c.grade() == RelevanceGrade.UNGRADED);
}
```

Both decorators check this before evaluating. If chunks arrive pre-graded, the decorator returns them as-is and fires no event. The decorator did no work — silence is the right signal. No `RetrievalQuality.NONE`, no phantom event. One retrieval, one event, from the decorator that actually evaluated.

**Contract for both decorators when guard fires:**
- Return chunks unchanged
- Do not invoke `evaluator`
- Do not fire any `RetrievalQuality` event

The `RelevanceGrade` field on `RetrievedChunk` is the natural idempotency signal — CRAG's job is to grade UNGRADED chunks.

When native reactive is active (`casehub.rag.reactive.enabled=true`), `ReactiveHybridCaseRetriever` is the `ReactiveCaseRetriever` bean directly — no bridge, no blocking decorator, guard never fires. Correct in both modes.

### Shared Logic Extraction: CragEvaluationLogic

Package-private helper class in `io.casehub.rag.crag`. Pure functions — no CDI, no Uni dependencies. Both decorators delegate to this shared logic. Refactor blocking `CorrectiveCaseRetriever` to use it too.

**State carrier:**

```java
record GradeResult(List<RetrievedChunk> graded, Set<String> seen,
                   int correct, int ambiguous, int incorrect) {}
```

Bundles graded chunks, dedup seen-set, and per-grade counts so state flows cleanly between operations.

**Methods:**

```java
static boolean isAlreadyGraded(List<RetrievedChunk> chunks);

static GradeResult gradeChunks(List<RetrievedChunk> chunks,
                               List<RelevanceGrade> grades);

static List<RetrievedChunk> filterIncorrect(List<RetrievedChunk> graded);

static boolean needsExpansion(int survivorCount, int maxResults,
                              int incorrectCount);

static List<RetrievedChunk> deduplicateExpanded(
    List<RetrievedChunk> expanded, Set<String> seen);

static List<RetrievedChunk> sortAndTruncate(
    List<RetrievedChunk> merged, int maxResults);

static RetrievalQuality buildQualityEvent(
    int totalRetrieved, int correct, int ambiguous,
    int incorrect, boolean expanded);
```

**Dedup key fix:** The existing code uses `sourceDocumentId + "\0" + content.hashCode()` — a 32-bit hash that can incorrectly deduplicate two chunks with different content but colliding hashCodes. Since dedup sets are small (<100 entries), use the full content: `sourceDocumentId + "\0" + content`. Both decorators get the fix via the shared helper.

### Retrieve Flow

The reactive decorator orchestrates the same logic as the blocking decorator, but with nested conditional `Uni` chains and worker-thread offloads. This is materially more complex than the blocking version — not a simple mirror.

**Uni chain structure:**

```
delegate.retrieve(query, corpus, maxResults, filter)           → Uni<List<RetrievedChunk>>
  │
  ├─ isAlreadyGraded? → return chunks as-is, no event          → Uni<List<RetrievedChunk>>
  │
  └─ transformToUni: offload evaluateBatch to worker thread    → Uni<GradeResult>
       │
       ├─ needsExpansion = false:
       │    map: filterIncorrect, sortAndTruncate              → Uni<List<RetrievedChunk>>
       │
       └─ needsExpansion = true:
            transformToUni: delegate.retrieve(expanded limit)  → Uni<List<RetrievedChunk>>
              │
              transformToUni: offload 2nd evaluateBatch        → Uni<GradeResult>
                │
                map: deduplicateExpanded, merge,
                     filterIncorrect, sortAndTruncate          → Uni<List<RetrievedChunk>>
       │
       invoke: qualityEvent.fireAsync() — fire-and-forget
```

Three async boundaries (delegate call, first evaluation offload, conditional second delegate + evaluation). The `CragEvaluationLogic` extraction keeps the pure computation in the helper — the Uni chain is orchestration only.

### Testing

**Unit tests** (`CorrectiveCaseRetrieverTest` pattern — pure JUnit, constructor injection, no `@QuarkusTest`):

| Test | Assertion |
|------|-----------|
| `alreadyGradedChunksPassThrough` | Pre-graded chunks returned as-is, no evaluation, no event fired |
| `allCorrectChunksPassThrough` | No filtering, no expansion |
| `allIncorrectTriggersExpansion` | Delegate called again with `maxResults * config.expansionMultiplier()` |
| `mixedGradesFilterIncorrect` | INCORRECT removed, CORRECT + AMBIGUOUS kept |
| `truncationPrefersCORRECT` | CORRECT sorted before AMBIGUOUS |
| `emptyCorpusReturnsEmpty` | Empty delegate result → empty output |
| `smallCorpusNoExpansion` | Below threshold, no expansion triggered |
| `qualityEventFired` | `fireAsync()` called with correct counters |
| `expansionDeduplicates` | Same chunk from original + expansion appears once |
| `evaluatorRunsOnWorkerThread` | Verify evaluator executes off subscribing thread (event loop safety) |

Event mock implements `fireAsync()` returning `CompletableFuture.completedFuture()`.

**Integration test** (`example-rag-pipeline`): `CragReactivePipelineIT` — exercises reactive decorator end-to-end with `InMemoryRelevanceEvaluator`.

### CragEvaluationLogic Direct Tests

The shared helper has 6 static methods and a `GradeResult` record — pure functions independently testable without CDI or Uni orchestration. Direct tests isolate the logic and survive decorator refactors.

| Test | Method | Assertion |
|------|--------|-----------|
| `isAlreadyGraded_emptyList` | `isAlreadyGraded` | Empty list → false |
| `isAlreadyGraded_allGraded` | `isAlreadyGraded` | All CORRECT/AMBIGUOUS/INCORRECT → true |
| `isAlreadyGraded_allUngraded` | `isAlreadyGraded` | All UNGRADED → false |
| `isAlreadyGraded_mixed` | `isAlreadyGraded` | Mix of graded + UNGRADED → false |
| `gradeChunks_countsAccumulate` | `gradeChunks` | Correct/ambiguous/incorrect counts match input grades |
| `gradeChunks_dedupKeysUseFullContent` | `gradeChunks` | Seen set uses `sourceDocumentId + "\0" + content`, not hashCode |
| `deduplicateExpanded_filtersSeen` | `deduplicateExpanded` | Chunks in seen set filtered, unseen pass through |
| `sortAndTruncate_correctBeforeAmbiguous` | `sortAndTruncate` | CORRECT-graded chunks precede AMBIGUOUS |
| `sortAndTruncate_truncatesAtMax` | `sortAndTruncate` | Result size <= maxResults |
| `needsExpansion_thresholdBoundaries` | `needsExpansion` | survivors == maxResults → false; incorrect == 0 → false |
| `buildQualityEvent_fields` | `buildQualityEvent` | All fields propagated correctly, evaluated=true |

### Additional Tests on Existing Classes

**CorrectiveCaseRetrieverTest** (blocking decorator — new test):

| Test | Assertion |
|------|-----------|
| `alreadyGradedChunksPassThrough` | Pre-graded chunks returned as-is, no evaluator invocation, no event fired |

**Existing tests** (from issue body) — add `grade == UNGRADED` assertions:
- `HybridCaseRetrieverTest` — confirms without CRAG, chunks are ungraded
- `BlockingToReactiveCaseRetrieverTest` — same confirmation on reactive bridge path

---

## Module Impact

| Module | Changes |
|--------|---------|
| `rag-api` | `CursorStore.delete()` — no default body |
| `rag` | `FileCursorStore.delete()` + tests |
| `rag-testing` | `InMemoryCursorStore.delete()` + tests |
| `rag-crag` | `ReactiveCorrectiveCaseRetriever`, `CragEvaluationLogic` (shared logic extraction + dedup fix + direct tests), refactor blocking `CorrectiveCaseRetriever` (use shared logic + add guard test), Mutiny provided dependency, tests |
| `example-rag-pipeline` | `CragReactivePipelineIT` |

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Activation model | Classpath-activated | Matches blocking decorator; avoids untested `@IfBuildProperty` on `@Decorator` |
| Double-CRAG prevention | Already-graded guard via `isAlreadyGraded()` | `RelevanceGrade.UNGRADED` is the natural idempotency signal — CRAG grades UNGRADED chunks |
| Guard pass-through event | No event fired | Decorator did no work — silence is the right signal. One retrieval, one event, from the decorator that evaluated |
| CursorStore.delete() | No default body | Two known implementations, both in-repo. Default `save("")` is the hack the issue exists to eliminate |
| CDI event | `fireAsync()` fire-and-forget | Reactive path should not block on observer completion |
| Blocking evaluation | Worker thread offload | `RelevanceEvaluator` is blocking (cross-encoder); same pattern as `BlockingToReactiveCaseRetriever` |
| Shared logic | Extract `CragEvaluationLogic` with `GradeResult` record | Avoids duplicating grading/filtering/sorting; carries state cleanly between operations |
| Dedup key | Full content string, not hashCode() | Eliminates 32-bit collision risk; dedup sets are small (<100) |
| Reactive evaluator SPI | Deferred | YAGNI — can be added later without changing the decorator |

## Review Findings Addressed

| # | Finding | Resolution |
|---|---------|------------|
| 1 | Double CRAG in default mode | Added already-graded guard as shared precondition |
| 2 | CursorStore.delete() should be abstract | Dropped default body — no backward-compat shim |
| 3 | expansionFactor → expansionMultiplier | Fixed to `config.expansionMultiplier()` |
| 4 | Mutiny dependency missing in rag-crag | Added explicit provided-scope dependency |
| 5 | CragEvaluationLogic under-specified | Added `GradeResult` record, explicit method signatures with types |
| 6 | Reactive Uni chain complexity | Replaced "mirrors" with explicit nesting diagram, 3 async boundaries |
| 7 | Worker thread offload test missing | Added `evaluatorRunsOnWorkerThread` test |
| 8 | dedupKey collision risk | Fixed to full content string in shared helper |
| A | Blocking guard behavior unspecified | Explicit contract: return as-is, no evaluator, no event |
| B | NONE event on pass-through is noise | No event on pass-through for both decorators |
| C | CragEvaluationLogic needs direct tests | Added 11 direct unit tests for pure functions |
| D | Blocking decorator needs guard test | Added `alreadyGradedChunksPassThrough` to CorrectiveCaseRetrieverTest |