# Knowledge Garden Platform Extraction — Design Spec

**Date:** 2026-09-10
**Issue:** casehubio/neocortex#304
**Cross-ref:** Hortora/engine#90
**Status:** Draft

---

## 1. Problem Statement

Hortora/engine's ~3.5K LOC is mostly generic RAG application logic — adaptive search, post-retrieval scoring, provenance tracking, collection migration, query augmentation, and federation. This code was built in engine first but isn't garden-specific. A second consumer (casehub issue resolution garden) is emerging, making this the right time to extract reusable capabilities into neocortex.

### 1.1 Current State

Engine sits on top of neocortex and adds six capability layers:

| Capability | Engine LOC | Generic? |
|---|---|---|
| Adaptive search (score floor, gap trim, overfetch) | ~570 | Yes |
| Post-retrieval scoring (temporal decay, version distance) | ~117 | Yes |
| Search profiles (BOM snapshots) | ~173 | Yes |
| Provenance tracking (document→decision lineage) | ~292 | Yes |
| Collection migration (dim/sparse/ColBERT checks) | ~186 | Partially |
| Query augmentation (LLM-generated search queries at ingest) | ~308 | Partially |
| Federation (recursive multi-garden search) | ~404 | Yes |
| MCP tools, deployment wiring, config | ~1,062 | No |

Neocortex already provides the foundational SPIs that engine builds on: `CaseRetriever`, `RetrievalTracker`, `MetadataExtractor`, `EmbeddingIngestor`, and the full decorator chain (CRAG, reranking, expansion, tracking, payload boost).

### 1.2 Goal

Extract platform-grade SPIs into neocortex. Move implementations that are genuinely generic. Leave domain-specific implementations in engine until a second consumer validates the SPI boundaries. Engine becomes a thin deployment artifact.

### 1.3 Design Principles

- **SPI first, impl second.** Define the right abstraction in rag-api. Leave the implementation in engine until a second consumer proves the SPI is correct.
- **Don't embed domain semantics.** Engine's ProvenanceStore has `issueRepo`, `issueNumber`, `specName` columns. The platform SPI must use generic parameters.
- **Respect existing patterns.** New modules follow the rag-* pattern (rag-api for SPI, separate module for impl). Decorators are for pipeline transformations, not routing.
- **Two feedback axes stay separate.** Retrieval relevance (was this the right document?) stays on `RetrievalTracker`. Execution outcome (did it work?) stays on `CbrCaseMemoryStore.recordOutcome()`. They score different things.

## 2. Architecture

### 2.1 New SPIs in rag-api

#### PostRetrievalScorer

Composable score multiplier applied after retrieval, before result presentation. Each scorer adjusts a chunk's relevance score based on metadata.

```java
package io.casehub.neocortex.rag;

public interface PostRetrievalScorer {
    double adjust(RetrievedChunk chunk, RetrievalQuery query, ScoringContext context);
}
```

`ScoringContext` carries per-query contextual data — information that varies by query and isn't available from the chunk or query alone:

```java
public record ScoringContext(
    Map<String, String> versionProfile
) {
    public static final ScoringContext EMPTY = new ScoringContext(Map.of());
}
```

`versionProfile` maps technology keys to their reference versions (e.g., `{"quarkus": "3.21", "java": "21"}`). Engine's `SearchProfileStore` (BOM snapshots) produces these.

Scorer *configuration* (decay factors, floors, topic weights) is constructor-injected, not carried per-query. `TemporalDecayScorer` reads `submittedDate`/`decayTier` from chunk metadata and uses system time — it ignores `ScoringContext`. `VersionScorer` reads `verifiedOn` from chunk metadata, reference versions from `context.versionProfile()`, query text from `query`, and its `Config(decayFactor, floor, defaultTopicWeight)` from constructor injection.

Scorers compose by multiplication — a chunk's final score is `baseScore * scorer1.adjust() * scorer2.adjust()`.

#### ProvenanceTracker

Document-to-action lineage tracking. Records which retrieved documents informed which downstream actions.

```java
package io.casehub.neocortex.rag;

public interface ProvenanceTracker {
    String record(String retrievalContext, String actionId, String actionType,
                  List<String> documentIds, String recordedBy);
    List<ProvenanceRecord> forwardLineage(String actionId, String actionType);
    List<ProvenanceRecord> reverseLineage(String documentId);
    ProvenanceStats stats(String retrievalContext);
    int purgeOlderThan(Instant cutoff);
}
```

Parameters are deliberately generic. Hortora maps: `retrievalContext` → issueRepo, `actionId` → issueNumber, `actionType` → "github-issue", `documentIds` → GE-IDs. A casehub issue garden would map: `actionId` → caseId, `actionType` → "case-resolution".

`ProvenanceRecord` is a record with `id`, `retrievalContext`, `actionId`, `actionType`, `documentId`, `recordedBy`, `timestamp`.

`ProvenanceStats` carries action-lineage aggregate statistics: total provenance records, unique documents referenced, unique actions, top-referenced documents, unreferenced count. This is distinct from `RetrievalAnalyzer.documentStats()` which tracks retrieval-level statistics (retrieval counts, scores, feedback distributions). ProvenanceStats answers "which documents have influenced downstream actions" — a lineage question. RetrievalAnalyzer answers "which documents are being retrieved" — a search quality question.

#### FederationStrategy

Multi-instance retrieval strategy. A single method encapsulates the full orchestration — topology discovery, remote querying, sufficiency checks, and result merging — because the orchestration IS the strategy. Different strategies differ in HOW they orchestrate (sequential vs parallel, short-circuit thresholds, tiered merge), not just which targets they query.

```java
package io.casehub.neocortex.rag;

public interface FederationStrategy {
    List<FederatedResult> federate(FederationQuery query, List<FederatedResult> localResults);
}
```

`FederationQuery` carries the query context:

```java
public record FederationQuery(
    String localId,
    String queryText,
    int maxResults,
    Set<String> visited,
    Map<String, List<String>> filterContext
) {}
```

- `visited` enables loop detection — each query carries the set of instance IDs already visited in the chain
- `filterContext` carries application-specific filter parameters (domains, type, tags for garden search) that the implementation passes through to remote instances
- `localResults` are the caller's already-processed local results, enabling the strategy to make sufficiency decisions

Federation operates at the REST/HTTP service-to-service level, NOT the CaseRetriever pipeline level. Federated results do NOT pass through the local decorator chain (CRAG, expansion, reranking, tracking) — each instance processes its own results independently. Merging happens after both local and remote results are fully processed.

`FederationTarget` carries the remote instance URL, ID, and relationship (upstream/peer). `FederatedResult` carries the result content, `adjustedScore` (already scored by the remote instance's own pipeline), source instance ID, and optional `metadata` (for audit/debugging, NOT for local re-scoring). Each instance runs its own scoring pipeline — the local instance does not re-score remote results. `AdaptiveFilter.filter()` operates on the merged set using each result's `adjustedScore`.

Engine's `ChainWalker` stays as the first `FederationStrategy` implementation: sequential upstream walk with relevance-threshold short-circuit + parallel peer fan-out (only when insufficient) + tiered merge + dedup.

#### DocumentQueryAugmenter

LLM-generated search queries appended to documents at ingest time (inverted HyDE pattern).

```java
package io.casehub.neocortex.rag;

public interface DocumentQueryAugmenter {
    Optional<List<String>> generateQueries(String title, String body, String path);
}
```

Distinct from `QueryExpander` which operates at query time. `DocumentQueryAugmenter` enriches documents BEFORE embedding so that the embedding captures the kinds of queries the document answers.

Returns `Optional.empty()` when augmentation is not applicable (too short, binary content, etc.). Returns `Optional.of(List.of())` when applicable but no queries generated.

### 2.2 New Modules

#### rag-scoring

PostRetrievalScorer implementations. Pure Java, zero external deps beyond rag-api.

| Class | What it does |
|---|---|
| `TemporalDecayScorer` | Exponential half-life decay by metadata tier. Constructor takes metadata key names (`dateKey`, `tierKey`) and tier→halflife mapping — no hardcoded garden field names. |
| `VersionScorer` | Version-distance decay. Constructor takes metadata key name (`versionKey`), version format, and `Config(decayFactor, floor, defaultTopicWeight)`. Major version miss penalizes more than minor. |
| `AdaptiveSearchWrapper` | Wraps CaseRetriever with overfetch, score floor, gap trim, and minimum results. Not a decorator — a utility that consumers call explicitly. Single-source only (no federation). |


`AdaptiveFilter` is a pure static utility in **rag-api** (not rag-scoring) alongside `AdaptiveSearchConfig` and `RetrievalAnalyzer`. Its only deps are rag-api types — placing it in rag-scoring would force unnecessary coupling for consumers that only need filtering (e.g., after federation merge).

Adaptive search is a separate concern from scoring. Scoring computes per-chunk adjustments; adaptive search applies cross-result thresholds (gap trim, score floor) that require seeing ALL results at once — this cannot be a CaseRetriever @Decorator because decorators intercept individual query/response flows while adaptive search needs the full scored result set to compute gaps and enforce minimums.

`AdaptiveSearchWrapper` is for **single-source** adaptive retrieval. It takes a `CaseRetriever`, a `List<PostRetrievalScorer>`, and an `AdaptiveSearchConfig`, then executes: retrieve (with overfetch) → score → floor → gap-trim → min-results guarantee. It does NOT handle federation — when federation is needed, the caller orchestrates federation externally and applies scoring + adaptive filtering to the merged result set.

The adaptive filtering step (floor → gap-trim → min-results) is also exposed as a standalone static utility (`AdaptiveFilter.filter(scored, requestedLimit, config)`) for consumers that retrieve and score results through other means (e.g., after federation merge).

`AdaptiveSearchConfig` record in rag-api:

```java
public record AdaptiveSearchConfig(
    double scoreFloor,
    double gapThreshold,
    int minResults,
    double overfetchMultiplier
) {
    public static AdaptiveSearchConfig defaults() {
        return new AdaptiveSearchConfig(0.3, 0.15, 3, 2.0);
    }
}
```

#### rag-query-augmentation

DocumentQueryAugmenter implementation via platform's `AgentProvider`. Depends on rag-api + casehub-platform-agent-api.

| Class | What it does |
|---|---|
| `AgentQueryAugmenter` | Uses AgentProvider to get a ChatModel, prompts it to generate search queries for a document. Provider-agnostic — Ollama, OpenAI, or any other provider is configuration. |
| `QueryAugmentingMetadataExtractor` | `MetadataExtractor` @Decorator that appends generated queries to document content before the base extractor processes it. |

### 2.3 Extensions to Existing Modules

#### rag-api

New SPIs listed in 2.1. New records:

- `AdaptiveSearchConfig` — score floor, gap threshold, min results, overfetch multiplier
- `ProvenanceRecord` — lineage record
- `ProvenanceStats` — action-lineage aggregates (total records, unique documents, unique actions, top-referenced, unreferenced count)
- `FederationTarget` — remote instance identity
- `FederatedResult` — result with source attribution
- `ScoringContext` — per-query scoring context (`versionProfile` map for version distance scoring; `EMPTY` constant for queries without version context)
- `FederationQuery` — federation query context (localId, queryText, maxResults, visited set, filterContext)

#### rag

`CollectionCompatibility` utility alongside `QdrantEmbeddingIngestor`:

```java
public class CollectionCompatibility {
    public static MigrationAction check(QdrantClient client, String collectionName,
                                         CollectionExpectedConfig expected) { ... }
}
```

`MigrationAction` is a sealed interface: `Compatible`, `DimensionMismatch(int actual, int expected)`, `MissingSparseVectors`, `MissingColBert`. The check is generic — what to DO about incompatibility (reindex, recreate, abort) is a deployment policy decision left to the caller.

Engine keeps its deployment-specific wiring: `GardenConfig` injection, `CorpusRef("hortora", ...)` construction, and cursor management policy.

#### memory-api — CBR Rename

| Current | New | What it is |
|---|---|---|
| `PlanCbrCase` | `ResolvedCase` | Historical execution trace with agent routing data |
| `TextualCbrCase` | `ResolutionGuide` | Prose resolution guidance (problem→solution pairs) |
| `PlanTrace` | `ResolutionStep` | Per-step execution record within a trace |

Unified under the `Resolution` concept. A case store holds `ResolvedCase` entries (what happened) and `ResolutionGuide` entries (what to do). Both are retrievable, rankable, and feedbackable.

**`FeatureVectorCbrCase` is intentionally excluded.** It's a structural variant — its name describes its representation format (feature vectors), not a domain concept. `PlanCbrCase` and `TextualCbrCase` are renamed because their "Plan"/"Textual" names are misleading about their domain purpose (historical resolution traces and resolution guidance respectively). `FeatureVectorCbrCase` accurately describes what it is — a case defined by numerical feature vectors — and has no misleading domain implication to fix.

**CBR_TYPE discriminator constants remain unchanged.** `ResolvedCase.CBR_TYPE` stays `"plan"` and `ResolutionGuide.CBR_TYPE` stays `"textual"`. These discriminators describe the storage format (execution-trace vs prose), not the domain concept name. Keeping them avoids data migration across Qdrant point payloads and JPA `CbrCaseEntity.caseType` columns. Existing persisted data remains readable without migration.

`CbrOutcome` gains an optional `retrievalId` field for correlation when lineage from retrieval to outcome is needed. This connects the execution outcome axis back to the retrieval that surfaced the case, without conflating the two feedback axes.

### 2.4 What Stays in Engine

| Component | Why it stays |
|---|---|
| `GardenMcpTools` (~652 LOC) | Application surface — MCP tool definitions with garden-specific descriptions. Thin delegation to platform services. |
| `GardenMetadataExtractor` (~145 LOC) | Implements neocortex `MetadataExtractor` SPI with garden-specific field names and GE-ID patterns. |
| `GardenOutcomeService` (~97 LOC) | JPA-coupled, uses garden-specific CASE_TYPE and tenant from GardenConfig. Bridges to `CbrCaseMemoryStore.recordOutcome()`. |
| `SearchProfileStore` (~118 LOC) | BOM snapshot store — stays until a second consumer needs profiles. |
| `ProvenanceStore` (~192 LOC) | Hortora-specific schema. Implements the new `ProvenanceTracker` SPI with domain parameter mapping. |
| `ChainWalker` (~184 LOC) | First `FederationStrategy` implementation. Stays until federation SPI is validated by a second consumer. |
| `ReconcileScheduler` (~29 LOC) | Thin delegation to existing neocortex `CorpusIngestionService`. |
| `GardenReindexService` (~42 LOC) | Thin delegation to existing neocortex `EmbeddingIngestor` + `CursorStore`. |
| Config, CDI producers (~215 LOC) | Deployment wiring. |

After extraction, engine's dependency on neocortex grows (new SPIs to implement), but engine's own code shrinks (scoring, migration checks, query augmentation SPI move out). Engine becomes: neocortex dep + config + MCP tools + federation + domain-specific SPI implementations.

## 3. Extraction Phases

Bottom-up: foundations before consumers. Each phase includes both sides — what enters neocortex AND what leaves engine.

### Phase 1 — Foundation SPIs

**Neocortex:**
- Add to rag-api: `PostRetrievalScorer`, `ScoringContext`, `ProvenanceTracker`, `ProvenanceRecord`, `ProvenanceStats`, `FederationStrategy`, `FederationQuery`, `FederationTarget`, `FederatedResult`, `DocumentQueryAugmenter`, `AdaptiveSearchConfig`
- Create rag-scoring: `TemporalDecayScorer`, `VersionScorer`
- Create rag-query-augmentation: `AgentQueryAugmenter`, `QueryAugmentingMetadataExtractor`

**Engine:**
- `TemporalDecayScorer` → delete, depend on rag-scoring
- `VersionScorer` → delete, depend on rag-scoring
- `ProvenanceStore` → implement `ProvenanceTracker` SPI, keep domain-specific schema
- `ChainWalker` → implement `FederationStrategy` SPI, keep REST-level federation
- `QueryAugmentingExtractor` → implement `DocumentQueryAugmenter`, delegate to `AgentQueryAugmenter` or keep Ollama impl

### Phase 2 — Search Infrastructure

**Neocortex:**
- Add to rag-scoring: `AdaptiveSearchWrapper`, `AdaptiveFilter`
- Add to rag: `CollectionCompatibility`, `MigrationAction`

**Engine:**
- `SearchResource` → use `AdaptiveSearchWrapper` from rag-scoring. Keep federation orchestration and MCP-specific result formatting.
- `CollectionMigration` → extract generic checks to `CollectionCompatibility`. Keep deployment policy (reindex on incompatibility, cursor reset).

### Phase 3 — CBR Rename

**Neocortex:**
- memory-api: `PlanCbrCase` → `ResolvedCase`, `TextualCbrCase` → `ResolutionGuide`, `PlanTrace` → `ResolutionStep`
- Add optional `retrievalId` to `CbrOutcome`
- Update all consumers across neocortex modules

**Engine:**
- Update all consumers of renamed types
- Update `GardenOutcomeService` to use new names

### Issue #304 Phase 3 Requirements Coverage

Issue #304 Phase 3 (Feedback pipeline) lists four items:

1. **Provenance tracking** — Addressed: `ProvenanceTracker` SPI in §2.1.
2. **Feedback context** — Deferred: this is an extension of `RetrievalTracker.feedback()` to carry additional context (what issue/project triggered the feedback). It's an enhancement to an existing SPI, not part of the extraction. Tracked as casehubio/neocortex#305.
3. **Staleness reports** — Already covered by existing platform: `RetrievalAnalyzer.qualitySignals()` produces `QualitySignal.STALE` for documents not retrieved within the staleness threshold, and `DocumentStats.lastRetrieved()` provides per-document freshness data. Version-specific content staleness (e.g., document verified against an old library version) would be a future capability building on `VersionScorer` — not in scope for this extraction.
4. **Two-axis feedback model** — Addressed: §5.

### Phase 4 — Engine Thinning

**Engine:**
- Strip any remaining extracted code
- Verify all MCP tools work against platform services
- Update engine's CLAUDE.md to reflect new architecture
- Verify Hortora/soredium skills continue working unchanged

## 4. Module Dependency Graph (Post-Extraction)

```
rag-api (SPIs: CaseRetriever, RetrievalTracker, MetadataExtractor,
         PostRetrievalScorer, ProvenanceTracker, FederationStrategy,
         DocumentQueryAugmenter, AdaptiveSearchConfig, AdaptiveFilter,
         ScoringContext, FederationQuery, FederatedResult, ...)
    ↑
    ├── rag-scoring (TemporalDecayScorer, VersionScorer, AdaptiveSearchWrapper)
    ├── rag-query-augmentation (AgentQueryAugmenter, QueryAugmentingMetadataExtractor)
    ├── rag-crossencoder (CRAG, reranking)
    ├── rag-expansion (HyDE, step-back, multi-query)
    ├── rag-tracking (retrieval event tracking, outcome storage)
    ├── rag (HybridCaseRetriever, QdrantEmbeddingIngestor, CollectionCompatibility)
    └── ... (existing modules unchanged)

memory-api (CbrCaseMemoryStore, ResolvedCase, ResolutionGuide,
            CbrOutcome with optional retrievalId, ...)
    ↑
    └── ... (existing memory modules unchanged)

Engine (deployment artifact)
    ├── depends on: rag-api, rag-scoring, rag-query-augmentation, memory-api, ...
    ├── implements: ProvenanceTracker, FederationStrategy, MetadataExtractor
    └── owns: MCP tools, config, CDI producers, deployment scripts
```

## 5. Two-Axis Feedback Model

Engine#90 identifies two feedback axes. The extraction preserves them as separate platform concepts:

**Axis 1 — Retrieval relevance:** "Was this the right document for this query?"
- SPI: `RetrievalTracker.feedback(retrievalId, docId, RetrievalOutcome)`
- Scope: scores the SEARCH, not the content
- Timing: immediately after retrieval or shortly after
- Location: rag-api / rag-tracking

**Axis 2 — Execution outcome:** "When we followed this guidance, did it work?"
- SPI: `CbrCaseMemoryStore.recordOutcome(caseId, tenantId, CbrOutcome)`
- Scope: scores the CONTENT, not the search
- Timing: much later, after execution
- Location: memory-api / memory backends
- Correlation: `CbrOutcome.retrievalId` (optional) links back to the retrieval event

Both feed back into future retrievals but through different paths: retrieval relevance adjusts ranking for similar queries (via `RetrievalAnalyzer`), execution outcome adjusts confidence on the case itself (via EMA in `CbrCaseMemoryStore`).

## 6. Migration Strategy

### Backward Compatibility

Engine must continue working during incremental extraction. Strategy:

1. **New neocortex SPIs are @DefaultBean no-ops.** Engine doesn't break if it doesn't implement them immediately. `PostRetrievalScorer` returns 1.0 (no adjustment), `FederationStrategy` returns empty (no federation), `DocumentQueryAugmenter` returns empty (no augmentation). `ProvenanceTracker` no-op logs a warning on `record()` calls as a safety net — in practice no data loss window exists because engine already has `ProvenanceStore` and will implement the SPI before the extraction deploys.
2. **Engine implements SPIs incrementally.** Each phase: add neocortex dep → implement SPI → delete extracted code → verify.
3. **CBR rename is a clean break.** Java records are implicitly final and cannot be aliased. All consumers update simultaneously in the same commit. This is a mechanical migration — the platform has no end users, so breaking changes cost nothing externally.

### Testing

Each phase includes:
- Contract tests for new SPIs in rag-testing / memory-testing
- Engine integration tests verify MCP tools still work
- Soredium skill tests verify garden tools still produce correct results

## 7. Test Strategy

| Module | What's tested |
|---|---|
| rag-api | SPI contracts (default methods, record validation) |
| rag-scoring | TemporalDecayScorer (half-life math, tier mapping), VersionScorer (version distance, major/minor, topic weight, ScoringContext.versionProfile), AdaptiveSearchWrapper (floor, gap, min results, overfetch), AdaptiveFilter (standalone filtering) |
| rag-query-augmentation | AgentQueryAugmenter (prompt construction, empty/null handling), QueryAugmentingMetadataExtractor (decorator composition) |
| rag | CollectionCompatibility (dimension mismatch, sparse check, ColBERT check) |
| memory-api | CBR rename compilation, CbrOutcome.retrievalId round-trip |
| memory-testing | Updated contract tests for renamed types |
| engine | Integration tests: MCP tools → platform SPIs → correct results |

## 8. Deferred Review Items

Three items were raised during decision review and consciously deferred:

1. **CBR rename scope creep risk** (R1-09): Bundling the rename with extraction increases scope. Accepted trade-off — the rename touches memory-api which is already being modified for CbrOutcome.retrievalId.

2. **Bottom-up ordering delays highest-value capability** (R1-10): Adaptive search (Phase 2) is the most impactful extraction but requires scoring SPIs (Phase 1) first. Accepted — foundations before consumers prevents rework.

3. **SPI proliferation with single consumer** (R1-12): Four new SPIs (PostRetrievalScorer, ProvenanceTracker, FederationStrategy, DocumentQueryAugmenter) each currently have one consumer. Accepted — pre-release platform, and the casehub issue resolution garden is the imminent second consumer.

## References

- casehubio/neocortex#304 — extraction issue (neocortex side)
- Hortora/engine#90 — extraction issue (engine side, detailed design context)
- `RetrievalTracker.java` (rag-api) — existing retrieval feedback SPI
- `CaseRetriever.java` (rag-api) — existing retrieval SPI
- `MetadataExtractor.java` (rag-api) — existing metadata extraction SPI
- `CbrCaseMemoryStore.java` (memory-api) — existing outcome recording
- `RetrievalAnalyzer.java` (rag-api) — retrieval analysis utilities
- Engine source survey (fork agent, 2026-09-10) — LOC breakdown and generic-vs-specific classification
- GE-20260513-4f26a7 — @DefaultBean + @ApplicationScoped CDI layer displacement
- casehub/garden/docs/protocols/universal/spi-adapter-module-placement.md — SPI adapters start in host repo
- casehub/garden/docs/protocols/universal/cdi-classpath-presence-requires-module-separation.md — classpath activation pattern
- casehub/garden/docs/protocols/universal/module-tier-structure.md — three-tier module structure
