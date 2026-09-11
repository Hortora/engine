# CBR Adoption & Examples — Design Spec

**Date:** 2026-07-04
**Epic:** casehubio/neocortex (to be filed)
**Related:** parent#227, neocortex#81, aml#92, devtown#129, clinical#115, life#52, iot#48

## Problem

The CBR SPI (`CbrCaseMemoryStore`, `CbrCase` hierarchy, `CbrQuery`) is production-ready with
three backends (NoOp, InMemory, Qdrant). Per-app integration guides exist for four domains
(aml, clinical, devtown, engine). But adoption has friction:

1. No runnable example — app developers read guides but have no code to copy or run
2. Qdrant config keys discoverable only by reading `QdrantCbrConfig.java` source
3. `EmbeddingModel` dependency for dense vector search on `problem()` undocumented in guides
4. Qdrant-only vs Qdrant+CaseMemoryStore dual-storage modes undocumented from consumer side
5. InMemory backend scoped to `test` only — no dev-mode guidance for rapid prototyping
6. Top-level README introduction mentions only `inference-*` and `rag-*` — the `memory-*` module set is not in the opening summary
7. No guides for Life or IoT domains

## Solution

One `example-cbr` module with six domain demos producing practical output, plus documentation
updates closing every adoption gap. Forward-compatible with the neocortex CBR roadmap (#81,
phases 2-7) — seed data and demo structure accommodate future capabilities without rework.

## Approach: Unified Example Module

Single module `examples/example-cbr/` containing six demo classes. Follows the proven pattern
from `example-text-analysis`: static seed data, `run(dependency)` method, `printResults()`,
`main()` for CLI, smoke/integration test tiers.

This structure itself proves the SPI is domain-agnostic — same store instance, six schemas,
six result profiles.

### Backend Tiers

`main()` runs with `InMemoryCbrCaseMemoryStore` — no Docker, no models, instant startup.
InMemory provides categorical exact match and numeric range filtering only. All similarity
scores are 1.0 (no ranking). Text fields and `problem()` are ignored in matching. Demo
queries filter on 1-2 key categorical features for useful result sets; remaining schema
fields are scenario context used by Qdrant for similarity ranking.

Integration tests run with Qdrant + `OnnxEmbeddingModel` via Testcontainers. Qdrant
provides graded similarity scores, dense vector search on `problem()` and text fields,
and cross-category retrieval. Expected output in each demo below reflects InMemory
behavior. The integration test tier verifies the full retrieval quality story.

---

## Demo Domains

### 1. AML Investigation (`AmlInvestigationDemo`)

**Paradigm:** Textual + Feature-Vector
**Case type:** `FeatureVectorCbrCase`
**App epic:** aml#92

**Schema:**

```java
CbrFeatureSchema.of("aml-investigation",
    FeatureField.categorical("transaction_pattern"),   // STRUCTURING, LAYERING, SMURFING, ROUND_TRIP
    FeatureField.categorical("entity_risk_tier"),      // LOW, MEDIUM, HIGH, PEP
    FeatureField.categorical("jurisdiction"),           // ISO 3166-1 alpha-2
    FeatureField.categorical("amount_range"),           // 0-10K, 10K-50K, 50K-100K, 100K-500K, 500K+
    FeatureField.numeric("prior_sars_on_entity", 0, 100),
    FeatureField.text("investigation_narrative"));
```

**Seed cases (10):** Closed investigations across structuring/layering/smurfing patterns, multiple
jurisdictions, PEP and non-PEP entities. Realistic narratives: "Multiple cash deposits under
$10,000 across 3 branches within 48 hours, beneficial owner linked to shell company in Cyprus."
Outcomes: SAR_FILED, CLEARED, ESCALATED with confidence scores.

**Query scenario:** New STRUCTURING alert on HIGH-risk PEP entity in CY. Demo query
filters on `transaction_pattern` only — broader categorical match for useful InMemory
results. Integration tests add all features for Qdrant-ranked retrieval.

**Expected output (InMemory):**
```
AML Investigation — CBR Results
================================
Query: transaction_pattern=STRUCTURING — new PEP entity alert

4 similar investigations found (of 10 in case base)

  #1 [1.00] SAR_FILED — Structuring via 3 branches, PEP beneficial owner, Cyprus
            Evidence path: bank statement analysis → beneficial ownership → SAR
            Duration: 8 days

  #2 [1.00] SAR_FILED — Structuring $9K deposits, HIGH risk, Cyprus shell company
            Evidence path: transaction pattern → KYC gaps → SAR
            Duration: 14 days

  #3 [1.00] SAR_FILED — Structuring via cash deposits, PEP entity, United Kingdom
            Evidence path: transaction analysis → enhanced due diligence → SAR
            Duration: 12 days

  #4 [1.00] CLEARED — Structuring pattern but legitimate business (seasonal cash business)
            Evidence path: bank statement → business verification → cleared
            Duration: 6 days

Summary: 75% SAR filing rate for STRUCTURING cases.
         Most common first step: bank statement analysis (3/4).
         Average duration: 10 days.
```

**Future phases:** Phase 2 (#82) — weighted scoring ranks by pattern+jurisdiction relevance.
Phase 3 (#83) — semantic retrieval finds narratively similar cases across category boundaries.
Phase 4 (#84) — tracks which retrievals actually helped investigators.

---

### 2. Clinical Adverse Event (`ClinicalAdverseEventDemo`)

**Paradigm:** Feature-Vector
**Case type:** `FeatureVectorCbrCase`
**App epic:** clinical#115

**Schema:**

```java
CbrFeatureSchema.of("clinical-adverse-event",
    FeatureField.categorical("adverse_event_type"),    // MedDRA preferred terms
    FeatureField.categorical("trial_arm"),             // TREATMENT, CONTROL, OPEN_LABEL
    FeatureField.numeric("severity_grade", 1, 5),      // CTCAE grade 1-5
    FeatureField.numeric("time_to_onset_days", 0, 365),
    FeatureField.text("event_description"));
```

**Seed cases (10):** AE investigations — hepatotoxicity, nephrotoxicity, neutropenia across
treatment/control arms. Varying severity (grade 1-4) and onset timing (day 7 to day 180).
Outcomes: SAFETY_PROTOCOL, CLEARED, MONITORING with causality assessments.

**Query scenario:** New grade 3 hepatotoxicity in treatment arm at day 21. Demo query
filters on `adverse_event_type` and `trial_arm` (categorical features).

**Expected output (InMemory):**
```
Clinical Adverse Event — CBR Results
=====================================
Query: adverse_event_type=Hepatotoxicity, trial_arm=TREATMENT

3 similar adverse events found (of 10 in case base)

  #1 [1.00] SAFETY_PROTOCOL — Grade 3 hepatotoxicity, treatment arm, day 18
            Causality: Probable. Action: dose reduction + liver function monitoring
            Note: no concurrent hepatotoxic medications

  #2 [1.00] SAFETY_PROTOCOL — Grade 3 hepatotoxicity, treatment arm, day 24
            Causality: Possible. Action: treatment hold pending liver panel
            Note: concurrent statin use — different causal mechanism

  #3 [1.00] SAFETY_PROTOCOL — Grade 2 hepatotoxicity progressed to grade 3, treatment, day 14
            Causality: Probable. Action: dose modification protocol activated
            Note: rapid progression from grade 2 (day 10) to grade 3 (day 14)

Summary: 100% safety protocol trigger rate for similar events.
         Median onset: day 18. Severity range: grade 2-3.
         ⚠ 1 case involved concurrent statin — assess concomitant medications.
```

**Future phases:** Phase 6 (#88) — temporal trajectory: "patients with this AE profile at
day 21 progressed to grade 4 in 2 of 5 cases by day 45." Phase 7 (#93) — multi-scope:
DSMB sees trial-level patterns across sites.

---

### 3. DevTown PR Review (`DevtownPrReviewDemo`)

**Paradigm:** Textual + Feature-Vector
**Case type:** `FeatureVectorCbrCase`
**App epic:** devtown#129

**Schema:**

```java
CbrFeatureSchema.of("devtown-pr-review",
    FeatureField.categorical("language"),       // JAVA, KOTLIN, TYPESCRIPT, PYTHON
    FeatureField.categorical("change_type"),    // FEATURE, BUGFIX, REFACTOR, DOCS, TEST
    FeatureField.numeric("files_changed", 1, 1000),
    FeatureField.numeric("lines_changed", 1, 50000),
    FeatureField.text("pr_description"));
```

**Seed cases (10):** Completed PR reviews — mix of languages, change types, scales. Java
refactors, TypeScript features, Python bugfixes. Outcomes: APPROVED, CHANGES_REQUESTED with
reviewer counts, common findings, review durations.

**Query scenario:** New Java refactor, 45 files, 2800 lines, "Extract transaction handling
into a dedicated service layer." Demo query filters on `change_type=REFACTOR` only.

**Expected output (InMemory):**
```
DevTown PR Review — CBR Results
=================================
Query: change_type=REFACTOR — Java, 45 files, 2800 lines

5 similar past reviews found (of 10 in case base)

  #1 [1.00] CHANGES_REQUESTED — Java refactor, 52 files, 3100 lines
            "Extract persistence layer from service classes"
            Reviewers: 3. Finding: missing transaction boundaries at new layer edge
            Duration: 3 days

  #2 [1.00] APPROVED — Java refactor, 38 files, 2200 lines
            "Separate domain model from REST DTOs"
            Reviewers: 2. Finding: clean separation, minor naming issues
            Duration: 1.5 days

  #3 [1.00] CHANGES_REQUESTED — Java refactor, 41 files, 2900 lines
            "Extract auth middleware into dedicated module"
            Reviewers: 2. Finding: circular dependency introduced between modules
            Duration: 2.5 days

  #4 [1.00] APPROVED — Java refactor, 60 files, 4100 lines
            "Consolidate 3 payment services into unified payment gateway"
            Reviewers: 3. Finding: integration test coverage gaps
            Duration: 4 days

  #5 [1.00] CHANGES_REQUESTED — Kotlin refactor, 35 files, 1800 lines
            "Migrate repository layer to coroutines"
            Reviewers: 2. Finding: blocking calls remaining in suspend functions
            Duration: 2 days

Summary: 40% first-pass approval rate for similar refactors.
         Recommended reviewers: 2-3. Average review time: 2.6 days.
         Top risk: transaction/dependency boundaries at extraction points (3/5 cases).
```

**Future phases:** Phase 2 (#82) — weighted scoring makes files_changed/lines_changed contribute
proportionally. Phase 3 (#83) — semantic match on PR descriptions.

---

### 4. Life — Contractor Coordination (`LifeContractorDemo`)

**Paradigm:** Feature-Vector
**Case type:** `FeatureVectorCbrCase`
**App epic:** life#52

**Schema:**

```java
CbrFeatureSchema.of("life-contractor",
    FeatureField.categorical("job_type"),       // PLUMBING, ELECTRICAL, ROOFING, APPLIANCE, GENERAL
    FeatureField.categorical("urgency"),        // EMERGENCY, ROUTINE, PLANNED
    FeatureField.categorical("property_area"),  // KITCHEN, BATHROOM, EXTERIOR, HVAC, GENERAL
    FeatureField.categorical("cost_band"),      // UNDER_100, 100_250, 250_500, 500_1000, OVER_1000
    FeatureField.categorical("season"),         // WINTER, SPRING, SUMMER, AUTUMN
    FeatureField.text("job_description"));
```

**Seed cases (10):** Completed contractor jobs — boiler repairs, roof fixes, electrical work,
appliance installs. Realistic outcomes: COMPLETED_ON_TIME, DELAYED, OVERCHARGED, EXCELLENT
with contractor identifiers, actual costs, SLA adherence data.

**Query scenario:** New routine boiler repair request in winter. Demo query filters on
`job_type=PLUMBING` and `property_area=HVAC` (categorical features).

**Expected output (InMemory):**
```
Life Contractor Coordination — CBR Results
============================================
Query: job_type=PLUMBING, property_area=HVAC — boiler repair needed

4 similar past jobs found (of 10 in case base)

  #1 [1.00] COMPLETED_ON_TIME — Routine boiler service, winter, HVAC
            Contractor: ABC Heating. Cost: £165 (quoted £180). SLA: 48h, completed in 36h.
            "Annual boiler service plus replacement of pressure valve"

  #2 [1.00] COMPLETED_ON_TIME — Routine boiler repair, winter, HVAC
            Contractor: ABC Heating. Cost: £195 (quoted £200). SLA: 48h, completed in 24h.
            "Intermittent ignition failure — replaced ignitor and flame sensor"

  #3 [1.00] COMPLETED_ON_TIME — Routine boiler repair, autumn, HVAC
            Contractor: ABC Heating. Cost: £210 (quoted £200). SLA: 72h, completed in 48h.
            "Boiler losing pressure — repressurised and replaced expansion vessel"

  #4 [1.00] DELAYED — Routine boiler service, winter, HVAC
            Contractor: QuickFix Ltd. Cost: £240 (quoted £150). SLA: 48h, completed in 120h.
            "Boiler service — contractor delayed due to parts availability"

Summary: ABC Heating — 3 jobs, 100% on-time, avg cost £190, avg SLA adherence 100%.
         QuickFix Ltd — 1 job, delayed 3 days, cost 60% over quote.
         Suggested SLA: 48 hours (historical median: 36 hours).
         Suggested contractor: ABC Heating (trust score derived from outcomes).
```

**Future phases:** Phase 4 (#84) — outcome learning improves contractor ranking. Phase 5 (#85)
— plan adaptation suggests specific case plan steps (e.g. "call contractor → confirm parts
availability → schedule" based on past successful sequences).

---

### 5. IoT Situation Handling (`IotSituationDemo`)

**Paradigm:** Feature-Vector
**Case type:** `FeatureVectorCbrCase`
**App epic:** iot#48

**Schema:**

```java
CbrFeatureSchema.of("iot-situation",
    FeatureField.categorical("situation_type"),  // TEMPERATURE_ANOMALY, MOTION_UNEXPECTED, WATER_LEAK, SMOKE_DETECTED, POWER_OUTAGE
    FeatureField.categorical("device_class"),    // THERMOSTAT, CAMERA, SENSOR, ALARM, METER
    FeatureField.categorical("room_type"),       // LIVING, BEDROOM, KITCHEN, BATHROOM, GARAGE, EXTERIOR
    FeatureField.categorical("time_of_day"),     // MORNING, AFTERNOON, EVENING, NIGHT
    FeatureField.categorical("severity"),        // LOW, MEDIUM, HIGH, CRITICAL
    FeatureField.text("situation_description"));
```

**Seed cases (10):** Resolved IoT situations — temperature spikes, unexpected motion, water leaks,
smoke detections. Mix of genuine incidents and false positives. Outcomes: RESOLVED_AUTOMATICALLY,
OPERATOR_DISMISSED, ESCALATED, WORK_ITEM_CREATED.

**Query scenario:** Temperature anomaly from kitchen thermostat in the evening. Demo query
filters on `situation_type` and `room_type` (categorical features).

**Expected output (InMemory):**
```
IoT Situation Handling — CBR Results
======================================
Query: situation_type=TEMPERATURE_ANOMALY, room_type=KITCHEN

5 similar past situations found (of 10 in case base)

  #1 [1.00] OPERATOR_DISMISSED — Temperature spike, kitchen thermostat, evening
            "Kitchen temperature rose 8°C in 15 minutes — oven preheating for dinner"
            Resolution: false positive, operator dismissed within 2 minutes

  #2 [1.00] OPERATOR_DISMISSED — Temperature anomaly, kitchen thermostat, evening
            "Rapid temperature increase during cooking — opened window resolved"
            Resolution: false positive, auto-resolved after window opened

  #3 [1.00] OPERATOR_DISMISSED — Temperature spike, kitchen thermostat, afternoon
            "Temperature spike coincided with dishwasher steam cycle"
            Resolution: false positive, operator added suppression rule

  #4 [1.00] WORK_ITEM_CREATED — Temperature anomaly, kitchen thermostat, morning
            "Kitchen temperature dropped 5°C overnight — boiler pilot light out"
            Resolution: work item created, contractor dispatched, resolved in 4 hours

  #5 [1.00] ESCALATED — Temperature anomaly, kitchen thermostat, night
            "Sustained high temperature, no cooking activity — extractor fan failure"
            Resolution: escalated to maintenance, fan motor replaced

Summary: 60% false positive rate for kitchen temperature anomalies (cooking-related).
         40% genuine — boiler/ventilation issues requiring work items.
         Suggestion: if oven/hob active, auto-downgrade to LOW severity.
         If no cooking appliance active, maintain MEDIUM and alert operator.
```

**Future phases:** Phase 6 (#88) — temporal trajectory: "temperature anomalies persisting
beyond 30 minutes in this room are 90% genuine." Phase 2 (#82) — weighted scoring improves
false-positive suppression by weighting time_of_day and device_class.

---

### 6. QuarkMind Battle (`QuarkmindBattleDemo`)

**Paradigm:** Plan-Based
**Case type:** `PlanCbrCase`
**App epic:** parent#227 (Wave 4: quarkmind)

**Schema:**

```java
CbrFeatureSchema.of("quarkmind-battle",
    FeatureField.categorical("opponent_race"),     // ZERG, PROTOSS, TERRAN
    FeatureField.categorical("detected_build"),    // ROACH_RUSH, ZEALOT_RUSH, MARINE_PUSH, MACRO, UNKNOWN
    FeatureField.numeric("army_size_ratio", 0.0, 3.0),
    FeatureField.numeric("resource_advantage", -5000, 5000));
```

**Seed cases (10):** Completed StarCraft II games with full plan traces — 4-6 steps per game.
Bindings: scout, assess-threat, bunker-up, early-pressure, expand, counter-push, macro-up.
Workers: various unit compositions. Mix of wins and losses across matchups.

**Query scenario:** New game vs Zerg, detected ROACH_RUSH, army ratio 0.8, resource
advantage -200. Demo query filters on `opponent_race` and `detected_build` (categorical
features).

**Expected output (InMemory):**
```
QuarkMind Battle — CBR Results (Plan-Based)
=============================================
Query: opponent_race=ZERG, detected_build=ROACH_RUSH — army 0.8, resources -200

5 similar past games found (of 10 in case base)

  #1 [1.00] WIN — vs Zerg, Roach Rush, army 0.75, resources -150
     Plan trace:
       1. scout        → reconnaissance  → overlord-scout  → SUCCESS (pri 1)
       2. assess-threat → threat-analysis → zerg-analyzer   → SUCCESS (pri 2)
       3. bunker-up    → static-defence  → bunker-wall     → SUCCESS (pri 3)
       4. counter-push → offensive       → marine-medivac  → SUCCESS (pri 4)

  #2 [1.00] WIN — vs Zerg, Roach Rush, army 0.85, resources -300
     Plan trace:
       1. scout        → reconnaissance  → reaper-scout    → SUCCESS (pri 1)
       2. bunker-up    → static-defence  → bunker-wall     → SUCCESS (pri 2)
       3. early-pressure → offensive     → hellion-harass   → SUCCESS (pri 3)
       4. expand       → economy         → natural-expand   → SUCCESS (pri 4)
       5. counter-push → offensive       → bio-push        → SUCCESS (pri 5)

  #3 [1.00] WIN — vs Zerg, Roach Rush, army 0.70, resources -100
     Plan trace:
       1. scout        → reconnaissance  → scan-sweep      → SUCCESS (pri 1)
       2. bunker-up    → static-defence  → bunker-wall     → SUCCESS (pri 2)
       3. counter-push → offensive       → siege-tank-push → SUCCESS (pri 3)

  #4 [1.00] WIN — vs Zerg, Roach Rush, army 0.90, resources 100
     Plan trace:
       1. scout        → reconnaissance  → overlord-scout  → SUCCESS (pri 1)
       2. assess-threat → threat-analysis → zerg-analyzer   → SUCCESS (pri 2)
       3. early-pressure → offensive     → marine-pressure  → FAILURE (pri 3)
       4. bunker-up    → static-defence  → bunker-wall     → SUCCESS (pri 4)
       5. counter-push → offensive       → bio-push        → SUCCESS (pri 5)

  #5 [1.00] LOSS — vs Zerg, Roach Rush, army 0.65, resources -500
     Plan trace:
       1. expand       → economy         → natural-expand   → SUCCESS (pri 1)
       2. macro-up     → economy         → double-refinery  → SUCCESS (pri 2)
       3. counter-push → offensive       → marine-push      → FAILURE (pri 3)

Plan analysis:
  Win rate: 80% (4/5). Opening binding breakdown:
    scout:         4/5 games, 4/4 wins — present in every winning plan, absent from the loss
    bunker-up:     4/5 games, 4/4 wins — critical defensive response
    counter-push:  5/5 games, 4/4 wins — necessary to close out
    early-pressure: 2/5 games, 2/2 success but 1 failure recovered

  The 1 loss skipped scouting entirely and opened with economy (expand → macro-up).

  Suggested opening: scout (pri 1) → bunker-up (pri 2) → counter-push (pri 3).
```

**Future phases:** Phase 5 (#85) — plan adaptation SPI enables transformational adaptation:
modify the retrieved plan (swap a worker, adjust priority, skip/add bindings) rather than
null adaptation. Phase 2 (#82) — weighted scoring makes army_size_ratio and resource_advantage
contribute proportionally.

---

## Module Structure

```
examples/example-cbr/
  pom.xml
  src/main/java/io/casehub/neocortex/examples/cbr/
    AmlInvestigationDemo.java
    ClinicalAdverseEventDemo.java
    DevtownPrReviewDemo.java
    LifeContractorDemo.java
    IotSituationDemo.java
    QuarkmindBattleDemo.java
    CbrDemoRunner.java
  src/test/java/io/casehub/neocortex/examples/cbr/
    AmlInvestigationDemoTest.java         @Tag("smoke")
    ClinicalAdverseEventDemoTest.java     @Tag("smoke")
    DevtownPrReviewDemoTest.java          @Tag("smoke")
    LifeContractorDemoTest.java           @Tag("smoke")
    IotSituationDemoTest.java             @Tag("smoke")
    QuarkmindBattleDemoTest.java          @Tag("smoke")
    CbrDemoRunnerTest.java                @Tag("smoke")
    CbrIntegrationIT.java                 @Tag("integration"), @QuarkusTest
```

### Demo Class Pattern

Each demo class is `public final class` with:

- `static final CbrFeatureSchema SCHEMA` — domain feature schema
- Static seed data as `List<SeedCase>` inner records with realistic domain content
- `public static List<Result> run(CbrCaseMemoryStore store)` — registers schema, stores seeds,
  runs query, returns structured results
- `static void printResults(List<Result> results)` — formatted console output with
  domain-specific interpretation
- `public static void main(String[] args)` — wires `InMemoryCbrCaseMemoryStore`, calls
  `run()` then `printResults()`
- Inner `record Result(ScoredCbrCase<?> scored, ...)` — domain-specific result

**Platform parameters** used by all demos:
- `tenantId`: `"demo"` — real applications obtain from `CurrentPrincipal`
- `entityId`: per-case UUID — real applications use the entity's lifecycle ID
- `domain`: `new MemoryDomain("cbr-examples")` — real applications register a domain per app
- `caseId`: per-case UUID — real applications use the case's business ID (e.g., investigation ID)

These parameters are required by `CbrCaseMemoryStore.store()` and `CbrQuery.of()` but are
orthogonal to CBR schema design. The demos use fixed values; the comments explain what real
applications substitute.

QuarkMind additionally includes plan trace analysis logic in `printResults()` — binding
frequency across wins/losses, opening pattern extraction.

### CbrDemoRunner

Runs all six demos sequentially with domain headers. Single `main()` entry point that
demonstrates the SPI is domain-agnostic: one `InMemoryCbrCaseMemoryStore` instance, six
schemas registered, six queries run.

### Test Tiers

**Smoke (`-Pexamples-smoke`):** `InMemoryCbrCaseMemoryStore`. Verifies:
- All six demos run without error (store/retrieve round-trip)
- Schema registration and query validation work (bad inputs rejected)
- Result counts match expected (categorical exact-match filtering)
- All scores are 1.0 (InMemory provides no similarity ranking)
- QuarkMind results include plan traces with expected structure
- No Docker, no models, runs in seconds

**Integration (`-Pexamples`):** `@QuarkusTest` with Testcontainers Qdrant + real
`OnnxEmbeddingModel`. Verifies:
- Dense vector search on `problem()` — semantically similar queries rank higher than
  categorical-only matches
- Qdrant payload filters work end-to-end
- `minSimilarity` threshold filtering works
- `notBefore` temporal filtering works
- Plan trace round-trip through Qdrant preserves full structure

Uses `langchain4j-embeddings-all-minilm-l6-v2` which bundles the ONNX model in the jar —
no `download-maven-plugin` needed. This differs from `example-rag-pipeline` which downloads
models separately because it also needs SPLADE and reranker models; the CBR example only
needs dense embeddings for `problem()` similarity.

### Dependencies

```xml
<!-- Compile -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-api</artifactId>
</dependency>

<!-- Runtime (CDI wiring) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Test — smoke -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-cbr-inmem</artifactId>
    <scope>test</scope>
</dependency>

<!-- Test — integration only (activated by examples profile) -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-qdrant</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-embeddings-all-minilm-l6-v2</artifactId>
    <scope>test</scope>
</dependency>
```

---

## Infrastructure Gap Closures

### 1. `docs/cbr/README.md` Updates

**Dense Vector Search section:**
- `CbrQuery.withProblem(text)` triggers embedding-based similarity when an `EmbeddingModel`
  CDI bean is available
- Without `EmbeddingModel`, queries fall back to payload-filter-only with synthetic 1.0 scores
- Maven dependency for LangChain4j ONNX embedding model
- Code snippet showing CDI producer for `EmbeddingModel`

**Dual Storage section:**
- Qdrant-only mode (default): CBR data lives in Qdrant only
- Qdrant+CaseMemoryStore: when a platform `CaseMemoryStore` bean is on the classpath,
  `QdrantCbrBeanProducer` delegates durable storage to it
- Recommendation: start with Qdrant-only; add CaseMemoryStore when you need the platform's
  memory query APIs alongside CBR

**Configuration Reference section:**
```properties
# Qdrant CBR configuration
casehub.memory.cbr.qdrant.host=localhost
casehub.memory.cbr.qdrant.port=6334
# casehub.memory.cbr.qdrant.api-key=           # optional, for authenticated Qdrant
# casehub.memory.cbr.qdrant.use-tls=false      # enable for production
casehub.memory.cbr.qdrant.collection-prefix=cbr
casehub.memory.cbr.qdrant.dense-vector-name=dense
casehub.memory.cbr.qdrant.max-retries=3
```

**Dev Mode section:**

InMemory is for **compile-time validation and SPI wiring** in local development — it
verifies schema registration, store/retrieve round-trip, and query validation. It does
not provide similarity ranking (all scores 1.0), text matching, or persistence. For
retrieval quality evaluation, use Qdrant via Testcontainers or Docker.

```properties
# Use InMemory backend in dev mode (no Docker needed)
%dev.quarkus.arc.selected-alternatives=io.casehub.neocortex.memory.cbr.inmem.InMemoryCbrCaseMemoryStore
```
With Maven dependency at `runtime` scope (not just `test`).

**App-Specific Guides table:** Updated to include Life and IoT.

### 2. New Guides

**`docs/cbr/guide-life.md`** — Contractor coordination CBR. Same structure as existing guides:
Why CBR, Paradigm, Schema, Retain, Retrieve, Mock→Production, Maven Dependencies.

**`docs/cbr/guide-iot.md`** — IoT situation handling CBR. Same structure. Includes
false-positive suppression as a concrete use case.

### 3. Existing Guide Updates (aml, clinical, devtown, engine)

Each guide gets:
- "Dense Vector Search" note with pointer to README.md section
- `application.properties` snippet for Qdrant config
- "Roadmap" section listing which neocortex phases enhance their domain

### 4. Top-Level README

Update introduction paragraph to mention all three module sets (`inference-*`, `rag-*`,
`memory-*`). The body already has a full `memory-*` section with CBR description and
module table — the gap is only the opening summary.

---

## Issue Breakdown

### Wave 1 — Documentation (no code, unblocks app teams)

| Issue | Description | Scale | Complexity |
|-------|-------------|-------|------------|
| A | `docs/cbr/README.md` — dense vector search, dual storage, config reference, dev mode | S | Low |
| B | `docs/cbr/guide-life.md` + `docs/cbr/guide-iot.md` — new domain guides | S | Low |
| C | Existing guide updates (aml, clinical, devtown, engine) — EmbeddingModel, config, roadmap | S | Low |
| D | Top-level README — CBR Memory section | XS | Low |

All Wave 1 issues are independent and can be parallelised.

### Wave 2 — Example Module

| Issue | Description | Scale | Complexity |
|-------|-------------|-------|------------|
| E | `example-cbr/` module scaffold — pom.xml, profiles, parent pom updates | S | Low |
| F | Feature-Vector demos (AML, Clinical, DevTown, Life, IoT) + smoke tests | M | Low |
| G | Plan-Based demo (QuarkMind) + smoke test — PlanCbrCase, plan trace analysis | S | Med |
| H | CbrDemoRunner + smoke test — runs all six, unified output | XS | Low |
| I | Integration tests — @QuarkusTest, Qdrant Testcontainers, EmbeddingModel | M | Med |

Dependencies: E → F, G, H (scaffold first, demos parallel) → I (integration tests last).

### Wave 3 — Cross-references

| Issue | Description | Scale | Complexity |
|-------|-------------|-------|------------|
| J | Link example-cbr from app-side epics — comment on aml#92, devtown#129, clinical#115, life#52, iot#48, parent#227 | XS | Low |
| K | TextualCbrCase demo — Qdrant-only, deferred to a future spec targeting semantic similarity scenarios (filed as neocortex#TBD) | S | Med |

After Wave 2 merges. Issue K is independent and can be filed at any time.

### Dependency Graph

```
Wave 1: A, B, C, D  (all independent)
Wave 2: E → F, G, H (parallel) → I
Wave 3: J (after Wave 2)
```

---

## Forward Compatibility with Neocortex Roadmap (#81)

The seed data and demo structure accommodate future phases. Each phase requires a new
platform capability (#82–#93) and a corresponding demo method — but the seed data and
demo class structure are designed once with enough richness to serve all phases:

| Phase | Neocortex Issue | What Changes in Examples |
|-------|----------------|------------------------|
| 2 — Weighted similarity | #82 | Add weighted query demo method; existing seed data works as-is |
| 3 — Semantic retrieval | #83 | Existing `problem()` text in seed cases becomes the semantic search target |
| 4 — Outcome learning | #84 | Add outcome tracking demo; existing outcome fields in seed data feed it |
| 5 — Plan adaptation | #85 | QuarkMind demo gains an adaptation method; existing plan traces are the input |
| 6 — Temporal trajectory | #88 | Clinical/IoT demos gain trajectory methods; existing time fields are the signal |
| 7 — Hierarchical scoping | #93 | Clinical demo gains multi-scope query; existing tenant/domain structure supports it |

The principle: seed data is designed once with enough richness to serve future phases. Each
phase adds a new demo method or query variant — it does not require new seed data or structural
changes.

---

## Success Criteria

- [ ] Running `java -cp ... CbrDemoRunner` prints all six domain results to stdout
- [ ] `mvn test -Pexamples-smoke` passes — InMemory backend, no Docker, under 10 seconds
- [ ] `mvn test -Pexamples` passes — Qdrant Testcontainers, dense vector assertions pass
- [ ] A developer reading any demo class can see: schema, seed data, query, results, interpretation
- [ ] A developer copying any demo class has a working InMemory CBR integration for local development
- [ ] `docs/cbr/README.md` answers: how to configure Qdrant, how to enable dense vector search,
  what dual storage means, how to use InMemory in dev mode
- [ ] Every app with a CBR epic (aml, clinical, devtown, life, iot, quarkmind) has either a
  guide or is covered by the engine guide
- [ ] Top-level README mentions CBR
