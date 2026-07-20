# Task 15: Convert devflow route registration

**Document ID:** `TASK-Olr-015`

## TASK-Olr-015.P1 Scope

Type: AFK

In a linked feature worktree for `/Users/ct/dev/projects/devflow.spool`, convert stage/cycle workflow declarations to owner-complete replacement against the Skein feature worktree while preserving live route re-pointing.

## TASK-Olr-015.P2 Must implement exactly

- **TASK-Olr-015.MI1:** Replace top-level plus install-time duplicate `register-workflows!` publication with one stable owner contribution/reconcile path.
- **TASK-Olr-015.MI2:** Preserve the current binding contract: a workflow already in progress resolves the current constructor symbol when it reaches a later named `:next` stage. Poured strands and completed stages are not rewritten.
- **TASK-Olr-015.MI3:** Complete-owner omission removes a route from the effective registry and makes a later transition to that route fail loudly with owner/status diagnostics.
- **TASK-Olr-015.MI4:** Preserve command/workflow introspection, guidance data, stage projections, revision loops, task gates, and all public devflow helper contracts.
- **TASK-Olr-015.MI5:** Inspect the repository's own `.skein/init.clj` reported stale by review note `a22m2` F10. Fix it if it is intended supported config; otherwise remove or document it rather than carrying unrelated kanban wiring into the release.
- **TASK-Olr-015.MI6:** Record and test against the shared immutable post-Task-7 Skein baseline commit. This peer worktree owns its contract docs, README, docstrings, and generated API files.

## TASK-Olr-015.P3 Done when

- **TASK-Olr-015.DW1:** Tests prove route replacement during an in-progress cycle affects the next transition, route deletion fails loudly, and existing graph history remains unchanged.
- **TASK-Olr-015.DW2:** `clojure -M:test ct.spools.devflow-test` and the repository full test alias pass against the recorded Skein baseline, including lifecycle, routing, guidance, specs, and projections.
- **TASK-Olr-015.DW3:** Repository format/lint/docs/API gates pass; no v3 tag is created.

## TASK-Olr-015.P4 Out of scope

- **TASK-Olr-015.OS1:** Do not change stage vocabulary, workflow forms, strand attributes, task-loop policy, or publish v3.

## TASK-Olr-015.P5 References

- **TASK-Olr-015.REF1:** `src/ct/spools/devflow.clj`, `devflow.md`, Opus notes `a22m2` F1/F10, and `PLAN-Olr-001.AA10`.
