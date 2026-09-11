## D1: Module placement for unified Confidence type

**Choice:** New `cognitive-api` module — tier-0 pure Java, zero dependencies
**Alternatives:**
- In mindmap-api — lightweight but couples mindmap concepts into memory's dependency tree
- In memory-api — forces mindmap-api to gain heavier dependencies, breaking its zero-dep constraint
- `confidence-api` — too narrow; would need immediate renaming when TemporalMark (#234) or MemorySpace (#230) land
- `cognitive-primitives` — vague; doesn't follow the established `*-api` naming convention (memory-api, mindmap-api, fusion-api, rag-api)
**Rationale:** `cognitive-api` starts lean (2 types: `Confidence`, `ConfidenceOrigin`) but has a clear growth path — `TemporalMark` (#234), `MemorySpace`/`Visibility` (#230), `Affect` (#238) all need the same cross-cutting home. Justified investment, not speculative infrastructure. Module name describes architectural role (cross-cutting cognitive type layer), not a specific concept — naming audit (#232) pins concept terminology, not module structure.
**Trade-offs:** Adds a module to the build. Every future type placement decision must consider cognitive-api vs module-specific.
**Sources:** cognitive-architecture-roadmap.md §1a, cognitive-coherence-audit.md §Dimension 1, mindmap-api/pom.xml (zero deps), memory-api/pom.xml (fusion-api + platform-api deps)
**Exploration:** quick
**Status:** revised — removed NodeRef (#243) from growth path (R1-03: NodeRef is mindmap-only, 31 references all in mindmap-* modules; listing it as cross-cutting was incorrect)

## D2: Decay reference placement — internal to Confidence

**Choice:** `Confidence(ConfidenceOrigin origin, double value, Instant decayReference)` — decay reference is a field on the record
**Alternatives:**
- External only — Confidence carries just origin+value; each store picks its own timestamp for decay (confirmedAt, updatedAt, storedAt). Simpler record but perpetuates entity-specific dispatch in the decorator.
- Both with fallback — internal with external override. Migration-friendly but adds precedence rules.
- DecayAnchor interface on entities — entities implement `DecayAnchor { Instant decayReference(); }` and decorator reads the interface. Clean separation but breaks when Confidence is passed without its owning entity (cross-store queries, serialized records, API responses — decay cannot be computed from Confidence alone).
**Rationale:** "When was this confidence level last established?" is a property of the confidence itself, not the entity. Eliminates the awkward asymmetry where MindMapNode uses `confirmedAt` and MindMapEdge uses `updatedAt` for the same conceptual purpose. Makes `ConfidenceDecayDecorator` generic — it reads `confidence().decayReference()` regardless of entity type. Null decayReference explicitly means "this confidence does not decay" (e.g., Memory) — cleaner than entity-type checking. Critically, Confidence records are passed across subsystem boundaries (cross-store queries in Phase 4, serialized snapshots, API responses) — the decay anchor must travel WITH the value to remain computable.
**Factory method semantics:** `stated()`, `inferred()`, `speculated()` require non-null decayReference — if you know HOW something was established, you should know WHEN. This is an origin-semantics constraint, not a MindMap-specific one: an epistemically grounded confidence (known provenance) always has a temporal anchor. Error messages must be domain-neutral ("decayReference required"), not MindMap-specific. `unknown()` creates with null decayReference because UNKNOWN is provenance-less — there's no meaningful decay anchor. The raw constructor remains available for edge cases where a caller has a non-UNKNOWN origin but genuinely no temporal anchor.
**Trade-offs:** MindMapNode's `confirmedAt` becomes redundant — resolved by D6 (remove confirmedAt). Backends must persist decayReference alongside origin+value.
**Depends on:** D1 (module placement)
**Sources:** ConfidenceDecayDecorator.java (mindmap/runtime), MindMapNode.java:27 confirmedAt, MindMapEdge.java:22 updatedAt
**Exploration:** quick
**Status:** revised — factory method null check documented as origin-semantics (not MindMap-specific), error messages domain-neutralized (R1-09)

## D3: ConfidenceOrigin scope — keep 3 values + UNKNOWN, non-nullable

**Choice:** ConfidenceOrigin gains `UNKNOWN` enum value for "provenance not tracked." Non-nullable on the Confidence record. Moved to cognitive-api WITHOUT `initialConfidence()` — default confidence values are extraction policy, not type semantics.
**Alternatives:**
- Nullable origin (original decision) — null means "source unknown." Introduces null-handling obligations at every consumer; breaks exhaustive switch coverage; current codebase has zero null origins.
- Expand now — add OBSERVED, COMPUTED, REFLECTED for Memory/CBR contexts. Richer but couples #229 scope to #232 naming audit.
- Replace with String — maximum flexibility but loses type safety.
**Rationale:** `UNKNOWN` fills the provenance gap without expanding the origin taxonomy. Every consumer gets exhaustive switch coverage. UNKNOWN is the ABSENCE of provenance information, not a new provenance category — orthogonal to domain-specific origins (#232 may add). `initialConfidence()` removed from the enum: the values (STATED=1.0, INFERRED=0.7, SPECULATED=0.3) are MindMap extraction defaults, not cognitive axioms. A stated fact CAN have any confidence level. The defaults move to the MindMap store layer where they're actually used (InMemoryMindMapStore.addNode, SqliteMindMapStore.addNode — 4 call sites total).
**Trade-offs:** `UNKNOWN` adds a fourth enum value all switch expressions must handle. MindMap stores need a local defaulting utility for initialConfidence.
**Depends on:** D1 (module placement)
**Sources:** cognitive-architecture-roadmap.md §Terminology ("importance becomes value with origin=null"), ConfidenceOrigin.java, InMemoryMindMapStore.java:95, SqliteMindMapStore.java:238
**Exploration:** quick
**Status:** revised — UNKNOWN replaces nullable origin (R1-08); initialConfidence() stays in mindmap layer, not cognitive-api (R1-09)

## D4: API surface — single Confidence accessor replaces split fields

**Choice:** MindMapNode/MindMapEdge: `confidenceOrigin()` + `confidence()` → single `Confidence confidence()`. NodeInput/EdgeInput: separate fields → single `Confidence confidence`. MemoryInput/Memory: `Double importance` → `Confidence confidence` (nullable — null means "no confidence assessment"). CbrCase: `Double confidence()` → `Confidence confidence()`. CbrCase.withOutcome: `withOutcome(String outcome, Double confidence)` → `withOutcome(String outcome, Confidence confidence)` — cascades to TextualCbrCase, PlanCbrCase, FeatureVectorCbrCase. NodeUpdate: `ConfidenceOrigin confidenceOrigin, Double confidence, Instant confirmedAt` → single nullable `Confidence confidence` (null means "don't change"; non-null replaces entire confidence including decayReference). MindMapQuery: keeps separate `Double minConfidence` and `ConfidenceOrigin confidenceOrigin` as query predicates — these are independent filters, not a value composition. ParsedEntity: `ConfidenceOrigin confidence` renamed to `ConfidenceOrigin origin` for clarity (ParsedEntity carries origin only, not a full Confidence record — the confidence value is derived at store time).
**Alternatives:**
- Keep split fields + add Confidence — backwards compatible but duplicates the concept, confuses which to use.
- Adapter layer — old methods delegate to Confidence internally. Avoids breaking callers but adds indirection that never gets cleaned up.
**Rationale:** Pre-release platform — breaking changes cost nothing. One concept = one method. All callers update to `node.confidence().value()` which is more explicit about what they're reading. Eliminates the possibility of confidenceOrigin and confidence being inconsistent.
**Trade-offs:** Every caller of `node.confidence()` (expecting double) must update. CbrCase.withOutcome signature change cascades to all 3 implementations. 155 ConfidenceOrigin references + all confidence() call sites.
**Depends on:** D2 (record shape)
**Sources:** MindMapNode.java, MindMapEdge.java, NodeUpdate.java, MindMapQuery.java, MemoryInput.java, Memory.java, CbrCase.java, TextualCbrCase.java, PlanCbrCase.java, FeatureVectorCbrCase.java, ParsedEntity.java
**Exploration:** quick
**Status:** revised — NodeUpdate, MindMapQuery, ParsedEntity, CbrCase.withOutcome explicitly addressed (R1-11, R1-12)

## D5: EMA confidence smoothing — instance method on Confidence

**Choice:** `Confidence.adjust(double observedRate, double learningRate, Instant newDecayReference)` — instance method on the Confidence record. Returns a new Confidence with the same origin, EMA-smoothed value (`(1 - learningRate) * this.value + learningRate * observedRate`), and the given decayReference. CbrOutcome.adjustConfidence is removed — callers use `confidence.adjust(successRate, lr, outcome.observedAt())` directly. CbrOutcome itself stays in memory-api unchanged (it carries CBR-specific fields: result, successRate, detail).
**Alternatives:**
- Static method on CbrOutcome (original D5) — couples general-purpose EMA math to a CBR-specific type. Future consumers (engagement scoring, reflection consolidation, trajectory analysis) would need a CbrOutcome dependency to access a math function that takes and returns Confidence.
- Standalone utility class — adds a class for a single method with no clear home.
- Keep Double-based adjustConfidence — callers wrap/unwrap Confidence manually. Error-prone.
**Rationale:** The EMA formula is general-purpose numerical smoothing — it takes a Confidence and returns a Confidence, a pure transformation on the type itself. Placing the method on the type it transforms is not speculative; it follows the principle of methods-on-their-types (like `withValue()` and `withDecayReference()` already on Confidence). The roadmap shows engagement scoring, reflection levels, and trajectory analysis as future EMA consumers — all would naturally call `confidence.adjust()` without any CBR dependency. The "trigger context" (CBR outcome, engagement event, reflection) is irrelevant to the transformation itself; the trigger selects the observedRate and learningRate to pass.
**Trade-offs:** Confidence gains one method. CbrOutcome stores that called adjustConfidence update to `old.adjust(successRate, lr, outcome.observedAt())` — a mechanical migration.
**Depends on:** D1 (module placement), D4 (API surface)
**Sources:** CbrOutcome.java:30-34 adjustConfidence, InMemoryCbrCaseMemoryStore.java:198, JpaCbrCaseMemoryStore.java:228, QdrantCbrCaseMemoryStore.java:671, cognitive-architecture-roadmap.md §3c (engagement, reflection as future confidence consumers)
**Exploration:** quick
**Status:** revised — EMA adjustment moves from CbrOutcome static method to Confidence.adjust() instance method (R1-02, supersedes R1-14)

## D6: confirmedAt resolution — remove from MindMapNode

**Choice:** Remove `confirmedAt` from `MindMapNode`. Node confirmation = updating `Confidence` with `decayReference=Instant.now()`. Value and origin are preserved unless the caller explicitly provides new values. NodeUpdate's `confirmedAt` field is subsumed by the new `Confidence confidence` field — callers construct a Confidence with updated decayReference.
**Semantic change from current behavior:** The current store contract implicitly resets confidence to 1.0 when `confirmedAt` is set without an explicit confidence value (`InMemoryMindMapStore.java:124-131`, `SqliteMindMapStore.java:300-303`, enforced by contract test `updateNode_confirmedAtWithoutConfidence_resetsTo1`). Under D6, confirmation resets only the decay anchor — value is preserved. A SPECULATED node at 0.3 that is "confirmed" stays at 0.3 with a fresh decay clock, rather than silently jumping to 1.0. To reset value to 1.0, the caller must explicitly construct `new Confidence(origin, 1.0, Instant.now())`. This is a deliberate improvement: the current implicit reset erases the epistemic distinction between SPECULATED and STATED knowledge. Confirming means "I've re-verified this is still believed" — it does NOT mean "I've upgraded my certainty to maximum." The contract test `updateNode_confirmedAtWithoutConfidence_resetsTo1` will be removed; a new test will verify that confirmation preserves value while resetting decayReference.
**Alternatives:**
- Keep confirmedAt, derive decayReference from it — contradicts D2's design (decayReference is on the Confidence record, not derived from entity fields). Two sources of truth.
- Keep both, enforce equality — redundant fields with an invariant to maintain. Store implementations must keep them in sync.
- Keep confirmedAt as audit-only — adds a field that no consumer reads for audit purposes (verified: only ConfidenceDecayDecorator.java:78 and MindMapAnalyzer.java:163 read confirmedAt, both for decay/staleness).
- Preserve the implicit 1.0 reset on confirmation — caller passes `Confidence confidence` without value, store defaults to 1.0. Rejects because Confidence is a record with non-null fields (D2: `double value`, not `Double`); there is no "without value" state. The caller is always explicit.
**Rationale:** `confirmedAt` was always the decay anchor for nodes. After unification, `confidence().decayReference()` serves this role. The two runtime consumers (ConfidenceDecayDecorator, MindMapAnalyzer.staleNodes) read `confirmedAt` purely for decay computation — both migrate to `confidence().decayReference()`. No consumer reads confirmedAt for audit/human-readable purposes. `updatedAt` already captures "when was this entity last modified" for audit needs.
**Trade-offs:** Callers that set `NodeUpdate.confirmedAt` must construct a Confidence record instead. The implicit 1.0 reset is lost — callers that intend a value reset must be explicit. Breaking change — but pre-release platform, and the breakage forces callers to be explicit about what "confirmation" means in their context.
**Depends on:** D2 (decayReference on Confidence), D4 (NodeUpdate surface change)
**Sources:** MindMapNode.java:26, ConfidenceDecayDecorator.java:78, MindMapAnalyzer.java:163, MindMapStoreContractTest.java:222, InMemoryMindMapStore.java:124-131 (implicit 1.0 reset), SqliteMindMapStore.java:300-303 (same)
**Exploration:** surfaced by review (R1-06, R2-02)
**Status:** revised — confirmation semantics explicitly documented as deliberate change from implicit 1.0 reset (R2-02)

## D7: Memory importance → Confidence.value mapping

**Choice:** Memory's `Double importance` maps to `Confidence.value` with `origin=UNKNOWN`. This is a deliberate semantic collapse — importance (attentional salience) and confidence (epistemic certainty) are conceptually distinct but unified under a single [0,1] scalar.
**Alternatives:**
- Keep importance separate from confidence — Memory carries both `Confidence confidence` AND `Double importance`. Preserves the semantic distinction but means the "unified confidence" is not actually unified — Memory has two numeric dimensions where other subsystems have one.
- Multi-dimensional Confidence — `Confidence(origin, certainty, salience, decayReference)`. Captures both dimensions but designs a multi-axis model without evidence of what axes are actually needed. Premature decomposition.
- Rename importance to confidence with no acknowledgment — silent conflation without explicit design rationale.
**Rationale:** The current `confidence` field on Memory has no clear semantics — it's used as a retrieval weight in `PersonalityWeightedRetrieval` and `MoodModulatedRetrieval`, where it modulates ranking. It's not a well-defined measure of either salience or certainty. Unifying under a single scalar: (1) enables cross-store comparison and ranking, (2) eliminates an ambiguous field, (3) is the prerequisite for later decomposition if real usage reveals distinct dimensions. The roadmap's terminology table explicitly retires `confidence` in favor of `origin`. If Phase 3+ work reveals that salience and certainty must be separate (e.g., "high-importance but low-confidence" patterns emerge in real agent behavior), the Confidence record can be extended then — with evidence of what decomposition is actually needed.
**Depends on:** D1 (module placement), D4 (API surface)
**Sources:** cognitive-architecture-roadmap.md §Terminology ("importance becomes value with origin=null"), Memory.java:14 (importance field), MemoryInput.java:13 (importance field), PersonalityWeightedRetrieval usage, MoodModulatedRetrieval usage
**Exploration:** surfaced by review (R1-17)
**Status:** captured

## D8: TemporalMark placement — cognitive-api

**Choice:** `TemporalMark` sealed interface goes in `cognitive-api` alongside `Confidence` and `ConfidenceOrigin`.
**Alternatives:**
- New `temporal-api` module — clean separation but adds a build artifact for a single sealed interface. cognitive-api would need to depend on it or vice versa.
- `mindmap-api` — temporal bounds already live there, but couples a cross-cutting concept to the mindmap subsystem. Memory and CBR would gain a mindmap dependency.
**Rationale:** cognitive-api is the zero-deps cross-cutting cognitive types module. TemporalMark is a cognitive concept on par with Confidence — it represents how the system understands time across all subsystems. D1 explicitly anticipated this placement.
**Trade-offs:** cognitive-api grows from 2 to 4 types (TemporalMark + 3 inner records). Still lean.
**Depends on:** D1 (module placement established cognitive-api as the cross-cutting home)
**Sources:** cognitive-architecture-roadmap.md §2a, cognitive-api/src/main/java/io/casehub/neocortex/cognitive/
**Exploration:** quick
**Status:** captured

## D9: Ordinal resolution — carry pre-resolved Instant

**Choice:** `Ordinal(String turnId, Instant resolved)` — the wall-clock timestamp is resolved at construction time and carried within the record. `resolveToInstant()` returns the pre-resolved value. No separate `sequence` field — no event type currently provides one, and sub-turn ordering can be encoded in the turnId if needed.
**Alternatives:**
- External resolver function — `resolveToInstant(Function<String, Instant> turnResolver)`. More flexible but every call site needs a resolver, and cognitive-api can't reference store types.
- Fallback to now — `resolveToInstant(Instant now)` always returns `now` for ordinals. Simplest but loses temporal precision entirely.
**Rationale:** A zero-deps type can't perform lookups. Carrying the resolved timestamp makes the mark self-contained — once constructed, it can be sorted, compared, and persisted without external dependencies. The resolver is the caller's responsibility at construction time, not at usage time. The turnId is retained alongside the resolved Instant for audit/traceability: it links the temporal mark back to its conversational origin ("this timestamp came from turn X"). This enables debugging (why is this event at this time?), provenance tracking, and potential re-resolution if timestamps are corrected. The resolved Instant is sufficient for sorting and comparison; the turnId serves the orthogonal concern of provenance.
**Trade-offs:** Resolution must happen at construction. If the turn-timestamp mapping changes later, the mark becomes stale. Acceptable — timestamps of past turns don't change. Ordinal carries turnId even after resolution — this is intentional for audit, not dead weight.
**Sources:** cognitive-architecture-roadmap.md §2a (resolveToInstant design), ExperienceEvent.java (turnId field), memory-api event types
**Exploration:** quick
**Status:** revised — documented turnId audit/traceability purpose (R1-13)

## D10: Relative anchor nullability — nullable

**Choice:** `Relative(Duration offset, @Nullable Instant anchor)`. When anchor is null, `resolveToInstant(Instant now)` returns `now + offset`. When non-null, returns `anchor + offset`.
**Alternatives:**
- Required anchor — caller must pin to a concrete Instant at construction. Simpler but loses the distinction between "3 days from now" (floating, re-resolves each call) and "3 days after the meeting" (pinned).
**Rationale:** LLM-extracted temporal references naturally produce both kinds: "next week" (relative to now, floating) and "3 days after the project started" (relative to a known anchor). The nullable anchor preserves this distinction for downstream consumers.
**Trade-offs:** Floating relative marks produce different values on each `resolveToInstant()` call. Consumers that persist the resolved value should call once and store the result.
**Sources:** cognitive-architecture-roadmap.md §2a, MindMapExtractor temporal parsing use case
**Exploration:** quick
**Status:** captured

## D11: Scope — type definition only, no adoption

**Choice:** #234 defines `TemporalMark` in cognitive-api with factory methods and `resolveToInstant()`. Adoption on existing types is scoped to follow-up issues: #235 (event timestamps), #236 (temporal MindMapQuery).
**Alternatives:**
- Type + MindMapExtractor integration — also integrate into temporal parsing. Bigger scope, crosses into mindmap.
- Type + all event timestamps — combine with #235. Scope creep, M → L.
**Rationale:** The type is independently valuable and testable. Follow-up issues are already defined in the queue. Keeping this S-sized means it can land quickly.
**Trade-offs:** TemporalMark exists but nothing uses it until #235/#236. Brief period of unused code.
**Sources:** .plan queue (#235, #236 follow immediately), issue #234 scope
**Exploration:** quick
**Status:** captured

## D12: Multi-tenant parameters — no MemorySpace dependency

**Choice:** `TemporalIndex.query()` takes `Collection<String> tenantIds`. No dependency on MemorySpace types (#230). Follow-up issue #254 wires the visibility layer when #230 lands.
**Alternatives:**
- Design space-aware now with placeholder types — invents types #230 will define authoritatively. Speculative infrastructure.
- Single tenantId only — forces callers to query once per space and merge results themselves. Defeats the purpose of a cross-store aggregator.
**Rationale:** MemorySpace maps spaces to tenants ("each space IS a tenant"). The only thing TemporalIndex needs from MemorySpace is a list of tenantIds to query across. A `Collection<String>` captures that without a type dependency. #254 filed as the wiring follow-up.
**Trade-offs:** TemporalIndex is space-agnostic until #254 lands. Callers must resolve spaces to tenantIds themselves in the interim.
**Sources:** cognitive-architecture-roadmap.md §1f, §2d (memory space impact), issue #230 (scope), issue #254 (follow-up)
**Exploration:** quick
**Status:** captured

## D13: Pure chronological aggregator — no salience scoring in the index

**Choice:** TemporalIndex returns `List<TemporalEntry>` sorted by timestamp. No salience scoring, no ranking policy. Ranking is a separate composable concern (D14). TemporalQuery carries a `Collection<MemoryDomain> memoryDomains` field specifying which memory domains to include when querying the Memory store. Defaults to `{experience}` — the highest-signal domain for temporal content. Callers that need affect trajectories, mood snapshots, or engagement events pass the relevant domains explicitly.
**Alternatives:**
- Ranker inside the index — `index.query(timeRange, tenantIds, ranker)`. Two responsibilities: aggregation AND ranking. Couples scoring policy to data access.
- Salience as the primary sort — TemporalIndex produces a ranked "attention list." But salience depends on context (agent's current task, affect state) that the index shouldn't know about.
- Query all memory domains by default — returns every mood snapshot, PAD change, and engagement tick. High noise-to-signal for the primary "what happened?" use case.
- Hardcoded domain filter (original implementation) — silently excludes 5 domains with no caller override. Undocumented restriction.
**Rationale:** The index answers "what happened in this time window?" — a data aggregation question. "What's important right now?" is a policy question for TemporalFocus (#244). Keeping them separate means the index is testable without scoring policy and the ranker is testable without store dependencies. The `memoryDomains` filter follows the same pattern as `sources` (StoreKind filter) — configurable with a sensible default. TemporalFocus (#244) will pass `{experience, affect}` to include affect trajectories for anticipatory processing.
**Trade-offs:** Callers that want ranked results must compose index + ranker at the call site (two lines, not one). Callers that need non-experience domains must specify them explicitly.
**Depends on:** D14 (composable ranking)
**Sources:** cognitive-architecture-roadmap.md §2d, §4b (TemporalFocus), ExperienceEvents.DOMAIN, AffectEvents.DOMAIN, MoodEvents.DOMAIN, RelationshipEvents.DOMAIN, ReflectionEvents.DOMAIN, EngagementEvents.DOMAIN
**Exploration:** quick
**Status:** revised — added configurable memoryDomains filter to TemporalQuery, replacing hardcoded EXPERIENCE_DOMAIN (R1-04)

## D14: Composable ranking via standalone TemporalRanker

**Choice:** `TemporalRanker` is a standalone `@FunctionalInterface` in cognitive-index: `double score(TemporalEntry entry, Instant now)`. Composes with TemporalIndex output at the call site. Default implementation: recency-based (newer = higher). TemporalFocus (#244) becomes a TemporalRanker implementation, not a separate utility wrapping the index.
**Alternatives:**
- Ranker as index parameter — couples ranking to query execution (violates D13).
- No ranker abstraction — TemporalFocus as a wrapper class. Works but duplicates the aggregation call; new ranking strategies require new wrapper classes.
**Rationale:** Runtime composable without coupling. The index produces data, the ranker re-orders it. They're orthogonal types connected by `List<TemporalEntry>`. TemporalFocus (#244) shrinks from "aggregation + ranking utility" to "a TemporalRanker implementation" — S-sized instead of M.
**Trade-offs:** Ranker can't influence what the index fetches (e.g., can't skip CBR results if only MindMap events are salient). Acceptable — the index already supports selective store querying via configuration.
**Sources:** cognitive-architecture-roadmap.md §4b (TemporalFocus), FusionStrategy pattern in fusion-api, OutcomeWeightingFunction pattern in memory-api
**Exploration:** quick
**Status:** captured

## D15: Sealed TemporalSource on TemporalEntry — zero information loss

**Choice:** `TemporalEntry` carries a `TemporalSource` sealed interface with variants: `FromMindMap(MindMapNode node)`, `FromMemory(Memory memory)`, `FromCbr(ScoredCbrCase<?> cbrCase)`. Callers pattern-match to access the original object.
**Alternatives:**
- Normalized common shape — flatten to `(id, text, source, timestamp, confidence)`. Lossy — callers needing node-specific fields (validUntil, traits, PAD) require a second query.
- Opaque Object payload — maximum flexibility, zero type safety. Defeats sealed exhaustiveness.
**Rationale:** The index aggregates heterogeneous results. Pattern matching gives callers type-safe access to the full original object. Sealed interface ensures exhaustive switch coverage — adding a new store variant is a compile error at every consumer. Zero information loss means no second query needed.
**Trade-offs:** TemporalEntry carries the full store object (MindMapNode, Memory, ScoredCbrCase). Memory footprint is higher than a normalized shape. Acceptable — these are query results already in memory.
**Sources:** MindMapNode.java (validFrom, validUntil, traits, PAD), Memory.java (createdAt, confidence), ScoredCbrCase (caseId, score)
**Exploration:** quick
**Status:** captured

## D16: Module placement — new cognitive-index module

**Choice:** New `cognitive-index` module. Depends on cognitive-api + mindmap-api + memory-api. Houses TemporalIndex (CDI bean), TemporalEntry, TemporalSource, TemporalRanker. Clear growth path for CognitiveProfile (#243) and TemporalFocus (#244).
**Alternatives:**
- cognitive-api + separate module — types in cognitive-api (zero-dep), bean in new module. Splits the concept across two modules. cognitive-api's growth path (D1) anticipated cross-cutting types, not cross-store query utilities.
- Existing mindmap module — couples a cross-store concern to a single store's CDI wiring. MindMap is the primary temporal source but not the only one.
**Rationale:** `cognitive-index` names the architectural role: cross-store cognitive queries. It's the natural home for all Phase 4 utilities (CognitiveProfile, TemporalFocus) that span MindMap + Memory + CBR. cognitive-api stays zero-deps for value types; cognitive-index is the query/service tier above it.
**Trade-offs:** Adds a module to the build. But the alternative is scattering cross-store concerns across individual store modules.
**Depends on:** D1 (cognitive-api established as cross-cutting type home)
**Sources:** cognitive-architecture-roadmap.md §4a (CognitiveProfile), §4b (TemporalFocus), module structure in CLAUDE.md
**Exploration:** quick
**Status:** captured

## D17: CDI bean with Instance<T> for graceful degradation

**Choice:** `TemporalIndex` is `@ApplicationScoped`. Injects stores via `Instance<MindMapStore>`, `Instance<CaseMemoryStore>`, `Instance<CbrCaseMemoryStore>`. Checks `isResolvable()` at query time. Missing stores are silently skipped — the index returns entries from whatever stores are available.
**Alternatives:**
- Optional constructor params (nullable) — similar effect but requires explicit null-checking. Less idiomatic in Quarkus/CDI.
- Require all stores — hard dependency. Forces apps to include all store modules even when they only use one subsystem. Breaks the modular classpath design.
**Rationale:** An app using only MindMap should get temporal indexing for MindMap nodes without pulling in memory-sqlite or memory-qdrant. `Instance<T>.isResolvable()` is the standard Quarkus pattern for optional dependencies — used throughout the codebase (e.g., `MultiModalEmbedderProducer`, `SeparateModelEmbedder`).
**Trade-offs:** Runtime discovery means a missing store is a silent no-op, not a compile error. Acceptable — TemporalIndex is enrichment, not critical path.
**Sources:** MultiModalEmbedderProducer.java (Instance pattern), SeparateModelEmbedder.java (optional SparseEmbedder), CbrReconciliationService.java (optional EmbeddingModel)
**Exploration:** quick
**Status:** captured

## D18: Stateless query aggregator — not a materialized index

**Choice:** TemporalIndex is a stateless query aggregator. It re-queries the underlying stores on every call and merges results. No persistence, no materialized data structure, no update mechanism. Same architectural category as `MindMapAnalyzer` and `RetrievalAnalyzer`. All public classes carry javadoc explaining this intent.
**Alternatives:**
- Materialized SPI (interface + inmem + SQLite backends) — maintains a persistent sorted timeline, updated via CDI events or polling. Higher query efficiency (O(log n) lookup) but introduces dual-write consistency burden, requires store mutation events that don't exist today, and adds SPI + 2 backends for something that could be 50 lines of orchestration.
- Cached aggregator — stateless with a TTL cache. Middle ground but premature optimisation — the underlying stores already have temporal indexes.
**Rationale:** The underlying stores already persist the data and have temporal indexes (#236). Each store query is O(log n) on an indexed column. Worst case per call: 3 tenants × 3 stores = 9 indexed queries — negligible for a tick-loop consumer. A materialized index would create a second source of truth with consistency obligations (the `CbrReconciliationService` problem) for zero practical gain. If performance becomes a concern: (1) add TTL cache to CDI bean (minutes of work), (2) add CDI event listeners + in-memory sorted set (hours), (3) materialize only if query frequency reaches thousands/second (unlikely).
**Trade-offs:** Every call re-queries stores. No pre-computed result. Acceptable — 9 indexed queries take microseconds-to-milliseconds. The tick loop runs at most every few seconds.
**Sources:** MindMapAnalyzer.java (same pattern — pure computation over store data), RetrievalAnalyzer.java (same pattern), CbrReconciliationService.java (cautionary — dual-write complexity), cognitive-coherence-audit.md §Temporal (CuriositySignalGenerator tick loop)
**Exploration:** deep-analysis
**Status:** captured

## D19: Event trait classification — property-driven, two-axis

**Choice:** Event traits use two orthogonal properties: `eventKind` (scheduled / anticipated) and `eventValence` (positive / negative / aspirational). TraitRules match on these explicit properties, not on PAD inference.
- Appointable: `event-kind=scheduled` (valence irrelevant — a funeral is appointable)
- Aspirational: `event-kind=anticipated` + `event-valence=aspirational`
- Threatening: `event-kind=anticipated` + `event-valence=negative`
- Opportunistic: `event-kind=anticipated` + `event-valence=positive`
**Alternatives:**
- PAD-derived — infer valence from PAD values (Threatening = negative pleasure). Fragile: PAD boundaries are arbitrary and overlapping; a stressful job interview has complex PAD.
- Hybrid (explicit + PAD fallback) — two code paths, harder to test, unclear which classification the consumer is seeing.
- Single `event-type` property — forces mutual exclusivity (can't be both Appointable and Threatening).
- Boolean properties per trait — verbose for LLM extractors.
**Rationale:** Orthogonality. An event CAN carry both `event-kind=scheduled` (Appointable) AND `event-valence=negative` (Threatening). The axes are independent — kind is about temporal fixedness, valence is about emotional anticipation. PAD inference is fragile because emotional nuance doesn't map cleanly to thresholds. Explicit properties are set by the LLM extractor or the caller — the source that understands the semantic context.
**Trade-offs:** Requires the extractor to set the right properties. If `eventKind` is missing, no event traits fire — but this is the correct behavior (not every future-dated node is an "event").
**Sources:** TraitRule.java (matches on node + edges), PersonableTraitRule.java (property-checking pattern), ProjectlikeTraitRule.java (status property check), cognitive-architecture-roadmap.md §3c
**Exploration:** quick
**Status:** captured

## D20: Anticipatory affect — explicit caller via AffectType enum

**Choice:** New `AffectType` enum (`INHERENT`, `ANTICIPATORY`) in `cognitive-api` (see D25 for placement). `AffectEvents` gets a new overload: `toMemoryInput(nodeId, tenantId, p, a, d, AffectType type)` that sets `affect-type` attribute. The existing overload defaults to `INHERENT`. `AffectTrajectoryDecorator` always logs as `INHERENT` — it captures the node's PAD, which is inherent affect. Anticipatory affect is logged by a separate caller.
**Alternatives:**
- Auto-detect from `validFrom` — if node is future-dated, tag as ANTICIPATORY. Conflates "future node" with "anticipatory affect." You can set inherent affect on a future event (a funeral's inherent sadness ≠ the agent's anticipatory grief).
- Dual-PAD on NodeUpdate — 6 PAD fields (3 inherent + 3 anticipatory). Heavy record expansion for a distinction that belongs in the memory log, not on the node itself.
**Rationale:** Inherent affect is a property of the event ("funerals are sad"). Anticipatory affect is a property of the agent's emotional response to the event ("I dread the funeral"). These are set by different actors at different times — inherent by the extractor at creation, anticipatory by the agent as the event approaches. Separate entry points enforce this distinction.
**Trade-offs:** Anticipatory affect logging is not automatic — the consuming application must call the overloaded converter explicitly. This is intentional: neocortex provides the mechanism; the agent framework (blocks) provides the trigger.
**Depends on:** D19 (event trait classification — anticipatory affect only applies to nodes with event traits)
**Sources:** AffectEvents.java (converter pattern), AffectTrajectoryDecorator.java (intercepts updateNode), AffectRecorded.java (CDI event), cognitive-architecture-roadmap.md §3c
**Exploration:** quick
**Status:** captured

## D21: Event lifecycle — convention only, no transition validation

**Choice:** Lifecycle states (PLANNED, CONFIRMED, ACTIVE, COMPLETED, CANCELLED, REVIEWED) are property values on the `status` key. No transition enforcement — the system does not validate that status changes follow legal paths. TraitRules apply traits based on current status. Enforcement is the caller's responsibility.
**Alternatives:**
- Validated transitions via decorator — a new decorator validates state transitions against a legal transition table. Robust but couples MindMapStore to lifecycle semantics. The generic property system shouldn't know about event-specific state machines.
- Convention + affect audit log — no enforcement but every status change logs an affect entry. Appealing but the AffectTrajectoryDecorator already logs PAD changes — status changes without PAD changes don't need affect logging.
**Rationale:** The existing pattern. `ProjectlikeTraitRule` already checks `node.property("status").isPresent()` with no validation of values. Lifecycle enforcement belongs in the application layer (blocks/engine), not in the storage layer. MindMapStore is a graph database, not a business rule engine. The status values are documented in the consumer guide as a convention.
**Trade-offs:** Nothing prevents invalid transitions (COMPLETED → PLANNED). Acceptable for a pre-release platform where the caller is always the LLM extractor or agent framework.
**Sources:** ProjectlikeTraitRule.java:18 (status check), cognitive-architecture-roadmap.md §3c, GE-20260803-263c2c (state machine technique — relevant for consumers, not for the storage layer)
**Exploration:** quick
**Status:** captured

## D22: RRULE — RecurrenceRule record in mindmap-api, generator in mindmap-intelligence

**Choice:** `RecurrenceRule` record in `mindmap-api`: `RecurrenceRule(Frequency freq, int interval, Integer count, Instant until, Set<DayOfWeek> byDay)` with `parse(String rrule)` factory and `toString()` that produces an RRULE string. Minimal RFC 5545 subset: FREQ (DAILY/WEEKLY/MONTHLY/YEARLY), INTERVAL, COUNT, UNTIL, BYDAY. `RecurrenceGenerator` static utility in `mindmap-intelligence`: `List<NodeInput> generateInstances(MindMapNode template, RecurrenceRule rule, Instant horizon)`. Property key on template: `rrule`. Pure function, no CDI.
**Alternatives:**
- Full RRULE via library — RFC 5545 with EXDATE, RDATE, WKST, BYMONTH, BYHOUR. Heavy dependency for a string property on a graph node.
- Opaque string, no parsing — maximum simplicity but defers all value to the caller. The generator couldn't exist in neocortex.
- CDI bean generator — @ApplicationScoped for injection. Adds CDI weight for a stateless pure function.
**Rationale:** The minimal subset covers the core recurrence patterns needed for cognitive modelling (weekly team meeting, monthly review, annual check-up). The record in `mindmap-api` enables downstream consumers (e.g., calendar queries in blocks) to parse the property without depending on mindmap-intelligence. The generator in mindmap-intelligence follows the `CuriositySignalGenerator` pattern — a static utility that operates on graph data.
**Trade-offs:** No EXDATE/RDATE support — can't express "every Monday except holidays." For a cognitive model, exceptions are handled by cancelling individual instance nodes (`status=CANCELLED`), which captures the cancellation as a cognitively meaningful event with its own affect. EXDATE is a planned extension for v2 when declarative exception intent becomes necessary.
**Planned extensions:** EXDATE (exception dates), RDATE (additional dates), BYMONTH, BYHOUR — added when real usage demonstrates need beyond the core cognitive model.
**Sources:** RFC 5545 §3.3.10 (RRULE), CuriositySignalGenerator.java (static utility pattern), cognitive-architecture-roadmap.md §3c
**Exploration:** quick
**Status:** captured

## D23: Template-to-instance relationship — property link

**Choice:** Instance nodes carry `template-node-id=<templateId>` and `recurrence-index=<N>` as properties. The template node carries `rrule=<RRULE string>`. No edges between template and instances.
**Alternatives:**
- NodeRef — `NodeRef(scheme="template", id=templateId)`. Type-safe but NodeRefs are for cross-system references (memory, cbr), not intra-graph relationships.
- Edge — `edge-type="recurrence-instance"` from template to each instance. Graph-native but creates O(n) edges that clutter the graph and trigger DerivedEdgeRule evaluation for each.
**Rationale:** Properties are the simplest mechanism for metadata that doesn't need graph traversal. "Find all instances of this template" is a property-value search, not a graph walk. Edges are for semantic relationships between entities; template→instance is an implementation relationship.
**Trade-offs:** No graph-native traversal from template to instances. Property search works but isn't indexed in InMemoryMindMapStore's `search()` (it does text matching across values, not exact property-value lookup). Acceptable — the use case is rare and n is small.
**Depends on:** D22 (RRULE design)
**Sources:** MindMapNode.properties() (property system), NodeRef.java (cross-system ref), DerivedEdgeDecorator.java (edge-triggered rules)
**Exploration:** quick
**Status:** captured

## D24: Trait interface — single Eventlike interface

**Choice:** One `Eventlike` interface covering all event-related properties: `eventKind()`, `eventValence()`, `status()`, `rrule()`. Accessible via `TraitProxy.as(node, Eventlike.class)`. Separate from the 4 TraitRule implementations — the rules classify, the interface provides typed access.
**Alternatives:**
- Per-trait interfaces (Appointable, Aspirational, Threatening, Opportunistic) — follows existing pattern (Personable, Projectlike, Organisational). But event traits share a property namespace — splitting creates 4 interfaces with overlapping property access.
**Rationale:** Event traits share a property namespace (`eventKind`, `eventValence`, `status`, `rrule`). A node classified as both Appointable and Threatening has the same properties accessible through either trait. One interface avoids duplication and provides a single typed view of all event properties. The existing pattern (one interface per trait) works when traits have disjoint property namespaces; event traits don't.
**Trade-offs:** Consumer code uses `Eventlike` for all event types rather than a specific interface. The trait name (`Set<String> traits()`) still carries the specific classification.
**Depends on:** D19 (event trait properties)
**Sources:** Personable.java (trait interface pattern), TraitProxy.java (JDK Proxy accessor), cognitive-architecture-roadmap.md §3c
**Exploration:** quick
**Status:** captured

## D25: AffectType enum placement — cognitive-api

**Choice:** `AffectType` enum in `io.casehub.neocortex.cognitive` alongside `ConfidenceOrigin` and `TemporalMark`. Values: `INHERENT`, `ANTICIPATORY`.
**Alternatives:**
- memory-api alongside AffectEvents converter — colocates with the only current producer, but organizes by implementation proximity, not concept ownership. Future consumers (MindMap decorators distinguishing inherent from anticipatory PAD, CBR cases carrying affect type for retrieval scoring) would depend on memory-api for a cognitive concept.
- mindmap-api — it's about MindMap node affect. But the enum is consumed by the memory converter, not the MindMap store.
**Rationale:** AffectType classifies emotional relationship to events — a cognitive distinction independent of which subsystem produces or consumes it. Conceptually parallel to ConfidenceOrigin (classifies certainty provenance) and TemporalMark (classifies temporal reference). Meets the cognitive-api acceptance criteria (D26): it models a cognitive classification, is subsystem-independent, and its nature is inherently cross-cutting. AffectEvents in memory-api imports it, same as it already imports ConfidenceOrigin from cognitive-api. cognitive-api remains zero-deps — AffectType is a two-value enum.
**Trade-offs:** AffectEvents must import from cognitive-api instead of a local type. This is the same pattern it already uses for ConfidenceOrigin.
**Sources:** AffectEvents.java (converter), ConfidenceOrigin.java (parallel pattern), cognitive-api package structure, D26 (acceptance criteria)
**Exploration:** quick
**Status:** revised — moved from memory-api to cognitive-api per cognitive classification principle (R1-05)

## D26: cognitive-api acceptance criteria

**Choice:** cognitive-api houses types that classify or qualify cognitive state independent of which subsystem produced or consumes them. A type belongs in cognitive-api when it meets ALL of: (1) it models a cognitive classification or qualification (not domain-specific logic or converter utilities), (2) it is independent of any particular subsystem's implementation details, (3) it is zero-deps (no transitive dependencies beyond java.base). Types that are inherently cross-cutting by nature qualify even when currently consumed by a single subsystem — placement is by concept ownership, not consumer count.
**Alternatives:**
- Consumer-count heuristic — place in cognitive-api when 2+ subsystems use it. Fragile: a type can have one consumer today and three tomorrow. Leads to premature placement in module-specific packages followed by disruptive moves.
- Producer-count heuristic — place where the only producer lives. Conflates implementation proximity with concept ownership. The producer is an implementation detail; the concept's nature is architectural.
- No criteria — ad-hoc reasoning per decision. Leads to inconsistent placement over time as different decision-makers apply different heuristics.
**Rationale:** The criterion separates concept ownership (architectural) from implementation proximity (mechanical). ConfidenceOrigin classifies certainty provenance, TemporalMark classifies temporal reference, AffectType classifies emotional relationship — all are cognitive classifications independent of the subsystem that produced the data. The "by nature" clause prevents reactive placement: a cognitive concept placed in a module-specific package just because it has one consumer today will need to move when the second consumer arrives. cognitive-api's zero-deps constraint (D1) is an additional filter — types that carry dependencies belong in their module's API, not cognitive-api.
**Current members:** Confidence, ConfidenceOrigin, TemporalMark (WallClock, Relative, Ordinal), AffectType (D25).
**Trade-offs:** Requires judgment about whether a type is a "cognitive classification" — but that judgment is now explicit and documented rather than implicit and ad-hoc.
**Depends on:** D1 (module placement)
**Sources:** D1 (cognitive-api module), D8 (TemporalMark placement reasoning), D25 (AffectType placement reasoning), cognitive-architecture-roadmap.md §Principles (Orthogonality)
**Exploration:** surfaced by review (R1-10)
**Status:** captured

## D27: Trajectory data access — direct dependency on memory-api + cognitive-index

**Choice:** Add `memory-api` and `cognitive-index` as dependencies to `mindmap-intelligence`. `CuriositySignalGenerator` injects `Instance<CaseMemoryStore>` for graceful degradation. `AffectTrajectoryAnalyzer` is a static utility — no runtime coupling.
**Alternatives:**
- Trajectory provider SPI — define interface in mindmap-api, cognitive-index implements. Clean but adds an interface for a single consumer.
- Pass trajectory externally — caller pre-computes and passes. Pushes complexity to every caller.
**Rationale:** Follows the established `Instance<T>` pattern (AffectTrajectoryDecorator, MultiModalEmbedderProducer). No circular dependencies — cognitive-index depends on mindmap-api, not mindmap-intelligence.
**Trade-offs:** mindmap-intelligence gains two new dependencies. Acceptable — it already depends on mindmap, mindmap-api, and quarkus-arc.
**Sources:** CuriositySignalGenerator.java, AffectTrajectoryDecorator.java (Instance pattern), cognitive-architecture-roadmap.md §3e
**Exploration:** quick
**Status:** captured

## D28: PROXIMITY signals participate in trajectory modulation

**Choice:** Remove the `if (category == PROXIMITY) continue` skip. PROXIMITY signals get trajectory-based modulation: worsening boosts, improving dampens. Implements the roadmap's "approaching + worsening = highest priority" temporal-affective interplay.
**Alternatives:**
- Keep PROXIMITY exempt — simpler but misses the roadmap's core design (temporal-affective interplay is the point of this issue).
**Rationale:** The roadmap explicitly defines the interplay matrix (approaching + worsening = highest priority, approaching + improving = lower priority). Exempting PROXIMITY signals defeats the purpose.
**Trade-offs:** PROXIMITY scores now depend on memory data. If no CaseMemoryStore, fallback to snapshot preserves current behavior.
**Depends on:** D27 (dependency access)
**Sources:** CuriositySignalGenerator.java:185, cognitive-architecture-roadmap.md §3e (temporal-affective interplay table)
**Exploration:** quick
**Status:** captured

## D29: Trajectory thresholds via CuriosityConfig record

**Choice:** New `CuriosityConfig` record in `mindmap-intelligence` with trajectory dampening thresholds. Provides `defaults()` factory. Injected into `CuriositySignalGenerator` via `Instance<CuriosityConfig>` — falls back to `CuriosityConfig.defaults()` if not provided. Also absorbs existing hardcoded constants (PROXIMITY_SCALE, STALE_THRESHOLD, MAX_BFS_DEPTH, TOP_CENTRALITY).
**Alternatives:**
- Quarkus `@ConfigMapping` — config-file driven. More flexible but the thresholds are agent-specific (per cognitive profile), not deployment-specific. Config mapping is the wrong layer.
- Keep hardcoded — simplest but the thresholds are tunable parameters that belong in configuration.
**Rationale:** A record with defaults matches the Phase 5 roadmap design — `CuriosityConfig` will eventually be produced from cognitive profile YAML. For now, a CDI-injectable record with sensible defaults gives testability (pass config in tests) and future YAML wiring without adding config-file infrastructure.
**Trade-offs:** Adds a type for what was previously 4 constants. Worth it — the trajectory thresholds double the number of tunable parameters.
**Depends on:** D27 (trajectory access)
**Sources:** CuriositySignalGenerator.java:26-29 (existing constants), cognitive-architecture-roadmap.md §5c (cognitive profile YAML)
**Exploration:** quick
**Status:** captured

## D30: TemporalFocus — pure static utility with pre-computed trajectories

**Choice:** `TemporalFocus` is a pure static utility in `cognitive-index`. `focus(entries, now, trajectories, config)` returns `List<AttentionItem>`. `ranker(trajectories, config)` returns a composable `TemporalRanker`. No CDI, no store access. Caller pre-computes `Map<String, AffectTrajectory>` and passes it in.
**Alternatives:**
- CDI bean with Instance<CaseMemoryStore> — queries trajectories on demand. Convenient but impure, introduces N+1 query risk.
- Wrapper class around TemporalIndex — violates D13 (pure chronological aggregator) and D14 (composable ranking).
**Rationale:** Follows the established static utility pattern (AffectTrajectoryAnalyzer, MindMapAnalyzer, RetrievalAnalyzer). Pure function = testable without CDI, composable with any TemporalIndex query. Pre-computed trajectories avoid N+1 queries.
**Trade-offs:** Caller must pre-compute trajectories. Acceptable — the tick-loop caller queries trajectories once and passes them to both CuriositySignalGenerator and TemporalFocus.
**Depends on:** D13 (pure aggregator), D14 (composable ranker)
**Sources:** TemporalRanker.java (functional interface), AffectTrajectoryAnalyzer.java (static utility pattern), cognitive-architecture-roadmap.md §4b
**Exploration:** quick
**Status:** captured

## D31: TemporalFocusConfig — tunable scoring parameters

**Choice:** `TemporalFocusConfig` record in `cognitive-index` with scoring parameters: `proximityScale` (7.0), `worseningBoostCap` (1.0), `improvingDampenFactor` (0.5), `volatilityBoostCap` (0.5). `defaults()` factory. Passed to `focus()` and `ranker()`. Aligns with Phase 5 cognitive profile YAML.
**Alternatives:**
- Hardcoded constants — simple but inconsistent with D29 (CuriosityConfig established the configurable-thresholds pattern).
**Rationale:** Consistency with CuriosityConfig. Both are cognitive scoring utilities whose parameters will be derived from the cognitive profile YAML in Phase 5.
**Depends on:** D29 (CuriosityConfig pattern)
**Sources:** CuriosityConfig.java (pattern), cognitive-architecture-roadmap.md §5c
**Exploration:** quick
**Status:** captured

## D32: AttentionItem record — scored entry with reason

**Choice:** `AttentionItem(TemporalEntry entry, double salience, String reason)` record in `cognitive-index`. Implements `Comparable<AttentionItem>` (descending salience). Reason is a human-readable string: "approaching event", "recent experience", "worsening affect".
**Alternatives:**
- Extend TemporalEntry with score+reason — breaks the clean separation between data (entry) and scoring (ranker).
- Enum-based reason — structured but limits expressiveness; string is simpler and the reason is for display, not programmatic use.
**Rationale:** Clean composition: TemporalEntry is data, AttentionItem is scored data. The record is the return type of `focus()` — it carries everything a consumer needs to render an attention list.
**Depends on:** D30 (TemporalFocus design)
**Sources:** TemporalEntry.java (entry record), cognitive-architecture-roadmap.md §4b
**Exploration:** quick
**Status:** captured

## D33: CognitiveProfile API shape — CDI bean + query object

**Choice:** `CognitiveProfile` is an `@ApplicationScoped` CDI bean in `cognitive-index`, following TemporalIndex's pattern. Injects `Instance<MindMapStore>`, `Instance<CaseMemoryStore>`, `Instance<CbrCaseMemoryStore>` for graceful degradation. Single `resolve(CognitiveProfileQuery)` method returns `Optional<EntityKnowledge>`. Dual-constructor pattern (CDI + test) for testability.
**Alternatives:**
- Static utility (like TemporalFocus) — pushes all query orchestration to the caller, defeating the single-call aggregation purpose.
- Hybrid CDI orchestrator + static assembler — unnecessary split; the assembly logic is trivial record construction.
**Rationale:** The value proposition is one-call cross-store aggregation. That requires store access, which means CDI. `Instance<T>` graceful degradation means apps without all three stores still work. The query object is extensible (future space parameters without breaking callers).
**Trade-offs:** Requires CDI for real-world use; test constructor mitigates.
**Depends on:** D13 (pure aggregator precedent in cognitive-index)
**Sources:** TemporalIndex.java (CDI + Instance pattern), CognitiveProfile roadmap §4a, GE-20260805-a28f5b (three-tier CDI composition)
**Exploration:** quick
**Status:** captured

## D34: Module placement — cognitive-index

**Choice:** `CognitiveProfile` lives in `cognitive-index` alongside `TemporalIndex`, `TemporalFocus`, and `AffectTrajectoryAnalyzer`.
**Alternatives:**
- New `cognitive-profile` module — adds a module for one utility. cognitive-index already has the right dependencies and description ("cross-store cognitive query tier").
- mindmap-intelligence — wrong level; mindmap-intelligence is about MindMap-specific analysis, not cross-store aggregation.
**Rationale:** cognitive-index already depends on mindmap-api and memory-api, has CDI wiring (jakarta.enterprise, jakarta.inject), and test deps on mindmap-inmem and memory-inmem. CognitiveProfile fits the module's purpose exactly.
**Trade-offs:** cognitive-index grows from 11 to ~14 types. Acceptable — they're all cross-store query utilities.
**Sources:** cognitive-index/pom.xml (dependencies), cognitive-architecture-roadmap.md §4a
**Exploration:** quick
**Status:** captured

## D35: Entity resolution input — factory methods on query record

**Choice:** `CognitiveProfileQuery` record with factory methods: `byId(nodeId, tenantId)` resolves via `getNode`; `byName(entityName, tenantId)` and `byName(entityName, subgraphId, tenantId)` resolve via `resolveNode`. Internally: `nodeId` and `entityName` are nullable fields with validation (exactly one must be non-null). `withX()` immutable builder methods follow CbrQuery's pattern.
**Alternatives:**
- Sealed `EntityRef` input type (`ById`, `ByName`) — cleaner type safety but adds a type for two-variant dispatch. The validation approach is simpler and consistent with how CbrQuery handles optional fields.
- Overloaded `resolve()` methods — fragile; adding parameters means new overloads. Query object scales better.
**Rationale:** Factory methods express intent clearly (`byId` vs `byName`), nullable fields with validation are idiomatic for this codebase, and the `withX()` pattern is proven (CbrQuery, MemoryQuery).
**Trade-offs:** Runtime validation vs compile-time — accepted because the codebase pattern is consistent.
**Depends on:** D33 (CDI bean + query object)
**Sources:** CbrQuery.java (withX pattern), MindMapStore.resolveNode/getNode signatures
**Exploration:** quick
**Status:** captured

## D36: EntityKnowledge record structure

**Choice:** `EntityKnowledge(MindMapNode node, List<MindMapEdge> edges, Map<MemoryDomain, List<Memory>> memories, AffectTrajectory trajectory, Set<NodeRef> unresolvedRefs, String tenantId)`. Node is non-null (Optional.empty() from resolve() if not found). Edges are direct connections. Memories keyed by domain — only requested domains appear. Trajectory always computed from affect memories if available (cheap). UnresolvedRefs captures NodeRefs (e.g., scheme="cbr") that couldn't be followed.
**Alternatives:**
- Flat record with named domain fields (`List<Memory> experiences`, `List<Memory> relationships`, etc.) — hardcodes domains at the type level; new domains need record changes.
- Wrapper types per section (`MemorySection`, `GraphSection`) — unnecessary nesting; the record is already structured by field name.
**Rationale:** `Map<MemoryDomain, List<Memory>>` is the natural structure — open to new domains without type changes. Trajectory as a derived field (computed from affect memories) follows the TemporalFocus pattern (derived scores from raw entries). UnresolvedRefs acknowledges known gaps (CBR has no get-by-ID) without silently dropping information.
**Trade-offs:** Caller must check `memories.containsKey(domain)` rather than calling a typed getter. Acceptable — Map access is idiomatic.
**Depends on:** D33, D37 (graph depth), D38 (domain scope)
**Sources:** AffectTrajectoryAnalyzer.java (trajectory computation), MindMapNode.refs() (NodeRef set), MemoryDomain.java (domain type)
**Exploration:** quick
**Status:** captured

## D37: Graph depth — entity + direct edges

**Choice:** Include the entity's direct edges (`List<MindMapEdge>` from `neighbors(nodeId, tenantId)`) but do NOT recursively resolve adjacent nodes. The edges carry target node IDs and edge types — enough for "who is Alice connected to?" without triggering cascading resolution.
**Alternatives:**
- Entity only — callers who want edges call `neighbors()` separately. Works but forces two calls for a common use case.
- Entity + full neighbor resolution — richer but expensive. Each neighbor could trigger its own memory/trajectory lookups. Risk of recursive fan-out.
**Rationale:** Edges are cheap (single store call) and provide essential structural context. Full neighbor resolution is a profile-per-neighbor operation — the caller can resolve specific neighbors if needed.
**Trade-offs:** No neighbor properties/traits in the result; caller must resolve individually.
**Sources:** MindMapStore.neighbors() signature, cognitive-architecture-roadmap.md §4a
**Exploration:** quick
**Status:** captured

## D38: Domain scope — configurable via query, default all

**Choice:** `CognitiveProfileQuery` has a `Set<MemoryDomain> domains` field. Empty set = all known domains (experience, relationship, reflection, mood, engagement, affect). Callers specify a subset to skip expensive queries. `withDomains(Set<MemoryDomain>)` builder method.
**Alternatives:**
- Always all — simpler API but 6+ store queries per call even when only one aspect is needed.
- Tiered presets (SUMMARY, SOCIAL, FULL) — middle ground but restrictive; callers may want arbitrary combinations.
**Rationale:** Follows CbrQuery's configurability pattern. Default-all is the "tell me everything" use case; selective is for efficiency when the caller knows what they need.
**Trade-offs:** More complex query object than a simple method signature. Acceptable — the withX() pattern keeps construction readable.
**Depends on:** D33 (query object)
**Sources:** CbrQuery.java (configurable query pattern), memory domain constants in ExperienceEvents/RelationshipEvents/etc.
**Exploration:** quick
**Status:** captured

## D39: Memory entity ID resolution — query both nodeId + node name

**Choice:** When querying memories for a resolved entity, use `MemoryQuery.forEntities(List.of(nodeId, nodeName), domain, tenantId)` to catch memories stored under either convention. AffectTrajectoryDecorator stores with entityId=nodeId (UUID); other producers may use the human-readable name. MemoryQuery supports up to 25 entityIds, so adding both is safe.
**Alternatives:**
- Query nodeId only — misses memories stored with the human-readable name. Forces all future memory producers to know the internal nodeId.
- Query name + all NodeRef IDs — may pull unrelated memories if refs point to different entities.
**Rationale:** Simple, comprehensive, follows the existing multi-entityId API. The two IDs (internal UUID, human-readable name) are the two natural ways to store memories about an entity.
**Trade-offs:** May return memories from unrelated entities if a node's name collides with another entity's memory entityId. Low risk in practice — entityIds are typically scoped by domain + tenant.
**Depends on:** D36 (EntityKnowledge structure)
**Sources:** AffectTrajectoryDecorator.java:83 (entityId=nodeId pattern), AffectEvents.toMemoryInput (nodeId as entityId), MemoryQuery.forEntities (multi-entityId support)
**Exploration:** quick
**Status:** captured

## D40: NodeRef following — scheme="memory" resolved, scheme="cbr" recorded

**Choice:** CognitiveProfile follows `NodeRef(scheme="memory")` by querying memories with `ref.id()` as an additional entityId. `NodeRef(scheme="cbr")` is recorded in `EntityKnowledge.unresolvedRefs()` because `CbrCaseMemoryStore` has no get-by-ID method. Other schemes are also recorded as unresolved.
**Alternatives:**
- Add get-by-ID to CbrCaseMemoryStore — correct long-term but scope creep for this issue. Separate issue.
- Ignore NodeRefs entirely — loses the cross-store linking that the roadmap explicitly calls for.
**Rationale:** Following memory refs is natural (entityId-based query). CBR's lack of entity lookup is a known gap — recording it as unresolved makes the gap visible without blocking progress. The gap can be filled by adding `findByCaseId()` to CbrCaseMemoryStore later.
**Trade-offs:** CBR data is invisible in EntityKnowledge until the SPI is extended. Acceptable — the NodeRef records the link for future resolution.
**Depends on:** D36 (unresolvedRefs field)
**Sources:** NodeRef.java (scheme/id/qualifier), CbrCaseMemoryStore.java (no get-by-ID), AffectTrajectoryDecorator.java:83 (NodeRef convention)
**Exploration:** quick
**Status:** captured

## D41: Error handling — Optional<EntityKnowledge>

**Choice:** `CognitiveProfile.resolve()` returns `Optional<EntityKnowledge>`. `Optional.empty()` when the MindMap node doesn't exist (name/ID not found). When the node exists but some stores are unavailable, the corresponding sections are empty (empty list/map, null trajectory) — graceful degradation, not error.
**Alternatives:**
- Return EntityKnowledge with null node — meaningless result; the node is the anchor for everything else.
- Throw EntityNotFoundException — too aggressive for a query operation. Callers would need try/catch for a normal "not found" case.
**Rationale:** Optional is the Java idiom for "this entity might not exist." Graceful degradation for unavailable stores follows TemporalIndex's pattern (missing stores are silently skipped).
**Sources:** TemporalIndex.java (Instance<T> graceful degradation)
**Exploration:** quick
**Status:** captured

## D42: AffectTrajectoryAnalyzer composition

**Choice:** CognitiveProfile queries affect-domain memories via `MemoryQuery.forEntities([nodeId, nodeName], AffectEvents.DOMAIN, tenantId)`, then passes the result to `AffectTrajectoryAnalyzer.analyze()` to compute the trajectory. Pure composition — no trajectory logic duplicated.
**Alternatives:**
- Recompute trajectory inline — duplicates existing code.
- Skip trajectory (caller computes) — misses the whole-profile purpose; trajectory is a core aspect of "everything about Alice."
**Rationale:** AffectTrajectoryAnalyzer is a pure static utility designed for exactly this — computing trajectory from a list of affect memories. Composing with it keeps CognitiveProfile focused on orchestration.
**Depends on:** D39 (memory entity ID resolution)
**Sources:** AffectTrajectoryAnalyzer.java (static analyze method), AffectEvents.DOMAIN constant
**Exploration:** quick
**Status:** captured

## D43: Space-aware API — single tenantId, designed for extension

**Choice:** v1 uses `String tenantId` (single tenant). The query record is designed for future extension via `withTenantIds(Set<String>)` — each memory space maps to a tenant, so multi-space resolution is multi-tenant querying. Breaking change is acceptable (pre-release platform).
**Alternatives:**
- Start with `Set<String> tenantIds` now — premature; the memory space model (#230) isn't designed yet. Building multi-tenant query aggregation without the space model risks wrong abstractions.
- No space consideration — forces a breaking change later when spaces are added.
**Rationale:** Single tenant is correct for the current system. The query record's immutable-builder pattern means `withTenantIds()` is a non-breaking addition. When #230 lands, the extension is mechanical.
**Trade-offs:** v1 can only profile entities within one tenant. Acceptable — multi-tenant entity resolution needs the space model first.
**Sources:** cognitive-architecture-roadmap.md §4a (space impact), issue #230 (memory space model)
**Exploration:** quick
**Status:** captured

## D44: Memory space module placement — new memory-space-api

**Choice:** New `memory-space-api` module at tier-0 (zero deps). Contains MemorySpace, SpaceType, Visibility (sealed), SpaceMembership, and SpaceMembershipStore SPI. Implementations in `memory-space-inmem` (@Alternative @Priority(2)) and `memory-space-sqlite` (@Alternative @Priority(1)).
**Alternatives:**
- cognitive-api — cognitive-api houses cognitive classifications (Confidence, TemporalMark). Memory spaces are architectural infrastructure, not cognitive classification. Violates D26 acceptance criteria.
- memory-api — memory-api is CaseMemoryStore-specific. MindMap and CBR also need space awareness — putting space types in memory-api creates a wrong dependency direction.
**Rationale:** Memory spaces are cross-cutting architectural infrastructure that sits above all cognitive stores. A dedicated tier-0 module keeps the concern isolated and dependency-clean. Follows the cognitive-api pattern of zero-dep shared types.
**Trade-offs:** One more module in the reactor. Acceptable — the concern is genuinely distinct.
**Sources:** cognitive-api/pom.xml (zero-dep pattern), D26 (cognitive-api criteria), shared-memory-design.md
**Exploration:** quick
**Status:** captured

## D45: Space membership persistence — three-tier CDI

**Choice:** SpaceMembershipStore SPI with three-tier CDI: in-memory for tests, SQLite for production. Space membership is durable configuration — it can't be rederived from cognitive stores. Follows MindMapStore/CaseMemoryStore three-tier CDI priority ladder.
**Alternatives:**
- Config-file (YAML) only — can't handle runtime membership changes (temporal validity, joins/leaves).
- Hybrid (YAML bootstrap + SPI mutations) — adds complexity. The SPI handles both initial setup and runtime changes; YAML loading (Phase 5g) is a future producer.
**Rationale:** Space membership changes over time (kids grow up, partners separate). A durable SPI store is the only model that handles temporal membership correctly. The three-tier CDI pattern is proven across the codebase.
**Trade-offs:** More infrastructure than a simple config file. Justified by the temporal membership requirement.
**Depends on:** D44 (module placement)
**Sources:** shared-memory-design.md (temporal membership), MindMapStore three-tier CDI (pattern)
**Exploration:** quick
**Status:** captured

## D46: Selective visibility scope — define type, defer per-record filtering

**Choice:** Define the full `Visibility` sealed hierarchy (Private, Shared, Selective) in this issue. Defer per-record visibility filtering to a future issue — adding a Visibility field to MemoryInput/NodeInput requires store changes (out of scope). The type is ready for when stores are extended.
**Alternatives:**
- Include store field additions — makes Selective end-to-end functional but requires modifying MemoryInput, NodeInput, and store query logic. Scope creep.
- Drop Selective entirely — YAGNI for now, but the sealed hierarchy is cleaner with it defined upfront. Adding a variant to a sealed hierarchy later is a breaking change for pattern matches.
**Rationale:** Defining all three variants now makes the sealed hierarchy complete and stable. Pattern matches in the visibility layer will compile-check exhaustively from day one. Per-record filtering is a store concern — separate issue.
**Trade-offs:** Selective is defined but not functional until stores gain Visibility support.
**Depends on:** D44 (module placement)
**Sources:** shared-memory-design.md §Selective Sharing, issue scope ("no store implementation changes")
**Exploration:** quick
**Status:** captured

## D47: MemorySpace record — space-as-tenant with type tag

**Choice:** `MemorySpace(String id, SpaceType type, String name, String ownerId)`. `id` IS the tenantId for store operations. `SpaceType` enum: `PRIVATE`, `SHARED`. `ownerId` is the owning agent for PRIVATE spaces (null for SHARED). `name` is human-readable.
**Alternatives:**
- MemorySpace as a sealed interface with Private/Shared variants — adds type hierarchy for a two-value enum. The record + SpaceType enum is simpler.
- Include role definitions on the space — roles are per-membership, not per-space. The space is just the container.
**Rationale:** The key insight from the design doc: "each space IS a tenant." MemorySpace wraps a tenantId with metadata. Stores see tenantIds; the visibility layer sees MemorySpaces. Clean separation.
**Trade-offs:** `ownerId` is null for SHARED spaces — a nullable field rather than type-enforced. Acceptable for two variants.
**Depends on:** D44 (module placement)
**Sources:** shared-memory-design.md §The Model (Option A)
**Exploration:** quick
**Status:** captured

## D48: SpaceMembership record — temporal with opaque roles

**Choice:** `SpaceMembership(String agentId, String spaceId, Set<String> roles, Instant validFrom, Instant validUntil)`. Roles are opaque strings — the platform doesn't interpret them. `validUntil` nullable (null = current member). Temporal validity enables "kids grow up, partners separate."
**Alternatives:**
- Enum-typed roles — restricts to platform-defined roles. casehub-life needs domain-specific roles (financial-authority, school-authority) that the platform shouldn't know about.
- No temporal validity — can't model membership changes over time. The shared-memory-design.md explicitly requires this.
**Rationale:** Opaque roles follow the traits pattern in MindMap — the platform provides the mechanism, consumers define the semantics. Temporal validity is a first-class requirement per the design doc.
**Trade-offs:** No compile-time role validation. Acceptable — roles are domain-specific, not platform concepts.
**Depends on:** D44 (module placement)
**Sources:** shared-memory-design.md §Group Dynamics (temporal membership), MindMap traits (opaque strings pattern)
**Exploration:** quick
**Status:** captured

## D49: SpaceMembershipStore SPI — minimal CRUD with temporal queries

**Choice:** `SpaceMembershipStore` with: `createSpace(MemorySpace)`, `getSpace(String spaceId) → Optional<MemorySpace>`, `addMember(SpaceMembership)`, `revokeMember(String agentId, String spaceId, Instant revokedAt)`, `spacesFor(String agentId, Instant asOf) → List<MemorySpace>`, `membersOf(String spaceId, Instant asOf) → List<SpaceMembership>`.
**Alternatives:**
- Add deleteSpace — cascading logic (members still exist) adds complexity. Omit for now; add when needed.
- Split read/write (CQRS-lite) — premature for a configuration store with low write volume.
**Rationale:** `spacesFor(agentId, asOf)` is the key query — CognitiveProfile, TemporalIndex, and future space-aware utilities call this to resolve which tenant IDs to query. `membersOf` supports admin views. `revokeMember` sets `validUntil` rather than deleting — temporal history is preserved.
**Trade-offs:** No deleteSpace — spaces can only be created, not removed. Acceptable for pre-release; add deletion when the data model stabilises.
**Depends on:** D44 (module placement), D45 (three-tier CDI), D47 (MemorySpace), D48 (SpaceMembership)
**Sources:** shared-memory-design.md §The Model, CognitiveProfile.java (consumer of spacesFor)
**Exploration:** quick
**Status:** captured

## D50: NoOpSpaceMembershipStore @DefaultBean — graceful degradation

**Choice:** `NoOpSpaceMembershipStore` as `@DefaultBean @ApplicationScoped` in `memory-space-api`. Returns a singleton private space derived from the agentId for `spacesFor()`. Single-agent apps work without adding space infrastructure. Multi-agent apps displace with the real implementation.
**Alternatives:**
- No DefaultBean (fail loud) — CDI fails at startup if no implementation is on the classpath. Breaks single-agent apps that add memory-space-api transitively. Inconsistent with the MindMapStore/CaseMemoryStore graceful degradation pattern.
**Rationale:** Every existing store SPI (MindMapStore, CaseMemoryStore, CbrCaseMemoryStore) has a @DefaultBean no-op. SpaceMembershipStore should follow the same pattern. The no-op creates a private space per agentId — semantically correct for single-agent deployments where every agent's memory IS private.
**Trade-offs:** Silent no-op if real implementation is missing. Mitigated: log a warning at startup when the no-op is active.
**Depends on:** D44 (module placement), D49 (SPI shape)
**Sources:** NoOpMindMapStore.java (pattern), NoOpCaseMemoryStore.java (pattern), light review finding
**Exploration:** quick (surfaced by review)
**Status:** captured

## D51: RetrievalModulator scope — post-retrieval only

**Choice:** RetrievalModulator operates after retrieval from any store. CBR decorators (TrustWeightedCbrCaseMemoryStore, OutcomeWeightingCbrCaseMemoryStore, ScopeDecayCbrCaseMemoryStore) remain unchanged. The modulator adds mood/personality/recency on top of whatever the store returned.
**Alternatives:**
- Replace CBR decorators — moves trust weighting out of the decorator stack into RetrievalModulator. Unifies all score modulation but loses per-retrieval trust trajectory cache, @IfBuildProperty gating, and the decorator composition model that CBR relies on.
- Hybrid — trust stays in decorator for CBR, modulator adds mood/personality for Memory/MindMap. Trust also in ModulationContext for stores without decorators. Inconsistent: trust is both inside and outside the store boundary.
**Rationale:** Clean separation of concerns. Store-internal decorators handle store-specific scoring (similarity, trust, scope decay). Post-retrieval modulation handles cross-cutting cognitive factors (mood, personality, recency). No coupling between the two layers.
**Trade-offs:** CBR results are modulated on top of an already-weighted score, so the modulator's contribution is additive, not foundational. This is correct — CBR similarity is the primary signal.
**Sources:** TrustWeightedCbrCaseMemoryStore.java, OutcomeWeightingCbrCaseMemoryStore.java, ScopeDecayCbrCaseMemoryStore.java, cognitive-architecture-roadmap.md §1c
**Exploration:** quick
**Status:** captured

## D52: Field extraction via accessor functions (ModulationProfile<T>)

**Choice:** A `ModulationProfile<T>` record carrying lambda accessors (`Function<T, Confidence>`, `Function<T, Double>` for PAD, `Function<T, Instant>` for timestamp). Pre-built profiles for Memory, MindMapNode, ScoredCbrCase in cognitive-index. No changes to existing types.
**Alternatives:**
- Common trait interface (Modulatable) — Memory/MindMapNode already have the methods, but Memory is a record (can't add implements without recompilation of all callers) and ScoredCbrCase would need a wrapper. Type-invasive.
- Unscored wrapper (ScoredItem<T>) — wrap every result before modulation. Uniform but adds allocation overhead and an extra unwrap step.
**Rationale:** Accessor functions are non-invasive — no changes to existing types. Type-safe via generics. The profile is a compile-time description of how to read fields from T. Method references (`Memory::confidence`, `MindMapNode::pleasure`) are concise and clear.
**Trade-offs:** Profile must be passed at every call site. Mitigated: pre-built constants in ModulationProfiles utility class.
**Depends on:** D51 (post-retrieval scope — profiles describe retrieval result types)
**Sources:** Memory.java, MindMapNode.java, ScoredCbrCase.java, CbrCase.java
**Exploration:** quick
**Status:** captured

## D53: Composable modulation factors via multiplication

**Choice:** Each factor is a `@FunctionalInterface ModulationFactor<T>` returning a double multiplier. Factors compose via multiplication to produce a composite score. Callers pick which factors to apply. Pre-built factory methods for common combos.
**Alternatives:**
- Monolithic modulator per store type — simpler call-site but can't mix-and-match factors. Adding a factor means changing each implementation.
- Pipeline with ordering — explicit ordered chain where each factor sees previous output. More powerful (factors could drop items) but harder to reason about. Multiplication is commutative — order doesn't matter.
**Rationale:** Multiplication-based composition is simple, commutative (order-independent), and extensible. New factors are just new implementations of ModulationFactor<T>. Follows the existing OutcomeWeightingFunction and TrustWeightingFunction patterns — functional interfaces that produce a double multiplier.
**Trade-offs:** Factors can only re-weight, not filter or restructure. Sufficient for mood/personality/recency/confidence modulation. If filtering is needed later, a separate pipeline layer can be added.
**Sources:** OutcomeWeightingFunction.java, TrustWeightingFunction.java, MoodModulatedRetrieval.java (score method — already multiplicative)
**Exploration:** quick
**Status:** captured

## D54: Core types in cognitive-api, pre-built instances in cognitive-index

**Choice:** `ModulationFactor<T>`, `ModulationProfile<T>`, and `RetrievalModulator` (static utility) in cognitive-api (zero deps, tier-0). `ModulationProfiles` (pre-built profiles for Memory, MindMapNode), `ModulationFactors` (factory methods), and `ModulationContext` (convenience record) in cognitive-index (depends on memory-api, mindmap-api).
**Alternatives:**
- All in cognitive-index — simpler but prevents memory-api or mindmap-api from referencing the modulation framework. Acceptable today but closes future options.
- New module (modulation-api) — follows fusion-api pattern but adds a module for ~3 types. Unnecessary when cognitive-api exists.
**Rationale:** The framework types (interface, profile, utility) are pure Java with no dependencies — they belong in cognitive-api alongside Confidence and TemporalMark. The concrete instances that reference Memory/MindMapNode belong in cognitive-index which already depends on both store APIs.
**Trade-offs:** The type/instance split means consumers need both cognitive-api (compile) and cognitive-index (for pre-built profiles). This is the same pattern as Confidence (cognitive-api) + CognitiveProfile (cognitive-index).
**Depends on:** D1 (cognitive-api module), D52 (accessor functions)
**Sources:** cognitive-api/pom.xml, cognitive-index/pom.xml, Confidence.java, CognitiveProfile.java
**Exploration:** quick
**Status:** captured

## D55: PerspectivalResolver in cognitive-index

**Choice:** `PerspectivalResolver` as `@ApplicationScoped` CDI bean in cognitive-index with `Instance<MindMapStore>` and `Instance<SpaceMembershipStore>` graceful degradation. Convention constants (NodeRef scheme) in mindmap-api.
**Alternatives:**
- mindmap module (CDI wiring) — closer to MindMapStore but doesn't depend on memory-space-api and perspective crosses the space boundary.
- New module (mindmap-perspective) — clean isolation but adds a module for ~2 types.
**Rationale:** cognitive-index already hosts CognitiveProfile which does identical cross-store resolution (Instance<MindMapStore>, Instance<CaseMemoryStore>). Perspectival views are a cognitive concept. The resolver needs both MindMapStore (to look up overlay nodes) and SpaceMembershipStore (to know which tenants to query).
**Trade-offs:** cognitive-index gains a dependency on memory-space-api. Acceptable — it already depends on memory-api and mindmap-api.
**Depends on:** D44 (memory-space-api module), D1 (cognitive-api module)
**Sources:** CognitiveProfile.java (Instance<T> pattern), shared-memory-design.md (overlay model), SpaceMembershipStore.java
**Exploration:** quick
**Status:** captured

## D56: No-overlay returns shared node as-is (null PAD)

**Choice:** When no private overlay exists for a shared node, return the shared node unchanged. Null PAD is semantically correct — the agent hasn't formed an emotional perspective yet.
**Alternatives:**
- Explicit zero PAD (0.0, 0.0, 0.0) — signals 'neutral' rather than 'unknown'. But imposes a default that may not be accurate.
- Skip the node — too aggressive, agent wouldn't see new shared knowledge they haven't engaged with.
**Rationale:** Null PAD means "no emotional data" which is exactly what "no overlay" means. The agent can add an overlay later through interaction. Zero PAD would be indistinguishable from a genuine neutral assessment.
**Sources:** shared-memory-design.md (overlay model), MindMapNode.java (nullable PAD fields)
**Exploration:** quick
**Status:** captured

## D57: Full overlay — PAD + confidence + properties

**Choice:** Overlay nodes carry PAD (emotional perspective), confidence (epistemic perspective), and properties (personal annotations). Merge rule: overlay fields win when present, shared fields fill gaps.
**Alternatives:**
- PAD only — minimal but misses the "notes: love her" use case from the design doc.
- PAD + confidence — clean boundary but misses personal annotations.
**Rationale:** The design doc explicitly shows overlays with notes/properties ("love her", "so annoying"). Confidence override is needed because an agent may trust shared knowledge differently from the group consensus. Properties enable personal context without polluting the shared node.
**Trade-offs:** More complex merge logic — must handle partial overlays (some fields present, others not). Mitigated: merge is a straightforward field-by-field overlay-wins-if-present rule.
**Depends on:** D56 (no-overlay behavior)
**Sources:** shared-memory-design.md (overlay model with notes), MindMapNode.java (confidence + properties fields)
**Exploration:** quick
**Status:** captured

## D58: Space parameters dropped from builder APIs

**Choice:** Builder APIs (#231) do NOT add spaceId parameters to query/input types
**Alternatives:**
- Add withSpaceId() to MindMapQuery, MemoryInput, CbrQuery — entrenches the space-as-tenant model which is a design mistake
- Add withSpaces(Set<String>) for multi-space querying — same problem, more complex
**Rationale:** The memory-space model (space-as-tenant, D44-D50) was identified as a design mistake during #231 brainstorming. Tenant is the hard boundary. Individual vs common memory is a property of the memory (entityId), not a partitioning system. SpaceMembershipStore is authorization that belongs in the platform. Filed #255 for rearchitecture. Adding space params to builders would entrench the wrong abstraction.
**Trade-offs:** #252 (Memory space YAML) is blocked until #255 resolves the correct model. Deferred from queue.
**Sources:** #255, memory-space-api design spec, user correction during brainstorming
**Exploration:** deep-analysis
**Status:** captured

## D59: Builder pattern — CbrQuery-style of() + withX()

**Choice:** Static `of()` factory with required fields + individual `withX()` methods returning new record instances
**Alternatives:**
- Builder inner class (GoF) — mutable intermediate object, doubles API surface, doesn't leverage records
- Lombok @Builder — adds dependency, doesn't work cleanly with record compact constructors, loses explicit validation
**Rationale:** Proven pattern already in the codebase (CbrQuery has 13 withX methods). Works naturally with records — compact constructor validation fires on every withX() call. No intermediate mutable state. Dramatic call-site improvement: `MindMapQuery.of(tenant, 100).withText("Alice")` vs 12-arg constructor with 9 nulls.
**Trade-offs:** Each withX() method is verbose (repeats all constructor args). Mitigated: mechanical to generate, IDE catches mismatches at compile time.
**Sources:** CbrQuery.java (proven pattern), MindMapQuery.java / NodeInput.java (current pain points)
**Exploration:** quick
**Status:** captured

## D60: SubgraphInput excluded from builder scope

**Choice:** SubgraphInput (3 fields: name, type, rootNodeId) does not get builders
**Alternatives:**
- Add builders for consistency — minimal benefit, no optional fields, no null args
**Rationale:** 3 fields, all required, no optionals. Builders add nothing. The positional constructor is already clear.
**Trade-offs:** Slight inconsistency — 5 of 6 mindmap input types have builders. Acceptable: consistency should not override YAGNI.
**Sources:** SubgraphInput.java (3 fields, no validation)
**Exploration:** quick
**Status:** captured

## D61: NodeUpdate uses withX() only, not accumulating helpers

**Choice:** NodeUpdate gets `withTraitsToAdd(Set)`, `withRefsToRemove(Set)` etc. — same withX() pattern as other types
**Alternatives:**
- Accumulating helpers (addTrait, removeTrait) — more ergonomic for single-item mutations but diverges from the established pattern
- Both (withX for bulk + single-item helpers) — maximum convenience but doubles the API surface
**Rationale:** Consistency with the other types. Callers build their Sets themselves. The pattern is already established by CbrQuery.withFilter() which takes a single-item convenience method alongside withFilters() for bulk — but NodeUpdate's mutation semantics (add vs remove sets) make single-item helpers less clear about which set they target.
**Trade-offs:** Single-item mutations are slightly more verbose: `withTraitsToAdd(Set.of("person"))` vs `addTrait("person")`. Acceptable tradeoff for pattern consistency.
**Sources:** CbrQuery.java (withFilter + withFilters pattern), NodeUpdate.java (add/remove semantics)
**Exploration:** quick
**Status:** captured

## D62: Delete all 5 memory-space modules

**Choice:** Remove `memory-space-api`, `memory-space`, `memory-space-inmem`, `memory-space-sqlite`, `memory-space-testing` entirely. Remove from parent pom.xml module list and dependencyManagement. Remove `casehub-neocortex-memory-space-api` compile dependency and `casehub-neocortex-memory-space-inmem` test dependency from cognitive-index pom.xml.
**Alternatives:**
- Gut but keep the modules — remove all types except a stub SpaceMembershipStore. Preserves the module structure for future reimplementation. Rejected: the model itself is wrong (D58), not just the implementation. Keeping empty modules signals intent to reuse the same abstraction.
- Move SpaceMembershipStore to platform — it's authorization infrastructure. Rejected: the SPI is designed around space-as-tenant; moving it doesn't fix the design mistake. Platform authorization is a separate concern to be designed independently.
**Rationale:** D58 established that space-as-tenant is a design mistake. Tenant is the hard boundary (organisation). Individual vs common memory is a property of the memory (entityId). The 5 modules implement a wrong abstraction — clean deletion is the correct action. Reference analysis confirms zero cross-repo consumers and only one intra-repo consumer (PerspectivalResolver in cognitive-index, addressed by D63).
**Trade-offs:** ~1800 lines of tested code deleted. Acceptable: the tests validated a wrong model. Future multi-agent memory sharing will need a different design.
**Depends on:** D58 (space-as-tenant identified as wrong)
**Sources:** PerspectivalResolver.java (sole external consumer), cognitive-index/pom.xml (sole external dependency), find-references results (all other refs self-contained within space modules or docs)
**Exploration:** quick
**Status:** captured

## D63: PerspectivalResolver — agentId property replaces SpaceMembershipStore

**Choice:** Change PerspectivalResolver to find overlay nodes by agentId property within the same tenant instead of querying SpaceMembershipStore for a private tenant. Signature changes from `resolve(List<MindMapNode>, String agentId, Instant asOf)` to `resolve(List<MindMapNode>, String agentId, String tenantId)`. Constructor drops SpaceMembershipStore — only MindMapStore remains. Overlay nodes carry `agentId` as a node property. Search by trait `"overlay"` within tenantId, then filter by property `agentId` client-side.
**Alternatives:**
- Add property filtering to MindMapQuery — semantically cleaner (server-side filter) but scope creep for a removal issue. Can be added later when performance requires it.
- Encode agentId as a trait (e.g. `"agent:alice"`) — traits describe what a node IS, not ownership. Semantically wrong.
- Per-agent subgraph for overlays — subgraphs are domain grouping, not ownership scoping. Over-engineered.
**Rationale:** The caller already knows the tenant context (shared nodes come from a specific tenant). The agentId property on overlay nodes replaces the space membership lookup with a simple client-side filter. `asOf` is dropped — it only existed for temporal space membership queries. MindMapQuery property filtering is a natural future enhancement but not required here; overlay node counts per tenant are bounded.
**Trade-offs:** Client-side filtering loads all overlay nodes in the tenant to filter by agentId. Acceptable for pre-release; bounded by number of agents × overlays per agent. Graceful degradation test for missing SpaceMembershipStore becomes unnecessary — replaced by the existing MindMapStore degradation test.
**Depends on:** D62 (module removal), D64 (OverlayRef convention)
**Sources:** PerspectivalResolver.java:57-63 (findPrivateTenant method to remove), PerspectivalResolverTest.java (6 test call sites, all in-module), MindMapQuery.java (no property filtering support)
**Exploration:** quick
**Status:** captured

## D64: OverlayRef convention — agentId property constant

**Choice:** Add `AGENT_ID = "agentId"` constant to OverlayRef. The property key is standardised so PerspectivalResolver and overlay creators use the same key. No changes to the OverlayRef.of() factory — agentId goes in the NodeInput properties map, not in the NodeRef.
**Alternatives:**
- No constant, raw string — callers use `"agentId"` directly. Fragile: typos and inconsistency between creator and consumer.
- Extend OverlayRef.of() to return a record with both NodeRef and agentId — over-engineers the convention. NodeRef and properties are separate concerns on NodeInput.
**Rationale:** OverlayRef already owns the overlay convention (SCHEME, of(), sharedNodeId()). Adding the property key constant keeps all convention knowledge in one place. The property is on the node, not the ref — OverlayRef.of() remains unchanged.
**Trade-offs:** Minor: one more constant. The convention relies on callers remembering to set the property — no compile-time enforcement. Mitigated: PerspectivalResolver logs or returns unmerged when no matching overlay is found.
**Depends on:** D63 (PerspectivalResolver needs this convention)
**Sources:** OverlayRef.java (existing convention), PerspectivalResolver.java:65-77 (loadOverlays consumer)
**Exploration:** quick
**Status:** captured

## D65: camelCase for YAML keys

**Choice:** Use camelCase for all YAML property keys in neocortex cognitive configuration
**Alternatives:**
- kebab-case — more conventional for standalone YAML configs (Kubernetes, GitHub Actions). Requires name transformation between Java fields and YAML keys. Creates platform inconsistency.
- Mixed (camelCase for data, kebab-case for identifiers) — inconsistent key naming convention
**Rationale:** Platform coherence is the primary constraint. Engine uses camelCase (`topK`, `minSimilarity`, `caseType`), eidos uses camelCase (`agentId`, `tenancyId`, `qualityHint`). Neocortex YAML is consumed alongside engine and eidos YAML in the same applications. Java record field names map directly without transformation.
**Trade-offs:** Less conventional for YAML-purists who expect kebab-case. Platform consistency outweighs external convention.
**Sources:** engine/api/src/test/resources/casehub/minimal.yaml, engine/runtime/src/test/resources/casehub/cbr-routing-test.yaml, eidos/eval/src/test/resources/profiles/technical-writer.yaml, eidos/org-runtime/src/test/resources/gastown-org.yaml
**Exploration:** quick
**Status:** captured

## D66: `type` as sealed hierarchy discriminator property

**Choice:** Use `type` as the discriminator property name for all 8 sealed hierarchies
**Alternatives:**
- `kind` — used in eidos org YAML and Kubernetes. Avoids collision if a type has its own semantic `type` property.
- Per-hierarchy custom (`fieldType`, `decay`, etc.) — most explicit but no convention to learn
**Rationale:** Standard JSON Schema / Jackson `@JsonTypeInfo` convention. Already used in the codebase's CBR feature serialization (GE-20260825-ba18b3). Short, universally understood. None of the 8 sealed hierarchies have a semantic `type` property that would collide.
**Trade-offs:** Reserves `type` as a key name in all discriminated objects — cannot be used for domain semantics.
**Sources:** GE-20260825-ba18b3 (CBR type discriminator pattern), GE-20260517-66d611 (Jackson mixin @JsonTypeInfo convention), JSON Schema oneOf + const pattern
**Exploration:** quick
**Status:** captured

## D67: FeatureValue inference-first with explicit override

**Choice:** YAML parser infers FeatureValue variant from value shape (string → StringVal, number → NumberVal, etc.) using the same logic as `FeatureValue.of(Object)`. When ambiguous (e.g., 2-element number array: RangeVal or NumberListVal?), the CbrFeatureSchema field type resolves it. Users can write explicit form `{ type: range, min: 0.5, max: 1.0 }` for disambiguation.
**Alternatives:**
- Always explicit — every FeatureValue requires `{ type: string, value: critical }`. Unambiguous but verbose.
- Always inferred, no override — can't handle ambiguous cases without schema context
**Rationale:** Matches `FeatureValue.of(Object)` semantics that already exist at runtime. Keeps the common case terse (`severity: critical`). Schema-resolved ambiguity leverages CbrFeatureSchema's field type declarations.
**Trade-offs:** Requires schema context for ambiguous cases — FeatureValue YAML outside of a schema context must use explicit form.
**Sources:** FeatureValue.java (of(Object) inference), CbrFeatureSchema.java (field type declarations), #246 audit §FeatureValue
**Exploration:** quick
**Status:** captured

## D68: @Named CDI bean references for SPI/functional interfaces

**Choice:** YAML references functional interfaces and SPIs via plain string matching a `@Named` CDI bean: `planAdapter: myCustomAdapter`. Pre-built defaults use well-known names (`outcomeWeighting: linear`, `reflectionSynthesizer: noop`). Omitted field → `@DefaultBean` activates.
**Alternatives:**
- Custom `@SpiRef` annotation — more type-safe but introduces a parallel naming mechanism inconsistent with `@Named` usage elsewhere in casehub
- Quarkus config reference — adds indirection (YAML → config → CDI)
**Rationale:** `@Named` is standard CDI, zero new infrastructure. Adding `@Named` to existing `@DefaultBean` implementations is a one-line change per class. Consistent with how engine and other casehub repos reference beans.
**Trade-offs:** `@Named` is string-based — no compile-time validation of the reference. Mitigated: YAML compiler validates bean existence at startup.
**Sources:** GE-20260517-66d611 (mixin pattern for keeping api pure), DefaultOutcomeWeightingFunction.java, NoOpPlanAdapter.java, NoOpReflectionSynthesizer.java
**Exploration:** quick
**Status:** captured

## D69: Scalar-or-object shorthand convention

**Choice:** Types with a natural single-value shorthand accept either a scalar or the full object form in YAML. Parser checks YAML node type (scalar vs mapping) and dispatches. Applied to: Confidence (`0.9` → UNKNOWN origin, no decay), NodeRef (`overlay:shared-123` → scheme:overlay, id:shared-123), RecurrenceRule (RRULE string → parsed record).
**Alternatives:**
- Always object form — simpler parsing, no ambiguity, but verbose for common cases
**Rationale:** Keeps the common case terse. `confidence: 0.9` vs `confidence: { origin: STATED, value: 0.9, decayReference: "2026-06-01T12:00:00Z" }`. The short form covers 80%+ of usage. Polymorphic deserialization based on YAML node type is a well-understood Jackson pattern.
**Trade-offs:** Parser must handle two input shapes per type. Documented per type in the conventions doc.
**Sources:** Confidence.java (of(double) factory), NodeRef.java (scheme/id fields), RecurrenceRule.java (parse/toString for RRULE)
**Exploration:** quick
**Status:** captured

## D70: SealedHierarchyModule in neocortex, no shared extraction

**Choice:** New `SealedHierarchyModule` as a victools `Module` in neocortex's own schema-generator module. No shared `casehub-schema-generator` extraction from engine.
**Alternatives:**
- Extract shared module now — put SealedHierarchyModule + EnumInliningModule in a shared repo. Prevents duplication but adds cross-repo dependency for 2 reusable classes.
- Put in engine's generator — wrong dependency direction (neocortex shouldn't depend on engine)
**Rationale:** Two reusable classes don't justify shared module extraction. EnumInliningModule is 20 lines — duplicating is cheaper than the coordination cost. Extract if a third consumer appears.
**Trade-offs:** EnumInliningModule duplicated across engine and neocortex. Acceptable: 20 lines of trivial code.
**Sources:** engine/generator/src/main/java/io/casehub/generator/CaseHubSchemaGenerator.java, engine/generator/src/main/java/io/casehub/generator/module/EnumInliningModule.java, GE-20260824-2eb1d7 (victools module patterns)
**Exploration:** quick
**Status:** captured

## D71: Accept 3-level nested sealed types as-is

**Choice:** Accept FeatureField → SimilaritySpec → WarpingConstraint (3-level `type:` discriminator nesting) without flattening. Document the deep case with a complete YAML example.
**Alternatives:**
- Flatten with dotted names — `similarity: dtw.sakoeChibaBand.5`. Opaque, hard to validate.
- Shorthand for common combinations — `similarity: dtw-sakoe(5)`. Convenient for 2-3 cases but explodes if hierarchy grows.
**Rationale:** 3 levels is the structural ceiling (WarpingConstraint has only primitive fields, FeatureField nesting is constrained to flat types). YAML indentation makes structure clear. The deep case is uncommon (only TimeSeries + DTW + SakoeChibaBand). Flattening would diverge from the Java API.
**Trade-offs:** Power-user configuration looks complex — but it IS complex. The YAML should reflect this.
**Sources:** FeatureField.java (9 variants), SimilaritySpec.java (6 variants, nested WarpingConstraint), WarpingConstraint.java (3 variants, primitive fields only)
**Exploration:** quick
**Status:** captured

## D72: Mirror Java unlimited recursion for CbrFilter.AllOf

**Choice:** YAML allows unlimited CbrFilter recursion via AllOf, mirroring the Java API. No artificial depth cap.
**Alternatives:**
- Cap at depth 2 — simpler schema validation, prevents pathological configs. Creates YAML/Java parity gap.
**Rationale:** Eliminating parity gaps between Java and YAML is the purpose of this issue. AllOf wrapping ≥2 filters is validated in Java. Depth is self-limiting by usability — nobody writes depth-5 filter trees.
**Trade-offs:** No schema-level depth validation. Java runtime validation (AllOf constructor rejects <2 filters) catches structural errors.
**Sources:** CbrFilter.java (AllOf contains List<CbrFilter>), #247 issue scope (YAML parity)
**Exploration:** quick
**Status:** captured

## D73: String-to-type conversion for platform types

**Choice:** External types (platform `Path`, `Duration`, `Instant`) use their natural string representation in YAML. Parser calls `Path.of()`, `Duration.parse()`, `Instant.parse()` during deserialization. Document each type's string form in the conventions doc.
**Alternatives:**
- Explicit import/alias section — YAML declares `imports: { Path: io.casehub.platform.api.path.Path }`. Over-engineered for ~3 external types.
**Rationale:** Same pattern as Duration → ISO-8601 and Instant → ISO-8601, which are universally accepted. Path.of(String) already exists. No new concepts needed.
**Trade-offs:** Parser must know the conversion function for each external type. Bounded — only 3 types currently.
**Sources:** io.casehub.platform.api.path.Path (of(String) factory), CbrQuery.java (scope field), #246 audit §Platform Types
**Exploration:** quick
**Status:** captured

## D74: Cognitive profile — separate YAML file, agentId join key

**Choice:** Cognitive profile is a standalone YAML file (e.g., `cognitive-profiles/alice.yaml`) with an `agentId` field matching the eidos AgentDescriptor. Neocortex parser loads independently; consuming apps wire by agentId. Zero coupling between eidos and neocortex.
**Alternatives:**
- Extension section in eidos YAML — pollutes eidos file with neocortex concerns, creates implicit dependency
- Merged at application level — app-specific schema, not a platform convention
**Rationale:** Dependency direction: neocortex doesn't depend on eidos, eidos doesn't depend on neocortex. Separate files with a shared key preserve this. Follows eidos's own pattern (one YAML file per agent).
**Trade-offs:** Two files per agent instead of one. Acceptable — they serve different concerns (identity/capability vs cognitive tuning).
**Sources:** AgentDescriptor.java (agentId field), eidos eval profiles (separate YAML per agent)
**Exploration:** quick
**Status:** captured

## D75: CognitiveDefaults — single aggregate record

**Choice:** `CognitiveDefaults(String agentId, String tenantId, PersonalityWeights personality, MoodBaseline moodBaseline, CuriosityConfig curiosity, TemporalFocusConfig temporalFocus, MindMapVocabulary vocabulary, Map<String, String> services)`. All fields except agentId optional — null sections use Java defaults. `services` maps SPI names to `@Named` bean references (e.g., `reflectionSynthesizer: llm`).
**Alternatives:**
- Sectioned YAML, no aggregate — no single "profile" concept; consumers must inject each piece separately
**Rationale:** The aggregate is the natural API for "what is this agent's cognitive configuration?" CDI producer exposes both the aggregate and individual components. `services` enables per-agent SPI selection via `@Named` lookup.
**Trade-offs:** Record references types from 4 modules — requires the right module placement (D77).
**Sources:** PersonalityWeights.java, MoodBaseline.java, CuriosityConfig.java, TemporalFocusConfig.java, MindMapVocabulary.java
**Exploration:** quick
**Status:** captured

## D76: Naming — CognitiveDefaults, not CognitiveProfile

**Choice:** `CognitiveDefaults` for the config record. Avoids collision with existing `CognitiveProfile` CDI bean in cognitive-index (runtime entity resolver).
**Alternatives:**
- `AgentCognitiveConfig` — longer, config-in-config naming awkward alongside CuriosityConfig
- `CognitiveDescriptor` — mirrors eidos but "descriptor" means identity/capability there, not tuning
**Rationale:** "Defaults" captures that these are baseline parameters that runtime behaviour deviates from (mood drifts, confidence decays). Clearly distinct from the runtime `CognitiveProfile` resolver.
**Trade-offs:** None significant.
**Sources:** CognitiveProfile.java (cognitive-index — existing runtime resolver)
**Exploration:** quick
**Status:** captured

## D77: Module placement — cognitive-index

**Choice:** `CognitiveDefaults`, parser, and `CognitiveDefaultsRegistry` live in cognitive-index. Adds mindmap-intelligence as a new dependency (for CuriosityConfig).
**Alternatives:**
- New `cognitive-profile` module — clean isolation but adds a module for 3 classes with identical dependency set
- cognitive-api — wrong: zero-deps constraint (D26)
**Rationale:** cognitive-index is the integration tier above individual subsystem modules. Already depends on memory-api and mindmap-api. Adding mindmap-intelligence is directionally correct (integration tier uses subsystem modules). Avoids a new module for 3 classes.
**Trade-offs:** cognitive-index gains one dependency edge (mindmap-intelligence). Acceptable — right direction.
**Depends on:** D1 (cognitive-api zero-deps), D16 (cognitive-index module)
**Sources:** cognitive-index/pom.xml (existing dependencies), mindmap-intelligence/pom.xml
**Exploration:** quick
**Status:** captured

## D78: Classpath scan with configurable path

**Choice:** Quarkus config `casehub.cognitive.profiles.path` (default: `cognitive-profiles/`). At startup, scan classpath under that path for `*.yaml` files. Each file → one `CognitiveDefaults`. No files = no beans (subsystems use `defaults()` factories). Jackson `ObjectMapper` + `YAMLFactory` parser.
**Alternatives:**
- Single file config property — doesn't scale for multi-agent
- CDI SPI discovery — over-engineered for file loading
**Rationale:** Convention-over-configuration. Drop a YAML file, it becomes a bean. Mirrors eidos profile discovery. Configurable path handles non-default deployments.
**Trade-offs:** Classpath scanning at startup has a small cost. Bounded — cognitive profile count is small (typically 1-10 agents).
**Sources:** eidos profile loading pattern, AgentDescriptorBootstrap.java (classpath discovery)
**Exploration:** quick
**Status:** captured

## D79: CognitiveDefaultsRegistry — runtime lookup by agentId

**Choice:** `@ApplicationScoped CognitiveDefaultsRegistry` loads all profiles at startup. API: `Optional<CognitiveDefaults> forAgent(String agentId)`, `CognitiveDefaults forAgentOrDefaults(String agentId)`. Fallback returns all-null sections (subsystems use defaults). Consumers inject the registry and look up by agentId.
**Alternatives:**
- One `@Named` bean per agent — requires compile-time knowledge of agentId
- `Instance<CognitiveDefaults>` with qualifier — CDI ceremony for a runtime value
**Rationale:** agentId is a runtime value (from agent context, request headers, eidos descriptor). A lookup method handles this naturally. The registry pattern is simple, explicit, and testable.
**Trade-offs:** No CDI injection by agent — must go through the registry. This is the correct pattern for runtime-keyed lookup.
**Sources:** CognitiveProfile.java (similar CDI bean pattern), AgentDescriptor.agentId
**Exploration:** quick
**Status:** captured

## D80: Complete structural predicate set for trait rule conditions

**Choice:** 11-predicate condition DSL: `hasProperty`, `propertyEquals`, `propertyIn`, `notHasProperty` (property); `hasEdgeType`, `hasEdgeTypes`, `hasAnyEdge` (edge); `inSubgraphType` (subgraph); `anyOf`, `allOf`, `not` (combinators). Boundary: structural predicates on the graph — no numeric comparison, no regex, no temporal queries.
**Alternatives:**
- 5 primitives only (strict YAGNI) — covers existing 7 rules but irregular API forces Java for obvious predicates like negation
**Rationale:** A regular, intuitive predicate set prevents users from dropping to Java for trivial conditions. The cost per predicate is a few lines of implementation. The boundary (structural predicates) is clear and defensible.
**Trade-offs:** 11 predicates to implement and test instead of 5. Marginal cost for significant usability gain.
**Sources:** PersonableTraitRule.java, ProjectlikeTraitRule.java, AppointableTraitRule.java (existing patterns), TraitRule.java (interface)
**Exploration:** quick
**Status:** captured

## D81: Two-level derived edge actions — direct + traversal

**Choice:** Level 1 (direct): `derive` creates edges with source/target references (`trigger.source`, `trigger.target`) for inverse/flip patterns. Level 2 (traversal): optional `traverse` block walks the graph following a specified edge type from a starting point, with direction and maxDepth — `derive` fires per reached node using `traversal.node` reference. Transitive closure is expressible without imperative store access.
**Alternatives:**
- Direct/inverse only — forces Java for transitive patterns that are structurally declarative
**Rationale:** Traversal is a common structural pattern (descendant chains, organisational hierarchies). The traverse block is declarative — it says WHAT to follow, not HOW to query. The implementation uses MindMapStore.neighbors() internally, filtered by edge type and direction.
**Trade-offs:** Traversal adds complexity to the rule compiler. The implementation needs graph walking with the decorator's existing depth limit as a safety net.
**Sources:** DerivedEdgeDecorator.java (recursion depth limit), DerivedEdgeRule.java (derive signature with store access)
**Exploration:** quick
**Status:** captured

## D82: Module placement — mindmap-intelligence

**Choice:** Rule DSL compiler, DeclarativeRuleRegistry, and intermediate types live in mindmap-intelligence.
**Alternatives:**
- New `mindmap-rules` module — clean isolation but adds a module for the same concern
- cognitive-index — wrong scope, rules are mindmap-specific
**Rationale:** mindmap-intelligence is where rules live. Programmatic trait rules are Java classes there today; YAML-compiled rules are the same interfaces produced by a startup loader. One module, one concern.
**Trade-offs:** mindmap-intelligence grows. Acceptable — it's the natural home.
**Sources:** PersonableTraitRule.java et al (7 existing TraitRules in mindmap-intelligence)
**Exploration:** quick
**Status:** captured

## D83: Global rules + per-agent overrides

**Choice:** Global rules in `rules/*.yaml` on classpath. Per-agent rules in `cognitive-profiles/*.yaml` (traitRules/derivedEdgeRules sections). At startup, merge: agent-specific rules override global rules with the same name (local wins, global suppressed). Agents without profiles get only global rules.
**Alternatives:**
- Cognitive profile only — can't express shared rules without duplication
- Global only — can't express per-agent differences
**Rationale:** Most rules are shared (inverse-knows, descendant-chain). Some agents need domain-specific classification. Override-by-name is simple and intuitive.
**Trade-offs:** Two loading paths (global + per-agent) with merge logic. Merge rule is simple: name-based override, no partial merge.
**Depends on:** D75 (CognitiveDefaults record — gains traitRules/derivedEdgeRules fields)
**Sources:** CognitiveDefaultsRegistry.java (YAML loading pattern)
**Exploration:** quick
**Status:** captured

## D84: DeclarativeRuleRegistry + decorator modification

**Choice:** `@ApplicationScoped DeclarativeRuleRegistry` in mindmap-intelligence loads global rules and merges with per-agent rules from CognitiveDefaultsRegistry. Exposes `List<TraitRule> traitRules(String agentId)` and `List<DerivedEdgeRule> derivedEdgeRules(String agentId)`. DerivedEdgeDecorator gains `Instance<DeclarativeRuleRegistry>` (graceful degradation) alongside existing `Instance<DerivedEdgeRule>`. Iterates both sources.
**Alternatives:**
- Quarkus synthetic CDI beans — requires deployment module, heavyweight for startup file load
**Rationale:** Registry pattern is simple, testable, follows CognitiveDefaultsRegistry. Decorator modification is 3 lines. Pre-release — API change is free.
**Trade-offs:** DerivedEdgeDecorator constructor changes. Trait matching code also needs modification. Both are pre-release, no backward compat concern.
**Depends on:** D79 (CognitiveDefaultsRegistry), D82 (module placement)
**Sources:** DerivedEdgeDecorator.java (Instance<DerivedEdgeRule> injection), CognitiveDefaultsRegistry.java (registry pattern)
**Exploration:** quick
**Status:** captured
