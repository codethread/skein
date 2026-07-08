# Pilot: hands-off feature runs

**Document ID:** `RFC-021`
**Status:** Accepted
**Date:** 2026-07-08
**Accepted:** 2026-07-08, coordinator note q44ji under user-delegated authority
**Related:** [Research digest](../feat/pilot-spool/research.md), [Workflow spool](../../spools/workflow.md), [Devflow spool](../../spools/devflow.md),
[Treadle](../../spools/shuttle/treadle.md), [Chime cookbook](../../spools/chime.cookbook.md), [Cron cookbook](../../spools/cron.cookbook.md),
[Weaver scheduler (RFC-009)](./2026-06-29-weaver-scheduler.md), [Shuttle-backed coordination (RFC-010)](./2026-07-02-shuttle-backed-coordination.md),
[Land family](../../.skein/workflows.clj), [Tenets](../TENETS.md)

## RFC-021.P1 Problem

Devflow, shuttle, and treadle already automate the work inside a feature:
stages pour as strand molecules, treadle fulfills `:subagent` gates, review
fans out through declared rosters. What is not automated is the space between.
A resident coordinator session still watches every run, and the
2026-07-08 session recorded dozens of coordinator touches across four nearby
cards — w8rw0, r0x9l, o7r6j, and n7aya, whose handover and synthesis notes are
the durable evidence: review-round driving, result verification, failure
triage, retry/kill decisions, handover notes, and final evidence refreshes.

Almost every touch was one bounded decision over durable state, and the same
session proved cold resume twice: a fresh coordinator holding only the card,
its notes, and the commits made the correct next decision without the
predecessor's context. The resident session pays for continuous attention
when the graph only needs a decision-maker at decision points.

Three structural facts shape the solution space:

- **RFC-021.P1.1:** `:self` steps and checkpoints progress only while a driver
  is live. In today's land family even "watch CI to green" (`:ci-green`) is a
  `:self` step, so an unattended run stalls at a wait a machine could complete.
  The gap is the missing driver, not the step shape: a seat that runs
  `land complete <feature> step=ci-green` once CI is green drives that wait
  exactly as a human coordinator does, leaving the land family unchanged. (The
  land cleanup step already kanban-finishes the card for card-backed runs, so
  that is not a gap — the only gap is the absent driver.)
- **RFC-021.P1.2:** No contract spawns a driver on attention. `workflow/await!`
  reports `:checkpoint`, `:stalled`, `:gate`, or `:done` to a caller that must
  already exist; chime notifies humans and mutates nothing; treadle fulfills
  only `workflow/gate "subagent"`. The "who shows up when a run needs a
  decision" seam is missing.
- **RFC-021.P1.3:** The merge lock serializes local main mutation but defines
  no train. There is no queue, no admission on lock release, and `break-lock`
  is rightly a human-only escape hatch.

The same session surfaced four recurring anomaly classes that any hands-off
design must handle rather than hide: stale run-id squat, hung workers that
must be killed by PID, cold resume from partial handovers, and review
evidence that goes stale after fix commits. RFC-021.P6 handles each.

Some points must stay human no matter how far automation goes: brief capture,
RFC/scope acceptance or abort, `break-lock`, and the endpoint of every bounded
escalation.

## RFC-021.P2 Goals

- **RFC-021.G1:** Launch a whole feature once and have it run intake through
  land with human input only at the declared human-only points and at loud
  escalations.
- **RFC-021.G2:** Coordinate through ephemeral seats: the system spawns a
  coordinator run only when a run needs a decision; the seat reads durable
  state, decides that point per recorded policy, writes its evidence, and
  exits.
- **RFC-021.G3:** Run a merge train on the merge sentinel: signed-off pilot
  runs with green CI land automatically, one at a time, and lock release
  admits the next queued run.
- **RFC-021.G4:** Leave a durable note on every automatic transition naming
  the HEAD sha, the run ids involved, and the evidence, so cold resume and
  later review keep working.
- **RFC-021.G5:** Keep TEN-003/TEN-004 discipline: every loop bounded, every
  exhausted bound escalating loudly to a human, and the new surface expressed
  as minimal `.skein` policy before it becomes anything larger.
- **RFC-021.G6:** Give each of the four observed anomaly classes an explicit,
  bounded recovery or escalation path.

## RFC-021.P3 Non-goals

- **RFC-021.NG1:** Do not auto-decide human checkpoints. `workflow/hitl`
  stays load-bearing; pilot never downgrades a human-only point to an agent
  checkpoint. The seat classifier enforces this — see the human-authority
  boundary invariant (RFC-021.REC4.INV).
- **RFC-021.NG2:** Do not add engine primitives in v1: no scheduler beyond
  RFC-009's, no core lease/claim feature, no contract-level event stream.
  Attributes and trusted config come first; P7 names the promotion candidates.
- **RFC-021.NG3:** Do not build replay or audit machinery. Resumability only,
  per PHILOSOPHY: notes exist for the next reader, and the code wins over the
  record.
- **RFC-021.NG4:** Do not design a multi-lane or cross-repo train. One local
  main, one lock, one queue.
- **RFC-021.NG5:** Do not retire hands-on coordination. Pilot is a per-feature
  launch choice; resident driving of devflow and land keeps working unchanged.
- **RFC-021.NG6:** Do not change the worker contract or expand worker
  autonomy. Workers still stop at implemented plus committed.

## RFC-021.P4 Options

The design decomposes into four decisions. Options are grouped per decision.

### Where the pilot state machine lives

- **RFC-021.O1:** Chime rules over the existing devflow and land runs: rules
  watch mutations and drive transitions. Reacts to real events with no new
  run shape, but chime's contract, docs, and shipped rules shape it as an
  attention surface that mutates nothing; rules are per-`[rule strand]`
  deduplicated edge triggers, a poor fit for stateful policy; and the
  feature's state would live nowhere readable.
- **RFC-021.O2:** A kanban-attribute state machine: a cron job scans card
  attributes and drives transitions. Cards are already the work root and
  cron can call trusted APIs, but this duplicates the workflow engine in
  attributes: no ready frontier, no checkpoints with required input, no
  `:by` provenance, no routed-cutover semantics, and two sources of truth
  for one feature's state.
- **RFC-021.O3:** A pilot workflow family: one run-id from intake to land,
  routed continuations stringing the existing devflow stages, a review
  cycle, and land. Reuses the engine's frontier, checkpoint provenance,
  choice input, revision loops, and history, and the `workflow/await!`
  states are exactly the attention states a dispatcher needs. The costs: it
  needs a dispatcher for the missing "who drives" seam (P1.2), and routed
  cutovers are hard, so evidence and cleanup must sit before checkpoints.

### Coordinator residency

- **RFC-021.O4:** Resident coordinator (status quo): a live session
  `await!`-loops over the run. Works today with the lowest decision latency,
  but pays continuous context and attention for idle waits, dies with the
  session, and the ~50-minute await ceiling requires repeated coordinator
  attention. This is the measured cost of dozens of touches (RFC-021.P1).
- **RFC-021.O5:** Ephemeral seats: a seat is spawned per decision point,
  decides it, and exits. Cold resume is proven; each seat is a tracked
  shuttle run (inspectable, retryable, visible to peers); cost is
  proportional to decisions, not elapsed time. In exchange it requires
  durable handover discipline at every transition, a lease so two seats
  never drive one run, and a dispatcher-period latency per decision.
- **RFC-021.O6:** Hybrid: resident through design, ephemeral after plan
  acceptance. The human is present during design anyway, but this creates
  two driving modes to reason about, and O5 already degrades to it: a human
  driving early stages is a seat that happens to be a person.

### Who spawns seats

- **RFC-021.O7:** Chime rule side effects spawn seats. Event-driven with no
  polling, but the same contract mismatch as O1: chime is notification-only
  by design, and hiding an executor inside attention rules makes failures
  hard to reason about.
- **RFC-021.O8:** A `.skein` dispatcher: a cron job sweeps pilot runs for
  attention states and spawns one leased seat per state, with RFC-009
  scheduler wakes for known future events (CI settle, hang budgets, lock
  release retry). Uses only shipped seams — cron, scheduler, shuttle spawn
  from trusted code — and keeps policy in one readable file, bounded and
  inspectable. The costs: one dispatcher period of polling latency, and the
  dispatcher is repo policy other workspaces cannot reuse until promoted.
- **RFC-021.O9:** A new reference-spool adapter (a treadle sibling) that
  watches ready checkpoints and self steps and spawns coordinator runs.
  First-class and reusable, but premature before the policy has landed one
  real feature; treadle itself shipped only after manual delegation proved
  the shape (RFC-010).

### Where auto-signoff and auto-merge policy live

- **RFC-021.O10:** Change the shared land family so sign-off auto-approves
  when checks pass. One landing code path, but it degrades hands-on landing
  for every non-pilot run; land is shared coordinator discipline and its
  explicit checkpoint is the point.
- **RFC-021.O11:** Put the policy in seat prompts and let the seat judge.
  Flexible, but sign-off must be mechanical and auditable; prompt-embedded
  judgment drifts, cannot be reviewed as data, and fails TEN-003 when it
  silently improvises.
- **RFC-021.O12:** Policy as `.skein` data: machine-checkable criteria the
  seat and pollers apply, with the grant recorded on the run root. Criteria
  are reviewable as config, the seat applies policy instead of inventing
  it, and conservative defaults are a data change. The cost is one more
  policy file to keep honest, and criteria that are too strict push
  everything to escalation.

## RFC-021.P5 Recommendation

- **RFC-021.REC1:** Choose **O3 + O5 + O8 + O12**: a pilot workflow family in
  `.skein` (a new `pilot.clj` concern file), driven by ephemeral seats that a
  cron-plus-scheduler dispatcher spawns, with sign-off and train policy as
  reviewable config data. Live in `.skein` policy first and promote to a
  reference spool only after one real feature lands through it, mirroring how
  treadle followed manual delegation.
- **RFC-021.REC2:** Run shape: one pilot run-id from intake to land. devflow is
  an external git-pinned spool (`codethread/devflow` in `.skein/spools.edn`,
  source synced by the weaver, not a repo-local file), so its composition
  surface is discovered from the pinned artifact. Reuse the devflow stage
  definitions through that surface where it exposes them, and route into the
  existing land family as a continuation rather than forking either; the exact
  reuse seam is Q1. The recorded fallback is that pilot wraps the shipped
  devflow run as a black box — continuations around it — and never forks its
  stage definitions. Because routed choices are hard cutovers, every stage must
  record its evidence before its checkpoint.
- **RFC-021.REC3:** Checkpoint ownership under pilot, mapped onto the pinned
  devflow graph (intake, proposal, spec-plan, route-after-plan, task-breakdown,
  and run-afk-loop or direct-implementation). Brief capture is a `:self` step
  (`:capture-brief`), not a checkpoint; the human-only checkpoints are the
  intake worktree checkpoint (`:create-or-confirm-worktree`), the proposal
  sign-off (`:human-signoff-proposal`, the RFC/scope acceptance point), and
  every abort — these stay `:kind :human` with `workflow/hitl`. devflow already
  ships `:discuss-scope` and `:route-after-plan` as agent checkpoints, so seats
  decide them as shipped. The spec-plan, task-breakdown, and AFK-acceptance
  sign-offs (`:human-signoff-spec-plan`, `:human-signoff-tasks`,
  `:human-acceptance-afk`) are the conversion targets: under pilot they become
  agent checkpoints decided by seats, gaining an `escalate` choice (required
  reason, routes to a chime-notified waiting state) and never an `abort` choice
  — aborting a feature stays human-only. Whether pilot can set that checkpoint
  kind while composing the devflow stages, or must leave those sign-offs human
  under the black-box fallback (RFC-021.REC2), is the Q1 seam decided in slice
  001. This conversion is itself part of what the human accepts at proposal
  sign-off.
- **RFC-021.REC4:** The dispatcher is a cron job over pilot run roots plus
  RFC-009 scheduler wakes for known future events. Attention states, read
  through the same lens `await!` uses: ready agent checkpoint, ready `:self`
  step, stalled gate, hang-budget breach, and stage done. For each attention
  state on a run with no live seat lease, it spawns exactly one seat.
- **RFC-021.REC4.INV (human-authority boundary):** The seat classifier is the
  human-authority boundary, and it is the only thing enforcing it.
  `workflow/checkpoint-kind` is unenforced provenance (TEN-002; `workflow.md`),
  so nothing in the engine stops a full-CLI seat from `choose!`-ing a
  `:kind :human`/`workflow/hitl` checkpoint or running `land break-lock`. The
  invariant is therefore policy-and-dispatcher-enforced, not engine-enforced:
  the classifier NEVER emits a seat-spawn attention state for a `workflow/hitl`
  (`:kind :human`) checkpoint and NEVER targets `break-lock` — both route to
  chime as human attention only. This exclusion is the load-bearing safety
  property of the whole hands-off design. It carries a dedicated test (the
  classifier, given a ready hitl checkpoint, must exclude it) and a provenance
  drill (no seat-closed hitl in the run's `:by` record) that complements
  RFC-021.AC4, and the seat prompt policy forbids closing hitl checkpoints and
  running `break-lock` outright.
- **RFC-021.REC5:** Seat contract and lease: the dispatcher stamps
  `pilot/seat = <run-id>` on the pilot run root before spawning, mirroring
  treadle's gate stamp. A seat serves one attention state: it verifies its
  lease, reads the run view and latest notes, applies recorded policy to
  decide that one point, writes a decision note (what it decided, why, HEAD
  sha, run ids, evidence), clears the lease, and exits. If the decision
  readies the next point, the next sweep spawns the next seat. Seats that
  cannot reconstruct enough context to decide escalate instead of guessing.
  Seat spawns per attention state are bounded by `max-attempts`; exhaustion
  escalates.
- **RFC-021.REC6:** Review cycle as routing: after AFK completion the run
  pours a review stage — roster review at an explicit
  `<base>..HEAD` commit range, then a seat reads the synthesis. Clean
  synthesis advances toward land. Findings route a revise loop: the seat
  sizes fix tasks per the delegation policy, treadle executes them, and
  re-review runs against the new HEAD. Rounds are bounded
  (`pilot/max-review-rounds`, default 3, informed by the multi-round churn
  observed on r0x9l); exhaustion escalates to a human with the full round
  history.
- **RFC-021.REC7:** Merge train on the merge sentinel:
  - CI waits stay `:self` steps and the land family is untouched. A cron poller
    over `gh pr checks` / `gh run list` acts through the land op: once checks
    are green at the step's sha it runs `land complete <feature> step=ci-green`
    as an unattended seat (`:by "pilot-ci-poller"`, HEAD sha and check evidence
    in the completion note), exactly as a human coordinator drives that wait.
    This closes the P1.1 driver gap for pilot runs while keeping the hands-on
    land family alone; there is no `:self`→`:ci` gate conversion.
  - Auto-signoff applies O12 criteria, all machine-checkable and all at the
    same HEAD sha: branch pushed, CI green at HEAD, roster synthesis clean at
    HEAD, review rounds within bound, and no lock anomaly. Anything short of
    all five escalates; there is no partial credit.
  - Train admission: after sign-off the run tries to acquire the merge lock.
    If held, the run parks with `pilot/train-state "queued"`; admission is
    FIFO by enqueue time. Lock release (the sentinel strand closing) schedules
    an admission attempt, with a periodic sweep as backstop since events are
    not a documented contract. Duplicate or malformed locks escalate loudly
    and are never auto-broken (the o7r6j lesson); `break-lock` stays human.
  - Auto-land is an explicit grant: the human enables it per run at scope
    acceptance and it is recorded as `pilot/auto-land "true"` on the run root.
    The v1 default is off — the train queues and escalates at sign-off until
    the P8 drills have passed on this repo.
- **RFC-021.REC8:** Escalation endpoints are chime rules, kept strictly as
  attention: HITL checkpoint ready, review rounds exhausted, seat failure or
  exhaustion, hang-budget breach, lock anomaly, train parked beyond budget,
  and escalate choices taken by seats. Every escalation also writes a durable
  note on the run, so the record survives a missed notification.

## RFC-021.P6 Anomaly classes

- **RFC-021.A1 Stale run-id squat.** A stale `treadle/run` or `pilot/seat`
  stamp can hold a slot after its run is no longer the right actor. Handling:
  seats verify their lease on start and exit without acting if superseded;
  the dispatcher treats a lease whose run is terminal as clearable and
  re-stamps before respawning; a `pilot` recovery op clears a named lease
  with a required reason, mirroring treadle's clear-and-respawn but wrapped
  so the precise low-level steps stop being folklore.
- **RFC-021.A2 Hung worker, PID-only kills.** Treadle has no wall-clock
  policy, so pilot adds one: per-harness hang budgets in policy data
  (`pilot/hang-budget-secs`). The dispatcher flags an in-flight run past
  budget as an attention state; a triage seat inspects `agent logs`, and
  either kills by PID through `agent kill` and retries within
  `max-attempts`, or extends the budget once with a recorded reason. One
  automatic kill-and-retry per attempt chain; after that, escalate. Pattern
  kills stay forbidden.
- **RFC-021.A3 Partial handover, cold resume.** Every seat decision note is
  structured handover: decision, reason, HEAD sha, run ids, evidence, next
  expected state. The dispatcher includes the latest notes in each seat
  prompt, so launch-prompt staleness cannot mislead a successor. A seat that
  finds notes and state insufficient to decide escalates with what it could
  and could not reconstruct, per TEN-003.
- **RFC-021.A4 Review-evidence staleness.** Findings are live only for the
  HEAD they name. Every review and re-review runs against an explicit commit
  range; the auto-signoff criterion "roster synthesis clean at HEAD" pins
  sign-off to the current sha; re-review notes must state which prior
  findings are fixed and which remain. A synthesis at any older sha is
  non-evidence for sign-off.

The human-authority boundary (RFC-021.REC4.INV) is not one of these recovery
classes but shares their discipline: because `checkpoint-kind` is provenance
only (TEN-002), the boundary is held by the classifier's exclusion of every
`workflow/hitl` checkpoint and `break-lock`, not by the engine. A seat closing
a hitl checkpoint is a boundary breach, not an anomaly to recover from;
RFC-021.AC4 audits the run's `:by` record to prove it never happened.

## RFC-021.P7 Placement: policy, spool, engine

Pure `.skein` policy (all of v1):

- The pilot workflow family, stage composition, and checkpoint-ownership map.
- The dispatcher cron job, scheduler wakes, seat lease attributes, seat
  prompt template, and the seat harness routing in `harnesses.clj`.
- Auto-signoff criteria, review-round and hang budgets, train queue
  attributes, admission job, and the auto-land grant.
- The CI-gate poller and chime escalation rules in `attention.clj`.
- The lease-clearing recovery op.

Reference-spool candidates, only after a real feature lands through pilot:

- The pilot family plus dispatcher as a spool (the O9 treadle sibling), with
  the seat contract documented the way treadle documents gate fulfillment.
- A documented merge-sentinel shape (lock plus queue) promoted out of
  `.skein/workflows.clj`, so a train is composable rather than repo folklore.
- A treadle hang-policy hook, if A2's budget attributes prove generally
  useful.

Engine gaps, deliberately not needed for v1 but recorded as spec-delta
candidates if userland attributes prove insufficient:

- A contract-level workflow event stream ("run completed", "lock released",
  "synthesis posted") for dispatchers; today chime receives events but its
  public rule context is strand-focused, and pilot compensates with sweeps.
- A lease/claim primitive, if attribute leases show races two processes can
  hit in practice.
- A first-class attention-frontier query, if the dispatcher's composed view
  becomes something several tools re-derive.

## RFC-021.P8 Acceptance criteria

- **RFC-021.AC1:** One real, small feature runs intake through land on this
  repo under pilot with human touches only at brief capture, proposal/scope
  sign-off, and genuine escalations. Touches are counted from the run record;
  single digits against the order-of-magnitude baseline of dozens of
  coordinator touches the 2026-07-08 session recorded across cards w8rw0,
  r0x9l, o7r6j, and n7aya (the handover and synthesis notes on those cards are
  the durable evidence).
- **RFC-021.AC2:** With auto-land granted, two pilot runs queue on the
  sentinel and the second lands with no human merge command after the first
  releases the lock.
- **RFC-021.AC3:** Each of the four anomaly classes is induced in a
  disposable workspace and either recovers within its declared bound or
  escalates loudly with a durable note; nothing stalls silently longer than
  one dispatcher period plus its budget.
- **RFC-021.AC4:** Provenance audit: every checkpoint close in the pilot run
  carries `:by`; no `workflow/hitl` checkpoint was closed by a seat.
- **RFC-021.AC5:** Evidence audit: every automatic transition note names the
  HEAD sha and run ids; a cold agent given only the run record states the
  feature's current state correctly.
- **RFC-021.AC6:** Escalation drill: exhausting a review-round bound and a
  hang budget each produce a chime notification and a durable escalation
  note, with `chime/failures` empty.

## RFC-021.P9 Open questions

- **RFC-021.Q1:** The devflow reuse seam, validated against the pinned spool's
  actual surface early in slice 001: does `skein.spools.devflow` (read from the
  synced spool checkout, not a repo-local source file) expose enough stage
  composition for pilot to string its stages and set their checkpoint kinds, or
  must pilot wrap devflow's shipped run as a black box with continuations around
  it? Wrapping is the recorded fallback; forking stage definitions is never done
  and is the worst outcome.
- **RFC-021.Q2:** Seat batching: one attention state per seat is the v1 rule;
  a drain pass (one seat decides all currently-ready points on its run)
  would cut spawn churn on chatty runs but weakens the one-decision audit
  grain.
- **RFC-021.Q3:** Hang budgets: measured from spawn or from last observed
  activity, and per-harness defaults — shuttle records enough to choose, but
  the right numbers need observation.
- **RFC-021.Q4:** Whether task-breakdown acceptance should stay human for the
  first pilot run as an extra training wheel, even though the design converts
  it; a launch parameter could defer the decision per feature.
- **RFC-021.Q5:** Train ordering beyond FIFO, and cross-feature conflicts:
  local merge-verify catches semantic collisions between queued green
  branches, but who owns the fix — a new fix round on the losing run, or
  escalation — needs a policy call.
- **RFC-021.Q6:** Promotion trigger: what evidence (how many landed features,
  which anomaly drills) justifies promoting the family and dispatcher from
  `.skein` policy to a reference spool.
