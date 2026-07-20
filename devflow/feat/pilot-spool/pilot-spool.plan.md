# Pilot Plan

**Document ID:** `PLAN-Pilot-001`
**Feature:** `pilot-spool`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** [RFC-021 Pilot: hands-off feature runs](../../rfcs/2026-07-08-pilot-spool.md) (accepted; recommendation
RFC-021.REC1 = O3 + O5 + O8 + O12)
**Root specs:** none changed — this feature lands entirely in `.skein` policy (see PROP-Pilot-001.P6)
**Feature specs:** none — v1 ships no `specs/*.delta.md` (RFC-021.P7, PROP-Pilot-001.P6)
**Status:** Reviewed
**Last Updated:** 2026-07-08

## PLAN-Pilot-001.P1 Goal and scope

Build the pilot layer described in [PROP-Pilot-001](./proposal.md): launch a whole
feature once and have it run intake through land as a single pilot run, driven by
ephemeral seats the dispatcher spawns only at decision points, with a merge train
that lands signed-off green runs one at a time. All of v1 lands as `.skein` policy in
a new `pilot.clj` concern file plus edits to sibling concern files; no shipped
contract changes (PROP-Pilot-001.P6). The plan translates PROP-Pilot-001.S1–S8 into
sequenced phases and hands the execution contracts to the task queue.

## PLAN-Pilot-001.P2 Approach

- **PLAN-Pilot-001.A1:** Build bottom-up in one file-owned chain. Nearly every phase
  edits the new `.skein/pilot.clj` concern file, so the pilot slices run sequentially
  (each `blocked_by` the previous) rather than as parallel siblings — the endorsed
  pattern for splitting one file by section (`strand agent about` :task-sizing).
  Sibling files touched once each (`init.clj`, `harnesses.clj`, `config.clj`,
  `attention.clj`) are owned by exactly one slice so no two mutators share a scope.
  The land family in `workflows.clj` is not edited — pilot drives it through the
  existing land op (PROP-Pilot-001.S6).
- **PLAN-Pilot-001.A2:** `pilot.clj` is a concern file loaded by `init.clj` through
  `runtime-alpha/use!`, ordered after `:config`, `:workflows`, and `:harnesses`
  because it composes the devflow stages, the land family, the CLI-tail helpers, and
  the seat harnesses. It installs the pilot workflow family, the dispatcher cron job,
  the seat contract, the auto-signoff and train policy, and the recovery ops, all as
  reviewable data and small functions over the trusted spool API — no module-level
  atoms (state is runtime-owned via `skein.api.runtime.alpha/spool-state`).
- **PLAN-Pilot-001.A3:** Reuse, never fork. The pilot family strings the existing
  `skein.spools.devflow` stages and routes into the existing land family in
  `.skein/workflows.clj` as continuations. devflow is an external git-pinned spool
  (`codethread/devflow` in `.skein/spools.edn`, source synced by the weaver), so the
  composition surface is read from the synced spool checkout, not a repo-local file.
  The devflow reuse seam (PROP-Pilot-001.Q1) is resolved in the first slice by reading
  that pinned surface; the recorded fallback is wrapping the shipped devflow run as a
  black box (continuations around it). If even that forces forking stage definitions,
  the slice stops and escalates rather than forking silently.
- **PLAN-Pilot-001.A4:** Prove each slice two ways: pure pieces (the checkpoint-
  ownership map, the auto-signoff predicate, FIFO admission, hang-budget and
  lease predicates) get focused tests under `test/skein/` and a config-load assertion
  in the `skein.config-test` style; live behavior (seat spawn, dispatcher sweep, the
  merge train) is exercised only in a disposable `--workspace` world seeded with the
  pilot config, never the canonical `.skein` world.

## PLAN-Pilot-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Pilot-001.AA1 | `.skein/pilot.clj` (new) | The whole pilot layer, built section by section across phases PH1 through PH8 |
| PLAN-Pilot-001.AA2 | `.skein/init.clj` | Register the `:pilot` module via `runtime-alpha/use!`, ordered after `:config`, `:workflows`, `:harnesses` |
| PLAN-Pilot-001.AA3 | `.skein/harnesses.clj` | Seat harness routing (which harness a spawned seat runs on) |
| PLAN-Pilot-001.AA4 | `.skein/config.clj` | Pilot named queries (attention frontier, train queue, failures), the pilot CLI ops incl. `about`/`prime`, and the lease-recovery op |
| PLAN-Pilot-001.AA5 | `.skein/pilot.clj` (CI poller) | Poller drives `land complete <feature> step=ci-green` through the land op; land family not edited |
| PLAN-Pilot-001.AA6 | `.skein/attention.clj` | Chime rules for the escalation endpoints (RFC-021.REC8) |
| PLAN-Pilot-001.AA7 | `docs/skein.md` | Operator section: launching a pilot run, the human-only points, reading escalations |
| PLAN-Pilot-001.AA8 | `test/skein/pilot_test.clj` (new) | Focused tests for the pure predicates and config load |

## PLAN-Pilot-001.P4 Contract and migration impact

- **PLAN-Pilot-001.CM1:** No shipped contract changes. All new surface is `.skein`
  policy and the repo-local `pilot` op family; the strand-model, CLI, REPL-API, and
  daemon-runtime specs are untouched (PROP-Pilot-001.P6). New state is strand
  attributes only — `pilot/seat`, `pilot/train-state`, `pilot/auto-land`,
  `pilot/max-review-rounds`, `pilot/hang-budget-secs`, and per-transition notes — all
  JSON `TEXT`, no DDL and no core primitive (PROP-Pilot-001.NG2). Promotion to a
  reference spool is deferred until one real feature lands through pilot (Q6).

## PLAN-Pilot-001.P5 Implementation phases

Each phase names the identity invariants it must preserve — the provenance, lease,
and checkpoint-ownership properties the whole design rests on — and validates live
behavior only in a disposable world.

### PLAN-Pilot-001.PH1 Pilot family and checkpoint ownership

Outcome: `pilot.clj` registers a pilot workflow family that strings the devflow
stages (intake → proposal → spec-plan → route-after-plan → task-breakdown →
run-afk-loop, or direct-implementation) and routes into the land family as one
run-id, with the checkpoint-ownership map applied (PROP-Pilot-001.S1–S2). devflow
is an external git-pinned spool (`codethread/devflow` in `.skein/spools.edn`),
read from the synced spool checkout, not a repo-local source file. The devflow
reuse seam (Q1) is resolved against that pinned surface or escalated; the recorded
fallback is wrapping the shipped devflow run as a black box, never forking stage
definitions.

Identity invariants: mapped onto the pinned devflow graph — brief capture is a
`:self` step (`:capture-brief`), and the human-only checkpoints are the intake
worktree checkpoint (`:create-or-confirm-worktree`), the proposal sign-off
(`:human-signoff-proposal`), and every abort, all staying `:kind :human` with
`workflow/hitl`; `:discuss-scope` and `:route-after-plan` are already agent
checkpoints decided by seats as shipped; the conversion targets
(`:human-signoff-spec-plan`, `:human-signoff-tasks`, `:human-acceptance-afk`)
become agent checkpoints that gain an `escalate` choice and never an `abort`
choice, subject to the Q1 seam. The seat classifier is the human-authority
boundary (RFC-021.REC4.INV): because `checkpoint-kind` is provenance-only and
unenforced (TEN-002), the classifier never selects a `workflow/hitl` checkpoint
and never targets `break-lock`. Every stage records its evidence before its
checkpoint, because routed choices are hard cutovers.

### PLAN-Pilot-001.PH2 Ephemeral seats and lease

Outcome: the seat contract and lease attributes (PROP-Pilot-001.S3, RFC-021.REC5),
the seat prompt template with latest-notes injection, the seat harness routing, and
the reason-gated lease-recovery op (RFC-021.A1).

Identity invariants: one leased seat per attention state; the lease is a
`pilot/seat = <run-id>` stamp on the run root; a seat verifies its lease on start and
exits without acting if superseded; every decision note is structured handover —
decision, reason, HEAD sha, run ids, evidence, next expected state; a seat that
cannot reconstruct enough context escalates instead of guessing; seat spawns per
attention state are bounded by `max-attempts`.

### PLAN-Pilot-001.PH3 Dispatcher

Outcome: a cron job over pilot run roots plus RFC-009 scheduler wakes that sweeps for
attention states — ready agent checkpoint, ready `:self` step, stalled gate,
hang-budget breach, stage done — read through the same lens `workflow/await!` uses,
and spawns exactly one seat per unserved state (PROP-Pilot-001.S4, RFC-021.REC4),
plus the pilot named queries and `about`/`prime` discovery surface.

Identity invariants: exactly one seat per unserved attention state, guarded by the
lease; a lease whose run is terminal is clearable and re-stamped before respawn
(RFC-021.A1); the dispatcher never spawns a second seat over a live lease. The
classifier is the human-authority boundary (RFC-021.REC4.INV): it never emits a
seat-spawn state for a `workflow/hitl` (`:kind :human`) checkpoint and never
targets `break-lock`; both route to chime as human attention only. This exclusion
carries a dedicated test — the classifier, given a ready hitl checkpoint, must
exclude it (task 3) — complementing the AC4 provenance drill (task 10/11).

### PLAN-Pilot-001.PH4 Review cycle as routing

Outcome: after AFK the run pours a review stage at an explicit `<base>..HEAD` range;
a seat reads the synthesis; clean advances toward land, findings route a bounded
revise loop (default 3, `pilot/max-review-rounds`) where the seat sizes fix tasks,
treadle executes them, and re-review runs against the new HEAD; exhaustion escalates
with the full round history (PROP-Pilot-001.S5, RFC-021.REC6).

Identity invariants: review evidence is live only for the HEAD sha it names
(RFC-021.A4); a synthesis at any older sha is non-evidence; re-review notes state
which prior findings are fixed and which remain; rounds are bounded and exhaustion
escalates loudly.

### PLAN-Pilot-001.PH5 CI poller driving the land op

Outcome: a cron poller in `.skein/pilot.clj` over `gh pr checks` / `gh run list`
drives the land family's `:ci-green` `:self` step through the existing land op —
once checks are green at the step's sha it runs `land complete <feature>
step=ci-green` `:by "pilot-ci-poller"` with a sha-named evidence note, exactly as a
human coordinator drives that wait (PROP-Pilot-001.S6 first half). The land family is
not edited; there is no `:self`→`:ci` gate conversion. The kanban-finish behavior is
already shipped — the land cleanup step already kanban-finishes the card for
card-backed runs (`.skein/workflows.clj`, asserted in `test/skein/config_test.clj`),
so it is not a gap and this slice adds nothing there.

Identity invariants: the poller drives `ci-green` only on real `gh` check evidence at
the step's sha; its `:by "pilot-ci-poller"` and note make every completion auditable;
the hands-on land family keeps working unchanged because pilot uses its public op and
touches none of its definitions.

### PLAN-Pilot-001.PH6 Auto-signoff and merge train

Outcome: auto-signoff applies the five machine-checkable O12 criteria, all at the
same HEAD sha, with no partial credit; FIFO train admission on the merge lock with
`pilot/train-state "queued"` parking, lock-release-scheduled admission and a periodic
sweep backstop; auto-land as an explicit per-run grant (`pilot/auto-land`, default
off); duplicate or malformed locks escalate and are never auto-broken
(PROP-Pilot-001.S6 second half, RFC-021.REC7).

Identity invariants: all five sign-off criteria evaluated at one HEAD sha, anything
short escalates; admission is FIFO by enqueue time; `break-lock` stays human-only;
auto-land is an explicit recorded grant, never a default.

### PLAN-Pilot-001.PH7 Hang budgets and anomaly recovery

Outcome: per-harness hang budgets (`pilot/hang-budget-secs`); the dispatcher flags an
in-flight run past budget as an attention state; a triage seat inspects `agent logs`
and either kills by PID through `agent kill` and retries within `max-attempts`, or
extends the budget once with a recorded reason; one automatic kill-and-retry per
attempt chain, then escalate (PROP-Pilot-001.S7, RFC-021.A2).

Identity invariants: kills are PID-only through `agent kill` — never pattern kills;
one automatic kill-and-retry per attempt chain then loud escalation; a budget
extension is recorded with a reason.

### PLAN-Pilot-001.PH8 Escalation as attention

Outcome: chime rules for every escalation endpoint — HITL checkpoint ready, review
rounds exhausted, seat failure or exhaustion, hang-budget breach, lock anomaly, train
parked beyond budget, and seat `escalate` choices — kept strictly as attention, each
paired with a durable note on the run (PROP-Pilot-001.S8, RFC-021.REC8).

Identity invariants: escalation is attention only, never a side effect on the run;
every escalation also writes a durable note so the record survives a missed
notification.

### PLAN-Pilot-001.PH9 Operator guide

Outcome: a `docs/skein.md` pilot section for the code owner — how to launch a pilot
run, the four human-only points (brief, scope acceptance, break-lock, escalation
endpoints), how auto-land is granted, and how to read escalations and the train
queue.

Identity invariants: the doc names the human-only points as load-bearing and states
that seats never close a `workflow/hitl` checkpoint.

### PLAN-Pilot-001.PH10 Anomaly and audit drills

Outcome: in a disposable world seeded with the pilot config, each of the four anomaly
classes is induced and either recovers within its bound or escalates loudly with a
durable note; the escalation drill and evidence audit pass (PROP-Pilot-001.AC3, AC5,
AC6).

Identity invariants: nothing stalls silently longer than one dispatcher period plus
its budget; every automatic transition note names the HEAD sha and run ids;
`chime/failures` stays empty after the escalation drill.

### PLAN-Pilot-001.PH11 Real feature end-to-end acceptance

Outcome: one real, small feature runs intake through land under pilot in a disposable
world with human touches only at brief capture, scope acceptance, and genuine
escalations; the merge-train and provenance audits pass (PROP-Pilot-001.AC1, AC2,
AC4).

Identity invariants: human touches are single digits against the order-of-magnitude
baseline of dozens of coordinator touches (cards w8rw0, r0x9l, o7r6j, n7aya); every
checkpoint close carries `:by`; no `workflow/hitl` checkpoint was closed by a seat.

## PLAN-Pilot-001.P6 Validation strategy

- **PLAN-Pilot-001.V1:** Blocking quality gates stay at zero after every slice:
  `make fmt-check lint reflect-check docs-check`. Any new splint suppression needs a
  written justification in `.splint.edn`.
- **PLAN-Pilot-001.V2:** Pure pieces are unit-tested under `test/skein/pilot_test.clj`
  and the pilot config asserted to load in an isolated runtime (the
  `skein.config-test` `with-config-runtime` pattern). Run the suite under the shared
  lock: `flock -w 3600 /tmp/skein-test.lock clojure -M:test`.
- **PLAN-Pilot-001.V3:** Live behavior — seat spawn and lease, dispatcher sweep,
  review routing, CI gate completion, auto-signoff, the merge train, hang-budget kill
  — is exercised only in a disposable `--workspace` world from `mktemp -d`, seeded
  with the pilot config, never the canonical `.skein` world and never a user's default
  workspace. Guard every path expansion with `${ws:?}`.
- **PLAN-Pilot-001.V4:** The acceptance drills (PH10–PH11) are the end-to-end proof:
  the four anomaly classes induced and bounded, the provenance and evidence audits,
  the escalation drill with `chime/failures` empty, and one real small feature run
  intake-through-land with single-digit human touches. All in a disposable world.
- **PLAN-Pilot-001.V5:** Never restart or reload the canonical weaver to pick up
  pilot changes; that is the coordinator's call with explicit user sign-off. Config
  reload for validation happens in the disposable world.

## PLAN-Pilot-001.P7 Risks and open questions

- **PLAN-Pilot-001.R1 (routed-cutover fragility):** a stage whose evidence is not
  written before its checkpoint loses it on a routed choice. Mitigation is the
  evidence-before-checkpoint rule (PROP-Pilot-001.R1); it constrains PH1 stage
  composition and is why Q1 must resolve in the first slice.
- **PLAN-Pilot-001.R2 (attribute-lease races):** seat leases are attributes, not a
  core claim primitive (PROP-Pilot-001.NG2/R4). Seat-start lease verification and
  dispatcher re-stamp-before-respawn bound the blast radius; a first-class lease is
  the recorded promotion candidate if races show up.
- **PLAN-Pilot-001.R3 (policy too strict):** auto-signoff has no partial credit, so
  over-conservative criteria push every run to escalation (PROP-Pilot-001.R3). The v1
  auto-land default is off so the criteria tune against real runs first.
- **PLAN-Pilot-001.Q1 (devflow reuse seam):** does `skein.spools.devflow` (read from
  the synced pinned-spool checkout, not a repo-local source file) expose enough stage
  composition for pilot to string its stages and set their checkpoint kinds, or must
  pilot wrap devflow's shipped run as a black box with continuations around it?
  Resolved in PH1 by reading that pinned surface early; wrapping is the recorded
  fallback and forking stage definitions is never done and the worst outcome. If
  wrapping proves blocking too, PH1 stops and escalates rather than forking silently
  (PROP-Pilot-001.Q1).
- **PLAN-Pilot-001.Q2 (hang-budget measurement):** measured from spawn or from last
  observed activity, and the right per-harness defaults — needs observation, tuned in
  PH7 against the disposable-world drill (PROP-Pilot-001.Q3).

## PLAN-Pilot-001.P8 Task context

- **PLAN-Pilot-001.TC1:** Read `devflow/TENETS.md`, `devflow/PHILOSOPHY.md`, this
  feature's [proposal.md](./proposal.md), and the accepted [RFC-021](../../rfcs/2026-07-08-pilot-spool.md)
  before coding. The pilot layer is `.skein` policy over the trusted spool API; it
  owns no privileged state and adds no engine primitive (PROP-Pilot-001.NG2).
- **PLAN-Pilot-001.TC2:** `.skein` is split one file per concern (see the repo
  CLAUDE.md "Coordination" section): `init.clj` activation order, `config.clj` queries
  and CLI ops, `workflows.clj` land and delegate-pipeline, `harnesses.clj` seats and
  routing, `attention.clj` chime rules, `nvd_scan.clj` the cron precedent,
  `reviewers.clj` rosters. `pilot.clj` is the new concern file. Read `docs/skein.md`
  before changing `.skein` config and smoke changes in a disposable world first.
- **PLAN-Pilot-001.TC3:** Anchors to study — the land family and merge sentinel in
  `.skein/workflows.clj`, the cron job shape in `.skein/nvd_scan.clj`, the chime rules
  in `.skein/attention.clj`, the seat-lease precedent in the treadle gate stamp
  (`spools/shuttle/treadle.md`), the composition surface of `skein.spools.devflow`
  — an external git-pinned spool: read its contract via the redirect in
  `spools/devflow.md` and its source from the synced pinned-spool checkout (the
  `codethread/devflow` sha in `.skein/spools.edn`) — and the config-load test pattern
  in `test/skein/config_test.clj`.
- **PLAN-Pilot-001.TC4:** Every `.skein` change is validated in a disposable world
  from `mktemp -d`, never the canonical `.skein` world; never restart or reload the
  canonical weaver; kill only by PID.

## PLAN-Pilot-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.
