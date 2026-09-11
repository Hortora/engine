# inference-tasks — Task Adapters Design Spec

**Date:** 2026-06-07
**Status:** Approved (rev 3)
**Issue:** casehubio/neural-text#4
**Module:** inference-tasks
**Consumers:** casehub-engine (#154 — NliClassifier), casehub-openclaw (TextClassifier → ActionRiskClassifier), casehub-eidos (ScalarRegressor → CapabilityHealth epistemic confidence), Hortora (CrossEncoderReranker)
**Depends on:** inference-api (C3, shipped)
**Authoritative prior spec:** Hortora/spec `docs/superpowers/specs/2026-06-03-onnx-inference-module-design.md` §Task Adapters

---

## What This Delivers

Four typed adapters that wrap `InferenceModel` and interpret `float[]` output as domain results. Callers work with `NliResult`, `ClassificationResult`, `float`, and `List<RankedResult>` — never raw tensors. All adapters are pure Java, zero deps, tested entirely with `InMemoryInferenceModel` stubs.

---

## Module Identity

- **Package:** `io.casehub.inference.tasks`
- **Dependencies:** `casehub-inference-api` (compile). `casehub-inference-inmem`, `archunit-junit5` (test).
- **Tier:** Pure Java, zero external deps (Tier 1 per `module-tier-structure.md`)
- **ArchUnit enforced:** zero casehub (non-inference), Quarkus, Spring, LangChain4j, ONNX Runtime, DJL imports
- **Thread safety:** Adapters are stateless and thread-safe — concurrent calls delegate to the thread-safe `InferenceModel`. Safe as `@ApplicationScoped` CDI beans in C5.

---

## Adapter Lifecycle

Adapters are **pure interpreters** — they do not implement `AutoCloseable` and do not own the `InferenceModel` they wrap. The model's lifecycle is the caller's (or CDI container's) responsibility.

Rationale: multiple adapters may share the same underlying model, or the model may outlive the adapter. In C5 (Quarkus), CDI manages model lifecycle; adapters are beans that receive an injected model. Keeping adapters resource-free makes them trivially testable and composable.

```java
var model = new OnnxInferenceModel(config);
var nli = new NliClassifier(model);
nli.classify("premise", "hypothesis");
model.close();  // caller closes
```

---

## Construction Validation

All adapters validate `model.outputSize()` at construction time when available:
- If `outputSize()` is present and doesn't match the expected size → `IllegalArgumentException`
- If `outputSize()` is empty (`OptionalInt.empty()`) → skip validation (model may still be correct; shape issues discovered at first `run()`)

This provides fail-fast at wiring time for misconfigured models without rejecting valid models with dynamic output dimensions.

---

## Runtime Output-Length Guard

In addition to construction-time validation, each adapter validates the length of `InferenceOutput.values()` at every call. If the output length doesn't match the expected size, the adapter throws `InferenceException("Expected N output values, got M")`.

This is defence-in-depth: construction validation catches misconfigured models at wiring time; runtime validation catches models whose `outputSize()` was empty (dynamic) or whose in-memory stub was misconfigured. The cost is one integer comparison per call. The benefit is turning cryptic `ArrayIndexOutOfBoundsException` at `values[2]` into a diagnostic message.

Each adapter extracts `values()` exactly once per output — the clone from `InferenceOutput.values()` is consumed immediately. No re-access.

---

## Types

### NliLabel (enum)

```java
public enum NliLabel {
    ENTAILMENT, NEUTRAL, CONTRADICTION
}
```

### NliResult (record)

```java
public record NliResult(float entailment, float neutral, float contradiction) {
    public NliLabel predicted() { /* returns label with highest score */ }
    public Map<NliLabel, Float> scores() {
        return Map.of(ENTAILMENT, entailment, NEUTRAL, neutral, CONTRADICTION, contradiction);
    }
}
```

All values in [0,1], sum to 1 (softmax applied by the classifier). `scores()` returns an unmodifiable 3-element map for programmatic label iteration — consistent with `ClassificationResult.scores()`.

### NliClassifier

```java
public final class NliClassifier {
    public NliClassifier(InferenceModel model) { ... }
    public NliClassifier(InferenceModel model,
                         int entailmentIndex, int neutralIndex, int contradictionIndex) { ... }
    public NliResult classify(String premise, String hypothesis) { ... }
}
```

- **Convention constructor:** `NliClassifier(model)` uses HuggingFace standard label order: index 0=contradiction, 1=neutral, 2=entailment. Delegates to the explicit constructor with `(model, 2, 1, 0)`.
- **Explicit-index constructor:** `NliClassifier(model, entailmentIndex, neutralIndex, contradictionIndex)` for models with non-standard label order. Validates all three indices are distinct and in range [0,2].
- Validates `outputSize() == 3` when available
- `classify()` flow: `InferenceInput.pair(premise, hypothesis)` → `model.run()` → extract `values()` once → validate length == 3 → softmax → `NliResult(values[entailmentIndex], values[neutralIndex], values[contradictionIndex])`
- Null premise or hypothesis → `IllegalArgumentException`

The explicit-index constructor is the real escape hatch for non-standard models. `TextClassifier` with NLI labels is not an equivalent — it returns `ClassificationResult`, not `NliResult`, losing the typed three-way probability distribution that NLI consumers depend on.

### ClassificationResult (record)

```java
public record ClassificationResult(String label, float confidence, Map<String, Float> scores) {
    public ClassificationResult {
        Objects.requireNonNull(label, "label must not be null");
        if (confidence < 0 || confidence > 1)
            throw new IllegalArgumentException("confidence must be in [0,1]");
        Objects.requireNonNull(scores, "scores must not be null");
        scores = Map.copyOf(scores);
    }
}
```

`label` is the highest-scoring label. `origin` is its softmax probability. `scores` maps all labels to their probabilities (unmodifiable via `Map.copyOf` in compact constructor).

### TextClassifier

```java
public final class TextClassifier {
    public TextClassifier(InferenceModel model, List<String> labels) { ... }
    public ClassificationResult classify(String text) { ... }
}
```

- Labels provided at construction (defensive copy via `List.copyOf`, immutable). Labels are a model property — fixed at training time.
- Validates `labels.size()` matches `outputSize()` when available
- Null or empty labels → `IllegalArgumentException`
- `classify()` flow: `InferenceInput.of(text)` → `model.run()` → extract `values()` once → validate length == labels.size() → softmax → find argmax → `ClassificationResult(labels[argmax], probs[argmax], allScoresMap)`
- Null text → `IllegalArgumentException`

**Consumer integration — casehub-openclaw `ActionRiskClassifier`:** `TextClassifier.classify(action.description())` → `ClassificationResult` → openclaw maps label+confidence to `RiskDecision.Autonomous` or `RiskDecision.GateRequired`. The `PlannedAction.description()` is the text input; risk labels (e.g., "low_risk", "high_risk") are construction-time model properties.

### ScalarRegressor

```java
public final class ScalarRegressor {
    public ScalarRegressor(InferenceModel model) { ... }
    public float predict(String text) { ... }
}
```

- Validates `outputSize() == 1` when available
- No softmax — returns the raw scalar value
- `predict()` flow: `InferenceInput.of(text)` → `model.run()` → extract `values()` once → validate length == 1 → return `values[0]`
- Null text → `IllegalArgumentException`

**Consumer integration — casehub-eidos `CapabilityHealth`:** `ScalarRegressor.predict(taskDescription)` returns a float confidence → eidos compares against `casehub.eidos.epistemic.weak-threshold` (default 0.3) → `CapabilityStatus.EpistemicallyWeak` if below threshold. The float→double widening is safe.

### RankedResult (record)

```java
public record RankedResult(String text, float score, int originalIndex) {}
```

Ordered by descending score in the list returned by `rerank()`. Duplicate candidates are handled naturally — each is scored independently, `originalIndex` distinguishes them.

### CrossEncoderReranker

```java
public final class CrossEncoderReranker {
    public CrossEncoderReranker(InferenceModel model) { ... }
    public float score(String query, String candidate) { ... }
    public List<RankedResult> rerank(String query, List<String> candidates) { ... }
}
```

- Validates `outputSize() == 1` when available (cross-encoders output a single relevance score per pair)
- `score()` flow: `InferenceInput.pair(query, candidate)` → `model.run()` → extract `values()` once → validate length == 1 → return `values[0]`
- `rerank()` flow: validate all arguments → build `List<InferenceInput>` with `pair(query, candidate)` for each candidate → `model.runBatch()` → extract score from each output → sort by descending score → `List<RankedResult>` (unmodifiable)
- Null query, null candidates, empty candidates → `IllegalArgumentException`
- Null elements in candidates → `IllegalArgumentException`

---

## Softmax

Package-private utility class `Softmax` in `io.casehub.inference.tasks`. Single implementation, zero public API surface. Numerically stable: subtract max before exp to prevent overflow.

```java
final class Softmax {
    private Softmax() {}

    static float[] apply(float[] logits) {
        float max = logits[0];
        for (int i = 1; i < logits.length; i++) if (logits[i] > max) max = logits[i];
        float sum = 0;
        float[] result = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            result[i] = (float) Math.exp(logits[i] - max);
            sum += result[i];
        }
        for (int i = 0; i < logits.length; i++) result[i] /= sum;
        return result;
    }
}
```

Used by `NliClassifier` and `TextClassifier`. `ScalarRegressor` and `CrossEncoderReranker` do not need softmax.

---

## Argument Validation

All adapters validate null arguments explicitly before constructing `InferenceInput`, ensuring `IllegalArgumentException` with adapter-specific messages (e.g., "premise must not be null"). `InferenceInput` factory NPEs are never exposed to callers — the adapter is the caller's boundary and owns its own precondition reporting.

## Error Handling

| Scenario | Exception | When |
|---|---|---|
| Null model | `IllegalArgumentException` | Constructor |
| Null/empty labels (TextClassifier) | `IllegalArgumentException` | Constructor |
| `outputSize()` mismatch | `IllegalArgumentException` | Constructor |
| Duplicate or out-of-range NLI indices | `IllegalArgumentException` | Constructor |
| Null arguments (premise, hypothesis, text, query, candidates, elements) | `IllegalArgumentException` | Method call (before InferenceInput construction) |
| Empty candidates list (rerank) | `IllegalArgumentException` | Method call |
| Output length mismatch at runtime | `InferenceException` | Method call |
| Model closed or inference failure | `InferenceException` (from model) | Method call |

No adapter-specific exception subclass. `IllegalArgumentException` for precondition violations, `InferenceException` for inference and output-shape failures.

---

## Testing Strategy

All tests use `InMemoryInferenceModel` — zero JNI, zero native libs.

### NliClassifier

- Softmax applied: raw logits [2.0, 1.0, 0.5] → probabilities sum to 1.0
- `predicted()` returns highest label
- Convention order verified: index 0=contradiction, index 2=entailment
- Explicit-index constructor: custom mapping produces correct NliResult field assignment
- Explicit-index validation: duplicate indices → IAE, out-of-range indices → IAE
- `scores()` returns Map with all three labels
- Output size validation: model with outputSize=5 → IAE at construction
- Output size unknown (empty OptionalInt): construction succeeds
- Runtime output-length guard: model returning wrong-length array → InferenceException
- Null premise/hypothesis → IAE
- Null model → IAE

### TextClassifier

- Labels match output indices: model returning [0.1, 0.9] with labels ["low", "high"] → label="high"
- Label count vs outputSize mismatch → IAE at construction
- Immutability: mutating labels list after construction has no effect
- Single-label degenerate case
- Runtime output-length guard: model returning wrong-length array → InferenceException
- ClassificationResult.scores is unmodifiable (Map.copyOf)
- Null/empty labels → IAE
- Null text → IAE

### ScalarRegressor

- Returns raw value: model returning [0.73] → predict() returns 0.73
- Output size validation: model with outputSize=3 → IAE
- Runtime output-length guard: model returning multi-element array → InferenceException
- Null text → IAE

### CrossEncoderReranker

- `score()` returns raw value from model
- `rerank()` returns results sorted by descending score
- `rerank()` preserves original indices correctly
- Empty candidates → IAE
- Single candidate → works (returns list of one)
- Null query/candidates/elements → IAE (elements checked before InferenceInput construction)
- Runtime output-length guard: model returning multi-element array → InferenceException
- Duplicate candidates scored independently with distinct originalIndex values

**Note:** `InMemoryInferenceModel.runBatch()` delegates to `run()` via the same function — unit tests cannot distinguish whether `rerank()` calls `runBatch()` or loops `run()`. The batch optimization is verified at the integration level against `OnnxInferenceModel` where `runBatch()` performs native batching.

### Softmax

- `Softmax.apply()` tested directly (package-private access from tests in same package)
- Numeric stability: large logits (e.g., [1000, 1001, 1002]) don't overflow
- Single-element input
- Uniform input: all equal logits → uniform distribution

### ArchUnit

`DependencyConstraintTest` enforces two categories of constraint:

**Framework exclusion** (same as inference-api and inference-inmem):
- No Quarkus/Jakarta, Spring, LangChain4j, ONNX Runtime, DJL imports

**Casehub domain exclusion** (new — first module where this needs an explicit rule):

The existing modules (inference-api, inference-inmem) don't enforce this because casehub domain types aren't on their classpaths. inference-tasks will be consumed by casehub modules in C5, making accidental domain imports possible. The architectural invariant from ARC42STORIES §2 ("zero dependencies on casehub domain artifacts") requires an explicit rule:

```java
@ArchTest
static final ArchRule noCasehubDomain = noClasses()
    .that().resideInAPackage("io.casehub.inference.tasks..")
    .should().dependOnClassesThat(
        DescribedPredicate.describe("casehub domain classes",
            cls -> cls.getPackageName().startsWith("io.casehub.")
                && !cls.getPackageName().startsWith("io.casehub.inference")));
```

This allows `io.casehub.inference.*` (the SPI) while blocking `io.casehub.engine.*`, `io.casehub.openclaw.*`, `io.casehub.eidos.*`, and any other casehub domain package. This rule should also be backported to inference-api, inference-inmem, and inference-runtime — tracked as a follow-up, not blocking C4.

---

## Dependency Graph

```
inference-tasks ──→ inference-api ←── inference-inmem (test only)
```

inference-tasks depends on inference-api at compile scope. inference-inmem and archunit-junit5 are test-scope only. No other dependencies.

---

## Departures from Hortora Spec

| Hortora spec | This spec | Rationale |
|---|---|---|
| `TextClassifier` signature shows `ClassificationResult { label, confidence, allScores: Map<String, Float> }` | Same shape but labels provided at construction, not embedded in the model | `InferenceOutput` is `float[]` — label names must come from somewhere external to the model output |
| `NliClassifier` has single constructor | Convention constructor + explicit-index constructor | Non-standard models need typed NliResult without falling back to TextClassifier's different return type |
| No softmax mentioned | NliClassifier and TextClassifier always apply softmax | Raw logits are not meaningful to callers; probabilities are |
| `CrossEncoderReranker.rerank()` only | Both `score()` and `rerank()` | Single-pair scoring is a common need (e.g., filtering a single candidate); `rerank()` uses `runBatch()` for batch efficiency |
| No lifecycle discussion | Adapters are explicitly not AutoCloseable | Prevents double-close bugs when adapters share a model |
| No output-length validation at runtime | Runtime guard on every call | Defence-in-depth — turns ArrayIndexOutOfBoundsException into diagnostic InferenceException |