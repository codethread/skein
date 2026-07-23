# Task 3: Add loaded namespace ledger and resolve uxc5f

**Document ID:** `TASK-Olr-003`

## TASK-Olr-003.P1 Scope

Type: AFK

Implement append-only in-generation namespace load evidence and source residual classification. Own `src/skein/core/weaver/spool_sync.clj`, the corresponding access/runtime state slots and shapes, and focused cases in `test/skein/spools_test.clj`. Card `uxc5f` is part of this task's acceptance.

## TASK-Olr-003.P2 Must implement exactly

- **TASK-Olr-003.MI1:** Record every successful namespace source load with root lib, stable module owner when known, namespace, canonical file, exact loaded-byte SHA-256, and monotonic load order. Represent base-classpath Skein/batteries ownership separately.
- **TASK-Olr-003.MI2:** Preserve prior records after reload. The latest successful load identifies the current binding; earlier records remain retained-code evidence.
- **TASK-Olr-003.MI3:** Compare the ledger with current approved source discovery to classify current provisions, orphaned namespaces, deleted files, shrunk paths, namespace renames, cross-root transfer attempts, duplicate providers, and hard conflicts.
- **TASK-Olr-003.MI4:** A transfer becomes current only after successful explicit load from the new root. A failed/unreadable/unclassified root cannot clear a prior residual or pending state.
- **TASK-Olr-003.MI5:** Replace the `uxc5f` current-source-only clean-pass logic. A zero per-root failure result is insufficient unless every loaded namespace has been classified.
- **TASK-Olr-003.MI6:** Preserve exact-byte hashing and conservative file-race behavior of current `reload-spool!`.

## TASK-Olr-003.P3 Done when

- **TASK-Olr-003.DW1:** Regression tests reproduce both `deps.edn :paths` shrinkage and loaded-file deletion and prove neither reports clean nor clears pending/residual state.
- **TASK-Olr-003.DW2:** Tests cover changed bytes, rename, transfer, duplicate provider, failed root, partial reload, classpath ownership, and latest-binding attribution.
- **TASK-Olr-003.DW3:** The ledger/status shapes pass specs and remain runtime-only; no SQLite schema or durable migration is added.
- **TASK-Olr-003.DW4:** `clojure -M:test skein.spools-test` passes cold. Add a durable note to card `uxc5f` with the exact regression test names; do not close that card yet.

## TASK-Olr-003.P4 Out of scope

- **TASK-Olr-003.OS1:** Do not claim JVM unload, remove Vars, or implement module contribution replacement.

## TASK-Olr-003.P5 References

- **TASK-Olr-003.REF1:** Card `uxc5f`; `DELTA-OlrDrt-001.CC12–CC14`; `PLAN-Olr-001.V2`.
