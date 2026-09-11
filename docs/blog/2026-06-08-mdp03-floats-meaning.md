---
layout: post
title: "CaseHub Neural Text — The Layer That Gives Floats Meaning"
date: 2026-06-08
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [inference, task-adapters, design]
---

# CaseHub Neural Text — The Layer That Gives Floats Meaning

**Date:** 2026-06-08
**Type:** phase-update

---

## What I was trying to build: typed adapters over raw tensor output

C3 delivered `InferenceModel` — a clean SPI that takes text in and returns `float[]` out. The SPI deliberately stops there. It doesn't know that index 0 of a 3-element array means "contradiction" and index 2 means "entailment". That's the job of C4: four typed adapters in `inference-tasks` that interpret raw output as domain results.

The target was straightforward from the Hortora spec: `NliClassifier`, `TextClassifier`, `ScalarRegressor`, `CrossEncoderReranker`. Each wraps an `InferenceModel`, calls `run()`, reads the `float[]`, and returns a typed result. No JNI, no native libs, pure Java.

## The lifecycle question that simplified everything

The first design question was whether adapters should implement `AutoCloseable` and own their wrapped model. It feels natural — you construct an `NliClassifier`, use it, close it. But two things kill it: model sharing and CDI.

In Quarkus (C5), the model is an `@ApplicationScoped` CDI bean. Multiple adapters share the same model — an `NliClassifier` and a `TextClassifier` might both wrap the same underlying ONNX session if the model supports both tasks. If adapters own and close the model, the first adapter to close kills the second.

The answer: adapters are pure interpreters. They hold a reference to the model, call `run()`, interpret the output. They don't implement `AutoCloseable`. They don't manage resources. The caller (or CDI) manages the model's lifecycle independently. This makes adapters trivially testable — construct with `InMemoryInferenceModel.returning(1.0f, 2.0f, 3.0f)`, call `classify()`, assert the result. No setup, no teardown.

## The NLI escape hatch that the review caught

The initial design used a single convention-based constructor: `NliClassifier(model)` with hardcoded HuggingFace label order — index 0 is contradiction, 1 is neutral, 2 is entailment. If a model uses non-standard order, the spec said "use `TextClassifier` with explicit labels instead."

Claude caught the gap in review: `TextClassifier.classify()` returns `ClassificationResult` — a winning label and a scores map. NLI consumers want `NliResult` — the full three-way probability distribution with `.entailment()`, `.neutral()`, `.contradiction()`, and `.predicted()`. Falling back to `TextClassifier` loses the typed API that NLI consumers depend on.

The fix: a second constructor — `NliClassifier(model, entailmentIndex, neutralIndex, contradictionIndex)` — that preserves the `NliResult` return type while supporting any label order. The convention constructor delegates to it with `(model, 2, 1, 0)`. Three distinct indices from {0,1,2} is exactly a permutation — validated at construction with `Set.of(a, b, c).size() != 3`.

## Two layers of validation, and why both matter

Every adapter validates at two points. At construction: `model.outputSize().ifPresent(size -> { if (size != 3) throw IAE })`. At runtime: `if (values.length != 3) throw InferenceException`. The construction check catches misconfigured models at wiring time. The runtime check catches models with dynamic output dimensions whose `outputSize()` returned empty.

The runtime guard is one integer comparison per call. Without it, a wrong-length output produces `ArrayIndexOutOfBoundsException` at `values[2]` — a stack trace that tells you nothing about what went wrong. With it, the message says "Expected 3 output values, got 2" — immediately actionable.

## The ArchUnit rule that didn't exist yet

inference-tasks is the first module in the repo that will be consumed by casehub domain modules in C5. The existing ArchUnit tests block framework imports (Quarkus, Spring, LangChain4j, ONNX Runtime, DJL), but none of them enforce the "zero casehub domain dependency" constraint from ARC42STORIES §2. Today it's safe because casehub types aren't on the classpath. Once Quarkus CDI wiring adds casehub dependencies transitively, an accidental import of `io.casehub.engine.*` would compile silently.

The new rule uses ArchUnit's `DescribedPredicate`:

```java
@ArchTest
static final ArchRule noCasehubDomain = noClasses()
    .that().resideInAPackage("io.casehub.inference.tasks..")
    .should().dependOnClassesThat(
        DescribedPredicate.describe("casehub domain classes",
            (JavaClass cls) -> cls.getPackageName().startsWith("io.casehub.")
                && !cls.getPackageName().startsWith("io.casehub.inference")));
```

It allows `io.casehub.inference.*` (the SPI) while blocking every other `io.casehub.*` package. The existing modules need the same rule backported — tracked as #10.

## What it is now

Four adapters, four domain result types, one shared softmax utility, one ArchUnit test with framework and domain exclusion rules. The adapters are the thinnest possible layer between raw model output and consumer code — softmax where needed, argmax for classification, pass-through for regression and reranking. `CrossEncoderReranker.rerank()` uses `runBatch()` for native ONNX batching; `score()` uses `run()` for single-pair evaluation.

C5 (Quarkus CDI wiring) and C6 (SPLADE sparse embeddings) are both unblocked. SPLADE follows the same adapter pattern — wraps `InferenceModel`, interprets output — but with log-saturation post-processing and a `Map<Integer, Float>` return type that doesn't fit the classifier/regressor/reranker taxonomy. That's why it's a separate module.