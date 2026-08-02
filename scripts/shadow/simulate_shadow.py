#!/usr/bin/env python3
"""Simulate 3 separate brainstorming sessions with real grep output."""

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from shadow.rag_shadow import extract_grep_results

GARDEN = os.environ.get("HORTORA_GARDEN", os.path.expanduser("~/.hortora/garden"))
PENDING = Path.home() / ".hortora/tmp/rag_pending.jsonl"
PENDING.parent.mkdir(parents=True, exist_ok=True)

specs = [
    {
        "session_id": "sim-ce-filter",
        "greps": [
            "cross.encoder|reranking|score.*filter",
            "adaptive.*filter|CE.*score|noise.*trim",
        ],
    },
    {
        "session_id": "sim-drools-persist",
        "greps": [
            "Drools|KieSession|drools.reliability",
            "CEP|sliding.*window|session.*persist",
        ],
    },
    {
        "session_id": "sim-behavioral",
        "greps": [
            "behavioral.*contract|attestation|trust.*score",
            "AgentDescriptor|probe.*latency|capability.*trust",
        ],
    },
]

for spec in specs:
    for pattern in spec["greps"]:
        cmd = f"git -C {GARDEN} grep -il -E '{pattern}' HEAD -- '*.md' ':!GARDEN.md' ':!CHECKED.md' ':!DISCARDED.md'"
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        grep_results = extract_grep_results(result.stdout)

        record = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "session_id": spec["session_id"],
            "command": cmd,
            "keywords": pattern,
            "grep_results": grep_results,
        }
        with open(PENDING, "a") as f:
            f.write(json.dumps(record) + "\n")

        print(f"  {spec['session_id']}: '{pattern[:40]}...' -> {len(grep_results)} results")

print(f"\nWrote {sum(len(s['greps']) for s in specs)} records to {PENDING}")
