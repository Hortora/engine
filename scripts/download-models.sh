#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="${HOME}/.hortora/models"

# BGE-M3 checksums — update after ONNX export is delivered
CHECKSUM_BGE_M3_MODEL="placeholder"
CHECKSUM_BGE_M3_TOKENIZER="placeholder"

verify_checksum() {
    local file="$1" expected="$2"
    if [ "$expected" = "placeholder" ]; then
        echo "  ! checksum verification skipped (placeholder)"
        return 0
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

download_file() {
    local target="$1" repo="$2" path="$3" expected="$4"
    local url="https://huggingface.co/${repo}/resolve/main/${path}"
    local dir
    dir=$(dirname "$target")
    mkdir -p "$dir"

    if [ -f "$target" ]; then
        if verify_checksum "$target" "$expected"; then
            echo "  ✓ verified: $target"
            return 0
        fi
        echo "  ↓ re-downloading (checksum mismatch): $url"
        rm -f "$target"
    else
        echo "  ↓ downloading: $url"
    fi

    curl -fSL --retry 3 -o "${target}.tmp" "$url"
    if ! verify_checksum "${target}.tmp" "$expected"; then
        rm -f "${target}.tmp"
        echo "  ✗ downloaded file failed checksum verification"
        return 1
    fi
    mv "${target}.tmp" "$target"
    echo "  ✓ saved and verified: $target"
}

echo "Hortora ONNX Model Downloader"
echo "=============================="
echo ""
echo "Target: ${MODEL_DIR}"
echo ""

echo "BGE-M3 (dense + sparse + ColBERT embeddings):"
download_file "${MODEL_DIR}/bge-m3/model.onnx" "BAAI/bge-m3" "onnx/model.onnx" "$CHECKSUM_BGE_M3_MODEL"
download_file "${MODEL_DIR}/bge-m3/tokenizer.json" "BAAI/bge-m3" "tokenizer.json" "$CHECKSUM_BGE_M3_TOKENIZER"

echo ""
echo "Downloads complete. Add to application.properties (or %dev profile):"
echo ""
echo "  %dev.casehub.inference.models.bge-m3.model-path=${MODEL_DIR}/bge-m3/model.onnx"
echo "  %dev.casehub.inference.models.bge-m3.tokenizer-path=${MODEL_DIR}/bge-m3/tokenizer.json"
echo "  %dev.casehub.inference.models.bge-m3.maxSequenceLength=8192"
