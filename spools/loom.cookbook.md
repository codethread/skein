# Skein Loom Spool — Cookbook

Composition recipes for `skein.spools.loom`: how to turn its read-only projections into a branch dashboard, a live workflow feed, or a rendered gate chain, and *why* each shape is the right one.

This is the **how/why** half of the loom docs. The other two are:

- [`loom.md`](./loom.md) — the **contract**: what each projection returns, the
  `branch-views` options, and the `flow-status` payload shape. Read it for what
  the spool promises.
- [`loom.api.md`](./loom.api.md) — the **generated reference**: every public
  fn's signature and docstring, produced from source.

Division of truth: signatures and the payload/option tables live in the contract and generated API doc; narrative and composition live here. This cookbook never restates a signature — it links to them. When a recipe needs an exact arity, follow the link.

Loom mutates nothing. Its graph projections — `work-dags`, `branch-views`, and `flow-status` — resolve no ambient runtime and take the active runtime as their first argument, so these recipes assume you already hold one — `(require '[skein.spools.loom :as loom] '[skein.api.current.alpha :as current])` and `(def rt (current/runtime))` inside trusted config or a live weaver REPL. The pure helpers `summarize` and `gate-chain-mermaid` take plain data instead and need no runtime.

## How to read a recipe

Every recipe has the same four parts:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which projections combine, and how.
3. **Snippet** — a runnable form, or the shell/JSON a renderer consumes.
4. **Why this shape** — the reasoning, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the spool itself, its test suite, this repo's `.skein` config, or the shipped dashboard that already renders it.

---

## Recipe: A per-branch dashboard with repo-defined policy

**Situation.** You want a "what's happening on each feature branch" view: one row per branch, each showing its work root, the active work hanging beneath it, and which of that work is ready to pick up right now. But *your* repo decides what counts as a branch and what counts as ready — the projection shouldn't bake that in.

**Composition.** `branch-views` splits cleanly into **policy** (yours) and **projection** (loom's). You pass two policy knobs — `:branch-attr`, the attribute that names a branch, and `:ready-query`, an inline query whose ready frontier defines "pickable" — and loom groups the active branch-stamped roots, hangs each root's active `parent-of` descendants beneath it, and marks the ones on your ready frontier.

```clojure
;; the repo's own "ready work" definition: active work, minus shuttle run records
;; and the bookkeeping strands (workflow/role molecule / digest / procedure) that
;; would clutter a human's frontier.
;; Query forms are data: [:and ...] requires all clauses, [:or ...] any, [:= a b]
;; tests equality, [:missing [:attr k]] matches a strand lacking attribute k,
;; [:in [:attr k] vs] tests membership, and [:not p] negates it. Full DSL:
;; ../devflow/specs/repl-api.md, SPEC-003.C13a.
(def work-query
  [:and [:= :state "active"]
   [:or [:missing [:attr "shuttle/run"]]
    [:not [:= [:attr "shuttle/run"] "true"]]]
   [:or [:missing [:attr "workflow/role"]]
    [:not [:in [:attr "workflow/role"] ["molecule" "digest" "procedure"]]]]])

;; all branches at once …
(loom/branch-views rt {:branch-attr :branch :ready-query work-query})
;; => [{:branch "feat-x" :roots [{:root {...} :active_descendants [...] :ready [...]}]}
;;     {:branch "feat-y" :roots [...]}]

;; … or scoped to one, failing loudly if nothing is stamped with it.
(loom/branch-views rt {:branch-attr :branch :ready-query work-query :branch "feat-x"})
```

From the shell the same projection is one JSON call — the repo's `branches` op is a thin wrapper that supplies exactly the policy above:

```sh
strand branches            # every branch-stamped root, grouped
strand branches feat-x     # scoped to one branch
```

**Why this shape.**

- **Policy in, projection out.** Loom never guesses which attribute names a
  branch or which query means "ready". The repo's convention — one active work
  root per branch stamped `branch` (kanban `claim` does this), execution strands
  hung beneath it — is expressed entirely through the two options. Swap
  `:branch-attr` and the same projection serves a different convention with no
  change to loom.
- **`:ready-query` is required, and inline on purpose.** An absent
  `:ready-query` fails loudly rather than defaulting to a frontier that silently
  doesn't match your policy. It takes a query *expression*, not a named query —
  the projection stays self-contained and doesn't depend on what queries happen
  to be registered.
- **Branch roots are graph roots, not any stamped strand.** A `parent-of` child
  that also carries a branch attribute is *not* a second root; it stays a
  descendant of its parent. That is why a dashboard shows one row per feature,
  not one per strand that mentions the branch.

Honest source: this repo's `branches` op in [`.skein/config.clj`](../.skein/config.clj) (it passes `:branch-attr :branch` and the `work` query as `:ready-query`, then fails loudly on an unstamped branch), and the grouping/root rules exercised by `branch-views-group-stamped-roots-and-join-ready-frontier` in [`test/skein/spools/loom_test.clj`](../test/skein/spools/loom_test.clj).

---

## Recipe: The whole active work graph in one payload

**Situation.** The flat CLI query surface hands you strand *rows*. A graph view — an indented tree, a boxart DAG — needs the *edges* too: who parents whom, and which strands block which. You don't want to re-walk the graph in the renderer.

**Composition.** `work-dags` does the walk once and returns every active `parent-of` root already joined to its hierarchy edges, its internal `depends-on` edges, and compact strand rows — one JSON-compatible `{:roots :dags}` structure a renderer can lay out directly.

```clojure
(loom/work-dags rt)
;; => {:roots [root-id …]
;;     :dags  [{:root {:id … :title … :state … :attributes …}
;;              :strands [{:id …} …]              ; root + active descendants
;;              :parent_of_edges  [{:from_strand_id … :to_strand_id …} …]
;;              :depends_on_edges [{:from_strand_id … :to_strand_id …} …]}
;;             …]}
```

```sh
strand current-dags        # the same projection, as JSON, for a renderer or a human
```

**Why this shape.**

- **Join once, render many.** The flat query is the right primitive for "list
  strands matching X"; a DAG view needs the edge sets alongside the rows.
  `work-dags` pays the subgraph walk a single time and returns both, so every
  consumer renders from one payload instead of re-deriving structure.
- **Edges are kept internal to the projection.** Subgraph expansion walks
  outward to external blockers and parents; loom filters every edge back down to
  endpoints that appear in the projection's own strand set. A renderer never has
  to guard against an edge that points at a strand it wasn't given.
- **Compact rows, stable shape.** Each strand is summarized to `{:id :title
  :state :attributes}` — enough to label a node, small enough to poll. The same
  `summarize` shape appears across loom's projections, so a renderer's node
  formatter is written once.

Honest source: `work-dags` and its `{:roots :dags}` contract in [`loom.clj`](./src/skein/spools/loom.clj), the repo's `current-dags` op in [`.skein/config.clj`](../.skein/config.clj), and `work-dags-projects-parent-of-roots-with-dependency-edges` in [`test/skein/spools/loom_test.clj`](../test/skein/spools/loom_test.clj).

---

## Recipe: `flow-status` as a live TUI's data feed

**Situation.** You're rendering a dashboard row per workflow run: its actionable frontier, whether any delegated agent failed, whether any gate stalled. You poll it on a timer. When the status call itself fails for a live run, the row must go *loud* — never show a healthy-looking empty frontier that hides a real problem.

**Composition.** `flow-status` is the single read-only join a renderer needs: it returns the run's `:history`, `:frontier`, and `:done`, plus `:gates` joined to their delegated runs, and `:stalled-gates` / `:agent-failures` scoped to *this run's own* work. The renderer polls it, counts the failure summaries to pick a row colour, and treats an exception from the call as its own red state.

```ts
// one active run → one enriched row (polled on a timer)
let status;
try {
  status = await strandJson(["flow-status", feature]);
} catch (e) {
  // loud: a broken flow-status on a live run is a red "error" row carrying the
  // message, never a healthy-looking zero frontier.
  return { ...base, stateLabel: "error", frontier: [], flowError: msg(e) };
}
const failCount = (status["agent-failures"] ?? []).length;
const stalled = (status["stalled-gates"] ?? []).length;
const stateLabel = status.done ? "done"
                 : failCount > 0 || stalled > 0 ? "attention"
                 : "active";
```

**Why this shape.**

- **One join, not five polls.** History, frontier, done-ness, gate/run state,
  and stalls arrive together in one payload, so a renderer makes a single call
  per run per tick instead of stitching the workflow, shuttle, and treadle
  surfaces itself.
- **Failures are scoped to the run.** `:stalled-gates` and `:agent-failures`
  cover only this run's gates and the runs they delegated — records from other
  workflows never leak in. A per-run row can trust the counts it renders.
- **Read-only means safe to poll hard.** `flow-status` mutates no workflow,
  shuttle, or treadle state, so a dashboard can refresh it every couple of
  seconds with no coordination risk — the reason the shuttle dashboard leans on
  it as its devflow feed.
- **The error path is a first-class state.** Because the enrichment call *can*
  fail (an unknown run-id throws), the honest renderer catches it into a visible
  "error" row. A zero frontier and a failed call must not look the same.

Honest source: the shuttle dashboard's devflow tab [`scripts/shuttle-dash/tabs/devflow.tsx`](../scripts/shuttle-dash/tabs/devflow.tsx) (`fetchDevflow` enriches each active run via `flow-status`, derives the error/attention/active/done state, and renders a loud error row on failure), and the `flow-status` payload contract in [`loom.md`](./loom.md).

---

## Recipe: The gate chain as one shared render helper

**Situation.** Several places want to draw the same picture — a run's subagent gates as a left-to-right chain, each marked ready, stalled, closed, or still open. You do not want the marker logic (which colour, which label) to drift between a Mermaid render and a boxart one.

**Composition.** `gate-chain-mermaid` is the *single* place that turns compact gate projections plus a ready-id set into node/marker/link text. `flow-status` already calls it and embeds the result as `:dev/mermaid`, so a consumer either lifts that field or calls the helper directly with its own gate list.

```clojure
;; flow-status embeds the chain for you …
(:dev/mermaid (loom/flow-status rt "my-feature"))

;; … or render any gate list yourself; the marker precedence is fixed:
;; stalled? > ready (id ∈ ready-ids) > closed > the gate's own state.
(loom/gate-chain-mermaid
  [{:id "g0" :title "Alpha" :state "active" :stalled? true}
   {:id "g1" :title "Beta"  :state "active"}
   {:id "g2" :title "Gamma" :state "closed"}]
  #{"g1"})
;; => "flowchart LR
;;     G0[\"Alpha (stalled)\"]
;;     G1[\"Beta (ready)\"]
;;     G2[\"Gamma (closed)\"]
;;     G0 --> G1
;;     G1 --> G2"
```

**Why this shape.**

- **One helper, identical markers everywhere.** Any op or renderer reusing
  `gate-chain-mermaid` gets the same marker precedence and node/link text, so a
  gate that reads "stalled" in one view can never read "ready" in another.
- **Stalled outranks ready outranks closed.** The precedence is deliberate: a
  stalled gate is the thing a human must see first, even if it is technically
  still on the ready frontier, and a closed gate that isn't stalled reads as
  done. Encoding that order in the helper keeps every view honest about
  priority.
- **`:dev/mermaid` is a convenience, not a contract boundary.** It rides in the
  `flow-status` payload for quick rendering, but the helper is public precisely
  so a consumer that wants boxart, an SVG, or a different layout can feed it the
  same `:gates` and ready-ids rather than re-implementing the marker rules.

Honest source: `gate-chain-mermaid` and its use inside `flow-status` (`:dev/mermaid`) in [`loom.clj`](./src/skein/spools/loom.clj), with the exact marker/precedence output pinned by `gate-chain-mermaid-marks-ready-stalled-and-closed` in [`test/skein/spools/loom_test.clj`](../test/skein/spools/loom_test.clj).

---

## See also

- [`loom.md`](./loom.md) — the contract: projection shapes, `branch-views`
  options, and the full `flow-status` payload.
- [`loom.api.md`](./loom.api.md) — generated signatures and docstrings.
- [`carder.md`](./carder.md) — the sibling read-only graph spool, for hygiene
  and triage rather than rendering.
- [`workflow.cookbook.md`](./workflow.cookbook.md) — the workflows whose runs
  `flow-status` projects; gates and checkpoints are defined there.
