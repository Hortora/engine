# The Document That Nobody Read

There's a question that sits underneath every RAG pipeline: did the retrieved documents actually help? Not "were they retrieved" — that's trivially observable — but did they contribute to the answer? Did anyone even open them?

I've been staring at Hortora's garden corpus — roughly 6,500 entries of hard-won technical knowledge — and suspecting that a good proportion of it is dead weight. Entries that exist, get indexed, occupy embedding space, but never surface in a retrieval that matters. The corpus is curated manually through staleness reviews, but that process has no signal about what's actually being used. It's gardening by feel.

So we built the retrieval tracking SPI for neocortex (#105). The design started as simple frequency counters, but the brainstorming pushed it somewhere more interesting.

## Three stages of document usefulness

The insight that shaped the design: retrieval frequency alone doesn't tell you much. A document that appears in search results constantly but nobody opens is noise. A document that gets opened but doesn't answer the question is a false positive. You need the full chain:

1. **Retrieved** — the document appeared in results
2. **Accessed** — someone pulled the full content
3. **Useful** — it actually helped, to some graduated degree

Stage 1 is something the system can observe directly. Stages 2 and 3 require the client to report back. That means the SPI needs both passive observation (what came back for this query) and an explicit feedback API (this document was `RELEVANT` / `NOT_RELEVANT` / somewhere between).

We settled on a four-level `RetrievalOutcome` enum — `NOT_RELEVANT`, `PARTIALLY_RELEVANT`, `RELEVANT`, `HIGHLY_RELEVANT`. Categorical, not numeric. Clients make a quick judgment without agonising over what 0.7 means versus 0.4.

## Decorator, not explicit calls

I originally planned for explicit `tracker.record()` calls at the consumer site. Claude's design review pushed back on this — correctly. The codebase already has a decorator chain on `CaseRetriever` (CRAG at `@Priority(100)`, query expansion at `@Priority(200)`), and a tracking decorator at `@Priority(50)` slots in as the outermost wrapper. Consumers get tracking for free when `rag-tracking` is on the classpath. Nobody has to remember to call anything.

The tricky part was the double-recording guard. When the default `BlockingToReactiveCaseRetriever` bridge is active, a reactive consumer's call traverses both decorator chains — the reactive tracking decorator wraps the bridge, which delegates to the blocking chain including the blocking tracking decorator. Without a guard, you'd record the same retrieval twice. We followed CRAG's `isAlreadyGraded()` pattern: the first decorator stamps a `_trackingId` metadata key on the returned chunks, the second detects it and skips.

The other non-obvious constraint: failure isolation. Unlike CRAG — which transforms the result set and whose failures are semantically meaningful — tracking is purely observational. If the SQLite write fails, the chunks must still be returned unchanged. The decorator catches everything from `tracker.record()` and logs it. This is a behavioural contract, not an implementation suggestion.

## What the adversarial review caught

Claude ran a four-round adversarial design review against the spec before implementation started. The reviewer raised 11 issues; 10 were verified as real fixes to the spec, 1 was accepted as a valid concern but rejected (feedback scope is forward-looking design, not scope creep for #105).

The most interesting catch: chunk-to-document deduplication. `RetrievedChunk` carries `sourceDocumentId`, and a document splitter commonly produces multiple chunks per document. The `retrieved_documents` table has a PK on `(retrieval_id, source_document_id)` — inserting multiple chunks from the same document would violate it. The fix: collapse duplicate doc IDs at record time, keeping the max relevance score.

## The Instant.MAX gotcha

The SQLite implementation surfaced a silent failure that earned a garden entry. `Instant.MAX.truncatedTo(MILLIS).toString()` produces `+1000000000-12-31T23:59:59.999Z`. That `+` prefix has ASCII value 43, which sorts lexicographically before any digit (ASCII 48+). So `timestamp < ?` with `Instant.MAX` as the bound evaluates to false for every row in the database. No error, no warning — just empty results.

The fix is to omit the WHERE clause entirely when the bound is a sentinel value. Simple once you know it. But the symptom ("time-windowed query returns nothing") sends you looking at indexes and data, not at how Java serialises extreme `Instant` values.

## What this enables

The tracking SPI records. It does not analyse. A follow-up service (#109) will compute the query-to-document-to-outcome weight graph — which documents are retrieved but never useful, which queries consistently return low-quality results, where the corpus has gaps. The data model is designed to support that: every feedback event traces back to the original query via a `retrievalId`, so the analysis layer can build edges in both directions.

The long game is retrieval quality that improves over time — not through better embeddings (that's a separate problem), but through better corpus curation informed by actual usage data. The garden's staleness lifecycle already has CONFIRM/REVISE/RETIRE. Now there's a data source to inform those decisions with something other than intuition.
