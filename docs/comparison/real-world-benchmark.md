# Real-World Benchmark: gardenSearch vs grep

*Generated 2026-06-27 · Issue #27*

## Configuration

| Parameter | Value |
|-----------|-------|
| Search mode | Dense-only (no ONNX/SPLADE) |
| Embedding model | nomic-embed-text (Ollama) |
| Vector dimensions | 768 |
| Distance metric | Cosine |
| HNSW config | m=16, ef_construct=100, threshold=10000 |
| Indexed points | 1,960 |
| gardenSearch result cap | 8 |
| grep result cap | 20 (of N total) |
| grep search surface | 6,672 .md files |
| gardenSearch search surface | 1,960 indexed entries |
| Label files in grep surface | 2,568 |
| Summary files in grep surface | 1,913 |
| Search surface ratio | grep 3.4x larger than gardenSearch |

**Note:** With 1,960 points below Qdrant's indexing threshold (10,000), vectors
are stored in flat segments — no HNSW graph is built. Search uses brute-force
scan, which is accurate but would not scale. For this corpus size, this has no
effect on result quality.

## Methodology

### Selection Criteria

Issues are a stratified sample across casehub repos. Each must be:

- **Closed with a substantial description** — long specs or detailed problem
  descriptions signal complexity where garden context matters
- **Technology diverse** — one issue per band, guided by PLATFORM.md and
  applications.md to ensure coverage across the tech surface
- **Repo diverse** — avoid picking 4+ from the same repo

### Three-Way Comparison

Each scenario runs three searches to separate the benefits of embedding-based
retrieval from query formulation:

| Method | Input | Retrieval | Output |
|--------|-------|-----------|--------|
| grep | keywords (2-4) | substring match | unsorted file paths |
| gardenSearch (keywords) | same keywords | embedding similarity | 8 ranked results |
| gardenSearch (NL) | problem description | embedding similarity | 8 ranked results |

**What each comparison measures:**

- **grep vs gardenSearch-keywords:** the **combined retrieval + ranking
  benefit**. Same keyword input, but different retrieval mechanisms (substring
  match vs embedding similarity) AND different output structure (unsorted vs
  ranked). These factors are confounded — the comparison shows the total
  benefit of switching from grep to gardenSearch, not the ranking benefit alone.
- **gardenSearch-keywords vs gardenSearch-NL:** the **query formulation
  benefit**. Same retrieval mechanism, same ranking — only the query style
  differs. This cleanly isolates whether natural language queries produce
  better results than keyword queries when the retrieval mechanism is held
  constant.

### Query Derivation

Queries are derived to match how each method would actually be used:

- **grep keywords:** follow `work-start` Step 3b's exact procedure — extract
  2-4 keywords from the issue title and description (domain name, library,
  framework, key concept). No free-form keyword crafting. Run as:
  `git -C ~/.hortora/garden grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'`
- **gardenSearch (keywords):** pass the same keyword string to gardenSearch
- **gardenSearch (natural language):** derive a natural language description of
  the problem or domain from the issue, the way skills would call gardenSearch

### Grep Result Cap and Scoring Procedure

gardenSearch returns 8 ranked results with full entry content inline. grep
returns N unsorted file paths (the synthetic benchmark showed 9 to 2,176).
Scoring all results is impractical.

**Cap policy:** Score the first 20 results returned by `git grep`. This mirrors
the realistic skill workflow — the LLM processes the first ~20 file paths and
stops. Report the total grep count separately as a noise-floor metric.

**Scoring procedure:** For grep results, the evaluator reads each entry's full
content (title + body) before scoring — not just the filename or the matching
line. gardenSearch returns inline content; grep requires deliberate file reads.
Both methods receive equal evaluation attention to prevent effort-driven bias.

### Scoring Rubric

Each returned entry is scored:

| Score | Meaning |
|-------|---------|
| 0 | Noise — unrelated to the issue |
| 1 | Tangentially related — right domain but not directly actionable |
| 2 | Directly relevant — would have influenced the work |

### Per-Scenario Metrics

Absolute counts AND precision for each method:

- **Total scored:** gardenSearch: up to 8; grep: up to 20 (of N total)
- **Relevant count** (score >= 1) and **precision** (relevant / total scored)
- **Highly relevant count** (score = 2) and **precision** (score=2 / total scored)
- **Unique finds** — entries surfaced by only one method (with score)
- **Noise entries** — for grep, flag results from `_summaries/` or `labels/`
  paths separately
- **Machine relevance correlation** — for gardenSearch results, report the
  machine relevance score (0.0-1.0) alongside the judgment score (0/1/2);
  analyse whether high machine relevance consistently maps to high judgment
  scores (calibration finding) or diverges (tuning signal)

### Win Criterion

Per-scenario wins are judged on two dimensions:

1. **Precision** — highest precision (relevant / total scored). Precision is
   the primary metric because it measures signal quality independent of the
   cap asymmetry (8 vs 20).
2. **Discovery** — most unique score=2 finds not surfaced by other methods.
   This measures whether a method finds entries the others miss entirely.

**Overall per-scenario verdict:**
- **Clear win:** highest precision AND most unique score=2 finds
- **Advantage:** highest precision OR most unique score=2 finds (but not both)
- **Tie:** precision within 10 percentage points and unique score=2 finds
  balanced (+/-1)

The summary aggregates per-scenario verdicts into an overall tally.

### Search Surface Asymmetry

The two methods search different file sets:

- **gardenSearch** indexes 1,960 entries: `.md` files with YAML frontmatter,
  excluding `_`-prefixed directories. `FlatCorpusStore.list()` filters
  `!p.startsWith("_")`, removing `_summaries/` and `_index/`.
  `GardenMetadataExtractor` returns empty content for files without frontmatter,
  effectively excluding label files (plain keyword lists, no frontmatter).

- **grep** searches 6,672 `.md` files: its pathspec excludes only `GARDEN.md`,
  `CHECKED.md`, `DISCARDED.md`. It searches summaries, labels, and structural
  files that gardenSearch never indexes — a 3.4x larger file set, predominantly
  noise.

This asymmetry is a genuine characteristic of how the tools work today. grep's
broader surface inflates its result count with label files (e.g.
`labels/qdrant.md`) and summaries (e.g. `_summaries/jvm/GE-20260609-2abdfd.md`)
that score 0 or 1 in any relevance rubric. The benchmark documents this
difference rather than equalising it — the noise is what skills actually
experience. The report notes whether grep results include label/summary files
and whether this affects precision.

### Bias Disclosure

This benchmark has a compounding evaluator bias that favours gardenSearch at
every link in the chain:

1. `nomic-embed-text` was selected for Claude's consumption
2. Claude derives natural language queries in its own comprehension style
3. The engine ranks by similarity to Claude's query style
4. Claude evaluates whether those results are useful to itself

Mitigations:
- grep keyword extraction follows `work-start`'s mechanical procedure, not
  Claude's free-form extraction — this controls for query derivation bias
- The three-way comparison separates retrieval+ranking from query formulation
  (though grep vs gardenSearch-keywords confounds retrieval mechanism with
  ranking — acknowledged above)
- Precision metrics are comparable across methods despite cap asymmetry
- Overlap and unique-find metrics are objective regardless of evaluator
- Machine relevance vs judgment correlation analysis reveals ranking quality
  independent of the evaluator

**This benchmark measures gardenSearch's value to Claude specifically. It does
not measure general retrieval quality.** Future iterations can add human
evaluation and cross-LLM comparison.

## Selected Issues

| # | Repo | Band | Title |
|---|------|------|-------|
| casehubio/engine#542 | engine | Reactive / async | CaseContextChangedEventHandler: BlockingOperationNotAllowedException — blocking JPA on Vert.x IO thread |
| casehubio/work#264 | work | CDI / module wiring | NoOpGroupMembershipProvider should be @DefaultBean, not @Default — causes CDI ambiguity |
| casehubio/ledger#131 | ledger | Persistence / migrations | LedgerEntry.tenancyId field shadowing breaks JOINED subclass persistence |
| casehubio/platform#98 | platform | REST / messaging | CloudEvent foundation in platform-api and platform stream modules |
| casehubio/platform#100 | platform | AI / LLM / inference | ChatModel adapter backed by AgentSession — native Claude prompt caching |
| casehubio/engine#576 | engine | Testing / CI | work-adapter template-mode tests fail — InMemoryWorkItemTemplateStore/Panache storage mismatch |

### Selection Rationale

- **engine#542 (Reactive / async):** Classic blocking-on-IO-thread bug — JPA
  `EntityManager.find()` called from Vert.x event loop. 1,831 chars with stack
  trace, symptom, root cause, and fix. Touches Mutiny, Vert.x threading, and
  reactive Hibernate patterns the garden covers extensively.

- **work#264 (CDI / module wiring):** Multi-module CDI ambiguity between
  `@Default` and `@DefaultBean` providers of `GroupMembershipProvider`. 1,522
  chars. Represents the `@Alternative`/`@DefaultBean` wiring pattern that
  recurs across the casehub module hierarchy.

- **ledger#131 (Persistence / migrations):** JPA JOINED-inheritance subclass
  field shadowing causes Hibernate to silently generate wrong SQL. 4,706
  chars with detailed entity hierarchy, SQL traces, and migration steps.
  Deep persistence issue touching Flyway, Hibernate 6, and multi-tenancy.

- **platform#98 (REST / messaging):** Spec-grade issue defining the CloudEvent
  foundation — CDI event types, `StreamContext` SPI, tenancy propagation,
  CloudEvents SDK integration. 4,726 chars. Represents the messaging and
  event-driven architecture domain.

- **platform#100 (AI / LLM / inference):** ChatModel adapter bridging
  LangChain4j's `ChatModel` interface to Claude's native `AgentSession` for
  prompt caching. 3,429 chars. Covers the AI/LLM integration layer —
  LangChain4j, Claude SDK, prompt caching, token cost optimization.

- **engine#576 (Testing / CI):** `InMemoryWorkItemTemplateStore` vs Panache
  storage mismatch causing test failures. 2,357 chars. Tests fail because
  `@Alternative @Priority` in-memory store bypasses Panache's JPA lifecycle.
  Crosses CDI test wiring and persistence mocking patterns.

**Repo distribution:** engine (2), work (1), platform (2), ledger (1) — four
repos represented. `casehubio/desiredstate` and `casehubio/ras` do not exist
as GitHub repositories; the spec's target repos were adjusted accordingly.

## Selected Specs

| Spec | Repo | Scope | Domains |
|------|------|-------|---------|
| `work/docs/superpowers/specs/2026-06-06-persistence-memory-module-design.md` | work | Narrow (single-module) | CDI priority tiers, Thread safety (ConcurrentHashMap, CopyOnWriteArrayList), Quarkus extension deactivation, Protocol compliance |
| `platform/docs/superpowers/specs/2026-06-26-agent-langchain4j-interop-design.md` | platform | Broad (cross-module) | CDI tier structure, LangChain4j interop, Mutiny reactive, SPI bidirectional bridging, JSON schema engineering, ExceptionMapper |

### Selection Rationale

- **work#191 (Narrow — persistence-memory module):** Single-module extraction
  creating `casehub-work-persistence-memory` from `testing/`. 278-line spec
  covering CDI priority ladder extension (adding Tier 3 at Priority 100),
  thread-safe data structure migration (LinkedHashMap → ConcurrentHashMap,
  ArrayList → ConcurrentHashMap<UUID, CopyOnWriteArrayList>), Quarkus
  extension deactivation for ephemeral deployment, and protocol compliance.
  Distinct from issue bands — focuses on module architecture, CDI tiers beyond
  `@DefaultBean`/`@Alternative` basics, and lock-free concurrency patterns.

- **platform#114, #115, #105 (Broad — agent-langchain4j interop):** Cross-module
  bidirectional bridge between `AgentProvider` (native SDK-based) and
  `ChatModel` (LangChain4j standard). 425-line spec covering complex CDI tier
  structure (two separate tier systems for AgentProvider and ChatModel),
  LangChain4j streaming handlers vs Mutiny Multi, JSON schema prompt
  engineering, circular dependency prevention via `Instance<ChatModel>`
  filtering, and graceful deactivation patterns. Covers different domains from
  the 6 issues: while issues touch CDI wiring and reactive, this spec focuses
  on abstraction layer interop, `@DefaultBean @Priority` coexistence, and
  bidirectional adapter contracts.

## Issue-Driven Results

### Issue 1: [Reactive / async] — casehubio/engine#542

**Summary:** `CaseContextChangedEventHandler` throws
`BlockingOperationNotAllowedException` because JPA `EntityManager` calls execute
on the Vert.x event loop thread. Fix: add `@Blocking` or dispatch via
`emitOn(Infrastructure.getDefaultWorkerPool())`.

**Keywords (work-start procedure):**
`BlockingOperationNotAllowedException|Vert\.x|IO thread|EntityManager`
Rationale: the exception class (domain signal), the framework (Vert.x), the
threading concept (IO thread), and the blocking API (EntityManager).

**Natural language query:** `"JPA blocking operation on Vert.x IO thread causes
BlockingOperationNotAllowedException when calling EntityManager from event
handler"`

#### grep (keywords) — first 20 of 330 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260613-51de5b | DB query over CaseInstanceCache for Vert.x @ConsumeEvent gate discrimination | summary | 1 |
| 2 | GE-20260607-7033a1 | Panache: use getEntityManager().createQuery() for typed single-column projections | summary | 0 |
| 3 | GE-20260420-86180e | em.merge() + em.remove() on JOINED inheritance entity throws OptimisticLockException | summary | 0 |
| 4 | GE-20260428-b966bd | Vert.x pub/sub fan-out race: mutable completion index overwritten by re-triggered component | summary | 0 |
| 5 | GE-20260501-4c94b8 | Vert.x Mutiny PgPool.getConnection() returns SqlConnection wrapper — ClassCastException | summary | 0 |
| 6 | GE-20260518-da7e91 | em.flush() + JPQL bulk UPDATE + em.clear() for same-transaction save-then-update | summary | 0 |
| 7 | GE-20260518-e4fa52 | RESTEasy Reactive endpoints calling .await() on IO thread throw BlockingOperationNotAllowedException | summary | 2 |
| 8 | GE-20260519-4a42e6 | Panache.withTransaction() requires duplicated Vert.x context — executeBlocking() root context fails | summary | 1 |
| 9 | GE-20260519-f0967f | Quarkus reactive SPI test shim: resolve Uni injections without Vert.x datasource | summary | 0 |
| 10 | GE-20260522-daca26 | Mutiny MultiEmitter.emit() from Vert.x I/O thread silently drops SSE frames | summary | 1 |
| 11 | GE-20260529-ff186e | emitOn(Infrastructure.getDefaultWorkerPool()) — shift blocking I/O off Vert.x IO thread | summary | 2 |
| 12 | GE-20260603-fdc6d5 | @Startup @ApplicationScoped prevents blocking @PostConstruct on Vert.x IO thread | summary | 1 |
| 13 | GE-20260604-96d82a | Reactive @Tool calling resolveChannel() blocks Vert.x I/O thread — @Blocking required | summary | 2 |
| 14 | GE-20260609-086833 | quarkus-vertx does NOT include Vert.x WebClient — need separate dependency | summary | 0 |
| 15 | GE-20260609-12a3d7 | ocraft 0.4.21 has 5 broken Vert.x 4.x API calls — requires bytecode patching | summary | 0 |
| 16 | GE-20260609-77a6f9 | casehub-work SNAPSHOT @RequestScoped CurrentPrincipal displaces @DefaultBean test mock | summary | 0 |
| 17 | GE-20260609-d24a97 | Vert.x 4.x rx-java2 binding WebSocket.writeBinaryMessage(Buffer) exists but core version requires Handler | summary | 0 |
| 18 | GE-20260610-7b4955 | Quarkus @ConsumeEvent is compile-time — cannot dynamically subscribe to named Vert.x addresses | summary | 1 |
| 19 | GE-20260610-e6929a | Hibernate 7 @Filter is not applied to EntityManager.find() / Panache findById() | summary | 0 |
| 20 | GE-20260611-a42c0b | Quarkus JPA subclass @Inject EntityManager omits parent qualifier — wrong PU in multi-datasource | summary | 0 |

**Precision:** 8/20 relevant (40%), 3/20 highly relevant (15%)
**Note:** All 20 results are `_summaries/` files. 330 total matches reflect the
broad keyword surface (`Vert.x` alone matches hundreds of entries).

#### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260428-fd7a65 | @Transactional(SUPPORTS) makes JPA reads callable from Vert.x IO thread | 0.61 | 2 |
| 2 | GE-20260604-ed1b02 | quarkus-flow task executor threads use newCachedThreadPool() — blocking is safe | 0.58 | 1 |
| 3 | GE-20260512-a9ad9f | Raw ExecutorService drops CDI context — @Transactional broken on background threads | 0.58 | 1 |
| 4 | GE-20260529-ff186e | emitOn(Infrastructure.getDefaultWorkerPool()) — shift blocking I/O off Vert.x IO thread | 0.57 | 2 |
| 5 | GE-20260505-da346d | @ApplicationScoped CDI beans are always-active — safe from any thread | 0.57 | 0 |
| 6 | GE-20260519-4a42e6 | Panache.withTransaction() requires duplicated Vert.x context | 0.57 | 1 |
| 7 | GE-20260604-96d82a | Reactive @Tool calling resolveChannel() blocks Vert.x I/O thread — @Blocking required | 0.56 | 2 |
| 8 | GE-20260616-312ba1 | @WithSession requires Vert.x safe sub-context — worker pool threads fail | 0.56 | 1 |

**Precision:** 7/8 relevant (88%), 3/8 highly relevant (38%)

#### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260604-96d82a | Reactive @Tool calling resolveChannel() blocks Vert.x I/O thread — @Blocking required | 0.79 | 2 |
| 2 | GE-20260529-ff186e | emitOn(Infrastructure.getDefaultWorkerPool()) — shift blocking I/O off Vert.x IO thread | 0.78 | 2 |
| 3 | GE-20260603-fdc6d5 | @Startup @ApplicationScoped prevents blocking @PostConstruct on Vert.x IO thread | 0.75 | 1 |
| 4 | GE-20260604-ed1b02 | quarkus-flow task executor threads use newCachedThreadPool() — blocking is safe | 0.74 | 1 |
| 5 | GE-20260429-68ee24 | @ConsumeEvent handler silently deadlocks if .join() called without blocking = true | 0.73 | 2 |
| 6 | GE-20260518-a61d1b | @ConsumeEvent(blocking=true) and @Transactional on same method work correctly | 0.73 | 2 |
| 7 | GE-20260501-56e179 | ThreadLocal set on calling thread is invisible inside CompletableFuture.supplyAsync() | 0.73 | 0 |
| 8 | GE-20260529-b994c2 | Uni.createFrom().item(supplier) with emitOn() — supplier still runs on subscription thread | 0.73 | 1 |

**Precision:** 7/8 relevant (88%), 4/8 highly relevant (50%)

#### Analysis

**Overlap:** 5 entries found by multiple methods:
- All three: GE-20260529-ff186e (score 2), GE-20260604-96d82a (score 2)
- grep + gardenSearch-KW: GE-20260519-4a42e6 (score 1)
- grep + gardenSearch-NL: GE-20260603-fdc6d5 (score 1)
- gardenSearch-KW + gardenSearch-NL: GE-20260604-ed1b02 (score 1)

**Unique to grep:** GE-20260518-e4fa52 (score 2 — RESTEasy Reactive .await() on
IO thread throws BlockingOperationNotAllowedException). Plus 11 score-0 entries
and 3 score-1 entries.

**Unique to gardenSearch (keywords):** GE-20260428-fd7a65 (score 2 —
@Transactional(SUPPORTS) makes JPA reads callable from Vert.x IO thread). Plus
GE-20260512-a9ad9f (score 1), GE-20260505-da346d (score 0),
GE-20260616-312ba1 (score 1).

**Unique to gardenSearch (NL):** GE-20260429-68ee24 (score 2 — @ConsumeEvent
handler deadlocks without blocking=true) and GE-20260518-a61d1b (score 2 —
@ConsumeEvent(blocking=true) + @Transactional works correctly). Plus
GE-20260501-56e179 (score 0), GE-20260529-b994c2 (score 1).

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: top-ranked entry (0.61) scored 2, but relevance scores are
  tightly clustered (0.56-0.61), making ranking less discriminating. The one
  score-0 entry (0.57) sits mid-range — machine relevance did not filter it.
- gardenSearch-NL: top two entries (0.79, 0.78) both scored 2, showing better
  calibration. The score-0 entry (0.73) is the second-lowest ranked. Higher
  spread (0.73-0.79) provides more ranking signal than keywords.

**Verdict:** Clear win for gardenSearch (NL).

- **Precision:** gardenSearch-NL (88% relevant, 50% highly relevant) and
  gardenSearch-KW (88% relevant, 38% highly relevant) both dominate grep (40%
  relevant, 15% highly relevant).
- **Discovery:** gardenSearch-NL surfaced 2 unique score-2 finds
  (GE-20260429-68ee24, GE-20260518-a61d1b) — both directly about the
  @ConsumeEvent + blocking pattern that IS the issue. gardenSearch-KW surfaced
  1 unique score-2 find. grep surfaced 1 unique score-2 find.
- **Noise:** grep returned 330 total matches (all 20 scored entries were
  `_summaries/` files), with 12/20 scoring 0. gardenSearch methods each had
  only 1 score-0 entry.
- **NL vs keywords (same retrieval):** the NL query found 4 score-2 entries vs
  3 for keywords, and its unique finds (the two @ConsumeEvent entries) were
  more directly actionable for this specific issue. The NL query's higher
  relevance spread also provided better ranking discrimination.

### Issue 2: [CDI / module wiring] — casehubio/work#264

**Summary:** `NoOpGroupMembershipProvider` is `@ApplicationScoped` (effectively
`@Default`) but should be `@DefaultBean` — when both it and platform's
`MockGroupMembershipProvider` (`@DefaultBean`) are on the classpath, Quarkus
throws `AmbiguousResolutionException`. Fix: add `@DefaultBean` to the no-op so
it participates in the CDI three-tier pattern.

**Keywords (work-start procedure):**
`DefaultBean|AmbiguousResolutionException|GroupMembershipProvider|ambiguous`
Rationale: the annotation fix (`DefaultBean`), the exception class
(`AmbiguousResolutionException`), the SPI type (`GroupMembershipProvider`), and
the problem concept (`ambiguous`).

**Natural language query:** `"CDI ambiguous dependency resolution when @Default
bean conflicts with @DefaultBean in multi-module Quarkus application"`

#### grep (keywords) — first 20 of 281 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260414-598352 | Angle brackets in BeautifulSoup get_text() output are unambiguous HTML-entity markers | summary | 0 |
| 2 | GE-20260523-86ed13 | casehub-engine requires casehub-platform and casehub-platform-expression on the classpath — @DefaultBean injection points fail | summary | 1 |
| 3 | GE-20260422-e48245 | @DefaultBean lives in io.quarkus.arc, not jakarta.enterprise.inject | summary | 1 |
| 4 | GE-20260423-bcb5b7 | quarkus-work-core registers both LeastLoadedStrategy and ClaimFirstStrategy — AmbiguousResolutionException | summary | 2 |
| 5 | GE-20260427-543663 | @Produces @DefaultBean for library-level overridable CDI defaults without @Alternative | summary | 1 |
| 6 | GE-20260513-4f26a7 | @DefaultBean + plain @ApplicationScoped enables CDI layer displacement | summary | 2 |
| 7 | GE-20260514-83ee13 | @DefaultBean in Quarkus is io.quarkus.arc.DefaultBean, not jakarta.enterprise.inject | summary | 1 |
| 8 | GE-20260515-99cf39 | Config-driven @Produces @DefaultBean for engine-internal strategy selection with consumer override | summary | 1 |
| 9 | GE-20260515-fd3156 | @DefaultBean on @Produces method makes the produced bean default — placing it on the class does not | summary | 1 |
| 10 | GE-20260517-9006f7 | @DefaultBean @ApplicationScoped blocking bridge for reactive SPI in @QuarkusTest | summary | 1 |
| 11 | GE-20260517-9e571a | @Typed required when CDI bean implements framework-owned interface to prevent AmbiguousResolutionException | summary | 1 |
| 12 | GE-20260517-a6d608 | DefaultBean @ApplicationScoped + MicroProfile Config @ConfigProperty enables zero-config SPI | summary | 1 |
| 13 | GE-20260528-f0a75c | @DefaultBean BlockingToReactiveBridge — wrap any blocking SPI as reactive | summary | 1 |
| 14 | GE-20260531-9118e7 | runtime Maven scope on shared mock library silently expands CDI scan, breaking @DefaultBean suppression | summary | 2 |
| 15 | GE-20260601-0eb1b6 | Quarkus augmentation cache masks @DefaultBean CDI conflicts — only surfaces on clean builds | summary | 1 |
| 16 | GE-20260601-3dbc80 | quarkus.arc.exclude-types resolves @DefaultBean ambiguity between two Jandex-indexed JARs | summary | 2 |
| 17 | GE-20260601-7a3b38 | @DefaultBean implementations invisible to CDI when their supertype JAR has no Jandex index | summary | 1 |
| 18 | GE-20260601-81be07 | Resolve CDI ambiguity between two competing @DefaultBean implementations by introducing a concrete non-default bean | summary | 2 |
| 19 | GE-20260601-fcf0d9 | Two @DefaultBean beans for the same type → Quarkus AmbiguousResolutionException, not Unsatisfied | summary | 2 |
| 20 | GE-20260604-81a6a6 | @DefaultBean @Unremovable required when injection point lives in a different Maven module | summary | 1 |

**Precision:** 19/20 relevant (95%), 6/20 highly relevant (30%)
**Note:** All 20 results are `_summaries/` files. 281 total matches reflect
the keyword surface — `DefaultBean` alone matches hundreds of entries across
the garden's extensive CDI coverage. Only 1 result scored 0 (a BeautifulSoup
entry matching "unambiguous").

#### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260529-04a5a5 | WireMock 3.x wireMockServer.verify() takes RequestPatternBuilder not MappingBuilder | 0.63 | 0 |
| 2 | GE-20260618-8526c8 | ChatModel.doChat(ChatRequest) is the override point for test doubles | 0.59 | 0 |
| 3 | GE-20260530-e14065 | U+061C (Arabic Letter Mark) is a BiDi control character outside C0/C1 ranges | 0.58 | 0 |
| 4 | GE-20260528-936918 | Response.Status.UNPROCESSABLE_ENTITY doesn't exist in Jakarta EE 9 JAX-RS | 0.58 | 0 |
| 5 | GE-20260605-73c9d6 | CommitmentState.DECLINED not CANCELLED for DECLINE speech-act outcome | 0.58 | 0 |
| 6 | GE-20260601-81be07 | Resolve CDI ambiguity between two competing @DefaultBean implementations | 0.58 | 2 |
| 7 | GE-0003 | Use a second Claude to verify the first Claude's work | 0.57 | 0 |
| 8 | GE-20260505-b04e30 | AssertJ: assertThat(() -> voidMethod()) won't compile — use assertThatCode() | 0.57 | 0 |

**Precision:** 1/8 relevant (13%), 1/8 highly relevant (13%)

#### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260424-59906a | Quarkus CDI does not scan @ApplicationScoped beans in plain JAR module dependencies | 0.77 | 1 |
| 2 | GE-20260601-fcf0d9 | Two @DefaultBean beans for the same type → Quarkus AmbiguousResolutionException | 0.77 | 2 |
| 3 | GE-20260522-adb5cd | Moving @ApplicationScoped bean from Quarkus app module to library JAR silently breaks CDI discovery | 0.76 | 1 |
| 4 | GE-20260422-ebb91d | CDI AmbiguousResolutionException when multiple @ApplicationScoped beans implement same SPI interface | 0.76 | 2 |
| 5 | GE-20260415-884e48 | @Alternative @Priority(n) in CDI 4.0/Quarkus globally activates the alternative — causes AmbiguousResolutionException | 0.74 | 1 |
| 6 | GE-20260519-244ad2 | Gate optional beans in a Quarkus extension with ExcludedTypeBuildItem + @ConfigRoot(BUILD_TIME) | 0.74 | 1 |
| 7 | GE-20260601-81be07 | Resolve CDI ambiguity between two competing @DefaultBean implementations by introducing a concrete non-default bean | 0.73 | 2 |
| 8 | GE-20260623-c651a1 | Removing @Alternative from one bean in a multi-implementation CDI chain silently changes resolution | 0.73 | 1 |

**Precision:** 8/8 relevant (100%), 3/8 highly relevant (38%)

#### Analysis

**Overlap:** 2 entries found by multiple methods:
- All three: GE-20260601-81be07 (score 2 — resolve CDI ambiguity between two
  @DefaultBean implementations by introducing a concrete non-default bean)
- grep + gardenSearch-NL: GE-20260601-fcf0d9 (score 2 — two @DefaultBean
  beans for the same type → AmbiguousResolutionException)

**Unique to grep:** 4 score-2 entries: GE-20260423-bcb5b7
(AmbiguousResolutionException from two @ApplicationScoped beans implementing
same SPI), GE-20260513-4f26a7 (@DefaultBean + plain @ApplicationScoped enables
CDI layer displacement), GE-20260531-9118e7 (runtime Maven scope breaking
@DefaultBean suppression), GE-20260601-3dbc80 (exclude-types resolves
@DefaultBean ambiguity). Plus 13 score-1 entries. Only 1 score-0 entry.

**Unique to gardenSearch (keywords):** 0 relevant results. All 7 unique entries
scored 0 — completely unrelated to CDI wiring.

**Unique to gardenSearch (NL):** 1 score-2 entry: GE-20260422-ebb91d (CDI
AmbiguousResolutionException from multiple @ApplicationScoped beans — includes
a @DefaultBean variant update section directly matching the issue). Plus 5
score-1 entries covering CDI scanning, @Alternative @Priority, extension bean
gating, and CDI chain resolution.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.57-0.63) are tightly clustered but map
  almost entirely to score-0 entries. The single relevant result (0.58) sits
  mid-range. Keywords were semantically misleading — embedding similarity saw
  "AmbiguousResolutionException" as a generic Java exception term, not a CDI
  concept. This is a catastrophic keyword-embedding mismatch.
- gardenSearch-NL: top two entries (0.77, 0.77) include one score-2 and one
  score-1, showing reasonable calibration. All 8 entries scored >= 1, with a
  tighter relevance range (0.73-0.77). The NL query's natural language framing
  ("CDI ambiguous dependency resolution") mapped cleanly to garden entries
  about CDI resolution patterns.

**Verdict:** Clear win for grep.

- **Precision:** grep (95% relevant, 30% highly relevant) dominates
  gardenSearch-NL (100% relevant, 38% highly relevant) in absolute score-2
  count (6 vs 3) despite both having strong relevant precision. gardenSearch-KW
  (13% relevant, 13% highly relevant) was essentially a failure.
- **Discovery:** grep surfaced 4 unique score-2 finds — all directly about
  @DefaultBean ambiguity patterns and resolution techniques. gardenSearch-NL
  surfaced 1 unique score-2 find. gardenSearch-KW surfaced 0.
- **Noise:** grep returned 281 total matches but the first 20 were
  exceptionally well-targeted — 19/20 relevant, with only a BeautifulSoup
  false-positive on "unambiguous". The CDI keyword set (`DefaultBean`,
  `AmbiguousResolutionException`) is highly specific to the problem domain and
  produced low-noise results.
- **NL vs keywords (same retrieval):** gardenSearch-NL massively outperformed
  gardenSearch-KW. The keyword query was catastrophic — embedding similarity
  treated the annotation/exception names as generic tokens rather than CDI
  concepts. The NL query's semantic framing ("CDI ambiguous dependency
  resolution") correctly activated the embedding model's understanding of the
  problem domain.
- **Why grep won:** this issue involves highly specific, searchable CDI
  annotation names (`@DefaultBean`, `AmbiguousResolutionException`) that
  appear verbatim in garden entries. grep's substring matching is strongest
  when the search terms are distinctive technical vocabulary that appears
  in relevant entries and rarely in irrelevant ones. The garden has
  extensive CDI coverage — grep found a deep cluster of directly relevant
  entries that embedding search could not rank into 8 results.

### Issue 3: [Persistence / migrations] — casehubio/ledger#131

**Summary:** `LedgerEntry.tenancyId` field added to the base class shadows the
same-named field already declared on three JOINED-inheritance subclasses
(`CaseLedgerEntry`, `WorkerDecisionEntry`, `MergeDecisionLedgerEntry`). Java
field shadowing + Hibernate bytecode enhancement makes the two fields invisible
to Java code, but `em.persist()` reads the base field via reflection — it is
null, violating the `NOT NULL` constraint. Fix: remove the shadowing field from
subclasses.

**Keywords (work-start procedure):**
`tenancyId|JOINED|shadowing|LedgerEntry`
Rationale: the specific field (`tenancyId`), the JPA inheritance strategy
(`JOINED`), the Java language concept (`shadowing`), and the entity class
(`LedgerEntry`).

**Natural language query:** `"JPA JOINED inheritance field shadowing — subclass
field shadows base class column causing Hibernate persist failure with NOT NULL
constraint violation"`

#### grep (keywords) — first 20 of 246 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260511-b6f903 | casehub-ledger LedgerEntry subclass: required caller-set fields for LedgerEntryRepository.save() | summary | 1 |
| 2 | GE-20260531-1587fe | JpaLedgerMerkleFrontierRepository must be added to selected-alternatives for LedgerVerificationService | summary | 0 |
| 3 | GE-20260531-d2ed26 | LedgerEntryRepository.save() triggers full Merkle chain update — concurrent writes violate UQ constraint | summary | 0 |
| 4 | GE-20260612-17c161 | casehub-ledger LedgerProcessor build step blocks em.persist() on LedgerEntry subclasses | summary | 1 |
| 5 | GE-20260612-de141c | casehub-ledger LedgerProcessor requires domainContentBytes() on all LedgerEntry subclasses | summary | 1 |
| 6 | GE-20260420-86180e | em.merge() + em.remove() on JOINED inheritance entity throws OptimisticLockException | summary | 1 |
| 7 | GE-20260501-11ce7f | MessageLedgerEntry.content is null for EVENT entries — LIKE search returns nothing | summary | 0 |
| 8 | GE-20260501-b12416 | MessageLedgerEntry.sequenceNumber is per-channel, not global — wrong ORDER BY | summary | 0 |
| 9 | GE-20260526-a5bbd2 | LedgerEntry.attach() sets supplement.ledgerEntry = this — bidirectional back-reference | summary | 0 |
| 10 | GE-20260530-da427e | Quarkus multi-PU sub-package matching assigns LedgerEntry subclass to wrong persistence unit | summary | 1 |
| 11 | GE-20260601-a35fb3 | InMemoryCaseInstanceRepository.findByUuid() silently returns null when stored tenancyId is null | summary | 0 |
| 12 | GE-20260605-5d0034 | JPA JOINED inheritance subclass with its own repository bypasses base-class save() invariants | summary | 2 |
| 13 | GE-20260607-1c0a05 | JPA subclass JPQL query in LedgerEntryRepository silently hides domain entries | summary | 1 |
| 14 | GE-20260607-d689c9 | Class shadowing in Quarkus extensions is unreliable — use BytecodeTransformerBuildItem | summary | 0 |
| 15 | GE-20260609-58e5d9 | JPA JOINED inheritance: child entity redeclaring parent field shadows the parent mapping | summary | 2 |
| 16 | GE-20260609-77a6f9 | casehub-work SNAPSHOT @RequestScoped CurrentPrincipal displaces test mock — null tenancyId | summary | 0 |
| 17 | GE-20260609-dac1a3 | @CacheResult cache key omits tenancyId — cached data leaks across tenants | summary | 0 |
| 18 | GE-20260612-1f4ed8 | JPA JOINED inheritance field shadowing + Hibernate bytecode enhancement: NOT NULL fails | summary | 2 |
| 19 | GE-20260616-240c04 | ReactiveMessageLedgerEntryRepository queries LedgerEntry.subjectId not channelId | summary | 1 |
| 20 | GE-20260618-3e5f2d | ErasureReceiptLedgerEntry entity name collision — foundation and application both define entity | summary | 1

**Precision:** 11/20 relevant (55%), 3/20 highly relevant (15%)
**Note:** All 20 results are `_summaries/` files. 246 total matches reflect the
keyword surface — `tenancyId` and `LedgerEntry` each match many ledger-domain
entries regardless of inheritance context. `shadowing` pulled in a class
shadowing entry (GE-20260607-d689c9) that is about Quarkus classloader
shadowing, not Java field shadowing.

#### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260607-d689c9 | Class shadowing in Quarkus extensions is unreliable — use BytecodeTransformerBuildItem | 0.70 | 0 |
| 2 | GE-20260605-8b9118 | Jackson readValue() silently accepts trailing content unless FAIL_ON_TRAILING_TOKENS | 0.55 | 0 |
| 3 | GE-20260512-a9ad9f | Raw ExecutorService drops CDI context — @Transactional silently broken | 0.54 | 0 |
| 4 | GE-20260529-ff186e | emitOn(Infrastructure.getDefaultWorkerPool()) — shift blocking I/O off Vert.x IO thread | 0.53 | 0 |
| 5 | GE-20260617-cc0834 | Shadow DOM keyboard event target is the host element — global shortcut guards miss inner fields | 0.53 | 0 |
| 6 | GE-20260530-4387cb | casehub-qhorus MessageService Panache static calls bypass InMemory store | 0.53 | 0 |
| 7 | GE-20260529-0c80ca | LangChain4j StreamingChatModel: mock doChat() not chat() | 0.53 | 0 |
| 8 | GE-20260618-8526c8 | ChatModel.doChat(ChatRequest) is the override point for test doubles | 0.53 | 0 |

**Precision:** 0/8 relevant (0%), 0/8 highly relevant (0%)

#### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260612-1f4ed8 | JPA JOINED inheritance field shadowing + Hibernate bytecode enhancement: NOT NULL fails | 0.80 | 2 |
| 2 | GE-20260609-58e5d9 | JPA JOINED inheritance: child entity redeclaring parent field shadows parent mapping | 0.74 | 2 |
| 3 | GE-20260605-5d0034 | JPA JOINED inheritance subclass with its own repository bypasses base-class save() invariants | 0.73 | 2 |
| 4 | GE-20260607-d689c9 | Class shadowing in Quarkus extensions is unreliable — use BytecodeTransformerBuildItem | 0.72 | 0 |
| 5 | GE-20260616-8b9da9 | Quarkus service classes don't inherit Logger — each class needs its own static Logger field | 0.68 | 0 |
| 6 | GE-20260429-d20380 | JPA JOINED inheritance: filtering by subclass column vs inherited column — only inherited uses index | 0.68 | 1 |
| 7 | GE-0143 | final hashCode() in parent — update the protected field from setters instead of overriding | 0.68 | 0 |
| 8 | GE-20260611-a42c0b | Quarkus JPA subclass @Inject EntityManager omits parent qualifier — wrong PU in multi-datasource | 0.67 | 1 |

**Precision:** 5/8 relevant (63%), 3/8 highly relevant (38%)

#### Analysis

**Overlap:** 3 entries found by multiple methods:
- grep + gardenSearch-NL: GE-20260612-1f4ed8 (score 2 — the exact field
  shadowing + bytecode enhancement entry), GE-20260609-58e5d9 (score 2 — child
  entity redeclaring parent field), GE-20260605-5d0034 (score 2 — JOINED
  inheritance subclass bypassing base-class save invariants)
- grep + gardenSearch-KW: GE-20260607-d689c9 (score 0 — class shadowing, wrong
  type of shadowing)

**Unique to grep:** 0 score-2 entries. 7 score-1 entries: GE-20260511-b6f903
(LedgerEntry subclass fields), GE-20260612-17c161 (LedgerProcessor blocks
em.persist), GE-20260612-de141c (LedgerProcessor domainContentBytes),
GE-20260420-86180e (em.merge/remove JOINED inheritance), GE-20260530-da427e
(multi-PU subclass assignment), GE-20260607-1c0a05 (JPQL hides entries),
GE-20260616-240c04 (wrong query field), GE-20260618-3e5f2d (entity name
collision). Plus 9 score-0 entries.

**Unique to gardenSearch (keywords):** 0 relevant results. All 7 unique entries
scored 0 — entirely unrelated to JPA, persistence, or inheritance. The keyword
query was a complete failure.

**Unique to gardenSearch (NL):** 0 score-2 entries. 2 score-1 entries:
GE-20260429-d20380 (JOINED inheritance column filtering — index behavior for
subclass vs inherited columns) and GE-20260611-a42c0b (JPA subclass
EntityManager qualifier — wrong PU). Plus 3 score-0 entries.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: the top result (0.70, class shadowing) scored 0 — the
  embedding treated "shadowing" as the dominant signal but matched the wrong
  kind (classloader shadowing, not field shadowing). Remaining results
  (0.53-0.55) are tightly clustered noise. The keyword combination was
  catastrophically misleading — "tenancyId" and "LedgerEntry" are
  domain-specific identifiers that have no semantic meaning to the embedding
  model, while "shadowing" matched a web/classloader concept instead of JPA
  field shadowing.
- gardenSearch-NL: the top result (0.80) scored 2 — strong calibration. The
  second result (0.74) also scored 2. The class shadowing false positive (0.72)
  sits correctly below the two directly relevant entries. The spread (0.67-0.80)
  provides meaningful ranking discrimination, with the three score-2 entries
  occupying the top three positions.

**Verdict:** Advantage gardenSearch (NL).

- **Precision:** gardenSearch-NL (63% relevant, 38% highly relevant) outperforms
  grep (55% relevant, 15% highly relevant) in both metrics. gardenSearch-KW (0%
  relevant) was a total failure.
- **Discovery:** all three score-2 entries were found by both grep and
  gardenSearch-NL — no unique score-2 finds for either method. gardenSearch-NL
  surfaced 2 unique score-1 finds (both about JOINED inheritance column
  behavior). grep surfaced 7 unique score-1 finds (broader LedgerEntry domain
  context).
- **Noise:** grep returned 246 total matches with 9/20 score-0 entries (45%
  noise). The keywords `tenancyId` and `LedgerEntry` are highly specific to the
  casehub ledger domain — they surface many ledger-related entries but most are
  about ledger operations, not about JOINED inheritance or field shadowing.
  `shadowing` matched a class-shadowing entry that is entirely unrelated.
- **NL vs keywords (same retrieval):** gardenSearch-NL massively outperformed
  gardenSearch-KW. The keyword query was a catastrophic failure — 0/8 relevant.
  The embedding model could not connect domain-specific identifiers
  (`tenancyId`, `LedgerEntry`) and a polysemous term (`shadowing`) to the JPA
  inheritance field shadowing concept. The NL query's explicit framing ("JPA
  JOINED inheritance field shadowing — subclass field shadows base class
  column") gave the embedding model enough semantic context to locate the exact
  entries.
- **Why gardenSearch-NL won on precision but not discovery:** gardenSearch-NL
  ranked the three most relevant entries in positions 1-3 (all score 2) —
  perfect top-of-list precision. grep found the same three entries but buried
  them at positions 12, 15, and 18 among 20 results, diluting precision. Both
  methods found the same highly relevant entries, but gardenSearch-NL presented
  them first while grep required wading through noise to find them.

### Issue 4: [REST / messaging] — casehubio/platform#98

**Summary:** Spec-grade issue defining the CloudEvent foundation — `io.cloudevents.CloudEvent`
as the typed CDI event type, `StreamContext` SPI for tenancy propagation, and five
classpath-activated stream modules (Kafka, AMQP, webhook, poll, Camel) that receive
external transport messages and fire `Event<CloudEvent>.fireAsync()` on the CDI bus.

**Keywords (work-start procedure):**
`CloudEvent|stream|fireAsync|StreamContext`
Rationale: the core event type (`CloudEvent`), the module concept (`stream`), the CDI
firing mechanism (`fireAsync`), and the tenancy SPI (`StreamContext`).

**Natural language query:** `"CloudEvent CDI event bus with fireAsync — stream modules
for Kafka AMQP webhook that receive external transport messages and fire typed CDI
events with tenancy propagation"`

#### grep (keywords) — first 20 of 614 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260416-7ec461 | Maven `-am -Dtest=ClassName` propagates test filter to all upstream modules | summary | 0 |
| 2 | GE-20260420-b9c06c | java.util.zip.ZipInputStream rejects BZip2-compressed ZIP entries | summary | 0 |
| 3 | GE-20260421-473024 | Rebasing a branch onto upstream silently breaks downstream interface implementors | summary | 0 |
| 4 | GE-20260423-daef97 | CDI event.fire() does not deliver to @ObservesAsync observers — fireAsync() required separately | summary | 2 |
| 5 | GE-20260429-21e6cf | Quarkus JPA entity in dependency forces datasource config on ALL downstream consumers | summary | 0 |
| 6 | GE-20260430-3275b1 | GitHub Packages SNAPSHOT CI timing race | summary | 0 |
| 7 | GE-20260504-5b9269 | Nested Collectors.groupingBy produces O(M) multi-key grouping without re-streaming | summary | 0 |
| 8 | GE-20260505-43a73b | Mockito `thenReturn(stream)` exhausts CDI Instance<T> mock on second providerFor() call | summary | 0 |
| 9 | GE-20260512-0fe012 | CDI fireAsync() inside @Transactional dispatches immediately — observer runs before commit | summary | 1 |
| 10 | GE-20260512-a09bd3 | Enforce blocking/reactive SPI method parity with a reflection test | summary | 0 |
| 11 | GE-20260517-0823c8 | Cross-repo TDD: mvn install required between repos | summary | 0 |
| 12 | GE-20260517-11dd6b | IllegalArgumentException catch around UUID.fromString swallows unrelated errors | summary | 0 |
| 13 | GE-20260517-f31786 | `event.fireAsync()` returns `CompletionStage<Event<T>>` not `CompletionStage<Void>` | summary | 1 |
| 14 | GE-20260522-2a4009 | onTermination() on inner Multi in Mutiny concatenation does not fire when cancelled | summary | 0 |
| 15 | GE-20260522-a575c3 | Range-bounded modal algorithm for empirical calibration of simulation timing | summary | 0 |
| 16 | GE-20260527-714661 | PostgreSQL LISTEN/NOTIFY @QuarkusTest: subscribe to unfiltered stream to confirm channel | summary | 0 |
| 17 | GE-20260527-8c3ff5 | Merging two transformToUniAndConcatenate Mutiny streams — duplicate SSE frames | summary | 0 |
| 18 | GE-20260527-cad5ba | Place fireAsync() before internal dispatch to decouple delivery paths | summary | 1 |
| 19 | GE-20260529-0c80ca | LangChain4j StreamingChatModel: mock doChat() not chat() | summary | 0 |
| 20 | GE-20260529-e43076 | Await CDI fireAsync() delivery in @ConsumeEvent reactive chain | summary | 1 |

**Precision:** 5/20 relevant (25%), 1/20 highly relevant (5%)
**Note:** All 20 results are `_summaries/` files. 614 total matches reflect the
extremely broad keyword `stream` — which matches Java streams, Mutiny streams,
SSE streams, PostgreSQL notification streams, and LLM streaming in addition to
the intended messaging stream modules. Only 4 of 20 results touched CDI
`fireAsync()` at all; the rest matched `stream` in unrelated contexts.

#### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260602-c38360 | PanacheQuery.stream() does not exist in Quarkus Hibernate Reactive Panache | 0.66 | 0 |
| 2 | GE-20260527-714661 | PostgreSQL LISTEN/NOTIFY @QuarkusTest: subscribe to unfiltered stream | 0.62 | 0 |
| 3 | GE-20260522-2a4009 | onTermination() on inner Multi in Mutiny concatenation does not fire | 0.61 | 0 |
| 4 | GE-20260615-d008ea | EndpointPropertyKeys.URL does not apply to KAFKA — only HTTP, GRPC, MCP, CAMEL, QHORUS | 0.60 | 2 |
| 5 | GE-20260505-43a73b | Mockito `thenReturn(stream)` exhausts CDI Instance<T> mock | 0.60 | 0 |
| 6 | GE-20260527-8c3ff5 | Merging two transformToUniAndConcatenate Mutiny streams — duplicate SSE frames | 0.59 | 0 |
| 7 | GE-20260504-5b9269 | Nested Collectors.groupingBy produces O(M) multi-key grouping | 0.58 | 0 |
| 8 | GE-20260602-286f16 | Mutiny Infrastructure.getDefaultBlockingExecutor() does not exist | 0.58 | 0 |

**Precision:** 1/8 relevant (13%), 1/8 highly relevant (13%)

#### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260505-8c57c2 | CDI events as a bridge for circular Maven module dependencies — fire from lower module, observe in upper | 0.67 | 2 |
| 2 | GE-20260615-00ff7a | Fire CDI Event<T> from registry writes to decouple provisioners from transport implementations | 0.65 | 2 |
| 3 | GE-20260527-cad5ba | Place fireAsync() before internal dispatch to decouple delivery paths in @Transactional methods | 0.65 | 1 |
| 4 | GE-20260609-ddd4b8 | CaseHub.signal() is async (Vert.x event bus) — not a synchronous blackboard update | 0.63 | 0 |
| 5 | GE-20260531-e1ce47 | CDI @Observes and @ObservesAsync are separate delivery channels — @Observes never receives fireAsync() events | 0.63 | 2 |
| 6 | GE-20260414-8c43a9 | ConcurrentHashMap<ID, CompletableFuture> registry for suspending workflows pending external events | 0.63 | 0 |
| 7 | GE-20260529-d1397c | Observing ChannelInitialisedEvent gives Qhorus ChannelBackend free startup recovery | 0.63 | 1 |
| 8 | GE-20260613-a5983e | @Transactional on a void JAX-RS SSE method commits when the method body returns | 0.62 | 0 |

**Precision:** 5/8 relevant (63%), 3/8 highly relevant (38%)

#### Analysis

**Overlap:** 1 entry found by multiple methods:
- grep + gardenSearch-NL: GE-20260527-cad5ba (score 1 — fireAsync() ordering
  in @Transactional methods)

**Unique to grep:** GE-20260423-daef97 (score 2 — CDI event.fire() does not
deliver to @ObservesAsync, fireAsync() required). Plus GE-20260512-0fe012
(score 1), GE-20260517-f31786 (score 1), GE-20260529-e43076 (score 1). Plus
15 score-0 entries.

**Unique to gardenSearch (keywords):** GE-20260615-d008ea (score 2 —
EndpointPropertyKeys.URL does not apply to KAFKA stream endpoints). Plus 7
score-0 entries.

**Unique to gardenSearch (NL):** 3 score-2 entries: GE-20260505-8c57c2 (CDI
events as cross-module bridge — the exact architectural pattern the stream
modules use), GE-20260615-00ff7a (fire CDI Event<T> from EndpointRegistry to
decouple provisioners from stream transport implementations — directly
describes the Camel/Kafka stream module wiring), GE-20260531-e1ce47 (CDI
@Observes vs @ObservesAsync channel separation — critical for understanding
`Event<CloudEvent>.fireAsync()` delivery). Plus GE-20260529-d1397c (score 1).
Plus 3 score-0 entries.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.58-0.66) are moderately spread but
  poorly calibrated. The top result (0.66, PanacheQuery.stream()) scored 0.
  The one relevant result (0.60, EndpointPropertyKeys/KAFKA) sits mid-range.
  The keyword query suffered from `stream` being polysemous — the embedding
  model treated it as the dominant signal and matched Java streams, Mutiny
  streams, and SSE streams. `CloudEvent` and `StreamContext` are domain-specific
  terms that have no semantic weight in the embedding model's vocabulary.
- gardenSearch-NL: the top two entries (0.67, 0.65) both scored 2, showing
  strong calibration. The three score-0 entries (0.63, 0.63, 0.62) cluster at
  the bottom. The spread (0.62-0.67) is narrow but the ranking correctly placed
  the most relevant entries at the top. The NL query's explicit framing of the
  architectural pattern ("receive external transport messages and fire typed CDI
  events") mapped to entries describing exactly that pattern.

**Verdict:** Clear win for gardenSearch (NL).

- **Precision:** gardenSearch-NL (63% relevant, 38% highly relevant) dominates
  both grep (25% relevant, 5% highly relevant) and gardenSearch-KW (13%
  relevant, 13% highly relevant).
- **Discovery:** gardenSearch-NL surfaced 3 unique score-2 finds — all directly
  about the CDI event bus pattern for decoupling transport modules from
  consumers. grep surfaced 1 unique score-2 find (CDI fire vs fireAsync
  channel separation). gardenSearch-KW surfaced 1 unique score-2 find
  (EndpointPropertyKeys.URL/KAFKA gotcha).
- **Noise:** grep returned 614 total matches — by far the highest grep count
  in the benchmark. The keyword `stream` is catastrophically polysemous:
  it matches Java streams (Collectors, Stream API), Mutiny Multi streams,
  SSE streams, PostgreSQL notification streams, and LLM streaming. Only 5 of
  20 scored results were relevant. gardenSearch-KW had the same polysemy
  problem with 7/8 results scoring 0.
- **NL vs keywords (same retrieval):** gardenSearch-NL (63% relevant, 38%
  highly relevant) massively outperformed gardenSearch-KW (13% relevant, 13%
  highly relevant). The keyword query was undermined by the same polysemy
  that hurt grep — `stream` dominated the embedding and pulled in unrelated
  results. The NL query's explicit description of the architectural intent
  ("receive external transport messages and fire typed CDI events with
  tenancy propagation") gave the embedding model enough context to locate
  entries about CDI event-driven decoupling patterns rather than generic
  stream operations.
- **Why gardenSearch-NL won:** this issue is about an architectural pattern
  (CDI event bus for transport module decoupling), not about specific API
  names. The garden has multiple entries about this exact pattern —
  Event<T>.fire()/fireAsync() for cross-module communication, EndpointRegistry
  event-driven stream route configuration, @Observes/@ObservesAsync channel
  separation — but they use varied vocabulary. Embedding similarity on a
  natural language description of the pattern found these entries; substring
  matching on `stream` found everything except what was needed.

### Issue 5: [AI / LLM / inference] — casehubio/platform#100

**Summary:** New `ChatModel` implementation backed by `AgentSession` (Claude SDK
subprocess) instead of `quarkus-langchain4j-anthropic` HTTP API. The adapter maps
LangChain4j's `ChatModel.chat(ChatRequest)` to `AgentSession.query()` calls on a
persistent subprocess, gaining native Claude prompt caching (70-80% token cost
reduction). Session lifecycle maps to CDI scoped beans; `@Alternative @Priority`
activates the adapter when on the classpath.

**Keywords (work-start procedure):**
`ChatModel|AgentSession|prompt.cach|LangChain4j`
Rationale: the LangChain4j interface being adapted (`ChatModel`), the backing
implementation (`AgentSession`), the optimization being targeted (`prompt cach` —
regex to match "prompt caching" and "prompt cache"), and the framework (`LangChain4j`).

**Natural language query:** `"ChatModel adapter bridging LangChain4j interface to
Claude AgentSession for native prompt caching — LLM provider integration with token
cost optimization"`

#### grep (keywords) — first 20 of 160 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260423-878486 | quarkus-langchain4j-jlama fails at test bootstrap with 'Unsupported value type: [ALL-UNNAMED]' | summary | 0 |
| 2 | GE-20260522-3e2589 | LangChain4j ChatModel cannot be stubbed as a lambda — override doChat(ChatRequest) not chat(ChatRequest) | summary | 2 |
| 3 | GE-20260525-80e370 | LangChain4j 1.x UserMessage: text accessor is singleText(), not text() | summary | 1 |
| 4 | GE-20260525-a8bd9a | quarkus-langchain4j AiServicesProcessor throws 'Duplicate key null' when -parameters javac flag missing | summary | 0 |
| 5 | GE-20260525-fd4868 | LangChain4j 1.x: ChatLanguageModel renamed to ChatModel; chat() replaced by doChat() as extension point | summary | 2 |
| 6 | GE-20260528-e9564b | LangChain4j Anthropic: ResponseFormat.JSON without schema throws UnsupportedFeatureException | summary | 2 |
| 7 | GE-20260529-0b8284 | Uni.createFrom().emitter() wrong for one-shot callback bridging — use completionStage() | summary | 1 |
| 8 | GE-20260529-0c80ca | LangChain4j StreamingChatModel: mock doChat() not chat() | summary | 2 |
| 9 | GE-20260531-bd4b53 | quarkus-langchain4j-anthropic 0.26.1 fails with 'Run time configuration cannot be consumed in Build Steps' | summary | 1 |
| 10 | GE-20260602-f2ca07 | Pass rendered.content() not the eval case to a blind judge — prevents descriptor leaking | summary | 0 |
| 11 | GE-20260603-301b80 | langchain4j-agentic DeclarativeUtil has a single CDI resolver hook for @ChatModelSupplier only | summary | 2 |
| 12 | GE-20260604-5bb2e7 | CircuitBreakerOpenException escapes AgentInvocationException wrapper in langchain4j agents | summary | 0 |
| 13 | GE-20260605-e4d5c3 | Standalone agentic agent invocation skips RAG pipeline — ContentRetriever.retrieve() never called | summary | 0 |
| 14 | GE-20260606-096df3 | LangChain4j EmbeddingSearchRequest.filter() pushes domain/metadata filters into Qdrant pre-scoring | summary | 0 |
| 15 | GE-20260606-cd1c61 | MCP ToolProvider CDI bean collides with user ToolProvider — auto-wiring causes AmbiguousResolutionException | summary | 1 |
| 16 | GE-20260608-4c8108 | AnnotationsImpliesAiServiceBuildItem transitively enables guardrails on agent interfaces | summary | 0 |
| 17 | GE-20260608-ff7e97 | langchain4j OutputGuardrails maxRetries counts total attempts, not retries-after-first | summary | 0 |
| 18 | GE-20260609-2a92d9 | langchain4j-document-parser-apache-tika artifact doesn't exist for LangChain4j 1.14.1 | summary | 0 |
| 19 | GE-20260609-49e48c | SupervisorAgentService bypasses WorkflowAgentsBuilder SPI | summary | 0 |
| 20 | GE-20260610-99d39c | jlama-core 0.8.4 PanamaTensorOperations.batchDotProduct throws UnsupportedOperationException: ARM_128 | summary | 0 |

**Precision:** 9/20 relevant (45%), 5/20 highly relevant (25%)
**Note:** All 20 results are `_summaries/` files. 160 total matches are moderate —
`ChatModel` and `LangChain4j` are specific enough to target the right domain, but
still pull in many general LangChain4j gotchas (jlama, tika, guardrails, RAG)
that have no relevance to building a ChatModel adapter.

#### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260609-dac1a3 | @CacheResult cache key omits tenancyId — cached data leaks across tenants | 0.64 | 0 |
| 2 | GE-20260530-4387cb | casehub-qhorus MessageService Panache static calls bypass InMemory store | 0.63 | 0 |
| 3 | GE-20260530-b68c00 | Maven caches GitHub Packages 401 failure in *.lastUpdated markers | 0.62 | 0 |
| 4 | GE-0087 | Assert that iframe.src changes after a save, not that the iframe content changed | 0.62 | 0 |
| 5 | GE-20260528-e9ed9f | LLM renderer cache key must hash all output-affecting context, not just LLM input fields | 0.61 | 0 |
| 6 | GE-20260613-53e590 | TEMPLATE_HASH covers only PROMPT_TEMPLATE — RESPONSE_FORMAT schema descriptions not cache-invalidating | 0.61 | 0 |
| 7 | GE-0084 | Headless Playwright does not cache iframe responses the way real browsers do | 0.61 | 0 |
| 8 | GE-0148 | Quarkus JAX-RS resource without @ApplicationScoped silently breaks instance-level caches | 0.60 | 0 |

**Precision:** 0/8 relevant (0%), 0/8 highly relevant (0%)

#### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260615-83f6cb | Default interface method as bridge consolidator — combine two-step activation checks | 0.61 | 0 |
| 2 | GE-20260422-0ed3e5 | CDI container wiring vs service-loader wiring in large JVM frameworks | 0.61 | 1 |
| 3 | GE-20260525-5cf881 | OpenClaw pluggable context engine — kind:context-engine delegates full context assembly | 0.60 | 0 |
| 4 | GE-20260606-fc0556 | GraalVM tracing agent as sole discovery path for ONNX Runtime + DJL native-image config | 0.59 | 0 |
| 5 | GE-20260601-08a351 | quarkus-oidc-client on the classpath triggers Keycloak DevServices even when unused | 0.59 | 0 |
| 6 | GE-20260614-b94048 | SPLADE cocondenser-ensembledistil is CC NonCommercial — permissive alternative | 0.59 | 0 |
| 7 | GE-0078 | Quarkus WebAuthn Actual HTTP Endpoint Paths (Only Discoverable via Bytecode) | 0.59 | 0 |
| 8 | GE-20260613-1e5ba4 | LangChain4j EmbeddingModel does not implement TokenCountEstimator | 0.59 | 1 |

**Precision:** 2/8 relevant (25%), 0/8 highly relevant (0%)

#### Analysis

**Overlap:** 0 entries found by multiple methods.

**Unique to grep:** 5 score-2 entries: GE-20260522-3e2589 (ChatModel cannot be
stubbed as a lambda — must override doChat()), GE-20260525-fd4868
(ChatLanguageModel renamed to ChatModel; chat() replaced by doChat()),
GE-20260528-e9564b (LangChain4j Anthropic ResponseFormat.JSON without schema
throws UnsupportedFeatureException), GE-20260529-0c80ca (StreamingChatModel mock
doChat() not chat()), GE-20260603-301b80 (@ChatModelSupplier CDI resolver hook —
the only supplier type with framework integration). Plus 4 score-1 entries and
11 score-0 entries.

**Unique to gardenSearch (keywords):** 0 relevant results. All 8 entries scored 0 —
entirely unrelated to ChatModel, LangChain4j, or LLM provider integration. The
embedding model interpreted "prompt caching" as generic caching and "ChatModel"
/"AgentSession" as abstract terms, returning entries about cache keys, Maven
caching, and iframe caching.

**Unique to gardenSearch (NL):** 0 score-2 entries. GE-20260422-0ed3e5 (score 1 —
CDI container wiring vs service-loader wiring, tangentially relevant to the CDI
`@Alternative @Priority` adapter wiring pattern) and GE-20260613-1e5ba4 (score 1 —
LangChain4j EmbeddingModel does not implement TokenCountEstimator, tangentially
related as a LangChain4j interface hierarchy gotcha). Plus 6 score-0 entries.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.60-0.64) are tightly clustered and map
  entirely to score-0 entries. The embedding model latched onto "caching" as
  the dominant signal and returned entries about HTTP caching, iframe caching,
  Maven caching, and tenant-aware cache keys — none about LLM prompt caching.
  "ChatModel" and "AgentSession" are casehub-specific domain terms with no
  pre-trained semantic weight; "LangChain4j" as a proper noun similarly lacks
  embedding purchase. This is a catastrophic keyword-embedding mismatch.
- gardenSearch-NL: relevance scores (0.59-0.61) are extremely tightly clustered
  with almost no spread — the embedding model found no strong matches in the
  corpus. The two tangentially relevant entries (0.61 and 0.59) sit at the
  extremes of a 0.02 range, providing no meaningful ranking discrimination.
  The NL query's explicit framing of the adapter pattern did not help because
  the garden lacks entries about Claude SDK integration, subprocess-backed
  LLM providers, or prompt caching — the concept space itself is absent from
  the corpus.

**Verdict:** Clear win for grep.

- **Precision:** grep (45% relevant, 25% highly relevant) dominates both
  gardenSearch-NL (25% relevant, 0% highly relevant) and gardenSearch-KW
  (0% relevant, 0% highly relevant). grep is the only method that found
  any score-2 entries.
- **Discovery:** grep surfaced 5 unique score-2 finds — all directly about
  `ChatModel` interface structure, the `doChat()` extension point, Anthropic
  `ResponseFormat` constraints, `StreamingChatModel` mocking, and the
  `@ChatModelSupplier` CDI resolver hook. These are precisely the API details
  needed to build the adapter described in the issue. gardenSearch methods
  surfaced 0 score-2 entries.
- **Noise:** grep returned 160 total matches — moderate. The keywords `ChatModel`
  and `LangChain4j` are specific enough to target the right framework, but
  11/20 scored results were unrelated LangChain4j gotchas (jlama bootstrap,
  tika parser, guardrails semantics, RAG pipeline). gardenSearch-KW returned
  8/8 score-0 entries; gardenSearch-NL returned 6/8 score-0 entries.
- **NL vs keywords (same retrieval):** gardenSearch-NL (25% relevant) marginally
  outperformed gardenSearch-KW (0% relevant), but both were essentially failures.
  The NL query found 2 tangentially relevant entries vs 0 for keywords — a
  relative improvement that is still practically useless for the task.
- **Why grep won:** this issue involves framework-specific API names (`ChatModel`,
  `doChat()`, `StreamingChatModel`, `ResponseFormat`, `@ChatModelSupplier`) that
  appear verbatim in garden entries about LangChain4j. grep's substring matching
  found these entries by exact name match. The embedding model has no pre-trained
  semantic association between "ChatModel" (a LangChain4j interface) and garden
  entries about that interface — it treated the term as generic tokens.
  Additionally, the garden has no entries about Claude SDK subprocess integration,
  `AgentSession`, or LLM prompt caching — the core subject matter of the issue
  is absent from the corpus, which means no retrieval method can find directly
  relevant entries on the novel aspects. grep won by finding entries about the
  LangChain4j side of the adapter (the API surface the adapter must implement);
  gardenSearch could not even find those.

### Issue 6: [Testing / CI] — casehubio/engine#576

**Summary:** `InMemoryWorkItemTemplateStore` (`@Alternative @Priority(100)`) wins
CDI resolution over `JpaWorkItemTemplateStore` (`@ApplicationScoped`). Tests write
via `WorkItemTemplate.persist(t)` (Panache active record, goes to H2) but read via
`templateStore.get()` (CDI-injected in-memory store, empty `ConcurrentHashMap`).
Fix: add `JpaWorkItemTemplateStore` to `exclude-types`, add
`InMemoryWorkItemTemplateStore` to `selected-alternatives`, rewrite
`persistTemplate()` to use the injected store instead of Panache statics.

**Keywords (work-start procedure):**
`InMemoryWorkItemTemplateStore|Panache|selected-alternatives|Alternative`
Rationale: the specific class causing the mismatch
(`InMemoryWorkItemTemplateStore`), the persistence framework being bypassed
(`Panache`), the Quarkus config property (`selected-alternatives`), and the CDI
annotation mechanism (`Alternative`).

**Natural language query:** `"Test fails because @Alternative @Priority in-memory
CDI bean bypasses Panache JPA persistence — test writes via Panache static methods
but reads from empty in-memory store"`

#### grep (keywords) — first 20 of 815 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260531-1e51d4 | casehub-engine-persistence-memory @Alternative beans need explicit selected-alternatives for SubCaseGroupRepository and PlanItemStore | summary | 2 |
| 2 | GE-20260531-1587fe | JpaLedgerMerkleFrontierRepository must be added to selected-alternatives alongside JpaLedgerEntryRepository for LedgerVerificationService to work in @QuarkusTest | summary | 2 |
| 3 | GE-20260607-7033a1 | Panache: use getEntityManager().createQuery() for typed single-column projections | summary | 0 |
| 4 | GE-20260424-647a6d | Encode group membership in a string field to avoid a join table | summary | 0 |
| 5 | GE-20260427-543663 | @Produces @DefaultBean for library-level overridable CDI defaults without @Alternative | summary | 1 |
| 6 | GE-20260428-096e90 | JPA FK without CASCADE requires manual child deletion before parent deletion | summary | 0 |
| 7 | GE-20260428-6d75d7 | Panache/JPA count methods may return int rather than long | summary | 0 |
| 8 | GE-20260429-a79d0e | @Alternative @Priority(N) in Quarkus CDI auto-activates without quarkus.arc.selected-alternatives config | summary | 2 |
| 9 | GE-20260512-66d997 | Panache static methods bypass CDI @Alternative stores — returns empty results silently | summary | 2 |
| 10 | GE-20260512-67b3b5 | Panache find() alias-prefixed field names return empty results silently | summary | 0 |
| 11 | GE-20260512-a3838e | Transitive hibernate-reactive-panache on classpath causes H2 test startup failure | summary | 0 |
| 12 | GE-20260512-c246b0 | Test Quarkus CDI SPI implementations with @Alternative static inner classes | summary | 1 |
| 13 | GE-20260513-4f26a7 | @DefaultBean + plain @ApplicationScoped enables CDI layer displacement | summary | 1 |
| 14 | GE-20260517-e78ae8 | JPA entity returned from @Transactional method is detached — field mutations silently lost | summary | 0 |
| 15 | GE-20260519-12efe9 | QuarkusTestProfile.getEnabledAlternatives() scopes a CDI @Alternative to a single @QuarkusTest class | summary | 2 |
| 16 | GE-20260519-4a42e6 | Panache.withTransaction() requires a duplicated Vert.x context | summary | 0 |
| 17 | GE-20260519-e193d2 | Awaitility polling lambdas in @QuarkusTest have no JTA context — Panache reads throw ContextNotActiveException | summary | 1 |
| 18 | GE-20260519-f33c66 | 'Reactive' Quarkus service classes may use Panache statics internally — CDI @Alternative in-memory stores don't intercept those paths | summary | 2 |
| 19 | GE-20260519-f9624b | CDI @Alternative reactive wrapper with private new Impl() delegate is a separate instance from CDI bean | summary | 1 |
| 20 | GE-20260521-0bd1e6 | @Alternative without @Priority silently disables @IfBuildProperty-gated beans | summary | 1 |

**Precision:** 12/20 relevant (60%), 6/20 highly relevant (30%)
**Note:** All 20 results are `_summaries/` files. 815 total matches reflect the
extremely broad keyword surface — `Alternative` alone matches hundreds of CDI
entries, and `Panache` matches every Panache-related gotcha in the garden.

#### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260531-1587fe | JpaLedgerMerkleFrontierRepository must be added to selected-alternatives alongside JpaLedgerEntryRepository | 0.72 | 2 |
| 2 | GE-20260601-cee623 | QuarkusTestProfile.getEnabledAlternatives() replaces quarkus.arc.selected-alternatives — does not merge | 0.65 | 1 |
| 3 | GE-20260417-c59817 | quarkus.arc.selected-alternatives in application.properties activates @Alternative beans in @QuarkusTest | 0.61 | 2 |
| 4 | GE-20260429-a79d0e | @Alternative @Priority(N) in Quarkus CDI auto-activates without selected-alternatives config | 0.61 | 2 |
| 5 | GE-20260521-3ce7ca | @Alternative @Priority(1) from external JAR does not override non-alternative bean — needs exclude-types + selected-alternatives | 0.61 | 2 |
| 6 | GE-20260415-884e48 | @Alternative @Priority(n) in CDI 4.0/Quarkus globally activates the alternative — causes AmbiguousResolutionException | 0.61 | 1 |
| 7 | GE-20260616-d70e7e | quarkus.arc.selected-alternatives silently accepts non-@Alternative beans | 0.60 | 1 |
| 8 | GE-20260524-2b587e | quarkus.arc.selected-alternatives does not activate @Alternative beans during quarkus:build | 0.59 | 1 |

**Precision:** 8/8 relevant (100%), 4/8 highly relevant (50%)

#### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260529-fef800 | casehub-engine in-memory: CaseCompleted CDI event unreliable in @QuarkusTest | 0.76 | 1 |
| 2 | GE-20260519-f33c66 | 'Reactive' Quarkus service classes may use Panache statics internally — CDI @Alternative in-memory stores don't intercept those paths | 0.75 | 2 |
| 3 | GE-20260530-4387cb | casehub-qhorus MessageService methods using Panache static calls bypass InMemory store in @QuarkusTest | 0.75 | 2 |
| 4 | GE-20260612-ce4271 | MutableCurrentPrincipal @ApplicationScoped state leaks between @QuarkusTest classes | 0.75 | 1 |
| 5 | GE-20260512-e552f7 | @ApplicationScoped bean state persists across @QuarkusTest classes — tests pass in isolation but fail in suite | 0.75 | 1 |
| 6 | GE-20260421-88296e | casehub-engine persistence-memory Maven profile required for all engine tests without Docker | 0.75 | 1 |
| 7 | GE-20260531-1e51d4 | casehub-engine-persistence-memory @Alternative beans need explicit selected-alternatives | 0.74 | 2 |
| 8 | GE-20260518-896005 | In-memory test doubles are not rolled back when @Transactional rolls back — JTA and non-JTA writes diverge | 0.74 | 1 |

**Precision:** 8/8 relevant (100%), 3/8 highly relevant (38%)

#### Analysis

**Overlap:** 4 entries found by multiple methods (no entries found by all three):
- grep + gardenSearch-KW: GE-20260531-1587fe (score 2 — selected-alternatives for
  JPA repository in @QuarkusTest), GE-20260429-a79d0e (score 2 — @Alternative
  @Priority auto-activation behavior)
- grep + gardenSearch-NL: GE-20260531-1e51d4 (score 2 — @Alternative beans need
  explicit selected-alternatives for persistence-memory), GE-20260519-f33c66
  (score 2 — Panache statics bypass CDI @Alternative in-memory stores)

**Unique to grep:** 2 score-2 entries: GE-20260512-66d997 (Panache static methods
bypass CDI @Alternative stores — the canonical general-pattern entry),
GE-20260519-12efe9 (QuarkusTestProfile.getEnabledAlternatives() scopes @Alternative
to single test class). Plus 6 score-1 entries and 8 score-0 entries.

**Unique to gardenSearch (keywords):** 2 score-2 entries: GE-20260417-c59817
(quarkus.arc.selected-alternatives in application.properties activates @Alternative
beans in @QuarkusTest — the config mechanism used in the fix), GE-20260521-3ce7ca
(@Alternative @Priority(1) from external JAR needs exclude-types +
selected-alternatives — describes the exact two-part fix pattern). Plus 4 score-1
entries.

**Unique to gardenSearch (NL):** 1 score-2 entry: GE-20260530-4387cb
(casehub-qhorus MessageService Panache static calls bypass InMemory store — a
specific instance of the exact same root cause pattern as the issue). Plus 5
score-1 entries.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: the top result (0.72) scored 2 — good calibration. Relevance
  scores show meaningful spread (0.59-0.72), with all four score-2 entries
  clustered in the top five positions (0.72, 0.61, 0.61, 0.61). All 8 results
  scored >= 1. The keyword combination worked well here because
  `selected-alternatives` is a highly specific Quarkus config term that
  maps cleanly to garden entries about the exact same config mechanism.
  Unlike previous issues where keywords produced catastrophic mismatches,
  these keywords have strong semantic signal in the embedding space.
- gardenSearch-NL: relevance scores are tightly clustered (0.74-0.76) with
  minimal spread. The top result (0.76, score 1) outranked the two score-2
  entries (0.75, 0.75). The NL query found the right domain (test infrastructure
  with in-memory stores) but could not discriminate between the exact Panache
  bypass pattern (score 2) and general @QuarkusTest state management issues
  (score 1). All 8 results scored >= 1, showing the NL query correctly
  targeted the testing/CDI domain.

**Verdict:** Advantage gardenSearch (keywords).

- **Precision:** gardenSearch-KW (100% relevant, 50% highly relevant) leads
  gardenSearch-NL (100% relevant, 38% highly relevant), both outperforming
  grep (60% relevant, 30% highly relevant). All three methods found relevant
  entries, but gardenSearch methods had zero noise.
- **Discovery:** grep surfaced 2 unique score-2 finds (the canonical Panache
  bypass pattern entry and the QuarkusTestProfile alternatives scoping entry).
  gardenSearch-KW surfaced 2 unique score-2 finds (the selected-alternatives
  config mechanism and the exclude-types + selected-alternatives fix pattern).
  gardenSearch-NL surfaced 1 unique score-2 find (the qhorus MessageService
  Panache bypass instance).
- **Noise:** grep returned 815 total matches — the highest count in the
  benchmark. `Alternative` is an extremely common term in the garden's CDI
  coverage, and `Panache` matches every Panache-related entry. Despite this,
  the first 20 results were moderately well-targeted (12/20 relevant, 6/20
  highly relevant) because the keywords co-occur in entries about CDI
  alternative testing patterns. gardenSearch methods had 0 noise entries.
- **NL vs keywords (same retrieval):** gardenSearch-KW (50% highly relevant)
  outperformed gardenSearch-NL (38% highly relevant). This is the first issue
  where keyword embedding outperformed NL embedding. The keywords
  `selected-alternatives` and `Alternative` are highly specific to the
  Quarkus CDI config domain — they carry strong semantic signal in the
  embedding space because they are technical terms with narrow, unambiguous
  meaning. The NL query's broader framing ("test writes via Panache static
  methods but reads from empty in-memory store") found the right problem
  domain but diluted the signal with general @QuarkusTest state management
  entries that are related but not as precisely targeted.
- **Why gardenSearch-KW won:** `selected-alternatives` is an extremely specific
  Quarkus configuration property name. Unlike polysemous keywords in previous
  issues (`stream`, `shadowing`, `ChatModel`), this term has exactly one
  meaning — the Quarkus ARC config that activates @Alternative beans. The
  embedding model correctly associated it with garden entries about that
  specific config mechanism. This demonstrates that keyword-based embedding
  search works well when the keywords are unambiguous technical terms with
  narrow semantics — the opposite of the catastrophic failures seen with
  polysemous terms in Issues 3 and 4.

## Spec Review Results

### Spec 1: [Narrow] — `work/docs/superpowers/specs/2026-06-06-persistence-memory-module-design.md`

**Summary:** Extraction of five in-memory store implementations from `testing/`
into a new `persistence-memory/` module with CDI `@Alternative @Priority(100)` as
a new Tier 3 in the platform CDI priority ladder. Includes thread-safe data
structure migration (LinkedHashMap to ConcurrentHashMap, ArrayList to
CopyOnWriteArrayList), Quarkus extension deactivation for ephemeral production
deployment, and protocol compliance for aggregate methods.

**Domains extracted:** CDI priority tiers, Thread safety, Quarkus extension
deactivation, Protocol compliance

#### Domain 1: CDI priority tiers

**Keywords:** `Priority(100)|CDI priority|tier.*Alternative|ephemeral.*backend`
**NL query:** "CDI priority ladder with multiple tiers of Alternative Priority
annotations for persistence backend selection where ephemeral in-memory store must
override all other backends"

##### grep (keywords) — first 10 unique entries of 24 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260531-1587fe | JpaLedgerMerkleFrontierRepository must be added to selected-alternatives alongside JpaLedgerEntryRepository | summary | 1 |
| 2 | GE-20260623-22f1f7 | casehub-qhorus QhorusInboundCurrentPrincipal Javadoc claims OidcCurrentPrincipal has @Priority(100) — it doesn't | summary | 1 |
| 3 | GE-20260427-5d7c67 | quarkus-work (full) brings JpaWorkloadProvider that clashes with any other WorkloadProvider bean | entry | 1 |
| 4 | GE-20260528-f0a75c | @DefaultBean BlockingToReactiveBridge — wrap any blocking SPI as reactive, displaced by native async @Alternative | entry | 1 |
| 5 | GE-20260604-5bb2e7 | CircuitBreakerOpenException escapes AgentInvocationException wrapper in langchain4j agents | entry | 0 |
| 6 | GE-20260612-ce4271 | MutableCurrentPrincipal @ApplicationScoped state leaks between @QuarkusTest classes | entry | 1 |
| 7 | GE-20260618-d84391 | CDI @Decorator with constructor injection enables pure Java unit tests | entry | 0 |
| 8 | GE-20260623-c651a1 | Removing @Alternative from one bean in a multi-implementation CDI chain silently changes resolution | entry | 2 |
| 9 | GE-20260625-891c48 | quarkus.datasource.active=false is runtime-only — Hibernate build step still demands a configured datasource | entry | 2 |

**Note:** 24 total grep matches include 10 label files and 2 INDEX files (all
score 0), plus 2 duplicate _summaries/ paths. Effective unique entry count is 10;
the table omits the 10th (a label file scored 0). GE-20260625-891c48 matches
because it mentions `@Alternative @Priority(100)` in-memory stores in the context
section — a cross-domain hit that also appears in Domain 3.

**Precision:** 7/9 relevant (78%), 2/9 highly relevant (22%)

##### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260515-6e8205 | Three-tier Maven module structure — api/common/runtime | 0.62 | 1 |
| 2 | GE-20260426-0915b7 | Poll a semantically-meaningful ready signal, not just HTTP 200 | 0.60 | 0 |
| 3 | GE-20260522-f7db12 | Stateless multi-tier SLA escalation via candidateGroups | 0.60 | 0 |
| 4 | GE-20260526-27301b | OpenClaw WhatsApp uses Baileys (personal tier) not Meta Cloud API (business tier) | 0.60 | 0 |
| 5 | clinical-rbac-endpoint-topology | Clinical REST endpoints must follow GCP-derived RBAC topology | 0.57 | 0 |
| 6 | GE-0166 | Dispatch parallel agents for exhaustive cross-codebase comparison | 0.57 | 0 |
| 7 | GE-20260524-c66b05 | Tutorial layer dependency labels can silently point at the wrong milestone | 0.56 | 0 |
| 8 | platform-storeall-security-exception | storeAll() must propagate SecurityException immediately | 0.56 | 0 |

**Precision:** 1/8 relevant (13%), 0/8 highly relevant (0%)

##### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260522-f7db12 | Stateless multi-tier SLA escalation via candidateGroups | 0.67 | 0 |
| 2 | clinical-ae-memory-grade-outcome | Separate GRADE and OUTCOME in clinical AE memory entries | 0.66 | 0 |
| 3 | GE-20260423-4aa1e0 | JPA InheritanceType.JOINED forces all hierarchy entities into one PU | 0.66 | 0 |
| 4 | GE-20260530-c05d12 | entityManager.persist() inside @PrePersist of a different entity is unsafe | 0.65 | 0 |
| 5 | GE-20260612-17c161 | casehub-ledger LedgerProcessor build step blocks em.persist() on LedgerEntry subclasses | 0.65 | 0 |
| 6 | GE-20260515-6e8205 | Three-tier Maven module structure — api/common/runtime | 0.64 | 1 |
| 7 | GE-20260504-ba71a8 | GitHub Actions cache/restore + cache/save as separate v4 actions | 0.64 | 0 |
| 8 | GE-20260611-d34557 | JPA @PrePersist vs explicit enricher call — dual-persistence implementations diverge on hash coverage | 0.64 | 0 |

**Precision:** 1/8 relevant (13%), 0/8 highly relevant (0%)

##### Analysis

**Overlap:** 1 entry found by multiple methods:
- grep + gardenSearch-KW + gardenSearch-NL: GE-20260515-6e8205 (score 1 — Maven
  module tiers, tangentially related)

**Unique to grep:** 2 score-2 entries: GE-20260623-c651a1 (removing @Alternative
silently changes CDI resolution — a gotcha for multi-tier CDI ladders) and
GE-20260625-891c48 (datasource.active=false is runtime-only — the spec's ephemeral
deploy approach does not work). Plus 5 score-1 entries and 2 score-0 entries.

**Unique to gardenSearch (keywords):** 0 relevant results. 7 of 8 results scored
0 — entirely unrelated. The embedding model interpreted "tier" and "priority" as
generic concepts and returned entries about SLA tiers, WhatsApp API tiers, RBAC
topology, and parallel agent strategies.

**Unique to gardenSearch (NL):** 0 relevant results beyond the shared
GE-20260515-6e8205. The NL query's description of "persistence backend selection"
and "ephemeral in-memory store" did not map to the CDI priority ladder concept.
The embedding model matched "multi-tier" to SLA escalation tiers and JPA
persistence concepts rather than CDI annotation priority.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.56-0.62) are tightly clustered with no
  meaningful spread. The one relevant entry (0.62) is indistinguishable from the
  noise. The keyword combination was semantically misleading — "Priority(100)"
  and "CDI priority" are framework-specific terms with no embedding purchase;
  "tier" and "ephemeral" are polysemous.
- gardenSearch-NL: relevance scores (0.64-0.67) are also tightly clustered. The
  top result (0.67, SLA escalation) scored 0. The one relevant entry (0.64) sits
  at the bottom. The NL query's vocabulary ("CDI priority ladder", "ephemeral
  in-memory store") did not activate garden entries about CDI annotation patterns
  because the garden entries use different framing (@Alternative, @DefaultBean,
  @Priority).

**Verdict:** Clear win for grep.

- **Precision:** grep (78% relevant, 22% highly relevant) dominates
  gardenSearch-KW (13% relevant, 0% highly relevant) and gardenSearch-NL
  (13% relevant, 0% highly relevant).
- **Discovery:** grep surfaced 2 unique score-2 finds — both directly about CDI
  resolution behavior with @Alternative annotations, including a critical gotcha
  about the spec's ephemeral deployment approach. gardenSearch surfaced 0 score-2
  entries.
- **Why grep won:** the search terms `Priority(100)` and `CDI priority` are
  highly specific strings that appear verbatim in garden entries about CDI
  priority behavior. `tier.*Alternative` matched entries about @Alternative
  bean resolution patterns. These are distinctive technical strings that grep
  matches exactly. The embedding model has no pre-trained understanding of
  "CDI priority ladder" as a specific Quarkus/Arc concept — it treated the
  words as generic tokens and matched entries about other kinds of tiers.

#### Domain 2: Thread safety (ConcurrentHashMap, CopyOnWriteArrayList)

**Keywords:** `ConcurrentHashMap|CopyOnWriteArrayList|thread.safe|lock.free`
**NL query:** "Thread-safe in-memory store using ConcurrentHashMap and
CopyOnWriteArrayList for lock-free concurrent access with READ COMMITTED
semantics"

##### grep (keywords) — first 20 of 141 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260423-c8d8cb | ConcurrentHashMap.computeIfAbsent() + .add() is not atomic with concurrent remove() — futures permanently lost | summary | 2 |
| 2 | GE-20260505-da346d | @ApplicationScoped CDI beans are always-active in Quarkus — safe from any thread | summary | 1 |
| 3 | GE-20260514-421a6e | ConcurrentHashMap.newKeySet().add() provides atomic idempotent registration without explicit locks | summary | 2 |
| 4 | GE-20260522-6c22a3 | ConcurrentHashMap.computeIfPresent returning null atomically removes the entry — eliminates TOCTOU | summary | 2 |
| 5 | GE-20260522-98b286 | ConcurrentHashMap.remove(key, value) uses equals() — reflexive equality on mutable list creates TOCTOU | summary | 2 |
| 6 | GE-20260529-d8156d | ConcurrentHashMap.computeIfAbsent blocks all same-bucket keys — unusable for network calls | summary | 2 |
| 7 | GE-20260602-047ac4 | Visitor/accumulator pattern for thread-safe multi-backend aggregation | summary | 1 |
| 8 | GE-20260602-6cfbdb | ConcurrentHashMap.put() rejects null values — breaks fault-tolerance catch blocks | summary | 2 |
| 9 | GE-20260607-3ded98 | ConcurrentHashMap.getOrDefault(key, List.of()) loses type parameter with parameterized List | summary | 1 |
| 10 | GE-20260607-813a95 | ConcurrentHashMap.computeIfAbsent() null return from mapping function — use sentinel pattern | summary | 2 |
| 11 | GE-20260609-bc9bab | ConcurrentHashMap.put() happens-before covers future get() only — not pre-existing value references | summary | 2 |
| 12 | GE-20260610-09f7bd | Collections.unmodifiableMap() is a live view — computeIfAbsent() on the view throws UnsupportedOp | summary | 2 |
| 13 | GE-20260610-98066a | Read-modify-write on thread-safe store is not atomic — concurrent writers lose updates | summary | 2 |
| 14 | GE-20260612-f6362e | ConcurrentHashMap.computeIfAbsent does not cache null — mapping function re-invoked on every call | summary | 2 |
| 15 | GE-0142 | Hibernate @OneToMany must be initialized with ArrayList, not CopyOnWriteArrayList | summary | 2 |
| 16 | GE-20260414-8c43a9 | ConcurrentHashMap<ID, CompletableFuture> registry for suspending workflows | summary | 1 |
| 17 | approaches/code-review.md | Code Review — Principles (mentions thread-safety checks) | non-entry | 1 |
| 18 | GE-20260428-a67806 | casehub-engine Vert.x event-bus handlers lack @Blocking — JPA calls fail from IO thread | entry | 1 |
| 19 | GE-20260618-53a50a | TaskDefinitionRegistry: safe to instantiate with new in unit tests | entry | 0 |
| 20 | native-image-patterns.md | Java Panama FFM + GraalVM Native Image Gotchas | non-entry | 0 |

**Precision:** 18/20 relevant (90%), 12/20 highly relevant (60%)
**Note:** All _summaries/ results are from the `jvm/` domain. 141 total matches
reflect the strong ConcurrentHashMap coverage in the garden. `ConcurrentHashMap`
alone appears in dozens of entries about its specific behavioral gotchas — this
is the strongest keyword-to-domain correlation in the entire benchmark.

##### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260529-59d35a | GreenMailExtension.getGreenMail() is protected | 0.62 | 0 |
| 2 | GE-20260505-da346d | @ApplicationScoped CDI beans are always-active — safe from any thread | 0.62 | 0 |
| 3 | GE-20260607-067ace | Lock-outside-transaction pattern for safe concurrent sequence assignment | 0.60 | 2 |
| 4 | GE-20260514-421a6e | ConcurrentHashMap.newKeySet().add() atomic idempotent registration | 0.60 | 2 |
| 5 | GE-20260530-4387cb | casehub-qhorus Panache statics bypass InMemory store | 0.59 | 0 |
| 6 | GE-20260610-98066a | Read-modify-write on thread-safe store is not atomic | 0.59 | 2 |
| 7 | GE-20260604-ed1b02 | quarkus-flow task executor uses newCachedThreadPool() | 0.59 | 0 |
| 8 | GE-20260615-6d0ae3 | nextSequenceNumber() row lock serializes Merkle frontier update | 0.58 | 1 |

**Precision:** 4/8 relevant (50%), 3/8 highly relevant (38%)

##### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260607-067ace | Lock-outside-transaction pattern for safe concurrent sequence assignment | 0.72 | 2 |
| 2 | GE-20260610-98066a | Read-modify-write on thread-safe store is not atomic — concurrent writers lose updates | 0.72 | 2 |
| 3 | GE-20260615-6d0ae3 | nextSequenceNumber() row lock serializes Merkle frontier update | 0.72 | 1 |
| 4 | GE-20260514-421a6e | ConcurrentHashMap.newKeySet().add() atomic idempotent registration | 0.70 | 2 |
| 5 | GE-20260617-6b01c4 | H2 2.x MVStore MERGE INTO is not concurrent-safe for first-inserts | 0.69 | 0 |
| 6 | GE-20260609-62a1a7 | H2 MERGE WHEN NOT MATCHED races under concurrent transactions | 0.69 | 0 |
| 7 | (duplicate of #6) | | 0.69 | — |
| 8 | GE-20260618-0ed34c | H2 LOCK_MODE=1 has no effect with MVStore — concurrent MERGE races persist | 0.67 | 0 |

**Precision:** 4/7 relevant (57%), 3/7 highly relevant (43%)
**Note:** Result #7 is a duplicate of #6 (same entry ID returned twice by the
engine — a gardenSearch deduplication bug).

##### Analysis

**Overlap:** 3 entries found by multiple methods:
- All three: GE-20260514-421a6e (score 2 — ConcurrentHashMap.newKeySet() atomic
  registration), GE-20260610-98066a (score 2 — read-modify-write on thread-safe
  store is not atomic)
- gardenSearch-KW + gardenSearch-NL: GE-20260607-067ace (score 2 — lock-outside-
  transaction pattern), GE-20260615-6d0ae3 (score 1 — Merkle frontier
  serialization)

**Unique to grep:** 10 score-2 entries: GE-20260423-c8d8cb (computeIfAbsent +
add not atomic with concurrent remove), GE-20260522-6c22a3 (computeIfPresent
returning null atomically removes entry), GE-20260522-98b286 (remove(key, value)
uses equals on mutable lists), GE-20260529-d8156d (computeIfAbsent blocks entire
bucket), GE-20260602-6cfbdb (put rejects null values), GE-20260607-813a95
(computeIfAbsent null return sentinel pattern), GE-20260609-bc9bab (put
happens-before only covers future get), GE-20260610-09f7bd
(Collections.unmodifiableMap is live view — computeIfAbsent throws), GE-20260612-f6362e
(computeIfAbsent does not cache null), GE-0142 (Hibernate @OneToMany cannot use
CopyOnWriteArrayList). Plus 6 score-1 entries and 2 score-0 entries.

**Unique to gardenSearch (keywords):** 0 score-2 entries beyond those in the
overlap. Plus 1 score-1 entry (GE-20260615-6d0ae3) that gardenSearch-NL also
found. 4 score-0 entries.

**Unique to gardenSearch (NL):** 0 score-2 entries beyond those in the overlap.
3 score-0 entries about H2 concurrency — the embedding model interpreted
"concurrent access" and "thread-safe" as database concurrency rather than
Java data structure concurrency, pulling in H2 MVStore race conditions.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.58-0.62) are tightly clustered. The three
  score-2 entries (0.60, 0.60, 0.59) sit mid-range. The two score-0 entries at
  the top (0.62, 0.62) outrank the relevant entries — the embedding model gave
  GreenMailExtension (a test framework gotcha) higher relevance than
  ConcurrentHashMap gotchas. Poor calibration.
- gardenSearch-NL: better calibration. The top two entries (0.72, 0.72) both
  scored 2. The three score-0 entries (0.69, 0.69, 0.67) cluster at the bottom.
  But the H2 concurrency entries (score 0) sit close to the relevant entries —
  the spread (0.67-0.72) is only 0.05, providing weak discrimination between
  Java concurrency and database concurrency.

**Verdict:** Clear win for grep.

- **Precision:** grep (90% relevant, 60% highly relevant) dominates both
  gardenSearch-NL (57% relevant, 43% highly relevant) and gardenSearch-KW
  (50% relevant, 38% highly relevant).
- **Discovery:** grep surfaced 10 unique score-2 finds — all specific
  ConcurrentHashMap behavioral gotchas that directly apply to the spec's
  thread-safety migration. Many of these are exactly the kind of "gotcha the
  spec doesn't address": computeIfAbsent bucket blocking, put rejecting null,
  computeIfAbsent not caching null returns, read-modify-write non-atomicity,
  and the CopyOnWriteArrayList incompatibility with Hibernate @OneToMany.
  gardenSearch surfaced 0 unique score-2 entries.
- **Noise:** grep returned 141 total matches, but the first 20 were
  exceptionally well-targeted — 18/20 relevant, 13/20 highly relevant. This
  is the highest precision of any grep result set in the entire benchmark.
  `ConcurrentHashMap` is an unambiguous technical term with exactly one meaning,
  and the garden has extensive coverage of its behavioral gotchas.
- **NL vs keywords (same retrieval):** gardenSearch-NL (57% relevant) marginally
  outperformed gardenSearch-KW (50% relevant). The NL query found the same
  core entries but pulled in H2 database concurrency noise. The keyword query
  pulled in GreenMail and quarkus-flow thread pool entries.
- **Why grep won:** `ConcurrentHashMap` and `CopyOnWriteArrayList` are
  unambiguous class names. The garden has a deep cluster of entries about
  ConcurrentHashMap behavioral gotchas — computeIfAbsent, computeIfPresent,
  put, remove, happens-before guarantees. grep's substring matching found all
  of them by exact class name. The embedding model can find entries about
  thread safety and concurrency generally, but it cannot distinguish between
  Java data structure concurrency (ConcurrentHashMap gotchas) and database
  concurrency (H2 race conditions) — both are "lock-free concurrent access."
  The garden's ConcurrentHashMap entries are the single most valuable resource
  for the spec's thread-safety migration, and grep found 10 of them that
  gardenSearch missed entirely.

#### Domain 3: Quarkus extension deactivation

**Keywords:** `datasource.active|hibernate-orm.active|extension.*deactivat|build.time.*deactivat`
**NL query:** "Quarkus datasource and hibernate-orm extension deactivation at
build time to prevent JPA validation when using in-memory persistence instead of
database"

##### grep (keywords) — all 9 results

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260625-891c48 | quarkus.datasource.active=false is runtime-only — Hibernate build step still demands a configured datasource | summary | 2 |
| 2 | GE-20260625-891c48 | (duplicate — entry version of #1) | entry | — |
| 3–9 | (labels/INDEX files) | labels/alternative-stores, augmentation, build-time, datasource, hibernate-orm, quarkus; jvm/INDEX | label/index | 0 |

**Precision:** 1/1 unique entry relevant (100%), 1/1 highly relevant (100%)
**Note:** Only 1 unique garden entry matched the keywords — but it is the exact
gotcha the spec needs. 7 label files and 1 INDEX file (all score 0) padded the
results. The keywords are extremely specific (`datasource.active`,
`hibernate-orm.active`), producing near-zero noise but also near-zero recall
beyond the single critical entry.

##### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260625-891c48 | quarkus.datasource.active=false is runtime-only — Hibernate build step still demands datasource | 0.75 | 2 |
| 2 | GE-20260420-dcec35 | quarkus-hibernate-reactive-panache forces Hibernate Reactive boot — @Alternative does not prevent it | 0.72 | 2 |
| 3 | GE-20260420-45d53b | quarkus.datasource.reactive=false suppresses Hibernate Reactive boot | 0.71 | 1 |
| 4 | GE-20260508-492336 | casehub-qhorus activates quarkus-hibernate-reactive unconditionally | 0.69 | 1 |
| 5 | GE-20260414-be9977 | Quarkus extension activation uses quarkus-extension.properties | 0.69 | 1 |
| 6 | GE-20260508-b4c9b4 | quarkus-rest does not include Bean Validation — silently ignored | 0.68 | 0 |
| 7 | GE-20260423-fce720 | quarkus-work-core FilterRule JPA entity requires a datasource | 0.66 | 2 |
| 8 | GE-20260420-daf5dc | optional dep still activates Hibernate Reactive in tests | 0.66 | 1 |

**Precision:** 7/8 relevant (88%), 3/8 highly relevant (38%)

##### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260625-891c48 | quarkus.datasource.active=false is runtime-only — Hibernate build step still demands datasource | 0.76 | 2 |
| 2 | GE-20260420-dcec35 | quarkus-hibernate-reactive-panache forces Hibernate Reactive boot — @Alternative does not prevent it | 0.73 | 2 |
| 3 | GE-20260514-477d2f | Hibernate 6 SessionFactoryObserverForNamedQueryValidation throws at boot for schema-drifted JARs | 0.71 | 1 |
| 4 | GE-20260429-21e6cf | JPA entity in a dependency artifact forces datasource config on ALL downstream consumers | 0.70 | 2 |
| 5 | GE-20260423-fce720 | quarkus-work-core FilterRule JPA entity requires a datasource | 0.70 | 2 |
| 6 | GE-20260422-f86f42 | Quarkus dual-PU setup with library injecting @Default EntityManager requires dummy datasource | 0.70 | 1 |
| 7 | GE-20260521-2b82e7 | Panache.withTransaction() uses the default PU — wrong in apps with only a named PU | 0.70 | 0 |
| 8 | GE-20260420-45d53b | quarkus.datasource.reactive=false suppresses Hibernate Reactive boot | 0.69 | 1 |

**Precision:** 7/8 relevant (88%), 4/8 highly relevant (50%)

##### Analysis

**Overlap:** 3 entries found by multiple methods:
- All three: GE-20260625-891c48 (score 2 — the critical gotcha about
  datasource.active=false being runtime-only)
- gardenSearch-KW + gardenSearch-NL: GE-20260420-dcec35 (score 2 — build-time
  extension activation bypasses @Alternative), GE-20260420-45d53b (score 1 —
  datasource.reactive=false suppression), GE-20260423-fce720 (score 2 — JPA
  entity forces datasource)

**Unique to grep:** 0 entries beyond the shared GE-20260625-891c48. grep found
only 1 unique garden entry (the rest were labels).

**Unique to gardenSearch (keywords):** GE-20260508-492336 (score 1 — qhorus
activates hibernate-reactive unconditionally), GE-20260414-be9977 (score 1 —
extension activation metadata files), GE-20260508-b4c9b4 (score 0),
GE-20260420-daf5dc (score 1 — optional dep activates extension in tests).

**Unique to gardenSearch (NL):** GE-20260429-21e6cf (score 2 — JPA entity in
dependency forces datasource config on ALL downstream consumers — directly
relevant to the persistence-memory module's consumers), GE-20260514-477d2f
(score 1 — SessionFactory named query validation at boot), GE-20260422-f86f42
(score 1 — dummy datasource for library @Default EntityManager), GE-20260521-2b82e7
(score 0).

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: the top result (0.75) scored 2 — good calibration. Relevance
  scores show meaningful spread (0.66-0.75) with score-2 entries at 0.75, 0.72,
  and 0.66. The one score-0 entry (0.68) sits mid-range but is surrounded by
  score-1 entries, not other noise. Good ranking quality overall.
- gardenSearch-NL: the top result (0.76) scored 2 — strong calibration. The
  four score-2 entries occupy positions 1, 2, 4, 5 with relevance 0.76, 0.73,
  0.70, 0.70. The score-0 entry (0.70) sits at position 7, correctly below most
  relevant entries. Narrow spread (0.69-0.76) but ranking is well-calibrated.

**Verdict:** Clear win for gardenSearch.

- **Precision:** gardenSearch-KW and gardenSearch-NL (both 88% relevant) tie on
  relevant precision; gardenSearch-NL (50% highly relevant) leads gardenSearch-KW
  (38% highly relevant), both far outperforming grep's effective 100% precision
  on its single entry.
- **Discovery:** gardenSearch-NL surfaced 1 unique score-2 find: GE-20260429-21e6cf
  (JPA entity in dependency forces datasource on all consumers — a gotcha the spec
  must handle for persistence-memory's consumers). gardenSearch-KW surfaced 0
  unique score-2 finds. grep surfaced 0 unique finds (its only result was shared
  by all three methods).
- **Noise:** grep's extreme specificity produced near-zero noise but also
  near-zero recall — only 1 unique entry in 24 total matches (the rest were
  label files). gardenSearch methods each returned 7-8 relevant results, providing
  a much richer picture of the Quarkus extension deactivation landscape.
- **NL vs keywords (same retrieval):** gardenSearch-NL (50% highly relevant)
  marginally outperformed gardenSearch-KW (38% highly relevant). The NL query
  uniquely found GE-20260429-21e6cf — a critical gotcha about JPA entities in
  dependencies forcing datasource config on all downstream consumers. This is
  directly relevant to the persistence-memory module: when consumers add
  `persistence-memory` alongside `casehub-work` (which contains JPA entities),
  the JPA entities force datasource config even though the consumer wants
  in-memory only.
- **Why gardenSearch won:** the concept space is "Quarkus build-time extension
  activation and deactivation" — a pattern that manifests across Hibernate ORM,
  Hibernate Reactive, datasource config, and JPA entity scanning. grep's keywords
  were too narrowly scoped to catch the full pattern. `datasource.active` and
  `hibernate-orm.active` are specific config property names that appear in only
  one entry; the broader pattern (build-time vs runtime property enforcement,
  @Alternative not suppressing build steps, JPA entity classpath contamination)
  is described using different vocabulary across multiple entries. The embedding
  model's semantic matching connected these related concepts.

#### Domain 4: Protocol compliance (aggregate no-scan delegation)

**Keywords:** `scan.*pagination|aggregate.*scan|no.scan.delegation|pagination.*truncat`
**NL query:** "In-memory store aggregate methods must not delegate to scan because
scan applies pagination which silently truncates aggregation results"

##### grep (keywords) — all 10 results

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260501-311bd8 | quarkus.mcp.server.tools.page-size controls tools/list pagination — undocumented default of 50 silently truncates | summary | 1 |
| 2 | GE-20260429-2e1c4f | quarkus-ledger sequence_number index is not unique — race yields silent duplicate sequences | entry | 1 |
| 3–10 | (labels/INDEX files) | labels/configuration, mcp, pagination, quarkus-mcp-server, tools, undocumented; quarkus/INDEX | label/index | 0 |

**Precision:** 2/2 unique entries relevant (100%), 0/2 highly relevant (0%)
**Note:** Only 2 unique garden entries matched the keywords. Both are tangentially
related — about pagination truncation (MCP server) and aggregation (ledger
sequences) — but neither is about the specific in-memory store scan-delegation
protocol. The garden may not have entries specifically about this protocol.

##### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260430-01fecd | Parallel agents with domain-split substitution tables for renames | 0.59 | 0 |
| 2 | GE-20260501-311bd8 | quarkus.mcp.server.tools.page-size pagination truncation | 0.59 | 1 |
| 3 | clinical-rbac-endpoint-topology | Clinical REST endpoints RBAC topology | 0.57 | 0 |
| 4 | GE-20260416-39d854 | Synthesised delegation methods need explicit `public` | 0.57 | 0 |
| 5 | GE-20260602-1fb07b | Enumerate atomic facts before content migration | 0.56 | 0 |
| 6 | GE-20260617-5a9ad1 | CaseDefinition.hashCode() uses only 3 fields | 0.56 | 0 |
| 7 | arc42stories-primary-record-declaration | ARC42STORIES.MD as primary architecture record | 0.56 | 0 |
| 8 | GE-0050 | Conventional commit scope for clustering | 0.56 | 0 |

**Precision:** 1/8 relevant (13%), 0/8 highly relevant (0%)

##### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | clinical-ae-memory-grade-outcome | Separate GRADE and OUTCOME in clinical AE memory entries | 0.68 | 0 |
| 2 | GE-20260420-d99177 | @QuarkusTest classes sharing H2 contaminate each other's data | 0.65 | 0 |
| 3 | GE-20260618-d244e2 | Hibernate 6 JPQL arithmetic on MIN/MAX in HAVING silently accepted by H2 but rejected by PostgreSQL | 0.65 | 0 |
| 4 | GE-20260617-5a9ad1 | CaseDefinition.hashCode() uses only namespace+name+version | 0.65 | 0 |
| 5 | GE-20260423-e96787 | EntityManager.merge() return value must be captured | 0.64 | 0 |
| 6 | GE-20260604-21b1fa | Mem0 /search scores are not comparable across separate calls | 0.64 | 0 |
| 7 | GE-20260504-c51f9c | Disable squash/rebase merges across all org repos | 0.64 | 0 |
| 8 | GE-20260523-fc29ea | Stale qhorus local snapshot causes TABLE NOT FOUND | 0.64 | 0 |

**Precision:** 0/8 relevant (0%), 0/8 highly relevant (0%)

##### Analysis

**Overlap:** 1 entry found by multiple methods:
- grep + gardenSearch-KW: GE-20260501-311bd8 (score 1 — MCP server pagination
  truncation, tangentially related)

**Unique to grep:** GE-20260429-2e1c4f (score 1 — ledger sequence aggregation).
Plus 8 label/index files (all score 0).

**Unique to gardenSearch (keywords):** 0 relevant results. 7 of 8 results scored
0 — completely unrelated. The embedding model interpreted "scan", "delegation",
and "pagination" as generic terms and returned entries about parallel agent
delegation, synthesised delegation methods, and RBAC topology.

**Unique to gardenSearch (NL):** 0 relevant results. All 8 results scored 0.
The embedding model interpreted "aggregate methods" and "silently truncates" as
generic concepts and returned entries about clinical AE memory, H2 test data
contamination, JPQL arithmetic, and hashCode — none related to in-memory store
pagination truncation.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.56-0.59) are extremely tightly clustered
  with no discrimination. The one relevant entry (0.59, GE-20260501-311bd8) is
  indistinguishable from the noise.
- gardenSearch-NL: relevance scores (0.64-0.68) are tightly clustered with no
  relevant entries at all. The embedding model found no semantic connection
  between the NL query about "scan applying pagination" and any garden content.

**Verdict:** Marginal advantage grep.

- **Precision:** grep (100% of 2 unique entries, tangentially related), both
  gardenSearch methods essentially failed (13% and 0% relevant respectively).
- **Discovery:** no method found any score-2 entries. The protocol
  `inmemory-store-aggregate-no-scan-delegation` is a casehub-specific convention
  that is not captured as a garden entry — only referenced from protocol files
  and the spec itself. This domain represents a gap in the garden's coverage.
- **Why all methods struggled:** the garden does not appear to have entries about
  the specific footgun of in-memory store scan() methods applying pagination to
  aggregate operations. This is a protocol-level concern specific to casehub's
  in-memory store implementations. grep found tangentially related entries about
  pagination truncation; gardenSearch found nothing.

#### Spec-Level Assessment

**Gotchas surfaced by grep but not gardenSearch:**

1. **GE-20260623-c651a1** (Domain 1, score 2) — Removing @Alternative from one
   bean in a multi-implementation CDI chain silently changes resolution without
   AmbiguousResolutionException. The spec introduces a four-tier CDI ladder where
   removing a module (e.g. persistence-mongodb) changes which bean wins. This
   entry warns that such changes are silent.

2. **10 ConcurrentHashMap gotchas** (Domain 2, all score 2) — grep surfaced a
   deep cluster of ConcurrentHashMap behavioral gotchas that gardenSearch missed
   entirely. The most relevant to the spec:
   - **computeIfAbsent bucket blocking** (GE-20260529-d8156d): if the spec's
     `computeIfAbsent` calls in AuditEntryStore involve any I/O, they will block
     all other keys in the same hash bucket.
   - **put rejects null** (GE-20260602-6cfbdb): if any store value could be
     null, ConcurrentHashMap.put() will throw NPE — LinkedHashMap allowed it.
   - **CopyOnWriteArrayList + Hibernate** (GE-0142): if any in-memory store
     object is also a Hibernate entity with @OneToMany, CopyOnWriteArrayList
     cannot be used for the collection.
   - **Read-modify-write non-atomicity** (GE-20260610-98066a): the spec's
     AuditEntryStore.append() uses computeIfAbsent + add — the add is not
     atomic with respect to concurrent reads of the list.

**Gotchas surfaced by gardenSearch but not grep:**

1. **GE-20260429-21e6cf** (Domain 3, score 2, NL only) — JPA entity in a
   dependency artifact forces datasource config on ALL downstream consumers.
   The spec proposes `persistence-memory` as a standalone module, but consumers
   who add it alongside `casehub-work` (which contains JPA entities) will
   still need a datasource configured — the in-memory stores do not eliminate
   the JPA entity scanning requirement. The spec's ephemeral deployment section
   partially addresses this with the H2 fallback, but does not warn about
   the consumer-side impact.

2. **GE-20260420-dcec35** (Domain 3, score 2, both gardenSearch methods) —
   @Alternative does not suppress build-time extension activation. The spec's
   `@Alternative @Priority(100)` in-memory stores will win CDI resolution at
   runtime, but the JPA extension's build-time entity scanning is unaffected.
   This reinforces that the fallback H2 datasource is mandatory, not optional.

**Would these have influenced the design?**

Yes, significantly. The spec has a critical gap in its ephemeral deployment
section: it proposes `quarkus.datasource.active=false` and
`quarkus.hibernate-orm.active=false` as the primary approach, with the H2 dummy
datasource as a "fallback." Garden entry GE-20260625-891c48 — found by all three
methods — proves the primary approach does not work: both properties are
runtime-only, and the Hibernate build step demands a configured datasource at
augmentation time. The spec should invert the priority: H2 dummy datasource
is the primary approach, and `datasource.active=false` cannot be used at all
for the stated purpose.

The ConcurrentHashMap gotcha cluster from grep would have produced a more
nuanced thread-safety section. The spec states "no explicit locks" and proposes
computeIfAbsent for AuditEntryStore — but does not address the bucket-blocking
behavior, null rejection, or read-modify-write non-atomicity of ConcurrentHashMap.
These are exactly the class of gotchas the garden exists to surface.

**Method effectiveness for spec review:**

| Domain | Winner | grep precision | gS-KW precision | gS-NL precision | Key finding |
|--------|--------|---------------|-----------------|-----------------|-------------|
| CDI priority tiers | grep | 78% (22% h.r.) | 13% (0% h.r.) | 13% (0% h.r.) | "tier" and "priority" are polysemous; grep matched verbatim annotation names |
| Thread safety | grep | 90% (60% h.r.) | 50% (38% h.r.) | 57% (43% h.r.) | ConcurrentHashMap is an unambiguous class name; garden has deep coverage |
| Extension deactivation | gardenSearch | 100% (1 entry) | 88% (38% h.r.) | 88% (50% h.r.) | Concept space too broad for narrow keyword grep; gardenSearch connected related patterns |
| Protocol compliance | marginal grep | 100% (0% h.r.) | 13% (0% h.r.) | 0% (0% h.r.) | Garden has no entries for this protocol — all methods failed |

**Overall spec review verdict:** grep won 2 domains, gardenSearch won 1, 1 was a
draw (all failed). But the gardenSearch win in Domain 3 was qualitatively the most
important: it surfaced a gotcha (JPA entity classpath contamination) that directly
invalidates a section of the spec, while grep's Domain 2 wins surfaced operational
gotchas that would refine the implementation but not change the design direction.
For spec review, both methods are necessary — grep for finding entries about
specific APIs and class names, gardenSearch-NL for finding entries about
architectural patterns described in different vocabulary.

### Spec 2: [Broad] — `platform/docs/superpowers/specs/2026-06-26-agent-langchain4j-interop-design.md`

**Summary:** Cross-module bidirectional bridge between `AgentProvider` (native
SDK-based, reactive `Multi<AgentEvent>`) and `ChatModel`/`StreamingChatModel`
(LangChain4j standard). Designs `ChatModelAgentProvider` (ChatModel → AgentProvider,
`@Alternative @Priority(1)`) and `AgentProviderChatModel` (AgentProvider → ChatModel,
`@DefaultBean @Priority(10)`). Two separate CDI tier systems, circular dependency
prevention via `Instance<ChatModel>` filtering, JSON schema prompt engineering, and
a `MissingTenancyExceptionMapper` returning 403 Forbidden. Covers issues #114,
#115, #105.

**Domains extracted:** CDI tier coexistence (@DefaultBean/@Alternative @Priority),
LangChain4j ChatModel adaptation, CDI circular dependency prevention, ExceptionMapper
HTTP error mapping

#### Domain 1: CDI tier coexistence

**Keywords:** `DefaultBean.*Priority|Alternative.*Priority.*coexist|@DefaultBean.*@Priority|@Alternative.*@Priority.*suppress`
**NL query:** "@DefaultBean @Priority coexistence with @Alternative — how multiple
CDI bean tiers interact when both DefaultBean and Alternative annotations are used
together in Quarkus multi-module application"

##### grep (keywords) — 16 unique entries of 23 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260513-4f26a7 | @DefaultBean + plain @ApplicationScoped enables CDI layer displacement | summary | 1 |
| 2 | GE-20260427-5d7c67 | quarkus-work (full) brings JpaWorkloadProvider that clashes with any other WorkloadProvider bean | entry | 1 |
| 3 | GE-20260422-e48245 | @DefaultBean lives in io.quarkus.arc, not jakarta.enterprise.inject | entry | 0 |
| 4 | GE-20260429-a79d0e | @Alternative @Priority(N) in Quarkus CDI auto-activates without selected-alternatives config | entry | 1 |
| 5 | GE-20260515-99cf39 | Config-driven @Produces @DefaultBean for engine-internal strategy selection with consumer override | entry | 1 |
| 6 | GE-20260517-9e571a | @Typed required when CDI bean implements framework-owned interface to prevent AmbiguousResolutionException | entry | 0 |
| 7 | GE-20260519-e13b01 | @QuarkusTest crashes with ClassSelector resolution failed when casehub-ledger runtime on classpath | entry | 0 |
| 8 | GE-20260601-3dbc80 | quarkus.arc.exclude-types resolves @DefaultBean ambiguity between two Jandex-indexed framework JARs | entry | 1 |
| 9 | GE-20260601-fcf0d9 | Two @DefaultBean beans for same type → AmbiguousResolutionException | entry | 2 |
| 10 | GE-20260604-2f0889 | @Alternative @Priority(N) stubs in @QuarkusTest appear in @All List<T> alongside original bean | entry | 2 |
| 11 | GE-20260609-77a6f9 | casehub-work SNAPSHOT @RequestScoped CurrentPrincipal displaces @DefaultBean test mock | entry | 1 |
| 12 | GE-20260609-8d6961 | @Alternative @Qualifier on bean class restricts CDI injection to qualifier-scoped points | entry | 1 |
| 13 | GE-20260623-c651a1 | Removing @Alternative from one bean in multi-implementation CDI chain silently changes resolution | entry | 2 |
| 14 | GE-20260626-c21b02 | @DefaultBean silently suppressed by any Instance<T> peer — breaks multi-implementation SPI patterns | entry | 2 |
| 15 | GE-20260627-51e402 | @Alternative on CDI bean silently suppresses all @DefaultBean beans of same type — vanish from container | entry | 2 |
| 16 | GE-20260427-543663 | @Produces @DefaultBean for library-level overridable CDI defaults without @Alternative | entry | 1 |

**Precision:** 13/16 relevant (81%), 5/16 highly relevant (31%)
**Note:** 23 total matches include 7 label/INDEX files (all score 0). The
16 unique entries show strong domain targeting — the regex pattern
`DefaultBean.*Priority` and `Alternative.*Priority.*suppress` are specific
enough to match entries about CDI tier coexistence rather than generic CDI
content.

##### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260616-99484f | Uni.combine() runs blocking IO and reactive DB lookup concurrently | 0.62 | 0 |
| 2 | GE-20260530-4387cb | casehub-qhorus Panache statics bypass InMemory store | 0.61 | 0 |
| 3 | GE-20260420-d99177 | @QuarkusTest H2 data contamination | 0.61 | 0 |
| 4 | GE-20260518-a61d1b | @ConsumeEvent(blocking=true) + @Transactional works | 0.60 | 0 |
| 5 | GE-20260504-ae76f6 | Squash-merged PR drops commits pushed after PR opened | 0.60 | 0 |
| 6 | GE-20260508-ce2285 | UUID-suffix business keys in @QuarkusTest | 0.60 | 0 |
| 7 | GE-20260626-c21b02 | @DefaultBean silently suppressed by any Instance<T> peer | 0.60 | 2 |
| 8 | GE-20260609-62a1a7 | H2 MERGE WHEN NOT MATCHED races | 0.60 | 0 |

**Precision:** 1/8 relevant (13%), 1/8 highly relevant (13%)

##### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260415-884e48 | @Alternative @Priority(n) globally activates — causes AmbiguousResolutionException | 0.74 | 2 |
| 2 | GE-20260623-c651a1 | Removing @Alternative silently changes CDI resolution — no AmbiguousResolutionException | 0.71 | 2 |
| 3 | GE-20260609-26ffa5 | @ApplicationScoped POJO + @Produces causes CDI ambiguous bean resolution | 0.71 | 1 |
| 4 | GE-20260422-ebb91d | CDI AmbiguousResolutionException multiple @ApplicationScoped beans same SPI | 0.70 | 1 |
| 5 | GE-20260608-401287 | @Readiness is both Health classifier and CDI qualifier | 0.70 | 0 |
| 6 | GE-20260604-d08c9f | @Transactional and @Tool coexist on same method | 0.70 | 0 |
| 7 | GE-20260424-59906a | Quarkus CDI does not scan @ApplicationScoped in plain JAR modules | 0.70 | 1 |
| 8 | GE-20260601-fcf0d9 | Two @DefaultBean beans → AmbiguousResolutionException | 0.70 | 2 |

**Precision:** 6/8 relevant (75%), 3/8 highly relevant (38%)

##### Analysis

**Overlap:** 3 entries found by multiple methods:
- All three: GE-20260626-c21b02 (score 2 — @DefaultBean suppressed by Instance<T>
  peer, the exact gotcha that motivated the spec's `@DefaultBean @Priority(10)`
  design choice)
- grep + gardenSearch-NL: GE-20260623-c651a1 (score 2 — removing @Alternative
  silently changes multi-tier CDI resolution), GE-20260601-fcf0d9 (score 2 —
  two @DefaultBean beans cause AmbiguousResolutionException)

**Unique to grep:** 2 score-2 entries: GE-20260604-2f0889 (@Alternative @Priority
stubs appear in @All List<T> alongside original bean — affects Instance<ChatModel>
visibility in two-tier deployments) and GE-20260627-51e402 (@Alternative suppresses
all @DefaultBean beans entirely — the foundational gotcha that prevents using
@Alternative for AgentProviderChatModel). Plus 7 score-1 entries and 3 score-0
entries.

**Unique to gardenSearch (keywords):** 0 relevant results. 7 of 8 entries scored
0 — completely unrelated to CDI tier coexistence. The embedding model treated
"DefaultBean", "Priority", "Alternative", and "suppress" as generic tokens,
matching entries about Vert.x, H2, Panache, and squash-merging.

**Unique to gardenSearch (NL):** 1 score-2 entry: GE-20260415-884e48 (@Alternative
@Priority globally activates and causes AmbiguousResolutionException — directly
relevant to understanding why the spec's tier system works). Plus 3 score-1
entries covering CDI ambiguity patterns and module scanning.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.60-0.62) are tightly clustered with
  essentially no spread. The single relevant entry (0.60) sits at rank 7 — the
  embedding model assigned the same relevance to H2 race conditions and squash
  merge gotchas as to the CDI tier coexistence entry. Keywords that are CDI
  annotation names (`@DefaultBean`, `@Priority`, `@Alternative`) have no
  pre-trained semantic weight in the embedding model's vocabulary.
- gardenSearch-NL: better calibration. The top entry (0.74) scored 2, and the
  two score-0 entries (0.70, 0.70) sit at the bottom alongside the score-2
  entry GE-20260601-fcf0d9. The spread (0.70-0.74) is narrow but the ranking
  correctly placed the most relevant entry first.

**Verdict:** Clear win for grep.

- **Precision:** grep (81% relevant, 31% highly relevant) dominates
  gardenSearch-NL (75% relevant, 38% highly relevant) on total score-2 count
  (5 vs 3). gardenSearch-KW (13% relevant, 13% highly relevant) was a failure.
- **Discovery:** grep surfaced 2 unique score-2 finds — GE-20260604-2f0889
  (Instance<ChatModel> visibility with @Alternative) and GE-20260627-51e402
  (the foundational @Alternative-suppresses-@DefaultBean gotcha). gardenSearch-NL
  surfaced 1 unique score-2 find. gardenSearch-KW surfaced 0 unique relevant
  entries.
- **Why grep won:** the search regex targets specific CDI annotation patterns
  (`DefaultBean.*Priority`, `Alternative.*Priority`) that appear verbatim in
  garden entries about CDI tier behavior. These are distinctive enough to
  filter out generic CDI content while capturing the exact coexistence gotchas
  the spec addresses.

#### Domain 2: LangChain4j ChatModel adaptation

**Keywords:** `ChatModel|doChat|StreamingChatModel|ChatLanguageModel`
**NL query:** "LangChain4j ChatModel adapter wrapping AgentProvider — doChat
extension point, StreamingChatModel handler, ChatResponse building,
provider-agnostic model bridging"

##### grep (keywords) — first 20 unique entries of 53 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260522-3e2589 | LangChain4j ChatModel cannot be stubbed as lambda — override doChat(ChatRequest) not chat(ChatRequest) | summary | 2 |
| 2 | GE-20260525-fd4868 | LangChain4j 1.x: ChatLanguageModel renamed to ChatModel; chat() replaced by doChat() as extension point | entry | 2 |
| 3 | GE-20260529-0c80ca | LangChain4j StreamingChatModel: mock doChat() not chat() — default chat() wraps handler in observingHandler | entry | 2 |
| 4 | GE-20260603-301b80 | langchain4j-agentic DeclarativeUtil has a single CDI resolver hook for @ChatModelSupplier only | entry | 2 |
| 5 | GE-20260614-337397 | quarkus-langchain4j Ollama extension registers @Default ChatModel — clashes with @DefaultBean fallback | entry | 2 |
| 6 | GE-20260617-d18081 | LangChain4j 1.x: implementing both ChatModel and StreamingChatModel requires explicit overrides for all four shared default methods | entry | 2 |
| 7 | GE-20260618-248ce7 | Agent.build() bakes ChatModel once — @InjectMock on ChatModelProvider silently ignored after first augment() | entry | 1 |
| 8 | GE-20260618-8526c8 | ChatModel.doChat(ChatRequest) is the override point for test doubles — not chat(ChatRequest) | entry | 2 |
| 9 | GE-20260618-5008f5 | @Alternative @Priority(10) CDI test bean avoids Quarkus CDI restart caused by @InjectMock | entry | 1 |
| 10 | GE-20260618-c552c3 | @InjectMock on @ApplicationScoped triggers CDI restart — Vert.x codec double-registers | entry | 0 |
| 11 | GE-20260525-80e370 | LangChain4j 1.x UserMessage: text accessor is singleText(), not text() | entry | 1 |
| 12 | GE-20260528-e9564b | LangChain4j Anthropic: ResponseFormat.JSON without schema throws UnsupportedFeatureException | entry | 2 |
| 13 | GE-20260529-0b8284 | Uni.createFrom().emitter() wrong for one-shot callback bridging — use completionStage() | entry | 1 |
| 14 | GE-20260531-1ec900 | Quarkus ARC registers non-CDI abstract class as @Dependent bean when it declares @Observes | entry | 0 |
| 15 | GE-20260531-686150 | Adding a member to an enum dimension set breaks evaluators that iterate values() | entry | 0 |
| 16 | GE-20260601-fcf0d9 | Two @DefaultBean beans for same type → AmbiguousResolutionException | entry | 1 |
| 17 | GE-20260604-b3d2ef | quarkus-flow CDI extension registers WorkflowApplication — adding @Produces causes ambiguity | entry | 0 |
| 18 | GE-20260609-49e48c | SupervisorAgentService bypasses WorkflowAgentsBuilder SPI | entry | 0 |
| 19 | GE-20260614-94c366 | quarkus-langchain4j-ollama via Maven test-scope profile requires mvn clean to invalidate cache | entry | 0 |
| 20 | GE-20260618-a7a383 | LangChain4j AiMessage and ChatResponse cannot be Mockito-mocked — use real constructors/builders | entry | 1 |

**Precision:** 14/20 relevant (70%), 8/20 highly relevant (40%)
**Note:** 53 total matches include 20+ label files (ChatModel.md,
StreamingChatModel.md, langchain4j.md, etc.) — all score 0. The first
20 unique entries show strong targeting because `ChatModel` and `doChat`
are distinctive LangChain4j API names with narrow meaning.

##### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260621-629712 | Canonical CloudEvent adapter pattern — 7 rules for CDI async adapters | 0.64 | 0 |
| 2 | (protocol) | Annotate CaseMemoryStore methods with @Timed | 0.61 | 0 |
| 3 | GE-20260529-bc1eaa | TIMESTAMPTZ not recognised by H2 in MODE=PostgreSQL | 0.59 | 0 |
| 4 | GE-20260621-629712 | (duplicate of #1) | 0.58 | 0 |
| 5 | GE-20260618-73a023 | H2 JDBC getMetaData().getURL() drops MODE | 0.57 | 0 |
| 6 | GE-20260526-27301b | OpenClaw WhatsApp uses Baileys | 0.57 | 0 |
| 7 | GE-20260530-29545c | WireMock 1.4.1 incompatible with Quarkus 3.32 | 0.57 | 0 |
| 8 | GE-20260512-2c2eff | Non-ANSI SQL in Flyway pass H2, fail PostgreSQL | 0.57 | 0 |

**Precision:** 0/7 relevant (0%), 0/7 highly relevant (0%)
**Note:** Result #4 is a duplicate. gardenSearch-KW returned zero relevant
entries — the embedding model could not connect "ChatModel", "doChat",
"StreamingChatModel", and "ChatLanguageModel" to garden entries about
LangChain4j. These are proper nouns and Java interface names with no
pre-trained semantic weight.

##### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260422-0ed3e5 | CDI container wiring vs service-loader wiring in large JVM frameworks | 0.69 | 1 |
| 2 | GE-20260621-629712 | Canonical CloudEvent adapter pattern — 7 rules for CDI async adapters | 0.66 | 0 |
| 3 | GE-20260422-f922f3 | quarkus-langchain4j-core stalls @QuarkusTest augmentation when no model provider configured | 0.66 | 2 |
| 4 | GE-20260501-4c94b8 | Vert.x Mutiny PgPool.getConnection() ClassCastException | 0.65 | 0 |
| 5 | GE-20260519-eb8340 | Instance<T>.handles() returns Iterable<? extends Instance.Handle<T>> — incompatible type | 0.65 | 2 |
| 6 | GE-20260422-70b817 | Span.wrap(SpanContext) OTel trace context in tests | 0.64 | 0 |
| 7 | GE-20260424-e33d79 | Hardcoding @PersistenceUnit in generic extension silently breaks consumers | 0.64 | 1 |
| 8 | GE-20260614-328420 | OpenClaw /v1/chat/completions requires model='openclaw' | 0.64 | 0 |

**Precision:** 4/8 relevant (50%), 2/8 highly relevant (25%)

##### Analysis

**Overlap:** 0 entries found by multiple methods.

**Unique to grep:** 8 score-2 entries: GE-20260522-3e2589 (ChatModel cannot be
stubbed as lambda), GE-20260525-fd4868 (ChatLanguageModel → ChatModel rename,
chat() → doChat()), GE-20260529-0c80ca (StreamingChatModel mock doChat() not
chat()), GE-20260603-301b80 (@ChatModelSupplier CDI resolver hook),
GE-20260614-337397 (quarkus-langchain4j Ollama @Default ChatModel clashes with
@DefaultBean), GE-20260617-d18081 (implementing both ChatModel and
StreamingChatModel requires four explicit overrides), GE-20260618-8526c8
(doChat(ChatRequest) is the override point), GE-20260528-e9564b (Anthropic
ResponseFormat.JSON without schema throws). Plus 6 score-1 entries and 6
score-0 entries.

**Unique to gardenSearch (keywords):** 0 relevant results. All 7 unique entries
scored 0 — entirely unrelated to LangChain4j or ChatModel.

**Unique to gardenSearch (NL):** 2 score-2 entries: GE-20260422-f922f3
(quarkus-langchain4j-core stalls @QuarkusTest when no model provider is
configured — directly relevant to the spec's graceful deactivation design for
ChatModelAgentProvider) and GE-20260519-eb8340 (Instance<T>.handles() returns
incompatible generic type — a gotcha for the spec's Instance<ChatModel>
filtering logic). Plus 2 score-1 entries (CDI wiring patterns) and 4 score-0
entries.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.57-0.64) are tightly clustered with zero
  relevant entries. The embedding model interpreted "ChatModel", "doChat",
  "StreamingChatModel", and "ChatLanguageModel" as generic tokens and matched
  entries about CloudEvent adapters, H2 SQL types, and OpenClaw WhatsApp. This
  is the same catastrophic keyword-embedding mismatch seen in Issues 3, 4, and
  5 — proper nouns and Java interface names have no pre-trained semantic weight.
- gardenSearch-NL: better discrimination. The top entry (0.69, CDI wiring vs
  service-loader) scored 1. The two score-2 entries (0.66 and 0.65) sit at
  ranks 3 and 5. The spread (0.64-0.69) is narrow but the NL query's framing
  of "adapter wrapping AgentProvider" connected to entries about provider
  patterns and extension bootstrap failures.

**Verdict:** Clear win for grep.

- **Precision:** grep (70% relevant, 40% highly relevant) dominates
  gardenSearch-NL (50% relevant, 25% highly relevant). gardenSearch-KW (0%
  relevant) was a total failure.
- **Discovery:** grep surfaced 8 unique score-2 finds — the entire LangChain4j
  ChatModel API surface needed to build the adapter (doChat extension point,
  ChatLanguageModel rename, StreamingChatModel mock pattern, diamond inheritance
  with four default methods, ResponseFormat.JSON schema requirement, Ollama
  @Default ChatModel clash). gardenSearch-NL surfaced 2 unique score-2 finds —
  both about CDI/Instance gotchas rather than LangChain4j API details.
- **Why grep won:** `ChatModel`, `doChat`, `StreamingChatModel`, and
  `ChatLanguageModel` are exact Java interface and method names. The garden has
  extensive coverage of LangChain4j API gotchas, all using these exact names.
  grep's substring matching found them all. The embedding model has no
  pre-trained understanding of these as LangChain4j-specific terms — it treated
  them as generic words. gardenSearch-NL's semantic framing helped somewhat
  (50% relevant vs 0% for keywords) but could not recover the specific API
  entries that require exact name matching.

#### Domain 3: CDI circular dependency prevention

**Keywords:** `Instance.*ChatModel|Instance.*filter|circular.*depend|@PostConstruct.*deactivat|graceful.*deactivat`
**NL query:** "CDI bean using Instance to dynamically lookup and filter beans at
PostConstruct to prevent circular dependency between two CDI beans that reference
each other"

##### grep (keywords) — first 20 unique entries of 35 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260505-8c57c2 | CDI events as bridge for circular Maven module dependencies | summary | 1 |
| 2 | GE-20260424-318ef3 | Service unit tests can't go in runtime/src/test/ when service depends on testing module | summary | 0 |
| 3 | GE-20260514-875f82 | Quarkus extension testing module creates circular Maven dependency | summary | 0 |
| 4 | GE-20260417-4a3c22 | Worker lambda receives null for context fields added to inputSchema | entry | 0 |
| 5 | GE-20260612-9ff1c6 | Programmatic worker binding fires on initial empty-context event | entry | 0 |
| 6 | GE-20260618-53a50a | TaskDefinitionRegistry safe to instantiate with new in unit tests | entry | 0 |
| 7 | (protocol) | Ledger count assertions must filter by LedgerEntry subclass | protocol | 0 |
| 8 | GE-20260423-9a5470 | Testing IntelliJ Annotators on injected language | entry | 0 |
| 9 | GE-20260417-96accd | Maven multi-module cycle: adding module as test-scope dep when it depends on you | entry | 0 |
| 10 | GE-20260505-43a73b | Mockito thenReturn(stream) exhausts CDI Instance<T> mock on second call | entry | 2 |
| 11 | GE-20260512-c30f52 | @QuarkusIntegrationTest in runtime module causes class loading failures | entry | 0 |
| 12 | GE-20260513-a49d06 | CDI this.method() bypasses @Transactional proxy | entry | 1 |
| 13 | GE-20260524-122018 | Maven parent POM bootstrap in CI needs <repositories> | entry | 0 |
| 14 | GE-20260601-a35fb3 | InMemoryCaseInstanceRepository.findByUuid silently returns null when tenancyId is null | entry | 0 |
| 15 | GE-20260604-a6f008 | Multi-module extension: optional module types often live in core module | entry | 0 |
| 16 | GE-20260609-9ee2ad | Qhorus ChannelService.create() does not fire ChannelInitialisedEvent | entry | 0 |
| 17 | GE-20260611-dedf69 | Two-phase signing pattern — prepareKey then sign | entry | 0 |
| 18 | GE-20260627-51e402 | @Alternative on CDI bean silently suppresses all @DefaultBean beans of same type | entry | 2 |
| 19 | GE-20260525-cc8321 | Pre-push hook blocks squash's own delivery push — requires --no-verify | entry | 0 |
| 20 | GE-20260522-6786c3 | el.textContent equals concatenation of text nodes in DOM order | entry | 0 |

**Precision:** 4/20 relevant (20%), 2/20 highly relevant (10%)
**Note:** 35 total matches include 10 label/INDEX files. The regex
`circular.*depend` matched 6 entries about Maven dependency cycles (build-system
layer) rather than CDI injection circular dependencies (runtime layer) — same
words, different concepts. `Instance.*filter` hit the two genuinely relevant
entries.

##### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260607-e27c23 | DefaultWorkerExecutionRecoveryService non-obvious CDI dependency | 0.63 | 1 |
| 2 | GE-20260514-875f82 | Quarkus extension testing creates circular Maven dependency | 0.62 | 0 |
| 3 | GE-20260529-ff186e | emitOn(Infrastructure.getDefaultWorkerPool()) | 0.61 | 0 |
| 4 | GE-20260519-12efe9 | QuarkusTestProfile.getEnabledAlternatives() scopes @Alternative | 0.61 | 1 |
| 5 | GE-20260502-c77725 | MultiInstanceSpawnService CANCEL races with coordinator | 0.60 | 0 |
| 6 | GE-20260616-780f2e | ImmutableDesiredStateGraph.withoutNode() destroys edges | 0.60 | 0 |
| 7 | GE-20260530-4387cb | Panache statics bypass InMemory store | 0.60 | 0 |
| 8 | GE-20260601-cee623 | QuarkusTestProfile.getEnabledAlternatives() replaces, not merges | 0.60 | 1 |

**Precision:** 3/8 relevant (38%), 0/8 highly relevant (0%)

##### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260626-c94109 | @LookupIfProperty for conditional CDI bean activation via Instance<> | 0.72 | 2 |
| 2 | GE-20260626-a37306 | Library jar with @ApplicationScoped silently overrides @DefaultBean alternatives | 0.71 | 2 |
| 3 | GE-20260424-59906a | Quarkus CDI does not scan beans in plain JAR modules | 0.70 | 1 |
| 4 | GE-20260505-da346d | @ApplicationScoped CDI beans always-active, safe from any thread | 0.70 | 0 |
| 5 | GE-20260615-d065bf | Two @Startup beans have no guaranteed init order — @PostConstruct unreliable | 0.70 | 1 |
| 6 | GE-20260523-afab1d | @ApplicationScoped bean invisible to ARC without Jandex index | 0.70 | 1 |
| 7 | GE-20260522-adb5cd | Moving bean from app module to library JAR breaks CDI discovery | 0.69 | 1 |
| 8 | GE-20260429-da95ec | Two-bean pattern for @ObservesAsync + @Transactional with OCC retry | 0.69 | 1 |

**Precision:** 7/8 relevant (88%), 2/8 highly relevant (25%)

##### Analysis

**Overlap:** 0 entries found by all three methods. 1 entry shared:
- grep + gardenSearch-KW: GE-20260514-875f82 (score 0 — Maven circular
  dependency, wrong layer)

**Unique to grep:** 2 score-2 entries: GE-20260505-43a73b (Mockito
thenReturn(stream) exhausts CDI Instance<T> mock on second call — a testing
gotcha for the spec's Instance<ChatModel> filtering logic) and GE-20260627-51e402
(@Alternative suppresses all @DefaultBean beans — directly informs the spec's
decision to use @DefaultBean @Priority instead of @Alternative). Plus 2 score-1
entries and 16 score-0 entries.

**Unique to gardenSearch (keywords):** 0 score-2 entries. 3 score-1 entries:
GE-20260607-e27c23 (non-obvious CDI dependency chains), GE-20260519-12efe9
(QuarkusTestProfile alternatives scoping), GE-20260601-cee623 (alternatives
replacement semantics). Plus 4 score-0 entries.

**Unique to gardenSearch (NL):** 2 score-2 entries: GE-20260626-c94109
(@LookupIfProperty for conditional CDI bean activation via Instance<> — an
alternative approach to the spec's @PostConstruct filtering pattern that is
cleaner and Quarkus-native) and GE-20260626-a37306 (library JAR with
@ApplicationScoped beans silently overrides @DefaultBean — a deployment gotcha
where adding agent-langchain4j alongside another module could silently change
CDI resolution). Plus 5 score-1 entries.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.60-0.63) are tightly clustered. The
  3 score-1 entries sit at 0.63, 0.61, 0.60 — indistinguishable from the
  5 score-0 entries. The embedding model interpreted "Instance", "filter",
  "circular dependency", and "@PostConstruct deactivation" as generic terms
  and returned a mix of CDI-adjacent and unrelated results.
- gardenSearch-NL: the top two entries (0.72, 0.71) both scored 2 — strong
  calibration. The score-0 entry (0.70) is at rank 4, one below the two
  directly relevant entries. The spread (0.69-0.72) is narrow but the NL
  query's framing of "dynamically lookup and filter beans at PostConstruct
  to prevent circular dependency" correctly activated entries about
  Instance-based CDI patterns and library JAR @DefaultBean override gotchas.

**Verdict:** Advantage gardenSearch (NL).

- **Precision:** gardenSearch-NL (88% relevant, 25% highly relevant) dominates
  grep (20% relevant, 10% highly relevant) and gardenSearch-KW (38% relevant,
  0% highly relevant).
- **Discovery:** gardenSearch-NL surfaced 2 unique score-2 finds — both about
  CDI Instance<> patterns and @DefaultBean override risks in multi-module
  deployments. grep surfaced 2 unique score-2 finds — one testing gotcha and
  one foundational @Alternative/@DefaultBean suppression entry. Both methods
  surfaced equal unique score-2 counts, but gardenSearch-NL's finds are
  more relevant to the spec's cross-module deployment concerns.
- **Why gardenSearch-NL won on precision:** grep's `circular.*depend` regex
  matched 6 Maven dependency cycle entries that are entirely different from
  CDI injection circular dependencies — the polysemy of "circular dependency"
  across build-system and runtime layers inflated noise to 80%. gardenSearch-NL's
  semantic understanding distinguished between Maven cycles and CDI injection
  patterns.

#### Domain 4: ExceptionMapper HTTP error mapping

**Keywords:** `ExceptionMapper|exception.*mapper|MissingTenancy|403.*Forbidden`
**NL query:** "JAX-RS ExceptionMapper that converts application exceptions to HTTP
error responses with JSON body — mapping domain exceptions to proper HTTP status
codes like 403 Forbidden in Quarkus RESTEasy Reactive"

##### grep (keywords) — 16 unique entries of 35 total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-20260517-da2a42 | casehub-work IllegalStateExceptionMapper silently maps IllegalStateException to HTTP 409 | entry | 2 |
| 2 | GE-20260529-636a36 | JAX-RS @Provider exception mapper bypassed when exception extends type caught by generic handler | entry | 2 |
| 3 | GE-20260530-3562b0 | ExceptionMapper<IllegalArgumentException> does not catch compact constructor violations during Jackson deserialization | entry | 2 |
| 4 | GE-20260601-13fc26 | JAX-RS §4.2.4: IOException from message body reader bypasses all exception mappers | entry | 2 |
| 5 | GE-20260612-b20b51 | casehub-engine YamlCaseHub requires CDI-injected ObjectMapper — NPE in plain JUnit | entry | 0 |
| 6 | (framework) | Quarkus Flow reference (CNCF Serverless Workflow) | framework | 0 |
| 7 | GE-0110 | IntelliJ localInspection requires implementationClass and explicit shortName | entry | 0 |
| 8 | GE-20260525-00cbde | Remove JAX-RS coupling from @ApplicationScoped service beans using inner static domain exceptions | entry | 2 |
| 9 | GE-20260526-a08a81 | MicroProfile REST Client throws WebApplicationException on non-2xx when return type is Response | entry | 1 |
| 10 | GE-20260531-dd44a2 | @Path("/") root resource captures all paths in RESTEasy Reactive | entry | 0 |
| 11 | GE-20260605-6aa860 | GitHub Actions GITHUB_TOKEN gets packages:read regardless of workflow permissions | entry | 0 |
| 12 | GE-20260607-3747a1 | Maven Central 403 from GitHub Actions — force BOM download before auth | entry | 0 |
| 13 | GE-20260414-c18090 | Quarkus 3.32+ auto-registers @Provider classes for native reflection | entry | 1 |
| 14 | GE-20260424-883890 | Quarkus JAX-RS duplicate endpoint error from stale agent resource file | entry | 0 |
| 15 | GE-20260428-13d4ff | Maven child POM missing snapshotRepository causes 403 deploy | entry | 0 |
| 16 | GE-20260501-bc4553 | Probe delete permission with nonexistent resource ID — 404 authorised, 403 denied | entry | 1 |

**Precision:** 8/16 relevant (50%), 5/16 highly relevant (31%)
**Note:** 35 total matches include 13 label files (exception-mapper.md,
http-status.md, jax-rs.md, etc.) and 6 framework/tools entries that matched
on "403" in unrelated contexts (Maven auth, GitHub Actions, fork permissions).

##### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260607-3747a1 | Maven Central 403 from GitHub Actions | 0.63 | 0 |
| 2 | GE-20260501-bc4553 | Probe delete permission 404/403 | 0.62 | 0 |
| 3 | GE-20260428-13d4ff | Maven child POM 403 deploy | 0.61 | 0 |
| 4 | GE-20260530-b68c00 | Maven caches GitHub Packages 401 | 0.61 | 0 |
| 5 | GE-20260501-d9c2d7 | GITHUB_TOKEN returns 403 on cross-repo dispatch | 0.60 | 0 |
| 6 | GE-0126 | Quarkus WebAuthn random session key, REST 401 | 0.59 | 0 |
| 7 | GE-20260528-936918 | Response.Status.UNPROCESSABLE_ENTITY doesn't exist in Jakarta EE 9 JAX-RS | 0.58 | 1 |
| 8 | GE-20260604-037c42 | gh repo fork fails 403 on empty repos | 0.58 | 0 |

**Precision:** 1/8 relevant (13%), 0/8 highly relevant (0%)

##### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-20260601-13fc26 | JAX-RS §4.2.4: IOException bypasses all exception mappers | 0.74 | 2 |
| 2 | GE-20260417-691885 | @WrapBusinessError converts @Tool exceptions to isError:true MCP responses | 0.72 | 1 |
| 3 | GE-20260517-da2a42 | casehub-work IllegalStateExceptionMapper silently maps to HTTP 409 | 0.72 | 2 |
| 4 | GE-20260529-636a36 | JAX-RS @Provider exception mapper bypassed by generic resource handler | 0.71 | 2 |
| 5 | GE-20260607-97e543 | WireMock: omitting happy-path stub makes wrong exception type | 0.71 | 0 |
| 6 | GE-20260511-3e5a75 | SLA enforcement: set candidateGroups + claimDeadline only | 0.70 | 0 |
| 7 | GE-20260525-00cbde | Remove JAX-RS coupling using inner static domain exception classes | 0.70 | 1 |
| 8 | GE-20260622-580d45 | quarkus.security.auth.enabled-in-dev-mode=false | 0.70 | 0 |

**Precision:** 5/8 relevant (63%), 3/8 highly relevant (38%)

##### Analysis

**Overlap:** 4 entries found by multiple methods:
- grep + gardenSearch-NL: GE-20260517-da2a42 (score 2 — transitive
  IllegalStateExceptionMapper silently overrides status codes),
  GE-20260529-636a36 (score 2 — @Provider mapper bypassed by generic handler),
  GE-20260601-13fc26 (score 2 — IOException bypasses all mappers),
  GE-20260525-00cbde (score 2 for grep, score 1 for gardenSearch-NL — inner
  static domain exceptions as alternative to ExceptionMapper)

**Unique to grep:** 1 score-2 entry: GE-20260530-3562b0
(ExceptionMapper<IllegalArgumentException> does not catch compact constructor
violations during Jackson deserialization — exception mapper type hierarchy
matching limitation). Plus 3 score-1 entries and 8 score-0 entries.

**Unique to gardenSearch (keywords):** 0 score-2 entries. 1 score-1 entry:
GE-20260528-936918 (Response.Status.UNPROCESSABLE_ENTITY doesn't exist in
Jakarta EE 9 — tangentially relevant to HTTP status code enum usage). Plus
7 score-0 entries — the embedding model latched onto "403" and "Forbidden" as
the dominant signals, matching Maven auth, GitHub Actions, and fork permission
entries that happen to involve HTTP 403 in non-JAX-RS contexts.

**Unique to gardenSearch (NL):** 0 score-2 entries. 1 score-1 entry:
GE-20260417-691885 (@WrapBusinessError for MCP error responses — a related
error-mapping pattern in a different context). Plus 3 score-0 entries.

**Machine relevance vs judgment correlation:**
- gardenSearch-KW: relevance scores (0.58-0.63) are tightly clustered. The
  top result (0.63, Maven Central 403) scored 0. The one score-1 entry (0.58)
  sits at rank 7 — indistinguishable from the noise. The embedding model
  treated "403 Forbidden" as the dominant signal and matched every entry
  mentioning HTTP 403, regardless of context (Maven auth, GitHub CI,
  permission probing). "ExceptionMapper" as a Java class name had no
  semantic weight.
- gardenSearch-NL: good calibration. The top three entries (0.74, 0.72, 0.72)
  all scored 2 or 1. The three score-0 entries (0.71, 0.70, 0.70) cluster at
  the bottom. The NL query's explicit framing of "JAX-RS ExceptionMapper that
  converts application exceptions to HTTP error responses" correctly activated
  entries about ExceptionMapper gotchas rather than generic 403 responses.

**Verdict:** Advantage grep.

- **Precision:** gardenSearch-NL (63% relevant, 38% highly relevant) outperforms
  grep (50% relevant, 31% highly relevant) on precision metrics, but grep
  found more total score-2 entries (5 vs 3 in NL). gardenSearch-KW (13%
  relevant, 0% highly relevant) was a failure.
- **Discovery:** grep surfaced 1 unique score-2 find (ExceptionMapper type
  hierarchy matching limitation). gardenSearch-NL surfaced 0 unique score-2
  finds — all its score-2 entries were also found by grep. The 4 shared
  score-2 entries demonstrate strong domain overlap between the methods for
  this specific domain.
- **Why grep has the advantage:** while gardenSearch-NL achieved higher
  precision per-result, grep surfaced a wider set of ExceptionMapper gotchas
  because `ExceptionMapper` as a substring matches entries that use the exact
  class name. The domain has strong keyword-to-concept correlation — entries
  about ExceptionMapper behavior literally contain the word "ExceptionMapper."
  gardenSearch-NL found the same core entries but in fewer results, and its
  one unique score-1 find (MCP @WrapBusinessError) represents a cross-domain
  connection that grep could not make. For spec review, grep's broader recall
  (5 score-2 entries vs 3) matters more than per-result precision.

#### Spec-Level Assessment

**Gotchas surfaced by grep but not gardenSearch:**

1. **8 LangChain4j API surface entries** (Domain 2, all score 2) — grep surfaced
   a deep cluster of ChatModel/StreamingChatModel API gotchas that gardenSearch
   missed entirely:
   - **doChat() extension point** (GE-20260522-3e2589, GE-20260525-fd4868,
     GE-20260618-8526c8): three entries documenting that `doChat(ChatRequest)` is
     the override point — not `chat()`. The spec correctly uses `doChat()` for
     both `AgentProviderChatModel` and test doubles, but these entries validate
     the design and warn about the lambda stubbing trap.
   - **Diamond inheritance** (GE-20260617-d18081): implementing both `ChatModel`
     and `StreamingChatModel` requires explicit overrides for all four shared
     default methods. The spec designs `AgentProviderChatModel implements
     ChatModel, StreamingChatModel` — this gotcha would have affected the
     implementation directly.
   - **Ollama @Default ChatModel clash** (GE-20260614-337397): quarkus-langchain4j
     Ollama registers a `@Default ChatModel` that clashes with `@DefaultBean`
     fallback beans. The spec's `AgentProviderChatModel` is `@DefaultBean
     @Priority(10)` — this entry confirms the tier design but also warns about
     the Ollama extension's non-standard registration.
   - **ResponseFormat.JSON** (GE-20260528-e9564b): the Anthropic provider throws
     `UnsupportedFeatureException` for JSON format without schema. The spec
     explicitly addresses this with prompt engineering, validating the design
     decision.

2. **@Alternative @Priority stubs in Instance<T>** (Domain 1, GE-20260604-2f0889,
   score 2) — `@Alternative @Priority(N)` stubs appear in `@All Instance<T>`
   alongside the original bean, not as replacements. The spec's
   `ChatModelAgentProvider` filters `Instance<ChatModel>` at `@PostConstruct` —
   this entry warns that `@Alternative` test stubs would be visible in the
   unfiltered Instance, potentially breaking the filtering logic in test contexts.

3. **Mockito Instance<T> exhaustion** (Domain 3, GE-20260505-43a73b, score 2) —
   `thenReturn(stream)` on a mocked `Instance<T>` exhausts the iterator on the
   second call. The spec's `ChatModelAgentProvider` uses `Instance<ChatModel>`
   with stream-based filtering — this testing gotcha would affect every unit test
   for the filtering logic.

4. **ExceptionMapper type hierarchy matching** (Domain 4, GE-20260530-3562b0,
   score 2) — JAX-RS ExceptionMapper matching walks the thrown type hierarchy
   only, never inspecting `getCause()`. If `MissingTenancyException` were wrapped
   by another exception during CDI interception, the mapper would not fire.

**Gotchas surfaced by gardenSearch but not grep:**

1. **@LookupIfProperty** (Domain 3, GE-20260626-c94109, score 2, NL only) — a
   Quarkus-native alternative to the spec's `@PostConstruct` filtering pattern
   for conditional CDI bean activation. The spec uses `Instance<ChatModel>` with
   manual filtering at `@PostConstruct` to gracefully deactivate
   `ChatModelAgentProvider` when no real ChatModel exists. `@LookupIfProperty`
   provides a cleaner, declarative approach where the bean is genuinely
   non-resolvable (not just inert) when a config property is absent. This could
   simplify the spec's graceful deactivation design.

2. **Library JAR @ApplicationScoped overrides @DefaultBean** (Domain 3,
   GE-20260626-a37306, score 2, NL only) — adding a library JAR that contains
   `@ApplicationScoped` beans can silently override `@DefaultBean` alternatives
   in the consuming application. The spec's `agent-langchain4j` module contains
   `@DefaultBean @Priority(10)` and `@Alternative @Priority(1)` beans — this
   entry warns that a consumer adding a library JAR with a non-`@DefaultBean`
   `ChatModel` would silently displace the adapter beans, potentially breaking
   the bidirectional bridge without any error.

3. **quarkus-langchain4j-core stalls without provider** (Domain 2,
   GE-20260422-f922f3, score 2, NL only) — the quarkus-langchain4j-core
   extension stalls `@QuarkusTest` augmentation when no model provider is
   configured. The spec's `ChatModelAgentProvider` gracefully deactivates when
   no real ChatModel exists — but this entry warns that the underlying
   quarkus-langchain4j extension itself may stall at augmentation before the
   bean's `@PostConstruct` even runs.

4. **Instance<T>.handles() type incompatibility** (Domain 2, GE-20260519-eb8340,
   score 2, NL only) — `Instance<T>.handles()` returns
   `Iterable<? extends Instance.Handle<T>>`, not `Iterable<InstanceHandle<T>>`.
   The spec's `ChatModelAgentProvider` uses `Instance<ChatModel>` with
   stream-based filtering — this type mismatch gotcha would affect the
   implementation.

5. **@Alternative @Priority globally activates** (Domain 1, GE-20260415-884e48,
   score 2, NL only) — in CDI 4.0/Quarkus, `@Alternative @Priority(n)` globally
   activates the alternative without `selected-alternatives` config. The spec
   uses `@Alternative @Priority(1)` for `ChatModelAgentProvider` — this entry
   confirms the design works but warns about the global activation scope.

**Would these have influenced the design?**

Yes, in both directions:

**grep's contribution — API surface validation:** The 8 LangChain4j API entries
from Domain 2 are the single most valuable cluster in the entire spec review.
They validate the spec's `doChat()` extension point choice, warn about the
diamond inheritance problem when implementing both `ChatModel` and
`StreamingChatModel`, confirm the prompt engineering approach for JSON format,
and document the quarkus-langchain4j Ollama registration behavior that affects
the `@DefaultBean @Priority` tier system. Without these entries, the spec
designer would miss the four-method default override requirement and potentially
hit a compilation error during implementation.

**gardenSearch-NL's contribution — cross-module deployment gotchas:** The
`@LookupIfProperty` entry (GE-20260626-c94109) suggests a cleaner alternative to
the spec's `@PostConstruct` filtering pattern that would simplify the graceful
deactivation design. The library JAR override entry (GE-20260626-a37306) warns
about a deployment scenario the spec doesn't address — a consumer adding a
library with `@ApplicationScoped` ChatModel silently breaking the bridge. The
quarkus-langchain4j augmentation stall (GE-20260422-f922f3) reveals that the
graceful deactivation at CDI runtime may be too late — the extension's build-time
processing can fail before the bean even initializes.

**Method effectiveness for spec review:**

| Domain | Winner | grep precision | gS-KW precision | gS-NL precision | Key finding |
|--------|--------|---------------|-----------------|-----------------|-------------|
| CDI tier coexistence | grep | 81% (31% h.r.) | 13% (13% h.r.) | 75% (38% h.r.) | @DefaultBean/@Alternative annotation names are distinctive grep targets |
| ChatModel adaptation | grep | 70% (40% h.r.) | 0% (0% h.r.) | 50% (25% h.r.) | ChatModel/doChat/StreamingChatModel are exact Java names — 8 unique score-2 entries |
| Circular dependency | gardenSearch-NL | 20% (10% h.r.) | 38% (0% h.r.) | 88% (25% h.r.) | "circular dependency" is polysemous (Maven vs CDI); NL disambiguated |
| ExceptionMapper | grep | 50% (31% h.r.) | 13% (0% h.r.) | 63% (38% h.r.) | ExceptionMapper as substring matched exact entries; "403" was polysemous |

**Overall spec review verdict:** grep won 3 domains, gardenSearch-NL won 1.
gardenSearch-KW was a failure across all 4 domains (0-13% highly relevant).

This broad spec strongly favours grep because its core subject matter —
LangChain4j API names, CDI annotation patterns, and JAX-RS class names — consists
of distinctive technical vocabulary that appears verbatim in garden entries.
grep's substring matching excels when the search terms ARE the domain vocabulary.

However, gardenSearch-NL's Domain 3 win surfaced the most architecturally
significant gotcha: `@LookupIfProperty` as a cleaner alternative to the spec's
`@PostConstruct` filtering pattern. This is the kind of cross-domain connection
that grep cannot make — the entry doesn't mention "circular dependency",
"ChatModel", or "Instance filter" as substrings, but it describes the exact
CDI pattern the spec needs.

For broad cross-module specs, the two methods are complementary:
- **grep** finds entries about specific APIs and class names the spec references
- **gardenSearch-NL** finds entries about architectural patterns described in
  different vocabulary than the spec uses

The critical gap: **gardenSearch-KW was catastrophic across all 4 domains.**
CDI annotation names, Java interface names, and HTTP status codes are proper
nouns and technical identifiers with no pre-trained semantic weight in the
embedding model. Keyword-based embedding search cannot match what substring
matching finds trivially.

## Summary

### Aggregate Metrics

Across 14 scenarios (6 issues + 8 spec domains):

| Method | Scenarios | Avg Precision (>=1) | Avg Precision (=2) | Total Unique Score=2 |
|--------|-----------|--------------------|--------------------|---------------------|
| grep (keywords) | 14 | 65% | 30% | 38 |
| gardenSearch (keywords) | 14 | 32% | 14% | 4 |
| gardenSearch (NL) | 14 | 62% | 30% | 13 |

grep and gardenSearch-NL are statistically comparable on average precision.
gardenSearch-KW is dramatically worse — its average precision is less than half
either alternative. grep dominates on unique score-2 discovery (38 entries found
by grep alone vs 13 by gardenSearch-NL alone), reflecting its ability to surface
deep clusters of API-specific entries that embedding search cannot rank into 8
results.

### Win / Loss / Tie

Pairwise per-scenario verdicts using the methodology's criteria (precision as
primary metric, unique score=2 finds as secondary):

| Comparison | Method A Wins | Method B Wins | Ties |
|------------|--------------|--------------|------|
| grep vs gardenSearch-KW | 12 | 2 | 0 |
| grep vs gardenSearch-NL | 6 | 6 | 2 |
| gardenSearch-KW vs gardenSearch-NL | 2 | 10 | 2 |

grep vs gardenSearch-KW is not competitive — grep wins 12 of 14 scenarios.
gardenSearch-KW's embedding model cannot interpret Java class names, CDI
annotation names, or framework-specific terms as domain vocabulary.

grep vs gardenSearch-NL is evenly split at 6-6-2. This is the central finding:
neither method dominates the other. They win on different query types.

gardenSearch-NL dominates gardenSearch-KW at 10-2-2. Natural language queries
are strictly superior to keyword queries when using embedding-based retrieval.

### Retrieval + Ranking Benefit Analysis

**grep vs gardenSearch-KW** measures the combined effect of switching from
substring matching to embedding similarity AND from unsorted results to ranked
results. These factors are confounded — the comparison shows the total benefit,
not each factor independently.

The data shows the combined retrieval + ranking change is **net negative for
keyword queries**: gardenSearch-KW lost 12 of 14 scenarios against grep. The
embedding model cannot leverage the ranking benefit because the underlying
retrieval fails — when the embedding model does not understand the query terms
semantically, ranking irrelevant results by similarity score produces well-ordered
noise.

**When embedding retrieval helps:** gardenSearch-KW won in Issue 1 (Reactive/async)
and Issue 6 (Testing/CI). Both cases share a trait: the keywords included at
least one unambiguous Quarkus-specific term (`selected-alternatives` in Issue 6,
Vert.x-related concepts in Issue 1) that carries semantic weight in the embedding
space. When keywords happen to align with concepts the embedding model understands,
the ranking benefit adds genuine value by placing the most relevant entries first.

**When embedding retrieval hurts:** gardenSearch-KW scored 0% relevant precision
in 4 scenarios (Issues 3, 5, Spec 2 Domains 2 and 4). In each case, the keywords
were either polysemous (`shadowing`, `stream`, `403`) or domain-specific proper
nouns (`ChatModel`, `LedgerEntry`, `tenancyId`) with no pre-trained semantic
weight. The embedding model matched surface-level word similarity rather than
domain meaning, producing results that were semantically adjacent but topically
irrelevant.

**Confounded factors:** the data cannot separate the ranking benefit from the
retrieval mechanism change. gardenSearch-KW's 8-result cap vs grep's 20-result
cap further confounds: even when gardenSearch-KW finds relevant entries, it
returns fewer total results and may exclude entries that grep's broader window
captures. To isolate the ranking benefit alone, one would need to rank grep's
results by embedding similarity — which this benchmark does not do.

### Query Formulation Benefit Analysis

**gardenSearch-KW vs gardenSearch-NL** cleanly isolates the query formulation
benefit because the retrieval mechanism and ranking are held constant.

Natural language queries consistently outperform keyword queries: gardenSearch-NL
won 10 of 14 scenarios, with gardenSearch-KW winning only 2 (Issue 6 and
Spec 1 Domain 4). The average precision gap is 30 percentage points for
relevant results (62% vs 32%) and 16 percentage points for highly relevant
results (30% vs 14%).

**When NL wins:** NL queries excel when the problem involves a concept or pattern
described in natural language rather than a specific API name. Issues 1 (blocking
operation on IO thread), 3 (JPA JOINED inheritance field shadowing), and 4
(CloudEvent CDI event bus) all describe architectural patterns. The NL query's
explicit framing gives the embedding model enough semantic context to locate
entries about the pattern, even when those entries use different vocabulary.

**When keywords win:** gardenSearch-KW won Issue 6, where `selected-alternatives`
is an unambiguous Quarkus config property name that carries strong semantic signal
in the embedding space. Keywords also tied in Spec 1 Domains 1 and 2, where both
methods struggled equally. The keyword advantage appears when search terms are
highly specific technical terms with exactly one meaning — the same characteristic
that makes grep excel.

**The catastrophic mismatch pattern:** in 6 of 14 scenarios, gardenSearch-KW
achieved 0-13% relevant precision while gardenSearch-NL achieved 25-88%. The
common cause: keywords that are Java class names (`ChatModel`, `LedgerEntry`),
annotation names (`@DefaultBean`, `@Priority`), or polysemous terms (`stream`,
`shadowing`, `tier`) produce embedding vectors that do not align with garden
entries about those concepts. NL queries describing the same concepts in
natural language produce vectors that do align.

**Implication:** if gardenSearch is used, the query formulation matters more than
any other factor. A well-crafted NL query transforms gardenSearch from worse-than-
grep to competitive-with-grep. A keyword query makes it catastrophically worse.

### Machine Relevance Calibration Summary

Across all gardenSearch results (110 gardenSearch-KW results, 109 gardenSearch-NL
results after deduplication), the correlation between machine relevance scores
(0.0-1.0) and human judgment scores (0/1/2) is weak for gardenSearch-KW and
moderate for gardenSearch-NL.

**gardenSearch-KW calibration:**

| Judgment | Count | Relevance Range | Median |
|----------|-------|-----------------|--------|
| 0        | 74    | 0.53-0.70       | ~0.59  |
| 1        | 19    | 0.56-0.71       | ~0.61  |
| 2        | 17    | 0.56-0.75       | ~0.61  |

The ranges overlap almost completely. A relevance score of 0.60 is equally likely
to be judgment 0, 1, or 2. The only useful threshold: entries above 0.70 are
disproportionately score-2 (the Spec 1 Domain 3 entries at 0.72-0.75, and
Issue 6 at 0.72), but this threshold excludes most score-2 entries. Machine
relevance provides no useful filtering signal for keyword queries.

**gardenSearch-NL calibration:**

| Judgment | Count | Relevance Range | Median |
|----------|-------|-----------------|--------|
| 0        | 42    | 0.59-0.73       | ~0.65  |
| 1        | 35    | 0.59-0.77       | ~0.71  |
| 2        | 33    | 0.63-0.80       | ~0.73  |

Better separation than gardenSearch-KW, but still substantial overlap. Approximate
thresholds:

- **>= 0.73:** predominantly score 2 (directly relevant). 20 of 33 score-2 entries
  sit above this threshold, but so do 7 score-1 and 1 score-0 entries.
- **0.67-0.72:** mixed zone. Score 1 and score 2 entries are equally likely. Score 0
  entries appear but less frequently.
- **< 0.67:** predominantly score 0 or low score 1. Only 5 of 33 score-2 entries fall
  below 0.67; 30 of 42 score-0 entries sit here.

**Practical implication:** for gardenSearch-NL, a relevance threshold of ~0.67
would filter out most noise (score-0) while retaining most relevant content. For
gardenSearch-KW, no threshold provides useful discrimination. This further supports
the conclusion that NL queries produce better-calibrated similarity scores than
keyword queries.

### Search Surface Asymmetry Impact

grep searches 6,672 `.md` files; gardenSearch indexes 1,960 entries. The 3.4x
larger grep surface includes 2,568 label files and 1,913 summary files that
gardenSearch never sees.

**Issue evaluations (6 scenarios, 120 scored grep results):**

All 120 scored grep results across the 6 issues were `_summaries/` files — 100%
of grep's issue-evaluation results came from paths that gardenSearch does not
index. This does not mean the results were noise: summaries are condensed versions
of actual garden entries, and many scored 1 or 2 (relevant or highly relevant).
But it means the two methods searched non-overlapping file sets in every issue
scenario. When both methods found the same entry, they matched it through
different files (grep via `_summaries/GE-*.md`, gardenSearch via the entry itself).

**Spec evaluations (8 domains, varying grep result counts):**

Label and INDEX files constituted a significant fraction of grep's raw matches
in spec scenarios:
- Spec 1 Domain 3: 8 of 9 total matches were labels/INDEX (89%)
- Spec 1 Domain 4: 8 of 10 total matches were labels/INDEX (80%)
- Spec 1 Domain 1: 12 of 24 total matches were labels/INDEX (50%)

After deduplication and filtering, these label/INDEX files contributed zero
relevant results — they matched keywords but contained no substantive content
(label files are keyword lists, INDEX files are directory manifests). They
inflated grep's raw match counts without improving precision on scored results.

**Noise attribution:** across all scenarios, the label/summary/INDEX files
contributed to grep's inflated total match counts (160-815 per scenario) but
did not systematically degrade precision on the scored first-20 results. The
first-20 window happened to select `_summaries/` files in the issue scenarios
because `git grep` returned them before other files. The noise from broader
search surface manifests primarily in the total match counts (which skills
must wade through or truncate), not in the first-20 precision.

**Key finding:** excluding `_summaries/` and `labels/` from grep would not
materially change the first-20 precision in this benchmark because the first-20
were predominantly summaries that scored well. However, it would dramatically
reduce total match counts (from hundreds to tens in most scenarios), improving
the signal-to-noise ratio for skills that process more than the first 20 results.

### Recommendations

#### 1. gardenSearch Migration: Proceed, but Conditionally

The data supports migrating to gardenSearch for **natural language queries only**.
gardenSearch-NL matches grep's aggregate precision (62% vs 65%) and provides
complementary discovery — 13 unique score-2 entries that grep missed entirely,
many representing cross-domain architectural insights that substring matching
cannot surface.

**Do not use gardenSearch with keyword queries.** gardenSearch-KW was catastrophic
across 12 of 14 scenarios. Passing `work-start`'s keyword strings directly to
gardenSearch produces worse results than the current grep approach. The embedding
model cannot interpret Java class names, CDI annotation names, or framework-
specific identifiers as domain vocabulary.

**Migration conditions:**

1. **NL query derivation is mandatory.** Any skill calling gardenSearch must craft
   a natural language description of the problem or concept, not pass keywords.
   This is the single largest determinant of gardenSearch quality.

2. **grep remains necessary for API-name lookups.** When the query involves
   specific class names (`ConcurrentHashMap`, `ChatModel`, `ExceptionMapper`) or
   annotation patterns (`@DefaultBean`, `@Alternative @Priority`), grep finds
   deep clusters of entries that gardenSearch misses entirely. grep surfaced 38
   unique score-2 entries vs gardenSearch-NL's 13 — most of grep's advantage
   comes from these API-name clusters.

3. **Consider a two-method approach.** The ideal strategy uses both: gardenSearch-NL
   for concept/pattern queries (the problem description in natural language) and
   grep for API-name queries (specific class names, annotation names, config
   property names extracted from the issue). The two methods are complementary —
   their wins do not overlap.

#### 2. Improve work-start grep Pathspec

Independent of the gardenSearch migration, `work-start` Step 3b's grep pathspec
should exclude `_summaries/` and `labels/` directories:

```
git -C ~/.hortora/garden grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md' ':!_summaries/' ':!labels/'
```

**Rationale:** in this benchmark, 100% of issue-scenario grep results came from
`_summaries/` files — condensed versions of entries that also exist as standalone
files. Matching both the summary and the entry doubles the result count without
adding information. Label files are keyword lists that match broadly but contain
no substantive content.

**Expected impact:** total match counts would drop substantially (the 2,568 label
files and 1,913 summary files constitute 67% of the grep search surface). The
first-20 precision might change unpredictably — in some scenarios, removing
summaries would surface entries that are currently pushed below position 20 by
summary duplicates; in others, it would surface lower-quality entries.

**Risk:** some entries may exist only as summaries (no standalone entry file). A
pathspec that excludes `_summaries/` would miss these entirely. Verify that
`_summaries/` files are always accompanied by a corresponding entry before
applying this exclusion.

#### 3. Additional Insights

**gardenSearch-KW should not be exposed as a tool parameter.** The current
`gardenSearch` MCP tool accepts a free-form query string. If skills pass keywords,
results are catastrophic. Either: (a) rename the parameter to signal that natural
language is expected (e.g., `description` instead of `query`), or (b) add query
guidance in the tool's description specifying that natural language problem
descriptions produce better results than keyword lists.

**The 8-result cap is not limiting gardenSearch.** In scenarios where
gardenSearch-NL won, it found 3-4 highly relevant entries in its 8 results —
sufficient for informing the work. The cap becomes limiting only for domains
with deep coverage (Thread safety: grep found 13 highly relevant entries in its
first 20). Increasing the cap beyond 8 might help for these domains but would
also increase noise in scenarios where the embedding model struggles.

**Dense-only mode performed adequately for NL queries.** This benchmark used
dense-only retrieval (no SPLADE sparse embeddings, no cross-encoder reranking).
The strong showing of gardenSearch-NL suggests that `nomic-embed-text` dense
embeddings are sufficient for natural language queries against this corpus.
Hybrid search (Phase 2) may improve gardenSearch-KW performance by adding keyword
matching back into the retrieval pipeline — this is worth testing, as it could
narrow the gap between keyword and NL queries.

**The evaluator bias compounds for NL queries.** gardenSearch-NL's strong
performance must be interpreted in light of the bias disclosure: Claude derived
the NL queries, the embedding model was selected for Claude's consumption, and
Claude evaluated the results. The 6-6-2 split against grep — an unbiased
substring matcher — may be optimistic for gardenSearch-NL's value to non-Claude
consumers. Validating with human evaluation would strengthen the finding.
