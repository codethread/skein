# Rename repl and alpha libraries

**Document ID:** `TASK-003`

## TASK-003.P1 Scope

Type: AFK

Rename the trusted Clojure user surface after the core model and weaver runtime are in place: connected REPL helpers, blessed alpha runtime libraries, generated namespace references used by Clojure tests, and graph/view helper vocabulary.

## TASK-003.P2 Must implement exactly

- **TASK-003.MI1:** Rename the connected helper namespace to `skein.repl` and expose strand helpers: `strand!`, `strand`, and `strands`. Remove public `task!`, `task`, and `tasks` helper expectations.
- **TASK-003.MI2:** Keep generic helpers when still accurate: `connect!`, `init!`, `update!`, `defquery!`, `load-queries!`, `queries`, `query`, and `ready`.
- **TASK-003.MI3:** Update `strand!` and `update!` helper inputs/outputs for `active`, `ephemeral`, and `inactive_at`; enforce the inactive-ephemeral and same-patch active/ephemeral failure contracts.
- **TASK-003.MI4:** Rename blessed namespaces from `atom.libs.alpha`, `atom.graph.alpha`, and `atom.views.alpha` to `skein.libs.alpha`, `skein.graph.alpha`, and `skein.views.alpha`.
- **TASK-003.MI5:** Rename `tasks-by-ids` to `strands-by-ids`; update graph/view helpers and tests to use strand row shapes.
- **TASK-003.MI6:** Update connected REPL, library workspace, graph, view, and runtime dependency tests to require only `skein.*` namespaces.

## TASK-003.P3 Done when

- **TASK-003.DW1:** `strand weaver repl`-equivalent Clojure helper startup can preload `skein.repl` and connect to a selected weaver world.
- **TASK-003.DW2:** Clojure tests cover strand helper creation/update/query/ready behavior and blessed `skein.*.alpha` namespaces.
- **TASK-003.DW3:** No current user-facing Clojure tests require `todo.repl` or `atom.*.alpha`.
- **TASK-003.DW4:** Relevant Clojure REPL/library/graph/view tests pass.

## TASK-003.P4 Out of scope

- **TASK-003.OS1:** Do not implement Go CLI generated `init.clj` template changes here unless needed by Clojure tests; Go CLI surface is task 4.
- **TASK-003.OS2:** Do not add new graph/view primitives or CLI view invocation.
- **TASK-003.OS3:** Do not preserve task-named helper aliases.

## TASK-003.P5 References

- **TASK-003.REF1:** [REPL API delta](../specs/repl-api.delta.md)
- **TASK-003.REF2:** [Runtime transformations delta](../specs/runtime-transformations.delta.md)
- **TASK-003.REF3:** [Plan](../skein-rename.plan.md) `SR-PLAN-001.PH3`
- **TASK-003.REF4:** Current anchors from scout: `src/todo/repl.clj`, `src/atom/libs/alpha.clj`, `src/atom/graph/alpha.clj`, `src/atom/views/alpha.clj`, and `test/todo/*_test.clj` library/repl/alpha tests.
