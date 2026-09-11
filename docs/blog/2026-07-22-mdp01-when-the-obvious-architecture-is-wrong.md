---
layout: post
title: "When the obvious architecture is wrong"
date: 2026-07-22
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, ensemble, plan-adaptation, architecture]
---

# When the obvious architecture is wrong

Single-plan adaptation has been in place since Phase 5 — `PlanAdapter` takes one retrieved case, transforms it for the current context, returns an adapted plan. Straightforward. When the time came to add cross-plan analysis (#148), the natural question was: how should the ensemble relate to the per-plan adapter?

My first instinct was replacement. If you're going to examine all plans together and synthesise one ensemble plan, why bother adapting each plan individually first? The ensemble subsumes single-plan adaptation — when N=1, it degenerates to the same thing. One SPI instead of two. Simpler.

Wrong.

The CBR literature is unambiguous on this. The ABARC model (Manzano, Ontañón, Plaza — ICCBR 2011) divides reuse into *individual reuse* first, then *multiagent reuse*. Workflow streams (Müller & Bergmann, ICCBR 2014) decompose cases into subcomponents and recombine them — per-case processing before cross-case combination. Compositional adaptation (Sizov, Öztürk, Marsi — ICCBR 2016) found that "use of only one case often yields an incomplete explanation" — you need multiple cases, but you need them individually adapted first.

The reason is straightforward once you see it: cross-plan analysis works better on adapted plans because the adapted plans already account for context differences. If you skip per-plan adaptation and analyse raw plans, consensus signals are polluted by steps that should have been context-adapted away. Four plans all using Worker-X looks like consensus — unless per-plan adaptation would have substituted Worker-Y because Worker-X is unavailable in the current context. The raw consensus is noise; the adapted consensus is signal.

So the architecture is two-stage, caller-orchestrated: per-plan `PlanAdapter.adapt()` runs at full quality, independently. Then `PlanEnsembleAnalyzer.analyze()` examines the adapted results for consensus, divergence, and quality patterns, and synthesises an ensemble plan. Both outputs serve the engine: per-case `RetrievedExperience` for context injection (individual past cases for rules and AI to reason about), `EnsemblePlan` for routing (the strongest combined signal).

The design review surfaced a subtler architectural principle worth noting. The `NoOpPlanEnsembleAnalyzer` — the @DefaultBean that runs when no real ensemble is on the classpath — originally reported `totalPlans=5` when it had only examined one plan. Technically accurate (five plans *were available*), but semantically dishonest. A consumer seeing `{occurrenceCount: 1, totalPlans: 5, agreement: UNIQUE}` reads "thorough analysis found weak consensus." The honest version — `{totalPlans: 1, agreement: UNANIMOUS}` — correctly conveys "limited analysis, full consensus within examined scope." The difference matters in clinical safety plans where consensus ratios drive audit decisions.

The SPI itself follows the established PlanAdapter pattern exactly: interface in memory-api, NoOp @DefaultBean in memory, tracking @Decorator in memory-cbr-tracking, contract tests in memory-testing. `EnsemblePlan` carries the synthesised plan plus `StepConsensus` analysis with per-step worker, outcome, and priority distributions, classified by `StepAgreement` (UNANIMOUS through UNIQUE). Real implementations — engine routing, clinical safety — provide their own domain-specific ensemble logic via CDI.
