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
