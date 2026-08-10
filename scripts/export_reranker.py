#!/usr/bin/env python3
"""Export a cross-encoder reranker model to ONNX for the Hortora engine.

Usage:
    pip install -r scripts/requirements-export.txt
    python scripts/export_reranker.py

Produces:
    ~/.hortora/models/reranker/model.onnx      — ONNX graph + weights (~91MB)
    ~/.hortora/models/reranker/tokenizer.json   — BERT tokenizer
"""

import hashlib
import os
import shutil
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

MODEL_NAME = "cross-encoder/ms-marco-MiniLM-L-6-v2"
MODEL_DIR = Path.home() / ".hortora" / "models" / "reranker"
OPSET_VERSION = 18


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_idempotent() -> bool:
    model_path = MODEL_DIR / "model.onnx"
    tokenizer_path = MODEL_DIR / "tokenizer.json"
    return model_path.exists() and tokenizer_path.exists()


def main():
    if check_idempotent():
        print(f"Reranker model already exported at {MODEL_DIR}")
        print(f"  {MODEL_DIR / 'model.onnx'}")
        print(f"  {MODEL_DIR / 'tokenizer.json'}")
        sys.exit(0)

    print(f"Loading {MODEL_NAME} (downloads ~90MB on first run)...")
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model.eval()

    tmp_dir = MODEL_DIR / ".export-tmp"
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=True)

    try:
        onnx_path = str(tmp_dir / "model.onnx")
        tokenizer_tmp = tmp_dir / "tokenizer.json"

        dummy = tokenizer("query", "passage", return_tensors="pt", padding=True)

        print(f"Exporting ONNX (opset {OPSET_VERSION})...")
        torch.onnx.export(
            model,
            (dummy["input_ids"], dummy["attention_mask"], dummy["token_type_ids"]),
            onnx_path,
            opset_version=OPSET_VERSION,
            input_names=["input_ids", "attention_mask", "token_type_ids"],
            output_names=["logits"],
            dynamic_axes={
                "input_ids": {0: "batch_size", 1: "sequence_length"},
                "attention_mask": {0: "batch_size", 1: "sequence_length"},
                "token_type_ids": {0: "batch_size", 1: "sequence_length"},
                "logits": {0: "batch_size"},
            },
        )

        tokenizer.save_pretrained(str(tmp_dir))

        print("Validating ONNX output against PyTorch...")
        session = ort.InferenceSession(onnx_path)

        test_pairs = [
            ("search query", "relevant document passage"),
            ("machine learning", "deep neural networks for classification"),
            ("hello", "world"),
        ]

        for query, passage in test_pairs:
            enc = tokenizer(query, passage, return_tensors="pt", padding=True)
            with torch.no_grad():
                pt_out = model(**enc).logits.numpy()
            onnx_out = session.run(None, {
                "input_ids": enc["input_ids"].numpy(),
                "attention_mask": enc["attention_mask"].numpy(),
                "token_type_ids": enc["token_type_ids"].numpy(),
            })[0]
            if not np.allclose(pt_out, onnx_out, atol=1e-4):
                max_diff = np.max(np.abs(pt_out - onnx_out))
                raise ValueError(f"Validation FAILED for '{query}'/'{passage}': max diff {max_diff}")
            print(f"  ✓ '{query}' / '{passage}'")

        MODEL_DIR.mkdir(parents=True, exist_ok=True)
        final_model = MODEL_DIR / "model.onnx"
        final_tokenizer = MODEL_DIR / "tokenizer.json"
        os.replace(str(tmp_dir / "model.onnx"), str(final_model))
        os.replace(str(tokenizer_tmp), str(final_tokenizer))

        print(f"\nExport complete:")
        print(f"  {final_model} ({final_model.stat().st_size / 1048576:.1f} MB)")
        print(f"  {final_tokenizer}")

    finally:
        if tmp_dir.exists():
            shutil.rmtree(tmp_dir)


if __name__ == "__main__":
    main()
