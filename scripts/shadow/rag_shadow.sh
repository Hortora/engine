#!/bin/bash
# PostToolUse hook handler — detects garden grep commands and delegates to Python.
# Must be fast for non-matching commands (exit 0 immediately).

INPUT=$(cat)
CMD=$(echo "$INPUT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null)

# Fast exit for non-garden grep commands.
# Check for garden path (both $HOME and ~ forms) AND git grep with -il flags.
# The -il pattern distinguishes garden file-listing grep from git log --grep.
GARDEN_ABS="${HORTORA_GARDEN:-$HOME/.hortora/garden}"
{ echo "$CMD" | grep -qF "$GARDEN_ABS" || echo "$CMD" | grep -qF '/.hortora/garden'; } && echo "$CMD" | grep -qE 'git\b.*\bgrep\b.*-[a-zA-Z]*i[a-zA-Z]*l' || exit 0

# Delegate to Python handler (backgrounded for zero latency impact).
# Export $PPID (Claude Code's process) as SESSION_PID before the pipeline —
# once backgrounded, the shell exits and os.getppid() returns 1 (launchd).
SCRIPT_DIR="$(python3 -c "import os,sys; print(os.path.dirname(os.path.realpath(sys.argv[1])))" "$0")"
export SESSION_PID=$PPID
export PYTHONPATH="${SCRIPT_DIR}/.."
echo "$INPUT" | python3 "${SCRIPT_DIR}/rag_shadow.py" &
exit 0
