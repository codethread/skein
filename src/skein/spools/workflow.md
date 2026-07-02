# Skein Workflow Spool

## 1. Overview

`skein.spools.workflow` is a Clojure-native workflow layer built on ordinary
Skein strands, edges, and batch mutations. It lets spool authors define
small workflow molecules that agents can execute one step at a time without
needing to understand the underlying graph engine.

This is userland spool code, not a separate scheduler or persistence
system. Workflows compile into normal strand graphs, and runtime state
remains inspectable through the usual Skein REPL/graph helpers. The spool
owns no privileged runtime state.

Core primitives: `workflow`, `step`, `gate`, `checkpoint`, `call`, `param`,
`compile`, `pour!`, `wisp!`, and `explain`.

The generic runtime API is `start!`, `next-steps`, `next-step`, `complete!`,
`choose!`, `choice-detail`, `choice-details`, and `done?`, keyed by
`workflow/run-id`. Higher-level spools such as `skein.spools.devflow` should
define opinionated workflow definitions and thin convenience wrappers around
this namespace.

## 2. Credit

Terminology — molecule, wisp, pour, bond, squash, burn, and the
proto-like workflow-definition-as-data pattern — borrows heavily from
[beads](https://github.com/steveyegge/beads) by Steve Yegge (see
`docs/MOLECULES.md` in that repo).

What skein does differently: workflow definitions are Clojure-native data
(functions and maps) instead of TOML formulas, and `compile` turns that data
into ordinary skein strands and edges rather than a separate issue-tracker
schema. There is no proto/template storage layer — a workflow definition
*is* the reusable template, expressed as a Clojure function or map.

## 3. Definition layer

### Builders

| Builder | Returns |
|---|---|
| `(param & opts)` | A param definition map. Supports `:required true` and `:default v`. |
| `(step id title & opts)` | A step definition map. `:depends-on`, `:attributes`, `:condition`, `:loop`, `:description`, `:state`. |
| `(gate id title waiter & opts)` | A step marked `workflow/gate <waiter>` as an external wait point. Same opts as `step`. See "Gates" below. |
| `(checkpoint id title & opts)` | A step definition with checkpoint metadata. `:kind` (`:human` or `:agent`, default `:human`), `:choices`. |
| `(call id procedure params & opts)` | An inline procedure-reuse step. `:depends-on`, `:title`, `:attributes`. |
| `(workflow name & body)` | A workflow definition: `{:name .. :steps [..]}` plus optional leading opts map (`:params`, `:attributes`, `:state`, `:phase`). |

`name`, `title`, `description`, and attribute values may be plain values or
functions of the resolved params map — resolution happens once, after
`:params` defaults/required-checks and caller `params` are merged.

Builders reject unknown option keys loudly: passing a mistyped key (`:require`,
`:depend-on`, and similar typos) to `param`, `step`, `gate`, `checkpoint`, `call`,
a choice map, or the `workflow` leading-opts map fails with the offending keys
and the allowed set in the ex-data.

### Conditions

A step's `:condition` gates inclusion at compile time:

- keyword `:k` — truthy when `(get params :k)` is truthy
- `[:= :k v]` — equality against a resolved param
- `[:!= :k v]` — inequality against a resolved param

Excluded steps are dropped from the compiled strand set entirely.

### Condition splicing (dependency integrity)

After loop/call expansion and condition filtering, `compile` validates every
`:depends-on` ref:

- a ref pointing at a step excluded by `:condition` is **spliced**: the
  dependent inherits the excluded step's own `:depends-on`, transitively
  (matching beads' `parent-child` fan-through behavior) — so removing a
  conditional step never leaves a dangling dependency or a false blocker.
- a ref that never existed (typo, or a step that was never defined) fails
  loudly with `{:step .. :missing ..}`.
- a step `:id` equal to the compiled root ref (default `:molecule`, or
  `opts :root-ref`) fails loudly rather than silently colliding with the
  root strand.

### Loops

`:loop` on a step expands it into one strand per item. Expansion runs **after**
params are resolved, so loops see the full workflow param map:

- **Render env.** Each expanded step's fn-valued `title`/`description`/
  `attributes` is rendered against `(merge params {:item item :i idx})` — the
  per-iteration `:item` and its 0-based index `:i`, layered over the workflow
  params (so a loop step still sees `:feature` and the like).
- **`:each` forms.** `{:each xs}` where `xs` is a literal sequential, a keyword
  naming a resolved param, or a fn of the resolved params map returning a
  sequential. A param value or fn result that is not sequential **fails loudly**.
- **`:count`.** `{:count n}` expands over items `1..n` unchanged.
- **Id suffix** (`base-id-<suffix>`): the number for `:count`; `(:id item)`
  when the item is a map carrying `:id`; otherwise the item's 1-based position.
- **Fan-in.** Another step's `:depends-on` naming the **base** loop id (the
  pre-expansion id) is rewritten to depend on **all** expanded ids for that
  loop, so a downstream step can wait on the loop as a whole. Refs naming a
  genuinely unknown id still fail ref validation (see condition splicing above).

Conditions on loop steps are evaluated (against the workflow params) per expanded
copy after render, then spliced like any other step — excluding a loop step
drops all its copies and reattaches base-id dependents through condition
splicing.

### Gates — external wait points

The runtime is pull-based and *every* strand is already a durable wait
point: an external actor (CI, cron, a sub-agent, another session) can close a
strand through the ordinary surface and the run resumes on the next
`next-steps` poll. A **gate** just marks a step "not yours to complete — wait
for `<waiter>`", so a driving agent can tell work-steps from wait-steps.

```clojure
(workflow/gate :ci-green "Wait for CI to go green" :ci :depends-on [:push])
```

- `(gate id title waiter & opts)` returns an ordinary step (role stays
  `"step"`, so done-semantics are untouched) stamped with `workflow/gate
  <waiter>`. `waiter` is a freeform actor hint — `:ci`, `:human`,
  `:subagent`, … — stored as a string; it carries no engine semantics.
- `step-view` surfaces it as `:gate "<waiter>"`, so the driving agent should
  treat a ready gate as **poll/hand off, don't do**.
- The external actor closes the gate via `complete!` with a `:by`:
  `(workflow/complete! run-id {:step gate-id :by "ci"})`. `complete!` **refuses
  to close a gate without `:by`** (fails loudly) and records `:by` as
  `workflow/outcome-by` on the closed step. Raw `repl/update!` remains the
  trusted escape hatch (TEN-002) for closing any strand directly.

**Dynamic fan-out needs no primitive.** The run subgraph is recomputed live
from the graph on every poll, so userland may add strands to a running
molecule mid-flight — ordinary `strand!` plus `parent-of`/`depends-on` edges to
the run's root — to spawn e.g. sub-agent steps discovered at runtime. Set
`workflow/role "step"` on them so they count as workflow work and gate the
run's done-check exactly like poured steps.

### Tool bindings — forge-agnostic definitions

The engine never executes; the driving agent interprets ready-step data. So
a workflow that touches external tools (a git forge, CI, a deploy target)
should never name the tool. Instead:

- Steps carry a semantic `workflow/action-ref` name (`"pr.ci.wait"`), and
  the concrete command arrives through `workflow/instruction`.
- The definition fn accepts a **bindings map** — action-ref → binding map —
  as pure data through its params, shipping one tool's bindings as the
  default reference. A user rebinds any subset from trusted config by
  deep-merging over the reference (`merge-with merge`) — per-step,
  per-field granularity — and definitions never change. The binding field
  vocabulary (e.g. `:instruction`, `:skills`) belongs to the workflow
  author's mapping table, which fails loudly on unbound actions and unknown
  keys (TEN-003); the engine anticipates nothing.
- **Round-trip constraint:** bindings ride `workflow/context` across routed
  loop rounds, and the JSON layer keywordizes map keys on read and writes
  keyword keys via `name` — a NAMESPACED keyword key silently loses its
  namespace. Data that round-trips must therefore use simple,
  non-namespaced keyword keys (`:pr.ci.wait`, `:instruction`), and the
  definition maps them onto the canonical string attribute vocabulary
  (`"workflow/instruction"`) when building step attributes.

The pull-request model in `test/skein/spools/workflow_test.clj`
(`workflow-pr-flow-rebinds-forge-without-lib-changes`) is the reference for
this pattern: GitHub bindings shipped as defaults, GitLab swapped in as a
partial user override, identical definitions. A weaver-side action registry
(resolving action-ref names over the socket for CLI-grade drivers) is a
possible future layer; it is intentionally not built yet.

## 4. Run lifecycle

```
start! ──▶ next-steps / next-step ──▶ complete! / choose! ──▶ (repeat) ──▶ auto-close
```

- `(start! run-id workflow params opts)` — fails if `run-id` already has an
  active root; pours the workflow with `workflow/run-id run-id`; returns
  `(next-steps run-id)`.
- `(next-steps run-id)` — all currently ready, agent-facing step views for
  the run (vector, possibly empty).
- `(next-step run-id)` — convenience wrapper that throws if more than one
  step is ready; use `next-steps` for workflows with parallel entry points
  or fan-out.
- `(complete! run-id)` / `(complete! run-id opts)` — closes a non-checkpoint
  ready step and returns `(next-steps run-id)`.
- `(choose! run-id choice)` / `(choose! run-id choice input)` /
  `(choose! run-id choice input opts)` — records a checkpoint decision,
  optionally routes to a continuation (`:next`), and returns
  `(next-steps run-id)`. Revision loops route `:next` back to the same stage
  (see §5).

**`complete!` opts** (trailing map, all optional):

- `:step` — materialized strand id, selects which ready step to complete
  when more than one is ready. Without it, single-ready-step behavior
  applies (fails loudly if ambiguous). Validated before any mutation.
- `:notes` — recorded on the closed step as `"workflow/notes"`.
- `:attributes` — merged onto the closed step's attributes. Molecules exist
  for the audit trail, so closing a step can record what happened.
- `:by` — actor identity, recorded as `"workflow/outcome-by"`. **Mandatory**
  when closing a `gate` step (see §3 "Gates"); ignored on non-gate steps.

**`choose!` opts** (trailing map, all optional):

- `:step` — same selector semantics as `complete!`, also accepted by
  `choice-details`/`choice-detail`.
- `:by` — actor identity, recorded as `"workflow/outcome-by"` on the closed
  checkpoint.

### Auto-close ("done")

A run is **done** iff every strand in the root subgraph with
`workflow/role` in `#{"step" "checkpoint" "procedure"}` is `"closed"`. This
is checked (and the root closed if true) after every mutation that could
finish the run — `start!` (in case a workflow has zero steps), `complete!`,
and `choose!`.

This is stricter than "nothing is ready": a step blocked by a
userland-added `depends-on`, or a whole run parent-blocked by a `bond!` on
its root, does **not** make the run look done, and does not get
force-closed. `done?` reflects the same rule and throws (fail loudly) for a
`run-id` that has never had a root strand at all.

## 5. Checkpoints and routing

A checkpoint is a step with `workflow/role "checkpoint"`. Use `choose!`,
never `complete!`, on a checkpoint.

`:choices` accepts plain keywords (routing is then unavailable) or maps:

```clojure
{:key :approved
 :label "Approve"
 :description "Continue to implementation."
 :next 'my.ns/next-workflow-fn}
```

| Choice map key | Effect |
|---|---|
| `:key` | Choice name (required, unique per checkpoint). |
| `:label`, `:description` | Stored in `workflow/choice-details` for `choice-details`/`choice-detail`. |
| `:next` | Symbol naming a 1-arg fn called with the merged params (see below). Its return is compiled as the **continuation** workflow. |

### `:next` — routing to a continuation

The `:next` fn is called with `call-params = (merge workflow/context choice-input)`
and may return either:

- a **workflow map** — compiled with `call-params`, which are also persisted as
  the continuation root's `workflow/context`; or
- `{:workflow w :params p}` — `w` is compiled with `p`, and `p` (not
  `call-params`) becomes the continuation root's persisted `workflow/context`.
  This makes the continuation authoritative over its own params: a revision
  round can force `:revision true` regardless of what the caller passed as
  choice input, and start opts carried in `context` flow into the next round's
  params instead of resetting to their defaults.

Choosing a `:next` choice applies **one** transactional `batch/apply!` that,
atomically:

- closes the checkpoint, recording the outcome (see attribute table);
- force-closes every remaining active `step`/`checkpoint`/`procedure`/`molecule`
  strand in the current run's subgraph (existing strands are bound by their
  durable id and updated in place); and
- pours the compiled continuation's new strands and edges under the same
  `run-id`, carrying `family` forward from the current root.

The continuation is compiled once, before any mutation. Folding the checkpoint
close, the old-root closes, and the continuation pour into a single transaction
means a single active root ever holds the `run-id` (no concurrent
`current-root` sees an ambiguous two-root window), and a failed apply commits
nothing — the old root and its checkpoint stay active and the run stays
resumable rather than being stranded in a false terminal state.

**Warning:** a routed choice closes out the remaining steps of the current
workflow. Any step not yet reached when the checkpoint is chosen is
abandoned, not paused — routing is a hard cutover to the continuation, not
a fork or a merge. If you need work to resume rather than terminate, route to
a continuation that re-pours it (see "Loops — revise by routing" below), or
design the checkpoint so all prerequisite work is `:depends-on` the checkpoint
itself.

**Constraint — `:next` persists a stringified symbol** resolved via
`requiring-resolve` at `choose!` time, not at compile time. Renaming or removing
the target fn after a run has poured but before its checkpoint is chosen breaks
that in-flight run. There is no registry indirection yet; treat
workflow-continuation fn names as part of the in-flight run's durability
contract.

### Loops — revise by routing

There is no reopen/reactivate mechanism. A revise-style "go back and redo"
choice is modelled as a **tail call**: its `:next` routes back to the same
stage (or a thin revision variant of it), so `choose!` closes the checkpoint
and old root (outcome e.g. `"revise"`) and pours a **fresh** stage subgraph
under the same `run-id`. Each iteration is thus an immutable subgraph — the
whole loop history stays in the graph, squashable, never mutated in place.

The pattern: a stage constructor takes a `:revision` flag and declares a
matching param (`:revision (param :default false)`); a thin revision fn wraps the
stage constructor with `:revision true`, returning `{:workflow w :params p}` so
the revision params win over any choice input and `p` persists as the new root's
`workflow/context`. A `:condition [:!= :revision true]` gates the work that must
not repeat; on a revision round the excluded step drops out and condition
splicing (§3) reattaches its dependents, so the round is ready at the first
genuinely-repeatable step.

```clojure
(defn proposal-workflow [{:keys [revision] :as _opts}]
  (workflow/workflow
    "Proposal"
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}}
    (workflow/step :inspect-context "Orient"
                   :condition [:!= :revision true])   ; skip on later rounds
    (workflow/step :write-proposal "Write proposal"
                   :depends-on [:inspect-context])
    (workflow/checkpoint :signoff "Sign off"
                         :depends-on [:write-proposal]
                         :choices [{:key :approved :next 'my.ns/spec-plan-workflow}
                                   {:key :revise :next 'my.ns/proposal-revision-workflow}])))

(defn proposal-revision-workflow [opts]
  (let [params (assoc opts :revision true)]
    {:workflow (proposal-workflow params) :params params}))
```

A routed `:revise` is an ordinary `:next` continuation, so the same transaction
and the same "closes out the remaining steps" warning above apply unchanged.

## 6. Molecule ops

| Fn | Effect |
|---|---|
| `(pour! workflow params opts)` | Materializes a persistent molecule strand graph. |
| `(wisp! workflow params opts)` | Materializes an ephemeral wisp strand graph (`workflow/wisp "true"` on the root); userland burns or squashes it explicitly. |
| `(bond! left-id right-id)` | Connects two materialized molecules: `right-id` depends on `left-id`. |
| `(burn! root-id)` | Deletes the molecule/wisp subgraph rooted at `root-id`. |
| `(squash! root-id title attributes)` | Replaces a materialized subgraph with one closed digest strand (`workflow/role "digest"`), then burns the original graph. |
| `(molecule-id result)` | Returns the materialized root id from a `pour!`/`wisp!` result. |

A bond adds a `depends-on` edge (`right-id` depends on `left-id`), stamped
`workflow/bond "sequential"` to distinguish it from the intra-molecule
dependency edges `compile` emits. A dep-blocked root **parent-blocks its
run**: `next-steps` returns `[]` for the bonded run — its steps stay hidden
even though their own deps are satisfied — until the blocking root closes
(which the left run's own completion does automatically).
Unlike beads, there are no `parallel`/`conditional` bond types: parallelism
already falls out of edge *absence* (the ready frontier is the parallel
set), and failure-routing belongs in checkpoint choices with `:next` until
the runtime grows a failure concept for edges to key off.

## 7. Attribute vocabulary

This table is the extension API: spools built on top of
`skein.spools.workflow` (like `skein.spools.devflow`) read and write these
`workflow/*` attributes directly on strands. Unless noted, attributes are
plain string-keyed `TEXT`/JSON values on the strand's `:attributes` map.

| Attribute | Meaning | Set by |
|---|---|---|
| `workflow/role` | `"molecule"`, `"step"`, `"checkpoint"`, `"procedure"`, or `"digest"`. Drives which strands count as workflow work. | `compile` (root/step strands), `expand-call-step` (procedure join step), `squash!` (digest). |
| `workflow/phase` | `"molecule"` or `"wisp"`. | `compile`, from `opts :phase` (defaults molecule) or `pour!`/`wisp!`. |
| `workflow/wisp` | `"true"` when phase is wisp. | `compile`, root strand only, when phase is `:wisp`. |
| `workflow/run-id` | Stable run handle used by `start!`/`next-steps`/`complete!`/`choose!`/`current-root`. | `compile`, from `opts :run-id` (root strand only). |
| `workflow/family` | Grouping label across related runs (e.g. `"devflow"`). Carried forward into `:next` continuations. | `compile`, from `opts :family` (root strand only). |
| `workflow/definition` | Stringified symbol naming the workflow definition fn/var. | `compile`, from `opts :definition` (root strand only). |
| `workflow/context` | Map merged with checkpoint choice input to build `:next` continuation params (also carries revision-loop state forward). | `compile`, from `opts :context` (root strand only); read back by `route-plan`. |
| `workflow/gate` | Freeform waiter/actor hint marking a step an external wait point (`"ci"`, `"human"`, `"subagent"`, …). Surfaced by `step-view` as `:gate`; makes `complete!` require `:by`. | `gate` builder. |
| `workflow/checkpoint` | Stable checkpoint id (the step's own local id). | `checkpoint` builder. |
| `workflow/checkpoint-kind` | Decision owner: `"human"` or `"agent"` (unenforced, provenance only — TEN-002). | `checkpoint` builder, from `:kind`. |
| `workflow/choices` | Vector of allowed choice-name strings. | `checkpoint` builder, from `:choices`. |
| `workflow/choice-details` | Map of choice name → `{"label" .. "description" .. "next" ..}`. | `checkpoint` builder, from map-form `:choices` entries. |
| `workflow/decision-point` | Freeform label naming what the checkpoint decides (devflow convention). | Caller-supplied `:attributes`, e.g. devflow. |
| `workflow/hitl` | `"true"` marking a human-in-the-loop checkpoint (devflow convention). | Caller-supplied `:attributes`, e.g. devflow. |
| `workflow/action-ref` | Semantic name of the action an agent should perform for this step (`"devflow.worktree.ensure"`, `"pr.ci.wait"`); the tool-binding key for forge-agnostic definitions (see "Tool bindings"). | Caller-supplied `:attributes`. |
| `workflow/instruction` | Freeform instruction text surfaced in `step-view`. | Caller-supplied `:attributes`. |
| `workflow/artifact` | Pointer to the artifact a step produces, surfaced in `step-view` (falls back to `devflow/artifact` if unset). | Caller-supplied `:attributes`. |
| `workflow/outcome` | The choice name recorded when a checkpoint closes via a `:next`-routed or plain choice. | `choose!`, on the checkpoint step, at close. |
| `workflow/outcome-input` | The `input` map passed to `choose!`. | `choose!`, on the checkpoint step, at close. |
| `workflow/outcome-by` | Actor identity that closed the strand. | `choose!` (checkpoint close, when opts supply `:by`); `complete!` (gate close, where `:by` is mandatory). |
| `workflow/notes` | Freeform notes recorded when a step closes. | `complete!`, from `opts :notes`. |
| `workflow/procedure` | Name of the `call` id whose expansion this join step represents. | `expand-call-step`, on the procedure join step. |
| `workflow/bond` | `"sequential"` — recorded on the bond edge itself, marking a cross-molecule bond. | `bond!`. |
| `workflow/squashed-root` | Root id of the subgraph a digest strand replaced. | `squash!`. |
| `workflow/squashed-count` | Number of strands in the squashed subgraph. | `squash!`. |
| `skills` | Freeform skill/tool hint for a step (not `workflow/`-namespaced; devflow convention, surfaced by `step-view`). | Caller-supplied `:attributes`. |

Other plain (non-`workflow/`-namespaced) attributes such as `"description"`
pass through from a step's `:description`/`:attributes` fields as-is —
`step-strand` only adds the `workflow/role`/`workflow/phase` pair itself.

## 8. End-to-end example

```clojure
(require '[skein.spools.workflow :as workflow])

(defn ship-workflow [{:keys [revision] :as _params}]
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Ship " feature))
    {:params {:feature (workflow/param :required true)
              :revision (workflow/param :default (boolean revision))}}
    (workflow/step :design
                   (fn [{:keys [feature]}] (str "Design " feature))
                   :condition [:!= :revision true])       ; skip on revise rounds
    (workflow/step :implement
                   (fn [{:keys [feature]}] (str "Implement " feature))
                   :depends-on [:design])
    (workflow/checkpoint :signoff
                         "Approve implementation?"
                         :depends-on [:implement]
                         :kind :human
                         :choices [{:key :approved
                                    :label "Approve"
                                    :description "Ship it."}
                                   {:key :revise
                                    :label "Revise"
                                    :description "Send implementation back and re-run the stage."
                                    :next 'my.ns/ship-revision-workflow}])))

(defn ship-revision-workflow [params]
  ;; {:workflow w :params p} keeps the revision params authoritative over the
  ;; choice input for the new round's compile and persisted context
  (let [p (assoc params :revision true)]
    {:workflow (ship-workflow p) :params p}))

;; run — set :context so the revise loop carries :feature forward
(workflow/start! "ship-feature-x" (ship-workflow {:feature "feature x"})
                 {:feature "feature x"} {:context {:feature "feature x"}})
;; => [{:id :design ...}]

(workflow/complete! "ship-feature-x")
;; => [{:id :implement ...}]

(workflow/complete! "ship-feature-x")
;; => [{:id :signoff :kind "checkpoint" :choices ["approved" "revise"] ...}]

;; revise: closes this round's root and pours a fresh one under the same
;; run-id; :design is condition-skipped, so the round is ready at :implement
(workflow/choose! "ship-feature-x" :revise {})
;; => [{:id :implement ...}]

(workflow/complete! "ship-feature-x")
;; => [{:id :signoff ...}]

;; approve: closes the checkpoint; no :next means the run is now done
(workflow/choose! "ship-feature-x" :approved {})
;; => []   ; run auto-closes: all step/checkpoint/procedure strands are closed

(workflow/done? "ship-feature-x")
;; => true
```

A checkpoint choice with `:next` routes to a continuation workflow and closes
out the rest of the current run (see §5) — see `skein.spools.devflow`'s
`proposal-workflow` → `spec-plan-workflow` hand-off for a routed hand-off, and
its `human-signoff-proposal` `:revise` → `proposal-revision-workflow` for a
revise-by-routing loop.

## 9. See also

- `skein.spools.devflow` — the reference higher-level spool built on this
  namespace: opinionated devflow-stage workflow definitions and thin
  `start!`/`next-step`/`complete!`/`choose!` wrappers keyed by feature name
  instead of a raw run-id. Its revise-style choices route back through their
  own stage (revise-by-routing, §5) rather than dead-ending the run. See
  `devflow.md`.
- `(skein.spools.workflow/explain)` / `(explain topic)` — machine-readable
  contracts for `:workflow`, `:step`, `:gate`, `:checkpoint`, and `:call`,
  intended for agents to call before constructing workflow data instead of
  relying on this document alone.
