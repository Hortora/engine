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
