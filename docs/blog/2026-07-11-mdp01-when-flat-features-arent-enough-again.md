---
layout: post
title: "When Flat Features Aren't Enough (Again)"
date: 2026-07-11
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, temporal, dtw, edit-distance]
---

CBR cases in neocortex have been point-in-time snapshots since day one — flat features like race, MMR, and game phase. The structured fields work (#89) added nested objects and lists for richer case anatomy, but those are still positional: "what was the economy at minute 3?" is a different question from "how did the economy evolve across the game?"

Temporal sequences are the next step. A StarCraft game isn't just a bag of features — it's a trajectory: economy rising, army building, posture shifting from macro to aggressive. "Find games where the opponent's economy grew like this" requires comparing entire curves, not individual values.

The design fork was where temporal data should live in the type hierarchy. `CbrCase` subtypes would force a choice — feature-vector case *or* temporal case — which is a false dichotomy. A game case naturally has both flat features and temporal ones. So temporal sequences became new `FeatureField` variants: `TimeSeries` for multi-dimensional observations over time, `DiscreteSequence` for ordered categorical labels. Same schema, same validation, same scoring pipeline.

`TimeSeries` carries its own inner schema — each observation is a `Map<String, Object>` validated against declared inner fields, with a designated timestamp field for ordering enforcement. The timestamp field is excluded from DTW distance computation. That's the key insight from the design review: DTW's whole purpose is handling temporal misalignment. Including an explicit time dimension would double-count it, penalising the very warping the algorithm exists to perform.

DTW and edit distance are pure Java, O(n×m) — no external dependencies, no trained models. DTW normalises by `max(n, m)` for length independence, which favours partial matches: a 5-step query trajectory matches against the corresponding portion of a 50-step case without penalty for the unmatched tail. Edit distance uses standard Levenshtein with uniform costs. Both plug into `CbrSimilarityScorer` through the existing three-level precedence (caller override, SimilaritySpec, type default) and participate in weighted composite scoring alongside flat features.

The practical result: a query can now weight economy trajectory at 3.0 and race at 0.1, and the scorer ranks candidates by trajectory similarity with race as a tiebreaker. Mixed schemas — flat features, structured fields, and temporal sequences in the same case — work without special handling. The in-memory and Qdrant backends both pass 90 contract tests.

Scale is the known limitation. Client-side DTW across 1000 candidates with 50-step sequences is fast (~50ms), but it doesn't scale to 100k cases. Sequence embeddings — encoding trajectories into fixed-length vectors for server-side search — are the future path when that ceiling matters. The hybrid fusion infrastructure is already built for adding retrieval legs.
