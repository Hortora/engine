---
layout: post
title: "CBR Reconciliation: Fixing a Write-Only Disaster Recovery Path"
date: 2026-07-05
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-neocortex]
tags: [cbr, reconciliation, spi, qdrant]
series: issue-74-cbr-reconciliation-batch
---

The CBR dual-store architecture has a blind spot I hadn't noticed until reconciliation forced the question. `QdrantCbrCaseMemoryStore` delegates durable storage to `CaseMemoryStore` — JPA or SQLite — and maintains a Qdrant index for similarity search. The delegation exists specifically so that if the Qdrant index is lost (dimension change, restart, failed upsert), the durable store can repopulate it.

Except it couldn't. `CaseMemoryStore.query()` requires `entityIds` — it's entity-scoped by design. There's no way to ask "what CBR entries exist for caseType X?" The delegate was a write-only disaster recovery path. Data went in but couldn't come back out for reindexing.

The fix is a `scan()` method on `CaseMemoryStore` with a new `MemoryCapability.SCAN`. Default throws `MemoryCapabilityException`; JPA and SQLite implement it with attribute-filtered cursor pagination. The attribute filter handles the CBR use case — `scan(tenantId, "cbr.caseType", "aml")` — but the method is general enough for any future admin enumeration.

One subtlety in the SQLite implementation: `json_extract` interprets dots in key names as JSON path separators. `$.cbr.caseType` navigates two levels deep into the JSON rather than matching the single top-level key `"cbr.caseType"`. The fix is quoted key syntax — `$."cbr.caseType"` — which isn't documented anywhere in SQLite's JSON1 docs.

With scan in place, reconciliation becomes a single-pass set-intersection. Build a `Map<UUID, Memory>` from the delegate scan. Scroll Qdrant with a tenant filter. For each Qdrant point: if it's in the map, remove it (consistent); if not, it's an orphan — delete. Whatever remains in the map after the scroll is missing from Qdrant — deserialize, re-embed, upsert. One pass, no N+1 queries. An earlier design had a two-phase approach — orphan cleanup first, then reindex — but the design review caught the N+1 in the orphan phase and proposed the unified algorithm.

The dimension migration gate was the other piece. `CbrCollectionManager.ensureCollection()` was silently deleting and recreating Qdrant collections on dimension mismatch — a `LOG.warning` and nothing else. In production, an accidental embedding model change would destroy all CBR points. Now it throws `CbrDimensionMismatchException` by default. Operators opt in to destructive recreation with `casehub.memory.cbr.qdrant.allow-dimension-migration=true`, and the warning message tells them reconciliation is needed for all tenants sharing that case type.

A minor finding from the earlier #70 review also landed: `ScoredCbrCase` had no score validation. The range is `[-1, 1]` (cosine similarity's mathematical range, not the `[0, 1]` that NLP embeddings typically produce). The validation uses an inverted range check — `!(score >= -1.0 && score <= 1.0)` — which rejects NaN for free. IEEE 754 defines all NaN comparisons as false, so `NaN >= -1.0` is false, the conjunction is false, the negation is true, and the guard fires. No separate `Double.isNaN()` needed.

The scan capability opens a door that was previously shut. Reconciliation is the first consumer, but any admin operation that needs to enumerate CaseMemoryStore entries — GDPR audit trails, data export, diagnostic dashboards — now has a foundation to build on.
