"""Tests for the BGE-M3 ONNX export pipeline.

These tests require the real model and run the full export + validation.
They are slow (~3-5 min) and require ~8GB RAM.
"""
import pytest
import os
import tempfile
from pathlib import Path

try:
    from bgem3_model import BGEM3InferenceModel
    import onnxruntime as ort
    MODEL_AVAILABLE = True
except ImportError:
    MODEL_AVAILABLE = False

slow = pytest.mark.skipif(not MODEL_AVAILABLE, reason="dependencies not available")


@slow
def test_onnx_export_has_correct_output_names():
    from export_bge_m3 import export_onnx, OPSET_VERSION
    model = BGEM3InferenceModel()
    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=OPSET_VERSION)
        session = ort.InferenceSession(onnx_path)
        output_names = [o.name for o in session.get_outputs()]
        assert output_names == ["dense", "sparse", "colbert"]


@slow
def test_onnx_export_has_correct_input_names():
    from export_bge_m3 import export_onnx, OPSET_VERSION
    model = BGEM3InferenceModel()
    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=OPSET_VERSION)
        session = ort.InferenceSession(onnx_path)
        input_names = [i.name for i in session.get_inputs()]
        assert input_names == ["input_ids", "attention_mask"]


@slow
def test_onnx_output_matches_pytorch():
    import torch
    import numpy as np
    from transformers import AutoTokenizer
    from export_bge_m3 import export_onnx, OPSET_VERSION

    model = BGEM3InferenceModel()
    tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-m3")

    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=OPSET_VERSION)
        session = ort.InferenceSession(onnx_path)

        text = "Quarkus CDI bean discovery"
        enc = tokenizer(text, return_tensors="pt", padding=False, truncation=True, max_length=8192)

        # PyTorch
        with torch.no_grad():
            pt_out = model(enc["input_ids"], enc["attention_mask"])

        # ONNX
        onnx_inputs = {
            "input_ids": enc["input_ids"].numpy(),
            "attention_mask": enc["attention_mask"].numpy(),
        }
        onnx_out = session.run(None, onnx_inputs)
        onnx_dense, onnx_sparse, onnx_colbert = onnx_out

        np.testing.assert_allclose(pt_out["dense"].numpy(), onnx_dense, atol=1e-4)
        np.testing.assert_allclose(pt_out["sparse"].numpy(), onnx_sparse, atol=1e-4)
        np.testing.assert_allclose(pt_out["colbert"].numpy(), onnx_colbert, atol=1e-4)


@slow
def test_onnx_batch_output_matches_pytorch():
    import torch
    import numpy as np
    from transformers import AutoTokenizer
    from export_bge_m3 import export_onnx, OPSET_VERSION

    model = BGEM3InferenceModel()
    tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-m3")

    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=OPSET_VERSION)
        session = ort.InferenceSession(onnx_path)

        texts = ["Short text", "A somewhat longer sentence for testing batch behavior"]
        enc = tokenizer(texts, return_tensors="pt", padding=True, truncation=True, max_length=8192)

        # PyTorch
        with torch.no_grad():
            pt_out = model(enc["input_ids"], enc["attention_mask"])

        # ONNX
        onnx_inputs = {
            "input_ids": enc["input_ids"].numpy(),
            "attention_mask": enc["attention_mask"].numpy(),
        }
        onnx_out = session.run(None, onnx_inputs)
        onnx_dense, onnx_sparse, onnx_colbert = onnx_out

        np.testing.assert_allclose(pt_out["dense"].numpy(), onnx_dense, atol=1e-4)
        np.testing.assert_allclose(pt_out["sparse"].numpy(), onnx_sparse, atol=1e-4)
        np.testing.assert_allclose(pt_out["colbert"].numpy(), onnx_colbert, atol=1e-4)


@slow
def test_onnx_scatter_handles_repeated_tokens():
    """Verify scatter_reduce amax works correctly in ONNX for duplicate input_ids."""
    import torch
    import numpy as np
    from transformers import AutoTokenizer
    from export_bge_m3 import export_onnx, OPSET_VERSION

    model = BGEM3InferenceModel()
    tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-m3")

    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=OPSET_VERSION)
        session = ort.InferenceSession(onnx_path)

        text = "test test test test"
        enc = tokenizer(text, return_tensors="pt", padding=False, truncation=True, max_length=8192)

        # PyTorch
        with torch.no_grad():
            pt_out = model(enc["input_ids"], enc["attention_mask"])

        # ONNX
        onnx_inputs = {
            "input_ids": enc["input_ids"].numpy(),
            "attention_mask": enc["attention_mask"].numpy(),
        }
        onnx_out = session.run(None, onnx_inputs)
        onnx_sparse = onnx_out[1]

        np.testing.assert_allclose(pt_out["sparse"].numpy(), onnx_sparse, atol=1e-4)


def test_check_idempotent_fails_without_data_file():
    """check_idempotent must return False when model.onnx.data is missing."""
    # This test doesn't need the model — it tests filesystem check logic
    import sys
    import hashlib
    from types import ModuleType
    from unittest.mock import patch

    # Create minimal mock modules
    torch_mock = ModuleType('torch')
    transformers_mock = ModuleType('transformers')
    transformers_mock.AutoTokenizer = type('AutoTokenizer', (), {})
    numpy_mock = ModuleType('numpy')
    ort_mock = ModuleType('onnxruntime')
    bgem3_mock = ModuleType('bgem3_model')
    bgem3_mock.BGEM3InferenceModel = type('BGEM3InferenceModel', (), {})

    with patch.dict('sys.modules', {
        'torch': torch_mock,
        'transformers': transformers_mock,
        'numpy': numpy_mock,
        'onnxruntime': ort_mock,
        'bgem3_model': bgem3_mock,
    }):
        from export_bge_m3 import check_idempotent, MODEL_DIR, CHECKSUM_FILE

        original_model_dir = MODEL_DIR
        original_checksum_file = CHECKSUM_FILE

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            # Create model.onnx and tokenizer.json but NOT model.onnx.data
            (tmp_path / "model.onnx").write_bytes(b"fake-onnx-graph")
            (tmp_path / "tokenizer.json").write_bytes(b"fake-tokenizer")

            checksum_path = tmp_path / "checksums.sha256"
            # Write checksums matching the fake files
            model_hash = hashlib.sha256(b"fake-onnx-graph").hexdigest()
            tokenizer_hash = hashlib.sha256(b"fake-tokenizer").hexdigest()
            checksum_path.write_text(
                f"{model_hash}  model.onnx\n"
                f"fakehash  model.onnx.data\n"
                f"{tokenizer_hash}  tokenizer.json\n"
            )

            # Monkey-patch module globals
            import export_bge_m3
            export_bge_m3.MODEL_DIR = tmp_path
            export_bge_m3.CHECKSUM_FILE = checksum_path
            try:
                assert check_idempotent() is False
            finally:
                export_bge_m3.MODEL_DIR = original_model_dir
                export_bge_m3.CHECKSUM_FILE = original_checksum_file
