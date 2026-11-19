---
layout: post
title: "Five review findings, two real bugs, and a type bridge"
date: 2026-06-14
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [federation, testing, wiremock, quarkus]
---

## Hortora Engine — Five review findings, two real bugs, and a type bridge

The federation chain walk landed last session (#5). This session was cleanup — five minor findings from the code review. I expected a quick session. Two of the five turned out to be already resolved in the code, one was correct as-is despite looking wrong, and two were genuine improvements.

The finding that taught me something was the executor field pattern in `ChainWalker`. Two fields — `managedExecutor` (CDI-injected `ManagedExecutor`) and `executor` (runtime `ExecutorService`) — connected by a one-line assignment in `@PostConstruct`. My first reaction was redundancy: collapse them, inject directly. Claude's initial spec agreed.

The code review caught the type error. `ManagedExecutor extends ExecutorService`, not the reverse. The test seam — `setExecutor(ExecutorService)` — accepts a `DirectExecutor` that isn't a `ManagedExecutor`. Typing the field as `ManagedExecutor` breaks the setter. Typing it as `ExecutorService` risks CDI ambiguity with Vert.x. The two-field pattern is a deliberate type bridge: unambiguous bean resolution on the CDI side, widest compatible type on the usage side, test-injectable via the setter. One line of code connecting three legitimate requirements. I was wrong to call it redundancy.

The WireMock fix was more mechanical but had a real correctness bug hiding in it. Port 9999 was hardcoded in three places — the Java test, the `WireMock.configureFor()` call, and the YAML fixture. The fix was a `QuarkusTestResourceLifecycleManager` that starts WireMock on a dynamic port and writes a temp YAML fixture. Standard Quarkus pattern. What wasn't obvious: `@QuarkusTestResource` defaults `restrictToAnnotatedClass` to `false`. Without the override, the resource's config overrides — including the child-with-upstream federation config — would silently bleed into every `@QuarkusTest` in the module. `SearchResourceTest` expects canonical config with no federation. It would have broken, possibly intermittently depending on test ordering.

The review also caught that my spec claimed `federation-test.yaml` was still used by `FederationConfigParserTest`. It isn't — the parser test uses `valid-child.yaml`, `valid-canonical.yaml`, and other fixtures, never the federation integration fixture. The file was genuinely orphaned after the migration. Deleted.

Five findings, two no-ops confirmed by reading the code, one no-op confirmed by type analysis, two real fixes shipped. The provenance assertion test (`searchResultsIncludeProvenanceFields`) closes the last coverage gap from the federation work — the non-federated path now asserts `source` and `sourcePrefix` explicitly.
