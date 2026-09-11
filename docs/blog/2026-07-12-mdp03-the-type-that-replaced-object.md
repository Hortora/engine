---
title: "The Type That Replaced Object"
date: 2026-07-12
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, type-safety, sealed-interface, java, migration]
---

## The Type That Replaced Object

`Map<String, Object>` runs through the entire CBR feature system. Every case stores its features in one. Every query carries its search features in one. The scorer casts from Object on every comparison. The validator checks types at runtime with instanceof chains. None of this is visible to the compiler.

The fix is a sealed interface with seven variants — one per data shape the system actually uses. `StringVal` for categoricals and text. `NumberVal` for numerics. `RangeVal` for range queries. `StringListVal` for categorical lists and discrete sequences. `NumberListVal` for numeric lists. `StructVal` for nested objects. `StructListVal` for object lists and time series observations. Seven types, covering every value that ever flows through `features()`.

The interesting part is what the type doesn't encode. `StringListVal` serves both `CategoricalList` (a set, filter-only) and `DiscreteSequence` (ordered, edit-distance scoring). The schema tells you which one it is — the value just tells you the shape. The scorer dispatches on the schema's `FeatureField` type first, then pattern-matches the `FeatureValue` variant. Two levels of dispatch, zero casts.

The migration touched every file in `memory-api` — CbrCase, both record implementations, CbrQuery, CbrFilter's HasMatch, the validator, the scorer, DtwSimilarity, LocalSimilarityFunction. All of it had to change together because Java's type system doesn't let you partially swap a return type. The production code was straightforward — pattern matching on sealed interfaces is exactly what Java 21 was designed for. The test migration was the grind. Every `Map.of("race", "Terran")` becomes `Map.of("race", string("Terran"))`. Every `Map.of("economy", 30)` becomes `Map.of("economy", number(30))`. Hundreds of call sites across seven test files and a 1700-line contract test.

The branch also cleaned up four orphaned branches from cross-repo engine sessions that had committed to neocortex without closing properly. One of those — `issue-672` — added `featureSimilarities` to `ScoredCbrCase`, which we needed anyway. Another — `fix-ce-score-promotion` — fixed a real score incoherence bug where `relevanceScore` still held the vector similarity after cross-encoder reranking had reordered by a different signal. Both merged to main, all four branches stamped and closed, 37 diary entries published to both blog destinations.

The contract test migration is still in progress — the production code and six of seven memory-api test files compile clean with 470 tests passing. The Qdrant backend, embedding module, and DTW optimization tasks (#137) follow once the test migration completes.
