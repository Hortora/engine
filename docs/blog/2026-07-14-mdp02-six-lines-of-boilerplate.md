# Six Lines of Boilerplate

Issue #64 proposed an abstract `CaseMemoryEventBridge` — a base class that CDI event observers would extend to get error isolation, tenant assertion, and Instance lifecycle management when writing to `CaseMemoryStore`. Three repos had the pattern: engine's `CaseMemoryObserver`, devtown's `CaseMemoryEmitter`, and devtown's `FeatureVectorEmitter`.

The first thing that fell apart was the abstraction itself. CDI event observation is declared via `@Observes` or `@ObservesAsync` on a method — the event type is statically declared in the signature. You can't abstract that away. Each consumer must still declare its own observer method for its own event type. So the bridge could only abstract what happens *after* the event arrives.

The second thing was the ratio. Reading all three consumers end-to-end, the domain-specific fact extraction — building `MemoryInput` from event fields — was 95% of each class. The shared boilerplate was six lines: `Instance<CaseMemoryStore>` injection, `isResolvable()` guard, try/catch with a log statement, and `destroy()` cleanup. An abstract class for six lines of savings is the wrong trade.

The third thing was a bug. Devtown's two emitters never call `Instance.destroy()` on the store reference. Engine does. If the store is `@Dependent`-scoped, that's a leak. A shared service eliminates the inconsistency — consumers inject `MemoryEmitter` and the lifecycle is handled once.

The result is a single `@ApplicationScoped` class with two methods: `emit(MemoryInput)` and `emitAll(List<MemoryInput>)`. It injects `CaseMemoryStore` directly — no `Instance<>`, because `MemoryEmitter` lives in the `memory/` module where `NoOpCaseMemoryStore @DefaultBean` is always available. The design review caught a fourth thing: the original spec swallowed `SecurityException`, which masks genuine tenant violations. The final version re-throws it.

The issue said Scale: S, Complexity: Low. That was right. The interesting part wasn't the code — it was the gap between what the issue proposed and what the code actually needed.
