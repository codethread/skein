# Task 6: Auto-signoff and merge train

**Document ID:** `TASK-Pilot-006`

## TASK-Pilot-006.P1 Scope

Type: AFK

Add auto-signoff and the merge train to `.skein/pilot.clj`: the five machine-checkable
sign-off criteria evaluated at one HEAD sha with no partial credit, FIFO admission on
the merge lock with `queued` parking and lock-release retry, the explicit per-run
auto-land grant (default off), and loud escalation for duplicate or malformed locks
(never auto-broken).

Owned files: `.skein/pilot.clj` (signoff + train section). Worker discipline: record
`progress`, `status=implemented` only on a green gate, one atomic commit of owned files
(no push), never close your strand or touch siblings, kill only by PID, live validation
in a disposable world only.

## TASK-Pilot-006.P2 Must implement exactly

- **TASK-Pilot-006.MI1 (auto-signoff):** a predicate that grants sign-off only when all
  five criteria hold at the same HEAD sha — branch pushed, CI green at HEAD, roster
  synthesis clean at HEAD, review rounds within bound, and no lock anomaly. No partial
  credit: anything short raises the escalation signal (RFC-021.REC7).
- **TASK-Pilot-006.MI2 (train admission):** after sign-off the run tries to acquire the
  merge lock via the land family's existing sentinel (`.skein/workflows.clj`). If held,
  the run parks with `pilot/train-state "queued"`; admission is FIFO by enqueue time.
- **TASK-Pilot-006.MI3 (lock-release retry):** lock release (the sentinel strand
  closing) schedules an admission attempt (via the task-3 scheduler wakes), with the
  dispatcher's periodic sweep as backstop since release is not a documented event
  contract.
- **TASK-Pilot-006.MI4 (auto-land grant):** auto-land is an explicit per-run grant
  recorded as `pilot/auto-land "true"` on the run root, set by the human at scope
  acceptance. Default is off: with no grant the train queues and escalates at sign-off
  rather than landing.
- **TASK-Pilot-006.MI5 (lock anomaly):** a duplicate or malformed merge lock escalates
  loudly (signal wired to chime in task 8) and is never auto-broken; `break-lock` stays
  human-only. Reuse the land family's lock-inspection helpers rather than reimplementing
  lock parsing.

## TASK-Pilot-006.P3 Done when

- **TASK-Pilot-006.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-006.DW2:** `test/skein/pilot_test.clj` gains cases: sign-off holds only
  when all five criteria are true at one sha and fails (with escalation signal) when any
  one is false or evaluated at a mixed sha; FIFO ordering of two queued runs; auto-land
  default off queues rather than lands; a duplicate lock raises the anomaly signal and
  is not broken. `flock -w 3600 /tmp/skein-test.lock env
  PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-Pilot-006.DW3:** In a disposable world, queue two signed-off pilot runs on the
  sentinel and confirm the second parks `queued` and admission is attempted on the
  first's lock release. Stop the disposable weaver by PID.
- **TASK-Pilot-006.DW4:** One atomic commit of `.skein/pilot.clj` and the `pilot_test.clj`
  additions (no push).

## TASK-Pilot-006.P4 Out of scope

- **TASK-Pilot-006.OS1:** Hang budgets (task 7); the chime rules that fire on the
  escalation signals this slice raises (task 8). The end-to-end train drill is task 11.

## TASK-Pilot-006.P5 References

- **TASK-Pilot-006.REF1:** [PROP-Pilot-001](../proposal.md) S6 (train half), G3;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH6; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  REC7.
- **TASK-Pilot-006.REF2:** `.skein/workflows.clj` (merge sentinel, `acquire-merge-lock!`,
  `release-merge-lock!`, `inspect-merge-lock`, `break-merge-lock!`), `strand land about`
  (land discipline).
