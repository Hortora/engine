---
layout: post
title: "The audit that fixed what we'd left behind"
date: 2026-06-23
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [code-quality, crlf, cdi, test-doubles, casehub-rag-testing, searchresource]
---

## Hortora Engine — The audit that fixed what we'd left behind

Every project accumulates debris. Not bugs — the code works. Small things: a logger using the wrong facade, a test that doesn't test what its name says, a REST endpoint returning `Response` when every caller already knows the type. Individually harmless. Collectively, they signal a codebase that doesn't take itself seriously.

I ran a systematic audit of the engine — every source file, every test, every config property — and filed nine issues. Then implemented all nine in a single branch.

The interesting ones weren't the fixes themselves. They were what the fixes revealed about the platform's maturity.

## `CaseRetriever` changed under our feet

The engine didn't compile. Somewhere between the hybrid search work and now, `casehub-rag-api` changed `CaseRetriever.retrieve()` from `String` to `RetrievalQuery`. A SNAPSHOT dependency doing what SNAPSHOT dependencies do — breaking downstream consumers without ceremony. The fix is `RetrievalQuery.of(query)`, one line, but it touched everything: `SearchResource`, the test doubles, the federation chain. Every issue in the audit had to account for the compilation failure before it could be tested.

`RetrievalQuery` itself is a clean record — `text` plus an optional `expandedText` for future query expansion. The right API change. But discovering it via a compilation error four days later is the tax you pay for SNAPSHOT coupling.

## The test double reconciliation

The engine had hand-written `TestCaseRetriever` and `TestEmbeddingIngestor` — `@Mock @ApplicationScoped` beans that hardcoded two fixture entries in their constructors. Meanwhile, `casehub-rag-testing` ships `InMemoryCaseRetriever` and `InMemoryEmbeddingIngestor` with `@Alternative @Priority(1)`, backed by an actual ingest-then-retrieve pipeline. The engine's doubles were shadowing the platform stubs entirely.

Deleting them required one discovery I hadn't anticipated: Quarkus doesn't scan test-scoped JARs for CDI beans by default. The `@Alternative` beans were on the classpath but invisible. `quarkus.index-dependency.casehub-rag-testing.group-id` and `.artifact-id` in `application.properties` fixed it — but the symptom was "production bean still active, no error" which is exactly the kind of silent failure that wastes an hour before you think to check bean discovery.

## CRLF: the test that passes without the fix

Both `GardenMetadataExtractor` and `FederationConfigParser` split frontmatter on `\n`. Files authored on Windows with `\r\n` endings break silently — the parser sees no frontmatter and returns an empty result. Straightforward to fix: `text.replace("\r\n", "\n")` at the parse boundary.

The test strategy was less obvious. The natural approach — a fixture `.md` file with `\r\n` endings — doesn't work. Git's `core.autocrlf` normalizes line endings on checkout. The fixture arrives with `\n` regardless of what was committed. The test passes, the fix is never exercised. We had to construct the CRLF content programmatically: `"---\r\ntitle: test\r\n---\r\n".getBytes()`. Immune to git normalization by construction.

## `SearchResource` grows up

The endpoint returned raw `Response`, which hides the return type from OpenAPI generation and is inconsistent with `RemoteGardenClient` (which already declares `List<SearchResult>`). The default result limit was hardcoded as `8` in two places with no upper bound — a caller could request `?limit=100000`. `parseVisited()` didn't trim whitespace, so `"a, b"` in the `X-Federation-Visited` header wouldn't match `"b"`.

None of these are hard to fix. All of them compound. A typed return, a `MAX_LIMIT` cap, a `.map(String::trim)` — the kind of work that's invisible when done but corrosive when left.
