---
layout: post
title: "Five Lines, Ten Issues, One Fix"
date: 2026-07-04
type: phase-update
entry_type: note
subtype: diary
projects: [engine]
tags: [federation, design-review, qdrant]
---

# Five Lines, Ten Issues, One Fix

I started the session planning to knock out four small issues on one branch — the
kind of batch where you clear the backlog in an afternoon. Two S-scale, two XS. Four
items, one branch, no drama.

The four: convex combination fusion (#33), federation type/tags propagation (#30),
ColBERT sequence length validation (#37), and filtering `.git/` from corpus listing
(#38). The last two turned out to need cross-repo changes (inference-api and
neocortex-corpus respectively), so they got deferred. That left two.

Then the design review got interesting.

## The bug was obvious

Federation type/tags propagation is a classic missing-parameter bug. `SearchResource.doSearch()`
builds a `PayloadFilter` from domain, type, and tags for local queries. It passes all three
to `searchLocal()`. Then it calls `chainWalker.walk(query, domains, maxResults, ownResults,
visited)` — and drops type and tags on the floor. The remote `/search` endpoint already
accepts those parameters. The plumbing just wasn't connected.

Five lines of production code. Thread `type` and `tags` through `ChainWalker.walk()` and
`RemoteGardenClient.search()`. One-line change in `SearchResource` at the call site. Done.

## The review found the design, not the code

I ran an adversarial design review on the spec covering both #30 and #37. The reviewer
came back with ten issues across three rounds. Nine were verified fixes, one was accepted
as a justified design choice (discrete parameters over a filter object — the right call
for two strings and a REST interface).

The #30 findings were minor — the spec incorrectly described `doSearch()` as needing
signature changes when it already had type and tags parameters. Accurate spec, wrong
characterisation of the change site.

The #37 findings were structural. My original design injected `InferenceModelConfig`
into `CollectionMigration` to read `maxSequenceLength` and validate it against the
ColBERT dimension. The reviewer identified two problems:

First, iterating all configured models is architecturally unsound. A non-ColBERT model
with a legitimate `maxSequenceLength` of 8192 would fail the validation even though
it produces no multi-vectors. The config doesn't tell you which model backs the
`MultiModalEmbedder` — you'd need to guess.

Second, the injection itself creates cross-layer coupling. `CollectionMigration`
validates Qdrant schema. `InferenceModelConfig` is an inference concern. Pulling
inference config into schema validation ties two layers that should be independent.

The fix: add `maxSequenceLength()` to the `MultiModalEmbedder` interface itself.
The embedder knows its own maximum sequence length — it's an intrinsic property of
the model, not a configuration detail the caller should be scraping from somewhere
else. `CollectionMigration` already has the embedder; now it can ask directly.

The uncited Qdrant 1M float constant got the same treatment — replaced with a
configurable `casehub.rag.max-multivector-floats` in `RagConfig`, where Qdrant
schema properties already live.

Both corrections improved the design. Both expanded the scope to cross-repo. So #37
got deferred — the right design needs changes to `inference-api`, `inference-bge-m3`,
and `rag`.

## What landed

Branch `issue-33-s-xs-batch` closes #30. Federation now propagates domain, type, and
tags to all upstream and peer nodes. Five lines of production code, integration test
via WireMock, DESIGN.md updated.

The session's real output was the design review catching coupling I hadn't seen. The
five-line fix was always going to be five lines. Knowing that `maxSequenceLength`
belongs on the embedder interface — that's the part worth the $10 review.
