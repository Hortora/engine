---
layout: post
title: "When Flat Features Aren't Enough"
date: 2026-07-10
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, qdrant, structured-data]
---

# CaseHub Neocortex — When Flat Features Aren't Enough

**Date:** 2026-07-10
**Type:** phase-update

---

## What I was trying to achieve: structured data in CBR case features

CBR cases in neocortex store features as `Map<String, Object>`, but everything downstream — the scorer, the Qdrant payload builder, the query translator — only handles strings and numbers. A game case with phases like `["EARLY_AGGRESSION", "MID_SKIRMISH"]` or key moments like `[{type: "FIRST_CONTACT", minute: 3.2}]` gets silently truncated when it hits Qdrant. The data goes into the JSON blob but never becomes filterable.

The goal: make structured values first-class in the SPI so Qdrant's native nested payload capabilities actually get exposed through the Java API.

## The design decision that shaped everything

The first question was whether structured fields should participate in weighted similarity scoring. Lists and nested objects could theoretically use Jaccard similarity, recursive scoring, or custom functions. I decided against it — graded similarity over structured data is domain-specific and poorly generalised. What does "0.7 similar" mean for two lists of game phases? It depends entirely on the domain.

Instead, structured fields are **filter-only**. They narrow the candidate set via hard pre-filters but contribute nothing to the similarity score. This keeps the scoring model clean — flat Categorical/Numeric/Text fields score, everything else filters. Callers who need custom scoring still have the `LocalSimilarityFunction` override mechanism.

The second decision followed naturally: if structured fields are filter-only, they don't belong in `CbrQuery.features` alongside scored fields. A new `Map<String, CbrFilter> filters` field gives the query unambiguous separation — features score, filters narrow. `CbrFilter` is a sealed interface with `Contains`, `ContainsAll`, `ContainsAny`, and `HasMatch` variants, each mapping directly to a Qdrant condition.

## What the design review caught

We ran an adversarial review — five rounds, nineteen issues. The review caught several things I wouldn't have found through testing alone.

The inner field validation was using a blacklist: reject `CategoricalList`, `NestedObject`, `ObjectList`. Claude pointed out that if someone adds a fourth structured variant later, they'd need to remember to update the blacklist. A whitelist — match `Categorical`, `Numeric`, `Text` and reject everything else — is future-proof by construction.

The review also caught that `HasMatch` sub-field names weren't validated against the inner schema. A query like `HasMatch(Map.of("nonexistent", "val"))` would pass construction-time validation (it's a valid String value) but silently produce empty results at query time — Qdrant queries a payload path with no data and no index. Adding sub-field name validation against the inner schema turns a silent empty-result bug into an immediate `IllegalArgumentException`.

## What it looks like now

Three new `FeatureField` sealed variants — `CategoricalList`, `NestedObject`, `ObjectList` — with one-level nesting enforced at schema declaration time. `CbrFeatureValidator` consolidates store-time and query-time validation that was previously duplicated between the in-memory and Qdrant backends. The Qdrant backend translates `Contains` to `matchKeyword` (Qdrant auto-handles array containment on keyword-indexed fields), `ContainsAll` to multiple must conditions, and `HasMatch` on object lists to `ConditionFactory.nested()`.

The contract test suite covers all filter types and validation paths — the same tests run against both the in-memory backend and the Qdrant integration backend, so the semantics are guaranteed identical.

This is the foundation for #91 (temporal case representation) and #92 (sequence similarity). Both need structured fields — temporal segments are naturally `ObjectList` with time-stamped sub-fields. The one-level nesting constraint might need revisiting for deeply nested temporal data, but for now it covers every concrete use case.
