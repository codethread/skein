# Task 7: Promote shipped specs

**Document ID:** `TASK-007` **Configuration identification:** `TASK-007` is the seventh task for `user-daemon-home`. Prefix every nested point ID with `TASK-007`.

## TASK-007.P1 Scope

Type: AFK

After implementation and validation, promote the feature-local contract changes into root specs and prepare the feature for archive.

## TASK-007.P2 Must implement exactly

- **TASK-007.MI1:** Read implemented behavior, feature deltas, and root specs before editing canonical specs.
- **TASK-007.MI2:** Merge `devflow/feat/user-daemon-home/specs/daemon-runtime.delta.md` into `devflow/specs/daemon-runtime.md`.
- **TASK-007.MI3:** Merge `devflow/feat/user-daemon-home/specs/cli.delta.md` into `devflow/specs/cli.md`.
- **TASK-007.MI4:** Merge `devflow/feat/user-daemon-home/specs/repl-api.delta.md` into `devflow/specs/repl-api.md`.
- **TASK-007.MI5:** Update root specs to remove stale `db`, `--config-path`, DB-path `open!`, DB-hashed metadata, and public `daemon start --config` contracts.
- **TASK-007.MI6:** Update `devflow/README.md` root spec index if status/summaries change.
- **TASK-007.MI7:** Mark feature-local delta files as merged if local convention supports a status update, and add final developer notes summarizing shipped spec promotion.
- **TASK-007.MI8:** Leave archive movement to the devflow finish/archive procedure unless the user explicitly asks this task runner to archive immediately.

## TASK-007.P3 Done when

- **TASK-007.DW1:** Root specs describe the implemented user-daemon-home behavior as canonical current contract.
- **TASK-007.DW2:** Feature-local deltas no longer contain unmerged shipped contract changes.
- **TASK-007.DW3:** `rg "--config-path|\bdb\b|open!|database_path|daemon start --config" devflow/specs README.md AGENTS.md` does not reveal stale public contract text except where intentionally discussed as removed history.
- **TASK-007.DW4:** Project validation from Task 6 still passes after spec/doc promotion if any docs affect tests/smoke.

## TASK-007.P4 Out of scope

- **TASK-007.OS1:** Do not implement missing code behavior in this spec-promotion task; if behavior is not implemented, record it as cut/deferred before promotion.
- **TASK-007.OS2:** Do not archive the feature folder unless running the separate devflow finish/archive procedure.
- **TASK-007.OS3:** Do not promote unimplemented planned behavior into root specs.

## TASK-007.P5 References

- **TASK-007.REF1:** `UDH-PLAN-001.P5`, `UDH-PLAN-001.P6`, `UDH-PLAN-001.DN5`.
- **TASK-007.REF2:** Feature deltas under `devflow/feat/user-daemon-home/specs/`.
- **TASK-007.REF3:** Root specs under `devflow/specs/` and `devflow/README.md`.
