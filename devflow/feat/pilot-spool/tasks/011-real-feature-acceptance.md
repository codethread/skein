# Task 11: Real feature end-to-end acceptance

**Document ID:** `TASK-Pilot-011`

## TASK-Pilot-011.P1 Scope

Type: HITL

Run one real, small feature intake-through-land under pilot in a disposable world, with
human touches only at brief capture, scope acceptance, and genuine escalations, then run
the merge-train and provenance audits (PROP-Pilot-001.AC1, AC2, AC4). This is the
capstone acceptance drill and it is HITL: a human must perform brief capture and scope
acceptance and judge the touch count, because those human-only points are load-bearing
and must be shown never to be auto-decided.

Owned files: none in `.skein`. Worker discipline: record `progress` with the audit
results, `status=implemented` only when every acceptance criterion passes, never close
your strand or touch siblings, kill only by PID, run everything in a disposable
`--workspace` world from `mktemp -d` — never the canonical `.skein` world and never a
user's default workspace.

## TASK-Pilot-011.P2 Must implement exactly

- **TASK-Pilot-011.MI1 (disposable world):** stand up a disposable world seeded with the
  pilot config (`ws=$(mktemp -d)`, weaver started with `--workspace "${ws:?}"`, every
  expansion guarded with `${ws:?}`). Choose a genuinely small real feature (for example
  a scoped doc or config fix) as the subject.
- **TASK-Pilot-011.MI2 (end-to-end run, AC1):** launch the feature as one pilot run and
  carry it intake → proposal → spec-plan → route-after-plan → task-breakdown →
  run-afk-loop → review → land. The human performs brief capture and proposal/scope
  sign-off; every other decision is a seat. Count the human touches from the run record
  — they must be single digits against the order-of-magnitude baseline of dozens of
  coordinator touches (cards w8rw0, r0x9l, o7r6j, n7aya), and only at brief capture,
  proposal/scope sign-off, and genuine escalations.
- **TASK-Pilot-011.MI3 (train drill, AC2):** with auto-land granted for the run, queue a
  second pilot run on the sentinel and confirm the second lands with no human merge
  command after the first releases the lock.
- **TASK-Pilot-011.MI4 (provenance audit, AC4):** confirm every checkpoint close in the
  pilot run carries `:by`, and that no `workflow/hitl` checkpoint was closed by a seat.
  This is the post-hoc drill for the human-authority boundary (RFC-021.REC4.INV): it
  proves in the run's `:by` record that the classifier exclusion held, complementing the
  classifier unit test in task 3.
- **TASK-Pilot-011.MI5 (escalation on stall):** if the run stalls at a point the design
  did not anticipate, stop and escalate to the human with the run record and notes;
  never downgrade a human-only point or fork the stage definitions to get past it.

## TASK-Pilot-011.P3 Done when

- **TASK-Pilot-011.DW1:** One real small feature ran intake-through-land under pilot in
  the disposable world with human touches only at brief capture, scope acceptance, and
  genuine escalations — single digits, counted from the run record (AC1).
- **TASK-Pilot-011.DW2:** Two auto-land-granted pilot runs queued on the sentinel and the
  second landed with no human merge command after the first released the lock (AC2).
- **TASK-Pilot-011.DW3:** The provenance audit passed — every checkpoint close carries
  `:by`, and no `workflow/hitl` checkpoint was closed by a seat (AC4).
- **TASK-Pilot-011.DW4:** The acceptance results recorded as a `progress` attr; the
  disposable worlds stopped by PID. If the drill surfaced a fix that belongs in the
  pilot layer, record it as a follow-up in the plan's Developer Notes rather than
  widening this slice.

## TASK-Pilot-011.P4 Out of scope

- **TASK-Pilot-011.OS1:** Promotion of the pilot family and dispatcher to a reference
  spool (deferred to Q6 until this drill and the task-10 drills have passed);
  auto-land-by-default (v1 keeps it off).

## TASK-Pilot-011.P5 References

- **TASK-Pilot-011.REF1:** [PROP-Pilot-001](../proposal.md) AC1, AC2, AC4, P5, Q6;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH11;
  [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md) P8.
- **TASK-Pilot-011.REF2:** repo CLAUDE.md "Disposable workspaces" and "Coordination"
  rules; `strand kanban prime`, `strand land about`, `mill strand prime` (the lifecycle
  the pilot run automates).
