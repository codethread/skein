# Task 8: Escalation chime rules

**Document ID:** `TASK-Pilot-008`

## TASK-Pilot-008.P1 Scope

Type: AFK

Wire the escalation endpoints to chime attention rules in `.skein/attention.clj`, and
add the shared escalation-note helper to `.skein/pilot.clj`. Every escalation is
attention only — never a side effect on the run — and every escalation also writes a
durable note so the record survives a missed notification.

Owned files: `.skein/attention.clj`, `.skein/pilot.clj` (escalation-note helper — the
final link in the `pilot.clj` chain). Worker discipline: record `progress`,
`status=implemented` only on a green gate, one atomic commit of owned files (no push),
never close your strand or touch siblings, kill only by PID, live validation in a
disposable world only.

## TASK-Pilot-008.P2 Must implement exactly

- **TASK-Pilot-008.MI1 (chime rules):** add chime rules in `.skein/attention.clj`
  (shape from the existing rules there) for every escalation endpoint — HITL checkpoint
  ready, review rounds exhausted, seat failure or exhaustion, hang-budget breach, lock
  anomaly, train parked beyond budget, and seat `escalate` choices (RFC-021.REC8). Reuse
  the existing HITL-checkpoint rule if it already covers that endpoint rather than
  duplicating it.
- **TASK-Pilot-008.MI2 (attention only):** the rules are strictly attention — they
  surface a run for a human, and take no action on the run itself. No rule closes a
  checkpoint, breaks a lock, or mutates the run.
- **TASK-Pilot-008.MI3 (durable note):** a shared escalation-note helper in
  `.skein/pilot.clj` writes a durable note on the run at every escalation, so a missed
  notification still leaves the record. Every escalation path from tasks 2–7 routes its
  note through this helper.
- **TASK-Pilot-008.MI4 (no chime failures):** the rules must not raise; a malformed row
  is skipped without respamming (mirror the parked-run rule's guard in
  `.skein/attention.clj`). `chime/failures` stays empty.

## TASK-Pilot-008.P3 Done when

- **TASK-Pilot-008.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-008.DW2:** `test/skein/pilot_test.clj` (or a chime-rule test alongside
  the existing attention tests) asserts each escalation endpoint matches its rule, the
  escalation-note helper writes the required fields, and no rule mutates the run.
  `flock -w 3600 /tmp/skein-test.lock env PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
  clojure -M:test` green.
- **TASK-Pilot-008.DW3:** In a disposable world, trip one escalation endpoint (e.g.
  review-rounds exhausted) and confirm the chime rule surfaces the run, a durable note
  is written, and `chime/failures` is empty. Stop the disposable weaver by PID.
- **TASK-Pilot-008.DW4:** One atomic commit of `.skein/attention.clj`, `.skein/pilot.clj`,
  and the test additions (no push).

## TASK-Pilot-008.P4 Out of scope

- **TASK-Pilot-008.OS1:** The full escalation drill across all endpoints (task 10); the
  operator doc (task 9). This slice wires the rules and the durable-note helper.

## TASK-Pilot-008.P5 References

- **TASK-Pilot-008.REF1:** [PROP-Pilot-001](../proposal.md) S8;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH8; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  REC8.
- **TASK-Pilot-008.REF2:** `.skein/attention.clj` (rule shapes, parked-run guard),
  `spools/chime.cookbook.md`, `.skein/init.local.clj` (where the notifier binds — do not
  edit; it is gitignored and developer-local).
