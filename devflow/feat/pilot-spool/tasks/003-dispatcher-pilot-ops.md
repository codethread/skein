# Task 3: Dispatcher and pilot ops

**Document ID:** `TASK-Pilot-003`

## TASK-Pilot-003.P1 Scope

Type: AFK

Add the dispatcher to `.skein/pilot.clj`: a cron job over pilot run roots plus RFC-009
scheduler wakes that sweeps for attention states and spawns exactly one seat per
unserved state. Add the pilot named queries and CLI op surface (including `about`/
`prime` and the lease-recovery op from task 2) to `.skein/config.clj`.

Owned files: `.skein/pilot.clj` (dispatcher section), `.skein/config.clj`. Worker
discipline: record `progress` as you go, `status=implemented` only on a green gate, one
atomic commit of owned files (no push), never close your strand or touch siblings, kill
only by PID, live validation in a disposable world only.

## TASK-Pilot-003.P2 Must implement exactly

- **TASK-Pilot-003.MI1 (cron job):** register a `skein.spools.cron` job over pilot run
  roots, following the shape of `.skein/nvd_scan.clj`. On each tick it sweeps every
  active pilot run for attention states.
- **TASK-Pilot-003.MI2 (attention states):** detect these states, read through the same
  lens `workflow/await!` uses — ready agent checkpoint, ready `:self` step, stalled
  gate, hang-budget breach, and stage done (PROP-Pilot-001.S4, RFC-021.REC4). Hang-
  budget breach detection is refined in task 7; here, leave a seam the budget check
  plugs into.
- **TASK-Pilot-003.MI3 (one seat per state):** for each attention state on a run with no
  live seat lease, spawn exactly one seat (using the task-2 seat mechanism). The lease
  is the guard against a double spawn: never spawn a second seat over a live lease.
- **TASK-Pilot-003.MI4 (terminal-lease reclaim):** a lease whose owning run is terminal
  is clearable; the dispatcher re-stamps before respawning (RFC-021.A1). A duplicate or
  otherwise anomalous lease is not auto-cleared here — it is an attention state that
  escalates (wired in task 8).
- **TASK-Pilot-003.MI5 (scheduler wakes):** register RFC-009 scheduler wakes for known
  future events — CI settle, hang budgets, lock-release retry — so the common case does
  not wait a full cron period. The sweep remains the backstop.
- **TASK-Pilot-003.MI6 (named queries):** in `.skein/config.clj`, add pilot named
  queries: the attention frontier (runs with an unserved attention state), the train
  queue (runs parked `pilot/train-state "queued"`), and pilot failures (seat failures/
  exhaustions). Mirror the existing query registration form.
- **TASK-Pilot-003.MI7 (op surface):** add the pilot CLI ops to `.skein/config.clj`,
  declared with `:subcommands` (never hand-written usage or dispatch, per
  `docs/writing-shared-spools.md`): an authored `about` JSON manual, a `prime`
  orientation, and the lease-recovery op (task 2's fn) with its required reason. Public
  op output is JSON.

## TASK-Pilot-003.P3 Done when

- **TASK-Pilot-003.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" make fmt-check
  lint reflect-check docs-check` reports zero findings.
- **TASK-Pilot-003.DW2:** `test/skein/pilot_test.clj` gains cases: the sweep classifies
  each attention state from fabricated run views; the dispatcher spawns no second seat
  when a live lease exists; a terminal-run lease is reclaimable. `flock -w 3600
  /tmp/skein-test.lock env PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`
  green.
- **TASK-Pilot-003.DW3:** In a disposable world, run one dispatcher tick against a
  pilot run parked at an agent checkpoint and confirm exactly one seat spawns; a second
  tick with the lease still live spawns none. `strand pilot about` and `strand pilot
  prime` return their JSON manuals. Stop the disposable weaver by PID.
- **TASK-Pilot-003.DW4:** One atomic commit of `.skein/pilot.clj`, `.skein/config.clj`,
  and the `pilot_test.clj` additions (no push).

## TASK-Pilot-003.P4 Out of scope

- **TASK-Pilot-003.OS1:** Review routing (task 4), CI gates and train (tasks 5–6),
  hang-budget policy (task 7), chime escalation rules (task 8). The dispatcher spawns
  seats for attention states; how each state is decided lands in the later slices.

## TASK-Pilot-003.P5 References

- **TASK-Pilot-003.REF1:** [PROP-Pilot-001](../proposal.md) S4;
  [PLAN-Pilot-001](../pilot-spool.plan.md) PH3; [RFC-021](../../../rfcs/2026-07-08-pilot-spool.md)
  REC4, A1.
- **TASK-Pilot-003.REF2:** `.skein/nvd_scan.clj` (cron job shape), `.skein/config.clj`
  (query and op registration, `:subcommands`), `docs/writing-shared-spools.md`
  (authoring rules), `spools/cron.cookbook.md`, `strand devflow-conventions`.
