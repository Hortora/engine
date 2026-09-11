---
layout: post
title: "CaseHub Neural Text — The Qualifier That Couldn't Share Its Name"
date: 2026-06-08
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [inference, cdi, quarkus, splade]
---

# CaseHub Neural Text — The Qualifier That Couldn't Share Its Name

**Date:** 2026-06-08
**Type:** phase-update

---

## What I was trying to achieve: CDI wiring for inference models

C4 shipped the task adapters. C6 needed the SPLADE sparse embedder. C5 needed to wire everything into Quarkus CDI so downstream consumers can inject models by name. The issue description called for `@InferenceModel` as the qualifier annotation. That lasted about thirty seconds of actual design work.

## The naming collision nobody thought about

The SPI interface is `io.casehub.inference.InferenceModel`. The qualifier would be `io.casehub.inference.quarkus.InferenceModel`. Different packages, same simple name. Java's import system can't handle that — you can't import both. Consumer code would look like:

```java
@Inject
@io.casehub.inference.quarkus.InferenceModel("nli")
io.casehub.inference.InferenceModel nliModel;
```

Fully qualified everything. Ugly, error-prone, and a daily annoyance for anyone writing injection points. The fix was obvious once the problem was visible: call it `@Inference` instead. Short, unambiguous, lives in the `quarkus` package where context makes it clear. The issue title was wrong and the design is better for it.

## CDI without a Quarkus extension

The full Quarkus extension pattern — deployment module, build-time synthetic beans, the works — is the proper way to do named resource injection. It's also gated by the native image prototype, and the inference modules are JVM-only until that lands.

The alternative: a single `InferenceModelProducer` with `@DefaultBean`, a `@Produces` method that inspects the `InjectionPoint` for the `@Inference` qualifier value, and a `ConcurrentHashMap` for lazy model creation. Configuration via `@ConfigMapping` with a `Map<String, ModelProperties>`. The producer reads the qualifier name, looks up the config, creates an `OnnxInferenceModel` on first access, and closes everything on shutdown.

```java
@Produces @DefaultBean @Inference("") @Dependent
InferenceModel produce(InjectionPoint ip) {
    String name = ip.getAnnotated().getAnnotation(Inference.class).value();
    return models.computeIfAbsent(name, this::createModel);
}
```

The `@Nonbinding` value on `@Inference` means CDI treats all `@Inference` qualifiers as the same bean regardless of the name — the InjectionPoint inspection resolves the specific model at runtime. `@DefaultBean` means any test that provides its own `@Produces @Inference("") InferenceModel` method wins automatically. The test override uses `@Alternative` enabled via `QuarkusTestProfile.getEnabledAlternatives()` — production producer is invisible, stub returns `InMemoryInferenceModel`, no config needed.

## The JNI classloader surprise

The inference-quarkus module had a pre-existing plain JUnit test that loads ONNX Runtime JNI directly. Adding `@QuarkusTest` classes broke it — `UnsatisfiedLinkError: Native Library already loaded in another classloader`. The symptom looks like a library packaging problem. The cause is surefire's fork reuse: the `@QuarkusTest` loads the JNI library via the Quarkus augmented classloader, and the plain JUnit test tries to reload it via the system classloader in the same JVM fork. JNI refuses. The fix is `reuseForks=false` — each test class gets its own JVM.

## SPLADE: the simplest adapter

`SparseEmbedder` follows the same pattern as the C4 task adapters — takes an `InferenceModel`, applies post-processing, returns a typed result. The post-processing is three lines: ReLU, `log1p`, threshold filter. The output is `Map<Integer, Float>` — vocabulary token indices to weights, suitable for direct Qdrant sparse vector upsert. The whole class is under 70 lines. Sometimes the right abstraction is just a function with a name.
