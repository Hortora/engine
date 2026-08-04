# Grove Phase 1 — Core App, Domain Map, Entry Detail, Basic Curation

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** TBD (to be filed on hortora/grove)
**Spec:** `docs/specs/2026-08-04-grove-garden-analytics-design.md`

**Goal:** Get Grove running as a standalone Quarkus app with a domain map landing page, entry detail view, and basic curation actions (edit, retire, confirm freshness).

**Architecture:** Single-module Quarkus app (like Trellis sidecar). Backend reads Qdrant REST API for garden data + SQLite for retrieval tracking + garden.db. Frontend uses casehub pages/block-ui with LitElement. Mutations commit to the garden git repo.

**Tech Stack:** Quarkus 3.36, Java 25, casehub pages/block-ui, LitElement (Lit 3.x), Qdrant Java client, SQLite JDBC, JGit, esbuild, yarn

## Global Constraints

- Quarkus 3.36.x (match engine version)
- Java 25 (match engine)
- casehub pages/block-ui npm packages via portal resolution (match Trellis pattern)
- Qdrant at localhost:6333, collection `hortora_garden`
- Garden dir at `${HORTORA_GARDEN:-~/.hortora/garden}`
- retrieval-tracking.db at `~/.hortora/stats/retrieval-tracking.db`
- garden.db at `~/.hortora/garden/garden.db`
- All mutations commit to the garden git repo with `grove:` prefix messages
- Port: 8090 (avoid conflict with engine on 8080)

---

### Task 1: Create repo and scaffold Quarkus project

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/application.properties`
- Create: `src/main/java/io/hortora/grove/GroveApp.java`
- Create: `src/main/webui/package.json`
- Create: `src/main/webui/esbuild.mjs`
- Create: `src/main/webui/src/app.ts`
- Create: `src/main/webui/src/index.html`
- Create: `CLAUDE.md`

**Interfaces:**
- Produces: runnable Quarkus app on port 8090 with blank landing page

- [ ] **Step 1: Create GitHub repo**

```bash
gh repo create Hortora/grove --public --description "Garden analytics and curation dashboard"
git clone https://github.com/Hortora/grove.git ~/claude/hortora/grove
```

- [ ] **Step 2: Create pom.xml**

Model on Trellis sidecar pom.xml. Key dependencies:
- Quarkus BOM 3.36.x
- `quarkus-rest`, `quarkus-rest-jackson`
- `quarkus-quinoa` (bundles frontend)
- `casehub-pages-push`, `casehub-pages-push-runtime` (if SSE needed, otherwise skip for Phase 1)
- `casehub-blocks` (block data model)
- `sqlite-jdbc` (retrieval tracking + garden.db)
- Qdrant Java client
- JGit (garden mutations)

Include Maven dependency plugin to unpack `casehub-pages-npm` and `casehub-blocks-ui-npm` into `src/main/webui/.casehub-packages/`.

- [ ] **Step 3: Create application.properties**

```properties
quarkus.http.port=8090
quarkus.quinoa.build-dir=dist
quarkus.quinoa.package-manager=yarn

# Qdrant
grove.qdrant.host=localhost
grove.qdrant.port=6333
grove.qdrant.collection=hortora_garden

# Garden
grove.garden.path=${HORTORA_GARDEN:${user.home}/.hortora/garden}
grove.retrieval-tracking.path=${user.home}/.hortora/stats/retrieval-tracking.db
grove.garden-db.path=${HORTORA_GARDEN:${user.home}/.hortora/garden}/garden.db
```

- [ ] **Step 4: Create minimal Java app and frontend shell**

`GroveApp.java` — empty, Quarkus auto-detects.
`app.ts` — minimal LitElement app with hash routing stub.
`package.json` — lit, esbuild, casehub packages via portal.
`index.html` — minimal HTML loading the built JS.

- [ ] **Step 5: Verify it starts**

```bash
./mvnw quarkus:dev
# Verify: http://localhost:8090 shows the blank shell
```

- [ ] **Step 6: Create CLAUDE.md and commit**

```bash
git add -A
git commit -m "feat: scaffold Grove — Quarkus + casehub pages/block-ui shell"
git push origin main
```

---

### Task 2: Backend — Qdrant client and domain stats

**Files:**
- Create: `src/main/java/io/hortora/grove/qdrant/QdrantGardenClient.java`
- Create: `src/main/java/io/hortora/grove/qdrant/GardenEntry.java`
- Create: `src/main/java/io/hortora/grove/qdrant/DomainStats.java`
- Create: `src/main/java/io/hortora/grove/qdrant/GardenOverview.java`
- Create: `src/main/java/io/hortora/grove/api/DomainResource.java`
- Create: `src/main/java/io/hortora/grove/config/GroveConfig.java`
- Test: `src/test/java/io/hortora/grove/qdrant/QdrantGardenClientTest.java`
- Test: `src/test/java/io/hortora/grove/api/DomainResourceTest.java`

**Interfaces:**
- Produces: `QdrantGardenClient.getDomainStats()` → `List<DomainStats>`
- Produces: `QdrantGardenClient.getOverview()` → `GardenOverview`
- Produces: `GET /api/domains` → JSON array of DomainStats
- Produces: `GET /api/overview` → JSON GardenOverview

- [ ] **Step 1: Write failing test for QdrantGardenClient**

Test that `getDomainStats()` returns domain names with entry counts, type breakdown, and average score. Use a mock Qdrant client or WireMock for the Qdrant REST API.

- [ ] **Step 2: Implement QdrantGardenClient**

`QdrantGardenClient` calls Qdrant's scroll API to fetch all points (payload only, no vectors). Groups by `domain` payload field. For each domain computes: count, type breakdown (gotcha/technique/undocumented/convention), average score.

`GardenEntry` record: id, title, type, domain, score, submitted, sourceDocumentId, content.

`DomainStats` record: domain, entryCount, typeBreakdown (Map<String, Integer>), averageScore, staleCount, stalePercent.

Staleness: parse YAML frontmatter from `content` field to extract `staleness_threshold` and `submitted`/`last_reviewed`. Compare against today.

`GardenOverview` record: totalEntries, totalDomains, staleCount, untaggedCount, neverRetrievedCount.

- [ ] **Step 3: Write DomainResource REST endpoint**

```java
@Path("/api/domains")
@ApplicationScoped
public class DomainResource {
    @Inject QdrantGardenClient client;

    @GET
    public List<DomainStats> getDomains() { ... }
}
```

Plus `GET /api/overview` returning `GardenOverview`.

- [ ] **Step 4: Run tests, verify, commit**

```bash
./mvnw test
git commit -m "feat: Qdrant garden client with domain stats and overview endpoints"
```

---

### Task 3: Backend — entry detail and search

**Files:**
- Create: `src/main/java/io/hortora/grove/api/EntryResource.java`
- Create: `src/main/java/io/hortora/grove/qdrant/FrontmatterParser.java`
- Test: `src/test/java/io/hortora/grove/qdrant/FrontmatterParserTest.java`
- Test: `src/test/java/io/hortora/grove/api/EntryResourceTest.java`

**Interfaces:**
- Consumes: `QdrantGardenClient` from Task 2
- Produces: `GET /api/entries/{geId}` → full entry with parsed frontmatter
- Produces: `GET /api/domains/{domain}/entries` → paginated entry list for a domain
- Produces: `FrontmatterParser.parse(content)` → Map of all YAML fields

- [ ] **Step 1: Write FrontmatterParser**

Extracts YAML frontmatter from `content` field (text between `---` markers at the start). Returns all fields as a Map including `staleness_threshold`, `tags`, `last_reviewed`, `author`, `verified_on`, `constraints`, `invalidation_triggers`.

Test with real entry content (copy from Qdrant payload sample).

- [ ] **Step 2: Write EntryResource endpoints**

`GET /api/entries/{geId}` — filter Qdrant by `sourceDocumentId` containing the GE-ID. Return full entry with parsed frontmatter merged into the response.

`GET /api/domains/{domain}/entries` — scroll Qdrant filtered by domain. Return sorted list with staleness status computed. Support query params: `sort` (score, submitted, staleness), `type` filter, `stale` filter (true/false).

- [ ] **Step 3: Run tests, verify, commit**

---

### Task 4: Backend — retrieval tracking integration

**Files:**
- Create: `src/main/java/io/hortora/grove/tracking/RetrievalStatsService.java`
- Create: `src/main/java/io/hortora/grove/tracking/EntryRetrievalStats.java`
- Create: `src/main/java/io/hortora/grove/api/TrackingResource.java`
- Test: `src/test/java/io/hortora/grove/tracking/RetrievalStatsServiceTest.java`

**Interfaces:**
- Produces: `RetrievalStatsService.getRetrievalCounts()` → Map<String, Integer> (sourceDocumentId → count)
- Produces: `RetrievalStatsService.getNeverRetrieved(allDocIds)` → Set<String>
- Produces: `GET /api/tracking/stats` → retrieval frequency per entry

- [ ] **Step 1: Write RetrievalStatsService**

Opens `retrieval-tracking.db` read-only. Queries `retrieved_documents` table grouped by `source_document_id` with count. Also joins against the full entry list to find entries with zero retrievals.

`EntryRetrievalStats` record: sourceDocumentId, retrievalCount, lastRetrievedAt.

- [ ] **Step 2: Wire into DomainStats**

Enhance `DomainStats` (Task 2) with `retrievedEntryCount` and `neverRetrievedCount` by joining retrieval data with domain entries.

- [ ] **Step 3: Run tests, verify, commit**

---

### Task 5: Frontend — domain map landing page

**Files:**
- Create: `src/main/webui/src/views/domain-map.ts`
- Create: `src/main/webui/src/components/domain-card.ts`
- Create: `src/main/webui/src/components/health-bar.ts`
- Modify: `src/main/webui/src/app.ts` (add route)

**Interfaces:**
- Consumes: `GET /api/domains`, `GET /api/overview`
- Produces: `<grove-domain-map>` view, `<grove-domain-card>` component

- [ ] **Step 1: Create domain-card component**

LitElement component showing: domain name, entry count, type breakdown (coloured segments), staleness indicator (green/amber/red based on stale percentage), average score, retrieval coverage ratio.

- [ ] **Step 2: Create health-bar component**

Reusable segmented bar for type breakdown and staleness. Accepts `segments: {label, count, color}[]`.

- [ ] **Step 3: Create domain-map view**

Top-level overview metrics (total entries, stale count, never-retrieved, index gaps). Below: grid of domain cards. Clicking a card navigates to `#domain/{name}`.

- [ ] **Step 4: Wire routing in app.ts**

Hash routing: `#` or `#home` → `<grove-domain-map>`, `#domain/{name}` → domain detail (Task 6), `#entry/{geId}` → entry detail (Task 7).

- [ ] **Step 5: Verify in browser, commit**

---

### Task 6: Frontend — domain detail view

**Files:**
- Create: `src/main/webui/src/views/domain-detail.ts`
- Create: `src/main/webui/src/components/entry-table.ts`
- Modify: `src/main/webui/src/app.ts` (add route)

**Interfaces:**
- Consumes: `GET /api/domains/{domain}/entries`
- Produces: `<grove-domain-detail>` view with sortable/filterable entry table

- [ ] **Step 1: Create entry-table component**

Sortable table with columns: GE-ID (link to entry detail), title, type badge, score, submitted date, staleness status badge, retrieval count. Column headers clickable to sort. Filter controls above: type dropdown, staleness toggle, score range.

- [ ] **Step 2: Create domain-detail view**

Header showing domain name + summary stats. Below: entry-table component. Back link to domain map.

- [ ] **Step 3: Verify in browser, commit**

---

### Task 7: Frontend — entry detail view + basic curation

**Files:**
- Create: `src/main/webui/src/views/entry-detail.ts`
- Create: `src/main/java/io/hortora/grove/curation/CurationService.java`
- Create: `src/main/java/io/hortora/grove/api/CurationResource.java`
- Test: `src/test/java/io/hortora/grove/curation/CurationServiceTest.java`

**Interfaces:**
- Consumes: `GET /api/entries/{geId}`, `QdrantGardenClient`, `FrontmatterParser`
- Produces: `<grove-entry-detail>` view with curation action buttons
- Produces: `POST /api/curation/confirm/{geId}` — sets `last_reviewed: today`
- Produces: `POST /api/curation/retire/{geId}` — adds deprecated marker
- Produces: `PUT /api/curation/edit/{geId}` — updates entry content

- [ ] **Step 1: Write CurationService**

Uses JGit to open the garden repo. Provides methods:
- `confirmFreshness(geId)` — reads entry file, updates `last_reviewed` in YAML frontmatter, writes, commits with `grove: confirm freshness GE-XXXX`
- `retire(geId, reason)` — adds `**Deprecated:** [reason] — [date]` after frontmatter, commits with `grove: retire GE-XXXX — [reason]`
- `editEntry(geId, updatedContent)` — writes new content, commits with `grove: edit GE-XXXX`

Resolves file path from `sourceDocumentId` payload field (e.g. `jvm/GE-20260516-3a27dc.md` → `~/.hortora/garden/jvm/GE-20260516-3a27dc.md`).

- [ ] **Step 2: Write CurationResource REST endpoints**

```java
@Path("/api/curation")
@ApplicationScoped
public class CurationResource {
    @POST @Path("/confirm/{geId}") ...
    @POST @Path("/retire/{geId}") ...
    @PUT  @Path("/edit/{geId}") ...
}
```

- [ ] **Step 3: Create entry-detail view**

Two-column layout. Left: full entry content rendered as formatted text with metadata badges (domain, type, score, staleness status, version status). Right: action sidebar with buttons — Confirm Freshness, Retire (with reason input), Edit (opens content in a textarea).

Retrieval stats section below content: retrieval count, last retrieved date.

- [ ] **Step 4: Wire curation actions**

Button clicks call the REST endpoints. On success, refresh the entry display. Show toast/notification for the action result.

- [ ] **Step 5: End-to-end test in browser, commit**

---

### Task 8: Backend — index reconciliation endpoint

**Files:**
- Create: `src/main/java/io/hortora/grove/health/IndexReconciler.java`
- Create: `src/main/java/io/hortora/grove/api/HealthResource.java`
- Test: `src/test/java/io/hortora/grove/health/IndexReconcilerTest.java`

**Interfaces:**
- Produces: `GET /api/health/reconcile` → comparison of Qdrant point count vs garden.db entry count vs file count on disk
- Produces: `IndexReconciler.reconcile()` → `ReconcileReport` (qdrantCount, gardenDbCount, fileCount, missingFromQdrant, missingFromDb)

- [ ] **Step 1: Write IndexReconciler**

Counts entries in three sources:
1. Qdrant — scroll with count-only (no payloads)
2. garden.db — `SELECT COUNT(*) FROM entries_index`
3. Filesystem — `find` on garden dir for `GE-*.md` files

Computes the gaps: files not in Qdrant, files not in garden.db, Qdrant points with no matching file.

- [ ] **Step 2: Write HealthResource endpoint**

Returns the reconcile report as JSON. Surfaced in the overview section of the domain map.

- [ ] **Step 3: Run tests, verify, commit**

---

## Phase 2 (future plan)

Not covered here — will be planned after Phase 1 ships:
- Vector quality signals (near-duplicate detection, semantic outliers, coverage density, cross-domain similarity)
- Version-aware content lifecycle (version registry, aging detection, de-emphasis)
- Bulk curation (multi-select, bulk confirm/retire/re-tag)
- Domain move action
- Trigger reindex from UI
