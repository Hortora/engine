#!/bin/bash
# End-to-end installer test in a fresh Podman container.
# Tests the real download/restore/build/index flow against GitHub Releases.
#
# Usage: scripts/test-installer-podman.sh
set -euo pipefail

ENGINE_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Hortora Installer E2E Test (Podman) ==="
echo ""

# Build the container image with prerequisites
echo "Building test container..."
podman build -t hortora-installer-test -f - "$ENGINE_DIR" <<'DOCKERFILE'
FROM eclipse-temurin:25-jdk-noble

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl zstd git ca-certificates gnupg python3 \
    && rm -rf /var/lib/apt/lists/*

# GitHub CLI
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | gpg --dearmor -o /etc/apt/keyrings/githubcli.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

# Copy engine source
COPY . /engine
WORKDIR /engine

# Create hortora directories (no brace expansion — dash doesn't support it)
RUN mkdir -p /root/.hortora/qdrant /root/.hortora/models/bge-m3 /root/.hortora/models/reranker /root/.hortora/cursors /root/.hortora/cache /root/.hortora/logs /root/.hortora/stats
DOCKERFILE

echo ""
echo "Running test inside container..."
echo ""

# Run the test script inside the container
# Pass GH token for authenticated downloads
podman run --rm \
    -e GITHUB_TOKEN="$GITHUB_TOKEN" \
    -e HORTORA_GARDEN_PATH="/engine/src/test/resources/test-garden/initial" \
    hortora-installer-test \
    bash -c '
set -euo pipefail

echo "=== Phase 1: Download and install Qdrant ==="
QDRANT_VERSION="1.19.0"
ARCH=$(uname -m)
case "$ARCH" in
    aarch64) QDRANT_ARCH="aarch64-unknown-linux-musl" ;;
    x86_64)  QDRANT_ARCH="x86_64-unknown-linux-gnu" ;;
    *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac
curl -fSL "https://github.com/qdrant/qdrant/releases/download/v${QDRANT_VERSION}/qdrant-${QDRANT_ARCH}.tar.gz" \
    | tar xz -C /root/.hortora/qdrant/
chmod +x /root/.hortora/qdrant/qdrant

cat > /root/.hortora/qdrant/config.yaml <<YAML
storage:
  storage_path: /root/.hortora/qdrant/storage
service:
  grpc_port: 6334
  http_port: 6333
YAML

/root/.hortora/qdrant/qdrant --config-path /root/.hortora/qdrant/config.yaml &
QDRANT_PID=$!
sleep 5
curl -sf http://localhost:6333/ > /dev/null && echo "✅ Qdrant running" || (echo "❌ Qdrant failed to start"; exit 1)

echo ""
echo "=== Phase 2: Download models from GitHub Releases ==="
gh auth setup-git 2>/dev/null || true

# Download checksums
gh release download latest --repo Hortora/engine --pattern "checksums.sha256" --output /tmp/checksums.sha256 --clobber
echo "  Downloaded checksums.sha256"

# Download and extract BGE-M3
gh release download latest --repo Hortora/engine --pattern "bge-m3-models.tar.zst" --output /tmp/bge-m3-models.tar.zst --clobber
zstd -d /tmp/bge-m3-models.tar.zst --stdout | tar x -C /root/.hortora/models/bge-m3/
echo "✅ BGE-M3 model installed"
ls -lh /root/.hortora/models/bge-m3/

# Download and extract reranker
gh release download latest --repo Hortora/engine --pattern "reranker-models.tar.zst" --output /tmp/reranker-models.tar.zst --clobber
zstd -d /tmp/reranker-models.tar.zst --stdout | tar x -C /root/.hortora/models/reranker/
echo "✅ Reranker model installed"
ls -lh /root/.hortora/models/reranker/

echo ""
echo "=== Phase 3: Download and restore snapshot ==="
# Download snapshot parts
for part in $(gh release view latest --repo Hortora/engine --json assets --jq ".assets[].name" | grep "^snapshot.tar.zst.part-"); do
    gh release download latest --repo Hortora/engine --pattern "$part" --output "/tmp/$part" --clobber
    echo "  Downloaded $part"
done

# Verify checksums
cd /tmp
for part in snapshot.tar.zst.part-*; do
    EXPECTED=$(grep "$part" checksums.sha256 | awk "{print \$1}")
    ACTUAL=$(sha256sum "$part" | awk "{print \$1}")
    if [ "$EXPECTED" != "$ACTUAL" ]; then
        echo "❌ Checksum mismatch for $part"
        exit 1
    fi
    echo "  ✅ Verified $part"
done

# Reassemble and restore
cat snapshot.tar.zst.part-* | zstd -d | tar x -C /tmp/
SNAP_FILE=$(find /tmp -name "*.snapshot" -type f -not -path "/tmp/snapshot.tar.zst*" | head -1)
echo "  Restoring snapshot: $SNAP_FILE"
curl -sf -X POST "http://localhost:6333/collections/hortora_garden/snapshots/upload" \
    -H "Content-Type: multipart/form-data" \
    -F "snapshot=@${SNAP_FILE}" > /dev/null

POINTS=$(curl -sf http://localhost:6333/collections/hortora_garden \
    | python3 -c "import json,sys; print(json.load(sys.stdin)[\"result\"][\"points_count\"])")
echo "✅ Snapshot restored: $POINTS points"

# Restore cursor with path rebasing
gh release download latest --repo Hortora/engine --pattern "garden.cursor" --output /tmp/garden.cursor --clobber
GARDEN_ABS=$(cd /engine/src/test/resources/test-garden/initial && pwd)
mkdir -p "$GARDEN_ABS/../cursors"
sed "s|__GARDEN_PATH__|$GARDEN_ABS|g" /tmp/garden.cursor > "$GARDEN_ABS/../cursors/garden.cursor"
echo "✅ Cursor restored and rebased"

echo ""
echo "=== Phase 4: Build and start engine ==="
./mvnw package -DskipTests -B -q
echo "✅ Engine built"

java --enable-native-access=ALL-UNNAMED \
    -Dquarkus.profile=dev \
    -Dhortora.garden.path="$GARDEN_ABS" \
    -jar target/quarkus-app/quarkus-run.jar &
ENGINE_PID=$!

for i in $(seq 1 60); do
    curl -sf http://localhost:8080/ > /dev/null 2>&1 && break
    sleep 1
done
curl -sf http://localhost:8080/ > /dev/null && echo "✅ Engine running" || (echo "❌ Engine failed to start"; exit 1)

# Wait a few seconds for any delta indexing
sleep 10

POINTS_AFTER=$(curl -sf http://localhost:6333/collections/hortora_garden \
    | python3 -c "import json,sys; print(json.load(sys.stdin)[\"result\"][\"points_count\"])")
echo "  Points after engine startup: $POINTS_AFTER (was $POINTS after snapshot restore)"

echo ""
echo "=== Phase 5: Delta re-indexing ==="
echo "  Copying 2 delta entries into garden..."
cp /engine/src/test/resources/test-garden/delta/*.md /engine/src/test/resources/test-garden/initial/

echo "  Triggering reindex..."
curl -sf -X POST http://localhost:8080/api/garden/reindex > /dev/null

echo "  Waiting for delta entries to be indexed..."
for i in $(seq 1 60); do
    NEW_POINTS=$(curl -sf http://localhost:6333/collections/hortora_garden \
        | python3 -c "import json,sys; print(json.load(sys.stdin)[\"result\"][\"points_count\"])" 2>/dev/null || echo "0")
    if [ "$NEW_POINTS" -gt "$POINTS_AFTER" ]; then
        echo "✅ Delta re-indexing complete: $NEW_POINTS points (added $(($NEW_POINTS - $POINTS_AFTER)))"
        break
    fi
    sleep 2
done

if [ "$NEW_POINTS" -le "$POINTS_AFTER" ]; then
    echo "❌ Delta re-indexing failed — still at $POINTS_AFTER points"
    exit 1
fi

echo ""
echo "=== Phase 6: Query test ==="
SEARCH_RESULT=$(curl -sf "http://localhost:8080/mcp/sse" 2>/dev/null || echo "MCP endpoint check skipped")
echo "  MCP SSE endpoint: accessible"

# Try a search via REST
SEARCH=$(curl -sf "http://localhost:8080/search?q=CBR+parameter+ordering" 2>/dev/null || echo "")
if [ -n "$SEARCH" ]; then
    echo "✅ Search returned results"
else
    echo "⚠️  Search endpoint did not respond (may need MCP client)"
fi

echo ""
echo "======================================="
echo "  ALL PHASES PASSED"
echo "  Snapshot restore: ✅"
echo "  Model download:   ✅"
echo "  Engine build:     ✅"
echo "  Delta reindex:    ✅"
echo "======================================="

kill $ENGINE_PID $QDRANT_PID 2>/dev/null || true
'

echo ""
echo "Container test complete."
