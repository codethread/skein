# Weaver Runtime Delta: Op help convention

**Document ID:** `SPEC-004-D005`
**Status:** Merged
**Base Spec:** [Weaver Runtime](../../../specs/daemon-runtime.md)
**Feature:** [../proposal.md](../proposal.md)
**Last Updated:** 2026-07-05

## SPEC-004-D005.P1 Changed contracts

- **SPEC-004-D005.C1:** Op invocation (SPEC-004.C63b/C63d) accretes a help alias: when the resolved op's `:arg-spec` declares `:subcommands` and the invocation carries **exactly one argv token** equal to `help`, `-h`, or `--help` **and no payloads**, the invocation returns the op's help detail projection (identical to `help <op>`, SPEC-004.C63c) as a normal successful single result; the handler is not called. Any other argv shape — extra tokens, payloads attached, or the token in a non-sole position — is not an alias and flows through normal parsing and its loud errors. Ops without `:subcommands` (flat arg-spec, raw-envelope) never trigger the alias.
- **SPEC-004-D005.C1a:** The alias is resolved **before lifecycle hook gating**: a help-alias invocation is a read-class registry projection, so the target op's mutating-class hooks do not fire for it — equivalent to `help <op>` in hook behavior, not just payload shape.
- **SPEC-004-D005.C2:** The alias is dispatch behavior, not registry state: it applies uniformly to every `:subcommands` op with no per-op opt-out, which is safe because `help`/`-h`/`--help` are reserved subcommand names (SPEC-003-D005.C1).

## SPEC-004-D005.P2 Unchanged contracts

- **SPEC-004-D005.U1:** Missing-subcommand and unknown-subcommand invocations remain loud parse-phase domain errors (SPEC-004.C63d); bare `<op>` never returns exit-0 help.
- **SPEC-004-D005.U2:** The built-in `help` op, registry metadata, provenance, and reload semantics are unchanged.
