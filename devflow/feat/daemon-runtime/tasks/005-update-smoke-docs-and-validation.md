# Update Smoke, Docs, and Validation

**Document ID:** `TASK-005`
**Feature:** `daemon-runtime`
**Plan:** [../daemon-runtime.plan.md](../daemon-runtime.plan.md)
**Specs:** [../specs/cli.delta.md](../specs/cli.delta.md), [../specs/repl-api.delta.md](../specs/repl-api.delta.md)

## TASK-005.P1 Scope

Type: AFK

Update automated smoke coverage and contributor/user documentation for the daemon-backed workflow.

## TASK-005.P2 Required work

- **TASK-005.W1:** Update `dev/todo/smoke.clj` so CLI and REPL smoke paths run through a real daemon connection.
- **TASK-005.W2:** Ensure the smoke script starts/stops disposable daemons and cleans up SQLite/runtime metadata artifacts.
- **TASK-005.W3:** Update `README.md` quickstart examples to show daemon start, task commands, REPL `open!`, and daemon stop/status.
- **TASK-005.W4:** Update `AGENTS.md` with daemon-backed CLI/REPL guidance and validation notes, including the tmux manual verification requirement for this feature.
- **TASK-005.W5:** Keep generated SQLite/runtime artifacts out of git status after smoke validation.

## TASK-005.P3 Done when

- **TASK-005.D1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **TASK-005.D2:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes and demonstrates daemon-backed CLI and REPL flows.
- **TASK-005.D3:** README and AGENTS examples no longer teach direct per-invocation SQLite task commands as the blessed path.
