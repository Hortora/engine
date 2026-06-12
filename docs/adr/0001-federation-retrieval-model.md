# 0001 — Federation retrieval model

Date: 2026-06-12
Status: Accepted

## Context and Problem Statement

The engine serves a single garden's Qdrant index. Gardens have canonical/child/peer relationships (defined in the RAG redesign spec). When a child garden's local results are insufficient, it needs to query upstream parent gardens. The question: how should federation work — entry-level relationships within a single store, or garden-level HTTP between independent services?

## Decision Drivers

* Each garden runs an independent stack (git repo + Qdrant + engine service) — per ADR-0005 in hortora/spec
* Parent indexes are always current — no sync, no stale local copies
* Federation must degrade gracefully — a dead parent must not break local search
* Results need provenance so consumers know where knowledge originated

## Considered Options

* **Option A** — Garden-level HTTP federation with provenance passthrough
* **Option B** — Entry-level parent/child relationships within a single Qdrant store
* **Option C** — Git-repo cloning of parent entries into child's Qdrant

## Decision Outcome

Chosen option: **Option A — Garden-level HTTP federation**, because each garden is an independent service (ADR-0005) and parent indexes must stay authoritative without local copies.

### Positive Consequences

* No data duplication — child never stores parent entries locally
* Parent index is always current — no sync lag or stale copies
* Graceful degradation — timeout per upstream call, continue with available results
* Provenance preserved through the full chain — each engine tags its own results with its garden ID and passes upstream results through unchanged

### Negative Consequences / Tradeoffs

* Latency — upstream walk adds sequential HTTP calls (bounded by max-depth)
* Availability dependency — upstream unavailability degrades recall (but never breaks local search)
* Version coupling — all engines in a federation must agree on the SearchResult JSON shape

## Pros and Cons of the Options

### Option A — Garden-level HTTP federation

* ✅ No data duplication across gardens
* ✅ Parent authority preserved — always searches the live parent index
* ✅ Graceful degradation per upstream/peer call
* ✅ Cycle detection via visited-set header
* ❌ Sequential upstream latency (mitigated by short-circuit on strong match)
* ❌ Requires network connectivity for federation

### Option B — Entry-level relationships in single store

* ✅ No network dependency — all lookups are local Qdrant queries
* ✅ Simpler implementation — no HTTP clients needed
* ❌ Requires parent entries copied into child's Qdrant — data duplication
* ❌ Stale copies when parent entries are updated
* ❌ Contradicts ADR-0005 ("federation is service-to-service HTTP, not git-repo cloning")

### Option C — Git-repo cloning

* ✅ Offline capability — full corpus available without network
* ❌ Stale copies — git pull frequency determines freshness
* ❌ Storage duplication — every child stores full parent corpus
* ❌ Contradicts "parent's index is always current" design principle

## Links

* Design spec: `docs/superpowers/specs/2026-06-12-federation-chain-walk-design.md`
* Prior spec: `hortora/spec/docs/design/2026-04-07-garden-rag-redesign-design.md` §Federation Architecture
* Prior spec: `hortora/spec/docs/design/2026-04-16-garden-retrieval-service.md` §Federation
* ADR-0005 (hortora/spec): "Per-garden Qdrant: federation is service-to-service HTTP, not git-repo cloning"
* GitHub issue: Hortora/engine#5
