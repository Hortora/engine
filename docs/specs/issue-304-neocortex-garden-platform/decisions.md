# Decisions — #304 Extract hortora/engine capabilities into neocortex

## D1: CBR naming cleanup scope

**Choice:** Include CBR naming cleanup in this design
**Alternatives:**
- Separate issue — orthogonal rename, reduces spec scope
- Defer entirely — only rename when a consumer is confused
**Rationale:** Doing the rename during extraction ensures naming consistency from the start. The rename touches memory-api which is already being modified for the outcome SPI.
**Trade-offs:** Larger spec scope
**Sources:** Hortora/engine#90 (CBR naming cleanup section)
**Exploration:** quick
**Status:** captured

## D2: Federation extraction

**Choice:** Define FederationStrategy SPI in rag-api (topology discovery, remote query, result merge). ChainWalker stays in engine as the Hortora-specific implementation. Full module extraction deferred until a second consumer validates the SPI.
**Alternatives:**
- Move federation to neocortex as a platform capability (new rag-federation module) — original choice, rejected: ChainWalker operates at REST/HTTP layer via RemoteGardenClient, not at the CaseRetriever SPI layer. Premature extraction of topology-specific code.
- Defer entirely — leave in engine until a second consumer appears, no SPI — too conservative: defining the SPI boundary now is forward-looking design without premature code movement.
- Extract as-is — move code unchanged without SPI design
**Rationale:** Engine#90 says "Federation — hortora topology-specific (could become an SPI with pluggable strategy)." ChainWalker uses RemoteGardenClient.search() (JAX-RS HTTP) returning SearchResult (engine type), not CaseRetriever.retrieve(). Federation operates at the REST layer, not the retrieval pipeline layer. Defining the SPI boundary now documents the right abstraction; leaving the implementation in engine respects single-consumer reality.
**Trade-offs:** SPI design without implementation risks wrong abstraction; mitigated by the existing ChainWalker as concrete reference
**Sources:** engine ChainWalker.java (~184 LOC), RemoteGardenClient.java (JAX-RS interface), engine#90 (federation stays in engine), ADR-0001 (garden-level HTTP federation)
**Exploration:** quick
**Status:** revised — R1-02: deferred full extraction, SPI-only approach per engine#90 guidance

## D3: Outcome service as SPI

**Choice:** Outcome feedback stays on CbrCaseMemoryStore.recordOutcome() where it already lives. RetrievalTracker retains only retrieval relevance feedback (RetrievalOutcome enum: NOT_RELEVANT through HIGHLY_RELEVANT). Add optional retrievalId field to CbrOutcome for correlation when lineage from retrieval to outcome is needed.
**Alternatives:**
- Extend RetrievalTracker with outcome axis — original choice, rejected: conflates search quality (retrieval relevance) with content quality (execution outcome). Different lifecycles, different data models. RetrievalOutcome is a retrieval-time enum; CbrOutcome carries successRate + detail + observedAt at a much later time.
- New OutcomeTracker SPI — separate from both RetrievalTracker and CbrCaseMemoryStore. Rejected: CbrCaseMemoryStore.recordOutcome() already exists and is the right location.
- Unify in RetrievalFeedback — extend the record to carry optional OutcomeResult
**Rationale:** Engine#90 explicitly says "Two feedback axes preserved — retrieval relevance and execution outcome stay separate platform concepts." GardenOutcomeService already uses CbrCaseMemoryStore.recordOutcome() with CbrOutcome (successRate, detail, observedAt) — this is the right abstraction. Retrieval relevance (was the right document found?) and execution outcome (did following the guidance work?) are feedback on different things: the SEARCH vs the CONTENT.
**Trade-offs:** Correlation between retrieval and outcome requires explicit retrievalId threading rather than implicit co-location
**Sources:** RetrievalTracker.java (rag-api), CbrCaseMemoryStore.recordOutcome() (memory-api), GardenOutcomeService.java (engine), engine#90 (two-axis feedback model)
**Exploration:** quick
**Depends on:** D1 (CBR naming — outcome wiring touches the same area)
**Status:** revised — R1-03: outcome stays on CBR, not RetrievalTracker

## D4: Extraction ordering

**Choice:** Bottom-up SPIs first (scoring/outcome/provenance → adaptive search/migration → federation)
**Alternatives:**
- Highest-value first — adaptive search first, rework if SPI boundaries shift
- Match issue phases — follow #304's 5-phase structure as written
**Rationale:** Foundations must exist before consumers. Each layer can be consumed by engine incrementally. No rework when later phases build on earlier SPIs.
**Trade-offs:** Highest-value capability (adaptive search) isn't delivered until Phase 2
**Sources:** neocortex module dependency graph, engine source survey
**Exploration:** quick
**Status:** captured

## D5: Post-retrieval scoring module placement

**Choice:** PostRetrievalScorer SPI in rag-api with generic contract: `double adjust(RetrievedChunk chunk, RetrievalQuery query, ScoringContext context)`. Temporal decay (configurable half-life by metadata tier) and version scoring (configurable version-distance by BOM) as parameterized impls in rag-scoring. Adaptive search as separate concern (CaseRetriever decorator or utility, not in rag-scoring). Search profiles stay in engine. QueryGenerator moved to D8.
**Alternatives:**
- All in rag-scoring (original choice) — rejected: bundles four distinct capabilities (scoring, adaptive search, query generation, search profiles) with different lifecycles and consumers. Violates the single-responsibility pattern established by existing rag-* modules.
- All in rag-api — fewer modules but mixes SPI definitions with implementations
- Impls stay in engine, SPI only in rag-api — conservative but temporal decay (half-life with configurable parameters) and version scoring (version-distance with configurable format) are generic platform capabilities, not Hortora-specific. The Hortora-specific parts are the metadata field names and tier assignments, which are configuration.
**Rationale:** TemporalDecayScorer.score(submittedDate, decayTier) uses a half-life formula parameterized by tier→halflife mapping — generic math, not domain logic. VersionScorer.score(verifiedOn, bom, queryText, config) computes version distance with configurable decay — generic pattern for any multi-version corpus. The field names (submitted, decay_tier, verified_on) and tier semantics are engine-level configuration, not embedded in the scorer implementations. Adaptive search (score floor, gap trim, min results) is a separate concern from scoring — it operates on scored results rather than computing scores.
**Trade-offs:** rag-scoring is smaller (scoring only), but adaptive search needs its own home
**Sources:** TemporalDecayScorer.java (~44 LOC, generic half-life), VersionScorer.java (~52 LOC, generic version distance), rag-crossencoder/rag-expansion/rag-tracking (existing module-per-concern pattern)
**Exploration:** quick
**Status:** revised — R1-04: unbundled concerns, removed QueryGenerator/adaptive/profiles from scope

## D6: Provenance module placement

**Choice:** ProvenanceTracker SPI in rag-api with generic action/document references: `record(String retrievalContext, String actionId, String actionType, List<String> documentIds, String recordedBy)`. Hortora implementation maps actionId→issueNumber, actionType→"github-issue", and carries issueRepo as retrievalContext. SQLite impl stays in engine until a second consumer validates the SPI.
**Alternatives:**
- New rag-provenance module with direct port (original choice) — rejected: engine ProvenanceStore.record(issueRepo, issueNumber, specName, geIds, recordedBy) embeds domain-specific parameters (issueRepo, issueNumber, specName). Direct port would embed Hortora/GitHub semantics in a platform module.
- Extend rag-tracking — share the tracking concept but different query patterns
- SPI only in rag-api, no module — minimal extraction, engine keeps its impl
**Rationale:** The underlying concept (which retrieved documents informed which downstream actions) IS a platform capability. But the current ProvenanceStore schema is Hortora-specific: issue_repo, issue_number, ge_id, spec_name columns. A platform SPI must abstract away the domain — generic action references instead of GitHub-specific parameters. Forward/reverse lineage queries become generic: forwardLineage(actionId, actionType) and reverseLineage(documentId). Hortora maps these to its domain.
**Trade-offs:** Generic SPI may not perfectly capture the domain-specific query patterns; implementation stays in engine until validated
**Sources:** engine ProvenanceStore.java (~192 LOC, domain-specific schema), rag-tracking pattern
**Exploration:** quick
**Status:** revised — R1-05: acknowledged domain-specificity, defined generic SPI shape

## D7: Federation module placement

**Choice:** Federation is NOT a CaseRetriever @Decorator. If extracted per D2, federation uses a FederationStrategy SPI that defines topology discovery, remote query, and result merge as first-class operations — not as a CaseRetriever pipeline transformation. ChainWalker stays in engine as the first FederationStrategy implementation.
**Alternatives:**
- CaseRetriever @Decorator (original choice) — rejected for three reasons: (1) Existing decorators (CRAG, reranking, expansion, tracking) transform the retrieval pipeline — they modify queries or post-process results from the SAME data path. Federation routes to DIFFERENT data sources via HTTP — this is routing, not decoration. (2) CaseRetriever is corpus-scoped (CorpusRef), federation is topology-scoped (garden IDs, URLs, visited sets). A FederatedRetriever decorator would need to ignore the CorpusRef it receives. (3) Federation should NOT go through the full local decorator chain — remote results arrive already scored by their own pipeline.
- Inside rag module — alongside HybridCaseRetriever, less modular
- Separate federation-api + federation — full SPI split, maximum extensibility
**Rationale:** ChainWalker.walk() operates at the REST layer: RemoteGardenClient.search() returns SearchResult (engine type), not RetrievedChunk (platform type). The chain walk algorithm (sequential upstream + parallel peer fan-out + tiered merge + dedup + relevance-threshold short-circuit) is fundamentally different from the decorator pattern (intercept, modify, delegate). Making federation a CaseRetriever decorator would disguise a routing/merge operation as a pipeline transformation.
**Trade-offs:** FederationStrategy SPI is a new abstraction pattern (not a decorator), requiring its own testing contract
**Sources:** ChainWalker.java (REST-level federation), RemoteGardenClient.java (JAX-RS client returning SearchResult), CaseRetriever decorator chain (pipeline transformations)
**Exploration:** quick
**Status:** revised — R1-01: federation is routing, not decoration; FederationStrategy SPI instead

## D8: Query augmentation approach

**Choice:** DocumentQueryAugmenter SPI in rag-api (ingestion-time augmentation), distinct from QueryExpander SPI (query-time expansion). Co-located with MetadataExtractor in the ingestion namespace, not alongside QueryExpander. Impl using AgentProvider in own module (rag-query-augmentation), not in rag-scoring.
**Alternatives:**
- QueryGenerator SPI in rag-api alongside QueryExpander (original choice) — rejected: conflates ingestion-time and query-time augmentation. QueryExpander operates at search time (expand(RetrievalQuery) → List<RetrievalQuery>). QueryGenerator operates at ingestion time (generate(title, body) → Optional<List<String>> appended to document before embedding). Different lifecycle stages, different inputs, different outputs.
- SPI only, defer impl — let engine keep its Ollama version
- Separate rag-query-augmentation module — own module for classpath activation
**Rationale:** Engine's QueryGenerator.generate(title, body, entryPath) is used by QueryAugmentingExtractor — a MetadataExtractor @Decorator that appends synthetic queries to documents BEFORE embedding (inverted HyDE). This is fundamentally different from QueryExpander which operates at QUERY time. Naming must make the lifecycle stage obvious: "DocumentQueryAugmenter" signals ingestion-time augmentation. Separate module avoids rag-scoring gaining a casehub-platform-agent-api dependency.
**Trade-offs:** One more module; naming must be carefully chosen to distinguish from QueryExpander
**Sources:** engine QueryGenerator.java (ingestion-time interface), QueryAugmentingExtractor.java (MetadataExtractor @Decorator), QueryExpander.java (rag-api, query-time)
**Exploration:** quick
**Status:** revised — R1-06: renamed to distinguish lifecycle stage, separated from rag-scoring

## D9: Collection migration placement

**Choice:** Extract generic migration checks (dimension mismatch, sparse vector check, ColBERT config check) as a utility in the rag module alongside QdrantEmbeddingIngestor. Engine keeps the deployment-specific wiring: GardenConfig injection, CorpusRef("hortora", gardenConfig.id()) construction, and cursor management policy (reset corpus + clear cursor on incompatibility).
**Alternatives:**
- Move CollectionMigration as-is (original choice implied) — rejected: CollectionMigration injects GardenConfig, constructs CorpusRef("hortora", gardenConfig.id()), and uses GardenConfig.id() for cursor management. The generic checks (extractDenseDimension, hasColbertConfig, hasSparseVectorsConfig) are extractable; the wiring is deployment-specific.
- New rag-migration module — clean separation but small (~186 LOC)
- Defer — leave in engine
**Rationale:** Collection migration checks (dimension check, sparse vector check, ColBERT config check) are generic Qdrant collection compatibility checks. But the POLICY (what to do when incompatible — reset corpus, clear cursor, re-index) is deployment-specific. Clean separation: rag module provides CollectionCompatibility.check(QdrantClient, collectionName, expectedConfig) → MigrationAction. Engine applies the action per its deployment policy.
**Trade-offs:** rag module grows slightly; engine still owns policy decisions
**Sources:** engine CollectionMigration.java (~186 LOC), neocortex rag/QdrantEmbeddingIngestor
**Exploration:** quick
**Status:** revised — R1-11: separated generic checks from deployment-specific wiring

## D10: CBR naming

**Choice:** PlanCbrCase → ResolvedCase, TextualCbrCase → ResolutionGuide, unified under "Resolution" concept
**Alternatives:**
- PlanCbrCase → ExecutionTrace or TracedCase — emphasizes the trace/history nature rather than implying conclusion. Addresses concern that "Resolved" contradicts cases with outcome=null.
- TextualCbrCase → ProseSolution or TextualResolution — emphasizes the solution nature rather than implying prescriptive guidance. Addresses concern that "Guide" implies how-to rather than past record.
- Keep current names — avoid breaking rename. Rejected: names are actively misleading (parent issue documents the confusion).
**Rationale:** The parent issue (engine#90) proposes "Resolution" as the unifying concept. "Resolved" describes the type's PURPOSE (recording a resolution), not its current STATE — a case under review is still a ResolvedCase type even if outcome hasn't been recorded yet, just as a "TrackedShipment" is the type name even when the shipment hasn't arrived. "ResolutionGuide" captures that TextualCbrCase is prescriptive prose guidance (problem→solution pairs retrieved by similarity), not just passive text. However, the reviewer's alternatives deserve testing against actual usage sites before finalizing.
**Trade-offs:** Breaking rename across memory-api consumers (neocortex + engine)
**Sources:** Hortora/engine#90 (CBR naming cleanup section)
**Exploration:** quick
**Depends on:** D1 (included in this design)
**Status:** revised — R1-07: added explicit naming alternatives for evaluation

## D12: Federation architecture level

**Choice:** Federation stays at REST/HTTP level (service-to-service), not retriever-pipeline level. Federated retrieval does NOT go through the local decorator chain (CRAG, expansion, reranking, tracking). Remote results arrive already processed by their own pipeline.
**Alternatives:**
- Retriever-pipeline level — federation as CaseRetriever @Decorator, remote results pass through local CRAG/expansion/reranking/tracking. Rejected: remote results are already scored, graded, and expanded by their source pipeline. Double-processing wastes compute and may corrupt scores.
- Hybrid — federation at REST level for remote query, but local decorators post-process the merged result set. Plausible for tracking (record that federated results were returned), but not for CRAG/expansion (which operate on the query, not the results).
**Rationale:** The current engine design keeps federation outside the retrieval pipeline: SearchResource.doSearch() calls caseRetriever.retrieve() for local results, then chainWalker.walk() for federated results. The decorator chain (CRAG, expansion, reranking, tracking) wraps only the local CaseRetriever. This is architecturally correct — each garden's pipeline processes its own results independently. Merging happens at the search-result level after both local and remote results are fully processed.
**Trade-offs:** Federated results cannot benefit from the local garden's CRAG quality filtering or expansion. This is acceptable — each garden controls its own quality.
**Sources:** SearchResource.doSearch() (engine), ChainWalker.walk() (engine), CaseRetriever decorator chain (neocortex)
**Exploration:** quick — surfaced by reviewer R1-08
**Status:** captured

## D11: Engine thinning approach

**Choice:** Include stripping extracted code from engine in this spec — each extraction phase removes code from engine and points to neocortex dependency
**Alternatives:**
- Separate Hortora/engine issue — cleaner repo boundary but risks drift
**Rationale:** Covering both sides (what goes into neocortex AND what leaves engine) ensures engine stays working throughout. Each phase is a complete extraction: add to neocortex, remove from engine, verify.
**Trade-offs:** Spec covers two repos, more coordination
**Sources:** Hortora/engine#90 (end state section)
**Exploration:** quick
**Status:** captured
