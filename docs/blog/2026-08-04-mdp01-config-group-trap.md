---
layout: post
title: "The Config Group Trap"
date: 2026-08-04
type: phase-update
entry_type: note
subtype: diary
projects: [hortora]
tags: [quarkus, smallrye-config, testing]
---

The engine's `gardenReindex` MCP tool has always worked — delete the Qdrant collection, reset the cursor, let the next ingestion cycle re-embed everything. Grove Phase 2 needs the same thing as a REST endpoint so the dashboard's "Trigger Reindex" button has something to hit.

The extraction was textbook: pull the logic into `GardenReindexService`, wire both the MCP tool and a new `POST /api/garden/reindex` endpoint to it. The endpoint returns `{"status":"ok","message":"..."}` on success, `{"status":"error","message":"..."}` on failure. Twenty minutes of work.

Then the tests broke — all of them, not just mine.

The `InMemoryEmbeddingIngestor` needs `max-sequence-length` from the inference model config. That property wasn't in the test `application.properties`, and it had never been needed before. Something in a recent dependency update made it required. Adding it seemed like the obvious fix.

It wasn't. SmallRye Config treats `@ConfigMapping` map entries as atomic groups. Setting `max-sequence-length` alone triggered validation of every required field in the group — `model-path`, `tokenizer-path`, the lot. The config either exists completely or not at all; there's no partial population.

So we provided full stub values globally: `model-path=stub`, `tokenizer-path=stub`, `max-sequence-length=768`. That fixed 195 tests. But it broke `HybridSearchProducerAbsentTest` — a test that verifies `MultiModalEmbedder` *isn't* resolvable when models aren't configured. The `@LookupIfProperty` annotation uses regex `.+`, which matches any non-empty string. With `model-path=stub` in the global config, the bean resolves. Empty string fails config validation. There's no value that satisfies SmallRye Config but *doesn't* match `.+`.

The fix was to step outside the `@QuarkusTest` framework entirely. We converted the absent test to a plain unit test that checks the `@LookupIfProperty` annotation is present with the right parameters via reflection. Same contract verification, no Quarkus boot, no config gymnastics.

196 tests pass now. They didn't before this branch — the config gap was pre-existing, just waiting for someone to trip over it.

The SmallRye Config group validation interaction is the kind of thing that catches you once per project. It's in the garden now as GE-20260804-6076a3, so it shouldn't catch anyone twice.
