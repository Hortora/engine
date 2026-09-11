# HyDE — Hypothetical Document Embeddings for Query Expansion

**Issue:** casehubio/neural-text#32
**Date:** 2026-06-21
**Status:** Approved

## Problem

`CaseRetriever.retrieve(String query, ...)` uses a single `String query` for three distinct purposes: dense embedding, sparse embedding, and cross-encoder reranking. This overloading means any pre-retrieval query transformation (like HyDE) corrupts downstream scoring — if a decorator swaps the query string, reranking evaluates "does this chunk match the hypothetical document?" instead of "does this chunk answer the user's question?"

HyDE generates a hypothetical document passage from the query, then embeds *that* for vector search. The hypothetical document is closer in embedding space to actual relevant documents than the raw query, bridging the query-document vocabulary gap. But the original query must still be used for reranking and CRAG evaluation.

## Solution

Fix the overloaded `String query` at the SPI level. Replace it with `RetrievalQuery` — a value type carrying both the original query and optional expansion text. Each pipeline stage uses the right text for its purpose.

## Design

### RetrievalQuery (rag-api)

```java
/**
 * Carries both the original query and optional pre-retrieval expansion text.
 * {@code expandedText} covers any pre-retrieval query transformation —
 * HyDE hypothetical documents, step-back prompts, template expansions.
 * Not related to CRAG's result-set expansion (higher top-K).
 */
public record RetrievalQuery(String text, String expandedText) {

    public RetrievalQuery {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("text must not be null or blank");
    }

    public static RetrievalQuery of(String text) {
        return new RetrievalQuery(text, null);
    }

    public String searchText() {
        return expandedText != null ? expandedText : text;
    }

    public RetrievalQuery withExpansion(String expandedText) {
        return new RetrievalQuery(text, expandedText);
    }
}
```

- `text()` — original query. Used for reranking, CRAG evaluation, SPLADE sparse embedding, logging.
- `expandedText()` — pre-retrieval expansion output (HyDE hypothetical document, template expansion, step-back prompt). Null when no expansion applied.
- `searchText()` — resolves which text to use for dense similarity matching: `expandedText` if present, else `text`.
- `withExpansion()` — produces a new query with expansion attached, preserving the original.

### CaseRetriever / ReactiveCaseRetriever SPI changes (rag-api)

Breaking change — `String query` → `RetrievalQuery query`:

```java
public interface CaseRetriever {
    List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                   int maxResults, PayloadFilter filter);

    default List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                           int maxResults) {
        return retrieve(query, corpus, maxResults, null);
    }
}
```

```java
public interface ReactiveCaseRetriever {
    Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus,
                                        int maxResults, PayloadFilter filter);

    default Uni<List<RetrievedChunk>> retrieve(RetrievalQuery query, CorpusRef corpus,
                                                int maxResults) {
        return retrieve(query, corpus, maxResults, null);
    }
}
```

No String-based convenience methods on the SPI. Callers construct `RetrievalQuery` explicitly.

**`RelevanceEvaluator` is intentionally unchanged** — it stays `evaluate(String query, String chunkContent)`. It operates on text content, not retrieval queries. CRAG decorators extract `query.text()` before calling the evaluator.

### QueryExpander SPI (rag-api)

```java
public interface QueryExpander {
    RetrievalQuery expand(RetrievalQuery query);
}
```

Takes `RetrievalQuery` (not `String`) so implementations have access to the original query text alongside any prior expansion — useful if a future expander needs to reason about both. No reactive variant; the HyDE decorator owns threading. No `@DefaultBean` — absence of a bean is the "off" state.

### rag-hyde module (new)

**Activation:** classpath + config. Both required.

```properties
casehub.rag.hyde.enabled=true    # gates @IfBuildProperty — classpath alone does NOT activate
casehub.rag.hyde.mode=llm        # "llm" (default) or "template"
casehub.rag.hyde.prompt-template # optional custom LLM prompt (default below)
casehub.rag.hyde.template        # template string for template mode (default below)
```

#### HydeConfig

```java
@ConfigMapping(prefix = "casehub.rag.hyde")
public interface HydeConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("llm")
    String mode();

    Optional<String> promptTemplate();

    Optional<String> template();
}
```

#### Decorators

`HydeCaseRetriever` — `@Decorator @Priority(200)` on `CaseRetriever`, gated by `@IfBuildProperty(name = "casehub.rag.hyde.enabled", stringValue = "true")`. Calls `QueryExpander.expand()` with fail-safe error handling, delegates with the expanded query.

```java
@Decorator
@Priority(200)
@IfBuildProperty(name = "casehub.rag.hyde.enabled", stringValue = "true")
public class HydeCaseRetriever implements CaseRetriever {

    private static final Logger LOG = Logger.getLogger(HydeCaseRetriever.class);

    private final CaseRetriever delegate;
    private final QueryExpander expander;

    @Inject
    HydeCaseRetriever(@Delegate @Any CaseRetriever delegate,
                      QueryExpander expander) { ... }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                          int maxResults, PayloadFilter filter) {
        RetrievalQuery expanded;
        try {
            expanded = expander.expand(query);
        } catch (Exception e) {
            LOG.warn("Query expansion failed, using original query", e);
            expanded = query;
        }
        return delegate.retrieve(expanded, corpus, maxResults, filter);
    }
}
```

`ReactiveHydeCaseRetriever` — same structure for `ReactiveCaseRetriever`. Wraps the `expander.expand()` call with `runSubscriptionOn(Infrastructure.getDefaultWorkerPool())` since both LLM and template expansion are blocking operations. Same fail-safe error handling — `.onFailure().recoverWithItem(query)` with logging.

**Priority 200** puts HyDE outermost — it executes before CRAG (Priority 100). The full pipeline:

```
raw query
  → HyDE @Priority(200): expand query, attach expandedText (fail-safe on error)
    → CRAG @Priority(100): evaluate with text(), filter, expand search
      → HybridCaseRetriever: dense embed with searchText(), sparse embed with text(), rerank with text()
```

#### LlmQueryExpander

```java
@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.hyde.mode", stringValue = "llm", enableIfMissing = true)
public class LlmQueryExpander implements QueryExpander {

    static final String DEFAULT_PROMPT =
        "Given the question below, write a short passage (3-5 sentences) "
            + "that would directly answer it. Write as if the passage comes from "
            + "an authoritative document. Do not include the question itself.\n\n"
            + "Question: %s\n\nPassage:";

    private final ChatModel chatModel;
    private final HydeConfig config;

    @Override
    public RetrievalQuery expand(RetrievalQuery query) {
        String prompt = config.promptTemplate().orElse(DEFAULT_PROMPT)
            .formatted(query.text());
        String hypothetical = chatModel.chat(prompt);
        return query.withExpansion(hypothetical);
    }
}
```

**LLM provider requirement:** `LlmQueryExpander` injects `ChatModel`, which is an interface from `langchain4j-core`. The consuming application must configure an LLM provider (e.g., `quarkus-langchain4j-openai`, `quarkus-langchain4j-ollama`) for a `ChatModel` bean to exist. If HyDE is enabled with `mode=llm` but no `ChatModel` is configured, CDI fails at startup with an unsatisfied dependency — this is the correct behavior (fail fast, not at query time).

#### TemplateQueryExpander

```java
@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.hyde.mode", stringValue = "template")
public class TemplateQueryExpander implements QueryExpander {

    static final String DEFAULT_TEMPLATE =
        "A document that answers the question \"%s\" would contain the following information:";

    private final HydeConfig config;

    @Override
    public RetrievalQuery expand(RetrievalQuery query) {
        String expanded = config.template().orElse(DEFAULT_TEMPLATE)
            .formatted(query.text());
        return query.withExpansion(expanded);
    }
}
```

**When to use template mode:**
- Deterministic, zero-latency, zero-cost expansion — no LLM call.
- Useful for structured/predictable query domains where document format is well-known (e.g., product catalogs, standardised forms).
- Useful in test environments as a lightweight HyDE approximation.
- Template format: Java `String.format` with `%s` placeholder for the query text. Custom templates set via `casehub.rag.hyde.template`.

**When to use LLM mode:**
- Free-form natural language queries where vocabulary gap is the primary retrieval problem.
- Domain-specific content where the LLM can generate plausible document-like text.
- Accepts the latency and cost tradeoff of an LLM call per query.

#### Dependencies

`rag-hyde` depends on:
- `rag-api` (for CaseRetriever, ReactiveCaseRetriever, QueryExpander, RetrievalQuery)
- `langchain4j-core` (for ChatModel — used by LlmQueryExpander)
- Quarkus Arc + Mutiny (CDI, reactive)

`rag-hyde` does NOT depend on `rag` or `rag-crag` — it decorates the SPI, not the implementation. Consumers add `rag-hyde` to classpath and set `casehub.rag.hyde.enabled=true`.

**Package:** `io.casehub.rag.hyde`
**Artifact:** `casehub-rag-hyde`

### CRAG retrofit (rag-crag)

**Activation model change:** CRAG currently activates purely by classpath presence — this was an explicit design decision in the CRAG spec and is documented in ARC42STORIES L10 as "classpath-activated." This spec changes CRAG to classpath + config activation, matching HyDE's model. This is a deliberate behavioral change: any deployment that has `casehub-rag-crag` on the classpath without `casehub.rag.crag.enabled=true` will no longer activate CRAG. This is the right direction — config-gated activation prevents accidental activation from transitive dependencies and lets you add the module without activating it during testing.

```properties
casehub.rag.crag.enabled=true    # required — classpath alone no longer activates
```

**All three CRAG beans** gain `@IfBuildProperty(name = "casehub.rag.crag.enabled", stringValue = "true")`:
- `CorrectiveCaseRetriever` (decorator)
- `ReactiveCorrectiveCaseRetriever` (decorator)
- `CragBeanProducer` (produces `RelevanceEvaluator`)

`CragBeanProducer` must be gated — without the gate, it would still produce a `RelevanceEvaluator` and throw `IllegalStateException` if no `CrossEncoderReranker` exists, even when CRAG is disabled.

**String → RetrievalQuery changes in CRAG decorators:**

`CorrectiveCaseRetriever`:
```java
@Override
public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                      int maxResults, PayloadFilter filter) {
    List<RetrievedChunk> chunks = delegate.retrieve(query, corpus, maxResults, filter);

    if (CragEvaluationLogic.isAlreadyGraded(chunks)) {
        return chunks;
    }

    // Evaluate against original query text, not expansion
    List<String> contents = chunks.stream().map(RetrievedChunk::content).toList();
    var initial = CragEvaluationLogic.gradeChunks(chunks,
        evaluator.evaluateBatch(query.text(), contents));

    // ... filtering logic unchanged ...

    if (CragEvaluationLogic.needsExpansion(...)) {
        // Pass full RetrievalQuery — keeps expansion for delegate embedding
        List<RetrievedChunk> expandedChunks = delegate.retrieve(
            query, corpus, expandedLimit, filter);

        // Evaluate expanded results against original query text
        List<String> newContents = newChunks.stream()
            .map(RetrievedChunk::content).toList();
        var expansionResult = CragEvaluationLogic.gradeChunks(newChunks,
            evaluator.evaluateBatch(query.text(), newContents));
        // ...
    }
    // ...
}
```

`ReactiveCorrectiveCaseRetriever` — same pattern: `query.text()` for all `evaluator.evaluateBatch()` calls, full `query` passthrough to `delegate.retrieve()`.

**`CragEvaluationLogic` is unchanged** — it's a pure static utility operating on `RetrievedChunk` and `RelevanceGrade`. It never touches the query.

`CragConfig` gains an `enabled` property:
```java
@ConfigMapping(prefix = "casehub.rag.crag")
public interface CragConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("0.7")
    double correctThreshold();

    @WithDefault("0.3")
    double incorrectThreshold();

    @WithDefault("3")
    int expansionMultiplier();
}
```

### HybridCaseRetriever / ReactiveHybridCaseRetriever changes (rag)

**Dense vs sparse embedding split:** HyDE targets dense retrieval — the hypothetical document bridges the query-document embedding-space gap. SPLADE already performs its own learned term expansion for lexical matching. Feeding SPLADE a hallucinated document risks phantom lexical terms, which is especially problematic for clinical/legal text where precise terminology matters. The principled default is to split:

- Dense embedding: `query.searchText()` — hypothetical document if expanded, else original query
- Sparse SPLADE embedding: `query.text()` — always the original query
- Cross-encoder reranking: `query.text()` — always the original query

```java
@Override
public List<RetrievedChunk> retrieve(RetrievalQuery query, CorpusRef corpus,
                                      int maxResults, PayloadFilter filter) {
    // ... tenant guard, collection check, filter building unchanged ...

    // Dense embedding: use searchText (hypothetical doc if expanded)
    Embedding denseEmbedding = embeddingModel.embed(
        TextSegment.from(query.searchText())).content();

    // Sparse embedding: use original query (SPLADE's own expansion handles vocabulary)
    Map<Integer, Float> sparseMap = sparseEmbedder != null
        ? sparseEmbedder.embed(query.text()) : null;

    // ... Qdrant query construction unchanged ...

    // Reranking: use original query
    if (rerankEnabled && reranker != null && !chunks.isEmpty()) {
        List<RankedResult> ranked = reranker.rerank(query.text(), texts);
        // ...
    }
}
```

`ReactiveHybridCaseRetriever` — same split. `embedQuery()` takes the full `RetrievalQuery` and uses `searchText()` for dense, `text()` for sparse:

```java
private QueryEmbeddings embedQuery(RetrievalQuery query) {
    Embedding denseEmbedding = embeddingModel.embed(
        TextSegment.from(query.searchText())).content();
    Map<Integer, Float> sparseMap = sparseEmbedder != null
        ? sparseEmbedder.embed(query.text()) : null;
    return new QueryEmbeddings(denseEmbedding, sparseMap);
}

private Uni<List<RetrievedChunk>> maybeRerank(RetrievalQuery query,
        List<RetrievedChunk> chunks, int maxResults) {
    // ...
    List<RankedResult> ranked = reranker.rerank(query.text(), texts);
    // ...
}
```

### Testing (rag-testing)

**InMemoryQueryExpander** — `@Alternative @Priority(1) @ApplicationScoped`. Deterministic stub: returns `query.withExpansion("hypothetical: " + query.text())`. Records expanded queries for test assertions. Follows `InMemoryRelevanceEvaluator` pattern.

**InMemoryCaseRetriever / InMemoryReactiveCaseRetriever** — signature change only (`String query` → `RetrievalQuery query`). The query parameter remains unused — these stubs return stored chunks or a fixed response, applying only `PayloadFilter`.

**All existing tests** — mechanical `"query"` → `RetrievalQuery.of("query")` migration.

#### HyDE + CRAG combined pipeline test

Explicit test verifying the critical invariant — when both decorators are active, CRAG evaluates against the original query text, not the HyDE expansion:

```java
// InMemoryQueryExpander produces expansion
// InMemoryCaseRetriever returns chunks
// InMemoryRelevanceEvaluator captures the query string it receives
// Assert: evaluator got query.text() ("original question"), not query.searchText() ("hypothetical: original question")
```

This test lives in `rag-crag` (which already depends on `rag-testing`). It uses `InMemoryQueryExpander` to simulate HyDE without `rag-hyde` on the classpath — the test constructs a `RetrievalQuery` with expansion set directly.

### Module structure

```
rag-api/                          (new types added)
  + RetrievalQuery
  + QueryExpander
  ~ CaseRetriever                  — String → RetrievalQuery
  ~ ReactiveCaseRetriever          — String → RetrievalQuery

rag-hyde/                          (new module)
  ├── HydeCaseRetriever            — @Decorator @Priority(200), fail-safe
  ├── ReactiveHydeCaseRetriever    — @Decorator @Priority(200), fail-safe
  ├── LlmQueryExpander             — QueryExpander impl, requires ChatModel
  ├── TemplateQueryExpander         — QueryExpander impl, pattern-based
  └── HydeConfig                   — @ConfigMapping

rag/                               (modified)
  ~ HybridCaseRetriever            — searchText() for dense, text() for sparse + reranking
  ~ ReactiveHybridCaseRetriever    — same
  ~ BlockingToReactiveCaseRetriever — pass-through signature change

rag-crag/                          (modified)
  ~ CorrectiveCaseRetriever        — RetrievalQuery + @IfBuildProperty
  ~ ReactiveCorrectiveCaseRetriever — RetrievalQuery + @IfBuildProperty
  ~ CragBeanProducer               — + @IfBuildProperty
  ~ CragConfig                     — + enabled property

rag-testing/                       (modified)
  ~ InMemoryCaseRetriever          — signature change
  ~ InMemoryReactiveCaseRetriever  — signature change
  + InMemoryQueryExpander          — deterministic stub
```

### Dependency graph

```
rag-api  ←── rag-hyde  (+ langchain4j-core for ChatModel)
   ↑
   ├──── rag
   ├──── rag-crag
   └──── rag-testing
```

## ARC42STORIES update plan

### Journey placement

HyDE belongs in **J4 Retrieval Quality** — the same journey as CRAG (C10). Both are retrieval pipeline enhancements that improve result quality through different mechanisms (pre-retrieval expansion vs post-retrieval correction).

### New chapter: C11 — HyDE Query Expansion

| Field | Value |
|---|---|
| Journey | J4 |
| Sequence | after C10 |
| Layers touched | L6 (Med), L7 (Med), L10 (Low), L11 (High) |
| Dependencies | C7 (CaseRetriever SPI), C10 (CRAG exists — activation model retrofit) |
| Issues | #32 |

**Delta summary:** `rag-hyde` module — CDI `@Decorator` on `CaseRetriever` that expands queries via `QueryExpander` SPI before retrieval. LLM-backed (default) and template-based expanders. Classpath + config activated. `rag-api` enriched with `RetrievalQuery` value type and `QueryExpander` SPI. `CaseRetriever`/`ReactiveCaseRetriever` SPI breaking change: `String query` → `RetrievalQuery query`. CRAG retrofitted to classpath + config activation. `HybridCaseRetriever` split: dense embedding uses `searchText()`, sparse uses `text()`.

### New layer: L11 HyDE

| Layer | Module | Tier | Hortora? |
|---|---|---|---|
| L11 HyDE | `rag-hyde` | CDI library, classpath + config activated | ✅ yes |

### Layer updates

- **L6 RAG SPI:** + `RetrievalQuery` value type, + `QueryExpander` SPI interface, `CaseRetriever`/`ReactiveCaseRetriever` signature change (`String` → `RetrievalQuery`). `rag-testing` gains `InMemoryQueryExpander` stub.
- **L7 RAG Runtime:** `HybridCaseRetriever`/`ReactiveHybridCaseRetriever` updated — dense embedding uses `searchText()`, sparse embedding uses `text()`, reranking uses `text()`. `BlockingToReactiveCaseRetriever` signature passthrough.
- **L10 CRAG:** Activation model changes from classpath-only to classpath + `casehub.rag.crag.enabled=true`. All three beans (`CorrectiveCaseRetriever`, `ReactiveCorrectiveCaseRetriever`, `CragBeanProducer`) gain `@IfBuildProperty`. Description changes from "classpath-activated" to "classpath + config activated". Signature updated for `RetrievalQuery`.

### Chapter flow update

```
C7 --> C10["C10: CRAG\n+ L10, L6 (enriched)"]
C4 --> C10
C7 --> C11["C11: HyDE\n+ L11, L6 (enriched), L7, L10 (retrofitted)"]
C10 --> C11
```

C11 depends on C10 because the CRAG activation model retrofit is part of C11's scope — it wouldn't make sense to retrofit CRAG without HyDE motivating the change.

### Chapter sequencing rationale addition

- C11 after C7 and C10: **hard** — HyDE decorates CaseRetriever (C7) and retrofits CRAG's activation model (C10); both must exist first. The `RetrievalQuery` SPI change affects L6 and L7 which were established in C7, and L10 which was established in C10.

### Layer × Chapter matrix update

Add column C11:

| Layer | C11 |
|---|---|
| L6 RAG SPI | Med |
| L7 RAG Runtime | Med |
| L10 CRAG | Low |
| L11 HyDE | High |

### §9.1 Journey Overview update

| J4 Retrieval Quality | Self-healing and expanded retrieval — evaluate, filter, correct, and expand queries | C10, C11 | 🔲 pending |

## Behavioral changes

- **CRAG activation:** changes from classpath-only to classpath + `casehub.rag.crag.enabled=true`. Deployments with `casehub-rag-crag` on the classpath must add this config property to maintain CRAG activation. This is deliberate — prevents accidental activation from transitive dependencies.
- **CaseRetriever SPI:** `String query` → `RetrievalQuery query`. All consumers must migrate.

## Out of scope

- Multi-query HyDE (generate N hypothetical documents, merge results) — tracked as #42.
- Step-back prompting QueryExpander — tracked as #43.
- Benchmarking HyDE vs raw retrieval — tracked separately as part of RAG quality measurement.