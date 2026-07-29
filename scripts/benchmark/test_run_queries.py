# scripts/benchmark/test_run_queries.py
import hashlib
import json
from unittest.mock import patch, MagicMock
import pytest
from benchmark.run_queries import (
    parse_search_response, compute_median, compute_unscored_pct, restore_snapshot,
)

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


def _make_snapshot(tmp_path, name="test-snap", point_count=2400, qdrant_version="1.12.6"):
    snap_dir = tmp_path / name
    snap_dir.mkdir(parents=True)
    snapshot_data = b"fake snapshot data"
    snapshot_file = snap_dir / "collection.snapshot"
    snapshot_file.write_bytes(snapshot_data)
    real_sha = hashlib.sha256(snapshot_data).hexdigest()
    manifest = {
        "name": name,
        "point_count": point_count,
        "created": "2026-07-29T10:00:00Z",
        "engine_commit": "7abba11",
        "garden_sha": "973b326a",
        "qdrant_version": qdrant_version,
        "qdrant_snapshot_name": "hortora_garden.snapshot",
        "snapshot_sha256": real_sha,
        "snapshot_size_bytes": len(snapshot_data),
        "scoring_sha": "e8f1a2b3",
    }
    (snap_dir / "manifest.json").write_text(json.dumps(manifest))
    return manifest


def _mock_urlopen_resp(data, status=200):
    mock_resp = MagicMock()
    if isinstance(data, bytes):
        mock_resp.read.return_value = data
    else:
        mock_resp.read.return_value = json.dumps(data).encode()
    mock_resp.__enter__ = lambda s: s
    mock_resp.__exit__ = MagicMock(return_value=False)
    mock_resp.status = status
    return mock_resp


def test_restore_snapshot_not_found(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.run_queries.SNAPSHOT_DIR", tmp_path)
    with pytest.raises(SystemExit):
        restore_snapshot("nonexistent", "http://localhost:6333")


def test_restore_snapshot_integrity_failure(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.run_queries.SNAPSHOT_DIR", tmp_path)
    snap_dir = tmp_path / "bad-snap"
    snap_dir.mkdir()
    (snap_dir / "collection.snapshot").write_bytes(b"data")
    manifest = {"snapshot_sha256": "wrong_hash", "snapshot_size_bytes": 4,
                "point_count": 100, "qdrant_version": "1.12.6",
                "scoring_sha": "abc", "name": "bad-snap"}
    (snap_dir / "manifest.json").write_text(json.dumps(manifest))
    with pytest.raises(SystemExit):
        restore_snapshot("bad-snap", "http://localhost:6333")


@patch("benchmark.run_queries.urllib.request.urlopen")
def test_restore_snapshot_success(mock_urlopen, tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.run_queries.SNAPSHOT_DIR", tmp_path)
    monkeypatch.setattr("benchmark.run_queries.BASELINE_PATH", tmp_path / "b.json")
    (tmp_path / "b.json").write_text("{}")
    manifest = _make_snapshot(tmp_path, "good-snap")

    qdrant_version_resp = _mock_urlopen_resp({"version": "1.12.6"})
    delete_resp = _mock_urlopen_resp({"result": True})
    upload_resp = _mock_urlopen_resp({"result": True})
    collection_resp = _mock_urlopen_resp(
        {"result": {"status": "green", "points_count": 2400}}
    )
    mock_urlopen.side_effect = [
        qdrant_version_resp, delete_resp, upload_resp, collection_resp,
    ]
    result = restore_snapshot("good-snap", "http://localhost:6333")
    assert result["name"] == "good-snap"
    assert result["point_count"] == 2400


@patch("benchmark.run_queries.restore_snapshot")
def test_main_with_corpus_snapshot(mock_restore, monkeypatch, tmp_path):
    import benchmark.run_queries as rq

    mock_restore.return_value = {
        "name": "test-snap", "point_count": 2400,
        "engine_commit": "7abba11", "garden_sha": "973b326a",
        "qdrant_version": "1.12.6",
    }
    monkeypatch.setattr(rq, "run_all_queries", lambda eu, limit=None: [])
    monkeypatch.setattr(rq, "RESULTS_DIR", tmp_path)
    monkeypatch.setattr(rq, "BASELINE_PATH", tmp_path / "b.json")
    (tmp_path / "b.json").write_text("{}")
    monkeypatch.setattr("sys.argv", [
        "run_queries.py", "snap-test", "--corpus-snapshot", "test-snap",
    ])

    rq.main()

    result = json.loads((tmp_path / "snap-test.json").read_text())
    assert result["corpus_snapshot"] == "test-snap"
    assert result["snapshot_manifest"]["point_count"] == 2400
    assert result["snapshot_manifest"]["engine_commit"] == "7abba11"
    mock_restore.assert_called_once()


def test_main_without_snapshot_has_no_snapshot_field(monkeypatch, tmp_path):
    import benchmark.run_queries as rq

    monkeypatch.setattr(rq, "wait_for_readiness", lambda *a, **kw: 2000)
    monkeypatch.setattr(rq, "run_all_queries", lambda eu, limit=None: [])
    monkeypatch.setattr(rq, "RESULTS_DIR", tmp_path)
    monkeypatch.setattr(rq, "BASELINE_PATH", tmp_path / "b.json")
    (tmp_path / "b.json").write_text("{}")
    monkeypatch.setattr("sys.argv", ["run_queries.py", "no-snap", "--min-points", "1"])

    rq.main()

    result = json.loads((tmp_path / "no-snap.json").read_text())
    assert "corpus_snapshot" not in result
    assert "snapshot_manifest" not in result
    assert "unscored_pct" in result


@patch("benchmark.run_queries.restore_snapshot")
def test_main_snapshot_skips_wait_for_readiness(mock_restore, monkeypatch, tmp_path):
    import benchmark.run_queries as rq

    wait_called = []
    monkeypatch.setattr(rq, "wait_for_readiness",
                        lambda *a, **kw: wait_called.append(1) or 2000)

    mock_restore.return_value = {"name": "s", "point_count": 100,
                                 "engine_commit": "x", "garden_sha": "y",
                                 "qdrant_version": "1.0.0"}
    monkeypatch.setattr(rq, "run_all_queries", lambda eu, limit=None: [])
    monkeypatch.setattr(rq, "RESULTS_DIR", tmp_path)
    monkeypatch.setattr(rq, "BASELINE_PATH", tmp_path / "b.json")
    (tmp_path / "b.json").write_text("{}")
    monkeypatch.setattr("sys.argv", [
        "run_queries.py", "test", "--corpus-snapshot", "s", "--min-points", "9999",
    ])
    rq.main()

    assert len(wait_called) == 0
