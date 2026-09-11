# Cognitive Defaults — Agent Cognitive Configuration YAML

**Issue:** casehubio/neocortex#248
**Depends on:** casehubio/neocortex#247 (YAML schema conventions)
**Blocks:** #250 (YAML-to-Java compiler), #251 (Identity-cognition derivation)

## Purpose

A single YAML file defines an agent's cognitive configuration: personality weights, mood baseline, curiosity parameters, temporal focus tuning, vocabulary definitions, and named SPI references. The file composes with eidos AgentDescriptor YAML via a shared `agentId` key — zero coupling between neocortex and eidos.

## Scope

1. `CognitiveDefaults` record — aggregate of all cognitive config types
2. YAML parser — Jackson `ObjectMapper` + `YAMLFactory`
3. `CognitiveDefaultsRegistry` CDI bean — classpath scan + runtime lookup by agentId
4. YAML schema definition with complete examples

---

## §1 File Structure

**Convention (D74):** One YAML file per agent in `cognitive-profiles/` on the classpath.

```
src/main/resources/
  cognitive-profiles/
    alice.yaml
    bob.yaml
```

Each file produces one `CognitiveDefaults` bean. The `agentId` field is the join key with eidos's `AgentDescriptor.agentId`.

**Discovery path** is configurable via Quarkus config (D78):
```properties
casehub.cognitive.profiles.path=cognitive-profiles/
```

No files = no beans. All subsystems fall back to their `defaults()` factories as they do today. No behaviour change for existing applications.

---

## §2 CognitiveDefaults Record

**Location (D77):** `io.casehub.neocortex.cognitive.index` in `cognitive-index` module.

```java
public record CognitiveDefaults(
    String agentId,
    String tenantId,
    PersonalityWeights personality,
    MoodBaseline moodBaseline,
    CuriosityConfig curiosity,
    TemporalFocusConfig temporalFocus,
    MindMapVocabulary vocabulary,
    Map<String, String> services
) {
    public CognitiveDefaults {
        Objects.requireNonNull(agentId, "agentId required");
        services = services != null ? Map.copyOf(services) : Map.of();
    }
}
```

**All fields except `agentId` are nullable.** Null means "use the subsystem's built-in defaults":
- `personality: null` → `PersonalityWeights` with all-1.0 weights (equal domain weighting)
- `moodBaseline: null` → no baseline configured (MoodDecay has no target)
- `curiosity: null` → `CuriosityConfig.defaults()` (7.0 proximity, 90-day stale, etc.)
- `temporalFocus: null` → `TemporalFocusConfig.defaults()` (7.0 proximity, 1.0 worsening boost, etc.)
- `vocabulary: null` → no vocabulary registered (VocabularyNormalizationDecorator passes through)
- `services: empty` → all SPIs use `@DefaultBean` resolution

**Naming (D76):** `CognitiveDefaults` — not `CognitiveProfile` (which is the existing runtime entity resolver in cognitive-index). "Defaults" captures that these are baseline parameters that runtime behaviour deviates from.

---

## §3 YAML Schema

Full example following #247 conventions (camelCase keys per D65):

```yaml
agentId: alice
tenantId: family-1

personality:
  experience: 1.5
  relationship: 0.8
  mood: 1.2
  affect: 1.0
  engagement: 0.6

moodBaseline:
  pleasure: 0.0
  arousal: -0.2
  dominance: 0.3

curiosity:
  proximityScale: 7.0
  staleDaysThreshold: 90
  maxBfsDepth: 4
  topCentrality: 3
  volatilityThreshold: 0.3
  maxBoostFactor: 1.0
  minDampenFactor: 0.1
  improvingDampenCap: 0.7
  volatilityBoostCap: 0.5
  trajectoryLimit: 20

temporalFocus:
  proximityScale: 7.0
  worseningBoostCap: 1.0
  improvingDampenFactor: 0.5
  volatilityBoostCap: 0.5

vocabulary:
  edgeTypes:
    - canonical: knows
      aliases: [knows-about, familiar-with]
      defaultDecayHalfLifeDays: 365
    - canonical: works-with
      aliases: [colleague-of]
      defaultDecayHalfLifeDays: 180
    - canonical: child-of

services:
  reflectionSynthesizer: llm
  explanationRenderer: detailed
```

### §3.1 Minimal Profile

Only `agentId` is required:

```yaml
agentId: bob
```

Everything else uses defaults. This is a valid cognitive profile.

### §3.2 Section-by-Section Mapping

| YAML Section | Java Type | Module |
|---|---|---|
| `agentId` | `String` | — |
| `tenantId` | `String` | — |
| `personality` | `PersonalityWeights` | memory-api |
| `moodBaseline` | `MoodBaseline` | memory-api |
| `curiosity` | `CuriosityConfig` | mindmap-intelligence |
| `temporalFocus` | `TemporalFocusConfig` | cognitive-index |
| `vocabulary` | `MindMapVocabulary` | mindmap-api |
| `services` | `Map<String, String>` | — |

### §3.3 Personality Weights Mapping

`personality:` is a `Map<MemoryDomain, Double>`. YAML keys are domain name strings (MemoryDomain is a string-wrapped record):

```yaml
personality:
  experience: 1.5    # MemoryDomain("experience") → 1.5
  relationship: 0.8  # MemoryDomain("relationship") → 0.8
```

The parser constructs `new PersonalityWeights(Map.of(new MemoryDomain("experience"), 1.5, ...))`.

### §3.4 Services Mapping

`services:` maps SPI interface simple names (camelCase) to `@Named` bean names:

```yaml
services:
  reflectionSynthesizer: llm
  planAdapter: domainSpecific
  outcomeWeighting: linear
```

The registry exposes `Optional<String> serviceName(String spiKey)` for consumers to resolve beans programmatically via `CDI.current().select(type, new NamedLiteral(name))`.

Known SPI keys (from D68):

| Key | SPI Type | Default |
|---|---|---|
| `reflectionSynthesizer` | `ReflectionSynthesizer` | `noop` |
| `planAdapter` | `PlanAdapter` | `noop` |
| `planEnsembleAnalyzer` | `PlanEnsembleAnalyzer` | `noop` |
| `explanationRenderer` | `ExplanationRenderer` | `default` |
| `outcomeWeighting` | `OutcomeWeightingFunction` | `linear` |
| `trustWeighting` | `TrustWeightingFunction` | `default` |

---

## §4 CognitiveDefaultsRegistry

**Location:** `io.casehub.neocortex.cognitive.index` in `cognitive-index`.

```java
@Startup
@ApplicationScoped
public class CognitiveDefaultsRegistry {

    private final Map<String, CognitiveDefaults> profiles;

    CognitiveDefaultsRegistry() {
        // no-arg for CDI proxy
        this.profiles = Map.of();
    }

    @PostConstruct
    void loadProfiles() {
        // scan classpath, parse YAML, populate profiles map
    }

    public Optional<CognitiveDefaults> forAgent(String agentId) {
        return Optional.ofNullable(profiles.get(agentId));
    }

    public CognitiveDefaults forAgentOrDefaults(String agentId) {
        return profiles.getOrDefault(agentId, 
            new CognitiveDefaults(agentId, null, null, null, null, null, null, Map.of()));
    }

    public Collection<CognitiveDefaults> allProfiles() {
        return profiles.values();
    }
}
```

**Loading (D78):** At `@PostConstruct`, reads `casehub.cognitive.profiles.path` config, scans classpath for `*.yaml` files under that path, parses each with Jackson `ObjectMapper(YAMLFactory).findAndRegisterModules()`, validates `agentId` uniqueness, stores in an unmodifiable map.

**Error handling:**
- Duplicate `agentId` across files → startup failure with clear message
- Malformed YAML → startup failure with file path + parse error
- Missing `agentId` field → startup failure
- Empty directory / no matching files → empty registry (not an error)

---

## §5 Jackson Deserialization

The parser uses Jackson YAML with custom deserializers for types that don't map naturally:

### §5.1 PersonalityWeights

`personality:` section is a `Map<String, Double>` in YAML. Custom deserializer wraps keys in `MemoryDomain`:

```java
public class PersonalityWeightsDeserializer extends StdDeserializer<PersonalityWeights> {
    @Override
    public PersonalityWeights deserialize(JsonParser p, DeserializationContext ctx) 
            throws IOException {
        Map<String, Double> raw = p.readValueAs(
            new TypeReference<Map<String, Double>>() {});
        Map<MemoryDomain, Double> weights = new LinkedHashMap<>();
        raw.forEach((k, v) -> weights.put(new MemoryDomain(k), v));
        return new PersonalityWeights(weights);
    }
}
```

### §5.2 MindMapVocabulary

`vocabulary:` section contains `edgeTypes` list. Each entry maps to `EdgeTypeDefinition`. Jackson handles this with standard record deserialization + `findAndRegisterModules()`.

### §5.3 Other Sections

`MoodBaseline`, `CuriosityConfig`, and `TemporalFocusConfig` are records with primitive fields — Jackson handles them natively with `findAndRegisterModules()` (parameter-names module).

---

## §6 Module Dependencies

cognitive-index pom.xml gains one new compile dependency:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-mindmap-intelligence</artifactId>
</dependency>
```

Existing dependencies (memory-api, mindmap-api, cognitive-api) already cover the other types.

Jackson YAML is needed at runtime (the registry parses YAML at startup):
```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

---

## §7 Testing

### §7.1 Unit Tests

- `CognitiveDefaultsTest` — record validation (agentId required, services defensive copy)
- `CognitiveDefaultsParserTest` — parse full profile, minimal profile, missing sections default to null
- `PersonalityWeightsDeserializerTest` — string keys → MemoryDomain wrapping

### §7.2 Integration Tests

- `CognitiveDefaultsRegistryTest` — classpath loading, agentId uniqueness, forAgent/forAgentOrDefaults
- Test profiles in `src/test/resources/cognitive-profiles/`

---

## §8 Convention Summary

| Concern | Convention | Decision |
|---|---|---|
| File structure | Separate YAML, agentId join key | D74 |
| Profile shape | Single CognitiveDefaults aggregate | D75 |
| Naming | CognitiveDefaults (not CognitiveProfile) | D76 |
| Module | cognitive-index | D77 |
| Loading | Classpath scan, configurable path | D78 |
| CDI exposure | CognitiveDefaultsRegistry with lookup | D79 |
| Key naming | camelCase (from D65) | D65 |
| SPI references | services map → @Named beans (from D68) | D68 |

---

## References

- casehubio/neocortex#247 — YAML schema conventions (D65-D73)
- casehubio/neocortex#248 — this issue
- docs/specs/2026-09-01-yaml-schema-conventions.md — conventions doc
- PersonalityWeights.java — memory-api, Map<MemoryDomain, Double>
- MoodBaseline.java — memory-api, PAD record [-1, 1]
- CuriosityConfig.java — mindmap-intelligence, 10-field record with defaults()
- TemporalFocusConfig.java — cognitive-index, 4-field record with defaults()
- MindMapVocabulary.java — mindmap-api, List<EdgeTypeDefinition> with builder
- EdgeTypeDefinition.java — mindmap-api, canonical + aliases + decayHalfLifeDays
- CognitiveProfile.java — cognitive-index, existing runtime entity resolver
- AgentDescriptor.java — eidos-api, 22-field record with agentId
- eidos/eval/src/test/resources/profiles/technical-writer.yaml — eidos YAML profile format
- GE-20260602-a4d290 — ObjectMapper(YAMLFactory) requires findAndRegisterModules()
