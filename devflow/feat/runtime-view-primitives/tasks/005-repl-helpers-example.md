# REPL Helpers and Example

**Document ID:** `RVP-TASK-005`
**Status:** Blocked
**Plan:** [runtime-view-primitives.plan.md](../runtime-view-primitives.plan.md)
**Specs:** [repl-api.delta.md](../specs/repl-api.delta.md)

## RVP-TASK-005.P1 Scope

Type: AFK

Expose the runtime view primitives through `todo.repl` helpers and add a small flagship example that composes them from a connected daemon REPL.

## RVP-TASK-005.P2 Implementation notes

- **RVP-TASK-005.I1:** Add REPL helpers for `query-ids!`, `tasks-by-ids`, `ancestor-root-ids`, the selected downward graph primitive, view registration, and `view` invocation.
- **RVP-TASK-005.I2:** Use the post-`user-daemon-home` connection model and remediation language.
- **RVP-TASK-005.I3:** Add an example active-feature-DAG view that starts from repo-scoped seed work and returns Clojure data.
- **RVP-TASK-005.I4:** Keep output Clojure-native; do not design CLI JSON contracts.

## RVP-TASK-005.P3 Done when

- **RVP-TASK-005.D1:** REPL tests cover helper composition after connection.
- **RVP-TASK-005.D2:** REPL tests cover failure before connection.
- **RVP-TASK-005.D3:** The example can be invoked through the connected REPL or stdin path established by `user-daemon-home`.
