---
layout: post
title: "Where the Protocol Said One Thing and the Codebase Said Another"
date: 2026-06-09
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [reactive, spi, protocol, mutiny, rag]
---

# Where the Protocol Said One Thing and the Codebase Said Another

Adding reactive SPIs to rag-api was supposed to be mechanical. `ReactiveCorpusStore` mirrors `CorpusStore` with `Uni<T>` returns. `ReactiveCaseRetriever` mirrors `CaseRetriever`. Five methods total. The interesting part was where they should live.

The module-tier-structure protocol is explicit: "JPA, Mutiny, and Quarkus runtime types remain excluded from Tier 1." rag-api is Tier 1 — pure Java, zero deps, ArchUnit enforced. So the reactive interfaces can't go there.

Except casehub-ledger-api already has Mutiny as `provided` scope. And casehub-eidos-api does too. Both Tier 1 modules. Both with reactive SPIs sitting next to the blocking ones. Both shipped and consumed by other platform repos.

The protocol's exclusion made sense when written — Mutiny was lumped with JPA and Quarkus as "infrastructure the consumer shouldn't need." But `provided` scope doesn't impose anything. The protocol's own pragmatic test — "does adding this dependency force a consumer to configure infrastructure they don't use?" — Mutiny passes. Every Quarkus consumer already has it on the classpath.

I filed issues to update both the protocol and PLATFORM.md. The fix: remove Mutiny from the exclusion list. Two repos already do it. Now a third does.

## The engine dependency question

The real design question was about casehub-engine. Engine will inject `ReactiveCaseRetriever` for fact-space prompt compilation. Where the reactive SPI lives determines what engine depends on.

If the reactive SPIs go in `rag/` (where the Qdrant implementations live), engine pulls in Qdrant + LangChain4j transitively. Engine has no use for either. In ledger and qhorus, this isn't a problem — engine already has JPA, so depending on their runtime modules costs nothing extra. RAG's heavy deps are specific to the implementation, not to the contract.

So the reactive SPIs stay in rag-api. Engine depends on `casehub-rag-api` at compile time for both tiers. The deploying app brings `casehub-rag` at runtime. In tests, `casehub-rag-testing` provides in-memory stubs.

## A scope bug that was always there

While adding the reactive in-memory stubs, I noticed the existing blocking stubs were missing `@ApplicationScoped`. Without it, CDI creates a new instance per injection point. The reactive wrapper injecting the blocking stub would get its own copy with its own backing data, separate from what a test injects directly.

Every other platform stub — qhorus's `InMemoryChannelStore`, eidos's `InMemoryAgentRegistry` — has `@ApplicationScoped`. One annotation per class. The kind of thing that works until someone injects the same store from two places and wonders why the data doesn't match.