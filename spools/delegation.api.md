
-----
# <a name="skein.spools.delegation">skein.spools.delegation</a>


Agent coordination spool layered over the agent-run engine.




## <a name="skein.spools.delegation/about-doc">`about-doc`</a>




Structured manual returned by `agent about`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L123-L552">Source</a></sub></p>

## <a name="skein.spools.delegation/agent-op">`agent-op`</a>
``` clojure
(agent-op #:op{:keys [args argv]})
```
Function.

Dispatch parsed `strand agent` subcommands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L2247-L2277">Source</a></sub></p>

## <a name="skein.spools.delegation/agent-plan">`agent-plan`</a>
``` clojure
(agent-plan {:keys [input]})
```
Function.

Create a feature strand plus task/review children for agent work.

  The plan root is marked `kind "agent-plan"`; each child carries `kind`
  `"task"` or `"review"`. The terse task input fields `harness`, `cwd`, and
  `max-attempts` weave to the `agent-run/harness`, `agent-run/cwd`, and
  `agent-run/max-attempts` attributes `delegate` and `retry` read.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L2304-L2338">Source</a></sub></p>

## <a name="skein.spools.delegation/council!">`council!`</a>
``` clojure
(council! topic opts)
```
Function.

Convene a multi-agent council as a `:fresh`-blackboard panel (A7): its rounds
  are turn-as-run barrier rows and seats deliberate by posting to and reading a
  shared council strand across turns, then a synthesizer weighs the whole
  deliberation.

  Option input is validated by `:skein.spools.delegation/council-input`.

  Scalar convenience: `:seat-count n` mints N identical seats, each running the
  council-wide `:harness`. Rich control: `:seats [{:name :harness? :brief?}]`
  gives per-seat harness and perspective; `:seat-count` and `:seats` are mutually
  exclusive. Harness has no default (mirroring `delegate`) — a seat with neither
  its own `:harness` nor a council-wide `:harness` fails loudly. The synthesizer
  runs `:synthesizer` (a harness) or the first seat's harness. `:rounds` (default
  2) is the turn count; `:spawned-by` and `:cwd` ride onto every run.

  Returns `{:blackboard <shared strand id> :turns [[run-ids]...] :synthesizer
  <run id>}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L1740-L1806">Source</a></sub></p>

## <a name="skein.spools.delegation/defroster!">`defroster!`</a>
``` clojure
(defroster! roster-name roster)
```
Function.

Register or replace a named reviewer roster (weaver-lifetime state, so
  trusted startup config re-registers it like harness aliases and queries).

  Roster data is plain and spec-defined — see `:skein.spools.delegation/roster`:
  `{:seats [{:name :harness :brief :scope?} ...] :synthesis {:harness ...}?}`,
  the panel primitive's seat vocabulary (a roster is a single-round,
  target-blackboard panel; see `roster->panel`). Each seat is one independent
  read-only review run with its own precise brief; `:scope` is prompt-level
  confinement text. `:synthesis` overrides the harness of the synthesis run
  (default: first seat's harness). Malformed data fails loudly with spec
  explain data.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L982-L998">Source</a></sub></p>

## <a name="skein.spools.delegation/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the delegation op surface, pattern, and query.

  Claims neither agent-run preamble slot: the injected worker text is the
  workspace's call, so a workspace wanting this spool's task workflow registers
  `worker-contract` itself (see the README).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L2340-L2372">Source</a></sub></p>

## <a name="skein.spools.delegation/panel!">`panel!`</a>
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

  Options are validated by `:skein.spools.delegation/panel-input`. `:target` is
  required for a `:target` blackboard, `:review-id` tags notes and resumes,
  `:spawned-by` and `:cwd` ride onto every run.

  Returns `{:panel :blackboard :turns [[run-ids...]...] :synthesizer? :pass}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L1529-L1617">Source</a></sub></p>

## <a name="skein.spools.delegation/panel-specs">`panel-specs`</a>
``` clojure
(panel-specs panel {:keys [target review-id]})
```
Function.

Compile an **inline panel value** into plain, fully-built run specs
  (shape: `:skein.spools.delegation/panel-specs`). This is the one prompt-building
  source for panels; `panel!` spawns runs from these specs.

  `panel` is a map conforming to `:skein.spools.delegation/panel`, validated
  identically to `panel!` input (closed keys and uniqueness before spec
  conform). Defaults are applied here: `:turns {:rounds 1}`, `:blackboard
  :target`, per-seat `:continuity :fresh`.

  Options: `:target` is the blackboard strand id for a `:target` panel (a
  blank target fails loudly); a `:fresh` panel ignores it and embeds a board
  placeholder the spawner resolves after minting. `:review-id` overrides the
  minted `:pass` tag (a blank override fails loudly).

  Output `:turns` is a vector of turn rows (turn 1 first); each run spec is
  `{:name :harness :prompt :resume-prompt? :attrs :resume-ref?}` where
  `:resume-ref` is the seat index in the previous row this turn continues (only
  present for a `:continuity :resume` turn r>1). Every run spec stamps
  `panel/seat`, `panel/turn`, `panel/blackboard`, and
  `panel/pass`. `:synthesizer` is present unless `:synthesis` is
  absent or `:none`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L1437-L1527">Source</a></sub></p>

## <a name="skein.spools.delegation/prime-doc">`prime-doc`</a>




Run-first coordinator priming returned by `agent prime`.

  A selection over `about-doc`, not new prose: the traps, coordinator loop,
  and delegation policy an agent must load before delegating, without the
  verb-by-verb reference that `about` and `strand help agent` carry.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L554-L589">Source</a></sub></p>

## <a name="skein.spools.delegation/review!">`review!`</a>
``` clojure
(review!
 target-id
 {:keys [reviewers seat-count harnesses contract synthesize? spawned-by cwd roster change-context fanout-attrs],
  :or {seat-count 2},
  :as opts})
```
Function.

Spawn independent read-only reviewers for a target strand.

  `:roster` names a `defroster!` roster (or is an inline
  `:skein.spools.delegation/roster` value) and is the one authoritative source of
  reviewer count, harnesses, and contracts for that review: combining it with
  `:reviewers`, `:seat-count`, `:harnesses`, or `:contract` fails loudly. A
  roster review always synthesizes, from the same `roster-review-specs` data
  a workflow composition would consume.

  `:change-context` (a `:skein.spools.delegation/change-context` value) is the
  caller-supplied diff surface — commit range, changed files, cheap code
  windows — injected into every reviewer prompt so reviewers read the diff
  instead of re-deriving it. The synthesizer never receives it (it weighs
  notes, not the diff).

  A kanban card is never a valid target: findings append as notes on the
  target, and card notes stay lean for handover, so a card-targeted review
  fails loudly toward the card's task tier. The check reads only the
  `kanban/card` marker attribute — no kanban spool code is involved.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L1635-L1727">Source</a></sub></p>

## <a name="skein.spools.delegation/review-contract">`review-contract`</a>




Read-only reviewer contract text used as the workspace default for `agent review`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L79-L94">Source</a></sub></p>

## <a name="skein.spools.delegation/roster->panel">`roster->panel`</a>
``` clojure
(roster->panel roster)
```
Function.

Convert a roster value into an equivalent single-round, target-blackboard
  panel: a roster IS a panel's seat vector, so this only supplies the turn
  grid, the blackboard kind, per-seat `:continuity`, and the synthesis harness
  default (the first seat's). Pure — the roster is validated identically to
  `defroster!` input. A rounds=1 panel compiles to the independent review
  shape, so this is how `review!` is expressible over the panel primitive.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L1619-L1633">Source</a></sub></p>

## <a name="skein.spools.delegation/roster-review-specs">`roster-review-specs`</a>
``` clojure
(roster-review-specs roster {:keys [target review-id change-context]})
```
Function.

Return a roster's review fan-out as plain, fully-built run specs
  (shape: `:skein.spools.delegation/review-specs`).

  This is the one prompt-building source for roster reviews. `review!` spawns
  agent-run runs from these specs, and workflow authors map them onto
  `:subagent` gates without re-implementing the contract layering: `:harness`
  and `:prompt` become the gate's `agent-run/harness`/`agent-run/prompt`,
  `:attrs` merge into the gate's attributes, and the synthesizer gate
  depends on every reviewer gate. Specs are pure data built from the
  roster and the workspace base review contract; `:target` is the strand id
  under review. Existence and the kanban-card prohibition are checked where
  runs are spawned, not here: specs are pure data with no runtime, so a
  workflow composition consuming them enforces the card policy at its own
  spawn seam (as `review!` and `panel!` do). Unknown rosters and a blank
  target fail loudly.

  `roster` is a registered roster name **or an inline roster value** — a map
  conforming to `:skein.spools.delegation/roster`, validated identically to
  `defroster!` input. Inline values are how parameterised compositions work:
  rosters are plain data, so pour-time code may filter, augment, or construct
  one and hand it straight to this seam (specs/attrs label it `:inline`;
  register under a name when attribution matters).

  Every call mints a `:pass` tag (override with a non-blank
  `:review-id`): reviewers prefix their notes with it and the synthesizer
  filters on it, so one pass's findings stay separable on a target that
  accumulates notes across rounds — run ids cannot serve here because
  workflow-composed synthesis is defined before any run exists.

  `:change-context` (an optional `:skein.spools.delegation/change-context` value:
  `{:commit-range :files :windows}`) is the caller-supplied diff surface. When
  present it is injected into every reviewer prompt so reviewers read the
  changed files instead of re-deriving the diff; the synthesizer never carries
  it. Malformed change context fails loudly against its spec.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L1195-L1267">Source</a></sub></p>

## <a name="skein.spools.delegation/rosters">`rosters`</a>
``` clojure
(rosters)
```
Function.

List registered reviewer rosters as full plain data.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L1000-L1004">Source</a></sub></p>

## <a name="skein.spools.delegation/worker-contract">`worker-contract`</a>




Task-workflow contract this spool exports for the agent-run task-contract slot.

  The engine's own `generic-worker-contract` already rides every headless
  preamble; this is the delegation-specific remainder, which only makes sense
  for a run serving a task. Nothing registers it automatically: a workspace that
  wants this task workflow opts in with `agent-run/set-default-task-contract!`,
  which substitutes the served strand's id for `<task-id>`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/delegation/src/skein/spools/delegation.clj#L96-L121">Source</a></sub></p>
