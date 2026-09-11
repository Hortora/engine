# MindMap SPI — Design Decisions

## D1: Module architecture

**Choice:** New top-level module family (`mindmap-api/`, `mindmap/`, `mindmap-inmem/`, `mindmap-sqlite/`, `mindmap-testing/`)
**Alternatives:**
- Inside memory-api — shares value types but creates coupling to memory-api's dependency tree and risks CDI displacement (GE-20260630-815259)
**Rationale:** Cleaner separation, own dependency tree, no coupling to existing memory SPIs. Follows the established pattern where each concern (inference, rag, corpus, memory) has its own module family.
**Trade-offs:** Cannot reuse MemoryDomain, MemoryCapability, etc. directly — may need own equivalents or shared types in a common module.
**Sources:** GE-20260630-815259 (cross-repo SPI extends CDI displacement), existing module structure pattern
**Exploration:** quick
**Status:** captured

## D2: Cross-store bridging

**Choice:** URI-style typed references (`NodeRef` with scheme/id/qualifier)
**Alternatives:**
- Shared identity namespace — tighter coupling, assumes all entities have a natural shared key
- Both (identity where natural, refs for cross-cutting) — more complex, two resolution mechanisms
**Rationale:** Loose coupling — the graph stores a reference without depending on the other SPI's classpath. Resolution is lazy. A node can reference a CaseMemoryStore entity, a CBR case, or an external URL with the same mechanism.
**Trade-offs:** No compile-time guarantee that the referenced entity exists. Resolution requires the consuming module to have the target SPI available.
**Sources:** CbrCaseMemoryStore standalone SPI pattern, existing bridging via CDI observers in memory/
**Exploration:** quick
**Status:** captured

## D3: Subgraph boundaries

**Choice:** Declared subgraphs with typed root nodes
**Alternatives:**
- Emergent from edge density — more flexible but requires graph analysis to answer "which subgraph?"
- Declared but optional — hybrid that allows orphan nodes, more complex query model
- SubgraphType as String with vocabulary registration (following D6's edge type governance model) — rejected: SubgraphType is architect-driven (determines structural analysis expectations, decay rates, partitioning semantics), not discovery-driven (LLM-extracted). Edge types grow through LLM extraction and need open vocabulary; subgraph types define how the graph is partitioned and each carries structural expectations. Different governance models are appropriate for different growth patterns. GENERAL serves as the catch-all for uncategorised content.
**Rationale:** Gives typed schema expectations per subgraph type (PROJECT has different expectations than PERSON). Simple to query ("give me the project subgraph for neocortex"). Cross-subgraph edges are explicit bridges, making the merge points visible. The enum enforces that adding a new structural category is a deliberate SPI evolution, not something that happens silently via configuration.
**Trade-offs:** Every node must belong to a subgraph — no free-floating concepts. May need a "general" or "uncategorised" subgraph as a catch-all. Application repos cannot introduce domain-specific subgraph types without modifying the SPI — this is intentional friction: each new type carries structural expectations (decay rates, analysis heuristics) that should be designed, not discovered.
**Sources:** Mind map mental model from conversation, R1-03 decision review finding
**Exploration:** quick
**Status:** captured

## D4: Naming

**Choice:** `mindmap` — MindMapStore, `mindmap-api/`, `io.casehub.neocortex.mindmap`
**Alternatives:**
- `knowledgegraph` — technically precise but longer, academic connotation
- `graph` — short but clashes conceptually with GraphCaseMemoryStore
**Rationale:** Matches the mental model ("mind maps that merge"), intuitive, no academic baggage. Short enough for module/package names.
**Trade-offs:** "Mind map" has connotations of tree-structured diagrams in some contexts — this is more graph than tree. Acceptable given the conversational context.
**Sources:** Conversation framing
**Exploration:** quick
**Status:** captured

## D5: Intelligence layer placement — decorator/consumer split

**Choice:** Two-tier split following the CbrCaseMemoryStore decorator pattern. Transparent concerns (vocabulary normalization, derived edge insertion via rules) operate as CDI `@Decorator` on the SPI — consumers get enriched results without knowing decorators exist. Explicit concerns (LLM extraction, gap detection, active learning, deep contradiction analysis) live above the SPI in a separate module (`mindmap-intelligence/`).
**Alternatives:**
- All intelligence above SPI — forces every consumer to explicitly invoke the rule engine; discards the proven decorator pattern from CBR
- All intelligence in the SPI — couples storage to LLM dependencies
- Enrichment pipeline in the SPI (like CaseEnrichmentStep) — less flexible than decorators
**Rationale:** The CBR system proves this pattern: `TrendEnrichmentCbrCaseMemoryStore` at `@Priority(90)` enriches transparently; LLM-based operations are explicit. The same split applies here: vocabulary normalization and forward-chaining rules for derived edges are cheap, deterministic, and should fire transparently on every addEdge(). LLM extraction, gap detection, and active learning are expensive, non-deterministic, and should be explicitly invoked.
**Truth maintenance durability:** Derived edge provenance properties (`mindmap.derived.trigger-edge-id`, `mindmap.derived.rule-name`, `mindmap.derived=true`) persisted as edge properties in the store are the **authoritative** source for trigger-to-derived edge relationships. The `DerivedEdgeDecorator`'s in-memory `ConcurrentHashMap<String, List<String>> triggerToDerived` is a session-scoped performance cache — it must NOT be the sole mechanism for truth maintenance cascades. `removeEdge()` must query the store for edges carrying `PROPERTY_TRIGGER_EDGE_ID = edgeId` to discover derived edges that should be retracted, falling back to persisted provenance when the cache is cold (e.g., after process restart). This ensures truth maintenance survives restarts and is correct regardless of cache state.
**Trade-offs:** Decorator chain adds implementation complexity. Decorator ordering must be documented. Transparent rules must be fast — slow rules should be moved to the explicit intelligence layer. Truth maintenance via store query on `removeEdge()` adds a query per removal — acceptable at agent scale (D25).
**Sources:** CbrCaseMemoryStore decorator chain (TrendEnrichment, OutcomeWeighting, TrustWeighted), CaseEnrichmentStep, R1-04 decision review finding, R2-03 decision review finding (truth maintenance durability)
**Exploration:** quick
**Status:** revised (was: all intelligence above SPI; added: truth maintenance durability requirement)

## D6: Controlled vocabulary governance — soft registration with validation tiers

**Choice:** Schema registration with alias normalization AND acceptance of unregistered types tagged as `UNVALIDATED`. Registered types normalise on ingest ("employed-by" → "works-at"). Unregistered types are accepted but flagged — the intelligence layer can promote validated types into the controlled vocabulary over time.
**Alternatives:**
- Hard rejection of unregistered types — blocks LLM discovery of novel relationships (R1-05 finding: chicken-and-egg with LLM extraction)
- Open with normalization above — flexible but allows synonym accumulation without any governance
**Rationale:** The LLM extraction layer (D5) may discover novel relationship types that aren't pre-registered. Hard rejection forces pre-registration of all possible types (defeating LLM discovery) or on-the-fly registration (making the boundary meaningless). Soft governance preserves the controlled vocabulary as the goal while allowing discovery. Edges carry a validation tier: `REGISTERED` (normalised, governed) or `UNVALIDATED` (accepted, flagged for review). A promotion path moves validated types from UNVALIDATED to REGISTERED.
**Trade-offs:** UNVALIDATED types can accumulate if the intelligence layer doesn't actively review them. Needs a periodic cleanup/promotion mechanism. Queries should distinguish between validated and unvalidated edges when governance matters.
**Scope:** Edge type vocabulary only. Trait name governance is intentionally intelligence-layer scope (per D9) — the SPI stores trait names as opaque strings and has no vocabulary governing what they mean. Trait name normalization ("person" vs "individual" vs "human") is the responsibility of TraitRule implementations and the intelligence layer, not the SPI. This follows the D5 split: transparent concerns (vocabulary normalization for edges) are SPI-level; semantic concerns (trait classification and normalization) are intelligence-level.
**Sources:** CbrFeatureSchema.registerSchema() pattern, conversation on vocabulary governance, R1-05 decision review finding, R1-04 decision review finding (scope clarification)
**Exploration:** quick
**Status:** revised (was: hard rejection of unregistered types)

## D7: Entity aliasing

**Choice:** First-class in the SPI (addAlias, resolveNode, mergeNodes)
**Alternatives:**
- Intelligence layer — simpler SPI but consumers must handle name→ID mapping, inconsistent resolution
**Rationale:** Aliasing must be storage-level to be consistent. If two consumers query by alias, they must resolve to the same node — this requires the store to own the alias index. Merge operations (union edges when two nodes are discovered to be the same entity) are destructive and must be atomic.
**Trade-offs:** Adds methods to the SPI. Merge is complex (edge deduplication, alias union, subgraph reassignment). Contract tests must cover merge edge cases.
**Sources:** Conversation on entity resolution
**Exploration:** quick
**Status:** captured

## D8: SPI shape

**Choice:** Approach A — granular graph primitives (single MindMapStore interface with CRUD methods for nodes, edges, aliases, subgraphs, vocabulary, traversal)
**Alternatives:**
- Batch-oriented operations — fewer methods but complex input types, harder to test individually
- Resource-oriented (one interface per concept) — over-decomposition, complex CDI wiring
**Rationale:** Same pattern as CbrCaseMemoryStore — a single interface with granular methods. Simple to implement, test, and decorate. The intelligence layer composes the primitives. Batch operations can be added later as default methods.
**Trade-offs:** More methods on the interface (~27 including `updateSubgraph`). Each must be independently tested in contract suite. For comparison, `CbrCaseMemoryStore` has 14 methods and 8 decorators — each delegating all 14. The ratio is comparable and the pattern is proven at the platform's current decorator count. The method count reflects domain complexity (nodes + edges + aliases + subgraphs + traversal + vocabulary + lifecycle + erasure), not interface bloat.
**Sources:** CbrCaseMemoryStore pattern (14 methods, 8 decorators), CaseMemoryStore pattern (12 methods), R1-05 decision review finding
**Exploration:** quick
**Status:** revised (was: method count estimate ~15-20)

## D9: Node content model — hybrid core + dynamic properties + opaque trait metadata

**Choice:** The SPI stores: (1) fixed core fields on `MindMapNode` (id, name, createdAt, confidence, provenance, etc.), (2) dynamic key-value properties, (3) a `Set<String>` of applied trait names as opaque metadata. The SPI does not know what trait names mean — it stores them as strings. Proxy generation, truth maintenance, forward-chaining rules, and compaction are intelligence-layer concerns (D5), not SPI concerns. The SPI's `property(key)` method provides unified access regardless of whether a value is stored as a core field or a dynamic property.
**Alternatives:**
- Sealed hierarchy (PersonNode, ProjectNode, etc.) — rigid, new types require SPI changes
- Full Drools Traits in the SPI — conflates storage with rule engine; violates D5's separation
- Single interface + properties only — works but doesn't store trait classification
**Rationale:** Clear boundary: the SPI is a property store that also tracks which trait names are applied. The intelligence layer owns trait semantics — which interfaces exist, how proxies are generated, when traits are applied/retracted via rules, and when compaction promotes dynamic properties to core. This follows D5's revised decorator/consumer split: trait management is explicit intelligence, not transparent decoration.
**Trade-offs:** Consumers of the raw SPI get untyped property bags. Typed access requires the intelligence/proxy layer. This is acceptable — the SPI's job is storage, not presentation.
**Sources:** Drools Traits system (user's prior work), R1-03 decision review finding (separation of concerns), D5 revised placement
**Exploration:** quick
**Depends on:** D5
**Status:** revised (was: traits in SPI)

## D10: Third-party vs own implementation

**Choice:** Own the SPI and model. Storage is an implementation detail that can change over time (SQLite day-one, TinkerPop/ArcadeDB if traversal complexity grows).
**Alternatives:**
- Build on TinkerPop/Gremlin directly — standard API but heavyweight dependency for simple traversal needs
- Build on ArcadeDB — multi-model but adds an embedded database dependency
- Use Apache Jena — formal ontology framework, explicitly rejected as too heavy
**Rationale:** The intelligence layer (controlled vocabulary, confidence, aliasing, rules, active learning, LLM extraction) is the novel part — no library covers it. The storage layer is simple (nodes + edges + properties in SQLite). TinkerPop is the escape hatch if traversal needs outgrow recursive CTEs. Neo4j rejected on licensing grounds (GPL/proprietary).
**Trade-offs:** Owning storage means maintaining traversal algorithms. Day-one traversal is simple (neighbors, bridges) but shortest-path, community detection, etc. would need implementation or a backend swap.
**Sources:** ArcadeDB (Apache 2.0), TinkerPop (Apache 2.0), Jena (Apache 2.0), Neo4j (GPL — rejected), conversation on NIH risk
**Exploration:** deep-analysis
**Status:** captured

## D11: Tenant isolation

**Choice:** Tenant-isolated, consistent with all platform memory SPIs. Every mutating and querying method requires `tenantId`. The SPI enforces tenant boundaries — a node in tenant A is invisible to queries in tenant B. Cross-tenant erasure is included on the SPI surface via `eraseEntityAcrossTenants(String entityName, Set<String> tenantIds)`, matching `CaseMemoryStore.eraseEntityAcrossTenants()` for GDPR compliance. Guarded by `CROSS_TENANT_ERASE` capability.
**Alternatives:**
- Tenant-unaware SPI with tenant filtering above — violates platform security conventions
- No cross-tenant operations — cleaner SPI but forces GDPR cross-tenant erasure through per-tenant iteration at the caller, duplicating the pattern CaseMemoryStore already provides
**Rationale:** Every memory SPI in the platform (`CaseMemoryStore`, `CbrCaseMemoryStore`) requires tenantId on every operation and enforces access via `MemoryPermissions.assertTenant()`. The mindmap SPI must follow the same convention for platform coherence and security. Cross-tenant erasure was added to match `CaseMemoryStore.eraseEntityAcrossTenants()` — GDPR Art.17 erasure requests apply across all tenants, and the platform convention is to provide this at the SPI level rather than forcing callers to iterate.
**Trade-offs:** Every method signature includes tenantId — slightly more verbose API. Cross-tenant erasure requires admin-level authorization at the caller.
**Sources:** CaseMemoryStore.store(MemoryInput) — tenantId required, CaseMemoryStore.eraseEntityAcrossTenants() — cross-tenant GDPR pattern, CbrCaseMemoryStore — tenantId on every call, MemoryPermissions.assertTenant(), R1-11 decision review finding, R1-02 decision review finding
**Exploration:** quick
**Status:** revised (was: no cross-tenant operations on SPI surface)

## D12: GDPR erasure

**Choice:** The SPI provides cascading graph-aware erasure: `eraseNode(nodeId, tenantId)` removes the node, all its edges, all its aliases, its subgraph membership, and any NodeRefs pointing to it from other nodes. `eraseBySubgraph(subgraphId, tenantId)` removes an entire subgraph and all its contents. Both return counts for audit logging. `eraseEntity(entityId, tenantId)` is a future capability if entity-level (vs node-level) erasure is needed.
**Alternatives:**
- Node-only erasure with orphan cleanup — simpler SPI but leaves dangling edges and refs
- Soft-delete with tombstones — preserves history but doesn't satisfy GDPR hard-delete requirements
**Rationale:** Graph erasure is structurally harder than flat record deletion. A node participates in edges, aliases, subgraphs, and cross-references. Deleting only the node leaves the graph in an inconsistent state. The SPI must guarantee cascading cleanup as an atomic operation.
**Trade-offs:** Cascading delete is complex to implement correctly, especially for NodeRefs (which may point to the deleted node from other nodes). Contract tests must verify no dangling references survive erasure.
**Sources:** CaseMemoryStore.eraseEntity(), CbrCaseMemoryStore.erase/eraseEntity/eraseByScope, GDPR Art.17, R1-11 decision review finding
**Exploration:** quick
**Status:** captured

## D13: Capability self-description

**Choice:** The SPI includes a `capabilities()` method returning `Set<MindMapCapability>` and a `requireCapability(MindMapCapability)` guard, following the `MemoryCapability` pattern. Initial capabilities include `TRAVERSAL`, `MERGE`, `VOCABULARY`, `ALIAS`, `SUBGRAPH`, `SEARCH`, `ERASE_NODE`, `ERASE_SUBGRAPH`. Backends declare which operations they support; consumers can check before calling optional operations.
**Alternatives:**
- No capabilities — all methods are mandatory on all backends. Simpler but forces every backend to implement everything, even if some operations are expensive or unsupported.
- Exceptions on unsupported operations — same result but less discoverable than a capability check.
**Rationale:** The in-memory backend will support everything. A future TinkerPop backend may not support atomic merge. A minimal backend may skip traversal. Capability self-description lets consumers adapt without catching exceptions.
**Trade-offs:** Adds an enum and two methods to the SPI. Backends must maintain an accurate capability set.
**Sources:** MemoryCapability enum, CaseMemoryStore.capabilities()/requireCapability(), R1-11 decision review finding
**Exploration:** quick
**Status:** captured

## D14: Blocks integration — connective tissue for cognitive subsystems

**Choice:** The mind map graph serves as the connective tissue linking blocks' cognitive subsystems. Currently each orchestrator (MentalModel, Strategy, UserModel, Narrative) has its own isolated CBR store. The mind map unifies these: a mental model about a user IS a subgraph, connected to the user profile node, connected to the strategies used with them, connected to narrative episodes from their interactions. Integration is via CDI observers on blocks' domain events (MentalStateSignal, EngagementSignal, DriverEvent) that create/update nodes and edges in the graph. The mind map intelligence layer participates in the InnerLifeOrchestrator's tick loop for rule evaluation, gap detection, and trait maintenance.
**Alternatives:**
- Keep separate CBR stores, use NodeRef to link — preserves existing architecture but doesn't unify
- Replace all CBR stores with graph — too aggressive, loses CBR's similarity scoring
**Rationale:** The existing orchestrators (InnerLifeOrchestrator → DriveOrchestrator → MentalModelOrchestrator → UserModelOrchestrator → StrategyLearningOrchestrator → NarrativeOrchestrator → MemoryHygieneOrchestrator) are silos. Each has its own CBR backend (CbrMentalModelStore, CbrStrategyStore, CbrUserProfileStore, CbrNarrativeStore). The mind map makes cross-system connections explicit and queryable. CuriosityDrive can traverse the graph to find knowledge gaps. Narrative episodes link to the entities they mention. Strategy profiles connect to the situations they apply in. The graph is the shared substrate; the CBR stores continue to own domain-specific similarity retrieval.
**Trade-offs:** Blocks depends on the mindmap-api module. Observer-based integration adds latency on each domain event. The circular data flow (blocks → graph → analyzer → curiosity → blocks) is intentional — it is a cognitive feedback loop, not an architectural smell. Knowledge begets curiosity begets more knowledge.
**Consistency model:** Best-effort, fire-and-forget via CDI observers — no convergence guarantee. If a CDI observer fails (e.g., `MentalStateSignal` processed by CbrMentalModelStore but the graph observer throws), the CBR store and graph diverge silently. For entities mentioned repeatedly, subsequent events will re-establish the connection. For entities mentioned once (a peripheral colleague, a one-time project), observer failure creates permanent divergence — the graph will never learn about that connection. This is acceptable because: (1) the graph is supplementary — the CBR stores remain authoritative for domain-specific retrieval; (2) `MindMapAnalyzer` detects sparse subgraphs and dangling NodeRefs, surfacing gaps as curiosity signals that can prompt re-discovery; (3) the cost of retry/compensation infrastructure exceeds the value of guaranteed convergence for a supplementary view. No retry, no compensation, no anti-entropy mechanism.
**Tick-loop budget:** Graph analysis on each tick is bounded by the analysis implementation (see D16). The integration pattern itself (CDI observer → addNode/addEdge) is O(1) per event. The cost concern is in the analysis phase, not the integration phase — addressed in D16.
**Sources:** blocks InnerLifeOrchestrator, MentalModelOrchestrator, DriveOrchestrator, CbrMentalModelStore, CbrStrategyStore, CbrUserProfileStore, CbrNarrativeStore, MemoryHygieneOrchestrator, KnowledgeGapSummary, R1-07 decision review finding
**Exploration:** deep-analysis
**Status:** revised (was: consistency model unspecified)

## D15: Decay, supersession, and temporal lifecycle on nodes and edges

**Choice:** Nodes and edges carry temporal lifecycle metadata, following the CBR pattern (`CbrOutcome`, `supersede()`, `reinstate()`, `SupersessionStatus`). Confidence decays over time for unconfirmed knowledge. Facts can be superseded by newer facts with an audit trail. The SPI exposes: `supersede(nodeOrEdgeId, supersedingId, reason, tenantId)`, `reinstate(nodeOrEdgeId, tenantId)`, `getSupersessionStatus(nodeOrEdgeId, tenantId)`. Decay is a configurable function (half-life or linear) applied at query time — stale knowledge loses confidence without being deleted. Explicit confirmation resets the decay clock.
**Alternatives:**
- No decay — knowledge stays at original confidence forever. Stale facts accumulate and mislead.
- Hard expiry — knowledge is deleted after a time window. Loses historical context.
**Rationale:** Knowledge goes stale. "Alice works at Acme" is high-confidence when stated, but after 2 years without confirmation it should decay. Supersession handles explicit updates ("Alice now works at Initech" supersedes the Acme edge). Decay handles uncertainty from age. Both are needed — supersession for known changes, decay for unknown staleness. The CBR system proved this pattern works (CbrOutcome EMA, supersession chains, reinstatement).
**Trade-offs:** Adds lifecycle methods to the SPI. Decay at query time adds computation. Need to decide default half-life per edge type (employment relationships decay slower than project assignments).
**Sources:** CbrCaseMemoryStore.supersede/reinstate/getSupersessionStatus, CbrOutcome, TemporalDecay (HalfLife, Linear, Step), conversation on temporal bounds
**Exploration:** quick
**Status:** captured

## D16: Graph analysis and curiosity indexing

**Choice:** The intelligence layer includes a `MindMapAnalyzer` (similar to `RetrievalAnalyzer` in rag-api) that computes structural, temporal, and quality signals from the graph. These signals are indexed for efficient querying by the curiosity engine. The analyzer produces ranked `CuriositySignal` records that drive agent questions and debate topics. Analysis runs on the InnerLifeOrchestrator's tick loop (or on-demand) and maintains incremental indexes — not full graph scans on every tick.
**Signals indexed:**
- **Structural:** sparse subgraphs (few nodes/edges relative to type), orphan nodes (no edges), structural holes (dense clusters with no bridges), low clustering coefficient
- **Quality:** low-confidence clusters (many INFERRED/SPECULATED edges), contradiction clusters (conflicting current edges), UNVALIDATED vocabulary edges (D6), dangling NodeRefs
- **Temporal:** stale regions (not updated recently), growth/decay rates per subgraph, supersession chains, confidence decay gradient
- **Centrality:** betweenness (bridge nodes worth knowing more about), degree (important entities to keep fresh)
**Alternatives:**
- No analysis — consumers query the raw graph and compute signals themselves. Duplicated logic, no indexing, expensive.
- Full graph analysis on every query — accurate but too slow for tick-loop integration.
**Rationale:** The graph's value as a curiosity driver depends on efficient access to "what's interesting." The target architecture uses incremental indexes (update on each addNode/addEdge/supersede) to keep cost proportional to changes, not graph size. The existing `RetrievalAnalyzer` pattern (pure computation over tracker data — documentStats, unretrievedDocuments, qualitySignals, correlationGraph) provides the architectural precedent.
**V1 implementation:** V1 uses full-scan analysis via static utility methods in `MindMapAnalyzer` — `orphanNodes()`, `degreeCentrality()`, `betweennessCentrality()` (Brandes' algorithm, O(V×E)), etc. all perform full graph traversal per call. This is adequate for agent-scale graphs (hundreds to low thousands of nodes per tenant, per D25) and serves as a proving ground for which signals are actually useful before investing in incremental index infrastructure.
**Incremental index trigger:** When graph analysis per tick exceeds 10ms at the P99, the full-scan approach should be replaced with maintained indexes updated on each mutation. The specific signals to index will be determined by V1 usage patterns — not all signals may warrant indexing.
**Trade-offs:** V1 full-scan adds O(V×E) worst-case per tick for betweenness centrality. Mitigated by: (a) agent-scale graphs are small; (b) analysis can be sampled (run betweenness every N ticks, not every tick); (c) cached results with TTL are acceptable for curiosity signals. Index maintenance (future) adds overhead to every write operation but makes analysis O(1).
**Sources:** RetrievalAnalyzer (rag-api), KnowledgeGapSummary (blocks memory), CuriosityDrive (blocks social/drive), DriveOrchestrator, conversation on graph analysis, R1-06 decision review finding
**Exploration:** deep-analysis
**Status:** revised (was: claimed incremental indexes without acknowledging V1 full-scan implementation)

## D17: Affective annotations on knowledge

**Choice:** Nodes and edges carry optional affective metadata — emotional valence of the knowledge itself, not the agent's mood. This is a property on the node/edge: `valence` (positive/negative/neutral), `arousal` (high/low — dangerous vs calm), and optionally `dominance` (empowering vs threatening). Uses the same PAD dimensional model as MoodState but applied to the knowledge, not the agent. Stored as core fields on edges (where affect is most natural — "lost-job" is negative, "promoted" is positive) and as properties on nodes where relevant.
**Alternatives:**
- No affect on knowledge — mood system operates only on agent state, not knowledge content. Loses the ability to do affect-aware retrieval or curiosity modulation.
- Free-form sentiment labels — "happy", "sad", "dangerous" as string tags. Inconsistent, not queryable as a dimension.
**Rationale:** The existing MoodState (neocortex) and MoodOrchestrator (blocks) track agent mood. But mood-congruent retrieval (MoodModulatedRetrieval) and affect-aware curiosity need to know the emotional valence of the knowledge being retrieved. "Don't probe a sensitive topic when the user is stressed" requires knowing which topics are sensitive. The PAD model is already established in the platform — extending it to knowledge annotations is consistent.
**Trade-offs:** Affect annotation adds fields to every edge. LLM extraction must infer valence, which is subjective and context-dependent. Some knowledge is affectively neutral — annotation should be optional, not mandatory.
**Sources:** MoodState (neocortex memory-api), MoodModulatedRetrieval (neocortex memory-api), MoodOrchestrator (blocks social), PAD dimensional model
**Exploration:** quick
**Status:** captured

## D18: Temporal proximity for curiosity prioritization

**Choice:** Nodes carry temporal markers via `validFrom`/`validUntil` fields (future dates — events, deadlines, aspirations). The graph analysis layer uses temporal proximity ("what's coming up soon?") to prioritize which areas of the graph to expand. Future-dated nodes act as attention magnets — as the date approaches, the curiosity engine intensifies knowledge-seeking in connected areas. A "calendar" is simply a query over temporally-marked nodes — no separate calendar store needed.
**Alternatives:**
- Separate calendar SPI — adds a new store for something the graph already models naturally
- No temporal prioritization — curiosity engine treats all gaps equally regardless of relevance to upcoming events
**Rationale:** Knowledge that matters next week is more valuable to expand than knowledge that matters next year. A node "visiting parents next Saturday" with temporal proximity = 5 days should drive questions about the parents, the trip, what they want to discuss. These are properties on nodes (future date), not new node types — they participate in the existing graph structure and analysis.
**Trade-offs:** Temporal markers require updates as events pass (are they still relevant? did they happen? what was the outcome?). LLM extraction must recognize temporal references ("next week") and resolve them to concrete dates.
**Spatial proximity (future work):** Spatial markers (locations, regions) and spatial proximity ("where am I / where am I going?") are a natural extension but not designed to the same level as temporal. They require: (a) a location field type on nodes (lat/long? named place? hierarchical?), (b) a location source (device location? user statement? extracted from conversation?), (c) a proximity function (geographic distance? semantic like "London" ≈ "UK"?). None of these are resolved. Spatial proximity will be designed as a separate decision when the platform has a location model.
**Sources:** Conversation on graph analysis prioritization, CuriosityDrive (blocks), no existing calendar capability in the platform, R1-09 decision review finding
**Exploration:** quick
**Status:** revised (was: spatial proximity presented as peer of temporal without design detail)

## D19: TraitApplicationDecorator chain ordering — @Priority(70), outermost

**Choice:** `TraitApplicationDecorator` at `@Priority(70)` in the CDI decorator chain, outermost relative to `DerivedEdgeDecorator` at `@Priority(80)`. Evaluates trait rules on the return path after all inner decorators (including DerivedEdge) have completed. Chain: `TraitApp(70) → DerivedEdge(80) → Store`.
**Alternatives:**
- @Priority(85), innermost — trait rules fire before derived edges exist, so they see an incomplete edge picture. Wrong ordering: "does node have parent-of edges?" requires derived inverses to already exist.
- Plain wrapper (no CDI @Decorator) — loses CDI discovery of TraitRule beans, breaks the DerivedEdgeDecorator pattern.
**Rationale:** Trait rules need to see ALL edges — including derived ones — to make correct decisions. The outermost decorator delegates first (lets DerivedEdge store the edge and fire derived rules), then evaluates trait rules against the node's complete edge set on the return path. When traits change, calls `delegate.updateNode()` (passthrough in DerivedEdge) and optionally `delegate.addEdge()` (goes through DerivedEdge, bounded by its depth counter).
**Trade-offs:** Trait rules fire for every edge mutation (including derived edges if CDI routing goes through the full chain). Performance impact is bounded by O(rules × mutations-per-user-action). Acceptable for agent-scale graphs.
**Sources:** DerivedEdgeDecorator @Priority(80) implementation, GE-20260716-f292d3 (CDI decorator ordering gotcha), spec §5.2.1
**Exploration:** deep-analysis
**Depends on:** D5 (intelligence layer placement)
**Status:** captured

## D20: Trait evaluation cycle prevention — reentrancy guard

**Choice:** `ThreadLocal<Boolean>` reentrancy guard in `TraitApplicationDecorator`. Trait evaluation fires once per user-initiated mutation. During evaluation, the guard is set. Mutations triggered BY trait evaluation (`delegate.updateNode`, `delegate.addEdge`) go through DerivedEdge (bounded by its own depth-3 counter) but skip trait re-evaluation.
**Alternatives:**
- Shared `ThreadLocal<Integer>` counter across DerivedEdge and TraitApp — allows multi-level trait chaining but couples two independent decorators through shared mutable state in a utility class.
- Independent depth counter (max 2) in TraitApp — total chain length is multiplicative (DerivedEdge depth × TraitApp depth), hard to reason about the interaction.
**Rationale:** The spec's "max depth 3" chain (trait → edge → trait → ...) is naturally enforced: the edge side is bounded by DerivedEdge's own counter, and the trait side fires at most once per user action. Trait-triggered edges get their derived edges (bounded at 3), but those derived edges don't trigger further trait evaluation. Simple, bounded, correct. No shared state between decorators.
**Trade-offs:** If applying trait A triggers an edge whose derived edge would make trait B applicable on a different node, trait B won't be discovered until the next user-triggered mutation. Acceptable for V1 — multi-level trait chains are unusual and can be addressed later if needed.
**Sources:** DerivedEdgeDecorator ThreadLocal<Integer> depth tracking, spec §5.2.1 (cycle prevention)
**Exploration:** deep-analysis
**Depends on:** D19 (chain ordering)
**Status:** captured

## D21: Proxy generation — JDK Proxy.newProxyInstance()

**Choice:** JDK `java.lang.reflect.Proxy` with convention-based method dispatch. `TraitProxy.as(node, Personable.class)` creates a proxy where `birthday()` → `node.property("birthday")`. Method name = property key. Return type drives conversion (String passthrough, Optional wrapping, Integer/Double parsing).
**Alternatives:**
- ByteBuddy — can proxy abstract classes, better stack traces. But trait interfaces are interfaces by design; adds a dependency for no architectural benefit.
- Manual implementation classes — no reflection, explicit, but every new trait interface requires a new hand-written class. Defeats the purpose of proxy generation.
**Rationale:** Zero external deps. Interface-only (which is exactly what trait interfaces are). Well-proven pattern (GE-20260803-0c691f uses the same approach for CDI Instance<T> stubbing). Convention-based mapping (method name = property key) matches the Drools Traits model the spec references (§5.3).
**Trade-offs:** JDK Proxy has worse stack traces than ByteBuddy. No compile-time verification that property keys match trait methods. Reflection has minor overhead per call (acceptable for graph queries, not hot-path inference).
**Sources:** GE-20260803-0c691f (CDI Instance stubbing via Proxy), spec §5.2 (proxy generation), §5.3 (Drools Traits model)
**Exploration:** quick
**Status:** captured

## D22: TraitApplicationDecorator placement — mindmap/ CDI module

**Choice:** `TraitApplicationDecorator` lives in `mindmap/` (CDI module), same as `DerivedEdgeDecorator`. Discovers `TraitRule` beans via CDI `Instance<TraitRule>`. No-op when no rules on classpath. Module split: `mindmap-api/` has `TraitRule` SPI interface, `mindmap/` has the decorator engine, `mindmap-intelligence/` has rule implementations + trait interfaces + `TraitProxy`.
**Alternatives:**
- In `mindmap-intelligence/` — decorator only exists when intelligence module is present. Cleaner isolation but breaks the established pattern. DerivedEdgeDecorator is in mindmap/ even though DerivedEdgeRule implementations could be in mindmap-intelligence/. Also means basic trait evaluation requires the intelligence module on the classpath.
**Rationale:** Follows the DerivedEdgeDecorator pattern exactly: decorator is the ENGINE (fires rules, applies/retracts traits), rules are the KNOWLEDGE (implementations in separate module). The decorator is useful even without the intelligence module — a consumer could provide their own TraitRule beans. Consistent CDI wiring story.
**Trade-offs:** mindmap/ has a decorator that does nothing when mindmap-intelligence/ is not on the classpath. Acceptable — same as DerivedEdgeDecorator with no DerivedEdgeRule beans.
**Sources:** DerivedEdgeDecorator pattern (mindmap/ module), D5 (intelligence layer split)
**Exploration:** quick
**Depends on:** D5 (intelligence layer placement), D19 (chain ordering)
**Status:** captured

## D23: SPI module boundary — extension point contracts in mindmap-api

**Choice:** `mindmap-api` contains both storage contracts (`MindMapStore`) and extension point contracts (`DerivedEdgeRule`, `TraitRule`). Rule interfaces live in the SPI module, rule implementations live in `mindmap-intelligence/` or application modules.
**Alternatives:**
- Extension point contracts in a separate module (`mindmap-rules-api`) — unnecessary module proliferation; the SPI module IS the extension point module
- Extension point contracts in `mindmap/` (CDI module) — forces rule implementations to depend on the CDI module, not just the SPI
- Extension point contracts in `mindmap-intelligence/` — breaks the engine/knowledge split: the decorator engine (in `mindmap/`) needs to discover rules via CDI, so the rule interface must be visible to `mindmap/`
**Rationale:** Platform convention: SPI modules contain all extension point contracts. `rag-api` has `QueryExpander`, `MetadataExtractor`, `CursorStore`. `memory-api` has `CaseMemoryStore` and `MemoryCapability`. The SPI module defines what consumers implement and what the runtime discovers. `DerivedEdgeRule` is in `mindmap-api` so that any module (including `mindmap-intelligence/` and application-level modules) can provide rule implementations by depending only on the zero-dependency SPI module.
**Trade-offs:** Rule implementations receive a `MindMapStore` parameter, giving them full read access to the store. This is by design — derived edge rules may need to query the graph to make decisions (e.g., "if the source node already has a parent-of edge, don't create a duplicate").
**Sources:** rag-api QueryExpander/MetadataExtractor/CursorStore pattern, DerivedEdgeDecorator CDI discovery, R1-12 decision review finding
**Exploration:** quick (surfaced by review)
**Status:** captured

## D24: Atomicity of compound SPI operations

**Choice:** Compound SPI operations (`mergeNodes`, `eraseNode` with cascading cleanup, `eraseSubgraph`, `eraseEntityAcrossTenants`) must be atomic — they either complete fully or leave no partial state. This is a documented SPI contract, not an implementation detail.
**Alternatives:**
- Eventual consistency for compound operations — simpler implementation but allows structurally inconsistent graph states (e.g., `mergeNodes` repoints edges but fails to merge aliases)
- Event-sourced mutation log with replay — full auditability and recovery, but heavyweight for the current scale and introduces a new infrastructure dependency
**Rationale:** Graph structural consistency is a hard requirement. A partially-failed `mergeNodes` (edges repointed but aliases not merged) or `eraseNode` (node deleted but edges left dangling) produces a graph that violates structural invariants. The SPI contract must guarantee that these operations are atomic. The SQLite backend provides this via database transactions. Future backends (TinkerPop, PostgreSQL) support transactions (`graph.tx().commit()`, JDBC transactions). Any backend that cannot provide atomicity for these operations should not implement the corresponding capabilities.
**Trade-offs:** Atomicity constrains backend implementation — backends must support transactions for compound operations. This is acceptable because the operations that require atomicity are the same ones that require transactional isolation in any graph store.
**Sources:** SQLite transaction semantics, TinkerPop Transaction API, MergeResult (spec §3.9 — documents the expected atomic outcome), R1-13 decision review finding
**Exploration:** quick (surfaced by review)
**Status:** captured

## D25: Scale assumption — agent-scale graphs

**Choice:** The MindMap SPI is designed for agent-scale graphs: hundreds to low thousands of nodes and edges per tenant. This assumption is explicit and drives design choices throughout.
**Design choices driven by this assumption:**
- Unbounded result sets from `neighbors()`, `nodesIn()`, `bridgeEdges()` (spec §3.1) — truncating would produce incorrect graph analysis
- Full-scan analysis in `MindMapAnalyzer` V1 (D16) — adequate at this scale
- O(V×E) betweenness centrality without sampling — completes in milliseconds for thousands of nodes
- SQLite as production backend (D10) — single-file embedded store, no operational overhead
- No pagination on query methods — the full result set fits comfortably in memory
**Scale triggers (when this assumption breaks):**
- Nodes per tenant > 10,000 — add pagination to `nodesIn()`, `neighbors()`. Consider sampling for `betweennessCentrality()`.
- Edges per tenant > 50,000 — implement incremental indexes (D16). Consider TinkerPop backend for traversal performance.
- Graph analysis per tick > 10ms P99 — cache analysis results with TTL. Sample analysis (run expensive signals every N ticks).
- Multiple agents sharing a tenant — partition by agent via subgraphs; if >5 agents share a tenant, evaluate per-agent tenant isolation.
**Alternatives:**
- Design for large-scale from day one — pagination, streaming, incremental indexes, distributed backend. Premature: adds complexity for a scale that may never arrive. Agent mind maps are inherently personal and bounded.
- No explicit scale assumption — allows design choices to drift toward inconsistent scale models
**Rationale:** An agent's mind map is bounded by the agent's experience. Even over years, an agent that has interacted with hundreds of people, dozens of projects, and thousands of facts produces a graph in the low thousands of nodes. Multi-agent tenants are the growth vector, not individual agent knowledge. Explicit thresholds and migration triggers prevent silent degradation.
**Sources:** spec §3.1 traversal result sets, D10 (SQLite backend rationale), D16 (graph analysis), R1-14 decision review finding
**Exploration:** quick (surfaced by review)
**Status:** captured
