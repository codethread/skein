# Route REPL and Dev Helpers

**Document ID:** `TASK-004`
**Feature:** `daemon-runtime`
**Plan:** [../daemon-runtime.plan.md](../daemon-runtime.plan.md)
**Specs:** [../specs/repl-api.delta.md](../specs/repl-api.delta.md), [../specs/daemon-runtime.md](../specs/daemon-runtime.md)

## TASK-004.P1 Scope

Type: AFK

Change `todo.repl` and `dev/user.clj` to use daemon-backed connections while preserving the compact helper vocabulary.

## TASK-004.P2 Required work

- **TASK-004.W1:** Redefine `todo.repl/open!` as `(open! db-file)`, selecting and connecting to an existing daemon for that database path.
- **TASK-004.W2:** Keep `init!`, `task!`, `update!`, `task`, `tasks`, and `ready` behavior and normalized return shapes.
- **TASK-004.W3:** Ensure helpers fail before `open!` and fail loudly if the selected daemon later becomes unreachable.
- **TASK-004.W4:** Update `dev/user.clj` demo helpers so they either instruct users to start the daemon first or provide explicit daemon start/stop helpers without silently falling back to direct SQLite.
- **TASK-004.W5:** Keep direct advanced nREPL access possible, but the blessed helper path should use `todo.client`/`todo.daemon.api`.

## TASK-004.P3 Done when

- **TASK-004.D1:** REPL helper tests cover pre-open failure, successful daemon-backed task flow, and daemon unavailable failure.
- **TASK-004.D2:** `dev/user.clj` can be loaded in a REPL and its demo flow uses the daemon-backed `open!` path.
- **TASK-004.D3:** The stripped helper vocabulary remains visible in `todo.repl`.
