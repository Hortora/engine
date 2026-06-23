#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="${HOME}/.hortora/models"

download_file() {
    local target="$1" repo="$2" path="$3"
    local url="https://huggingface.co/${repo}/resolve/main/${path}"
    local dir
    dir=$(dirname "$target")
    mkdir -p "$dir"

    if [ -f "$target" ]; then
        echo "  ✓ exists: $target"
        return 0
    fi

    echo "  ↓ downloading: $url"
    curl -fSL --retry 3 -o "${target}.tmp" "$url"
    mv "${target}.tmp" "$target"
    echo "  ✓ saved: $target"
}

echo "Hortora ONNX Model Downloader"
echo "=============================="
echo ""
echo "Target: ${MODEL_DIR}"
echo ""

echo "SPLADE (sparse embeddings):"
download_file "${MODEL_DIR}/splade/model.onnx" "prithivida/Splade_PP_en_v1" "onnx/model.onnx"
download_file "${MODEL_DIR}/splade/tokenizer.json" "prithivida/Splade_PP_en_v1" "tokenizer.json"

echo ""
echo "Cross-encoder reranker:"
download_file "${MODEL_DIR}/reranker/model.onnx" "cross-encoder/ms-marco-MiniLM-L-6-v2" "onnx/model.onnx"
download_file "${MODEL_DIR}/reranker/tokenizer.json" "cross-encoder/ms-marco-MiniLM-L-6-v2" "tokenizer.json"

echo ""
echo "Downloads complete. Add to application.properties (or %dev profile):"
echo ""
echo "  %dev.casehub.inference.models.splade.model-path=${MODEL_DIR}/splade/model.onnx"
echo "  %dev.casehub.inference.models.splade.tokenizer-path=${MODEL_DIR}/splade/tokenizer.json"
echo "  %dev.casehub.inference.models.reranker.model-path=${MODEL_DIR}/reranker/model.onnx"
echo "  %dev.casehub.inference.models.reranker.tokenizer-path=${MODEL_DIR}/reranker/tokenizer.json"
