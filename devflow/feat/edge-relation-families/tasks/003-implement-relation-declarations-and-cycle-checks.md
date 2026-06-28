# Task 3: Implement relation declarations and cycle checks

**Document ID:** `ERF-TASK-003`

## ERF-TASK-003.P1 Scope

Type: AFK

Replace closed edge-type validation and global DAG checks with open relation names plus durable acyclic relation declarations.

## ERF-TASK-003.P2 Must implement exactly

- **ERF-TASK-003.MI1:** Add storage support in `src/skein/db.clj` for durable acyclic relation declarations and bootstrap `depends-on`, `parent-of`, and `supersedes` during `init!`.
- **ERF-TASK-003.MI2:** Replace `specs/allowed-edge-types` with a relation-name grammar matching `[a-z0-9][a-z0-9._/-]*`.
- **ERF-TASK-003.MI3:** Drop the `strand_edges.edge_type` closed `CHECK` from the schema. Keep JSON and FK constraints.
- **ERF-TASK-003.MI4:** Rescope `path-exists?` / `require-acyclic-edge!` so cycle checks only walk the relation being written and only run when that relation is declared acyclic.
- **ERF-TASK-003.MI5:** Keep universal self-edge rejection for all relations.
- **ERF-TASK-003.MI6:** Add storage/weaver helpers for declaring and listing acyclic relations. Re-declaration of the same relation is idempotent; declaring a relation after edges of that type exist fails loudly unless it is a shipped bootstrap declaration being validated during initialization.
- **ERF-TASK-003.MI7:** Add/adjust tests proving custom annotation relation names store, annotation cycles are allowed, declared relation cycles fail, and shipped operational relations are declared.

## ERF-TASK-003.P3 Done when

- **ERF-TASK-003.DW1:** Focused Clojure DB/weaver tests for relation validation and cycle checks pass.
- **ERF-TASK-003.DW2:** `related-to` and arbitrary valid names are not hardcoded allowlist requirements.
- **ERF-TASK-003.DW3:** `depends-on`, `parent-of`, and `supersedes` are visibly declared acyclic in initialized storage.

## ERF-TASK-003.P4 Out of scope

- **ERF-TASK-003.OS1:** Edge query predicates and traversal helper options.
- **ERF-TASK-003.OS2:** Supersession transaction behavior beyond declaring `supersedes` acyclic.
- **ERF-TASK-003.OS3:** Relation catalog namespace.

## ERF-TASK-003.P5 References

- **ERF-TASK-003.REF1:** `devflow/feat/edge-relation-families/specs/strand-model.delta.md`
- **ERF-TASK-003.REF2:** `devflow/feat/edge-relation-families/specs/daemon-runtime.delta.md`
- **ERF-TASK-003.REF3:** `src/skein/db.clj`
