# API-to-YAML Mapping Audit

**Issue:** casehubio/neocortex#246
**Purpose:** Identify all YAML gaps in cognitive APIs for parity with Java + DSL (builders) + annotations.

## Mapping Classes

| Class | Meaning | YAML Strategy |
|-------|---------|---------------|
| Direct | Record fields map 1:1 to YAML keys | Flat YAML object |
| Discriminated | Sealed hierarchy — variants need identification | `type:` discriminator key |
| Reference | Functional interface / SPI — no inline YAML equivalent | Named reference to registered CDI bean |
| Unmappable | Generic, runtime-only, or requires live code | Java/CDI only — not expressible in YAML |

## Legend

- **Req** = required (non-null in compact constructor)
- **Opt** = optional (nullable, has default)
- **Def** = has default value in `of()` factory

---

## mindmap-api

### Records (config-surface)

| Type | Fields | Required | Optional | Mapping | YAML Shape | Blockers |
|------|--------|----------|----------|---------|------------|----------|
| MindMapQuery | 12 | tenantId, limit | subgraphId, text, edgeType, traits, minConfidence, confidenceOrigin, includeSuperseded, validAfter, validBefore, updatedAfter | Direct | `tenant: t1` `text: Alice` `traits: [person]` `limit: 100` | None |
| NodeInput | 12 | name, subgraphId | confidence, provenance, traits, refs, validFrom, validUntil, pleasure, arousal, dominance, properties | Direct | `name: Grandma` `subgraph: sg1` `traits: [person]` `pad: {p: 0.8, a: 0.3, d: 0.5}` | `Confidence` is nested record; `NodeRef` set needs nested objects |
| EdgeInput | 11 | sourceNodeId, targetNodeId, edgeType | confidence, provenance, validFrom, validUntil, pleasure, arousal, dominance, properties | Direct | `source: n1` `target: n2` `type: knows` | Same Confidence/properties nesting as NodeInput |
| NodeUpdate | 13 | (none — all nullable/defaulted) | name, confidence, traitsToAdd, traitsToRemove, refsToAdd, refsToRemove, validFrom, validUntil, pleasure, arousal, dominance, propertiesToSet, propertiesToRemove | Direct | `traits-add: [x]` `traits-remove: [y]` `pad: {p: 0.5}` | Additive mutation semantics (add/remove sets) — YAML needs clear key naming convention |
| SubgraphInput | 3 | name, type | rootNodeId | Direct | `name: Family` `type: PERSON` | None |
| NodeRef | 3 | scheme, id | qualifier | Direct | `scheme: overlay` `id: shared-123` | None |
| EdgeTypeDefinition | 3 | canonical | aliases, defaultDecayHalfLifeDays | Direct | `canonical: knows` `aliases: [knows-about]` `decay-half-life-days: 30` | None |
| RecurrenceRule | 5 | freq, interval | count, until, byDay | Direct | `freq: WEEKLY` `interval: 2` `by-day: [MO, WE, FR]` | Has `parse(String)` / `toString()` for RFC 5545 — YAML could accept either structured or RRULE string |
| MindMapVocabulary | 1 | edgeTypes (list) | — | Direct | `edge-types:` `- canonical: knows` `  aliases: [knows-about]` | List of EdgeTypeDefinition — nested but straightforward |

### Enums

| Type | Values | Mapping | Notes |
|------|--------|---------|-------|
| SubgraphType | PERSON, PROJECT, RESEARCH_AREA, ORGANISATION, CONCEPT, GENERAL | Direct | String literal in YAML |
| ValidationTier | REGISTERED, UNVALIDATED | Direct | String literal |
| MindMapCapability | TRAVERSAL, MERGE, VOCABULARY, ALIAS, SUBGRAPH, SEARCH, SUPERSESSION, ERASE_NODE, ERASE_SUBGRAPH, ERASE_ENTITY, CROSS_TENANT_ERASE, GRAPH_ANALYSIS | Direct | 12 values — capability set in YAML |

### Interfaces / SPIs

| Type | Kind | Mapping | Blocker Detail |
|------|------|---------|----------------|
| DerivedEdgeRule | SPI interface — `derive(MindMapNode, MindMapEdge, MindMapStore) → List<EdgeInput>` | Reference | Lambda body with store access — needs named bean reference. YAML declares `rule: person-knows-friend`, CDI resolves. **Key gap for #249 (Declarative Rule DSL)** — this is where YAML rule expressions replace Java lambdas. |
| OverlayRef | Convention class (constants + static factories) | Direct | `SCHEME` and `AGENT_ID` constants are conventions, not configurable. YAML overlay nodes use the conventions implicitly. |

---

## memory-api

### Records (config-surface)

| Type | Fields | Required | Optional | Mapping | YAML Shape | Blockers |
|------|--------|----------|----------|---------|------------|----------|
| MemoryInput | 10 | entityId, domain, tenantId, text, attributes | caseId, confidence, pleasure, arousal, dominance | Direct | `entity: alice` `domain: experience` `tenant: t1` `text: observed X` | `MemoryDomain` is a record (name string) — maps to plain string in YAML |
| MemoryQuery | 8 | entityIds, domain, tenantId, order | caseId, question, limit (def 20), since | Direct | `entities: [alice]` `domain: experience` `limit: 50` | None |
| CbrQuery | 16 | tenantId, domain, caseType, features, filters, weights, topK, minSimilarity, vectorWeight, retrievalMode, fusionStrategy, scope | notBefore, problem, temporalDecay, scopeDecay | Direct | `tenant: t1` `case-type: incident` `top-k: 10` `features:` (nested FeatureValue map) | **Most complex type.** `features: Map<String, FeatureValue>` → discriminated values. `filters: Map<String, CbrFilter>` → discriminated. `scope: Path` is platform type — needs string-to-Path mapping. `TemporalDecay` and `ScopeDecay` are sealed (see below) |
| CbrFeatureSchema | 3 | caseType, fields | learningRate | Direct | `case-type: incident` `fields:` (list of FeatureField) `learning-rate: 0.1` | `fields: List<FeatureField>` is a discriminated sealed list — see FeatureField below |
| CbrRetentionPolicy | 6 | tenantId, domain | caseType, maxAgeDays, maxCasesPerType, minTrustScore (at least one non-null) | Direct | `tenant: t1` `domain: cbr` `max-age-days: 90` `max-cases: 1000` | None |
| PersonalityWeights | 1 | domainWeights | — | Direct | `experience: 1.5` `relationship: 0.8` `mood: 1.2` | `Map<MemoryDomain, Double>` — MemoryDomain is a string-wrapped record, so YAML keys are domain name strings |
| MoodState | 9 | agentId, tenantId, cause, pleasure, arousal, dominance | timestamp (def now), turnId, metadata | Direct | `agent: alice` `cause: received praise` `pleasure: 0.8` `arousal: 0.3` | None |
| MoodBaseline | 3 | pleasure, arousal, dominance | — | Direct | `pleasure: 0.0` `arousal: -0.2` `dominance: 0.3` | None — all primitives |
| TrendSpec | 2 | types | timeUnit (def HOURS) | Direct | `types: [SLOPE, VOLATILITY]` `time-unit: HOURS` | None |
| NumericRange | 2 | min, max | — | Direct | `min: 0.0` `max: 100.0` | None |

### Sealed Hierarchies

| Type | Variants | Mapping | YAML Shape | Complexity |
|------|----------|---------|------------|------------|
| FeatureField | 9: Categorical(name, similaritySpec?), Numeric(name, min, max, similaritySpec?), Text(name, semantic?), CategoricalList(name), NumericList(name, min, max), NestedObject(name, innerFields), ObjectList(name, innerFields), TimeSeries(name, innerFields, timestampField, similaritySpec?, trendSpec?), DiscreteSequence(name, similaritySpec?) | Discriminated | `- name: severity` `  type: categorical` or `- name: temperature` `  type: numeric` `  min: 0` `  max: 100` `  similarity: {type: gaussian, sigma: 10}` | **HIGH** — 9 variants, some with nested sealed SimilaritySpec, some with recursive innerFields. TimeSeries is the most complex (5 fields, nested list + nested sealed). |
| FeatureValue | 7: StringVal(value), NumberVal(value), RangeVal(min, max), StringListVal(values), NumberListVal(values), StructVal(fields), StructListVal(items) | Discriminated | Inferrable from YAML native types: string → StringVal, number → NumberVal, list of strings → StringListVal, map → StructVal. **No explicit discriminator needed** — YAML parser can infer variant from value shape. | **MEDIUM** — `FeatureValue.of(Object)` already does runtime type inference. YAML parser mirrors this logic. |
| CbrFilter | 8: Contains(value), ContainsAll(values), ContainsAny(values), NotContains(value), NotContainsAny(values), ContainsRange(range), HasMatch(subFields), AllOf(filters) | Discriminated | `severity: {contains: critical}` or `tags: {contains-any: [urgent, p0]}` or `score: {range: {min: 0.5, max: 1.0}}` or `compound: {all-of: [{contains: x}, {not-contains: y}]}` | **MEDIUM** — 8 variants but each is small. AllOf nests other filters (recursive). |
| SimilaritySpec | 6: CategoricalTable(similarities), GaussianDecay(sigma), StepDecay(tolerance), ExponentialDecay(decayRate), DtwSpec(constraint), EditDistanceSpec(substitutionSimilarities, insertCost?, deleteCost?) | Discriminated | `type: gaussian` `sigma: 10.0` or `type: categorical-table` `similarities:` `  red: {blue: 0.3}` or `type: dtw` `constraint: {type: sakoe-chiba, window: 5}` | **HIGH** — CategoricalTable has `Map<String, Map<String, Double>>` symmetric matrix. DtwSpec nests WarpingConstraint (another sealed). EditDistanceSpec has similar nested map. |
| WarpingConstraint | 3: Unconstrained(), SakoeChibaBand(windowSize), ItakuraParallelogram(maxSlope) | Discriminated | `type: unconstrained` or `type: sakoe-chiba` `window: 5` or `type: itakura` `max-slope: 2.0` | **LOW** — 3 simple variants, 0-1 fields each |
| TemporalDecay | 3: HalfLife(halfLife: Duration), Linear(zeroAt: Duration), Step(cutoff: Duration, afterCutoff: double) | Discriminated | `type: half-life` `half-life: PT24H` or `type: linear` `zero-at: PT72H` or `type: step` `cutoff: PT12H` `after: 0.5` | **LOW** — Duration fields use ISO-8601 string in YAML |
| ScopeDecay | 3: Exponential(base), Linear(maxDepth), Step(beyondExact) | Discriminated | `type: exponential` `base: 0.5` or `type: linear` `max-depth: 3` | **LOW** — 3 simple variants, 1 field each |

### Functional Interfaces / SPIs

| Type | Signature | Mapping | Blocker Detail |
|------|-----------|---------|----------------|
| LocalSimilarityFunction | `compute(FeatureValue, FeatureValue) → double` | Reference | YAML references named bean: `similarity: exact-match`. Has `EXACT_MATCH` constant. Custom impls are CDI beans. |
| OutcomeWeightingFunction | `apply(double similarity, double confidence) → double` | Reference | YAML: `outcome-weighting: linear` (references DefaultOutcomeWeightingFunction or custom). Config params (influence) are Quarkus config, not YAML-expressed. |
| TrustWeightingFunction | `apply(double similarity, double trustScore, OptionalDouble trajectory) → double` | Reference | Same pattern — `trust-weighting: default`. |
| AgentTrustProvider | `currentTrustScore(String agentId) → OptionalDouble` | Reference | Infrastructure SPI — not agent-configurable. YAML declares which provider bean, not the function body. |
| ReflectionSynthesizer | `synthesize(agentId, tenantId, sources, targetLevel) → List<ReflectionEvent>` | Reference | SPI for LLM-backed reflection. YAML: `reflection-synthesizer: llm` or `reflection-synthesizer: noop`. |
| PlanAdapter | `adapt(caseType, ScoredCbrCase, Map features) → AdaptedPlan` | Reference | SPI for CBR plan adaptation. Named bean reference. |
| PlanEnsembleAnalyzer | `analyze(caseType, scoredCases, adaptedPlans, features) → EnsemblePlan` | Reference | SPI for cross-plan structural analysis. Named bean reference. |
| ExplanationRenderer | `render(CbrRetrievalTrace) → String` | Reference | SPI for human-readable trace rendering. Named bean reference. |

### Enums

| Type | Values | Mapping | Notes |
|------|--------|---------|-------|
| MemoryDomain | record(name: String) — acts as enum-like | Direct | Plain string in YAML: `domain: experience` |
| MemoryOrder | CHRONOLOGICAL, RELEVANCE, SALIENCE | Direct | String literal |
| MemoryCapability | 14 values (CHRONOLOGICAL_ORDER through PURGE) | Direct | Capability set |
| RetrievalMode | FEATURE_ONLY, SEMANTIC_ONLY, HYBRID | Direct | String literal |
| FusionStrategy | RRF, DBSF, CC | Direct | String literal (from fusion-api) |
| TrendType | SLOPE, DELTA, VOLATILITY, ACCELERATION, CHANGE_POINTS, DURATION, OBSERVATION_COUNT | Direct | String literal |
| AdaptationAction | RETAINED, SUBSTITUTED, BOOSTED, SUPPRESSED, ADDED, REMOVED | Direct | String literal (result enum, but appears in adapted plan config) |
| StepAgreement | UNANIMOUS, CONSENSUS, CONTESTED, MINORITY, UNIQUE | Direct | Result enum — appears in ensemble output |
| QualitySignal | POSITIVE, NEGATIVE, NEUTRAL | Direct | String literal |

---

## cognitive-api

### Records

| Type | Fields | Required | Optional | Mapping | YAML Shape | Blockers |
|------|--------|----------|----------|---------|------------|----------|
| Confidence | 3 | origin, value | decayReference (null = no decay) | Direct | `origin: STATED` `value: 0.9` `decay-reference: 2026-06-01T12:00:00Z` or shorthand `confidence: 0.9` (UNKNOWN origin, no decay) | Shorthand vs full form — YAML parser needs to accept both |

### Sealed Hierarchies

| Type | Variants | Mapping | YAML Shape | Complexity |
|------|----------|---------|------------|------------|
| TemporalMark | 3: WallClock(instant), Relative(offset, anchor?), Ordinal(turnId, resolved) | Discriminated | `type: wall-clock` `instant: 2026-06-01T12:00:00Z` or `type: relative` `offset: PT-2H` or `type: ordinal` `turn: t42` `resolved: 2026-06-01T12:00:00Z` | **LOW** — 3 variants, simple fields. Duration/Instant use ISO-8601 strings. |

### Enums

| Type | Values | Mapping | Notes |
|------|--------|---------|-------|
| ConfidenceOrigin | STATED, INFERRED, SPECULATED, UNKNOWN | Direct | String literal |
| AffectType | INHERENT, ANTICIPATORY | Direct | String literal |

### Functional / Generic

| Type | Kind | Mapping | Blocker Detail |
|------|------|---------|----------------|
| ModulationFactor\<T\> | @FunctionalInterface — `apply(T item, ModulationProfile<T>) → double` | Unmappable | Generic type parameter + functional body. Pre-built factories in `ModulationFactors` (cognitive-index) are named — YAML could reference: `modulation: recency-decay`. But custom functions are Java-only. |
| ModulationProfile\<T\> | record with 5 `Function<T, ?>` fields | Unmappable | All fields are `Function<T, X>` — pure runtime accessor extraction. Not configurable. Pre-built profiles in `ModulationProfiles` are constants. |

---

## cognitive-index

### Records (config-surface)

| Type | Fields | Required | Optional | Mapping | YAML Shape | Blockers |
|------|--------|----------|----------|---------|------------|----------|
| CognitiveProfileQuery | 7 | tenantId, (nodeId XOR entityName), memoryLimit | subgraphId, domains, includeEdges | Direct | `entity: Grandma` `tenant: t1` `domains: [experience, mood]` `memory-limit: 50` | Mutual exclusion (nodeId vs entityName) — YAML parser validates |
| TemporalQuery | 7 | tenantIds, limit | from, to, sources, entityIds, upcoming | Direct | `tenants: [t1]` `from: 2026-01-01T00:00:00Z` `limit: 100` `sources: [MINDMAP, MEMORY]` | `StoreKind` inner enum — string literals |
| TemporalFocusConfig | 4 | all required (primitives) | — | Direct | `proximity-scale: 7.0` `worsening-boost-cap: 1.0` `improving-dampen: 0.5` `volatility-boost-cap: 0.5` | None — all primitives with `defaults()` factory |
| ModulationContext | 3 | moodState, personalityWeights, now | — | Direct | Nested MoodState + PersonalityWeights | Composed of other Direct-mappable types |

### Functional

| Type | Kind | Mapping | Blocker Detail |
|------|------|---------|----------------|
| TemporalRanker | @FunctionalInterface — `rank(TemporalEntry, Instant) → double` | Reference | Pre-built rankers could be named. Custom ranking is Java-only. |

---

## fusion-api

### Enums

| Type | Values | Mapping | Notes |
|------|--------|---------|-------|
| FusionStrategy | RRF, DBSF, CC | Direct | String literal — already referenced by CbrQuery |

### Config aspects

| Type | Notes | Mapping | Blocker Detail |
|------|-------|---------|----------------|
| ScoreFusion.ScoredLeg\<T\> | Contains `ToDoubleFunction<T>` scorer field | Unmappable | Generic + functional — runtime scoring function. Leg *weights* are configurable (already in FusionWeightsConfig via Quarkus config), but the scorer itself is Java-only. |

---

## Summary

### Counts

| Mapping Class | Count | Notes |
|---------------|-------|-------|
| **Direct** | 28 records + 13 enums | Core config surface — all expressible in YAML |
| **Discriminated** | 8 sealed hierarchies (40 total variants) | Need `type:` discriminator convention. FeatureField (9 variants) and SimilaritySpec (6 variants, nested) are the most complex. |
| **Reference** | 10 functional/SPI interfaces | YAML references named CDI bean. Pre-built defaults exist for most. |
| **Unmappable** | 3 types | ModulationFactor\<T\>, ModulationProfile\<T\>, ScoreFusion.ScoredLeg\<T\> — generic + functional. |

### Key Blockers for #247 (YAML Schema Design)

1. **Sealed hierarchy discriminator convention** — 8 hierarchies need a consistent `type:` key strategy. Some (FeatureValue) can be inferred from YAML value shape; others (FeatureField, SimilaritySpec) need explicit discriminators. Recommend: always allow explicit `type:`, infer when unambiguous.

2. **Nested sealed types** — FeatureField.TimeSeries contains SimilaritySpec which contains WarpingConstraint: 3 levels of discriminated nesting. YAML readability at depth 3 is a concern.

3. **Recursive structures** — CbrFilter.AllOf contains `List<CbrFilter>`. FeatureField.NestedObject/ObjectList contain `List<FeatureField>` (constrained to flat types). YAML handles recursion naturally, but validation rules need encoding.

4. **Functional interface → named reference** — 10 SPIs need a registry pattern. YAML says `plan-adapter: my-adapter`; CDI resolves `@Named("my-adapter") PlanAdapter`. Convention needed: `@Named` qualifier or custom annotation?

5. **DerivedEdgeRule** — the highest-priority blocker for YAML parity. Current rules are Java lambdas. #249 (Declarative Rule DSL) designs the YAML replacement — a rule expression language, not just a named reference.

6. **Confidence shorthand** — `confidence: 0.9` (simple scalar) vs `confidence: {origin: STATED, value: 0.9, decay-reference: ...}` (full form). YAML parser needs polymorphic deserialization based on value shape.

7. **Map-keyed types** — CbrQuery.features (`Map<String, FeatureValue>`), CbrQuery.filters (`Map<String, CbrFilter>`), PersonalityWeights (`Map<MemoryDomain, Double>`), SimilaritySpec.CategoricalTable (`Map<String, Map<String, Double>>`). YAML maps are natural, but the key types vary (String, MemoryDomain record).

8. **Platform types** — CbrQuery.scope is `io.casehub.platform.api.path.Path` — cross-module dependency. YAML representation is a string path parsed by the platform. Need import/resolution convention.

## References

- MindMapQuery.java — 12-field record with builders
- NodeInput.java — 12-field record with builders
- EdgeInput.java — 11-field record with builders
- NodeUpdate.java — 13-field mutation record with additive semantics
- SubgraphInput.java — 3-field record
- NodeRef.java — 3-field record
- EdgeTypeDefinition.java — vocabulary entry
- DerivedEdgeRule.java — SPI with MindMapStore access
- RecurrenceRule.java — RFC 5545 RRULE subset
- MindMapVocabulary.java — vocabulary container with GoF builder
- MemoryInput.java — 10-field record with builders
- MemoryQuery.java — 8-field record with builders
- CbrQuery.java — 16-field record (most complex)
- CbrFeatureSchema.java — schema with FeatureField list
- CbrRetentionPolicy.java — retention config
- FeatureField.java — 9-variant sealed hierarchy
- FeatureValue.java — 7-variant sealed hierarchy with type inference
- CbrFilter.java — 8-variant sealed hierarchy with recursion
- SimilaritySpec.java — 6-variant sealed hierarchy with nested sealed
- TemporalDecay.java — 3-variant sealed hierarchy
- ScopeDecay.java — 3-variant sealed hierarchy
- WarpingConstraint.java — 3-variant sealed hierarchy
- PersonalityWeights.java — domain weight map
- MoodState.java — 9-field PAD event
- MoodBaseline.java — 3-field PAD baseline
- TrendSpec.java — trend detection config
- Confidence.java — 3-field record with shorthand factories
- TemporalMark.java — 3-variant sealed hierarchy
- ModulationFactor.java — generic functional interface
- ModulationProfile.java — generic accessor record
- CognitiveProfileQuery.java — 7-field record with mutual exclusion
- TemporalQuery.java — 7-field record with StoreKind enum
- TemporalFocusConfig.java — 4-field tuning config
- LocalSimilarityFunction.java — functional interface with EXACT_MATCH constant
- OutcomeWeightingFunction.java — functional interface
- TrustWeightingFunction.java — functional interface
- AgentTrustProvider.java — functional interface
- ReflectionSynthesizer.java — SPI
- PlanAdapter.java — SPI
- PlanEnsembleAnalyzer.java — SPI
- ExplanationRenderer.java — SPI
- FusionStrategy.java — 3-value enum
- GitHub #246 — focal issue
- GitHub #247 — downstream: YAML schema design
- GitHub #249 — downstream: declarative rule DSL
