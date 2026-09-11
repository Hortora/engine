---
layout: post
title: "The First Five Minutes"
date: 2026-08-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [sc2, strategy-classifier, onnx, inference, fog-of-war, cnn, game-ai]
---

## The First Five Minutes

A StarCraft game is won or lost in the opening. By minute five, the opponent's strategy is committed — the tech path chosen, the army composition decided, the timing attack loaded. The defender's job is to recognise what's coming before it arrives. Humans do this by pattern-matching: a Terran with two Barracks and no expansion is rushing. A Zerg with an early Spire is going Mutalisks. A Protoss with a fast Dark Shrine means invisible units are about to walk into your base.

This is a solved problem for human players with thousands of hours of practice. It is not solved for AI agents. An AI agent playing StarCraft needs to identify the opponent's strategy from partial observations, under fog of war, in real time — and the classification has to be fast enough to run every game second without blocking the decision loop.

That's what the strategy classifier does. It's a small CNN-Attention model that takes the first five minutes of visible game state and outputs a probability distribution over strategy archetypes. MECH_PUSH. BIO_TIMING. LING_BANE. CANNON_RUSH. One model per matchup — three models in total, each under 600KB as ONNX, each running inference in under a millisecond on JVM via ONNX Runtime.

## The Fog-of-War Problem

The obvious approach is to train on the full game state — both players' buildings and units at every second. The model sees everything; it learns the patterns. But this doesn't transfer to gameplay. An AI agent doesn't have god-mode vision. It sees the opponent's base only when it scouts, and in the early game that means a single worker walking across the map, arriving around minute two, and staying for maybe twenty seconds before being chased away.

The first version of the fog-of-war simulation reflected this literally. The scout arrives, the mask opens, the scout leaves, the mask closes. Opponent buildings blink into existence during scouting and vanish when the scout retreats. Early-game visibility was around 12%.

This is wrong. A real player doesn't forget what they saw. If the scout spots a Factory at minute two, that Factory is still there at minute three even though the scout has left. Knowledge is cumulative.

The fix was straightforward: the scouting mask ramps up and never drops back. First scout arrival jumps visibility to 30–50%. Each subsequent visit increases it toward full. Buildings seen once stay seen. This single change pushed early-game visibility from 12% to 30–50%, and BANSHEE_HARASS accuracy — which depends entirely on recognising a StarportTechLab before the Banshee arrives — went from 0% to 61%.

## The Model

The architecture is compact. Each 30-second window of game state produces a feature vector of 239 dimensions: own building counts, scouted opponent building counts (masked by fog-of-war), and a binary vision indicator. Ten windows cover five minutes of game time.

Two 1D convolutions extract local temporal patterns — the kind of thing a build order produces, where the sequence of Barracks → Factory → Starport matters as much as their presence. Sinusoidal positional encoding marks where in the game each window falls. A single-head attention layer learns which windows matter most — typically the ones immediately after scouting, when the most new information arrives.

The result is pooled (ignoring padded windows for short games), concatenated with four map features, and pushed through a two-layer classifier. The whole thing has fewer parameters than most embedding layers. Temperature calibration after training bakes honest probability estimates into the final weights — a 0.7 confidence means the model is right 70% of the time, not that the softmax happened to peak there.

## Labelling at Scale

The training data comes from SC2EGSet — professional tournament replays from IEM Katowice, DreamHack Masters, and other events spanning 2019–2022. Each replay is a JSON containing every game event: every building placed, every unit produced, every upgrade started, timestamped in game loops.

Labelling 2400+ replays by hand is impractical. Instead, a rule-based labeller examines the opponent's build order and assigns strategy archetypes using the same heuristics a human analyst would: Roach Warren before minute 5.5 is ROACH_RUSH. Banshee unit produced is BANSHEE_HARASS. CC-first before Factory is MACRO_ECONOMY. The rules are specific to each race because each race's tech tree produces different strategic signatures.

For replays the rules can't classify — unusual openings, hybrid strategies, or timing-dependent distinctions that need context beyond build order — an LLM labelling pass handles the ambiguous remainder. The pipeline labels each replay from both players' perspectives, doubling the dataset.

## Where This Fits

The classifier isn't a standalone curiosity. It's the first tier of a three-level decision cascade in quarkmind — the game AI engine built on top of casehub's CBR memory layer.

**Tier 1: Local ONNX** — the strategy classifier runs every game second. Sub-millisecond inference, zero network latency. It answers the fast question: what is the opponent doing right now?

**Tier 2: Inference tasks** — deeper analysis models (NLI, reranking) that run periodically. They answer: given this strategy, what have I done before that worked?

**Tier 3: LLM** — the heavy reasoner for novel situations. It answers: the opponent is doing something I haven't seen — what should I try?

The CBR layer ties it together. Strategy classification becomes a retrieval feature: "show me past games where the opponent opened MECH_PUSH on this map." The classifier's output is a structured field in the CbrCase feature vector, enabling similarity queries like "what did I do last time I faced LING_BANE with this army composition?" The ScoredCbrCase that comes back carries a plan — an adapted sequence of production and positioning decisions that worked before.

## Honest Numbers

The vs_terran model currently sits at 54% top-1 accuracy with 94% top-3. BANSHEE_HARASS jumped from 12% to 61% with cumulative fog-of-war. The vs_zerg model is at 74% top-1 with ROACH_RUSH at 88%. The dominant classes are approaching useful; rare archetypes remain hard.

This is the expected shape. Tournament games oversample standard play. Rushes, air-heavy builds, and economic greed are rare because professionals punish them. Class-weighted focal loss with sqrt-capped inverse frequency helps, but the data imbalance is structural. More tournament packs and the LLM labelling pass for edge cases will close the gap.

Top-3 at 94% matters because the cascade doesn't need a single answer. It needs a ranked short list. If the classifier says "probably MECH_PUSH, maybe BIO_TIMING, possibly TECH_RUSH" — that's enough for the CBR retrieval to pull relevant past games for all three possibilities and let the deeper models disambiguate.

The five-minute window is deliberately conservative. Most strategy commitments are visible by minute three, and the model operates on whatever windows are available. At minute two, it has partial information and says so through its confidence scores. By minute five, the prediction is usually decisive. The game starts responding to the classification in real time, adjusting production and scouting based on the evolving probability distribution.

What's left is more data, more replays, and the LLM pass for the long tail. The architecture and the fog-of-war model are stable. The inference pipeline is production-ready — ONNX models load in the JVM test suite, classify random inputs in under a millisecond, and produce calibrated probabilities that sum to 1.0. The question now is how far the accuracy ceiling can move with better training data and richer labelling.
