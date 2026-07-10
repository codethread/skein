
-----
# <a name="skein.spools.kanban">skein.spools.kanban</a>


User-facing kanban board over Skein strands.

  Cards are the user<->agent tracking surface: everything a user asks for is a
  `feature` card (occasionally grouped under an `epic`), and every agent
  working directly with a user works under a claimed card. All card state
  lives under `kanban/*` attributes; `kanban/status` is the board lane
  (`refinement` -> `pending` -> `claimed` -> `in_review` -> explicit closed outcome) and
  `kanban/priority` (p1 immediate blocker .. p4 someday, default p3) orders
  lanes and `kanban next`.

  Cards are work roots: claiming stamps `owner`/`branch`/`worktree`, and
  plans, devflow runs, and task DAGs hang beneath the card with `parent-of`
  edges — the kanban spool complements those workflows, it does not replace
  them. Notes and handovers are closed child note strands, so a cold agent
  can self-discover in-flight work: `kanban board` -> `kanban card <id>` ->
  latest handover.




## <a name="skein.spools.kanban/about">`about`</a>
``` clojure
(about)
```
Function.

Return the kanban convention and installed helper surface.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L645-L700">Source</a></sub></p>

## <a name="skein.spools.kanban/add!">`add!`</a>
``` clojure
(add! title flags)
```
Function.

Create a kanban card in the pending (or refinement) lane.

  `--type epic` creates a grouping epic; `--epic <id>` hangs a new feature
  under an existing epic with a parent-of edge.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L138-L156">Source</a></sub></p>

## <a name="skein.spools.kanban/board">`board`</a>
``` clojure
(board)
```
Function.

Return the grouped board snapshot: epics, feature lanes, closed count.

  Claimed cards carry their latest handover so a cold agent can see in one
  call who is working where and how to pick up interrupted work.
  `:needs-review` aggregates the human-review frontier across claimed and
  in-review cards.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L518-L564">Source</a></sub></p>

## <a name="skein.spools.kanban/board-str">`board-str`</a>
``` clojure
(board-str {:keys [epics refinement pending claimed in_review needs-review closed unknown-status]})
```
Function.

Render a `board` result map as a stacked-lane ASCII board string.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L611-L638">Source</a></sub></p>

## <a name="skein.spools.kanban/card-view">`card-view`</a>
``` clojure
(card-view id)
```
Function.

Return one card joined to its notes, latest handover, work, and frontier.

  This is the resume entry point: everything an agent needs to continue a
  card lives here.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L427-L445">Source</a></sub></p>

## <a name="skein.spools.kanban/claim!">`claim!`</a>
``` clojure
(claim! id flags)
```
Function.

Claim a pending feature card, stamping the work-root attributes.

  `--owner` and `--branch` are mandatory so every claimed card answers who is
  driving it and on which branch; `--worktree` is optional (direct work in the
  main checkout has no separate worktree). Epics group work and are never
  claimed themselves.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L270-L290">Source</a></sub></p>

## <a name="skein.spools.kanban/finish!">`finish!`</a>
``` clojure
(finish! id flags)
```
Function.

Close a claimed or in_review kanban card with an explicit outcome status.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L308-L321">Source</a></sub></p>

## <a name="skein.spools.kanban/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the kanban op, batch pattern, and board queries into the active weaver.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L853-L879">Source</a></sub></p>

## <a name="skein.spools.kanban/kanban-batch">`kanban-batch`</a>
``` clojure
(kanban-batch {:keys [input]})
```
Function.

Create pending feature cards with bodies and depends-on edges.

  Input shape: {:items [{:key "slug" :title "Title" :body "optional"
  :priority "p1|p2|p3|p4 (optional, default p3)"
  :deps ["sibling-key-or-existing-strand-id"]}]}. `deps` values matching sibling
  keys become batch-local edges; all other values are treated as durable strand
  ids and fail loudly if absent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L194-L221">Source</a></sub></p>

## <a name="skein.spools.kanban/kanban-op">`kanban-op`</a>
``` clojure
(kanban-op #:op{:keys [args]})
```
Function.

Dispatch parsed `strand kanban ...` subcommands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L834-L851">Source</a></sub></p>

## <a name="skein.spools.kanban/next-card">`next-card`</a>
``` clojure
(next-card)
```
Function.

Return the highest-priority (p1 first) oldest active pending feature card, or nil.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L466-L475">Source</a></sub></p>

## <a name="skein.spools.kanban/note!">`note!`</a>
``` clojure
(note! id text flags)
```
Function.

Append a note (or `--handover` note) to a card via the blessed notes relation.

  The note rides the shared `notes` edge (`skein.api.notes.alpha/note!`) with
  `kanban/note`, `kind`, and optional `kanban/handover`/`author` as decorating
  attrs, so concurrent agents never race a read-merge-write cycle and every note
  keeps its own timestamp and author. A handover note is the crash/stop
  contract: record what is done, what is next, validation state, and gotchas so
  any agent can resume from `kanban card <id>` alone.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L327-L349">Source</a></sub></p>

## <a name="skein.spools.kanban/prime">`prime`</a>
``` clojure
(prime)
```
Function.

Return the full agent-priming payload for working the kanban board.

  The single source of truth for kanban usage discipline: repo agent docs
  point here (`strand kanban prime`) rather than duplicating conventions that
  then drift from the spool. A superset of `about` — it reuses the same lane,
  attribute, command, and pattern surface and adds the working agreement,
  pick-up flow, notes/handover discipline, adjacent-work awareness, and branch
  visibility that an agent needs before touching the board.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L702-L773">Source</a></sub></p>

## <a name="skein.spools.kanban/print-board!">`print-board!`</a>
``` clojure
(print-board!)
```
Function.

Print the live board as ASCII; the human view for `mill weaver repl`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L640-L643">Source</a></sub></p>

## <a name="skein.spools.kanban/promote!">`promote!`</a>
``` clojure
(promote! id)
```
Function.

Move a refinement card into the pending lane (an explicit human act).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L250-L256">Source</a></sub></p>

## <a name="skein.spools.kanban/request-review!">`request-review!`</a>
``` clojure
(request-review! id)
```
Function.

Move a claimed kanban card into the in_review lane.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L292-L298">Source</a></sub></p>

## <a name="skein.spools.kanban/rework!">`rework!`</a>
``` clojure
(rework! id)
```
Function.

Move an in_review kanban card back to claimed for rework.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L300-L306">Source</a></sub></p>

## <a name="skein.spools.kanban/set-priority!">`set-priority!`</a>
``` clojure
(set-priority! id priority)
```
Function.

Set an active card's priority (p1 highest urgency .. p4 someday).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L258-L268">Source</a></sub></p>
