# Table of contents
-  [`skein.spools.brief`](#skein.spools.brief)  - One primitive substrate for two things every delegating workspace re-invents: the per-run **brief** (the structured contract a delegated run is told) and the durable **guide** (authoring knowledge an agent fetches before acting).
    -  [`about`](#skein.spools.brief/about) - Return the brief/guide conventions and installed helper surface.
    -  [`block`](#skein.spools.brief/block) - Return the clause block registered under <code>k</code>, failing loudly on an unknown key with the available keys.
    -  [`blocks`](#skein.spools.brief/blocks) - Return every registered clause block key mapped to its title (or nil).
    -  [`brief->prompt`](#skein.spools.brief/brief->prompt) - Render <code>brief</code> to the single deterministic prompt string every consumer shares (treadle gate prompts, delegate task bodies, roster contracts, panel seat briefs, pipeline-task-prompt).
    -  [`brief-attrs`](#skein.spools.brief/brief-attrs) - Return the projection attributes for validated <code>brief</code>: <code>brief/owns</code> (its scoped owned paths) and <code>brief/budgets</code> (its budget map), each present only when the brief declares it.
    -  [`brief-op`](#skein.spools.brief/brief-op) - Dispatch parsed <code>strand brief ...</code> subcommands.
    -  [`budgets-attr`](#skein.spools.brief/budgets-attr) - Projection attribute carrying a brief's <code>:budgets</code> map so a describe / projection view can show what a run may spend before it is poured.
    -  [`defblock!`](#skein.spools.brief/defblock!) - Register clause block <code>block</code> under keyword <code>k</code> in <code>runtime</code>, returning <code>k</code>.
    -  [`defguide!`](#skein.spools.brief/defguide!) - Register authoring guide <code>guide</code> under keyword <code>k</code> in <code>runtime</code>, returning <code>k</code>.
    -  [`guide`](#skein.spools.brief/guide) - Return the guide registered under <code>k</code>, failing loudly on an unknown key with the available keys.
    -  [`guide-attr`](#skein.spools.brief/guide-attr) - The strand attribute a workflow step sets to advertise the guide its driving agent should fetch before acting (generalises devflow's <code>devflow/guide</code>).
    -  [`guides`](#skein.spools.brief/guides) - Return every registered guide key mapped to its <code>:purpose</code> — the index a step-view or <code>strand brief guides</code> renders.
    -  [`install!`](#skein.spools.brief/install!) - Install the <code>brief</code> op into the active weaver.
    -  [`overlapping-owns`](#skein.spools.brief/overlapping-owns) - Return owned-path collisions across sibling task maps, as a vector of <code>{:path &lt;str&gt; :tasks [&lt;id&gt; ...]}</code> — one entry per path claimed by more than one task.
    -  [`owns-attr`](#skein.spools.brief/owns-attr) - Projection attribute carrying a brief's <code>:scope :owns</code> paths so a describe / projection view can show what a run will own before it is poured.
    -  [`prime`](#skein.spools.brief/prime) - Return full agent priming for the brief/guide surface: <code>about</code> plus the working discipline for authoring contracts and fetching guidance.
    -  [`strand-guide`](#skein.spools.brief/strand-guide) - Resolve the guide advertised by <code>strand</code>'s <code>guide/key</code> attribute, or nil when the strand advertises none.
    -  [`validate-brief`](#skein.spools.brief/validate-brief) - Return <code>brief</code>, failing loudly when it is not a map, carries keys outside the closed set, or violates the <code>::brief</code> spec.

-----
# <a name="skein.spools.brief">skein.spools.brief</a>


One primitive substrate for two things every delegating workspace re-invents:
  the per-run **brief** (the structured contract a delegated run is told) and the
  durable **guide** (authoring knowledge an agent fetches before acting). Both are
  plain data validated loudly against a closed key set, and both render through
  the same deterministic prompt seam — so a scope/budget clause and a guide's
  constraints share one renderer instead of three hand-rolled `(str ...)` blocks.

  The unifying substrate is the **clause block**: a named, reusable prose
  fragment (`{:title :lines}`) registered once and referenced by key. A brief is
  a small closed map of well-known sections (`:context :mission :deliverable
  :scope :budgets :rules`) plus `:blocks` that pull registered clause blocks in
  by key — so the fixed part of a contract (source rules, a blocked-domain list,
  a worker preamble) lives in one registered block instead of copy-pasted prose
  in every prompt. A guide generalises devflow's guidance shape as-is
  (`:purpose :artifacts :prerequisites :knowledge :procedures :constraints
  :validation :templates :see-also`).

  Two runtime-owned registries (clause blocks, guides), keyed by a symbol this
  spool owns and re-registered by trusted config on reload like harnesses and
  rosters. Every public model fn takes `runtime` first and never resolves ambient
  runtime itself (docs/writing-shared-spools.md); only `install!` resolves the
  active runtime, and CLI op handlers read `:op/runtime` from their context.
  Briefs themselves are per-run and inline — they earn no registry (like panel
  seats), only the durable/named clause blocks and guides do.

  TEN-006 boundary: rich brief data is trusted-Clojure/payload territory — the
  CLI never authors a brief. Only durable *named* things (guides, blocks) ride
  argv, via read-only `strand brief guide|guides|block|blocks` fetches. Rendering
  a brief is `brief->prompt` in trusted Clojure and weave patterns.

  Projection, not enforcement: `brief-attrs` mirrors a brief's owned paths and
  budgets into `brief/owns`/`brief/budgets` attrs so a `describe`/projection view
  can show what a run will own and spend before it is poured, and
  `overlapping-owns` is a pure detector of owned-path collisions across sibling
  tasks. Neither is wired into any delegate/pour path — disjoint-scope
  *enforcement* is a behaviour change that stays a userland opt-in.

  Workflow steps advertise the guide an agent should read via the `guide/key`
  strand attribute (generalising devflow's `devflow/guide`); `strand-guide`
  resolves it, failing loudly on an unknown key.




## <a name="skein.spools.brief/about">`about`</a>
``` clojure
(about runtime)
```
Function.

Return the brief/guide conventions and installed helper surface.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L322-L357">Source</a></sub></p>

## <a name="skein.spools.brief/block">`block`</a>
``` clojure
(block runtime k)
```
Function.

Return the clause block registered under `k`, failing loudly on an unknown
  key with the available keys.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L95-L100">Source</a></sub></p>

## <a name="skein.spools.brief/blocks">`blocks`</a>
``` clojure
(blocks runtime)
```
Function.

Return every registered clause block key mapped to its title (or nil).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L102-L105">Source</a></sub></p>

## <a name="skein.spools.brief/brief->prompt">`brief->prompt`</a>
``` clojure
(brief->prompt runtime brief)
```
Function.

Render `brief` to the single deterministic prompt string every consumer
  shares (treadle gate prompts, delegate task bodies, roster contracts, panel
  seat briefs, pipeline-task-prompt). Validates first, then emits fixed-order
  sections, resolving each `:blocks` key against `runtime`'s clause-block
  registry (an unknown block fails loudly). Sections with no content are omitted
  so a sparse brief renders a tight prompt.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L183-L209">Source</a></sub></p>

## <a name="skein.spools.brief/brief-attrs">`brief-attrs`</a>
``` clojure
(brief-attrs brief)
```
Function.

Return the projection attributes for validated `brief`: `brief/owns` (its
  scoped owned paths) and `brief/budgets` (its budget map), each present only
  when the brief declares it. Pure — projects scalars a `describe`/projection
  view reads, never the rich brief itself (that stays trusted-Clojure payload).
  Validates first, so a malformed brief never projects a half-formed attr set.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L225-L235">Source</a></sub></p>

## <a name="skein.spools.brief/brief-op">`brief-op`</a>
``` clojure
(brief-op ctx)
```
Function.

Dispatch parsed `strand brief ...` subcommands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L381-L392">Source</a></sub></p>

## <a name="skein.spools.brief/budgets-attr">`budgets-attr`</a>




Projection attribute carrying a brief's `:budgets` map so a describe /
  projection view can show what a run may spend before it is poured.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L220-L223">Source</a></sub></p>

## <a name="skein.spools.brief/defblock!">`defblock!`</a>
``` clojure
(defblock! runtime k block)
```
Function.

Register clause block `block` under keyword `k` in `runtime`, returning `k`.

  A clause block is `{:lines ["..."] :title "..."?}` — a named reusable prose
  fragment (source rules, a blocked-domain list, a worker preamble). Referenced
  from a brief's `:blocks` and rendered verbatim. Fails loudly on a non-keyword
  key, unknown keys, or a shape the `::block` spec rejects.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L78-L93">Source</a></sub></p>

## <a name="skein.spools.brief/defguide!">`defguide!`</a>
``` clojure
(defguide! runtime k guide)
```
Function.

Register authoring guide `guide` under keyword `k` in `runtime`, returning `k`.

  A guide is the generalised devflow guidance shape: `:purpose` is required, the
  rest of the closed set (`:artifacts :prerequisites :knowledge :procedures
  :constraints :validation :templates :see-also`) is optional freeform data.
  Fails loudly on a non-keyword key, keys outside the closed set, or a missing
  `:purpose`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L268-L284">Source</a></sub></p>

## <a name="skein.spools.brief/guide">`guide`</a>
``` clojure
(guide runtime k)
```
Function.

Return the guide registered under `k`, failing loudly on an unknown key with
  the available keys.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L286-L291">Source</a></sub></p>

## <a name="skein.spools.brief/guide-attr">`guide-attr`</a>




The strand attribute a workflow step sets to advertise the guide its driving
  agent should fetch before acting (generalises devflow's `devflow/guide`).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L303-L306">Source</a></sub></p>

## <a name="skein.spools.brief/guides">`guides`</a>
``` clojure
(guides runtime)
```
Function.

Return every registered guide key mapped to its `:purpose` — the index a
  step-view or `strand brief guides` renders.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L293-L297">Source</a></sub></p>

## <a name="skein.spools.brief/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the `brief` op into the active weaver. The clause-block and guide
  registries are runtime-owned and start empty; trusted config populates them
  with `defblock!`/`defguide!`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L407-L420">Source</a></sub></p>

## <a name="skein.spools.brief/overlapping-owns">`overlapping-owns`</a>
``` clojure
(overlapping-owns tasks)
```
Function.

Return owned-path collisions across sibling task maps, as a vector of
  `{:path <str> :tasks [<id> ...]}` — one entry per path claimed by more than
  one task. Each task is `{:id .. :attributes {..}}` carrying a `brief/owns`
  path vector (keyword- or string-keyed, tolerating a JSON round-trip). Pure and
  advisory: it *detects* the collision a userland disjoint-scope check would act
  on; it wires no enforcement into any delegate/pour path (open Q3 — enforcement
  stays a userland opt-in).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L237-L255">Source</a></sub></p>

## <a name="skein.spools.brief/owns-attr">`owns-attr`</a>




Projection attribute carrying a brief's `:scope :owns` paths so a describe /
  projection view can show what a run will own before it is poured.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L215-L218">Source</a></sub></p>

## <a name="skein.spools.brief/prime">`prime`</a>
``` clojure
(prime runtime)
```
Function.

Return full agent priming for the brief/guide surface: `about` plus the
  working discipline for authoring contracts and fetching guidance.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L359-L375">Source</a></sub></p>

## <a name="skein.spools.brief/strand-guide">`strand-guide`</a>
``` clojure
(strand-guide runtime strand)
```
Function.

Resolve the guide advertised by `strand`'s `guide/key` attribute, or nil when
  the strand advertises none. Returns `{:key <kw> :guide <map>}`. A `guide/key`
  attribute naming an unregistered guide fails loudly rather than silently
  yielding no guidance (TEN-003).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L308-L316">Source</a></sub></p>

## <a name="skein.spools.brief/validate-brief">`validate-brief`</a>
``` clojure
(validate-brief brief)
```
Function.

Return `brief`, failing loudly when it is not a map, carries keys outside the
  closed set, or violates the `::brief` spec. Sub-maps `:deliverable`/`:scope`
  are held to their own closed key sets so a typo like `:owned` fails at
  authoring time instead of silently vanishing from the rendered prompt.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/brief.clj#L137-L148">Source</a></sub></p>
