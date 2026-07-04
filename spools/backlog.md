# Backlog spool

The backlog spool is a small repo-local convention that keeps `BACKLOG.md` as the human-visible feature queue while Skein strands hold executable state and audit history.

## Model

`BACKLOG.md` is organized into three sections:

- **In progress** — items that already have a backlog strand and tracked work in Skein.
- **Ready** — items ready to turn into a strand and delegate.
- **Refinement** — ideas or decisions that must only be acted on after an explicit human command.

Each Markdown checkbox row in **In progress** or accepted **Ready** work points at one backlog item strand:

```md
- [ ] `3rwu8` Some feature idea, maybe referencing an RFC or feature folder
```

The strand carries:

| Attribute | Meaning |
| --- | --- |
| `backlog/item` | String `"true"` for backlog item strands. |
| `backlog/status` | `pending`, `claimed`, `done`, or another explicit outcome such as `abandoned`. |
| `backlog/file` | Usually `BACKLOG.md`. |
| `kind` | `feature` for item roots. |

Feature plans, devflow runs, review strands, and task DAGs should hang under the backlog item strand with `parent-of` edges. The backlog item is the audit root.

## CLI op

Install registers one operation:

```sh
strand backlog about
strand backlog add "Feature idea" [--body "Longer context"] [--source devflow/rfcs/...]
strand backlog next
strand backlog claim <id> [--owner agent] [--branch feature-branch] [--worktree /path]
strand backlog finish <id> [--outcome done|abandoned]
strand backlog sync
```

`next` returns the first unchecked, active, `pending` backlog item in file order from the actionable backlog sections. `claim` marks it `claimed` but leaves the checkbox unchecked. `finish` closes the strand and checks the row. `sync` fails loudly if the Markdown file and graph disagree. Refinement items are not actionable until a human explicitly moves or adds them through the backlog flow.

## Queries

Install also registers:

- `backlog-items` — all backlog item strands.
- `backlog-unstarted` — active backlog items with `backlog/status=pending`.
