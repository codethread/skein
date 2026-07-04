# Kanban spool

The kanban spool is the user-facing work board held entirely in Skein strands. It tracks **user↔agent** work: everything a user asks for becomes a `feature` card (occasionally grouped under an `epic`), and every agent working directly with a user works under a claimed card. It complements — never replaces — devflow runs, agent plans, and delegation, which all hang beneath cards.

## Model

Each card is one strand whose `kanban/status` places it in a board lane:

- **refinement** — an idea or undecided direction; never actionable until a human explicitly runs `kanban promote`.
- **pending** — the actionable queue; `kanban next` serves the oldest pending feature.
- **claimed** — work has started; the claim stamps who is driving it and where.
- **closed** — the strand is closed and `kanban/status` records the explicit outcome (`done`, `abandoned`, ...).

Card state lives under the `kanban/*` attribute topic:

| Attribute | Meaning |
| --- | --- |
| `kanban/card` | String `"true"` for card strands. |
| `kanban/type` | `feature` (default) or `epic` (grouping card; `parent-of` its features). |
| `kanban/status` | `refinement`, `pending`, `claimed`, or an explicit closed outcome. |
| `kanban/source` | Optional path or URL for design context (RFC, feature folder). |
| `kanban/note` | `"true"` on note strands (closed `parent-of` children of a card). |
| `kanban/handover` | `"true"` on handover notes. |
| `owner` | Who is driving the work; required at claim. |
| `branch` | The work branch; required at claim. |
| `worktree` | Optional worktree path. |

The card is the **work root**: feature plans, devflow runs, review strands, and task DAGs hang under it with `parent-of` edges, and the claim-time `branch`/`owner`/`worktree` stamp makes the whole subtree discoverable by branch (see the repo's `strand branches` convention). Shuttle runs are never tracked by kanban directly, but because delegated work hangs under card descendants, `strand subgraph <card-id>` (and future queries) can project every agent working under a feature.

**Relating work.** Relate cards or tasks to each other with `depends-on` edges (`strand update <a> --edge depends-on:<b>`); agents check the `:related` list in `kanban card <id>` when claiming or resuming so blockers and dependents surface without extra queries.

**Viewing work in a branch.** `strand branches "$(git branch --show-current)"` shows the feature cards and their substrands stamped on the current branch.

## Notes, handovers, and crash recovery

Notes are closed child strands rather than attributes, so concurrent agents never race a read-merge-write cycle and every note keeps its own timestamp and author:

```sh
strand kanban note <id> "Decided X because Y" --author claude
strand kanban note <id> --handover --author claude "Done: impl+tests. Next: docs. Validation: clojure -M:test green. Gotcha: reload weaver after merge."
```

A **handover** note is the stop/interruption contract: what is done, what is next, validation state, gotchas, and where the work lives. The resume path for a cold agent needs no prior context:

1. `strand kanban board` — claimed cards show owner, branch, worktree, and their latest handover.
2. `strand kanban card <id>` — the full card: notes (newest first), latest handover, active work subtree, and the ready frontier.

## CLI op

Install registers one operation:

```sh
strand kanban about
strand kanban add "Feature idea" [--body "Longer context"] [--source devflow/rfcs/...] [--status pending|refinement] [--type feature|epic] [--epic <epic-id>]
strand kanban board
strand kanban card <id>
strand kanban next
strand kanban promote <id>
strand kanban claim <id> --owner <name> --branch <branch> [--worktree /path]
strand kanban note <id> <text> [--author <name>] [--handover]
strand kanban finish <id> [--outcome done|abandoned]
```

`board` returns the grouped snapshot (epics, refinement/pending/claimed lanes, closed count); active cards with a status outside the known lanes surface in `unknown-status` rather than being hidden. It also returns `needs-review`: a vector aggregated across all claimed feature cards of `{:card :item}` entries (plus `:branch` from the claim stamp), one per card descendant that is active, in the engine ready frontier, and marks human review (`hitl`/`workflow/hitl` true, or `kind` `review`), sorted by card id then item id — the always-present cross-card review queue. `next` returns the oldest active pending feature (epics are never served). `promote` is the explicit human command that moves a refinement card into the pending lane. `claim` fails loudly without `--owner` and `--branch` and refuses epics; `--worktree` is optional for direct work in the main checkout. `finish` closes the card with the outcome status.

`card` returns the resume view (card, notes, latest handover, active work, ready frontier) plus `related`: a vector of `{:relation :strand}` entries for every `depends-on` edge touching the card — `depends-on` when the card is the dependent, `depended-on-by` when it is the dependency — sorted by other-endpoint id.

For bulk authoring, the `kanban-batch` weave pattern creates pending feature cards with bodies and `depends-on` edges atomically:

```sh
strand weave --pattern kanban-batch --input '{"items":[{"key":"design","title":"Design feature","body":"..."},{"key":"docs","title":"Write docs","deps":["design","existing-strand-id"]}]}'
```

## Queries

Install also registers:

- `kanban-cards` — all kanban card strands.
- `kanban-unstarted` — active cards with `kanban/status=pending`.
