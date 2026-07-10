# Skein Ephemeral Spool — Cookbook

Composition recipes for `skein.spools.ephemeral`: how to shape real temporary state out of the primitives, and *why* each shape is the right one.

This is the **how/why** half of the ephemeral docs. The other two halves are:

- [`ephemeral.md`](./ephemeral.md) — the **contract**: the attribute convention,
  the surface table, and the workspace-wide burn semantics.
- [`ephemeral.api.md`](./ephemeral.api.md) — the **generated reference**: every
  public fn's signature, arities, and docstring, produced from the source.

Division of truth: signatures and the attribute vocabulary live in the contract and the generated API doc; narrative and composition live here. This cookbook never restates a fn signature or the attribute table — it links to them.

The whole spool is one attribute convention: a strand carrying `ephemeral "true"` with a `parent-of` edge from its owner. That is small on purpose, so the recipes here are less about the four functions and more about *when* a throwaway strand earns its place, how to burn the right ones, and how to reach the same convention from the shell.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume the `require`s shown).
4. **Why this shape** — the reasoning: what the convention buys you, and what the
   alternative would cost.

---

## Recipe: Scratch strands under a task root, burned at the boundary

**Situation.** You are working a long task and want a few throwaway strands tracked *while* you work — a running list of failing cases, a scratch note about an API you're reverse-engineering — that should not outlive the task.

**Composition.** Create each scratch strand with `ephemeral!` under the task root, so it carries the `ephemeral "true"` attribute and a `parent-of` edge from the root. When the task is done, call `burn-ephemeral!` once to clear them.

```clojure
(require '[skein.repl :as repl]
         '[skein.spools.ephemeral :as ephemeral])

(def task (repl/strand! "Implement CSV importer"))

;; jot scratch strands as you go — each hangs off the task root
(ephemeral/ephemeral! (:id task) "Scratch: malformed-line cases" {:owner "agent"})
(ephemeral/ephemeral! (:id task) "Scratch: upstream API returns 204 on empty" {:owner "agent"})

(ephemeral/ephemeral-ids)
;; => ["s-..." "s-..."]

;; at the end of the run, clear the scratch in one call
(ephemeral/burn-ephemeral!)
;; => {:burned ["s-..." "s-..."] :count 2}
```

**Why this shape.**

- **The strand is ordinary; only the attribute is special.** Nothing in the
  engine treats an ephemeral strand differently — no auto-burn on parent close,
  no lifecycle hook. `burn-ephemeral!` is a call *you* make at the boundary of the
  work. That is the honest contract, and it is why the whole spool composes just
  the documented graph helpers (contract [§1](./ephemeral.md#1-overview)).
- **`parent-of` gives the scratch a home.** Hanging each scratch off the task
  root means it shows up under that root in a subgraph view and never floats as an
  orphan, so a hygiene pass (or a reviewer) can see what the run was chewing on.
- **One burn call, not N deletes.** Because the attribute marks the whole class,
  you clear the round's scratch with a single `burn-ephemeral!` instead of
  tracking and closing each id by hand.

Honest source: the `ephemeral!` / `ephemeral-ids` / `burn-ephemeral!` round-trip verified against a live weaver, mirroring the ephemeral case in [`test/skein/spools_test.clj`](../test/skein/spools_test.clj) (`ephemeral-spool-composes-public-helper-surfaces`).

---

## Recipe: Burn only one parent's scratch

**Situation.** Two tasks are running scratch strands at once and you want to clear just one task's when it finishes, without touching the other's.

**Composition.** `burn-ephemeral!` is workspace-wide by design, so scope it yourself: AND the reusable `ephemeral-query` together with a `parent-of` edge filter for the parent you're finishing, then burn exactly those ids.

```clojure
(require '[skein.repl :as repl]
         '[skein.spools.ephemeral :as ephemeral]
         '[skein.api.graph.alpha :as graph]
         '[skein.api.current.alpha :as current])

(def rt (current/runtime))
(def task-a (repl/strand! "Task A"))
(def task-b (repl/strand! "Task B"))
(ephemeral/ephemeral! (:id task-a) "A scratch")
(ephemeral/ephemeral! (:id task-b) "B scratch")

;; scope the shared query form to just task-a's children.
;; Query forms are data: [:and a b] requires both predicates, [:= :id x] tests a
;; field for equality, and [:edge/in "parent-of" q] matches strands on the
;; incoming side of a parent-of edge whose other endpoint satisfies q. Full DSL
;; in the REPL API spec (../devflow/specs/repl-api.md, SPEC-003.C13a).
(def a-scratch
  [:and ephemeral/ephemeral-query [:edge/in "parent-of" [:= :id (:id task-a)]]])

(def a-ids (graph/query-ids rt a-scratch {}))
;; => ["s-a1" ...]

(graph/burn-by-ids! rt a-ids)
;; => {:burned ["s-a1" ...] :count 1}

;; task-b's scratch is untouched
(ephemeral/ephemeral-ids)
;; => ["s-b1"]
```

**Why this shape.**

- **`ephemeral-query` is a value, so it composes.** It is exposed as data (not
  hidden behind a fn) precisely so you can AND it with any other predicate — a
  parent filter here, an owner filter elsewhere — and hand the result to
  `query-ids`. The contract calls this out as the way to get finer granularity
  than the workspace-wide burn (contract [§3](./ephemeral.md#3-surface)).
- **`[:edge/in "parent-of" ...]` reads "children of this parent".** The scratch
  strands are the targets of the parent's `parent-of` edges, so an edge-*in*
  filter on the parent id selects exactly the strands that hang beneath it.
- **You reuse the same burn primitive.** Scoping changes only *which ids* you
  compute; `burn-by-ids!` does the same closing work `burn-ephemeral!` does under
  the hood, so there is no second code path to trust.

Honest source: the scoped-burn composition verified against a live weaver, built from `ephemeral-query` plus the `graph/query-ids` / `graph/burn-by-ids!` helpers the spool itself composes.

---

## Recipe: Reach the same convention from the shell

**Situation.** You're driving from the CLI, not a REPL, and want the same throwaway-strand behaviour — create scratch under a task, list it, burn it — without dropping into Clojure.

**Composition.** The spool is a thin helper over an attribute convention, so the shipped `strand` ops reach the same shape directly: `add` with `--attr ephemeral=true`, an `update --edge parent-of:<id>` to home it, and a **named query** registered once so `list` and `burn` can find the class.

```sh
# create a scratch strand carrying the convention, and home it under the task
child=$(strand add "Scratch: failing case" --attr ephemeral=true --attr owner=agent)
cid=$(printf '%s' "$child" | jq -r .id)
strand update "$task_id" --edge parent-of:"$cid"
```

`strand list --query` takes a **registered** query name, so register `ephemeral-query` under a name once from trusted config or a live REPL:

```clojure
(require '[skein.spools.ephemeral :as ephemeral]
         '[skein.repl :as repl])
(repl/defquery! 'ephemeral ephemeral/ephemeral-query)
```

`strand burn` is id-only — it never takes a query. So from the shell you list via the named query and burn the ids it hands back; for a query-shaped bulk burn, drop into the REPL and call `graph/burn-by-ids!` on the ids `query-ids` computes.

```sh
strand list --query ephemeral        # the active ephemeral strands
strand burn "$cid"                   # burn one by id when you're done with it
```

**Why this shape.**

- **There is no `strand ephemeral` op, and there doesn't need to be.** The value
  is the attribute, not a command — `--attr ephemeral=true` on an ordinary `add`
  *is* an ephemeral strand. Registering the query name is what lets the generic
  `list`/`ready` ops select the class from the shell; `burn` still takes ids, so
  a query-shaped bulk burn belongs in the REPL via `graph/burn-by-ids!`.
- **Register the query once, in config.** `defquery!` names the reusable
  `ephemeral-query` value so every later `strand list --query ephemeral` resolves
  it; an inline query form is rejected by the CLI, which only dispatches
  registered names.
- **REPL and CLI are the same convention, not two systems.** A strand created
  with `ephemeral!` shows up in the CLI's named-query list, and one created with
  `--attr ephemeral=true` is burned by `burn-ephemeral!`. Pick whichever surface
  you're already in.

Honest source: verified end to end against a live weaver — `strand add --attr` / `update --edge`, `repl/defquery!`, then `strand list --query ephemeral` and `strand burn`.

---

## Recipe: Knowing when *not* to reach for ephemeral

**Situation.** You have a result in hand — a decision you made, a summary of what a run produced, a note for the next agent — and you're tempted to jot it as an ephemeral strand because it's quick.

**Composition.** Don't. A durable outcome is an ordinary strand (or a note on the strand it belongs to). Reserve `ephemeral!` for state that is worthless once the current run ends.

```clojure
;; WRONG: a decision you'll want tomorrow, marked to be burned tonight
(ephemeral/ephemeral! (:id task) "Decided: wide table, no joins")

;; RIGHT: a durable outcome is an ordinary strand or a note
(repl/strand! "Decision: wide table, no joins" {:owner "agent"})
;; or attach it as a note on the strand it explains (see bobbin.cookbook.md)
```

**Why this shape.**

- **The test is lifespan, not size.** "Small" and "quick" are not reasons to mark
  a strand ephemeral — *"worthless after this run"* is. A one-line decision that
  the next agent needs is durable; a fifty-line scratch dump that only helped you
  think is ephemeral.
- **`burn-ephemeral!` is indiscriminate.** It clears the whole active ephemeral
  class in one call. Anything you'd regret losing to that call does not belong in
  it — the burn is exactly why durable outcomes stay out.
- **Provenance wants a real strand.** Decisions, resume notes, and run summaries are
  what a reviewer or successor reads later; a bobbin context pack surfaces notes
  and ordinary descendants, not burned scratch (see
  [`bobbin.cookbook.md`](./bobbin.cookbook.md)).

Honest source: the workspace-wide burn semantics documented in the contract ([§3](./ephemeral.md#3-surface)) and the spool's stated scope as a throwaway convention, not a durable-state store ([§1](./ephemeral.md#1-overview)).

---

## See also

- [`ephemeral.md`](./ephemeral.md) — the contract: the attribute convention, the
  surface table, and the workspace-wide burn semantics.
- [`ephemeral.api.md`](./ephemeral.api.md) — generated signatures and docstrings
  for every public fn referenced above.
- [`bobbin.cookbook.md`](./bobbin.cookbook.md) — the companion assembler for
  *durable* context: packing a strand's notes, blockers, and provenance for a
  delegated agent or reviewer.
