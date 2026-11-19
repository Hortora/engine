---
layout: post
title: "Three bugs between us and a benchmark"
date: 2026-07-04
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [bge-m3, benchmark, qdrant, debugging, retrieval]
---

The BGE-M3 benchmark was supposed to be the easy part. The model was exported, the scripts were written, the design had been reviewed. All that remained was running the engine against the garden corpus and comparing results.

It took two days to get a single data point.

The first problem was invisible. I deleted the cursor directory, started Qdrant fresh, launched the engine — and got zero collections, zero points, zero errors. No "bootstrapping with fullScan" log message, no warnings, nothing. The engine started cleanly and did nothing.

Claude traced it backward through `CollectionMigration` and `CorpusIngestionService`. The issue turned out to be an interaction between two correct behaviours. `CollectionMigration` checks the Qdrant collection at startup — if it doesn't exist, it returns early. Separately, `CorpusIngestionService` checks for a cursor file — if one exists, it calls `changesSince()` instead of `fullScan()`. Neither is wrong. But when the collection is absent and a stale cursor survives from a previous deployment, they combine to produce silent zero-ingestion. The cursor says "I've seen these files," and no collection exists to contradict it.

The cursor survived because `rm -rf /var/folders/*/T/casehub-ingestion-cursors/` silently matches nothing on macOS — the actual path has two directory levels, not one. The glob expanded to zero arguments, `rm` exited 0, and I assumed it worked.

The fix was five lines in `CollectionMigration`: if the collection doesn't exist but a cursor does, clear the cursor. After that, ingestion ran — 2083 points in 39 minutes on CPU.

Then the tokenizer. DJL's `HuggingFaceTokenizer` silently clamps `maxLength` to `modelMaxLength` when the latter isn't set. BGE-M3's tokenizer.json doesn't include `model_max_length`, so DJL defaults to 512. We were passing `maxLength=8192` and the tokenizer was ignoring it — only a WARN log, no exception. The fix was adding `modelMaxLength` to the options map in `OnnxInferenceModel`.

Then the ColBERT limit. With the tokenizer fixed and sequence length set to 1024, Qdrant rejected every upsert: `Total size of all vectors (1048576) must be less than 1048576`. ColBERT stores one 1024-dim vector per token. At 1024 tokens, that's exactly 1024 × 1024 = 1,048,576 floats — hitting Qdrant's hard cap with no configuration option to raise it. I settled on 768 tokens, which covers 89% of the corpus while staying well under the limit.

The final numbers, with all three bugs fixed:

| Approach | Precision | Latency |
|----------|-----------|---------|
| grep | unmeasurable (21–2176 hits) | — |
| Dense only | 45% | — |
| Three-leg (dense + SPLADE + BM25) | 90% | 240ms |
| BGE-M3 four-signal (768 tokens) | 87% | 50ms |

Three percentage points of precision for a 67% latency improvement and collapsing three separate models into one. The remaining gap is concentrated in SEMANTIC_WIN scenarios where SPLADE's sparse signal was slightly better than BGE-M3's learned lexical for Java-domain terminology. That's what #33 (Convex Combination fusion tuning) is for.

The benchmark that was supposed to take an afternoon took two sessions and surfaced bugs in three separate layers: cursor lifecycle, tokenizer configuration, and vector storage limits. Each was silent. Each produced zero errors. The retrieval pipeline worked perfectly — it just never received any data.
