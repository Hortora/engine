---
layout: post
title: "The Feature That Was Already Half-Built"
date: 2026-07-14
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, temporal, trend-detection]
---

## The Feature That Was Already Half-Built

Issue #88 was filed six weeks ago as "CBR Phase 6 — temporal trajectory CBR." It described four deliverables: a new TemporalCbrCase type, trajectory similarity via DTW, trend detection primitives, and trajectory query extensions. When I opened it today, two of the four were already done.

TimeSeries and DiscreteSequence shipped as FeatureField types in the structured-fields work. DTW shipped with warping constraints, early abandonment, and LbKeogh pruning. The contract tests were already at 23 temporal-specific tests. The issue was describing infrastructure that had been built piecemeal across three earlier branches without anyone closing the loop.

The remaining question was sharper than the issue suggested: DTW tells you two trajectories have similar shapes. It doesn't tell you *what* the shapes mean. "Grade 1 → 3 in 48 hours" and "Grade 3 at presentation" might have the same final state, but clinically they're different signals entirely. The missing piece was characterisation — slope, acceleration, change-points — not comparison.

## Why TemporalCbrCase Is Wrong

The CbrCase hierarchy discriminates by reasoning paradigm. TextualCbrCase does text matching. FeatureVectorCbrCase does feature scoring. PlanCbrCase does plan adaptation. These are fundamentally different ways of using a case.

A TemporalCbrCase would discriminate by what kind of data one of its features contains. That's a category error. A clinical case doesn't become "temporal" because it has a vital-signs trajectory — it has temporal fields *and* static demographics *and* maybe a treatment plan. Forcing the choice at the type level would mean a case can't be both. The existing model already handles this: a FeatureVectorCbrCase with TimeSeries fields alongside flat Categorical and Numeric fields.

## Derived Numeric Features — The Insight That Simplified Everything

Trend metrics — slope, delta, volatility, acceleration, change-points — are just numbers. Once computed from a TimeSeries, they're Numeric feature values that participate in the existing weighted composite scoring. No new query types. No new filter types. No new store implementations. The entire existing CBR infrastructure handles them without modification.

The architecture has three layers:

**TrendSpec on TimeSeries** declares what to compute. The consumer says "compute SLOPE and DELTA for this field" at schema declaration time. That's the entire opt-in.

**TrendAnalyzer** does the computation. Seven O(n) algorithms in pure Java: least-squares regression for slope, Welford's for volatility, half-split finite difference for acceleration, CUSUM for change-point detection. No dependencies, no framework, testable in isolation.

**TrendEnrichmentDecorator** wires it into the CBR pipeline. At store time it computes trends and injects them into the case. At query time it does the same for the query features. The consumer never calls TrendAnalyzer directly — the decorator handles both sides symmetrically.

The design review caught a real asymmetry in the original spec: store-time enrichment was automatic but query-time was the consumer's responsibility. That would have silently produced zero matches whenever someone forgot to enrich their query features. The decorator now intercepts `retrieveSimilar` too.

## What This Actually Enables

A query like "find past cases where heart rate was escalating at a similar rate AND had a similar trajectory shape, prioritising rate match" now works with existing CBR infrastructure:

```java
query = CbrQuery.of(tenant, CBR, "clinical-ae", features, 10)
    .withWeight("vitals_slope_heart_rate", 3.0)
    .withWeight("vitals", 2.0);
```

The slope weight drives matching on escalation rate. The DTW weight drives matching on trajectory shape. Both contribute to the same weighted composite score. The clinical question #88 was filed to answer — "have we seen this pattern of rapid escalation before?" — is answerable without any new query API.
