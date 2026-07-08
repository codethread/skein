# REPL API Delta: Daemon Query Registry

**Document ID:** `SPEC-003-D002` **Status:** Merged **Base Spec:** [REPL API](../../../specs/repl-api.md) **Last Updated:** 2026-06-25

## SPEC-003-D002.P1 Changed contracts

- **SPEC-003-D002.C1:** `defquery!` registers a named query expression or parameterized query map in the active daemon's in-memory query registry.
- **SPEC-003-D002.C2:** `load-queries!` reads one EDN map of query names to query definitions and merges it into the active daemon's in-memory query registry.
- **SPEC-003-D002.C3:** `queries` returns the active daemon's in-memory query registry.
- **SPEC-003-D002.C4:** `query`, `tasks`, and `ready` resolve symbol or keyword query names against daemon memory, so names loaded by CLI are reusable from REPL and names loaded by REPL are reusable from CLI.
- **SPEC-003-D002.C5:** A missing named query throws a clear Clojure exception naming the missing query and, when cheap, available query names.
- **SPEC-003-D002.C6:** Query names accepted by REPL registry helpers are simple symbols or keywords. Symbol and keyword forms of the same unqualified name resolve to the same daemon registry entry.

## SPEC-003-D002.P2 Unchanged contracts

- **SPEC-003-D002.U1:** Ad hoc vector/map query definitions remain accepted directly by `query`, `tasks`, and `ready`.
- **SPEC-003-D002.U2:** Helpers still fail before `open!` selects an active daemon-backed database connection.
