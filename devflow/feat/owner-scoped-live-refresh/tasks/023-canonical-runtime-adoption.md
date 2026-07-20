# Task 23: Adopt the new canonical live runtime

**Document ID:** `TASK-Olr-023`

## TASK-Olr-023.P1 Scope

Type: HITL

After all branches land and CI is green, perform the one user-approved canonical generation replacement required to adopt the new base Skein code and peer pins. This is the only task allowed to interrupt the old coordination runtime.

## TASK-Olr-023.P2 Must implement exactly

- **TASK-Olr-023.MI1:** Present current active agent/roster/workflow state and wait until no active run, interactive session, or uncommitted coordination operation depends on the old weaver. Obtain explicit user approval for the cutover window.
- **TASK-Olr-023.MI2:** The human performs any required global installation step; agents must not run `make install`. Stop/start the canonical weaver only through the approved supervisor path and by exact PID where manual termination is required.
- **TASK-Olr-023.MI3:** Verify startup on the landed Skein commit and published peer pins. Inspect joined status for module failures, unexplained residuals, retained wakes, hard conflicts, or missing owners before resuming delegation.
- **TASK-Olr-023.MI4:** Run focused live smoke for help, kanban card/task views, devflow status, agent harness/roster discovery, one cheap disposable agent launch, workflow executor discovery, Chime, cron/scheduler, full refresh, and targeted harness refresh.
- **TASK-Olr-023.MI5:** If adoption fails, preserve logs and metadata, stop by PID/supervisor, and follow the rehearsed old-pin/old-binary recovery; never improvise repeated restarts against the coordination database.

## TASK-Olr-023.P3 Done when

- **TASK-Olr-023.DW1:** User approval and quiet-state evidence precede the replacement.
- **TASK-Olr-023.DW2:** The canonical weaver reports the new modules/pins healthy, live smoke passes, and agent work can resume.
- **TASK-Olr-023.DW3:** Card `tsofs` has a final adoption note; implementation tasks and devflow run may proceed to landing/archive completion under normal coordinator policy.

## TASK-Olr-023.P4 Out of scope

- **TASK-Olr-023.OS1:** Do not modify feature code, peer releases, database schema/data, or restart repeatedly without a newly diagnosed cause and user approval.

## TASK-Olr-023.P5 References

- **TASK-Olr-023.REF1:** `AGENTS.md` hard rules, Task 21 rehearsal, Task 22 landing handoff, and `PLAN-Olr-001.PH6/R6`.
