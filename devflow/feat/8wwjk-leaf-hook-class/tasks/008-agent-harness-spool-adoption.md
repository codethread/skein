# Task 8: agent-harness.spool adoption (agent-run/delegation/bench) + release tag

**Document ID:** `TASK-Lhc-008`

## TASK-Lhc-008.P1 Scope

Type: AFK

Adopt per-leaf classes across all three roots of
`~/dev/projects/agent-harness.spool` (pinned v11 in skein-src): `agent-run`,
`delegation` (the `agent` op family), and `bench`. The motivating fix: `agent`
today registers op-wide `:deadline-class :unbounded` for the sake of its
awaiting verbs — under leaf classes, only the blocking leaves (`await`,
`flow-await`-style verbs, interactive attach) declare `:unbounded`; every other
verb (`ps`, `status`, `logs`, `rosters`, `harnesses`, `spawn`, `delegate`,
`retry`, `kill`, `review`, ...) gets its true class (`:read` or `:mutating`,
`:standard`).

## TASK-Lhc-008.P2 Must implement exactly

- **TASK-Lhc-008.MI1:** Every op leaf in all three roots declares both classes
  in its arg-spec; no registration-opts classes remain; doc-only leaves
  included.
- **TASK-Lhc-008.MI2:** Handler `:subcommand` consumption updated to path
  vectors; any positional-action grammars enumerated in a migration matrix in
  the worklog, folded to real nesting where mechanical, left with a note where
  not.
- **TASK-Lhc-008.MI3:** Root docs/contracts updated (`strand about agent`
  prose source, bench manual) where they state op-wide classes.
- **TASK-Lhc-008.MI4:** Suite green against the feature checkout of skein-src
  (dep wiring per repo convention; state it in the worklog).
- **TASK-Lhc-008.MI5:** Release ceremony: commits pushed on the default branch;
  annotated tag `v12` pushed. Record tag + SHA in the worklog note.

## TASK-Lhc-008.P3 Done when

- **TASK-Lhc-008.DW1:** All three roots' suites cold-green against the feature
  checkout; repo gates green.
- **TASK-Lhc-008.DW2:** `v12` annotated tag pushed; worklog carries tag + SHA.

## TASK-Lhc-008.P4 Out of scope

- **TASK-Lhc-008.OS1:** Any skein-src edit; other spool repos.
