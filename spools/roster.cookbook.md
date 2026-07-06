# Skein Roster Spool — Cookbook

Composition recipes for `skein.spools.roster`: how to keep "what work is active
in this weaver?" honest — tracking a long-running job, waiting for a scope to go
quiet before you land, and letting workflow/devflow roots track themselves — and
*why* each shape is the right one.

This is the **how/why** half of the roster docs. The other two are:

- [`roster.md`](./roster.md) — the **contract**: the `roster/*` attribute
  vocabulary, staleness and await semantics, the automatic workflow/devflow
  integration, and the CLI op. Read it for what the spool promises.
- [`roster.api.md`](./roster.api.md) — the **generated reference**: every public
  fn's signature and docstring, produced from source.

Division of truth: the attribute table and fn signatures live in the contract
and generated API doc; narrative and composition live here. This cookbook never
restates the `roster/*` table or a signature — it links to them.

The runtime-touching helpers used here — `track!`, `heartbeat!`, `finish!`,
`roster`, and `await-quiet!` — take `runtime` as their first argument and never
resolve the ambient runtime themselves, so these recipes assume you already hold
one —
`(require '[skein.spools.roster :as roster] '[skein.api.current.alpha :as current])`
and `(def rt (current/runtime))` in trusted config or a live weaver REPL. (The
discovery helpers `about` and `prime` take no runtime.) From the shell the same
surface is `strand roster …`.

## How to read a recipe

Every recipe has the same four parts:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which helpers combine, and how.
3. **Snippet** — a runnable form or the equivalent shell.
4. **Why this shape** — the reasoning, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the spool's own
contract, its test suite, or the feature spec.

---

## Recipe: Track a long-running job the graph can't see

**Situation.** You're running something the weaver can't infer from graph
activity — an AFK loop, an ad hoc build session, an agent whose root carries no
feature slug or owner. You want it to show up in "what's active right now",
heartbeat as it makes progress, and drop out of the active surface when it's
done, without inventing a whole tracking scheme.

**Composition.** The explicit lifecycle is three calls: `track!` once at the
start (it creates a new entry or restamps an existing strand in place),
`heartbeat!` once per visible unit of progress, and `finish!` at the end with a
final status and optional result. Nothing here locks or gates — it only makes
the work *visible*.

```clojure
;; start: one entry, feature + owner required, optional branch/engine/body
(def entry (:id (roster/track! rt {:feature "roster-spool"
                                   :owner   "afk-loop"
                                   :engine  "afk"
                                   :branch  "roster-spool"})))

;; progress: refresh the heartbeat so the entry stays fresh (non-stale)
(roster/heartbeat! rt entry)

;; look: active entries in scope, each annotated with :stale? and :age-ms
(roster/roster rt {:feature "roster-spool"})
;; => [{:strand {…} :stale? false :age-ms 12}]

;; end: close it with a final status; it stays inspectable via show, drops from active
(roster/finish! rt entry {:status "finished" :result "done"})
```

```sh
strand roster track --feature roster-spool --owner afk-loop --engine afk --branch roster-spool
strand roster heartbeat <entry-id>
strand roster finish <entry-id> --status finished --result done
```

**Why this shape.**

- **Explicit tracking is for work the graph can't infer.** Workflow/devflow
  roots track themselves (next recipe); this trio is exactly for the engines
  that *don't* mutate the graph as they run, so their progress would otherwise be
  invisible. Call `track!` once at the start of a unit of work you want seen.
- **Heartbeat is progress, not a keep-alive daemon.** `heartbeat!` refreshes
  `roster/heartbeat-at`; you call it at genuine progress points, and staleness
  is derived from that timestamp. There's no background pinger to manage.
- **Finish keeps history, not clutter.** `finish!` records the final status and
  closes the strand, so the entry stays readable through ordinary `show`/graph
  tools but leaves the active roster surface. Stale and finished work is never
  auto-burned — cleanup stays a deliberate act.
- **The delta-write is race-safe by construction.** `heartbeat!` and `finish!`
  send only their own attribute delta, so a heartbeat landing just after a
  concurrent `finish!` can only touch the timestamp on an already-closed entry —
  it can never resurrect `roster/status` to active.

Honest source: the AFK example in [`roster.md`](./roster.md) and the lifecycle
behaviour pinned by `track!-creates-a-new-active-entry`,
`heartbeat!-updates-heartbeat-at-on-an-active-entry`,
`finish!-closes-the-entry-with-status-result-and-finished-at`, and
`heartbeat-vs-finish-cannot-produce-contradictory-entries` in
[`test/skein/roster_test.clj`](../test/skein/roster_test.clj).

---

## Recipe: `await-quiet!` as a fan-in barrier before you land

**Situation.** A coordinator has fanned work out across a feature and wants to
wait until all of it has gone quiet before landing — but it must not block
forever on a run that has silently stalled, and it must not confuse "nothing
active" with "something is wedged".

**Composition.** `await-quiet!` blocks on a scope (`:feature`, `:branch`, or
`:worktree`) and returns a tagged reason: `:quiet` when the scope has no active
entries, `:stale` the moment any selected entry crosses the stale threshold, or
`:timeout` at the deadline. The `:stale` short-circuit is the safety valve — it
fires *ahead* of declaring quiet, so a wedged run surfaces instead of hiding.

```clojure
(let [{:keys [reason entries]}
      (roster/await-quiet! rt {:feature "roster-spool"
                               :timeout-ms 60000
                               :stale-after-ms (* 5 60 1000)})]
  (case reason
    :quiet   (land! "roster-spool")                 ; all active work finished
    :stale   (escalate! entries)                    ; something wedged — do NOT land
    :timeout (recheck-later entries)))
```

```sh
strand roster await-quiet --feature roster-spool --timeout-ms 60000
```

**Why this shape.**

- **Quiet is a decision point, not a guarantee.** `await-quiet!` reports the
  state of the scope at the moment it returns; it enforces no lock or exclusivity
  (roster never does). The coordinator decides what quiet *means* — here, "safe
  to land" — which is why the return is a reason to branch on, not a boolean.
- **`:stale` outranks `:quiet` on purpose.** A stopped heartbeat needs attention
  before a coordinator lands, so staleness short-circuits ahead of any quiet
  report. Landing on a silently stalled fan-out is exactly the bug this ordering
  prevents.
- **Scopes compose with the same vocabulary as tracking.** You await the same
  `:feature`/`:branch`/`:worktree` you tracked under, so the barrier lines up
  with the work without a second bookkeeping model.
- **`await-quiet!` returns control at the deadline.** With no quiet and no stale
  entry, it returns `:timeout` (default thirty minutes, matching `workflow/await!`)
  rather than blocking indefinitely, so a coordinator always gets control back.

Honest source: the await semantics in [`roster.md`](./roster.md) (Staleness and
awaiting; `SPEC-RosterSpool-001.C5`), pinned by
`await-quiet!-returns-quiet-immediately-with-no-active-entries-in-scope`,
`await-quiet!-short-circuits-on-stale-entries-without-waiting-for-timeout`,
`await-quiet!-returns-quiet-once-the-active-entry-finishes`, and
`await-quiet!-times-out-when-scope-stays-active-and-fresh` in
[`test/skein/roster_test.clj`](../test/skein/roster_test.clj).

---

## Recipe: Let workflow and devflow roots track themselves

**Situation.** You're driving a workflow or devflow run and don't want to
hand-track it — but you *do* want it in the active roster, staying fresh as work
proceeds beneath it. And you need to know the one case where the roster stays
silent and you must still `track!` yourself.

**Composition.** `install!` registers one async graph-integration handler. For
every strand add/update it either **auto-stamps** a sufficient, unstamped graph
root into a roster entry, or **refreshes the heartbeat** of the active entry that
roots the touched strand's `parent-of` ancestry. You compose *nothing* — you
just make the root sufficient by giving it a discoverable feature slug and owner.

A root is auto-stamped when it is active, is not workflow plumbing, is a graph
root, and carries both a feature slug and an owner (each resolved in a priority
order the contract lists — e.g. `workflow/run-id` or `devflow/feature` for the
slug, `owner` or `workflow/actor` for the owner):

```clojure
;; an active workflow/devflow root that already carries a slug + owner …
(repl/strand! "Feature: roster-spool"
              {"workflow/run-id" "roster-spool" "owner" "coordinator"
               "branch" "roster-spool"})
;; … becomes a roster entry on its own; work churning beneath it keeps it fresh.
(roster/roster rt {:feature "roster-spool"})   ; => [{:strand {…} :stale? false …}]

;; the negative case: a root missing a slug or owner is NEVER invented —
;; track! it explicitly (AFK recipe above).
```

**Why this shape.**

- **The roster meets the graph where it already is.** Workflow/devflow roots
  already carry a run-id/feature and an actor; the handler reads those *public*
  attributes and stamps the entry, so common flows need zero extra calls. Roots
  even carry their `branch`/`worktree` through, so branch-scoped `roster`/
  `await-quiet!` find auto-tracked work.
- **Heartbeats ride real activity.** Because any mutation under a tracked root
  refreshes its heartbeat, a genuinely progressing flow stays non-stale without a
  `heartbeat!` call — and a flow that goes silent goes stale, which is precisely
  the signal you want.
- **The coupling is one-directional and loop-safe.** Roster only *reads*
  workflow/devflow attributes — it never requires their namespaces — and it
  ignores its own bookkeeping writes so the async handler can't feed itself.
- **Silence on missing identity is a feature.** The spool never invents a
  feature or owner. A root lacking either is the explicit-`track!` case, so the
  boundary between "auto-tracked" and "you must track" is a property of the root,
  not a guess.

Honest source: the automatic workflow/devflow integration section of
[`roster.md`](./roster.md) (`SPEC-RosterSpool-001.C11–C15`), pinned by
`auto-stamps-active-non-plumbing-roots-carrying-feature-and-owner`,
`auto-stamp-carries-branch-and-worktree-into-the-roster-entry`,
`graph-mutations-refresh-the-tracked-root-heartbeat`, and
`does-not-auto-stamp-roots-missing-identity-or-marked-plumbing` in
[`test/skein/roster_test.clj`](../test/skein/roster_test.clj).

---

## See also

- [`roster.md`](./roster.md) — the contract: the `roster/*` vocabulary,
  staleness/await semantics, workflow/devflow integration, and the CLI op.
- [`roster.api.md`](./roster.api.md) — generated signatures and docstrings.
- [`loom.md`](./loom.md) — read-only branch/flow projections that read the same
  active work from a different angle (rendering rather than awaiting).
- [`workflow.cookbook.md`](./workflow.cookbook.md) — the workflow runs whose
  roots the roster auto-stamps.
