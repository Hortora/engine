#!/bin/bash
# garden-bom-hook.sh — PreToolUse hook for mcp__hortora__gardenSearch
#
# Resolves BOM from ~/.hortora/profile.yaml + <project>/.hortora/bom.yaml,
# creates/updates a search profile on the engine, and injects the profile
# param into gardenSearch calls.
#
# Install as a PreToolUse hook in ~/.claude/settings.json:
#   "hooks": {
#     "PreToolUse": [{
#       "matcher": "mcp__hortora__gardenSearch",
#       "command": "~/.hortora/engine/scripts/garden-bom-hook.sh"
#     }]
#   }

TOOL_NAME="$1"
if [ "$TOOL_NAME" != "mcp__hortora__gardenSearch" ]; then
    exit 0
fi

PROJECT_ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
PROFILE_NAME=$(basename "${PROJECT_ROOT:-default}")
FLAG="/tmp/.hortora-bom-${PROFILE_NAME}"

if [ -f "$FLAG" ]; then
    exit 0
fi

STACK=$(python3 ~/.hortora/engine/scripts/resolve-bom.py 2>/dev/null)
if [ -z "$STACK" ]; then
    exit 0
fi

curl -s -X PUT "http://localhost:8080/api/garden/profiles/${PROFILE_NAME}" \
    -H "Content-Type: application/json" \
    -d "{\"stack\": \"${STACK}\"}" >/dev/null 2>&1

touch "$FLAG"
exit 0
