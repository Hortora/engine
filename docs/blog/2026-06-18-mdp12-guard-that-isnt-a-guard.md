---
layout: post
title: "The Guard That Isn't a Guard"
date: 2026-06-18
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neural-text]
tags: [tenancy, cdi, hortora, architecture]
series: issue-36-hortora-integration-gaps
---

# CaseHub Neural-Text — The Guard That Isn't a Guard

**Date:** 2026-06-18
**Type:** phase-update

---

## What I was trying to achieve: make rag/ consumable by Hortora without weakening casehub tenancy

Issue #35 closed the functional gaps — `PayloadFilter`, optional sparse embeddings, deterministic IDs. But it left a CDI blocker: every `rag/` implementation class hard-injects `CurrentPrincipal` from `casehub-platform-api`. Hortora doesn't have that bean. Startup fails before any code runs.

The obvious fix was `Instance<CurrentPrincipal>` — the same pattern already used for `SparseEmbedder` in the same producer classes. I nearly went with it.

## What we believed going in: Instance<> is consistent, therefore correct

The initial spec said: "The `Instance<T>` pattern is already used in the same producer classes for `SparseEmbedder` and `CrossEncoderReranker`. Extending it to `CurrentPrincipal` is consistent and unsurprising." Clean, minimal, ships fast.

The review caught the category error. `SparseEmbedder` being absent means "use dense-only mode" — a feature degradation. `CurrentPrincipal` being absent means "skip all tenant authorization" — a security degradation. Same CDI mechanism, fundamentally different semantic. Treating them identically in code makes them look equivalent when they aren't.

The second problem: 10 scattered `if (currentPrincipal != null)` guards across four classes. Every future method must remember the guard. One miss is either a `NullPointerException` for Hortora or a silent security bypass for casehub.

## TenantGuard — a deployment decision, not a runtime check

The fix is a `@FunctionalInterface` constructed once at CDI producer time:

```java
@FunctionalInterface
interface TenantGuard {
    void assertTenant(String tenantId);

    static TenantGuard of(CurrentPrincipal principal) {
        return principal == null
            ? tenantId -> {}
            : tenantId -> MemoryPermissions.assertTenant(
                tenantId, principal, RequestContextCheck.isActive());
    }
}
```

The producers resolve `Instance<CurrentPrincipal>`, call `TenantGuard.of()`, and pass the guard to the implementation constructors. The constructors become package-private — enforcing that consumers inject via CDI, not construct directly (zero external callers existed anyway, but the visibility was lying about it).

Every operation calls `tenantGuard.assertTenant(corpus.tenantId())`. No null checks. No conditionals. The deployment-time decision is captured once and honoured everywhere.

I also considered pushing null-safety into `MemoryPermissions` in `casehub-platform-api`. Wrong place — the platform should maintain its strong contract that `CurrentPrincipal` is never null in a request context. The optionality is a neural-text boundary concern, not a platform concern.

## The boundary that moved without updating the map

The bigger issue wasn't the code — it was the documentation. ARC42STORIES §2 explicitly said: "Hortora shares inference-\* only — rag-\* modules are casehub-specific." The PLATFORM.MD deep-dive said the same. #35 deliberately changed that boundary but never updated the architectural record.

We updated twelve sections across ARC42STORIES — stakeholders, constraints, context diagram, solution strategy, layer table, crosscutting concepts, AD-001, quality requirements, and the risk table. Every reference to unconditional tenancy enforcement or Hortora-only-takes-inference now reflects the new reality.

The L6/L7 layer table rows changed from "casehub only" to "✅ yes". That's the architectural signal — everything else is prose catching up to it.

A cross-repo issue (casehubio/parent#272) tracks the PLATFORM.MD deep-dive update, which lives in a different repo.

## Where this leaves us

Neural-text's rag modules are now genuinely consumable by non-casehub Quarkus applications. Tenancy enforcement is active when `CurrentPrincipal` is provided (every casehub deployment), silent when it isn't. The architecture says what the code does; the code does what the architecture says.

The ball is in Hortora/engine's court. We recommended Path A for ingestion orchestration — provide a `CorpusIngestionBinding` CDI bean and let `CorpusIngestionService` handle scanning, watching, and cursors. That drops the most duplicated code. But the choice is theirs.
