# Alpha Surface delta for agent-layer-rename

**Document ID:** `SPEC-Alr-002` **Root spec:** [alpha-surface.md](../../../specs/alpha-surface.md) (`SPEC-005`) **Feature:** [../proposal.md](../proposal.md) (`PROP-Alr-001`) **Rename table:** [../brief.md](../brief.md) **Status:** Planned **Last Updated:** 2026-07-09

## SPEC-Alr-002.P1 Summary

This is the F1 mechanical-rename delta (no behavior change). The alpha-surface index names the renamed surface in two places: the classpath-shipped reference-spool list (`reed`) and the repo-local approved-spool catalog (`spools/shuttle`/treadle/`spools/agents`). Both are token renames. The delta also ADDS one contract statement recording the frozen trained-vocabulary surface, so the rename's deliberate exceptions are documented where the surface catalog lives (`PROP-Alr-001.G5`). Distribution tiers are unchanged — namespace family ≠ distribution tier (`PROP-Alr-001.P5.H8`): `executors/shell` stays classpath-shipped, `executors/subagent` stays approved-local-root.

## SPEC-Alr-002.P2 Contract changes

- **SPEC-Alr-002.CC1** (edit, `SPEC-005.C3`, line 13): rename the shipped reference spool `reed` → `executors/shell` in the in-contract list, keeping alphabetical order (it sorts between `ephemeral` and `guild`).
  - **Old fragment:** ``…`batteries`, `bobbin`, `carder`, `ephemeral`, `guild`, `reed`, `roster`, `selvage`, and `workflow` at [`spools/*.md`](../../spools/README.md).``
  - **New fragment:** ``…`batteries`, `bobbin`, `carder`, `ephemeral`, `executors/shell`, `guild`, `roster`, `selvage`, and `workflow` at [`spools/*.md`](../../spools/README.md) (the shell executor's contract doc nests one level deeper, at `spools/executors/shell.md`).``
  - The parenthetical is required precision, not a semantic change: after the doc move (`PROP-Alr-001.S4`) the shell executor's doc no longer sits flat under `spools/*.md`, so the descriptor would otherwise misdescribe its path. `executors/shell` stays in-contract and classpath-shipped (`PROP-Alr-001.P5.H8`).

- **SPEC-Alr-002.CC2** (edit, `SPEC-005.C4`, line 14): rename the repo-local approved-spool roots and the treadle mention.
  - **Old:** `Repo-local approved spools in this repository (the `spools/shuttle` root — which also hosts treadle — plus `spools/agents`, `spools/chime`, and `spools/kanban`) and externally distributed spools (devflow) are userland, not shipped alpha surface. Their READMEs/docs are their own contracts with their own cadence, outside this line.`
  - **New:** `Repo-local approved spools in this repository (the `spools/agent-run` root — which also hosts the subagent executor — plus `spools/delegation`, `spools/chime`, and `spools/kanban`) and externally distributed spools (devflow) are userland, not shipped alpha surface. Their READMEs/docs are their own contracts with their own cadence, outside this line.`
  - `spools/shuttle` → `spools/agent-run`; `treadle` → `the subagent executor` (its source relocates to `spools/agent-run/src/skein/spools/executors/subagent.clj`, so the agent-run root still hosts it, `PROP-Alr-001.P5.H8`); `spools/agents` → `spools/delegation`. `chime`/`kanban`/devflow untouched.

- **SPEC-Alr-002.CC3** (ADD, `SPEC-005.P?` — new contract item appended to the userland/approved-spool section that contains `SPEC-005.C4`): record the frozen trained-vocabulary surface as a new contract statement. This is `PROP-Alr-001.G5` and the brief's "frozen surfaces" rows. Verbatim text to add:

  > The trained-vocabulary agent surface is frozen and does not follow the concept-naming rename: the `strand agent …` CLI verbs, the `agent-plan` weave pattern, the `agent-failures` named query, and the `:subagent` workflow-gate waiter value keep their current names. These are names agents and downstream configs are trained on, or whose blast radius is deliberately bounded (the `:subagent` waiter is pinned by `skein.spools.devflow`), so they stay put even as the spool namespaces and durable attribute keys beneath them move to concept-named vocabularies.

  Rationale for placement: `SPEC-005` is the in-contract/internal surface index and already enumerates the approved userland spools (`SPEC-005.C4`), so the "these names are pinned across the rename" guarantee belongs beside that catalog. Assign the next free `SPEC-005.Cn` number at edit time.

## SPEC-Alr-002.P3 Flagged (out of scope for F1)

- **SPEC-Alr-002.F1:** None. All three items are token renames or an additive statement of an already-decided freeze; no alpha-surface behavior or tier boundary changes. The tier assignments (`executors/shell` classpath, `executors/subagent` approved-local-root) are explicitly held constant (`PROP-Alr-001.P5.H8`).
