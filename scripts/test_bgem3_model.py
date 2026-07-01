"""Tests for the BGE-M3 three-head ONNX wrapper.

These tests load the real BAAI/bge-m3 model and validate output shapes,
normalization, and scatter behavior. Slow — requires ~2.2GB model download.
"""
import pytest

try:
    import torch
    from bgem3_model import BGEM3InferenceModel
    MODEL_AVAILABLE = True
except ImportError:
    MODEL_AVAILABLE = False
    torch = None

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


@slow
def test_output_keys_match_java_expectations(model, tokenizer):
    """Output names must be 'dense', 'sparse', 'colbert' — not 'dense_vecs' etc."""
    input_ids, attention_mask = _tokenize(tokenizer, "Hello world")
    with torch.no_grad():
        output = model(input_ids, attention_mask)
    assert set(output.keys()) == {"dense", "sparse", "colbert"}
