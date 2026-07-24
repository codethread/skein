# Task 6: [HITL] External Skein v1 stamp gate

**Document ID:** `TASK-Dsp-006`

## TASK-Dsp-006.P1 Scope

Type: HITL

Block every convention-dependent sibling release until the external kanban card `b3v1r` closes with a Skein `v1` marker that includes landed Phase A. This feature does not own or create that stamp — card `b3v1r` alone does. This task is the explicit human gate, not an AFK dependency: a downstream release must never run against an unmarked core.

## TASK-Dsp-006.P2 Must implement exactly

- **TASK-Dsp-006.MI1:** Confirm Phase A has landed (Task 5 accepted) and that card `b3v1r` has stamped an annotated Skein `v1` marker whose commit includes that landed Phase A.
- **TASK-Dsp-006.MI2:** Record the stamped `v1` marker, its peeled commit SHA, and the human confirmation as notes on the plan's Developer Notes and card `b3v1r`.
- **TASK-Dsp-006.MI3:** Do not proceed until the human presses the button or explicitly delegates the stamp; leave this task `blocked` until then.

## TASK-Dsp-006.P3 Done when

- **TASK-Dsp-006.DW1:** The Skein `v1` marker exists, peels to a commit that includes landed Phase A, and is recorded.
- **TASK-Dsp-006.DW2:** Human confirmation of the stamp is recorded before any sibling release starts.

## TASK-Dsp-006.P4 Out of scope

- **TASK-Dsp-006.OS1:** Creating or stamping Skein `v1` inside this feature — that is card `b3v1r`'s sole responsibility (ruling `wnxuv`).
- **TASK-Dsp-006.OS2:** Any sibling code change or marker publication (Tasks 7–8).

## TASK-Dsp-006.P5 References

- **TASK-Dsp-006.REF1:** `PLAN-Dsp-001.CM3`, `.PH-B`, `.TC4`; kanban card `b3v1r`; ruling `wnxuv`.
- **TASK-Dsp-006.REF2:** Kanban task `l5lwo`; waq0l note `5bae1` (sibling suites hard-code `../skein-src`).
