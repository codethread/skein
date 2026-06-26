# DB Set and Graph Primitives

**Document ID:** `TASK-002`

## TASK-002.P1 Scope

Type: AFK

Add SQL-backed persistence helpers for id-oriented query execution, batch task hydration, and the MVP `parent-of` graph traversals.

## TASK-002.P2 References

- **TASK-002.R1:** [Feature plan](../runtime-transformation-primitives.plan.md)
- **TASK-002.R2:** [Daemon delta](../specs/daemon-runtime.delta.md)
- **TASK-002.R3:** `src/todo/db.clj`, `src/todo/query.clj`, `test/todo/db_test.clj`

## TASK-002.P3 Implementation notes

- **TASK-002.I1:** Add DB-layer helper for query id selection from an ad hoc query definition and params. It should share query compilation behavior with existing `all-tasks`/`ready-tasks` paths and return ids ordered like `list` query results. Registered query-name resolution will be wired at the daemon/helper layer, but the end-to-end feature must support both ad hoc definitions and registered names.
- **TASK-002.I2:** Add `tasks-by-ids`-style DB helper. It must return rows in first-occurrence input order, collapse duplicate input ids, return `[]` for empty input, and fail loudly if any requested id is missing.
- **TASK-002.I3:** Add `ancestor-root-ids` over `parent-of`: traverse upward from seed ids where parent edge direction is `from_task_id` parent -> `to_task_id` child; include seed ids as depth-zero candidates; dedupe; return topmost matching ancestors per path. If `:where` is supplied, matching is based on that task query predicate; otherwise roots are tasks with no `parent-of` parent. Empty seed input returns `[]`; any missing seed id fails loudly.
- **TASK-002.I4:** Add `subgraph` over `parent-of`: expand downward from root ids and return `{:root-ids [...] :tasks [...] :edges [...]}`. Root ids preserve first-occurrence order with duplicates collapsed; tasks are normalized at daemon boundary later but DB rows should be stable ordered by id; edges include only internal `parent-of` edges ordered by `from_task_id`, `to_task_id`, edge type. Empty root input returns `{:root-ids [] :tasks [] :edges []}`; any missing root id fails loudly.
- **TASK-002.I5:** Keep SQL and persistence behavior in `todo.db`; do not put recursive SQL in alpha helper namespaces.

## TASK-002.P4 Done when

- **TASK-002.D1:** DB tests cover query ids ordering, empty/missing/duplicate `tasks-by-ids`, ancestor roots including multi-parent DAG cases, and subgraph task/edge inclusion.
- **TASK-002.D2:** The helpers fail loudly on missing task ids, missing ancestor seed ids, and missing subgraph root ids.
- **TASK-002.D3:** Relevant Clojure tests pass.
