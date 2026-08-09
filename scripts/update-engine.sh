#!/bin/bash
# Hortora engine developer tool — rebuild and restart after code changes.
# For first-time setup, use: scripts/hortora-setup.sh install
set -euo pipefail

ENGINE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="io.hortora.engine"
DOMAIN="gui/$(id -u)"
LOG_DIR="$HOME/.hortora/logs"

case "${1:-help}" in

update)
    echo "Building engine..."
    "$ENGINE_DIR/mvnw" -f "$ENGINE_DIR/pom.xml" package -DskipTests -q
    echo "  Built: target/quarkus-app/quarkus-run.jar"

    echo "Restarting service..."
    launchctl kickstart -k "$DOMAIN/$LABEL"
    echo "  Restarted: $LABEL"

    sleep 3
    if curl -sf http://localhost:8080/search?q=test > /dev/null 2>&1; then
        echo "Engine is running on port 8080."
    else
        echo "Engine starting... check logs: $0 logs"
    fi
    ;;

status)
    if launchctl print "$DOMAIN/$LABEL" 2>/dev/null | head -5; then
        echo ""
        if curl -sf http://localhost:8080/search?q=test > /dev/null 2>&1; then
            echo "HTTP: responding on port 8080"
        else
            echo "HTTP: not responding (starting or crashed)"
        fi
    else
        echo "Service not installed. Run: scripts/hortora-setup.sh install"
    fi
    ;;

logs)
    tail -f "$LOG_DIR/engine-stdout.log" "$LOG_DIR/engine-stderr.log"
    ;;

help|*)
    echo "Usage: $0 {update|status|logs}"
    echo ""
    echo "  update     Rebuild and restart (the common case after code changes)"
    echo "  status     Show service state and HTTP health"
    echo "  logs       Tail engine log files"
    echo ""
    echo "First-time setup: scripts/hortora-setup.sh install"
    ;;

esac
