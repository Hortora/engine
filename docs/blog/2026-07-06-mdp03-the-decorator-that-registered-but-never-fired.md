---
layout: post
title: "The decorator that registered but never fired"
date: 2026-07-06
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [quarkus, arc, cdi, decorator, debugging]
---

Arc's `@Decorator` support has a gap nobody warns you about. If the bean being decorated was created by a `@Produces` method, the decorator registers at build time, passes every diagnostic check, and then silently does nothing at runtime.

We spent a session chasing this through three wrong hypotheses before finding it.

## The symptom

`QueryExpandingCaseRetriever` — a `@Decorator @Priority(200)` that wraps `CaseRetriever` to expand queries via HyDE before retrieval — was invisible. Benchmark results identical with and without the dependency. No errors. No warnings. `BuildTimeEnabledProcessor` DEBUG output confirmed "Enabling" for both the decorator and `LlmQueryExpander`. Every diagnostic said the decorator was active.

Except the constructor never fired. The FINE log we added to verify? Silence.

## Three hypotheses, all wrong

**Hypothesis 1: `@IfBuildProperty` doesn't work for external JARs.** The decorator lives in `rag-expansion`, consumed as an external dependency. We read the `BuildTimeEnabledProcessor` source — it scans the full `CombinedIndexBuildItem`, including external JARs. No special handling for decorators. Eliminated.

**Hypothesis 2: Arc's unused bean removal is silently pruning the decorator.** We added `@Unremovable`. No change. The decorator was never being removed — it was being registered and then ignored.

**Hypothesis 3: `NoOpQueryExpander` is making interception invisible.** If no expansion mode is configured, the `@DefaultBean` pass-through expander handles queries unchanged. Plausible — but the Hortora engine had `casehub.rag.expansion.mode=llm` set. And the `@QuarkusTest` with the same configuration worked. Eliminated.

## The tell

The `@QuarkusTest` used `InMemoryCaseRetriever` — a managed `@Alternative @Priority(1) @ApplicationScoped` bean. That worked. In production, `HybridCaseRetriever` was the `CaseRetriever`. That didn't.

The difference: `InMemoryCaseRetriever` has `@ApplicationScoped` on the class. `HybridCaseRetriever` was produced by a `@Produces` method in `RagBeanProducer`.

## Why producer method beans break decorators

Arc applies decorators by generating subclasses. For a managed bean (`@ApplicationScoped` on the class), Arc generates a subclass at build time that routes method calls through the decorator chain. The subclass IS the bean — CDI creates an instance of the subclass, not the original class.

For a `@Produces` method bean, the producer creates the instance. Arc wraps it in a client proxy for scope management, but it cannot subclass an already-created object. The decorator is registered in the bean graph. It just never gets wired into the call chain.

No error. No warning. The `@QuarkusTest` passes because it uses a managed bean. The production path uses a producer. Two different CDI mechanisms, only one of which supports decoration.

## The fix

Convert `HybridCaseRetriever` from a producer method bean to a managed bean:

```java
@ApplicationScoped
public class HybridCaseRetriever implements CaseRetriever {
    @Inject
    HybridCaseRetriever(QdrantClient client, MultiModalEmbedder embedder,
                        Instance<CurrentPrincipal> principal, RagConfig config) {
        this(client, MatryoshkaMultiModalEmbedder.wrapIfNeeded(embedder, ...),
             TenantGuard.of(principal.isResolvable() ? principal.get() : null),
             config);
    }

    // Package-private — direct construction in unit tests
    HybridCaseRetriever(QdrantClient client, MultiModalEmbedder embedder,
                        TenantGuard guard, RagConfig config) { ... }
}
```

The `@Inject` constructor handles the computed dependencies that the producer method used to manage — `MatryoshkaMultiModalEmbedder` wrapping and `TenantGuard` resolution. The original package-private constructor stays for unit tests that construct the retriever directly.

Same treatment for `ReactiveHybridCaseRetriever`. Both producer methods removed from their respective producer classes.

The fact that a `@QuarkusTest` can pass while the production path silently fails — because they use different bean discovery mechanisms for the same type — is the kind of gap that costs days. Now it's in the garden.
