---
layout: post
title: "The Missing Phase"
date: 2026-07-14
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, plan-adaptation, spi]
---

CBR has four phases — Retain, Retrieve, Reuse, Revise — and until now neocortex implemented three of them. Cases were stored, retrieved with similarity scoring, and their outcomes fed back via EMA confidence. But the retrieved plans passed straight through to routing strategies unchanged. There was no Reuse.

## What Reuse Actually Means

The gap was visible in `CbrAgentRoutingStrategy.analyseExperiences()`. That method was doing primitive plan adaptation inline — filtering steps by current capability, checking worker eligibility, computing weighted success rates. It worked, but the logic was hardcoded, closed to extension, and invisible to any consumer outside the engine.

Clinical needs different adaptation for AE escalation plans. The engine's routing strategy needs worker substitution based on trust scores. Both are "take a past plan, adapt it to the current situation" — but neither can share the other's logic when it's buried inside a routing strategy.

## The SPI Shape

`PlanAdapter` is a single-method interface in `memory-api`:

```java
public interface PlanAdapter {
    AdaptedPlan adapt(ScoredCbrCase<PlanCbrCase> retrieved,
                      Map<String, FeatureValue> currentFeatures);
}
```

The context parameter is the current case's feature map — the same data that drove retrieval. Engine-specific context (available workers, trust scores) is injected by the implementation via CDI, not threaded through the SPI. This keeps the interface in Tier 1 with zero engine dependencies.

Each adapted step carries an `AdaptationAction` — `RETAINED`, `SUBSTITUTED`, `BOOSTED`, `SUPPRESSED`, `ADDED`, or `REMOVED` — plus a reason string. The action enum is the key design choice: it makes adaptation auditable without a separate trace structure, and it lets downstream mapping interpret `stepOutcome` correctly. A `SUBSTITUTED` step's original outcome doesn't apply to the new worker — the mapping nulls it out, treating the substitution as "no evidence."

## The Design Review Catch

The adversarial review caught something I'd missed: `AdaptedStep` originally didn't carry `stepOutcome`. That field is what `CbrAgentRoutingStrategy.analyseExperiences()` uses to compute weighted success rates — `RoutingOutcome.valueOf(step.stepOutcome())`. Without it, adapted plans would silently break the routing signal. The fix was straightforward once the reviewer pointed at the exact call site, but I wouldn't have caught it from the type signatures alone.

The review also pushed back on `@FunctionalInterface`. My instinct was to mark it — single abstract method, clean lambda semantics. But `PlanAdapter` is a domain SPI, not a math function. It will likely grow lifecycle methods as the platform evolves, and locking it to SAM now creates a needless breaking change later.

## Where It Sits

The adapter is deliberately NOT a `@Decorator` on `CbrCaseMemoryStore`. Three reasons: the decorator chain doesn't know the case type (would require downcasting), adaptation is a domain operation rather than a storage concern, and the adapter needs the current case's features which aren't in the `retrieveSimilar()` signature.

Instead, the consumer calls it between retrieval and consumption — a `NoOpPlanAdapter` `@DefaultBean` ensures existing behavior is unchanged until someone provides a real implementation. The `TrackingPlanAdapter` decorator fires `CbrAdaptationRecorded` CDI events for observability, matching the retrieval tracking pattern.

The engine-side integration (casehubio/engine#727) wires `PlanAdapter` into `CbrRetrievalService` and refactors `analyseExperiences()` to consume adapted steps instead of doing its own inline analysis. That's where the real adaptation logic lives — neocortex provides the contract, the engine provides the intelligence.
