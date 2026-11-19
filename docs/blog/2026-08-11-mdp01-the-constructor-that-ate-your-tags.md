---
layout: post
title: "The constructor that ate your tags"
date: 2026-08-11
entry_type: note
subtype: diary
projects: [engine]
tags: [qdrant, metadata, silent-failure, neocortex-rag]
---

Grove's tag analytics were returning empty — no co-occurrence data, no orphan detection, nothing. The engine extracts tags from garden entry frontmatter during indexing, so the question was where in the pipeline they disappear.

`GardenMetadataExtractor` does the right thing. It parses the YAML `tags` array into `ExtractionResult.listMetadata()` and also writes a pipe-separated `tags_joined` string to the regular metadata map as a fallback. Both maps leave the extractor correctly populated.

`QdrantPointBuilder` also does the right thing. It iterates both `metadata` and `listMetadata` on the `ChunkInput` it receives, writing list entries as proper Qdrant array values via `ValueFactory.list()`. The Qdrant payload index for `tags` already exists, typed as keyword.

The gap was in the middle. `CorpusIngestionService.chunkDocument()` — the method that bridges extraction to ingestion — was creating `ChunkInput` with the three-argument constructor. That constructor sets `listMetadata = Map.of()`. The four-argument version carries it through. One constructor compiles identically to the other. No warning, no error, no type mismatch. The list metadata just vanishes.

What made this invisible for months: the `tags_joined` string fallback flowed through `metadata` (the string map) without issue. `SearchResult.tags()` reconstructs the list by splitting on `|`, so the REST API always returned correct tags. Only consumers reading the Qdrant payload directly — which is exactly what Grove does — saw empty results.

The fix already existed. Commit `6e0b815` in neocortex-rag had landed the same day, switching both code paths in `chunkDocument()` to the four-argument constructor. The engine was running against a stale SNAPSHOT JAR from the day before. A local `mvn install` of neocortex and a reindex is all that's needed.

On the engine side, I added a small completeness fix: `GardenMetadataExtractor` now always emits `tags` in `listMetadata`, even as an empty list when no frontmatter tags are present. The acceptance criteria for #87 specified that entries without tags should have `tags: []` in the payload, not an absent field.

While investigating, I checked how many garden entries actually lacked tags. 159 out of 2733 — mostly early GE-0xxx entries from before the tagging convention was established. A keyword-matching script tagged 155 of them from title and domain signals. Four protocol entries with no domain field remain untagged. The corpus is now at 99.85% tag coverage, which means the reindex will populate Grove's analytics with real data across the full garden.

The deeper lesson: constructor overloads that silently discard data are a trap. The three-argument `ChunkInput` constructor looks correct at every call site. The API surface gives no signal that you're losing information. String fallbacks mask the loss at the integration layer. The only consumer that exposes the bug is one that bypasses the fallback — and that consumer might not exist when the code is written.
