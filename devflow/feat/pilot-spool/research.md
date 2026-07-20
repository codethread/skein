# Pilot-spool research digest

Purpose: distill the current coordination machinery for the pilot-spool design. This is input, not a proposal.

## Orientation (read first)

New to this feature? Start here, then read this digest, then the RFC.

Where things live:

- `devflow/rfcs/2026-07-08-pilot-spool.md` (RFC-021) — the design decision and its options.
- `devflow/feat/pilot-spool/proposal.md`, `pilot-spool.plan.md`, `tasks/` — the scoped proposal, the phases, and the 11 build slices.
- This digest — the systems the design sits on, explained one at a time.
- Build target: a new `.skein/pilot.clj` concern file plus small edits to sibling `.skein` files. No shipped-contract changes.

The five systems pilot composes (all shipped, no engine changes needed):

- The workflow engine — runs a feature as a strand graph of steps, gates, and checkpoints.
- Treadle and shuttle — spawn agent runs to fulfil `:subagent` gates.
- Chime — watches graph mutations and notifies a human; it never mutates.
- Cron — timer jobs that may call trusted spool APIs.
- The land family — the coordinator-only workflow that merges a branch to main under a lock.

Key terms:

- **molecule** — one workflow stage poured as its own cluster of strands under the run.
- **step** — work the current driver owns; waiter `:self` means the driver must act. **gate** — work an external actor owns; the waiter
  (`:ci`, `:subagent`, `:human`) names who. A gate with no executor surfaces as attention.
- **checkpoint** — a step decided with `choose!`; `workflow/hitl` (`:kind :human`) marks a human-only one. A **routed choice** is a hard cutover:
  it closes the checkpoint and pours the next stage in one transaction, so evidence must be recorded before the checkpoint.
- **seat** — an ephemeral coordinator run spawned to decide one attention point, then exit. **lease** — the `pilot/seat` stamp that stops two seats driving one run.
- **train** — the queue of signed-off pilot runs waiting on the single merge lock to land one at a time.
- **dispatcher** — the cron sweep that classifies attention states and spawns one seat each; it is also the human-authority boundary (RFC-021.REC4.INV).

## Sources read

- `spools/workflow.md`: workflow engine contract.
- `spools/shuttle/treadle.md`: shuttle-backed fulfillment for `:subagent` gates.
- `strand agent about`: delegation, review, council, task-sizing, and recovery surface.
- `spools/chime.cookbook.md`, `spools/chime.api.md`, `.skein/attention.clj`: attention rules and notifications.
- `spools/cron.cookbook.md`, `.skein/nvd_scan.clj`: scheduled workspace jobs.
- `strand kanban prime`, `spools/kanban.md`: board lanes and review integration.
- `.skein/workflows.clj`, `strand land about`: landing workflow and merge-lock lifecycle.
- External pinned `devflow.md`: feature lifecycle, stages, HITL checkpoints, and AFK task gates.
- Kanban cards and notes: `w8rw0`, `r0x9l`, `o7r6j`, `n7aya`.

## Workflow engine facts that shape orchestration

- A workflow run is a normal strand graph. The engine adds no hidden scheduler or persistence layer.
- The stable run handle is `workflow/run-id`; devflow uses the feature name as that handle.
- Ready work is pulled with `next-steps`, `next-gates`, and `next-checkpoint`.
- Mutations return `{:ready [...] :done boolean}` so a caller can distinguish done from stalled.
- `step` means the driving agent owns the work. It must use waiter `:self` or fail.
- `gate` means an external actor owns the work. The waiter is a freeform hint such as `:ci`, `:human`, `:subagent`, or `:shell`.
- Gates carry `workflow/gate`. Closing a gate requires `:by`, so provenance is explicit.
- A gate with no registered executor surfaces to the coordinator as attention.
- A gate with a registered executor stays quiet while the executor reports healthy.
- A registered executor may report stall detail; `await!` then returns `:stalled`.
- Checkpoints are workflow steps with `workflow/role "checkpoint"`. They must be decided with `choose!` or `advance!` with `:choice`.
- Checkpoint choices may declare required input. Missing input fails before mutation.
- A choice with `:next` routes to a continuation workflow.
- A routed choice is a hard cutover: it closes the checkpoint, force-closes the remaining active strands in the current molecule, and pours the next stage in one transaction.
- A choice with `:revise` re-pours the current stage under the same run id with override params.
- There is no pause/resume fork for abandoned steps after a routed checkpoint. The design must place all required prerequisites before the checkpoint.
- Named routes are resolved through the weaver-lifetime registry at decision time. This is the reload-safe route form.
- Raw symbol routes are durable only while the named var remains resolvable.
- A run is done only when every `step`, `checkpoint`, and `procedure` strand in the run subgraph is closed.
- "Nothing ready" is not the same as done. A run can be hidden behind dependencies or active gates.
- Dynamic fan-out is already possible by adding ordinary strands under the run root with `workflow/role "step"` and graph edges.
- The engine records history and can archive finished runs, but it does not replay agent work.

## Treadle and shuttle facts

- Treadle is a bridge for ready workflow gates whose waiter is `subagent`.
- It watches ready gates, spawns shuttle runs, and closes the gate when the shuttle run finishes with a non-blank successful result.
- Gate request attributes are `workflow/gate=subagent`, `shuttle/harness`, optional `shuttle/prompt`, optional `shuttle/cwd`, and optional `shuttle/max-attempts`.
- Treadle stamps the gate with `treadle/run` and the run with `treadle/gate` / `treadle/run-id`.
- It links gate and run with a `delegates` edge, not `parent-of`, so the run does not appear inside the workflow ready frontier.
- A failed, exhausted, or superseded delegated run stalls the gate until a coordinator clears `treadle/run` or `treadle/error`.
- `agent retry` is the wrong recovery verb for treadle gates. It creates the wrong structural relation and treadle will not adopt the run.
- Treadle has no wall-clock hang policy. A healthy long-running run stays quiet unless shuttle marks it terminal or a coordinator notices through other means.
- A run whose gate was routed away is stamped delivered as `gate-closed`; the result stays on the run.
- A finished run whose gate is active but no longer ready is marked `treadle/delivery-blocked` and retried when the gate becomes ready.

## Agent spool facts

- `agent delegate` is for task strands. It injects the worker contract, builds the prompt from the current task, and spawns a serving shuttle run.
- `agent spawn` is for raw helper runs. Helpers are non-serving and do not block later delegation of the task.
- `agent delegate --ready <plan>` fans out every ready, non-HITL task that has a harness and no active or finished serving run.
- Run success does not close the task. A coordinator must verify the result and close the task to unblock dependents.
- Failed runs stay active and visible until retry or kill.
- Recovery is task-first: edit the task contract if it was wrong, then retry the task or specific failed run.
- The worker contract requires reading the assigned strand and notes, writing progress, marking `status=implemented` only after validation, and never closing the assigned strand.
- The policy favors small tasks that finish inside one worker context window.
- Sibling tasks should own disjoint file scopes; split large files by section and sequence them if needed.
- Review is a non-serving helper fan-out. Roster review is declared in trusted config and synthesized.
- Council is a panel preset. It creates a shared blackboard, turn-as-run seats, and a synthesizer.
- There is no generic panel CLI verb; rich panel shapes live in trusted Clojure.

## Chime facts

- Chime watches graph mutations and evaluates registered rules. It ships no rules and no notifier.
- Workspace config registers rules with `chime/defrule!`; a developer binds their own notifier with `chime/set-notifier!`.
- A rule receives context including `:event`, `:strand`, and `:ready-ids`.
- `:ready-ids` is computed once per scan, so rules can notice a strand that became ready because a different strand changed.
- A rule returns nil for no notification or `{:title ... :body ...}` to notify.
- Chime deduplicates per `[rule strand]` while the rule keeps matching, then resets when the rule stops matching.
- Missing notifier and notifier failures are recorded in `chime/failures`; alerts are not silently dropped.
- `.skein/attention.clj` currently notifies on HITL checkpoints ready, shuttle run failure/exhaustion, treadle errors, kanban started/done,
  kanban blocked by failed delegated work, and parked pending runs.
- The parked-run detector is a local policy over ready shuttle runs that are pending, not in-flight, and older than five minutes.
- Chime rules call notification only. They do not spawn runs in the shipped contract.
- A rule function could call arbitrary trusted Clojure because config is trusted, but the chime contract, docs, and current rules shape it as an attention surface, not an executor.

## Cron facts

- Cron owns timing only. A job owns the work.
- Jobs are registered from trusted config with an id, interval, jitter, and a `:run!` symbol resolving to `(fn [runtime] ...)`.
- `:initial-delay-fn` can seed the first fire from external state.
- Re-registering a job id replaces the prior schedule.
- Throws are recorded in cron failures and do not stop the cadence.
- `.skein/nvd_scan.clj` shows the available seam: a cron tick can run shell commands, call GitHub, and write kanban cards through the runtime.
- The NVD job uses GitHub issues as a best-effort external lock and raises a p1 kanban card on findings.
- Cron can call existing spool APIs from trusted Clojure, including kanban. It can also call new pilot APIs if those exist.

## Kanban and land facts

- Kanban lanes are `refinement`, `pending`, `claimed`, `in_review`, and closed outcomes.
- `in_review` is a first-class lane. Rework moves it back to `claimed`; finish closes it.
- The kanban card is the work root. Devflow runs, task DAGs, reviews, and coordination strands hang below it.
- `board` includes a cross-card `needs-review` frontier across claimed and in-review cards.
- Claims stamp `owner`, `branch`, and optional `worktree` on the card root.
- Handovers are immutable note strands. Cold resume expects `kanban board`, then `kanban card <id>`, then latest handover.
- The land workflow is coordinator-only. Workers stop at implemented plus committed.
- Land sequence: push/draft PR, branch CI green, roster sign-off review, agent sign-off checkpoint, local squash merge under lock,
  local verification, push main and watch CI, cleanup.
- Card-backed land moves the card to `in_review` when sign-off review starts and back to `claimed` on abort or rollback.
- Merge lock is a singleton active strand with `kind=merge-lock`, `owner` as the land root id, and `land/run-id` as the feature.
- The lock is acquired after sign-off approval and immediately before local merge verification.
- `land status` reports the lock. `land break-lock <reason>` closes a stale lock with a recorded reason.
- `break-lock` is an explicit human/operator escape hatch, not an automatic timeout.

## Devflow lifecycle facts

- Devflow is a higher-level workflow spool, not a separate engine.
- Stage roots are molecules routed under one feature/run-id.
- Stage graph: intake, proposal, spec-plan, route-after-plan, task-breakdown, run-afk-loop or direct-implementation, abort.
- Intake starts with a HITL worktree checkpoint, then captures the brief, then an agent scope checkpoint routes to proposal or revises intake for more brief.
- Proposal, spec-plan, task-breakdown, direct implementation, and AFK acceptance all have HITL sign-off/acceptance checkpoints.
- Every HITL checkpoint can abort with a required reason, routing to the abort stage.
- Agent checkpoints do not offer abort in the shipped devflow lifecycle.
- Revision loops use `:revise`, not custom wrapper stages.
- `run-afk-loop` can pour sequential `:subagent` gates from approved task data.
- Task ids become step ids, so they must be token-safe before pouring.
- Devflow carries artifacts and action refs on step views; drivers should follow the step's own instruction text.

## Extension seams available without engine changes

- A pilot feature can be another workflow-family in `.skein/workflows.clj` or an external spool loaded from `.skein/spools.edn`.
- It can use ordinary workflow stages, gates, checkpoints, routed continuations, revision loops, and required choice input.
- It can use `workflow/await!` to block an ephemeral coordinator until `:checkpoint`, `:step`, `:gate`, `:stalled`, or `:done`.
- It can rely on treadle for AFK task execution through `:subagent` gates.
- It can add dynamic task gates mid-run if the approved plan discovers tasks at runtime.
- It can use chime to notify a human at bounded escalation endpoints: HITL checkpoint ready, treadle error, failed run, parked run,
  duplicate lock, or pilot-specific attention state.
- It can use cron to poll external state or periodically attempt safe transitions, because cron jobs may call trusted spool APIs.
- It can raise kanban cards or notes as durable attention records.
- It can use land's merge-lock pattern as the merge sentinel for a single local merge train lane.
- It can use existing review rosters for sign-off and the existing land workflow for final landing, if pilot treats land as a sub-process rather than reimplementing it.
- It can represent break-lock as a trusted op that closes a sentinel strand with a required reason.
- It can represent RFC/scope acceptance or abort as existing human checkpoints with required choice input.
- It can keep brief capture as a normal devflow artifact step.
- It can launch helper/coordinator runs through shuttle from trusted code: the agent spool exposes spawn/delegate surfaces and shuttle is the underlying engine.

## Precise gaps and spec-delta candidates

- No first-class "spawn an ephemeral coordinator when attention is needed" contract exists in `spools/workflow.md`. `await!` reports attention; it does not launch a driver.
- No workflow executor exists for `:self` steps. A self step means the current driver acts, which is the opposite of an absent persistent coordinator.
- Chime has no contract-level spawn action. `spools/chime.cookbook.md` and `chime.api.md` describe notification, not mutation or agent launch.
- Cron can call trusted APIs, but it has no event-specific trigger. `spools/cron.cookbook.md` gives timer jobs, not run-frontier hooks.
- Treadle fulfills only `workflow/gate "subagent"`. It does not fulfill coordinator checkpoints or self steps.
- Treadle recovery is manual clear-and-respawn. There is no policy hook for hang age, lease expiry, or automatic replacement.
- Workflow has no lease/claim primitive for a coordinator. Any run-id squat or stale in-flight coordinator must be expressed in userland attributes.
- Workflow events are not documented at the contract level as a stable stream for "run completed", "lock released", or "synthesis posted".
  Chime receives events, but its public rule context is strand-focused.
- Land's merge lock is repo policy in `.skein/workflows.clj`, not a reusable spool contract. A pilot merge train would need a documented sentinel shape or a new spool surface.
- `land status` exposes the lock but not a queue. Existing land serializes local main mutation; it does not define automated green-PR train admission, ordering, or release events.
- Review synthesis is recorded as notes through agents review. There is no stable "synthesis posted" event beyond note creation and helper run closure.
- Kanban has lanes and review frontier, but no pilot-specific lane for "automated train waiting" or "pilot parked". That may be attributes rather than lane changes.
- Devflow's agent checkpoints can be decided by a coordinator, but human-only points must remain `:kind :human` with `workflow/hitl`.
  Pilot must not downgrade them to agent checkpoints.
- The engine's routing hard-cutover means any pilot stage with cleanup or evidence that must survive must put it before a checkpoint or route to a continuation that records it.

## Observed coordination costs from this session

- The session paid dozens of coordinator touches across four nearby cards — w8rw0, r0x9l, o7r6j, and n7aya, whose handover and synthesis notes are
  the durable evidence: repeated review rounds, manual verification, failure triage, notes, retry/kill decisions, handovers, and final evidence refreshes.
- `w8rw0` was a mostly clean readability pass, but review still fanned out six notes plus synthesis.
  One issue survived review: malformed `|-margin` input could silently drop prose.
- Resolving `w8rw0` required human/coordinator judgment on whether that fail-loud gap blocked the card and how to patch or defer it.
- `r0x9l` showed review churn cost. Multiple review rounds tracked the same themes: `in_review` docs drift, merge-lock shape,
  redundant `land lock`, rollback safety, and test coverage.
- Resolving `r0x9l` required several patch/re-review cycles, not one pass.
  The coordinator had to distinguish live findings from stale carryover after each commit.
- `o7r6j` showed a dashboard follow-on. The first implementation generalized a banner abstraction too early, masked duplicate merge locks,
  and let banner fetch failures blank the board.
- Resolving `o7r6j` required a targeted fix: move the banner local, isolate failure, surface duplicate/malformed lock states, and record validation evidence.
- `n7aya` showed serial mutator coordination. Tasks 003 through 009 were verified and closed one at a time with full gate evidence after each batch.
- The later review found a high reload-state defect in global macro registries and a partially deferred live-weaver acceptance gate.
- Resolving `n7aya` needed a cold understanding of predecessor notes because the launch prompt was not enough; notes carried task order, gates run, and deferred evidence.

## Four anomaly classes

- Stale run-id squat: a prior serving run or stale `treadle/run` can occupy the slot for work even after it is no longer the right actor.
  Existing recovery is explicit: inspect status/logs, clear the treadle stamp for gates or retry/supersede the failed run for normal delegation,
  then respawn.
- Hung worker killed by PID: workers may hang or lose their session. The contract forbids pattern kills because prompts can contain the same
  command string and kill sibling agents. Resolution needs identifying the exact process or session, killing by PID through `agent kill` or OS PID,
  then recording the reason and retry path.
- Partial handover plus cold resume: a new coordinator often has only card state, notes, and commits. Resolution depends on durable notes that say
  what changed, validation, gotchas, and what is next. Without that, the successor repeats searches or trusts stale prompt text.
- Review-evidence staleness: after fix commits, earlier review findings may no longer apply, but their notes stay visible. Resolution requires a fresh
  commit-range review or explicit re-verification note naming the current HEAD and which prior findings are fixed or still live.

## Design cautions for pilot

- Treat pilot automation as a userland orchestrator over workflow, shuttle, chime, cron, kanban, and land unless a named gap truly needs engine work.
- Keep human-only points as HITL checkpoints and notify, do not auto-choose them.
- Make every automatic transition leave durable notes with the current HEAD, run ids, and evidence; otherwise review-evidence staleness gets worse.
- Prefer required input on aborts, break-lock, and scope decisions. Silent default reasons will make later recovery ambiguous.
- If a merge train is built on the merge sentinel, define queue state and duplicate-lock handling. The current singleton lock only serializes mutation; it is not a train.
- Add explicit stale-run and stale-coordinator recovery verbs or attributes. The existing low-level recovery paths are precise but easy to misuse.
