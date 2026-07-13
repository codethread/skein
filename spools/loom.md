# Skein Loom Spool

> This is the **contract** doc: what each projection returns, the `branch-views`
> options, and the `flow-status` payload shape. Its two companions are
> [`loom.cookbook.md`](./loom.cookbook.md) — worked composition recipes (how/why
> you turn these projections into a dashboard, a live feed, or a gate chain) —
> and [`loom.api.md`](./loom.api.md) — generated fn signatures and docstrings.
> Reach for the cookbook when you want a runnable pattern, the API doc when you
> want an exact arity, and this doc for what the spool promises.

## 1. Overview

`skein.spools.loom` is a read-only reference spool that projects the active strand graph into the shapes consumers actually render: parent-of work DAGs with their depends-on edges, per-root branch progress views joined to a ready frontier, and workflow flow-status (history, frontier, subagent gates, delegated runs, and stalls) with a Mermaid gate chain. It mutates nothing.

The name follows the textile metaphor: a loom holds the whole warp under tension and reveals the developing cloth. This spool holds the active strand graph and reveals its work structure.

These projections were previously hand-rolled inside repo `.skein/config.clj`. They are generic graph vocabulary that other code builds on, so they ship here on the classpath while a repo keeps only its own policy — which attribute names a branch, which query feeds the ready frontier, and the op registration glue. `.skein/config.clj`'s `current-dags`, `branches`, and `flow-status` ops are thin wrappers over this spool.

Every function is **read-only** and composes the public `skein.api.graph.alpha` / `skein.api.weaver.alpha` / `skein.spools.workflow` surfaces. Because it reads edges via `graph/subgraph`/`weaver/list`, it requires an **in-process weaver runtime** — trusted startup config, the weaver's own nREPL, or an in-process test runtime. Callers pass the runtime explicitly.

## 2. Usage

```clojure
(require '[skein.spools.loom :as loom]
         '[skein.api.current.alpha :as current])

(def rt (current/runtime))

;; active parent-of work DAGs with active depends-on edges
(loom/work-dags rt)

;; per-branch progress views; the caller names the branch attribute and
;; supplies an inline query expression whose ready frontier joins each root
(loom/branch-views rt {:branch-attr :branch
                       :ready-query [:= :state "active"]})

;; scope to one branch
(loom/branch-views rt {:ready-query [:= :state "active"] :branch "feat-x"})

;; workflow flow status join with a Mermaid gate chain
(loom/flow-status rt "my-feature")

;; just the gate-chain render helper
(loom/gate-chain-mermaid gates #{"ready-gate-id"})
```

## 3. Surface

| Fn | Behavior |
|---|---|
| `(summarize strand)` | Compact strand shape `{:id :title :state :attributes}`. |
| `(work-dags rt)` | `{:roots :dags}` — every active parent-of root with its hierarchy edges, internal depends-on edges, and compact strand rows. |
| `(branch-views rt opts)` | Vector of `{:branch :roots}` grouped by branch attribute, sorted by branch name. |
| `(flow-status rt run-id)` | Read-only workflow flow-status join (see below). |
| `(gate-chain-mermaid gates ready-ids)` | Dev-only Mermaid `flowchart LR` marking each gate `ready`/`stalled`/`closed`/its state. |
| `(install!)` | Installation metadata: function symbols and `:read-only true` for trusted registration by name. Registers no ops. |

`active-by-id`, `internal-active-edges`, `descendants-by-root`, and `root-view` are private graph-composition helpers used internally by `work-dags`/`branch-views`; they carry no external-consumer contract and are not part of this surface table.

`branch-views` options:

- `:branch-attr` — attribute key naming the branch (default `:branch`). A branch
  root is an active strand carrying this attribute that is not itself a parent-of
  child of another active strand.
- `:ready-query` — **required** inline query expression (vector or map, as
  accepted by `skein.api.weaver.alpha/ready`) whose ready frontier feeds each
  root view. A named query symbol/keyword is not resolved. Absent `:ready-query`
  fails loudly with `ex-info`.
- `:branch` — optional branch name; scopes the projection to one branch.

`branch-views` validates its `opts` map loudly: a non-map `opts`, an unknown key, a non-keyword `:branch-attr`, or a non-string `:branch` all fail with a contextual `ex-info`.

`flow-status` returns a JSON-compatible map with:

- `:run-id`, `:history`, `:frontier`, `:done` — from the workflow run.
- `:gates` — compact subagent-gate projections joined to their delegated agent-run runs, each
  with `:stalled?` and (when stalled) a `:stall/reason` of `"spawn-error"` or
  `"agent-failure"`.
- `:stalled-gates`, `:agent-failures` — compact summaries scoped to **this run's
  own** gates and delegated runs, so records from other workflows never leak into
  an unrelated run's payload.
- `:dev/mermaid` — the gate chain rendered by `gate-chain-mermaid`.

## 4. See also

- [README.md](./README.md) — shipped spools index.
- [carder.md](./carder.md) — the sibling read-only graph spool (hygiene/triage).
- `test/skein/spools/loom_test.clj` — executable contract examples against a real
  weaver runtime.
- [Authoring your own spool code](../docs/reference.md#authoring-your-own-spool-code)
  — the loading/approval model for spools you write yourself.
