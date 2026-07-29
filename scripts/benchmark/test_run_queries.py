# scripts/benchmark/test_run_queries.py
import json
from benchmark.run_queries import parse_search_response, compute_median, compute_unscored_pct

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

def test_compute_median_with_none():
    """Test that compute_median skips None values."""
    assert compute_median([100.0, None, 150.0]) == 125.0
    assert compute_median([None, 100.0, 200.0]) == 150.0
    assert compute_median([100.0, 150.0, None]) == 125.0

def test_compute_median_all_none():
    """Test that compute_median returns None when all values are None."""
    assert compute_median([None, None, None]) is None
    assert compute_median([]) is None

def test_main_accepts_min_points_argument(monkeypatch, tmp_path):
    """--min-points overrides MIN_INDEXED_POINTS for wait_for_readiness."""
    import benchmark.run_queries as rq

    captured_min = {}

    original_wait = rq.wait_for_readiness
    def fake_wait(engine_url=rq.ENGINE_URL, qdrant_url=rq.QDRANT_URL, min_points=rq.MIN_INDEXED_POINTS):
        captured_min["value"] = min_points
        return min_points

    monkeypatch.setattr(rq, "wait_for_readiness", fake_wait)
    monkeypatch.setattr(rq, "run_all_queries", lambda eu, limit=None: [])
    monkeypatch.setattr(rq, "RESULTS_DIR", tmp_path)
    monkeypatch.setattr(rq, "BASELINE_PATH", tmp_path / "b.json")
    (tmp_path / "b.json").write_text("{}")
    monkeypatch.setattr("sys.argv", ["run_queries.py", "test-config", "--min-points", "2500"])

    rq.main()

    assert captured_min["value"] == 2500


SAMPLE_BASELINE = {
    "GE-20260428-fd7a65": {
        "issue-1-reactive-async": {"benchmark_score": 2, "methods": ["gardenSearch-KW"]}
    },
    "GE-20260604-ed1b02": {
        "issue-1-reactive-async": {"benchmark_score": 1, "methods": ["gardenSearch-NL"]}
    },
}


def test_compute_unscored_pct_all_scored():
    results = [
        {"scenario_id": "issue-1-reactive-async", "entries": [
            {"id": "jvm/GE-20260428-fd7a65.md"},
            {"id": "jvm/GE-20260604-ed1b02.md"},
        ]},
    ]
    assert compute_unscored_pct(results, SAMPLE_BASELINE) == 0.0


def test_compute_unscored_pct_some_unscored():
    results = [
        {"scenario_id": "issue-1-reactive-async", "entries": [
            {"id": "jvm/GE-20260428-fd7a65.md"},
            {"id": "jvm/GE-20260604-ed1b02.md"},
            {"id": "jvm/GE-20260999-unknown.md"},
            {"id": "jvm/GE-20260999-other00.md"},
        ]},
    ]
    assert compute_unscored_pct(results, SAMPLE_BASELINE) == 0.5


def test_compute_unscored_pct_wrong_scenario():
    results = [
        {"scenario_id": "issue-2-cdi-wiring", "entries": [
            {"id": "jvm/GE-20260428-fd7a65.md"},
        ]},
    ]
    assert compute_unscored_pct(results, SAMPLE_BASELINE) == 1.0


def test_compute_unscored_pct_empty_results():
    assert compute_unscored_pct([], SAMPLE_BASELINE) == 0.0


def test_compute_unscored_pct_no_entries():
    results = [{"scenario_id": "issue-1-reactive-async", "entries": []}]
    assert compute_unscored_pct(results, SAMPLE_BASELINE) == 0.0


def test_main_includes_unscored_pct(monkeypatch, tmp_path):
    import benchmark.run_queries as rq

    monkeypatch.setattr(rq, "wait_for_readiness", lambda *a, **kw: 2000)
    monkeypatch.setattr(rq, "run_all_queries", lambda eu, limit=None: [
        {"scenario_id": "issue-1-reactive-async", "query_type": "KW",
         "query_text": "test", "entries": [{"id": "jvm/GE-unknown.md"}],
         "latency_ms": [10.0], "latency_median_ms": 10.0},
    ])
    monkeypatch.setattr(rq, "RESULTS_DIR", tmp_path)
    monkeypatch.setattr(rq, "BASELINE_PATH", tmp_path / "empty_baseline.json")
    (tmp_path / "empty_baseline.json").write_text("{}")
    monkeypatch.setattr("sys.argv", ["run_queries.py", "test-config", "--min-points", "1"])

    rq.main()

    result = json.loads((tmp_path / "test-config.json").read_text())
    assert "unscored_pct" in result
    assert result["unscored_pct"] == 1.0
