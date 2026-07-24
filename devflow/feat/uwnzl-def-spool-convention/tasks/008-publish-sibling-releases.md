# Task 8: Publish sibling releases and raise v1 floors

**Document ID:** `TASK-Dsp-008`

## TASK-Dsp-008.P1 Scope

Type: AFK

With the Skein `v1` stamp confirmed (Task 6) and the branches prepared (Task 7), raise each sibling's `:skein/min` floor to `v1`, cut new ordered `v<int>` markers, and record a `release-exception.md` per repo. This runs under the coordinator's delegated release authority and only after the stamp exists.

## TASK-Dsp-008.P2 Must implement exactly

- **TASK-Dsp-008.MI1:** On the prepared candidate commits, raise `:skein/min` to `v1` in devflow.spool, kanban.spool, and agent-harness.spool.
- **TASK-Dsp-008.MI2:** Run each repo's compatibility gates against the stamped skein-src `v1`.
- **TASK-Dsp-008.MI3:** Cut a new ordered `v<int>` marker per repo following that repo's release precedent, with a `release-exception.md` record for the breaking successor.
- **TASK-Dsp-008.MI4:** Resolve and record each marker's peeled commit SHA from the remote; fail if it differs from the reviewed candidate. Published markers are never amended — a later failure returns to a new repair-and-release marker cycle.

## TASK-Dsp-008.P3 Done when

- **TASK-Dsp-008.DW1:** All three ordered markers exist remotely, peel to the reviewed commits, carry `:skein/min "v1"`, and have matching release-exception records.
- **TASK-Dsp-008.DW2:** Each sibling's compatibility gate is green against stamped skein-src `v1`.
- **TASK-Dsp-008.DW3:** Marker, peeled-SHA, and gate notes are recorded on kanban task `l5lwo` and the plan's Developer Notes; Skein's own `.skein/spools.edn` pins are unchanged.

## TASK-Dsp-008.P4 Out of scope

- **TASK-Dsp-008.OS1:** Bumping Skein's sibling pins or removing legacy grammar keys — that is Phase C (Task 9).
- **TASK-Dsp-008.OS2:** Creating the Skein `v1` stamp itself (card `b3v1r`, Task 6).

## TASK-Dsp-008.P5 References

- **TASK-Dsp-008.REF1:** `PLAN-Dsp-001.PH-B`, `.CM3`; kanban task `l5lwo`; peer release precedent from epic `waq0l`.
- **TASK-Dsp-008.REF2:** Task 6 stamp confirmation; Task 7 candidate commits.
