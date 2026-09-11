---
layout: post
title: "Why Records Can't Carry Behaviour"
date: 2026-07-07
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, java-records, sealed-interfaces, similarity]
series: issue-107-categorical-similarity-tables
---

The original design was straightforward — attach a `LocalSimilarityFunction` to each `FeatureField` and let the scorer call it. `LocalSimilarityFunction` is a `@FunctionalInterface`, already used for per-field overrides via the caller's `Map<String, LocalSimilarityFunction>`. Extending it to schema-level declarations seemed natural.

It's wrong, and the reason is subtle enough to be worth documenting.

`FeatureField` variants are Java records. Records derive `equals()` and `hashCode()` from all their components. A `LocalSimilarityFunction` is a lambda — and lambdas compare by identity, not logical equivalence. Two schemas built identically in different places would fail equality checks. Value semantics, which records are supposed to guarantee, silently break.

The fix was `SimilaritySpec` — a sealed interface with record variants. `CategoricalTable`, `GaussianDecay`, `StepDecay`, `ExponentialDecay`. Pure data, no behaviour. The schema declares *what kind* of similarity to compute (Gaussian with σ=0.3), and the scorer resolves that declaration to computation at scoring time. The distinction between declaring intent and carrying behaviour matters more than it initially looks.

This gives a clean three-level precedence chain. Caller overrides (level 1) handle runtime concerns — `EmbeddingTextSimilarity` needs a live `EmbeddingModel` and an embedding cache, which can't be expressed as data. Schema-attached specs (level 2) handle domain configuration that travels with the field definition. Type defaults (level 3) handle the common case — linear decay for numerics, exact match for categoricals.

The design also surfaced a second decision: sealing `FeatureField` itself. The three variants (`Categorical`, `Numeric`, `Text`) are the only ones that exist, and making that explicit means every dispatch site — the scorer, the Qdrant query translator, the collection manager — uses exhaustive `switch` instead of `instanceof` chains. Adding a fourth variant forces the compiler to flag every site that needs updating.

One detail I hadn't thought through until the scorer was actually written: `NumericRange` handling. The original scorer had `NumericRange` logic baked into `numericSimilarity()`. With multiple decay functions, that logic would need duplicating in each one. Extracting `computeNormalizedDistance()` — which centralises the range-inside/range-outside logic and returns a normalised distance — means every decay function receives a single `double` and applies its curve. The Gaussian, step, and exponential implementations are each one line.

The categorical table turned out simpler than expected. Symmetric mirroring in the constructor, self-pairs handled by the scorer (always 1.0 before table lookup), unlisted pairs default to 0.0. A builder for ergonomic construction. The interesting part isn't the table itself — it's that `CategoricalTable` is just another `SimilaritySpec` variant. The same sealed-interface mechanism that dispatches numeric decay functions dispatches categorical table lookups. One pattern, two problems solved.

`Text` fields deliberately don't carry `SimilaritySpec`. The `semantic` flag already works through caller overrides — the Qdrant store detects `semantic=true` and wires up `EmbeddingTextSimilarity` at runtime. Embedding similarity requires a live model, batch precomputation, and a cache. That's runtime behaviour, not schema configuration. The boundary between levels 1 and 2 turns out to be clean: if it needs runtime state, it's a caller override. If it's pure domain configuration, it's a spec.
