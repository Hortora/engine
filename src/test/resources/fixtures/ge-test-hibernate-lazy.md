---
title: "Hibernate lazy loading fails outside transaction"
domain: jvm
type: gotcha
score: 8
tags: [hibernate, lazy-loading, transactions]
submitted: "2026-04-15"
---

When accessing a lazy-loaded collection outside of a transaction boundary,
Hibernate throws `LazyInitializationException`. This happens because the
persistence context is closed after the transaction commits.

The fix is either to fetch eagerly with a JOIN FETCH query, use
`@Transactional` to keep the session open, or use the Open Session in View
anti-pattern (not recommended).
