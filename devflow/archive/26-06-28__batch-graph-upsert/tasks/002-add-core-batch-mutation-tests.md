# Task 2: Add core batch mutation tests

**Document ID:** `BGU-TASK-002`

## BGU-TASK-002.P1 Scope

Type: AFK

Add focused storage-level tests for the `skein.db/apply-batch!` contract implemented in Task 1.

## BGU-TASK-002.P2 Must implement exactly

- **BGU-TASK-002.MI1:** Add or extend tests in `test/skein/db_test.clj` for a happy path that binds existing refs, updates one strand, creates at least one strand, upserts an edge by refs, burns one existing strand, and verifies the returned final refs and persisted final graph.
- **BGU-TASK-002.MI2:** Test boundary shape validation: unknown top-level keys, malformed section types, missing bound ids, and blank refs fail loudly and do not mutate.
- **BGU-TASK-002.MI3:** Test one-ref-one-strand validation: duplicate existing id aliases fail loudly and do not mutate.
- **BGU-TASK-002.MI4:** Test ref validation: non-keyword refs, namespaced refs, and unknown refs in edge/burn sections fail loudly.
- **BGU-TASK-002.MI5:** Test create/update/burn conflict validation: create without title, duplicate strand entries for one ref, burn of new ref, and update+burn of the same existing ref fail loudly.
- **BGU-TASK-002.MI6:** Test edge behavior: unsupported edge op fails, edge touching burned ref fails, cycle creation fails, and existing edge attributes are replaced on upsert.
- **BGU-TASK-002.MI7:** Test transaction rollback by submitting a payload that performs an early valid mutation and a later invalid edge/cycle, then verifying no earlier change committed.
- **BGU-TASK-002.MI8:** Keep tests isolated with temporary datasources and explicit `db/init!`, following existing test patterns.

## BGU-TASK-002.P3 Done when

- **BGU-TASK-002.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes or any unrelated failure is clearly documented.
- **BGU-TASK-002.DW2:** Core batch tests prove both successful persistence and rollback/fail-loud behavior.

## BGU-TASK-002.P4 Out of scope

- **BGU-TASK-002.OS1:** Weaver API/event tests.
- **BGU-TASK-002.OS2:** REPL helper tests.
- **BGU-TASK-002.OS3:** Smoke workflow changes.

## BGU-TASK-002.P5 References

- **BGU-TASK-002.REF1:** `devflow/feat/batch-graph-upsert/specs/strand-model.delta.md`
- **BGU-TASK-002.REF2:** `src/skein/db.clj`
- **BGU-TASK-002.REF3:** `test/skein/db_test.clj`
