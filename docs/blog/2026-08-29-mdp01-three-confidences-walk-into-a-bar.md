---
layout: post
title: "Three Confidences Walk Into a Bar"
date: 2026-08-29
entry_type: note
subtype: diary
projects: [casehubio/neocortex]
tags: [cognitive-api, confidence, mindmap, memory, architecture]
series: issue-253-cognitive-rearchitecture
---

# Three Confidences Walk Into a Bar

When MindMap says `confidence: 0.7`, it means "INFERRED, initial value from LLM extraction, decaying since confirmedAt." When Memory says `importance: 0.7`, it means "retrieval weight, no provenance, no decay." When CBR says `confidence: 0.7`, it means "EMA-adjusted from outcome feedback, no decay anchor." Three numbers that look identical but mean entirely different things.

The unified `Confidence(origin, value, decayReference)` record collapses this into a single type shared across all three stores. The interesting decision wasn't the record shape — that's obvious once you see the problem. It was what to do with `confirmedAt`.

MindMap nodes had a `confirmedAt` timestamp that served double duty: decay anchor for exponential confidence decay, and a trigger for resetting confidence to 1.0 when a node was "confirmed." That implicit reset is the problem. A SPECULATED node at 0.3 that gets confirmed shouldn't silently jump to 1.0 — that erases the epistemic distinction between speculated and stated knowledge. Confirming means "I've re-verified this is still believed," not "I've upgraded my certainty to maximum."

Under the new model, `decayReference` lives on the `Confidence` record itself. Confirmation resets the decay clock — value and origin stay as they are. If you want 1.0, you construct it explicitly.

The migration touched every layer of both the MindMap and Memory subsystems — interfaces, records, stores, decorators, analysers, SQL backends, intelligence extractors. `ConfidenceOrigin` moved from mindmap-api to a new tier-0 `cognitive-api` module (zero dependencies), gained `UNKNOWN` for Memory/CBR contexts where provenance was never tracked, and lost `initialConfidence()` — those default values are MindMap extraction policy, not cognitive axioms.

What's left: CBR types still carry `Double confidence`. That's the next batch — `CbrCase`, `CbrOutcome.adjustConfidence`, and the Qdrant backend's payload serialisation. Then documentation sync and #229 closes.
