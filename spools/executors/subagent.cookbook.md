# Skein Treadle Spool — Cookbook

Composition recipes for `skein.spools.executors.subagent`: how to let a workflow hand a step to a spawned agent and get the result back, and *why* the bridge is shaped the way it is.

This is the **how/why** half of the treadle docs. The other two halves are:

- [`executors/subagent.md`](./subagent.md) — the **contract**: gate request
  attributes, the `subagent/*` vocabulary, delivery semantics, and recovery.
  Read it for what the adapter promises.
- [`executors/subagent.api.md`](./subagent.api.md) — the **generated reference**: every
  public fn's signature and docstring, produced from the source.

Division of truth: signatures and argument lists live in the generated API doc; narrative and composition live here and in the contract. This cookbook never restates a fn signature or the attribute table — it links to them.

Treadle sits between two spools that know nothing of each other: the [workflow engine](../workflow.md), which models a step it can't do itself as a `gate`, and the [agent-run engine](../agent-run/README.md), which spawns agent runs but has no notion of workflows. Treadle is the only namespace that speaks both. Load order matters — **shuttle first, then treadle** (its `install!` fails loudly otherwise and runs an initial gate scan) — and treadle must load *after* any startup config that registers the harness aliases your gates name. See [`treadle.md`, "Loading"](./subagent.md#loading).

## How to read a recipe

Every recipe has the same four parts:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[skein.spools.workflow :as workflow])`).
4. **Why this shape** — the reasoning: why these primitives, what the attribute
   conventions buy you, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the treadle source or its test suite — so you can read the load-bearing version. The tests use a tiny `sh`-based harness (`sh-tail`, which runs only the final prompt line), so a gate's whole spawn-deliver cycle runs without a real coding agent.

---

## Recipe: A workflow step an agent does — the `:subagent` gate

**Situation.** A stage in your workflow isn't the driving agent's to *do* — it's work to delegate to a fresh agent run — but the workflow should still block on it and pick up the result before moving on.

**Composition.** Model the step as an ordinary `workflow/gate` with waiter `:subagent`, carrying the run request as `agent-run/*` attributes. That's the whole authoring surface. Treadle watches for the gate to become ready, spawns a agent-run run from those attributes, and — when the run succeeds — completes the gate through `workflow/complete!`. The step after the gate then unblocks normally.

```clojure
(require '[skein.spools.workflow :as workflow])

(def build-widget
  (workflow/workflow
    "Build widget"
    (workflow/step :design "Design widget" :self)
    (workflow/gate :implement "Implement widget" :subagent    ; not :self — delegate it
                   :depends-on [:design]
                   :attributes {"agent-run/harness" "pi"
                                "agent-run/prompt"  "Implement the widget per specs/widget.md"
                                "agent-run/cwd"     "/path/to/worktree"})
    (workflow/step :review "Review implementation" :self
                   :depends-on [:implement])))

(workflow/start! "widget-1" build-widget {})
(workflow/complete! "widget-1")            ; finish :design; :implement becomes ready
;; treadle sees the ready :subagent gate, spawns a agent-run run, and on success
;; stamps the gate workflow/outcome-by = run id, workflow/outcome-notes = agent-run/result,
;; then :review becomes ready.
```

**Why this shape.**

- **Both engines stay ignorant of each other.** The workflow author writes a
  plain gate; the agent-run spawns a plain run. Neither namespace grows a
  dependency on the other — treadle is the small adapter that knows both
  vocabularies, so you can use either engine alone and bolt them together only
  where you need to (contract [`treadle.md`, "Overview"](./subagent.md#overview)).
- **Readiness drives it, so nothing polls.** Treadle is an event handler over the
  same graph both engines already watch. The gate spawns its run the moment it
  becomes ready — after `:design` closes, not before — because `depends-on`
  readiness is the only trigger. A gate blocked behind an unfinished step is left
  untouched until it unblocks.
- **The result comes back as a gate outcome, not a side channel.** The run's
  `agent-run/result` lands on the gate as `workflow/outcome-notes`, and `workflow/outcome-by`
  records the run id — so the delegated output is part of the workflow's own
  audit trail, and the next step reads it like any other completed step.

Honest source: `happy-path-spawns-delivers-and-unblocks-next-step` in ``test/skein/treadle_test.clj``, and the worked example in [`treadle.md`](./subagent.md#worked-example).

---

## Recipe: Mapping gate data to the spawned run

**Situation.** Different gates need different agents, prompts, working directories, or crash-attempt bounds — and you'd rather not repeat a prompt you already wrote as the step's instruction.

**Composition.** The gate's `agent-run/*` attributes *are* the run request. `agent-run/harness` is required and passes straight to `agent-run/spawn-run!`. `agent-run/prompt` is optional: when absent, treadle derives one from `workflow/instruction`, then `description`, then the title. `agent-run/cwd` and `agent-run/max-attempts` pass through. Anything malformed stamps `subagent/error` on the gate rather than spawning a broken run.

```clojure
(require '[skein.spools.workflow :as workflow])

;; explicit prompt, pinned harness and worktree, bounded recovery
(workflow/gate :implement "Implement widget" :subagent
               :attributes {"agent-run/harness"      "build"
                            "agent-run/prompt"       "Implement per specs/widget.md"
                            "agent-run/cwd"          "/path/to/worktree"
                            "agent-run/max-attempts" "2"})     ; integer or integer-string

;; no agent-run/prompt: treadle derives the run prompt from workflow/instruction
(workflow/gate :docs "Write the docs" :subagent
               :attributes {"agent-run/harness"      "grunt"
                            "workflow/instruction" "Draft docs/widget.md from the spec"})
```

**Why this shape.**

- **The gate is a pure-data run request.** Handing the run its whole
  configuration as attributes keeps treadle a translator with no policy: it reads
  the gate, builds a `spawn-run!` call, and gets out of the way. The
  [fan-out recipe](../workflow.cookbook.md#recipe-fan-out-over-a-collection-with-a-chained-loop)
  in the workflow cookbook uses exactly this to compute per-task `agent-run/*`
  attributes from a loop item.
- **Prompt derivation avoids saying the same thing twice.** A step that already
  carries a `workflow/instruction` doesn't need it copied into `agent-run/prompt`;
  treadle falls back through instruction → description → title so the gate stays
  DRY. If nothing non-blank exists to send, that's an authoring error and treadle
  stamps `subagent/error` instead of spawning a promptless run.
- **Bad requests fail on the gate, loudly and locally.** A missing or invalid
  `agent-run/harness`, or a `agent-run/max-attempts` that isn't an integer, stamps
  `subagent/error` on the gate and does not retry. The gate is then skipped until
  a coordinator clears the error — the failure is durable and visible, never a
  silently dropped spawn (contract
  [`treadle.md`, "Gate request attributes"](./subagent.md#gate-request-attributes)).

Honest source: the gate request table in [`treadle.md`](./subagent.md#gate-request-attributes), and `missing-harness-stamps-error-and-does-not-retry` in ``test/skein/treadle_test.clj``.

---

## Recipe: Finding and recovering a stalled gate

**Situation.** A delegated run died — it crashed, or it exited cleanly but wrote nothing back. The gate is stuck. You want to find it and get a fresh agent onto it.

**Composition.** Discovery is the `stalled-gates` named query (or the `gate-stalled?` predicate on a gate view). Recovery is a single mutation: **clear the gate's `subagent/run` attribute** (optionally rewriting `agent-run/prompt`). The next scan finds no live run for the gate and spawns a fresh delegation. From the CLI, use `strand update <gate-id> --attr subagent/run=`; an empty string is treated as cleared. For spawn-side errors, `--attr subagent/error=` clears the error for the next scan.

```clojure
(require '[skein.api.weaver.alpha :as api]
         '[skein.api.current.alpha :as current])

(def rt (current/runtime))              ; the active weaver runtime

;; find stalled subagent gates (spawn-side error, or a dead delegated run)
(api/list-query rt 'stalled-gates {})

;; recover: clear the stamp and, if useful, sharpen the prompt.
;; Clearing subagent/run is what triggers the fresh spawn — not `agent retry`.
(api/update rt gate-id {:attributes {"subagent/run"    nil
                                     "agent-run/prompt" "echo recovered"}})
;; next scan spawns a fresh run stamped subagent/gate, linked by a delegates edge,
;; which the treadle then delivers.
```

**Why this shape.**

- **Clearing the stamp, not retrying, is the recovery verb.** Treadle keys
  delivery and idempotency off the gate's single current `subagent/run`. Blanking
  it tells the next scan "no live run here," which spawns a fresh delegation and
  re-stamps the gate. `agent retry <run-id>` is deliberately *not* this: it
  supersedes the dead run and parents a fresh one to the gate with a `parent-of`
  edge — the structural relation treadle avoids — so the retry run is cross-wired
  into the workflow subgraph as spurious `next-steps` work, carries no
  `subagent/gate`, gets no `delegates` edge, and is never adopted. The gate stays
  stalled until you clear the stamp (contract
  [`treadle.md`, "Failure and recovery"](./subagent.md#failure-and-recovery)).
- **A blank result is a failure, not a delivery.** Delivery selects only closed
  runs in agent-run phase `done`, which shuttle records solely for a non-blank
  result. A run that exits 0 with empty stdout is a silently dead worker; shuttle
  records it `failed`, so it never satisfies the gate. The gate stays ready and
  stamped, discoverable through `stalled-gates` and (as a delegated run) through
  `agent-failures` — loud, not lost.
- **The two discovery paths stay in lockstep.** `gate-stalled?` reads the gate's
  single current `subagent/run`; the `stalled-gates` query reaches the run's phase
  through the `delegates` edge. Stamping a fresh run marks the dead one
  `subagent/superseded-by`, which the query excludes — so the gate stops surfacing
  the instant a healthy replacement is in flight, exactly when the predicate also
  goes quiet.

Honest source: `failed-run-stays-ready-and-clearing-stamp-spawns-fresh-run`, `agent-retry-of-blank-gate-run-supersedes-without-stale-delivery`, `recovery-respawn-keeps-stalled-gates-in-lockstep-with-predicate`, and `stalled-gates-query-reports-spawn-errors-and-dead-runs` in ``test/skein/treadle_test.clj``.

---

## Recipe: Why delivery runs through `workflow/complete!`

**Situation.** You're reasoning about *when* a subagent gate closes, and want to know why the run doesn't just close its own gate when it finishes.

**Composition.** Nothing to wire — this recipe explains a load-bearing choice. A finished run never closes the gate itself; treadle delivers by calling `workflow/complete!` on the gate with the run result, and only for a genuinely successful run. Treadle also registers itself as the workflow executor for `:subagent`, which keeps `await!` quiet on a healthy gate.

```clojure
(require '[skein.spools.workflow :as workflow])

;; await! stays :waiting on a healthy subagent gate (treadle is its executor),
;; and surfaces :stalled — with the spawn error — only when the gate is genuinely stuck.
(workflow/await! "widget-1" {:timeout-secs 10})
;; => {:reason :stalled :detail {:stall {:error "... agent-run/harness ..."}}}  ; when stuck
```

**Why this shape.**

- **Only the workflow may close a workflow step.** Delivery through
  `workflow/complete!` means the gate closes with the engine's own bookkeeping —
  `workflow/outcome-by`, `workflow/outcome-notes`, the auto-close of any procedure join —
  intact. A run reaching in to close the gate strand directly would bypass all of
  it. The result travels as a gate outcome, keeping the workflow the single
  authority over its own graph.
- **A silently dead worker must not satisfy a gate.** Because delivery selects
  only phase-`done` runs (non-blank result), the blank-exit-0 case can't
  masquerade as success. If treadle instead let the run self-close the gate on
  exit, a hollow run would advance the workflow with nothing done. Routing
  through `complete!` on `done`-only is what makes that impossible.
- **A registered executor keeps coordination quiet until it shouldn't be.**
  `install!` registers `gate-stalled?` as the `:subagent` executor, so a
  coordinator's `await!` reports `:waiting` on a healthy delegated gate instead of
  surfacing it as unattended, and wakes to `:stalled` only on a real stall
  (spawn error, or a dead/exhausted/superseded run). No wall-clock hang policy is
  invented — stall is a graph fact, not a timeout (contract
  [`treadle.md`, "Coordination attention"](./subagent.md#coordination-attention)).

Honest source: `blank-result-gate-fails-loudly-stays-discoverable-and-recovers` and `subagent-registers-executor-for-flow-await` in `test/skein/executors/subagent_test.clj`.

---

## See also

- [`executors/subagent.md`](./subagent.md) — the contract: gate request
  attributes, the `subagent/*` vocabulary, delivery, failure, and recovery.
- [`executors/subagent.api.md`](./subagent.api.md) — generated signatures and docstrings for
  every public fn referenced above.
- [`workflow.cookbook.md`](../workflow.cookbook.md) — the gate and fan-out recipes
  that author the `:subagent` gates treadle fulfills.
- [`agent-run.cookbook.md`](../agent-run.cookbook.md) — the run engine treadle spawns
  onto: harnesses, readiness, and crash recovery.
