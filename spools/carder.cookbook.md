# Skein Carder Spool — Cookbook

Composition recipes for `skein.spools.carder`: how to build a periodic graph hygiene loop out of the read-only reports, and *why* each shape is the right one.

This is the **how/why** half of the carder docs. The other two halves are:

- [`carder.md`](./carder.md) — the **contract**: what each report means, the
  runtime it needs, the exclusion rules, and the option vocabulary. Read it for
  what the spool promises.
- [`carder.api.md`](./carder.api.md) — the **generated reference**: every public
  fn's signature, arities, and docstring, produced from the source.

Division of truth: signatures, row shapes, and the option table live in the contract and the generated API doc; the recipes here never restate them — they link. When a recipe needs an exact option, follow the link to [carder.md §3](./carder.md#3-surface).

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which reports combine, and how you act on them.
3. **Snippet** — a complete, runnable form (assume `(require '[skein.spools.carder
   :as carder])`).
4. **Why this shape** — the reasoning: why this report, what its exclusions buy
   you, and what pairs with it.

Each recipe cites the honest source it was distilled from — this repo's config, the shipped ops, or the test suite — so you can read the load-bearing version.

Carder mutates nothing; every recipe is a *read* that hands you rows to act on with other tools. The reports that walk edges (`orphans`, `blocked-by-failure`, `report`) need an in-process weaver runtime — trusted config, the weaver's REPL, or a test runtime — and fail loudly without one ([carder.md §1](./carder.md#1-overview)).

---

## Recipe: Triage stale active work

**Situation.** A long-lived weaver accumulates active strands that stopped moving — a spike someone walked away from, a task whose owner never closed it. You want a weekly "what has gone quiet?" pass so nothing rots silently in the active set.

**Composition.** `stale` with a `:days` threshold. Each row is a compact strand summary plus `:days-stale`, already sorted oldest-first, so the top of the list is your triage queue.

```clojure
(require '[skein.spools.carder :as carder])

;; Anything active and untouched for two weeks (the default threshold).
(carder/stale)

;; A tighter weekly sweep.
(carder/stale {:days 7})
;; => [{:id "..." :title "Forgotten spike" :state "active" :days-stale 186 ...}
;;     {:id "..." :title "Half-done doc"   :state "active" :days-stale 21  ...}]
```

**Why this shape.**

- **`stale` is the one report that needs no edge access.** It composes only the
  public strand-listing surface ([carder.md §1](./carder.md#1-overview)), so it
  is the cheapest hygiene check and the natural first pass.
- **Plumbing is excluded by default, so the list is human work.** Workflow
  molecule/procedure/digest strands and shuttle run records drop out unless you
  pass `:include-plumbing? true` ([carder.md §3](./carder.md#3-surface)) — a
  stale-work list you can act on, not one cluttered with bookkeeping that is
  *supposed* to sit quietly.
- **`:days-stale` on every row lets you rank, not just filter.** Because rows
  carry the age and arrive oldest-first, you triage from the top and stop when
  the ages stop alarming you.

Honest source: `stale-detects-doctored-updated-at-and-validates-options` in [`test/skein/spools/carder_test.clj`](../test/skein/spools/carder_test.clj), which back-dates a strand's `updated_at`, asserts it surfaces with a positive `:days-stale`, and asserts a fresh strand does not.

---

## Recipe: Orphan sweep before archiving a feature

**Situation.** You're about to archive a feature or tidy a branch, and you want to catch active strands that fell out of the graph — created, never wired to a parent or dependency, now floating. But your board legitimately has some edge-free roots (a fresh kanban card has no children yet), and you don't want those flagged every sweep.

**Composition.** `orphans` returns active strands with zero edges in any relation and no `workflow/*` attributes. Wrap it with a small predicate that removes the roots you *expect* to be edge-free, so the report shows only genuine strays — exactly what this repo's `carder-report` op does for its kanban card roots.

```clojure
(require '[skein.spools.carder :as carder])

;; Raw sweep: every active strand with no incident edges (workflow carriers excluded).
(carder/orphans)
;; => [{:id "..." :title "Loose note" :state "active" ...} ...]

;; Suppress the roots you know are legitimately edge-free before presenting the
;; list. The predicate below is a REPO-LOCAL POLICY EXAMPLE, not spool contract:
;; it encodes THIS repo's rule that unstarted kanban cards are expected orphans
;; (adapted from .skein/config.clj). Write your own predicate for whatever roots
;; your board treats as legitimately edge-free.
(defn- expected-orphan? [row]
  (and (= "true" (get-in row [:attributes :kanban/card]))
       (contains? #{"pending" "refinement"} (get-in row [:attributes :kanban/status]))))

(remove expected-orphan? (carder/orphans))
```

**Why this shape.**

- **Workflow carriers are already excluded, so an orphan is a real stray.** A
  strand carrying any `workflow/*` attribute is never reported even with no edges
  ([carder.md §3](./carder.md#3-surface)) — those are engine bookkeeping, not
  lost work. What remains is a strand that a human or agent created and forgot to
  connect.
- **The suppression belongs to *your* config, not the spool.** Carder reports the
  raw structural fact; which edge-free roots are expected is a workspace
  convention (an unstarted card, a coordination root you stamp yourself). Keeping
  the suppression in your op wrapper means the spool stays a pure report and your
  known-good exceptions stay declarative and reviewable.
- **Run it before archiving, not after.** An orphan sweep is most valuable at a
  boundary — closing a feature — because that is when a floating strand is about
  to be lost from view for good.

Honest source: `suppress-expected-carder-orphans` / `kanban-card-orphan?` and the `carder-report-op` wrapper in this repo's [`.skein/config.clj`](../.skein/config.clj), which strips unstarted kanban card roots from the orphan section; and `orphans-require-no-edges-and-no-workflow-attributes` in [`test/skein/spools/carder_test.clj`](../test/skein/spools/carder_test.clj).

---

## Recipe: Find work blocked behind a failed run, then retry it

**Situation.** A delegated agent run failed and stayed active (failures are loud and visible on purpose). Downstream strands that `depends-on` it are now stuck — ready-looking but still blocked on the failed run. You want to find that stuck work and route it back into recovery.

**Composition.** `blocked-by-failure` returns each active strand that has an active `depends-on` blocker whose `shuttle/phase` is `"failed"` or `"exhausted"`, with a `:blockers` vector carrying each blocker's id, phase, and `shuttle/error`. Feed those blocker ids to `strand agent retry`, the recovery verb.

```clojure
(require '[skein.spools.carder :as carder])

(carder/blocked-by-failure)
;; => [{:id "downstream-doc"
;;      :title "Downstream doc"
;;      :blockers [{:id "impl-run" :shuttle/phase "failed" :shuttle/error "boom" ...}]}]
```

Then hand each failed blocker to the agents spool's recovery verb (shell):

```sh
# from the :blockers of each row — supersede the dead run and respawn
strand agent retry impl-run
```

**Why this shape.**

- **Readiness alone can't see this.** A blocked strand's `depends-on` edge points
  at a strand that is still *active* (a failed run stays active until retried or
  killed — [agents README §3](./agents/README.md)), so the ready
  frontier treats it as legitimately waiting. `blocked-by-failure` is what turns
  "waiting" into "waiting on a failure worth acting on".
- **The row already carries the reason.** Each blocker brings its
  `shuttle/error`, so you triage *why* it failed — a bad prompt, a flaky gate —
  before deciding between `agent retry` (respawn) and fixing the task body first.
- **Carder finds; the agents spool fixes.** Carder stays read-only and names the
  stuck work; `agent retry` supersedes the dead run and respawns from the task's
  current body ([agents README §3](./agents/README.md)). The two compose:
  one reports, the other mutates.

Honest source: `blocked-by-failure-reports-failed-blocker-details` in [`test/skein/spools/carder_test.clj`](../test/skein/spools/carder_test.clj), which wires a strand to `failed` and `exhausted` blockers and asserts the row carries both blocker ids and the `shuttle/error`; the retry contract is in the [agents spool README](./agents/README.md).

---

## Recipe: Wrap the hygiene report in your own op or scheduled sweep

**Situation.** A one-off `stale` or `orphans` call is easy to forget. You want the whole hygiene picture — stale, orphaned, failure-blocked — available on demand as a first-class command, or fired on a schedule so drift surfaces without anyone remembering to look.

**Composition.** `report` rolls all three sections into one JSON-compatible map, each with a `:count` and `:rows`. Wrap that one call behind whatever surface your workspace already uses — a registered op for on-demand runs, or a scheduled job for a standing sweep — and keep any policy in the wrapper, not the spool.

```clojure
(require '[skein.spools.carder :as carder])

(carder/report {:days 7})
;; => {:opts {:days 7 :include-plumbing? false}
;;     :stale             {:count 2 :rows [...]}
;;     :orphans           {:count 1 :rows [...]}
;;     :blocked-by-failure {:count 1 :rows [...]}}
```

Registered as an op, the report becomes a command with typed flags. Keep the op body a thin unpack over `report` and put workspace policy — the default threshold, your own orphan suppression (the `expected-orphan?` predicate from the orphan recipe above) — in the wrapper:

```clojure
;; op body: a thin read-only wrapper over report, plus your own orphan policy
(defn hygiene-report-op [ctx]
  (let [{:keys [days include-plumbing]} (:op/args ctx)
        result (carder/report (cond-> {}
                                days (assoc :days days)
                                (some? include-plumbing) (assoc :include-plumbing? include-plumbing)))
        kept   (vec (remove expected-orphan? (get-in result [:orphans :rows])))]
    ;; keep :count and :rows in sync after filtering, matching report's shape
    (update result :orphans assoc :rows kept :count (count kept))))
```

A scheduled job is the same call on a timer: run `report` on an interval and raise a card (or post a note) whenever a section is non-empty. This repo wires exactly this shape — a read-only report op — in [`.skein/config.clj`](../.skein/config.clj); treat that as one worked example, not the required form.

**Why this shape.**

- **`report` is built for a wrapper.** It takes one options map, passes it to
  every section, and returns JSON-compatible data ([carder.md
  §3](./carder.md#3-surface)) — so a CLI op is a thin `(:op/args ctx)` unpack,
  and a scheduled job is one call whose result you post as a note or a card.
- **The op is where workspace policy lives.** The default threshold, the
  expected-orphan suppression, and whether to include plumbing are all *your
  workspace's* choices; keeping them in the wrapper leaves the spool a pure,
  reusable report.
- **On demand or on a timer, same call.** A coordinator runs the op when tidying;
  a scheduled job runs the identical `report` on an interval and raises a card
  when a section is non-empty. Pick the cadence; the report doesn't care who
  calls it.

Honest source: the `(report opts)` contract in [carder.md §3](./carder.md#3-surface), and this repo's own read-only report op in [`.skein/config.clj`](../.skein/config.clj) as one worked example of the wrapper shape.

---

## See also

- [`carder.md`](./carder.md) — the contract: each report's meaning, the
  in-process runtime requirement, the exclusion rules, and the option table.
- [`carder.api.md`](./carder.api.md) — generated signatures and docstrings for
  every public fn referenced above.
- [`selvage.cookbook.md`](./selvage.cookbook.md) — the companion hygiene spool:
  where Carder reports structural drift, Selvage lints attribute *values* against
  a declared vocabulary.
- [agents spool README](./agents/README.md) — `strand agent retry`, the recovery
  verb the failure-blocked recipe hands off to.
