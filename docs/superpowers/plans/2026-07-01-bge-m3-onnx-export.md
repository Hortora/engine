# BGE-M3 Three-Head ONNX Export Script — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Python export script that produces a single ONNX model with three named output heads (dense, sparse, ColBERT) so the engine's BGE-M3 pipeline becomes functional with real embeddings.

**Architecture:** Adapt aapot/bge-m3-onnx's proven PyTorch wrapper with three modifications (sparse scatter baked into graph, output names renamed, CLS included in ColBERT). Export via `torch.onnx.export` directly, O2 optimize with `onnxruntime.transformers.optimizer`, validate against PyTorch, and atomically write to `~/.hortora/models/bge-m3/`.

**Tech Stack:** Python 3.10+, PyTorch ≥2.2, Transformers ≥4.38, ONNX ≥1.14, ONNX Runtime ≥1.17, HuggingFace Hub ≥0.20

## Global Constraints

- Export uses `torch.onnx.export` directly — NOT Optimum's `onnx_export_from_model`
- O2 optimization via `onnxruntime.transformers.optimizer.optimize_model()` as a post-export step
- ONNX opset 16 (minimum for `ScatterElements` with max reduction); escalate to 18 if export fails
- Non-in-place `torch.scatter_reduce()` with `include_self=True` (default) for reliable ONNX tracing
- Output names: `dense`, `sparse`, `colbert` (must match `BgeM3Embedder` Java expectations)
- Input names: `input_ids`, `attention_mask` (must match `OnnxInferenceModel` hard validation)
- Vocab size derived from `self.config.vocab_size` (250002 for XLM-RoBERTa), not hardcoded
- ColBERT includes CLS token (required for `OnnxInferenceModel.runBatch()` padding stripping)
- Atomic write: export to temp directory, rename on success
- Validation runs against the **post-O2** model, not the intermediate export
- Minimum 8GB RAM (16GB recommended); peak ~6–8GB during O2 optimization
- `download-models.sh` downloads neither `model.onnx` nor `tokenizer.json` — the export script is the sole source of both

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `scripts/bgem3_model.py` | Create | PyTorch `nn.Module` wrapper — three-head forward with sparse scatter |
| `scripts/export_bge_m3.py` | Create | Export entrypoint — download, export, optimize, validate, atomic write |
| `scripts/requirements-export.txt` | Create | Pinned Python dependencies for export |
| `scripts/test_bgem3_model.py` | Create | Unit tests for the PyTorch wrapper (pre-ONNX) |
| `scripts/test_export_bge_m3.py` | Create | Integration tests for the export pipeline |
| `scripts/download-models.sh` | Modify | Replace download calls with existence + checksum verification |

---

### Task 1: PyTorch Model Wrapper

The core deliverable — the `nn.Module` that produces all three outputs with correct names, shapes, and scatter logic.

**Files:**
- Create: `scripts/bgem3_model.py`
- Create: `scripts/test_bgem3_model.py`

**Interfaces:**
- Consumes: BAAI/bge-m3 weights via `huggingface_hub.snapshot_download`
- Produces: `BGEM3InferenceModel` class with `forward(input_ids, attention_mask) -> Dict[str, Tensor]` returning `{"dense": [batch, 1024], "sparse": [batch, vocab_size], "colbert": [batch, seq_len, 1024]}`

- [ ] **Step 1: Create `requirements-export.txt`**

```
torch>=2.2,<2.5
transformers>=4.38,<5.0
onnx>=1.14
onnxruntime>=1.17,<2.0
huggingface_hub>=0.20
```

Write to `scripts/requirements-export.txt`.

- [ ] **Step 2: Write failing test — dense output shape and normalization**

Create `scripts/test_bgem3_model.py`. These tests require the real BAAI/bge-m3 model weights (~2.2GB download on first run). Mark with `@pytest.mark.slow` and skip if weights are unavailable.

```python
"""Tests for the BGE-M3 three-head ONNX wrapper.

These tests load the real BAAI/bge-m3 model and validate output shapes,
normalization, and scatter behavior. Slow — requires ~2.2GB model download.
"""
import pytest
import torch
import numpy as np

try:
    from bgem3_model import BGEM3InferenceModel
    MODEL_AVAILABLE = True
except ImportError:
    MODEL_AVAILABLE = False

slow = pytest.mark.skipif(not MODEL_AVAILABLE, reason="bgem3_model not importable")


@pytest.fixture(scope="module")
def model():
    return BGEM3InferenceModel()


@pytest.fixture(scope="module")
def tokenizer():
    from transformers import AutoTokenizer
    return AutoTokenizer.from_pretrained("BAAI/bge-m3")


def _tokenize(tokenizer, text):
    enc = tokenizer(text, return_tensors="pt", padding=False, truncation=True, max_length=8192)
    return enc["input_ids"], enc["attention_mask"]


@slow
def test_dense_shape_is_1024(model, tokenizer):
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    assert output["dense"].shape == (1, 1024)


@slow
def test_dense_is_l2_normalized(model, tokenizer):
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    norm = torch.linalg.norm(output["dense"], dim=-1)
    assert torch.allclose(norm, torch.ones_like(norm), atol=1e-5)
```

Run: `cd scripts && python -m pytest test_bgem3_model.py::test_dense_shape_is_1024 -v`
Expected: FAIL — `bgem3_model` module not found.

- [ ] **Step 3: Write failing test — sparse output shape and scatter behavior**

Append to `scripts/test_bgem3_model.py`:

```python
@slow
def test_sparse_shape_is_vocab_size(model, tokenizer):
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    assert output["sparse"].shape == (1, model.config.vocab_size)


@slow
def test_sparse_values_are_non_negative(model, tokenizer):
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    assert (output["sparse"] >= 0).all()


@slow
def test_sparse_is_actually_sparse(model, tokenizer):
    """Most vocab entries should be zero — only tokens present in input get weights."""
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    nonzero_count = (output["sparse"] > 0).sum().item()
    total = output["sparse"].shape[1]
    assert nonzero_count < total * 0.01, f"Too many nonzero entries: {nonzero_count}/{total}"


@slow
def test_sparse_scatter_max_pools_duplicate_tokens(model, tokenizer):
    """Repeated tokens should produce max-pooled weights, not summed."""
    input_ids, attention_mask = _tokenize(tokenizer, "test test test test")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    # The "test" token should have a weight, and it should be the max across positions,
    # not the sum. Since all positions have the same token, max == any individual weight.
    # Key check: weight should be reasonable (< 10), not accumulated (would be ~4x higher).
    test_token_id = tokenizer.encode("test", add_special_tokens=False)[0]
    weight = output["sparse"][0, test_token_id].item()
    assert 0 < weight < 10, f"Weight {weight} looks accumulated, not max-pooled"
```

- [ ] **Step 4: Write failing test — ColBERT output shape and normalization**

Append to `scripts/test_bgem3_model.py`:

```python
@slow
def test_colbert_shape_matches_sequence_length(model, tokenizer):
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    seq_len = input_ids.shape[1]
    assert output["colbert"].shape == (1, seq_len, 1024)


@slow
def test_colbert_includes_cls_token(model, tokenizer):
    """ColBERT must include CLS (position 0) for OnnxInferenceModel.runBatch() compatibility."""
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    # CLS is at position 0; it should have a non-zero vector
    cls_vector = output["colbert"][0, 0]
    assert cls_vector.abs().sum() > 0, "CLS vector is all zeros — CLS was excluded"


@slow
def test_colbert_rows_are_l2_normalized(model, tokenizer):
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    norms = torch.linalg.norm(output["colbert"][0], dim=-1)
    # Only check non-padding positions (all should be non-padding for this short input)
    real_positions = attention_mask[0].sum().item()
    real_norms = norms[:real_positions]
    assert torch.allclose(real_norms, torch.ones_like(real_norms), atol=1e-5)


@slow
def test_colbert_padding_positions_are_zeroed(model, tokenizer):
    """When batching, padding positions should have zero vectors."""
    short = tokenizer("Hi", return_tensors="pt", padding=False, truncation=True)
    long = tokenizer("This is a longer sentence for testing", return_tensors="pt",
                     padding=False, truncation=True)
    # Manually batch with padding
    max_len = max(short["input_ids"].shape[1], long["input_ids"].shape[1])
    input_ids = torch.zeros(2, max_len, dtype=torch.long)
    attention_mask = torch.zeros(2, max_len, dtype=torch.long)
    for i, enc in enumerate([short, long]):
        seq_len = enc["input_ids"].shape[1]
        input_ids[i, :seq_len] = enc["input_ids"]
        attention_mask[i, :seq_len] = enc["attention_mask"]

    with torch.no_grad():
        output = model(input_ids, attention_mask)
    # Short sentence's padding positions should be zero
    short_len = short["input_ids"].shape[1]
    if short_len < max_len:
        padding_vectors = output["colbert"][0, short_len:]
        assert (padding_vectors == 0).all(), "Padding positions are not zeroed"
```

- [ ] **Step 5: Write failing test — output key names**

Append to `scripts/test_bgem3_model.py`:

```python
@slow
def test_output_keys_match_java_expectations(model, tokenizer):
    """Output names must be 'dense', 'sparse', 'colbert' — not 'dense_vecs' etc."""
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    assert set(output.keys()) == {"dense", "sparse", "colbert"}
```

- [ ] **Step 6: Implement `bgem3_model.py`**

Create `scripts/bgem3_model.py`:

```python
"""BGE-M3 three-head PyTorch wrapper for ONNX export.

Adapted from https://huggingface.co/aapot/bge-m3-onnx/blob/main/bgem3_model.py
with three modifications:
  1. Sparse scatter baked into forward() — produces [batch, vocab_size]
  2. Output names: 'dense', 'sparse', 'colbert' (matching BgeM3Embedder.java)
  3. ColBERT includes CLS token (required for OnnxInferenceModel.runBatch())
"""

import os
import torch
from torch import nn, Tensor
from transformers import AutoModel, AutoConfig
from huggingface_hub import snapshot_download
from typing import Dict


class BGEM3InferenceModel(nn.Module):

    def __init__(self, model_name: str = "BAAI/bge-m3", colbert_dim: int = -1):
        super().__init__()
        model_name = snapshot_download(
            repo_id=model_name,
            allow_patterns=[
                "model.safetensors",
                "colbert_linear.pt",
                "sparse_linear.pt",
                "config.json",
                "tokenizer.json",
                "tokenizer_config.json",
                "sentencepiece.bpe.model",
                "special_tokens_map.json",
            ],
        )
        self.config = AutoConfig.from_pretrained(model_name)
        self.model = AutoModel.from_pretrained(model_name)
        self.colbert_linear = nn.Linear(
            in_features=self.model.config.hidden_size,
            out_features=(
                self.model.config.hidden_size if colbert_dim == -1 else colbert_dim
            ),
        )
        self.sparse_linear = nn.Linear(
            in_features=self.model.config.hidden_size, out_features=1
        )
        colbert_state_dict = torch.load(
            os.path.join(model_name, "colbert_linear.pt"),
            map_location="cpu", weights_only=True,
        )
        sparse_state_dict = torch.load(
            os.path.join(model_name, "sparse_linear.pt"),
            map_location="cpu", weights_only=True,
        )
        self.colbert_linear.load_state_dict(colbert_state_dict)
        self.sparse_linear.load_state_dict(sparse_state_dict)
        self._model_dir = model_name

    @property
    def tokenizer_path(self) -> str:
        return os.path.join(self._model_dir, "tokenizer.json")

    def forward(self, input_ids: Tensor, attention_mask: Tensor) -> Dict[str, Tensor]:
        last_hidden_state = self.model(
            input_ids=input_ids, attention_mask=attention_mask, return_dict=True
        ).last_hidden_state

        # Dense: CLS pooling + L2 normalize
        dense = torch.nn.functional.normalize(last_hidden_state[:, 0], dim=-1)

        # Sparse: per-token weight -> ReLU -> scatter to vocab with max reduction
        token_weights = torch.relu(self.sparse_linear(last_hidden_state)).squeeze(-1)
        sparse = torch.zeros(
            input_ids.shape[0], self.config.vocab_size,
            device=token_weights.device, dtype=token_weights.dtype,
        )
        sparse = torch.scatter_reduce(sparse, 1, input_ids, token_weights, reduce="amax")

        # ColBERT: all tokens (including CLS) -> linear -> mask padding -> L2 normalize
        colbert = self.colbert_linear(last_hidden_state)
        colbert = colbert * attention_mask[:, :, None].float()
        colbert = torch.nn.functional.normalize(colbert, dim=-1)
        colbert = colbert * attention_mask[:, :, None].float()

        return {"dense": dense, "sparse": sparse, "colbert": colbert}
```

Key differences from aapot's wrapper:
- `sparse_embedding()` replaced with inline scatter: `torch.scatter_reduce(zeros, 1, input_ids, token_weights, reduce="amax")`
- `colbert_embedding()` uses `last_hidden_state` (all tokens), not `last_hidden_state[:, 1:]`
- Output keys are `dense`, `sparse`, `colbert` (not `dense_vecs` etc.)
- `torch.no_grad()` blocks removed — ONNX tracing needs gradient graph traversal
- `tokenizer.json` included in `snapshot_download` patterns for later copy
- ColBERT: normalize then re-zero padding (normalize can turn zero vectors into NaN → re-mask)

- [ ] **Step 7: Run all tests**

Run: `cd scripts && python -m pytest test_bgem3_model.py -v --tb=short`
Expected: All tests PASS. If `scatter_reduce` fails, check PyTorch version (≥2.2 required).

- [ ] **Step 8: Commit**

```bash
git add scripts/bgem3_model.py scripts/test_bgem3_model.py scripts/requirements-export.txt
git commit -m "feat: BGE-M3 three-head PyTorch wrapper with sparse scatter  Refs #35"
```

---

### Task 2: Export Script

The entrypoint that downloads weights, exports to ONNX, applies O2 optimization, validates, and atomically writes the final artifacts.

**Files:**
- Create: `scripts/export_bge_m3.py`
- Create: `scripts/test_export_bge_m3.py`

**Interfaces:**
- Consumes: `BGEM3InferenceModel` from `bgem3_model.py`
- Produces: `~/.hortora/models/bge-m3/model.onnx`, `~/.hortora/models/bge-m3/tokenizer.json`, `scripts/bge-m3-checksums.sha256`

- [ ] **Step 1: Write failing test — ONNX export produces correct output names**

Create `scripts/test_export_bge_m3.py`:

```python
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
    from export_bge_m3 import export_onnx
    model = BGEM3InferenceModel()
    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=16)
        session = ort.InferenceSession(onnx_path)
        output_names = [o.name for o in session.get_outputs()]
        assert output_names == ["dense", "sparse", "colbert"]


@slow
def test_onnx_export_has_correct_input_names():
    from export_bge_m3 import export_onnx
    model = BGEM3InferenceModel()
    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=16)
        session = ort.InferenceSession(onnx_path)
        input_names = [i.name for i in session.get_inputs()]
        assert input_names == ["input_ids", "attention_mask"]
```

- [ ] **Step 2: Write failing test — ONNX output matches PyTorch output**

Append to `scripts/test_export_bge_m3.py`:

```python
@slow
def test_onnx_output_matches_pytorch():
    import torch
    import numpy as np
    from transformers import AutoTokenizer
    from export_bge_m3 import export_onnx

    model = BGEM3InferenceModel()
    tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-m3")

    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=16)
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
    from export_bge_m3 import export_onnx

    model = BGEM3InferenceModel()
    tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-m3")

    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=16)
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
```

- [ ] **Step 3: Write failing test — repeated-token scatter**

Append to `scripts/test_export_bge_m3.py`:

```python
@slow
def test_onnx_scatter_handles_repeated_tokens():
    """Verify scatter_reduce amax works correctly in ONNX for duplicate input_ids."""
    import torch
    import numpy as np
    from transformers import AutoTokenizer
    from export_bge_m3 import export_onnx

    model = BGEM3InferenceModel()
    tokenizer = AutoTokenizer.from_pretrained("BAAI/bge-m3")

    with tempfile.TemporaryDirectory() as tmpdir:
        onnx_path = os.path.join(tmpdir, "model.onnx")
        export_onnx(model, onnx_path, opset_version=16)
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
```

- [ ] **Step 4: Implement `export_bge_m3.py`**

Create `scripts/export_bge_m3.py`:

```python
#!/usr/bin/env python3
"""Export BGE-M3 to a three-head ONNX model for the Hortora engine.

Usage:
    pip install -r scripts/requirements-export.txt
    python scripts/export_bge_m3.py

Produces:
    ~/.hortora/models/bge-m3/model.onnx     — three-head ONNX (~2.2GB)
    ~/.hortora/models/bge-m3/tokenizer.json  — XLM-RoBERTa tokenizer
    scripts/bge-m3-checksums.sha256          — SHA-256 checksums for both
"""

import hashlib
import os
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoTokenizer

from bgem3_model import BGEM3InferenceModel


MODEL_DIR = Path.home() / ".hortora" / "models" / "bge-m3"
CHECKSUM_FILE = Path(__file__).parent / "bge-m3-checksums.sha256"
OPSET_VERSION = 16


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


def optimize_onnx(input_path: str, output_path: str):
    """Apply O2 optimization (transformer fusions) via ONNX Runtime."""
    from onnxruntime.transformers.optimizer import optimize_model
    optimized = optimize_model(input_path, optimization_level=2)
    optimized.save_model_to_file(output_path)


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
        raw_onnx = str(tmp_dir / "model_raw.onnx")
        optimized_onnx = str(tmp_dir / "model.onnx")
        tokenizer_tmp = tmp_dir / "tokenizer.json"

        # Export
        print(f"Exporting ONNX (opset {OPSET_VERSION})...")
        try:
            export_onnx(model, raw_onnx, opset_version=OPSET_VERSION)
        except Exception as e:
            if OPSET_VERSION < 18:
                print(f"Export failed on opset {OPSET_VERSION}: {e}")
                print("Retrying with opset 18...")
                export_onnx(model, raw_onnx, opset_version=18)
            else:
                raise

        # O2 optimization
        print("Applying O2 optimization (transformer fusions)...")
        optimize_onnx(raw_onnx, optimized_onnx)
        os.remove(raw_onnx)

        # Copy tokenizer from download cache
        shutil.copy2(model.tokenizer_path, tokenizer_tmp)

        # Validate against post-O2 model
        print("Validating ONNX output against PyTorch...")
        validate(model, optimized_onnx)

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
```

- [ ] **Step 5: Run export tests**

Run: `cd scripts && python -m pytest test_export_bge_m3.py -v --tb=short`
Expected: All tests PASS. These are slow (~3-5 minutes each).

- [ ] **Step 6: Run the actual export**

Run: `cd scripts && python export_bge_m3.py`
Expected: Model exported to `~/.hortora/models/bge-m3/model.onnx`, checksums written to `scripts/bge-m3-checksums.sha256`.

- [ ] **Step 7: Commit**

```bash
git add scripts/export_bge_m3.py scripts/test_export_bge_m3.py scripts/bge-m3-checksums.sha256
git commit -m "feat: BGE-M3 ONNX export script with validation  Refs #35"
```

---

### Task 3: Update download-models.sh

Replace the broken download calls with existence + checksum verification.

**Files:**
- Modify: `scripts/download-models.sh`

**Interfaces:**
- Consumes: `scripts/bge-m3-checksums.sha256` (produced by Task 2)
- Produces: Exit 0 if model files present and valid, exit 1 with instructions if missing

- [ ] **Step 1: Write the updated download-models.sh**

The current script downloads `BAAI/bge-m3/onnx/model.onnx` (backbone-only, wrong model) and `tokenizer.json` from HuggingFace. Replace both download calls with checksum verification against the committed checksums file.

Replace the entire content of `scripts/download-models.sh` with:

```bash
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
```

- [ ] **Step 2: Test the script manually**

Run: `bash scripts/download-models.sh`
Expected: If model was exported in Task 2 Step 6, both files verified. Otherwise, prints export instructions and exits 1.

- [ ] **Step 3: Commit**

```bash
git add scripts/download-models.sh
git commit -m "feat: download-models.sh verifies exported ONNX instead of downloading  Refs #35"
```

---

### Task 4: Update application.properties Comment

Minor — update the comment to reference the export script instead of `download-models.sh`.

**Files:**
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Update the comment**

Change line 10 from:
```properties
# BGE-M3 ONNX model (dev mode — run scripts/download-models.sh first)
```
to:
```properties
# BGE-M3 ONNX model (dev mode — run scripts/export_bge_m3.py first, see scripts/download-models.sh)
```

- [ ] **Step 2: Verify build still passes**

Run: `./mvnw verify -pl .`
Expected: BUILD SUCCESS — no functional change, just a comment.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "docs: update model config comment to reference export script  Refs #35"
```
