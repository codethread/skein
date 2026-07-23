# Task 22: Run full acceptance and prepare landing

**Document ID:** `TASK-Olr-022`

## TASK-Olr-022.P1 Scope

Type: AFK

Run final release-coordinate acceptance, verify documentation/spec promotion and clean repository state, and prepare peer plus Skein branches for the coordinator landing workflow. This task proves readiness but does not adopt the new canonical runtime.

## TASK-Olr-022.P2 Must implement exactly

- **TASK-Olr-022.MI1:** Re-run Skein's full locked Clojure suite once at queue acceptance, Go tests, smoke, pinned spool suite gate, format, lint, reflection, docs, and API-doc clean regeneration against published peer pins.
- **TASK-Olr-022.MI2:** Run each peer's current full suite/quality gates at its released tag/commit and confirm release exception records match the landed source.
- **TASK-Olr-022.MI3:** Verify root specs contain every reviewed delta, feature deltas are Merged, task/plan notes contain validation evidence, and card `uxc5f` is closed only after its acceptance evidence exists.
- **TASK-Olr-022.MI4:** Check all worktrees for generated SQLite/runtime artifacts, uncommitted changes, wrong bases, unpushed commits, or stale local-root references.
- **TASK-Olr-022.MI5:** Prepare dependency-ordered landing handoffs: peer commits/tags first if not already landed, then Skein via `strand land`; identify the exact main commit and adoption runbook for Task 23. A peer code failure after publication returns to a new repair/release marker cycle; published tags are never amended.

## TASK-Olr-022.P3 Done when

- **TASK-Olr-022.DW1:** Every blocking gate is green with commands/results recorded on the doing task and plan notes.
- **TASK-Olr-022.DW2:** Branches are clean, reviewed, pushed, and ready for coordinator landing; published pins resolve and peer source is byte-identical to the published marker.
- **TASK-Olr-022.DW3:** Canonical weaver still runs the old generation uninterrupted and Task 23 has exact quiet/adoption/recovery instructions.

## TASK-Olr-022.P4 Out of scope

- **TASK-Olr-022.OS1:** Do not run global `make install`, restart the canonical weaver, or claim live adoption success.

## TASK-Olr-022.P5 References

- **TASK-Olr-022.REF1:** `AGENTS.md` validation/landing discipline, `strand land about`, `PLAN-Olr-001.V6–V8`, and Task 21 rehearsal notes.
