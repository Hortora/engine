#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="${HOME}/.hortora/models"

CHECKSUM_SPLADE_MODEL="0934583a27a031a66b2e847cbc260fbbef29689e969f500436460ef5146a43f2"
CHECKSUM_SPLADE_TOKENIZER="2fc687b11de0bc1b3d8348f92e3b49ef1089a621506c7661fbf3248fcd54947e"
CHECKSUM_RERANKER_MODEL="5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a"
CHECKSUM_RERANKER_TOKENIZER="d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66"

verify_checksum() {
    local file="$1" expected="$2"
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

echo "SPLADE (sparse embeddings):"
download_file "${MODEL_DIR}/splade/model.onnx" "prithivida/Splade_PP_en_v1" "onnx/model.onnx" "$CHECKSUM_SPLADE_MODEL"
download_file "${MODEL_DIR}/splade/tokenizer.json" "prithivida/Splade_PP_en_v1" "tokenizer.json" "$CHECKSUM_SPLADE_TOKENIZER"

echo ""
echo "Cross-encoder reranker:"
download_file "${MODEL_DIR}/reranker/model.onnx" "cross-encoder/ms-marco-MiniLM-L-6-v2" "onnx/model.onnx" "$CHECKSUM_RERANKER_MODEL"
download_file "${MODEL_DIR}/reranker/tokenizer.json" "cross-encoder/ms-marco-MiniLM-L-6-v2" "tokenizer.json" "$CHECKSUM_RERANKER_TOKENIZER"

echo ""
echo "Downloads complete. Add to application.properties (or %dev profile):"
echo ""
echo "  %dev.casehub.inference.models.splade.model-path=${MODEL_DIR}/splade/model.onnx"
echo "  %dev.casehub.inference.models.splade.tokenizer-path=${MODEL_DIR}/splade/tokenizer.json"
echo "  %dev.casehub.inference.models.reranker.model-path=${MODEL_DIR}/reranker/model.onnx"
echo "  %dev.casehub.inference.models.reranker.tokenizer-path=${MODEL_DIR}/reranker/tokenizer.json"
