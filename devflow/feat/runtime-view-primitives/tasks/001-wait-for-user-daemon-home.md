# Wait for User Daemon Home

**Document ID:** `RVP-TASK-001`
**Status:** Blocked
**Plan:** [runtime-view-primitives.plan.md](../runtime-view-primitives.plan.md)

## RVP-TASK-001.P1 Scope

Type: HITL

This is the feature-level blocked marker. Do not start implementation for `runtime-view-primitives` until `devflow/feat/user-daemon-home` has shipped or the human explicitly chooses to rebase this feature on a different daemon connection model.

## RVP-TASK-001.P2 Human unblock condition

- **RVP-TASK-001.H1:** `user-daemon-home` has promoted its daemon runtime, CLI, and REPL contracts into root specs; or
- **RVP-TASK-001.H2:** the human records a new decision in `runtime-view-primitives.plan.md` allowing this feature to proceed before that promotion.

## RVP-TASK-001.P3 Done when

- **RVP-TASK-001.D1:** The plan's blocked marker has been updated or removed with the reason.
- **RVP-TASK-001.D2:** This task is marked complete in `tasks/index.yml`.
- **RVP-TASK-001.D3:** Downstream task statuses are changed from `blocked` to `pending` only where their `blocked_by` dependencies allow them to run.
