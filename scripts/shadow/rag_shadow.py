#!/usr/bin/env python3
"""PostToolUse hook handler — records garden grep calls for RAG shadow comparison."""

import fcntl
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

PENDING_PATH = Path(os.environ.get(
    "RAG_SHADOW_PENDING",
    os.path.expanduser("~/.hortora/tmp/rag_pending.jsonl"),
))
PID_PATH = PENDING_PATH.parent / "rag_fire.pid"
LOCK_PATH = PENDING_PATH.parent / "rag_fire.lock"
DEBOUNCER_SCRIPT = Path(__file__).parent / "rag_fire.py"


def extract_keywords(command: str) -> str:
    match = re.search(r"""-[a-zA-Z]*E[a-zA-Z]*\s+(['"])(.*?)\1""", command)
    if match:
        return match.group(2)
    match = re.search(r"""-[a-zA-Z]*E[a-zA-Z]*\s+(\S+)""", command)
    if match:
        return match.group(1)
    parts = command.split()
    for i, part in enumerate(parts):
        if part == "-E" and i + 1 < len(parts):
            val = parts[i + 1].strip("'\"")
            return val
    for i, part in enumerate(parts):
        if "grep" in part:
            for j in range(i + 1, len(parts)):
                candidate = parts[j]
                if candidate.startswith("-"):
                    continue
                if candidate == "HEAD" or candidate.startswith("--"):
                    break
                return candidate.strip("'\"")
            break
    return ""


_GE_PATTERN = re.compile(r"GE-(\d{8}-[0-9a-f]{6}|\d{4})$")


def _is_ge_entry(path: str) -> bool:
    stem = Path(path).stem
    return bool(_GE_PATTERN.match(stem))


def extract_grep_results(output: str) -> list[str]:
    results = []
    for line in output.strip().splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("HEAD:"):
            line = line[5:]
        if not line.endswith(".md"):
            continue
        if line.startswith("_summaries/") or line.startswith("_index/") or line.startswith("labels/"):
            continue
        stem = Path(line).stem
        if stem in ("GARDEN", "CHECKED", "DISCARDED", "INDEX"):
            continue
        if not _is_ge_entry(line):
            continue
        results.append(line)
    return results


def normalize_ge_id(path: str) -> str:
    if path.endswith(".md"):
        path = path[:-3]
    if "/" in path:
        path = path.rsplit("/", 1)[-1]
    return path


def is_garden_grep(command: str, garden_path: str) -> bool:
    has_garden = garden_path in command or "/.hortora/garden" in command
    if not has_garden:
        return False
    has_git_grep = bool(re.search(r"\bgit\b.*\bgrep\b", command))
    if not has_git_grep:
        return False
    has_il_flags = bool(re.search(r"grep.*-[a-zA-Z]*i[a-zA-Z]*l", command))
    return has_il_flags


def build_pending_record(
    session_id: str,
    command: str,
    keywords: str,
    grep_results: list[str],
) -> dict:
    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "session_id": session_id,
        "command": command,
        "keywords": keywords,
        "grep_results": grep_results,
    }


LOG_DIR = Path(os.path.expanduser("~/.hortora/logs"))


def resolve_script_dir(script_path: str) -> Path:
    """Resolve through symlinks to find the real script's directory."""
    return Path(os.path.realpath(script_path)).parent


def _spawn_debouncer_if_needed() -> None:
    PENDING_PATH.parent.mkdir(parents=True, exist_ok=True)
    lock_fd = os.open(str(LOCK_PATH), os.O_CREAT | os.O_RDWR)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        os.close(lock_fd)
        return

    try:
        if PID_PATH.exists():
            try:
                pid = int(PID_PATH.read_text().strip())
                os.kill(pid, 0)
                return
            except (ValueError, ProcessLookupError, PermissionError):
                PID_PATH.unlink(missing_ok=True)

        LOG_DIR.mkdir(parents=True, exist_ok=True)
        stderr_log = open(LOG_DIR / "rag_fire_stderr.log", "a")
        subprocess.Popen(
            [sys.executable, str(DEBOUNCER_SCRIPT)],
            stdout=subprocess.DEVNULL,
            stderr=stderr_log,
            start_new_session=True,
        )
    finally:
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)


def main() -> None:
    try:
        hook_input = json.load(sys.stdin)
    except (json.JSONDecodeError, EOFError):
        return

    command = hook_input.get("tool_input", {}).get("command", "")
    tool_response = hook_input.get("tool_response", {})
    output = tool_response.get("stdout", "") if isinstance(tool_response, dict) else str(tool_response)

    garden_path = os.environ.get("HORTORA_GARDEN", os.path.expanduser("~/.hortora/garden"))
    if not is_garden_grep(command, garden_path):
        return

    session_id = os.environ.get("SESSION_PID", str(os.getpid()))
    keywords = extract_keywords(command)
    grep_results = extract_grep_results(output)

    record = build_pending_record(session_id, command, keywords, grep_results)

    PENDING_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(PENDING_PATH, "a") as f:
        f.write(json.dumps(record) + "\n")

    _spawn_debouncer_if_needed()


if __name__ == "__main__":
    main()
