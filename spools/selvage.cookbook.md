# Skein Selvage Spool — Cookbook

Composition recipes for `skein.spools.selvage`: how to grow and enforce a workspace attribute vocabulary out of the primitives, and *why* each shape is the right one.

This is the **how/why** half of the selvage docs. The other two halves are:

- [`selvage.md`](./selvage.md) — the **contract**: what a checkset is, the
  supported check forms, watch-mode semantics, and the violation shape. Read it
  for what the spool promises.
- [`selvage.api.md`](./selvage.api.md) — the **generated reference**: every
  public fn's signature, arities, and docstring, produced from the source.

Division of truth: signatures and the check-form table live in the contract and the generated API doc; the recipes here never restate them — they link. When a recipe needs an exact check form, follow the link to [selvage.md §4](./selvage.md#4-checkset-checks).

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume `(require '[skein.repl :as
   repl] '[skein.spools.selvage :as selvage])`).
4. **Why this shape** — the reasoning: why these primitives, what the checkset
   buys you, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — a shipped attribute table, this repo's config, or the test suite — so you can read the load-bearing version.

---

## Recipe: Harden an attribute table you already wrote down

**Situation.** A spool's contract doc already lists the attributes its work carries and the values each one allows — this repo's kanban board has a `kanban/lane` set, a `kanban/priority` scale, a `kanban/type`. The table is a promise on paper, but nothing stops a strand from carrying `kanban/lane "in-progress"` when the lanes are `pending`/`claimed`/…. You want that table enforced, not just documented.

**Composition.** Translate each row of the attribute table into one `:enum` or `:type` check and register them as a single named checkset with `register-checkset!`. The checkset name mirrors the attribute namespace, so a reader knows exactly which convention it guards.

```clojure
(require '[skein.repl :as repl]
         '[skein.spools.selvage :as selvage])

;; One check per row of the kanban board's attribute table (kanban.md §"Card state").
(selvage/register-checkset! :kanban
  {:doc "Enforce the kanban board attribute table this repo committed to."
   :checks [{:attr "kanban/card"     :enum ["true"]}
            {:attr "kanban/type"     :enum ["feature" "epic"]}
            {:attr "kanban/lane"     :enum ["refinement" "pending" "claimed" "in_review"]}
            ;; kanban.md fixes the lanes but leaves the closed outcome
            ;; open-ended (done/abandoned/…). Pin the outcome set THIS board
            ;; actually uses rather than treating it as a spool-fixed enum, and
            ;; widen it when your board adopts a new one.
            {:attr "kanban/outcome"  :enum ["done" "abandoned"]}
            {:attr "kanban/priority" :enum ["p1" "p2" "p3" "p4"]}]})

(def good (repl/strand! "Ship docs" {:kanban/card "true" :kanban/type "feature"
                                     :kanban/lane "pending" :kanban/priority "p2"}))
(selvage/check (:id good))
;; => []

(def bad (repl/strand! "Typo'd card" {:kanban/card "true"
                                      :kanban/lane "in-progress"
                                      :kanban/priority "urgent"}))
(mapv #(select-keys % [:attr :check :value]) (selvage/check (:id bad)))
;; => [{:attr "kanban/lane" :check :enum :value "in-progress"}
;;     {:attr "kanban/priority" :check :enum :value "urgent"}]
```

**Why this shape.**

- **The checkset is data, so the doc and the lint stay one thing.** Each check
  is the machine-readable form of a table row. When the board grows a lane, you
  add one value to one vector — the same edit you would make to the contract
  table — instead of writing validation code.
- **Checks are conditional by design.** An `:enum`/`:type` check only fires when
  the attribute is *present* (contract [§4](./selvage.md#4-checkset-checks)),
  so a strand that carries no `kanban/*` attributes is never flagged. That is
  what lets one workspace hold many checksets without them colliding on
  unrelated strands.
- **Selvage never rejects the mutation.** Skein keeps attributes loose and
  userland-owned on purpose ([selvage.md §1](./selvage.md#1-overview)); a
  checkset is a convention you *check*, not a schema the engine enforces. That
  is the whole reason this lives in a spool and not in the core.

Honest source: the `kanban/*` attribute table in [`kanban.md`](./kanban.md), which fixes `kanban/lane` to `refinement`/`pending`/`claimed`/`in_review` on active cards and records a closed card's outcome under `kanban/outcome` (`done`, `abandoned`, …) — so the outcome values above are a workspace choice, not a spool-fixed enum — and the `agent-run` checkset in [selvage.md §2](./selvage.md#2-usage). The same shape hardens the `roster/*` table ([`roster.md`](./roster.md)) or the `workflow/role` enum ([`workflow.md`](./workflow.md#7-attribute-vocabulary)).

---

## Recipe: A pre-merge hygiene pass, scoped to the work that matters

**Situation.** Before you archive a feature or land a branch, you want to know that every strand it touched honours its checksets — but you don't want the async watcher running the whole time, and you don't want to hand-list strand ids. You want a one-shot sweep, and often you want it scoped to just this feature's work rather than the entire graph.

**Composition.** Call `check-all` with no arguments for a full sweep, or hand it a predicate DSL query form to scope the sweep to a slice of active work. It returns the flat violation vector across every matched strand.

```clojure
(require '[skein.spools.selvage :as selvage])

;; Full pre-merge sweep across all active strands.
(selvage/check-all)
;; => [ ... every violation in the active graph ... ]

;; Scoped: only the strands owned by this agent, or only kanban cards.
;; The scope is a query form: [:= a b] tests equality and [:attr k] reads
;; attribute k, so [:= [:attr :owner] "agent"] matches strands whose owner
;; attribute is "agent" (full DSL: ../devflow/specs/repl-api.md, SPEC-003.C13a).
(selvage/check-all [:= [:attr :owner] "agent"])
(selvage/check-all [:= [:attr :kanban/card] "true"])
;; => [{:strand-id "..." :checkset :kanban :attr "kanban/lane" :check :enum :value "wip" ...}]

;; Gate on it: non-empty means stop and fix before merging.
(let [bad (selvage/check-all [:= [:attr :kanban/card] "true"])]
  (when (seq bad)
    (throw (ex-info "Kanban checkset violations block the merge"
                    {:violations bad}))))
```

**Why this shape.**

- **On-demand beats always-on for a gate.** `check-all` reads the graph once and
  returns; there is no handler to install or tear down. A pre-merge check is a
  moment, not a subscription — reach for watch mode (next recipe) only when you
  want continuous detection.
- **The query form is the same DSL your other reads use.** Scoping with `[:=
  [:attr :owner] "agent"]` reuses the predicate DSL that backs `list`/`ready`, so
  a feature-scoped sweep is `[:= [:attr :feature] "my-feature"]` — no new
  filtering vocabulary to learn.
- **A flat vector is easy to gate on.** `(seq (check-all ...))` is the whole
  test; the violation maps carry `:strand-id`, `:attr`, and `:message` so the
  failure report points at exactly what to fix.

Honest source: `check-all-can-be-scoped-by-query-form` in [`test/skein/spools/selvage_test.clj`](../test/skein/spools/selvage_test.clj), which registers a checkset and asserts a query form narrows the sweep to the in-scope strands.

---

## Recipe: Watch mode — catch violations as they land, then review the catch

**Situation.** You want standing detection: as agents and workflows mutate the graph all day, you want a record of every strand that drifted from the checkset, to review at the end of a session rather than block anyone in the moment.

**Composition.** `install!` (which calls `watch!`) registers an asynchronous handler on `:strand/added` and `:strand/updated`. Violations accrue in a log you read with `violations` and reset with `clear-violations!`. Clear once at the start of the window you care about, work, then read the log.

```clojure
(require '[skein.repl :as repl]
         '[skein.spools.selvage :as selvage])

(selvage/register-checkset! :board {:checks [{:attr "kanban/lane" :enum ["pending"]}]})
(selvage/install!)            ; registers the async watcher
(selvage/clear-violations!)   ; start the review window clean

(def card (repl/strand! "Watched card" {:kanban/lane "pending"}))
(repl/update! (:id card) {:attributes {:kanban/lane "failed"}})

;; the watcher runs off-thread; give it a beat before reading in a REPL
(Thread/sleep 250)
(mapv #(select-keys % [:strand-id :attr :value]) (selvage/violations))
;; => [{:strand-id "..." :attr "kanban/lane" :value "failed"}]

(selvage/clear-violations!)
;; => {:cleared true}
```

**Why this shape.**

- **Watch mode is detection, not rejection.** The handlers fire *after* the
  mutation commits ([selvage.md §1](./selvage.md#1-overview)), so a bad write has
  already landed by the time it is recorded. That is deliberate — Selvage ships
  the minimal lint surface and leaves rejection to the core's synchronous hooks.
  Treat the log as an audit trail to triage, not a guard rail.
- **Clear defines the window.** The log is append-only across the weaver
  lifetime, so `clear-violations!` at the top of a session (or after you've
  actioned a batch) is what makes "what did today's work drift on?" a meaningful
  question.
- **The delay is real, and honest.** Because delivery is asynchronous, a REPL
  read immediately after a mutation can race the handler — the `Thread/sleep` is
  there for that reason, not for show. In a long-running coordinator you just
  read the log later and never notice.

Honest source: `watch-records-and-clears-violations` in [`test/skein/spools/selvage_test.clj`](../test/skein/spools/selvage_test.clj), which installs the watcher, mutates a strand to a disallowed enum value, and asserts the recorded violation then the clean slate after `clear-violations!`.

---

## Recipe: Tighten a checkset as the convention evolves

**Situation.** Your convention changed — a lane was renamed, a value retired, a new required pairing agreed. You want to move the whole workspace to the new checkset without carrying a second "v2" registration, and you want existing strands re-judged against the new rules immediately.

**Composition.** Call `register-checkset!` again with the same name. It replaces the prior registration for that name wholesale; the next `check`/`check-all` judges every strand against the new spec. `unregister-checkset!` retires a convention entirely.

```clojure
(require '[skein.repl :as repl]
         '[skein.spools.selvage :as selvage])

(def card (repl/strand! "Feature card" {:kanban/lane "in-review"}))

;; Old convention allowed :in-review …
(selvage/register-checkset! :board {:checks [{:attr "kanban/lane" :enum ["pending" "in-review"]}]})
(selvage/check (:id card))
;; => []

;; … the lane was retired. Re-register the same name; the strand is now flagged.
(selvage/register-checkset! :board {:checks [{:attr "kanban/lane" :enum ["pending" "claimed"]}]})
(mapv :value (selvage/check (:id card)))
;; => ["in-review"]

;; Retire the convention outright when it no longer applies.
(selvage/unregister-checkset! :board)
;; => {:unregistered :board}
```

**Why this shape.**

- **Replace-by-name keeps one source of truth.** There is never a `:board` and a
  `:board-v2`; the registry holds exactly one spec per name, so the checkset in
  force is unambiguous. Tightening is the same call as declaring.
- **Re-judgement is immediate and retroactive.** `check` reads the *current*
  registry every time, so the moment you re-register, every existing strand is
  measured against the new rules — which is exactly how you find the strands a
  convention change stranded.
- **Everything fails loudly.** `register-checkset!` rejects an unknown check type or spec
  key at registration ([selvage.md §3](./selvage.md#3-surface)), and
  `unregister-checkset!` throws on a name that was never registered — so a typo in the
  evolution surfaces at once instead of silently doing nothing.

Honest source: `register-checkset-replaces-existing-checkset` in [`test/skein/spools/selvage_test.clj`](../test/skein/spools/selvage_test.clj), which re-registers a name to change its allowed enum, then removes it and asserts the second removal fails loudly.

---

## See also

- [`selvage.md`](./selvage.md) — the contract: check forms, watch-mode
  semantics, the violation shape, and the full surface table.
- [`selvage.api.md`](./selvage.api.md) — generated signatures and docstrings for
  every public fn referenced above.
- [`carder.cookbook.md`](./carder.cookbook.md) — the companion hygiene spool:
  where Selvage lints attribute *values*, Carder reports structural drift (stale,
  orphaned, and failure-blocked work).
- The attribute tables Selvage is built to guard: [`kanban.md`](./kanban.md)
  (`kanban/*`), [`roster.md`](./roster.md) (`roster/*`), and
  [`workflow.md`](./workflow.md#7-attribute-vocabulary) (`workflow/*`).
