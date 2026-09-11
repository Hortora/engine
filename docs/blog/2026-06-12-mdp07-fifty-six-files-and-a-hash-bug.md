---
layout: post
title: "Fifty-Six Files and a Hash Bug"
date: 2026-06-12
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [corpus, zip, implementation, chain-integrity, tdd]
---

# Fifty-Six Files and a Hash Bug

The corpus storage spec was approved yesterday. Today it became code — two new modules, 56 files, 136 tests, every feature from the spec implemented.

## The small thing first

Issue #15 was three code review findings from the reactive Qdrant work. The important one: `ReactiveQdrantEmbeddingIngestor` and `ReactiveHybridCaseRetriever` both called `MemoryPermissions.assertTenant()` eagerly at method entry, outside the Uni pipeline. If tenancy validation failed, `SecurityException` flew synchronously — the caller never got a Uni back, never had a chance to handle the failure reactively.

The fix is `Uni.createFrom().deferred()`. Wrap the assertTenant call inside the deferred supplier so it executes at subscription time, and the exception flows through the Uni failure channel where Mutiny consumers expect it. The same code pattern as the platform's `CaseMemoryStore` adapter contract — security gate first, but inside the reactive chain, not before it.

Also extracted `StubEmbeddingModel` and `stubPrincipal` from four identical inner classes into a shared `RagTestFixtures`. And added delete-then-reingest tests that exercise the `ensuredCollections` eviction path. One commit, closed, done.

## Building the corpus

The spec describes three planes — data (write/read), delta (change tracking), ops (integrity) — each independently implementable. I kept that separation in the code. `ZipCorpusStore` implements `CorpusStore` + `CorpusReader`. `ZipChangeSource` implements `ChangeSource`. `ZipIntegrityChecker` implements `CorpusIntegrity`. They share a `MasterIndex` and `ChainManifest` but own their plane's logic.

The interesting design question was how to handle multiple versions of the same path inside a single ZIP file. Zip4j doesn't support duplicate entry names. The solution: versioned entry names — `1/docs/readme.md`, `2/docs/readme.md`. Version number is the prefix, logical path follows. Reading a specific version is a string lookup. Rebuilding the index from central directories on startup is a parse of the first slash position. It trades ZIP browsability (a user opening the ZIP sees version directories, not a flat file list) for clean unambiguous addressing.

## The hash bug

The chain manifest stores a SHA-256 `contentHash` for each closed ZIP — this is how a receiver verifies integrity. During rollover, the code computed the hash, then wrote `_chain/meta.json` inside the ZIP. But appending an entry to a ZIP rewrites the central directory. The hash no longer matches the file.

The dependency runs backwards from what feels natural. You want to compute the hash, write the hash into the metadata, close the file. But the metadata IS part of the file, and writing it changes what the hash covers. The fix: write the metadata first with a null hash placeholder, then compute the hash over the complete file. `chain.json` is the authority for the hash anyway — the internal copy is informational.

This is a general pattern for any archive-then-hash workflow. The hash must be the last operation. Anything written after the hash computation invalidates it.

## Zero-dependency JSON

The corpus module is Hortora-eligible — zero casehub dependencies, zero framework dependencies beyond Zip4j. The `chain.json` manifest needed serialization. Jackson would have been easy but it's a heavy dependency for a fixed 12-field schema. I went with a hand-written `StringBuilder` JSON writer and a simple recursive-descent reader. It works, it's tested, it's about 150 lines. If the schema grows complex enough to justify a library, add it then.

## What's here

`corpus-api` at L8: four blocking SPIs, three reactive mirrors, seven value types. ArchUnit enforcement confirming zero forbidden dependencies. Reflection-based parity test confirming every blocking method has a reactive equivalent.

`corpus` at L9: `ZipCorpusStore` with rolling archives and size-threshold rollover. Chain manifest with atomic writes (temp + rename). `ZipChangeSource` with cursor-based delta tracking. `ZipIntegrityChecker` covering the full detection matrix from the spec. `FlatCorpusStore` for filesystem-only mode. `CompositeCorpusStore` with sync-then-report for the forage integration path. Compaction in both modes — `TOMBSTONES_ONLY` preserves version history, `FULL` keeps only the latest. `CorpusMigrator` for garden onboarding. Blocking-to-reactive bridges for all three SPI planes.

The ingestion bridge (#19) — wiring `ChangeSource` + `CorpusReader` into the RAG pipeline — is next. The corpus modules have no dependency on RAG, LangChain4j, or Qdrant. That separation was the design goal and it held through implementation.
