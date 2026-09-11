---
layout: post
title: "The Storage Layer That Wasn't There"
date: 2026-06-12
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [corpus, zip, architecture, rag, storage]
---

# The Storage Layer That Wasn't There

The RAG pipeline is done — embedding, Qdrant, hybrid search, reactive SPIs. But there's a gap between "I have documents" and "I can search them." The pipeline assumes someone else handles storage, versioning, change tracking, and distribution. Nobody does.

The Hortora garden is the immediate problem. ~1,900 markdown entries on disk, git-backed, growing toward something much larger. I want it searchable via the RAG pipeline. But there's no ingestion path — nothing watches for new entries, nothing tracks what's been processed, nothing distributes the corpus.

## First principles: what does "store documents for RAG" actually mean?

I started with the obvious question — can Qdrant just load files? No. Qdrant is a vector database. It stores pre-computed embeddings, not documents. The pipeline is: parse, chunk, embed, store vectors. Qdrant handles the last step. Everything before it is missing.

The garden's forage skill writes `.md` files directly to disk. No API call, no service boundary. Any storage layer either has to change forage (cross-repo, not happening soon) or detect changes after the fact.

## ZIP as primary store — the argument that took four rounds

The design conversation started with git as the change detection mechanism. Git knows what changed via `git log <sha>..HEAD`. Simple cursor model — store the last processed SHA, process new commits. But git doesn't scale to a million files. `git status` does a full `stat()` on every tracked file. At 500K+ files, common operations take tens of seconds.

That led to ZIP archives. Zip4j supports genuine append — it seeks to the central directory offset, writes new entry data there, rewrites the central directory. Existing data is untouched. I verified this from the actual source code: `AbstractAddFileToZipTask.initializeOutputStream()` calls `splitOutputStream.seek(HeaderUtil.getOffsetStartOfCentralDirectory(zipModel))`. Not a web page claim — the actual Java on GitHub.

Rolling independent ZIPs (new archive every 100MB) instead of one giant file. Each ZIP is self-contained, independently browsable, appendable until sealed. A chain manifest (`chain.json`) links them with UUIDs, content hashes, and domain breakdowns.

The spec went through four review passes. Each round caught real design issues:

Round 1 found 15 problems — the existing `CorpusStore` name collision, implementation config leaking into the SPI, missing `DELETED` change type, undefined tenancy model, no reactive variants. All legitimate. The name collision was the most consequential: the existing `io.casehub.rag.CorpusStore` pushes embeddings into Qdrant. It's an `EmbeddingIngestor`, not a corpus store. Renaming it frees the name for what actually stores a corpus.

Round 2 caught the false atomicity claim — ZIP append involves three sequential writes, and a crash mid-central-directory-rewrite can corrupt the archive. The chain model IS the crash safety mechanism, not the ZIP format itself. Also caught that compaction (removing orphaned versions) breaks the chain's content hashes — solved by generating a new UUID for compacted ZIPs rather than mutating existing ones.

Round 3 found the COMPOSITE mode data consistency bug. Forage writes flat files. The ingestion bridge polls `ChangeSource.changesSince()`. `CorpusReader` reads from ZIP. But the flat file isn't in the ZIP yet — `CorpusReader` returns empty for content the bridge was just told exists. The fix: `CompositeChangeSource` syncs flat files into ZIP internally before reporting changes. Sync-then-report. The bridge never needs to know about flat files.

Round 4 stripped dead weight — the metadata parameter on `append()` had no read-side counterpart, no persistence mechanism, and was redundant with `MetadataExtractor`.

## Upstream: quarkus-langchain4j annotations

While designing the corpus module, I filed decomposition issues against quarkus-langchain4j #2572 — the `@RegisterAiService` simplification epic. Five child issues: `@RagPipeline` (#2574), `@HybridSearch` (#2575), `@DocumentIngestion` (#2576), `@MemoryConfig`/`@ToolConfig` (#2577), and the foundation supplier-to-bean migration (#2578). Each carries design feedback from building the RAG pipeline — custom sparse embedding support, incremental ingestion mode, conditional routing.

Tracking issue #16 in neural-text records seven additional annotation candidates not yet filed upstream: reactive bridging, collection lifecycle, `@Corpus` qualifier, `@QueryTransformer`, `@TenantIsolation`, `@MetadataExtractor`, `@EmbeddingCache`.

## The rename

`CorpusStore` to `EmbeddingIngestor`. IntelliJ semantic rename across rag-api, rag, rag-testing. 29 files, 12 class renames, all in one commit per protocol. The breakage is the point — every caller now says exactly whether it's storing documents or pushing embeddings.

The corpus storage module lands next — two new layers (L8 corpus-api, L9 corpus) with ZIP-backed rolling archives, chain manifest, integrity checking, composite mode for forage integration, and a migration path for the existing garden. The ingestion bridge in rag/ follows after that.
