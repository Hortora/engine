---
layout: post
title: "The invisible payload"
date: 2026-08-07
entry_type: note
subtype: diary
projects: [engine]
tags: [jackson, java-records, search, api-design]
---

I'd been looking at #80 — promoting frontmatter fields into Qdrant payload so Grove could read them directly instead of parsing entry content at query time. Five fields: `staleness_threshold`, `tags`, `last_reviewed`, `author`, `verified_on`. The extractor already had all five wired up. Tests passed. I was about to mark it done.

Then I looked at `SearchResult`.

The metadata map — the one holding all those carefully extracted fields — was annotated `@JsonIgnore`. Every field was stored in Qdrant, available internally for scoring, and completely invisible in the REST API response. Grove would have continued parsing content strings, and the "done" issue would have been a lie.

This is the kind of gap that passes every test because the tests exercise internal behaviour, not the consumer contract. The extractor extracts correctly. The scorer scores correctly. The REST endpoint returns JSON that happens to be missing the fields the feature was supposed to expose. Nothing fails; nothing works.

The fix was straightforward: derived `@JsonProperty` methods on the record that read from the internal metadata map. No constructor changes, no call-site updates, zero ceremony. `stalenessThreshold()` returns `metadata.get("staleness_threshold")`, Jackson serialises it as a top-level field, and the internal map stays `@JsonIgnore`d.

Then Claude caught the federation NPE.

When Jackson deserialises a record from external JSON — which happens every time the engine receives upstream results during federation — it passes `null` for `@JsonIgnore`d components. The canonical constructor has no choice; records are immutable. My new methods called `.get()` on that null map, and the federation integration test blew up with a 500.

The misleading part: the NPE stack trace pointed to serialisation. The null was injected during deserialisation. If I'd only looked at the stack trace, I'd have been debugging the wrong operation. The real tell was that locally-constructed instances worked perfectly — the null only appeared on records reconstructed from external JSON, because the explicit constructors default to `Map.of()`.

Null-guarding the methods was a one-line fix per field. The interesting part isn't the fix — it's the shape of the failure. `@JsonIgnore` on a record component doesn't mean "skip this field." It means "inject null into an immutable slot and hope nobody touches it." Any derived method is a landmine until you guard it, and the explosion happens in a different operation than the one that planted it.

Two features shipped on this branch: #80's payload promotion and #83's version-distance scoring. The scoring was already complete from a previous session — temporal decay by staleness tier, BOM-relative version distance with topic weighting, search profiles for named BOMs. This session closed the gap that made #80's promise real: the fields exist in the response now, not just in the index.
