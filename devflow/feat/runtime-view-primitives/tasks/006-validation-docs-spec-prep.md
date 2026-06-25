# Validation Docs and Spec Prep

**Document ID:** `RVP-TASK-006`
**Status:** Blocked
**Plan:** [runtime-view-primitives.plan.md](../runtime-view-primitives.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md), [cli.delta.md](../specs/cli.delta.md)

## RVP-TASK-006.P1 Scope

Type: AFK

Complete validation and prepare feature-local docs/specs for eventual promotion after implementation.

## RVP-TASK-006.P2 Implementation notes

- **RVP-TASK-006.I1:** Update smoke/demo coverage for a trusted view loaded or registered in the connected daemon world.
- **RVP-TASK-006.I2:** Update docs examples only where they describe the new REPL/runtime view workflow.
- **RVP-TASK-006.I3:** Keep root spec promotion for devflow finish/archive; this task prepares deltas, it does not archive the feature.

## RVP-TASK-006.P3 Done when

- **RVP-TASK-006.D1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
- **RVP-TASK-006.D2:** `(cd cli && go test ./...)` passes or is confirmed unaffected.
- **RVP-TASK-006.D3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes.
- **RVP-TASK-006.D4:** `git status --short` shows no generated SQLite/runtime artifacts.
