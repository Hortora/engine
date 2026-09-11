# Corrective RAG (CRAG) — Self-Healing Retrieval with Relevance Evaluation

**Issue:** casehubio/neural-text#33
**Date:** 2026-06-18
**Status:** Approved (rev 4 — post code review round 3)

## Problem

Standard RAG blindly trusts retrieval results. When Qdrant returns irrelevant chunks — vocabulary mismatch, stale corpus content, ambiguous queries — those chunks reach the LLM as context and degrade its output. In regulated domains (AML, clinical), irrelevant context injected into prompts can cause entity confusion, misleading safety assessments, or incorrect compliance determinations. The current pipeline has no mechanism to detect or correct poor retrieval quality.

The platform's `@DefaultBean` → no-op pattern already masks absent backends. CRAG addresses a different failure: a *present* backend returning *bad results* that silently propagate.

## Solution

A post-retrieval quality gate that evaluates each chunk's relevance, filters irrelevant results, optionally expands the search when too few good results survive, and surfaces quality metadata to consumers.

Three components:

1. **`RelevanceEvaluator` SPI** (rag-api) — judges individual (query, chunk) pairs as Correct/Ambiguous/Incorrect
2. **`CorrectiveCaseRetriever` CDI decorator** (rag-crag) — intercepts any `CaseRetriever`, applies evaluation, filters, and expands
3. **`CrossEncoderRelevanceEvaluator` default** (rag-crag) — threshold-based grading using the cross-encoder model already deployed for reranking

## Architecture

### Module layout

| Module | Contains | Tier | Hortora? |
|--------|----------|------|----------|
| `rag-api` | `RelevanceEvaluator` SPI, `RelevanceGrade`, `RetrievalQuality`, enriched `RetrievedChunk` | 1 (pure Java) | ✅ yes |
| `rag-crag` | `CorrectiveCaseRetriever` (`@Decorator`), `ReactiveCorrectiveCaseRetriever` (`@Decorator`), `CrossEncoderRelevanceEvaluator`, `CragBeanProducer`, `CragConfig` | CDI library | ✅ yes |
| `rag-testing` | `InMemoryRelevanceEvaluator` stub | 1 | ✅ yes |
| `rag` | No structural change — `RetrievedChunk` gains a 5th field, existing 4-arg construction defaults to `UNGRADED` | 3 | ✅ yes |

### Activation

Classpath presence — add `casehub-rag-crag` as a compile dependency. `CorrectiveCaseRetriever` is a CDI `@Decorator` — it intercepts the active `CaseRetriever` bean (whatever it is). Remove the dependency → zero cost, no CRAG code loaded.

**Prerequisite:** Adding `casehub-rag-crag` to the classpath requires a `CrossEncoderReranker` bean to be available (the default `RelevanceEvaluator` delegates to it). If no cross-encoder model is configured, the application will fail at startup with a descriptive error. In test scope, `InMemoryRelevanceEvaluator` (from `rag-testing`) replaces the real evaluator — no cross-encoder required.

### CDI wiring — @Decorator, not @Alternative

CRAG uses the CDI `@Decorator` pattern, not `@Alternative`. This solves three problems:

1. **No circular dependency** — constructor-injected `@Delegate` is CDI-managed; resolves to the underlying bean without self-injection
2. **No priority collision** — decorators don't compete with alternatives in bean resolution. `InMemoryCaseRetriever` (`@Alternative @Priority(1)` in rag-testing) and the CRAG decorator coexist: in `@QuarkusTest`, the decorator wraps the in-memory stub; in production, it wraps `HybridCaseRetriever`
3. **Classpath activation** — the decorator only exists when `rag-crag` is on the classpath

### Framework dependency split

The blocking `@Decorator` uses only CDI annotations (`jakarta.decorator.Decorator`, `jakarta.decorator.Delegate` — both in `jakarta.enterprise.cdi-api`) — no Quarkus-specific dependency. The reactive `@Decorator` uses `@IfBuildProperty(name = "casehub.rag.reactive.enabled", stringValue = "true")` which is Quarkus-specific. This matches the existing codebase pattern — blocking path is framework-independent, reactive path requires Quarkus Arc.

**Note on `CragConfig`:** The `@ConfigMapping` interface is SmallRye Config API. The decorator class references `CragConfig` as a plain interface — no SmallRye annotations on the decorator itself. However, `CragConfig` bean resolution requires SmallRye Config's CDI integration, which in practice means Quarkus. This is the same pattern as `RagConfig` in the existing codebase.

**Verification note:** `@IfBuildProperty` on a `@Decorator` class is a first-use pattern in this codebase. Quarkus Arc processes decorators via `DecoratorBuildItem` separately from normal beans. Both annotations target `ElementType.TYPE` and should compose, but verify during implementation. If it fails, the fallback is to gate the reactive decorator via a separate `@IfBuildProperty`-gated producer (same pattern as `ReactiveRagBeanProducer`), though that composes less cleanly with `@Decorator`.

### Dependencies

```
rag-crag → rag-api (compile)
rag-crag → inference-tasks (compile, for CrossEncoderReranker)
rag-crag → jakarta.enterprise.cdi-api (provided) — includes jakarta.decorator.* annotations
rag-crag → inference-inmem (test)
rag-crag → rag-testing (test)
```

No `quarkus:build` goal. Jandex indexed for CDI discovery.

## API Types (rag-api)

### RelevanceGrade

```java
public enum RelevanceGrade {
    CORRECT,    // chunk is relevant to the query
    AMBIGUOUS,  // uncertain — may or may not be relevant
    INCORRECT,  // chunk is not relevant to the query
    UNGRADED    // no evaluation was performed
}
```

### RetrievedChunk (enriched)

```java
public record RetrievedChunk(String content, String sourceDocumentId,
                             double relevanceScore, Map<String, String> metadata,
                             RelevanceGrade grade) {

    public RetrievedChunk(String content, String sourceDocumentId,
                          double relevanceScore, Map<String, String> metadata) {
        this(content, sourceDocumentId, relevanceScore, metadata, RelevanceGrade.UNGRADED);
    }

    public RetrievedChunk withGrade(RelevanceGrade grade) {
        return new RetrievedChunk(content, sourceDocumentId, relevanceScore, metadata, grade);
    }
}
```

The canonical constructor is 5-arg. Existing 4-arg call sites compile via the convenience constructor and default to `UNGRADED`. The compact constructor validation (null checks, `Map.copyOf()`) moves to the 5-arg canonical constructor.

### CaseRetriever (unchanged return type)

```java
public interface CaseRetriever {
    List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults, PayloadFilter filter);

    default List<RetrievedChunk> retrieve(String query, CorpusRef corpus, int maxResults) {
        return retrieve(query, corpus, maxResults, null);
    }
}
```

**Return type stays `List<RetrievedChunk>`.** Per-chunk grades travel on `RetrievedChunk` itself. Aggregate quality metadata (`RetrievalQuality`) is delivered as a CDI event, not on the return value. This avoids coupling the prompt compiler to retrieval telemetry and eliminates the cross-repo migration that a `RetrievalResult` wrapper would require.

Mirrored on `ReactiveCaseRetriever` — `Uni<List<RetrievedChunk>>`, unchanged.

### RetrievalQuality (CDI event, not return type)

```java
public record RetrievalQuality(
    int totalRetrieved,
    int totalCorrect,
    int totalAmbiguous,
    int totalIncorrect,
    boolean evaluated,
    boolean expandedSearch
) {
    public static final RetrievalQuality NONE =
        new RetrievalQuality(0, 0, 0, 0, false, false);
}
```

Consumers may use `RetrievalQuality.NONE` as a default when no event has been received — the non-CRAG path never fires this event.

`evaluated=true` means CRAG was active and grading happened. `evaluated=false` means no evaluation (non-CRAG path). Lives in `rag-api` (pure Java record — CDI event infrastructure is provided by the container, not the event type).

**Event dispatch:** The blocking decorator fires `qualityEvent.fire(quality)` (synchronous — safe, as the blocking path runs on a worker thread). The reactive decorator fires `qualityEvent.fireAsync(quality)` (asynchronous — avoids blocking the Vert.x event loop if any observer does blocking work). Observers of `RetrievalQuality` should use `@ObservesAsync` when the reactive path is active.

### RelevanceEvaluator SPI

```java
public interface RelevanceEvaluator {
    RelevanceGrade evaluate(String query, String chunkContent);

    default List<RelevanceGrade> evaluateBatch(String query, List<String> chunkContents) {
        List<RelevanceGrade> grades = new ArrayList<>(chunkContents.size());
        for (String content : chunkContents) {
            grades.add(evaluate(query, content));
        }
        return List.copyOf(grades);
    }
}
```

Pure Java, Tier 1. No inference dependency — implementations bring their own. The `evaluateBatch` default is sequential; `CrossEncoderRelevanceEvaluator` overrides with `CrossEncoderReranker.rerank()` for genuinely batched ONNX inference.

## rag-crag Module

### Maven coordinates

| Element | Value |
|---------|-------|
| Folder | `rag-crag/` |
| artifactId | `casehub-rag-crag` |
| Package | `io.casehub.rag.crag` |

### CragConfig

```java
@ConfigMapping(prefix = "casehub.rag.crag")
public interface CragConfig {
    @WithDefault("0.7")
    double correctThreshold();

    @WithDefault("0.3")
    double incorrectThreshold();

    @WithDefault("3")
    int expansionMultiplier();
}
```

`@ConfigMapping` is `io.smallrye.config.ConfigMapping`. CDI integration requires SmallRye Config (present in Quarkus). For unit tests outside a CDI container, provide a stub implementation of the interface.

### CrossEncoderRelevanceEvaluator

Default `RelevanceEvaluator` implementation. Reuses the cross-encoder model already deployed for reranking — no new model required. Scores each (query, chunk) pair and maps to `RelevanceGrade` via two configurable thresholds:

- Score ≥ `correctThreshold` → CORRECT
- Score ≤ `incorrectThreshold` → INCORRECT
- Between → AMBIGUOUS

Batch evaluation uses `CrossEncoderReranker.rerank()` for efficient batched inference, then thresholds each score.

**Calibration note:** Cross-encoder scores are model-dependent. A model trained for MS MARCO passage ranking produces scores in a different range than one trained for NLI. Default thresholds (0.7/0.3) are starting points — threshold tuning is mandatory per deployed model. Validate against a held-out relevance judgment set. Wrong thresholds silently degrade quality: too lenient passes irrelevant chunks, too strict filters good ones.

### CorrectiveCaseRetriever — CDI @Decorator

Constructor injection — all dependencies are constructor parameters, enabling pure Java unit tests without a CDI container.

```java
@Decorator
@Priority(100)
public class CorrectiveCaseRetriever implements CaseRetriever {

    private final CaseRetriever delegate;
    private final RelevanceEvaluator evaluator;
    private final CragConfig config;
    private final Event<RetrievalQuality> qualityEvent;

    @Inject
    CorrectiveCaseRetriever(@Delegate @Any CaseRetriever delegate,
                            RelevanceEvaluator evaluator,
                            CragConfig config,
                            Event<RetrievalQuality> qualityEvent) {
        this.delegate = delegate;
        this.evaluator = evaluator;
        this.config = config;
        this.qualityEvent = qualityEvent;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, CorpusRef corpus,
                                          int maxResults, PayloadFilter filter) {
        // 1. Initial retrieval
        List<RetrievedChunk> chunks = delegate.retrieve(query, corpus, maxResults, filter);
        int totalRetrieved = chunks.size();

        // 2. Evaluate each chunk
        List<String> contents = chunks.stream().map(RetrievedChunk::content).toList();
        List<RelevanceGrade> grades = evaluator.evaluateBatch(query, contents);

        // 3. Grade each chunk, build seen set for dedup
        List<RetrievedChunk> graded = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int correct = 0, ambiguous = 0, incorrect = 0;
        for (int i = 0; i < chunks.size(); i++) {
            RelevanceGrade grade = grades.get(i);
            switch (grade) {
                case CORRECT   -> correct++;
                case AMBIGUOUS -> ambiguous++;
                case INCORRECT -> incorrect++;
                default -> {}
            }
            RetrievedChunk c = chunks.get(i);
            seen.add(dedupKey(c));
            graded.add(c.withGrade(grade));
        }

        // 4. Filter: keep CORRECT + AMBIGUOUS, discard INCORRECT
        List<RetrievedChunk> surviving = new ArrayList<>(graded.stream()
            .filter(c -> c.grade() != RelevanceGrade.INCORRECT)
            .toList());

        // 5. Top-K expansion if too few survive
        boolean expanded = false;
        if (surviving.size() < maxResults) {
            expanded = true;
            int expandedLimit = maxResults * config.expansionMultiplier();
            List<RetrievedChunk> expandedChunks = delegate.retrieve(
                query, corpus, expandedLimit, filter);

            // Filter to only new chunks (not already seen)
            List<RetrievedChunk> newChunks = expandedChunks.stream()
                .filter(c -> !seen.contains(dedupKey(c)))
                .toList();

            // Evaluate new chunks
            if (!newChunks.isEmpty()) {
                List<String> newContents = newChunks.stream()
                    .map(RetrievedChunk::content).toList();
                List<RelevanceGrade> newGrades = evaluator.evaluateBatch(query, newContents);

                totalRetrieved += newChunks.size();
                for (int i = 0; i < newChunks.size(); i++) {
                    RelevanceGrade grade = newGrades.get(i);
                    switch (grade) {
                        case CORRECT   -> correct++;
                        case AMBIGUOUS -> ambiguous++;
                        case INCORRECT -> incorrect++;
                        default -> {}
                    }
                    if (grade != RelevanceGrade.INCORRECT) {
                        surviving.add(newChunks.get(i).withGrade(grade));
                    }
                }
            }
        }

        // 6. Sort by grade (CORRECT first, then AMBIGUOUS), preserving
        //    retrieval order within each grade. Truncate to maxResults.
        surviving.sort(Comparator.comparingInt(
            (RetrievedChunk c) -> c.grade() == RelevanceGrade.CORRECT ? 0 : 1));
        List<RetrievedChunk> result = surviving.stream()
            .limit(maxResults)
            .toList();

        // 7. Fire quality event (synchronous — blocking path runs on worker thread)
        qualityEvent.fire(new RetrievalQuality(
            totalRetrieved, correct, ambiguous, incorrect, true, expanded));

        return result;
    }

    private static String dedupKey(RetrievedChunk c) {
        return c.sourceDocumentId() + "\0" + c.content().hashCode();
    }
}
```

**Dedup strategy:** `sourceDocumentId` + `content.hashCode()` combined as a `String` key in a `HashSet<String>`. Content strings are already in heap from the initial retrieval; the set adds only the concatenated key overhead. `hashCode()` avoids storing duplicate full-content strings in the set. Collision probability is negligible for <100 chunks within a single retrieval call. A collision means a distinct chunk is incorrectly skipped during expansion — acceptable, as colliding chunks share a sourceDocumentId and likely similar content.

**Truncation ordering:** Before `limit(maxResults)`, the surviving set is sorted by grade: CORRECT first, then AMBIGUOUS. Within the same grade, retrieval order (Qdrant ranking) is preserved via a stable sort. This ensures that when truncation cuts chunks, AMBIGUOUS ones are dropped before CORRECT ones — grade quality takes precedence over retrieval rank.

### ReactiveCorrectiveCaseRetriever

Mirrors the same logic with `Uni<>` chains, running evaluation on the worker pool. Uses `qualityEvent.fireAsync(quality)` instead of `fire()` — asynchronous dispatch avoids blocking the Vert.x event loop. Gated by `@IfBuildProperty(name = "casehub.rag.reactive.enabled", stringValue = "true")`.

Constructor injection, same pattern as the blocking decorator.

### CragBeanProducer

Produces `RelevanceEvaluator` only — the decorator is CDI-managed via `@Decorator`, not produced by a factory.

```java
@ApplicationScoped
public class CragBeanProducer {

    @Inject CragConfig config;
    @Inject Instance<CrossEncoderReranker> rerankerInstance;

    @Produces @ApplicationScoped
    RelevanceEvaluator evaluator() {
        if (!rerankerInstance.isResolvable()) {
            throw new IllegalStateException(
                "rag-crag requires a CrossEncoderReranker bean. " +
                "Configure casehub.inference.models.<name> and produce a CrossEncoderReranker.");
        }
        return new CrossEncoderRelevanceEvaluator(
            rerankerInstance.get(),
            config.correctThreshold(),
            config.incorrectThreshold());
    }
}
```

### Configuration

```properties
casehub.rag.crag.correct-threshold=0.7    # score above = CORRECT (tune per model)
casehub.rag.crag.incorrect-threshold=0.3  # score below = INCORRECT (tune per model)
casehub.rag.crag.expansion-multiplier=3   # fetch 3x on retry
```

### Expansion strategy — scope and limitations

Top-K expansion helps when relevant content exists at lower ranks in the same corpus. It does **not** help when the corpus lacks relevant content entirely (vocabulary mismatch, stale corpus). These are the dominant failure modes in regulated domains.

Cross-corpus search and query expansion are separate architectural concerns:
- Query expansion → HyDE (#32), a pre-retrieval stage
- Cross-corpus search → requires a corpus registry/discovery mechanism that doesn't exist

Neither belongs inside the CRAG decorator. The decorator's fallback strategies are: filter (always) and expand (when surviving < maxResults). Both are configuration-driven, not pluggable via SPI — the correction logic is simple orchestration; the pluggable parts are the evaluator (how to judge) and the retriever (how to search).

## Changes to Existing Modules

### rag module

No return type change. `HybridCaseRetriever`, `ReactiveHybridCaseRetriever`, `BlockingToReactiveCaseRetriever` unchanged — they already return `List<RetrievedChunk>`. The only change: `RetrievedChunk` gains a 5th field (`grade`), but existing 4-arg construction compiles via the convenience constructor (defaults to `UNGRADED`).

### rag-testing module

`InMemoryCaseRetriever` — existing 4-arg `RetrievedChunk` construction unchanged (convenience constructor defaults to `UNGRADED`).

New: `InMemoryRelevanceEvaluator` — `@Alternative @Priority(1) @ApplicationScoped`, configurable fixed grade:

```java
public class InMemoryRelevanceEvaluator implements RelevanceEvaluator {
    private final RelevanceGrade fixedGrade;

    public InMemoryRelevanceEvaluator() { this.fixedGrade = RelevanceGrade.CORRECT; }
    private InMemoryRelevanceEvaluator(RelevanceGrade grade) { this.fixedGrade = grade; }

    public static InMemoryRelevanceEvaluator returning(RelevanceGrade grade) {
        return new InMemoryRelevanceEvaluator(grade);
    }

    @Override
    public RelevanceGrade evaluate(String query, String chunkContent) {
        return fixedGrade;
    }
}
```

### Consumer migration

**No cross-repo migration required.** `CaseRetriever.retrieve()` still returns `List<RetrievedChunk>`. casehub-engine and Hortora call sites are unchanged. Per-chunk grades are available via `chunk.grade()` if consumers choose to inspect them. `RetrievalQuality` is observable via CDI `@ObservesAsync RetrievalQuality` for consumers that want it.

Issue #40 (consumer migration) can be closed or repurposed as "consumer adoption of CRAG quality metadata" rather than a mechanical migration.

## Testing Strategy

### Unit tests in rag-crag (pure Java, no CDI)

Constructor injection enables direct instantiation without a CDI container. Tests provide `InMemoryCaseRetriever`, `InMemoryRelevanceEvaluator`, a stub `CragConfig` implementation, and a no-op `Event<RetrievalQuality>` (lambda capturing the fired value for assertion).

`CrossEncoderRelevanceEvaluatorTest`:
- Score above correctThreshold → CORRECT
- Score below incorrectThreshold → INCORRECT
- Score between → AMBIGUOUS
- Batch matches individual evaluation
- Backed by `InMemoryInferenceModel`

`CorrectiveCaseRetrieverTest`:
- All CORRECT → all chunks returned with grades, quality event has `evaluated=true`, `expandedSearch=false`
- All INCORRECT → all filtered, expansion triggered, quality counters reflect both initial and expansion evaluation
- Mixed grades → INCORRECT filtered, CORRECT + AMBIGUOUS kept
- Expansion triggered when surviving < maxResults
- Expansion deduplication — chunks from initial retrieval not re-evaluated
- Quality event `totalRetrieved`, `totalCorrect`, `totalAmbiguous`, `totalIncorrect` reflect combined initial + expansion counts
- Grades populated on returned chunks
- Truncation ordering — CORRECT chunks from expansion preferred over AMBIGUOUS chunks from initial retrieval when both exceed maxResults
- Empty initial retrieval → expansion triggered, quality counters correct (`totalRetrieved=0` from initial, expansion counts added)
- Backed by `InMemoryCaseRetriever` + `InMemoryRelevanceEvaluator`

### Unit tests in rag (RetrievedChunk enrichment)

`RetrievedChunkTest` — verify 4-arg convenience constructor defaults to `UNGRADED`, `withGrade()` produces correct copy, canonical 5-arg validation (null checks, `Map.copyOf()`).

Existing `HybridCaseRetrieverTest`, `BlockingToReactiveCaseRetrieverTest` — verify returned chunks have `grade == UNGRADED`.

### Integration test in example-rag-pipeline

`RagPipelineIT` — existing test, verify returned chunks carry `UNGRADED` (no CRAG on classpath in default profile). New `CragPipelineIT` under `examples` profile — add `casehub-rag-crag` dependency, ingests documents, retrieves with CRAG active, verifies filtering and per-chunk grades. Smoke profile uses `InMemoryRelevanceEvaluator`.

## ARC42STORIES Update Plan

CRAG starts **Journey J4: Retrieval Quality** — the first post-completion extension of the architecture.

### New journey

| Journey | Description | Chapters | Status |
|---------|-------------|----------|--------|
| J4 Retrieval Quality | Self-healing retrieval — evaluate, filter, and correct poor retrieval results | C10 | 🔲 pending |

### New chapter

| # | Chapter | Journey | Layers touched | Delta summary | Status |
|---|---------|---------|----------------|---------------|--------|
| 10 | Corrective RAG (CRAG) | J4 | L6, L7, **new L10** | L6: Low (RelevanceEvaluator SPI, RelevanceGrade, enriched RetrievedChunk), L7: Low (no structural change), L10: High (new module) | 🔲 pending (#33) |

### New layer

| Layer | Module | Tier | Hortora? |
|-------|--------|------|----------|
| L10 CRAG | `rag-crag` | CDI library, classpath-activated | ✅ yes |

### Updated chapter flow

```
C7 → C10["C10: CRAG\n+ L10, L6 (enriched)"]
C4 → C10
```

C10 depends on C7 (RAG pipeline — `CaseRetriever` SPI) and C4 (Task Adapters — `CrossEncoderReranker`). No dependency on C9 (Ingestion Bridge) — CRAG decorates retrieval, not ingestion.

### Accountability gaps closed by C10

| Gap | What breaks without it | Closed by |
|-----|------------------------|-----------|
| Silent bad retrieval | Irrelevant chunks reach LLM prompt, degrade output quality | RelevanceEvaluator grades each chunk; INCORRECT filtered |
| No retrieval quality signal | Consumers (AML, clinical) can't assess confidence in retrieved context | Per-chunk `RelevanceGrade` + `RetrievalQuality` CDI event |
| No corrective action | Poor initial retrieval is final | Top-K expansion re-retrieves when too few good results survive |

## Not in Scope

- Specific ONNX evaluator model — infrastructure only; model is deployment config. Tracked as #39.
- Query expansion / HyDE — tracked as #32, separate pre-retrieval concern
- Corpus broadening / cross-corpus search — requires corpus registry that doesn't exist
- PLATFORM.md capability ownership update — done at work-end
- Dedicated three-way classification model — upgrade path via the SPI (#39)
- FallbackStrategy SPI — correction logic (filter + expand) is simple orchestration; pluggable parts are already pluggable (evaluator, retriever)