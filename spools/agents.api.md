# Table of contents
-  [`skein.spools.agents`](#skein.spools.agents)  - Agent coordination spool layered over the shuttle run engine.
    -  [`about-doc`](#skein.spools.agents/about-doc) - Structured manual returned by <code>agent about</code>.
    -  [`agent-op`](#skein.spools.agents/agent-op) - Dispatch parsed <code>strand agent</code> subcommands.
    -  [`agent-plan`](#skein.spools.agents/agent-plan) - Create a feature strand plus task/review children for agent work.
    -  [`council!`](#skein.spools.agents/council!) - Convene a multi-agent council as a <code>:fresh</code>-blackboard panel (A7): its rounds are turn-as-run barrier rows and seats deliberate by posting to and reading a shared council strand across turns, then a synthesizer weighs the whole deliberation.
    -  [`defroster!`](#skein.spools.agents/defroster!) - Register or replace a named reviewer roster (weaver-lifetime state, so trusted startup config re-registers it like harness aliases and queries).
    -  [`install!`](#skein.spools.agents/install!) - Install the agents op surface, pattern, query, and worker preamble hook.
    -  [`panel!`](#skein.spools.agents/panel!) - Spawn a panel from an inline panel value.
    -  [`panel-specs`](#skein.spools.agents/panel-specs) - Compile an **inline panel value** into plain, fully-built run specs (shape: <code>:skein.spools.agents/panel-specs</code>).
    -  [`review!`](#skein.spools.agents/review!) - Spawn independent read-only reviewers for a target strand.
    -  [`review-contract`](#skein.spools.agents/review-contract) - Read-only reviewer contract text used as the workspace default for <code>agent review</code>.
    -  [`roster->panel`](#skein.spools.agents/roster->panel) - Convert a roster value into an equivalent single-round, target-blackboard panel: each reviewer becomes an independent seat whose contract is the seat brief, and the roster synthesizer (or the first reviewer's harness) becomes the panel synthesis.
    -  [`roster-review-specs`](#skein.spools.agents/roster-review-specs) - Return a roster's review fan-out as plain, fully-built run specs (shape: <code>:skein.spools.agents/review-specs</code>).
    -  [`rosters`](#skein.spools.agents/rosters) - List registered reviewer rosters as full plain data.
    -  [`worker-contract`](#skein.spools.agents/worker-contract) - Worker contract text appended to every shuttle preamble.

-----
# <a name="skein.spools.agents">skein.spools.agents</a>


Agent coordination spool layered over the shuttle run engine.




## <a name="skein.spools.agents/about-doc">`about-doc`</a>




Structured manual returned by `agent about`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L105-L456">Source</a></sub></p>

## <a name="skein.spools.agents/agent-op">`agent-op`</a>
``` clojure
(agent-op #:op{:keys [args argv]})
```
Function.

Dispatch parsed `strand agent` subcommands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L1939-L1960">Source</a></sub></p>

## <a name="skein.spools.agents/agent-plan">`agent-plan`</a>
``` clojure
(agent-plan {:keys [input]})
```
Function.

Create a feature strand plus task/review children for agent work.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L1987-L2018">Source</a></sub></p>

## <a name="skein.spools.agents/council!">`council!`</a>
``` clojure
(council! topic opts)
```
Function.

Convene a multi-agent council as a `:fresh`-blackboard panel (A7): its rounds
  are turn-as-run barrier rows and seats deliberate by posting to and reading a
  shared council strand across turns, then a synthesizer weighs the whole
  deliberation.

  Option input is validated by `:skein.spools.agents/council-input`.

  Scalar convenience: `:members n` mints N identical seats, each running the
  council-wide `:harness`. Rich control: `:seats [{:name :harness? :brief?}]`
  gives per-seat harness and perspective; `:members` and `:seats` are mutually
  exclusive. Harness has no default (mirroring `delegate`) — a seat with neither
  its own `:harness` nor a council-wide `:harness` fails loudly. The synthesizer
  runs `:synthesizer` (a harness) or the first seat's harness. `:rounds` (default
  2) is the turn count; `:spawned-by` and `:cwd` ride onto every run.

  Returns `{:council <shared strand id> :turns [[run-ids]...] :synthesizer
  <run id>}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L1545-L1610">Source</a></sub></p>

## <a name="skein.spools.agents/defroster!">`defroster!`</a>
``` clojure
(defroster! roster-name roster)
```
Function.

Register or replace a named reviewer roster (weaver-lifetime state, so
  trusted startup config re-registers it like harness aliases and queries).

  Roster data is plain and spec-defined — see `:skein.spools.agents/roster`:
  `{:reviewers [{:name :harness :contract :scope?} ...] :synthesizer
  {:harness ...}?}`. Each reviewer is one independent read-only review run
  with its own precise contract; `:scope` is prompt-level confinement text.
  `:synthesizer` overrides the harness of the synthesis run (default: first
  reviewer's harness). Malformed data fails loudly with spec explain data.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L842-L856">Source</a></sub></p>

## <a name="skein.spools.agents/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the agents op surface, pattern, query, and worker preamble hook.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L2020-L2036">Source</a></sub></p>

## <a name="skein.spools.agents/panel!">`panel!`</a>
``` clojure
(panel! panel opts)
```
Function.

Spawn a panel from an inline panel value.

  Compiles `panel` with `panel-specs`, then resolves the blackboard: a
  `:target` panel deliberates over the supplied `:target` strand; a `:fresh`
  panel mints a new shared board strand (role `panel`) and substitutes it for
  the compiler's board placeholder in every prompt and attr. Each turn row is
  spawned as one run per seat, wiring a `depends-on` barrier on every run of
  the previous row. A `:continuity :resume` seat additionally threads its turn
  r>1 run onto its previous turn's run via spawn `:resume` — because a session
  cannot be resumed before it exists, a row containing a resuming seat is
  spawned only after `panel!` awaits the previous row to completion (so
  `:fresh`-continuity rounds spawn upfront behind barriers, while `:resume`
  rounds block prior rounds). The synthesizer, when the panel declares one,
  depends on the final turn row.

  Options are validated by `:skein.spools.agents/panel-input`. `:target` is
  required for a `:target` blackboard, `:review-id` tags notes and resumes,
  `:spawned-by` and `:cwd` ride onto every run.

  Returns `{:panel :blackboard :turns [[run-ids...]...] :synthesizer? :review-pass}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L1349-L1428">Source</a></sub></p>

## <a name="skein.spools.agents/panel-specs">`panel-specs`</a>
``` clojure
(panel-specs panel {:keys [target review-id]})
```
Function.

Compile an **inline panel value** into plain, fully-built run specs
  (shape: `:skein.spools.agents/panel-specs`). This is the one prompt-building
  source for panels; `panel!` spawns runs from these specs.

  `panel` is a map conforming to `:skein.spools.agents/panel`, validated
  identically to `panel!` input (closed keys and uniqueness before spec
  conform). Defaults are applied here: `:turns {:rounds 1}`, `:blackboard
  :target`, per-seat `:continuity :fresh`.

  Options: `:target` is the blackboard strand id for a `:target` panel (a
  blank target fails loudly); a `:fresh` panel ignores it and embeds a board
  placeholder the spawner resolves after minting. `:review-id` overrides the
  minted `:review-pass` tag (a blank override fails loudly).

  Output `:turns` is a vector of turn rows (turn 1 first); each run spec is
  `{:name :harness :prompt :resume-prompt? :attrs :resume-ref?}` where
  `:resume-ref` is the seat index in the previous row this turn continues (only
  present for a `:continuity :resume` turn r>1). Every run spec stamps
  `shuttle/panel-seat`, `shuttle/panel-turn`, `shuttle/review-target`, and
  `shuttle/review-pass`. `:synthesizer` is present unless `:synthesis` is
  absent or `:none`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L1257-L1347">Source</a></sub></p>

## <a name="skein.spools.agents/review!">`review!`</a>
``` clojure
(review!
 target-id
 {:keys [reviewers members harnesses contract synthesize? spawned-by cwd roster change-context],
  :or {members 2},
  :as opts})
```
Function.

Spawn independent read-only reviewers for a target strand.

  `:roster` names a `defroster!` roster (or is an inline
  `:skein.spools.agents/roster` value) and is the one authoritative source of
  reviewer count, harnesses, and contracts for that review: combining it with
  `:reviewers`, `:members`, `:harnesses`, or `:contract` fails loudly. A
  roster review always synthesizes, from the same `roster-review-specs` data
  a workflow composition would consume.

  `:change-context` (a `:skein.spools.agents/change-context` value) is the
  caller-supplied diff surface — commit range, changed files, cheap code
  windows — injected into every reviewer prompt so reviewers read the diff
  instead of re-deriving it. The synthesizer never receives it (it weighs
  notes, not the diff).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L1449-L1532">Source</a></sub></p>

## <a name="skein.spools.agents/review-contract">`review-contract`</a>




Read-only reviewer contract text used as the workspace default for `agent review`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L54-L69">Source</a></sub></p>

## <a name="skein.spools.agents/roster->panel">`roster->panel`</a>
``` clojure
(roster->panel roster)
```
Function.

Convert a roster value into an equivalent single-round, target-blackboard
  panel: each reviewer becomes an independent seat whose contract is the seat
  brief, and the roster synthesizer (or the first reviewer's harness) becomes
  the panel synthesis. Pure — the roster is validated identically to
  `defroster!` input. A rounds=1 panel compiles to the independent review
  shape, so this is how `review!` is expressible over the panel primitive.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L1430-L1447">Source</a></sub></p>

## <a name="skein.spools.agents/roster-review-specs">`roster-review-specs`</a>
``` clojure
(roster-review-specs roster {:keys [target review-id change-context]})
```
Function.

Return a roster's review fan-out as plain, fully-built run specs
  (shape: `:skein.spools.agents/review-specs`).

  This is the one prompt-building source for roster reviews. `review!` spawns
  shuttle runs from these specs, and workflow authors map them onto
  `:subagent` gates without re-implementing the contract layering: `:harness`
  and `:prompt` become the gate's `shuttle/harness`/`shuttle/prompt`,
  `:attrs` merge into the gate's attributes, and the synthesizer gate
  depends on every reviewer gate. Specs are pure data built from the
  roster and the workspace base review contract; `:target` is the strand id
  under review (existence is checked where runs are spawned, not here).
  Unknown rosters and a blank target fail loudly.

  `roster` is a registered roster name **or an inline roster value** — a map
  conforming to `:skein.spools.agents/roster`, validated identically to
  `defroster!` input. Inline values are how parameterised compositions work:
  rosters are plain data, so pour-time code may filter, augment, or construct
  one and hand it straight to this seam (specs/attrs label it `:inline`;
  register under a name when attribution matters).

  Every call mints a `:review-pass` tag (override with a non-blank
  `:review-id`): reviewers prefix their notes with it and the synthesizer
  filters on it, so one pass's findings stay separable on a target that
  accumulates notes across rounds — run ids cannot serve here because
  workflow-composed synthesis is defined before any run exists.

  `:change-context` (an optional `:skein.spools.agents/change-context` value:
  `{:commit-range :files :windows}`) is the caller-supplied diff surface. When
  present it is injected into every reviewer prompt so reviewers read the
  changed files instead of re-deriving the diff; the synthesizer never carries
  it. Malformed change context fails loudly against its spec.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L1021-L1090">Source</a></sub></p>

## <a name="skein.spools.agents/rosters">`rosters`</a>
``` clojure
(rosters)
```
Function.

List registered reviewer rosters as full plain data.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L858-L862">Source</a></sub></p>

## <a name="skein.spools.agents/worker-contract">`worker-contract`</a>




Worker contract text appended to every shuttle preamble.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/agents/src/skein/spools/agents.clj#L71-L103">Source</a></sub></p>
