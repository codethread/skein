# Skein Loom Spool

## 1. Overview

`skein.spools.loom` is a read-only reference spool that projects the active
strand graph into the shapes consumers actually render: parent-of work DAGs with
their depends-on edges, per-root branch progress views joined to a ready
frontier, and workflow flow-status (history, frontier, subagent gates, delegated
runs, and stalls) with a Mermaid gate chain. It mutates nothing.

The name follows the textile metaphor: a loom holds the whole warp under tension
and reveals the developing cloth. This spool holds the active strand graph and
reveals its work structure.

These projections were previously hand-rolled inside repo `.skein/config.clj`.
They are generic graph vocabulary that other code builds on, so they ship here on
the classpath while a repo keeps only its own policy — which attribute names a
branch, which query feeds the ready frontier, and the op registration glue.
`.skein/config.clj`'s `current-dags`, `branches`, and `flow-status` ops are thin
wrappers over this spool.

Every function is **read-only** and composes the public
`skein.api.weaver.alpha` / `skein.spools.workflow` surfaces. Because it reads
edges via `api/subgraph`/`api/list`, it requires an **in-process weaver
runtime** — trusted startup config, the weaver's own nREPL, or an in-process
test runtime. Callers pass the runtime explicitly.

## 2. Usage

```clojure
(require '[skein.spools.loom :as loom]
         '[skein.api.current.alpha :as current])

(def rt (current/runtime))

;; active parent-of work DAGs with active depends-on edges
(loom/work-dags rt)

;; per-branch progress views; the caller names the branch attribute and the
;; query whose ready frontier joins each root
(loom/branch-views rt {:branch-attr :branch
                       :ready-query 'work})

;; scope to one branch
(loom/branch-views rt {:ready-query 'work :branch "feat-x"})

;; workflow flow status join with a Mermaid gate chain
(loom/flow-status rt "my-feature")

;; just the gate-chain render helper
(loom/gate-chain-mermaid gates #{"ready-gate-id"})
```

## 3. Surface

| Fn | Behavior |
|---|---|
| `(active-by-id rt)` | Active strands keyed by id. |
| `(summarize strand)` | Compact strand shape `{:id :title :state :attributes}`. |
| `(internal-active-edges active-ids edges)` | Edges whose endpoints are both in `active-ids`, sorted for stable output. |
| `(descendants-by-root rt active-ids root-id)` | `{:root-id :strand-ids :parent-of}` for the active parent-of subgraph below `root-id` (root included). |
| `(work-dags rt)` | `{:roots :dags}` — every active parent-of root with its hierarchy edges, internal depends-on edges, and compact strand rows. |
| `(root-view rt active-ids ready-ids root)` | `{:root :active_descendants :ready}` for one root: its active parent-of descendants and the subset of root+descendants present in `ready-ids`. |
| `(branch-views rt opts)` | Vector of `{:branch :roots}` grouped by branch attribute, sorted by branch name. |
| `(flow-status rt run-id)` | Read-only workflow flow-status join (see below). |
| `(gate-chain-mermaid gates ready-ids)` | Dev-only Mermaid `flowchart LR` marking each gate `ready`/`stalled`/`closed`/its state. |
| `(install!)` | Installation metadata: function symbols and `:read-only true` for trusted registration by name. Registers no ops. |

`branch-views` options:

- `:branch-attr` — attribute key naming the branch (default `:branch`). A branch
  root is an active strand carrying this attribute that is not itself a parent-of
  child of another active strand.
- `:ready-query` — **required** named-or-inline query whose ready frontier feeds
  each root view. Absent `:ready-query` fails loudly with `ex-info`.
- `:branch` — optional branch name; scopes the projection to one branch.

`flow-status` returns a JSON-compatible map with:

- `:run-id`, `:history`, `:frontier`, `:done` — from the workflow run.
- `:gates` — compact subagent-gate projections joined to their treadle runs, each
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
- [Authoring your own spool code](../docs/skein.md#authoring-your-own-spool-code)
  — the loading/approval model for spools you write yourself.
