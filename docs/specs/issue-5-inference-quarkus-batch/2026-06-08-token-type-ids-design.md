# token_type_ids Support for BERT-based Models

**Issue:** casehubio/neural-text#8
**Status:** Approved
**Scope:** inference-runtime only — no API or SPI changes

## Problem

`OnnxInferenceModel.run()` passes only `input_ids` and `attention_mask` tensors. BERT-family models (original BERT, ALBERT) require a third `token_type_ids` tensor to distinguish sentence segments in pair-encoding tasks. DeBERTa (our primary NLI model) does not use it, so the current code is correct for all immediate use cases — this is forward-looking support.

## Design

**Detection:** At construction time, store `this.requiresTokenTypeIds = session.getInputNames().contains("token_type_ids")`. Checked once, no per-call overhead.

**run():** When `requiresTokenTypeIds` is true, extract `encoding.getTypeIds()`, wrap as `long[1][seqLen]`, create an `OnnxTensor`, and include `"token_type_ids"` in the input map. Close the tensor in the try-with-resources alongside the existing tensors.

**runBatch():** Same pattern. Extract `getTypeIds()` for each encoding, zero-pad to `maxLen` (correct — `token_type_ids=0` means segment A), stack into `long[batchSize][maxLen]`, create tensor, include in input map.

**InMemoryInferenceModel:** No changes — it doesn't deal with tensors.

**InferenceModel SPI, InferenceInput, InferenceOutput:** No changes — this is purely internal to the runtime.

## Testing

- Existing test model (2 inputs, DeBERTa-style) exercises the skip path — no `token_type_ids` in model inputs, so the flag is false and behaviour is unchanged.
- Generate a 3-input test ONNX model (`input_ids`, `attention_mask`, `token_type_ids`) using a Python script with `onnx` library. Model performs a trivial computation so outputs are deterministic and verifiable.
- Tests: single text, text pair, batch — all with the 3-input model.