# Skein Bobbin Spool — Cookbook

Composition recipes for `skein.spools.bobbin`: how to turn the graph around a
strand into context an agent can act on, and *why* each shape is the right one.

This is the **how/why** half of the bobbin docs. The other two halves are:

- [`bobbin.md`](./bobbin.md) — the **contract**: the bundle sections, the
  self-contained-edges guarantee, and the render rules.
- [`bobbin.api.md`](./bobbin.api.md) — the **generated reference**: every public
  fn's signature, arities, and docstring, produced from the source.

Division of truth: signatures and the section table live in the contract and the
generated API doc; narrative and composition live here. This cookbook never
restates a fn signature or the section table — it links to them.

Bobbin has two functions worth composing — `pack` (a JSON-compatible bundle) and
`render` (deterministic prompt text) — and its value is entirely in *what you
feed them into*: a delegation prompt, a reviewer brief, or another tool. These
recipes are about those hand-offs.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches
your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which primitives combine, and how.
3. **Snippet** — a complete, runnable form (assume
   `(require '[skein.spools.bobbin :as bobbin])`).
4. **Why this shape** — the reasoning: why these sections, what the guarantees
   buy you, and what the alternative would cost.

---

## Recipe: Brief a delegated agent before handing off a task

**Situation.** You're about to delegate a task strand to another agent, and its
launch prompt alone won't tell the agent what it's walking into — what blocks it,
what it hangs beneath, what a predecessor already decided in the notes.

**Composition.** `pack` the task strand into a bundle, `render` that bundle to
plain text, and use the text as the context prefix of your delegation prompt. The
target's `body` comes through in full; everything else is one compact line.

```clojure
(require '[skein.spools.bobbin :as bobbin])

(def context (bobbin/render (bobbin/pack "abc12")))

;; hand `context` to whatever spawns the run — e.g. as the extra --prompt text
;; for `strand agent delegate` (see spools/agents/README.md)
(println context)
;; # Bobbin context pack
;; ## Target
;; - abc12 | Implement CSV importer | active | attrs {:owner "agent"}
;; ### Body
;; Parse CSV, upsert rows.
;; Handle malformed lines by skipping with a logged warning.
;; ## Blockers
;; - g3hq9 | Design import schema | active
;; ...
```

**Why this shape.**

- **`render` is deterministic, so the prompt is stable.** Sections come out in a
  fixed order and related strands are sorted, so the same graph renders the same
  text every time — no prompt churn from run to run (contract
  [§3](./bobbin.md#3-surface), rendered by `render`).
- **The target's `body` is the one thing shown in full.** Bobbin renders the
  strand under scrutiny with its whole `body` attribute and every *related* strand
  as a single line, so the agent gets the full brief for its own task and a cheap
  one-line map of the neighbourhood — not a wall of bodies.
- **The pack is self-contained.** Every edge a section emits references only
  strands summarized in that same section, so the rendered neighbourhood never
  points at a strand the agent can't see. Nothing dangles (contract
  [§1](./bobbin.md#1-overview)).

Honest source: the `pack` → `render` round-trip verified against a live weaver;
the determinism and body-in-full guarantees are pinned by
[`test/skein/spools/bobbin_test.clj`](../test/skein/spools/bobbin_test.clj)
(`render-is-deterministic-and-includes-target-body`).

---

## Recipe: Trim the pack to just what a prompt needs

**Situation.** The default bundle carries every section — blockers, dependents,
parents, children, notes, workflow — but this prompt only needs a couple of them,
and the extra lines are wasted tokens (or noise the agent will over-index on).

**Composition.** Pass `{:include #{...}}` to `pack` with just the sections you
want. `render` walks only the sections present in the bundle, so a trimmed pack
renders a trimmed brief with no extra work.

```clojure
(require '[skein.spools.bobbin :as bobbin])

;; a prompt that only needs the task itself, what blocks it, and prior notes
(def slim (bobbin/pack "abc12" {:include #{:strand :blockers :notes}}))

(keys slim)
;; => (:bobbin/version :include :strand :blockers :notes)

(println (bobbin/render slim))   ; renders only Target / Blockers / Notes
```

**Why this shape.**

- **`:include` narrows the computation, not only the render.** Only the requested
  sections are built, so a slim pack skips the graph walks it doesn't need — the
  parents ancestry crawl, the dependents scan — rather than computing everything
  and dropping most of it.
- **The section vocabulary fails loudly.** Ask for a section that doesn't exist
  and `pack` throws with the allowed set in `ex-data`, so a typo in `:include`
  surfaces immediately instead of silently dropping context (contract
  [§3](./bobbin.md#3-surface)).
- **Fewer sections, sharper agent.** A reviewer briefed on blockers and notes
  shouldn't also be handed the target's children and dependents — trimming keeps
  the prompt pointed at the decision you actually want made.

Honest source: the `:include` subset and its loud-failure behaviour verified
against a live weaver, pinned by
[`test/skein/spools/bobbin_test.clj`](../test/skein/spools/bobbin_test.clj)
(`include-selection-and-failure-are-explicit`).

---

## Recipe: Brief a reviewer on a strand's blockers, provenance, and notes

**Situation.** You want a second agent to review a piece of work, and the review
turns on context the diff doesn't show: what the work depended on, what run it
belongs to, and what predecessors recorded in the notes.

**Composition.** Pack the strand with the review-relevant sections — `:strand`,
`:blockers`, `:parents`, `:notes` — render it, and prepend your own review
instruction. The notes section carries the handovers and provenance; the parents
section shows what the work sits beneath.

```clojure
(require '[skein.spools.bobbin :as bobbin])

(def brief (bobbin/pack "abc12" {:include #{:strand :blockers :parents :notes}}))

(def prompt
  (str "You are reviewing the strand below. Confirm its blockers are truly "
       "resolved and its notes were honoured before signing off.\n\n"
       (bobbin/render brief)))

(println prompt)
```

**Why this shape.**

- **Notes are the provenance channel.** Bobbin's notes section pulls strands
  attached by the `notes` edge, ordered by `shuttle/at` then creation time, so a
  reviewer reads predecessors' handovers in the order they happened — the same
  notes an agent leaves with `agent note` (contract
  [§3, section meanings](./bobbin.md#3-surface)).
- **Blockers frame the sign-off question.** The blockers section is the *active*
  transitive `depends-on` closure, so "are these resolved?" is a concrete,
  bounded question the reviewer can actually answer rather than a vague "is this
  ready?".
- **Your instruction stays yours.** Bobbin renders context, not intent — so you
  prepend the review ask and bobbin never dictates the prompt's purpose. The pack
  is a section; the preamble is the frame around it.

Honest source: the reviewer-brief composition (`pack` with a review `:include`
set, rendered under a preamble) verified against a live weaver; the notes
ordering is pinned by
[`test/skein/spools/bobbin_test.clj`](../test/skein/spools/bobbin_test.clj)
(`pack-projects-small-context-graph`).

---

## Recipe: Pack a workflow step and locate its run automatically

**Situation.** The strand you're briefing an agent on is a step inside a workflow
run, and the agent needs to know which run it's part of and where the run's root
is — without you looking it up.

**Composition.** `pack` a strand that carries `workflow/*` attributes and the
bundle gains a `:workflow` section: the run id, the step's role, the workflow
attributes, and the molecule root summary when it resolves. No extra call — the
section appears only when the target is workflow-tagged.

```clojure
(require '[skein.spools.bobbin :as bobbin])

(def bundle (bobbin/pack "step-strand-id"))

(:workflow bundle)
;; => {:run-id "ship-importer"
;;     :role "step"
;;     :attributes {"workflow/run-id" "ship-importer" "workflow/role" "step"}
;;     :root {:id "run-root" :title "Run" :state "active" ...}}
```

**Why this shape.**

- **Key presence is the signal.** The `:workflow` key is present only when the
  target carries workflow attributes, so a consumer detects "this is workflow
  work" by asking whether the key exists — a plain task pack simply omits it
  (contract [§3, section meanings](./bobbin.md#3-surface)).
- **The root is found for you.** Bobbin resolves the run's `molecule` root from
  the `workflow/run-id` and summarizes it inline, so the brief points at the run's
  anchor strand without the caller running a second query to find it.
- **Same `pack` call, richer for workflow strands.** You don't branch on "is this
  a workflow step?" — you pack the strand and read `:workflow` if it's there. The
  spool absorbs the conditional.

Honest source: the workflow section shape is pinned by
[`test/skein/spools/bobbin_test.clj`](../test/skein/spools/bobbin_test.clj)
(`workflow-section-appears-for-workflow-strands`) and verified against a live
weaver.

---

## Recipe: Feed the pack to a tool instead of a prompt

**Situation.** The consumer isn't an agent reading prose — it's code: a dashboard
panel, a routing decision, a check that a strand's blockers are all closed. You
want structured data, not rendered text.

**Composition.** Use `pack` directly and skip `render`. The bundle is
JSON-compatible and carries `:bobbin/version` plus the resolved `:include` list,
so a downstream tool can consume it as data and know exactly which sections are
present.

```clojure
(require '[skein.spools.bobbin :as bobbin])

(def bundle (bobbin/pack "abc12"))

;; the bundle is plain data: version, the sections it contains, and each section
(:bobbin/version bundle)          ; => 1
(:include bundle)                 ; => [:strand :blockers :dependents :parents :children :notes]
(map :id (get-in bundle [:blockers :strands]))   ; => ("g3hq9" ...)
```

**Why this shape.**

- **`render` is for prompts; `pack` is for programs.** Rendering flattens the
  bundle to text an agent reads — throwing away the structure a tool needs. When
  the consumer is code, stop at `pack` and keep the sections addressable.
- **`:bobbin/version` and `:include` make the shape self-describing.** A consumer
  reads the version to guard against format drift and the `:include` list to know
  which sections it actually got, instead of guessing from key presence.
- **Every section's edges stay internal.** Because a section's edges only
  reference strands summarized in that same section, a tool can render a section's
  mini-graph without resolving ids that live outside it — the guarantee that also
  makes the JSON safe to ship whole (contract [§1](./bobbin.md#1-overview)).

Honest source: the JSON-compatible bundle keys verified against a live weaver;
the self-contained-edges guarantee is pinned by
[`test/skein/spools/bobbin_test.clj`](../test/skein/spools/bobbin_test.clj)
(`pack-section-edges-are-self-contained`).

---

## See also

- [`bobbin.md`](./bobbin.md) — the contract: the bundle sections, the
  self-contained-edges guarantee, and the render rules.
- [`bobbin.api.md`](./bobbin.api.md) — generated signatures and docstrings for
  `pack`, `render`, and `install!`.
- [`ephemeral.cookbook.md`](./ephemeral.cookbook.md) — the companion for
  *throwaway* state; bobbin is for the durable notes and provenance a pack
  surfaces.
- [`spools/agents/README.md`](./agents/README.md) — the delegation surface a
  rendered pack feeds into as prompt context.
