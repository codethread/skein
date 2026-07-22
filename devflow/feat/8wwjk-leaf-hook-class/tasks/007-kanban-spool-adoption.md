# Task 7: kanban.spool adoption + release tag

**Document ID:** `TASK-Lhc-007`

## TASK-Lhc-007.P1 Scope

Type: AFK

Adopt per-leaf classes and real nesting in the kanban spool
(`~/dev/projects/kanban.spool`, pinned v6 in skein-src). The known migration:
`kanban` declares leaf classes on every verb (`about`/`board`/`card`/`next`/
`prime` `:read`; `add`/`claim`/`finish`/`note`/`priority`/`promote`/`reopen`/
`review`/`rework` `:mutating`; all `:standard`), and `kanban task` migrates from
the positional `<action> add|list` hack to real nested subcommands
(`task add <feature> ...` `:mutating`, `task list <feature>` `:read`) with its
returns tree mirrored and handler dispatch reading the `:subcommand` path
vector.

## TASK-Lhc-007.P2 Must implement exactly

- **TASK-Lhc-007.MI1:** Every leaf declares both classes in the arg-spec; no
  registration-opts classes remain.
- **TASK-Lhc-007.MI2:** `task` nesting per above; the board/tracker projections
  and `print-board!` keep working (they join tasks by attributes, not argv).
- **TASK-Lhc-007.MI3:** Handler `:subcommand` consumption updated to path
  vectors throughout; docs (spool README/contract) updated.
- **TASK-Lhc-007.MI4:** Suite green against the feature checkout of skein-src
  (wire the dep per this repo's own convention — local-root dev override or
  GITLIBS-style pin — and say which you used in the worklog).
- **TASK-Lhc-007.MI5:** Release ceremony: commit(s) on the default branch,
  pushed; annotated tag `v7` on the release commit, pushed. Record the tag SHA
  in the worklog note.

## TASK-Lhc-007.P3 Done when

- **TASK-Lhc-007.DW1:** Spool suite cold-green against the feature checkout;
  repo's own lint/format gates green if present.
- **TASK-Lhc-007.DW2:** `v7` annotated tag pushed; worklog carries tag + SHA.

## TASK-Lhc-007.P4 Out of scope

- **TASK-Lhc-007.OS1:** Any skein-src edit (pin bumps are Task 10); other spool
  repos.
