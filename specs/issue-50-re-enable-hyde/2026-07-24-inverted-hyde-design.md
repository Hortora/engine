# Inverted HyDE: Index-Time Query Generation

**Issue:** Hortora/engine#50
**Date:** 2026-07-24
**Status:** Approved

## Context

HyDE (query-time hypothetical document generation) was benchmarked twice and is definitively net-negative for this corpus (-2.2pp precision in both double-retrieval and single-retrieval modes). The root cause, confirmed by Weller et al. (EACL 2024), is that generative query/document expansion harms strong retrievers — expansion improves recall but adds noise that degrades ranking precision among top candidates.

This spec supersedes the prior design (`2026-07-23-re-enable-hyde-design.md`), which proposed re-enabling query-time HyDE via config change. That approach was implemented and benchmarked on 2026-07-23, producing the -2.2pp results above. Issue #50's acceptance criteria should be updated to reflect this pivot from query-time to index-time expansion — the original criteria (re-enable config, per-leg separation dependency) no longer apply.

Inverted HyDE flips the approach: instead of generating hypothetical *documents* from queries at query time, generate hypothetical *queries* from documents at *index time*. This grounds the expansion in actual corpus content and eliminates the query-time latency penalty. The generated queries bridge the vocabulary gap between how developers search and how garden entries are written.

### Prior benchmark results (this branch)

| Config | File | Precision | Delta vs baseline |
|--------|------|-----------|-------------------|
| No HyDE (baseline) | `crossencoder-pool50-scored.json` | 61.6% | — |
| Double-retrieval HyDE | `hyde-perleg-separation.json` | 59.4% | -2.2pp |
| Single-retrieval HyDE | `hyde-single-retrieval.json` | 59.4% | -2.2pp |

## Approach

**Content augmentation with separator — engine-only, no neocortex changes.**

### Architecture: decorator pattern

`GardenMetadataExtractor` remains a pure extraction function — it parses YAML frontmatter, extracts metadata, and produces `ExtractionResult` with no side effects.

A new `QueryAugmentingExtractor` wraps `MetadataExtractor` via CDI decorator, separating extraction from augmentation. `CorpusBindingProducer` injects `MetadataExtractor` via CDI constructor injection, so the decorator is automatically applied to the ingestion pipeline. The decorator:

1. Delegates to the real extractor (`GardenMetadataExtractor`)
2. If inverted HyDE is enabled and `ExtractionResult.body` is non-empty, generates queries via `OllamaQueryGenerator`
3. Reads/writes sidecar `.queries` cache (using `GardenConfig.path()` for filesystem access)
4. Appends queries to `ExtractionResult.body` with a separator
5. Returns the augmented `ExtractionResult`
6. If Ollama is unavailable, returns the original result unchanged

The decorator is conditionally active based on `hortora.inverted-hyde.enabled`.

### Query generation

`OllamaQueryGenerator` calls a local Ollama instance (gemma3:4b) with a domain-tuned prompt:

```
Given this knowledge garden entry about JVM development, generate exactly 3 short questions
that a developer would type into a search box to find this entry.
Use the same technical vocabulary the entry uses — class names, annotations, error messages.
One question per line, no numbering, no explanations.
```

Input: entry title + first ~2000 chars of body (well within gemma3:4b's 8192-token context). Output: 3 lines of query text.

**Output validation:** `OllamaQueryGenerator` validates before caching:
- Expect exactly `query-count` lines; trim extra lines, discard if fewer than 2 valid lines
- Per-line bounds: minimum 15 chars, maximum 150 chars — filters degenerate queries ("What is CDI?") and model explanations
- Empty/whitespace lines discarded
- If fewer than 2 lines pass validation, skip augmentation for this entry (log warning, index without queries)

Invalid output is never cached. Entries that fail validation are indexed normally and retried on next modification or reindex.

**Why Ollama, not AgentProvider:** Index-time augmentation runs on every garden entry (~2000+ on full reindex). Claude API calls at $0.003–0.015 each would cost $6–30 per full reindex; local Ollama inference costs nothing. Query generation is a simple task — a 4B parameter model is sufficient. Local inference also avoids a hard dependency on Vertex AI credentials, which may not be available in all environments (local dev, CI). `AgentProvider` is designed for interactive session-based LLM use; a stateless one-shot HTTP call to Ollama is the right abstraction for batch index-time processing.

### Content augmentation

Generated queries are appended to `ExtractionResult.body` with a separator:

```
[original content — title + body]

---HYPOTHETICAL-QUERIES---
How does @DefaultBean interact with @Produces in Quarkus?
Why does CDI ignore @DefaultBean on class-level annotations?
What pattern enables consumer-replaceable CDI defaults?
```

BGE-M3 embeds the full combined text. Dense, sparse, BM25, and ColBERT all pick up the query vocabulary. The hypothesis: sparse/BM25 benefit most from vocabulary bridging, dense benefits less (consistent with Weller et al.'s finding that expansion helps recall more than precision). Note: Weller et al. study query-time expansion; index-time content augmentation has a different failure mode (permanent index vocabulary addition vs. transient query noise), but the finding about recall vs. precision generalises.

**Chunking interaction:** The garden corpus uses recursive chunking (max 6000 chars, 500 overlap). Most garden entries are well under 6000 chars including augmented queries (~3 queries × ~80 chars = ~240 chars + separator), so they remain single-chunk. For the rare multi-chunk entry, queries append to the tail and may land in a later chunk — this is acceptable because that chunk retains the same `sourceDocumentId` and still contributes to the entry's discoverability via all retrieval signals. If corpus sizes grow significantly, augmentation can be moved post-chunking via a neocortex `ContentAugmenter` SPI (future work).

### Sidecar caching

Generated queries are cached in `.queries` files alongside each garden entry:

```
~/.hortora/garden/jvm/GE-20260515-fd3156.md       ← entry
~/.hortora/garden/jvm/GE-20260515-fd3156.queries   ← cached queries
```

First line is a version header; subsequent lines are one query each:

```
# v:a3f2c1 prompt=<hash6> model=gemma3:4b count=3
How does @DefaultBean interact with @Produces in Quarkus?
Why does CDI ignore @DefaultBean on class-level annotations?
What pattern enables consumer-replaceable CDI defaults?
```

The version hash is derived from prompt template + model name + query-count. Regeneration logic:
- `.queries` exists, version hash matches, and entry `.md` is not newer → use cached (no LLM call)
- `.queries` missing, version hash mismatch, or `.md` is newer → generate via Ollama, write cache

This automatically invalidates cached queries when the prompt is tuned, the model is changed, or the query count is adjusted — no manual deletion of `.queries` files required.

Files are local artifacts, not checked into the garden repo (`*.queries` in `.gitignore`).

### Display stripping

`SearchResource.searchLocal()` strips the `---HYPOTHETICAL-QUERIES---` section from `chunk.content()` before passing it as the `body` parameter of `SearchResult`. All search paths (`search()`, `searchFor()`, `searchAdaptive()`) flow through `searchLocal()`, so stripping covers all return paths.

**Cross-encoder interaction:** The cross-encoder reranker evaluates `chunk.content()` before stripping — it sees the augmented content including hypothetical queries. This is intentional: the hypothetical queries provide additional signal about what the document is about, which helps the cross-encoder assess relevance. Since all entries are uniformly augmented, relative rankings are preserved. Selective augmentation of only the dense embedding (without cross-encoder influence) requires the separate named vectors approach (out of scope, see #53).

**Fusion key:** `RetrievedChunk.fusionKey()` is `sourceDocumentId + "\0" + content`. During the transition from non-augmented to augmented index, entries may have inconsistent fusion keys. A full reindex (`gardenReindex()`) after enabling inverted HyDE eliminates this — all entries get uniform augmentation.

### Graceful degradation

If Ollama is unavailable (not running, model not pulled):
- `QueryAugmentingExtractor` logs a warning and returns content without augmentation
- Entries are indexed normally — same as current baseline
- Tests: `InMemoryEmbeddingIngestor` doesn't embed, so no impact

**Deployment:** After enabling inverted HyDE, run `gardenReindex()` to augment all existing entries. Without this, only newly modified entries get queries, resulting in a mixed index with no automated convergence path. `gardenReindex()` deletes the Qdrant collection and resets the cursor; the next ingestion cycle re-embeds all entries with augmented content.

### Configuration

```properties
# Inverted HyDE — local query generation via Ollama
hortora.inverted-hyde.enabled=true
hortora.inverted-hyde.ollama.host=http://localhost:11434
hortora.inverted-hyde.ollama.model=gemma3:4b
hortora.inverted-hyde.query-count=3
```

## Changes

| File | Change |
|------|--------|
| `QueryAugmentingExtractor` (new) | CDI decorator on `MetadataExtractor` — orchestrates query generation and content augmentation |
| `OllamaQueryGenerator` (new) | Ollama HTTP client for query generation |
| `GardenMetadataExtractor` | Unchanged — remains pure extraction |
| `SearchResource` | Strip `---HYPOTHETICAL-QUERIES---` section from `chunk.content()` in `searchLocal()` before constructing `SearchResult` |
| `SessionQueryExpander` | Remove — query-time HyDE is definitively closed by benchmarks |
| `SessionQueryExpanderTest` | Remove |
| `application.properties` | Add inverted HyDE config; remove commented-out expansion config and DISABLED comment |
| `pom.xml` | HTTP client dependency for Ollama API (if not already present) |

## Verification

Re-run the existing benchmark harness (`scripts/benchmark/`) against all 14 real-world scenarios.

**Comparison:**
- **Baseline:** `crossencoder-pool50-scored.json` (no HyDE, current pipeline)
- **Treatment:** inverted HyDE (augmented content, same pipeline)

**Acceptance criteria:**
- Zero precision regressions on any individual scenario
- Aggregate precision improvement ≥ +1pp across all 14 scenarios
- At least 1 scenario shows ≥ +2pp improvement (demonstrates signal, not noise)
- Latency: no query-time overhead expected (augmentation is index-time only)

**Non-determinism:** Query generation is cached in sidecar `.queries` files, so augmented content is stable across benchmark runs. Embedding (ONNX) and retrieval (Qdrant NN) are deterministic given the same input. Multiple runs are not needed.

**Rollback:** Qdrant snapshot `hortora_garden-2913056546253732-2026-07-24-03-04-39.snapshot` restores the baseline index. No re-indexing needed.

### Testing

Unit tests for:
- `QueryAugmentingExtractor` — delegation to real extractor, content augmentation with/without queries, disabled state
- `OllamaQueryGenerator` — output validation (line count, length bounds, empty filtering), sidecar cache read/write/staleness detection, cache version invalidation on config change
- `SearchResource` — stripping logic for the separator in `searchLocal()`

No integration tests against Ollama — graceful degradation means tests run without it.

## Known Limitations

- **Federation asymmetry:** Remote gardens that don't implement inverted HyDE won't have vocabulary-bridging queries in their index. Local augmented entries have a systematic vocabulary advantage in federated results. Each garden controls its own ingestion pipeline; federation-level consistency is a separate concern.

## Out of Scope

- Separate named vectors for query embeddings (approach C — graduate to this if A works) — #53
- Expansion drift detection (#118/#120 — complementary but independent)
- LLM quality evaluation framework (manual sampling for now) — #54
- Query-time HyDE re-enablement (definitively closed by benchmarks)

## References

- [Weller et al., "When do Generative Query and Document Expansions Fail?" EACL 2024](https://aclanthology.org/2024.findings-eacl.134/)
- [Inverted HyDE: Solving Real-World Dense Retrieval Challenges](https://behitek.com/blog/inverted-hyde/)
- Prior benchmarks: `scripts/benchmark/results/hyde-perleg-separation.json`, `hyde-single-retrieval.json`
- Prior design (superseded): `2026-07-23-re-enable-hyde-design.md`
