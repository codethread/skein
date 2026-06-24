# REPL API Delta: Daemon Runtime

**Document ID:** `SPEC-003-D001`
**Status:** Planned
**Last Updated:** 2026-06-24
**Root spec:** [REPL API](../../../specs/repl-api.md)
**Feature spec:** [Daemon Runtime](./daemon-runtime.md)

## SPEC-003-D001.P1 Changed purpose

- **SPEC-003-D001.C1:** The REPL API remains the compact interactive Clojure interface over the task surface, but helpers call the daemon runtime instead of an in-process datasource.

## SPEC-003-D001.P2 Interface changes

- **SPEC-003-D001.C2:** `open!` remains the blessed helper and selects a daemon runtime by database path, using the same single-argument shape `(open! db-file)` as today.
- **SPEC-003-D001.C3:** `open!` connects to an existing daemon for `db-file` and fails loudly when no matching daemon is reachable; it does not start a daemon implicitly.
- **SPEC-003-D001.C4:** A helper that needs a daemon connection fails before use when no daemon is selected or reachable.
- **SPEC-003-D001.C5:** `init!`, `task!`, `update!`, `task`, `tasks`, and `ready` keep their task-level behavior and normalized return values.

## SPEC-003-D001.P3 Runtime behavior

- **SPEC-003-D001.C6:** REPL helpers do not silently create local datasources as a fallback when daemon connection fails.
- **SPEC-003-D001.C7:** REPL helper errors preserve daemon, transport, and domain failure messages as Clojure exceptions.
- **SPEC-003-D001.C8:** Advanced users may connect directly to the daemon nREPL for trusted interactive work, but the blessed helper path calls `todo.daemon.api` operations rather than arbitrary internals.
