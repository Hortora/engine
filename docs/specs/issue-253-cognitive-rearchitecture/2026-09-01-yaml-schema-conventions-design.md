# YAML Schema Conventions for Cognitive Type Configuration

**Issue:** casehubio/neocortex#247
**Depends on:** casehubio/neocortex#246 (API-to-YAML audit)
**Blocks:** #248 (Cognitive profile YAML), #249 (Declarative rule DSL), #250 (YAML-to-Java compiler)

## Purpose

Establish YAML schema conventions for all cognitive types in neocortex. The goal is parity between Java (builders) and YAML — all front ends should express the same configuration. This document defines the conventions; downstream issues implement the parser and compiler.

## Scope

1. YAML schema conventions document (this document)
2. SealedHierarchyModule design for victools jsonschema-generator
3. Schema examples for each cognitive type category
4. Assessment of shared schema-generator module extraction from engine

---

## §1 Key Naming Convention

**Convention:** camelCase for all YAML property keys.

```yaml
# Correct
topK: 10
minSimilarity: 0.6
caseType: incident
decayReference: "2026-06-01T12:00:00Z"

# Wrong
top-k: 10
min-similarity: 0.6
case-type: incident
```

**Rationale (D65):** Platform coherence. Engine (`topK`, `minSimilarity`, `caseType`), eidos (`agentId`, `tenancyId`, `qualityHint`), and neocortex YAML are consumed in the same applications. Java record field names map directly without transformation.

**Enum values** use their Java constant name as-is: `origin: STATED`, `type: PERSON`, `mode: HYBRID`. Enums where the YAML convention differs (jsonschema2pojo-generated enums in engine use kebab-case) are not present in neocortex — neocortex types are hand-written records with standard enum constants.

**Identifier names** (user-chosen names for nodes, edges, workers) are unconstrained — the user picks whatever casing fits their domain.

---

## §2 Sealed Hierarchy Discriminator

**Convention:** Use `type` as the discriminator property for all sealed hierarchies (D66). **Exception:** FeatureValue uses inference-first from YAML value shape (§3), with optional explicit `type` for disambiguation.

```yaml
# SimilaritySpec — 6 variants
similarity:
  type: gaussian
  sigma: 10.0

# TemporalDecay — 3 variants
temporalDecay:
  type: halfLife
  halfLife: PT24H

# WarpingConstraint — 3 variants
constraint:
  type: sakoeChibaBand
  window: 5
```

**Discriminator values** are the variant's simple class name in camelCase:

| Sealed Type | Variant | Discriminator Value |
|---|---|---|
| FeatureField | Categorical | `categorical` |
| FeatureField | Numeric | `numeric` |
| FeatureField | Text | `text` |
| FeatureField | CategoricalList | `categoricalList` |
| FeatureField | NumericList | `numericList` |
| FeatureField | NestedObject | `nestedObject` |
| FeatureField | ObjectList | `objectList` |
| FeatureField | TimeSeries | `timeSeries` |
| FeatureField | DiscreteSequence | `discreteSequence` |
| SimilaritySpec | CategoricalTable | `categoricalTable` |
| SimilaritySpec | GaussianDecay | `gaussian` |
| SimilaritySpec | StepDecay | `step` |
| SimilaritySpec | ExponentialDecay | `exponential` |
| SimilaritySpec | DtwSpec | `dtw` |
| SimilaritySpec | EditDistanceSpec | `editDistance` |
| WarpingConstraint | Unconstrained | `unconstrained` |
| WarpingConstraint | SakoeChibaBand | `sakoeChibaBand` |
| WarpingConstraint | ItakuraParallelogram | `itakura` |
| CbrFilter | Contains | `contains` |
| CbrFilter | ContainsAll | `containsAll` |
| CbrFilter | ContainsAny | `containsAny` |
| CbrFilter | NotContains | `notContains` |
| CbrFilter | NotContainsAny | `notContainsAny` |
| CbrFilter | ContainsRange | `containsRange` |
| CbrFilter | HasMatch | `hasMatch` |
| CbrFilter | AllOf | `allOf` |
| TemporalDecay | HalfLife | `halfLife` |
| TemporalDecay | Linear | `linear` |
| TemporalDecay | Step | `step` |
| ScopeDecay | Exponential | `exponential` |
| ScopeDecay | Linear | `linear` |
| ScopeDecay | Step | `step` |
| TemporalMark | WallClock | `wallClock` |
| TemporalMark | Relative | `relative` |
| TemporalMark | Ordinal | `ordinal` |

**Naming convention for discriminator values:** lowerCamelCase of the variant class name, with these simplifications:
- Drop `Decay`/`Spec` suffixes when unambiguous within the hierarchy (e.g., `GaussianDecay` → `gaussian`, `DtwSpec` → `dtw`)
- Keep full name when needed for clarity (`sakoeChibaBand`, `categoricalTable`)

### §2.1 CbrFilter — Key-Value Shorthand

CbrFilter variants use a key-value shorthand where the discriminator IS the key:

```yaml
filters:
  severity:
    contains: critical
  tags:
    containsAny: [urgent, p0]
  score:
    containsRange:
      min: 0.5
      max: 1.0
  compound:
    allOf:
      - contains: x
      - notContains: y
```

This works because each CbrFilter variant has exactly one structural shape. The variant name as key doubles as both discriminator and value accessor.

### §2.2 Nested Sealed Types

Three levels of `type:` discriminator nesting is the structural ceiling (D71). The deepest path is FeatureField.TimeSeries → SimilaritySpec.DtwSpec → WarpingConstraint:

```yaml
fields:
  - name: vitals
    type: timeSeries
    timestampField: timestamp
    innerFields:
      - name: heartRate
        type: numeric
        min: 40
        max: 200
    similarity:
      type: dtw
      constraint:
        type: sakoeChibaBand
        window: 5
    trendSpec:
      types: [SLOPE, VOLATILITY]
      timeUnit: HOURS
```

No flattening or shorthand — the nesting mirrors the Java type hierarchy. This is a power-user configuration that should look complex because it is.

### §2.3 Recursive Structures

CbrFilter.AllOf allows unlimited recursion (D72), mirroring the Java API:

```yaml
filters:
  complex:
    allOf:
      - contains: critical
      - allOf:
          - containsAny: [a, b]
          - notContains: c
```

Java runtime validation (AllOf rejects <2 filters) catches structural errors.

---

## §3 FeatureValue Type Inference

**Convention:** Inference-first with explicit override (D67). The YAML parser infers the FeatureValue variant from the YAML value shape:

| YAML Shape | Inferred Variant |
|---|---|
| `"critical"` (string) | StringVal |
| `42` or `3.14` (number) | NumberVal |
| `["a", "b"]` (string list) | StringListVal |
| `[1, 2, 3]` (number list) | NumberListVal or RangeVal (see below) |
| `{key: val}` (mapping) | StructVal |
| `[{k: v}, {k: v}]` (list of mappings) | StructListVal |

**Ambiguity resolution:** A 2-element number array is ambiguous (RangeVal vs NumberListVal). The CbrFeatureSchema's field type resolves this:
- Field declared as `Numeric` → RangeVal (`[0.5, 1.0]` = `min: 0.5, max: 1.0`)
- Field declared as `NumericList` → NumberListVal

**Explicit override:** When schema context is unavailable or when disambiguation is needed:

```yaml
# Explicit FeatureValue
features:
  range:
    type: range
    min: 0.5
    max: 1.0
  numbers:
    type: numberList
    values: [0.5, 1.0]
```

---

## §4 Scalar-or-Object Shorthand

**Convention:** Types with a dominant simple form accept either a scalar or the full object (D69).

### Confidence

```yaml
# Short form — UNKNOWN origin, no decay
confidence: 0.9

# Full form
confidence:
  origin: STATED
  value: 0.9
  decayReference: "2026-06-01T12:00:00Z"
```

Parser dispatches on YAML node type: scalar → `Confidence.of(value)`, mapping → full deserialization.

### NodeRef

```yaml
# Short form — scheme:id
refs:
  - "overlay:shared-123"

# Full form
refs:
  - scheme: overlay
    id: shared-123
    qualifier: v2
```

Parser dispatches on node type: scalar string → split on first `:`, mapping → full deserialization.

### RecurrenceRule

```yaml
# Short form — RRULE string
rrule: "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR"

# Full form
rrule:
  freq: WEEKLY
  interval: 2
  byDay: [MO, WE, FR]
```

Parser dispatches on node type: scalar string → `RecurrenceRule.parse()`, mapping → field-by-field construction.

---

## §5 SPI / Functional Interface References

**Convention:** Plain string referencing a `@Named` CDI bean (D68).

```yaml
# References @Named("linear") OutcomeWeightingFunction
outcomeWeighting: linear

# References @Named("noop") ReflectionSynthesizer
reflectionSynthesizer: noop

# References @Named("myCustomAdapter") PlanAdapter
planAdapter: myCustomAdapter
```

**Omitted field → `@DefaultBean` activates.** The YAML compiler resolves the string to a CDI bean at startup. Unknown names fail fast with a descriptive error.

Pre-built default names:

| SPI | Default Name | Implementation |
|---|---|---|
| OutcomeWeightingFunction | `linear` | DefaultOutcomeWeightingFunction |
| TrustWeightingFunction | `default` | DefaultTrustWeightingFunction |
| ReflectionSynthesizer | `noop` | NoOpReflectionSynthesizer |
| PlanAdapter | `noop` | NoOpPlanAdapter |
| PlanEnsembleAnalyzer | `noop` | NoOpPlanEnsembleAnalyzer |
| ExplanationRenderer | `default` | DefaultExplanationRenderer |
| LocalSimilarityFunction | `exactMatch` | EXACT_MATCH constant |
| TemporalRanker | (none — functional interface, composed at call site) | — |
| AgentTrustProvider | (infrastructure SPI — not agent-configurable) | — |

**DerivedEdgeRule** follows the same `@Named` pattern for now. #249 (Declarative Rule DSL) will extend this to support inline rule expressions in YAML — a rule DSL, not just a named reference.

---

## §6 Platform Type Conversion

**Convention:** External types use their natural string representation (D73).

| Type | YAML Form | Conversion |
|---|---|---|
| `Duration` | ISO-8601: `"PT24H"`, `"PT72H"` | `Duration.parse()` |
| `Instant` | ISO-8601: `"2026-06-01T12:00:00Z"` | `Instant.parse()` |
| `Path` (platform) | String path: `"/tenant/cases/incident"` | `Path.of()` |
| `DayOfWeek` | Enum name: `MO`, `TU`, `WE` | Standard mapping |
| `ChronoUnit` | Enum name: `HOURS`, `DAYS` | `ChronoUnit.valueOf()` |

---

## §7 Map-Keyed Types

YAML maps are the natural representation for `Map<String, X>` types:

### Features (Map<String, FeatureValue>)

```yaml
features:
  severity: critical
  temperature: 37.5
  tags: [urgent, cardiology]
  vitals:
    heartRate: 72
    bloodPressure: 120
```

Keys are field names (strings). Values use FeatureValue type inference (§3).

### Filters (Map<String, CbrFilter>)

```yaml
filters:
  severity:
    contains: critical
  tags:
    containsAny: [urgent, p0]
```

Keys are field names. Values use CbrFilter key-value shorthand (§2.1).

### Weights (Map<String, Double>)

```yaml
weights:
  severity: 2.0
  temperature: 1.0

# PersonalityWeights (Map<MemoryDomain, Double>)
personalityWeights:
  experience: 1.5
  relationship: 0.8
  mood: 1.2
```

MemoryDomain keys are plain strings (MemoryDomain is a string-wrapped record).

### CategoricalTable (Map<String, Map<String, Double>>)

```yaml
similarity:
  type: categoricalTable
  similarities:
    red:
      blue: 0.3
      green: 0.5
    blue:
      green: 0.7
```

Symmetric matrix — the parser mirrors entries (`red→blue: 0.3` implies `blue→red: 0.3`).

---

## §8 Complete YAML Examples

### §8.1 CbrFeatureSchema

```yaml
caseType: incident
learningRate: 0.1
fields:
  - name: severity
    type: categorical
    similarity:
      type: categoricalTable
      similarities:
        critical:
          high: 0.7
          medium: 0.3
        high:
          medium: 0.6
  - name: temperature
    type: numeric
    min: 35.0
    max: 42.0
    similarity:
      type: gaussian
      sigma: 0.5
  - name: symptoms
    type: text
    semantic: true
  - name: tags
    type: categoricalList
  - name: scores
    type: numericList
    min: 0.0
    max: 100.0
  - name: vitals
    type: timeSeries
    timestampField: timestamp
    innerFields:
      - name: heartRate
        type: numeric
        min: 40
        max: 200
      - name: oxygenSat
        type: numeric
        min: 70
        max: 100
    similarity:
      type: dtw
      constraint:
        type: sakoeChibaBand
        window: 5
    trendSpec:
      types: [SLOPE, VOLATILITY, CHANGE_POINTS]
      timeUnit: HOURS
  - name: actions
    type: discreteSequence
    similarity:
      type: editDistance
      substitutionSimilarities:
        triage:
          assess: 0.8
        assess:
          diagnose: 0.7
      insertCost: 0.5
      deleteCost: 0.5
  - name: patient
    type: nestedObject
    innerFields:
      - name: age
        type: numeric
        min: 0
        max: 120
      - name: gender
        type: categorical
  - name: treatments
    type: objectList
    innerFields:
      - name: drug
        type: categorical
      - name: dosage
        type: numeric
        min: 0
        max: 10000
```

### §8.2 CbrQuery

```yaml
tenantId: hospital-1
domain: cbr
caseType: incident
topK: 10
minSimilarity: 0.6
vectorWeight: 0.3
retrievalMode: HYBRID
fusionStrategy: CC
scope: "/hospital-1/cases/incident"
features:
  severity: critical
  temperature: 38.5
  tags: [urgent, cardiology]
filters:
  severity:
    contains: critical
  tags:
    containsAny: [urgent, p0]
weights:
  severity: 2.0
  temperature: 1.0
temporalDecay:
  type: halfLife
  halfLife: PT720H
scopeDecay:
  type: exponential
  base: 0.5
```

### §8.3 MindMap NodeInput

```yaml
name: Grandma
subgraphId: sg-family
confidence: 0.9
traits: [person, elder]
refs:
  - "overlay:shared-123"
  - scheme: memory
    id: mem-456
validFrom: "2026-01-01T00:00:00Z"
pad:
  pleasure: 0.8
  arousal: 0.3
  dominance: 0.5
properties:
  age: "82"
  role: matriarch
```

### §8.4 MindMap Vocabulary

```yaml
edgeTypes:
  - canonical: knows
    aliases: [knows-about, familiar-with]
    defaultDecayHalfLifeDays: 365
  - canonical: works-with
    aliases: [colleague-of]
    defaultDecayHalfLifeDays: 180
  - canonical: child-of
```

### §8.5 MemoryInput

```yaml
entityId: alice
domain: experience
tenantId: family-1
text: "Observed grandma smiling at the garden"
confidence: 0.85
caseId: case-42
attributes:
  event-type: observation
  subject: grandma
  capability: social-perception
pad:
  pleasure: 0.7
  arousal: 0.2
  dominance: 0.4
```

### §8.6 PersonalityWeights + MoodState

```yaml
# PersonalityWeights
personalityWeights:
  experience: 1.5
  relationship: 0.8
  mood: 1.2
  affect: 1.0

# MoodState
mood:
  agentId: alice
  tenantId: family-1
  cause: "received praise from team lead"
  pleasure: 0.8
  arousal: 0.3
  dominance: 0.6

# MoodBaseline
baseline:
  pleasure: 0.0
  arousal: -0.2
  dominance: 0.3
```

---

## §9 SealedHierarchyModule Design

### Purpose

A victools `Module` implementation that generates JSON Schema `oneOf` + `const` discriminator for Java sealed interfaces. Handles the 8 sealed hierarchies in neocortex cognitive types.

### Module Location

New module: `schema-generator/` in neocortex (D70). Contains:
- `SealedHierarchyModule` — victools Module for sealed interface → oneOf + discriminator
- `EnumInliningModule` — copy of engine's 20-line enum handler (inline enum values, no $ref)
- `CognitiveSchemaGenerator` — wires modules together, generates schema for cognitive types
- `ShorthandModule` — handles scalar-or-object polymorphic types (Confidence, NodeRef, RecurrenceRule)

### SealedHierarchyModule Behaviour

For each type that is a `sealed interface` or `sealed abstract class`:

1. Discover permitted subtypes via `Class.getPermittedSubclasses()`
2. For each subtype, generate the subtype's schema via `context.resolve(subtype)` + `createDefinitionReference()`
3. Add a `const` property `type` with the discriminator value (derived from the subtype's simple name per the naming convention in §2)
4. Compose all subtypes into a `oneOf` array on the parent type's schema
5. Set `type: object` and `additionalProperties: false` on each subtype schema

```java
public class SealedHierarchyModule implements Module {

    private final Map<Class<?>, Map<Class<?>, String>> discriminatorOverrides;

    public SealedHierarchyModule() {
        this(Map.of());
    }

    public SealedHierarchyModule(Map<Class<?>, Map<Class<?>, String>> overrides) {
        this.discriminatorOverrides = overrides;
    }

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
        builder.forTypesInGeneral()
            .withCustomDefinitionProvider((type, context) -> {
                Class<?> erasedType = type.getErasedType();
                if (!erasedType.isSealed()) {
                    return null;
                }
                ObjectNode schema = context.getGeneratorConfig().createObjectNode();
                ArrayNode oneOf = schema.putArray("oneOf");

                for (Class<?> subtype : erasedType.getPermittedSubclasses()) {
                    ObjectNode subtypeEntry = oneOf.addObject();
                    ArrayNode allOfArr = subtypeEntry.putArray("allOf");

                    // Discriminator const
                    ObjectNode discriminatorObj = allOfArr.addObject();
                    ObjectNode props = discriminatorObj.putObject("properties");
                    String discriminatorValue = resolveDiscriminator(erasedType, subtype);
                    props.putObject("type").put("const", discriminatorValue);
                    discriminatorObj.putArray("required").add("type");

                    // Subtype schema reference
                    allOfArr.addObject().set("$ref",
                        context.createDefinitionReference(
                            context.resolve(subtype)));
                }

                return new CustomDefinition(schema);
            });
    }

    private String resolveDiscriminator(Class<?> parent, Class<?> subtype) {
        Map<Class<?>, String> overrides = discriminatorOverrides.getOrDefault(
            parent, Map.of());
        return overrides.getOrDefault(subtype, defaultDiscriminator(subtype));
    }

    private static String defaultDiscriminator(Class<?> subtype) {
        String name = subtype.getSimpleName();
        // lowerCamelCase: first char lowercase
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
```

**Discriminator overrides** allow shortened names where the full class name is verbose (e.g., `GaussianDecay` → `gaussian`). The override map is passed at construction:

```java
new SealedHierarchyModule(Map.of(
    SimilaritySpec.class, Map.of(
        GaussianDecay.class, "gaussian",
        StepDecay.class, "step",
        ExponentialDecay.class, "exponential",
        DtwSpec.class, "dtw",
        EditDistanceSpec.class, "editDistance"
    ),
    WarpingConstraint.class, Map.of(
        ItakuraParallelogram.class, "itakura"
    )
));
```

### ShorthandModule Behaviour

For types that accept scalar-or-object (§4):

```java
public class ShorthandModule implements Module {
    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
        builder.forTypesInGeneral()
            .withCustomDefinitionProvider((type, context) -> {
                Class<?> erasedType = type.getErasedType();
                if (erasedType == Confidence.class) {
                    return confidenceSchema(context);
                }
                if (erasedType == NodeRef.class) {
                    return nodeRefSchema(context);
                }
                if (erasedType == RecurrenceRule.class) {
                    return recurrenceRuleSchema(context);
                }
                return null;
            });
    }

    private CustomDefinition confidenceSchema(SchemaGenerationContext context) {
        ObjectNode schema = context.getGeneratorConfig().createObjectNode();
        ArrayNode oneOf = schema.putArray("oneOf");
        // Short form: scalar number
        oneOf.addObject().put("type", "number").put("minimum", 0).put("maximum", 1);
        // Full form: object with origin, value, decayReference
        ObjectNode full = oneOf.addObject();
        full.put("type", "object");
        ObjectNode props = full.putObject("properties");
        props.putObject("origin").put("type", "string")
            .putArray("enum").add("STATED").add("INFERRED")
            .add("SPECULATED").add("UNKNOWN");
        props.putObject("value").put("type", "number")
            .put("minimum", 0).put("maximum", 1);
        props.putObject("decayReference").put("type", "string")
            .put("format", "date-time");
        full.putArray("required").add("origin").add("value");
        return new CustomDefinition(schema);
    }
}
```

### CognitiveSchemaGenerator Wiring

```java
public class CognitiveSchemaGenerator {
    private final SchemaGenerator schemaGenerator;

    public CognitiveSchemaGenerator() {
        SchemaGeneratorConfigBuilder builder =
            new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);

        builder.with(Option.DEFINITIONS_FOR_ALL_OBJECTS);
        builder.with(new JakartaValidationModule(
            JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        builder.with(new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_ORDER));

        builder.with(new EnumInliningModule());
        builder.with(new SealedHierarchyModule(DISCRIMINATOR_OVERRIDES));
        builder.with(new ShorthandModule());

        this.schemaGenerator = new SchemaGenerator(builder.build());
    }

    public JsonNode generate(Class<?> rootType) {
        return schemaGenerator.generateSchema(rootType);
    }
}
```

---

## §10 Shared Module Extraction Assessment

**Decision (D70):** No extraction now. The shared surface between engine and neocortex is two classes:
- `EnumInliningModule` — 20 lines, trivially duplicated
- `SealedHierarchyModule` — new, neocortex-specific (engine has no sealed hierarchies in its schema today)

**Extraction trigger:** When a third consumer appears (e.g., eidos needs schema generation) or when the engine adopts sealed hierarchies, extract `EnumInliningModule` and `SealedHierarchyModule` into `casehub-schema-generator`. Until then, duplication is cheaper than coordination.

---

## §11 DerivedEdgeRule — Convention Only

DerivedEdgeRule follows the `@Named` pattern (§5) for now:

```yaml
derivedEdgeRules:
  - personKnowsFriend
  - organizationHierarchy
```

Each string resolves to `@Named("personKnowsFriend") DerivedEdgeRule` via CDI.

Issue #249 (Declarative Rule DSL) will extend this to support inline rule expressions — a rule DSL where YAML replaces Java lambdas. This issue only establishes the naming convention.

---

## §12 Convention Summary

| Concern | Convention | Decision |
|---|---|---|
| Key naming | camelCase | D65 |
| Sealed discriminator property | `type` | D66 |
| Discriminator values | lowerCamelCase of variant name, simplified | D66 |
| FeatureValue | Inference-first, schema-resolved ambiguity | D67 |
| SPI references | `@Named` CDI bean string | D68 |
| Shorthand types | Scalar-or-object per type | D69 |
| SealedHierarchyModule placement | neocortex schema-generator module | D70 |
| Nested sealed depth | Accept 3-level as-is | D71 |
| Recursive structures | Mirror Java, unlimited | D72 |
| Platform types | String-to-type conversion | D73 |
| CbrFilter syntax | Key-value shorthand | §2.1 |
| Map-keyed types | Natural YAML maps | §7 |
| Enum values | Java constant name | §1 |

---

## References

- casehubio/neocortex#246 — API-to-YAML audit (8 blockers)
- casehubio/neocortex#247 — this issue
- casehubio/neocortex#249 — Declarative rule DSL (extends §11)
- engine/generator/src/main/java/io/casehub/generator/CaseHubSchemaGenerator.java — victools wiring pattern
- engine/generator/src/main/java/io/casehub/generator/module/EnumInliningModule.java — enum inlining module
- engine/generator/src/main/java/io/casehub/generator/module/WorkerSchemaModule.java — CustomDefinitionProvider pattern
- engine/api/src/test/resources/casehub/minimal.yaml — engine YAML convention (camelCase)
- engine/runtime/src/test/resources/casehub/cbr-routing-test.yaml — engine CBR YAML convention
- eidos/eval/src/test/resources/profiles/technical-writer.yaml — eidos YAML convention (camelCase)
- eidos/org-runtime/src/test/resources/gastown-org.yaml — eidos org YAML (kind discriminator)
- GE-20260824-2eb1d7 — victools custom module patterns (CustomDefinitionProvider, TypeAttributeOverride)
- GE-20260517-66d611 — Jackson ObjectMapperCustomizer mixin for sealed interfaces
- GE-20260719-1309d7 — Jackson mixin-scoped @JsonTypeInfo (separate YAML/JSON handling)
- GE-20260825-ba18b3 — Polymorphic sealed hierarchy serialization with type discriminator
- GE-20260710-31b535 — jsonschema2pojo enum fromValue() kebab-case convention
- FeatureValue.java — of(Object) type inference
- CbrFilter.java — AllOf recursive structure
- SimilaritySpec.java — nested WarpingConstraint sealed hierarchy
- Confidence.java — of(double) shorthand factory
- NodeRef.java — scheme/id string form
- RecurrenceRule.java — parse/toString for RRULE
