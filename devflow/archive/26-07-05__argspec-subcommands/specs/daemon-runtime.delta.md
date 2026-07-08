# Weaver Runtime Delta: Arg-spec subcommands

**Document ID:** `SPEC-004-D004` **Status:** Merged **Base Spec:** [Weaver Runtime](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Last Updated:** 2026-07-05

## SPEC-004-D004.P1 Changed contracts

- **SPEC-004-D004.C1:** Op invocation (SPEC-004.C63b) accretes: when a registered `:arg-spec` declares `:subcommands`, the blessed parser routes the first argv token to the matching nested spec before the handler runs; the handler receives the merged parsed map (nested args plus `:subcommand`) as `:op/args`. Missing/unknown subcommand tokens are loud parse-phase domain errors carrying available subcommand names; the handler is not called.
- **SPEC-004-D004.C2:** The built-in `help` op detail projection (SPEC-004.C63c) accretes: when an op's arg-spec declares `:subcommands`, the detail includes the subcommand rendering (name, doc, per-subcommand flags/positionals) from the parser `explain` projection.
- **SPEC-004-D004.C3:** Arg-spec structural validation for `:subcommands` (one level only; no top-level flags/positionals alongside; reserved `subcommand` arg name) fails loudly at `register-op!`/`replace-op!` time, not first parse.

## SPEC-004-D004.P2 Unchanged contracts

- **SPEC-004-D004.U1:** Registry entry metadata, provenance recording, collision behavior, and reload semantics (SPEC-004.C63a, C63c reload clauses) are unchanged.
