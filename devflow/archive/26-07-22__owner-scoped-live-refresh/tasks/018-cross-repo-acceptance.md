# Task 18: Run cross-repository acceptance matrix

**Document ID:** `TASK-Olr-018`

## TASK-Olr-018.P1 Scope

Type: AFK

Run and record the complete pre-release acceptance matrix across the Skein and peer feature worktrees using local coordinates and disposable worlds. This is an acceptance task, not an untracked repair loop: record failures and route them through a precise existing or newly added task before re-running.

## TASK-Olr-018.P2 Must implement exactly

- **TASK-Olr-018.MI1:** Run focused cross-repository suites against the exact local worktree combination: `clojure -M:test skein.spools-test skein.weaver-test skein.config-test`; agent-harness `clojure -M:test`; kanban `clojure -M:test`; and devflow `clojure -M:test`. Record exact commits and commands.
- **TASK-Olr-018.MI2:** Exercise the live-image keystone: launch a long-running cheap shell agent, refresh its alias/backend definitions, prove it remains supervised/capturable/stoppable with launch bindings, then prove a later launch uses the new definition.
- **TASK-Olr-018.MI3:** Exercise full and targeted module refresh, owner deletion/override/restoration, event dispatch snapshot, workflow re-point, Chime baseline, cron two-layer reconciliation, generic retained wake, and kanban tracker refresh.
- **TASK-Olr-018.MI4:** Reproduce `uxc5f` path shrink and source deletion in the integrated world; prove status remains residual/non-clean and failed classification cannot clear it. Add final evidence note to `uxc5f` and close it only if its implementation commit is present and all focused tests pass.
- **TASK-Olr-018.MI5:** Capture commands, commits, outcomes, and any expected warnings in the plan Developer Notes. Remove generated SQLite/runtime artifacts after every world.

## TASK-Olr-018.P3 Done when

- **TASK-Olr-018.DW1:** Focused Skein cross-repo tests, `(cd cli && go test ./...)`, `clojure -M:smoke`, `make fmt-check lint reflect-check docs-check`, and peer repository test/quality gates pass for the recorded candidate set; the full locked Skein suite remains exclusively Task 22.
- **TASK-Olr-018.DW2:** Agent-harness, kanban, and devflow tests/quality gates pass against that same recorded Skein commit; any failure has a tracked task and the acceptance matrix is rerun only after that task closes.
- **TASK-Olr-018.DW3:** All keystone scenarios pass without touching or restarting the canonical weaver; worktrees contain no unexplained generated artifacts.

## TASK-Olr-018.P4 Out of scope

- **TASK-Olr-018.OS1:** Do not run previous-marker compatibility alarms, publish tags, update consumer pins, or land branches.

## TASK-Olr-018.P5 References

- **TASK-Olr-018.REF1:** `PLAN-Olr-001.V1–V8`, card `uxc5f`, and each repository's contributor/test commands.
