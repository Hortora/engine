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

def test_compute_median_with_none():
    """Test that compute_median skips None values."""
    assert compute_median([100.0, None, 150.0]) == 125.0
    assert compute_median([None, 100.0, 200.0]) == 150.0
    assert compute_median([100.0, 150.0, None]) == 125.0

def test_compute_median_all_none():
    """Test that compute_median returns None when all values are None."""
    assert compute_median([None, None, None]) is None
    assert compute_median([]) is None
