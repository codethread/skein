# Task 21: Update pins and rehearse disposable cutover

**Document ID:** `TASK-Olr-021`

## TASK-Olr-021.P1 Scope

Type: AFK

Update the Skein feature branch to the published peer successor tags and peeled SHAs, then rehearse the exact cold migration and live-refresh scenarios in disposable workspaces using release coordinates rather than local roots.

## TASK-Olr-021.P2 Must implement exactly

- **TASK-Olr-021.MI1:** Change each `.skein/spools.edn` tag and SHA atomically; update synchronized test/dependency pins and expected release markers across Skein.
- **TASK-Olr-021.MI2:** Verify offline `declared`/approval/status projections and release floors against the exact published tags without changing canonical runtime state.
- **TASK-Olr-021.MI3:** Initialize a disposable workspace from generated config, start a cold weaver with the release pins, and run help, graph, kanban, devflow, agent harness, scheduler/cron, Chime, and status smoke paths.
- **TASK-Olr-021.MI4:** Rehearse full refresh, targeted harness alias change, source deletion/path-shrink residual, code-only reload, clean stop, and second cold start. Record wall-clock and operator steps for the canonical adoption runbook.
- **TASK-Olr-021.MI5:** Confirm no production source or config still relies on local peer worktrees. If rehearsal exposes a peer code defect, stop: add a repair task, return through Tasks 18–20, and publish a new marker; never amend or retarget an existing tag.
- **TASK-Olr-021.MI6:** Rehearse against old durable data: copy the canonical workspace's `data/skein.sqlite` (file copy only — the canonical world is never opened by the new code) into a disposable world and start the new generation over that copy. Verify retained-wake reporting for pre-existing wake rows, kanban/board projections over old strands, devflow run resume from state written by the previous spool versions, and no residual or migration surprise in joined status. Then rehearse the old-pin/old-binary rollback against that same touched copy and record both runbooks.

## TASK-Olr-021.P3 Done when

- **TASK-Olr-021.DW1:** Tags and peeled SHAs match remote release records and all synchronized pin tests pass.
- **TASK-Olr-021.DW2:** Disposable cold migration and every live scenario pass using published coordinates only.
- **TASK-Olr-021.DW4:** The old-data rehearsal (MI6) passes over a snapshot of the canonical database, including the rollback leg, and its runbook is recorded for Task 23.
- **TASK-Olr-021.DW3:** Canonical weaver metadata, process, pins, database, and active runs are untouched, and no peer source changed after its published marker.

## TASK-Olr-021.P4 Out of scope

- **TASK-Olr-021.OS1:** Do not merge/land branches, install global binaries, or restart the canonical weaver.

## TASK-Olr-021.P5 References

- **TASK-Olr-021.REF1:** `PLAN-Olr-001.PH5/V8`, peer release records, `.skein/spools.edn`, and disposable-workspace hard rules in `AGENTS.md`.
