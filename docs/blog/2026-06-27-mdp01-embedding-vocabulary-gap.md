---
layout: post
title: "The Vocabulary Gap: Why Embedding Search Can't Read Java"
date: 2026-06-27
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [vector-search, grep, benchmark, embeddings, nomic-embed-text, garden]
series: issue-27-real-world-benchmark
---

# Hortora Engine — The Vocabulary Gap: Why Embedding Search Can't Read Java

**Date:** 2026-06-27
**Type:** phase-update

---

*Part of a series on [#27 — Real-world benchmark](https://github.com/Hortora/engine/issues/27). Previous: [The Grep Firehose vs. Eight Ranked Answers](2026-06-26-mdp01-grep-firehose-vs-ranked-answers.md).*

## What I expected: a clear upgrade

The synthetic benchmark painted a convincing picture. Vector search returned 8 ranked results where grep returned 2,000 unsorted filenames. The skill migration was designed around this — gardenSearch as the primary path, grep as a degraded fallback. The real-world benchmark was supposed to confirm the value proposition on actual GitHub issues.

It didn't.

## What actually happened: a 6-6-2 split

We ran the benchmark across 14 scenarios — 6 closed issues from casehub repos (one per technology band: reactive, CDI, persistence, REST, AI/LLM, testing) and 8 technical domains extracted from 2 design specs. Three searches per scenario: grep with keywords, gardenSearch with the same keywords, gardenSearch with a natural language description.

grep and gardenSearch-NL split 6-6-2. Neither dominates. They win on completely different query types, and their wins don't overlap.

## The vocabulary gap

The finding I didn't expect: gardenSearch with keywords was catastrophic. Lost 12 of 14 scenarios against grep, with an average precision of 32% versus grep's 65%.

The root cause is that `nomic-embed-text` — a general-purpose embedding model — treats Java class names, CDI annotations, and framework identifiers as generic English tokens. `ChatModel` becomes "chat" + "model" and matches entries about HTTP caching. `@DefaultBean` matches anything containing "default." `shadowing` matches Shadow DOM entries instead of JPA field shadowing.

This is silent. The relevance scores look normal — 0.53 to 0.70. The results are plausible. They're just wrong.

The same engine with natural language queries recovered to 62% precision — competitive with grep. "CDI ambiguous dependency resolution when @Default bean conflicts with @DefaultBean" finds the right entries. "@DefaultBean|AmbiguousResolutionException" finds entries about WireMock.

Query formulation is the single largest quality lever. A 30-percentage-point precision gap between keyword and NL queries on the same retrieval engine.

## Where grep still wins outright

grep dominated the CDI and AI/LLM bands. For `@DefaultBean|AmbiguousResolutionException|GroupMembershipProvider|ambiguous`, grep returned 19/20 relevant results at 95% precision. The garden has deep CDI coverage with these exact annotation names in the text. Substring matching finds them all. gardenSearch-NL managed 100% precision but in only 8 slots — grep's discovery depth (6 unique score-2 entries vs 3) was the deciding factor.

The pattern held across spec reviews. The LangChain4j interop spec is built around API names — `ChatModel`, `doChat()`, `StreamingChatModel`, `ExceptionMapper` — and grep found 8 unique score-2 entries about these exact interfaces that gardenSearch missed entirely.

## Where gardenSearch wins outright

gardenSearch-NL won the reactive, REST/messaging, and persistence bands. Each of these involves a concept described in natural language rather than a specific API name.

"JPA blocking operation on Vert.x IO thread causes BlockingOperationNotAllowedException" found entries about `@ConsumeEvent` blocking patterns that don't contain the words "JPA" or "Vert.x" in their titles. grep's keywords — `Blocking|Vert.x|IO thread|JPA` — returned 330 files at 40% precision, drowning the signal in Vert.x entries about unrelated topics.

The most architecturally significant single finding came from gardenSearch-NL on the broad spec review: `@LookupIfProperty` as a cleaner alternative to the spec's `@PostConstruct` filtering pattern for circular dependency prevention. grep can't make that cross-domain connection because the entry uses different vocabulary entirely.

## The evaluator bias is real

Every link in the chain favours gardenSearch. The embedding model was selected for Claude's consumption. Claude derived the NL queries in its own comprehension style. Claude evaluated whether the results were useful to itself. The 6-6-2 split against grep — an unbiased substring matcher — may be optimistic.

## What this means for the migration

The migration as designed — gardenSearch primary, grep fallback — isn't supported by the evidence. The right architecture is both methods for different query types: gardenSearch for concepts and patterns described in natural language, grep for specific API names and annotations.

There's a harder problem underneath: grep doesn't scale. At 1,960 entries, grep's keyword matches range from 160 to 2,176 files per query. At 100k entries those numbers become unusable. gardenSearch returns 8 ranked results regardless of corpus size — its advantage isn't retrieval quality (which is mediocre today), it's retrieval stability under scale.

SPLADE hybrid search — already built but not deployed — adds learned sparse token matching alongside dense embeddings. It may fix the keyword catastrophe without requiring a domain-tuned model. That's the next test.
