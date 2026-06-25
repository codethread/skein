# Init and REPL Examples

**Document ID:** `RPS-TASK-006`
**Status:** Complete
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)
**Specs:** [daemon-runtime.delta.md](../specs/daemon-runtime.delta.md), [repl-api.delta.md](../specs/repl-api.delta.md), [cli.delta.md](../specs/cli.delta.md)

## RPS-TASK-006.P1 Scope

Type: AFK

Update examples and smoke coverage to show the plugin model through selected config-dir `init.clj`, local plugin directory loading, and connected REPL/stdin workflows.

## RPS-TASK-006.P2 Implementation notes

- **RPS-TASK-006.I1:** Add a minimal example `init.clj` using `atom.bootstrap.alpha/use-defaults!`.
- **RPS-TASK-006.I2:** Show manual clone/local plugin directory usage with `atom.plugin.alpha/load-plugin!`, including an `atom-plugin.edn` example with `:format-version 1`, `:name`, and optional `:version`/`:provides`.
- **RPS-TASK-006.I3:** Show how agents can inspect loaded plugin metadata through `todo daemon repl --stdin`.
- **RPS-TASK-006.I4:** Include a short explanation of blessed vs lower-level vs internal/raw schema use.
- **RPS-TASK-006.I5:** Do not show git fetch/tag/pin helpers as implemented behavior.
- **RPS-TASK-006.I6:** Document that plugin `init.clj` should be idempotent, `load-plugin!` owns metadata registration, partial trusted side effects are not rolled back on failure, and plugin-specific dependencies/classpath mutation are not supported.

## RPS-TASK-006.P3 Done when

- **RPS-TASK-006.D1:** Docs/examples no longer imply core is the only extension path.
- **RPS-TASK-006.D2:** Smoke or integration coverage verifies bootstrap from `init.clj`, local plugin directory loading, and plugin introspection through connected REPL/stdin.
