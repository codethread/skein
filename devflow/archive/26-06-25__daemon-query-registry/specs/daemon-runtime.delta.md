# Daemon Runtime Delta: In-memory Query Registry

**Document ID:** `SPEC-004-D001` **Status:** Merged **Base Spec:** [Daemon Runtime](../../../specs/daemon-runtime.md) **Last Updated:** 2026-06-25

## SPEC-004-D001.P1 Changed contracts

- **SPEC-004-D001.C1:** A daemon owns an in-memory query registry for its lifetime in addition to its active SQLite datasource.
- **SPEC-004-D001.C2:** The daemon API includes operations to register one query definition, load a map of query definitions, list registered query definitions, resolve a named query, and execute `list`/`ready` using a named query.
- **SPEC-004-D001.C3:** Query registry contents are not durable across daemon restarts.
- **SPEC-004-D001.C4:** Query definitions are data-first EDN DSL definitions, not executable user functions.
- **SPEC-004-D001.C5:** Missing named query resolution fails loudly with a clear domain error.
- **SPEC-004-D001.C6:** The registry accepts simple symbol or keyword names from clients and resolves symbol/keyword variants of the same unqualified name to one entry.

## SPEC-004-D001.P2 Unchanged contracts

- **SPEC-004-D001.U1:** The daemon remains local-only and metadata/identity rules are unchanged.
- **SPEC-004-D001.U2:** CLI user input remains data arguments to fixed daemon API forms, not interpolated executable code.
- **SPEC-004-D001.U3:** Trusted startup config remains the only startup code-loading mechanism; this feature does not add untrusted plugin execution.
