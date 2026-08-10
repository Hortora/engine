# Federation Chain Walk

Refs #5

## Overview

Implement upstream chain walk and peer fan-out in the engine's search path. The engine reads its garden's federation config from SCHEMA.md, searches its own Qdrant first, then walks upstream to parent garden services via HTTP if results are insufficient, and queries peer gardens in parallel for supplementary results. Results are merged with provenance labels and priority ordering.

This spec covers the chain-walk algorithm only. Schema-driven document models, multi-source local retrieval, and field declaration engines are out of scope — they are separate concerns filed as future work.

## Context from Prior Specs

The RAG redesign spec (`hortora/spec/docs/design/2026-04-07`) defines federation as **garden-level** relationships:
- **Canonical** — root authority, no upstream, unaware of children
- **Child** — extends a parent with strict non-duplication, walks upstream for retrieval
- **Peer** — equals sharing knowledge, searched in parallel, overlaps acceptable

The retrieval service spec (`hortora/spec/docs/design/2026-04-16-garden-retrieval-service.md`) says: "Child garden's retrieval service searches its local Qdrant first. On upstream query, HTTP call to parent's search API. Parent's index is always current — no sync, no stale local copies."

ADR-0005 confirms: "Per-garden Qdrant: federation is service-to-service HTTP, not git-repo cloning."

CLAUDE.md: "Federation in this service — canonical/child chain walk is Hortora-specific logic, lives here not in any shared module."

## Federation Config in SCHEMA.md

The engine reads its garden's SCHEMA.md at startup. Only the federation block is relevant to this spec — field declarations are out of scope.

```yaml
---
schema-version: 2.0.0
id: my-garden
id-prefix: PE

federation:
  role: child                   # canonical | child | peer
  relevance-threshold: 0.7     # below this, trigger upstream/peer queries
  max-depth: 5                  # max upstream hops (defensive bound)
  upstream:
    - url: https://api.hortora.io/jvm
      id-prefix: GE
      search-order: fallback    # fallback | always
    - url: https://enterprise.internal/garden
      id-prefix: PE
      search-order: fallback
  peers:
    - url: http://localhost:8091
      id-prefix: RE
---
```

**`search-order` semantics:**
- `fallback` — only query this upstream if own results are all below the relevance threshold or fewer than the requested limit
- `always` — always include this upstream's results, merged with priority ordering

**`relevance-threshold`** — a result scoring below this threshold is treated as "no match" for the purpose of triggering upstream/peer queries. Default: 0.7. This is a starting point — the threshold should be tuned empirically per embedding model. `nomic-embed-text` cosine similarity scores tend to range 0.3–0.9 for related content. Log own-result score distributions and set the threshold at a percentile that captures "strong matches" for your corpus.

**`max-depth`** — maximum number of upstream hops. Default: 5. Prevents pathological-but-legitimate deep chains from creating unacceptable latency (5 hops × 5s timeout = 25s worst case). The visited-set header prevents cycles independently; the depth limit bounds latency.

A canonical garden has `upstream: []` and `peers: []` — it never walks upstream. It still serves as an upstream target for child gardens calling its `/search` endpoint.

### Parse Failure Mode

The engine **fails fast at startup** if SCHEMA.md contains invalid federation config:
- Malformed YAML → startup failure with parse error
- Invalid `role:` value (not `canonical`, `child`, or `peer`) → startup failure
- Syntactically invalid upstream/peer URL → startup failure
- Missing `id:` or `id-prefix:` → startup failure

If no SCHEMA.md exists or no `federation:` block is present, the engine operates as a canonical garden with no upstream and no peers — identical to current behaviour. `GardenConfig` provides defaults for `id` (`"garden"`) and `id-prefix` (`"GE"`).

### Version Coupling

Federated engines must agree on the `/search` response JSON shape. An engine returning `SearchResult` with `source`/`sourcePrefix` fields talking to a peer that doesn't have these fields will produce deserialization mismatches. All engines in a federation should run the same API version. This is acceptable for now — this is a single-developer platform with no external consumers. When federation goes multi-team, an API version header or content negotiation should be added.

## Federation Config Model

```java
record FederationConfig(
    String gardenId,                      // from SCHEMA.md id:
    String idPrefix,                      // from SCHEMA.md id-prefix:
    String role,                          // "canonical", "child", "peer"
    double relevanceThreshold,            // default 0.7
    int maxDepth,                         // default 5
    List<UpstreamRef> upstream,           // parent chain, walked in order
    List<PeerRef> peers                   // queried in parallel
) {
    record UpstreamRef(String url, String idPrefix, String searchOrder) {}
    record PeerRef(String url, String idPrefix) {}
}
```

Parsed from SCHEMA.md at startup by `FederationConfigParser`. Validation is strict — any invalid config fails startup.

When no SCHEMA.md or no `federation:` block exists, a default config is constructed from `GardenConfig` properties: `gardenId` from `hortora.garden.id`, `idPrefix` from `hortora.garden.id-prefix`, `role` = `"canonical"`, empty upstream and peers.

## Provenance Model

Results always carry their **originating garden ID** — never a relative label like `"own"`. The `source` field holds the garden's `id` from its SCHEMA.md (e.g. `"jvm-garden"`, `"enterprise-garden"`, `"my-garden"`).

This is critical for recursive walks. When my garden (PE, id=`"my-garden"`) calls enterprise parent (EP, id=`"enterprise-garden"`), and EP itself walks upstream to community canonical (GE, id=`"jvm-garden"`), EP returns results with mixed provenance:
- EP's own results: `source="enterprise-garden"`, `sourcePrefix="EP"`
- Results EP got from GE: `source="jvm-garden"`, `sourcePrefix="GE"`

My ChainWalker receives these results with accurate provenance from the entire chain — no re-tagging needed. Each engine tags its own Qdrant results with its garden ID, and passes through upstream/peer results with their provenance unchanged.

**`"own"` is a presentation label, not a data value.** The MCP tool output uses `[own]` as a display label for results whose `source` matches the engine's own `gardenId`. The REST API always returns the absolute garden ID.

**Tagging rule:**
1. Own Qdrant results → `source` = own `gardenId`, `sourcePrefix` = own `idPrefix`
2. Upstream/peer results → pass through `source` and `sourcePrefix` from the upstream response unchanged
3. MCP output → replace `source == ownGardenId` with `[own]` for display; all others labelled `[sourcePrefix]`

## Chain-Walk Algorithm

The search path in `SearchResource.doSearch()` is extended with federation:

```
1. Search own Qdrant (existing behaviour)
     ↓
2. Evaluate results: are there >= limit results above relevance threshold?
     ↓
3. If yes → skip upstream and peers, return own results with provenance
   If no → continue to step 4
     ↓
4. For each upstream in declared order (up to max-depth):
     - If search-order is "always", OR
       if search-order is "fallback" AND own results are insufficient:
         → HTTP GET to upstream's /search endpoint
         → Pass through provenance from upstream response unchanged
         → If sufficient results now exceed threshold, stop walking (short-circuit)
     - If current depth >= max-depth, stop walking
     ↓
5. If results are still insufficient after upstream walk:
     → Query all peers in parallel (via ManagedExecutor)
     → Pass through provenance from peer responses unchanged
     ↓
6. Merge all results:
     - Deduplicate by entry ID (same entry from parent chain shouldn't appear twice)
     - Priority ordering: own > parent > grandparent > peer
     - Provenance already correct — no re-tagging
     ↓
7. Return merged results up to limit
```

**Limit semantics:** `limit` applies to the **final merged output**. Each upstream/peer call passes the same `limit` to avoid over-fetching. The merge step may receive more than `limit` results total (own + upstream + peers) and truncates to `limit` after priority ordering.

**Sufficiency test:** own results are "sufficient" when there are at least `limit` results with relevance scores above the threshold. When sufficient, neither upstream walk nor peer fan-out fires.

### Upstream Walk Detail

The upstream list is walked **sequentially in declared order**. This is the chain — my garden → enterprise parent → community canonical. Each step is an HTTP call to the parent's `/search` endpoint with the same query and filters.

When an upstream returns results above the threshold, the walk **short-circuits** — no need to query further upstream. The closest parent with good results wins. If no parent has good results, the walk exhausts the chain (up to max-depth) and returns whatever was found.

### Peer Fan-Out Detail

Peers are searched **in parallel** only when own + upstream results are still insufficient. Uses `ManagedExecutor` (MicroProfile Context Propagation, CDI-injectable) with `invokeAll(peerCallables, timeout, unit)` — parallel execution with the configured timeout. CDI context propagation is handled automatically.

Peer results are supplementary — they augment, never replace own or upstream results. Peer results carry their original provenance and are appended after own/upstream results in the merged output.

### Cycle Detection

Each search request carries a `X-Federation-Visited` header — a comma-separated set of garden IDs. When the engine receives a search request:

1. Check if own garden ID is in the visited set → if yes, return empty results (cycle detected)
2. Check if the visited set has >= max-depth entries → if yes, return empty results (depth exceeded)
3. Add own garden ID to the visited set
4. Pass the updated visited set in the `X-Federation-Visited` header on all upstream and peer calls

This handles:
- Direct cycles: A → B → A
- Indirect cycles: A → B → C → A
- Peer cycles: A peers with B, B peers with A
- Pathological depth: A → B → C → D → ... (bounded by max-depth)

### Timeout and Resilience

Each upstream/peer HTTP call has a configurable read timeout (default 5 seconds), set via `QuarkusRestClientBuilder.readTimeout()`.

If a remote garden is unreachable or times out:
- Log a warning with the garden URL and error
- Continue with available results
- Federation degrades gracefully — a dead parent doesn't break local search

No circuit breaker in phase 1. The REST client timeout provides per-call resilience. A full circuit breaker framework (`quarkus-smallrye-fault-tolerance`) can be added when real deployment failure patterns are observed — it's a separate dependency and concern.

## Remote Garden Client

A plain JAX-RS interface (no `@RegisterRestClient` — clients are created programmatically for N upstream/peer URLs):

```java
@Path("/search")
@Consumes(MediaType.APPLICATION_JSON)
interface RemoteGardenClient {
    @GET
    List<SearchResult> search(
        @QueryParam("q") String query,
        @QueryParam("domain") List<String> domains,
        @QueryParam("limit") int limit,
        @HeaderParam("X-Federation-Visited") String visited
    );
}
```

Clients are created at startup from the federation config:

```java
RemoteGardenClient client = QuarkusRestClientBuilder.newBuilder()
    .baseUri(URI.create(upstreamRef.url()))
    .readTimeout(5, TimeUnit.SECONDS)
    .build(RemoteGardenClient.class);
```

Stored in a `Map<String, RemoteGardenClient>` keyed by URL, managed by `ChainWalker`.

## CDI Wiring

```
GardenConfig
  ├── path()          — garden directory path
  ├── id()            — @WithDefault("garden") — garden identity
  ├── idPrefix()      — @WithDefault("GE") — id prefix
  └── schemaPath()    — @WithDefault("${hortora.garden.path}/SCHEMA.md")

FederationConfigParser (@ApplicationScoped)
  ├── injects GardenConfig
  ├── @Produces FederationConfig
  │     reads SCHEMA.md at startup → parse federation: block
  │     if no SCHEMA.md or no federation: block → default canonical config from GardenConfig
  │     if invalid config → throw, fail startup
  └── FederationConfig is a normal CDI bean (produced, injectable)

ChainWalker (@ApplicationScoped)
  ├── injects FederationConfig
  ├── injects ManagedExecutor (for peer fan-out)
  ├── @PostConstruct: create RemoteGardenClient instances from upstream/peer URLs
  │     store in Map<String, RemoteGardenClient>
  └── walk(query, domains, limit, ownResults) → List<SearchResult>
        if no upstream and no peers → return empty immediately
        run algorithm steps 4-6

SearchResource
  ├── injects ChainWalker
  └── doSearch():
        1. own Qdrant search (existing)
        2. tag own results with gardenId/idPrefix from FederationConfig
        3. federatedResults = chainWalker.walk(query, domains, limit, ownResults)
        4. merge own + federated, dedup, priority order, truncate to limit
```

## Changes to SearchResult

`SearchResult` gains provenance fields:

```java
public record SearchResult(
    String title,
    String domain,
    String type,
    int score,
    String body,
    double relevance,
    String source,          // NEW: originating garden ID (always populated)
    String sourcePrefix     // NEW: id prefix (always populated, e.g. "GE", "PE")
) {}
```

`source` and `sourcePrefix` are always populated with the originating garden's identity. For own results: `source` = `FederationConfig.gardenId()`, `sourcePrefix` = `FederationConfig.idPrefix()`. For upstream/peer results: values pass through from the remote response.

## Changes to GardenEntry

**No changes.** `GardenEntry` remains a typed record. Federation is about garden-to-garden relationships, not entry-to-entry relationships.

## Changes to GardenConfig

```java
@ConfigMapping(prefix = "hortora.garden")
public interface GardenConfig {

    @WithDefault("${user.home}/.hortora/garden")
    Path path();

    @WithDefault("garden")
    String id();

    @WithDefault("GE")
    String idPrefix();

    @WithDefault("${hortora.garden.path}/SCHEMA.md")
    Path schemaPath();
}
```

`id()` and `idPrefix()` provide defaults when no SCHEMA.md exists. When SCHEMA.md is present, the `FederationConfigParser` reads `id:` and `id-prefix:` from it and those values take precedence.

## Dependencies

**Explicit addition to pom.xml:**

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-client-jackson</artifactId>
</dependency>
```

`quarkus-rest-client` is already transitively available from `quarkus-rest-jackson`. `quarkus-rest-client-jackson` adds JSON deserialization support for REST client responses. `ManagedExecutor` is available from MicroProfile Context Propagation (transitive via Quarkus).

No other dependencies needed.

## Changes to Existing Code

| Class | Change |
|-------|--------|
| `GardenConfig` | Add `id()`, `idPrefix()`, `schemaPath()` with `@WithDefault` |
| `SearchResource.doSearch()` | Tag own results with provenance. After own search, call `chainWalker.walk()`. Merge results. |
| `SearchResult` | Add `source` and `sourcePrefix` fields (always populated with originating garden ID) |
| `GardenMcpTools` | Display `[own]` for results matching own gardenId; `[sourcePrefix]` for others |

## New Code

| Class | Package | Purpose |
|-------|---------|---------|
| `FederationConfig` | `io.hortora.garden.federation` | Config records (FederationConfig, UpstreamRef, PeerRef) |
| `FederationConfigParser` | `io.hortora.garden.federation` | Parses SCHEMA.md, `@Produces FederationConfig`. Fails fast on invalid config. |
| `RemoteGardenClient` | `io.hortora.garden.federation` | JAX-RS interface for upstream/peer HTTP calls |
| `ChainWalker` | `io.hortora.garden.federation` | `@ApplicationScoped`. Creates clients at `@PostConstruct`. Orchestrates walk + fan-out + merge. |

## REST API Changes

**No new parameters.** The existing `/search?q=&domain=&limit=` endpoint works unchanged. Federation is transparent to the caller — the engine searches upstream and peers internally and returns merged results.

The response shape gains two fields (`source`, `sourcePrefix`) on each result. This is a breaking change to the JSON schema — callers must update to handle the new fields.

**New internal header:** `X-Federation-Visited` is passed between garden services during chain walk. Not exposed to external callers — it's set by the engine when making upstream/peer calls.

## MCP Tool Changes

`garden_search` output format changes to include provenance:

```
## [own] JPA batch insert ordering

<body>

---

## [PE] Hibernate flush timing gotcha

<body>

---

## [GE] Hibernate lifecycle callbacks

<body>
```

Own results (where `source` == own `gardenId`) are labelled `[own]`. All others are labelled with their `sourcePrefix`.

## Testing Strategy

**Unit tests (mock clients injected into ChainWalker):**
- `FederationConfigParser` — parse valid SCHEMA.md, missing federation block (→ default canonical), malformed YAML (→ startup failure), invalid role (→ startup failure), invalid URL (→ startup failure)
- `ChainWalker` with mock `RemoteGardenClient` instances:
  - Fallback triggers when own results are below threshold
  - Fallback skipped when own results are sufficient
  - Always-mode always queries upstream
  - Short-circuit when upstream returns strong matches
  - Peer fan-out triggers only when own + upstream are insufficient
  - Cycle detection via visited set (return empty on cycle)
  - Depth limit exceeded → stop walking
  - Priority ordering in merged results (own > parent > grandparent > peer)
  - Deduplication by entry ID
  - Timeout on upstream → continue with available results
  - No federation config → empty result from walk
  - **Recursive provenance:** upstream returns results with mixed `source` values (its own + its upstream) → all pass through unchanged, no re-tagging

**Integration tests (WireMock for upstream/peer HTTP):**
- Child searches, WireMock upstream receives the query with correct parameters including `X-Federation-Visited` header, results merge correctly with provenance
- Cycle detection: configure A → B → A via WireMock, verify no infinite loop
- Timeout: WireMock delay exceeds timeout, verify child returns own results within acceptable latency
- Recursive provenance: WireMock upstream returns results with mixed source values, verify they pass through to the final response unchanged

**No changes to existing tests.** When no SCHEMA.md or no `federation:` block exists, the engine behaves identically to current behaviour.

## Future Work (Out of Scope)

These are separate concerns that may be filed as their own issues:

- **Schema-driven document model** — replace `GardenEntry` with a generic document type validated against schema-declared field declarations. Motivated when a second garden schema arrives.
- **Multi-source local retrieval** — multiple local folders/Qdrant instances configured as sources. Motivated when the engine needs to serve more than one garden.
- **Field declaration engine** — rich field metadata (type, filterable, searchable, enum, range) declared in SCHEMA.md. Motivated when runtime schema configuration is needed.
- **Augmentation layer** — `_augment/` directory for private overlays on parent entries. Described in the RAG redesign spec but not needed for chain walk.
- **Non-duplication on submission** — semantic duplicate detection against upstream on entry ingestion. Described in the retrieval service spec.
- **Circuit breaker** — `quarkus-smallrye-fault-tolerance` for production-grade resilience. Add when real failure patterns are observed.
