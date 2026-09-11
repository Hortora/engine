---
layout: post
title: "The Gate That Didn't Need Opening"
date: 2026-06-24
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [native-image, deployment, architecture]
---

The native image gate passed weeks ago. C2 proved both JNI layers — ONNX Runtime and HuggingFace Tokenizers — work in Quarkus native image on macOS ARM. Reachability metadata shipped in `inference-quarkus`. The gate was green.

I never asked the obvious question: should I walk through it?

The inference service starts once and runs for days. Native image buys fast startup — seconds instead of tens of seconds. For a service that amortises startup over millions of requests, that's nothing. Worse, HotSpot's JIT compiler optimises hot paths over time. AOT can't. For sustained workloads, JVM mode is strictly better.

The JNI maintenance cost sealed it. Two native libraries need reachability metadata, `--initialize-at-run-time` entries, and native library extraction handling. Every ONNX Runtime or Tokenizers version bump risks breakage. That's ongoing engineering cost for zero runtime benefit.

So the decision: `inference-*` modules stay JVM. The reachability metadata stays in `inference-quarkus` for downstream consumers that actually need native — like Hortora's future CLI client, where startup time is the whole point.

The cross-repo cleanup took longer than the decision. neural-text CLAUDE.md, eight edits to ARC42STORIES.MD, a parent repo issue (parent#306), and a Hortora engine issue (hortora/engine#20) to drop their native image CI job — same reasoning applies to their search service. The stale scan also caught J4 still showing pending even though both CRAG and HyDE shipped.

The pattern is worth naming: a gate that validates capability is not a commitment to use that capability. C2 answered "can we?" — but nobody asked "should we?" until today.
