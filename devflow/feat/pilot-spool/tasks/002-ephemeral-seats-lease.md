# Task 2: Ephemeral seats and lease

**Document ID:** `TASK-Pilot-002`

## TASK-Pilot-002.P1 Scope

Type: AFK

Add the ephemeral seat contract and lease to `.skein/pilot.clj`: the lease attributes,
the seat prompt template with latest-notes injection, the seat harness routing in
`.skein/harnesses.clj`, and the reason-gated lease-recovery op. A seat serves exactly
one attention state — verify lease, read state and notes, decide one point, write a
structured decision note, clear the lease, exit.

Owned files: `.skein/pilot.clj` (seat section — you extend the file from task 1),
`.skein/harnesses.clj`. Worker discipline: record `progress` as you go, set
`status=implemented` only when the gate is green, one atomic commit of owned files (no
push), never close your strand or touch siblings, kill only by PID, live validation in
a disposable world only.

## TASK-Pilot-002.P2 Must implement exactly

- **TASK-Pilot-002.MI1 (lease):** the lease is a `pilot/seat = <run-id>` stamp on the
  pilot run root, mirroring treadle's gate stamp (`spools/shuttle/treadle.md`). Provide
  fns to stamp, verify (does the live seat still own it), and clear the lease.
- **TASK-Pilot-002.MI2 (seat lifecycle):** a seat, on start, verifies its lease and
  exits without acting if superseded (RFC-021.A1). If it holds the lease it reads the
  run view and the latest notes, applies recorded policy to decide that one attention
  state, writes a decision note, clears the lease, and exits.
- **TASK-Pilot-002.MI3 (structured decision note):** every decision note is a structured
  handover carrying decision, reason, HEAD sha, run ids, evidence, and next expected
  state (RFC-021.A3). Provide a single note-builder fn so all seats emit the same shape.
- **TASK-Pilot-002.MI4 (escalate-on-uncertainty):** a seat that cannot reconstruct
  enough context from state plus notes escalates with what it could and could not
  reconstruct, rather than guessing (TEN-003, RFC-021.A3).
- **TASK-Pilot-002.MI5 (bounded spawns):** seat spawns per attention state are bounded
  by `max-attempts`; exhaustion escalates (escalation itself is wired in task 8 — here,
  expose the bound and the exhaustion signal).
- **TASK-Pilot-002.MI6 (prompt template):** a seat prompt template that injects the run
  view and the latest notes so launch-prompt staleness cannot mislead a successor
  (RFC-021.A3). Route seats to a harness declared in `.skein/harnesses.clj`.
- **TASK-Pilot-002.MI7 (recovery op):** a `pilot` recovery fn that clears a named lease
  with a required reason (RFC-021.A1) — the wrapped, no-longer-folklore form of
  treadle's clear-and-respawn. Expose it for the CLI op surface wired in task 3.

## TASK-Pilot-002.P3 Done when

- **TASK-Pilot-002.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-002.DW2:** `test/skein/pilot_test.clj` gains cases: lease stamp/verify/
  clear round-trips; a seat whose lease is superseded exits without acting; the
  decision-note builder produces every required field; the recovery fn refuses a
  missing reason. Run `flock -w 3600 /tmp/skein-test.lock env
  PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-Pilot-002.DW3:** In a disposable world (`ws=$(mktemp -d)`, seeded pilot
  config, weaver started with `--workspace "${ws:?}"`), spawn one seat against a
  fabricated attention state and confirm it stamps then clears the lease and leaves a
  structured note. Stop the disposable weaver by PID.
- **TASK-Pilot-002.DW4:** One atomic commit of `.skein/pilot.clj`,
  `.skein/harnesses.clj`, and the `pilot_test.clj` additions (no push).

## TASK-Pilot-002.P4 Out of scope

- **TASK-Pilot-002.OS1:** The dispatcher that spawns seats and the pilot ops/queries —
  task 3. The escalation chime rules — task 8. This slice provides the seat mechanism
  the dispatcher will drive.

## TASK-Pilot-002.P5 References

- **TASK-Pilot-002.REF1:** [PROP-Pilot-001](../proposal.md) S3, S7;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH2; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  REC5, A1, A3.
- **TASK-Pilot-002.REF2:** `spools/shuttle/treadle.md` (gate-stamp precedent),
  `.skein/harnesses.clj` (seat routing), `strand agent about` (seat spawn/await/note
  verbs, `max-attempts`).
