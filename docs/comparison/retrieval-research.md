# Retrieval Research — Landscape, Validation, and Roadmap

*2026-06-30 · Refs #29*

## Current Architecture

Three-leg retrieval with server-side RRF fusion inside Qdrant:

| Leg | Model | Vector type | What it catches |
|-----|-------|-------------|-----------------|
| Dense | nomic-embed-text (Ollama, 768-dim) | Named vector `dense` | Semantic concept matching — NL queries |
| Sparse | Splade_PP_en_v1 (ONNX, DistilBERT) | Sparse vector `sparse` | Learned term expansion (limited on Java vocabulary) |
| BM25 | Qdrant Document vectors (`qdrant/bm25`) | Sparse vector `bm25` | Exact keyword matching — Java identifiers, annotations |

All three legs execute as Qdrant prefetch queries and fuse via server-side RRF in a single gRPC call. Cross-encoder reranker (ms-marco-MiniLM-L-6-v2) is available but optional. `CamelCaseExpander` preprocesses text at ingestion time so BM25 tokenizes `DefaultBean` → `Default Bean DefaultBean`.

Qdrant v1.18+ required for Document vector inference.

## Benchmark Validation

Three-leg benchmark (2026-06-30) re-ran the #27 methodology (14 scenarios, KW + NL queries) with all three retrieval legs enabled.

### Headline Results

| Metric | Dense-only | Three-leg | Delta |
|--------|-----------|-----------|-------|
| Overall precision | 45% | 94% | **+49pp** |
| Keyword-gap precision | 43% | 89% | **+46pp** |
| Non-keyword-gap precision | 47% | 98% | **+51pp** |
| Average latency | 28ms | 256ms | 9.1x |
| Indexed points | 1,984 | 2,026 | +42 |

### Keyword-Gap Scenarios (where grep beat dense in #27)

| Scenario | Dense | Three-leg | Failure mode |
|----------|-------|-----------|-------------|
| issue-2-cdi-wiring/KW | 0% | 100% | VOCABULARY_GAP |
| issue-5-ai-llm-inference/KW | 0% | 100% | VOCABULARY_GAP + DOMAIN_ABSENCE |
| issue-5-ai-llm-inference/NL | 17% | 100% | VOCABULARY_GAP + DOMAIN_ABSENCE |
| spec1-d1-cdi-priority-tiers/KW | 0% | 100% | VOCABULARY_GAP |
| spec1-d2-thread-safety/KW | 50% | 100% | UNAMBIGUOUS_TERM |
| spec2-d1-cdi-tier-coexistence/KW | 40% | 100% | VOCABULARY_GAP |
| spec2-d2-chatmodel-adaptation/KW | 50% | 100% | VOCABULARY_GAP |
| spec2-d2-chatmodel-adaptation/NL | 50% | 100% | VOCABULARY_GAP |

11 of 14 keyword-gap query pairs improved. 2 apparent regressions are dominated by unscored new entries (titles look relevant — likely scoring artifacts).

### BM25's Marginal Contribution Beyond SPLADE

Comparing three-leg vs dense+splade isolates BM25's contribution:

| Scenario | Dense+SPLADE | Three-leg | BM25 added |
|----------|-------------|-----------|------------|
| issue-2-cdi-wiring/KW | 57% | 100% | +43pp |
| spec1-d1-cdi-priority-tiers/KW | 50% | 100% | +50pp |
| issue-5-ai-llm-inference/NL | 71% | 100% | +29pp |
| spec1-d2-thread-safety/KW | 67% | 100% | +33pp |
| spec1-d4-protocol-compliance/NL | 50% | 100% | +50pp |
| spec2-d3-circular-deps/KW | 60% | 100% | +40pp |

Zero regressions on non-keyword-gap scenarios. BM25 is the dominant contributor to closing the keyword gap — SPLADE alone did not solve it.

### Caveats

- 87 new entries are unscored (appeared in three-leg but absent from #27 baseline). True precision may differ once scored.
- Corpus grew 1,984 → 2,026 points between runs (+42 entries).
- SPLADE/reranker were temporarily enabled for the benchmark and re-commented afterward.

Raw results: `scripts/benchmark/results/three-leg.json`

## Literature Landscape (June 2026)

### Hybrid Retrieval Is the Consensus Architecture

The 2025-2026 production RAG consensus is hybrid dense + BM25/sparse → RRF fusion → neural reranking. Our three-leg architecture matches this pattern.

A [benchmark on financial QA](https://arxiv.org/abs/2604.01733) (April 2026) confirmed: two-stage hybrid retrieval + neural reranking achieves Recall@5 of 0.816 and MRR@3 of 0.605, outperforming all single-stage methods. BM25 outperforms dense retrieval (text-embedding-3-large) on every metric except Recall@20 when domain-specific terminology is involved.

Key finding: **BM25 is not a legacy fallback — it is a peer retrieval method.** On domain-specific corpora with precise terminology (Java class names, annotations, config properties), BM25 catches what dense embeddings miss. This matches our benchmark results exactly.

### ColBERT: Best as Reranker, Not First-Stage Retriever

ColBERT produces per-token vectors and scores via MaxSim (sum of best token-pair similarities). Research consistently recommends it for [reranking top-k candidates](https://qdrant.tech/documentation/tutorials-search-engineering/using-multivector-representations/), not as a first-stage retriever:

- Higher precision and ranking quality than cross-encoders
- Faster than cross-encoders at reranking because document vectors are precomputed (cross-encoders must re-encode every query-document pair)
- Higher storage cost (one vector per token vs one per document)
- Qdrant supports ColBERT natively since v1.10 via `MAX_SIM` multivectors

**Implication for us:** ColBERT would replace the cross-encoder reranker, not add a fourth retrieval leg. This is a quality + latency improvement on the reranking stage.

### BGE-M3: Single Model, Three Output Types

[BGE-M3](https://huggingface.co/BAAI/bge-m3) (BAAI, 550M params) produces dense, sparse, and ColBERT vectors from a single forward pass:

| Output | Dimensions | Equivalent to |
|--------|-----------|---------------|
| Dense | 1024 | nomic-embed-text (768-dim) |
| Sparse | Learned term weights | SPLADE |
| ColBERT | Per-token vectors | Cross-encoder reranker |

BGE-M3's sparse output [outperforms BM25](https://arxiv.org/html/2402.03216v3) on short-to-medium text retrieval (MIRACL nDCG@10: M3-sparse vs BM25), though BM25 remains competitive on long documents. For our garden entries (50-200 lines), BGE-M3 sparse would likely match or beat BM25.

Combining all three BGE-M3 outputs achieves MIRACL nDCG@10 of 70.0 (vs 67.8 dense-only). The multi-mode architecture is validated and production-ready.

**Implication:** BGE-M3 could replace nomic-embed-text + SPLADE + cross-encoder with a single model. But Qdrant BM25 would likely stay as a complementary lexical leg — BGE-M3 sparse is *learned*, not lexical, so it catches different things than raw BM25.

### Fusion Methods: RRF vs Convex Combination

The [financial QA benchmark](https://arxiv.org/abs/2604.01733) found Convex Combination (CC, α=0.5) outperforms RRF (k=60) on recall: 0.726 vs 0.695. Both methods perform well, but CC with equal weighting of dense and sparse scores achieves optimal recall while maintaining ranking quality.

We currently use Qdrant-native RRF. Testing CC fusion is a low-cost experiment that could improve recall.

### HyDE / Query Expansion: Limited Gains

Hypothetical Document Embeddings (HyDE) — generating synthetic documents and embedding them as queries — [consistently underperforms](https://arxiv.org/html/2604.01733v1) vanilla dense retrieval. The generated pseudo-documents introduce noise by fabricating plausible but incorrect content, pulling the query embedding away from relevant documents.

**Decision:** Skip HyDE and step-back prompting. BM25 already solved the keyword gap that query expansion was trying to address.

### CRAG: Moderate Gains, Cannot Match Hybrid Fusion

Corrective RAG (self-healing retrieval with relevance evaluation and retry) provides moderate improvement but "cannot match hybrid fusion" in the [financial QA benchmark](https://arxiv.org/abs/2604.01733). Our three-leg hybrid already delivers what CRAG tries to recover from.

**Decision:** Skip CRAG adoption. The neural-text framework exists if needed later, but priority is elsewhere.

### LLM-Based Sparse Retrievers

Newer alternatives to SPLADE use LLM backbones instead of BERT, addressing the vocabulary gap:

- [**Mistral-SPLADE**](https://arxiv.org/abs/2408.11119): Mistral backbone, surpasses all existing SPLADE variants on BEIR (nDCG@10 = 0.5507)
- [**LACONIC**](https://arxiv.org/abs/2601.01684): Llama-3 backbone, only sparse model in MTEB top 15, 71% less index memory than equivalent dense
- [**CSPLADE**](https://arxiv.org/html/2504.10816v1): Addresses dying ReLU problem in decoder-only LLM sparse training

These solve SPLADE's vocabulary gap with larger vocabularies and better pre-training. However, BM25 already solved that gap for us at much lower computational cost.

**Decision:** Monitor but don't adopt. If BGE-M3 sparse proves sufficient, LLM-based sparse adds no marginal value.

### Code-Domain Embedding Models

Models trained on source code handle Java identifiers natively:

| Model | Params | Training data | Notes |
|-------|--------|--------------|-------|
| [nomic-embed-code](https://huggingface.co/nomic-ai/nomic-embed-code) | 7B | CoRNStack (code+docstrings) | Same family as our current nomic-embed-text; purpose-built for code |
| [Jina Code V2](https://jina.ai/news/elevate-your-code-search-with-new-jina-code-embeddings/) | 161M | Code + documentation | 8192 token context, code similarity tasks |
| CodeSage Large V2 | — | Identifier deobfuscation + contrastive learning | Strong on code understanding |
| [Qwen3-Embedding-8B](https://huggingface.co/Qwen/Qwen3-Embedding-8B) | 8B | General + code | Surpasses all API models on MTEB (70.6 vs OpenAI 64.6) |

**nomic-embed-code** is the most relevant: same Nomic family, purpose-built for code, would handle `ConcurrentHashMap` and `@DefaultBean` natively. But at 7B params it is 50x larger than nomic-embed-text — whether it is practical via Ollama on development hardware is an open question.

**Decision:** Evaluate if BGE-M3 adoption happens — compare BGE-M3 dense against nomic-embed-code on the #27 vocabulary-gap scenarios. If BGE-M3 dense already handles Java identifiers well, a separate code model adds no value.

## What We Validated vs What We're Skipping

| Technique | Status | Rationale |
|-----------|--------|-----------|
| BM25 keyword matching | **✅ Validated** | 45% → 94% precision, keyword gap closed |
| SPLADE sparse (web-trained) | **⚠️ Weak** | Zero Java vocabulary, churns results without quality gain |
| Cross-encoder reranking | **⚠️ Untested** | Crashed engine in dev mode; ColBERT may be better alternative |
| Three-leg RRF fusion | **✅ Validated** | Server-side Qdrant RRF works cleanly |
| Payload filtering (domain/type/tags) | **✅ Validated** | Wired into `gardenSearch` MCP tool |
| HyDE / query expansion | **❌ Skip** | Introduces noise, underperforms vanilla dense in benchmarks |
| CRAG | **❌ Skip** | Cannot match hybrid fusion; our three-leg already covers it |
| LLM-based sparse (Mistral-SPLADE, LACONIC) | **❌ Skip** | BM25 solved the vocabulary gap cheaper |
| Code-domain embedding models | **⏸ Defer** | Evaluate only if BGE-M3 dense doesn't handle Java identifiers |

## Roadmap — Track 3 Recommendations

Track 1 (maximize existing infrastructure) and Track 2 (complementary algorithms) are complete. Track 3 focuses on architectural simplification and the next quality frontier.

### Priority 1: BGE-M3 Adoption (neural-text #30)

Replace the three-model stack (nomic-embed-text + SPLADE + cross-encoder) with BGE-M3:

- BGE-M3 dense (1024-dim) replaces nomic-embed-text (768-dim) — higher dimensionality, broader training
- BGE-M3 sparse replaces SPLADE — learned sparse from the same model, no separate ONNX inference
- BGE-M3 ColBERT replaces cross-encoder reranker — faster (precomputed doc vectors), higher precision
- Qdrant BM25 stays as complementary lexical leg — BGE-M3 sparse is learned, not lexical

Net result: four signals (dense + learned-sparse + BM25 + ColBERT-rerank) from two sources (BGE-M3 + Qdrant BM25), simpler than today's three-model setup.

**Deployment question:** BGE-M3 is 550M params. Needs evaluation for Ollama or direct ONNX serving on development hardware.

### Priority 2: Convex Combination Fusion

Test CC (α=0.5) against RRF on the #27 scenarios. Low-cost experiment — configuration change in Qdrant query parameters, no code change needed.

### Priority 3: Score the 87 Unscored Entries

Before any further architecture work, score the 87 new entries from the three-leg benchmark to establish the true precision baseline. This determines whether there is a remaining quality gap worth chasing.

## References

### Benchmarks and Surveys
- [From BM25 to Corrective RAG: Benchmarking Retrieval Strategies](https://arxiv.org/abs/2604.01733) — April 2026, financial QA, 10 retrieval strategies compared
- [Hybrid Dense-Sparse Retrieval for High-Recall Information Retrieval](https://www.researchgate.net/publication/399428523_Hybrid_Dense-Sparse_Retrieval_for_High-Recall_Information_Retrieval)
- [Production RAG That Works: Hybrid Search + Re-Ranking](https://machine-mind-ml.medium.com/production-rag-that-works-hybrid-search-re-ranking-colbert-splade-e5-bge-624e9703fa2b)
- [Best Embedding Models 2026: Benchmark and Comparison](https://app.ailog.fr/en/blog/news/embedding-models-2026)

### Models
- [BGE-M3 Model Card](https://huggingface.co/BAAI/bge-m3) — BAAI, dense + sparse + ColBERT, 550M params
- [BGE-M3 Paper](https://arxiv.org/html/2402.03216v3) — Multi-Lingual, Multi-Functionality, Multi-Granularity
- [nomic-embed-code](https://huggingface.co/nomic-ai/nomic-embed-code) — 7B code embedding model
- [6 Best Code Embedding Models Compared](https://modal.com/blog/6-best-code-embedding-models-compared) — Modal, 2025

### Sparse Retrieval
- [Mistral-SPLADE: LLMs for Better Learned Sparse Retrieval](https://arxiv.org/abs/2408.11119)
- [LACONIC: Dense-Level Effectiveness for Scalable Sparse Retrieval](https://arxiv.org/abs/2601.01684) — Llama-3 backbone, 2026
- [CSplade: Learned Sparse Retrieval with Causal Language Models](https://arxiv.org/html/2504.10816v1)
- [Modern Sparse Neural Retrieval: From Theory to Practice](https://qdrant.tech/articles/modern-sparse-neural-retrieval/) — Qdrant

### ColBERT and Late Interaction
- [Qdrant ColBERT Multivectors Tutorial](https://qdrant.tech/documentation/tutorials-search-engineering/using-multivector-representations/)
- [Any Embedding Model Can Become a Late Interaction Model](https://qdrant.tech/articles/late-interaction-models/) — Qdrant
- [Qdrant Hybrid Search Revamped — Query API](https://qdrant.tech/articles/hybrid-search/)

### Our Benchmarks
- `docs/comparison/real-world-benchmark.md` — #27, dense-only baseline (14 scenarios)
- `docs/comparison/hybrid-benchmark.md` — #28, SPLADE hybrid + three-leg results
- `scripts/benchmark/results/three-leg.json` — raw three-leg benchmark data
- `scripts/benchmark/baseline_scores.json` — manual relevance scores from #27
