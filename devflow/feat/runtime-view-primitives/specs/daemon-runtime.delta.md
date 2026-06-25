# Daemon Runtime Delta — Runtime View Primitives

**Document ID:** `RVP-DELTA-001`
**Status:** Draft
**Date:** 2026-06-25
**Blocked by:** `user-daemon-home` spec promotion
**Updates:** [Daemon Runtime](../../../specs/daemon-runtime.md)

## RVP-DELTA-001.P1 Summary

Add the first read-only runtime transformation primitives to the daemon API: id-oriented query execution, batch hydration, minimal `parent-of` graph traversal, and a trusted in-memory view registry.

## RVP-DELTA-001.P2 Contract changes

- **RVP-DELTA-001.C1:** The daemon exposes `query-ids!`, accepting either an ad hoc query definition or a registered query name plus optional params, and returning matching task ids only.
- **RVP-DELTA-001.C2:** The daemon exposes `tasks-by-ids`, accepting a collection of task ids and returning normalized task rows for those ids in deterministic input order unless the implementation explicitly documents another deterministic order.
- **RVP-DELTA-001.C3:** The daemon exposes `ancestor-root-ids` over `parent-of`, accepting seed ids and a root selector, walking reverse `parent-of` edges, and returning ancestor ids that satisfy the root selector.
- **RVP-DELTA-001.C4:** The daemon exposes one minimal downward expansion primitive over `parent-of`: either `descendant-ids` for id-only expansion or `subgraph` for ids plus internal edges. The implementation should choose the smallest shape that proves the feature-DAG view slice.
- **RVP-DELTA-001.C5:** Graph primitives are set-oriented and SQLite-backed where practical, including recursive CTEs for transitive traversal.
- **RVP-DELTA-001.C6:** The daemon owns an in-memory view registry for read-only trusted Clojure functions. Registry contents are runtime state and are not persisted in SQLite.
- **RVP-DELTA-001.C7:** `register-view!` registers or replaces one named view function in the active daemon runtime. View names follow the same simple unqualified symbol/keyword normalization model as named queries.
- **RVP-DELTA-001.C8:** `view!` invokes a registered view by name with caller params and returns the view function's Clojure data result.
- **RVP-DELTA-001.C9:** Missing queries, missing views, invalid ids, invalid root selectors, malformed params, and view exceptions fail loudly with domain information preserved for REPL callers.
- **RVP-DELTA-001.C10:** Runtime view registration and invocation are trusted Clojure/REPL daemon capabilities. They are not added to the public JSON socket CLI allowlist in this feature.

## RVP-DELTA-001.P3 Non-goals retained

- **RVP-DELTA-001.N1:** Do not persist runtime views, graph views, or Clojure functions.
- **RVP-DELTA-001.N2:** Do not expose raw SQL or executable Clojure through the low-privilege JSON CLI surface.
- **RVP-DELTA-001.N3:** Do not add mutating workflow primitives.
