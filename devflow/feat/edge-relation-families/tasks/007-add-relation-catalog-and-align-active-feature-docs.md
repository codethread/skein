# Task 7: Add relation catalog and align active feature docs

**Document ID:** `ERF-TASK-007`

## ERF-TASK-007.P1 Scope

Type: AFK

Ship the source-visible annotation relation catalog and align active devflow feature artifacts with the new state/relation model, excluding `batch-graph-upsert` while it is landing independently.

## ERF-TASK-007.P2 Must implement exactly

- **ERF-TASK-007.MI1:** Add `src/skein/relations/alpha.clj` with data-first catalog entries for annotation conventions: `related-to`, `duplicates`, `references`, `implements`, `verifies`, `tracks`, and `caused-by`.
- **ERF-TASK-007.MI2:** Include direction/help metadata in the catalog. Do not make the catalog a storage allowlist and do not add engine behavior for annotation names.
- **ERF-TASK-007.MI3:** Include operational relation metadata for `depends-on`, `parent-of`, and `supersedes` only as documentation/source-visible data that matches the actual shipped batteries.
- **ERF-TASK-007.MI4:** Add tests for catalog shape and lookup helpers without testing private implementation details.
- **ERF-TASK-007.MI5:** Do not edit `devflow/feat/batch-graph-upsert` from this task; Task 9 owns adapting batch graph upsert after that feature lands.
- **ERF-TASK-007.MI6:** Update any other active feature artifacts under `devflow/feat/` that reference removed lifecycle schema or old edge-type contracts.

## ERF-TASK-007.P3 Done when

- **ERF-TASK-007.DW1:** Focused tests for `skein.relations.alpha` pass.
- **ERF-TASK-007.DW2:** `rg ':active|--active|inactive_at|allowed edge type|allowed edge types|final graph acyclicity|state "replaced"|:state "replaced"' devflow/feat --glob '!batch-graph-upsert/**'` shows no stale active-feature implementation contracts outside the deferred batch graph upsert handoff.
- **ERF-TASK-007.DW3:** Catalog docs/data clearly mark annotation conventions as behavior-free.

## ERF-TASK-007.P4 Out of scope

- **ERF-TASK-007.OS1:** Public root docs/spec promotion.
- **ERF-TASK-007.OS2:** Adding workflow commands for catalog annotation names.
- **ERF-TASK-007.OS3:** Public/root documentation sweep; Task 8 owns repo-wide docs cleanup.

## ERF-TASK-007.P5 References

- **ERF-TASK-007.REF1:** `devflow/feat/edge-relation-families/specs/repl-api.delta.md`
- **ERF-TASK-007.REF2:** `devflow/feat/edge-relation-families/specs/daemon-runtime.delta.md`
- **ERF-TASK-007.REF3:** `devflow/feat/edge-relation-families/tasks/009-adapt-batch-graph-upsert.md`
