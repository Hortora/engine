# Real-World Benchmark: gardenSearch vs grep — Design Spec

*2026-06-26 · Revised after review (round 2)*

## Context

Issue #23 produced a synthetic benchmark comparing gardenSearch vs grep on six
predefined queries. The results were clear — vector search wins on natural
language and symptom queries, grep wins on exact IDs — but the queries were
hand-crafted, not derived from actual development work.

This benchmark measures whether the planned migration from grep to gardenSearch
(designed in the 2026-06-23 skill integration spec, partially deployed in
soredium commit `9103132`) is worth completing. No skill currently uses
gardenSearch in production — skills search the garden via `git grep` in
`work-start` Step 3b only. `java-dev` and `code-review` load specific approach
files (`approaches/testing.md`, `approaches/code-review.md`) but do not search
the garden broadly.

The core question: given a real GitHub issue or design spec, does gardenSearch
surface garden entries that would have helped more than the grep-based search
that `work-start` uses today?

## Scope

Two evaluation tracks, equal weight:

1. **Issue-driven search** (6 issues) — read the issue, derive queries using
   each method's natural input style, run both, score results
2. **Spec review search** (2 specs) — read a spec, extract technical domains,
   search for gotchas that would have influenced the design

## Issue Selection

Stratified sample across casehub repos. Selection criteria:

- **Closed issues with substantial descriptions** — long specs or detailed
  problem descriptions signal complexity where garden context matters
- **Technology diversity** — one issue per band, guided by PLATFORM.md and
  applications.md to ensure coverage across the tech surface

| Band | Example domains | Target repo(s) |
|------|----------------|-----------------|
| Reactive / async | Mutiny, SmallRye, thread bridges | engine, work |
| CDI / module wiring | discovery, @Alternative, Jandex, SPIs | platform, engine |
| Persistence / migrations | JPA, Flyway, H2/Postgres, reactive Hibernate | ledger, work |
| REST / messaging | JAX-RS, CloudEvents, Qhorus channels | work, platform |
| AI / LLM / inference | Claude agents, LangChain4j, ONNX, embeddings | platform, ras |
| Testing / CI | @QuarkusTest, ArchUnit, flaky tests, mocking | any |

## Spec Selection

Two specs from different repos with different architectural scopes. Selection
criteria:

- **Substantial technical domain content** — specs that touch specific
  frameworks, libraries, or platform patterns where gotchas are likely
- **Different architectural scope** — one narrow (single-module feature), one
  broad (cross-module integration) — to test domain-specific vs cross-domain
  retrieval

### Spec review procedure

For each spec:

1. **Extract technical domains** — read the spec, identify 2-4 distinct
   technical domains referenced (e.g. "reactive Mutiny chains", "Flyway
   migration ordering", "CDI @LookupIfProperty")
2. **Per domain, run three searches** — same three-way comparison as issue
   track (grep keywords, gardenSearch keywords, gardenSearch natural language)
3. **Score with same 0/1/2 rubric** — "would this entry have influenced the
   design or surfaced a gotcha the spec doesn't address?"
4. **Per-domain metrics** — same as per-issue: result counts, precision,
   relevant/highly relevant, unique finds
5. **Per-spec verdict** — which method surfaced gotchas the spec missed?

## Evaluation Methodology

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

### Search Surface Asymmetry

The two methods search different file sets:

- **gardenSearch** indexes ~1,934 entries: `.md` files with YAML frontmatter,
  excluding `_` prefixed directories. `FlatCorpusStore.list()` filters
  `!p.startsWith("_")`, removing `_summaries/` (1,909 files) and `_index/`.
  `GardenMetadataExtractor` returns empty content for files without frontmatter,
  effectively excluding label files (plain keyword lists, no frontmatter).

- **grep** searches ~4,669 `.md` files: its pathspec excludes only `GARDEN.md`,
  `CHECKED.md`, `DISCARDED.md`. It searches summaries, labels, and structural
  files that gardenSearch never indexes — a 2.4x larger file set, predominantly
  noise.

This asymmetry is a genuine characteristic of how the tools work today. grep's
broader surface inflates its result count with label files (e.g.
`labels/qdrant.md`) and summaries (e.g. `_summaries/jvm/GE-20260609-2abdfd.md`)
that score 0 or 1 in any relevance rubric. The benchmark documents this
difference rather than equalising it — the noise is what skills actually
experience. The report will note whether grep results include label/summary
files and whether this affects precision.

This is also a finding about the current grep command's quality: `work-start`
Step 3b could add `:!_summaries/` and `:!labels/` to its pathspec to reduce
noise. That improvement is out of scope for this benchmark but worth noting.

### Query Derivation

Queries are derived to match how each method would actually be used:

- **grep keywords:** follow `work-start` Step 3b's exact procedure — extract
  2-4 keywords from the issue title and description (domain name, library,
  framework, key concept). No free-form keyword crafting. Run as:
  `git -C ~/.hortora/garden grep -il -E "keyword1|keyword2" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'`
- **gardenSearch (keywords):** pass the same keyword string to gardenSearch
- **gardenSearch (natural language):** derive a natural language description of
  the problem or domain from the issue, the way the 2026-06-23 skill
  integration spec envisions skills calling gardenSearch

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

With three methods and multiple metrics, the summary needs a defined win
criterion to be reproducible.

**Per-scenario wins are judged on two dimensions:**

1. **Precision** — highest precision (relevant / total scored). Precision is
   the primary metric because it measures signal quality independent of the
   cap asymmetry (8 vs 20).
2. **Discovery** — most unique score=2 finds not surfaced by other methods.
   This measures whether a method finds entries the others miss entirely.

**Overall per-scenario verdict:**
- **Clear win:** highest precision AND most unique score=2 finds
- **Advantage:** highest precision OR most unique score=2 finds (but not both)
- **Tie:** precision within 10 percentage points and unique score=2 finds
  balanced (±1)

The summary aggregates per-scenario verdicts into an overall tally.

### Overlap Matching

Match entries across methods by GE-ID. Extract `GE-XXXXXXXX-XXXXXX` from grep
file paths (strip directory prefix and `.md`), match against gardenSearch
result IDs. Non-GE entries (approaches, protocols) match by full relative
path.

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

## Evaluator

Claude-as-evaluator. Claude IS the intended consumer of both grep and
gardenSearch in the skill workflow, so the right evaluator is Claude itself.

This is a directional benchmark — finger in the air. The bias chain above
means the results should be interpreted as "is the migration worth doing for
Claude-based workflows?" not "is vector search objectively better than grep?"

## Search Configuration

The engine runs in **dense-only mode** for this benchmark. ONNX model paths
are commented out in `application.properties`; `HybridSearchProducer` does
not produce `SparseEmbedder` or `CrossEncoderReranker` beans. Search uses
Ollama `nomic-embed-text` (768-dim) dense embeddings with Qdrant HNSW.

The report records the exact configuration (model, dimensions, Qdrant
collection schema) as metadata. Comparing dense-only vs hybrid is out of
scope — that's a separate benchmark after ONNX models are deployed.

## Deliverable

Single report at `docs/comparison/real-world-benchmark.md`.

### Report Structure

```
# Real-World Benchmark: gardenSearch vs grep

## Configuration
  Search mode, embedding model, corpus size (indexed point count),
  Qdrant collection schema, search surface sizes (gardenSearch vs grep)

## Methodology
  Selection criteria, three-way comparison design (what each comparison
  measures and what it confounds), query derivation procedure, scoring
  rubric, grep cap and scoring procedure, search surface asymmetry,
  win criterion, bias disclosure

## Issue-Driven Results (6 sections)
  Per issue:
    - Issue reference, repo, tech band, one-line summary
    - Keywords derived (work-start Step 3b procedure)
    - Natural language query derived
    - grep (keywords) → results table (ID, title, score) [first 20 of N]
      - Flag any _summaries/ or labels/ entries
    - gardenSearch (keywords) → results table (ID, title, relevance, score)
    - gardenSearch (NL) → results table (ID, title, relevance, score)
    - Overlap / unique finds across all three
    - Precision comparison (relevant/total per method)
    - Machine relevance vs judgment score observations
    - Per-scenario verdict (clear win / advantage / tie, per criterion)

## Spec Review Results (2 sections)
  Per spec:
    - Spec reference, domains extracted
    - Per domain: three-way search results with same metrics
    - Gotchas surfaced, design influence assessment
    - Per-domain verdict

## Summary
    - Aggregate metrics table (all three methods): precision, discovery,
      noise-floor counts
    - Win/loss/tie tally (per defined criterion)
    - Retrieval + ranking benefit (grep vs gardenSearch-keywords)
    - Query formulation benefit (gardenSearch-keywords vs gardenSearch-NL)
    - Search surface asymmetry impact (how many grep results were
      summaries/labels)
    - Machine relevance calibration summary
    - Recommendations for completing the skill migration
    - Recommendation: improve work-start grep pathspec to exclude
      _summaries/ and labels/
```

## Prerequisites

- Engine running with Qdrant in dense-only mode (current default)
- Ollama running with `nomic-embed-text`
- Garden corpus indexed — record actual point count (do not assume #23's number)
- Access to closed issues across casehub repos via `gh`

## Non-Goals

- Automated reproducible pipeline (future iteration)
- Human relevance judgments (future iteration)
- Cross-LLM evaluator comparison (future iteration)
- Dense-only vs hybrid configuration comparison (future iteration)
- Changes to the engine or gardenSearch — this is measurement only
