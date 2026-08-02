"""Tests for rag_fire.py — the debouncer that fires RAG calls after grep quiescence."""

import json
import os
import tempfile
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from shadow.rag_fire import (
    combine_keywords,
    group_by_session,
    build_rag_query,
    build_comparison_record,
    strip_regex_syntax,
    is_quiescent,
)


class TestStripRegexSyntax:
    def test_pipe_alternation(self):
        assert strip_regex_syntax("BlockingOperationNotAllowedException|Vert.x") == "BlockingOperationNotAllowedException Vert.x"

    def test_dot_star(self):
        assert strip_regex_syntax("CDI.*Alternative") == "CDI Alternative"

    def test_backslash_dot(self):
        assert strip_regex_syntax(r"Vert\.x") == "Vert.x"

    def test_character_class(self):
        assert strip_regex_syntax("thread[._-]safe") == "thread safe"

    def test_anchors(self):
        assert strip_regex_syntax("^CDI$") == "CDI"

    def test_quantifiers(self):
        assert strip_regex_syntax("scan+|pagination?") == "scan pagination"

    def test_plain_text(self):
        assert strip_regex_syntax("BlockingOperationNotAllowedException") == "BlockingOperationNotAllowedException"

    def test_complex_pattern(self):
        result = strip_regex_syntax("Priority\\(100\\)|CDI priority|tier.*Alternative")
        assert "Priority" in result
        assert "CDI" in result
        assert "Alternative" in result

    def test_collapses_whitespace(self):
        assert strip_regex_syntax("a|b||c") == "a b c"


class TestCombineKeywords:
    def test_single_pattern(self):
        result = combine_keywords(["BlockingOperationNotAllowedException|Vert.x"])
        assert "BlockingOperationNotAllowedException" in result
        assert "Vert.x" in result

    def test_multiple_patterns_deduped(self):
        result = combine_keywords([
            "BlockingOperationNotAllowedException|Vert.x",
            "IO thread|event loop",
            "Vert.x|EntityManager",
        ])
        words = result.split()
        assert words.count("Vert.x") == 1

    def test_empty_list(self):
        assert combine_keywords([]) == ""

    def test_preserves_meaningful_terms(self):
        result = combine_keywords(["CDI.*Alternative", "Priority\\(100\\)"])
        assert "CDI" in result
        assert "Alternative" in result
        assert "Priority" in result


class TestGroupBySession:
    def test_single_session(self):
        records = [
            {"session_id": "100", "keywords": "a|b", "grep_results": ["x.md"], "command": "cmd1", "timestamp": "t1"},
            {"session_id": "100", "keywords": "c|d", "grep_results": ["y.md"], "command": "cmd2", "timestamp": "t2"},
        ]
        groups = group_by_session(records)
        assert len(groups) == 1
        assert "100" in groups
        assert len(groups["100"]) == 2

    def test_multiple_sessions(self):
        records = [
            {"session_id": "100", "keywords": "a", "grep_results": [], "command": "c1", "timestamp": "t1"},
            {"session_id": "200", "keywords": "b", "grep_results": [], "command": "c2", "timestamp": "t2"},
            {"session_id": "100", "keywords": "c", "grep_results": [], "command": "c3", "timestamp": "t3"},
        ]
        groups = group_by_session(records)
        assert len(groups) == 2
        assert len(groups["100"]) == 2
        assert len(groups["200"]) == 1


class TestBuildRagQuery:
    def test_from_grep_calls(self):
        grep_calls = [
            {"keywords": "BlockingOperationNotAllowedException|Vert.x"},
            {"keywords": "IO thread|event loop"},
        ]
        query = build_rag_query(grep_calls)
        assert "BlockingOperationNotAllowedException" in query
        assert "Vert.x" in query
        assert "IO" in query
        assert "thread" in query


class TestBuildComparisonRecord:
    def test_structure(self):
        grep_calls = [
            {
                "command": "git grep ...",
                "keywords": "CDI|Alternative",
                "grep_results": ["reactive/GE-20260428-a67806.md"],
                "timestamp": "2026-07-30T10:14:30Z",
            }
        ]
        rag_results = [
            {"id": "jvm/GE-20260428-a67806.md", "title": "Test", "relevance": 0.92, "crossEncoderScore": 0.87},
        ]
        record = build_comparison_record(
            session_id="12345",
            grep_calls=grep_calls,
            rag_query="CDI Alternative",
            rag_results=rag_results,
            rag_status="ok",
            rag_latency_ms=1200,
        )
        assert record["session_id"] == "12345"
        assert record["grep_union"] == ["GE-20260428-a67806"]
        assert len(record["rag_results"]) == 1
        assert record["rag_results"][0]["id"] == "GE-20260428-a67806"
        assert record["rag_status"] == "ok"
        assert record["rag_latency_ms"] == 1200
        assert "timestamp" in record

    def test_grep_union_deduped(self):
        grep_calls = [
            {"command": "c1", "keywords": "a", "grep_results": ["r/GE-20260428-a67806.md"], "timestamp": "t1"},
            {"command": "c2", "keywords": "b", "grep_results": ["r/GE-20260428-a67806.md", "c/GE-20260415-884e48.md"], "timestamp": "t2"},
        ]
        record = build_comparison_record("s", grep_calls, "q", [], "ok", 0)
        assert sorted(record["grep_union"]) == ["GE-20260415-884e48", "GE-20260428-a67806"]

    def test_rag_results_normalized(self):
        rag_results = [
            {"id": "jvm/GE-20260428-a67806.md", "title": "T", "relevance": 0.9, "crossEncoderScore": None},
        ]
        record = build_comparison_record("s", [], "q", rag_results, "ok", 0)
        assert record["rag_results"][0]["id"] == "GE-20260428-a67806"
        assert "crossEncoderScore" not in record["rag_results"][0]

    def test_engine_unavailable(self):
        record = build_comparison_record("s", [], "q", [], "unavailable", 0)
        assert record["rag_status"] == "unavailable"
        assert record["rag_results"] == []


class TestIsQuiescent:
    def test_recent_file_not_quiescent(self):
        with tempfile.NamedTemporaryFile(suffix=".jsonl", delete=False) as f:
            f.write(b"test\n")
            path = Path(f.name)
        try:
            assert is_quiescent(path, quiescence_s=60) is False
        finally:
            path.unlink()

    def test_old_file_is_quiescent(self):
        with tempfile.NamedTemporaryFile(suffix=".jsonl", delete=False) as f:
            f.write(b"test\n")
            path = Path(f.name)
        try:
            old_time = time.time() - 120
            os.utime(path, (old_time, old_time))
            assert is_quiescent(path, quiescence_s=60) is True
        finally:
            path.unlink()

    def test_missing_file_is_quiescent(self):
        assert is_quiescent(Path("/nonexistent"), quiescence_s=60) is True
