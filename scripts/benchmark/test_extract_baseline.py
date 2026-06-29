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
