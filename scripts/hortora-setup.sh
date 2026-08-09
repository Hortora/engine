#!/bin/bash
# Hortora engine installer — first-time setup and service management.
# For rebuilding after code changes, use: scripts/update-engine.sh update
set -euo pipefail

# --- Version pinning (update when publishing a new Release) ---
QDRANT_VERSION="1.19.0"
RELEASE_REPO="Hortora/engine"
RELEASE_TAG="latest"
HORTORA_DIR="$HOME/.hortora"
GARDEN_PATH="${HORTORA_GARDEN_PATH:-$HOME/.hortora/garden}"
ENGINE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LABEL_ENGINE="io.hortora.engine"
LABEL_QDRANT="io.hortora.qdrant"
DOMAIN="gui/$(id -u)"
VERSION_FILE="$HORTORA_DIR/version.json"

# --- Helpers ---

log()  { echo "[hortora] $*"; }
warn() { echo "[hortora] WARNING: $*" >&2; }
fail() { echo "[hortora] ERROR: $*" >&2; exit 1; }

version_get() {
    local key="$1"
    python3 -c "
import json
try:
    d = json.load(open('$VERSION_FILE'))
    print(d.get('$key', ''))
except:
    pass
" 2>/dev/null || true
}

version_set() {
    local key="$1" val="$2"
    python3 -c "
import json, os
p = '$VERSION_FILE'
try:
    d = json.load(open(p))
except:
    d = {}
d['$key'] = '$val'
d['installed'] = '$(date -u +%Y-%m-%dT%H:%M:%SZ)'
with open(p, 'w') as f:
    json.dump(d, f, indent=2)
print('  Updated version.json: $key=$val')
" 2>/dev/null
}

check_prerequisites() {
    local missing=0

    if ! command -v java &>/dev/null; then
        warn "JDK not found. Install JDK 25+ and ensure 'java' is on PATH."
        missing=1
    else
        local jver
        jver=$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)
        if [ "${jver:-0}" -lt 25 ] 2>/dev/null; then
            warn "JDK $jver found, but 25+ required."
            missing=1
        fi
    fi

    if ! command -v curl &>/dev/null; then
        warn "'curl' not found."
        missing=1
    fi

    if ! command -v zstd &>/dev/null; then
        warn "'zstd' not found. Install via: brew install zstd"
        missing=1
    fi

    if ! command -v gh &>/dev/null; then
        warn "'gh' (GitHub CLI) not found. Install via: brew install gh"
        missing=1
    fi

    if [ "$(uname -s)" != "Darwin" ]; then
        warn "Only macOS is supported (launchd). Linux systemd is future work."
        missing=1
    fi

    if [ "$missing" -eq 1 ]; then
        fail "Prerequisites check failed. Fix the above and retry."
    fi

    log "Prerequisites OK"
}

download_release_asset() {
    local asset="$1" dest="$2"
    log "  Downloading $asset..."
    gh release download "$RELEASE_TAG" \
        --repo "$RELEASE_REPO" \
        --pattern "$asset" \
        --output "$dest" \
        --clobber
}

template_plist() {
    local src="$1" dest="$2"
    local java_home
    java_home=$(/usr/libexec/java_home 2>/dev/null || echo "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home")
    local path_val="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"

    sed -e "s|__HOME__|$HOME|g" \
        -e "s|__JAVA_HOME__|$java_home|g" \
        -e "s|__ENGINE_DIR__|$ENGINE_DIR|g" \
        -e "s|__PATH__|$path_val|g" \
        -e "s|__QUARKUS_PROFILE__|${QUARKUS_PROFILE:-dev}|g" \
        -e "s|__GARDEN_PATH__|$GARDEN_PATH|g" \
        "$src" > "$dest"
}

wait_for_url() {
    local url="$1" timeout="${2:-30}" label="${3:-service}"
    local elapsed=0
    while ! curl -sf "$url" >/dev/null 2>&1; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [ "$elapsed" -ge "$timeout" ]; then
            warn "$label did not respond at $url within ${timeout}s"
            return 1
        fi
    done
    log "  $label responding at $url (${elapsed}s)"
}

# --- Subcommands ---

create_dirs() {
    log "Creating directory structure..."
    mkdir -p "$HORTORA_DIR/qdrant" \
             "$HORTORA_DIR/models/bge-m3" \
             "$HORTORA_DIR/models/reranker" \
             "$HORTORA_DIR/cursors" \
             "$HORTORA_DIR/cache" \
             "$HORTORA_DIR/logs" \
             "$HORTORA_DIR/stats"
    log "  Directories ready at $HORTORA_DIR"
}

do_install_qdrant() {
    local current
    current=$(version_get qdrant)
    if [ "$current" = "$QDRANT_VERSION" ] && [ -x "$HORTORA_DIR/qdrant/qdrant" ]; then
        log "Qdrant $QDRANT_VERSION already installed — skipping."
        return 0
    fi

    log "Installing Qdrant $QDRANT_VERSION..."

    local arch os_name archive_name
    arch=$(uname -m)
    os_name=$(uname -s)

    case "$os_name-$arch" in
        Darwin-arm64)  archive_name="qdrant-aarch64-apple-darwin.tar.gz" ;;
        Darwin-x86_64) archive_name="qdrant-x86_64-apple-darwin.tar.gz" ;;
        Linux-x86_64)  archive_name="qdrant-x86_64-unknown-linux-gnu.tar.gz" ;;
        Linux-aarch64) archive_name="qdrant-aarch64-unknown-linux-gnu.tar.gz" ;;
        *) fail "Unsupported platform: $os_name-$arch" ;;
    esac

    local url="https://github.com/qdrant/qdrant/releases/download/v${QDRANT_VERSION}/${archive_name}"
    log "  Downloading from $url"
    curl -fSL "$url" | tar xz -C "$HORTORA_DIR/qdrant/"
    chmod +x "$HORTORA_DIR/qdrant/qdrant"

    cat > "$HORTORA_DIR/qdrant/config.yaml" <<YAML
storage:
  storage_path: $HORTORA_DIR/qdrant/storage
service:
  grpc_port: 6334
  http_port: 6333
YAML

    template_plist "$SCRIPT_DIR/io.hortora.qdrant.plist.template" \
                   "$HOME/Library/LaunchAgents/io.hortora.qdrant.plist"
    launchctl bootout "$DOMAIN/$LABEL_QDRANT" 2>/dev/null || true
    launchctl bootstrap "$DOMAIN" "$HOME/Library/LaunchAgents/io.hortora.qdrant.plist"

    wait_for_url "http://localhost:6333/" 30 "Qdrant"
    version_set qdrant "$QDRANT_VERSION"
    log "Qdrant $QDRANT_VERSION installed."
}

do_install_models() {
    log "Checking ONNX models..."

    local tmpdir="$HORTORA_DIR/tmp/models-download"
    mkdir -p "$tmpdir"

    download_release_asset "checksums.sha256" "$tmpdir/checksums.sha256"

    # BGE-M3 model
    local bge_expected
    bge_expected=$(grep "bge-m3-models.tar.zst" "$tmpdir/checksums.sha256" | awk '{print $1}')
    local bge_current
    bge_current=$(version_get bge-m3)

    if [ "$bge_current" = "$bge_expected" ] && [ -f "$HORTORA_DIR/models/bge-m3/model.onnx" ]; then
        log "  BGE-M3 model up to date — skipping."
    else
        download_release_asset "bge-m3-models.tar.zst" "$tmpdir/bge-m3-models.tar.zst"
        local actual
        actual=$(shasum -a 256 "$tmpdir/bge-m3-models.tar.zst" | awk '{print $1}')
        if [ "$actual" != "$bge_expected" ]; then
            fail "BGE-M3 checksum mismatch: expected $bge_expected, got $actual"
        fi
        zstd -d "$tmpdir/bge-m3-models.tar.zst" --stdout | tar x -C "$HORTORA_DIR/models/bge-m3/"
        version_set bge-m3 "$bge_expected"
        log "  BGE-M3 model installed."
    fi

    # Reranker model
    local reranker_expected
    reranker_expected=$(grep "reranker-models.tar.zst" "$tmpdir/checksums.sha256" | awk '{print $1}')
    local reranker_current
    reranker_current=$(version_get reranker)

    if [ "$reranker_current" = "$reranker_expected" ] && [ -f "$HORTORA_DIR/models/reranker/model.onnx" ]; then
        log "  Reranker model up to date — skipping."
    else
        download_release_asset "reranker-models.tar.zst" "$tmpdir/reranker-models.tar.zst"
        actual=$(shasum -a 256 "$tmpdir/reranker-models.tar.zst" | awk '{print $1}')
        if [ "$actual" != "$reranker_expected" ]; then
            fail "Reranker checksum mismatch: expected $reranker_expected, got $actual"
        fi
        zstd -d "$tmpdir/reranker-models.tar.zst" --stdout | tar x -C "$HORTORA_DIR/models/reranker/"
        version_set reranker "$reranker_expected"
        log "  Reranker model installed."
    fi

    rm -rf "$tmpdir"
    log "Models installed."
}

do_install_snapshot() {
    local current_snapshot
    current_snapshot=$(version_get snapshot)

    local points
    points=$(curl -sf http://localhost:6333/collections/hortora_garden 2>/dev/null \
        | python3 -c "import json,sys; print(json.load(sys.stdin)['result']['points_count'])" 2>/dev/null || echo "0")

    if [ "$points" -gt 0 ] && [ -n "$current_snapshot" ]; then
        log "Snapshot already restored ($points points) — skipping."
        return 0
    fi

    log "Restoring Qdrant snapshot..."

    local tmpdir="$HORTORA_DIR/tmp/snapshot-download"
    mkdir -p "$tmpdir"

    if [ ! -f "$tmpdir/checksums.sha256" ]; then
        download_release_asset "checksums.sha256" "$tmpdir/checksums.sha256"
    fi

    local parts
    parts=$(gh release view "$RELEASE_TAG" --repo "$RELEASE_REPO" --json assets \
        --jq '.assets[].name' | grep "^snapshot.tar.zst.part-")

    for part in $parts; do
        download_release_asset "$part" "$tmpdir/$part"
        local expected actual
        expected=$(grep "$part" "$tmpdir/checksums.sha256" | awk '{print $1}')
        actual=$(shasum -a 256 "$tmpdir/$part" | awk '{print $1}')
        if [ "$actual" != "$expected" ]; then
            fail "Checksum mismatch for $part: expected $expected, got $actual"
        fi
        log "  Verified $part"
    done

    log "  Reassembling snapshot..."
    cat "$tmpdir"/snapshot.tar.zst.part-* | zstd -d | tar x -C "$tmpdir/"

    local snapshot_file
    snapshot_file=$(find "$tmpdir" -name "*.snapshot" -type f | head -1)
    if [ -z "$snapshot_file" ]; then
        fail "No .snapshot file found after extraction"
    fi

    log "  Uploading snapshot to Qdrant..."
    curl -sf -X POST "http://localhost:6333/collections/hortora_garden/snapshots/upload" \
        -H "Content-Type: multipart/form-data" \
        -F "snapshot=@${snapshot_file}" \
        >/dev/null

    download_release_asset "garden.cursor" "$tmpdir/garden.cursor"
    sed "s|__GARDEN_PATH__|$GARDEN_PATH|g" "$tmpdir/garden.cursor" \
        > "$HORTORA_DIR/cursors/garden.cursor"

    points=$(curl -sf http://localhost:6333/collections/hortora_garden \
        | python3 -c "import json,sys; print(json.load(sys.stdin)['result']['points_count'])" 2>/dev/null || echo "0")
    if [ "$points" -eq 0 ]; then
        warn "Snapshot restored but collection reports 0 points. Check Qdrant logs."
    else
        log "  Snapshot restored: $points points"
    fi

    local cursor_checksum
    cursor_checksum=$(shasum -a 256 "$HORTORA_DIR/cursors/garden.cursor" | awk '{print $1}')
    version_set snapshot "$RELEASE_TAG"
    version_set cursor "$cursor_checksum"

    rm -rf "$tmpdir"
    log "Snapshot restored."
}

do_install_engine() {
    log "Building engine..."
    "$ENGINE_DIR/mvnw" -f "$ENGINE_DIR/pom.xml" package -DskipTests -q
    log "  Built: target/quarkus-app/quarkus-run.jar"

    log "Installing engine service..."
    template_plist "$SCRIPT_DIR/io.hortora.engine.plist.template" \
                   "$HOME/Library/LaunchAgents/io.hortora.engine.plist"
    launchctl bootout "$DOMAIN/$LABEL_ENGINE" 2>/dev/null || true
    launchctl bootstrap "$DOMAIN" "$HOME/Library/LaunchAgents/io.hortora.engine.plist"

    wait_for_url "http://localhost:8080/" 30 "Engine"
    log "Engine installed and running."
}

do_status() {
    echo "Hortora Engine Status"
    echo "====================="
    echo ""
    echo "Directories: $HORTORA_DIR"

    if [ -f "$VERSION_FILE" ]; then
        echo ""
        echo "Installed versions:"
        python3 -c "
import json
d = json.load(open('$VERSION_FILE'))
for k, v in d.items():
    print(f'  {k}: {v}')
" 2>/dev/null || echo "  (could not read version.json)"
    else
        echo "  version.json: not found"
    fi

    echo ""
    if curl -sf http://localhost:6333/ >/dev/null 2>&1; then
        local ver
        ver=$(curl -sf http://localhost:6333/ | python3 -c "import json,sys; print(json.load(sys.stdin)['version'])" 2>/dev/null)
        echo "Qdrant: running (v$ver)"
        local points
        points=$(curl -sf http://localhost:6333/collections/hortora_garden \
            | python3 -c "import json,sys; print(json.load(sys.stdin)['result']['points_count'])" 2>/dev/null || echo "?")
        echo "  Collection: hortora_garden ($points points)"
    else
        echo "Qdrant: not running"
    fi

    echo ""
    if curl -sf http://localhost:8080/ >/dev/null 2>&1; then
        echo "Engine: running on port 8080"
    else
        echo "Engine: not running"
    fi

    echo ""
    echo "Models:"
    [ -f "$HORTORA_DIR/models/bge-m3/model.onnx" ] && echo "  BGE-M3: installed" || echo "  BGE-M3: missing"
    [ -f "$HORTORA_DIR/models/reranker/model.onnx" ] && echo "  Reranker: installed" || echo "  Reranker: missing"

    echo ""
    if [ -d "$GARDEN_PATH" ]; then
        local count
        count=$(find "$GARDEN_PATH" -name "GE-*.md" -not -path "*/_summaries/*" 2>/dev/null | wc -l | tr -d ' ')
        echo "Garden: $GARDEN_PATH ($count entries)"
    else
        echo "Garden: NOT FOUND at $GARDEN_PATH"
        echo "  Set HORTORA_GARDEN_PATH or clone the garden to ~/.hortora/garden"
    fi
}

do_uninstall() {
    log "Uninstalling Hortora services..."

    log "  Stopping engine..."
    launchctl bootout "$DOMAIN/$LABEL_ENGINE" 2>/dev/null && log "    Stopped engine" || log "    Engine not running"
    rm -f "$HOME/Library/LaunchAgents/io.hortora.engine.plist"

    log "  Stopping Qdrant..."
    launchctl bootout "$DOMAIN/$LABEL_QDRANT" 2>/dev/null && log "    Stopped Qdrant" || log "    Qdrant not running"
    rm -f "$HOME/Library/LaunchAgents/io.hortora.qdrant.plist"

    log "Services stopped and plists removed."
    log "Data preserved at $HORTORA_DIR — delete manually if needed."
}

# --- Main dispatch ---

case "${1:-help}" in

install)
    check_prerequisites
    create_dirs
    do_install_qdrant
    do_install_models
    do_install_snapshot
    do_install_engine
    log ""
    log "Installation complete. Run '$0 status' to verify."
    ;;

install-qdrant)    check_prerequisites; create_dirs; do_install_qdrant ;;
install-models)    check_prerequisites; create_dirs; do_install_models ;;
install-snapshot)  check_prerequisites; do_install_snapshot ;;
install-engine)    check_prerequisites; do_install_engine ;;
status)            do_status ;;
uninstall)         do_uninstall ;;

help|*)
    echo "Usage: $0 {install|install-qdrant|install-models|install-snapshot|install-engine|status|uninstall}"
    echo ""
    echo "  install           Full first-time setup (all steps in order)"
    echo "  install-qdrant    Download + install native Qdrant binary + launchd service"
    echo "  install-models    Download pre-exported ONNX models from GitHub Release"
    echo "  install-snapshot  Download + restore Qdrant snapshot and cursor"
    echo "  install-engine    Build engine from source + install launchd service"
    echo "  status            Show what's installed, running, and outdated"
    echo "  uninstall         Stop services, remove plists (keeps data)"
    ;;

esac
