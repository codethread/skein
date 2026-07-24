# Task 7: Prepare sibling spool conversions

**Document ID:** `TASK-Dsp-007`

## TASK-Dsp-007.P1 Scope

Type: AFK

Prepare the Phase B branches for devflow.spool, kanban.spool, and agent-harness.spool: export `def spool`, delete the `module` export, and convert each repo's own consuming surfaces. This is the pre-stamp work the coordinator may run before the Skein `v1` stamp exists. It stops short of raising `:skein/min` floors and publishing markers, so nothing here assumes a marked core.

Provision these linked worktrees from each repo's then-current main after
Phase A lands:

- `/Users/ct/dev/projects/devflow.spool__uwnzl-def-spool-convention` on
  `codex/uwnzl-def-spool-convention`
- `/Users/ct/dev/projects/kanban.spool__uwnzl-def-spool-convention` on
  `codex/uwnzl-def-spool-convention`
- `/Users/ct/dev/projects/agent-harness.spool__uwnzl-def-spool-convention` on
  `codex/uwnzl-def-spool-convention`

Owned files are the exported spool namespaces, `.skein/init.clj`, their direct
fixtures/helpers, and the repo-local prose that documents activation:

- devflow: `src/ct/spools/devflow.clj`, `.skein/init.clj`,
  `test/ct/spools/devflow_test.clj`, `README.md`, and `devflow.md`
- kanban: `src/ct/spools/kanban.clj`, `.skein/init.clj`,
  `.skein/peering_adapter.clj`, `test/ct/spools/kanban_peering_test.clj`,
  `README.md`, `kanban.md`, and `kanban.cookbook.md`
- agent-harness: `agent-run/src/ct/spools/agent_run.clj`,
  `delegation/src/ct/spools/delegation.clj`,
  `bench/src/ct/spools/bench.clj`, `.skein/init.clj`,
  `test/ct/spools/test_support.clj`, and the four component `README.md` files

Before editing, refresh this inventory with `git grep` in each worktree and
record any additional direct fixture or activation caller on `l5lwo`; do not
expand into unrelated sibling code.

## TASK-Dsp-007.P2 Must implement exactly

- **TASK-Dsp-007.MI1:** In each of the three sibling repos, add the required `(def spool …)` export and delete every existing `module` export; record a missing expected legacy export rather than inventing a compatibility alias.
- **TASK-Dsp-007.MI2:** Convert each repo's own consuming surfaces — workspace config, fixtures, activation helpers, and docs — to the convention.
- **TASK-Dsp-007.MI3:** Run each repo's own suite and quality gates against Phase-A skein-src to prove the conversion holds under per-key precedence.
- **TASK-Dsp-007.MI4:** Leave each branch prepared and reviewed but unpublished; record the exact candidate commits for Task 8.

## TASK-Dsp-007.P3 Done when

- **TASK-Dsp-007.DW1:** All three sibling branches convert cleanly and their own gates are green against Phase-A skein-src.
- **TASK-Dsp-007.DW2:** No `:skein/min "v1"` floor is set and no marker is published.
- **TASK-Dsp-007.DW3:** Candidate commits and gate evidence are recorded on kanban task `l5lwo` and the plan's Developer Notes.

## TASK-Dsp-007.P4 Out of scope

- **TASK-Dsp-007.OS1:** Raising `:skein/min` floors, cutting markers, or any publish — that is Task 8, gated on the stamp (Task 6).
- **TASK-Dsp-007.OS2:** Skein pin changes and grammar-key removal (Phase C, Task 9).

## TASK-Dsp-007.P5 References

- **TASK-Dsp-007.REF1:** `PLAN-Dsp-001.PH-B`, `.AA7`, `.CM3`; kanban task `l5lwo`.
- **TASK-Dsp-007.REF2:** Sibling repos and worktrees listed in P1; close-out shape from epic `waq0l` (`rtnfv`).
