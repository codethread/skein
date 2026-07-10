
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
  execution strands hang beneath the card with `parent-of` edges — the kanban
  spool complements the engines that produce them, it does not replace them. Notes are closed child note strands, so a cold agent can
  self-discover in-flight work: `kanban board` -> `kanban card <id>` ->
  the doing-task and its latest note.




## <a name="skein.spools.kanban/about">`about`</a>
``` clojure
(about)
```
Function.

Return the kanban convention and installed helper surface.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L761-L818">Source</a></sub></p>

## <a name="skein.spools.kanban/add!">`add!`</a>
``` clojure
(add! title flags)
```
Function.

Create a kanban card in the pending (or refinement) lane.

  `--type epic` creates a grouping epic; `--epic <id>` hangs a new feature
  under an existing epic with a parent-of edge.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L148-L166">Source</a></sub></p>

## <a name="skein.spools.kanban/board">`board`</a>
``` clojure
(board)
```
Function.

Return the grouped board snapshot: epics, feature lanes, closed count.

  Claimed and in-review cards carry their doing-task so a cold agent can see in
  one call who is working where and how to pick up interrupted work.
  `:needs-review` aggregates the human-review frontier across claimed and
  in-review cards.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L635-L681">Source</a></sub></p>

## <a name="skein.spools.kanban/board-str">`board-str`</a>
``` clojure
(board-str {:keys [epics refinement pending claimed in_review needs-review closed unknown-status]})
```
Function.

Render a `board` result map as a stacked-lane ASCII board string.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L735-L754">Source</a></sub></p>

## <a name="skein.spools.kanban/card-view">`card-view`</a>
``` clojure
(card-view id)
```
Function.

Return one card joined to its notes, tasks, work, and frontier.

  This is the resume entry point: everything an agent needs to continue a
  card lives here. `:tasks` projects the feature card's child tasks with the
  four derived statuses (empty for cards that carry no task tier).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L541-L560">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L280-L300">Source</a></sub></p>

## <a name="skein.spools.kanban/finish!">`finish!`</a>
``` clojure
(finish! id flags)
```
Function.

Close a claimed or in_review kanban card with an explicit outcome status.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L318-L331">Source</a></sub></p>

## <a name="skein.spools.kanban/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install the kanban op, batch pattern, and board queries into the active weaver.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L979-L1005">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L204-L231">Source</a></sub></p>

## <a name="skein.spools.kanban/kanban-op">`kanban-op`</a>
``` clojure
(kanban-op #:op{:keys [args]})
```
Function.

Dispatch parsed `strand kanban ...` subcommands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L959-L977">Source</a></sub></p>

## <a name="skein.spools.kanban/next-card">`next-card`</a>
``` clojure
(next-card)
```
Function.

Return the highest-priority (p1 first) oldest active pending feature card, or nil.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L581-L590">Source</a></sub></p>

## <a name="skein.spools.kanban/note!">`note!`</a>
``` clojure
(note! id text flags)
```
Function.

Append a note to a card via the blessed notes relation.

  The note rides the shared `notes` edge (`skein.api.notes.alpha/note!`) with
  `kanban/note`, `kind`, and optional `author` as decorating attrs, so
  concurrent agents never race a read-merge-write cycle and every note keeps
  its own timestamp and author. Note as you go — a resuming agent reads the
  doing-task and its latest note from `kanban card <id>` alone.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L442-L461">Source</a></sub></p>

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
  pick-up flow, note discipline, adjacent-work awareness, and branch
  visibility that an agent needs before touching the board.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L820-L890">Source</a></sub></p>

## <a name="skein.spools.kanban/print-board!">`print-board!`</a>
``` clojure
(print-board!)
```
Function.

Print the live board as ASCII; the human view for `mill weaver repl`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L756-L759">Source</a></sub></p>

## <a name="skein.spools.kanban/promote!">`promote!`</a>
``` clojure
(promote! id)
```
Function.

Move a refinement card into the pending lane (an explicit human act).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L260-L266">Source</a></sub></p>

## <a name="skein.spools.kanban/request-review!">`request-review!`</a>
``` clojure
(request-review! id)
```
Function.

Move a claimed kanban card into the in_review lane.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L302-L308">Source</a></sub></p>

## <a name="skein.spools.kanban/rework!">`rework!`</a>
``` clojure
(rework! id)
```
Function.

Move an in_review kanban card back to claimed for rework.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L310-L316">Source</a></sub></p>

## <a name="skein.spools.kanban/set-priority!">`set-priority!`</a>
``` clojure
(set-priority! id priority)
```
Function.

Set an active card's priority (p1 highest urgency .. p4 someday).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L268-L278">Source</a></sub></p>

## <a name="skein.spools.kanban/task-add!">`task-add!`</a>
``` clojure
(task-add! feature-id title flags)
```
Function.

Create a task strand under a feature card via a `parent-of` edge.

  `--depends-on <id>` is repeatable and lays the same `depends-on` edges that
  are the concurrency DAG and drive the derived `blocked`/`ready` split; task
  status is never stored.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L398-L418">Source</a></sub></p>

## <a name="skein.spools.kanban/task-list">`task-list`</a>
``` clojure
(task-list feature-id)
```
Function.

Project a feature card's tasks with their derived statuses.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L420-L427">Source</a></sub></p>

## <a name="skein.spools.kanban/task-op">`task-op`</a>
``` clojure
(task-op {:keys [action feature title]} flags)
```
Function.

Dispatch a parsed `kanban task ...` action, failing loudly on an unknown one.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/kanban/src/skein/spools/kanban.clj#L429-L436">Source</a></sub></p>
