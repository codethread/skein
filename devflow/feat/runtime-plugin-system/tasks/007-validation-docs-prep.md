# Validation Docs Prep

**Document ID:** `RPS-TASK-007`
**Status:** Blocked
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md), [cli.delta.md](../specs/cli.delta.md)

## RPS-TASK-007.P1 Scope

Type: AFK

Complete full validation and prepare feature-local docs/specs for promotion after implementation.

## RPS-TASK-007.P2 Implementation notes

- **RPS-TASK-007.I1:** Run full project validation.
- **RPS-TASK-007.I2:** Ensure generated daemon/plugin state from tests or smoke is cleaned up.
- **RPS-TASK-007.I3:** Prepare deltas for root spec promotion in the next task; do not archive in this task.

## RPS-TASK-007.P3 Done when

- **RPS-TASK-007.D1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **RPS-TASK-007.D2:** `(cd cli && go test ./...)` passes.
- **RPS-TASK-007.D3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes.
- **RPS-TASK-007.D4:** `git status --short` shows no generated SQLite/runtime/plugin artifacts.
