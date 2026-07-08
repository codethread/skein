# CLI Surface Delta: Daemon Query Registry

**Document ID:** `SPEC-002-D002` **Status:** Merged **Base Spec:** [CLI Surface](../../../specs/cli.md) **Last Updated:** 2026-06-25

## SPEC-002-D002.P1 Changed contracts

- **SPEC-002-D002.C1:** `list --query name` and `ready --query name` resolve `name` against the daemon's in-memory query registry.
- **SPEC-002-D002.C2:** Remove CLI `--query-file`; query loading is a daemon config or REPL workflow, not a CLI workflow.
- **SPEC-002-D002.C3:** A missing named query fails non-zero with a clear message naming the missing query and, when cheap, available query names.
- **SPEC-002-D002.C4:** Query names accepted by the CLI are simple unqualified names. Symbol and keyword forms of the same unqualified name resolve to the same daemon registry entry.
- **SPEC-002-D002.C5:** The CLI does not expose query registry mutation or listing commands in this MVP; use REPL `defquery!`, `load-queries!`, and `queries` for registry management.

## SPEC-002-D002.P2 Unchanged contracts

- **SPEC-002-D002.U1:** `--where` remains the ad hoc query path and is still mutually exclusive with `--query`.
- **SPEC-002-D002.U2:** Runtime `--param key=value` values remain string-valued CLI parameters.
- **SPEC-002-D002.U3:** Task commands still require a matching reachable daemon and do not silently open SQLite directly.
