---
layout: post
title: "Scoring the benchmark, then fixing what it found"
date: 2026-06-30
type: phase-update
entry_type: note
subtype: diary
projects: [hortora-engine]
tags: [retrieval, benchmark, gardenSearch]
---

## The numbers land

I scored all 224 entries from the three-leg benchmark — 14 real-world scenarios, each queried with both keywords and natural language, 8 results per query. The rubric is simple: 0 for noise, 1 for tangentially related, 2 for directly relevant (would have influenced the work).

Results: 89.3% relevant precision, 64.3% highly relevant, 10.7% noise.

Twelve of fourteen scenarios hit 87.5% precision or higher. Two weak spots stood out — spec1-d4 keyword queries at 25% (the word "scan" matches bytecode scanning, attestation aggregation, and the actual store-scan-pagination problem the scenario targets), and spec2-d4 keyword queries at 50% (ObjectMapper entries contaminating ExceptionMapper results). Both are keyword polysemy, not a systemic retrieval problem.

## The grep comparison flips

The #27 benchmark — dense-only gardenSearch vs grep — ended in a draw. grep averaged 65% precision, gardenSearch-NL averaged 62%, and the head-to-head was 6-6-2. gardenSearch-KW was a disaster at 32%, losing 12 of 14 scenarios.

Three-leg changes this completely. gardenSearch-KW jumped from 32% to 87.5% average precision. gardenSearch-NL went from 62% to 96.4%. The 6-6-2 draw is gone — gardenSearch now wins on signal quality across the board.

BM25 did the heavy lifting. The keyword catastrophe was always about Java identifiers — `ChatModel`, `@DefaultBean`, `ConcurrentHashMap` — that dense embeddings couldn't interpret as domain vocabulary. BM25 matches these as literal tokens. That's the entire fix.

grep's remaining advantage is structural: it searches a larger file set (6,700+ files vs 2,000 indexed garden entries) and has no result cap. For the AI assistant use case — where the LLM processes the full result set and benefits from high signal-to-noise — these advantages matter less than the 89% vs 65% precision gap.

## Adaptive result extension

The benchmark exposed a different problem: the fixed 8-result cap. grep's #27 advantage included 38 unique score-2 entries that gardenSearch couldn't surface because they ranked 9th or lower. Some domains have 15+ genuinely relevant entries — cutting at 8 loses the tail.

The fix: gardenSearch now over-fetches 2x the requested limit from Qdrant, then walks the score distribution past the cutoff. If the gap between consecutive relevance scores is less than 0.05 and the next result is still above the relevance floor, it gets included. The extension stops at the first significant score drop-off.

The response also includes a metadata signal — total count of results above the relevance threshold — so the caller knows when more exist beyond what was returned. An LLM seeing "8 results (16 above relevance threshold)" can re-query with a higher limit if the domain is deep.

The scoring work, then the comparison analysis, then the feature implementation — each step motivated the next. The benchmark wasn't just measurement; it was the specification for what to build.
