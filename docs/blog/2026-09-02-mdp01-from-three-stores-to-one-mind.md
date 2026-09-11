---
layout: post
title: "From Three Stores to One Mind"
date: 2026-09-02
entry_type: note
subtype: diary
projects: [casehubio/neocortex]
tags: [cognitive-architecture, identity, personality, mindmap, memory, cbr, yaml, derivation]
series: issue-253-cognitive-rearchitecture
---

# From Three Stores to One Mind

*Continues from [Three Confidences Walk Into a Bar](2026-08-29-mdp01-three-confidences-walk-into-a-bar.md).*

Neocortex started as three independent subsystems: MindMap (knowledge graph), Memory (experience store), and CBR (case-based reasoning). Each had its own confidence model, its own temporal semantics, its own query surface. They shared a Maven parent and nothing else.

The cognitive rearchitecture changed that. Five days, 32 issues, one question: what would it take for an agent's identity to drive how it remembers, feels, reasons, and is curious?

## The Theory

The personality-cognition link is one of differential psychology's most replicated findings. Chamorro-Premuzic & Furnham (2004, *Personality and Intellectual Competence*) established that personality traits predict cognitive style across tasks, not just preference. Openness to Experience correlates with abstract encoding and diversive curiosity — Litman (2005, "Curiosity and the pleasures of learning") distinguishes this from specific curiosity (focused gap-filling), and the correlation with Openness is strong. Conscientiousness maps to what Riding & Rayner (1998, *Cognitive Styles and Learning Strategies*) call the analytical end of the holistic-analytical dimension — stricter thresholds, validation-focused attention, categorical organisation.

The emotional layer draws on two foundations. Mehrabian & Russell (1974, *An Approach to Environmental Psychology*) gave us the PAD model — pleasure, arousal, dominance as the three independent dimensions of emotional state. Lazarus (1991, *Emotion and Adaptation*) established appraisal theory: emotional response to events depends on the individual's assessment of relevance and coping capacity. The same event triggers different affect depending on who encounters it.

For memory, Broadbent (1958, *Perception and Communication*) showed that selective attention determines what enters encoding — personality shapes the attentional filter. Bower (1981, "Mood and Memory") demonstrated mood-congruent retrieval: current emotional state biases which memories surface. And Szpunar et al. (2014) showed that future thinking is goal-directed, not random simulation — prospective memory is shaped by current motivations.

For analogical reasoning, Gentner & Smith (2012) showed that conservative reasoners prefer literal similarity while creative reasoners exploit structural alignment across distant domains. This maps directly to CBR retrieval parameters — how far afield should the system look for relevant cases?

The gap in agent systems is that identity and cognition are typically configured independently. An eidos descriptor says the agent is analytical and risk-averse. The memory system uses hardcoded retrieval weights. Nothing connects the two.

The rearchitecture closes that gap. Identity drives cognition through a derivation chain: disposition → personality weights → mood baseline → curiosity focus → retrieval modulation → graph structure preference. One YAML file. One derivation function. Every cognitive parameter traces back to who the agent is.

## What Was Built — Five Layers

### Layer 1: Unified Confidence

Three subsystems used "confidence" to mean different things. MindMap: origin-tracked with exponential decay. Memory: a raw retrieval weight called "importance." CBR: EMA-adjusted from outcome feedback.

The fix: a single `Confidence(origin, value, decayReference)` record in `cognitive-api`, shared across all three. Every confidence value now carries provenance — STATED, INFERRED, SPECULATED, or UNKNOWN — and a decay anchor. The naming audit renamed `importance` to `confidence` across the memory stack.

### Layer 2: Temporal Architecture

Three new capabilities:

- **TemporalIndex** — cross-store chronological aggregation. Query "what changed in the last hour" across MindMap nodes, memory entries, and CBR cases in one call.
- **PAD emotional dimensions** — pleasure/arousal/dominance on every memory entry and MindMap node. Affect is no longer metadata; it's a first-class query dimension.
- **AffectTrajectoryAnalyzer** — computes slope, volatility, and trend from the affect log. Is this entity's emotional trajectory worsening, improving, or volatile?

### Layer 3: Cognitive Dynamics

The layer where stores become cognition:

- **Prospective events** — `RecurrenceRule` (RFC 5545 RRULE subset) on MindMap nodes. Scheduled events expand into future instances. The graph models what will happen, not just what did.
- **Trajectory-aware curiosity** — `CuriositySignalGenerator` dampens curiosity when affect trajectory is improving (things are going well — less need to explore) and boosts it when worsening or volatile (something's wrong — look harder).
- **TemporalFocus** — ranked attention list combining proximity, recency, and trajectory scoring. The agent's attention is finite; this decides where it goes.
- **CognitiveProfile** — cross-store entity resolution. Given a name or ID, resolve the MindMap node, all edges, memories across six domains, affect trajectory, and NodeRef following into a single `EntityKnowledge` record.

### Layer 4: Retrieval Modulation

- **ModulationFactor** — `@FunctionalInterface` composable scoring multiplier. Recency decay, confidence weighting, mood congruence, domain weighting — each is a factor. Factors compose via multiplication. `RetrievalModulator` applies them and sorts by composite score.
- **PerspectivalMerge** — the same MindMap node can carry different affect overlays per agent. Alice sees "Project Alpha" with pleasure=0.7 (opportunity). Bob sees it with pleasure=-0.3 (threat). Same node, different emotional colouring, merged at query time.

### Layer 5: Configuration Architecture

The declarative layer that ties everything together:

- **CognitiveDefaults** — a single YAML file per agent declares personality weights, mood baseline, curiosity direction, temporal focus, CBR strategy, social cognition config, graph structure preference, extraction bias, vocabulary, and service bindings. Fifteen fields, but callers use `empty()` + `withX()` methods — the same immutable-record-as-builder pattern as `CbrQuery`. Adding a new derivation output means one `withX()` method; zero callers break.
- **Declarative rule DSL** — 11 structural predicates (`hasProperty`, `propertyEquals`, `anyOf`, `allOf`, `not`, ...) for trait rules, plus two-level derived edge rules (direct flip + graph traversal). YAML rules compile to the same Java interfaces as programmatic rules. They coexist.
- **DeclarativeRuleRegistry** — loads global rules from `rules/*.yaml`, merges with per-agent overrides from cognitive profiles. Name collision = local wins.
- **CognitiveDerivationEngine** — the pure function: `DescriptorView → CognitiveDefaults`. Eight derivation pathways, each a pure map from identity to cognition: disposition profile → personality weights, disposition axes → mood baseline, autonomy/ruleFollowing/socialOrient → curiosity category weights, goals → subgraph proximity weights, ruleFollowing/riskAppetite → CBR retrieval defaults, socialOrient/conflictMode → trust and conflict interpretation, disposition profile → graph inference style, disposition profile → extraction bias. All eight produce config; `deriveAndMerge()` overlays explicit YAML overrides on derived defaults. Zero compile-time dependency on eidos.

## The Eight Connections

The derivation chain has eight connection points — each maps a facet of identity to a cognitive parameter. They aren't arbitrary. Each corresponds to a cognitive function that differential psychology has shown to vary with personality.

### 1. Attention & Encoding

**What it derives:** `MindMapExtractor` prompt biases — relationship sensitivity, affect sensitivity.

**Why it matters:** Broadbent (1958) showed that selective attention determines what enters memory encoding. An analytical agent (Ni/Te-dominant) should extract more structural relationships and patterns from text. An empathetic agent (Fe/Fi-dominant) should extract more affective annotations and emotional context. Without this connection, every agent encodes the same information regardless of who they are.

**Identity source:** Disposition profile weighted functions.

### 2. Memory Weighting ✅

**What it derives:** `PersonalityWeights` — per-domain retrieval multipliers (experience, reflection, relationship, engagement, mood).

**Why it matters:** Chamorro-Premuzic & Furnham (2004) established that personality predicts which cognitive domains dominate. An Ni-dominant agent retrieves reflections and abstract patterns first. An Fe-dominant agent retrieves relationships and social context first. This is the most direct personality→cognition link — it determines what an agent remembers when asked about something.

**Identity source:** Disposition profile → weighted average across 8 Jungian functions, each mapping to specific domain weights.

### 3. Affective Interpretation ✅

**What it derives:** `MoodBaseline` — PAD resting point (pleasure, arousal, dominance).

**Why it matters:** Lazarus (1991) showed that emotional response depends on individual appraisal. The same event — "startup acquired" — triggers opportunity (pleasure=0.7) for a bold agent and threat (pleasure=-0.3) for a conservative one. Without a personality-derived baseline, all agents have the same emotional resting state, which means they appraise events identically. Mehrabian & Russell's (1974) PAD model gives us three independent axes to differentiate.

**Identity source:** Disposition axes — riskAppetite→pleasure, socialOrient→arousal, autonomy→dominance.

### 4. Curiosity Direction

**What it derives:** `CuriositySignalGenerator` category weights — STRUCTURAL, QUALITY, CENTRALITY multipliers.

**Why it matters:** Litman (2005) distinguishes diversive curiosity (broad exploration) from specific curiosity (focused gap-filling), and correlates them with Openness to Experience. An autonomous agent should boost STRUCTURAL signals — explore the unknown. A rule-following agent should boost QUALITY signals — validate what's known. Without this, every agent is curious about the same things.

**Identity source:** Disposition axes (autonomy→STRUCTURAL, ruleFollowing→QUALITY) + goals.

### 5. Social Cognition

**What it derives:** Trust formation rate, conflict interpretation strategy.

**Why it matters:** Bowlby's (1969) attachment theory showed that individual differences in social cognition are rooted in internal working models. A cooperative agent reads negative engagement as a signal to repair. A competitive agent reads it as intelligence about the opponent. Trust formation speed varies too — high socialOrient agents build trust faster, high autonomy agents are slower to trust.

**Identity source:** socialOrient + conflictMode axes → `AgentTrustProvider` parameters.

### 6. Prospective Focus

**What it derives:** Subgraph proximity weights for future-facing attention.

**Why it matters:** Szpunar et al. (2014) showed that prospective thinking is goal-directed, not random simulation. A career-oriented agent should amplify PROJECT subgraph proximity. A family-oriented agent should amplify PERSON subgraph events. Without this, `TemporalFocus` treats all future events equally regardless of what the agent cares about.

**Identity source:** Goals + domain context → subgraph type weights.

### 7. Analogical Strategy

**What it derives:** `CbrQuery` defaults — minSimilarity, temporalDecay, retrievalMode.

**Why it matters:** Gentner & Smith (2012) showed that conservative reasoners prefer literal similarity while creative reasoners exploit structural alignment across distant domains. A rule-following agent should use high minSimilarity (only close precedents matter). A risk-tolerant agent should use lower thresholds, drawing analogies from further afield. This is the personality→reasoning link — how far the agent looks for relevant past cases.

**Identity source:** ruleFollowing + riskAppetite axes.

### 8. Graph Structure Preference

**What it derives:** Disposition-gated derived edge rule activation — connective vs categorical inference.

**Why it matters:** Riding & Rayner (1998) documented the holistic-analytical dimension of information processing. Some agents should build dense, interconnected graphs (holistic thinkers — Ni/Fe), discovering cross-domain bridges. Others should build clean, categorised structures (systematic thinkers — Te/Si), enforcing hierarchy. The same `DerivedEdgeRule` set fires differently depending on who's thinking.

**Identity source:** Disposition profile → which inference rules activate.

### Why These Eight — and Not Others

These eight map to cognitive functions that personality research has shown to vary across individuals: attention, memory retrieval, emotional appraisal, curiosity, social cognition, prospective thinking, analogical reasoning, and knowledge organisation. Each has a body of evidence connecting it to personality traits.

What we didn't include: working memory capacity, processing speed, language fluency. These are more hardware-constrained than personality-driven. They vary across individuals but not predictably from personality traits. An agent's disposition doesn't tell you how many items it can hold in working memory — that's a resource limit, not a cognitive style.

The eight connections also share a structural property: each is a pure function from identity to config. No side effects, no state, no runtime dependencies. This makes them testable, composable, and independently deployable. A connection that required runtime state or cross-connection dependencies would break the derivation model.

## How They Compose

A single YAML file declares identity and cognition together:

```yaml
descriptor:
  disposition:
    socialOrient: independent
    ruleFollowing: moderate
    riskAppetite: calculated
    autonomy: high
    conflictMode: analytical
  dispositionProfile:
    - { term: ni, weight: 0.35 }
    - { term: te, weight: 0.30 }
    - { term: fi, weight: 0.20 }
    - { term: se, weight: 0.15 }
  goals:
    - identify strategic patterns

curiosity:                        # all 8 sections derive from descriptor
  category-weights:
    STRUCTURAL: 1.3               # auto: high autonomy → explore broadly
    QUALITY: 1.0                  # auto: moderate ruleFollowing
    CENTRALITY: 1.2               # auto: strategic goal boost

cbr:
  min-similarity: 0.5             # auto: moderate ruleFollowing
  retrieval-mode: HYBRID          # auto: moderate → balanced approach

social:
  trust-formation-rate: 0.3       # auto: independent → slower trust
  conflict-interpretation: NEUTRAL # auto: analytical

personality:                      # explicit overrides win over derived
  reflection: 1.5

traitRules:
  - trait: StrategicThinker
    when:
      hasEdgeTypes: [analyses, evaluates]
```

The loader reads this file, runs the derivation engine to fill all eight cognitive parameters from the descriptor, merges explicit overrides (personality, curiosity, etc.), registers vocabulary, and produces CDI beans. One file, one agent, full cognitive configuration.

## What's Novel

**Disposition-driven cognition.** I haven't seen another agent framework where personality weights, mood baseline, curiosity direction, temporal focus, case-based reasoning strategy, trust formation, graph structure preference, and extraction bias all derive from a single identity descriptor via a research-grounded mapping function. Eight cognitive parameters, each backed by a specific finding in differential psychology, each a pure function from identity to config. Most systems treat these as independent config knobs.

**Declarative rules that coexist with programmatic rules.** The 11-predicate condition DSL and two-level derived edge rules compile to the same Java interfaces. A platform ships global rules; individual agents override by name. No runtime penalty for the abstraction — a declarative rule evaluates the same sealed-interface pattern match as a hand-written one.

**Cross-store cognitive queries.** `CognitiveProfile` resolves an entity across MindMap, Memory (six domains), CBR, and affect trajectory in one call. `TemporalFocus` ranks attention across all stores. These are the queries a cognitive system actually needs — not per-store lookups with manual joins.

**Perspectival affect.** The same knowledge graph node carrying different emotional colouring per agent, merged at query time via `PerspectivalMerge`. This isn't just configuration — it's a structural claim about how subjective experience relates to shared knowledge.

## All Eight — Wired

The derivation engine now maps all eight connection points. Each is a pure function from descriptor to config — no side effects, no state, 41 unit tests across the full derivation surface.

| Connection | What it derives | From what |
|---|---|---|
| ✅ Memory weighting | `PersonalityWeights` domain multipliers | Disposition profile (Ni→reflection, Fe→relationship, etc.) |
| ✅ Mood baseline | `MoodBaseline` PAD resting point | Disposition axes (riskAppetite→pleasure, autonomy→dominance) |
| ✅ Curiosity direction | `CuriositySignalGenerator` category weights | Goals + disposition (autonomy→STRUCTURAL, ruleFollowing→QUALITY) |
| ✅ Prospective focus | Subgraph proximity weights in `CuriositySignalGenerator` | Goals (career→PROJECT, family→PERSON, research→RESEARCH_AREA) |
| ✅ CBR strategy | `CbrStrategyDefaults` (minSimilarity, temporalDecay, retrievalMode) | ruleFollowing + riskAppetite (Gentner's analogical reasoning dimension) |
| ✅ Social cognition | Trust formation rate, `ConflictInterpretation` enum | socialOrient + conflictMode (Bowlby's attachment dimension) |
| ✅ Graph structure | `InferenceStyle` (CONNECTIVE/CATEGORICAL/BALANCED) | Disposition profile (holistic Ni/Fe→bridges, systematic Te/Si→categories) |
| ✅ Extraction bias | `ExtractionBiasDefaults` relationship + affect sensitivity | Disposition profile (analytical→more edges, empathetic→more affect) |

Two of those — curiosity direction and prospective focus — are wired end-to-end. `CuriositySignalGenerator` reads the derived category weights and subgraph proximity weights at runtime, scaling signal scores by the agent's disposition. The other four produce config that nothing yet consumes. The types exist, the derivation runs, the config lands in `CognitiveDefaults` — but no runtime code reads `CbrStrategyDefaults` to set query defaults or `SocialCognitionDefaults` to calibrate trust formation. Those are tracked as follow-up work: four issues filed, one per subsystem.

The `CognitiveDefaults` record grew to 15 fields during this work. That's the record holding everything an agent needs for cognitive configuration — personality, mood, curiosity, temporal focus, CBR strategy, social cognition, graph structure, extraction bias, plus vocabulary, services, rules, and the source descriptor. Adding a field used to break every caller. Now it has `empty()` + `withX()` methods following the same pattern as `CbrQuery`, so future additions touch one method, not twelve call sites.

## Where This Goes

Three architectural directions remain:

- **Consumer wiring.** Four derivation outputs produce config that nothing reads yet. Each needs a design decision about how derived defaults flow to runtime consumers — `CbrStrategyDefaults` to query construction, `SocialCognitionDefaults` to trust formation, `GraphStructureDefaults` to rule gating, `ExtractionBiasDefaults` to LLM prompt construction. The derivation side is pure; the consumption side crosses into CDI wiring and prompt engineering.
- **Hot-reload.** V1 requires restart for config changes. Quarkus's `@ConfigMapping` with file watching could make cognitive profiles reloadable — change an agent's curiosity direction and see the effect without restarting.
- **Multi-agent perspectival queries.** `PerspectivalMerge` handles one agent's overlay at a time. The next step is comparative queries — "how does Alice's emotional colouring of this entity differ from Bob's?" — which is a social cognition primitive.

The deeper point: cognitive architecture shouldn't be bolted on. It should emerge from identity.

## References

- Bower, G. H. (1981). Mood and Memory. *American Psychologist*, 36(2), 129–148.
- Broadbent, D. E. (1958). *Perception and Communication*. Pergamon Press.
- Chamorro-Premuzic, T., & Furnham, A. (2004). *Personality and Intellectual Competence*. Lawrence Erlbaum Associates.
- Gentner, D., & Smith, L. (2012). Analogical Reasoning. In V. S. Ramachandran (Ed.), *Encyclopedia of Human Behavior* (2nd ed.). Elsevier.
- Lazarus, R. S. (1991). *Emotion and Adaptation*. Oxford University Press.
- Litman, J. A. (2005). Curiosity and the pleasures of learning. *Cognition and Emotion*, 19(6), 793–814.
- Mehrabian, A., & Russell, J. A. (1974). *An Approach to Environmental Psychology*. MIT Press.
- Riding, R., & Rayner, S. (1998). *Cognitive Styles and Learning Strategies*. David Fulton Publishers.
- Szpunar, K. K., Spreng, R. N., & Schacter, D. L. (2014). A taxonomy of prospection. *Frontiers in Human Neuroscience*, 8, 590.
