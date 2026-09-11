# CBR Paradigms, Storage Analysis, and Use Case Mapping

**Date:** 2026-06-30
**Purpose:** Background research and analysis informing the CBR retrieval architecture design (issue #20). Reference material — not an implementation spec.

---

## 1. CBR Paradigm Taxonomy

The CBR literature (Aamodt & Plaza 1994, Bergmann et al. 2005, Cunningham 2009) identifies seven distinct paradigms.

### 1.1 Paradigm Grid

| Paradigm | Case Representation | Retrieval / Similarity | Adaptation Method | Reference Systems | CaseHub Relevance |
|---|---|---|---|---|---|
| **Textual CBR (TCBR)** | NL text; optionally template-extracted | Embedding cosine, TF-IDF, BM25 | Null (classify/rank), template slot-fill | CBR-RAG (Wiratunga 2024), SOPHIA | Already implemented via `CaseMemoryStore.query(question, RELEVANCE)` + `CbrCaseEntry` |
| **Feature-Vector CBR** | Flat typed attributes (categorical, numeric, boolean) with optional value taxonomies | k-NN with local-global similarity: per-attribute functions, weighted average | Null (classification), substitutional, parameter adjustment | PATDEX, Aha's IB-series | Engine#478's `CbrQuery` with `Map<String, Object>` features — the immediate target |
| **Plan-Based CBR (Case-Based Planning)** | Ordered steps with preconditions/effects, goal hierarchies, causal links | Plan-space retrieval: match on goals and subgoal decompositions | **Transformational** (modify retrieved plan) or **Derivational** (replay reasoning trace) | **CHEF** (Hammond), PRODIGY/ANALOGY, SPA, HICAP | CaseHub plan trace retrieval — plans composed of Bindings → Capabilities → Workers |
| **Structural / Object-Oriented CBR** | Typed object graphs with is-a/part-of hierarchies, relational attributes | Structural similarity across object graphs respecting class hierarchies | Substitution within object hierarchies, constraint propagation | CREEK (Aamodt), **Déjà Vu** (Smyth), FABEL | CaseHub case definitions are compositional hierarchies (Bindings → BindingTarget → Capability) |
| **Graph-Based / Process-Oriented CBR (POCBR)** | Semantic labeled directed graphs: task, data, control-flow nodes and edges | Graph edit distance, subgraph matching, GNN embeddings | Workflow stream decomposition, learned insert/delete operators | Bergmann & Müller POCBR, CODAW, CBRFlow | CaseHub plans as DAGs — stages, milestones, binding dependencies |
| **Conversational CBR (CCBR)** | Incrementally revealed features via dialogue | Interactive question-driven narrowing | Typically null — focuses on retrieval precision | NaCoDAE (Aha & Breslow), HICAP | Orthogonal to representation — changes the retrieval interaction model |
| **Knowledge-Intensive / Exemplar CBR** | Thin cases + deep domain causal models | Semantically-guided: domain model propagates features to causal states | Model-guided: causal model identifies what can change and how | PROTOS, **CASEY**, CREEK | Trust maturity model could inform similarity — surface-similar but causally different cases |

### 1.2 Adaptation Taxonomy (Kolodner 1993, Wilke & Bergmann 1998)

| Method | Description | CaseHub Mapping |
|--------|-------------|-----------------|
| **Null adaptation** | Apply retrieved solution as-is | Classification, direct routing reuse, implementation selection |
| **Transformational** | Directly modify the retrieved solution — substitution, parameter adjustment, structural insertion/deletion | CHEF model: swap worker, adjust priority, skip/add bindings |
| **Derivational** | Replay the problem-solving *trace* in the new context | PRODIGY model: re-derive the plan using retrieved reasoning steps |

### 1.3 Similarity Mechanism Taxonomy (Cunningham 2009)

| Mechanism | How it compares | CaseHub Implementation |
|-----------|----------------|----------------------|
| **Direct** | Feature-vector metrics (Euclidean, weighted k-NN, overlap) | Qdrant payload filters, in-memory field matching |
| **Transformation-based** | Edit distance, tree edit, DTW | Plan-trace adaptation cost (future) |
| **Information-theoretic** | Entropy, compression distance | Not currently planned |
| **Emergent / ML-based** | Learned similarity from neural embeddings, kernels | Dense embedding via ONNX models, mem0 vector search |

### 1.4 Richter's Knowledge Containers

1. **Vocabulary knowledge** — representation language (our feature schema)
2. **Case knowledge** — the cases themselves (CbrCase instances in CaseMemoryStore)
3. **Similarity knowledge** — how to compare (CbrFeatureSchema field types + matching rules)
4. **Adaptation knowledge** — how to modify (engine's Revise step — future)

The containers are complementary: enriching one reduces the burden on others.

---

## 2. CaseHub's Current State

### 2.1 Degenerate CBR Diagnosis (GE-20260612-bd3b4d)

CaseHub runs Retain + Reuse only. Trust-scored routing improves with repetition of identical situations but cannot generalise across similar ones:

| CBR Step | Present? | Current Implementation |
|----------|----------|----------------------|
| **Retain** | Partial | Ledger attestations (trust scores) + `CbrCaseEntry` (platform#87, shipped) + `CaseOutcomeObserver` (engine#477, shipped) |
| **Retrieve** | Missing | Exact key lookup only — no feature-vector similarity |
| **Reuse** | Partial | `TrustWeightedAgentStrategy` (workers). Application-layer workarounds for implementations (QuarkMind `StrategyTrustRouter`) |
| **Revise** | Missing | No plan adaptation |

### 2.2 Existing Retain Infrastructure

| Component | Repo | Status |
|-----------|------|--------|
| `CbrCaseEntry` — `problem`, `solution`, `outcome`, `origin` | platform-api | Shipped (platform#87) |
| `CaseOutcomeObserver` — `onOutcome(CaseOutcomeEvent)` at case close | engine-api | Shipped (engine#477) |
| `CaseOutcomeEvent` — `caseType`, `caseId`, `caseFileSnapshot`, `outcomeLabel`, `closedAt` | engine-api | Shipped (engine#477) |

### 2.3 Three Independent Retrieval Systems

| System | Store | Query | Result | Similarity |
|--------|-------|-------|--------|-----------|
| **CaseMemoryStore** (platform) | `store(MemoryInput)` | `query(MemoryQuery)` with `question` + `RELEVANCE` | `Memory` | Text: mem0 vector, JPA FTS, SQLite FTS5, graphiti graph |
| **CaseRetriever** (neural-text) | `ingest(ChunkInput)` | `retrieve(RetrievalQuery, CorpusRef)` | `RetrievedChunk` | Hybrid: dense + SPLADE + BM25 RRF over Qdrant |
| **CBR** (gap) | — | — | — | — |

### 2.4 CaseMemoryStore Already Supports Textual CBR

`CaseMemoryStore.query()` with `question` + `MemoryOrder.RELEVANCE` + `CbrCaseEntry.from()` IS Textual CBR retrieval. No new SPI needed for this paradigm:

```java
List<Memory> memories = memoryStore.query(
    MemoryQuery.forEntity(entityId, CBR_DOMAIN, tenantId)
        .withQuestion(currentProblemDescription)
        .withOrder(MemoryOrder.RELEVANCE)
        .withLimit(topK));
List<CbrCaseEntry> cases = memories.stream().map(CbrCaseEntry::from).toList();
```

The gap is Feature-Vector and Plan-Based CBR — structured queries that `MemoryQuery` cannot express.

---

## 3. Storage Approach Analysis

Three approaches for Qdrant-backed CBR storage, each with different precision/complexity tradeoffs.

### 3.1 Approach 1 — Dense Embedding of Serialized Features

Serialize features to NL text, embed with dense model, vector search.

**Mechanism:** Already works via `CaseMemoryStore.query(question, RELEVANCE)` with mem0.

**Strength:** No new code. Handles NL-heavy features (incident narratives, patient symptoms).

**Weakness:** Categorical precision lost in embedding. Engine#28 benchmark proved embedding domain terms is unreliable (GE-20260629-63d619: SPLADE expands "ChatModel" to hotel/beauty/renovation). "Zerg roach rush" and "Protoss zealot rush" may embed close together despite requiring completely different responses.

**Best for:** Textual CBR. AML narratives. Clinical event descriptions.

### 3.2 Approach 2 — Explicit Feature Encoding

One-hot (categorical) + ordinal (numeric) encoding into fixed-dimension vectors. Cosine similarity.

```
opponent_race: Zerg=[1,0,0]  Protoss=[0,1,0]  Terran=[0,0,1]
game_phase:    EARLY=0.0     MID=0.5          LATE=1.0
army_ratio:    0.7 (raw float)
→ [1,0,0, 0.0, 0.7]
```

**Strength:** Exact categorical matching (one-hot gives cosine 1.0 for match, 0.0 for mismatch). Deterministic. No embedding ambiguity.

**Weakness:** Fixed vector schema per caseType. Different domains → different dimensions. Application must provide encoder. Qdrant collection vector size fixed at creation.

**Best for:** Feature-Vector CBR with well-known categorical schemas. QuarkMind.

### 3.3 Approach 3 — Payload Filters + Optional Dense Vector (Recommended)

Categorical/numeric features as Qdrant payload fields with keyword/float indexes. Text features optionally dense-embedded. Query combines exact filters + range filters + optional vector search.

```
Point:
  payload:
    caseType: "starcraft-game"        ← keyword index
    opponent_race: "Zerg"             ← keyword index
    army_size_ratio: 0.7              ← float index
    solution: "early-pressure"
    cbr.features: "{...json...}"
  vector:
    "dense": [0.12, -0.34, ...]       ← embedding of problem() text (optional)
```

**Strength:** Exact categorical precision. Numeric range queries. Dense vectors optional. Uses existing Qdrant infrastructure (`PayloadFilter` algebra from `rag-api`). Scales to millions. Feature schema drives index creation.

**Weakness:** Requires Qdrant. More complex than text search.

**Best for:** General case. Feature-Vector and Plan-Based CBR. Approaches 1 and 2 are special cases of this.

### 3.4 Backend Comparison Grid

| Backend | Textual CBR | Feature-Vector CBR | Plan-Based CBR | Scale | Sweet Spot |
|---------|------------|-------------------|---------------|-------|-----------|
| **inmem** | Basic text match | In-memory field matching | In-memory field matching | Hundreds | Testing, dev, QuarkMind dev |
| **JPA** (PostgreSQL) | `ts_rank` FTS | SQL WHERE on attributes (needs extension) | SQL WHERE (needs extension) | Thousands | Structured queries, existing PostgreSQL |
| **SQLite** | FTS5 MATCH | SQL WHERE (needs extension) | SQL WHERE (needs extension) | Thousands | Single-process, edge |
| **mem0** | Vector embeddings (semantic) | Not supported (text only) | Not supported | Tens of thousands | Dense semantic similarity, NL-heavy |
| **graphiti** | Temporal graph query | Entity relationship matching | Partial (graph proximity) | Thousands | Temporal patterns, relationship-rich |
| **Qdrant** (NEW) | Dense embedding | Payload filters + range | Payload filters + trace payload | Millions | **General purpose** — all paradigms at scale |

---

## 4. Use Case Mapping

### 4.1 QuarkMind (StarCraft II Game AI)

**CBR Paradigm:** Feature-Vector CBR → Plan-Based CBR

**Feature Vector:**
```java
Map.of(
    "opponent_race", "Zerg",           // Categorical
    "detected_build", "ROACH_RUSH",    // Categorical
    "game_phase", "EARLY",             // Categorical
    "army_size_ratio", 0.7,            // Numeric [0.0, 3.0]
    "resource_advantage", -200         // Numeric [-5000, 5000]
)
```

**Retrieval:** Payload filter on `opponent_race=Zerg`, `detected_build=ROACH_RUSH`, range on `army_size_ratio`. Returns top-5 similar past games with outcomes.

**Plan trace (Tier 2):** "4/5 similar games activated bindings scout → assess-threat → early-pressure with worker `aggressive-macro` at priority 1. Those 4 won."

**Recommended backend:** inmem for dev/test; Qdrant for production.

### 4.2 AML (Anti-Money Laundering Investigation)

**CBR Paradigm:** Textual CBR + Feature-Vector CBR

**Feature Vector:**
```java
Map.of(
    "transaction_pattern", "STRUCTURING",     // Categorical
    "entity_risk_tier", "HIGH",               // Categorical
    "jurisdiction", "CYPRUS",                  // Categorical
    "amount_range", "50K-100K",               // Categorical (bucketed)
    "prior_sars_on_entity", 2,                // Numeric
    "investigation_narrative", "Subject..."   // Text
)
```

**Retrieval:** Payload filter on `transaction_pattern`, `entity_risk_tier`, `jurisdiction`; dense embedding on `investigation_narrative`. Returns: "8 similar past investigations, 6 resulted in SAR filing."

**Recommended backend:** JPA for structured queries + Qdrant for narrative similarity.

### 4.3 Clinical (Adverse Event Investigation)

**CBR Paradigm:** Feature-Vector CBR + Knowledge-Intensive CBR

**Feature Vector:**
```java
Map.of(
    "adverse_event_type", "HEPATOTOXICITY",   // Categorical
    "trial_arm", "TREATMENT",                 // Categorical
    "severity_grade", 3,                      // Numeric [1-5]
    "time_to_onset_days", 14,                 // Numeric
    "event_description", "Patient presented..." // Text
)
```

**Retrieval:** Payload filter on `adverse_event_type`, `trial_arm`; range on `severity_grade >= 3`. Returns: "4 similar past AEs, all triggered safety protocol."

**Note:** Surface-similar AEs may have different causal mechanisms. Knowledge-Intensive CBR (domain causal model) is future work.

**Recommended backend:** Qdrant for clinical narrative similarity + payload filtering.

### 4.4 DevTown (PR Review)

**CBR Paradigm:** Textual CBR + Feature-Vector CBR

**Feature Vector:**
```java
Map.of(
    "language", "JAVA",                       // Categorical
    "change_type", "REFACTOR",                // Categorical
    "files_changed", 12,                      // Numeric
    "lines_changed", 450,                     // Numeric
    "pr_description", "Refactors the auth..." // Text
)
```

**Retrieval:** Filter on `language`, `change_type`; range on `files_changed`. Returns: "Similar past PRs required 2 reviewers, common finding was missing transaction boundaries."

**Recommended backend:** mem0 for description similarity; Qdrant for structured matching at scale.

---

## 5. Academic References

- Aamodt, A. & Plaza, E. (1994). Case-Based Reasoning: Foundational Issues. *AI Communications*, 7(1), 39-59.
- López de Mántaras, R. et al. (2005). Retrieval, Reuse, Revision, and Retention in CBR. *KER*, 20(3), 215-240.
- Cunningham, P. (2009). A Taxonomy of Similarity Mechanisms for CBR. *IEEE TKDE*, 21(11), 1532-1543.
- Bergmann, R. et al. (2005). Representation in Case-Based Reasoning. *KER*, 20(3), 209-213.
- Bergmann, R. & Müller, G. (2017). Process-Oriented Case-Based Reasoning. *ICCBR 2017*.
- Borrajo, D. et al. (2015). Progress in Case-Based Planning. *ACM Computing Surveys*, 47(2).
- Hammond, K. (1986). CHEF: A Model of Case-Based Planning. *AAAI-86*.
- Kolodner, J. (1993). *Case-Based Reasoning*. Morgan Kaufmann.
- Wilke, W. & Bergmann, R. (1998). Techniques and Knowledge Used for Adaptation. *EWCBR-98*.