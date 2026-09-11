## D1: Thing ↔ MindMapNode — base interface hierarchy (content vs cognition)

**Choice:** Thing is a base interface in thing-api (zero deps). MindMapNode extends Thing, adding cognitive features (confidence, PAD, temporal bounds, provenance, principalId, sharedWith, refs). Every MindMapNode IS a Thing — no wrapping, no bridging. Consumers who want semantic knowledge representation depend on thing-api only. Cognitive system components depend on mindmap-api for the full MindMapNode.
**Alternatives:**
- Separate types, no hierarchy (original D1) — wrapping layer, ThingResolver needed, consumer can't pass a MindMapNode where a Thing is expected. Reviewer correctly identified this as unnecessary complexity for three convenience methods.
- is()/as() as default methods on MindMapNode only (reviewer proposal) — works but couples consumers to mindmap-api which drags in cognitive-api (Confidence) and platform-api (PrincipalId). Consumers wanting just semantic knowledge get the full cognitive dependency tree.
- ThingView CDI service (no new type) — no object identity for Thing.
**Rationale:** Thing represents semantic knowledge representation — entities with identity, type, properties, and traits. MindMapNode extends this with cognitive features the agent's reasoning system needs. All MindMapNodes are Things (even simple content nodes — they just have type "note" or "general"). The hierarchy gives consumers a clean, zero-deps dependency surface while preserving MindMapNode's full richness for the cognitive system. No bridging needed — a MindMapNode IS a Thing.
**Trade-offs:** mindmap-api gains a thing-api dependency (zero deps, trivial). Thing.type() is abstract — MindMapNode implementations resolve it from the subgraph type.
**Sources:** MindMapNode.java, TraitProxy.java, issue #278 (Thing model prior art), spec review R1-02 (MindMapNode is already a clean domain interface)
**Exploration:** deep-analysis (revised through spec review)
**Status:** revised — from separate-types-no-hierarchy to base-interface-hierarchy after spec review and user clarification. Thing is semantic knowledge representation; MindMapNode is cognitive augmentation.

## D2: Module placement — new thing-api

**Choice:** New `thing-api` module at tier-0 (zero deps, pure Java). Contains Thing interface and the convention-based proxy for as(). Consumer-facing module — app developers depend on thing-api, not mindmap-api.
**Alternatives:**
- cognitive-api — already the zero-deps cross-cutting home, but Thing is a knowledge representation base type, not a cognitive classification. Overloads cognitive-api's purpose (D26 from #253 defines it as cognitive classifications).
- mindmap-api — co-locates Thing with MindMapNode, but blurs the consumer/internal boundary. Consumers importing mindmap-api would see both Thing and MindMapNode, defeating the separation.
**Rationale:** Thing is foundational — the knowledge representation base type that Subject, MindMap, and the type system all reference. It warrants its own module with a clean dependency surface. Follows the same pattern as cognitive-api (zero-deps shared type module) but for a different architectural concern.
**Trade-offs:** Adds a module to the reactor. Justified — the concern is genuinely distinct from cognitive classification.
**Depends on:** D1 (Thing ↔ MindMapNode separation)
**Sources:** cognitive-api/pom.xml (zero-dep pattern), D26 from #253 (cognitive-api acceptance criteria)
**Exploration:** quick
**Status:** revised — removed Triple from module contents (R1-02). D3 decided against triples on Thing; no Triple type needed.

## D3: Thing interface shape — minimal: identity + properties + traits + is/as

**Choice:** Thing carries identity (id, name, type), properties (Map<String, String>), traits (Set<String>), and is()/as() methods. No edges, no triples view, no graph traversal. Relationships are a graph concern queried via the store, not carried on the entity.
**Alternatives:**
- With triples — unified property+edge view (List<Triple>). Conceptually elegant but forces hydration of edges at construction time. Either Thing carries stale relationship data or needs a live store reference, both violating the lightweight zero-deps contract.
- Rich — includes edges and type metadata. Thing becomes a heavy aggregate. Pulls graph traversal into a zero-deps type, defeating its purpose.
**Rationale:** Thing is an entity with identity, properties, and trait-based type identity. Properties ARE the datatype triples (this, key, value). Edge queries give the object triples (this, edgeType, target). No need to unify them on the type. Keeps thing-api zero-deps and Thing lightweight — constructable from a property map without store access.
**Trade-offs:** Consumers who need relationships must query the store separately. This is correct — relationships are live graph state, not entity state.
**Depends on:** D1 (separation), D2 (zero-deps module)
**Sources:** MindMapNode.java (property/trait surface), TraitProxy.java (as() pattern), MindMapStore.neighbors() (edge queries)
**Exploration:** quick
**Status:** captured

## D4: is()/as() implementation — pre-computed traits, convention-based proxy

**Choice:** `is(String traitName)` checks `traits().contains(traitName)` — traits are pre-computed at store time by TraitRule evaluation in the decorator stack. Thing reads the result. `as(Class<T>)` uses a JDK Proxy in thing-api that maps interface method names to `property(methodName)` calls. Zero external deps — only java.lang.reflect.Proxy and the Thing interface. Thing is a snapshot value, not a live evaluator.
**Alternatives:**
- Live rule evaluation — Thing carries a rule evaluator and edges, evaluating is() on demand. Violates zero-deps, makes Thing a live object rather than a value. Rules need edge access which is a graph concern (D3).
**Rationale:** Follows the Drools pattern: the rule engine computes traits, the fact carries the result. Trait computation happens at the store/decorator layer where edges and rules are available. Thing is a value — lightweight, snapshot-based, no store reference. The method-name-to-property-key convention (birthday() reads property("birthday")) is already proven by TraitProxy and needs no external knowledge.
**Trade-offs:** Traits reflect state at construction time. If properties change after Thing is constructed, is() may be stale. Correct behavior — consumers should re-resolve from the store for fresh state.
**Depends on:** D1 (separation), D3 (Thing shape)
**Sources:** TraitProxy.java (JDK Proxy + method-name convention), TraitInvocationHandler.java, PersonableTraitRule.java (store-time evaluation)
**Exploration:** quick
**Status:** captured

## D5: SubgraphType enum → dynamic string + constants

**Choice:** Replace SubgraphType enum with String in MindMapSubgraph and SubgraphInput. Current enum values become lowercase string constants in a SubgraphTypes utility class in mindmap-api. Lowercase-normalized (same convention as Subject.type()). RuleCondition.InSubgraphType changes its field from SubgraphType to String and gets a real implementation (currently returns false).
**Alternatives:**
- SubgraphType record wrapper — SubgraphType(String name) for type safety. Adds a type for what is just a string label. The enum-to-record migration creates more churn than enum-to-String for callers.
**Rationale:** LLM discovers new entity types at runtime — a fixed enum prevents this. String with constants follows Subject(type, id) convention. Constants preserve compile-time references for well-known types without restricting new ones. InSubgraphType finally gets a working implementation.
**Trade-offs:** Callers using SubgraphType.PERSON switch to SubgraphTypes.PERSON — mechanical migration. Loss of exhaustive switch — acceptable since the point is that new types can appear at runtime.
**Depends on:** D1 (Thing/MindMapNode separation — subgraph types are graph partitioning in mindmap-api, not Thing identity in thing-api)
**Sources:** SubgraphType.java (6-value enum), SubgraphInput.java, MindMapSubgraph.java, RuleCondition.InSubgraphType (returns false), Subject.java (lowercase normalization convention), issue #281
**Exploration:** quick
**Status:** captured

## D6: Type registry — types as MindMap nodes in a type subgraph

**Choice:** Types are first-class data in MindMap. A designated subgraph (type=SubgraphTypes.TYPE_SYSTEM) contains type-definition nodes. `subtype-of` edges form the type hierarchy. Core types carry a `java-class` property linking to their Java interface. Dynamic types are nodes without a `java-class` property. No new SPI — type management is MindMapStore operations on a special subgraph. A TypeResolver utility in a higher module reads the type subgraph and builds the mapping.
**Alternatives:**
- Standalone TypeRegistry SPI — separate storage from MindMap. Cleaner separation but duplicates graph structure (nodes, edges, hierarchy) that MindMap already provides. Two storage systems for the same concept.
- In-code registry only — core types registered programmatically. No runtime type discovery. Blocks the LLM from discovering new types, which is the core requirement.
**Rationale:** The type system IS knowledge about knowledge — it belongs in the knowledge graph. MindMap already provides nodes, edges, properties, and traversal. Using a special subgraph means type hierarchy queries are just graph traversal — no new query infrastructure. The LLM discovers new types by adding nodes to the type subgraph, which is exactly how it adds any other knowledge.
**Trade-offs:** Type operations couple to MindMapStore availability. TypeResolver (the consumer utility) needs MindMapStore access. thing-api stays zero-deps — it carries the Thing interface but not the type resolution logic.
**Depends on:** D5 (dynamic subgraph types — TYPE_SYSTEM is a new constant)
**Sources:** issue #278 (type subgraph proposal), MindMapStore.nodesIn() (subgraph queries), MindMapStore.neighbors() (hierarchy traversal), CognitiveLoader.java (vocabulary registration pattern)
**Exploration:** quick
**Status:** captured

## D7: Subject ↔ Thing bridge — convention, not dependency

**Choice:** Subject references Thing by naming convention. Subject.type() == Thing.type() (core or dynamic type name). Subject.id() == Thing.id() (MindMapNode UUID). No code dependency between memory-api and thing-api. Resolution from Subject to Thing happens at the call site via ThingResolver. Subject stays exactly as-is — a lightweight pointer.
**Alternatives:**
- Subject depends on thing-api — Subject gains toThing(ThingResolver) or Thing gains toSubject(). Forces a dependency between two tier-0 modules. Couples memory-api to the Thing concept.
- Shared supertype (ThingRef) — both Subject and Thing implement a reference interface. Over-engineered for an (id, type) pointer convention.
**Rationale:** Follows the NodeRef pattern — NodeRef(scheme="memory", id=memoryId) is a convention, not a type dependency. Both modules stay independent and zero-deps. The convention is simple and documented: same id, same type. Resolution is the caller's concern, not a type system concern.
**Trade-offs:** No compile-time enforcement that Subject.type() matches Thing.type(). Convention-based — if either side changes its type naming, the bridge breaks silently. Acceptable for a pre-release platform where both conventions are documented.
**Depends on:** D2 (thing-api zero-deps), D3 (Thing carries type())
**Sources:** Subject.java (type + id), NodeRef.java (convention-based cross-store reference), issue #278 (Subject becomes a Thing reference)
**Exploration:** quick
**Status:** captured

## D8: Dynamic property schema — schema as properties on type nodes

**Choice:** Property schema for dynamic types is stored as properties on type nodes in the type subgraph. Convention: `schema.{fieldName}.type={string|number|boolean|date}`, `schema.{fieldName}.required={true|false}`. No new types in thing-api. A PropertySchema utility in mindmap-intelligence reads these properties and validates Thing properties against the schema. Validation is advisory (warn, not reject) — LLMs may produce novel properties that extend the schema.
**Alternatives:**
- PropertySchema record in thing-api — structured type for field definitions. Needs serialization conventions since MindMapNode properties are Map<String, String>. Cleaner API but adds complexity for a property-convention problem.
- JSON Schema as single property — full JSON Schema power in a `property-schema` property. Heavyweight JSON parsing in a zero-deps context. MindMap properties are strings, not JSON values.
**Rationale:** Properties on type nodes follow the existing model — no new types, no serialization, no parsing. Schema is metadata about a type stored as more properties using a naming convention. The advisory validation matches the design principle: LLM-discovered types may have novel properties the schema doesn't cover yet. The schema documents expectations and enables reasoning, not enforcement.
**Trade-offs:** Schema expressiveness is limited to simple types (string/number/boolean/date). No nested objects, no array types. Sufficient for the property model (Map<String, String>). If richer schema is needed later, the convention extends naturally (schema.{field}.items.type, etc.).
**Depends on:** D6 (types as MindMap nodes — schema lives on type nodes)
**Sources:** MindMapNode.properties() (string-valued property model), issue #282 (dynamic property schema), CognitiveLoader.java (convention-based metadata registration)
**Exploration:** quick
**Status:** captured

## D9: No ThingResolver needed — MindMapNode IS a Thing

**Choice:** No ThingResolver. Since MindMapNode extends Thing, any MindMapNode returned by MindMapStore is already a Thing. Consumers use MindMapStore directly and narrow to the Thing interface. TypeRegistry in mindmap-intelligence provides type metadata queries (subtypes, java-class mapping, schema).
**Alternatives:**
- ThingResolver CDI bean (original D9) — unnecessary when MindMapNode IS a Thing. No bridging layer needed.
**Rationale:** The base-interface hierarchy (D1 revised) eliminates the need for a resolver. MindMapStore.getNode() returns MindMapNode which IS a Thing. Consumer code: `Thing thing = store.getNode(id, tenant);` — the widening cast is implicit.
**Trade-offs:** Consumers still need MindMapStore access to retrieve Things. This is the correct trade-off — the store is the entry point for all graph operations.
**Depends on:** D1 (base-interface hierarchy)
**Sources:** D1 revision, spec review R1-02
**Exploration:** quick
**Status:** revised — ThingResolver dropped, replaced by direct MindMapStore access with Thing interface narrowing

## D10: Thing.type() — derived from subgraph type

**Choice:** Thing.type() returns the subgraph type of the node. After SubgraphType → String (D5), the subgraph type IS the entity type. No separate `type` property needed — no consistency problem between two sources of truth. MindMapNode gains a `subgraphType()` method that returns the subgraph's type string, resolved by the store at construction time. Thing.type() delegates to this via MindMapNode's implementation.
**Alternatives:**
- Explicit `type` property on nodes (original D10) — creates a consistency problem when type property disagrees with subgraph type. Requires ThingResolver validation. Adds a reserved property key. Spec review R1-05 correctly identified this as duplication.
- Inferred from traits — ambiguous when multiple traits satisfied.
**Rationale:** After D5, subgraphs have dynamic string types. The subgraph type IS what the entity is — a node in a "person" subgraph IS a person. A node in a "note" subgraph IS a note. No separate property needed, no consistency burden, no reserved key. subgraphType() on MindMapNode is type-safe, discoverable, and cannot be overwritten by callers.
**Trade-offs:** An entity's type is determined by its subgraph membership. Changing an entity's type means moving it to a different subgraph. This is a feature, not a bug — type identity is a structural commitment, not a mutable property.
**Depends on:** D5 (dynamic subgraph types), D1 (Thing as base interface — type() is on Thing, subgraphType() on MindMapNode)
**Sources:** spec review R1-05, R1-06, MindMapStore.createSubgraph(), MindMapExtractor.findOrCreateSubgraph()
**Exploration:** quick
**Status:** revised — type derived from subgraph (spec review R1-05), subgraphType() accessor replaces magic property (spec review R1-06)

## D11: Trait interfaces — consumer-defined, platform-provided optional

**Choice:** as(Class<T>) works with ANY interface by convention — method names map to property keys. Consumers can define their own domain-specific trait interfaces without depending on platform definitions. The existing trait interfaces (Personable, Projectlike, Organisational, Eventlike) stay in mindmap-intelligence as platform-provided conveniences, not required dependencies. thing-api ships with ZERO pre-defined trait interfaces — it is purely structural.
**Alternatives:**
- Move trait interfaces to thing-api — thing-api carries domain knowledge (birthday, role, email). Violates zero-deps purpose.
- New thing-traits module — more module proliferation for optional convenience types.
- Consumers must import mindmap-intelligence — defeats the consumer/internal separation.
**Rationale:** The proxy is generic — it maps method names to property keys regardless of which interface defines them. A consumer who defines `interface Customer { Optional<String> accountId(); }` gets typed access to `property("accountId")` without importing any platform module. Platform traits are optional conveniences for common entity types. This is the most architecturally coherent option (R1-03).
**Trade-offs:** No shared trait vocabulary across consumers. Each consumer may define overlapping interfaces. Acceptable — the property keys are the shared vocabulary, not the Java interfaces.
**Depends on:** D4 (convention-based proxy)
**Sources:** TraitProxy.java (generic method-name convention), Personable.java (platform trait example), R1-03
**Exploration:** surfaced by review (R1-03)
**Status:** captured

## D12: Single proxy in thing-api — no PropertyAccessor needed

**Choice:** The JDK Proxy invocation handler lives in thing-api and operates on Thing.property(). Since MindMapNode extends Thing, it inherits property(). One proxy implementation serves both. No PropertyAccessor abstraction needed — Thing itself IS the property accessor. TraitInvocationHandler in mindmap-intelligence is replaced by delegating to Thing.as() (which MindMapNode inherits).
**Alternatives:**
- PropertyAccessor functional interface (original D12) — unnecessary indirection now that MindMapNode extends Thing. Thing already has property().
- Duplicate proxy in thing-api and mindmap-intelligence — maintenance burden for type coercion logic.
**Rationale:** The base-interface hierarchy (D1 revised) makes PropertyAccessor redundant. The proxy in thing-api operates on Thing.property(). MindMapNode inherits as() from Thing, which calls the proxy. One implementation, one interface, zero extra abstractions.
**Trade-offs:** mindmap-intelligence's TraitProxy.as() becomes a deprecated delegate to Thing.as().
**Depends on:** D1 (MindMapNode extends Thing), D4 (convention-based proxy)
**Sources:** TraitInvocationHandler.java, spec review R1-04
**Exploration:** surfaced by review (R1-04)
**Status:** revised — PropertyAccessor dropped, Thing itself is the property contract

## D13: Type subgraph bootstrapping — CognitiveLoader at startup

**Choice:** CognitiveLoader (@PostConstruct in mindmap-intelligence) bootstraps the TYPE_SYSTEM subgraph and core type nodes at startup. Same pattern as existing vocabulary registration. Creates the subgraph if absent, creates core type nodes (person, project, organisation, concept, research-area, general) if absent. Idempotent — safe to run on every startup. Per-tenant: each tenant gets its own type subgraph populated.
**Alternatives:**
- Lazy creation on first ThingResolver call — race conditions in concurrent environments. First resolve fails if type subgraph doesn't exist yet.
- Migration script — one-time setup but doesn't handle new tenants created after deployment.
**Rationale:** CognitiveLoader already does startup registration (vocabulary). Adding type subgraph bootstrap is natural. @PostConstruct runs once, idempotent, handles all tenants. discoverTenants() (from CaseMemoryStore) provides the tenant list. Instance<MindMapStore> graceful degradation — if no MindMap backend, no bootstrap needed.
**Trade-offs:** Core types are hardcoded in CognitiveLoader. Adding a new core type requires a code change. Acceptable — core type promotion is a conscious developer act (issue #278).
**Depends on:** D6 (type subgraph), D5 (TYPE_SYSTEM constant)
**Sources:** CognitiveLoader.java (existing @PostConstruct registration), MindMapStore.createSubgraph(), R1-06
**Exploration:** surfaced by review (R1-06)
**Status:** captured

## D14: Thing is an interface — MindMapNode implementations provide the concrete type

**Choice:** Thing is an interface in thing-api. No implementation record in thing-api — the implementations are the existing MindMapNode implementations (InMemoryMindMapNode, SqliteMindMapNode) which implement MindMapNode which extends Thing. Thing carries default methods for is() and as(); abstract methods for id(), name(), type(), property(), properties(), traits().
**Alternatives:**
- ThingRecord in thing-api (original D14) — unnecessary now that MindMapNode extends Thing. The MindMapNode implementations ARE the Thing implementations.
- Thing as a class — doesn't work since MindMapNode is an interface and needs to extend Thing.
**Rationale:** The base-interface hierarchy (D1 revised) means Thing is purely an interface. Its implementations are the MindMapNode backend implementations. No separate Thing implementation needed.
**Trade-offs:** thing-api has no standalone Thing implementation — you can't create a Thing without a MindMapNode. If a standalone Thing is ever needed (e.g., for testing without MindMap), a simple record can be added to thing-api later.
**Depends on:** D1 (MindMapNode extends Thing), D2 (thing-api module)
**Sources:** InMemoryMindMapNode (mindmap-inmem), SqliteMindMapNode (mindmap-sqlite), R1-16
**Exploration:** surfaced by review (R1-16)
**Status:** revised — no ThingRecord needed, MindMapNode implementations are the Thing implementations
