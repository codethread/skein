# Skein Devflow Spool

## 1. Overview

`skein.spools.devflow` is the reference higher-level spool built on
`skein.spools.workflow`. It encodes an opinionated feature-delivery lifecycle as
ordinary workflow definitions plus thin convenience wrappers keyed by **feature
name** — the feature name *is* the `workflow/run-id`, so there is no separate
run handle to track.

Each lifecycle stage is a plain workflow definition (`intake-workflow`,
`proposal-workflow`, …) that pours as its own molecule. Stages hand off to one
another through checkpoint `:next` routing (see workflow.md §5): choosing a
routed option closes the current stage's molecule and pours the next stage's
under the same feature/run-id. The spool owns no engine semantics of its own —
lifecycle, routing, revision loops, and `done?` all come from
`skein.spools.workflow`.

This document covers the stage graph and the devflow attribute conventions.
For the engine (`start!`/`complete!`/`choose!` mechanics, checkpoints, routing
transactions, gates, molecule ops, the `workflow/*` vocabulary), see
[workflow.md](./workflow.md).

## 2. Stage map

Stages, their checkpoints, and where each choice routes. A choice with no
target is **terminal** — it closes the checkpoint and the stage continues in the
same molecule. A choice with a target closes the current stage and pours the
target stage under the same feature.

```
start! ─▶ intake
             :create-or-confirm-worktree  (HITL)
                 created-worktree / already-in-worktree ─▶ (continue intake)
                 abort ─▶ abort
             :discuss-scope  (agent)
                 proposal-ready     ─▶ proposal
                 needs-more-brief   ─▶ intake (revision)
          proposal
             :human-signoff-proposal  (HITL)
                 approved ─▶ spec-plan
                 revise   ─▶ proposal (revision)
                 abort    ─▶ abort
          spec-plan
             :human-signoff-spec-plan  (HITL)
                 approved ─▶ route-after-plan
                 revise   ─▶ spec-plan (revision)
                 abort    ─▶ abort
          route-after-plan
             :route-after-plan  (agent)
                 task-breakdown         ─▶ task-breakdown
                 direct-implementation  ─▶ direct-implementation
          task-breakdown
             :human-signoff-tasks  (HITL)
                 approved ─▶ run-afk-loop
                 revise   ─▶ task-breakdown (revision)
                 abort    ─▶ abort
          run-afk-loop
             :run-afk-loop step ─▶ (run auto-closes: done)
          direct-implementation
             :human-acceptance  (HITL)
                 accepted ─▶ (run auto-closes: done)
                 revise   ─▶ direct-implementation (revision)
                 abort    ─▶ abort
          abort
             :record-abort step ─▶ (run auto-closes: done)
```

Notes:

- **Two terminal paths** reach a done run without routing: `run-afk-loop`
  (after the task queue is approved) and `direct-implementation` on `:accepted`.
  The route-after-plan checkpoint chooses between them.
- **`:abort` is reachable from every HITL (`:human`) checkpoint** — the intake
  worktree checkpoint and the four sign-off checkpoints. The two `:agent`
  checkpoints (`:discuss-scope`, `:route-after-plan`) offer no abort. Aborting
  routes to `abort-workflow`, whose `:record-abort` step then closes the run.
- `abort-workflow` declares a **required `:reason` param**, so an aborting choose
  must pass it as choice input: `(choose! feature :abort {:reason "…"})`. The
  feature comes from context; the reason comes from the input.

## 3. Revision loops

Every human sign-off `:revise` choice, and intake's `:discuss-scope`
`:needs-more-brief`, routes back through the stage's own
`<stage>-revision-workflow` wrapper (revise-by-routing, workflow.md §5). Each
wrapper returns `{:workflow w :params p}` with `:revision true`, so the revision
params win over any choice input and persist as the new round's
`workflow/context`.

`:revision true` condition-skips exactly two steps, via `:condition [:!= :revision true]`:

- **intake** skips `:create-or-confirm-worktree` — the worktree was already
  created/confirmed on the first pass, so the revision round is ready at
  `:capture-brief`.
- **proposal** skips `:inspect-context` — orientation was done on the first
  pass, so the revision round is ready at `:write-proposal`.

The `spec-plan`, `task-breakdown`, and `direct-implementation` stages carry a
`:revision` param too, but declare no condition on it, so their revision rounds
re-run the whole stage.

Start opts seeded into `workflow/context` by `start!` (see §4) survive every
revision loop rather than resetting to defaults, because each revision wrapper
carries `context` forward as its authoritative params.

`:revision` is stage-local: forward hand-offs (`:proposal-ready`, `:approved`,
route-after-plan's two choices) route through `enter-<stage>-workflow`
wrappers that dissoc `:revision` from the continuation params, so a round
approved after a revise never leaks `:revision true` into a downstream
stage's context. Other start opts pass through untouched.

## 4. Agent usage

The wrappers key everything by feature name and pass opts straight through to
the engine. The `next-steps`/`next-step` (and `choice-details`/`choice-detail`)
pairs return the same shapes as their `skein.spools.workflow` counterparts.

| Wrapper | Signature | Notes |
|---|---|---|
| `start!` | `(feature)` / `(feature opts)` | Pours `intake-workflow` under `family "devflow"`. Coerces keyword `opts` values to strings and seeds them (plus `:feature`) into `workflow/context` so they survive revision loops. Returns the initial ready step views (vector). |
| `next-steps` | `(feature)` | All ready step views for the feature. |
| `next-step` | `(feature)` | The single ready step view; throws if ambiguous. |
| `complete!` | `(feature)` / `(feature opts)` | Closes the current non-checkpoint step. `opts` (`:step`, `:notes`, `:attributes`, `:by`) pass through. Returns the next ready step views. |
| `choose!` | `(feature choice)` / `(feature choice input)` / `(feature choice input opts)` | Records the checkpoint choice and routes if the choice has a `:next`. Returns the next ready step views. |
| `choice-details` | `(feature)` / `(feature opts)` | Choice explanations for the current checkpoint. |
| `choice-detail` | `(feature choice)` / `(feature choice opts)` | One choice's explanation. |
| `feature-roots` | `(feature)` | The active root molecule for the feature as a vector (empty if none). |

There is no devflow `done?` wrapper — use `skein.spools.workflow/done?` with the
feature name.

Driving example with one revise round:

```clojure
(require '[skein.spools.devflow :as devflow])

;; feature name is the run-id; step-view's :id is the generated strand id,
;; a checkpoint's stable definition name arrives as the :checkpoint string
(devflow/start! "search-filters")
;; => [{:kind "checkpoint" :checkpoint "create-or-confirm-worktree"
;;      :choices ["created-worktree" "already-in-worktree" "abort"] ...}]

;; terminal choice — stays in the intake molecule and advances to capture-brief
(devflow/choose! "search-filters" :created-worktree {})
;; => [{:title "Capture user brief for search-filters" :artifact "brief" ...}]

(devflow/complete! "search-filters")
;; => [{:kind "checkpoint" :checkpoint "discuss-scope"
;;      :choices ["proposal-ready" "needs-more-brief"] ...}]

;; scope is clear — route to the proposal stage (fresh molecule, same feature)
(devflow/choose! "search-filters" :proposal-ready {})
;; => [{:action-ref "devflow.proposal.orient" ...}]

;; complete inspect-context, write-proposal, and the agent-review call steps
;; until the sign-off checkpoint is ready
;; ... => [{:kind "checkpoint" :checkpoint "human-signoff-proposal"
;;          :choices ["approved" "revise" "abort"] ...}]

;; revise: closes this proposal round and pours a fresh one; :inspect-context
;; is condition-skipped, so the round is ready at :write-proposal
(devflow/choose! "search-filters" :revise {})
;; => [{:artifact "proposal.md" :skills "devflow" ...}]

;; ... re-run write-proposal + review, reach human-signoff-proposal again ...

;; approve: route to the spec/plan stage
(devflow/choose! "search-filters" :approved {})
;; => [{:artifact "specs/*.delta.md" :skills "devflow" ...}]
```

## 5. Registries

Devflow exposes its constructors and commands as data (stringified symbols) for
trusted resolution:

- `(workflows)` returns `workflow-registry` — stage/revision constructors by
  stable key: `:intake`, `:intake-revision`, `:proposal`, `:proposal-revision`,
  `:spec-plan`, `:spec-plan-revision`, `:route-after-plan`, `:tasks`,
  `:tasks-revision`, `:run-afk-loop`, `:direct-implementation`,
  `:direct-implementation-revision`, `:agent-review`, and `:cycle`
  (`devflow-cycle`, the ordered composable stage list).
- `(commands)` returns `command-registry` — agent-facing commands by key:
  `:start`, `:next-step`, `:next-steps`, `:choice-details`, `:choice-detail`,
  `:choose`, `:complete`.
- `(install!)` returns `{:installed true :namespace 'skein.spools.devflow
  :commands command-registry :workflows workflow-registry}`.

## 6. Attribute conventions

Devflow reads and writes these attributes on strands, on top of the engine's
`workflow/*` vocabulary (workflow.md §7). Stage-level attributes sit on the root
molecule; the rest sit on individual step/checkpoint strands.

| Attribute | Meaning | Set on / by |
|---|---|---|
| `devflow/stage` | Lifecycle stage: `"intake"`, `"proposal"`, `"spec-plan"`, `"route-after-plan"`, `"tasks"`, `"afk"`, `"implementation"`, `"abort"`. | Root molecule, by each stage constructor. |
| `devflow/feature` | The feature name (same value as the run-id). | Root molecule, by each stage constructor. |
| `devflow/artifact` | Artifact a step produces (`"brief"`, `"proposal.md"`, `"specs/*.delta.md"`, `"<feature>.plan.md"`, `"tasks/index.yml"`). `step-view` surfaces it as `:artifact` (via the engine's `workflow/artifact` → `devflow/artifact` fallback). | Artifact-writing steps. |
| `workflow/hitl` | `"true"` marking a human-in-the-loop checkpoint. | Every `:human` checkpoint. |
| `workflow/decision-point` | Freeform label for what the checkpoint decides (`"worktree-ready"`, `"scope-ready"`, `"proposal-signed-off"`, `"choose-tasks-or-implementation"`, `"plan-signed-off"`, `"tasks-signed-off"`, `"implementation-accepted"`). | Each checkpoint. |
| `workflow/action-ref` | Pointer to the action/skill an agent should invoke (`"devflow.worktree.ensure"`, `"devflow.proposal.orient"`, `"devflow.tasks.run-afk-loop"`, `"devflow.implementation.direct"`, `"devflow.implementation.validate"`, `"devflow.abort.record"`). Surfaced by `step-view`. | Steps/checkpoints that hand off to a named action. |
| `workflow/instruction` | Freeform instruction text surfaced in `step-view`. | Steps/checkpoints needing explicit guidance. |
| `skills` | Skill/tool hint (`"devflow"`), surfaced in `step-view`. | The four `write-*` artifact steps (`:capture-brief` produces `"brief"` without it). |

The intake root additionally carries `devflow/worktree-check`
(`"required"` or `"already-in-worktree-ok"`), seeded from the `start!`
`:worktree-check` opt.

## 7. See also

- [workflow.md](./workflow.md) — the engine this spool is built on: run
  lifecycle, checkpoints and `:next` routing, revise-by-routing loops, gates,
  molecule ops, and the full `workflow/*` attribute vocabulary.
- `(skein.spools.workflow/explain topic)` — machine-readable builder contracts
  agents can call before constructing workflow data.
- [README.md](./README.md) — shipped spools index and loading notes.
