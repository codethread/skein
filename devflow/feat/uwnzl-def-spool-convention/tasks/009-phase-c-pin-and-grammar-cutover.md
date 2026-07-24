# Task 9: Phase C pin bump and legacy-key cutover

**Document ID:** `TASK-Dsp-009`

## TASK-Dsp-009.P1 Scope

Type: AFK

Close the transitional window. Bump the sibling pins to the released markers, drop the remaining sibling-backed triples and the parity guard, remove `:contribute`/`:reconcile` from `::module-opts` with no shim, land the promoted C19 exception and final spec deltas, then refresh the canonical runtime without restarting it and verify live. Tracked as kanban task `36o73`.

## TASK-Dsp-009.P2 Must implement exactly

- **TASK-Dsp-009.MI1:** Bump `.skein/spools.edn` sibling pins to the Phase B released markers (Task 8) and drop the remaining sibling-backed `:contribute`/`:reconcile` triples from `.skein/init.clj`.
- **TASK-Dsp-009.MI2:** Sweep the remaining core tests and fixtures that generate explicit entry-point keys, and delete the narrowed parity test from Task 3.
- **TASK-Dsp-009.MI3:** Remove `:contribute`/`:reconcile` from `::module-opts` with no aliases and no shims (TEN-000@1).
- **TASK-Dsp-009.MI4:** Land the Phase C spec delta that promotes the SPEC-003.C19 exception into the root spec.
- **TASK-Dsp-009.MI5:** Refresh the canonical world with `runtime/refresh!` — never a restart, which needs explicit user sign-off — and verify live behaviour.

## TASK-Dsp-009.P3 Done when

- **TASK-Dsp-009.DW1:** The epic DONE-WHEN greps hold: `git grep '(def module'` in `spools/` is empty and `.skein/init.clj` carries no `:contribute`/`:reconcile`.
- **TASK-Dsp-009.DW2:** Focused suites, `make spool-suite-gate` against bumped pins, and `make fmt-check lint reflect-check docs-check` are green.
- **TASK-Dsp-009.DW3:** A canonical-world refresh runs green with live verification, and `git diff --check` is clean.

## TASK-Dsp-009.P4 Out of scope

- **TASK-Dsp-009.OS1:** Restarting the canonical weaver or running `make install`.
- **TASK-Dsp-009.OS2:** The final full acceptance matrix and review handover (Task 10).

## TASK-Dsp-009.P5 References

- **TASK-Dsp-009.REF1:** `PLAN-Dsp-001.PH-C`, `.CM1`, `.CM2`, `.V4`; `DELTA-Dsp-001.CC5`; close-out shape `rtnfv` from epic `waq0l`.
- **TASK-Dsp-009.REF2:** Kanban task `36o73`; Task 8 released markers; the customisation reload ladder in `docs/spools/customisation.md`.
