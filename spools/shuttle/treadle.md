# Skein Treadle Spool

## Overview

`skein.spools.treadle` is the shuttle-backed adapter for workflow gates whose waiter is `subagent`. It watches ready workflow gates, spawns shuttle runs, and completes the gate with the run result when the run succeeds.

The workflow engine remains forge/tool agnostic: workflow authors declare an ordinary `(workflow/gate ... :subagent ...)` with attributes. Shuttle remains an agent-run engine with no workflow concepts. Treadle is the small bridge that knows both vocabularies.

## Loading

Load shuttle first, then treadle from the same approved local-root spool:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))
(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :shuttle
  {:ns 'skein.spools.shuttle
   :spools ['skein.spools/shuttle]
   :call 'skein.spools.shuttle/install!
   :required? true})
(runtime-alpha/use! runtime :treadle
  {:ns 'skein.spools.treadle
   :spools ['skein.spools/shuttle]
   :call 'skein.spools.treadle/install!
   :required? true})
```

`install!` fails loudly unless `:shuttle/engine` is already installed.

Install treadle **after** any startup config that registers harness aliases:
`install!` runs an initial gate scan, so an alias a durable ready gate names
(e.g. `pi-main`) must already be registered or that gate is stamped
`treadle/error` on every cold start.

Gate scans serialize on a runtime-owned monitor: independent weaver runtimes
in one JVM scan independently and never block each other.

## Gate request attributes

| Attribute | Required | Meaning |
|---|---|---|
| `workflow/gate` = `"subagent"` | yes | Marks a ready workflow gate for treadle fulfillment. Other waiters are ignored. |
| `shuttle/harness` | yes | Harness or alias name passed to `shuttle/spawn-run!`. Missing or invalid values stamp `treadle/error` on the gate. |
| `shuttle/prompt` | no | Run prompt. If absent, treadle derives one from `workflow/instruction`, `description`, then title. If nothing non-blank exists, it stamps `treadle/error`. |
| `shuttle/cwd` | no | Passed through as the run working directory. |
| `shuttle/max-attempts` | no | Shuttle crash-recovery attempt bound. Accepts an integer or an integer string (attributes may arrive JSON-stringified); anything else stamps `treadle/error`. |

## Treadle attributes

| Attribute | On | Meaning |
|---|---|---|
| `treadle/run` | gate step | Id of the delegated shuttle run. |
| `treadle/error` | gate step | Durable spawn-side failure detail; the gate is skipped until a coordinator clears it. |
| `treadle/gate` | run strand | Id of the gate step this run fulfills. |
| `treadle/run-id` | run strand | Workflow `run-id` owning the gate. |
| `treadle/delivered` | run strand | `"true"`, `"gate-closed"`, or `"error: …"`; presence means delivery is terminal for this run. |
| `treadle/delivery-blocked` | run strand | Written once when a finished run's gate is active but not currently ready (e.g. a dependency added after spawn). Non-terminal: the run stays undelivered and delivery retries when the gate becomes ready again. |

Treadle links each gate to its delegated run with a `delegates` annotation edge plus the `treadle/run`, `treadle/gate`, and `treadle/run-id` attributes. It must not use `parent-of` for this provenance because that structural relation would place the run inside the workflow subgraph and surface it as workflow `next-steps` work.

## Worked example

```clojure
(require '[skein.spools.workflow :as workflow])

(def build-widget
  (workflow/workflow
    "Build widget"
    (workflow/step :design "Design widget")
    (workflow/gate :implement "Implement widget" :subagent
                   :depends-on [:design]
                   :attributes {"shuttle/harness" "pi"
                                "shuttle/prompt" "Implement the widget per specs/widget.md"
                                "shuttle/cwd" "/path/to/worktree"})
    (workflow/step :review "Review implementation"
                   :depends-on [:implement])))

(workflow/start! "widget-1" build-widget {})
(workflow/complete! "widget-1")
;; The treadle observes :implement as a ready subagent gate, spawns a shuttle
;; run, then completes the gate with workflow/outcome-by = run id and
;; workflow/notes = shuttle/result when the run closes successfully.
```

## Failure and recovery

A failed shuttle run stays active and the workflow gate remains ready and stamped with `treadle/run`. A coordinator can reset the run for shuttle recovery or clear the gate's `treadle/run` attribute to request a fresh delegation. Gates stamped with `treadle/error` are skipped until the error attribute is cleared.

If a gate is closed or routed away while its shuttle run is in flight, the completed run is stamped `treadle/delivered "gate-closed"` and the result remains on the run strand for audit. If the gate is still active but no longer ready when the run finishes, the run is stamped `treadle/delivery-blocked` and delivery retries once the gate is ready again.

Crash-window caveat: spawn idempotency re-adopts only live prior runs. A run that failed before the weaver crash stamped its gate is orphaned (a fresh run spawns beside it), never re-adopted — the failed run stays visible for audit.

## Coordination attention

`treadle/install!` registers workflow stall predicate `:treadle`. It reports a ready subagent gate as stalled when the gate has `treadle/error` or its stamped `treadle/run` is in shuttle phase `failed`/`exhausted`; no wall-clock hang policy is applied.

The spool also registers `stalled-gates` and `blocked-deliveries` named queries for coordinator inspection.

## See also

- [`skein.spools.workflow`](../workflow.md) — workflow gates and runtime API.
- [`skein.spools.shuttle`](./README.md) — shuttle run lifecycle and harness registry.
- [`test/skein/treadle_test.clj`](../../test/skein/treadle_test.clj) — executable contract tests.
