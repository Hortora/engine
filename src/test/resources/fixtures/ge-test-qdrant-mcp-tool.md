---
title: "Qdrant MCP tool returns stale results after collection recreation"
domain: tools
type: gotcha
score: 7
tags: [qdrant, mcp, collections]
submitted: "2026-06-05"
---

When a Qdrant collection is deleted and recreated with the same name, an active
MCP tool session may return stale cached results for several seconds. The Qdrant
client caches collection metadata. Restart the garden-engine service after
recreating collections to force a fresh connection.
