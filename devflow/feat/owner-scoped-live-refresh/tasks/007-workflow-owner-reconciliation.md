# Task 7: Convert workflow and shell executor

**Document ID:** `TASK-Olr-007`

## TASK-Olr-007.P1 Scope

Type: AFK

Convert `skein.spools.workflow` constructor/executor registries and the shell executor to stable owner-complete declaration replacement while preserving versioned workflow state and live route semantics.

## TASK-Olr-007.P2 Must implement exactly

- **TASK-Olr-007.MI1:** Separate workflow constructor symbols from executor function values in the owner model. Constructor lookup resolves the current symbol at each named transition; executor evaluation snapshots the current function value for one gate evaluation.
- **TASK-Olr-007.MI2:** Add complete-owner replace/remove APIs for workflow definitions and executors, including default/system owners and explicit override/collision behavior.
- **TASK-Olr-007.MI3:** Keep live workflow run graph and registry/resource atoms in versioned spool-state. Refreshing declarations must not replace state atom identities or alter poured strands.
- **TASK-Olr-007.MI4:** Convert workflow and shell executor module entry points to contribution/reconcile. Preserve shell initial scan, subprocess tracking, gate errors, and event integration without duplicate scans or stale executor entries.
- **TASK-Olr-007.MI5:** Remove deleted workflow/executor declarations on owner refresh and expose active/shadowed owner data through domain introspection/status.

## TASK-Olr-007.P3 Done when

- **TASK-Olr-007.DW1:** Tests prove a run already between stages resolves a replaced constructor on its next named transition, while an executor call in progress completes with its snapshot.
- **TASK-Olr-007.DW2:** Constructor/executor omission, override restoration, shell refresh, preserved state identity, and no duplicate initial scan are covered.
- **TASK-Olr-007.DW3:** `clojure -M:test skein.spools.workflow-test skein.spools.executors.shell-test skein.config-test` and `make fmt-check lint reflect-check` pass, including spool-state shape assertions.

## TASK-Olr-007.P4 Out of scope

- **TASK-Olr-007.OS1:** Do not change workflow vocabulary, molecule persistence, gate routing, or agent-harness subagent executor.

## TASK-Olr-007.P5 References

- **TASK-Olr-007.REF1:** `DELTA-OlrDrt-001.CC8/CC10`, `PLAN-Olr-001.V5`, terra-med note `vovp1` finding 3.
