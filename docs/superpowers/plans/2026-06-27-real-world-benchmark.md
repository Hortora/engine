# Real-World Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a comparison report measuring gardenSearch vs grep on real GitHub issues and design specs, using the three-way methodology defined in the spec.

**Architecture:** Claude-as-evaluator runs three searches per scenario (grep keywords, gardenSearch keywords, gardenSearch NL), scores each result 0/1/2, and writes the report with precision metrics, win verdicts, and bias disclosures. The engine must be running with Qdrant and Ollama throughout.

**Tech Stack:** Hortora engine (Quarkus MCP), Qdrant, Ollama nomic-embed-text, `gh` CLI, `git grep`

## Global Constraints

- Engine runs in dense-only mode (no ONNX models configured)
- grep keyword extraction follows work-start Step 3b exactly: 2-4 keywords from issue title/description
- grep pathspec: `'*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'`
- grep results capped at first 20; evaluator reads full entry content before scoring
- gardenSearch returns up to 8 ranked results
- Scoring rubric: 0 = noise, 1 = tangentially related, 2 = directly relevant
- Win criterion: precision (relevant/total) + discovery (unique score=2 finds)
- All issue references use `gh issue view` with `--repo` flag
- Report output: `docs/comparison/real-world-benchmark.md`
- Every commit references #27

---

### Task 1: Infrastructure Verification and Corpus Baseline

**Files:**
- Create: `docs/comparison/real-world-benchmark.md` (Configuration section only)

**Interfaces:**
- Consumes: running engine, Qdrant, Ollama
- Produces: confirmed corpus point count, search configuration metadata for all subsequent tasks

- [ ] **Step 1: Verify Qdrant is running**

```bash
curl -s http://localhost:6333/collections | python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d, indent=2))"
```

Expected: JSON listing collections including `hortora_garden`.

- [ ] **Step 2: Verify Ollama is running with nomic-embed-text**

```bash
ollama list | grep nomic-embed-text
```

Expected: `nomic-embed-text` listed with model size.

- [ ] **Step 3: Start the engine if not running**

```bash
./mvnw quarkus:dev
```

Wait for startup logs showing ingestion complete. If already running, skip.

- [ ] **Step 4: Call gardenStatus to get corpus size**

Call `gardenStatus` via the MCP tool (or `curl -s 'http://localhost:8080/mcp/sse'` session). Record the indexed entry count.

Do NOT assume 1,949 from #23 — the corpus may have grown. Record the actual number.

- [ ] **Step 5: Record Qdrant collection schema**

```bash
curl -s http://localhost:6333/collections/hortora_garden | python3 -c "import sys,json; d=json.load(sys.stdin); c=d['result']['config']; print(json.dumps(c, indent=2))"
```

Record: vector dimensions, distance metric, HNSW parameters.

- [ ] **Step 6: Count grep search surface**

```bash
git -C ~/.hortora/garden ls-files '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md' | wc -l
```

Record: total .md files grep would search. Also count label and summary files:

```bash
git -C ~/.hortora/garden ls-files '_summaries/*.md' '_summaries/**/*.md' | wc -l
```

```bash
git -C ~/.hortora/garden ls-files 'labels/*.md' | wc -l
```

- [ ] **Step 7: Write the Configuration section of the report**

Create `docs/comparison/real-world-benchmark.md` with:

```markdown
# Real-World Benchmark: gardenSearch vs grep

*Generated 2026-06-27 · Issue #27*

## Configuration

| Parameter | Value |
|-----------|-------|
| Search mode | Dense-only (no ONNX/SPLADE) |
| Embedding model | nomic-embed-text (Ollama) |
| Vector dimensions | <from Step 5> |
| Distance metric | <from Step 5> |
| Indexed points | <from Step 4> |
| gardenSearch result cap | 8 |
| grep result cap | 20 (of N total) |
| grep search surface | <from Step 6> .md files |
| gardenSearch search surface | <from Step 4> indexed entries |
| Label files in grep surface | <from Step 6> |
| Summary files in grep surface | <from Step 6> |
```

- [ ] **Step 8: Commit**

```bash
git add docs/comparison/real-world-benchmark.md
git commit -m "docs: real-world benchmark — configuration baseline

Refs #27"
```

---

### Task 2: Issue Selection (6 Issues)

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Methodology section with issue table)

**Interfaces:**
- Consumes: `gh` CLI access to casehub repos
- Produces: 6 selected issues (number, repo, tech band, title) used by Tasks 4-9

- [ ] **Step 1: Pull recent closed issues with substantial bodies across repos**

For each repo, list closed issues sorted by most recent, filtering for those with long bodies. Run each separately:

```bash
gh issue list --repo casehubio/engine --state closed --limit 30 --json number,title,body --jq '.[] | select(.body | length > 500) | "#\(.number) \(.title) [\(.body | length) chars]"'
```

```bash
gh issue list --repo casehubio/work --state closed --limit 30 --json number,title,body --jq '.[] | select(.body | length > 500) | "#\(.number) \(.title) [\(.body | length) chars]"'
```

```bash
gh issue list --repo casehubio/platform --state closed --limit 30 --json number,title,body --jq '.[] | select(.body | length > 500) | "#\(.number) \(.title) [\(.body | length) chars]"'
```

```bash
gh issue list --repo casehubio/ledger --state closed --limit 30 --json number,title,body --jq '.[] | select(.body | length > 500) | "#\(.number) \(.title) [\(.body | length) chars]"'
```

```bash
gh issue list --repo casehubio/desiredstate --state closed --limit 30 --json number,title,body --jq '.[] | select(.body | length > 500) | "#\(.number) \(.title) [\(.body | length) chars]"'
```

```bash
gh issue list --repo casehubio/ras --state closed --limit 30 --json number,title,body --jq '.[] | select(.body | length > 500) | "#\(.number) \(.title) [\(.body | length) chars]"'
```

- [ ] **Step 2: Map issues to technology bands**

Read PLATFORM.md and applications.md to understand the tech surface. For each candidate issue, read the full body to identify its primary technology domain. Map each to one of the six bands:

| Band | Example domains |
|------|----------------|
| Reactive / async | Mutiny, SmallRye, thread bridges, emitOn, blocking/reactive |
| CDI / module wiring | discovery, @Alternative, Jandex, SPIs, @LookupIfProperty |
| Persistence / migrations | JPA, Flyway, H2/Postgres, reactive Hibernate, Panache |
| REST / messaging | JAX-RS, CloudEvents, Qhorus channels, REST client |
| AI / LLM / inference | Claude agents, LangChain4j, ONNX, embeddings, MCP |
| Testing / CI | @QuarkusTest, ArchUnit, flaky tests, mocking, test infrastructure |

- [ ] **Step 3: Select one issue per band**

Pick one issue per band that:
- Has a detailed description (preferably a spec or structured problem description)
- Touches a domain where the garden has entries (check domain subdirectories)
- Spans different repos (avoid picking 4 from the same repo)

If a band has no good candidates, note this and document why.

- [ ] **Step 4: Record selections**

For each selected issue, record:
- Issue number and repo
- Tech band assignment
- One-line summary
- Why it was selected (brief)

- [ ] **Step 5: Write the Methodology section**

Append to the report the three-way comparison design, query derivation procedure, scoring rubric, grep cap and scoring procedure, search surface asymmetry, win criterion, and bias disclosure — all from the spec. Then add the issue selection table:

```markdown
## Selected Issues

| # | Repo | Band | Title |
|---|------|------|-------|
| casehubio/engine#N | engine | Reactive / async | ... |
| casehubio/work#N | work | CDI / module wiring | ... |
| ... | ... | ... | ... |
```

- [ ] **Step 6: Commit**

```bash
git add docs/comparison/real-world-benchmark.md
git commit -m "docs: real-world benchmark — methodology and issue selection

Refs #27"
```

---

### Task 3: Spec Selection (2 Specs)

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append spec selection to Methodology)

**Interfaces:**
- Consumes: workspace spec directories across casehub repos
- Produces: 2 selected specs (path, repo, scope type) used by Tasks 10-11

- [ ] **Step 1: Find specs with substantial technical domain content**

Search workspace spec directories for specs with detailed technical content. Check:

```bash
ls ~/claude/public/casehub/engine/specs/ 2>/dev/null
```

```bash
ls ~/claude/public/casehub/work/specs/ 2>/dev/null
```

```bash
ls ~/claude/public/casehub/platform/specs/ 2>/dev/null
```

Also check `docs/superpowers/specs/` in each project repo:

```bash
ls ~/claude/casehub/engine/docs/superpowers/specs/ 2>/dev/null
```

```bash
ls ~/claude/casehub/work/docs/superpowers/specs/ 2>/dev/null
```

```bash
ls ~/claude/casehub/platform/docs/superpowers/specs/ 2>/dev/null
```

- [ ] **Step 2: Read candidate specs and assess technical domain density**

For each candidate, read the spec and count distinct technical domains referenced (frameworks, libraries, platform patterns). Good candidates have 3+ distinct domains where garden gotchas could apply.

- [ ] **Step 3: Select one narrow and one broad spec**

- **Narrow:** single-module feature spec — tests domain-specific gotcha surfacing
- **Broad:** cross-module integration spec — tests cross-domain retrieval

Ensure the two specs are from different repos and cover different technology areas than each other (and ideally different from the issue bands to broaden coverage).

- [ ] **Step 4: Record selections and append to report**

```markdown
## Selected Specs

| Spec | Repo | Scope | Domains |
|------|------|-------|---------|
| `path/to/narrow-spec.md` | engine | Narrow (single-module) | CDI, ONNX, ... |
| `path/to/broad-spec.md` | work | Broad (cross-module) | Flyway, REST, Qhorus, ... |
```

- [ ] **Step 5: Commit**

```bash
git add docs/comparison/real-world-benchmark.md
git commit -m "docs: real-world benchmark — spec selection

Refs #27"
```

---

### Task 4: Issue Evaluation 1 — Reactive / async

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Issue 1 section)

**Interfaces:**
- Consumes: selected issue from Task 2, running engine, garden repo
- Produces: scored results section for this issue

This task is the template for Tasks 5-9. Each follows the same procedure.

- [ ] **Step 1: Read the issue**

```bash
gh issue view <N> --repo <REPO> --json title,body --jq '"\(.title)\n\n\(.body)"'
```

Understand what was built/fixed and why.

- [ ] **Step 2: Derive grep keywords (work-start Step 3b procedure)**

Extract 2-4 keywords from the issue title and description: domain name, library, framework, key concept. Document the exact keywords chosen and why.

Example: for an issue about Mutiny thread scheduling, keywords might be `Mutiny|thread|emitOn|scheduling`.

- [ ] **Step 3: Derive natural language query**

Write a natural language description of the problem/domain as a developer would describe it to gardenSearch. Document the exact query.

Example: `"reactive Mutiny thread scheduling — blocking operations on wrong thread"`

- [ ] **Step 4: Run grep**

```bash
git -C ~/.hortora/garden grep -il -E "keyword1|keyword2|keyword3" HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md' | head -20
```

Record: total result count (`| wc -l` without head) and the first 20 file paths.

For each of the 20 results, read the entry's full content (title + body from frontmatter). Score 0/1/2. Flag any `_summaries/` or `labels/` entries.

- [ ] **Step 5: Run gardenSearch with keywords**

Call `gardenSearch` MCP tool with the same keyword string from Step 2 (e.g. `"Mutiny thread emitOn scheduling"`). No domain filter.

Record: 8 results with ID, title, machine relevance, judgment score (0/1/2).

- [ ] **Step 6: Run gardenSearch with natural language**

Call `gardenSearch` MCP tool with the natural language query from Step 3.

Record: 8 results with ID, title, machine relevance, judgment score (0/1/2).

- [ ] **Step 7: Compute metrics and write section**

For each method, compute:
- Relevant count (score >= 1) and precision (relevant / total scored)
- Highly relevant count (score = 2) and precision (score=2 / total scored)

Identify overlap (entries found by multiple methods, matched by GE-ID) and unique finds.

Determine per-scenario verdict:
- **Precision win:** highest precision
- **Discovery win:** most unique score=2 finds
- **Overall:** clear win / advantage / tie

Note machine relevance vs judgment score patterns.

Write the section to the report:

```markdown
## Issue-Driven Results

### Issue 1: [Reactive / async] — casehubio/<repo>#<N>

**Summary:** <one-line>

**Keywords (work-start procedure):** `keyword1|keyword2|keyword3`
**Natural language query:** "<query>"

#### grep (keywords) — first 20 of <N> total

| # | ID | Title | Label/Summary? | Score |
|---|-----|-------|----------------|-------|
| 1 | GE-... | ... | no | 2 |
| ... | ... | ... | ... | ... |

**Precision:** X/20 relevant (Y%), Z/20 highly relevant (W%)

#### gardenSearch (keywords)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-... | ... | 0.67 | 2 |
| ... | ... | ... | ... | ... |

**Precision:** X/8 relevant (Y%), Z/8 highly relevant (W%)

#### gardenSearch (natural language)

| # | ID | Title | Relevance | Score |
|---|-----|-------|-----------|-------|
| 1 | GE-... | ... | 0.72 | 2 |
| ... | ... | ... | ... | ... |

**Precision:** X/8 relevant (Y%), Z/8 highly relevant (W%)

#### Analysis

**Overlap:** <N entries found by multiple methods>
**Unique to grep:** <entries, with scores>
**Unique to gardenSearch (keywords):** <entries, with scores>
**Unique to gardenSearch (NL):** <entries, with scores>

**Verdict:** <clear win / advantage / tie> for <method>. <1-2 sentence explanation>.
```

- [ ] **Step 8: Commit**

```bash
git add docs/comparison/real-world-benchmark.md
git commit -m "docs: real-world benchmark — issue 1 (reactive/async)

Refs #27"
```

---

### Task 5: Issue Evaluation 2 — CDI / module wiring

Same procedure as Task 4 with the CDI/module wiring issue from Task 2.

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Issue 2 section)

Follow Steps 1-8 from Task 4 exactly, substituting the CDI issue. Commit message: `"docs: real-world benchmark — issue 2 (CDI/module wiring)"`.

---

### Task 6: Issue Evaluation 3 — Persistence / migrations

Same procedure as Task 4 with the persistence/migrations issue from Task 2.

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Issue 3 section)

Follow Steps 1-8 from Task 4 exactly, substituting the persistence issue. Commit message: `"docs: real-world benchmark — issue 3 (persistence/migrations)"`.

---

### Task 7: Issue Evaluation 4 — REST / messaging

Same procedure as Task 4 with the REST/messaging issue from Task 2.

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Issue 4 section)

Follow Steps 1-8 from Task 4 exactly, substituting the REST issue. Commit message: `"docs: real-world benchmark — issue 4 (REST/messaging)"`.

---

### Task 8: Issue Evaluation 5 — AI / LLM / inference

Same procedure as Task 4 with the AI/LLM/inference issue from Task 2.

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Issue 5 section)

Follow Steps 1-8 from Task 4 exactly, substituting the AI issue. Commit message: `"docs: real-world benchmark — issue 5 (AI/LLM/inference)"`.

---

### Task 9: Issue Evaluation 6 — Testing / CI

Same procedure as Task 4 with the testing/CI issue from Task 2.

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Issue 6 section)

Follow Steps 1-8 from Task 4 exactly, substituting the testing issue. Commit message: `"docs: real-world benchmark — issue 6 (testing/CI)"`.

---

### Task 10: Spec Review 1 — Narrow Spec

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Spec Review 1 section)

**Interfaces:**
- Consumes: selected narrow spec from Task 3, running engine, garden repo
- Produces: scored results section for this spec

- [ ] **Step 1: Read the spec**

Read the selected narrow spec in full. Understand what it designs and which technical domains it touches.

- [ ] **Step 2: Extract technical domains**

Identify 2-4 distinct technical domains referenced in the spec (e.g. "CDI @LookupIfProperty conditional beans", "ONNX Runtime model loading", "Qdrant collection migration"). Document each domain and why it was chosen.

- [ ] **Step 3: Per domain — derive queries and run three-way search**

For each extracted domain, follow Task 4 Steps 2-6:
1. Derive 2-4 grep keywords (work-start procedure)
2. Derive a natural language query
3. Run grep, cap at 20, read and score each result
4. Run gardenSearch with keywords, score each result
5. Run gardenSearch with NL query, score each result

The scoring question for specs is: "would this entry have influenced the design or surfaced a gotcha the spec doesn't address?"

- [ ] **Step 4: Compute per-domain metrics**

For each domain, compute the same metrics as Task 4 Step 7: precision, unique finds, overlap, verdict.

- [ ] **Step 5: Write the spec review section**

```markdown
## Spec Review Results

### Spec 1: [Narrow] — `path/to/spec.md`

**Summary:** <what the spec designs>
**Domains extracted:** domain1, domain2, domain3

#### Domain 1: <domain name>

**Keywords:** `keyword1|keyword2`
**NL query:** "<query>"

[Same results tables as issue sections — grep, gardenSearch-keywords, gardenSearch-NL]

**Verdict:** ...

#### Domain 2: <domain name>

[Same structure]

#### Spec-Level Assessment

**Gotchas surfaced by gardenSearch but not grep:** <list>
**Gotchas surfaced by grep but not gardenSearch:** <list>
**Would these have influenced the design?** <assessment>
```

- [ ] **Step 6: Commit**

```bash
git add docs/comparison/real-world-benchmark.md
git commit -m "docs: real-world benchmark — spec review 1 (narrow)

Refs #27"
```

---

### Task 11: Spec Review 2 — Broad Spec

Same procedure as Task 10 with the broad (cross-module) spec from Task 3.

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Spec Review 2 section)

Follow Steps 1-6 from Task 10 exactly, substituting the broad spec. The broad spec may have more domains (up to 4). Commit message: `"docs: real-world benchmark — spec review 2 (broad)"`.

---

### Task 12: Summary and Recommendations

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (append Summary section)

**Interfaces:**
- Consumes: all scored results from Tasks 4-11
- Produces: final report with aggregate analysis

- [ ] **Step 1: Build the aggregate metrics table**

Aggregate across all scenarios (6 issues + all spec domains):

```markdown
## Summary

### Aggregate Metrics

| Method | Scenarios | Avg Precision (>=1) | Avg Precision (=2) | Total Unique Score=2 |
|--------|-----------|--------------------|--------------------|---------------------|
| grep (keywords) | N | X% | Y% | Z |
| gardenSearch (keywords) | N | X% | Y% | Z |
| gardenSearch (NL) | N | X% | Y% | Z |
```

- [ ] **Step 2: Build the win/loss/tie tally**

Count per-scenario verdicts across all scenarios:

```markdown
### Win / Loss / Tie

| Comparison | Method A Wins | Method B Wins | Ties |
|------------|--------------|--------------|------|
| grep vs gardenSearch-KW | X | Y | Z |
| grep vs gardenSearch-NL | X | Y | Z |
| gardenSearch-KW vs gardenSearch-NL | X | Y | Z |
```

- [ ] **Step 3: Write the retrieval + ranking benefit analysis**

Analyse grep vs gardenSearch-keywords across all scenarios. This comparison measures the combined benefit of switching retrieval mechanism (substring → embedding) and adding ranking. Note: these factors are confounded — the analysis shows the total benefit, not the ranking benefit alone.

Key questions:
- How often did gardenSearch-keywords surface entries that grep missed entirely?
- How often did grep's broader search surface (summaries, labels) produce noise?
- What was the precision difference?

- [ ] **Step 4: Write the query formulation benefit analysis**

Analyse gardenSearch-keywords vs gardenSearch-NL. This cleanly isolates the benefit of natural language queries over keyword queries when the retrieval mechanism is held constant.

Key questions:
- Did NL queries find entries that keyword queries missed?
- Did keyword queries sometimes outperform NL (more precise targeting)?
- For which tech bands did each query style excel?

- [ ] **Step 5: Write the machine relevance calibration summary**

Analyse the correlation between machine relevance scores (0.0-1.0) and judgment scores (0/1/2) across all gardenSearch results:

- What machine relevance threshold maps to judgment score >= 1?
- What machine relevance threshold maps to judgment score = 2?
- Are there outliers (high relevance, low judgment or vice versa)?

- [ ] **Step 6: Write the search surface asymmetry impact**

Count how many of grep's scored results came from `_summaries/` or `labels/` paths across all scenarios. Report: what fraction of grep's noise is attributable to searching a larger file set.

- [ ] **Step 7: Write recommendations**

Two categories:

1. **Skill migration recommendation** — based on the evidence, is completing the gardenSearch migration worth it? For which query styles? With what caveats?
2. **grep pathspec improvement** — recommend adding `:!_summaries/` and `:!labels/` to work-start Step 3b's pathspec to reduce noise, independent of whether gardenSearch migration proceeds.

- [ ] **Step 8: Commit**

```bash
git add docs/comparison/real-world-benchmark.md
git commit -m "docs: real-world benchmark — summary and recommendations

Refs #27"
```

---

### Task 13: Final Review and Close

**Files:**
- Modify: `docs/comparison/real-world-benchmark.md` (any corrections from review)

- [ ] **Step 1: Read the complete report end-to-end**

Verify:
- All 6 issues have complete three-way results
- Both specs have complete per-domain results
- All metrics are computed consistently
- Verdicts follow the defined win criterion
- Summary tallies match individual verdicts
- Bias disclosure is present in methodology
- Configuration section matches actual infrastructure used

- [ ] **Step 2: Fix any inconsistencies**

If any metrics don't add up or verdicts don't match the data, fix them.

- [ ] **Step 3: Commit corrections if any**

```bash
git add docs/comparison/real-world-benchmark.md
git commit -m "docs: real-world benchmark — final review corrections

Refs #27"
```

- [ ] **Step 4: Push branch**

```bash
git push -u origin issue-27-real-world-benchmark
```
