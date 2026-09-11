---
layout: post
title: "Filling in the CBR gaps"
date: 2026-07-12
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, memory-api, sealed-interfaces]
---

Five small issues had been sitting in the backlog since the structured fields (#89) and sequence similarity (#92) work landed. Each was a gap noticed during implementation and deferred — not because it was hard, but because it wasn't needed yet. The kind of work that compounds if you leave it too long: every new feature built on top has to work around the missing pieces.

## The sealed interface tax

All five changes root in `memory-api`'s sealed interface hierarchy — `FeatureField`, `CbrFilter`, `SimilaritySpec`. Adding a variant to any of these means updating every exhaustive switch in the codebase. That's the trade-off of sealed interfaces: the compiler enforces completeness, which is exactly what you want for a type hierarchy where missing a case is a bug. But it means even a small addition touches a lot of files.

The design review caught several switch sites I'd missed in the spec — `CbrCollectionManager.registerSchemaIndexes()`, `QdrantCbrCaseMemoryStore.buildTextOverrides()`, two separate switches in `CbrQueryTranslator`. Nine issues raised across two rounds, all legitimate. The most interesting was the Itakura infeasibility problem.

## Itakura's edge case

The Itakura parallelogram constrains DTW warping path slope rather than absolute deviation from the diagonal. Unlike Sakoe-Chiba — where `w = max(windowSize, |n-m|)` guarantees the endpoints are always reachable — Itakura can produce infeasible constraints. With `maxSlope=1.5` on sequences of length 4 and 3, the continuous ratio (1.33) passes the constraint, but discrete ceil/floor rounding at row 2 produces `jStart=2 > jEnd=1`. No valid path exists.

The original spec had a pre-check formula. Claude's design reviewer found a counterexample that broke it — the continuous-domain check passes but the discrete DP fails. The fix: check `jStart > jEnd` inside the DP loop and short-circuit on the first empty row. Return `DtwResult(0.0, List.of())` — zero score, empty alignment. This distinguishes infeasible constraints from very dissimilar sequences.

## Variable edit distance costs

The normalization was the tricky part. With unit costs, `max(n, m)` is the correct denominator. With asymmetric insert/delete costs, the maximum possible edit distance depends on whether substitution (cost ≤ 1.0) is cheaper than delete-plus-insert. When `effDel + effIns >= 1.0`, the DP prefers substitution and the max distance is `min(n,m) + remainder * cost`. When the sum is less than 1.0, the DP prefers delete-plus-insert for every mismatch, and the max is `n * effDel + m * effIns`. Both paths reduce to `max(n, m)` with unit costs.

## AllOf and polarity

`AllOf` wraps multiple filters on the same field — the `Map<String, CbrFilter>` structure only allows one filter per field name. The design review caught a polarity bug in the spec: "add all conditions to `must`" is wrong when `AllOf` wraps a `NotContains`. Positive filters route to `must`, negation filters to `must_not`. The fix required extracting per-filter dispatch into a helper method so `AllOf` can delegate recursively while preserving each inner filter's polarity.

The `DtwSpec` migration from `Integer windowSize` to `WarpingConstraint` was the right call — `null` as "unconstrained" violated the pattern every other sealed interface in this codebase follows. `Unconstrained()` is explicit, exhaustive, and makes the switch expressions cleaner.
