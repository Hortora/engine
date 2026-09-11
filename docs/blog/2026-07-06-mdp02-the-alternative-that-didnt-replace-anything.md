---
layout: post
title: "The Alternative That Didn't Replace Anything"
date: 2026-07-06
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cdi, quarkus-arc, query-expansion, testing]
---

I filed a bug that was already fixed. That's the short version.

The longer version starts with an engine adoption analysis — which neocortex issues would unblock hortora/engine's retrieval pipeline. A subagent reported that `InMemoryQueryExpander` didn't exist in `rag-testing`. I filed #111 based on that analysis without checking. The class has been on main since the neural-text rename, four months ago.

The interesting part came after closing the bogus issue. The real question wasn't whether a test stub existed — it was whether a test stub *could actually work*. `LlmQueryExpander` carries `@IfBuildProperty(enableIfMissing=true)`, which means it registers by default in any Quarkus app with `rag-expansion` on the classpath. Its constructor needs `ChatModel`. The `InMemoryQueryExpander` is `@Alternative @Priority(1)` — it wins the selection. But Quarkus Arc validates injection points of *all* registered beans, not just the selected one. The `@Alternative` controls which bean gets injected at the consumer's injection point. It does not suppress build-time validation of the bean it displaces.

So `InMemoryQueryExpander` exists, gets selected, and does nothing to prevent `LlmQueryExpander` from failing the build because no `ChatModel` is available.

The fix has three parts. Remove `enableIfMissing = true` so `LlmQueryExpander` only registers when the consumer explicitly sets `casehub.rag.expansion.mode=llm`. Add a `NoOpQueryExpander` as `@DefaultBean` — a pass-through that returns the query unchanged, following the platform's no-op pattern for operational SPIs. And change `ExpansionConfig.mode()` from `@WithDefault("llm") String` to `Optional<String>` — the design review caught that SmallRye Config's `@WithDefault` registers values in `DefaultValuesConfigSource`, which `@IfBuildProperty` might see during build-time evaluation, defeating the whole fix.

The design review also caught that without a startup warning, a consumer who sets `expansion.enabled=true` but forgets to set a mode gets silent pass-through with no feedback. `ExpansionConfigValidator` logs a warning on startup when expansion is enabled without a mode configured.

The distinction between "not selected" and "not registered" is invisible in CDI until it breaks your build. `@Alternative` does selection. `@IfBuildProperty` does registration. They're different mechanisms solving different problems, and conflating them cost a session.
