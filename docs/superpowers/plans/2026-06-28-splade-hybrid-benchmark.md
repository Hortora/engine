# SPLADE Hybrid Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a diagnostic benchmark harness that compares dense-only vs dense+SPLADE vs full-hybrid retrieval across 14 real-world scenarios, with SPLADE vocabulary analysis to diagnose failure modes.

**Architecture:** Three decoupled Python scripts — `run_queries.py` (query execution + latency), `splade_vocab.py` (ONNX vocabulary analysis), `analyze.py` (delta analysis + report generation). All share a common `queries.py` data module. Baseline scores extracted from the #27 report via `extract_baseline.py`.

**Tech Stack:** Python 3.12+, `onnxruntime`, `tokenizers`, `urllib.request` (stdlib HTTP)

## Global Constraints

- Engine REST API at `http://localhost:8080/search`
- Qdrant REST API at `http://localhost:6333`
- ONNX models at `~/.hortora/models/splade/` and `~/.hortora/models/reranker/`
- Baseline report at `docs/comparison/real-world-benchmark.md`
- All scripts in `scripts/benchmark/`, results in `scripts/benchmark/results/`
- Config names: `dense-only`, `dense+splade`, `full-hybrid`
- Python dataclasses for data structures, JSON for persistence
- No external dependencies except `onnxruntime` and `tokenizers` (for splade_vocab.py only)

---

### Task 1: Query Definitions Module

**Files:**
- Create: `scripts/benchmark/__init__.py`
- Create: `scripts/benchmark/queries.py`
- Test: `scripts/benchmark/test_queries.py`

**Interfaces:**
- Consumes: nothing (foundational module)
- Produces: `SCENARIOS: list[Scenario]` — 14 scenario objects with `id`, `type`, `description`, `context`, `tech_band`, `kw_query`, `nl_query`, `grep_pattern`, `baseline_verdict`, `failure_modes`

- [ ] **Step 1: Write the test**

```python
# scripts/benchmark/test_queries.py
from benchmark.queries import SCENARIOS, Scenario

def test_scenario_count():
    assert len(SCENARIOS) == 14

def test_issue_scenarios():
    issues = [s for s in SCENARIOS if s.type == "issue"]
    assert len(issues) == 6

def test_spec_domain_scenarios():
    domains = [s for s in SCENARIOS if s.type == "spec-domain"]
    assert len(domains) == 8

def test_all_scenarios_have_required_fields():
    for s in SCENARIOS:
        assert s.id, f"Missing id"
        assert s.kw_query, f"{s.id}: missing kw_query"
        assert s.nl_query, f"{s.id}: missing nl_query"
        assert s.grep_pattern, f"{s.id}: missing grep_pattern"
        assert s.failure_modes, f"{s.id}: missing failure_modes"
        assert s.baseline_verdict, f"{s.id}: missing baseline_verdict"
        assert s.tech_band, f"{s.id}: missing tech_band"
        assert s.context, f"{s.id}: missing context"

def test_failure_modes_are_valid():
    valid = {"VOCABULARY_GAP", "POLYSEMY", "DOMAIN_ABSENCE", "SEMANTIC_WIN", "UNAMBIGUOUS_TERM"}
    for s in SCENARIOS:
        for fm in s.failure_modes:
            assert fm in valid, f"{s.id}: invalid failure_mode '{fm}'"

def test_baseline_verdicts_are_valid():
    valid = {"grep-win", "gs-nl-win", "gs-kw-win", "gs-nl-advantage", "gs-kw-advantage",
             "gs-win", "grep-advantage", "grep-marginal", "tie"}
    for s in SCENARIOS:
        assert s.baseline_verdict in valid, f"{s.id}: invalid verdict '{s.baseline_verdict}'"

def test_scenario_ids_unique():
    ids = [s.id for s in SCENARIOS]
    assert len(ids) == len(set(ids))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/benchmark/test_queries.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'benchmark'`

- [ ] **Step 3: Create the queries module**

Create `scripts/benchmark/__init__.py` (empty file).

Create `scripts/benchmark/queries.py` with all 14 scenarios. The exact query texts come from `docs/comparison/real-world-benchmark.md`. Read the report and transcribe verbatim — do not paraphrase or abbreviate query strings.

```python
# scripts/benchmark/queries.py
from dataclasses import dataclass, field

@dataclass
class Scenario:
    id: str
    type: str              # "issue" | "spec-domain"
    description: str       # one-line summary
    context: str           # full issue/spec description for scoring calibration
    tech_band: str
    kw_query: str          # keyword query (work-start Step 3b)
    nl_query: str          # natural language query
    grep_pattern: str      # reference only (grep results from #27)
    baseline_verdict: str  # #27 result
    failure_modes: list[str] = field(default_factory=list)

SCENARIOS: list[Scenario] = [
    Scenario(
        id="issue-1-reactive-async",
        type="issue",
        description="CaseContextChangedEventHandler throws BlockingOperationNotAllowedException — JPA EntityManager calls on Vert.x event loop thread",
        context="casehubio/engine#542: CaseContextChangedEventHandler throws BlockingOperationNotAllowedException because JPA EntityManager calls execute on the Vert.x event loop thread. Fix: add @Blocking or dispatch via emitOn(Infrastructure.getDefaultWorkerPool()).",
        tech_band="reactive-async",
        kw_query="BlockingOperationNotAllowedException|Vert.x|IO thread|EntityManager",
        nl_query="JPA blocking operation on Vert.x IO thread causes BlockingOperationNotAllowedException when calling EntityManager from event handler",
        grep_pattern=r"BlockingOperationNotAllowedException|Vert\.x|IO thread|EntityManager",
        baseline_verdict="gs-nl-win",
        failure_modes=["SEMANTIC_WIN"],
    ),
    Scenario(
        id="issue-2-cdi-wiring",
        type="issue",
        description="NoOpGroupMembershipProvider @ApplicationScoped conflicts with @DefaultBean — AmbiguousResolutionException",
        context="casehubio/work#264: NoOpGroupMembershipProvider is @ApplicationScoped (effectively @Default) but should be @DefaultBean. When both it and platform's MockGroupMembershipProvider (@DefaultBean) are on classpath, Quarkus throws AmbiguousResolutionException.",
        tech_band="cdi-wiring",
        kw_query="DefaultBean|AmbiguousResolutionException|GroupMembershipProvider|ambiguous",
        nl_query="CDI ambiguous dependency resolution when @Default bean conflicts with @DefaultBean in multi-module Quarkus application",
        grep_pattern=r"DefaultBean|AmbiguousResolutionException|GroupMembershipProvider|ambiguous",
        baseline_verdict="grep-win",
        failure_modes=["VOCABULARY_GAP"],
    ),
    Scenario(
        id="issue-3-persistence-migrations",
        type="issue",
        description="LedgerEntry.tenancyId field shadows subclass field — Hibernate persist fails with NOT NULL violation",
        context="casehubio/ledger#131: LedgerEntry.tenancyId added to base class shadows same-named field on JOINED-inheritance subclasses. Hibernate bytecode enhancement makes two fields invisible to Java, but em.persist() reads base field via reflection (null), violating NOT NULL constraint.",
        tech_band="persistence-migrations",
        kw_query="tenancyId|JOINED|shadowing|LedgerEntry",
        nl_query="JPA JOINED inheritance field shadowing — subclass field shadows base class column causing Hibernate persist failure with NOT NULL constraint violation",
        grep_pattern=r"tenancyId|JOINED|shadowing|LedgerEntry",
        baseline_verdict="gs-nl-advantage",
        failure_modes=["POLYSEMY", "SEMANTIC_WIN"],
    ),
    Scenario(
        id="issue-4-rest-messaging",
        type="issue",
        description="CloudEvent foundation — CDI event bus with fireAsync, stream modules for Kafka/AMQP/webhook with tenancy propagation",
        context="casehubio/platform#98: Spec defining CloudEvent foundation — io.cloudevents.CloudEvent as CDI event type, StreamContext SPI, five classpath-activated stream modules (Kafka, AMQP, webhook, poll, Camel) firing Event<CloudEvent>.fireAsync() with tenancy propagation.",
        tech_band="rest-messaging",
        kw_query="CloudEvent|stream|fireAsync|StreamContext",
        nl_query="CloudEvent CDI event bus with fireAsync — stream modules for Kafka AMQP webhook that receive external transport messages and fire typed CDI events with tenancy propagation",
        grep_pattern=r"CloudEvent|stream|fireAsync|StreamContext",
        baseline_verdict="gs-nl-win",
        failure_modes=["POLYSEMY", "SEMANTIC_WIN"],
    ),
    Scenario(
        id="issue-5-ai-llm-inference",
        type="issue",
        description="ChatModel adapter backed by AgentSession for native prompt caching — 70-80% token cost reduction",
        context="casehubio/platform#100: ChatModel adapter backed by AgentSession (Claude SDK subprocess) instead of HTTP API, gaining native prompt caching (70-80% token cost reduction for long sessions).",
        tech_band="ai-llm-inference",
        kw_query="ChatModel|AgentSession|prompt.cach|LangChain4j",
        nl_query="ChatModel adapter bridging LangChain4j interface to Claude AgentSession for native prompt caching — LLM provider integration with token cost optimization",
        grep_pattern=r"ChatModel|AgentSession|prompt.cach|LangChain4j",
        baseline_verdict="grep-win",
        failure_modes=["VOCABULARY_GAP", "DOMAIN_ABSENCE"],
    ),
    Scenario(
        id="issue-6-testing-ci",
        type="issue",
        description="InMemoryWorkItemTemplateStore @Alternative @Priority wins CDI — tests write via Panache but read from empty in-memory store",
        context="casehubio/engine#576: InMemoryWorkItemTemplateStore (@Alternative @Priority(100)) wins CDI over JpaWorkItemTemplateStore. Tests write via Panache static methods (H2) but read via injected in-memory store (empty ConcurrentHashMap).",
        tech_band="testing-ci",
        kw_query="InMemoryWorkItemTemplateStore|Panache|selected-alternatives|Alternative",
        nl_query="Test fails because @Alternative @Priority in-memory CDI bean bypasses Panache JPA persistence — test writes via Panache static methods but reads from empty in-memory store",
        grep_pattern=r"InMemoryWorkItemTemplateStore|Panache|selected-alternatives|Alternative",
        baseline_verdict="gs-kw-advantage",
        failure_modes=["UNAMBIGUOUS_TERM"],
    ),
    # Spec 1 domains (persistence-memory-module-design.md)
    Scenario(
        id="spec1-d1-cdi-priority-tiers",
        type="spec-domain",
        description="CDI priority ladder with @Alternative @Priority(100) as Tier 3 for in-memory store",
        context="Spec: persistence-memory module. Extraction of in-memory stores into new persistence-memory/ module with @Alternative @Priority(100) as Tier 3 in CDI ladder, thread-safe data structures, Quarkus extension deactivation.",
        tech_band="cdi-priority-tiers",
        kw_query="Priority(100)|CDI priority|tier.*Alternative|ephemeral.*backend",
        nl_query="CDI priority ladder with multiple tiers of Alternative Priority annotations for persistence backend selection where ephemeral in-memory store must override all other backends",
        grep_pattern=r"Priority\(100\)|CDI priority|tier.*Alternative|ephemeral.*backend",
        baseline_verdict="grep-win",
        failure_modes=["VOCABULARY_GAP"],
    ),
    Scenario(
        id="spec1-d2-thread-safety",
        type="spec-domain",
        description="Thread-safe in-memory store with ConcurrentHashMap and CopyOnWriteArrayList",
        context="Spec: persistence-memory module. Data structure migration to ConcurrentHashMap/CopyOnWriteArrayList for lock-free concurrency with READ COMMITTED semantics in in-memory stores.",
        tech_band="thread-safety",
        kw_query="ConcurrentHashMap|CopyOnWriteArrayList|thread.safe|lock.free",
        nl_query="Thread-safe in-memory store using ConcurrentHashMap and CopyOnWriteArrayList for lock-free concurrent access with READ COMMITTED semantics",
        grep_pattern=r"ConcurrentHashMap|CopyOnWriteArrayList|thread.safe|lock.free",
        baseline_verdict="grep-win",
        failure_modes=["UNAMBIGUOUS_TERM"],
    ),
    Scenario(
        id="spec1-d3-extension-deactivation",
        type="spec-domain",
        description="Quarkus datasource and hibernate-orm extension deactivation for in-memory persistence",
        context="Spec: persistence-memory module. Quarkus extension deactivation at build time to prevent JPA validation when using in-memory persistence instead of database.",
        tech_band="extension-deactivation",
        kw_query="datasource.active|hibernate-orm.active|extension.*deactivat|build.time.*deactivat",
        nl_query="Quarkus datasource and hibernate-orm extension deactivation at build time to prevent JPA validation when using in-memory persistence instead of database",
        grep_pattern=r"datasource.active|hibernate-orm.active|extension.*deactivat|build.time.*deactivat",
        baseline_verdict="gs-win",
        failure_modes=["SEMANTIC_WIN"],
    ),
    Scenario(
        id="spec1-d4-protocol-compliance",
        type="spec-domain",
        description="In-memory store aggregate methods must not delegate to scan — pagination silently truncates",
        context="Spec: persistence-memory module. Protocol compliance: aggregate methods must iterate the full data set, not delegate to scan which applies pagination and silently truncates results.",
        tech_band="protocol-compliance",
        kw_query="scan.*pagination|aggregate.*scan|no.scan.delegation|pagination.*truncat",
        nl_query="In-memory store aggregate methods must not delegate to scan because scan applies pagination which silently truncates aggregation results",
        grep_pattern=r"scan.*pagination|aggregate.*scan|no.scan.delegation|pagination.*truncat",
        baseline_verdict="grep-marginal",
        failure_modes=["DOMAIN_ABSENCE"],
    ),
    # Spec 2 domains (agent-langchain4j-interop-design.md)
    Scenario(
        id="spec2-d1-cdi-tier-coexistence",
        type="spec-domain",
        description="@DefaultBean @Priority coexistence with @Alternative in multi-module CDI tier system",
        context="Spec: agent-langchain4j interop. Cross-module bidirectional bridge between AgentProvider and ChatModel/StreamingChatModel using two separate CDI tier systems (@Alternative @Priority(1) and @DefaultBean @Priority(10)).",
        tech_band="cdi-tier-coexistence",
        kw_query="DefaultBean.*Priority|Alternative.*Priority.*coexist|@DefaultBean.*@Priority|@Alternative.*@Priority.*suppress",
        nl_query="@DefaultBean @Priority coexistence with @Alternative — how multiple CDI bean tiers interact when both DefaultBean and Alternative annotations are used together in Quarkus multi-module application",
        grep_pattern=r"DefaultBean.*Priority|Alternative.*Priority.*coexist|@DefaultBean.*@Priority|@Alternative.*@Priority.*suppress",
        baseline_verdict="grep-win",
        failure_modes=["VOCABULARY_GAP"],
    ),
    Scenario(
        id="spec2-d2-chatmodel-adaptation",
        type="spec-domain",
        description="LangChain4j ChatModel adapter wrapping AgentProvider with doChat extension point",
        context="Spec: agent-langchain4j interop. Design of ChatModelAgentProvider to adapt LangChain4j ChatModel interface, implementing doChat() backed by AgentProvider. Includes StreamingChatModel, ChatResponse building.",
        tech_band="chatmodel-adaptation",
        kw_query="ChatModel|doChat|StreamingChatModel|ChatLanguageModel",
        nl_query="LangChain4j ChatModel adapter wrapping AgentProvider — doChat extension point, StreamingChatModel handler, ChatResponse building, provider-agnostic model bridging",
        grep_pattern=r"ChatModel|doChat|StreamingChatModel|ChatLanguageModel",
        baseline_verdict="grep-win",
        failure_modes=["VOCABULARY_GAP"],
    ),
    Scenario(
        id="spec2-d3-circular-deps",
        type="spec-domain",
        description="CDI circular dependency prevention via Instance<ChatModel> filtering at @PostConstruct",
        context="Spec: agent-langchain4j interop. Circular dependency prevention via Instance<ChatModel> filtering at @PostConstruct and graceful deactivation patterns for the bidirectional adapter.",
        tech_band="circular-deps",
        kw_query="Instance.*ChatModel|Instance.*filter|circular.*depend|@PostConstruct.*deactivat|graceful.*deactivat",
        nl_query="CDI bean using Instance to dynamically lookup and filter beans at PostConstruct to prevent circular dependency between two CDI beans that reference each other",
        grep_pattern=r"Instance.*ChatModel|Instance.*filter|circular.*depend|@PostConstruct.*deactivat|graceful.*deactivat",
        baseline_verdict="gs-nl-advantage",
        failure_modes=["POLYSEMY", "SEMANTIC_WIN"],
    ),
    Scenario(
        id="spec2-d4-exception-mapper",
        type="spec-domain",
        description="JAX-RS ExceptionMapper for MissingTenancy → 403 Forbidden",
        context="Spec: agent-langchain4j interop. MissingTenancyExceptionMapper returning 403 Forbidden for missing tenancy context exceptions in Quarkus RESTEasy Reactive.",
        tech_band="exception-mapper",
        kw_query="ExceptionMapper|exception.*mapper|MissingTenancy|403.*Forbidden",
        nl_query="JAX-RS ExceptionMapper that converts application exceptions to HTTP error responses with JSON body — mapping domain exceptions to proper HTTP status codes like 403 Forbidden in Quarkus RESTEasy Reactive",
        grep_pattern=r"ExceptionMapper|exception.*mapper|MissingTenancy|403.*Forbidden",
        baseline_verdict="grep-advantage",
        failure_modes=["VOCABULARY_GAP"],
    ),
]
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/mdproctor/claude/hortora/engine && python3 -m pytest scripts/benchmark/test_queries.py -v`
Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add scripts/benchmark/__init__.py scripts/benchmark/queries.py scripts/benchmark/test_queries.py
git commit -m "feat: benchmark query definitions — 14 scenarios from #27

Refs #28"
```

---

### Task 2: Baseline Score Extraction

**Files:**
- Create: `scripts/benchmark/extract_baseline.py`
- Create: `scripts/benchmark/test_extract_baseline.py`
- Generates: `scripts/benchmark/baseline_scores.json`

**Interfaces:**
- Consumes: `docs/comparison/real-world-benchmark.md` (markdown file)
- Consumes: `SCENARIOS` from `queries.py` (for scenario ID mapping)
- Produces: `baseline_scores.json` — `dict[str, dict[str, dict]]` keyed by GE-ID → scenario_id → `{"benchmark_score": int, "methods": list[str]}`

- [ ] **Step 1: Write the test**

```python
# scripts/benchmark/test_extract_baseline.py
import json
import textwrap
from benchmark.extract_baseline import parse_results_table, extract_scenario_id

SAMPLE_TABLE = textwrap.dedent("""\
    | # | ID | Title | Relevance | Score |
    |---|-----|-------|-----------|-------|
    | 1 | GE-20260428-fd7a65 | @Transactional(SUPPORTS) makes JPA reads callable | 0.61 | 2 |
    | 2 | GE-20260604-ed1b02 | quarkus-flow task executor threads | 0.58 | 1 |
    | 3 | GE-20260505-da346d | @ApplicationScoped CDI beans are always-active | 0.57 | 0 |
""")

SAMPLE_GREP_TABLE = textwrap.dedent("""\
    | # | ID | Title | Label/Summary? | Score |
    |---|-----|-------|----------------|-------|
    | 1 | GE-20260613-51de5b | DB query over CaseInstanceCache | summary | 1 |
    | 2 | GE-20260607-7033a1 | Panache: use getEntityManager() | summary | 0 |
""")

def test_parse_search_results_table():
    results = parse_results_table(SAMPLE_TABLE)
    assert len(results) == 3
    assert results[0] == ("GE-20260428-fd7a65", 2)
    assert results[1] == ("GE-20260604-ed1b02", 1)
    assert results[2] == ("GE-20260505-da346d", 0)

def test_parse_grep_results_table():
    results = parse_results_table(SAMPLE_GREP_TABLE)
    assert len(results) == 2
    assert results[0] == ("GE-20260613-51de5b", 1)
    assert results[1] == ("GE-20260607-7033a1", 0)

def test_extract_scenario_id_issue():
    assert extract_scenario_id("### Issue 1: [Reactive / async]") == "issue-1-reactive-async"
    assert extract_scenario_id("### Issue 6: [Testing / CI]") == "issue-6-testing-ci"

def test_extract_scenario_id_spec_domain():
    assert extract_scenario_id("#### Domain 1: CDI priority tiers", spec_num=1) == "spec1-d1-cdi-priority-tiers"
    assert extract_scenario_id("#### Domain 4: ExceptionMapper HTTP error mapping", spec_num=2) == "spec2-d4-exception-mapper"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/benchmark/test_extract_baseline.py -v`
Expected: FAIL — `ModuleNotFoundError`

- [ ] **Step 3: Implement extract_baseline.py**

```python
#!/usr/bin/env python3
"""Extract #27 benchmark scores from real-world-benchmark.md into baseline_scores.json."""

import json
import re
import sys
from pathlib import Path

REPORT_PATH = Path(__file__).parent.parent.parent / "docs" / "comparison" / "real-world-benchmark.md"
OUTPUT_PATH = Path(__file__).parent / "baseline_scores.json"

SCENARIO_ID_MAP = {
    "issue 1": "issue-1-reactive-async",
    "issue 2": "issue-2-cdi-wiring",
    "issue 3": "issue-3-persistence-migrations",
    "issue 4": "issue-4-rest-messaging",
    "issue 5": "issue-5-ai-llm-inference",
    "issue 6": "issue-6-testing-ci",
}

DOMAIN_ID_MAP = {
    (1, 1): "spec1-d1-cdi-priority-tiers",
    (1, 2): "spec1-d2-thread-safety",
    (1, 3): "spec1-d3-extension-deactivation",
    (1, 4): "spec1-d4-protocol-compliance",
    (2, 1): "spec2-d1-cdi-tier-coexistence",
    (2, 2): "spec2-d2-chatmodel-adaptation",
    (2, 3): "spec2-d3-circular-deps",
    (2, 4): "spec2-d4-exception-mapper",
}


def parse_results_table(table_text: str) -> list[tuple[str, int]]:
    results = []
    for line in table_text.strip().split("\n"):
        line = line.strip()
        if not line.startswith("|") or line.startswith("|---") or line.startswith("| #"):
            continue
        cols = [c.strip() for c in line.split("|")[1:-1]]
        if len(cols) < 3:
            continue
        ge_id = cols[1].strip()
        if not re.match(r"GE-\d{8}-[0-9a-f]{6}", ge_id):
            continue
        score_str = cols[-1].strip()
        try:
            score = int(score_str)
        except ValueError:
            continue
        results.append((ge_id, score))
    return results


def extract_scenario_id(heading: str, spec_num: int = 0) -> str:
    issue_match = re.match(r"###\s+Issue\s+(\d+):\s+\[(.+?)\]", heading)
    if issue_match:
        num = issue_match.group(1)
        band = issue_match.group(2).strip().lower().replace(" / ", "-").replace(" ", "-")
        return f"issue-{num}-{band}"
    domain_match = re.match(r"####\s+Domain\s+(\d+):\s+(.+)", heading)
    if domain_match:
        d_num = int(domain_match.group(1))
        name = domain_match.group(2).strip()
        name = re.sub(r"\s*\(.*\)", "", name)
        slug = name.lower().replace(" ", "-").replace("/", "-")
        return f"spec{spec_num}-d{d_num}-{slug}"
    return ""


def extract_all(report_text: str) -> dict:
    scores: dict[str, dict[str, dict]] = {}
    lines = report_text.split("\n")
    current_scenario = ""
    current_method = ""
    current_spec = 0
    table_lines: list[str] = []
    in_table = False

    def flush_table():
        nonlocal table_lines, in_table
        if not table_lines or not current_scenario or not current_method:
            table_lines = []
            in_table = False
            return
        results = parse_results_table("\n".join(table_lines))
        for ge_id, score in results:
            if ge_id not in scores:
                scores[ge_id] = {}
            if current_scenario not in scores[ge_id]:
                scores[ge_id][current_scenario] = {"benchmark_score": score, "methods": []}
            entry = scores[ge_id][current_scenario]
            if current_method not in entry["methods"]:
                entry["methods"].append(current_method)
        table_lines = []
        in_table = False

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("### Issue"):
            flush_table()
            current_scenario = extract_scenario_id(stripped)
            current_method = ""
        elif stripped.startswith("### Spec"):
            flush_table()
            spec_match = re.match(r"### Spec (\d+):", stripped)
            if spec_match:
                current_spec = int(spec_match.group(1))
        elif stripped.startswith("#### Domain"):
            flush_table()
            current_scenario = extract_scenario_id(stripped, current_spec)
            current_method = ""
        elif "grep (keywords)" in stripped:
            flush_table()
            current_method = "grep"
        elif "gardenSearch (keywords)" in stripped or "gardenSearch (KW)" in stripped:
            flush_table()
            current_method = "gardenSearch-KW"
        elif "gardenSearch (natural language)" in stripped or "gardenSearch (NL)" in stripped:
            flush_table()
            current_method = "gardenSearch-NL"
        elif stripped.startswith("| #") or stripped.startswith("|---"):
            in_table = True
            table_lines.append(stripped)
        elif in_table and stripped.startswith("| ") and re.match(r"\|\s*\d+\s*\|", stripped):
            table_lines.append(stripped)
        elif in_table and not stripped.startswith("|"):
            flush_table()

    flush_table()
    return scores


def main():
    report_path = Path(sys.argv[1]) if len(sys.argv) > 1 else REPORT_PATH
    report_text = report_path.read_text()
    scores = extract_all(report_text)

    total_entries = sum(len(scenarios) for scenarios in scores.values())
    output_path = Path(sys.argv[2]) if len(sys.argv) > 2 else OUTPUT_PATH
    output_path.write_text(json.dumps(scores, indent=2, sort_keys=True))
    print(f"Extracted {len(scores)} unique GE-IDs across {total_entries} (entry, scenario) pairs")
    print(f"Written to {output_path}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/benchmark/test_extract_baseline.py -v`
Expected: All 4 tests PASS

- [ ] **Step 5: Run extraction against actual report**

Run: `python3 scripts/benchmark/extract_baseline.py`
Expected: Output shows ~100+ unique GE-IDs, file written to `scripts/benchmark/baseline_scores.json`

Verify the output: spot-check 3-5 entries against the report manually. Verify that `GE-20260530-4387cb` has different scores for different scenarios (the R2-02 test case from the design review).

- [ ] **Step 6: Commit**

```bash
git add scripts/benchmark/extract_baseline.py scripts/benchmark/test_extract_baseline.py scripts/benchmark/baseline_scores.json
git commit -m "feat: extract #27 baseline scores into structured JSON

Refs #28"
```

---

### Task 3: Query Runner

**Files:**
- Create: `scripts/benchmark/run_queries.py`
- Create: `scripts/benchmark/test_run_queries.py`
- Create: `scripts/benchmark/results/` (directory)

**Interfaces:**
- Consumes: `SCENARIOS` from `queries.py`
- Produces: `results/<config-name>.json` — `{"config": str, "timestamp": str, "results": list[QueryResult]}` where each `QueryResult` has `scenario_id`, `query_type`, `entries` (list of API response objects with rank added), `latency_ms` (list of 3 measurements), `latency_median_ms`

- [ ] **Step 1: Write the test**

```python
# scripts/benchmark/test_run_queries.py
import json
from benchmark.run_queries import parse_search_response, compute_median

SAMPLE_RESPONSE = json.dumps([
    {"id": "jvm/GE-20260428-fd7a65.md", "title": "Test entry", "domain": "jvm",
     "type": "gotcha", "score": 9, "body": "content here", "relevance": 0.85,
     "source": "garden", "sourcePrefix": "own"},
    {"id": "jvm/GE-20260604-ed1b02.md", "title": "Second entry", "domain": "jvm",
     "type": "technique", "score": 7, "body": "more content", "relevance": 0.72,
     "source": "garden", "sourcePrefix": "own"},
])

def test_parse_search_response():
    entries = parse_search_response(SAMPLE_RESPONSE)
    assert len(entries) == 2
    assert entries[0]["rank"] == 0
    assert entries[0]["id"] == "jvm/GE-20260428-fd7a65.md"
    assert entries[1]["rank"] == 1

def test_compute_median():
    assert compute_median([100.0, 200.0, 150.0]) == 150.0
    assert compute_median([100.0]) == 100.0
    assert compute_median([100.0, 200.0]) == 150.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/benchmark/test_run_queries.py -v`
Expected: FAIL

- [ ] **Step 3: Implement run_queries.py**

```python
#!/usr/bin/env python3
"""Run benchmark queries against the engine REST API and capture results with latency."""

import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from benchmark.queries import SCENARIOS

ENGINE_URL = "http://localhost:8080"
QDRANT_URL = "http://localhost:6333"
COLLECTION_NAME = "hortora_garden"
RESULTS_DIR = Path(__file__).parent / "results"
NUM_PASSES = 3
QUERY_PAUSE_S = 0.5
READINESS_POLL_S = 5
MIN_INDEXED_POINTS = 1900


def search(query: str, base_url: str = ENGINE_URL, limit: int = 8) -> tuple[str, float]:
    url = f"{base_url}/search?q={urllib.parse.quote(query)}&limit={limit}"
    start = time.monotonic()
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = resp.read().decode()
    elapsed_ms = (time.monotonic() - start) * 1000
    return body, elapsed_ms


def parse_search_response(response_body: str) -> list[dict]:
    entries = json.loads(response_body)
    for i, entry in enumerate(entries):
        entry["rank"] = i
    return entries


def compute_median(values: list[float]) -> float:
    s = sorted(values)
    n = len(s)
    if n % 2 == 1:
        return s[n // 2]
    return (s[n // 2 - 1] + s[n // 2]) / 2


def check_qdrant_ready(qdrant_url: str = QDRANT_URL) -> int:
    url = f"{qdrant_url}/collections/{COLLECTION_NAME}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    return data["result"]["points_count"]


def wait_for_readiness(engine_url: str = ENGINE_URL, qdrant_url: str = QDRANT_URL):
    print("Waiting for engine readiness...")
    for attempt in range(60):
        try:
            search("test query", engine_url)
            break
        except Exception:
            time.sleep(READINESS_POLL_S)
    else:
        raise RuntimeError("Engine not responding after 5 minutes")

    print("Waiting for indexing to complete...")
    prev_count = -1
    stable_checks = 0
    for attempt in range(120):
        try:
            count = check_qdrant_ready(qdrant_url)
            print(f"  Indexed points: {count}")
            if count >= MIN_INDEXED_POINTS and count == prev_count:
                stable_checks += 1
                if stable_checks >= 2:
                    print(f"Indexing complete: {count} points")
                    return count
            else:
                stable_checks = 0
            prev_count = count
        except Exception as e:
            print(f"  Qdrant check failed: {e}")
        time.sleep(READINESS_POLL_S)
    raise RuntimeError("Indexing did not stabilise")


def run_all_queries(engine_url: str = ENGINE_URL) -> list[dict]:
    queries = []
    for scenario in SCENARIOS:
        for qt, query_text in [("KW", scenario.kw_query), ("NL", scenario.nl_query)]:
            queries.append({"scenario_id": scenario.id, "query_type": qt, "query_text": query_text})

    print(f"Warmup pass ({len(queries)} queries)...")
    for q in queries:
        try:
            search(q["query_text"], engine_url)
        except Exception as e:
            print(f"  Warmup failed for {q['scenario_id']}/{q['query_type']}: {e}")
        time.sleep(QUERY_PAUSE_S)

    results = []
    for q in queries:
        latencies = []
        entries_per_pass = []
        for pass_num in range(NUM_PASSES):
            body, elapsed_ms = search(q["query_text"], engine_url)
            entries = parse_search_response(body)
            latencies.append(elapsed_ms)
            entries_per_pass.append(entries)
            time.sleep(QUERY_PAUSE_S)
        results.append({
            "scenario_id": q["scenario_id"],
            "query_type": q["query_type"],
            "query_text": q["query_text"],
            "entries": entries_per_pass[0],
            "latency_ms": latencies,
            "latency_median_ms": compute_median(latencies),
        })
        print(f"  {q['scenario_id']}/{q['query_type']}: {len(entries_per_pass[0])} results, "
              f"median {compute_median(latencies):.1f}ms")

    return results


def main():
    if len(sys.argv) < 2:
        print("Usage: run_queries.py <config-name> [engine-url]")
        sys.exit(1)

    config_name = sys.argv[1]
    engine_url = sys.argv[2] if len(sys.argv) > 2 else ENGINE_URL

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    point_count = wait_for_readiness(engine_url)

    print(f"\nRunning benchmark for config: {config_name}")
    results = run_all_queries(engine_url)

    output = {
        "config": config_name,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "point_count": point_count,
        "num_passes": NUM_PASSES,
        "results": results,
    }

    output_path = RESULTS_DIR / f"{config_name}.json"
    output_path.write_text(json.dumps(output, indent=2))
    print(f"\nResults written to {output_path}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/benchmark/test_run_queries.py -v`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
mkdir -p scripts/benchmark/results
git add scripts/benchmark/run_queries.py scripts/benchmark/test_run_queries.py
git commit -m "feat: benchmark query runner with latency measurement

Refs #28"
```

---

### Task 4: SPLADE Vocabulary Analysis

**Files:**
- Create: `scripts/benchmark/splade_vocab.py`
- Create: `scripts/benchmark/test_splade_vocab.py`

**Interfaces:**
- Consumes: `SCENARIOS` from `queries.py`
- Consumes: ONNX model at `~/.hortora/models/splade/model.onnx`
- Consumes: Tokenizer at `~/.hortora/models/splade/tokenizer.json`
- Produces: `results/splade-vocab.json` — per-query token activations with classifications

- [ ] **Step 1: Write the test**

```python
# scripts/benchmark/test_splade_vocab.py
from benchmark.splade_vocab import (
    classify_token, TIER_1_TERMS, TIER_2_TERMS, domain_tier, InputTokens,
)

def test_classify_input_whole_word():
    input_tokens = InputTokens(whole={"default", "bean"}, subwords={"##ing"})
    source, form = classify_token("default", input_tokens)
    assert source == "INPUT"
    assert form == "WHOLE_WORD"

def test_classify_input_subword():
    input_tokens = InputTokens(whole={"default"}, subwords={"##tion"})
    source, form = classify_token("##tion", input_tokens)
    assert source == "INPUT"
    assert form == "SUBWORD"

def test_classify_expansion_whole_word():
    input_tokens = InputTokens(whole={"default"}, subwords=set())
    source, form = classify_token("bean", input_tokens)
    assert source == "EXPANSION"
    assert form == "WHOLE_WORD"

def test_classify_expansion_subword():
    input_tokens = InputTokens(whole={"default"}, subwords=set())
    source, form = classify_token("##ject", input_tokens)
    assert source == "EXPANSION"
    assert form == "SUBWORD"

def test_tier_1_terms_are_unambiguous():
    for term in TIER_1_TERMS:
        assert domain_tier(term) == 1, f"{term} should be Tier 1"

def test_tier_2_terms_are_ambiguous():
    for term in TIER_2_TERMS:
        assert domain_tier(term) == 2, f"{term} should be Tier 2"

def test_non_domain_term():
    assert domain_tier("the") == 0
    assert domain_tier("computer") == 0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/benchmark/test_splade_vocab.py -v`
Expected: FAIL

- [ ] **Step 3: Implement splade_vocab.py**

```python
#!/usr/bin/env python3
"""SPLADE vocabulary analysis — decode sparse vectors to diagnose domain coverage."""

import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from benchmark.queries import SCENARIOS

MODEL_DIR = Path.home() / ".hortora" / "models" / "splade"
RESULTS_DIR = Path(__file__).parent / "results"
THRESHOLD = 0.01

TIER_1_TERMS = frozenset({
    "applicationscoped", "qualifier", "interceptor", "produces", "singleton",
    "persist", "cascade",
    "panache", "devservices", "jandex", "arc",
    "synchronized", "volatile",
    "mutiny",
})

TIER_2_TERMS = frozenset({
    "inject", "alternative", "scope", "observer",
    "entity", "merge", "lazy", "fetch",
    "atomic", "concurrent", "lock",
    "uni", "multi", "subscribe", "emit",
})


@dataclass
class InputTokens:
    whole: set[str] = field(default_factory=set)
    subwords: set[str] = field(default_factory=set)


def classify_token(token: str, input_tokens: InputTokens) -> tuple[str, str]:
    is_subword = token.startswith("##")
    form = "SUBWORD" if is_subword else "WHOLE_WORD"
    if is_subword:
        source = "INPUT" if token in input_tokens.subwords else "EXPANSION"
    else:
        source = "INPUT" if token in input_tokens.whole else "EXPANSION"
    return source, form


def domain_tier(token: str) -> int:
    clean = token.lstrip("#").lower()
    if clean in TIER_1_TERMS:
        return 1
    if clean in TIER_2_TERMS:
        return 2
    return 0


def get_input_tokens(tokenizer, text: str) -> InputTokens:
    encoding = tokenizer.encode(text)
    tokens = encoding.tokens
    whole = set()
    subwords = set()
    for t in tokens:
        if t in ("[CLS]", "[SEP]", "[PAD]"):
            continue
        if t.startswith("##"):
            subwords.add(t)
        else:
            whole.add(t)
    return InputTokens(whole=whole, subwords=subwords)


def compute_sparse_vector(session, tokenizer, text: str) -> list[tuple[str, float, str, str, int]]:
    encoding = tokenizer.encode(text)
    input_ids = np.array([encoding.ids], dtype=np.int64)
    attention_mask = np.array([encoding.attention_mask], dtype=np.int64)
    token_type_ids = np.zeros_like(input_ids)

    outputs = session.run(None, {
        "input_ids": input_ids,
        "attention_mask": attention_mask,
        "token_type_ids": token_type_ids,
    })
    logits = outputs[0][0]

    weights = np.log1p(np.maximum(0, logits))
    input_tokens = get_input_tokens(tokenizer, text)
    vocab = tokenizer.get_vocab()
    id_to_token = {v: k for k, v in vocab.items()}

    activated = []
    for idx in np.where(weights >= THRESHOLD)[0]:
        token = id_to_token.get(int(idx), f"[{idx}]")
        weight = float(weights[idx])
        source, form = classify_token(token, input_tokens)
        tier = domain_tier(token)
        activated.append((token, weight, source, form, tier))

    activated.sort(key=lambda x: x[1], reverse=True)
    return activated


def analyze_all(model_path: Path, tokenizer_path: Path) -> list[dict]:
    import onnxruntime as ort
    from tokenizers import Tokenizer

    session = ort.InferenceSession(str(model_path))
    tokenizer = Tokenizer.from_file(str(tokenizer_path))

    output_dim = session.get_outputs()[0].shape[-1]
    print(f"SPLADE model output dimension: {output_dim}")

    results = []
    for scenario in SCENARIOS:
        for qt, query_text in [("KW", scenario.kw_query), ("NL", scenario.nl_query)]:
            print(f"  {scenario.id}/{qt}: ", end="", flush=True)
            activated = compute_sparse_vector(session, tokenizer, query_text)
            input_tokens = get_input_tokens(tokenizer, query_text)

            tier1_hits = [t for t in activated if t[4] == 1]
            tier2_hits = [t for t in activated if t[4] == 2]
            expansions = [t for t in activated if t[2] == "EXPANSION"]

            result = {
                "scenario_id": scenario.id,
                "query_type": qt,
                "query_text": query_text,
                "failure_modes": scenario.failure_modes,
                "input_tokens": {
                    "whole": sorted(input_tokens.whole),
                    "subwords": sorted(input_tokens.subwords),
                },
                "total_activated": len(activated),
                "top_20": [
                    {"token": t[0], "weight": round(t[1], 4), "source": t[2],
                     "form": t[3], "domain_tier": t[4]}
                    for t in activated[:20]
                ],
                "expansion_count": len(expansions),
                "tier1_hits": len(tier1_hits),
                "tier1_tokens": [t[0] for t in tier1_hits],
                "tier2_hits": len(tier2_hits),
                "tier2_tokens": [t[0] for t in tier2_hits],
                "missing_domain_terms": sorted(
                    (TIER_1_TERMS | TIER_2_TERMS) - {t[0].lstrip("#").lower() for t in activated}
                ),
            }
            results.append(result)
            print(f"{len(activated)} tokens, {len(expansions)} expansions, "
                  f"T1={len(tier1_hits)} T2={len(tier2_hits)}")

    return results


def main():
    model_path = MODEL_DIR / "model.onnx"
    tokenizer_path = MODEL_DIR / "tokenizer.json"

    if not model_path.exists():
        print(f"SPLADE model not found at {model_path}")
        print("Run scripts/download-models.sh first")
        sys.exit(1)

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    print("Running SPLADE vocabulary analysis...")
    results = analyze_all(model_path, tokenizer_path)

    output_path = RESULTS_DIR / "splade-vocab.json"
    output_path.write_text(json.dumps(results, indent=2))
    print(f"\nResults written to {output_path}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/benchmark/test_splade_vocab.py -v`
Expected: All 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add scripts/benchmark/splade_vocab.py scripts/benchmark/test_splade_vocab.py
git commit -m "feat: SPLADE vocabulary analysis with two-tier domain classification

Refs #28"
```

---

### Task 5: Delta Analysis and Report Generation

**Files:**
- Create: `scripts/benchmark/analyze.py`
- Create: `scripts/benchmark/test_analyze.py`

**Interfaces:**
- Consumes: `SCENARIOS` from `queries.py`
- Consumes: `baseline_scores.json`
- Consumes: `results/dense-only.json`, `results/dense+splade.json`, `results/full-hybrid.json`
- Consumes: `results/splade-vocab.json`
- Consumes: `hybrid_scores.json` (optional — manual scores for new entries)
- Produces: `to_score.json` — new entries needing manual scoring
- Produces: `docs/comparison/hybrid-benchmark.md` — final report

- [ ] **Step 1: Write the test**

```python
# scripts/benchmark/test_analyze.py
from benchmark.analyze import (
    extract_ge_id, compute_delta, compute_precision, compute_rr,
)

def test_extract_ge_id():
    assert extract_ge_id("jvm/GE-20260428-fd7a65.md") == "GE-20260428-fd7a65"
    assert extract_ge_id("GE-20260428-fd7a65") == "GE-20260428-fd7a65"
    assert extract_ge_id("tools/approaches/testing.md") == "tools/approaches/testing"

def test_compute_delta_shared():
    baseline = [{"id": "jvm/GE-aaa.md", "rank": 0}, {"id": "jvm/GE-bbb.md", "rank": 1}]
    hybrid = [{"id": "jvm/GE-bbb.md", "rank": 0}, {"id": "jvm/GE-aaa.md", "rank": 1}]
    delta = compute_delta(baseline, hybrid)
    assert len(delta["shared"]) == 2
    assert len(delta["new"]) == 0
    assert len(delta["lost"]) == 0
    shared_ids = {s["ge_id"] for s in delta["shared"]}
    assert "GE-aaa" in shared_ids
    assert "GE-bbb" in shared_ids

def test_compute_delta_new_and_lost():
    baseline = [{"id": "jvm/GE-aaa.md", "rank": 0}]
    hybrid = [{"id": "jvm/GE-bbb.md", "rank": 0}]
    delta = compute_delta(baseline, hybrid)
    assert len(delta["shared"]) == 0
    assert len(delta["new"]) == 1
    assert delta["new"][0]["ge_id"] == "GE-bbb"
    assert len(delta["lost"]) == 1
    assert delta["lost"][0]["ge_id"] == "GE-aaa"

def test_compute_precision():
    scored = [2, 1, 0, 2, 0, 1, 0, 0]
    assert compute_precision(scored, threshold=1) == 4 / 8
    assert compute_precision(scored, threshold=2) == 2 / 8

def test_compute_precision_empty():
    assert compute_precision([], threshold=1) == 0.0

def test_compute_rr():
    scored = [0, 0, 2, 1]
    assert compute_rr(scored) == 1 / 3  # first score=2 at rank 3 (1-indexed)

def test_compute_rr_no_relevant():
    scored = [0, 0, 0]
    assert compute_rr(scored) == 0.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/benchmark/test_analyze.py -v`
Expected: FAIL

- [ ] **Step 3: Implement analyze.py**

```python
#!/usr/bin/env python3
"""Delta analysis across benchmark configs + report generation."""

import json
import re
import sys
from pathlib import Path

from benchmark.queries import SCENARIOS

RESULTS_DIR = Path(__file__).parent / "results"
BASELINE_PATH = Path(__file__).parent / "baseline_scores.json"
HYBRID_SCORES_PATH = Path(__file__).parent / "hybrid_scores.json"
TO_SCORE_PATH = Path(__file__).parent / "to_score.json"
REPORT_PATH = Path(__file__).parent.parent.parent / "docs" / "comparison" / "hybrid-benchmark.md"


def extract_ge_id(path: str) -> str:
    without_ext = re.sub(r"\.md$", "", path)
    filename = without_ext.split("/")[-1] if "/" in without_ext else without_ext
    if re.match(r"GE-\d{8}-[0-9a-f]{6}", filename):
        return filename
    return without_ext


def compute_delta(baseline_entries: list[dict], hybrid_entries: list[dict]) -> dict:
    baseline_ids = {extract_ge_id(e["id"]): e for e in baseline_entries}
    hybrid_ids = {extract_ge_id(e["id"]): e for e in hybrid_entries}
    shared, new, lost = [], [], []
    for ge_id, entry in hybrid_ids.items():
        if ge_id in baseline_ids:
            shared.append({
                "ge_id": ge_id,
                "baseline_rank": baseline_ids[ge_id]["rank"],
                "hybrid_rank": entry["rank"],
                "rank_change": baseline_ids[ge_id]["rank"] - entry["rank"],
            })
        else:
            new.append({"ge_id": ge_id, "hybrid_rank": entry["rank"],
                        "title": entry.get("title", ""), "body": entry.get("body", "")})
    for ge_id, entry in baseline_ids.items():
        if ge_id not in hybrid_ids:
            lost.append({"ge_id": ge_id, "baseline_rank": entry["rank"],
                         "title": entry.get("title", "")})
    return {"shared": shared, "new": new, "lost": lost}


def compute_precision(scores: list[int], threshold: int = 1) -> float:
    if not scores:
        return 0.0
    return sum(1 for s in scores if s >= threshold) / len(scores)


def compute_rr(scores: list[int]) -> float:
    for i, s in enumerate(scores):
        if s >= 2:
            return 1.0 / (i + 1)
    return 0.0


def load_results(config_name: str) -> dict:
    path = RESULTS_DIR / f"{config_name}.json"
    if not path.exists():
        return {}
    return json.loads(path.read_text())


def load_baseline_scores() -> dict:
    if not BASELINE_PATH.exists():
        return {}
    return json.loads(BASELINE_PATH.read_text())


def load_hybrid_scores() -> dict:
    if not HYBRID_SCORES_PATH.exists():
        return {}
    return json.loads(HYBRID_SCORES_PATH.read_text())


def get_score(ge_id: str, scenario_id: str, baseline: dict, hybrid_scores: dict) -> int | None:
    if ge_id in hybrid_scores and scenario_id in hybrid_scores[ge_id]:
        return hybrid_scores[ge_id][scenario_id]["benchmark_score"]
    if ge_id in baseline and scenario_id in baseline[ge_id]:
        return baseline[ge_id][scenario_id]["benchmark_score"]
    return None


def find_result(results: dict, scenario_id: str, query_type: str) -> list[dict]:
    for r in results.get("results", []):
        if r["scenario_id"] == scenario_id and r["query_type"] == query_type:
            return r.get("entries", [])
    return []


def analyze_all():
    configs = ["dense-only", "dense+splade", "full-hybrid"]
    loaded = {c: load_results(c) for c in configs}
    baseline_scores = load_baseline_scores()
    hybrid_scores = load_hybrid_scores()
    vocab_data = {}
    vocab_path = RESULTS_DIR / "splade-vocab.json"
    if vocab_path.exists():
        for entry in json.loads(vocab_path.read_text()):
            vocab_data[(entry["scenario_id"], entry["query_type"])] = entry

    to_score_entries = []
    scenario_results = []

    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            baseline_entries = find_result(loaded.get("dense-only", {}), scenario.id, qt)
            results_per_config = {}
            for config in configs:
                entries = find_result(loaded.get(config, {}), scenario.id, qt)
                results_per_config[config] = entries

            deltas = {}
            for config in ["dense+splade", "full-hybrid"]:
                if results_per_config.get(config):
                    deltas[config] = compute_delta(baseline_entries, results_per_config[config])
                    for new_entry in deltas[config]["new"]:
                        if get_score(new_entry["ge_id"], scenario.id, baseline_scores, hybrid_scores) is None:
                            to_score_entries.append({
                                "ge_id": new_entry["ge_id"],
                                "scenario_id": scenario.id,
                                "query_type": qt,
                                "config": config,
                                "title": new_entry.get("title", ""),
                                "body": new_entry.get("body", ""),
                                "context": scenario.context,
                            })

            scores_per_config = {}
            for config in configs:
                entries = results_per_config.get(config, [])
                scored = []
                for e in entries:
                    ge_id = extract_ge_id(e["id"])
                    s = get_score(ge_id, scenario.id, baseline_scores, hybrid_scores)
                    scored.append(s if s is not None else -1)
                scores_per_config[config] = scored

            latencies = {}
            for config in configs:
                for r in loaded.get(config, {}).get("results", []):
                    if r["scenario_id"] == scenario.id and r["query_type"] == qt:
                        latencies[config] = r.get("latency_median_ms", 0)

            scenario_results.append({
                "scenario_id": scenario.id,
                "query_type": qt,
                "failure_modes": scenario.failure_modes,
                "baseline_verdict": scenario.baseline_verdict,
                "deltas": {k: v for k, v in deltas.items()},
                "scores": scores_per_config,
                "latencies": latencies,
                "vocab": vocab_data.get((scenario.id, qt)),
            })

    if to_score_entries:
        grouped = {}
        for entry in to_score_entries:
            key = entry["scenario_id"]
            if key not in grouped:
                grouped[key] = {"context": entry["context"], "entries": []}
            grouped[key]["entries"].append({
                "ge_id": entry["ge_id"],
                "query_type": entry["query_type"],
                "config": entry["config"],
                "title": entry["title"],
                "body": entry["body"][:500],
            })
        TO_SCORE_PATH.write_text(json.dumps(grouped, indent=2))
        print(f"  {len(to_score_entries)} new entries need scoring → {TO_SCORE_PATH}")

    return scenario_results


def generate_report(scenario_results: list[dict]) -> str:
    lines = ["# Hybrid Benchmark: Dense-Only vs SPLADE vs Full Hybrid\n"]
    lines.append(f"*Generated {__import__('datetime').date.today()}*\n")
    lines.append("## Configuration\n")
    lines.append("See spec: `docs/superpowers/specs/2026-06-28-splade-hybrid-benchmark-design.md`\n")

    configs = ["dense-only", "dense+splade", "full-hybrid"]
    lines.append("## Headline Results\n")

    for qt in ["KW", "NL"]:
        qt_results = [r for r in scenario_results if r["query_type"] == qt]
        lines.append(f"### {qt} Queries\n")
        lines.append("| Scenario | Failure Mode | " + " | ".join(
            f"{c} prec" for c in configs) + " | Delta (SPLADE) | Delta (Full) |")
        lines.append("|---|---|" + "|".join(["---"] * len(configs)) + "|---|---|")
        for r in qt_results:
            precs = []
            for c in configs:
                scored = [s for s in r["scores"].get(c, []) if s >= 0]
                p = compute_precision(scored) if scored else float("nan")
                precs.append(f"{p:.0%}" if not (p != p) else "—")
            d_splade = ""
            d_full = ""
            delta_s = r["deltas"].get("dense+splade")
            delta_f = r["deltas"].get("full-hybrid")
            if delta_s:
                d_splade = f"+{len(delta_s['new'])}/-{len(delta_s['lost'])}"
            if delta_f:
                d_full = f"+{len(delta_f['new'])}/-{len(delta_f['lost'])}"
            fm = ", ".join(r["failure_modes"])
            lines.append(f"| {r['scenario_id']} | {fm} | " + " | ".join(precs) +
                         f" | {d_splade} | {d_full} |")
        lines.append("")

    lines.append("## Latency\n")
    lines.append("| Scenario | Query | dense-only | dense+splade | full-hybrid |")
    lines.append("|---|---|---|---|---|")
    for r in scenario_results:
        lats = [f"{r['latencies'].get(c, 0):.0f}ms" for c in configs]
        lines.append(f"| {r['scenario_id']} | {r['query_type']} | " + " | ".join(lats) + " |")
    lines.append("")

    lines.append("## Per-Scenario Results\n")
    for r in scenario_results:
        lines.append(f"### {r['scenario_id']} / {r['query_type']}\n")
        lines.append(f"**Failure modes:** {', '.join(r['failure_modes'])}")
        lines.append(f"**Baseline verdict:** {r['baseline_verdict']}\n")

        for config_name in ["dense+splade", "full-hybrid"]:
            delta = r["deltas"].get(config_name)
            if not delta:
                continue
            lines.append(f"**Delta vs dense-only ({config_name}):**")
            lines.append(f"- Shared: {len(delta['shared'])}, New: {len(delta['new'])}, Lost: {len(delta['lost'])}")
            if delta["shared"]:
                rank_changes = [s["rank_change"] for s in delta["shared"]]
                lines.append(f"- Rank changes: {rank_changes}")
            lines.append("")

        vocab = r.get("vocab")
        if vocab and r["query_type"] == "KW":
            lines.append("**SPLADE vocabulary (KW):**")
            top5 = vocab.get("top_20", [])[:5]
            for t in top5:
                lines.append(f"- `{t['token']}` ({t['weight']:.3f}) [{t['source']}/{t['form']}]")
            lines.append(f"- T1 hits: {vocab.get('tier1_hits', 0)}, T2 hits: {vocab.get('tier2_hits', 0)}")
            lines.append("")
        lines.append("---\n")

    return "\n".join(lines)


def main():
    print("Running delta analysis...")
    results = analyze_all()

    unscored = sum(1 for r in results
                   for s in r["scores"].values()
                   for score in s if score == -1)
    if unscored > 0:
        print(f"\n⚠️  {unscored} entries have no score. Score new entries in hybrid_scores.json, "
              f"then re-run analyze.py.")

    report = generate_report(results)
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(report)
    print(f"Report written to {REPORT_PATH}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/benchmark/test_analyze.py -v`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add scripts/benchmark/analyze.py scripts/benchmark/test_analyze.py
git commit -m "feat: delta analysis and report generation for hybrid benchmark

Refs #28"
```

---

### Task 6: Dense-Only Consistency Check

After all scripts are built, run the dense-only baseline to validate the harness against #27.

**Files:**
- Modify: `scripts/benchmark/analyze.py` (add consistency check function)
- Test: add to `scripts/benchmark/test_analyze.py`

**Interfaces:**
- Consumes: `results/dense-only.json`, `baseline_scores.json`
- Produces: console output with per-scenario consistency report

- [ ] **Step 1: Write the consistency check test**

```python
# append to scripts/benchmark/test_analyze.py

def test_consistency_check_identical():
    from benchmark.analyze import check_consistency
    baseline_ids = {"GE-aaa", "GE-bbb", "GE-ccc"}
    rerun_ids = {"GE-aaa", "GE-bbb", "GE-ccc"}
    result = check_consistency(baseline_ids, rerun_ids, "test-scenario", "KW")
    assert result["match"] is True
    assert result["added"] == set()
    assert result["removed"] == set()

def test_consistency_check_diverged():
    from benchmark.analyze import check_consistency
    baseline_ids = {"GE-aaa", "GE-bbb"}
    rerun_ids = {"GE-aaa", "GE-ccc"}
    result = check_consistency(baseline_ids, rerun_ids, "test-scenario", "KW")
    assert result["match"] is False
    assert result["added"] == {"GE-ccc"}
    assert result["removed"] == {"GE-bbb"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/benchmark/test_analyze.py::test_consistency_check_identical -v`
Expected: FAIL

- [ ] **Step 3: Implement check_consistency in analyze.py**

Add to `analyze.py`:

```python
def check_consistency(baseline_ids: set[str], rerun_ids: set[str],
                      scenario_id: str, query_type: str) -> dict:
    added = rerun_ids - baseline_ids
    removed = baseline_ids - rerun_ids
    return {
        "scenario_id": scenario_id,
        "query_type": query_type,
        "match": added == set() and removed == set(),
        "added": added,
        "removed": removed,
    }


def run_consistency_check():
    baseline_scores = load_baseline_scores()
    dense_only = load_results("dense-only")
    if not dense_only:
        print("No dense-only results found. Run: run_queries.py dense-only")
        return False

    total_checked = 0
    total_diverged = 0
    diverged_scenarios = []

    for scenario in SCENARIOS:
        for qt in ["KW", "NL"]:
            entries = find_result(dense_only, scenario.id, qt)
            rerun_ids = {extract_ge_id(e["id"]) for e in entries}

            baseline_ids = set()
            method_key = f"gardenSearch-{qt}"
            for ge_id, scenarios in baseline_scores.items():
                if scenario.id in scenarios:
                    if method_key in scenarios[scenario.id].get("methods", []):
                        baseline_ids.add(ge_id)

            result = check_consistency(baseline_ids, rerun_ids, scenario.id, qt)
            total_checked += 1
            if not result["match"]:
                total_diverged += 1
                diverged_scenarios.append(result)
                print(f"  ⚠️  {scenario.id}/{qt}: "
                      f"+{len(result['added'])} new, -{len(result['removed'])} missing")

    total_entries = sum(len(find_result(dense_only, s.id, qt))
                        for s in SCENARIOS for qt in ["KW", "NL"])
    total_changed = sum(len(r["added"]) + len(r["removed"]) for r in diverged_scenarios)

    print(f"\nConsistency: {total_checked - total_diverged}/{total_checked} scenarios match #27")
    if total_changed > total_entries * 0.1:
        print(f"⛔ {total_changed}/{total_entries} entries changed (>{10}%) — halt and investigate")
        return False
    if diverged_scenarios:
        print(f"⚠️  {total_diverged} scenarios diverged — re-baseline those scenarios")
    else:
        print("✅ All scenarios match #27 baseline")
    return True
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest scripts/benchmark/test_analyze.py -v`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add scripts/benchmark/analyze.py scripts/benchmark/test_analyze.py
git commit -m "feat: dense-only consistency check against #27 baseline

Refs #28"
```
