---
layout: post
title: "Federation: teaching the engine to ask upstream"
date: 2026-06-12
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [federation, retrieval, quarkus]
---

## Federation: teaching the engine to ask upstream

The engine has served a single garden since day one. One Qdrant, one corpus, one search path. That's fine when all your knowledge lives in one place. It stops being fine when gardens have parents.

The Hortora model has three garden relationships: canonical (root authority), child (extends a parent), and peer (equals sharing knowledge). A child garden searches its own Qdrant first — and when results are weak, walks upstream to its parent via HTTP. If the parent also has a parent, the walk continues. Peers are queried in parallel for supplementary results. The whole thing is transparent to the caller: you search `/search?q=hibernate+flush`, and the engine figures out whether to ask upstream.

The design question that consumed most of the time wasn't the algorithm — it was what federation means for provenance. When my garden calls its parent, and the parent calls the grandparent, and results flow back through the chain, who does each result belong to? I wanted absolute identity: every result carries the garden ID of wherever it originated, and that value passes through the chain unchanged. No relative labels like "own" in the data — `[own]` is a display-only label the MCP tool applies when the source matches the local garden.

This took five spec revisions to get right. The first draft proposed a generic `Document` model, multi-source local retrieval, and a schema-driven field declaration engine — four features pretending to be one. Claude pushed back hard on scope, and rightly so. By revision three, the spec was focused on the chain walk alone. The remaining iterations worked out CDI wiring (records can't be `@ApplicationScoped` — they're final, CDI can't proxy them), the merge algorithm (tier-grouped with relevance sorting within each tier), and where cycle detection belongs (in `SearchResource` before the Qdrant search, not in `ChainWalker`).

One thing I didn't anticipate: a JAX-RS client interface with `@Path("/search")` at the class level gets silently registered by Quarkus as a server resource, shadowing the real endpoint. Every test broke — blank queries returned 200 instead of 400, domain filters returned empty results. Nothing in the logs hinted at the cause. The fix is to make the client interface package-private. The symptom was completely disconnected from the root cause — a pattern worth remembering.

The implementation landed as four new classes in `io.hortora.garden.federation`: `FederationConfig` (the record, produced via `@Singleton` not `@ApplicationScoped`), `FederationConfigParser` (observes `StartupEvent` for fail-fast validation), `ChainWalker` (owns the full merge — upstream walk, peer fan-out via `ManagedExecutor`, dedup, tier-grouped ordering), and `RemoteGardenClient` (programmatic REST client instances per upstream URL). `SearchResult` gained `id`, `source`, and `sourcePrefix` fields. The config comes from a `federation:` block in SCHEMA.md — when absent, the engine behaves identically to before.

The provenance model is the part I'm most satisfied with. Tier ordering uses the structural position of the HTTP call (which upstream produced the result), not the `source` field. This means results relayed through an intermediate garden keep their original provenance while still sorting correctly. A result from the grandparent that was relayed by the parent stays labelled as grandparent but sorts at parent tier — because from the originating garden's perspective, it came from one HTTP call to the parent.

What federation opens up is the ability to run a private garden that extends a public canonical one. My garden searches locally first, walks to the community JVM garden when it doesn't have the answer, and the caller sees merged results with provenance. The canonical garden never knows the child exists — it just serves its `/search` endpoint.
