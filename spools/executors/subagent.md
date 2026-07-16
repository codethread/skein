# Skein Subagent Executor Spool

> This is the **contract** doc: gate request attributes, the `gate/*` vocabulary, delivery
> semantics, and recovery. Its two companions are [`subagent.cookbook.md`](./subagent.cookbook.md),
> for worked composition recipes, and [`subagent.api.md`](./subagent.api.md), for generated fn
> signatures and docstrings. Reach for the cookbook when you want a runnable pattern, the API doc
> when you want an exact arity, and this doc for what the adapter promises.

## Overview

`skein.spools.executors.subagent` is the agent-run-backed adapter for workflow gates whose waiter is
`subagent`. It watches ready workflow gates, spawns agent-run runs, and completes the gate with the
run result when the run succeeds.

The workflow engine remains forge/tool agnostic: workflow authors declare an ordinary
`(workflow/gate ... :subagent ...)` with attributes. Agent-run remains a run engine with no workflow
concepts. The subagent executor is the small bridge that knows both vocabularies.

## Loading

Load agent-run first, then the subagent executor from the same approved local-root spool:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/sync! runtime)
(runtime/use! runtime :agent-run
  {:ns 'skein.spools.agent-run
   :spools ['skein.spools/agent-run]
   :call 'skein.spools.agent-run/install!
   :required? true})
(runtime/use! runtime :subagent
  {:ns 'skein.spools.executors.subagent
   :spools ['skein.spools/agent-run]
   :call 'skein.spools.executors.subagent/install!
   :required? true})
```

`install!` fails loudly unless `:agent-run/engine` is already installed.

Install the subagent executor **after** any startup config that registers harness aliases.
`install!` runs an initial gate scan, so an alias a durable ready gate names (e.g. `worker`) must
already be registered or that gate is stamped `gate/error` on every cold start.

Gate scans serialize on a runtime-owned monitor: independent weaver runtimes in one JVM scan
independently and never block each other.

## Gate request attributes

| Attribute | Required | Meaning |
|---|---|---|
| `workflow/gate` = `"subagent"` | yes | Marks a ready workflow gate for subagent-executor fulfillment. Other waiters are ignored. |
| `agent-run/harness` | yes | Harness or alias name passed to `agent-run/spawn-run!`. Missing or invalid values stamp `gate/error` on the gate. |
| `agent-run/prompt` | no | Run prompt. If absent, derives one from `workflow/instruction`, `description`, then title; if none exists, stamps `gate/error`. |
| `agent-run/cwd` | no | Passed through as the run working directory. |
| `agent-run/max-attempts` | no | Crash-recovery attempt bound. Accepts an integer or integer string; anything else stamps `gate/error`. |

## Gate attributes

| Attribute | On | Meaning |
|---|---|---|
| `gate/error` | gate step | Durable spawn-side failure detail; the gate is skipped until a coordinator clears it. |
| `gate/run-id` | run strand | Workflow `run-id` owning the gate. |
| `gate/delivered` | run strand | `"true"`, `"gate-closed"`, or `"error: …"`; presence means delivery is terminal for this run. |
| `gate/delivery-blocked` | run strand | Written once when a finished run's gate is active but not ready; delivery retries when the gate becomes ready. |

The subagent executor links each delegated run to its gate with a `serves` edge from the run to the
gate. It must not use `parent-of` for this provenance because that structural relation would place
the run inside the workflow subgraph and surface it as workflow `ready` work. Agent-run lineage
uses `supersedes` edges; retry successors inherit the same `serves` target.

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
;; The subagent executor observes :implement as a ready subagent gate, spawns an agent run
;; run, then completes the gate with workflow/outcome-by = run id and
;; workflow/outcome-notes = agent-run/result when the run closes successfully.
```

## Failure and recovery

Only a genuinely successful run delivers a gate: delivery selects closed runs in agent-run phase
`done`, which agent-run records solely for a non-blank result. A run that exits 0 with a **blank**
result is recorded `failed` by agent-run (see the README blank-result paragraph), so it never
completes the gate. A silently dead worker must not satisfy a subagent gate. Instead the failed run
keeps its `serves` edge to the gate. The ready gate is discoverable via the stall predicate and
`stalled-gates` query below and, as a delegated run, via `agent-failures`.

A coordinator recovers such a gate with `agent retry <run-id>` on the failed or exhausted
gate-serving run. Retry marks the dead run superseded and spawns a successor that inherits the run's
`serves` edge, dependency edges, and run metadata. The subagent executor then observes the successor
as the gate's current serving run and delivers it when it succeeds. For spawn-side failures, clear
the gate's `gate/error` attribute after fixing the bad request. A CLI clearing idiom is `--attr
gate/error=` (empty string, since there is no attribute-delete flag); the next scan can spawn the
gate's first serving run.

If a gate is closed or routed away while its agent-run run is in flight, the completed run is
stamped `gate/delivered "gate-closed"` and the result remains on the run strand for audit. If the
gate is still active but no longer ready when the run finishes, the run is stamped
`gate/delivery-blocked` and delivery retries once the gate is ready again.

Crash-window caveat: spawn idempotency re-adopts runs through the durable `serves` edge. A run that
failed before its transaction wrote that edge is orphaned; a fresh run may spawn beside it, and the
failed run stays visible for audit.

## Coordination attention

`install!` calls `(workflow/register-executor! :subagent gate-stalled?)`, registering itself as the
executor for every gate whose `waiter` is `subagent`. Because an executor is registered, `await!`
stays silent (`:waiting`) on a healthy subagent gate instead of surfacing it immediately as `:gate`.
`gate-stalled?` reports a ready subagent gate as stalled (`:reason :stalled`) when the gate has
`gate/error` or its current serving run is in agent-run phase `failed` or `exhausted`; otherwise it
reports nothing. No wall-clock hang policy is applied. A superseded run is not itself a stall
because `agent retry` moves service to the successor.

The spool also registers `stalled-gates` and `blocked-deliveries` named queries for coordinator
inspection. `stalled-gates` is the SQL-side mirror of the stall predicate: it returns active
subagent gates that either carry `gate/error` or have an incoming `serves` edge from a run in phase
`failed` or `exhausted`. The same `serves`+lineage rule drives `gate-stalled?`: superseded runs are
outside the dead-phase set, and their successors inherit the `serves` edge. After `agent retry`, the
gate stops surfacing as soon as the successor is in flight. `blocked-deliveries` returns finished
runs parked on `gate/delivery-blocked`.

## See also

- [`skein.spools.workflow`](../workflow.md) — workflow gates and runtime API.
- [`skein.spools.agent-run`](../agent-run/README.md) — agent-run run lifecycle and harness registry.
- ``test/skein/executors/subagent_test.clj`` — executable contract tests.
