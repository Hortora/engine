---
layout: post
title: "The Vocabulary Gap That Wasn't"
date: 2026-08-03
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [embedding, retrieval, evaluation, tokenizer, vocabulary-gap]
---

The premise was clean: BERT WordPiece fragments Java identifiers into nonsense — `ConcurrentHashMap` becomes `concurrent ##has ##hma ##p` — and that fragmentation degrades embedding retrieval. Hortora/engine#27 demonstrated it with 14 scenarios. The fix seemed obvious: find an embedding model trained on code, one that keeps `HashMap` as a single token.

We ran a four-layer evaluation across six models: two baselines (nomic-embed-text and BGE-M3) and four code-domain candidates (CodeBERT, UniXcoder, jina-code, nomic-v1.5). Two thousand four hundred garden entries, frozen as a snapshot. Fourteen retrieval scenarios with human-judged relevance scores.

The tokenizer results confirmed the hypothesis perfectly. UniXcoder and jina-code handle CamelCase as word boundaries — `ConcurrentHashMap` stays as two meaningful tokens. `@DefaultBean` becomes `@ Default Bean`, not the four meaningless fragments WordPiece produces. BGE-M3's SentencePiece tokenizer — supposedly the upgrade path — turned out to be the worst of the lot: 5.3 average tokens per identifier, worse than WordPiece's 4.8. That was the first surprise.

The second was the discrimination test. I designed ten pairs: concepts that should embed far apart (`@DefaultBean` vs the English word `default`) and concepts that should embed close (`ConcurrentHashMap` vs "thread-safe map for lock-free concurrency"). Every model except jina-code got the polarity wrong — scoring superficially similar pairs higher than semantically related ones. jina-code was the only model that understood `@DefaultBean` is dissimilar to `default` (0.40 cosine) while `ConcurrentHashMap` is similar to its natural language description (0.82 cosine). The gap was 0.009 in the right direction. Every other model was negative.

Then the retrieval benchmark broke everything.

UniXcoder — best tokenizer, 3.1 average tokens per identifier — scored 26.6% precision. nomic-embed-text — mediocre tokenizer, 4.8 average — scored 60.4%. The model that can't even tokenize Java identifiers correctly retrieves relevant entries more than twice as well as the one that tokenizes them perfectly.

CodeBERT was worse: 2.0% precision. Effectively random. It produces embeddings where everything clusters together — all-pair cosine similarity above 0.94 in the discrimination test. It has no discriminative power for retrieval at all, despite being pre-trained on six programming languages.

jina-code — the one model with both good tokenization and correct discrimination — managed 54.1%. Better than the other code models, but still 6 percentage points below nomic-embed-text. The model that genuinely understands Java semantics at the embedding level can't translate that understanding into retrieval precision on a technical prose corpus.

The reason is the training objective. nomic-embed-text was fine-tuned with contrastive learning on massive text-pair datasets — it learned to produce embedding spaces where similar documents cluster and dissimilar ones separate. CodeBERT and UniXcoder were trained with masked language modelling on code — they learned to predict missing tokens, not to discriminate between documents. Tokenization determines how identifiers are represented internally; the training objective determines the geometry of the embedding space. Retrieval needs geometry, not representation.

BGE-M3 scored 56.4% — slightly below nomic's 60.4% despite having four times the parameters. Its value was always supposed to be multi-modal (dense + sparse + ColBERT from one model), but the evaluation confirmed its dense embeddings are not an upgrade. The sparse and ColBERT modes don't rescue the discrimination problem either — zero token overlap on semantically related pairs like `@DefaultBean` and "CDI ambiguous dependency resolution."

The recommendation is straightforward: stay with the current three-leg retrieval system. nomic-embed-text dense + SPLADE sparse + BM25 keyword matching already delivers 94% precision. BM25 handles the vocabulary gap scenarios directly — it matches `ConcurrentHashMap` as an exact keyword, bypassing the embedding problem entirely. Swapping the dense model provides at most a marginal improvement to one leg of a three-leg system.

BGE-M3 adoption still makes sense — but for model consolidation (one model replacing three), not for dense embedding quality. The dense leg might slightly degrade; the operational simplification justifies it.

The deeper takeaway is about where the vocabulary gap actually matters. The gap is real in tokenization. It's real in discrimination. It's irrelevant for end-to-end retrieval when BM25 keyword matching is in the pipeline. The three-leg architecture doesn't just compensate for weak dense embeddings on code vocabulary — it makes the entire question of code-domain embedding models moot. The system already solved the problem, just not where I expected.
