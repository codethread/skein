# Task 17: Promote reviewed spec deltas

**Document ID:** `TASK-Olr-017`

## TASK-Olr-017.P1 Scope

Type: AFK

Merge the three reviewed feature deltas into their durable root specs after the integrated code contract is settled. This task owns root-spec promotion only; Task 26 owns human guidance and generated API references.

## TASK-Olr-017.P2 Must implement exactly

- **TASK-Olr-017.MI1:** Merge `DELTA-OlrDrt-001` into SPEC-004, removing superseded global reload, module-use, and current generation-accounting text rather than appending contradictory clauses.
- **TASK-Olr-017.MI2:** Merge `DELTA-OlrRepl-001` into SPEC-003, including `skein.api.registry.alpha`, the exact runtime surface disposition, module/contribution/reconcile shapes, direct owner mutation, and no targeted module deletion.
- **TASK-Olr-017.MI3:** Merge `DELTA-OlrAlpha-001` into SPEC-005, recording the one-time runtime-alpha rewrite and peer v7/v4/v2 next-marker exception without generalizing it.
- **TASK-Olr-017.MI4:** Update `devflow/README.md` only if root-spec index text changes. Mark each feature delta `Merged` after its durable text is present and internally consistent.
- **TASK-Olr-017.MI5:** Check all existing SPEC cross-references and preserved clause IDs. Retire superseded IDs explicitly where reuse would be ambiguous.

## TASK-Olr-017.P3 Done when

- **TASK-Olr-017.DW1:** Root specs describe one current lifecycle and contain every reviewed delta contract with no stale current `sync!`/`use!`/global `reload!` requirement.
- **TASK-Olr-017.DW2:** `make docs-check` and `git diff --check` pass; feature deltas are marked Merged.
- **TASK-Olr-017.DW3:** A targeted `rg` over `devflow/specs` finds old lifecycle names only in explicit retired/history statements reviewed in the task note.

## TASK-Olr-017.P4 Out of scope

- **TASK-Olr-017.OS1:** Do not rewrite human guides, regenerate API docs, edit peer docs, change code, tag releases, or update pins.

## TASK-Olr-017.P5 References

- **TASK-Olr-017.REF1:** Three reviewed feature deltas, root SPEC-003/004/005, and task-DAG review note `t9itj` M2/M4.
