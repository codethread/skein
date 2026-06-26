# Implement Daemon Use Registry

**Document ID:** `RLW-TASK-004`
**Status:** Pending
**Plan:** [../runtime-library-workspace.plan.md](../runtime-library-workspace.plan.md)
**Spec delta:** [../specs/repl-api.delta.md](../specs/repl-api.delta.md)

## RLW-TASK-004.P1 Scope

Type: AFK

Implement `atom.libs.alpha/use!` and module-use introspection for resilient layered daemon startup. This task owns daemon-side `:ns`/`:file` activation, `:libs` and `:after` gating, and result recording.

## RLW-TASK-004.P2 Required work

- **RLW-TASK-004.W1:** Add daemon-lifetime module-use registry state and helpers such as `uses` and `use`.
- **RLW-TASK-004.W2:** Implement `(use! key opts)` with validation for `:ns`, `:file`, `:libs`, `:after`, `:call`, and `:required?`.
- **RLW-TASK-004.W3:** Ensure malformed opts throw loudly and are not treated as optional skips.
- **RLW-TASK-004.W4:** Gate `:libs` before load/require using approved + sync state. Return `:skipped` for `:not-approved`, `:not-synced`, or `:sync-failed` as appropriate.
- **RLW-TASK-004.W5:** Gate `:after` before load/require. Return `:skipped` with `:missing-after` when dependencies are absent, skipped, or failed.
- **RLW-TASK-004.W6:** Implement daemon-side `require` for `:ns`, selected-config-dir-relative `load-file` for `:file`, and zero-arity `:call` after successful load.
- **RLW-TASK-004.W7:** Record load/call exceptions as `:failed` by default; with `:required? true`, record then rethrow.
- **RLW-TASK-004.W8:** Duplicate `use!` keys replace previous state for reload workflows.

## RLW-TASK-004.P3 Done when

- **RLW-TASK-004.D1:** Tests cover loaded namespace, loaded file, call return recording, all gating skip reasons, load/call failure recording, required rethrow, malformed options, and duplicate replacement.
- **RLW-TASK-004.D2:** Connected helper calls route to daemon state and execute activation in the daemon, not the helper JVM.
- **RLW-TASK-004.D3:** Relevant Clojure tests pass.
