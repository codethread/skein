# Blessed Alpha Helpers

**Document ID:** `TASK-004`

## TASK-004.P1 Scope

Type: AFK

Implement the blessed source-visible `atom.*.alpha` runtime transformation helper namespace(s) over the daemon operations.

## TASK-004.P2 References

- **TASK-004.R1:** [Feature plan](../runtime-transformation-primitives.plan.md)
- **TASK-004.R2:** [REPL delta](../specs/repl-api.delta.md)
- **TASK-004.R3:** `src/atom/libs/alpha.clj`, `src/todo/repl.clj`, `test/todo/libs_test.clj`, `test/todo/repl_test.clj`

## TASK-004.P3 Implementation notes

- **TASK-004.I1:** Implement the namespace split chosen in Task 1, likely `atom.graph.alpha` and `atom.views.alpha`.
- **TASK-004.I2:** Follow `atom.libs.alpha` routing style: when running inside the daemon JVM, call `todo.daemon.api` directly; from connected helper REPL clients, route through `todo.client/call-world` using `todo.repl/connected-config-dir`.
- **TASK-004.I3:** Expose helpers for query ids, tasks by ids, ancestor root ids, and subgraph with names/return shapes frozen in Task 1.
- **TASK-004.I4:** Expose view helpers for `register-view!`, `view!`, `views`, and any single-view lookup if chosen in Task 1.
- **TASK-004.I5:** Keep base `todo.repl` compact; do not preload broad transformation helper vars into `todo.repl` unless the plan is explicitly revised.
- **TASK-004.I6:** If an `install!` helper is added, document exactly what default registrations it performs and keep it free of user-specific defaults.

## TASK-004.P4 Done when

- **TASK-004.D1:** Alpha helper tests prove daemon-side and connected-helper routing for graph primitives and views.
- **TASK-004.D2:** Public helper docstrings or minimal docs make daemon-side vs connected-helper behavior clear.
- **TASK-004.D3:** Existing runtime-library workspace helpers continue to pass tests.
- **TASK-004.D4:** Relevant Clojure tests pass.
