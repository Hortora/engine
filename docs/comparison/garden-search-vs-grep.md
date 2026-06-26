# Garden Search vs Grep — Comparison Report

*Generated from `scripts/benchmark_search.py`*

## Per-Query Results

### Query 1: `qdrant java client`

**Style:** Keywords — both should find entries; does vector rank better?

- **gardenSearch query:** `qdrant java client`
- **grep pattern:** `qdrant.*client|qdrant.*java`

**gardenSearch results:**

| # | ID | Title | Domain | Relevance |
|---|-----|-------|--------|-----------|
| 1 | jvm/GE-20260609-521cca.md | Qdrant Java client Filter type is Common.Filter not Points.Filter | jvm | 0.61 |
| 2 | jvm/GE-20260601-08a351.md | quarkus-oidc-client on the classpath triggers Keycloak DevServices even when tests use static token auth | jvm | 0.59 |
| 3 | jvm/GE-20260618-c5d2d8.md | JDK HttpServer as zero-dependency mock server for Quarkus REST client testing | jvm | 0.57 |
| 4 | jvm/GE-20260614-1ece0f.md | quarkus-langchain4j Ollama REST client default timeout is 10s — too short for local LLMs | jvm | 0.55 |
| 5 | jvm/GE-20260609-086833.md | quarkus-vertx does NOT include Vert.x WebClient — need smallrye-mutiny-vertx-web-client separately | jvm | 0.55 |
| 6 | jvm/GE-20260609-2abdfd.md | Qdrant Java client 1.9.x lacks Query API — prefetch + fusion requires client ≥1.10.0 | jvm | 0.55 |
| 7 | jvm/GE-20260517-5b8e78.md | casehub-qhorus core services (MessageService, CommitmentService, ChannelService, ChannelGateway) are CDI-injectable despite only being documented as MCP tools | jvm | 0.55 |
| 8 | quarkus/GE-20260415-ffcbdd.md | Multiple @QuarkusTest classes in Surefire cause intermittent TIME_WAIT port conflict | quarkus | 0.54 |

**grep results:** 21 files

- `_summaries/jvm/GE-20260609-2abdfd.md`
- `_summaries/jvm/GE-20260609-521cca.md`
- `_summaries/jvm/GE-20260609-c1998e.md`
- `_summaries/jvm/GE-20260616-bdde66.md`
- `jvm/GE-20260609-26ffa5.md`
- `jvm/GE-20260609-2abdfd.md`
- `jvm/GE-20260609-521cca.md`
- `jvm/GE-20260609-c1998e.md`
- `jvm/GE-20260616-bdde66.md`
- `jvm/INDEX.md`
- `labels/filter.md`
- `labels/grpc.md`
- `labels/guava.md`
- `labels/hybrid-search.md`
- `labels/java-client.md`
- `labels/listenablefuture.md`
- `labels/mockito.md`
- `labels/qdrant.md`
- `labels/query-api.md`
- `labels/testing.md`
- ... and 1 more

### Query 2: `quarkus MCP`

**Style:** Keywords — grep hits many; vector should rank actionable gotchas higher

- **gardenSearch query:** `quarkus MCP`
- **grep pattern:** `quarkus.*mcp|mcp.*quarkus`

**gardenSearch results:**

| # | ID | Title | Domain | Relevance |
|---|-----|-------|--------|-----------|
| 1 | casehub-work/GE-20260427-bf4338.md | WorkItemStatus.EXPIRED.isTerminal() returns false — EXPIRED is not treated as terminal by quarkus-work | quarkus | 0.63 |
| 2 | quarkus/GE-20260414-5b3897.md | quarkus-flow uses the CNCF Serverless Workflow SDK directly — not Kogito | quarkus | 0.62 |
| 3 | quarkus/GE-20260414-be9977.md | Quarkus extension activation uses quarkus-extension.properties not quarkus-extension.yaml for deployment-artifact | quarkus | 0.62 |
| 4 | jvm/GE-20260508-b4c9b4.md | quarkus-rest does not include Bean Validation — @NotBlank/@Valid silently ignored without quarkus-hibernate-validator | jvm | 0.61 |
| 5 | jvm/GE-20260617-397d41.md | quarkus-rest-client-reactive does not exist in Quarkus 3.x — correct artifact is quarkus-rest-client | jvm | 0.60 |
| 6 | jvm/GE-20260618-9b08e4.md | quarkus-messaging-kafka — Quarkus 3.x artifact renamed from quarkus-smallrye-reactive-messaging-kafka | jvm | 0.59 |
| 7 | quarkus/GE-20260420-eb0bcb.md | quarkus-mcp-server @Tool methods support Uni<T>, CompletionStage, and @NonBlocking | quarkus | 0.59 |
| 8 | quarkus/GE-20260414-14d244.md | quarkus-flow TaskExecutorFactory SPI — undocumented extension point for custom task execution | quarkus | 0.59 |

**grep results:** 77 files

- `_summaries/jvm/GE-20260430-b015f5.md`
- `_summaries/jvm/GE-20260531-ac2489.md`
- `_summaries/jvm/GE-20260604-8b199c.md`
- `_summaries/jvm/GE-20260604-96d82a.md`
- `_summaries/jvm/GE-20260604-d08c9f.md`
- `_summaries/jvm/GE-20260606-cd1c61.md`
- `_summaries/jvm/GE-20260616-a67eec.md`
- `_summaries/quarkus/GE-20260414-fa6489.md`
- `_summaries/quarkus/GE-20260417-01b5d5.md`
- `_summaries/quarkus/GE-20260417-45f47f.md`
- `_summaries/quarkus/GE-20260417-691885.md`
- `_summaries/quarkus/GE-20260418-0f137f.md`
- `_summaries/quarkus/GE-20260418-d123af.md`
- `_summaries/quarkus/GE-20260420-e61431.md`
- `_summaries/quarkus/GE-20260420-eb0bcb.md`
- `_summaries/quarkus/GE-20260421-67bdd2.md`
- `_summaries/quarkus/GE-20260501-311bd8.md`
- `_summaries/quarkus/GE-20260501-50a9f4.md`
- `jvm/GE-20260430-b015f5.md`
- `jvm/GE-20260517-5b8e78.md`
- ... and 57 more

### Query 3: `reactive thread scheduling problems`

**Style:** Natural language — semantic recall for entries without 'scheduling'

- **gardenSearch query:** `reactive thread scheduling problems`
- **grep pattern:** `thread|scheduling|emitOn|Mutiny`

**gardenSearch results:**

| # | ID | Title | Domain | Relevance |
|---|-----|-------|--------|-----------|
| 1 | jvm/GE-20260530-5e5c67.md | runSubscriptionOn(workerPool) in a reactive adapter deadlocks when callers are already on the worker pool | jvm | 0.64 |
| 2 | jvm/GE-20260529-ff186e.md | emitOn(Infrastructure.getDefaultWorkerPool()) — correct way to shift blocking I/O off Vert.x IO thread in a reactive SPI | jvm | 0.62 |
| 3 | macos-native-appkit/GE-0072.md | `performSelectorOnMainThread:waitUntilDone:NO` from main thread schedules asynchronously | macos-native-appkit | 0.62 |
| 4 | jvm/GE-20260526-399a43.md | quarkus-rest (RESTEasy Reactive) + JDBC Panache requires @Blocking on resource classes — tests pass without it, production degrades silently | jvm | 0.61 |
| 5 | jep/GE-20260415-b218d7.md | Dedicated daemon thread + task queue for thread-affinite interpreters in multi-threaded JVM servers | jep | 0.60 |
| 6 | jep/GE-20260415-5a9a11.md | JEP SharedInterpreter called from non-owner thread hangs indefinitely — no exception | jep | 0.60 |
| 7 | jvm/GE-20260616-99484f.md | Uni.combine() runs blocking IO (worker pool) and reactive DB lookup (event loop) concurrently without Vert.x context violations | jvm | 0.59 |
| 8 | jvm/GE-20260518-e4fa52.md | RESTEasy Reactive endpoints that call .await() on the IO thread throw BlockingOperationNotAllowedException — add @Blocking | jvm | 0.59 |

**grep results:** 515 files

- `_summaries/jep/GE-20260415-5a9a11.md`
- `_summaries/jep/GE-20260415-b218d7.md`
- `_summaries/jvm/GE-20260501-4c94b8.md`
- `_summaries/jvm/GE-20260501-56e179.md`
- `_summaries/jvm/GE-20260505-da346d.md`
- `_summaries/jvm/GE-20260512-6d0c2b.md`
- `_summaries/jvm/GE-20260512-a9ad9f.md`
- `_summaries/jvm/GE-20260515-c272d2.md`
- `_summaries/jvm/GE-20260517-f31786.md`
- `_summaries/jvm/GE-20260518-069f64.md`
- `_summaries/jvm/GE-20260518-bee1b3.md`
- `_summaries/jvm/GE-20260518-e4fa52.md`
- `_summaries/jvm/GE-20260519-d32fc0.md`
- `_summaries/jvm/GE-20260521-1e95dc.md`
- `_summaries/jvm/GE-20260521-d72294.md`
- `_summaries/jvm/GE-20260522-2a4009.md`
- `_summaries/jvm/GE-20260522-bc642c.md`
- `_summaries/jvm/GE-20260522-daca26.md`
- `_summaries/jvm/GE-20260523-80cc31.md`
- `_summaries/jvm/GE-20260523-bd68ba.md`
- ... and 495 more

### Query 4: `test passes locally fails in CI`

**Style:** Symptom description — grep has no single good keyword

- **gardenSearch query:** `test passes locally fails in CI`
- **grep pattern:** `CI|locally|flaky|test.*fail`

**gardenSearch results:**

| # | ID | Title | Domain | Relevance |
|---|-----|-------|--------|-----------|
| 1 | web/GE-20260625-2c2539.md | JSDOM location.hash persists across vitest test cases — URL state leaks between tests | web | 0.64 |
| 2 | casehub-work/docs/protocols/casehub/clinical-tenant-isolation-test-coverage.md | Tenant isolation tests require both wrong-tenant (404) and bypass (200) assertions |  | 0.64 |
| 3 | jvm/GE-20260428-7e57f9.md | @QuarkusTest always runs in mock profile — Playwright tests pass while the real application (replay/emulated) is broken | jvm | 0.63 |
| 4 | jvm/GE-20260526-bfc589.md | REST Assured Instant equality fails intermittently — Jackson and Instant.toString() diverge on sub-second precision | jvm | 0.63 |
| 5 | tools/GE-20260429-63a862.md | Claude Code subagent test result reports are unreliable — always verify independently | tools | 0.63 |
| 6 | casehub-engine/GE-20260604-97031b.md | Global WorkItem.find() in engine integration tests picks up WorkItems from other test cases — Awaitility timeout with wrong WorkItem completed | casehub-engine | 0.62 |
| 7 | jvm/GE-20260512-2c2eff.md | Non-ANSI SQL types in Flyway migrations pass H2 tests silently but fail on PostgreSQL at deployment | jvm | 0.62 |
| 8 | jvm/GE-20260421-efa107.md | Maven -Dexcludes does not suppress Quarkus @QuarkusTest class-loader failures — use Maven profiles instead | jvm | 0.62 |

**grep results:** 2176 files

- `README.md`
- `SKILL-SPEC.md`
- `_summaries/beautifulsoup/GE-0008.md`
- `_summaries/casehub-desiredstate/GE-20260616-02d0a7.md`
- `_summaries/casehub-engine/GE-20260525-d06282.md`
- `_summaries/casehub-engine/GE-20260526-2ee43b.md`
- `_summaries/casehub-engine/GE-20260526-34a4c4.md`
- `_summaries/casehub-engine/GE-20260531-1e51d4.md`
- `_summaries/casehub-engine/GE-20260531-864d8e.md`
- `_summaries/casehub-ledger/GE-20260615-6d0ae3.md`
- `_summaries/casehub-work/GE-20260522-de5ee3.md`
- `_summaries/casehub-work/GE-20260522-f7db12.md`
- `_summaries/casehub-work/GE-20260607-fad749.md`
- `_summaries/claude-code/GE-20260525-58fcbf.md`
- `_summaries/drools/GE-0063.md`
- `_summaries/electron/GE-0176.md`
- `_summaries/intellij-platform/GE-0110.md`
- `_summaries/java/GE-0067.md`
- `_summaries/java/GE-20260416-39d854.md`
- `_summaries/java/GE-20260416-ca1c71.md`
- ... and 2156 more

### Query 5: `CDI bean not found at runtime`

**Style:** Symptom → root cause mapping

- **gardenSearch query:** `CDI bean not found at runtime`
- **grep pattern:** `CDI|bean.*not.*found|AmbiguousResolution|LookupIfProperty`

**gardenSearch results:**

| # | ID | Title | Domain | Relevance |
|---|-----|-------|--------|-----------|
| 1 | jvm/GE-20260519-244ad2.md | Gate optional beans in a Quarkus extension with ExcludedTypeBuildItem + @ConfigRoot(BUILD_TIME) — not @IfBuildProperty on runtime beans | jvm | 0.67 |
| 2 | jvm/GE-20260522-adb5cd.md | Moving @ApplicationScoped bean from Quarkus app module to library JAR silently breaks CDI discovery | jvm | 0.67 |
| 3 | jvm/GE-20260523-afab1d.md | @ApplicationScoped bean present in JAR bytecode is invisible to Quarkus ARC without a Jandex index | jvm | 0.66 |
| 4 | quarkus/GE-20260422-3242bf.md | Use Instance<T> for optional CDI injection — resolves gracefully to null when no bean exists | quarkus | 0.66 |
| 5 | quarkus/GE-20260426-6ed53b.md | @IfBuildProfile is resolved at build time — runtime profile switch cannot add excluded beans | quarkus | 0.66 |
| 6 | quarkus/GE-20260420-4b55e2.md | Micrometer Gauge beans need @Startup + public @Transactional methods — three interacting gotchas | quarkus | 0.66 |
| 7 | quarkus/GE-0133.md | Quarkus CDI silently ignores `@ApplicationScoped` beans in jars without a Jandex index | quarkus | 0.66 |
| 8 | quarkus/GE-20260424-59906a.md | Quarkus CDI does not scan @ApplicationScoped beans in plain JAR module dependencies | quarkus | 0.65 |

**grep results:** 1098 files

- `_summaries/casehub-engine/GE-20260523-86ed13.md`
- `_summaries/casehub-engine/GE-20260526-2ee43b.md`
- `_summaries/casehub-engine/GE-20260526-34a4c4.md`
- `_summaries/casehub-engine/GE-20260529-0c23f1.md`
- `_summaries/casehub-engine/GE-20260529-b5723e.md`
- `_summaries/casehub-engine/GE-20260531-1e51d4.md`
- `_summaries/casehub-engine/GE-20260531-864d8e.md`
- `_summaries/casehub-engine/GE-20260607-609772.md`
- `_summaries/casehub-engine/GE-20260607-e27c23.md`
- `_summaries/casehub-engine/GE-20260612-b20b51.md`
- `_summaries/casehub-ledger/GE-20260531-1587fe.md`
- `_summaries/casehub-qhorus/GE-20260607-58c683.md`
- `_summaries/casehub-work/GE-20260529-af0f2e.md`
- `_summaries/java/GE-20260421-1192cd.md`
- `_summaries/java/GE-20260505-8c57c2.md`
- `_summaries/jvm/GE-20260422-0ed3e5.md`
- `_summaries/jvm/GE-20260422-53d0f7.md`
- `_summaries/jvm/GE-20260422-e48245.md`
- `_summaries/jvm/GE-20260423-29f45a.md`
- `_summaries/jvm/GE-20260423-bcb5b7.md`
- ... and 1078 more

### Query 6: `GE-20260609-2abdfd`

**Style:** Exact ID — grep finds the exact file; vector search returns unrelated results

- **gardenSearch query:** `GE-20260609-2abdfd`
- **grep pattern:** `GE-20260609-2abdfd`

**gardenSearch results:**

| # | ID | Title | Domain | Relevance |
|---|-----|-------|--------|-----------|
| 1 | jvm/GE-20260530-4387cb.md | casehub-qhorus MessageService methods using Panache static calls bypass InMemory store in @QuarkusTest | jvm | 0.58 |
| 2 | jvm/GE-20260530-b68c00.md | Maven caches GitHub Packages 401 failure in *.lastUpdated markers — fixing auth alone doesn't unblock resolution | jvm | 0.54 |
| 3 | tools/GE-20260424-3f5e60.md | GitHub repo transfer API returns 200 immediately but transfer completes asynchronously | tools | 0.54 |
| 4 | jvm/GE-20260528-936918.md | Response.Status.UNPROCESSABLE_ENTITY doesn't exist in Jakarta EE 9 JAX-RS — use raw integer 422 | jvm | 0.53 |
| 5 | tools/GE-20260428-f94886.md | setup-java server-id only wires credentials for that exact repository id — different ids in pom get 401 | tools | 0.53 |
| 6 | tools/GE-20260417-a420df.md | PR base becomes stale when upstream maintainer merges your content as a new PR directly to main | tools | 0.52 |
| 7 | tools/GE-20260601-350be3.md | GitHub PR CONFLICTING/DIRTY when fork main diverged from upstream — no file conflicts | tools | 0.52 |
| 8 | intellij-platform/GE-0112.md | `SafeDeleteProcessorDelegate.shouldDeleteElement()` Removed in 2023.2 — Use `findConflicts()` | intellij-platform | 0.51 |

**grep results:** 9 files

- `_summaries/jvm/GE-20260609-2abdfd.md`
- `jvm/GE-20260609-2abdfd.md`
- `jvm/GE-20260609-521cca.md`
- `jvm/INDEX.md`
- `labels/hybrid-search.md`
- `labels/java-client.md`
- `labels/qdrant.md`
- `labels/query-api.md`
- `labels/version.md`

## Summary

| # | Query | gardenSearch hits | grep hits | Style |
|---|-------|-------------------|-----------|-------|
| 1 | `qdrant java client` | 8 | 21 | Keywords — both should find entries; does vector rank better? |
| 2 | `quarkus MCP` | 8 | 77 | Keywords — grep hits many; vector should rank actionable gotchas higher |
| 3 | `reactive thread scheduling problems` | 8 | 515 | Natural language — semantic recall for entries without 'scheduling' |
| 4 | `test passes locally fails in CI` | 8 | 2176 | Symptom description — grep has no single good keyword |
| 5 | `CDI bean not found at runtime` | 8 | 1098 | Symptom → root cause mapping |
| 6 | `GE-20260609-2abdfd` | 8 | 9 | Exact ID — grep finds the exact file; vector search returns unrelated results |
