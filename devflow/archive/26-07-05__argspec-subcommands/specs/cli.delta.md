# CLI Surface Delta: Arg-spec subcommands

**Document ID:** `SPEC-002-D005` **Status:** Merged **Base Spec:** [CLI Surface](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Last Updated:** 2026-07-05

## SPEC-002-D005.P1 Changed contracts

- **SPEC-002-D005.C1:** `strand help <op>` (SPEC-002.C39) accretes: when the op's arg-spec declares subcommands, the rendered detail lists them — each subcommand's name, doc, flags, and positionals — one level deep, with no extra flags required.

## SPEC-002-D005.P2 Unchanged contracts

- **SPEC-002-D005.U1:** The dispatcher contract is untouched: `strand` still ships verbatim argv after the op name (SPEC-002.C30) and parses no per-op flags. Subcommand routing is entirely weaver-side parser behavior.
