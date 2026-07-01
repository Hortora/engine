#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="${HOME}/.hortora/models"
SCRIPT_DIR="$(dirname "$0")"
CHECKSUM_FILE="${SCRIPT_DIR}/bge-m3-checksums.sha256"

verify_checksum() {
    local file="$1" name="$2"
    if [ ! -f "$CHECKSUM_FILE" ]; then
        echo "  ✗ checksum file not found: $CHECKSUM_FILE"
        echo "    Run the export script first, then commit the checksums file."
        return 1
    fi
    local expected
    expected=$(grep "  ${name}$" "$CHECKSUM_FILE" | awk '{print $1}')
    if [ -z "$expected" ]; then
        echo "  ✗ no checksum for ${name} in $CHECKSUM_FILE"
        return 1
    fi
    local actual
    actual=$(shasum -a 256 "$file" | awk '{print $1}')
    if [ "$actual" != "$expected" ]; then
        echo "  ✗ checksum mismatch: $file"
        echo "    expected: $expected"
        echo "    actual:   $actual"
        return 1
    fi
    return 0
}

echo "Hortora ONNX Model Verifier"
echo "============================"
echo ""
echo "Target: ${MODEL_DIR}"
echo ""

MISSING=0

echo "BGE-M3 (dense + sparse + ColBERT embeddings):"

# model.onnx — produced by export script, not downloadable
if [ -f "${MODEL_DIR}/bge-m3/model.onnx" ]; then
    if verify_checksum "${MODEL_DIR}/bge-m3/model.onnx" "model.onnx"; then
        echo "  ✓ verified: ${MODEL_DIR}/bge-m3/model.onnx"
    else
        MISSING=1
    fi
else
    echo "  ✗ not found: ${MODEL_DIR}/bge-m3/model.onnx"
    MISSING=1
fi

# tokenizer.json — produced by export script alongside model.onnx
if [ -f "${MODEL_DIR}/bge-m3/tokenizer.json" ]; then
    if verify_checksum "${MODEL_DIR}/bge-m3/tokenizer.json" "tokenizer.json"; then
        echo "  ✓ verified: ${MODEL_DIR}/bge-m3/tokenizer.json"
    else
        MISSING=1
    fi
else
    echo "  ✗ not found: ${MODEL_DIR}/bge-m3/tokenizer.json"
    MISSING=1
fi

if [ "$MISSING" -eq 1 ]; then
    echo ""
    echo "BGE-M3 model not found or checksum mismatch."
    echo ""
    echo "This model requires a one-time local export (~2-5 min, ~8GB RAM):"
    echo ""
    echo "  pip install -r scripts/requirements-export.txt"
    echo "  python scripts/export_bge_m3.py"
    echo ""
    echo "The export downloads ~2.2GB of PyTorch weights, converts to ONNX,"
    echo "and writes the model to ~/.hortora/models/bge-m3/"
    exit 1
fi

echo ""
echo "All models verified."
echo ""
echo "Add to application.properties (or %dev profile):"
echo ""
echo "  %dev.casehub.inference.models.bge-m3.model-path=${MODEL_DIR}/bge-m3/model.onnx"
echo "  %dev.casehub.inference.models.bge-m3.tokenizer-path=${MODEL_DIR}/bge-m3/tokenizer.json"
echo "  %dev.casehub.inference.models.bge-m3.maxSequenceLength=8192"
