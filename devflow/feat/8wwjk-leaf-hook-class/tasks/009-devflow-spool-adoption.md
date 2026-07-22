# Task 9: devflow.spool adoption + release tag

**Document ID:** `TASK-Lhc-009`

## TASK-Lhc-009.P1 Scope

Type: AFK

Adopt per-leaf classes in `~/dev/projects/devflow.spool` (pinned v3 in
skein-src): every op/guidance surface it registers declares both classes on its
leaves (guidance/describe/status reads `:read :standard`; run-mutating verbs
`:mutating :standard`; any blocking await leaf `:unbounded`).

## TASK-Lhc-009.P2 Must implement exactly

- **TASK-Lhc-009.MI1:** Every leaf declares both classes in its arg-spec; no
  registration-opts classes; `:subcommand` consumption updated to path vectors.
- **TASK-Lhc-009.MI2:** Positional-action grammars enumerated (matrix in
  worklog); folded where mechanical.
- **TASK-Lhc-009.MI3:** Suite green against the feature checkout (dep wiring
  per repo convention; state it).
- **TASK-Lhc-009.MI4:** Release ceremony: commits pushed; annotated tag `v4`
  pushed. Record tag + SHA in the worklog note.

## TASK-Lhc-009.P3 Done when

- **TASK-Lhc-009.DW1:** Suite cold-green against the feature checkout; repo
  gates green.
- **TASK-Lhc-009.DW2:** `v4` annotated tag pushed; worklog carries tag + SHA.

## TASK-Lhc-009.P4 Out of scope

- **TASK-Lhc-009.OS1:** Any skein-src edit; other spool repos.
