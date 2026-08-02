#!/bin/bash
# Hortora engine service manager — install, update, status, logs, uninstall.
set -euo pipefail

ENGINE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PLIST_SRC="$ENGINE_DIR/scripts/io.hortora.engine.plist"
PLIST_DST="$HOME/Library/LaunchAgents/io.hortora.engine.plist"
LABEL="io.hortora.engine"
DOMAIN="gui/$(id -u)"
LOG_DIR="$HOME/.hortora/logs"

case "${1:-help}" in

install)
    mkdir -p "$LOG_DIR"

    echo "Building engine..."
    "$ENGINE_DIR/mvnw" -f "$ENGINE_DIR/pom.xml" package -DskipTests -q
    echo "  Built: target/quarkus-app/quarkus-run.jar"

    echo "Installing launchd plist..."
    cp "$PLIST_SRC" "$PLIST_DST"
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    launchctl bootstrap "$DOMAIN" "$PLIST_DST"
    echo "  Installed and started: $LABEL"

    echo "Setting Qdrant restart policy..."
    docker update --restart unless-stopped qdrant-bench 2>/dev/null && echo "  Qdrant: restart=unless-stopped" || echo "  Qdrant container 'qdrant-bench' not found — skip"

    sleep 3
    if curl -sf http://localhost:8080/search?q=test > /dev/null 2>&1; then
        echo "Engine is running on port 8080."
    else
        echo "Engine starting... check logs: $0 logs"
    fi
    ;;

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

uninstall)
    echo "Stopping service..."
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null && echo "  Stopped: $LABEL" || echo "  Not running"
    rm -f "$PLIST_DST"
    echo "  Removed plist"
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
        echo "Service not installed. Run: $0 install"
    fi
    ;;

logs)
    tail -f "$LOG_DIR/engine-stdout.log" "$LOG_DIR/engine-stderr.log"
    ;;

help|*)
    echo "Usage: $0 {install|update|uninstall|status|logs}"
    echo ""
    echo "  install    Build, install plist, start service, set Qdrant restart policy"
    echo "  update     Rebuild and restart (the common case after code changes)"
    echo "  uninstall  Stop service, remove plist"
    echo "  status     Show service state and HTTP health"
    echo "  logs       Tail engine log files"
    ;;

esac
