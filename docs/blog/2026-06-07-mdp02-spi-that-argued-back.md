---
layout: post
title: "CaseHub Neural Text — The SPI That Argued Back"
date: 2026-06-07
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [inference, spi, onnx, design]
---

# CaseHub Neural Text — The SPI That Argued Back

**Date:** 2026-06-07
**Type:** phase-update

---

## What I was trying to achieve: a clean SPI for ONNX text inference

C2 proved ONNX Runtime JNI and DJL Tokenizers JNI work in Quarkus native image. The prototype left behind three static utility classes — `OnnxSessionLoader`, `RawInference`, `TokenizerLoader` — that did the job but had no SPI boundary. C3 wraps that bridge code in a proper `InferenceModel` SPI so five downstream consumers (casehub-engine, casehub-openclaw, casehub-eidos, casehub-rag, Hortora) can use it without touching ONNX types.

## What I believed going in: the Hortora spec was close enough to implement

The prior Hortora spec (2026-06-03) defined the SPI shape: `InferenceModel`, `InferenceInput`, `InferenceOutput`, `ModelConfig`. I expected to refine it lightly and move to code. Instead, every design decision got stress-tested across four review rounds, and four of them changed.

## Four departures, each earned

The spec started as the Hortora design and ended somewhere different. Each departure came from the same question: does this serve the architecture or just avoid work?

**ModelConfig moved out of inference-api.** The prior spec put `ModelConfig` in inference-api alongside the SPI types. But ModelConfig is a construction concern — paths to ONNX files and thread pool sizes. The in-memory stub has no use for model paths. Moving it to inference-runtime keeps inference-api genuinely zero-dep: four types, no configuration, nothing that smells of a particular runtime.

**InferenceOutput lost its tensor names.** The prior spec had `InferenceOutput.tensor(String name)`, which leaks ONNX model internals into the SPI. Every target model produces one output tensor. We went with `InferenceOutput(float[] values)` — the runtime extracts the tensor, callers get floats. If a multi-output model ever arrives, `ModelConfig` gains an `outputName` field to select which tensor. The SPI stays simple.

**outputSize() became OptionalInt.** The first draft used `default int outputSize() { return -1; }`. The review caught it: sentinel values in a multi-consumer SPI are a type-system failure. `OptionalInt` forces callers to handle the "I don't know" case explicitly. Five repos will implement this interface — compiler-enforced correctness beats convention.

**InferenceInput constrained to max 2 texts.** The prior spec accepted `List<String>` of any length but threw at runtime for 3+ texts. The review pointed out the contract smell: a type that accepts states the implementation rejects. No ONNX text model takes 3+ texts through a single tokenizer call. The compact constructor now rejects it at the type boundary.

## The close() contract that contradicted itself

The spec said `try { session.close(); } finally { tokenizer.close(); }` and also said `close() must not throw`. These can't both be true — try-finally propagates exceptions from the try block. The implementation used sequential try-catch instead:

```java
try { session.close(); } catch (Exception ignored) {}
try { tokenizer.close(); } catch (Exception ignored) {}
```

Both resources always close. No exception escapes. The code review flagged it as a spec deviation, which was technically correct — but the implementation satisfies both constraints where the spec satisfies only one.

## The NPE that wasn't an IAE

Claude caught this in the final code review: `InMemoryInferenceModel.runBatch(null)` threw `NullPointerException` while `OnnxInferenceModel.runBatch(null)` threw `IllegalArgumentException`. The SPI Javadoc says `@throws IllegalArgumentException`. The cause: `Objects.requireNonNull` always throws NPE. It's the reflexive Java null check, but when the SPI specifies IAE, it silently violates the contract. Two implementations of the same interface, different observable behaviour for the same call. A one-line fix — but the kind of thing that only surfaces when someone tests exception types, which most unit tests don't.

## What it is now

Three modules, 80 tests, zero domain dependencies on inference-api and inference-inmem (ArchUnit enforced). The `InferenceModel` SPI is text-in, tensor-out. Callers pass strings, get float arrays. The runtime handles tokenization, tensor creation, session management, and lifecycle. The in-memory stub returns fixed outputs for deterministic testing.

The C2 bridge code is gone — `OnnxInferenceModel` subsumes all of it. The gate command in inference-quarkus now creates an `OnnxInferenceModel` in a try-with-resources block instead of juggling three static utility classes.

C4 (task adapters) comes next: `NliClassifier`, `TextClassifier`, `ScalarRegressor`, `CrossEncoderReranker`. They're the layer that gives the float arrays meaning — index 0 is entailment, index 1 is neutral, index 2 is contradiction. The SPI doesn't know or care.
