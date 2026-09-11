# Inference SPI Foundation — Design Spec

**Date:** 2026-06-07
**Status:** Approved (rev 4 — final)
**Issue:** casehubio/neural-text#3
**Modules:** inference-api, inference-runtime, inference-inmem
**Consumers:** casehub-engine (#154), casehub-openclaw, casehub-eidos, casehub-rag, Hortora
**Authoritative prior spec:** Hortora/spec `docs/superpowers/specs/2026-06-03-onnx-inference-module-design.md`

---

## Departures from Prior Spec

The Hortora spec (2026-06-03) defined the initial SPI shape. This spec supersedes it for the following decisions:

| Prior spec | This spec | Rationale |
|---|---|---|
| `ModelConfig` in inference-api | `ModelConfig` in inference-runtime | Config is a construction concern, not an SPI concern. inference-inmem has no use for model paths. |
| `InferenceOutput.tensor(String name)` | `InferenceOutput(float[] values)` record | All target models produce one output tensor. Tensor names are ONNX internals that don't belong in the SPI. See §Multi-Output Escape Hatch for the upgrade path. |
| No error handling specified | `InferenceException` unchecked exception | SPI must not leak `OrtException` (checked). Runtime wraps all JNI/IO errors. |
| No output size validation | `InferenceModel.outputSize()` returning `OptionalInt` | Five consumers each expect different output shapes. Fail-fast at wiring time prevents deployment misconfiguration. |

---

## SPI Scope

This SPI is scoped to **text-in, tensor-out inference**. All five known consumers (NLI, classification, regression, SPLADE, cross-encoder) operate on text inputs. Non-text modalities (image, audio, multimodal) are out of scope — they would require a different input type and tokenization strategy.

The name `InferenceModel` (not `TextInferenceModel`) is intentional — the module lives in `casehub-neural-text`, which already scopes to text. If a non-text inference SPI is needed in the future, it would be a separate module with its own types.

---

## SPI Category: Required Capability

`InferenceModel` is a **required-capability SPI** — neither of the platform's two default patterns (no-op default for operational SPIs, populated default for vocabulary SPIs) applies. There is no sensible no-op: an inference model that returns nothing is not functional. Quarkus CDI wiring (C5) must fail at startup if no model is configured, not provide a silent no-op bean.

---

## Multi-Output Escape Hatch

The SPI returns `InferenceOutput(float[] values)` — one tensor per inference call. This is correct for all five known consumers. When a multi-output model arrives (e.g., a cross-encoder that returns both a score tensor and an attention map):

**The SPI does not change.** The runtime selects which output tensor to extract. Today it extracts the first output. The escape hatch: `ModelConfig` gains an optional `outputName` field to select a specific named output tensor. The SPI stays simple — `InferenceOutput` always carries exactly one tensor's values. The selection happens in the runtime, not the SPI.

This avoids re-introducing tensor name coupling at the SPI level while giving consumers control over which output they receive.

---

## Module 1: inference-api

**Package:** `io.casehub.inference`
**Dependencies:** none (pure Java, zero deps)
**ArchUnit enforced:** zero casehub, Quarkus, Spring, LangChain4j imports

### Types

**`InferenceModel`** — the SPI interface.

```java
public interface InferenceModel extends AutoCloseable {
    InferenceOutput run(InferenceInput input);
    List<InferenceOutput> runBatch(List<InferenceInput> inputs);
    default OptionalInt outputSize() { return OptionalInt.empty(); }
    @Override void close();
}
```

- `AutoCloseable` because ONNX sessions hold native memory. `close()` must be idempotent — second and subsequent calls are no-ops.
- `runBatch` is on the SPI (not a convenience wrapper) because native ONNX batching — padding multiple inputs into one tensor for a single `session.run()` — is significantly faster than looping `run()`. The in-memory impl loops; the ONNX impl batches natively.
- `runBatch()` contract: null `inputs` list or null elements throw `IllegalArgumentException`. Empty list returns an empty list (vacuous — no inputs, no outputs). Returns one `InferenceOutput` per input, in the same order. The returned list is unmodifiable.
- `outputSize()` returns `OptionalInt` — empty means unknown (or dynamic). ONNX runtime returns the real value from session metadata. Task adapters (C4) validate at construction: `model.outputSize().ifPresent(size -> { if (size != 3) throw ... })`.
- Thread safety contract: implementations must be safe for concurrent `run()`/`runBatch()` calls.
- Lifecycle: `InferenceModel` is one-shot — construct, use, close. Calling `run()` or `runBatch()` after `close()` throws `InferenceException`. All implementations must enforce this — including test stubs. Implementations are not recyclable.

**`InferenceInput`** — text input to a model.

```java
public record InferenceInput(List<String> texts) {
    public InferenceInput {
        if (texts == null || texts.isEmpty())
            throw new IllegalArgumentException("texts must not be empty");
        if (texts.size() > 2)
            throw new IllegalArgumentException(
                "at most 2 texts supported (single text or text pair)");
        texts = List.copyOf(texts);
    }
    public static InferenceInput of(String text) { ... }
    public static InferenceInput pair(String first, String second) { ... }
}
```

- Immutable — defensive copy in compact constructor.
- **Constrained to at most 2 texts.** No known ONNX text model takes 3+ text inputs through a single tokenizer encode call. The type prevents invalid states rather than deferring rejection to the runtime.
- `of()` for single-text models (classification, regression, SPLADE).
- `pair()` for two-text models (NLI premise+hypothesis, cross-encoder query+document).
- Runtime dispatches tokenization on `texts.size()`: 1 → single encode, 2 → pair encode with [SEP].

**`InferenceOutput`** — raw model output values.

```java
public record InferenceOutput(float[] values) {
    public InferenceOutput {
        Objects.requireNonNull(values, "values must not be null");
        values = values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }

    @Override public boolean equals(Object o) {
        return o instanceof InferenceOutput other && Arrays.equals(values, other.values);
    }
    @Override public int hashCode() { return Arrays.hashCode(values); }
    @Override public String toString() {
        if (values.length <= 5) return "InferenceOutput" + Arrays.toString(values);
        return "InferenceOutput[" + values[0] + ", " + values[1] + ", " + values[2]
            + ", ... (" + values.length + " values)]";
    }
}
```

- **Fully defensive**: clones on construction (prevents upstream mutation) and on access (prevents downstream mutation). Consistent with `InferenceInput`'s `List.copyOf` guarantee.
- Clone cost: NLI (3 floats) = trivial. SPLADE (30K floats) = ~120KB per access, called once per inference — negligible.
- `toString()` truncates to first 3 values + length when > 5 elements. Prevents multi-megabyte log lines from SPLADE outputs.
- Semantic interpretation is the task adapter's job (C4): NLI → indices 0/1/2 are entailment/neutral/contradiction. Classification → one score per class. Regression → single scalar. SPLADE → vocab-sized weight vector.

**`InferenceException`** — unchecked wrapper for runtime errors.

```java
public class InferenceException extends RuntimeException {
    public InferenceException(String message) { ... }
    public InferenceException(String message, Throwable cause) { ... }
}
```

---

## Module 2: inference-runtime

**Package:** `io.casehub.inference.runtime`
**Dependencies:** inference-api, ONNX Runtime JVM (`onnxruntime`), DJL Tokenizers (`ai.djl.huggingface:tokenizers`)
**ArchUnit enforced:** zero casehub, Quarkus, Spring, LangChain4j imports

### Types

**`ModelConfig`** — construction parameters for an ONNX model.

```java
public record ModelConfig(
    Path modelPath,
    Path tokenizerPath,
    int maxSequenceLength,
    int intraOpThreads,
    int interOpThreads
) {
    public ModelConfig {
        Objects.requireNonNull(modelPath);
        Objects.requireNonNull(tokenizerPath);
        if (maxSequenceLength <= 0)
            throw new IllegalArgumentException("maxSequenceLength must be positive");
        if (intraOpThreads < 0)
            throw new IllegalArgumentException("intraOpThreads must be non-negative");
        if (interOpThreads < 0)
            throw new IllegalArgumentException("interOpThreads must be non-negative");
    }
    public ModelConfig(Path modelPath, Path tokenizerPath) {
        this(modelPath, tokenizerPath, 512, 0, 0);
    }
    public ModelConfig(Path modelPath, Path tokenizerPath, int maxSequenceLength) {
        this(modelPath, tokenizerPath, maxSequenceLength, 0, 0);
    }
}
```

- `intraOpThreads` — parallelism within a single ONNX operation. 0 = ONNX Runtime default (all cores).
- `interOpThreads` — parallelism across operations in the graph. 0 = ONNX Runtime default.
- These are pure runtime concerns, not framework-specific. Consumers using inference-runtime without Quarkus can control thread allocation directly. Execution providers (CUDA, CoreML) and graph optimization level are deferred to C5.

**`OnnxInferenceModel`** — the ONNX Runtime implementation of `InferenceModel`.

```java
public final class OnnxInferenceModel implements InferenceModel { ... }
```

Constructor:
1. `OrtEnvironment.getEnvironment()` — process-global singleton
2. Configure `SessionOptions` — set intra/inter-op thread counts from `ModelConfig` if non-zero
3. `env.createSession(modelPath, sessionOptions)` — loads model, allocates native heap. Wraps `OrtException` → `ModelLoadException`
4. **Validate model inputs**: verify the model has inputs named `input_ids` and `attention_mask`. If missing, throw `ModelLoadException` with a message listing the actual input names.
5. **Validate model outputs**: verify at least one output exists and its shape is rank 2 (`[batch, dim]`). If not, throw `ModelLoadException`.
6. `HuggingFaceTokenizer.builder().optTokenizerPath(...).optMaxLength(...).optTruncation(true).optPadding(false).build()` — tokenizer with truncation enabled, padding disabled (batch-level padding is handled by the runtime, not the tokenizer — see `runBatch()` below). Wraps `IOException` → `ModelLoadException`
7. `outputSize` — read from validated output shape's last dimension. If the dimension is dynamic (-1), `outputSize()` returns `OptionalInt.empty()` — task adapters skip validation rather than seeing a meaningless value.

`run()` flow:
1. Check not closed — throw `InferenceException` if closed
2. Tokenize: `texts.size() == 1` → `tokenizer.encode(text)`, `texts.size() == 2` → `tokenizer.encode(a, b)`
3. Extract `long[] inputIds`, `long[] attentionMask` from `Encoding`
4. Wrap as 2D tensors: `long[1][seqLen]`
5. `session.run(Map.of("input_ids", ..., "attention_mask", ...))` — input tensor names hard-coded (universal HuggingFace standard)
6. Extract `float[][]` from first output, return `new InferenceOutput(logits[0])`
7. Tensors closed via try-with-resources

`runBatch()` flow:
1. Check not closed → `InferenceException` if closed
2. Null list → `IllegalArgumentException`
3. Empty list → return empty unmodifiable list
4. Null elements → `IllegalArgumentException`
5. Tokenize all inputs individually
- Pad to batch-max length (not `maxSequenceLength` — shorter when inputs are similar length). Tokenizer padding is disabled at construction; the runtime pads all inputs to the batch-max length after tokenization, using the tokenizer's pad token ID (`tokenizer.getPadTokenId()`).
- Stack into `long[batchSize][maxLen]` tensors
- Single `session.run()` call
- Split `float[batchSize][outputDim]` result into `List<InferenceOutput>` (unmodifiable, same order as inputs)

`close()`: idempotent, try-finally pattern. Does not close `OrtEnvironment` (process singleton).

```java
@Override
public void close() {
    if (closed) return;
    closed = true;
    try { session.close(); } finally { tokenizer.close(); }
}
```

Thread safety: `OrtSession.run()` and `HuggingFaceTokenizer.encode()` are both documented thread-safe. No mutable instance state beyond the `volatile boolean closed` flag.

**`ModelLoadException`** — subclass of `InferenceException` for construction failures.

```java
public class ModelLoadException extends InferenceException {
    public ModelLoadException(String message, Throwable cause) { ... }
}
```

### C2 bridge code disposition

`OnnxSessionLoader`, `RawInference`, `TokenizerLoader` are deleted. Their logic is subsumed by `OnnxInferenceModel`: constructor replaces session/tokenizer loading, `run()` replaces `classifyPair()` generalized to handle single-text and pair inputs.

### Known limitation

Only `input_ids` and `attention_mask` are passed. Models requiring `token_type_ids` (original BERT, ALBERT) will produce incorrect results. Tracked as #8. DeBERTa (our primary NLI model) does not use token_type_ids.

---

## Module 3: inference-inmem

**Package:** `io.casehub.inference.inmem`
**Dependencies:** inference-api only
**ArchUnit enforced:** zero casehub, Quarkus, Spring, LangChain4j, ONNX Runtime, DJL imports

### Types

**`InMemoryInferenceModel`** — deterministic stub for testing.

```java
public final class InMemoryInferenceModel implements InferenceModel {

    private volatile boolean closed;

    public static InMemoryInferenceModel returning(float... values) { ... }
    public static InMemoryInferenceModel withFunction(
            int outputSize, Function<InferenceInput, float[]> fn) { ... }

    @Override public InferenceOutput run(InferenceInput input) { ... }
    @Override public List<InferenceOutput> runBatch(List<InferenceInput> inputs) { ... }
    @Override public OptionalInt outputSize() { ... }
    @Override public void close() { closed = true; }
}
```

- `returning()` — clones the varargs array on construction and on each `run()` call. Consistent with `InferenceOutput`'s defensive-copy pattern. `outputSize()` returns `OptionalInt.of(values.length)`.
- `withFunction()` — custom logic per input, explicit `outputSize` required. `outputSize()` returns `OptionalInt.of(outputSize)`. **Thread safety: the provided function must itself be thread-safe** — the model delegates directly, no synchronization is added. Document in Javadoc.
- `runBatch()` — validation ordering: closed check → null list → empty list → null elements → loop `run()`. Same ordering as `OnnxInferenceModel`. The closed check must precede the empty-list early return — `runBatch(List.of())` after `close()` must throw, not return an empty list.
- `close()` — sets a closed flag. Subsequent `run()`/`runBatch()` calls throw `InferenceException`. Idempotent (second close is a no-op). Honors the SPI post-close contract — test stubs must not silently allow post-close usage.
- No JNI, no native libs. Safe in `@QuarkusTest`, native image tests, plain JUnit.

---

## Data Flow

```
Caller text
  → InferenceInput.of("text") or .pair("a", "b")
  → OnnxInferenceModel.run():
      → HuggingFace Tokenizers JNI: text → token IDs + attention mask
      → OnnxTensor: token IDs → long[1][seqLen] tensors
      → OrtSession.run(tensors) → float[][] raw output
      → InferenceOutput(logits[0])
  → Task adapter (C4): interprets float[] as domain result
```

---

## Dependency Graph

```
inference-tasks (C4)  ──→  inference-api  ←──  inference-inmem
inference-splade (C6) ──→       ↑
inference-quarkus (C5) ──→  inference-runtime
```

All arrows point toward inference-api. inference-runtime depends on inference-api (not reverse). inference-inmem depends on inference-api only. Task modules (C4, C6) and Quarkus wiring (C5) are future epics.

---

## Error Scenarios

| Scenario | When | Exception | Message guidance |
|---|---|---|---|
| Model file doesn't exist | Constructor | `ModelLoadException` | Include the path that was not found |
| Tokenizer JSON corrupt or missing | Constructor | `ModelLoadException` | Include the path and underlying parse error |
| Model missing `input_ids` or `attention_mask` input | Constructor | `ModelLoadException` | List actual input names so the user can identify the mismatch |
| Model output not rank 2 | Constructor | `ModelLoadException` | Show actual shape |
| `run()`/`runBatch()` called after `close()` | Runtime | `InferenceException` | "Model is closed" |
| ONNX Runtime error during inference | Runtime | `InferenceException` | Wrap `OrtException` with context |
| Tokenization failure | Runtime | `InferenceException` | Wrap with input context (text length, encoding mode) |

All construction errors are `ModelLoadException` (subclass of `InferenceException`). All runtime errors are `InferenceException`. No checked exceptions escape the SPI.

---

## Testing Strategy

### inference-api (unit tests)

- `InferenceInput`: null texts, empty list, size 1 (valid), size 2 (valid), size 3+ (rejected), immutability (mutating source list doesn't affect record)
- `InferenceOutput`: null values (rejected), equality/hashCode with `Arrays.equals` semantics, defensive copy (mutating source array doesn't affect record, mutating `values()` return doesn't affect record), `toString` truncation for arrays > 5 elements
- `InferenceException`: standard exception construction

### inference-inmem (unit tests)

- `InMemoryInferenceModel.returning()`: returns expected values, `outputSize()` matches, same output regardless of input, clones varargs on construction (mutating source doesn't affect stub), clones on each `run()` call (mutating returned output doesn't affect next call)
- `InMemoryInferenceModel.withFunction()`: function receives correct input, `outputSize()` returns configured value
- `runBatch()`: returns one output per input, consistent with individual `run()` calls; empty list returns empty list; null list and null elements throw IAE
- Post-close: `run()` after `close()` throws `InferenceException`; second `close()` is a no-op

### inference-runtime (unit + integration tests)

**Unit tests (no ONNX model required):**
- `ModelConfig`: validation (null paths, zero/negative maxSeqLen, negative thread counts)

**Integration tests (require a test ONNX model):**
- End-to-end: load model → tokenize → infer → extract output → close
- Model validation at load: model missing `input_ids` → `ModelLoadException` with input name list
- Model validation at load: model with wrong output rank → `ModelLoadException` with shape
- Post-close: `run()` after `close()` throws `InferenceException`
- Close idempotency: second `close()` is a no-op, no exception
- Close ordering: session close failure still closes tokenizer
- **Batch/single equivalence**: `run(input)` and `runBatch(List.of(input)).get(0)` must produce identical `InferenceOutput`. If batch padding causes divergence, it's a bug.
- Thread safety: concurrent `run()` calls on the same model produce correct results

**Test model strategy:** Create a minimal ONNX model with the correct input/output signature (random weights, ~10KB) using Python's `onnx` library. Pair with a real `tokenizer.json` from a small model (e.g., `bert-base-uncased` tokenizer — ~700KB, well-tested, compatible with `input_ids` + `attention_mask` input names). Check both into test resources at `inference-runtime/src/test/resources/test-model/`. Tests verify the runtime can load, tokenize, and run — not that inference results are semantically meaningful. Semantic correctness is verified in C4 (task adapters) with real models.

### ArchUnit (all three modules)

- `inference-api`: zero imports from `io.casehub` (non-inference), Quarkus, Spring, LangChain4j, ONNX Runtime, DJL
- `inference-runtime`: zero imports from `io.casehub` (non-inference), Quarkus, Spring, LangChain4j
- `inference-inmem`: zero imports from `io.casehub` (non-inference), Quarkus, Spring, LangChain4j, ONNX Runtime, DJL

---

## Deferred

| Concern | Issue | Notes |
|---|---|---|
| `token_type_ids` third input tensor | #8 | BERT/ALBERT need it; DeBERTa doesn't. Low priority. |
| Multi-output tensor selection | C5 scope | `ModelConfig.outputName` field selects a specific named output. SPI unchanged. |
| Execution provider config (CUDA, CoreML) | C5 scope | Quarkus config will expose `OrtSession.SessionOptions`. |
| Non-text modalities (image, audio) | Out of scope | Would require a different SPI with different input types. |
| CLAUDE.md sync | Post-implementation | CLAUDE.md lists `ModelConfig` in inference-api; update after C3 lands. |