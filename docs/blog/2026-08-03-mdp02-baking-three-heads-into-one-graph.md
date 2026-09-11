---
layout: post
title: "Baking Three Heads Into One Graph"
date: 2026-08-03
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [bge-m3, onnx, export, sparse, colbert, inference]
---

HuggingFace Optimum exports the transformer backbone. That's it. The three things that make BGE-M3 useful — dense CLS pooling, sparse token-to-vocabulary scatter, and ColBERT per-token projection — are custom Python logic in BAAI's `modeling.py`. They don't survive the standard export. You get `last_hidden_state` and nothing else.

The existing three-head exports on HuggingFace (aapot, philipchung) get most of the way there, but the sparse output is per-token: `[batch, seq_len, 1]`. The Java side expects vocab-indexed weights: `[batch, 250002]` where index position IS the vocabulary token ID. That means the token-to-vocab scatter has to live inside the ONNX graph, not as post-processing in Java.

The scatter is the interesting part. BGE-M3's sparse head applies `relu(linear(hidden_state))` per token, producing a weight for each token position. Multiple tokens can map to the same vocabulary entry — the word "the" appearing three times in a sentence should produce one weight for token ID 70, not three. So you need max-reduction: for each vocab position, keep the highest weight from any token that maps to it.

```python
token_weights = torch.relu(self.sparse_linear(last_hidden_state)).squeeze(-1)
token_weights = token_weights * attention_mask.float()
sparse = torch.zeros(batch_size, self.config.vocab_size,
                     device=token_weights.device, dtype=token_weights.dtype)
sparse = torch.scatter_reduce(sparse, 1, input_ids, token_weights, reduce="amax")
```

`torch.scatter_reduce` with `reduce="amax"` does the max-pooling to vocab indices in one op. The non-in-place variant (not `scatter_reduce_()`) is essential — ONNX tracing can't follow in-place mutations reliably. This requires opset 16 for the `ScatterElements` reduction mode.

The other gotcha is ColBERT and CLS. BAAI's reference implementation computes ColBERT on `last_hidden_state[:, 1:]` — everything except CLS. Makes sense in their Python pipeline where they handle the offset. But `OnnxInferenceModel.runBatch()` strips padding using the attention mask length, which counts CLS. Exclude CLS from ColBERT output and you get `actualLen > output_seq_len`, which pads the array with nulls. Those nulls surface as NPEs during L2 normalization — but only in batch mode, not single-sentence inference, because `run()` doesn't strip padding. I included CLS in the ColBERT output. It's semantically safe — CLS carries holistic meaning — and it eliminates the off-by-one.

The export script validates the final optimized model (post-O2, not pre-optimization) against PyTorch on eight test cases: standard English, short input, near-max-length (~8192 tokens), multilingual, repeated tokens, and a multi-sentence batch. The batch test is non-obvious but critical — transformer attention with padding produces subtly different embeddings at different batch sizes, so validating batch_size=1 alone is insufficient. `allclose(atol=1e-4)` on all three outputs.

The output is a ~2.2GB ONNX model with external data, written atomically via temp directory rename. A verification script checks existence and SHA-256 checksums — it doesn't download anything. The model is produced locally; the checksums are committed to git.

One model, one forward pass, three retrieval signals. The Java infrastructure — `BgeM3Embedder`, `MultiModalEmbedder`, four-leg hybrid search — was already wired and tested with stubs. This script produces the real model that makes it functional.
