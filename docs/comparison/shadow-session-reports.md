# Shadow Harness Session Reports

Real-world comparison data from sessions where both gardenSearch (MCP) and manual grep were used. Each report captures queries, results, and assessment from a working session — not synthetic benchmarks.

## Report 1 — CDI augmentation failure (2026-07-30)

**Context:** Debugging Quarkus CDI augmentation failures after engine upgrade.

**Query:** `"Quarkus CDI augmentation failure engine SPI migration reactive to blocking"` with keywords `SignalReceivedEventHandler|WorkerExecutionManager|Uni|exclude-types|CasehubEnabledProfile`

| What was needed | Garden told | Manual investigation told |
|---|---|---|
| SignalReceivedEventHandler depends on WorkerExecutionRecoveryService | Yes (GE-e27c23 title) | Yes — read constructor |
| New engine beans with excluded deps break augmentation | Yes (GE-2ee43b title) | Yes — hit with PersonalitySignalRecorder |
| exclude-types management is fragile | Yes (GE-0c23f1 title) | Yes — read 113-line exclude list |
| CasehubEnabledProfile exclude-type overrides need care | Yes (GE-609772 title) | Yes — read getConfigOverrides() |
| ExpressionEvaluator moved to platform-api | No | Found by reading imports |
| ReactiveQhorusMcpTools → QhorusMcpTools | No | Found by find on qhorus repo |
| BackendRegistration extracted from ChannelGateway | No | Found by decompiling jar |
| PersonalitySignalRecorder is a new bean needing exclusion | No (it's new) | Found from CDI error |
| Engine SPIs migrated from Uni<T> to blocking | No | Found by comparing interfaces |

**Assessment:** Garden confirmed 4 known gotchas. Missed 5 recent code changes (not in garden). MCP was unavailable (Qdrant down) — grep on garden repo was fallback.

**Issues filed:** #64 (CasehubEnabledProfile keyword miss), #65 (Qdrant graceful degradation)

---

## Report 2 — Qhorus channel creation design (2026-07-31)

**Context:** Designing general-purpose chat rooms (Claudony issue 177).

**MCP searches:** 4 queries with keywords.
- `"Qhorus channel creation general-purpose rooms"` with `ChannelService|ChannelCreateRequest|ChannelBackend|allowedTypes` → 10 results (with keywords), 9 without
- Without keywords: missed 4 entries (idempotency gotcha, delete auto-cleanup, reactive validation bypass, lossy type constraints)

**Manual grep:** 4 targeted greps (`ChannelService`, `channel.*creat`, `ChannelBackend|ChannelInitialised`, `allowedTypes|MessageType`) → ~60 files, ~12 relevant after triage.

| Dimension | MCP (with keywords) | Manual grep |
|---|---|---|
| Entries found | 10 | ~12 relevant (60 raw) |
| Queries | 1 | 4 |
| Runtime | ~5 seconds | ~534 seconds |
| False positives | 0 | ~48 |
| Coverage | Slightly narrower | Slightly broader |

**Assessment:** MCP with keywords matched grep's coverage with zero noise. Keywords were essential — without them, 4 relevant entries missed. The entries grep found that MCP missed were tangential (naming conventions, package locations).

---

## Report 3 — LLM coordinator/tmux design (2026-07-31)

**Context:** Designing an LLM coordinator with tmux sessions.

**MCP searches:** 4 queries with keywords.
- `"process management electron tmux terminal WebSocket bridge IPC"` with `tmux|Electron|xterm|WebSocket|IPC|process|terminal`
- Pre-fix: 2 results. Post-fix (#67/#68): 16 results, 48 above threshold.

**Manual grep:** Multiple strategies, 56 tool calls, 534 seconds → 22 entries.

| Dimension | MCP (pre-fix) | MCP (post-fix) | Manual |
|---|---|---|---|
| Entries found | 2 | 16 | 22 |
| Overlap with manual | 2 | 8 | — |
| MCP-only finds | 0 | 8 | — |
| Runtime | ~5s | ~5s | ~534s |

**Root cause:** Cross-encoder scored tmux command gotchas below zero (semantically distant from NL query). Score-floor filtering cut 46 candidates. Fix: disable score-floor and gap-trimming when keywords present.

**Issues filed:** #67 (score-gap trimming), #68 (broad keyword recall)

---

## Report 4 — Flyway/JPA/Panache design (2026-07-31)

**Context:** SLA calibration implementation with Flyway migrations and JPA entities.

| Query | MCP | Manual grep |
|---|---|---|
| SLA calibration / capability duration | 0 results | 0 results |
| Flyway / H2 / JPA / migration | 16 results (48 above threshold) | 190+ paths (noise) |
| Engine plan item / EventLog / duration | 16 results (48 above threshold) | 95+ paths |
| Panache / @OneToMany / @ElementCollection / cascade | 16 results (48 above threshold) | 25 paths |

**Key finding — semantic beats keyword:** Narrowed manual grep patterns (`@ElementCollection`, `flyway.*version|V[0-9]+__`) returned 0 — exact terms weren't in entries. MCP found the concepts expressed differently (JPA collection persistence without literal `@ElementCollection`).

**MCP surfaced actionable entries:**
- GE-20260529-bc1eaa — TIMESTAMP WITH TIME ZONE not TIMESTAMPTZ
- GE-20260512-7720ab — H2-reserved words as column names
- GE-20260713-b879b2 — TEXT not JSONB for JSON in H2
- GE-20260605-b734b3 — portable MERGE INTO for H2+PostgreSQL
- GE-20260605-ff8729 — Panache persist(Iterable) for batch

**Stability issue:** 3 of 4 first MCP calls failed with connection errors, all succeeded on retry.

**Assessment:** MCP clearly better when up. Full content, relevance-ranked, minimal noise. Manual grep: 190 paths requiring dozens of Read calls to reach same insights. Connection instability is the main remaining weakness.

---

## Report 5 — CDI observer for CaseStatusChanged/FAULTED (2026-07-31)

**Context:** Designing a CDI observer for case status changes (FAULTED, worker failure binding).

**MCP search:** `"CDI observer case status changed FAULTED worker failure binding"` with keywords `CaseStatusChanged|CaseStatus|FAULTED|WorkerRetriesExhaustedEventHandler|HumanTaskTarget` → 16 results.

**Manual grep:** `CaseStatusChanged|FAULTED|WorkerRetriesExhausted|HumanTaskTarget|failure.binding|contextChange|CDI.observer` → 29 unique entries (15 in grep results captured by hook).

| Dimension | MCP | Manual grep |
|---|---|---|
| Total entries | 16 (2 on-target, 14 noise) | 29 (3 critical, many relevant) |
| Top hits | GE-20260607-245588 (FAULTED mechanism), GE-20260629-670471 (duplicate FAULTED logs) | Same + 3 critical misses |

**Critical entries MCP missed:**
- **GE-20260607-609772** — CaseStatusChangedHandler excluded by test profiles → cases silently stay RUNNING. Directly affects observer testing.
- **GE-20260531-864d8e** — `@Observes` vs `@ObservesAsync` — sync observers silently never fire for async events. Engine fires via `fireAsync()`.
- **GE-20260605-fa1a51** — PlanItemCompletedEvent only fires for worker completions, not context signals. Informs which events fire under which paths.

**Assessment:** MCP found the two highest-signal entries (FAULTED mechanism) via semantic overlap. Missed operational gotchas about CDI observer patterns, test profile exclusions, and event path coverage. These are adjacent knowledge — different vocabulary, same problem space. Manual grep caught them because keywords appear literally regardless of semantic framing.

**Key insight:** For this issue, manual search found the entries that would prevent the most painful debugging. MCP found the entries that frame the problem. You need both.

---

## Report 6 — Scheduler SPI / db-scheduler design (2026-07-31)

**Context:** Designing a db-scheduler backend as alternative to Quartz for the engine's JobScheduler SPI.

**MCP searches:** 3 queries with keywords:
- `"scheduler SPI alternative backend pluggable implementation"` with `JobScheduler|WorkerExecutionManager|Quartz|db-scheduler|ScheduledJobRequest`
- `"CDI alternative bean selection DefaultBean classpath module selection"` with `DefaultBean|ApplicationScoped|Alternative|selected-alternatives|WorkerBackend`
- (third query not recorded)

**Results:** 48 entries across 3 calls (16 per call, some overlap). 6 directly actionable hits including Quartz cron failures, scheduler CDI cascading failures, Jandex indexing requirement, and CDI priority ties.

**Manual grep:** Initially grepped wrong directory (protocols subdirectory, not garden). Got 0 results. Re-run against correct garden path: 19 files matched.

| Dimension | Grep (correct location) | MCP gardenSearch |
|---|---|---|
| Exact keyword hits | 19 files | ~same |
| Semantic matches (no exact keyword) | 0 | ~10 additional |
| Effort | Must know directory structure | Just query |

**Key finding — grep comparison methodology caveat:** Claude grepped the wrong directory and concluded "grep found nothing, MCP wins." The real comparison is much closer once grep targets the right path. Semantic discovery (~10 additional entries without exact keywords) is MCP's genuine advantage. Future comparisons must ensure grep targets `~/.hortora/garden`.

**Assessment:** MCP genuinely better for semantic discovery (entries about `selected-alternatives` priority ties, `start-mode=halted` that don't contain the query keywords). Grep is a solid baseline when pointed at the right directory. The unfair comparison is a recurring risk — Claude doesn't always know where the garden lives.

---

## Report 7 — LLM narrator agent event wiring (2026-08-01)

**Context:** Wiring an LLM narrator agent into an event observation pipeline in a Quarkus app.

**MCP search:** `"LLM narrator agent wiring event stream observation pipeline Quarkus"` with keywords `NarratorAgent|ObservationAccumulator|PartitionedObservationService|ManorEvent|EventStreamBus` → 16 results (48 above threshold).

**Manual grep:** Same keywords → 1 result (GE-20260629-e8b16d, EventStreamBus).

| Dimension | MCP | Manual grep |
|---|---|---|
| Unique entries | 16 | 1 |
| Exact keyword hit | GE-20260629-e8b16d (EventStreamBus) | Same |
| Semantic-only hits | 15 — CDI observer gotchas, event bus publish/send, direct injection, SSE dispatch | 0 |

**Key finding — MCP is a strict superset:** 4 of 5 keywords are project-specific terms (`NarratorAgent`, `ManorEvent`, etc.) that don't appear in garden entries. The garden stores reusable platform knowledge, not project-specific class names. Only `EventStreamBus` bridges both. MCP's semantic layer understood that "event stream observation pipeline wiring" relates to CDI observer dispatch, event bus fan-out, `@ConsumeEvent` gotchas — none containing the literal keywords.

**Assessment:** MCP strictly better. Manual grep adds nothing MCP doesn't already cover. The semantic matches (CDI observer pitfalls, event bus patterns) are the most valuable hits and are invisible to keyword grep. Manual fallback only worth keeping for when MCP server is down.

---

## Report 8 — RBAC/security for MCP tools (2026-08-01)

**Context:** Designing RBAC security layer for MCP tool endpoints (issue #74).

**MCP search:** limit=50, returned 50 entries (50 above threshold). Cross-encoder scored from -0.4 to -7.3.

**Manual grep:** ~350+ filenames across every domain. No content, no ranking.

| Dimension | MCP | Manual grep |
|---|---|---|
| Results | 50 ranked, full content | ~350 filenames, no content |
| Tool calls needed | 1 | 1 + 30-50 reads |
| Signal-to-noise | ~90% relevant in top 20 | ~5% relevant overall |
| False positives | Low | Massive — "MCP" matches IDE plugin entries, "principal" matches CDI scope entries |

**Grep noise breakdown:**
- `labels/` ~120 — label index files
- `tools/` ~60 — Claude Code entries mentioning "MCP" in MCP server context
- `intellij-platform/` ~20 — IntelliJ MCP plugin entries
- `jvm/` ~100 — 15-20 relevant buried in 80+ irrelevant
- `casehub-*/protocols/` ~10 — 8 relevant (clinical RBAC)
- `quarkus/` ~12 — 3-4 relevant

**Top MCP hits (all directly actionable):**
- Clinical RBAC endpoint topology (score -0.4)
- Non-OIDC SecurityIdentity → MissingTenancyException (score -1.6) — exact trap for MCP tools
- Deny policy on /* blocks ALL requests (score -2.4)
- auth.enabled-in-dev-mode=false technique (score -2.6)
- @TestSecurity doesn't populate CurrentPrincipal.groups() (score -2.9)

**Key finding — grep matches keywords, not concepts:** "MCP" in "RBAC for MCP tools" is semantically different from "MCP" in "IntelliJ MCP plugin ide_find_references timeout." Grep can't distinguish them. The cross-encoder can. This is the strongest case yet for MCP over grep — 350 results with 5% relevance vs 50 results with 90% relevance.

**Assessment:** MCP is categorically better. Grep is not a viable fallback — it's a different tool for a different job. The only remaining value of grep is when the MCP server is genuinely unavailable, and even then the 350→50 read calls needed make it barely usable.

---

## Summary of findings across all reports

1. **Keywords are essential** — MCP without keywords misses 30-50% of relevant entries. With keywords, matches or exceeds grep coverage.
2. **Semantic search finds what grep can't** — entries that describe concepts without using the exact search terms (Report 4).
3. **Grep finds what semantic can't** — recent code changes not in the garden (Report 1), entries with exact but unusual terms.
4. **Score filtering was too aggressive** — fixed in #67/#68. Cross-encoder penalizes semantically distant but keyword-matching entries.
5. **Connection instability is the main remaining gap** — Qdrant down (#65 fixed with diagnostic) and intermittent failures (Report 4).
6. **MCP is dramatically more efficient** — 1 query vs 4+ greps, seconds vs minutes, content inline vs paths-then-read.
7. **Adjacent knowledge is the hardest gap** — MCP finds entries semantically close to the query but misses entries in the same problem space with different vocabulary (Report 5). CDI observer patterns, test profile exclusions, and event path coverage are adjacent to "case status FAULTED" but use entirely different terms.
8. **Grep comparison methodology matters** — Claude doesn't always know where the garden lives and greps the wrong directory, producing false "grep found nothing" results (Report 6). All comparisons must target `~/.hortora/garden`.
9. **MCP is a strict superset for cross-project knowledge** — when keywords are project-specific (class names that don't appear in garden entries), grep finds nothing while MCP's semantic layer bridges the vocabulary gap (Report 7). Manual fallback only needed when MCP server is down.
10. **At scale, grep is not viable** — 350 results with 5% relevance vs 50 results with 90% relevance (Report 8). Polysemous terms ("MCP", "principal") cause massive false positives that grep cannot filter. The cross-encoder distinguishes semantic contexts that keyword matching cannot.
