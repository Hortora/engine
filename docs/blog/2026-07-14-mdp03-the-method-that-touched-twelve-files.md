---
layout: post
title: "The Method That Touched Twelve Files"
date: 2026-07-14
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, spi-design, java]
---

# The Method That Touched Twelve Files

Four small issues batched onto one branch: a missing Boolean case in `FeatureValue.of()`, a retention purge API, temporal recency decay, and a decorator chain integration test. The kind of session where each item is thirty minutes of work and the interesting part is the connective tissue.

## The Boolean That Fell Through

`FeatureValue.of(Object)` is the dynamic converter — takes raw Java objects from CDI event payloads and maps them to the typed `FeatureValue` sealed hierarchy. It handled `String`, `Number`, `Map`, `List<String>`, `List<Number>`, `List<Map>`. Not `Boolean`.

A `Boolean` hit the final `throw new IllegalArgumentException("unsupported feature value type")` and the CDI handler silently dropped the case. No log, no warning — the observer caught the exception and moved on. The fix is one line: map `Boolean` to `StringVal("true"/"false")`, which is the natural CBR categorical representation. But `List<Boolean>` also needed handling — the list dispatcher checks `first instanceof String`, `first instanceof Number`, `first instanceof Map`, and a list of booleans fell through the same gap.

## Adding One Method to a Sealed SPI

`purge(CbrRetentionPolicy)` on `CbrCaseMemoryStore` is a straightforward API — tenant/domain scoped, age-based and count-based criteria, at-least-one-required validation. The implementation in each store is 15–30 lines.

The ripple is the interesting part. Adding one method to the blocking SPI means adding it to the reactive SPI too (parity test enforces this). Then: three decorators on the blocking side (tracking, reranking, outcome-weighting), three on the reactive side, the no-op default bean, the blocking-to-reactive bridge, the in-memory store, the JPA store, the Qdrant store. Twelve files for one method signature.

Every decorator is a pure pass-through — `return delegate.purge(policy)`. The in-memory store filters a `CopyOnWriteArrayList`. The JPA store runs bulk JPQL DELETEs. The Qdrant store scrolls points, copies the protobuf list to an `ArrayList` (the original is unmodifiable — discovered that at runtime, not compile time), sorts by `_stored_at`, and deletes the excess by point ID.

That last detail became a garden entry. Qdrant's `scrollResult.getResultList()` returns a protobuf-generated `UnmodifiableList` that looks like a regular `List<RetrievedPoint>` at compile time. Call `sort()` and you get `UnsupportedOperationException` with no prior warning. Always `new ArrayList<>(scrollResult.getResultList())` before mutating.

## Temporal Decay as a Post-Scoring Concern

`TemporalDecay` is a sealed interface with one variant: `HalfLife(Duration)`. A 30-day-old case with a 30-day half-life contributes 50% of its feature similarity score. 60 days → 25%. The formula is `0.5^(age/halfLife)` — smooth exponential, no hard cutoffs.

The design decision was where to apply it. The spec from IoT said "after scoring, before topK and minSimilarity" — which means the store applies decay, not the scorer. `CbrSimilarityScorer` stays pure feature similarity. The store multiplies by the decay factor and then filters. This keeps the scorer testable without time dependencies and means decay interacts correctly with `minSimilarity` — a stale case with a high feature score can drop below the threshold.

The field on `CbrQuery` is nullable. `null` means no decay, backward compatible. Every `with*()` builder method and the raw constructor needed updating — 8 call sites across the codebase that use the 13-arg constructor directly (now 14-arg).

The Qdrant store doesn't apply decay yet. Its three retrieval paths each assemble scores differently, and wiring `_stored_at` through all of them for a feature that IoT runs on the in-memory store didn't justify the complexity. `notBefore` hard cutoff already handles the Qdrant case for now.
