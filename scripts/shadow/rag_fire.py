#!/usr/bin/env python3
"""Debouncer — waits for grep quiescence, fires one RAG call, logs comparison."""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from shadow.rag_shadow import normalize_ge_id

PENDING_PATH = Path(os.environ.get(
    "RAG_SHADOW_PENDING",
    os.path.expanduser("~/.hortora/tmp/rag_pending.jsonl"),
))
PID_PATH = PENDING_PATH.parent / "rag_fire.pid"
LOG_PATH = Path(os.environ.get(
    "RAG_SHADOW_LOG",
    os.path.expanduser("~/.hortora/logs/rag-comparison.jsonl"),
))
ENGINE_URL = os.environ.get("RAG_SHADOW_ENGINE_URL", "http://localhost:8080")
QUIESCENCE_S = int(os.environ.get("RAG_SHADOW_QUIESCENCE", "60"))
TIMEOUT_S = int(os.environ.get("RAG_SHADOW_TIMEOUT", "300"))
RAG_LIMIT = 50


def strip_regex_syntax(pattern: str) -> str:
    result = pattern
    result = result.replace(r"\.", ".").replace(r"\(", "(").replace(r"\)", ")")
    result = re.sub(r"\.\*", " ", result)
    result = re.sub(r"\.\+", " ", result)
    result = re.sub(r"\[.*?\]", " ", result)
    result = re.sub(r"[|]", " ", result)
    result = re.sub(r"[*+?^${}()\\]", "", result)
    result = re.sub(r"\s+", " ", result).strip()
    return result


def combine_keywords(patterns: list[str]) -> str:
    if not patterns:
        return ""
    all_terms: list[str] = []
    seen: set[str] = set()
    for pattern in patterns:
        cleaned = strip_regex_syntax(pattern)
        for term in cleaned.split():
            if term and term not in seen:
                seen.add(term)
                all_terms.append(term)
    return " ".join(all_terms)


def group_by_session(records: list[dict]) -> dict[str, list[dict]]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        groups[record["session_id"]].append(record)
    return dict(groups)


def build_rag_query(grep_calls: list[dict]) -> str:
    return combine_keywords([call["keywords"] for call in grep_calls])


def build_comparison_record(
    session_id: str,
    grep_calls: list[dict],
    rag_query: str,
    rag_results: list[dict],
    rag_status: str,
    rag_latency_ms: float,
) -> dict:
    grep_union_set: set[str] = set()
    for call in grep_calls:
        for path in call.get("grep_results", []):
            grep_union_set.add(normalize_ge_id(path))

    normalized_rag = []
    for r in rag_results:
        entry: dict = {
            "id": normalize_ge_id(r["id"]),
            "title": r.get("title", ""),
            "relevance": r.get("relevance", 0.0),
        }
        if r.get("crossEncoderScore") is not None:
            entry["crossEncoderScore"] = r["crossEncoderScore"]
        normalized_rag.append(entry)

    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "session_id": session_id,
        "grep_calls": grep_calls,
        "grep_union": sorted(grep_union_set),
        "rag_query": rag_query,
        "rag_results": normalized_rag,
        "rag_status": rag_status,
        "rag_latency_ms": rag_latency_ms,
    }


def is_quiescent(path: Path, quiescence_s: int) -> bool:
    if not path.exists():
        return True
    mtime = path.stat().st_mtime
    return (time.time() - mtime) >= quiescence_s


def _call_engine(query: str) -> tuple[list[dict], str, float]:
    url = f"{ENGINE_URL}/search?q={urllib.parse.quote(query)}&limit={RAG_LIMIT}"
    start = time.monotonic()
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = json.loads(resp.read().decode())
        latency = (time.monotonic() - start) * 1000
        return body, "ok", latency
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as e:
        latency = (time.monotonic() - start) * 1000
        return [], "unavailable", latency


def _read_pending() -> list[dict]:
    if not PENDING_PATH.exists():
        return []
    records = []
    for line in PENDING_PATH.read_text().splitlines():
        line = line.strip()
        if line:
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return records


def _process_and_log() -> None:
    records = _read_pending()
    if not records:
        return

    sessions = group_by_session(records)
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)

    for session_id, grep_calls in sessions.items():
        query = build_rag_query(grep_calls)
        if not query:
            continue
        rag_results, status, latency = _call_engine(query)
        record = build_comparison_record(
            session_id, grep_calls, query, rag_results, status, latency,
        )
        with open(LOG_PATH, "a") as f:
            f.write(json.dumps(record) + "\n")

    PENDING_PATH.unlink(missing_ok=True)


def main() -> None:
    PID_PATH.parent.mkdir(parents=True, exist_ok=True)
    PID_PATH.write_text(str(os.getpid()))

    try:
        start_time = time.time()
        while True:
            time.sleep(10)
            if is_quiescent(PENDING_PATH, QUIESCENCE_S):
                _process_and_log()
                break
            if time.time() - start_time > TIMEOUT_S:
                _process_and_log()
                break
    finally:
        PID_PATH.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
