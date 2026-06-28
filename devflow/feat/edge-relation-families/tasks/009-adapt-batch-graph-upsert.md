# Task 9: Adapt batch graph upsert to state and relation families

**Document ID:** `ERF-TASK-009`

## ERF-TASK-009.P1 Scope

Type: AFK

After the batch graph upsert feature has landed, update its shipped code, tests, smoke coverage, and current non-archived docs/spec promotion points to the edge-relation-family model. This task carries the compatibility intent that was identified while both features were active, without editing the batch feature artifacts out from under its owner before merge.

## ERF-TASK-009.P2 Must implement exactly

- **ERF-TASK-009.MI1:** Update `skein.db/apply-batch!` and related validation so generic batch strand create/update entries use `:state` instead of `:active`, accepting only `"active"` and `"closed"` through generic batch payloads.
- **ERF-TASK-009.MI2:** Ensure generic batch payloads reject manual `state="replaced"`; replacement must be performed through the core supersession transaction, not by combining a state patch with a raw `supersedes` edge.
- **ERF-TASK-009.MI3:** Update batch edge upsert validation from the old closed edge-type allowlist and whole-graph acyclicity model to valid relation names plus declaration-scoped acyclicity for declared acyclic relations.
- **ERF-TASK-009.MI4:** Preserve batch graph upsert's existing guarantees: local refs, atomic create/update/burn/edge-upsert application, edge attribute replacement on matching edges, burn constraints, normalized results, `:batch/applied`, deterministic compatibility fanout, helper routing, and no public CLI batch command.
- **ERF-TASK-009.MI5:** Update batch graph upsert tests and smoke assertions that mention `:active`, old `active` JSON output, allowed edge types, or final graph acyclicity so they prove the new state/relation behavior instead.
- **ERF-TASK-009.MI6:** Update any current, non-archived root specs or docs that describe the shipped batch graph upsert contract so they say `:state`, valid relation names, and declaration-scoped acyclicity. Do not edit `devflow/archive` historical feature artifacts.
- **ERF-TASK-009.MI7:** Use a non-supersession annotation relation such as `references` in examples when demonstrating ordinary batch edge upsert that links an old strand to a new one. Use the core supersession helper/operation for replacement examples.

## ERF-TASK-009.P3 Done when

- **ERF-TASK-009.DW1:** Focused Clojure tests covering batch graph upsert pass under the state/relation-family model.
- **ERF-TASK-009.DW2:** Smoke coverage still exercises `skein.batch.alpha/apply!` and no longer uses the removed lifecycle schema.
- **ERF-TASK-009.DW3:** Grep checks over current code/docs, excluding `devflow/archive`, show no stale batch graph upsert contract references to `:active`, old JSON `active`, allowed edge-type allowlists, or whole-graph acyclicity.

## ERF-TASK-009.P4 Out of scope

- **ERF-TASK-009.OS1:** Public CLI batch mutation commands.
- **ERF-TASK-009.OS2:** Public edge delete/replace operations.
- **ERF-TASK-009.OS3:** Live migration or compatibility aliases for old batch payloads.
- **ERF-TASK-009.OS4:** Editing archived batch graph upsert planning history.

## ERF-TASK-009.P5 References

- **ERF-TASK-009.REF1:** `devflow/feat/edge-relation-families/specs/strand-model.delta.md`
- **ERF-TASK-009.REF2:** `devflow/feat/edge-relation-families/specs/repl-api.delta.md`
- **ERF-TASK-009.REF3:** `devflow/feat/edge-relation-families/specs/daemon-runtime.delta.md`
- **ERF-TASK-009.REF4:** Batch graph upsert implementation and tests after that feature lands.
