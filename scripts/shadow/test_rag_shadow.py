"""Tests for rag_shadow.py — the PostToolUse hook handler."""

import json
import os
import tempfile
from pathlib import Path
from unittest.mock import patch

import pytest

from shadow.rag_shadow import (
    extract_keywords,
    extract_grep_results,
    normalize_ge_id,
    is_garden_grep,
    build_pending_record,
)


class TestExtractKeywords:
    def test_simple_e_flag(self):
        cmd = "git -C ~/.hortora/garden grep -il -E 'BlockingOperationNotAllowedException|Vert.x' HEAD -- '*.md'"
        assert extract_keywords(cmd) == "BlockingOperationNotAllowedException|Vert.x"

    def test_double_quoted_pattern(self):
        cmd = 'git -C ~/.hortora/garden grep -il -E "IO thread|event loop" HEAD -- \'*.md\''
        assert extract_keywords(cmd) == "IO thread|event loop"

    def test_no_e_flag_positional(self):
        cmd = "git -C ~/.hortora/garden grep -il 'CDI.*Alternative' HEAD -- '*.md'"
        assert extract_keywords(cmd) == "CDI.*Alternative"

    def test_combined_flags(self):
        cmd = "git -C ~/.hortora/garden grep -ilE 'ChatModel|AgentSession' HEAD -- '*.md'"
        assert extract_keywords(cmd) == "ChatModel|AgentSession"

    def test_no_pattern_returns_empty(self):
        cmd = "git -C ~/.hortora/garden grep HEAD"
        assert extract_keywords(cmd) == ""

    def test_pattern_with_exclusions(self):
        cmd = "git -C ~/.hortora/garden grep -il -E 'Panache|scan' HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md'"
        assert extract_keywords(cmd) == "Panache|scan"


class TestExtractGrepResults:
    def test_head_prefix_stripped(self):
        output = "HEAD:reactive/GE-20260428-a67806.md\nHEAD:cdi/GE-20260415-884e48.md\n"
        results = extract_grep_results(output)
        assert results == ["reactive/GE-20260428-a67806.md", "cdi/GE-20260415-884e48.md"]

    def test_no_head_prefix(self):
        output = "reactive/GE-20260428-a67806.md\ncdi/GE-20260415-884e48.md\n"
        results = extract_grep_results(output)
        assert results == ["reactive/GE-20260428-a67806.md", "cdi/GE-20260415-884e48.md"]

    def test_empty_output(self):
        assert extract_grep_results("") == []
        assert extract_grep_results("\n") == []

    def test_mixed_prefix(self):
        output = "HEAD:reactive/GE-20260428-a67806.md\ncdi/GE-20260415-884e48.md\n"
        results = extract_grep_results(output)
        assert results == ["reactive/GE-20260428-a67806.md", "cdi/GE-20260415-884e48.md"]

    def test_filters_non_ge_paths(self):
        output = "HEAD:GARDEN.md\nHEAD:reactive/GE-20260428-a67806.md\nHEAD:CHECKED.md\n"
        results = extract_grep_results(output)
        assert results == ["reactive/GE-20260428-a67806.md"]

    def test_filters_labels(self):
        output = "HEAD:labels/cdi.md\nHEAD:reactive/GE-20260428-a67806.md\nHEAD:labels/testing.md\n"
        results = extract_grep_results(output)
        assert results == ["reactive/GE-20260428-a67806.md"]

    def test_filters_summaries(self):
        output = "HEAD:_summaries/jvm/GE-20260428-a67806.md\nHEAD:jvm/GE-20260428-a67806.md\n"
        results = extract_grep_results(output)
        assert results == ["jvm/GE-20260428-a67806.md"]

    def test_filters_index(self):
        output = "HEAD:jvm/INDEX.md\nHEAD:_index/global.md\nHEAD:jvm/GE-0139.md\n"
        results = extract_grep_results(output)
        assert results == ["jvm/GE-0139.md"]

    def test_filters_non_ge_names(self):
        output = "HEAD:drools/rule-builder-dsl.md\nHEAD:drools/GE-0056.md\n"
        results = extract_grep_results(output)
        assert results == ["drools/GE-0056.md"]


class TestNormalizeGeId:
    def test_strip_directory_and_extension(self):
        assert normalize_ge_id("reactive/GE-20260428-a67806.md") == "GE-20260428-a67806"

    def test_nested_directory(self):
        assert normalize_ge_id("tools/jvm/GE-20260420-c1d394.md") == "GE-20260420-c1d394"

    def test_bare_filename(self):
        assert normalize_ge_id("GE-20260428-a67806.md") == "GE-20260428-a67806"

    def test_already_normalized(self):
        assert normalize_ge_id("GE-20260428-a67806") == "GE-20260428-a67806"

    def test_non_ge_path_returns_stem(self):
        assert normalize_ge_id("approaches/testing.md") == "testing"

    def test_old_format_ge_id(self):
        assert normalize_ge_id("tools/GE-0139.md") == "GE-0139"

    def test_protocol_path(self):
        assert normalize_ge_id("casehub/platform-casememorystore-timed-annotation") == "platform-casememorystore-timed-annotation"


class TestIsGardenGrep:
    def test_standard_garden_grep(self):
        cmd = "git -C /Users/mdproctor/.hortora/garden grep -il -E 'CDI' HEAD -- '*.md'"
        assert is_garden_grep(cmd, "/Users/mdproctor/.hortora/garden") is True

    def test_non_garden_grep(self):
        cmd = "git -C /some/other/repo grep -il 'CDI' HEAD"
        assert is_garden_grep(cmd, "/Users/mdproctor/.hortora/garden") is False

    def test_garden_path_with_tilde(self):
        cmd = "git -C ~/.hortora/garden grep -il -E 'CDI' HEAD -- '*.md'"
        assert is_garden_grep(cmd, "/Users/mdproctor/.hortora/garden") is True

    def test_git_log_grep_excluded(self):
        cmd = "git -C /Users/mdproctor/.hortora/garden log --grep='CDI'"
        assert is_garden_grep(cmd, "/Users/mdproctor/.hortora/garden") is False

    def test_git_grep_without_il_excluded(self):
        cmd = "git -C /Users/mdproctor/.hortora/garden grep 'CDI' HEAD"
        assert is_garden_grep(cmd, "/Users/mdproctor/.hortora/garden") is False

    def test_custom_garden_path(self):
        cmd = "git -C /opt/garden grep -il -E 'test' HEAD -- '*.md'"
        assert is_garden_grep(cmd, "/opt/garden") is True


class TestSymlinkResolution:
    def test_resolve_script_dir_follows_symlinks(self):
        """PYTHONPATH must resolve through symlinks to the real script's parent."""
        from shadow.rag_shadow import resolve_script_dir

        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir_real = Path(os.path.realpath(tmpdir))
            real_dir = tmpdir_real / "real" / "shadow"
            real_dir.mkdir(parents=True)
            real_script = real_dir / "rag_shadow.sh"
            real_script.write_text("#!/bin/bash\n")

            symlink_dir = tmpdir_real / "links"
            symlink_dir.mkdir()
            symlink = symlink_dir / "rag_shadow.sh"
            symlink.symlink_to(real_script)

            resolved = resolve_script_dir(str(symlink))
            assert resolved == real_dir, (
                f"Expected {real_dir}, got {resolved} — "
                f"symlink should resolve to the real script's directory"
            )

    def test_resolve_script_dir_no_symlink(self):
        """Non-symlink paths resolve to their own directory."""
        from shadow.rag_shadow import resolve_script_dir

        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir_real = Path(os.path.realpath(tmpdir))
            script = tmpdir_real / "rag_shadow.sh"
            script.write_text("#!/bin/bash\n")

            resolved = resolve_script_dir(str(script))
            assert resolved == tmpdir_real


class TestDebouncerStderrVisibility:
    def test_debouncer_stderr_not_devnull(self):
        """Debouncer subprocess must log errors, not swallow them."""
        import importlib
        import inspect
        import shadow.rag_shadow as mod
        importlib.reload(mod)

        source = inspect.getsource(mod._spawn_debouncer_if_needed)
        assert "stderr=subprocess.DEVNULL" not in source, (
            "_spawn_debouncer_if_needed still uses subprocess.DEVNULL for stderr — "
            "import errors will be silently swallowed"
        )


class TestBuildPendingRecord:
    def test_record_structure(self):
        record = build_pending_record(
            session_id="12345",
            command="git grep ...",
            keywords="CDI|Alternative",
            grep_results=["reactive/GE-20260428-a67806.md"],
        )
        assert record["session_id"] == "12345"
        assert record["keywords"] == "CDI|Alternative"
        assert record["command"] == "git grep ..."
        assert record["grep_results"] == ["reactive/GE-20260428-a67806.md"]
        assert "timestamp" in record

    def test_empty_results(self):
        record = build_pending_record(
            session_id="12345",
            command="git grep ...",
            keywords="nonexistent",
            grep_results=[],
        )
        assert record["grep_results"] == []
