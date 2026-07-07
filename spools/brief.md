# Brief spool

> This is the **contract** doc: the brief and guide shapes, the clause-block
> substrate, the closed-key validation rules, the shared `brief->prompt`
> renderer, the projection helpers, and the CLI op. Its two companions are
> [`brief.cookbook.md`](./brief.cookbook.md) — worked composition recipes (how
> you author a run's contract as data, share a fixed clause across prompts, and
> advertise a guide on a workflow step) — and [`brief.api.md`](./brief.api.md) —
> generated fn signatures and docstrings. Reach for the cookbook when you want a
> runnable pattern, the API doc when you want an exact arity, and this doc for
> what the spool promises.

The brief spool holds two things every delegating workspace re-invents on top of
Skein's graph: the per-run **brief** (the structured contract a delegated run is
told — its mission, deliverable, scope, budgets, and source rules) and the
durable **guide** (authoring knowledge an agent fetches before it acts). Both are
plain data validated against a closed key set, and both render through one
deterministic prompt seam, so a scope clause and a guide's constraints go through
the same renderer instead of three hand-rolled `(str ...)` blocks that drift
apart.

`skein.spools.brief` ships on the weaver classpath beside batteries, workflow,
and roster, so no `spools.edn` approval is needed — `require` it and call
`install!` from `init.clj`, an activated spool, or a live `mill weaver repl` (see
[Activation](#activation)). It requires only `skein.api.*.alpha` and the
`skein.spools.util`/`skein.spools.format` authoring helpers, so it sits below the
spools that consume it (agents, treadle, devflow).

## The clause block

The load-bearing primitive is the **clause block**: a named, reusable prose
fragment `{:title "..." :lines ["..." ...]}` registered once and referenced by
key. The fixed part of a contract — source rules, a blocked-domain list, a worker
preamble — lives in one registered block instead of copy-pasted prose in every
prompt. A brief pulls clause blocks in by key from its `:blocks` vector; a guide's
`:constraints`/`:validation` are the same shape. One substrate, one renderer.

`defblock!` registers a block, `block` fetches one (failing loudly with the
available keys on a miss), and `blocks` lists every key mapped to its title. The
registry is runtime-owned spool state, re-registered by trusted config on reload
like harness aliases and rosters.

## Brief

A brief is a small closed map of well-known sections. Every key is optional; a
sparse brief renders a tight prompt.

| Section | Shape | Renders as |
| --- | --- | --- |
| `:context` | string, or `{:ref strand-id}` | the decision this run feeds |
| `:mission` | non-empty vector of strings | the objective list |
| `:body` | string | freeform elaboration, rendered *with* `:mission` |
| `:deliverable` | `{:path :format :end-with :validate [cmd...]}` | what to produce and how to check it |
| `:scope` | `{:owns [..] :forbid-reads [..] :commit? bool}` | owned files, sibling isolation, commit rule |
| `:budgets` | `{:web-searches 18 ...}` (values `nat-int?`) | per-run caps |
| `:rules` | vector of strings | extra inline rules |
| `:blocks` | vector of keywords | registered clause blocks to append verbatim |

`:mission` and `:body` both render when present — `:mission` is the objective
list, `:body` its freeform elaboration — so neither silently wins.

### Closed-key validation

`validate-brief` is loud and layered:

1. It rejects any key outside the closed brief set, and rejects unknown keys in
   the `:deliverable` and `:scope` sub-maps too. A typo like `{:scope {:owned
   [..]}}` fails at authoring time instead of vanishing from the rendered prompt.
   (`clojure.spec`'s `s/keys` is structurally open, so this closed-set check is
   the part a spec can't express.)
2. It checks shapes and types against the `::brief` spec — `:budgets` values must
   be `nat-int?`, `:mission` a non-empty vector, and so on.
3. `brief->prompt` resolves each `:blocks` key against the registry and fails
   loudly with the available keys on a miss, so an unknown block throws at render
   time rather than rendering blank.

### The renderer

`brief->prompt` is the one prompt seam every consumer shares — treadle gate
prompts, delegate task bodies, roster contracts, panel seat briefs, and this
repo's `pipeline-task-prompt`. It validates first, then emits sections in a fixed
order (context, mission, deliverable, scope, budgets, rules, then named blocks),
omitting any section with no content.

Briefs are per-run and inline, so they earn no registry — like panel seats, you
author one where you need it. Only the durable, named clause blocks and guides
are registered.

### Projection, not enforcement

`brief-attrs` projects a validated brief's owned paths and budgets to the
`brief/owns` and `brief/budgets` strand attributes, so a `describe`/projection
view can show what a run will own and spend before it is poured. It returns
scalars only, never the rich brief itself.

`overlapping-owns` is a pure detector: given sibling task maps carrying
`brief/owns`, it returns the owned-path collisions as `{:path .. :tasks [..]}`
entries. It is advisory — it reports the collision a userland disjoint-scope
check would act on, and wires no enforcement into any delegate or pour path.
Disjoint-scope *enforcement* is a behaviour change that stays a userland opt-in,
not a default baked into the primitive.

## Guide

A guide is the durable authoring knowledge an agent fetches before acting,
generalising devflow's guidance shape. `:purpose` is required; the rest of the
closed set is optional freeform data: `:artifacts`, `:prerequisites`,
`:knowledge`, `:procedures`, `:constraints`, `:validation`, `:templates`,
`:see-also`.

`defguide!` registers a guide, `guide` fetches one (loud on a miss), and `guides`
lists every key mapped to its `:purpose`. Guides share the runtime-owned registry
convention with clause blocks.

### The `guide/key` step convention

A workflow step advertises the guide its driving agent should read by setting the
`guide/key` strand attribute (this generalises devflow's `devflow/guide`).
`strand-guide` resolves it, returning `{:key <kw> :guide <map>}`, or nil when a
strand advertises none. A `guide/key` naming an unregistered guide fails loudly
rather than silently yielding no guidance.

## CLI op

The `brief` op is a thin, read-only JSON fetch surface, declared as a
`:subcommands` arg-spec so its `help` is generated:

| Subcommand | Returns |
| --- | --- |
| `strand brief about` | the brief/guide conventions and installed surface |
| `strand brief prime` | full agent priming for authoring briefs and fetching guides |
| `strand brief guides` | registered guide keys mapped to purposes |
| `strand brief guide <key>` | one registered guide as JSON |
| `strand brief blocks` | registered clause-block keys mapped to titles |
| `strand brief block <key>` | one registered clause block |

The CLI **never authors a brief.** Rich brief data is trusted-Clojure/payload
territory (TEN-006); only durable *named* things — guides and blocks — ride
argv. Rendering a brief is `brief->prompt` in trusted Clojure and weave patterns,
never a CLI verb.

## Activation

Add brief to `init.clj` and populate its registries from trusted config:

```clojure
(runtime-alpha/use! runtime :skein/spools-brief
                    {:ns 'skein.spools.brief
                     :call 'skein.spools.brief/install!})
```

`install!` registers the `brief` op and touches the (empty) registries. Trusted
config then registers clause blocks and guides with `defblock!`/`defguide!` — as
this repo's `config.clj` does for its `:worker-contract` block, which
`pipeline-task-prompt` renders through a brief.

## Examples

See [`brief.cookbook.md`](./brief.cookbook.md) for worked recipes, and
[`test/skein/spools/brief_test.clj`](../test/skein/spools/brief_test.clj), which
drives every documented behavior against a real weaver and doubles as an
executable reference — including `pipeline-brief`, which reconstructs a real
`pipeline-task-prompt` on top of `brief->prompt`.
