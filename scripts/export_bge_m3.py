#!/usr/bin/env python3
"""Export BGE-M3 to a three-head ONNX model for the Hortora engine.

Usage:
    pip install -r scripts/requirements-export.txt
    python scripts/export_bge_m3.py

Produces:
    ~/.hortora/models/bge-m3/model.onnx     — three-head ONNX (~2.2GB)
    ~/.hortora/models/bge-m3/tokenizer.json  — XLM-RoBERTa tokenizer
    scripts/bge-m3-checksums.sha256          — SHA-256 checksums for both

Note: O2 optimization via onnxruntime.transformers.optimizer is not used —
it fails on this model size with protobuf serialization errors.
"""

import hashlib
import os
import shutil
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoTokenizer

from bgem3_model import BGEM3InferenceModel


MODEL_DIR = Path.home() / ".hortora" / "models" / "bge-m3"
CHECKSUM_FILE = Path(__file__).parent / "bge-m3-checksums.sha256"
OPSET_VERSION = 18


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_idempotent() -> bool:
    """Return True if model already exported and checksums match."""
    model_path = MODEL_DIR / "model.onnx"
    tokenizer_path = MODEL_DIR / "tokenizer.json"
    if not model_path.exists() or not tokenizer_path.exists():
        return False
    if not CHECKSUM_FILE.exists():
        return False
    expected = {}
    for line in CHECKSUM_FILE.read_text().splitlines():
        parts = line.strip().split("  ", 1)
        if len(parts) == 2:
            expected[parts[1]] = parts[0]
    if "model.onnx" not in expected or "tokenizer.json" not in expected:
        return False
    if sha256(model_path) != expected["model.onnx"]:
        return False
    if sha256(tokenizer_path) != expected["tokenizer.json"]:
        return False
    return True


def export_onnx(model: BGEM3InferenceModel, output_path: str, opset_version: int = OPSET_VERSION):
    """Export the model to ONNX format."""
    dummy_input = {
        "input_ids": torch.randint(0, model.config.vocab_size, (1, 32)),
        "attention_mask": torch.ones(1, 32, dtype=torch.long),
    }
    torch.onnx.export(
        model,
        (dummy_input["input_ids"], dummy_input["attention_mask"]),
        output_path,
        opset_version=opset_version,
        input_names=["input_ids", "attention_mask"],
        output_names=["dense", "sparse", "colbert"],
        dynamic_axes={
            "input_ids":      {0: "batch_size", 1: "sequence"},
            "attention_mask": {0: "batch_size", 1: "sequence"},
            "dense":          {0: "batch_size"},
            "sparse":         {0: "batch_size"},
            "colbert":        {0: "batch_size", 1: "sequence"},
        },
    )


def validate(model: BGEM3InferenceModel, onnx_path: str):
    """Validate ONNX output matches PyTorch for edge-case inputs."""
    tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-m3")
    session = ort.InferenceSession(onnx_path)

    test_cases = [
        "Hello world",
        "Quarkus CDI bean discovery with ArC container",
        "test test test test",
        "Hi",
        "BGE-M3は多言語対応の埋め込みモデルです",
        "نموذج متعدد اللغات",
    ]

    print(f"Validating {len(test_cases)} individual sentences...")
    for text in test_cases:
        enc = tokenizer(text, return_tensors="pt", padding=False, truncation=True, max_length=8192)

        with torch.no_grad():
            pt_out = model(enc["input_ids"], enc["attention_mask"])

        onnx_inputs = {
            "input_ids": enc["input_ids"].numpy(),
            "attention_mask": enc["attention_mask"].numpy(),
        }
        onnx_out = session.run(None, onnx_inputs)

        for i, name in enumerate(["dense", "sparse", "colbert"]):
            pt_val = pt_out[name].numpy()
            onnx_val = onnx_out[i]
            if not np.allclose(pt_val, onnx_val, atol=1e-4):
                max_diff = np.max(np.abs(pt_val - onnx_val))
                raise ValueError(
                    f"Validation FAILED for '{text}' output '{name}': max diff {max_diff}"
                )
        print(f"  ✓ '{text[:40]}...' " if len(text) > 40 else f"  ✓ '{text}'")

    # Batch validation
    batch_texts = ["Short text", "A somewhat longer sentence for testing batch behavior"]
    print(f"Validating batch ({len(batch_texts)} sentences)...")
    enc = tokenizer(batch_texts, return_tensors="pt", padding=True, truncation=True, max_length=8192)
    with torch.no_grad():
        pt_out = model(enc["input_ids"], enc["attention_mask"])
    onnx_inputs = {
        "input_ids": enc["input_ids"].numpy(),
        "attention_mask": enc["attention_mask"].numpy(),
    }
    onnx_out = session.run(None, onnx_inputs)
    for i, name in enumerate(["dense", "sparse", "colbert"]):
        pt_val = pt_out[name].numpy()
        onnx_val = onnx_out[i]
        if not np.allclose(pt_val, onnx_val, atol=1e-4):
            max_diff = np.max(np.abs(pt_val - onnx_val))
            raise ValueError(
                f"Batch validation FAILED for output '{name}': max diff {max_diff}"
            )
    print(f"  ✓ batch ({len(batch_texts)} sentences)")

    # Print shapes for confirmation
    enc_single = tokenizer("Hello world", return_tensors="pt", padding=False, truncation=True)
    onnx_out = session.run(None, {
        "input_ids": enc_single["input_ids"].numpy(),
        "attention_mask": enc_single["attention_mask"].numpy(),
    })
    print(f"\nOutput shapes:")
    for i, name in enumerate(["dense", "sparse", "colbert"]):
        print(f"  {name}: {onnx_out[i].shape}")


def write_checksums(model_path: Path, tokenizer_path: Path):
    """Write SHA-256 checksums to scripts/bge-m3-checksums.sha256."""
    model_hash = sha256(model_path)
    tokenizer_hash = sha256(tokenizer_path)
    content = f"{model_hash}  model.onnx\n{tokenizer_hash}  tokenizer.json\n"
    CHECKSUM_FILE.write_text(content)
    print(f"\nChecksums written to {CHECKSUM_FILE}")
    print(f"  model.onnx:     {model_hash}")
    print(f"  tokenizer.json: {tokenizer_hash}")


def main():
    # Idempotency check
    if check_idempotent():
        print("Model already exported and verified — checksums match.")
        print(f"  {MODEL_DIR / 'model.onnx'}")
        print(f"  {MODEL_DIR / 'tokenizer.json'}")
        sys.exit(0)

    print("Loading BGE-M3 model (this downloads ~2.2GB on first run)...")
    model = BGEM3InferenceModel()
    model.eval()

    # Atomic export: work in temp dir, rename on success
    tmp_dir = MODEL_DIR / ".export-tmp"
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=True)

    try:
        onnx_path = str(tmp_dir / "model.onnx")
        tokenizer_tmp = tmp_dir / "tokenizer.json"

        # Export
        print(f"Exporting ONNX (opset {OPSET_VERSION})...")
        export_onnx(model, onnx_path, opset_version=OPSET_VERSION)

        # Copy tokenizer from download cache
        shutil.copy2(model.tokenizer_path, tokenizer_tmp)

        # Validate ONNX output
        print("Validating ONNX output against PyTorch...")
        validate(model, onnx_path)

        # Atomic rename
        final_model = MODEL_DIR / "model.onnx"
        final_tokenizer = MODEL_DIR / "tokenizer.json"
        if final_model.exists():
            os.remove(final_model)
        if final_tokenizer.exists():
            os.remove(final_tokenizer)
        shutil.move(str(tmp_dir / "model.onnx"), str(final_model))
        shutil.move(str(tokenizer_tmp), str(final_tokenizer))

        # Write checksums
        write_checksums(final_model, final_tokenizer)

        print(f"\nExport complete:")
        print(f"  {final_model}")
        print(f"  {final_tokenizer}")
        print(f"\nCommit scripts/bge-m3-checksums.sha256 to update download-models.sh verification.")

    finally:
        if tmp_dir.exists():
            shutil.rmtree(tmp_dir)


if __name__ == "__main__":
    main()
