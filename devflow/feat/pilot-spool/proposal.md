# Pilot Proposal

**Document ID:** `PROP-Pilot-001`
**Last Updated:** 2026-07-08
**Related RFCs:** [RFC-021 Pilot: hands-off feature runs](../../rfcs/2026-07-08-pilot-spool.md) (accepted; recommendation
RFC-021.REC1 = O3 + O5 + O8 + O12). Adjacent: [RFC-009 Weaver Scheduler](../../rfcs/2026-06-29-weaver-scheduler.md) (the
future-wake substrate the dispatcher uses), [RFC-010 Shuttle-backed Coordination](../../rfcs/2026-07-02-shuttle-backed-coordination.md)
(treadle, the gate-fulfilment precedent the seat contract mirrors).
**Related root specs:** None changed. This feature lands entirely in `.skein` policy and repo-local spool layer; the shipped
contracts (strand-model, cli, repl-api, daemon-runtime, alpha-surface) are untouched. See PROP-Pilot-001.P6 for the placement
rationale and RFC-021.P7 for the promotion candidates deferred out of v1.
**Related contracts:** [Devflow spool](../../../spools/devflow.md), [Workflow spool](../../../spools/workflow.md),
[Treadle spool](../../../spools/shuttle/treadle.md), [Chime cookbook](../../../spools/chime.cookbook.md),
[Cron cookbook](../../../spools/cron.cookbook.md), [Land family](../../../.skein/workflows.clj).
**Source:** [Coordination research digest](./research.md) and the accepted RFC-021. Load-bearing evidence is restated below.

## PROP-Pilot-001.P1 Problem

Devflow, shuttle, and treadle already automate the work inside a feature: stages
pour as strand molecules, treadle fulfils `:subagent` gates, and review fans out
through declared rosters. What stays manual is the space between — a resident
coordinator session watches every run and makes each bounded decision by hand.
The 2026-07-08 session recorded dozens of coordinator touches across four nearby
cards — w8rw0, r0x9l, o7r6j, and n7aya, whose handover and synthesis notes are
the durable evidence: review-round driving, result verification, failure triage,
retry and kill decisions, handover notes, and evidence refreshes (RFC-021.P1).

Almost every touch was one bounded decision over durable state, and the same
session proved cold resume twice: a fresh coordinator holding only the card, its
notes, and the commits made the correct next decision without the predecessor's
context. The resident session pays for continuous attention when the graph only
needs a decision-maker at decision points. Three structural gaps let that stand
(RFC-021.P1.1–P1.3): `:self` steps and checkpoints progress only while a driver
is live, so an unattended run stalls at a wait a machine could complete; no
contract spawns a driver when a run needs attention; and the merge lock
serializes local main but defines no train.

Some points must stay human no matter how far this goes: brief capture,
proposal/scope sign-off or abort, `break-lock`, and the endpoint of every
bounded escalation. The design keeps all four load-bearing.

## PROP-Pilot-001.P2 Goals

- **PROP-Pilot-001.G1:** Launch a whole feature once and have it run intake
  through land with human input only at the declared human-only points and at
  loud escalations.
- **PROP-Pilot-001.G2:** Coordinate through ephemeral seats — the system spawns
  a coordinator run only when a run needs a decision; the seat reads durable
  state, decides that one point per recorded policy, writes its evidence, and
  exits. Cost is proportional to decisions, not elapsed time.
- **PROP-Pilot-001.G3:** Run a merge train on the merge sentinel: signed-off
  pilot runs with green CI land one at a time, and lock release admits the next
  queued run.
- **PROP-Pilot-001.G4:** Leave a durable note on every automatic transition
  naming the HEAD sha, the run ids, and the evidence, so cold resume and later
  review keep working.
- **PROP-Pilot-001.G5:** Hold TEN-003/TEN-004 discipline: every loop bounded,
  every exhausted bound escalating loudly to a human, and the new surface
  expressed as minimal `.skein` policy before it becomes anything larger.
- **PROP-Pilot-001.G6:** Give each of the four observed anomaly classes (stale
  run-id squat, hung worker, cold resume from partial handover, stale review
  evidence) an explicit, bounded recovery or escalation path.

## PROP-Pilot-001.P3 Non-goals

- **PROP-Pilot-001.NG1:** No auto-decided human checkpoints. `workflow/hitl`
  stays load-bearing; pilot never downgrades a human-only point to an agent
  checkpoint. The seat classifier is the enforcement — see the human-authority
  boundary invariant (RFC-021.REC4.INV, PROP-Pilot-001.S4).
- **PROP-Pilot-001.NG2:** No new engine primitives in v1 — no scheduler beyond
  RFC-009's, no core lease/claim feature, no contract-level event stream.
  Attributes and trusted config come first; P6 names the promotion candidates.
- **PROP-Pilot-001.NG3:** No replay or audit machinery. Resumability only:
  notes exist for the next reader, and the code wins over the record.
- **PROP-Pilot-001.NG4:** No multi-lane or cross-repo train. One local main, one
  lock, one queue.
- **PROP-Pilot-001.NG5:** Pilot does not retire hands-on coordination. It is a
  per-feature launch choice; resident driving of devflow and land keeps working
  unchanged.
- **PROP-Pilot-001.NG6:** No change to the worker contract and no expanded
  worker autonomy. Workers still stop at implemented plus committed.
- **PROP-Pilot-001.NG7:** No implementation strategy, phase breakdown, attribute
  or DDL specifics, or test matrix here — those belong in the feature plan and
  task queue.

## PROP-Pilot-001.P4 Proposed scope

The chosen direction is RFC-021.REC1 (O3 + O5 + O8 + O12): a pilot workflow
family, driven by ephemeral seats that a cron-plus-scheduler dispatcher spawns,
with sign-off and train policy as reviewable config data. It lives in `.skein`
policy first and promotes to a reference spool only after one real feature lands
through it, mirroring how treadle followed manual delegation. The RFC records the
rejected alternatives (chime side effects, a kanban-attribute state machine, a
resident coordinator, a new reference adapter, shared-land or prompt-embedded
sign-off); this proposal does not restate them.

- **PROP-Pilot-001.S1 (one run from intake to land):** A pilot run is a single
  run-id that strings the existing devflow stages and routes into the existing
  land family as continuations, never forking either. devflow is an external
  git-pinned spool (`codethread/devflow` in `.skein/spools.edn`, source synced
  by the weaver), so its composition surface is discovered from the pinned
  artifact, not a repo-local source file. The recorded fallback is that pilot
  wraps the shipped devflow run as a black box — continuations around it —
  rather than forking stage definitions. Because routed choices are hard
  cutovers, every stage records its evidence before its checkpoint. The exact
  devflow reuse seam is Q1.
- **PROP-Pilot-001.S2 (checkpoint ownership):** Mapped onto the pinned devflow
  graph. Brief capture is a `:self` step (`:capture-brief`), not a checkpoint;
  the human-only checkpoints are the intake worktree checkpoint
  (`:create-or-confirm-worktree`), the proposal sign-off
  (`:human-signoff-proposal`, the RFC/scope acceptance point), and every abort —
  these stay `:kind :human` with `workflow/hitl`. devflow already ships
  `:discuss-scope` and `:route-after-plan` as agent checkpoints, so seats decide
  them as shipped. The spec-plan, task-breakdown, and AFK-acceptance sign-offs
  (`:human-signoff-spec-plan`, `:human-signoff-tasks`, `:human-acceptance-afk`)
  are the conversion targets: under pilot they become agent checkpoints decided
  by seats, gaining an `escalate` choice (required reason, routes to a
  chime-notified waiting state) and never an `abort` choice — aborting a feature
  stays human-only. Whether pilot can set that checkpoint kind while composing
  the devflow stages, or must leave those sign-offs human under the black-box
  fallback (S1), is the Q1 seam decided in the first slice. Accepting this
  conversion is itself part of what the human accepts at proposal sign-off.
- **PROP-Pilot-001.S3 (ephemeral seats and lease):** The system spawns one
  leased seat per attention state on a run with no live seat. A seat serves one
  attention state: it verifies its lease, reads the run view and latest notes,
  applies recorded policy, writes a decision note (decision, reason, HEAD sha,
  run ids, evidence, next expected state), clears the lease, and exits. Seats
  that cannot reconstruct enough context to decide escalate rather than guess.
  Seat spawns per attention state are bounded; exhaustion escalates.
- **PROP-Pilot-001.S4 (dispatcher):** A cron job over pilot run roots plus
  RFC-009 scheduler wakes for known future events (CI settle, hang budgets, lock
  release retry) sweeps for attention states — ready agent checkpoint, ready
  `:self` step, stalled gate, hang-budget breach, stage done — read through the
  same lens `workflow/await!` uses, and spawns exactly one seat per unserved
  state. The classifier is the human-authority boundary (RFC-021.REC4.INV):
  because `checkpoint-kind` is provenance-only and unenforced (TEN-002), the
  classifier NEVER emits a seat-spawn state for a `workflow/hitl`
  (`:kind :human`) checkpoint and NEVER targets `break-lock` — both route to
  chime as human attention only. That exclusion is the load-bearing safety
  property and carries a dedicated test.
- **PROP-Pilot-001.S5 (review cycle as routing):** After AFK completion the run
  pours a review stage at an explicit `<base>..HEAD` range. A clean synthesis
  advances toward land; findings route a bounded revise loop (default 3 rounds)
  where the seat sizes fix tasks per the delegation policy, treadle executes
  them, and re-review runs against the new HEAD. Exhausted rounds escalate with
  the full round history.
- **PROP-Pilot-001.S6 (merge train):** CI waits stay `:self` steps and the land
  family is untouched. A cron poller over `gh pr checks` / `gh run list` acts
  through the land op: once checks are green at the step's sha it runs
  `land complete <feature> step=ci-green` as an unattended seat (`:by
  "pilot-ci-poller"`, HEAD sha and check evidence in the note), exactly as a
  human coordinator drives that wait — closing the P1.1 driver gap for pilot
  runs with no `:self`→`:ci` gate conversion. Auto-signoff applies
  machine-checkable criteria, all at the same HEAD sha — branch pushed, CI green,
  roster synthesis clean, rounds within bound, no lock anomaly — with no partial
  credit; anything short escalates. Admission is FIFO on the merge lock: a
  blocked run parks `queued`, and lock release schedules an admission attempt
  with a periodic sweep as backstop. Duplicate or malformed locks escalate
  loudly and are never auto-broken; `break-lock` stays human. Auto-land is an
  explicit per-run grant recorded at scope acceptance, default off in v1.
- **PROP-Pilot-001.S7 (anomaly recovery):** Each of the four anomaly classes
  gets a bounded path (RFC-021.P6): lease verification plus a reason-gated
  recovery op for stale squat; per-harness hang budgets with one automatic
  kill-and-retry (PID-only) then escalate for hung workers; structured handover
  notes plus notes-in-prompt for cold resume; and HEAD-pinned review evidence,
  where a synthesis at any older sha is non-evidence for sign-off.
- **PROP-Pilot-001.S8 (escalation as attention):** Escalation endpoints are
  chime rules kept strictly as attention — HITL checkpoint ready, review rounds
  exhausted, seat failure or exhaustion, hang-budget breach, lock anomaly, train
  parked beyond budget, and seat `escalate` choices. Every escalation also writes
  a durable note on the run, so the record survives a missed notification.

## PROP-Pilot-001.P5 Acceptance criteria

- **PROP-Pilot-001.AC1:** One real, small feature runs intake through land on
  this repo under pilot with human touches only at brief capture, proposal/scope
  sign-off, and genuine escalations — single digits against the
  order-of-magnitude baseline of dozens of coordinator touches the 2026-07-08
  session recorded across cards w8rw0, r0x9l, o7r6j, and n7aya (handover and
  synthesis notes on those cards), counted from the run record.
- **PROP-Pilot-001.AC2:** With auto-land granted, two pilot runs queue on the
  sentinel and the second lands with no human merge command after the first
  releases the lock.
- **PROP-Pilot-001.AC3:** Each of the four anomaly classes is induced in a
  disposable workspace and either recovers within its declared bound or
  escalates loudly with a durable note; nothing stalls silently longer than one
  dispatcher period plus its budget.
- **PROP-Pilot-001.AC4:** Provenance audit — every checkpoint close in the pilot
  run carries `:by`, and no `workflow/hitl` checkpoint was closed by a seat.
- **PROP-Pilot-001.AC5:** Evidence audit — every automatic transition note names
  the HEAD sha and run ids, and a cold agent given only the run record states the
  feature's current state correctly.
- **PROP-Pilot-001.AC6:** Escalation drill — exhausting a review-round bound and
  a hang budget each produce a chime notification and a durable escalation note,
  with `chime/failures` empty.

## PROP-Pilot-001.P6 Placement: no spec deltas in v1

Per RFC-021.P7, all of v1 lands as pure `.skein` policy and repo-local spool
layer: the pilot workflow family and checkpoint-ownership map, the dispatcher
cron job and scheduler wakes, the seat lease attributes and prompt template and
harness routing, the auto-signoff criteria and review/hang budgets and train
queue attributes and admission job, the CI-gate poller and chime escalation
rules, and the lease-clearing recovery op. None of this touches a shipped
contract, so **this feature ships no `devflow/feat/pilot-spool/specs/*.delta.md`
files.** The macros feature is the precedent: a whole feature can land in `.skein`
policy with no root-spec change.

The promotion candidates RFC-021.P7 records — the pilot family plus dispatcher as
an O9 treadle-sibling spool, a documented merge-sentinel shape, and a treadle
hang-policy hook — are explicitly deferred until a real feature has landed
through pilot (Q6). The engine gaps RFC-021.P7 lists (a workflow event stream, a
lease/claim primitive, an attention-frontier query) are spec-delta candidates for
a later feature only if the attribute-and-config approach proves insufficient in
practice; they are not part of this feature.

## PROP-Pilot-001.P7 Risks

- **PROP-Pilot-001.R1 (routed-cutover fragility):** A routed choice is a hard
  cutover that closes the stage's remaining steps, so a stage whose evidence is
  not written before its checkpoint loses it. Mitigation is the S1 rule —
  evidence before every checkpoint — but it constrains stage composition and is
  the main reason Q1 must resolve before the plan hardens.
- **PROP-Pilot-001.R2 (dispatcher latency):** Every decision waits up to one
  dispatcher period plus any budget. Acceptable for feature-scale work, but it
  means pilot is never lower-latency than a resident coordinator; the RFC-009
  scheduler wakes for known future events keep the common case tight.
- **PROP-Pilot-001.R3 (policy too strict):** Auto-signoff has no partial credit,
  so criteria that are too conservative push every run to escalation and erode
  the hands-off win. The v1 auto-land default is off precisely so the criteria
  can be tuned against real runs before anything lands unattended.
- **PROP-Pilot-001.R4 (attribute-lease races):** Seat leases are attributes, not
  a core claim primitive (NG2), so two processes can in principle race a stamp.
  Seat-start lease verification and dispatcher re-stamp-before-respawn bound the
  blast radius; a first-class lease is the recorded promotion candidate if races
  show up.
- **PROP-Pilot-001.R5 (repo-local reach):** The dispatcher and family are
  `.skein` policy other workspaces cannot reuse until promoted. This is the
  intended sequencing (land one feature first), but it means the v1 payoff is
  scoped to this repo.
- **PROP-Pilot-001.R6 (event-stream compensation):** No documented contract
  emits "run completed" / "lock released" events, so the dispatcher compensates
  with sweeps and scheduler wakes. Correct but chattier than an event-driven
  design; the workflow event stream is the recorded engine-gap candidate.

## PROP-Pilot-001.P8 Open questions

RFC-021's open questions carry forward as non-blocking for this proposal. Q1 is
the one to watch: it decides at spec-delta / plan time and, if it forces forking
stage definitions, is a stop-and-escalate rather than a silent fork.

- **PROP-Pilot-001.Q1 (devflow reuse seam, RFC-021.Q1):** Validated against the
  pinned spool's actual surface early in the first slice. Does
  `skein.spools.devflow` (read from the synced spool checkout, not a repo-local
  source file) expose enough stage composition for pilot to string its stages
  and set their checkpoint kinds, or must pilot wrap devflow's shipped run as a
  black box with continuations around it? Wrapping is the recorded fallback;
  forking stage definitions is never done and is the worst outcome. If wrapping
  proves blocking too, stop and note rather than fork.
- **PROP-Pilot-001.Q2 (seat batching, RFC-021.Q2):** One attention state per seat
  is the v1 rule; a drain pass would cut spawn churn on chatty runs but weakens
  the one-decision audit grain.
- **PROP-Pilot-001.Q3 (hang budgets, RFC-021.Q3):** Measured from spawn or from
  last observed activity, and the right per-harness defaults — needs observation.
- **PROP-Pilot-001.Q4 (training-wheel checkpoints, RFC-021.Q4):** Whether
  task-breakdown acceptance should stay human for the first pilot run even though
  the design converts it; a launch parameter could defer the decision per feature.
- **PROP-Pilot-001.Q5 (train ordering, RFC-021.Q5):** Ordering beyond FIFO and
  who owns a cross-feature merge-verify conflict between queued green branches — a
  fix round on the losing run, or escalation.
- **PROP-Pilot-001.Q6 (promotion trigger, RFC-021.Q6):** What evidence — how many
  landed features, which anomaly drills — justifies promoting the family and
  dispatcher from `.skein` policy to a reference spool.
