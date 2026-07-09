# Skein Subagent Executor Spool

> This is the **contract** doc: gate request attributes, the `subagent/*`
> vocabulary, delivery semantics, and recovery. Its two companions are
> [`subagent.cookbook.md`](./subagent.cookbook.md) — worked composition recipes
> (how/why you bridge a `:subagent` gate to a run, and recover a stalled one) —
> and [`subagent.api.md`](./subagent.api.md) — generated fn signatures and
> docstrings. Reach for the cookbook when you want a runnable pattern, the API
> doc when you want an exact arity, and this doc for what the adapter promises.

## Overview

`skein.spools.executors.subagent` is the agent-run-backed adapter for workflow gates whose waiter is `subagent`. It watches ready workflow gates, spawns agent-run runs, and completes the gate with the run result when the run succeeds.

The workflow engine remains forge/tool agnostic: workflow authors declare an ordinary `(workflow/gate ... :subagent ...)` with attributes. Agent-run remains a run engine with no workflow concepts. The subagent executor is the small bridge that knows both vocabularies.

## Loading

Load agent-run first, then the subagent executor from the same approved local-root spool:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))
(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :agent-run
  {:ns 'skein.spools.agent-run
   :spools ['skein.spools/agent-run]
   :call 'skein.spools.agent-run/install!
   :required? true})
(runtime-alpha/use! runtime :subagent
  {:ns 'skein.spools.executors.subagent
   :spools ['skein.spools/agent-run]
   :call 'skein.spools.executors.subagent/install!
   :required? true})
```

`install!` fails loudly unless `:agent-run/engine` is already installed.

Install the subagent executor **after** any startup config that registers harness aliases: `install!` runs an initial gate scan, so an alias a durable ready gate names (e.g. `worker`) must already be registered or that gate is stamped `subagent/error` on every cold start.

Gate scans serialize on a runtime-owned monitor: independent weaver runtimes in one JVM scan independently and never block each other.

## Gate request attributes

| Attribute | Required | Meaning |
|---|---|---|
| `workflow/gate` = `"subagent"` | yes | Marks a ready workflow gate for treadle fulfillment. Other waiters are ignored. |
| `agent-run/harness` | yes | Harness or alias name passed to `agent-run/spawn-run!`. Missing or invalid values stamp `subagent/error` on the gate. |
| `agent-run/prompt` | no | Run prompt. If absent, treadle derives one from `workflow/instruction`, `description`, then title. If nothing non-blank exists, it stamps `subagent/error`. |
| `agent-run/cwd` | no | Passed through as the run working directory. |
| `agent-run/max-attempts` | no | Agent-run crash-recovery attempt bound. Accepts an integer or an integer string (attributes may arrive JSON-stringified); anything else stamps `subagent/error`. |

## Subagent attributes

| Attribute | On | Meaning |
|---|---|---|
| `subagent/run` | gate step | Id of the delegated agent-run run. |
| `subagent/error` | gate step | Durable spawn-side failure detail; the gate is skipped until a coordinator clears it. |
| `subagent/gate` | run strand | Id of the gate step this run fulfills. |
| `subagent/run-id` | run strand | Workflow `run-id` owning the gate. |
| `subagent/superseded-by` | run strand | Id of the fresh run that replaced this dead one when the gate was cleared and respawned; excludes the run's stale `delegates` edge from `stalled-gates`. |
| `subagent/delivered` | run strand | `"true"`, `"gate-closed"`, or `"error: …"`; presence means delivery is terminal for this run. |
| `subagent/delivery-blocked` | run strand | Written once when a finished run's gate is active but not currently ready (e.g. a dependency added after spawn). Non-terminal: the run stays undelivered and delivery retries when the gate becomes ready again. |

The subagent executor links each gate to its delegated run with a `delegates` annotation edge plus the `subagent/run`, `subagent/gate`, and `subagent/run-id` attributes. It must not use `parent-of` for this provenance because that structural relation would place the run inside the workflow subgraph and surface it as workflow `next-steps` work. (A generic `agent retry` of a delegated run *does* create such a `parent-of` edge back to the gate — which is exactly why it is not the gate-recovery verb; see [Failure and recovery](#failure-and-recovery).)

## Worked example

```clojure
(require '[skein.spools.workflow :as workflow])

(def build-widget
  (workflow/workflow
    "Build widget"
    (workflow/step :design "Design widget" :self)
    (workflow/gate :implement "Implement widget" :subagent
                   :depends-on [:design]
                   :attributes {"agent-run/harness" "pi"
                                "agent-run/prompt" "Implement the widget per specs/widget.md"
                                "agent-run/cwd" "/path/to/worktree"})
    (workflow/step :review "Review implementation" :self
                   :depends-on [:implement])))

(workflow/start! "widget-1" build-widget {})
(workflow/complete! "widget-1")
;; The subagent executor observes :implement as a ready subagent gate, spawns a agent-run
;; run, then completes the gate with workflow/outcome-by = run id and
;; workflow/outcome-notes = agent-run/result when the run closes successfully.
```

## Failure and recovery

Only a genuinely successful run delivers a gate: delivery selects closed runs in agent-run phase `done`, which agent-run records solely for a non-blank result. A run that exits 0 with a **blank** result is recorded `failed` by agent-run (see the README blank-result paragraph), so it never completes the gate — a silently dead worker must not satisfy a subagent gate. Instead the failed run stays active and the gate remains ready and stamped with `subagent/run`, discoverable via the stall predicate and `stalled-gates` query below and (as a delegated run) via `agent-failures`.

A coordinator recovers such a gate by clearing its `subagent/run` attribute (optionally re-writing `agent-run/prompt`). A CLI clearing idiom is `--attr subagent/run=` (empty string, since there is no attribute-delete flag): the next scan finds no live run for the gate and spawns a fresh delegation — a run stamped `subagent/gate` and linked by a `delegates` edge, which the subagent executor then delivers. Stamping the fresh run repoints the dead run's provenance with `subagent/superseded-by`, so the gate's stale `delegates` edge stops surfacing it in `stalled-gates` once a healthy replacement is in flight. Clearing the stamp, not retrying, is what triggers that fresh delegation. The same empty-string rule applies to `subagent/error`: `--attr subagent/error=` clears a spawn-side error for the next scan.

`agent retry <run-id>` is **not** the gate-recovery verb. It supersedes the dead run (phase → `superseded`) and spawns a fresh run parented to the run's served target; for a subagent run that target resolves to the gate itself (`run-summary`'s `:for` is the run's `subagent/gate`). So retry *does* re-link a run to the gate — but only structurally, via a `parent-of` edge, the very relation the subagent executor avoids for delegations (see [Subagent attributes](#subagent-attributes)): it cross-wires the fresh run into the workflow subgraph as spurious `next-steps` work. It does **not** create the delegation the subagent executor recognises — the fresh run carries no `subagent/gate`, gets no `delegates` edge, and the gate's `subagent/run` stamp is never rewritten. The subagent executor keys delivery and idempotency off `subagent/gate` / `subagent/run`, so it never adopts or delivers the retry run: the gate stays stalled on the now-`superseded` stamped run until `subagent/run` is cleared. Gates stamped with `subagent/error` are skipped until the error attribute is cleared.

If a gate is closed or routed away while its agent-run run is in flight, the completed run is stamped `subagent/delivered "gate-closed"` and the result remains on the run strand for audit. If the gate is still active but no longer ready when the run finishes, the run is stamped `subagent/delivery-blocked` and delivery retries once the gate is ready again.

Crash-window caveat: spawn idempotency re-adopts only live prior runs. A run that failed before the weaver crash stamped its gate is orphaned (a fresh run spawns beside it), never re-adopted — the failed run stays visible for audit.

## Coordination attention

`install!` calls `(workflow/register-executor! :subagent gate-stalled?)`, registering treadle as the executor for every gate whose `waiter` is `subagent`. Because an executor is registered, `await!` stays silent (`:waiting`) on a healthy subagent gate instead of surfacing it immediately as `:gate`; `gate-stalled?` reports a ready subagent gate as stalled (`:reason :stalled`) when the gate has `subagent/error` or its stamped `subagent/run` is in agent-run phase `failed`/`exhausted`/`superseded`, else it reports nothing. No wall-clock hang policy is applied. `superseded` is included so a gate whose run was retired by `agent retry` stays discoverable rather than silently pending until a coordinator clears the stamp.

The spool also registers `stalled-gates` and `blocked-deliveries` named queries for coordinator inspection. `stalled-gates` is the SQL-side mirror of the stall predicate: it returns active subagent gates that either carry `subagent/error` or have a `delegates`-linked run in a terminal phase (`failed`/`exhausted`/`superseded`). A named query cannot join a gate's `subagent/run` id back to the run row, so it matches through the `delegates` edge instead. To keep that edge-scoped view in lockstep with the current-stamp `:subagent` predicate, stamping a fresh run marks the run it replaces `subagent/superseded-by`, and the query excludes superseded runs. So after a clear-and-respawn the gate stops surfacing as soon as a healthy replacement is in flight — exactly when `gate-stalled?` (which reads the single current `subagent/run`) also returns nil. Its membership rule is therefore "the current delegated run is dead", the same condition the predicate applies. `blocked-deliveries` returns finished runs parked on `subagent/delivery-blocked`.

## See also

- [`skein.spools.workflow`](../workflow.md) — workflow gates and runtime API.
- [`skein.spools.agent-run`](../agent-run/README.md) — agent-run run lifecycle and harness registry.
- ``test/skein/treadle_test.clj`` — executable contract tests.
