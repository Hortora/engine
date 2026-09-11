# inference-quarkus — CDI Wiring Design

**Issue:** casehubio/neural-text#5
**Status:** Approved
**Scope:** inference-quarkus module — Quarkus CDI integration for inference-* modules

## Architecture

CDI-only, single module (no deployment/runtime split). Full Quarkus extension deferred to native image gate pass. JVM mode only.

## Public API

### @Inference qualifier

```java
@Qualifier
@Retention(RUNTIME)
@Target({FIELD, PARAMETER, METHOD, TYPE})
public @interface Inference {
    @Nonbinding String value();
}
```

Named `@Inference` (not `@InferenceModel`) to avoid import collision with the `InferenceModel` SPI interface.

### Configuration

```properties
casehub.inference.models.nli.model-path=models/nli/model.onnx
casehub.inference.models.nli.tokenizer-path=models/nli/tokenizer.json
casehub.inference.models.nli.max-sequence-length=512
casehub.inference.models.nli.intra-op-threads=0
casehub.inference.models.nli.inter-op-threads=0
```

`@ConfigMapping(prefix = "casehub.inference")` with `Map<String, ModelProperties> models()`.

### InferenceModelProducer

`@ApplicationScoped @DefaultBean`. Reads config, creates `OnnxInferenceModel` instances lazily, closes all on shutdown.

```java
@Produces @Inference("") InferenceModel produce(InjectionPoint ip)
```

Inspects `InjectionPoint` for `@Inference` qualifier value. Looks up config by name, creates model on first access.

### Test override

Tests provide their own `@Produces @Inference("") InferenceModel` method returning `InMemoryInferenceModel`. Wins over `@DefaultBean` automatically.

## Classes

| Class | Purpose |
|-------|---------|
| `Inference` | CDI qualifier annotation |
| `InferenceModelConfig` | `@ConfigMapping` interface |
| `InferenceModelProducer` | CDI producer + lifecycle management |