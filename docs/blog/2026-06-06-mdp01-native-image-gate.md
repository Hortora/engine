---
layout: post
title: "Neural Text — The Native Image Gate"
date: 2026-06-06
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [graalvm, native-image, onnx-runtime, djl, jni, quarkus]
---

# Neural Text — The Native Image Gate

**Date:** 2026-06-06
**Type:** phase-update

---

## What I was trying to prove: both JNI layers work in Quarkus native image on macOS ARM

casehub-neural-text needs two JNI libraries — ONNX Runtime for model inference and DJL HuggingFace Tokenizers for text tokenization. Both use native `.dylib` files loaded at runtime. GraalVM native image compiles Java ahead of time into a standalone binary. Whether those JNI layers survive the compilation is an open question — no one has published a working configuration for either library in native image.

This is a hard gate. If it fails, the Quarkus integration module ships JVM-only and Hortora's distributable native binary goal is deferred.

## What I believed going in: the config would be the hard part, the code would be trivial

I expected to spend most of the time iterating on GraalVM configuration — `--initialize-at-run-time` entries, reflection registrations, resource bundling. The actual JNI bridge code (load tokenizer, create session, run inference) is maybe 100 lines across three classes. The risk was in the native image compilation, not the Java.

That was half right.

## Three failures before the gate passed

The bridge code compiled to native image on the first attempt — 37MB binary, built in 51 seconds. Quarkus started in 0.018 seconds. Then silence. No output. No error. No crash. Just a process sitting there doing nothing.

I spent two hours assuming it was a JNI library loading hang. It wasn't. Claude and I traced it to `@QuarkusMain(name = "native-gate")` — the `name` parameter prevents Quarkus from discovering the command-mode entry point in native image. No warning, no diagnostic. Remove the name, and `run()` gets called. This is the kind of failure that burns time precisely because the symptom points in the wrong direction.

With `run()` actually executing, the DJL tokenizer loaded and then the Rust code panicked: `called Result::unwrap() on Err(JavaException)`. Another opaque error. The Rust tokenizer calls back into Java via JNI to create `CharSpan` objects — character offset spans. Without explicit JNI registration for `CharSpan`, the callback fails, and the Rust side panics with no indication of what Java class is missing. The GraalVM tracing agent revealed this — running the app in JVM mode with `-agentlib:native-image-agent` captured every JNI crossing, including the Rust→Java callbacks that are invisible in Java source.

The third failure was ONNX Runtime: `UnsatisfiedLinkError` on `OrtEnvironment.createHandle()`. I had `--initialize-at-run-time=ai.onnxruntime.OnnxRuntime` — the class that loads the native library. But ONNX Runtime's initialization spans multiple classes in the package. `OrtEnvironment` was initializing before `OnnxRuntime` had loaded the library. Package-level deferral (`--initialize-at-run-time=ai.onnxruntime`) fixed it.

## The gate passed

```
PASS: DJL Tokenizer JNI loaded and executed
Model inputs: [input_ids, attention_mask]
Model outputs: [logits]
PASS: ONNX Runtime JNI loaded and session created
Logits: contradiction=4.9234, entailment=-3.5457, neutral=-1.6523
PASS: End-to-end inference completed
```

All three phases pass in native image. The logits are identical to JVM mode. Quarkus starts in under 20 milliseconds.

## What this means for the roadmap

The native image gate was the highest-risk item on the ONNX inference track. With it passed:
- C5 (inference-quarkus) can target native image — the GraalVM config files and `NATIVE-IMAGE-NOTES.md` feed directly into the Quarkus extension
- Hortora's distributable native binary is feasible
- The JNI bridge code in inference-runtime is the real starting point for C3 (SPI + runtime) — no throwaway prototype to discard

The code lives in the production modules. If the gate had failed, the same JVM-mode code would still feed C3 — nothing was wasted.
