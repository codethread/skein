# Task 1: Implement core batch mutation

**Document ID:** `BGU-TASK-001`

## BGU-TASK-001.P1 Scope

Type: AFK

Implement the storage-level transactional batch graph mutation primitive in `src/skein/db.clj` according to `BGU-DELTA-001`.

## BGU-TASK-001.P2 Must implement exactly

- **BGU-TASK-001.MI1:** Add a public `skein.db` function, preferably `apply-batch!`, that accepts a payload with optional top-level `:refs`, `:strands`, `:edges`, and `:burn` keys.
- **BGU-TASK-001.MI2:** Validate payload shape fail-loudly at the boundary. Unknown top-level keys, malformed sections, non-keyword refs, namespaced refs, blank refs, duplicate ref declarations, duplicate existing id aliases, missing bound ids, and unknown refs outside create entries must throw `ex-info` with useful data.
- **BGU-TASK-001.MI3:** Classify strand entries by `:ref`: bound refs update existing strands; unbound refs create new strands and extend the final ref table.
- **BGU-TASK-001.MI4:** Enforce create/update rules: create requires non-blank `:title`; update supports only `:title`, `:active`, and `:attributes`; removed lifecycle fields remain invalid through existing validation.
- **BGU-TASK-001.MI5:** Enforce burn rules: burn refs must be existing bound refs, may not be newly created, may not also appear in `:strands`, and may not be referenced by edge operations in the same payload.
- **BGU-TASK-001.MI6:** Implement edge op validation for v1 `{:op :upsert ...}` only. Validate `:from`, `:to`, allowed edge type, optional JSON object attributes, self-edge rejection, and final graph acyclicity through existing edge insertion checks.
- **BGU-TASK-001.MI7:** Ensure edge upsert inserts missing edges and replaces attributes on existing matching `(from, to, type)` edges.
- **BGU-TASK-001.MI8:** Apply all creates, updates, edge upserts, and burns inside one transaction. Any validation or persistence failure must leave no partial mutation committed.
- **BGU-TASK-001.MI9:** Return data shaped for weaver normalization: final `:refs`, `:created`, `:updated` before/after entries, `:burned` ids with pre-delete rows, and `:edges` outcomes.
- **BGU-TASK-001.MI10:** Do not change the existing `add-strand-batch!` public behavior used by weave patterns.

## BGU-TASK-001.P3 Done when

- **BGU-TASK-001.DW1:** A manual REPL call against an initialized datasource can create, update, burn, and edge-upsert in one payload.
- **BGU-TASK-001.DW2:** A deliberately invalid payload throws before mutation or rolls back completely.
- **BGU-TASK-001.DW3:** Existing single-strand add/update/burn and create-only batch functions still compile.

## BGU-TASK-001.P4 Out of scope

- **BGU-TASK-001.OS1:** Weaver API, events, REPL helper namespace, CLI commands, and smoke tests.
- **BGU-TASK-001.OS2:** Edge delete/replace operations.
- **BGU-TASK-001.OS3:** Migrating pattern/weave internals to the new primitive.

## BGU-TASK-001.P5 References

- **BGU-TASK-001.REF1:** `devflow/feat/batch-graph-upsert/specs/strand-model.delta.md`
- **BGU-TASK-001.REF2:** `src/skein/db.clj`
- **BGU-TASK-001.REF3:** `test/skein/db_test.clj`
