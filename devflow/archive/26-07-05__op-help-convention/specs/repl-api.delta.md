# REPL API Delta: Op help convention

**Document ID:** `SPEC-003-D005` **Status:** Merged **Base Spec:** [REPL API](../../../specs/repl-api.md) **Feature:** [../proposal.md](../proposal.md) **Last Updated:** 2026-07-05

## SPEC-003-D005.P1 Changed contracts

- **SPEC-003-D005.C1:** `help`, `-h`, and `--help` are reserved subcommand names for `:subcommands` arg-specs: the shared structural validator (SPEC-003.C64) rejects declaring any of them, loudly, at the same seams (parse, explain, registration).
- **SPEC-003-D005.C2:** The parser is unchanged beyond C1 — help-token routing is an invocation-dispatch concern (SPEC-004-D005), not a parse result. `parse` on argv `["help"]` for a subcommand op is never reached in dispatch; called directly it still fails with unknown-subcommand as today.

## SPEC-003-D005.P2 Unchanged contracts

- **SPEC-003-D005.U1:** Flat arg-specs and raw-envelope ops: no reserved names, no new validation, no behavior change.
