# Task 10: Anomaly and audit drills

**Document ID:** `TASK-Pilot-010`

## TASK-Pilot-010.P1 Scope

Type: AFK

In a disposable world seeded with the pilot config, induce each of the four anomaly
classes and confirm each recovers within its bound or escalates loudly with a durable
note, then run the escalation and evidence audits (PROP-Pilot-001.AC3, AC5, AC6). This
is a validation slice: it owns no `.skein` source, it exercises the layer built in
tasks 1–8 and records the drill results.

Owned files: none in `.skein`. A drill script or notes may be added under
`devflow/feat/pilot-spool/` or `test/skein/` only if it does not touch a pilot source
file another slice owns. Worker discipline: record `progress` with the drill outcomes,
`status=implemented` only when every drill passes, never close your strand or touch
siblings, kill only by PID, run everything in a disposable `--workspace` world from
`mktemp -d` — never the canonical `.skein` world.

## TASK-Pilot-010.P2 Must implement exactly

- **TASK-Pilot-010.MI1 (disposable world):** stand up a disposable world:
  `ws=$(mktemp -d)`, seed it with the repo `.skein` config plus the pilot layer, start a
  weaver with `mill weaver start --workspace "${ws:?}"`. Guard every expansion with
  `${ws:?}`. Hold `ws` in your own shell variable, not a shared scratch file.
- **TASK-Pilot-010.MI2 (A1 stale squat):** induce a stale `pilot/seat` lease whose run
  is terminal; confirm the dispatcher treats it as clearable and re-stamps before
  respawn, and that the recovery op clears a named lease with a required reason.
- **TASK-Pilot-010.MI3 (A2 hung worker):** induce an over-budget in-flight run; confirm
  one automatic PID kill-and-retry then a loud escalation, with no pattern kill.
- **TASK-Pilot-010.MI4 (A3 cold resume):** give a fresh seat only the run record and
  notes (no launch-prompt context); confirm it either decides correctly or escalates
  naming what it could and could not reconstruct.
- **TASK-Pilot-010.MI5 (A4 review-evidence staleness):** present a synthesis at an older
  sha than HEAD; confirm it is rejected as non-evidence for the advance decision.
- **TASK-Pilot-010.MI6 (escalation drill, AC6):** exhaust a review-round bound and a
  hang budget; confirm each produces a chime notification and a durable escalation note,
  and `chime/failures` is empty.
- **TASK-Pilot-010.MI7 (evidence audit, AC5):** confirm every automatic transition note
  in the drill names the HEAD sha and the run ids, and that a cold agent given only the
  run record states the run's current state correctly.

## TASK-Pilot-010.P3 Done when

- **TASK-Pilot-010.DW1:** All four anomaly classes (MI2–MI5) either recover within their
  declared bound or escalate loudly with a durable note; nothing stalls silently longer
  than one dispatcher period plus its budget.
- **TASK-Pilot-010.DW2:** The escalation drill (MI6) shows a chime notification and a
  durable note for each exhausted bound, with `chime/failures` empty.
- **TASK-Pilot-010.DW3:** The evidence audit (MI7) passes — every automatic transition
  note names the HEAD sha and run ids, and the cold-agent state read is correct.
- **TASK-Pilot-010.DW4:** Drill outcomes recorded as a `progress` attr and, if a drill
  script was added, one atomic commit of that script (no push). Stop every disposable
  weaver by PID.

## TASK-Pilot-010.P4 Out of scope

- **TASK-Pilot-010.OS1:** The real feature end-to-end run and the merge-train and
  provenance audits (task 11). This slice induces the anomaly classes in isolation.

## TASK-Pilot-010.P5 References

- **TASK-Pilot-010.REF1:** [PROP-Pilot-001](../proposal.md) AC3, AC5, AC6, G6;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH10;
  [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md) A1–A4.
- **TASK-Pilot-010.REF2:** repo CLAUDE.md "Disposable workspaces" rule; `strand agent
  about` (seat/lease/kill verbs); `spools/chime.cookbook.md` (`chime/failures`).
