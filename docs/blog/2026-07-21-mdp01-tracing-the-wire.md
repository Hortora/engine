---
layout: post
title: "Tracing the Wire"
date: 2026-07-21
type: phase-update
entry_type: note
subtype: diary
projects: [neocortex]
tags: [cloudevents, cdi, cbr]
series: issue-142-cloudevent-outcome-wiring
---

The CBR feedback loop had a gap. `CbrOutcomeConsumer` existed — it could convert a `CbrOutcomeData` record into a `CbrOutcome` and call `recordOutcome()` — but nothing delivered events to it. The consumer was wired to nothing.

The issue said "blocked by" the platform's CloudEvent→CDI adapter. I wanted to understand what that actually meant before writing any code, so I traced the platform's event dispatch from first principles.

Three stream processors — AMQP, Kafka, Poll — all converge on the same line: `cloudEventBus.fireAsync(ce)`. Raw `CloudEvent` objects land in CDI. `DataSourceRouter` and `RasEngine` both observe them with `@ObservesAsync CloudEvent` and filter manually by type. No typed dispatch existed.

Except it did — almost. A plan for `@CloudEventType` qualifier dispatch had been written, implemented on a branch, squash-merged onto another branch, and then lost when that branch was squash-merged to main. The GitHub issue was closed. The code was on a dead branch. It took three rounds of `fetch` + `git show origin/main:...` before the code finally appeared — someone had re-landed it.

With `@CloudEventType` on platform main, the consumer wiring became a single annotation:

```java
public void onCloudEvent(
        @ObservesAsync @CloudEventType(CbrEventTypes.CBR_OUTCOME) CloudEvent event) {
```

No manual type filtering. `CloudEventTypeDispatcher` handles the fan-out. The consumer deserializes the data payload and delegates to the existing domain logic.

The design review caught something I would have missed entirely. The prior CBR spec — the one that defined this consumer — showed `@Observes`, not `@ObservesAsync`. That would have compiled, passed unit tests, and silently received zero events in production. `CloudEventTypeDispatcher` fires with `fireAsync()`, which delivers exclusively to `@ObservesAsync` observers. A synchronous observer is invisible to async dispatch. No error, no warning, no log entry. We filed it as an erratum.

The CDI wiring test exists specifically to catch this failure mode. It fires a CloudEvent with the `@CloudEventType` qualifier through CDI's `fireAsync()` and asserts that the observer receives it. If someone changes the annotation back to `@Observes`, the test fails — because the event never arrives.

Three commits, five files changed. The platform investigation took longer than the implementation.
