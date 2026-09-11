---
layout: post
title: "Trailing Edges"
date: 2026-06-15
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [examples, langchain4j, dependency-management, cleanup]
---

# Trailing Edges

Every feature ships with a wake of small things. The examples project landed yesterday with 23 tests and an architecture justification doc, but the code review flagged four issues — none blocking, all worth fixing before moving on. I batched them onto a single branch and worked through the list.

## The version that doesn't exist

The most interesting one was #25. `ExampleModelProducer` was missing an `EmbeddingModel` bean — the CDI graph was incomplete under the `examples` profile, so `RagPipelineIT` would fail at container startup even though the in-memory alternatives would have taken over the actual injection points. CDI validates everything, not just what gets used.

The fix was simple: uncomment the `langchain4j-embeddings` dependency, add a producer that creates an `OnnxEmbeddingModel` from the downloaded model files. Except the dependency didn't resolve. Maven reported it as "absent" from Central, then fell through to GitHub Packages and returned 401 — which looks like an auth problem but isn't.

The `langchain4j-embeddings` artifact uses completely independent versioning from the main `langchain4j` library. Our parent POM declared it at `${langchain4j.version}` (1.14.1), but that version has never existed. Maven Central has 31 versions of this artifact: 0.17.0 through 0.36.2, then 1.0.0-alpha1 through 1.0.0-beta5. Nothing in the 1.x proper range. The module was originally in a separate repo, moved into the main langchain4j repo's `embeddings/` directory, but the published artifacts still follow the old version scheme.

The fix: a separate `langchain4j-embeddings.version` property set to `1.0.0-beta5`. The jar depends on `langchain4j-core:1.0.0` but is binary compatible with our 1.14.1 — the `EmbeddingModel` interface is stable within 1.x.

## Cleanup that changes an API

The other three issues were polish. NliClassifier smoke tests were using the convention constructor (indices 2,1,0) while the demos used explicit DeBERTa indices (0,1,2) — confusing for anyone comparing test to demo, even though the assertions were structural. `ScoringDemo.main()` only ran sentiment but had an unreachable `runToxicity()` method — added a print note rather than pretending the model is bundled.

The one I thought about was `CorpusTestSupport`. The old API — `copyCorpusToTempDir()` — created and returned a temp directory, leaking it because nobody cleaned up. I could have added `deleteOnExit()`, but that's a band-aid over a design problem: the utility owns a lifecycle it can't manage. Changed it to `copyCorpus(Path targetDir)` — callers pass their `@TempDir` and JUnit handles cleanup. Breaking the API is the right move; it makes every caller explicit about who owns the directory.

That same change let me extract the duplicated corpus file list. `CORPUS_FILES` was identical in `ZipCorpusIngestDemo` (src/main) and `CorpusTestSupport` (src/test) — adding or removing a corpus file meant updating both. Pulled it into `ExampleCorpus.FILES` in src/main where both can reference it.

## The protocol that was already done

Issue #21 asked to migrate `assertTenant` from 2-arg to 3-arg across the four RAG adapters. When I went to look at the call sites, all ten were already using the 3-arg form with `RequestContextCheck.isActive()` — the migration happened during #19 when the ingestion bridge needed it for `@Scheduled` contexts. The only remaining work was extending the garden protocol's `applies_to` field to include the RAG adapter SPIs alongside the memory store adapters.

The discovery that work is already done is its own kind of useful finding. It means the protocol drifted from reality — the code was ahead of the documentation. Fixing that is the whole point of maintaining protocols.
