# ColBERT Relevance Evaluator + Correlation Graph Design

**Issues:** #62, #167
**Date:** 2026-07-26
**Status:** Draft

---

## 1. ColBERT Relevance Evaluator (#62)

### Problem

`CorrectiveCaseRetriever` (CRAG decorator) uses `RelevanceEvaluator` to grade
retrieved chunks. The only production implementation is
`CrossEncoderRelevanceEvaluator`, which runs ONNX inference on (query, content)
pairs. Post-BGE-M3 migration, cross-encoder models are removed — CRAG has no
evaluator and fails at startup.

ColBERT MAX_SIM scores are already computed during retrieval (Qdrant outer
query in `HybridCaseRetriever`) and live on `RetrievedChunk.relevanceScore`.
These scores are a strong relevance signal — but the `RelevanceEvaluator`
interface strips them, passing only text to the evaluator.

The `instanceof CrossEncoderRelevanceEvaluator` check in
`CorrectiveCaseRetriever` is a polymorphism violation that would compound if
a second `instanceof` were added for ColBERT.

### Design

#### 1.1 Refactor `RelevanceEvaluator` interface

Collapse to a single chunk-aware method. Every implementation receives full
chunk context — scores, metadata, content — and uses what it needs.

```java
// rag-api: RelevanceEvaluator.java
public interface RelevanceEvaluator {
    List<ScoredGrade> evaluateChunks(String query, List<RetrievedChunk> chunks);
}
```

This removes the text-only `evaluate()` and `evaluateBatch()` methods.
Each implementation extracts what it needs from chunks:

- **Cross-encoder:** extracts `content()`, runs ONNX inference, returns scored grades
- **ColBERT:** reads `relevanceScore()`, applies thresholds, returns scored grades
- **InMemoryRelevanceEvaluator** (test double): returns fixed grade with NaN score

No default implementation. No LSP violation — every implementation fulfils
the full contract. The NaN sentinel is an implementation detail of the test
double, not a contract-level concern.

#### 1.2 Move `ScoredGrade` to `rag-api`

`ScoredGrade(RelevanceGrade grade, float score)` is currently in
`rag-crossencoder` (`io.casehub.neocortex.rag.crossencoder`). It is a
general value type — grade plus numeric score — not cross-encoder-specific.

Move to `io.casehub.neocortex.rag` in `rag-api`. Update imports in
`rag-crossencoder`.

#### 1.3 `ColBertRelevanceEvaluator`

New class in `rag-api` module, `io.casehub.neocortex.rag` package (alongside
`ScoredGrade` and `RelevanceEvaluator`). Pure Java, zero framework
dependencies — a score-threshold mapper that fits rag-api's tier 1 profile.

```java
public final class ColBertRelevanceEvaluator implements RelevanceEvaluator {

    private final double correctThreshold;
    private final double incorrectThreshold;

    // Thresholds validated: incorrectThreshold <= correctThreshold

    @Override
    public List<ScoredGrade> evaluateChunks(String query,
                                             List<RetrievedChunk> chunks) {
        return chunks.stream()
            .map(c -> new ScoredGrade(
                gradeFromScore(c.relevanceScore()),
                (float) c.relevanceScore()))
            .toList();
    }

    private RelevanceGrade gradeFromScore(double score) {
        if (score >= correctThreshold)   return RelevanceGrade.CORRECT;
        if (score <= incorrectThreshold) return RelevanceGrade.INCORRECT;
        return RelevanceGrade.AMBIGUOUS;
    }
}
```

Zero inference calls. Reads `relevanceScore` directly from chunks —
which IS the ColBERT MAX_SIM score when ColBERT reranking is active
in `HybridCaseRetriever`.

#### 1.4 Refactor `CrossEncoderRelevanceEvaluator`

Implement `evaluateChunks()` — the sole method on the interface. Extracts
text content from chunks, runs cross-encoder inference, returns scored
grades. The old `evaluateBatchWithScores()` method is removed —
`evaluateChunks()` is the polymorphic entry point.

```java
@Override
public List<ScoredGrade> evaluateChunks(String query,
                                         List<RetrievedChunk> chunks) {
    if (chunks.isEmpty()) return List.of();
    List<String> contents = chunks.stream()
        .map(RetrievedChunk::content).toList();
    List<RankedResult> ranked = reranker.rerank(query, contents);
    ScoredGrade[] results = new ScoredGrade[contents.size()];
    for (RankedResult r : ranked) {
        results[r.originalIndex()] = new ScoredGrade(
            gradeFromScore(r.score()), r.score());
    }
    return List.of(results);
}
```

#### 1.5 Refactor `CorrectiveCaseRetriever`

Eliminate the `instanceof CrossEncoderRelevanceEvaluator` check. Always
call `evaluateChunks()`:

```java
var scored = evaluator.evaluateChunks(query.text(), chunks);
grades = scored.stream().map(ScoredGrade::grade).toList();

List<RetrievedChunk> gradedInput = chunks;
if (hasUsableScores(scored)) {
    float[] scores = extractScores(scored);
    gradedInput = RerankingLogic.attachScores(chunks, scores);
}
```

`hasUsableScores()` checks for non-NaN scores. Production evaluators
(cross-encoder and ColBERT) always return real scores. The test double
(`InMemoryRelevanceEvaluator`) returns NaN, signalling "no reranking
scores available."

Same refactor applies to the expansion path (second evaluation pass).

**Reactive variant:** `ReactiveCorrectiveCaseRetriever` is referenced in
ARC42STORIES (L1075, L1092) but does not exist in the codebase — confirmed
via `ide_find_class`. The blocking-to-reactive bridge handles reactive CRAG.
The refactoring scope is `CorrectiveCaseRetriever` only. ARC42STORIES should
be updated to remove the stale reference (tracked as a separate concern).

#### 1.6 CDI wiring

`CrossEncoderBeanProducer` currently throws when no `CrossEncoderReranker`
is available — a hard startup failure in post-BGE-M3 deployments.

Fix: single producer with fallback logic, validated against ColBERT
reranking configuration. The producer injects `casehub.rag.retrieval.rerank-enabled`
via MicroProfile `@ConfigProperty` (no compile dependency on `RagConfig`
from the `rag` runtime module):

```java
@ApplicationScoped
public class CrossEncoderBeanProducer {

    private static final Logger LOG =
        Logger.getLogger(CrossEncoderBeanProducer.class);

    @Inject CragConfig config;
    @Inject Instance<CrossEncoderReranker> rerankerInstance;

    @ConfigProperty(name = "casehub.rag.retrieval.rerank-enabled",
                    defaultValue = "false")
    boolean rerankEnabled;

    @Produces
    @ApplicationScoped
    RelevanceEvaluator evaluator() {
        if (rerankerInstance.isResolvable()) {
            return new CrossEncoderRelevanceEvaluator(
                rerankerInstance.get(),
                config.correctThreshold(),
                config.incorrectThreshold());
        }
        if (!rerankEnabled) {
            throw new IllegalStateException(
                "No CrossEncoderReranker available and ColBERT reranking "
                + "is not enabled (casehub.rag.retrieval.rerank-enabled"
                + "=false). Configure a cross-encoder model or enable "
                + "ColBERT reranking.");
        }
        LOG.infof("No CrossEncoderReranker — using ColBertRelevanceEvaluator "
            + "(correct >= %.2f, incorrect <= %.2f)",
            config.colbert().correctThreshold(),
            config.colbert().incorrectThreshold());
        return new ColBertRelevanceEvaluator(
            config.colbert().correctThreshold(),
            config.colbert().incorrectThreshold());
    }
}
```

No new producer class. One producer, validated fallback.

**Activation matrix:**

| Cross-encoder available | `rerank-enabled` | Evaluator used |
|------------------------|-----------------|----------------|
| Yes | any | `CrossEncoderRelevanceEvaluator` |
| No | true | `ColBertRelevanceEvaluator` |
| No | false | Startup failure (descriptive error) |

**Note:** ColBERT reranking in `HybridCaseRetriever` has additional
runtime conditions beyond `rerank-enabled` (embedder supports ColBERT
mode, ColBERT embeddings non-null, fusion strategy is not CC). The
`rerank-enabled` check is a necessary but not fully sufficient guard.
Edge cases where `rerank-enabled=true` but ColBERT reranking doesn't
activate (e.g., CC fusion strategy) represent pre-existing config
contradictions in `HybridCaseRetriever` and are outside the scope of
this spec.

#### 1.7 Threshold configuration

Separate config keys per evaluator with appropriate defaults. ColBERT
MAX_SIM scores (typically 0.3–0.8) require different thresholds than
cross-encoder scores (0.0–1.0).

```java
@ConfigMapping(prefix = "casehub.rag.crag")
public interface CragConfig {

    @WithDefault("0.7")
    double correctThreshold();          // cross-encoder

    @WithDefault("0.3")
    double incorrectThreshold();        // cross-encoder

    @WithDefault("3")
    int expansionMultiplier();

    @WithDefault("false")
    boolean enabled();

    ColBertConfig colbert();

    interface ColBertConfig {
        @WithDefault("0.55")
        double correctThreshold();      // ColBERT MAX_SIM

        @WithDefault("0.35")
        double incorrectThreshold();    // ColBERT MAX_SIM
    }
}
```

Config keys:

| Evaluator | Correct threshold | Incorrect threshold |
|-----------|------------------|-------------------|
| Cross-encoder | `casehub.rag.crag.correct-threshold` (0.7) | `casehub.rag.crag.incorrect-threshold` (0.3) |
| ColBERT | `casehub.rag.crag.colbert.correct-threshold` (0.55) | `casehub.rag.crag.colbert.incorrect-threshold` (0.35) |

The producer selects the appropriate thresholds based on which evaluator
is constructed (§1.6). No shared defaults across incompatible score spaces.

---

## 2. Query-Document-Outcome Correlation Graph (#167)

### Problem

`RetrievalAnalyzer` provides document-level stats (`documentStats`) and
query-level quality signals (`lowRelevanceQueries`, `zeroHitQueries`,
`queryFrequency`). These are independent views. There is no structure that
joins queries ↔ documents ↔ outcomes to answer:

- Which specific documents make a query perform poorly?
- Which queries retrieve the same document set (and should be deduplicated)?
- Which documents are central to many queries (high impact if removed/degraded)?

### Design

#### 2.1 Value types (all in `rag-api`)

```java
// The bipartite graph: queries on one side, documents on the other
// Query keys are normalized: query.text().strip().toLowerCase()
public record CorrelationGraph(
    Map<String, QueryNode> queries,
    Map<String, DocumentNode> documents
) { /* defensive copies, non-null validation */ }

// Query-side node
public record QueryNode(
    String queryText,                          // normalized
    int retrievalCount,
    Map<String, EdgeStats> documentEdges       // documentId → stats
) { /* validation */ }

// Document-side node
public record DocumentNode(
    String documentId,
    int retrievalCount,
    Map<String, EdgeStats> queryEdges          // normalized queryText → stats
) { /* validation */ }

// Edge between a query and a document
public record EdgeStats(
    int coOccurrenceCount,
    double averageScore,
    Map<RetrievalOutcome, Integer> outcomeDistribution
) { /* validation, defensive copy on outcomeDistribution */ }
```

#### 2.2 Analysis result types

```java
// Queries that retrieve overlapping document sets
public record QueryCluster(
    Set<String> queryTexts,
    double jaccardSimilarity,
    Set<String> sharedDocumentIds
) { /* validation: queryTexts.size() >= 2, similarity in [0,1] */ }

// Document centrality and outcome quality
public record DocumentImpact(
    String documentId,
    int distinctQueryCount,
    int totalRetrievals,
    double averageScore,
    Map<RetrievalOutcome, Integer> aggregateOutcomes
) { /* validation */ }
```

#### 2.3 Builder and analysis methods on `RetrievalAnalyzer`

```java
// Build the graph by joining records with feedback
public static CorrelationGraph correlationGraph(
    RetrievalTracker tracker, CorpusRef corpus,
    Instant since, Instant until);

// Group queries by shared document sets (Jaccard similarity).
// jaccardThreshold controls edge construction in the similarity graph —
// it is NOT a floor on returned cluster similarity (see §2.5).
public static List<QueryCluster> queryClusters(
    CorrelationGraph graph, double jaccardThreshold);

// Rank documents by centrality and outcome quality
public static List<DocumentImpact> documentImpact(
    CorrelationGraph graph);
```

#### 2.4 `correlationGraph()` algorithm

1. Fetch all `RetrievalRecord` for the corpus and time window
2. Fetch all `RetrievalFeedback` for the same window
3. Index feedback by `(retrievalId, sourceDocumentId)` →
   `List<RetrievalOutcome>` (multi-valued — multiple feedback entries
   for the same retrieval+document pair are all preserved, not last-wins)
4. For each record, derive the query key via
   `record.query().text().strip().toLowerCase()` (original user query,
   not `searchText()` which includes expansion artifacts).
   For each document in the record's results:
   - Increment the query→document edge count
   - Accumulate the document's relevance score
   - Look up feedback list (if any) and accumulate ALL outcomes into
     the edge's outcome distribution
5. Build dual-indexed `QueryNode` and `DocumentNode` maps

Pure computation. O(R×D + F) where R = records, D = avg documents per
record, F = feedback entries.

#### 2.5 `queryClusters()` algorithm

Single-linkage clustering via connected components:

1. For each query node, compute its document set (keys of `documentEdges`)
2. Build a similarity graph: for each pair of queries, compute Jaccard
   similarity `|docsA ∩ docsB| / |docsA ∪ docsB|`. Add an edge if
   similarity ≥ threshold
3. Find connected components — each component with ≥ 2 nodes is a cluster
4. For each cluster:
   - `queryTexts`: all queries in the component
   - `jaccardSimilarity`: minimum pairwise Jaccard among all pairs in the
     cluster (conservative — the weakest link)
   - `sharedDocumentIds`: intersection of ALL members' document sets
     (may be empty for large clusters even when pairwise intersections
     are non-empty)

O(Q²) where Q = distinct queries. For large Q, could optimise with
MinHash — but for the expected corpus sizes (hundreds to low thousands
of distinct queries), brute-force Jaccard is fine.

Return clusters sorted by minimum similarity (highest first).

**Note on returned similarity:** Single-linkage clustering connects
queries transitively — pairs that are not directly similar join the same
cluster through intermediate queries. The returned `jaccardSimilarity`
(minimum pairwise Jaccard) can therefore be below the `jaccardThreshold`
used for edge construction. Consumers needing a stricter guarantee should
filter returned clusters by `jaccardSimilarity >= desired_floor`.

#### 2.6 `documentImpact()` algorithm

For each document node in the graph:
1. Count distinct queries that retrieve it (`queryEdges.size()`)
2. Sum total retrievals across all queries
3. Compute average score across all edges
4. Aggregate outcome distributions across all edges

Return sorted by `distinctQueryCount` descending (most central first).

---

## 3. Module Impact

| Module | Changes |
|--------|---------|
| `rag-api` | `ScoredGrade` moved here; `RelevanceEvaluator` refactored to single `evaluateChunks()` method (removes `evaluate()` and `evaluateBatch()`); `ColBertRelevanceEvaluator` (new, pure Java); new value types: `CorrelationGraph`, `QueryNode`, `DocumentNode`, `EdgeStats`, `QueryCluster`, `DocumentImpact`; new methods on `RetrievalAnalyzer` |
| `rag-crossencoder` | `CrossEncoderBeanProducer` refactored with ColBERT fallback + `rerank-enabled` validation; `CrossEncoderRelevanceEvaluator` refactored to implement `evaluateChunks()`; `CorrectiveCaseRetriever` refactored to eliminate `instanceof`; `CragConfig` extended with `ColBertConfig` sub-group; `ScoredGrade` import updated |
| `rag-testing` | `InMemoryRelevanceEvaluator` refactored to implement `evaluateChunks()` (returns fixed grade with NaN score) |

No new modules. No new dependencies.

---

## 4. Testing Strategy

### #62 tests

- `ColBertRelevanceEvaluatorTest`: threshold mapping (CORRECT/AMBIGUOUS/INCORRECT boundaries), edge cases (exact threshold, zero, negative scores)
- `CrossEncoderRelevanceEvaluatorTest`: verify `evaluateChunks()` produces same results as old `evaluateBatchWithScores()`
- `CorrectiveCaseRetrieverTest`: existing tests pass with refactored code; new test verifying ColBERT evaluator integration (score-based grading flows through CRAG expansion correctly)
- `CrossEncoderBeanProducerTest`: validates activation matrix — cross-encoder preferred when available; ColBERT fallback when `rerank-enabled=true`; startup failure when neither available
- `InMemoryRelevanceEvaluatorTest`: returns fixed grade via `evaluateChunks()`; scores are NaN

### #167 tests

- `CorrelationGraphTest`: builder produces correct graph from known records + feedback; empty inputs; single query; single document; query text normalization (case, whitespace); multiple feedback entries for same (retrievalId, docId)
- `QueryClusterTest`: known overlapping queries cluster; disjoint queries don't cluster; threshold boundary; transitive clustering (A↔B, B↔C → {A,B,C}); cluster-level Jaccard is minimum pairwise
- `DocumentImpactTest`: centrality ranking; outcome aggregation; single-query documents
- `RetrievalAnalyzerCorrelationTest`: integration — end-to-end from `InMemoryRetrievalTracker` data through graph building to analysis results

---

## 5. Out of Scope

- **ColBERT MAX_SIM threshold auto-calibration** — manual config for now (GitHub issue to be filed)
- **Graph persistence** — computed on demand from tracker data (deliberate design decision: the tracker is the source of truth)
- **Reactive variants of correlation analysis** — blocking-only, consistent with existing `RetrievalAnalyzer` pattern (deliberate design decision)
- **MinHash optimisation for query clustering** — brute-force Jaccard sufficient at expected scale (GitHub issue to be filed for future optimisation)
- **Visualization / rendering of the correlation graph** (GitHub issue to be filed)
- **ARC42STORIES update** — remove stale `ReactiveCorrectiveCaseRetriever` references from L1075 and L1092 (GitHub issue to be filed)
