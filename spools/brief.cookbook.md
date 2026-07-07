# Skein Brief Spool — Cookbook

Composition recipes for `skein.spools.brief`: how to author a run's contract as
data instead of a hand-concatenated prompt, share a fixed clause across many
prompts, project a brief's scope for a pre-pour view, and advertise a guide on a
workflow step — and *why* each shape is the right one.

This is the **how/why** half of the brief docs. The other two are:

- [`brief.md`](./brief.md) — the **contract**: the brief and guide shapes, the
  clause-block substrate, closed-key validation, the renderer, the projection
  helpers, and the CLI op. Read it for what the spool promises.
- [`brief.api.md`](./brief.api.md) — the **generated reference**: every public
  fn's signature and docstring, produced from source.

Division of truth: the section tables and fn signatures live in the contract and
generated API doc; narrative and composition live here. This cookbook never
restates the brief-section table or a signature — it links to them.

The runtime-touching helpers used here — `defblock!`, `block`, `blocks`,
`brief->prompt`, `defguide!`, `guide`, `guides`, `strand-guide` — take `runtime`
as their first argument and never resolve the ambient runtime themselves, so
these recipes assume you already hold one —
`(require '[skein.spools.brief :as brief] '[skein.api.current.alpha :as current])`
and `(def rt (current/runtime))` in trusted config or a live weaver REPL. The
pure helpers `validate-brief`, `brief-attrs`, and `overlapping-owns` take no
runtime; the discovery helpers `about` and `prime` take only the runtime. From
the shell the read-only fetch surface is `strand brief …`.

## How to read a recipe

Every recipe has the same four parts:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which helpers combine, and how.
3. **Snippet** — a runnable form or the equivalent shell.
4. **Why this shape** — the reasoning, and what the alternative would cost.

Each recipe cites the honest source it was distilled from — the spool's own
contract or its test suite.

---

## Recipe: Author a delegated run's contract as data

**Situation.** You are about to delegate a run and you need to tell it its
mission, what to produce, what it owns, and what it may spend. The reflex is to
`str` a prompt together. Do that twice and the two prompts drift; nothing can
validate the shape, and a mistyped section silently vanishes.

**Composition.** Author the contract as a brief map and hand it to
`brief->prompt`. Put the fixed part (source rules, a worker preamble) in a
registered clause block referenced from `:blocks`; put this run's variable part
in the well-known sections. `validate-brief` runs first inside the renderer, so a
typo fails loudly instead of rendering blank.

```clojure
(brief/defblock! rt :src/rules
                 {:title "Source rules"
                  :lines ["Cite every claim with a URL." "Flag anything you could not verify."]})

(brief/brief->prompt
 rt
 {:context "Which grocery integration should we build?"
  :mission ["Which UK retailers block headless fetches?"]
  :deliverable {:path "research/01-a.md" :format :markdown
                :end-with "a verdict section"
                :validate ["test -s research/01-a.md"]}
  :scope {:owns ["research/01-a.md"] :forbid-reads ["research/*.md"] :commit? false}
  :budgets {:web-searches 18 :web-fetches 12}
  :blocks [:src/rules]})
```

**Why this shape.**

- **One renderer, no drift.** Every consumer — treadle gate prompts, delegate
  task bodies, this repo's `pipeline-task-prompt` — goes through the same
  `brief->prompt`, so a section's rendered shape is defined once.
- **Loud on a typo.** `{:scope {:owned [..]}}` throws at authoring time because
  `:scope` is held to its own closed key set. A hand-built string would have
  dropped the owned files with no error.
- **Always cap a research run.** A `:budgets` clause is the single most effective
  control found for exploration runs; an uncapped run is the most expensive
  failure mode. Give every research brief explicit budgets.

Honest source: the [brief section table](./brief.md#brief) and the renderer
behaviour pinned by `brief->prompt-renders-fixed-order-sections-and-blocks` and
`validate-brief-rejects-unknown-and-mistyped-subkeys` in
[`test/skein/spools/brief_test.clj`](../test/skein/spools/brief_test.clj).

---

## Recipe: Share a fixed clause across every prompt

**Situation.** The same preamble — a worker contract, a blocked-domain list —
belongs on every prompt a workspace emits. Copy-pasting it into each prompt
string means a later edit updates one copy and forgets the rest.

**Composition.** Register the fixed text once as a clause block, then reference it
by key from every brief's `:blocks`. This repo does exactly this: `config.clj`
registers the standing shuttle worker contract as the `:worker-contract` block at
`install!`, and `pipeline-task-prompt` renders it by key.

```clojure
;; register once, in trusted config
(brief/defblock! rt :worker-contract
                 {:title "Worker contract" :lines [agents/worker-contract]})

;; reference by key from any brief
(brief/brief->prompt rt {:context (str "Delegated pipeline run: " run-id)
                         :mission [(task-title item)]
                         :blocks [:worker-contract]})
```

From the shell you can read what is registered, but you cannot author a brief
there — rich brief data stays in trusted Clojure:

```sh
strand brief blocks              # every registered clause-block key → title
strand brief block worker-contract
```

**Why this shape.**

- **Edit once.** The block is the single source of the fixed clause; every prompt
  that references it picks up the edit.
- **A missing block fails loudly.** Rename the block and forget a reference, and
  `brief->prompt` throws with the available keys — it never renders the contract
  blank.
- **The CLI fetches, never authors.** `strand brief block <key>` reads a durable
  named thing; the brief that composes it is Clojure. That is the TEN-006 line —
  named durable things ride argv, per-run rich data does not.

Honest source: the [clause-block section](./brief.md#the-clause-block), this
repo's `.skein/config.clj` `pipeline-task-prompt`, and
`brief-reconstructs-a-real-pipeline-prompt` and
`install!-registers-a-fetch-only-op` in
[`test/skein/spools/brief_test.clj`](../test/skein/spools/brief_test.clj).

---

## Recipe: Project a brief's scope before you pour

**Situation.** Before a plan is poured you want to see what each run will own and
spend — a "what will this cost" view — and you want to catch two sibling runs
claiming the same file. You do *not* want the primitive to block the pour; that
call is yours.

**Composition.** Stamp `brief-attrs` onto each task strand so `brief/owns` and
`brief/budgets` are projected as attributes a `describe`/projection view reads.
Then run `overlapping-owns` over the sibling tasks to detect owned-path
collisions. Both are pure and advisory — you decide what to do with a collision.

```clojure
;; project a brief's owns/budgets to attrs when you create the task
(api/add rt {:title "Research retailer A"
             :attributes (brief/brief-attrs research-brief)})

;; later, over the sibling tasks, detect owned-path collisions
(brief/overlapping-owns
 [{:id "t1" :attributes {"brief/owns" ["a.md" "shared.md"]}}
  {:id "t2" :attributes {"brief/owns" ["b.md" "shared.md"]}}])
;; => [{:path "shared.md" :tasks ["t1" "t2"]}]
```

**Why this shape.**

- **Projection, not policy.** `brief-attrs` mirrors scalars a view can read; it
  never stores the rich brief. `overlapping-owns` reports a collision; it does not
  raise. Enforcement — refusing to pour overlapping runs — is a behaviour change
  that stays a userland opt-in, so you add it deliberately (e.g. by calling a
  `fail!` on a non-empty result in your own weave pattern) rather than inheriting
  it silently.
- **Validated before projected.** `brief-attrs` validates the brief first, so a
  malformed brief never projects a half-formed attr set.

Honest source: the [projection section](./brief.md#projection-not-enforcement)
and `brief-attrs-projects-owns-and-budgets` /
`overlapping-owns-detects-cross-task-collisions` in
[`test/skein/spools/brief_test.clj`](../test/skein/spools/brief_test.clj).

---

## Recipe: Advertise a guide on a workflow step

**Situation.** A workflow step's driving agent needs to read the right authoring
guidance before it writes the artifact — the proposal conventions before a
proposal, the spec rules before a spec. You want that pairing to live on the step,
not in the agent's memory.

**Composition.** Register the durable knowledge as a guide, and set the
`guide/key` attribute on the step to advertise it. The agent (or a step view)
resolves it with `strand-guide`, which fails loudly if the step names a guide that
was never registered.

```clojure
(brief/defguide! rt :proposal
                 {:purpose "Frame the feature and its alternatives."
                  :prerequisites ["Read the intake notes."]
                  :constraints ["Keep it under two pages."]})

;; a workflow step advertises the guide its agent should read
(api/add rt {:title "Write proposal" :attributes {:guide/key "proposal"}})

;; the agent resolves it before acting
(brief/strand-guide rt step)
;; => {:key :proposal :guide {:purpose "Frame the feature…" …}}
```

```sh
strand brief guides              # every registered guide key → purpose
strand brief guide proposal      # one guide as JSON
```

**Why this shape.**

- **The pairing lives on the graph.** The step carries the guide key, so anyone
  reading the step — a human, a step view, a delegated agent — resolves the same
  guidance. This generalises devflow's `devflow/guide` convention.
- **A dangling key fails loudly.** A `guide/key` naming an unregistered guide
  throws rather than yielding no guidance, so a renamed guide surfaces as an error
  instead of a step that silently drops its guidance.

Honest source: the [guide section](./brief.md#guide) and
`strand-guide-resolves-advertised-guide` / `defguide!-registers-and-fails-loudly`
in [`test/skein/spools/brief_test.clj`](../test/skein/spools/brief_test.clj).

## See also

- [`brief.md`](./brief.md) — the contract this cookbook composes against.
- [`workflow.md`](./workflow.md) — the engine whose steps carry the `guide/key`
  attribute and whose gates render `brief->prompt` output.
- [Writing shared spools](../docs/writing-shared-spools.md) — the
  composability-over-ergonomics and runtime-first rules these helpers follow.
