# Task 19: Prepare peer compatibility exception releases

**Document ID:** `TASK-Olr-019`

## TASK-Olr-019.P1 Scope

Type: AFK

Prepare, but do not publish, the owner-approved breaking successor releases for agent-harness, kanban, and devflow. Reconfirm current tags and compute exact successor tags/peeled commits at execution time.

## TASK-Olr-019.P2 Must implement exactly

- **TASK-Olr-019.MI1:** Re-read each peer's release contract and current annotated markers. Use v8/v5/v3 only if v7/v4/v2 remain latest; otherwise choose the next integer and update all candidate docs/pin plans.
- **TASK-Olr-019.MI2:** Run each previous-marker `bin/compat-alarm` against the release candidate. Separate failures caused by the explicitly authorized lifecycle/API break from unrelated regressions; unrelated failures block publication.
- **TASK-Olr-019.MI3:** Write a release exception record in each peer repository naming old/new marker, affected roots/names, expected alarm failures, immutable old pin, sole known consumer, required Skein commit range, and no-shim decision.
- **TASK-Olr-019.MI4:** Verify each candidate commit is clean, pushed or otherwise publishable, and passes its current owner suite against the exact Skein candidate.
- **TASK-Olr-019.MI5:** Produce a human review packet with exact annotated-tag and push commands, commit SHAs, peeled SHA verification commands, expected consumer pin changes, and rollback (retain old pins) procedure.

## TASK-Olr-019.P3 Done when

- **TASK-Olr-019.DW1:** All three release packets and exception records exist, expected alarm failures are reviewed, and no unrelated failure remains.
- **TASK-Olr-019.DW2:** No tag, release, or consumer pin has been created/changed by this task.
- **TASK-Olr-019.DW3:** The plan Developer Notes record candidate commits and proposed markers for the HITL publisher.

## TASK-Olr-019.P4 Out of scope

- **TASK-Olr-019.OS1:** Do not create/push tags, update `.skein/spools.edn`, merge Skein, or restart any weaver.

## TASK-Olr-019.P5 References

- **TASK-Olr-019.REF1:** `DELTA-OlrAlpha-001.CC4–CC7`, `PLAN-Olr-001.CM4`, peer `bin/compat-alarm`, and user authorization recorded on card `tsofs`.
