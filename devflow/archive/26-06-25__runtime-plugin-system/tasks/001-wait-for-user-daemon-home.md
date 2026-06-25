# Confirm User Daemon Home Shipped

**Document ID:** `RPS-TASK-001`
**Status:** Complete
**Plan:** [runtime-plugin-system.plan.md](../runtime-plugin-system.plan.md)

## RPS-TASK-001.P1 Scope

Type: HITL

This task was the feature-level blocked marker. It is complete because `user-daemon-home` has shipped and is archived at `devflow/archive/26-06-25__user-daemon-home`.

## RPS-TASK-001.P2 Confirmed shipped contracts

- **RPS-TASK-001.C1:** `devflow/specs/cli.md` now defines selected config-dir daemon worlds, `config.json` with `source`, default `init.clj` daemon startup, `todo daemon repl`, and `todo daemon repl --stdin`.
- **RPS-TASK-001.C2:** `devflow/specs/repl-api.md` now defines `connect!` by daemon world and connected REPL/stdin behavior.
- **RPS-TASK-001.C3:** `devflow/specs/daemon-runtime.md` now defines config-dir daemon worlds, selected state/data dirs, fixed socket/metadata discovery, default daemon-owned storage, and selected config-dir `init.clj` loading.

## RPS-TASK-001.P3 Done when

- **RPS-TASK-001.D1:** The plan's blocked marker has been replaced with the shipped prerequisite reference.
- **RPS-TASK-001.D2:** This task is marked complete in `tasks/index.yml`.
- **RPS-TASK-001.D3:** Downstream AFK tasks are marked pending with dependency edges preserved.
