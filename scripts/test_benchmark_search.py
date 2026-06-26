"""Tests for benchmark_search report formatting."""
import pytest
from benchmark_search import (
    Query, SearchResult, QueryResult, format_report, QUERIES,
)


def _query(name: str = "test query") -> Query:
    return Query(name=name, search_query=name, grep_pattern="test", style="test style")


def _search_result(
    doc_id: str = "GE-20260101-abc123",
    title: str = "Test Entry",
    domain: str = "jvm",
    type_: str = "gotcha",
    relevance: float = 0.85,
) -> SearchResult:
    return SearchResult(doc_id=doc_id, title=title, domain=domain, type=type_, relevance=relevance)


class TestFormatReport:
    def test_includes_title(self) -> None:
        report = format_report([])
        assert "# Garden Search vs Grep — Comparison Report" in report

    def test_per_query_section_with_results(self) -> None:
        qr = QueryResult(
            query=_query("qdrant java client"),
            search_results=[_search_result()],
            grep_files=["HEAD:jvm/GE-20260101-abc123.md"],
        )
        report = format_report([qr])
        assert "### Query 1: `qdrant java client`" in report
        assert "GE-20260101-abc123" in report
        assert "jvm/GE-20260101-abc123.md" in report

    def test_search_error_displayed(self) -> None:
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=[],
            search_error="Connection refused",
        )
        report = format_report([qr])
        assert "ERROR — Connection refused" in report

    def test_grep_error_displayed(self) -> None:
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=[],
            grep_error="git not found",
        )
        report = format_report([qr])
        assert "ERROR — git not found" in report

    def test_empty_results_shown(self) -> None:
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=[],
        )
        report = format_report([qr])
        assert "No results" in report
        assert "No matches" in report

    def test_summary_table_counts(self) -> None:
        qr = QueryResult(
            query=_query("test"),
            search_results=[_search_result(), _search_result(doc_id="GE-20260102-def456")],
            grep_files=["HEAD:a.md", "HEAD:b.md", "HEAD:c.md"],
        )
        report = format_report([qr])
        assert "| 1 | `test` | 2 | 3 |" in report

    def test_grep_files_stripped_of_head_prefix(self) -> None:
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=["HEAD:jvm/test.md"],
        )
        report = format_report([qr])
        lines_after_grep = report.split("grep results")[1]
        assert "`jvm/test.md`" in lines_after_grep
        assert "HEAD:" not in lines_after_grep

    def test_grep_files_truncated_at_20(self) -> None:
        files = [f"HEAD:jvm/GE-{i:08d}-000000.md" for i in range(25)]
        qr = QueryResult(
            query=_query(),
            search_results=[],
            grep_files=files,
        )
        report = format_report([qr])
        assert "... and 5 more" in report


class TestQueryDefinitions:
    def test_six_queries_defined(self) -> None:
        assert len(QUERIES) == 6

    @pytest.mark.parametrize("idx", range(6))
    def test_query_has_required_fields(self, idx: int) -> None:
        q = QUERIES[idx]
        assert q.name
        assert q.search_query
        assert q.grep_pattern
        assert q.style
