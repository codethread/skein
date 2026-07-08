# CLI Surface Delta: Op help convention

**Document ID:** `SPEC-002-D006` **Status:** Merged **Base Spec:** [CLI Surface](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Last Updated:** 2026-07-05

## SPEC-002-D006.P1 Changed contracts

- **SPEC-002-D006.C1:** Help discovery (SPEC-002.C39) accretes the invocation alias: for ops whose arg-spec declares subcommands, `strand <op> help|-h|--help` (sole token, no payloads) returns the same detail as `strand help <op>`, exit 0. The dispatcher remains uninvolved: the alias is weaver dispatch behavior and argv still ships verbatim (SPEC-002.C30).
- **SPEC-002-D006.C2:** All CLI JSON output is pinned byte-faithful: single-result stdout rendering, stream-relay lines, and the `details=` JSON appended to weaver error messages on stderr are emitted without HTML escaping (`<`, `>`, `&` print literally, never `<`). Both known escaping sites move to non-escaping encoders: result marshalling in `cli/internal/client/invoke.go` and detail marshalling in `cli/internal/client/client.go`.

## SPEC-002-D006.P2 Unchanged contracts

- **SPEC-002-D006.U1:** Bin-only `--help` (dispatcher usage, SPEC-002.C34) is untouched: `strand --help` documents the dispatcher; `strand <op> --help` reaches the weaver like any argv.
