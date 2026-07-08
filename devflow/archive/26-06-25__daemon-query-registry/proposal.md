# Daemon Query Registry Proposal

**Document ID:** `PROP-001` **Status:** Draft **Last Updated:** 2026-06-25 **Related RFCs:** [RFC-002 Task Query DSL](../../rfcs/2026-06-24-task-query-dsl.md) **Related Specs:** [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Daemon Runtime](../../specs/daemon-runtime.md) **Philosophy:** [Devflow Philosophy](../../PHILOSOPHY.md)

## PROP-001.P1 Problem

Named queries currently live in per-client state: the CLI resolves names by rereading `--query-file`, and the REPL registers names only in its own process. That splits runtime behavior across clients. In the intended daemon-core model, users load runtime behavior through trusted config or the REPL, then the CLI can consume known daemon state for common low-privilege operations.

## PROP-001.P2 Goals

- **PROP-001.G1:** Let REPL users add/load EDN query definitions into the daemon's in-memory query registry.
- **PROP-001.G2:** Let CLI users use a named query already present in daemon memory without re-supplying a query file.
- **PROP-001.G3:** Let REPL users use the same daemon-memory query names.
- **PROP-001.G4:** Fail loudly with a clear message when a requested named query does not exist.
- **PROP-001.G5:** Keep the feature small and data-first; avoid adding a durable saved-query schema unless explicitly requested later.

## PROP-001.P3 Non-goals

- **PROP-001.NG1:** Durable saved-query persistence across daemon restarts is out of scope.
- **PROP-001.NG2:** User-defined executable query functions are out of scope.
- **PROP-001.NG3:** Fuzzy suggestions for similar query names are optional only if they require very little maintainable code.

## PROP-001.P4 Proposed scope

- **PROP-001.S1:** Add a daemon-owned in-memory query registry keyed by simple query names. CLI input, symbols, and keywords referring to the same unqualified name resolve to the same registry entry.
- **PROP-001.S2:** Add daemon API operations to register, load, list, resolve, and run named query definitions.
- **PROP-001.S3:** Do not add CLI query registry mutation commands in the MVP; query registration/loading is REPL-only.
- **PROP-001.S4:** Extend CLI `list`/`ready` so `--query name` resolves from daemon memory.
- **PROP-001.S5:** Remove CLI `--query-file`; query loading is handled by trusted daemon config or REPL helpers, not the CLI.
- **PROP-001.S6:** Extend REPL helpers so `defquery!`, `load-queries!`, and `queries` operate against daemon memory rather than only local client memory.
- **PROP-001.S7:** Add tests proving a query registered or loaded through REPL is reusable from CLI through the daemon.

## PROP-001.P5 Open questions

- **PROP-001.Q1:** None for MVP. Fuzzy missing-name suggestions remain optional polish only.
