# Daemon Runtime Delta: Runtime Transformation Primitives

**Document ID:** `SPEC-004-D002`
**Status:** Draft
**Base Spec:** [Daemon Runtime](../../../specs/daemon-runtime.md)
**Last Updated:** 2026-06-26

## SPEC-004-D002.P1 Changed contracts

- **SPEC-004-D002.C1:** The daemon owns an in-memory read-only view registry for its lifetime, alongside the existing named-query registry and runtime library state.
- **SPEC-004-D002.C2:** View registry entries are named by simple unqualified names and point to fully qualified Clojure function symbols resolvable in the daemon JVM. Duplicate registration replaces the prior entry for reload workflows.
- **SPEC-004-D002.C3:** View invocation resolves the registered function symbol in the daemon JVM and calls it with one context map containing at least `:params`. View functions are read-only transformations in this feature; mutating workflows require a separate contract.
- **SPEC-004-D002.C4:** Daemon semantic operations expose set-oriented task primitives needed by trusted runtime libraries: `:query-ids`, `:tasks-by-ids`, `:ancestor-root-ids`, and `:subgraph`.
- **SPEC-004-D002.C4a:** `query-ids` returns a vector of task ids for an ad hoc query definition or registered query name, ordered by the same stable task ordering as `list` query results.
- **SPEC-004-D002.C4b:** `tasks-by-ids` accepts a collection of task ids, collapses duplicate ids by first occurrence, returns normalized task rows in that first-occurrence input order, returns `[]` for empty input, and fails loudly if any requested id is missing.
- **SPEC-004-D002.C5:** `ancestor-root-ids` over `parent-of` traverses from seed ids upward against edges where parent `from_task_id` points to child `to_task_id`. It includes seed ids as depth zero candidates, deduplicates results, and returns the topmost matching ancestors on every path: if a `:where` filter is supplied, matching ancestors that have no matching parent above them; if no filter is supplied, graph roots with no `parent-of` parent. Empty seed input returns `[]`; any missing seed id fails loudly. The MVP has no edge-type option.
- **SPEC-004-D002.C6:** `subgraph` over `parent-of` expands from root ids downward and returns `{:root-ids [...] :tasks [...] :edges [...]}`. `:root-ids` preserves first-occurrence input order with duplicates collapsed. `:tasks` contains normalized rows for roots and descendants, ordered by stable task id. `:edges` contains only internal `parent-of` edges connecting included tasks, ordered by `from_task_id`, `to_task_id`, then edge type. Roots are included in the task set. Empty root input returns `{:root-ids [] :tasks [] :edges []}`; any missing root id fails loudly. The MVP has no edge-type option.
- **SPEC-004-D002.C7:** View registration and invocation are trusted Clojure/nREPL/config workflows, not JSON socket public CLI operations in this feature.
- **SPEC-004-D002.C8:** View registry contents are daemon-lifetime runtime state and are not durable across daemon restarts.
- **SPEC-004-D002.C9:** Blessed runtime transformation helpers live in source-visible `atom.graph.alpha` and `atom.views.alpha` namespaces and build on the runtime library workspace model.
- **SPEC-004-D002.C10:** View registry daemon operation names are `:register-view!`, `:view!`, and `:views`.

## SPEC-004-D002.P2 Unchanged contracts

- **SPEC-004-D002.U1:** SQLite stores durable task and edge facts only.
- **SPEC-004-D002.U2:** JSON socket allowlist remains limited to public CLI behavior unless a later feature defines CLI view invocation and output contracts.
- **SPEC-004-D002.U3:** Trusted Clojure code still runs with daemon authority; this feature does not add sandboxing.
